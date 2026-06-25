package com.jlxc.androidotgscrcpy.video;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import com.jlxc.androidotgscrcpy.adb.AdbConnection;
import com.jlxc.androidotgscrcpy.adb.AdbStream;

import java.nio.ByteBuffer;
import java.util.List;

public final class ScreenRecordStreamer {
    public interface LogSink { void log(String message); }

    private final AdbConnection adb;
    private final Surface surface;
    private final int width;
    private final int height;
    private final LogSink log;
    private volatile boolean running;
    private Thread thread;
    private MediaCodec decoder;

    public ScreenRecordStreamer(AdbConnection adb, Surface surface, int width, int height, LogSink log) {
        this.adb = adb;
        this.surface = surface;
        this.width = Math.max(width, 320);
        this.height = Math.max(height, 240);
        this.log = log;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { streamLoop(); }
        }, "screenrecord-streamer");
        thread.start();
    }

    public void stop() {
        running = false;
        try { if (decoder != null) decoder.stop(); } catch (Throwable ignored) {}
        try { if (decoder != null) decoder.release(); } catch (Throwable ignored) {}
        decoder = null;
    }

    private void streamLoop() {
        try {
            setupDecoder();
            while (running) {
                log("Starting adb screenrecord H.264 stream...");
                AdbStream stream = null;
                try {
                    stream = openScreenRecordStream();
                    pump(stream);
                } catch (Throwable t) {
                    log("Stream stopped: " + t.getMessage());
                } finally {
                    if (stream != null) stream.close();
                }
                if (running) Thread.sleep(600);
            }
        } catch (Throwable t) {
            log("Decoder error: " + t.getMessage());
        } finally {
            stop();
        }
    }

    private AdbStream openScreenRecordStream() throws Exception {
        String cmd = "screenrecord --output-format=h264 --bit-rate 4000000 -";
        try {
            return adb.openStream("exec:" + cmd);
        } catch (Throwable execFailed) {
            log("exec: failed, falling back to shell:. Reason: " + execFailed.getMessage());
            return adb.openStream("shell:" + cmd);
        }
    }

    private void setupDecoder() throws Exception {
        decoder = MediaCodec.createDecoderByType("video/avc");
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        if (Build.VERSION.SDK_INT >= 30) {
            try { format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1); } catch (Throwable ignored) {}
        }
        decoder.configure(format, surface, null, 0);
        decoder.start();
        log("MediaCodec AVC decoder started: " + width + "x" + height);
    }

    private void pump(AdbStream stream) throws Exception {
        H264NalParser parser = new H264NalParser();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long pts = 0;
        while (running && !stream.isClosed()) {
            byte[] data = stream.read(1000);
            if (data == null) continue;
            List<byte[]> nals = parser.push(data);
            for (byte[] nal : nals) {
                int inIndex = decoder.dequeueInputBuffer(10000);
                if (inIndex >= 0) {
                    ByteBuffer input = getInputBuffer(decoder, inIndex);
                    if (input == null) continue;
                    input.clear();
                    int len = Math.min(input.remaining(), nal.length);
                    input.put(nal, 0, len);
                    decoder.queueInputBuffer(inIndex, 0, len, pts, 0);
                    pts += 33333;
                }
                int outIndex;
                do {
                    outIndex = decoder.dequeueOutputBuffer(info, 0);
                    if (outIndex >= 0) decoder.releaseOutputBuffer(outIndex, true);
                } while (outIndex >= 0);
            }
        }
    }

    private static ByteBuffer getInputBuffer(MediaCodec codec, int index) {
        if (Build.VERSION.SDK_INT >= 21) return codec.getInputBuffer(index);
        return codec.getInputBuffers()[index];
    }

    private void log(String s) {
        if (log != null) log.log(s);
    }
}
