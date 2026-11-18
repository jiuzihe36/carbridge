# carbridge 模块地图

> 目标：**改一个功能只需要动一个文件**。这份文档就是“改哪里”的索引。
> 每个类只负责一件事；跨类调用一律通过接口，不直接摸对方的字段。

## 一、按功能找类

| 我要改… | 改这个文件 | 不要碰 |
|---|---|---|
| 界面长什么样、颜色、间距 | `app/res/layout/activity_main.xml`、`app/res/values/*.xml` | 任何 `.java` |
| 状态文字/圆点颜色/什么时候清进度 | `app/StatusView.java`（Kind 枚举里就是规则） | `MainActivity.onState` 的渲染部分 |
| 进度链每步显示 ✓/●/○/✕ | `app/StepChain.java` | `MainActivity.renderSteps` |
| 实时指标（fps/分辨率/码率/详情行）的格式 | `app/MetricsText.java` | `MainActivity.updateMetrics` 的填控件部分 |
| 画质的上下限、档位、参数计算 | `app/QualityPrefs.java` | 画质对话框 |
| 本机身份四行显示什么 | `app/IdentitySection.java` | 身份对话框（在 MainActivity） |
| 日志的内存/文件上限、裁剪策略 | `app/SessionLog.java` | `MainActivity.log()`（它只做转发） |
| 蓝牙 SDP 信标（四条记录、监听） | `carplay/bt/CarPlaySdpBeacon.java` | — |
| 蓝牙地址从哪来（getAddress/settings/root/手填） | `carplay/bt/BtIdentity.java` | — |
| iAP2 报文顺序、0x5703/0x4300/0x4301 | `carplay/iap2/Iap2SenderSession.java` | `Iap2LinkPacket`（链路层） |
| iAP2 链路层（FF5A 头、seq/ack、校验和） | `carplay/iap2/Iap2LinkPacket.java` | `Iap2SenderSession`（业务层） |
| MFi 认证（证书解析、挑战、验签） | `carplay/iap2/MfiCrypto.java` + `Iap2SenderSession.onAuthResponse` | — |
| CSM 帧与参数 TLV | `carplay/iap2/Iap2Csm.java` | — |
| AirPlay 会话（pair-setup/SETUP/推流） | `carplay/core/AirPlaySession.java` | — |
| AirPlay 的固定身份（model/os/srcvers） | `carplay/core/AirPlaySetup.java` | — |
| ctrl-int 邀请服务（车机反连的 7100） | `carplay/net/CtrlIntServer.java` | — |
| mDNS 广播（`_carplay-ctrl` / TXT） | `carplay/net/MdnsCarPlay.java` | — |
| 加入车机热点 | `carplay/net/WifiJoiner.java` | — |
| 热点 Wi-Fi MAC 解析 | `carplay/net/WifiMacResolver.java` | — |
| Car+（CarLife）通道、编码参数协商 | `app/HuClient.java` | — |
| 屏幕合成/编码参数的画布适配 | `carplay/core/DisplayGeometry.java` | — |
| 分辨率自适应的决策 | `carplay/core/AdaptiveResolution.java` | — |
| 多车机配置存取 | `carplay/CarProfileStore.java` | — |
| 首次使用向导 | `app/GuideWizardActivity.java` + `res/layout/activity_guide.xml` | `MainActivity`（只在首启时拉起它） |
| 线程池 | `carplay/util/Bg.java` | 各处 `new Thread` 已收敛到这里 |
| 被 catch 吞掉的异常 | `carplay/util/Diag.java` | 各 catch 块里的 `Diag.w(tag, t)` |

## 二、依赖方向（只能从上往下）

```
Activity / 界面层
   ├─ app/*（StatusView / StepChain / MetricsText / QualityPrefs / IdentitySection / SessionLog）
   ├─ carplay/CarPlayWirelessTx（编排：蓝牙 → iAP2 → AirPlay）
   └─ app/HuClient（Car+ 通道）
          ↓
carplay/iap2、carplay/core、carplay/net、carplay/bt（协议与设备）
          ↓
carplay/util（Bg / Diag）——任何层都可用，它不反向依赖
```

**反向依赖是禁止的**：协议层不许 `import com.carbridge.app.*`。要通知界面，用回调接口
（例如 `Iap2SenderSession.Listener`、`CarPlayWirelessTx.Listener`）。

## 三、改动前的固定动作

```bash
# 1. 纯逻辑单测（秒级，101 项）
cd tools/carbridge-tests && ./run.sh

# 2. 协议端到端（假车机 ↔ 发送端，25 项断言）
cd tools/fakecar-jvm && ./build.sh && ./run.sh

# 3. 出包 + 装机
cd ~/carbridge && export JAVA_HOME=~/项目/资料/toolchain/jdk-current/Contents/Home && ./android/build.sh
adb -t <transport_id> install -r android/build/carbridge-0.1.0.apk
```

改协议/认证 → 必须跑 1 和 2；只改界面 → 跑 1 即可。

## 四、仍然“缠在一起”的三处（下次要动的目标）

| 位置 | 为什么还没拆 | 拆之前要先做什么 |
|---|---|---|
| `MainActivity.onState()` | 同时做：计时打点、状态映射、按钮切换、自动重连调度 | 状态映射已抽到 `StatusView`，剩下的是副作用编排，可以先给“重连调度”抽类 |
| `HuClient` 会话循环（2100 行） | Car+ 通道的时序全在里面，没有实车日志对照，改错只能靠现象发现 | 先给 `Fault`/状态转换补 JVM 测试，再抽 Controller |
| `MainActivity` 的对话框族（MAC/广播版本/画质） | 都是 Android UI，抽出去要传一堆控件 | 低优先级；它们本身就是“界面”，留着不算债 |

## 五、命名与注释约定

- 类名用名词（`SessionLog` / `StepChain`），不出现 `Helper`、`Util` 这种万能后缀（`Bg`、`Diag` 例外，它们是纯工具）。
- 注释解释**为什么**，不解释“这行在做什么”。历史 bug 的修法要在注释里写明“以前是什么样、会出什么问题”。
- 新增纯计算逻辑 → 必须进 `tools/carbridge-tests`，并在 `run.sh` 的 sources 里登记。
