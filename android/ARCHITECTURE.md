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
| MAC 文本规范化/校验（含严格 vs 宽松两种强度） | `carplay/util/MacText.java` | 别在别处再写一套 |
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
| 通话音频采集（telephony 流） | `carplay/audio/CallAudioCapture.java` | — |
| 通话检测（音频模式监听） | `carplay/audio/PhoneCallWatcher.java` | — |
| 通话/铃声的音频路由与音量 | `carplay/audio/CarAudioRouter.java` | — |
| 0x5703 热点凭据解析（多布局打分） | `carplay/iap2/WifiConfigParser.java` | — |
| MAC 文本规范化（严格/宽松两档） | `carplay/util/MacText.java` | — |
| 蓝牙适配器统一入口（Manager/兼容） | `carplay/bt/Bt.java` | — |
| 日志缓冲与落盘 | `app/SessionLog.java` | `MainActivity.log()`（只转发） |
| 进度链判定 / 状态→显示规则 / 指标格式 | `app/StepChain.java` `StatusView.java` `MetricsText.java` | — |
| 画质参数（上下限/档位/参数计算） | `app/QualityPrefs.java` | 画质对话框 |
| 本机身份四行（名称/地址/识别/广播版本） | `app/IdentitySection.java` | 身份对话框 |
| 多车机配置归档（按车机 IP） | `carplay/CarProfileStore.java` | — |
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

# 4. 端到端冒烟（装机 → 起假车机 → 自动连 → 断言五条关键日志）
./tools/smoke.sh                 # 全流程
SKIP_BUILD=1 SKIP_INSTALL=1 ./tools/smoke.sh   # 只跑链路
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

## 六、踩过的坑（改之前先看这一节）

| 现象 | 根因 | 现在怎么处理 |
|---|---|---|
| 锁屏后连不上 / 假车机不能触摸 | 唤醒 Car+ 用的是 `startActivity`，锁屏下被系统拒绝（后台启动限制） | 先 `startService`（`com.oplus.ocar.action.START_CARLIFE_SERVICE`），锁屏时跳过 Activity |
| 用一会儿自己掉线，之后疯狂重连 | CarLife 的**空帧（len=0）被当成致命错误**，`closeAll()` 把会话拆了 | 空帧只计数跳过，只有非法长度才重启 |
| 帧率上不去（一直 30） | Car+ 自己的「帧率设置」档位；另外我们的判据是 `gotFps == fps`，会给 60 主动降回 30 | 判据改成「不低于目标」，采纳更高值；60 档在系统设置里 |
| 导航语音没有声音 | 9241/9242 原来只读排空不转发 | 混音进同一条音频流（`AudioDuckingMixer`） |
| 通话声音还在手机端 | HFP 被车机断掉；`setStreamVolume(0)` 被系统拒绝（通话流受保护） | 优先 telephony 流；HFP 连上时走 SCO 自动静音 |
| 车机识别不到本机 | 车机只在**配对那一刻**读 SDP；手机在 Android 14+ 无法把自己设为可被发现 | 从手机侧搜车机配对；界面文案已按此改写 |
| 日志里「签名形状不符」 | 假车机发的是 72B 假签名 | 假车机改成真 RSA-1024 签名，本地能跑通验签 |
