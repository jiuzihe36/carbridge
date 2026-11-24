# AirPlay（Wi-Fi 侧）

链路：`mDNS → pair-setup / pair-verify → RTSP SETUP → RECORD → 视频/音频/输入`

### mDNS 端点发现
状态：CONFIRMED
证据：实车可发现车机端点并建立会话
代码：android/app/java/com/carbridge/carplay/net/MdnsCarPlay.java

### pair-setup / pair-verify（SRP / Ed25519 / X25519）
状态：CONFIRMED
证据：实车可完成配对；RFC 8032 / RFC 7748 官方向量单测通过
代码：android/app/java/com/carbridge/carplay/pairing/PairVerifySession.java
测试：tools/carbridge-tests/run.sh「cryptoVectors」

### RTSP SETUP（视频 / 音频 / 触摸）
状态：CONFIRMED
证据：实车出画面、可触摸
代码：android/app/java/com/carbridge/carplay/core/AirPlaySession.java

### 视频：H.264 零转码转发
状态：CONFIRMED
证据：实车画面；按帧封装，不做二次编码
代码：android/app/java/com/carbridge/carplay/bridge/CarPlayBridge.java
决策：docs/decisions/0003-zero-transcode-video.md

### 触摸：AirPlay HID → CarLife touch
状态：CONFIRMED
证据：实车触摸生效（含锁屏场景）
代码：android/app/java/com/carbridge/carplay/core/HidInput.java

### 触摸 Report ID（描述符声明 ID 时剥报告首字节）
状态：INFERRED
证据：单测 10 项（带 ID 单指/多点、首字节不匹配不剥、剥后不足 5 字节报错）；本车车机描述符是否声明 Report ID 尚未确认
代码：android/app/java/com/carbridge/carplay/core/HidInput.java、android/app/java/com/carbridge/carplay/core/HidDescriptor.java
关闭条件：实车日志出现「触摸ReportID=N」时点一次屏幕，坐标无偏移即关闭

### 音频：媒体通道
状态：CONFIRMED
证据：实车有声；Car+ 抓系统混音，音乐整段透传
代码：android/app/java/com/carbridge/carplay/audio/CarAudioRouter.java

### 音频：通话（telephony）
状态：INFERRED
证据：平台限制（第三方 App 无法静音通话流）→ HFP 优先；不同车机效果待逐台确认
代码：android/app/java/com/carbridge/carplay/audio/CallAudioCapture.java
关闭条件：逐台实车记录（PASS / PARTIAL + 第一断点）
