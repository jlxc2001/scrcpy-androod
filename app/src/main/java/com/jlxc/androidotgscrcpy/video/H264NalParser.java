package com.jlxc.androidotgscrcpy.video;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class H264NalParser {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    public synchronized List<byte[]> push(byte[] data) {
        List<byte[]> out = new ArrayList<byte[]>();
        if (data == null || data.length == 0) return out;
        try { buffer.write(data); } catch (Exception ignored) {}
        byte[] all = buffer.toByteArray();
        int first = findStartCode(all, 0);
        if (first < 0) {
            // Keep a small tail so start code split across USB packets is not lost.
            if (all.length > 3) {
                buffer.reset();
                buffer.write(all, all.length - 3, 3);
            }
            return out;
        }
        int search = first + startCodeLength(all, first);
        while (true) {
            int next = findStartCode(all, search);
            if (next < 0) break;
            if (next > first) {
                byte[] nal = new byte[next - first];
                System.arraycopy(all, first, nal, 0, nal.length);
                out.add(nal);
            }
            first = next;
            search = first + startCodeLength(all, first);
        }
        buffer.reset();
        if (first < all.length) buffer.write(all, first, all.length - first);
        return out;
    }

    private static int findStartCode(byte[] data, int from) {
        for (int i = Math.max(0, from); i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) return i;
                if (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1) return i;
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] data, int pos) {
        if (pos + 2 < data.length && data[pos] == 0 && data[pos + 1] == 0 && data[pos + 2] == 1) return 3;
        return 4;
    }
}
