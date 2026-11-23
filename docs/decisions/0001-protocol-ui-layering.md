# 0001 协议层与界面层单向依赖

日期：2026-11-18
状态：已采纳

## 背景

早期 `MainActivity` 里同时有界面、连接编排、日志与协议细节（约 2500 行），
改一个协议行为要动界面类，改界面又可能碰坏协议路径。

## 决策

- `com.carbridge.carplay.*`（协议与 AirPlay/iAP2 实现）**不得** `import com.carbridge.app.*`；
- 依赖方向只允许：`app`（编排/界面）→ `carplay`（协议）→ `util`（无业务依赖）；
- 用 `tools/check-arch.sh` 守卫，进 `tools/verify.sh` 必跑项。

## 后果

- 协议层可以脱离 Android 界面单独做纯逻辑单测（见 `tools/carbridge-tests`）。
- 新增协议/宿主适配时应加独立子包，而不是往 `MainActivity` 里堆分支。
- 需要回调界面时走接口或事件，不直接引用 Activity。

## 证据

`tools/check-arch.sh` 的两条断言；`tools/carbridge-tests/run.sh` 可独立编译协议子包。
