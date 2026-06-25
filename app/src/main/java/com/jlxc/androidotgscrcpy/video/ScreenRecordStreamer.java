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
    private final int videoWidth;
    private final int videoHeight;
    private final int bitRate;
    private final boolean useSizeOption;
    private final LogSink log;

    private volatile boolean running;
    private volatile AdbStream currentStream;
    private Thread thread;
    private MediaCodec decoder;

    public ScreenRecordStreamer(AdbConnection adb, Surface surface, int videoWidth, int videoHeight,
                                int bitRate, boolean useSizeOption, LogSink log) {
        this.adb = adb;
        this.surface = surface;
        this.videoWidth = Math.max(videoWidth, 320);
        this.videoHeight = Math.max(videoHeight, 240);
        this.bitRate = Math.max(bitRate, 1000000);
        this.useSizeOption = useSizeOption;
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
        AdbStream s = currentStream;
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) {}
        }
        releaseDecoder();
    }

    private void streamLoop() {
        try {
            setupDecoder();
            while (running) {
                log("Starting screenrecord stream: " + videoWidth + "x" + videoHeight + " @ " + (bitRate / 1000000f) + "Mbps");
                AdbStream stream = null;
                try {
                    stream = openScreenRecordStream();
                    currentStream = stream;
                    pump(stream);
                } catch (Throwable t) {
                    if (running) log("Stream stopped: " + t.getMessage());
                } finally {
                    currentStream = null;
                    if (stream != null) stream.close();
                }
                if (running) Thread.sleep(350);
            }
        } catch (Throwable t) {
            log("Decoder error: " + t.getMessage());
        } finally {
            running = false;
            releaseDecoder();
        }
    }

    private AdbStream openScreenRecordStream() throws Exception {
        String cmd = buildScreenRecordCommand();
        try {
            return adb.openStream("exec:" + cmd);
        } catch (Throwable execFailed) {
            log("exec: failed, falling back to shell:. Reason: " + execFailed.getMessage());
            return adb.openStream("shell:" + cmd);
        }
    }

    private String buildScreenRecordCommand() {
        StringBuilder sb = new StringBuilder();
        sb.append("screenrecord --output-format=h264 --bit-rate ").append(bitRate);
        if (useSizeOption) {
            sb.append(" --size ").append(videoWidth).append("x").append(videoHeight);
        }
        // Some Android versions stop screenrecord automatically after a few minutes.
        // The loop above restarts it when that happens.
        sb.append(" -");
        return sb.toString();
    }

    private void setupDecoder() throws Exception {
        decoder = MediaCodec.createDecoderByType("video/avc");
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", videoWidth, videoHeight);
        try { format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024); } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= 30) {
            try { format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1); } catch (Throwable ignored) {}
        }
        decoder.configure(format, surface, null, 0);
        decoder.start();
        log("MediaCodec AVC decoder started: " + videoWidth + "x" + videoHeight);
    }

    private void pump(AdbStream stream) throws Exception {
        H264NalParser parser = new H264NalParser();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long pts = 0;
        while (running && !stream.isClosed()) {
            drainOutput(info);
            byte[] data = stream.read(500);
            if (data == null) continue;
            List<byte[]> nals = parser.push(data);
            for (byte[] nal : nals) {
                queueNal(nal, pts);
                pts += 16666; // prefer low-latency pacing; real timestamps are not provided by screenrecord raw stream
                drainOutput(info);
            }
        }
    }

    private void queueNal(byte[] nal, long pts) throws Exception {
        if (decoder == null || nal == null || nal.length == 0) return;
        int inIndex = decoder.dequeueInputBuffer(3000);
        if (inIndex >= 0) {
            ByteBuffer input = getInputBuffer(decoder, inIndex);
            if (input == null) return;
            input.clear();
            int len = Math.min(input.remaining(), nal.length);
            input.put(nal, 0, len);
            decoder.queueInputBuffer(inIndex, 0, len, pts, 0);
        }
    }

    private void drainOutput(MediaCodec.BufferInfo info) {
        if (decoder == null) return;
        while (true) {
            int outIndex;
            try {
                outIndex = decoder.dequeueOutputBuffer(info, 0);
            } catch (Throwable t) {
                return;
            }
            if (outIndex >= 0) {
                try { decoder.releaseOutputBuffer(outIndex, true); } catch (Throwable ignored) {}
            } else {
                return;
            }
        }
    }

    private void releaseDecoder() {
        MediaCodec d = decoder;
        decoder = null;
        if (d != null) {
            try { d.stop(); } catch (Throwable ignored) {}
            try { d.release(); } catch (Throwable ignored) {}
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
