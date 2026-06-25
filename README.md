# Android OTG Scrcpy Demo V2.1 Video Fix

V2.1 是针对“触控和按钮可用，但是没有画面”的修正版。

本版仍然使用 Android 控制端通过 OTG 作为 ADB Host 连接被控端，并使用：

- `screenrecord --output-format=h264 -` 获取 H.264 视频流
- `MediaCodec` 在控制端硬解显示
- `input tap/swipe/keyevent` 发送触控和按钮命令

## V2.1 修改点

- 视频解码器不再一开始就启动，而是等待 H.264 SPS/PPS 到达后再启动 MediaCodec。
- 将 SPS/PPS 作为 `csd-0` / `csd-1` 传给 MediaCodec，解决部分设备黑屏但有数据的问题。
- 增加视频流日志：是否收到 video bytes、NAL 数、SPS、PPS、decoder 启动状态。
- 保留 V2 的触控坐标映射、画质档位、适应/铺满、按钮控制等功能。

## 测试重点

如果仍然没有画面，请看 App 下方日志：

- 如果一直显示 `No video bytes yet`：说明被控端的 `screenrecord --output-format=h264 -` 没有输出视频流，后续需要换真正 scrcpy-server。
- 如果有 `Video data: xxx bytes` 但一直 `waiting SPS/PPS`：说明视频流被 shell/PTY 或设备实现影响，H.264 起始码不正常。
- 如果出现 `MediaCodec AVC decoder started after SPS/PPS` 但画面仍黑：说明解码器或 Surface 输出兼容性问题。

