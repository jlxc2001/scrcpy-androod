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

    private byte[] sps;
    private byte[] pps;
    private boolean decoderStarted;
    private long totalBytes;
    private long totalNals;
    private long lastProgressLog;

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
            while (running) {
                resetDecoderState();
                log("Starting video stream: " + videoWidth + "x" + videoHeight + " @ " + (bitRate / 1000000f) + "Mbps");
                AdbStream stream = null;
                try {
                    stream = openScreenRecordStream();
                    currentStream = stream;
                    pump(stream);
                } catch (Throwable t) {
                    if (running) log("Video stream stopped: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                } finally {
                    currentStream = null;
                    if (stream != null) {
                        try { stream.close(); } catch (Throwable ignored) {}
                    }
                    releaseDecoder();
                }
                if (running) {
                    try {
                        Thread.sleep(450);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            running = false;
            releaseDecoder();
        }
    }

    private void resetDecoderState() {
        sps = null;
        pps = null;
        decoderStarted = false;
        totalBytes = 0;
        totalNals = 0;
        lastProgressLog = System.currentTimeMillis();
        releaseDecoder();
    }

    private AdbStream openScreenRecordStream() throws Exception {
        String cmd = buildScreenRecordCommand();
        // Prefer exec: because it is the closest thing to adb exec-out and does not allocate a PTY.
        // PTY based shell streams may corrupt binary H.264 on some devices.
        try {
            log("Opening ADB exec stream: " + cmd);
            return adb.openStream("exec:" + cmd);
        } catch (Throwable execFailed) {
            log("exec: open failed, falling back to shell:. " + execFailed.getMessage());
            log("Opening ADB shell stream: " + cmd);
            return adb.openStream("shell:" + cmd);
        }
    }

    private String buildScreenRecordCommand() {
        StringBuilder sb = new StringBuilder();
        sb.append("screenrecord --output-format=h264 --bit-rate ").append(bitRate);
        if (useSizeOption) {
            sb.append(" --size ").append(videoWidth).append("x").append(videoHeight);
        }
        sb.append(" -");
        return sb.toString();
    }

    private void pump(AdbStream stream) throws Exception {
        H264NalParser parser = new H264NalParser();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long pts = 0;
        long startedAt = System.currentTimeMillis();
        while (running && !stream.isClosed()) {
            drainOutput(info);
            byte[] data = stream.read(500);
            if (data == null) {
                if (totalBytes == 0 && System.currentTimeMillis() - startedAt > 2500) {
                    log("No video bytes yet. Target may not support: screenrecord --output-format=h264 -");
                    startedAt = System.currentTimeMillis();
                }
                continue;
            }
            totalBytes += data.length;
            List<byte[]> nals = parser.push(data);
            if (nals.size() == 0) {
                logProgress(false);
                continue;
            }
            for (byte[] nal : nals) {
                int type = nalType(nal);
                totalNals++;
                if (type == 7) {
                    sps = copy(nal);
                    log("H.264 SPS received (" + nal.length + " bytes)");
                    tryStartDecoder();
                    continue;
                }
                if (type == 8) {
                    pps = copy(nal);
                    log("H.264 PPS received (" + nal.length + " bytes)");
                    tryStartDecoder();
                    continue;
                }
                if (!decoderStarted) {
                    if (type >= 0) logProgress(false);
                    continue;
                }
                int flags = 0;
                if (type == 5) flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
                queueNal(nal, pts, flags);
                pts += 16666;
                drainOutput(info);
            }
            logProgress(decoderStarted);
        }
    }

    private void tryStartDecoder() throws Exception {
        if (decoderStarted || sps == null || pps == null) return;
        decoder = MediaCodec.createDecoderByType("video/avc");
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", videoWidth, videoHeight);
        try { format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024); } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= 30) {
            try { format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1); } catch (Throwable ignored) {}
        }
        // Some Android decoders stay black unless SPS/PPS is provided as csd before start().
        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
        decoder.configure(format, surface, null, 0);
        decoder.start();
        decoderStarted = true;
        log("MediaCodec AVC decoder started after SPS/PPS: " + videoWidth + "x" + videoHeight);
    }

    private void queueNal(byte[] nal, long pts, int flags) throws Exception {
        if (decoder == null || nal == null || nal.length == 0) return;
        int inIndex = decoder.dequeueInputBuffer(3000);
        if (inIndex >= 0) {
            ByteBuffer input = getInputBuffer(decoder, inIndex);
            if (input == null) return;
            input.clear();
            int len = Math.min(input.remaining(), nal.length);
            input.put(nal, 0, len);
            decoder.queueInputBuffer(inIndex, 0, len, pts, flags);
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
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try { log("Decoder output format: " + decoder.getOutputFormat()); } catch (Throwable ignored) {}
            } else {
                return;
            }
        }
    }

    private void releaseDecoder() {
        MediaCodec d = decoder;
        decoder = null;
        decoderStarted = false;
        if (d != null) {
            try { d.stop(); } catch (Throwable ignored) {}
            try { d.release(); } catch (Throwable ignored) {}
        }
    }

    private void logProgress(boolean decoding) {
        long now = System.currentTimeMillis();
        if (now - lastProgressLog < 1500) return;
        lastProgressLog = now;
        if (totalBytes == 0) return;
        String state;
        if (decoding) state = "decoding";
        else if (sps == null || pps == null) state = "waiting SPS/PPS";
        else state = "waiting decoder";
        log("Video data: " + totalBytes + " bytes, " + totalNals + " NALs, " + state);
    }

    private static int nalType(byte[] nal) {
        int pos = startCodeEnd(nal);
        if (pos < 0 || pos >= nal.length) return -1;
        return nal[pos] & 0x1F;
    }

    private static int startCodeEnd(byte[] data) {
        if (data == null || data.length < 5) return -1;
        if (data[0] == 0 && data[1] == 0 && data[2] == 1) return 3;
        if (data.length >= 6 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) return 4;
        for (int i = 0; i + 4 < data.length && i < 8; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) return i + 3;
                if (data[i + 2] == 0 && data[i + 3] == 1) return i + 4;
            }
        }
        return -1;
    }

    private static byte[] copy(byte[] src) {
        byte[] out = new byte[src.length];
        System.arraycopy(src, 0, out, 0, src.length);
        return out;
    }

    private static ByteBuffer getInputBuffer(MediaCodec codec, int index) {
        if (Build.VERSION.SDK_INT >= 21) return codec.getInputBuffer(index);
        return codec.getInputBuffers()[index];
    }

    private void log(String s) {
        if (log != null) log.log(s);
    }
}
