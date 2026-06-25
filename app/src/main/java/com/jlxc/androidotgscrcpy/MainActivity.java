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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jlxc.androidotgscrcpy.adb.AdbConnection;
import com.jlxc.androidotgscrcpy.adb.AdbCrypto;
import com.jlxc.androidotgscrcpy.adb.UsbAdbDevice;
import com.jlxc.androidotgscrcpy.video.ScreenRecordStreamer;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.jlxc.androidotgscrcpy.USB_PERMISSION";

    private UsbManager usbManager;
    private UsbDevice selectedDevice;
    private AdbConnection adb;
    private ScreenRecordStreamer streamer;

    private TextView logView;
    private TextView titleView;
    private SurfaceView surfaceView;
    private int remoteWidth = 1080;
    private int remoteHeight = 1920;
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
        titleView.setText("OTG Scrcpy Demo / ADB Host");
        titleView.setTextColor(Color.rgb(57, 197, 187));
        titleView.setTextSize(18);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(titleView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        surfaceView = new SurfaceView(this);
        surfaceView.setBackgroundColor(Color.BLACK);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { surfaceReady = true; }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; stopPreview(); }
        });
        surfaceView.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) { return handleRemoteTouch(v, event); }
        });
        left.addView(surfaceView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(8), dp(12), dp(12), dp(12));
        root.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3));

        addButton(right, "刷新 USB", new View.OnClickListener() { @Override public void onClick(View v) { refreshUsbDevices(); } });
        addButton(right, "连接 ADB", new View.OnClickListener() { @Override public void onClick(View v) { connectAdb(); } });
        addButton(right, "读取设备信息", new View.OnClickListener() { @Override public void onClick(View v) { readDeviceInfo(); } });
        addButton(right, "开始预览", new View.OnClickListener() { @Override public void onClick(View v) { startPreview(); } });
        addButton(right, "停止预览", new View.OnClickListener() { @Override public void onClick(View v) { stopPreview(); } });

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        right.addView(nav, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        addButton(nav, "返回", new View.OnClickListener() { @Override public void onClick(View v) { keyEvent(4); } }, 1);
        addButton(nav, "HOME", new View.OnClickListener() { @Override public void onClick(View v) { keyEvent(3); } }, 1);
        addButton(nav, "最近", new View.OnClickListener() { @Override public void onClick(View v) { keyEvent(187); } }, 1);

        logView = new TextView(this);
        logView.setTextColor(Color.rgb(210, 230, 230));
        logView.setTextSize(12);
        logView.setText("准备就绪。\n");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        right.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void addButton(LinearLayout parent, String text, View.OnClickListener listener) {
        addButton(parent, text, listener, 0);
    }

    private void addButton(LinearLayout parent, String text, View.OnClickListener listener, int weight) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
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
        if (selectedDevice == null) {
            log("未发现 ADB 接口。请确认：被控端已开启 USB 调试，并且 OTG 主从方向正确。");
        } else {
            log("已选择: " + selectedDevice.getDeviceName());
        }
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
                    log("连接成功，可以读取设备信息或开始预览。首次连接如果被控端弹授权，请点允许后再点一次连接。 ");
                } catch (Throwable t) {
                    log("连接失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    closeAdb();
                }
            }
        });
    }

    private void readDeviceInfo() {
        if (!ensureAdb()) return;
        runBg(new Runnable() {
            @Override public void run() {
                try {
                    String out = adb.shell("getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; wm size; wm density", 6000);
                    log("设备信息:\n" + out.trim());
                    parseRemoteSize(out);
                } catch (Throwable t) {
                    log("读取失败: " + t.getMessage());
                }
            }
        });
    }

    private void startPreview() {
        if (!ensureAdb()) return;
        if (!surfaceReady) {
            log("Surface 未就绪，稍后再试。 ");
            return;
        }
        stopPreview();
        runBg(new Runnable() {
            @Override public void run() {
                try {
                    String sizeOut = adb.shell("wm size", 3000);
                    parseRemoteSize(sizeOut);
                } catch (Throwable ignored) {}
                streamer = new ScreenRecordStreamer(adb, surfaceView.getHolder().getSurface(), remoteWidth, remoteHeight, new ScreenRecordStreamer.LogSink() {
                    @Override public void log(String message) { MainActivity.this.log(message); }
                });
                streamer.start();
            }
        });
    }

    private void stopPreview() {
        ScreenRecordStreamer s = streamer;
        streamer = null;
        if (s != null) {
            s.stop();
            log("预览已停止。 ");
        }
    }

    private boolean ensureAdb() {
        if (adb == null || !adb.isConnected()) {
            log("ADB 未连接。先点“连接 ADB”。");
            return false;
        }
        return true;
    }

    private void keyEvent(final int keyCode) {
        if (!ensureAdb()) return;
        runBg(new Runnable() { @Override public void run() { shellQuick("input keyevent " + keyCode); } });
    }

    private boolean handleRemoteTouch(View view, MotionEvent event) {
        if (adb == null || !adb.isConnected()) return true;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            downTime = System.currentTimeMillis();
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            final Point p1 = mapToRemote(view, downX, downY);
            final Point p2 = mapToRemote(view, event.getX(), event.getY());
            final long duration = Math.max(50, System.currentTimeMillis() - downTime);
            final float dx = event.getX() - downX;
            final float dy = event.getY() - downY;
            runBg(new Runnable() {
                @Override public void run() {
                    if (Math.hypot(dx, dy) < dp(10) && duration < 300) {
                        shellQuick("input tap " + p2.x + " " + p2.y);
                    } else {
                        shellQuick("input swipe " + p1.x + " " + p1.y + " " + p2.x + " " + p2.y + " " + duration);
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
        float viewRatio = vw / (float) vh;
        float remoteRatio = remoteWidth / (float) remoteHeight;
        float drawW, drawH, offX, offY;
        if (viewRatio > remoteRatio) {
            drawH = vh;
            drawW = drawH * remoteRatio;
            offX = (vw - drawW) / 2f;
            offY = 0;
        } else {
            drawW = vw;
            drawH = drawW / remoteRatio;
            offX = 0;
            offY = (vh - drawH) / 2f;
        }
        int rx = (int) ((x - offX) / drawW * remoteWidth);
        int ry = (int) ((y - offY) / drawH * remoteHeight);
        rx = Math.max(0, Math.min(remoteWidth - 1, rx));
        ry = Math.max(0, Math.min(remoteHeight - 1, ry));
        return new Point(rx, ry);
    }

    private void shellQuick(String cmd) {
        try {
            adb.shell(cmd, 2500);
        } catch (Throwable t) {
            log("命令失败: " + cmd + " / " + t.getMessage());
        }
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
                            remoteWidth = Integer.parseInt(wh[0]);
                            remoteHeight = Integer.parseInt(wh[1]);
                            log(String.format(Locale.US, "远端分辨率: %dx%d", remoteWidth, remoteHeight));
                            return;
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }
    }

    private void closeAdb() {
        AdbConnection c = adb;
        adb = null;
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
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (logView != null) logView.append(text + "\n");
            }
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class Point {
        final int x;
        final int y;
        Point(int x, int y) { this.x = x; this.y = y; }
    }
}
