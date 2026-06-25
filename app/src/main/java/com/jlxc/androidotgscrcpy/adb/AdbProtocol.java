package com.jlxc.androidotgscrcpy.adb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class AdbProtocol {
    private AdbProtocol() {}

    static final int A_SYNC = command("SYNC");
    static final int A_CNXN = command("CNXN");
    static final int A_OPEN = command("OPEN");
    static final int A_OKAY = command("OKAY");
    static final int A_CLSE = command("CLSE");
    static final int A_WRTE = command("WRTE");
    static final int A_AUTH = command("AUTH");

    static final int A_VERSION = 0x01000000;
    static final int A_MAXDATA = 4096;

    static final int AUTH_TOKEN = 1;
    static final int AUTH_SIGNATURE = 2;
    static final int AUTH_RSAPUBLICKEY = 3;

    static final int HEADER_LENGTH = 24;

    static int command(String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        return (b[0] & 0xff) | ((b[1] & 0xff) << 8) | ((b[2] & 0xff) << 16) | ((b[3] & 0xff) << 24);
    }

    static String commandToString(int command) {
        char[] c = new char[4];
        c[0] = (char) (command & 0xff);
        c[1] = (char) ((command >> 8) & 0xff);
        c[2] = (char) ((command >> 16) & 0xff);
        c[3] = (char) ((command >> 24) & 0xff);
        return new String(c);
    }

    static byte[] stringPayload(String s) {
        byte[] src = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[src.length + 1];
        System.arraycopy(src, 0, out, 0, src.length);
        out[out.length - 1] = 0;
        return out;
    }

    static byte[] makeMessage(int command, int arg0, int arg1, byte[] payload) {
        if (payload == null) payload = new byte[0];
        int checksum = checksum(payload);
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + payload.length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(command);
        buffer.putInt(arg0);
        buffer.putInt(arg1);
        buffer.putInt(payload.length);
        buffer.putInt(checksum);
        buffer.putInt(command ^ 0xffffffff);
        buffer.put(payload);
        return buffer.array();
    }

    static int checksum(byte[] payload) {
        int sum = 0;
        if (payload != null) {
            for (byte b : payload) sum += b & 0xff;
        }
        return sum;
    }

    static Message parseHeader(byte[] header) throws AdbException {
        if (header.length != HEADER_LENGTH) throw new AdbException("Bad ADB header length: " + header.length);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = buffer.getInt();
        int arg0 = buffer.getInt();
        int arg1 = buffer.getInt();
        int length = buffer.getInt();
        int checksum = buffer.getInt();
        int magic = buffer.getInt();
        if ((command ^ 0xffffffff) != magic) {
            throw new AdbException("Bad ADB magic for " + commandToString(command));
        }
        if (length < 0 || length > 1024 * 1024) {
            throw new AdbException("Invalid ADB payload length: " + length);
        }
        return new Message(command, arg0, arg1, length, checksum, null);
    }

    static final class Message {
        final int command;
        final int arg0;
        final int arg1;
        final int payloadLength;
        final int checksum;
        final byte[] payload;

        Message(int command, int arg0, int arg1, int payloadLength, int checksum, byte[] payload) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.payloadLength = payloadLength;
            this.checksum = checksum;
            this.payload = payload;
        }

        Message withPayload(byte[] payload) {
            return new Message(command, arg0, arg1, payloadLength, checksum, payload);
        }
    }
}
