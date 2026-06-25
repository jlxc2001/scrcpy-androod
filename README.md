# Android OTG Scrcpy Demo V2

这是一个 Android 6.0+ 控制端 APK Demo，用一台安卓设备通过 OTG 连接另一台开启 USB 调试的安卓设备，实现有线 ADB Host 控制与 scrcpy-like 预览。

> 这不是完整 scrcpy 协议移植版。V2 仍然使用 `screenrecord --output-format=h264 -` 拉取 H.264 画面，用 `MediaCodec` 在控制端硬解显示；触控使用 ADB `input` 命令。这样便于先验证 Android-to-Android 有线控制链路。

## V2 更新

- 修正预览缩放后的触控坐标映射。
- 新增“适应 / 铺满”显示模式。
- 新增“流畅 / 清晰 / 原生”画质档位：
  - 流畅：长边 1280，约 4Mbps。
  - 清晰：长边 1920，约 8Mbps。
  - 原生：不传 `--size`，约 12Mbps。
- 新增“交换宽高”，用于被控端横竖屏识别不准时手动修正。
- 新增电源键、音量键、文本输入。
- 改进 ScreenRecordStreamer 停止逻辑，停止预览时会主动关闭当前 ADB stream。
- 保留 V1 的 OTG USB 枚举、ADB RSA 授权、设备信息读取、返回/Home/最近任务。

## 使用方法

1. 被控端打开开发者选项与 USB 调试。
2. 控制端安装 APK。
3. 用 OTG 线连接两台设备，确保控制端处于 USB Host 角色。
4. 打开 APK，点“刷新 USB”。
5. 点“连接 ADB”。
6. 被控端弹出“是否允许 USB 调试”后点允许。
7. 回到控制端再次点“连接 ADB”。
8. 点“读取设备信息”。
9. 点“开始预览”。

## 触控说明

- 轻触：`input tap x y`
- 拖动：`input swipe x1 y1 x2 y2 duration`
- 长按：`input swipe x y x y duration`

如果点击位置偏移：

1. 先确认显示模式是“适应”。
2. 如果画面方向与坐标方向不一致，点“交换宽高”。
3. 停止预览后重新开始预览。

## GitHub Actions

workflow 文件路径：

```text
.github/workflows/android.yml
```

如果 GitHub 网页创建 workflow，请在仓库里新建文件：

```text
.github/workflows/android.yml
```

内容可以使用本项目压缩包中的同名文件。

## 已知限制

- `screenrecord` 方案延迟高于真正 scrcpy-server。
- `input text` 对中文、日文等复杂文本不稳定，V2 主要适合英文、数字和简单符号。
- 多点触控暂未实现，因为 ADB `input` 命令本身能力有限。
- 部分被控端系统会限制或修改 `screenrecord` 输出能力。

## 后续方向

- 使用真正 scrcpy-server 代替 `screenrecord`。
- 使用 scrcpy control socket 代替 ADB `input` 命令。
- 加入自动重连、设备授权状态提示、横竖屏自动检测。
