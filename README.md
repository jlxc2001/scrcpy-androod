# AndroidOTGScrcpyDemo V3 true scrcpy-server

这是 Android 控制端 APK，通过 OTG 连接另一台开启 USB 调试的安卓设备。

V3 相比 V2 的核心变化：

- 不再使用 `screenrecord --output-format=h264 -` 作为主视频方案。
- GitHub Actions 构建时自动下载官方 `scrcpy-server-v4.0` 到 APK assets。
- APK 运行时通过自写 ADB Host 把 `scrcpy-server-v4.0` push 到被控端 `/data/local/tmp/scrcpy-server.jar`。
- 通过 `app_process` 启动 `com.genymobile.scrcpy.Server 4.0`。
- 通过 `localabstract:scrcpy_<scid>` 连接 true scrcpy video socket。
- 视频流使用 `raw_stream=true`，控制端直接用 `MediaCodec` 解码 H.264。
- 触控优先使用 scrcpy control socket，支持多指 MotionEvent 基础转发。
- 返回/Home/最近任务/音量/文本发送优先使用 scrcpy control socket；control 未连接时回退到 adb shell input。
- 增加熄屏投屏/亮屏/旋转按钮。

## 版本说明

- 控制端 APK：Android 6.0+。
- 被控端：需要 USB 调试授权。
- 被控端视频：scrcpy 官方要求 Android 5.0+。
- 音频同步：本 V3.0 暂未接入 audio socket；后续 V3.2 可加。scrcpy 官方音频要求 Android 11+，Android 12+ 更稳定。

## GitHub Actions

workflow 路径：

```text
.github/workflows/android.yml
```

它会下载：

```text
https://github.com/Genymobile/scrcpy/releases/download/v4.0/scrcpy-server-v4.0
```

并校验 SHA-256：

```text
84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a
```

## 本地构建

如果你不用 GitHub Actions，而是在本地构建，需要手动下载官方 server 文件：

```text
app/src/main/assets/scrcpy-server-v4.0
```

否则 APK 能编译，但运行时会提示缺少 scrcpy-server asset。

## 使用顺序

1. 被控端打开开发者选项和 USB 调试。
2. 控制端安装 APK。
3. OTG 线连接两台设备，注意控制端必须是 USB Host。
4. 点“刷新 USB”。
5. 点“连接 ADB”。
6. 被控端弹出 USB 调试授权时点允许。
7. 回控制端再点一次“连接 ADB”。
8. 点“读取设备信息”。
9. 点“启动真 scrcpy”。

## 当前限制

V3.0 先实现 true scrcpy-server 视频和 control socket。音频 socket 尚未接入，因此没有音频同步。

如果视频 socket 已连接，但没有画面，请看日志中是否收到：

```text
scrcpy H.264 SPS received
scrcpy H.264 PPS received
MediaCodec AVC decoder started for true scrcpy raw stream
```

如果 scrcpy control socket 未连接，按钮和触控会自动回退到 ADB shell input。
