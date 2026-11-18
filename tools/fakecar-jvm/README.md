# 假车机测试台（JVM）

在 Mac 上把**车机侧的 iAP2 行为**跑起来，对着**产品代码里的发送端**（`Iap2SenderSession`）做逐字节断言。
不需要第二台手机、不需要蓝牙、不需要上车。

## 为什么这么做

真车验证一次成本极高（要上车、要配对、要看车机屏），而每次改协议都想验证。这个测试台把
「车机怎么回」写成可编程的画像（`FakeCar.Profile`），把「我们发了什么」变成断言，几秒钟跑完。

**它已经抓到过真 bug**：车机发 RST 拆链后，我们的会话状态仍停在 READY（上层以为还连着），
根因是链路层 CLOSED 没有传到会话层、且后续在途消息把状态又推回 READY。

## 跑

```bash
export JAVA_HOME=~/项目/资料/toolchain/jdk-current/Contents/Home
./tools/fakecar-jvm/build.sh
./tools/fakecar-jvm/run.sh          # 失败时打印链路跟踪与发送端日志
FAKECAR_DEBUG=1 ./tools/fakecar-jvm/run.sh   # 打印每个包的收发
```

退出码：0 = 全过；1 = 有失败（CI 可用）。

## 现有场景

| 场景 | 车机画像 | 断言 |
|---|---|---|
| 标准车机 | 清单完整、热点开、回 0x4301 | 五条设备问候都发、0x4300 只发一次且带真实 MAC、0x5702 发出 |
| 奥迪形态 | 可收清单只有 5 项（不含 0x4E0D/0x4E0B） | 清单没声明也照发五条问候（真 iPhone 行为） |
| 车机热点没开 | 不回 0x5703 | 超时兜底改发 0x4300，且只发一次 |
| 新势力形态 | 只收 0x4300 / 只发 0x4301 | 不等 0x5703 就提前发 0x4300 |
| 重复触发 | 超时两次 | 0x4300 仍然只发一次（闸门） |
| 拒绝识别 | 回 0x1D03 | 会话进 ERROR，不卡死 |
| 中途拆链 | 收到 0x5702 后发 RST | 会话进 CLOSED，且不会被后续消息推回 READY |
| 0x4301 先到 | 0x5703 后立刻发 0x4301 | 不会进 ERROR |

## 加一个场景

1. 在 `Harness.main()` 里加一段 `scenario("名字", profile, body)`；
2. profile 用 `FakeCar.Profile` 的字段描述车机（清单、是否回 0x5703/0x4301、要不要 RST……）；
3. body 里 `waitFor(car, 0x4300, 2000)` 等条件，然后 `r.check(...)` 断言；
4. `car.receivedIds` / `car.receivedBodies` 是车机实际收到的全部消息，`car.trace()` 是链路级跟踪。

## 边界

- 只覆盖 **iAP2 控制链路**（识别 / MFi / 问候 / 0x5703 / 0x4300 / 0x4301 / 拆链）。
- **不覆盖 AirPlay 媒体层**（pair-setup、SETUP、屏幕流）——那部分要真车或另一台接收端。
- 管道假设「一次 send = 一个完整包」（产品代码的 `sendRaw` 就是这么写的），不做 RFCOMM 流式重组。
- 不验证 MFi 签名（产品默认宽松模式）；`setVerifyMfiSignature(true)` 时需要真实证书链。
