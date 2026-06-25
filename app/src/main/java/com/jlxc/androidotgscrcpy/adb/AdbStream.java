package com.jlxc.androidotgscrcpy.adb;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class AdbStream {
    private final AdbConnection connection;
    private final int localId;
    private volatile int remoteId;
    private volatile boolean opened;
    private volatile boolean closed;
    private final Object openLock = new Object();
    private final LinkedBlockingQueue<byte[]> readQueue = new LinkedBlockingQueue<byte[]>();

    AdbStream(AdbConnection connection, int localId) {
        this.connection = connection;
        this.localId = localId;
    }

    int getLocalId() { return localId; }
    int getRemoteId() { return remoteId; }
    public boolean isClosed() { return closed; }

    void onOkay(int remoteId) {
        synchronized (openLock) {
            this.remoteId = remoteId;
            this.opened = true;
            openLock.notifyAll();
        }
    }

    void onWrite(byte[] data) {
        if (data != null && data.length > 0) readQueue.offer(data);
    }

    void onClose() {
        closed = true;
        readQueue.offer(new byte[0]);
        synchronized (openLock) { openLock.notifyAll(); }
    }

    void waitUntilOpen(long timeoutMs) throws AdbException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (openLock) {
            while (!opened && !closed && System.currentTimeMillis() < deadline) {
                openLock.wait(Math.max(1, deadline - System.currentTimeMillis()));
            }
            if (!opened) throw new AdbException("ADB stream open timed out or was closed");
        }
    }

    public byte[] read(long timeoutMs) throws InterruptedException {
        byte[] data = readQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (data == null) return null;
        if (data.length == 0 && closed) return null;
        return data;
    }

    public String readAllAsString(long idleTimeoutMs, long maxWaitMs) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (!closed && System.currentTimeMillis() < deadline) {
            byte[] data = read(Math.min(idleTimeoutMs, Math.max(1, deadline - System.currentTimeMillis())));
            if (data == null) {
                if (closed) break;
                continue;
            }
            out.write(data);
        }
        close();
        return out.toString("UTF-8");
    }

    public void write(byte[] data) throws AdbException {
        if (closed) throw new AdbException("Stream already closed");
        connection.send(AdbProtocol.A_WRTE, localId, remoteId, data == null ? new byte[0] : Arrays.copyOf(data, data.length));
    }

    public void close() {
        if (!closed) {
            closed = true;
            try { connection.send(AdbProtocol.A_CLSE, localId, remoteId, new byte[0]); } catch (Throwable ignored) {}
            readQueue.offer(new byte[0]);
        }
    }
}
