package com.jlxc.androidotgscrcpy;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jlxc.androidotgscrcpy.adb.AdbConnection;
import com.jlxc.androidotgscrcpy.adb.AdbCrypto;
import com.jlxc.androidotgscrcpy.adb.UsbAdbDevice;
import com.jlxc.androidotgscrcpy.scrcpy.ScrcpyControlClient;
import com.jlxc.androidotgscrcpy.scrcpy.ScrcpyRawStreamer;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.jlxc.androidotgscrcpy.USB_PERMISSION";

    private UsbManager usbManager;
    private UsbDevice selectedDevice;
    private AdbConnection adb;
    private ScrcpyRawStreamer streamer;
    private ScrcpyControlClient scrcpyControl;

    private TextView logView;
    private TextView titleView;
    private TextView statusView;
    private RemoteSurfaceView surfaceView;
    private FrameLayout previewFrame;
    private EditText inputText;

    private int remoteWidth = 1080;
    private int remoteHeight = 1920;
    private int streamWidth = 720;
    private int streamHeight = 1280;
    private int qualityMode = 0; // 0=smooth, 1=clear, 2=native
    private boolean fillMode = false;
    private boolean surfaceReady;

    private float downX, downY;
    private long downTime;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) {
                    selectedDevice = device;
                    log("USB permission granted: " + device.getDeviceName());
                    connectAdb();
                } else {
                    log("USB permission denied.");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                log("USB device attached.");
                refreshUsbDevices();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                log("USB device detached.");
                stopPreview();
                closeAdb();
                selectedDevice = null;
                updateStatus();
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        buildUi();
        registerUsbReceiver();
        refreshUsbDevices();
        updateVideoSize();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbReceiver); } catch (Throwable ignored) {}
        stopPreview();
        closeAdb();
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.rgb(8, 10, 12));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(12), dp(12), dp(8), dp(12));
        root.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 7));

        titleView = new TextView(this);
        titleView.setText("Android OTG Scrcpy V3 / true scrcpy-server 4.0");
        titleView.setTextColor(Color.rgb(57, 197, 187));
        titleView.setTextSize(18);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(titleView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(160, 190, 190));
        statusView.setTextSize(12);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(statusView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

        previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);
        left.addView(previewFrame, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        surfaceView = new RemoteSurfaceView(this);
        surfaceView.setBackgroundColor(Color.BLACK);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { surfaceReady = true; }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; stopPreview(); }
        });
        surfaceView.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) { return handleRemoteTouch(v, event); }
        });
        FrameLayout.LayoutParams surfaceLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        surfaceLp.gravity = Gravity.CENTER;
        previewFrame.addView(surfaceView, surfaceLp);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(8), dp(12), dp(12), dp(12));
        root.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3));

        addButton(right, "刷新 USB", new View.OnClickListener() { @Override public void onClick(View v) { refreshUsbDevices(); } });
        addButton(right, "连接 ADB", new View.OnClickListener() { @Override public void onClick(View v) { connectAdb(); } });
        addButton(right, "读取设备信息", new View.OnClickListener() { @Override public void onClick(View v) { readDeviceInfo(); } });
        addButton(right, "启动真 scrcpy", new View.OnClickListener() { @Override public void onClick(View v) { startPreview(); } });
        addButton(right, "停止预览", new View.OnClickListener() { @Override public void onClick(View v) { stopPreview(); } });

        LinearLayout viewRow = new LinearLayout(this);
        viewRow.setOrientation(LinearLayout.HORIZONTAL);
        right.addView(viewRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        addButton(viewRow, "适应", new View.OnClickListener() { @Override public void onClick(View v) { toggleFillMode(); } }, 1);
        addButton(viewRow, "画质", new View.OnClickListener() { @Override public void onClick(View v) { toggleQualityMode(); } }, 1);
        addButton(viewRow, "交换宽高", new View.OnClickListener() { @Override public void onClick(View v) { swapRemoteSize(); } }, 1);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        right.addView(nav, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        addButton(nav, "返回", new View.OnClickListener() { @Override public void onClick(View v) { keyEvent(4); } }, 1);
        addButton(nav, "HOME", new View.OnClickListener() { @Override public void onClick(View v) { keyEvent(3); } }, 1);
        addButton(nav, "最近", new View.OnClickListener() { @Override public void onClick(View v) { keyEvent(187); } }, 1);

        LinearLayout moreKeys = new LinearLayout(this);
        moreKeys.setOrientation(LinearLayout.HORIZONTAL);
        right.addView(moreKeys, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        addButton(moreKeys, "电源", new View.OnClickListener() { @Override public void onClick(View v) { keyEvent(26); } }, 1);
        addButton(moreKeys, "音量-", new View.OnClickListener() { @Override public void onClick(View v) { keyEvent(25); } }, 1);
        addButton(moreKeys, "音量+", new View.OnClickListener() { @Override public void onClick(View v) { keyEvent(24); } }, 1);

        LinearLayout scrcpyKeys = new LinearLayout(this);
        scrcpyKeys.setOrientation(LinearLayout.HORIZONTAL);
        right.addView(scrcpyKeys, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        addButton(scrcpyKeys, "熄屏投屏", new View.OnClickListener() { @Override public void onClick(View v) { setRemoteDisplayPower(false); } }, 1);
        addButton(scrcpyKeys, "亮屏", new View.OnClickListener() { @Override public void onClick(View v) { setRemoteDisplayPower(true); } }, 1);
        addButton(scrcpyKeys, "旋转", new View.OnClickListener() { @Override public void onClick(View v) { rotateRemote(); } }, 1);

        inputText = new EditText(this);
        inputText.setSingleLine(true);
        inputText.setTextColor(Color.WHITE);
        inputText.setHintTextColor(Color.rgb(100, 130, 130));
        inputText.setHint("输入文本后发送，scrcpy 协议支持 UTF-8");
        inputText.setInputType(InputType.TYPE_CLASS_TEXT);
        inputText.setBackgroundColor(Color.rgb(18, 26, 28));
        right.addView(inputText, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        addButton(right, "发送文本", new View.OnClickListener() { @Override public void onClick(View v) { sendTextToRemote(); } });

        logView = new TextView(this);
        logView.setTextColor(Color.rgb(210, 230, 230));
        logView.setTextSize(12);
        logView.setText("准备就绪。V3 改为官方 scrcpy-server v4.0：视频走 scrcpy raw stream，触控优先走 scrcpy control socket。\n");
        final ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        right.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void addButton(LinearLayout parent, String text, View.OnClickListener listener) { addButton(parent, text, listener, 0); }

    private void addButton(LinearLayout parent, String text, View.OnClickListener listener, int weight) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.rgb(31, 42, 46));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp;
        if (weight > 0) lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
        else lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        parent.addView(button, lp);
    }

    private void refreshUsbDevices() {
        HashMap<String, UsbDevice> map = usbManager.getDeviceList();
        selectedDevice = null;
        log("USB devices: " + map.size());
        for (Map.Entry<String, UsbDevice> entry : map.entrySet()) {
            UsbDevice device = entry.getValue();
            boolean adbInterface = UsbAdbDevice.hasAdbInterface(device);
            log("- " + device.getDeviceName() + " VID=" + device.getVendorId() + " PID=" + device.getProductId() + " ADB=" + adbInterface);
            if (adbInterface && selectedDevice == null) selectedDevice = device;
        }
        if (selectedDevice == null) log("未发现 ADB 接口。请确认被控端已开启 USB 调试，并且 OTG 主从方向正确。");
        else log("已选择: " + selectedDevice.getDeviceName());
        updateStatus();
    }

    private void connectAdb() {
        if (selectedDevice == null) {
            refreshUsbDevices();
            if (selectedDevice == null) return;
        }
        if (!usbManager.hasPermission(selectedDevice)) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(selectedDevice, pi);
            log("Requesting USB permission...");
            return;
        }
        runBg(new Runnable() {
            @Override public void run() {
                try {
                    closeAdb();
                    UsbDeviceConnection connection = usbManager.openDevice(selectedDevice);
                    if (connection == null) throw new RuntimeException("openDevice returned null");
                    UsbAdbDevice usbAdb = UsbAdbDevice.open(selectedDevice, connection);
                    AdbCrypto crypto = AdbCrypto.loadOrCreate(MainActivity.this);
                    adb = new AdbConnection(usbAdb, crypto, new AdbConnection.LogSink() {
                        @Override public void log(String message) { MainActivity.this.log(message); }
                    });
                    adb.connect();
                    log("ADB 连接成功。首次连接如果被控端弹授权，请点允许后再点一次连接。 ");
                    updateStatus();
                } catch (Throwable t) {
                    log("连接失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    closeAdb();
                    updateStatus();
                }
            }
        });
    }

    private void readDeviceInfo() {
        if (!ensureAdb()) return;
        runBg(new Runnable() {
            @Override public void run() {
                try {
                    String cmd = "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; wm size; wm density";
                    String out = adb.shell(cmd, 6000);
                    log("设备信息:\n" + out.trim());
                    parseRemoteSize(out);
                    updateVideoSize();
                } catch (Throwable t) { log("读取失败: " + t.getMessage()); }
            }
        });
    }

    private void startPreview() {
        if (!ensureAdb()) return;
        if (!surfaceReady) { log("Surface 未就绪，稍后再试。"); return; }
        stopPreview();
        runBg(new Runnable() {
            @Override public void run() {
                try {
                    String sizeOut = adb.shell("wm size", 3000);
                    parseRemoteSize(sizeOut);
                    updateVideoSize();
                } catch (Throwable ignored) {}
                VideoProfile profile = getVideoProfile();
                streamer = new ScrcpyRawStreamer(MainActivity.this, adb, surfaceView.getHolder().getSurface(),
                        profile.width, profile.height, profile.maxSize, profile.bitRate,
                        true, true,
                        new ScrcpyRawStreamer.LogSink() { @Override public void log(String message) { MainActivity.this.log(message); } },
                        new ScrcpyRawStreamer.ControlReadyCallback() {
                            @Override public void onControlReady(ScrcpyControlClient client) {
                                scrcpyControl = client;
                                updateStatus();
                            }
                        });
                streamer.start();
                log("启动真 scrcpy：远端 " + remoteWidth + "x" + remoteHeight + "，视频 " + profile.width + "x" + profile.height + "，" + getQualityName());
            }
        });
    }

    private void stopPreview() {
        ScrcpyRawStreamer s = streamer;
        streamer = null;
        scrcpyControl = null;
        if (s != null) {
            s.stop();
            log("预览已停止。 ");
        }
        updateStatus();
    }

    private boolean ensureAdb() {
        if (adb == null || !adb.isConnected()) { log("ADB 未连接。先点“连接 ADB”。"); return false; }
        return true;
    }

    private void keyEvent(final int keyCode) {
        if (!ensureAdb()) return;
        ScrcpyControlClient c = scrcpyControl;
        if (c != null && c.isReady()) {
            c.sendKey(keyCode);
            return;
        }
        runBg(new Runnable() { @Override public void run() { shellQuick("input keyevent " + keyCode); } });
    }

    private void setRemoteDisplayPower(boolean on) {
        ScrcpyControlClient c = scrcpyControl;
        if (c != null && c.isReady()) {
            c.sendDisplayPower(on);
            log(on ? "已通过 scrcpy control 请求亮屏。" : "已通过 scrcpy control 请求熄屏投屏。 ");
        } else {
            log("scrcpy control socket 未就绪。先启动真 scrcpy。 ");
        }
    }

    private void rotateRemote() {
        ScrcpyControlClient c = scrcpyControl;
        if (c != null && c.isReady()) c.rotateDevice();
        else log("scrcpy control socket 未就绪。 ");
    }

    private void sendTextToRemote() {
        if (!ensureAdb()) return;
        final String raw = inputText == null ? "" : inputText.getText().toString();
        if (raw.length() == 0) return;
        ScrcpyControlClient c = scrcpyControl;
        if (c != null && c.isReady()) {
            c.sendText(raw);
            return;
        }
        runBg(new Runnable() {
            @Override public void run() {
                String encoded = encodeInputText(raw);
                if (encoded.length() == 0) { log("文本为空或包含过多 input text 不兼容字符。"); return; }
                shellQuick("input text " + encoded);
            }
        });
    }

    private boolean handleRemoteTouch(View view, MotionEvent event) {
        if (adb == null || !adb.isConnected()) return true;
        ScrcpyControlClient c = scrcpyControl;
        if (c != null && c.isReady()) return handleScrcpyTouch(view, event, c);
        return handleAdbFallbackTouch(view, event);
    }

    private boolean handleScrcpyTouch(View view, MotionEvent event, ScrcpyControlClient c) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                Point p = mapToRemote(view, event.getX(i), event.getY(i));
                c.sendTouch(MotionEvent.ACTION_MOVE, event.getPointerId(i), p.x, p.y, remoteWidth, remoteHeight, 1f);
            }
            return true;
        }
        int index = event.getActionIndex();
        if (index < 0 || index >= event.getPointerCount()) index = 0;
        int outAction = action;
        float pressure = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) ? 0f : 1f;
        if (action == MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                Point p = mapToRemote(view, event.getX(i), event.getY(i));
                c.sendTouch(MotionEvent.ACTION_UP, event.getPointerId(i), p.x, p.y, remoteWidth, remoteHeight, 0f);
            }
            return true;
        }
        Point p = mapToRemote(view, event.getX(index), event.getY(index));
        c.sendTouch(outAction, event.getPointerId(index), p.x, p.y, remoteWidth, remoteHeight, pressure);
        return true;
    }

    private boolean handleAdbFallbackTouch(View view, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY(); downTime = System.currentTimeMillis(); return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            final Point p1 = mapToRemote(view, downX, downY);
            final Point p2 = mapToRemote(view, event.getX(), event.getY());
            final long duration = Math.max(50, System.currentTimeMillis() - downTime);
            final float dx = event.getX() - downX;
            final float dy = event.getY() - downY;
            runBg(new Runnable() {
                @Override public void run() {
                    if (Math.hypot(dx, dy) < dp(10)) {
                        if (duration >= 500) shellQuick("input swipe " + p2.x + " " + p2.y + " " + p2.x + " " + p2.y + " " + duration);
                        else shellQuick("input tap " + p2.x + " " + p2.y);
                    } else {
                        long swipeDuration = Math.max(80, Math.min(1500, duration));
                        shellQuick("input swipe " + p1.x + " " + p1.y + " " + p2.x + " " + p2.y + " " + swipeDuration);
                    }
                }
            });
            return true;
        }
        return true;
    }

    private Point mapToRemote(View view, float x, float y) {
        int vw = Math.max(1, view.getWidth());
        int vh = Math.max(1, view.getHeight());
        float scale;
        float offX;
        float offY;
        if (fillMode) {
            scale = Math.max(vw / (float) remoteWidth, vh / (float) remoteHeight);
            offX = (vw - remoteWidth * scale) / 2f;
            offY = (vh - remoteHeight * scale) / 2f;
        } else {
            scale = Math.min(vw / (float) remoteWidth, vh / (float) remoteHeight);
            offX = (vw - remoteWidth * scale) / 2f;
            offY = (vh - remoteHeight * scale) / 2f;
        }
        int rx = (int) ((x - offX) / scale);
        int ry = (int) ((y - offY) / scale);
        rx = Math.max(0, Math.min(remoteWidth - 1, rx));
        ry = Math.max(0, Math.min(remoteHeight - 1, ry));
        return new Point(rx, ry);
    }

    private void shellQuick(String cmd) {
        try { adb.shell(cmd, 2500); } catch (Throwable t) { log("命令失败: " + cmd + " / " + t.getMessage()); }
    }

    private void parseRemoteSize(String text) {
        if (text == null) return;
        String[] lines = text.split("\\n");
        for (String line : lines) {
            int idx = line.indexOf(":");
            String part = idx >= 0 ? line.substring(idx + 1).trim() : line.trim();
            if (part.matches(".*[0-9]+x[0-9]+.*")) {
                String[] pieces = part.split("[^0-9x]+");
                for (String p : pieces) {
                    if (p.matches("[0-9]+x[0-9]+")) {
                        String[] wh = p.split("x");
                        try {
                            int w = Integer.parseInt(wh[0]);
                            int h = Integer.parseInt(wh[1]);
                            if (w >= 240 && h >= 240) {
                                remoteWidth = w; remoteHeight = h;
                                log(String.format(Locale.US, "远端分辨率: %dx%d", remoteWidth, remoteHeight));
                                updateStatus(); return;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }
    }

    private void updateVideoSize() {
        VideoProfile p = getVideoProfile();
        streamWidth = p.width; streamHeight = p.height;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (surfaceView != null) {
                    surfaceView.setVideoSize(remoteWidth, remoteHeight);
                    surfaceView.setFillMode(fillMode);
                }
                updateStatus();
            }
        });
    }

    private void toggleFillMode() {
        fillMode = !fillMode;
        updateVideoSize();
        log(fillMode ? "显示模式：铺满/裁切。" : "显示模式：适应/完整显示。 ");
    }

    private void toggleQualityMode() {
        qualityMode = (qualityMode + 1) % 3;
        updateVideoSize();
        log("画质档位：" + getQualityName() + "。正在预览时请停止后重新启动。 ");
    }

    private void swapRemoteSize() {
        int t = remoteWidth; remoteWidth = remoteHeight; remoteHeight = t;
        updateVideoSize();
        log("已交换远端宽高为 " + remoteWidth + "x" + remoteHeight + "。 ");
    }

    private VideoProfile getVideoProfile() {
        if (qualityMode == 2) return new VideoProfile(makeEven(remoteWidth), makeEven(remoteHeight), 0, 12000000);
        int longSide = qualityMode == 0 ? 1280 : 1920;
        int[] wh = scaleToLongSide(remoteWidth, remoteHeight, longSide);
        int br = qualityMode == 0 ? 4000000 : 8000000;
        return new VideoProfile(wh[0], wh[1], longSide, br);
    }

    private String getQualityName() {
        if (qualityMode == 0) return "流畅 max_size=1280 / 4Mbps";
        if (qualityMode == 1) return "清晰 max_size=1920 / 8Mbps";
        return "原生 / 12Mbps";
    }

    private int[] scaleToLongSide(int w, int h, int longSide) {
        int max = Math.max(w, h);
        if (max <= longSide) return new int[] { makeEven(w), makeEven(h) };
        float scale = longSide / (float) max;
        return new int[] { makeEven(Math.round(w * scale)), makeEven(Math.round(h * scale)) };
    }

    private int makeEven(int v) { v = Math.max(2, v); return (v % 2 == 0) ? v : v - 1; }

    private void updateStatus() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (statusView == null) return;
                String adbState = (adb != null && adb.isConnected()) ? "ADB:已连接" : "ADB:未连接";
                String ctrlState = (scrcpyControl != null && scrcpyControl.isReady()) ? "SCRCPY控制:已连接" : "SCRCPY控制:未连接";
                String usbState = selectedDevice == null ? "USB:未选择" : "USB:" + selectedDevice.getDeviceName();
                statusView.setText(usbState + "  |  " + adbState + "  |  " + ctrlState + "  |  远端:" + remoteWidth + "x" + remoteHeight + "  |  " + (fillMode ? "铺满" : "适应"));
            }
        });
    }

    private String encodeInputText(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ' ') sb.append("%s");
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) sb.append(c);
            else if ("_-.,@:/".indexOf(c) >= 0) sb.append('\\').append(c);
            else sb.append(' ');
        }
        return sb.toString().trim().replace(" ", "");
    }

    private void closeAdb() {
        AdbConnection c = adb; adb = null;
        if (c != null) c.close();
    }

    private void runBg(final Runnable runnable) {
        new Thread(new Runnable() {
            @Override public void run() {
                try { runnable.run(); } catch (Throwable t) { log("后台错误: " + t.getMessage()); }
            }
        }).start();
    }

    private void log(final String text) {
        runOnUiThread(new Runnable() { @Override public void run() { if (logView != null) logView.append(text + "\n"); } });
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private static final class Point {
        final int x, y;
        Point(int x, int y) { this.x = x; this.y = y; }
    }

    private static final class VideoProfile {
        final int width, height, maxSize, bitRate;
        VideoProfile(int width, int height, int maxSize, int bitRate) {
            this.width = width; this.height = height; this.maxSize = maxSize; this.bitRate = bitRate;
        }
    }
}
