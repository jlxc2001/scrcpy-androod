# AndroidOTGScrcpyDemo

一台安卓设备通过 OTG 控制另一台安卓设备的第一版 Demo。

当前版本定位：**USB ADB Host + screenrecord H.264 预览 + ADB input 控制验证版**。

> 这不是完整 PC 版 scrcpy 的直接移植。它先验证最关键链路：控制端 Android APK 能不能通过 OTG 与被控端 adbd 通信、执行 shell、拉取 H.264 画面并发送触控命令。后续可把 `screenrecord` 预览替换为真正的 `scrcpy-server` 视频流。

## 功能

- 控制端支持 Android 6.0+，`minSdk 23`
- 通过 OTG 枚举被控端 ADB USB interface
- 请求 USB Host 权限
- 生成并保存 ADB RSA key
- 支持被控端弹出“允许 USB 调试”授权
- ADB Shell 测试：读取机型、系统版本、分辨率、DPI
- 有线预览：通过 `screenrecord --output-format=h264 -` 获取 H.264 画面并用 `MediaCodec` 解码显示
- 基础控制：点击、滑动、返回、HOME、最近任务

## 使用方法

1. 在被控端手机打开：开发者选项 → USB 调试。
2. 控制端手机安装本 APK。
3. 用 OTG 线连接两台安卓设备。控制端必须处于 USB Host 角色。
4. 打开 APK，点击“刷新 USB”。
5. 点击“连接 ADB”。
6. 被控端如果弹出 USB 调试授权，勾选允许并确认。
7. 重新点击“连接 ADB”。
8. 点击“读取设备信息”，确认链路可用。
9. 点击“开始预览”。

## 注意事项

- USB-C 对 USB-C 连接时，部分手机会抢主从角色。连接不成功时，建议用 OTG 转接头或带供电的 OTG Hub。
- 被控端必须手动开启 USB 调试，普通 APK 不能静默开启 USB 调试。
- 第一版预览使用 Android 系统自带 `screenrecord`，不是完整 scrcpy 协议，所以延迟、时长和兼容性不如真正 scrcpy。
- 部分系统的 `screenrecord` 会有 180 秒限制，代码已做断开后自动重连。
- 触控目前通过 ADB shell 的 `input tap/swipe/keyevent` 实现，连续拖动不如 scrcpy 原生控制协议顺滑。

## 后续真正 scrcpy 化的接口位置

- ADB 协议层：`app/src/main/java/com/jlxc/androidotgscrcpy/adb/`
- 视频预览层：`app/src/main/java/com/jlxc/androidotgscrcpy/video/ScreenRecordStreamer.java`
- 后续替换点：把 `ScreenRecordStreamer.openScreenRecordStream()` 换成：
  1. push `scrcpy-server.jar` 到 `/data/local/tmp/`
  2. `app_process` 启动 server
  3. ADB 打开 `localabstract:scrcpy` socket
  4. 按 scrcpy 协议读取视频流和控制 socket

## GitHub Actions 打包

仓库已包含 `.github/workflows/android.yml`。

上传到 GitHub 后，进入 Actions 手动运行 `Android CI`，即可在 artifacts 下载 debug APK。
