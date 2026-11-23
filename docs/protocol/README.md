# 协议 docset

规则（`tools/verify.sh` 会检查结构）：

- 每个 `###` 条目必须写 `状态：CONFIRMED|INFERRED|OPEN` 与 `证据：…`；
- `OPEN` 条目必须写 `关闭条件：…`（什么证据能把它关掉）；
- `代码：` 里的路径必须真实存在，防止文档腐烂。

状态定义见根目录 [`AGENT.md`](../../AGENT.md)，逐阶段总表见 [`../STATUS.md`](../STATUS.md)。

| docset | 范围 |
|---|---|
| [`iap2.md`](iap2.md) | 蓝牙 SDP 信标、RFCOMM 分帧、识别、MFi、无线参数交接 |
| [`airplay.md`](airplay.md) | mDNS、pair-setup/verify、RTSP SETUP、视频/音频/触摸 |

协议正文只写**可从代码或实车/假车机验证的字段与顺序**；拿不到证据的写在 `OPEN`，不补经验值。
