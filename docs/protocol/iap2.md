# iAP2（蓝牙侧）

链路：`SDP 信标 → RFCOMM → 链路分帧（SYN/SYNACK/ACK）→ 识别 → MFi → 无线参数`

### SDP 信标（4 条记录）`2d8d2466` / `deca-fade` / `cade-defa` / `02030302`
状态：CONFIRMED
证据：实车——车机蓝牙列表出现「(支持无线CarPlay连接)」；假车机可据此识别本机
代码：android/app/java/com/carbridge/carplay/bt/CarPlaySdpBeacon.java
测试：tools/smoke.sh 断言「信标四条注册」

### RFCOMM 链路分帧与重同步
状态：CONFIRMED
证据：单测覆盖半包、坏帧、重同步；假车机端到端
代码：android/app/java/com/carbridge/carplay/iap2/Iap2LinkEngine.java
测试：tools/carbridge-tests/run.sh「Iap2LinkEngine（分帧 / 重新同步）」

### Identification（`0x1D01`）
状态：CONFIRMED
证据：假车机完整走通会话
代码：android/app/java/com/carbridge/carplay/iap2/Iap2SenderSession.java

### MFi 鉴权——宽松模式（默认）
状态：CONFIRMED
证据：实车可通行；假车机真 RSA 可跑通验签链路
代码：android/app/java/com/carbridge/carplay/iap2/Iap2SenderSession.java
测试：tools/carbridge-tests/run.sh「MfiCrypto」

### MFi 鉴权——严格验签模式
状态：INFERRED
证据：代码有开关，假车机可跑通真 RSA；尚无要求严格签名的实车样本
代码：android/app/java/com/carbridge/carplay/iap2/Iap2SenderSession.java
关闭条件：一台要求严格 MFi 签名的车机会话日志（含鉴权失败点）

### 无线参数交接（`0x5703` / `0x4300` / `0x4301`）
状态：CONFIRMED
证据：实车可入网并建立 AirPlay；`0x5703` 缺席时用 EIR 兜底再发 `0x4300`
代码：android/app/java/com/carbridge/carplay/CarPlayWirelessTx.java

### `0x5703` 多布局解析（标准 / DiPlay）
状态：CONFIRMED
证据：单测覆盖四种布局；解析按打分择优，避免 SSID/密码错位
代码：android/app/java/com/carbridge/carplay/iap2/WifiConfigParser.java
测试：tools/carbridge-tests/run.sh「WifiConfigParser」

### Wi-Fi 入网与 BSSID 对账
状态：CONFIRMED
证据：实车入网并继续 mDNS 发现
代码：android/app/java/com/carbridge/carplay/net/WifiJoiner.java
