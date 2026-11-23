# carbridge

把 Android 手机的画面，通过**无线 CarPlay** 投到车机上。

手机不是 iPhone。carbridge 在本机建立 iAP2 / AirPlay 发送端，让车机把它识别为
一台无线 CarPlay 设备；画面来源是本机的 CarLife 服务（OPPO Car+），H.264 码流
零转码直接封进 AirPlay 视频通道，触摸事件反向回传给 CarLife。

```
手机 ── CarLife(本机) ──► carbridge ── iAP2 + AirPlay(无线) ──► 车机
```

## 工作方式

1. **蓝牙**：注册 CarPlay SDP 信标（`2d8d2466` / `deca-fade` / `cade-defa` / `02030302`），
   车机配对时读到这些记录，才会把手机当成 CarPlay 设备。
2. **iAP2**：车机回连手机的 RFCOMM 服务，完成识别、鉴权与无线参数交换。
3. **AirPlay**：车机提供 AirPlay 端点，carbridge 完成配对与 SETUP，建立视频/音频/触摸通道。
4. **CarLife**：Car+ 在本机输出 H.264 与音频，carbridge 直接转发，不做二次编码。

## 环境

- Android 8.0+（API 26）
- 车机支持无线 CarPlay
- 手机已安装并登录 OPPO Car+（CarLife）

## 构建

需要 JDK 17+ 与 Android SDK（build-tools 34+、platform 35）。

```bash
export ANDROID_SDK_ROOT=$HOME/android-sdk
./android/build.sh
# 产物：android/build/carbridge-0.1.0.apk
```

联调用的假车机：

```bash
./tools/fakecar/build.sh
```

## 使用

1. 打开 App，确认「本机身份 → 车机识别」显示 `已注册 4 条`。
2. **先在车机上删掉本机**。车机只在「配对」那一刻读一次手机的 SDP，之后一直吃缓存；
   它缓存里如果还是「普通手机」，桌面上的 Apple CarPlay 就一直是灰的。
3. 手机 App →「本机身份 → 重新配对车机」→「清除配对」：清掉手机侧配对，并打开
   蓝牙设置页。**打开期间本机可被车机发现，只有约 120 秒**，别退出这个页面。
4. 车机 → 搜索设备 → 选这台手机 → 配对 → 手机上点「配对」。
5. 车机蓝牙列表里这台设备应出现「(支持无线CarPlay连接)」字样；回车机桌面点 `Apple CarPlay`。

顺序不能反：只删手机侧，车机会保留旧 link key，不会重新查 SDP。
改过版本或换过配对，建议重启一次车机再重新配对。

### 车机一键解锁（免电脑）

车机判「这台手机算不算 CarPlay 设备」还有第二条路：系统属性
`persist.sys.wirelesscarplay.address` 等于手机蓝牙 MAC 时，车机的 CarPlay 应用
直接把它放进设备列表，**不检查配对时的 SDP 缓存**。

App 里「本机身份 → 车机一键解锁（免电脑）」会自己做完：扫网段找车机 → 内置 adb
连进去（用的是车机已经信任的那把密钥）→ 写属性并回读校验 → 重启车机 CarPlay 应用
→ 把车机最近的 `isSupportCarplay` 判定日志抓回来。跑完只剩一步：到车机上点列表里
这台手机。前提是手机已连上车机热点。

## 目录

```
android/               主应用（Java，无 Gradle 构建）
  app/java/            com.carbridge.app / carplay / mediacore
  app/res/             布局与资源
  build.sh             aapt2 → javac → d8 → zipalign → apksigner
tools/fakecar/         便携假车机，用于协议联调
docs/ARCHITECTURE.md   模块划分与会话流程
```

## 开发与验证

改动前先读 [`AGENT.md`](AGENT.md)（证据分级、禁止按车型分支、调试与验证规则）；
当前哪些已确认、哪些还是 `OPEN` 见 [`docs/STATUS.md`](docs/STATUS.md)，架构见
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)，持久决策见 `docs/decisions/`。

```bash
tools/verify.sh            # 架构守卫 + 纯逻辑单测（默认）
BUILD=1 tools/verify.sh    # 追加 APK 构建
SMOKE=1 tools/verify.sh    # 追加装机 + 假车机全流程（需手机 + 平板）
```

`verify.sh` 输出 `PASS / SKIP / FAIL` 三态：**SKIP 表示本次未验证，不算通过**。

## 限制

- 仅支持无线 CarPlay，不支持 USB 有线。
- Android 14+ 起应用无法用 API 把本机设为「可被发现」（`setDiscoverable` 已被框架删除）；
  唯一可用入口是打开系统蓝牙设置页，系统会给约 120 秒的可被发现窗口。
- 不包含 Apple MFi 授权证书；是否校验签名由车机决定，代码里可配置。
- 分辨率、音频间隔、UUID 变体集中在 `carplay/CarProfile.java`，换车型改这里。
- 不同 ROM 的后台限制不同，需要允许自启动与后台运行。

## 声明

仅供个人学习与设备互联研究使用。CarPlay、iPhone、MFi 是 Apple Inc. 的商标，
本项目与 Apple 无关联，不包含 Apple 的授权代码或证书。使用风险自负。

## 许可

MIT，见 [LICENSE](LICENSE)。
