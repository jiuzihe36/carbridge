# STATUS — 证据状态

> 规则见 [`AGENT.md`](../AGENT.md)。`CONFIRMED` 才能当作协议真相；`OPEN` 禁止凭经验补全。
> 证据类型：**实车**（用户车机，按 README 流程）/ **假车机**（tools/fakecar）/ **单测**（tools/carbridge-tests）/ **规范**。
> 状态变化时与代码同一提交更新本文件。

最后更新：2026-11-18

## 链路阶段

| 阶段 | 状态 | 证据 | 备注 |
|---|---|---|---|
| 蓝牙 SDP 信标（`2d8d2466` / `deca-fade` / `cade-defa` / `02030302`） | CONFIRMED | 实车 | 车机列表出现「(支持无线CarPlay连接)」；信标服务每 10s 自检重挂 |
| EIR 广播（Apple Device IE） | CONFIRMED | 实车 | 供车机在配对前识别身份 |
| iAP2 链路分帧 / 重同步（SYN/SYNACK/ACK） | CONFIRMED | 单测 + 假车机 | `Iap2LinkEngine` 单测覆盖半包、坏帧、重同步 |
| Identification（`0x1D01`） | CONFIRMED | 假车机 | 假车机可完整走通会话 |
| MFi 鉴权 — 宽松模式（默认） | CONFIRMED | 实车 + 假车机 | 车机 `CONF_AUTHENTICATION=false` 时也能通；有「探测性验签」写日志 |
| MFi 鉴权 — 严格验签模式 | INFERRED | 单测 + 假车机 RSA | 代码有开关且假车机可跑通真 RSA；**尚无要求严格签名的实车验证** |
| 无线参数 `0x5703` / `0x4300` / `0x4301` | CONFIRMED | 实车 | `0x5703` 缺席时用 EIR 兜底入网，再发 `0x4300` 让车机在 `0x4301` 回 AP 信息 |
| Wi-Fi 入网（热点加入 + BSSID 对账） | CONFIRMED | 实车 | 地址来源会进会话日志 |
| mDNS 端点发现 | CONFIRMED | 实车 | 车机提供 AirPlay 端点 |
| AirPlay pair-setup / pair-verify | CONFIRMED | 实车 | 含 SRP / Ed25519 / X25519 |
| RTSP SETUP（视频 / 音频 / 触摸） | CONFIRMED | 实车 | RECORD 后进入推流与心跳 |
| 视频：Car+ H.264 Annex-B 零转码转发 | CONFIRMED | 实车 | 不二次编码；分辨率/帧率优先取车机上报值 |
| 触摸回传（AirPlay HID → CarLife touch） | CONFIRMED | 实车 | 含车机硬键映射 |
| 音频：媒体通道 | CONFIRMED | 实车 | 音频路由与采集在 `carplay/audio` |
| 音频：通话 | INFERRED | 实车观察 | 平台限制：第三方 App 无法静音通话流，已改为 **HFP 优先**；不同车机的最终效果仍需逐台确认 |
| 帧率 / 分辨率自适应 | INFERRED | 实车 + 单测 | 判据为「不低于目标帧率」；实测 Car+ 交付上限约 30fps，上限放开到 120 |
| 车机一键解锁（ADB 写 `persist.sys.wirelesscarplay.address`） | CONFIRMED | 实车 | 扫网段 → 内置 adb → 写属性回读 → 重启车机 CarPlay 应用 |
| 假车机（`tools/fakecar`） | CONFIRMED | 单测 + 冒烟 | 便携假车机，联调与回归用 |
| 原生 CarPlay 采集对照（`tools/carplay-native`、`carprobe`） | INFERRED | 本地工具 | 用于对照分析，不作为协议真相 |

## 开放项（OPEN）

| 事项 | 为什么 OPEN | 什么能关闭它 |
|---|---|---|
| USB 有线 CarPlay | 未实现，只做无线 | 有线链路抓包 + 实车验证 |
| 要求严格 MFi 签名的车机 | 未遇到实车样本 | 一台该类车机的完整会话日志（含鉴权失败点） |
| 多协议：HiCar / ICCOA / CarWith | 未实现，仅保留扩展方向 | 对应协议证据链 + 独立模块接入 |
| 通话音频在更多车机上的表现 | 平台限制 + 车机差异 | 逐台实车记录（`PASS` / `PARTIAL` + 第一断点） |
| 车机旧会话残留导致的「连上但不出画面」 | 有现象、缺系统性证据 | 复现步骤 + 车机侧日志 + 断开/重连代际隔离验证 |

## 已知平台约束（不是 bug）

- 第三方 App 无法静音通话流 → 通话走 HFP 优先。
- 车机只在**配对那一刻**读一次 SDP，之后吃缓存 → 换机/改版本要按 README 顺序重配对。
- 同时存在两个 CarLife 客户端会让 Car+ 接受连接但不派发协议帧 → `HuClient` 进程级单例互斥。
