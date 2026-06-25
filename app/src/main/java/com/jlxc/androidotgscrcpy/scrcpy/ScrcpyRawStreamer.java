package com.jlxc.androidotgscrcpy.scrcpy;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import com.jlxc.androidotgscrcpy.adb.AdbConnection;
import com.jlxc.androidotgscrcpy.adb.AdbStream;
import com.jlxc.androidotgscrcpy.adb.AdbSync;
import com.jlxc.androidotgscrcpy.video.H264NalParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class ScrcpyRawStreamer {
    public interface LogSink { void log(String message); }
    public interface ControlReadyCallback { void onControlReady(ScrcpyControlClient client); }

    private static final String SERVER_ASSET = "scrcpy-server-v4.0";
    private static final String SERVER_REMOTE_PATH = "/data/local/tmp/scrcpy-server.jar";
    private static final String SCRCPY_VERSION = "4.0";

    private final Context context;
    private final AdbConnection adb;
    private final Surface surface;
    private final int videoWidth;
    private final int videoHeight;
    private final int maxSize;
    private final int bitRate;
    private final boolean controlEnabled;
    private final boolean stayAwake;
    private final LogSink log;
    private final ControlReadyCallback controlReadyCallback;

    private volatile boolean running;
    private Thread thread;
    private MediaCodec decoder;
    private AdbStream videoStream;
    private AdbStream controlStream;
    private AdbStream serverStream;
    private ScrcpyControlClient controlClient;

    private byte[] sps;
    private byte[] pps;
    private boolean decoderStarted;
    private long totalBytes;
    private long totalNals;
    private long lastProgressLog;

    public ScrcpyRawStreamer(Context context, AdbConnection adb, Surface surface, int videoWidth, int videoHeight,
                             int maxSize, int bitRate, boolean controlEnabled, boolean stayAwake,
                             LogSink log, ControlReadyCallback controlReadyCallback) {
        this.context = context.getApplicationContext();
        this.adb = adb;
        this.surface = surface;
        this.videoWidth = Math.max(videoWidth, 320);
        this.videoHeight = Math.max(videoHeight, 240);
        this.maxSize = Math.max(0, maxSize);
        this.bitRate = Math.max(1000000, bitRate);
        this.controlEnabled = controlEnabled;
        this.stayAwake = stayAwake;
        this.log = log;
        this.controlReadyCallback = controlReadyCallback;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() { @Override public void run() { runInternal(); } }, "scrcpy-v3-streamer");
        thread.start();
    }

    public void stop() {
        running = false;
        if (controlClient != null) controlClient.close();
        closeStream(videoStream);
        closeStream(controlStream);
        closeStream(serverStream);
        releaseDecoder();
    }

    public ScrcpyControlClient getControlClient() { return controlClient; }

    private void runInternal() {
        try {
            log("V3 true-scrcpy mode: pushing official scrcpy-server v4.0...");
            byte[] server = loadServerAsset();
            log("scrcpy-server asset size: " + server.length + " bytes");
            AdbSync.push(adb, SERVER_REMOTE_PATH, server, 0644);
            log("scrcpy-server pushed to " + SERVER_REMOTE_PATH);

            int scid = new Random().nextInt() & 0x7fffffff;
            String scidHex = String.format(Locale.US, "%08x", scid);
            String socketName = "scrcpy_" + scidHex;
            String cmd = buildServerCommand(scidHex);
            log("Starting scrcpy-server: " + cmd);
            serverStream = adb.openStream("shell:" + cmd);
            startServerLogReader(serverStream);

            videoStream = connectLocalabstract(socketName, "video", 10000);
            if (controlEnabled) {
                controlStream = connectLocalabstract(socketName, "control", 10000);
                controlClient = new ScrcpyControlClient(controlStream, new ScrcpyControlClient.LogSink() {
                    @Override public void log(String msg) { ScrcpyRawStreamer.this.log(msg); }
                });
                if (controlReadyCallback != null) controlReadyCallback.onControlReady(controlClient);
                log("scrcpy control socket connected. 多指触控/熄屏控制走 scrcpy 控制协议。 ");
            }
            log("scrcpy video socket connected. Decoding raw H.264...");
            resetDecoderState();
            pumpRawH264(videoStream);
        } catch (Throwable t) {
            if (running) log("scrcpy V3 stopped: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            running = false;
            if (controlReadyCallback != null) controlReadyCallback.onControlReady(null);
            if (controlClient != null) controlClient.close();
            closeStream(videoStream);
            closeStream(controlStream);
            closeStream(serverStream);
            releaseDecoder();
        }
    }

    private String buildServerCommand(String scidHex) {
        StringBuilder sb = new StringBuilder();
        sb.append("CLASSPATH=").append(SERVER_REMOTE_PATH).append(" app_process / com.genymobile.scrcpy.Server ");
        sb.append(SCRCPY_VERSION);
        sb.append(" scid=").append(scidHex);
        sb.append(" log_level=debug");
        sb.append(" tunnel_forward=true");
        sb.append(" audio=false");
        sb.append(" control=").append(controlEnabled ? "true" : "false");
        sb.append(" cleanup=false");
        sb.append(" raw_stream=true");
        sb.append(" video_codec=h264");
        sb.append(" video_bit_rate=").append(bitRate);
        if (maxSize > 0) sb.append(" max_size=").append(maxSize);
        if (stayAwake) sb.append(" stay_awake=true");
        return sb.toString();
    }

    private byte[] loadServerAsset() throws Exception {
        InputStream in = null;
        try {
            in = context.getAssets().open(SERVER_ASSET);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            byte[] data = out.toByteArray();
            if (data.length < 100000) {
                throw new IllegalStateException("scrcpy-server-v4.0 asset looks invalid. Build with GitHub Actions download step.");
            }
            return data;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private AdbStream connectLocalabstract(String socketName, String label, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Throwable last = null;
        while (running && System.currentTimeMillis() < deadline) {
            try {
                log("Connecting scrcpy " + label + " socket: localabstract:" + socketName);
                return adb.openStream("localabstract:" + socketName);
            } catch (Throwable t) {
                last = t;
                try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        throw new IllegalStateException("Could not connect scrcpy " + label + " socket" + (last == null ? "" : ": " + last.getMessage()));
    }

    private void startServerLogReader(final AdbStream s) {
        new Thread(new Runnable() {
            @Override public void run() {
                while (running && s != null && !s.isClosed()) {
                    try {
                        byte[] data = s.read(500);
                        if (data != null && data.length > 0) {
                            String text = new String(data, "UTF-8").trim();
                            if (text.length() > 0) log("server: " + text);
                        }
                    } catch (Throwable t) {
                        break;
                    }
                }
            }
        }, "scrcpy-server-log").start();
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

    private void pumpRawH264(AdbStream stream) throws Exception {
        H264NalParser parser = new H264NalParser();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long pts = 0;
        long start = System.currentTimeMillis();
        while (running && stream != null && !stream.isClosed()) {
            drainOutput(info);
            byte[] data = stream.read(500);
            if (data == null) {
                long now = System.currentTimeMillis();
                if (totalBytes == 0 && now - start > 3000) {
                    log("Waiting for scrcpy raw H.264 bytes...");
                    start = now;
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
                    log("scrcpy H.264 SPS received (" + nal.length + " bytes)");
                    tryStartDecoder();
                    continue;
                }
                if (type == 8) {
                    pps = copy(nal);
                    log("scrcpy H.264 PPS received (" + nal.length + " bytes)");
                    tryStartDecoder();
                    continue;
                }
                if (!decoderStarted) {
                    logProgress(false);
                    continue;
                }
                int flags = type == 5 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
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
        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
        decoder.configure(format, surface, null, 0);
        decoder.start();
        decoderStarted = true;
        log("MediaCodec AVC decoder started for true scrcpy raw stream.");
    }

    private void queueNal(byte[] nal, long ptsUs, int flags) {
        if (decoder == null) return;
        try {
            int idx = decoder.dequeueInputBuffer(0);
            if (idx < 0) return;
            ByteBuffer input = getInputBuffer(decoder, idx);
            if (input == null) return;
            input.clear();
            if (nal.length > input.capacity()) {
                log("NAL too large for decoder input: " + nal.length);
                return;
            }
            input.put(nal);
            decoder.queueInputBuffer(idx, 0, nal.length, ptsUs, flags);
        } catch (Throwable t) {
            log("queueNal failed: " + t.getMessage());
        }
    }

    private void drainOutput(MediaCodec.BufferInfo info) {
        if (decoder == null) return;
        try {
            while (true) {
                int out = decoder.dequeueOutputBuffer(info, 0);
                if (out >= 0) {
                    decoder.releaseOutputBuffer(out, info.size > 0);
                } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    log("Decoder output format: " + decoder.getOutputFormat());
                } else {
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    private ByteBuffer getInputBuffer(MediaCodec codec, int idx) {
        if (Build.VERSION.SDK_INT >= 21) return codec.getInputBuffer(idx);
        return codec.getInputBuffers()[idx];
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

    private int nalType(byte[] nal) {
        if (nal == null || nal.length == 0) return -1;
        int i = 0;
        while (i + 3 < nal.length) {
            if (nal[i] == 0 && nal[i + 1] == 0) {
                if (nal[i + 2] == 1) { i += 3; break; }
                if (nal[i + 2] == 0 && nal[i + 3] == 1) { i += 4; break; }
            }
            i++;
        }
        if (i >= nal.length) return -1;
        return nal[i] & 0x1f;
    }

    private byte[] copy(byte[] src) {
        byte[] out = new byte[src.length];
        System.arraycopy(src, 0, out, 0, src.length);
        return out;
    }

    private void logProgress(boolean decoding) {
        long now = System.currentTimeMillis();
        if (now - lastProgressLog > 2000) {
            lastProgressLog = now;
            log("scrcpy video bytes=" + totalBytes + " nals=" + totalNals + " decoding=" + decoding);
        }
    }

    private void closeStream(AdbStream s) {
        if (s != null) try { s.close(); } catch (Throwable ignored) {}
    }

    private void log(String message) {
        if (log != null) log.log(message);
    }
}
