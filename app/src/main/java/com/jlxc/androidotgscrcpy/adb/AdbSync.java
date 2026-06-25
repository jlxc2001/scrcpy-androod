package com.jlxc.androidotgscrcpy.adb;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class AdbSync {
    private AdbSync() {}

    private static final int CHUNK = 2048; // Keep below ADB max payload used by this demo.

    public static void push(AdbConnection adb, String remotePath, byte[] data, int mode) throws Exception {
        if (data == null || data.length == 0) throw new AdbException("push data is empty");
        AdbStream s = adb.openStream("sync:");
        try {
            String target = remotePath + "," + mode;
            writePacket(s, "SEND", target.getBytes(StandardCharsets.UTF_8));
            int offset = 0;
            while (offset < data.length) {
                int len = Math.min(CHUNK, data.length - offset);
                byte[] part = new byte[len];
                System.arraycopy(data, offset, part, 0, len);
                writePacket(s, "DATA", part);
                offset += len;
            }
            writeDone(s, (int) (System.currentTimeMillis() / 1000L));
            SyncReply reply = readReply(s, 15000);
            if (!"OKAY".equals(reply.id)) {
                throw new AdbException("sync push failed: " + reply.id + " " + reply.message);
            }
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private static void writePacket(AdbStream s, String id, byte[] payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(id.getBytes(StandardCharsets.US_ASCII));
        out.write(le32(payload.length));
        out.write(payload);
        s.write(out.toByteArray());
    }

    private static void writeDone(AdbStream s, int mtime) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("DONE".getBytes(StandardCharsets.US_ASCII));
        out.write(le32(mtime));
        s.write(out.toByteArray());
    }

    private static SyncReply readReply(AdbStream s, long timeoutMs) throws Exception {
        byte[] idb = readExact(s, 4, timeoutMs);
        String id = new String(idb, StandardCharsets.US_ASCII);
        if ("OKAY".equals(id)) return new SyncReply(id, "");
        byte[] lenb = readExact(s, 4, timeoutMs);
        int len = ByteBuffer.wrap(lenb).order(ByteOrder.LITTLE_ENDIAN).getInt();
        String msg = "";
        if (len > 0 && len < 1024 * 1024) {
            msg = new String(readExact(s, len, timeoutMs), StandardCharsets.UTF_8);
        }
        return new SyncReply(id, msg);
    }

    private static byte[] readExact(AdbStream s, int len, long timeoutMs) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (out.size() < len && System.currentTimeMillis() < deadline) {
            byte[] data = s.read(Math.max(1, Math.min(500, deadline - System.currentTimeMillis())));
            if (data == null) continue;
            int need = len - out.size();
            out.write(data, 0, Math.min(need, data.length));
            if (data.length > need) {
                // This very small sync helper expects reply packets not to be coalesced with future data.
                // For adb sync DONE replies this is OK in practice.
            }
        }
        if (out.size() != len) throw new AdbException("sync read timed out");
        return out.toByteArray();
    }

    private static byte[] le32(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    private static final class SyncReply {
        final String id;
        final String message;
        SyncReply(String id, String message) { this.id = id; this.message = message; }
    }
}
