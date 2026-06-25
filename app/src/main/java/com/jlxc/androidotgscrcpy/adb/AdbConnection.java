package com.jlxc.androidotgscrcpy.adb;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class AdbConnection {
    public interface LogSink { void log(String message); }

    private final UsbAdbDevice usb;
    private final AdbCrypto crypto;
    private final LogSink logger;
    private final AtomicInteger nextLocalId = new AtomicInteger(1);
    private final Map<Integer, AdbStream> streams = new ConcurrentHashMap<Integer, AdbStream>();
    private volatile boolean connected;
    private volatile boolean running;
    private Thread readerThread;

    public AdbConnection(UsbAdbDevice usb, AdbCrypto crypto, LogSink logger) {
        this.usb = usb;
        this.crypto = crypto;
        this.logger = logger;
    }

    public void connect() throws Exception {
        log("Sending CNXN...");
        sendRaw(AdbProtocol.makeMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, AdbProtocol.stringPayload("host::")));
        boolean sentPublicKey = false;
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            AdbProtocol.Message msg = readMessage(5000);
            if (msg == null) continue;
            if (msg.command == AdbProtocol.A_CNXN) {
                connected = true;
                running = true;
                log("ADB connected: " + payloadText(msg.payload));
                startReaderThread();
                return;
            }
            if (msg.command == AdbProtocol.A_AUTH && msg.arg0 == AdbProtocol.AUTH_TOKEN) {
                log("ADB AUTH token received.");
                byte[] signature = crypto.signAdbToken(msg.payload);
                sendRaw(AdbProtocol.makeMessage(AdbProtocol.A_AUTH, AdbProtocol.AUTH_SIGNATURE, 0, signature));
                AdbProtocol.Message reply = readMessage(5000);
                if (reply != null && reply.command == AdbProtocol.A_CNXN) {
                    connected = true;
                    running = true;
                    log("ADB connected after signature: " + payloadText(reply.payload));
                    startReaderThread();
                    return;
                }
                if (!sentPublicKey) {
                    sentPublicKey = true;
                    log("Signature not accepted yet. Sending public key. Confirm the USB debugging dialog on target phone.");
                    sendRaw(AdbProtocol.makeMessage(AdbProtocol.A_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, crypto.getAdbPublicKeyPayload()));
                }
            } else {
                log("Handshake msg: " + AdbProtocol.commandToString(msg.command));
            }
        }
        throw new AdbException("ADB connection timed out. If target shows an authorization dialog, tap Allow and reconnect.");
    }

    public boolean isConnected() { return connected; }

    public AdbStream openStream(String destination) throws Exception {
        if (!connected) throw new AdbException("ADB not connected");
        int localId = nextLocalId.getAndIncrement();
        AdbStream stream = new AdbStream(this, localId);
        streams.put(localId, stream);
        send(AdbProtocol.A_OPEN, localId, 0, AdbProtocol.stringPayload(destination));
        stream.waitUntilOpen(10000);
        return stream;
    }

    public String shell(String command, long maxWaitMs) throws Exception {
        AdbStream stream = openStream("shell:" + command);
        return stream.readAllAsString(500, maxWaitMs);
    }

    public String exec(String command, long maxWaitMs) throws Exception {
        AdbStream stream = openStream("exec:" + command);
        return stream.readAllAsString(500, maxWaitMs);
    }

    void send(int command, int arg0, int arg1, byte[] payload) throws AdbException {
        sendRaw(AdbProtocol.makeMessage(command, arg0, arg1, payload));
    }

    private synchronized void sendRaw(byte[] message) throws AdbException {
        usb.write(message, 5000);
    }

    private void startReaderThread() {
        readerThread = new Thread(new Runnable() {
            @Override public void run() { readerLoop(); }
        }, "adb-reader");
        readerThread.start();
    }

    private void readerLoop() {
        while (running) {
            try {
                AdbProtocol.Message msg = readMessage(0);
                if (msg == null) continue;
                dispatch(msg);
            } catch (Throwable t) {
                if (running) log("ADB reader stopped: " + t.getMessage());
                running = false;
                connected = false;
                for (AdbStream s : streams.values()) s.onClose();
                break;
            }
        }
    }

    private void dispatch(AdbProtocol.Message msg) throws AdbException {
        int localId = msg.arg1;
        int remoteId = msg.arg0;
        AdbStream stream = streams.get(localId);
        if (msg.command == AdbProtocol.A_OKAY) {
            if (stream != null) stream.onOkay(remoteId);
        } else if (msg.command == AdbProtocol.A_WRTE) {
            if (stream != null) {
                stream.onWrite(msg.payload);
                send(AdbProtocol.A_OKAY, stream.getLocalId(), stream.getRemoteId(), new byte[0]);
            }
        } else if (msg.command == AdbProtocol.A_CLSE) {
            if (stream != null) {
                stream.onClose();
                streams.remove(localId);
                send(AdbProtocol.A_CLSE, stream.getLocalId(), remoteId, new byte[0]);
            }
        } else if (msg.command == AdbProtocol.A_AUTH) {
            log("Unexpected AUTH while connected");
        } else {
            log("Unhandled ADB msg: " + AdbProtocol.commandToString(msg.command));
        }
    }

    private AdbProtocol.Message readMessage(int timeoutMs) throws AdbException {
        byte[] header = readExact(AdbProtocol.HEADER_LENGTH, timeoutMs);
        if (header == null) return null;
        AdbProtocol.Message h = AdbProtocol.parseHeader(header);
        byte[] payload = new byte[h.payloadLength];
        if (h.payloadLength > 0) {
            byte[] p = readExact(h.payloadLength, timeoutMs);
            if (p == null) throw new AdbException("ADB payload read timed out");
            payload = p;
            if (AdbProtocol.checksum(payload) != h.checksum) throw new AdbException("ADB payload checksum mismatch");
        }
        return h.withPayload(payload);
    }

    private byte[] readExact(int length, int timeoutMs) throws AdbException {
        byte[] out = new byte[length];
        int offset = 0;
        while (offset < length) {
            byte[] tmp = new byte[length - offset];
            int n = usb.read(tmp, tmp.length, timeoutMs);
            if (n == 0 && timeoutMs > 0) return null;
            if (n <= 0) continue;
            System.arraycopy(tmp, 0, out, offset, n);
            offset += n;
        }
        return out;
    }

    public void close() {
        running = false;
        connected = false;
        for (AdbStream s : streams.values()) s.onClose();
        streams.clear();
        usb.close();
    }

    private void log(String s) {
        if (logger != null) logger.log(s);
    }

    private String payloadText(byte[] data) {
        if (data == null) return "";
        int len = data.length;
        while (len > 0 && data[len - 1] == 0) len--;
        try { return new String(Arrays.copyOf(data, len), "UTF-8"); } catch (Exception e) { return ""; }
    }
}
