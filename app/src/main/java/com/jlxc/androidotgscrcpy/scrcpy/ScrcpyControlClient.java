package com.jlxc.androidotgscrcpy.scrcpy;

import android.view.KeyEvent;
import android.view.MotionEvent;

import com.jlxc.androidotgscrcpy.adb.AdbStream;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class ScrcpyControlClient {
    public interface LogSink { void log(String msg); }

    private static final int TYPE_INJECT_KEYCODE = 0;
    private static final int TYPE_INJECT_TEXT = 1;
    private static final int TYPE_INJECT_TOUCH_EVENT = 2;
    private static final int TYPE_SET_DISPLAY_POWER = 10;
    private static final int TYPE_ROTATE_DEVICE = 11;

    private static final int BUTTON_PRIMARY = 1;

    private final AdbStream stream;
    private final LogSink log;
    private volatile boolean closed;

    public ScrcpyControlClient(AdbStream stream, LogSink log) {
        this.stream = stream;
        this.log = log;
    }

    public boolean isReady() {
        return stream != null && !closed && !stream.isClosed();
    }

    public synchronized void sendKey(int keyCode) {
        if (!isReady()) return;
        try {
            stream.write(keyMsg(KeyEvent.ACTION_DOWN, keyCode));
            stream.write(keyMsg(KeyEvent.ACTION_UP, keyCode));
        } catch (Throwable t) {
            closed = true;
            if (log != null) log.log("scrcpy key failed: " + t.getMessage());
        }
    }

    public synchronized void sendText(String text) {
        if (!isReady() || text == null || text.length() == 0) return;
        try {
            byte[] raw = text.getBytes(StandardCharsets.UTF_8);
            int len = Math.min(raw.length, 300);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(TYPE_INJECT_TEXT);
            writeInt(out, len);
            out.write(raw, 0, len);
            stream.write(out.toByteArray());
        } catch (Throwable t) {
            closed = true;
            if (log != null) log.log("scrcpy text failed: " + t.getMessage());
        }
    }

    public synchronized void sendDisplayPower(boolean on) {
        if (!isReady()) return;
        try {
            stream.write(new byte[] { (byte) TYPE_SET_DISPLAY_POWER, (byte) (on ? 1 : 0) });
        } catch (Throwable t) {
            closed = true;
            if (log != null) log.log("scrcpy display power failed: " + t.getMessage());
        }
    }

    public synchronized void rotateDevice() {
        if (!isReady()) return;
        try {
            stream.write(new byte[] { (byte) TYPE_ROTATE_DEVICE });
        } catch (Throwable t) {
            closed = true;
            if (log != null) log.log("scrcpy rotate failed: " + t.getMessage());
        }
    }

    public synchronized void sendTouch(int action, long pointerId, int x, int y, int screenW, int screenH, float pressure) {
        if (!isReady()) return;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(32);
            out.write(TYPE_INJECT_TOUCH_EVENT);
            out.write(action & 0xff);
            writeLong(out, pointerId);
            writeInt(out, x);
            writeInt(out, y);
            writeShort(out, screenW);
            writeShort(out, screenH);
            writeShort(out, encodeU16FixedPoint(pressure));
            int actionButton = (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) ? BUTTON_PRIMARY : 0;
            int buttons = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) ? 0 : BUTTON_PRIMARY;
            writeInt(out, actionButton);
            writeInt(out, buttons);
            stream.write(out.toByteArray());
        } catch (Throwable t) {
            closed = true;
            if (log != null) log.log("scrcpy touch failed: " + t.getMessage());
        }
    }

    public void close() {
        closed = true;
        try { stream.close(); } catch (Throwable ignored) {}
    }

    private static byte[] keyMsg(int action, int keyCode) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(14);
        out.write(TYPE_INJECT_KEYCODE);
        out.write(action & 0xff);
        writeInt(out, keyCode);
        writeInt(out, 0); // repeat
        writeInt(out, 0); // meta state
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int v) throws Exception {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array());
    }

    private static void writeLong(ByteArrayOutputStream out, long v) throws Exception {
        out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v).array());
    }

    private static void writeShort(ByteArrayOutputStream out, int v) throws Exception {
        out.write((v >> 8) & 0xff);
        out.write(v & 0xff);
    }

    private static int encodeU16FixedPoint(float value) {
        if (value <= 0f) return 0;
        if (value >= 1f) return 0xffff;
        return Math.round(value * 0xfffff) >> 4;
    }
}
