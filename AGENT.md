# AGENT.md — carbridge 工程规则

本文件是**规范性文件**；`README.md` 只做入门。任何协议实现、排障或代码修改前先读本文件。
涉及结构时同时读 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)，当前证据状态见
[`docs/STATUS.md`](docs/STATUS.md)。

> 做法参考 CarVeX（`iamr0s/carvex-carplay-research`）：证据分级、状态机来自协议而不来自车型、
> 找第一个断点、验证门禁不把 SKIP 当通过。这里按 carbridge 的实际情况裁剪。

## 0. 阅读顺序

1. 本文件。
2. `docs/ARCHITECTURE.md`：模块划分、会话流程、关键约定。
3. `docs/STATUS.md`：哪些已 `CONFIRMED`、哪些还是 `OPEN`。
4. 相关代码。**现有实现是候选实现，不是协议真相**——它的可信度由 STATUS.md 里的证据决定。

## 1. 项目定位

把 Android 手机的画面经**无线 CarPlay** 投到车机上：

```text
蓝牙 SDP 信标 → iAP2（识别 / 鉴权 / 无线参数）
              → AirPlay（pair-verify / SETUP / RECORD）
              → 视频（Car+ H.264 零转码）/ 音频 / 触摸回传
```

手机在车机眼里是一台 iPhone；画面来源是本机 CarLife（OPPO Car+）。

## 2. 证据等级

| 等级 | 含义 |
|---|---|
| `CONFIRMED` | 有可复现证据：实车日志/抓包、假车机跑通、单测锁定、公开规范或固件字节 |
| `INFERRED` | 有证据支持，但仍有未读取的分支或边界；改动必须显式保留未确定部分 |
| `OPEN` | 证据不足。**禁止凭经验补全**，也不得在 UI/文档里宣称已支持 |

- **允许作为证据**：带版本号的实车会话日志、抓包、`tools/fakecar` 跑通、纯逻辑单测、协议规范。
- **只能当作索引**：旧笔记、代码注释、反编译输出、第三方实现、「某台车能连」的现象。
  它们可以提出候选假设，不能直接成为生产修改依据。

## 3. 状态机来自协议，不来自车型

禁止按车型 / 车机型号硬编码分支，也禁止用配置表切换另一套状态机。
车型差异只能通过**协商字段、能力位与实际消息**表达；`CarProfile` 只是兜底默认值，
不能变成「某车型专属路径」。未知或未闭合的路径保持 `OPEN`。

## 4. 修改协议代码的规则

修改前必须：

1. `git status` —— 看清现有未提交改动，不覆盖、不回滚无关修改。
2. 把要改的每个文件备份到 `local-backups/<YYYY-MM-DD>-<主题>/`。
3. 不格式化、不批量重排与本次修改无关的代码。

每次生产修改要能回答五个问题：

1. 证据是什么（实车日志 / 假车机 / 单测 / 规范）。
2. 当前实现与证据差在哪。
3. 新实现如何与证据对齐。
4. 用哪些测试验证。
5. 是否经过实车验证；没有就写 `OPEN`。

**禁止**：盲目固定延迟、无条件重发、跳过声明或认证检查、无证据关闭 `OPEN`、
按车型硬编码、拿第三方实现替代证据。

## 5. 调试：找第一个断点，不看最后一条异常

按顺序定位第一个偏离预期的阶段：

```text
BLUETOOTH → SDP 信标 → iAP2 link（SYN/SYNACK/ACK）→ AUTH → IDENTIFICATION
          → WIFI HANDOFF（0x5703 / 0x4300 / 0x4301）→ ENDPOINT（mDNS）
          → AIRPLAY CONTROL → RTSP SETUP → VIDEO / AUDIO / INPUT
```

会话日志至少要能回答：

- APK 版本、会话 ID、目标蓝牙地址、连接发起方向；
- 实际协商的 link / control 版本；
- 最后一条发送成功的消息、下一条预期消息；
- 第一个超时或资源关闭点；
- 实际使用的 Network / 接口 / 地址来源（热点入网时尤其重要）。

判断规则（都踩过）：

- 链路 ACK ≠ 业务消息已被接受；
- RFCOMM 连接成功 ≠ iAP2 已建立；
- 收到 `0x5703` ≠ 已入网；
- 收到 `0x4301` ≠ AirPlay 端点可达；
- SETUP 200 ≠ 首帧成功。

## 6. 验证门禁

```bash
tools/verify.sh            # docset + 架构守卫 + 静态体检 + 纯逻辑单测（必跑）
BUILD=1 tools/verify.sh    # 追加 APK 构建
SMOKE=1 tools/verify.sh    # 追加载机 + 假车机全流程（需手机 + 平板）
```

- `tools/verify.sh` 输出 `PASS / SKIP / FAIL` 三态；**`SKIP` 不算通过**，汇总里会单独计数。
- `tools/check-arch.sh`：协议层不许反向依赖界面层（`carplay/*` 不得 `import com.carbridge.app.*`）。
- `tools/carbridge-tests/run.sh`：纯 JVM 单测（不依赖 Android）。
- `tools/smoke.sh`：装机 → 起假车机 → 自动连接 → 等到推流，失败退出非 0。

改协议相关模块（`carplay/**`、`tools/fakecar/**`）必须至少跑一次 `tools/verify.sh`；
有真机时再跑 `SMOKE=1`。

## 7. 实车测试协议

1. 先提交代码，保证测试版本可追溯，再打包安装。
2. 一次只验证一个假设。
3. 从连接开始保留完整日志，不只留末尾异常（App 内「诊断包」直接导出）。
4. 记录：是否手动断开、车机是否残留旧会话、是否重启过车机。
5. 结果只允许四种写法：`PASS` / `PARTIAL`（写明第一断点）/ `FAIL` / `OPEN`。
6. 不因一次成功就关闭兼容性问题。

## 8. 文档维护

- 持久决策写入 `docs/decisions/NNNN-*.md`：背景 / 决策 / 后果 / 证据。
- 证据状态变化时，**同一次提交**里更新 `docs/STATUS.md`。
- 协议正文与研究笔记留在本地 `docs/`（`.gitignore` 已排除，不进仓库）；仓库只保留
  架构、状态、决策与规则。
- `README.md` 只做入门，不复制协议细节。
