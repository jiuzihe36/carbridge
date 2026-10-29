# fakecar —— 便携假车机

把一台 Android 手机变成「车机」，用来联调 carbridge 的无线 CarPlay 发送端。

## 做什么

在配件侧复刻车机行为，跑通并打印整条链路：

```
SDP 认 2d8d2466 → 连 iAP2 通道 → 握手(DETECT/SYN) → 设备识别(0x1D00/0x1D01)
→ MFi(0xAA00~0xAA05) → 问候(0x4E0D) → WiFi 参数(0x5703) → CarPlay 启动(0x4300/0x4301)
→ AirPlay pair-verify(M1~M4) → ANNOUNCE / SETUP / /info → 屏幕流(110) → 音频流(100)
```

## 构建

```bash
./tools/fakecar/build.sh
# 产物：tools/fakecar/build/fakecar-0.1.0.apk
```

## 使用

1. 两台手机先在系统蓝牙里配对（一次即可）。
2. 目标手机打开 carbridge，确认 SDP 信标已注册。
3. 假车机打开 fakecar，点「自动找手机并连接」。
4. 在日志里依次看到：识别 → iAP2 → 鉴权 → 问候 → 0x5703 → 0x4301 → AirPlay → 首帧。
5. 可选：填目标手机 IP，让 ctrl-int 邀请也走通（不影响主链路）。

## 说明

`iap2/`、`core/`、`pairing/` 与主应用共用同一套纯 Java 协议实现；
`AirPlayServer` 与 `Iap2AccessorySession` 是配件侧的实现，可在 JVM 上直接跑冒烟测试。
