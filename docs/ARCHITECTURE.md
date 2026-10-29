# 架构

## 模块

```
com.carbridge.app
  MainActivity        界面、连接编排、日志与诊断
  HuClient            CarLife(HU) 客户端：通道协商、视频/音频接收、触摸回传
  HuProto             车机侧协议编解码
  CarPairing          已配对车机的识别与解绑
  SdpBeaconService    CarPlay SDP 信标的常驻前台服务
  KeepAliveService    投屏期间的进程保活

com.carbridge.carplay
  CarPlayWirelessTx   发送端状态机（蓝牙 → iAP2 → AirPlay → 推流）
  bt/                 SDP 信标、iAP2 RFCOMM 服务、EIR 扫描
  iap2/               iAP2 链路、会话、识别与无线参数
  pairing/            AirPlay pair-setup / pair-verify / SRP / Ed25519 / X25519
  core/               AirPlay HTTP 会话、加密、HID、显示与时序
  net/                mDNS 注册、WiFi 加入
  bridge/             CarLife 码流 → AirPlay 通道

com.carbridge.mediacore
  AAC-LC 编码与自检
```

## 会话流程

```
1. 蓝牙配对
   手机注册 SDP：2d8d2466 / deca-fade / cade-defa / 02030302
   车机配对时读取，记住这台“iPhone”

2. iAP2
   车机回连 RFCOMM → 识别（0x1D01）→ 鉴权（MFi）→ 无线参数（0x5703/0x4301）

3. AirPlay
   mDNS 发现车机端点 → pair-verify → SETUP（视频/音频/触摸）
   → RECORD → 心跳

4. 视频
   Car+ 输出 H.264 Annex-B → 按帧封装进 AirPlay 视频通道（零转码）
   AirPlay HID → CarLife touch 通道
```

## 关键约定

- **蓝牙信标必须常驻**：车机只在配对时读一次 SDP，记录消失就再也认不出。
  服务每 10 秒自检，线程死亡或记录不全时自动重挂。
- **发送端单实例**：同时存在两个 CarLife 客户端会让 Car+ 接受连接但不派发协议帧，
  表现为永久停在“握手协商”。`HuClient` 用进程级单例互斥。
- **参数从对端解析**：分辨率、帧率、音频格式优先取车机 `/info` 与 iAP2 上报值，
  `CarProfile` 只是兜底。
- **MFi 可配置**：`MfiCrypto` 默认宽松模式；车机要求严格签名时改为校验模式。
