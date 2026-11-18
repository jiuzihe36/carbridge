package com.carbridge.app;

/* JADX INFO: loaded from: HuClient.class */
public final class HuClient {
    private static final int HU_SLOTS = 6;
    private final android.content.Context ctx;
    private final com.carbridge.app.HuClient.Sink sink;
    private volatile boolean running;
    private volatile int learnedFps;
    private static final int DEFAULT_TARGET_FPS = 30;
    private volatile int encW;
    private volatile int encH;
    private volatile int encFps;
    private volatile int carW;
    private volatile int carH;
    private volatile int carFps;
    private static final long LAUNCH_COOLDOWN_MS = 3000;
    private volatile long lastLaunchAtMs;
    private int portFails;
    private volatile com.carbridge.carplay.bridge.CarPlayBridge carPlayBridge;
    private volatile boolean videoReading;
    private volatile boolean audioReading;
    private volatile boolean ttsReading;
    private volatile int mediaGen;
    private volatile int hbGen;
    private static final int SETTLE_MIN_MS = 4500;
    private static final int SETTLE_STEP_MS = 1500;
    private static final int SETTLE_MAX_MS = 6000;
    private static final int COLD_SETTLE_MS = 8000;
    private static final int CAST_READY_DELAY_MS = 600;
    private static final int INITIAL_OBSERVE_MS = 2000;
    private static final int OBSERVE_MS = 5000;
    private static final int MAX_OBSERVATIONS = 2;
    private static final long PROGRESS_WAIT_MS = 20000;
    private static final long NO_PROGRESS_WAIT_MS = 8000;
    private static final int STREAM_OBSERVE_MS = 10000;
    private volatile long startedAt;
    private volatile long firstFrameAt;
    private volatile long encoderAtMs;
    private volatile long encoderInitSentAtMs;
    private volatile boolean frameRateChanging;
    private volatile boolean liveFrameRateChange;
    private volatile boolean liveFrameRateChangeDone;
    private volatile boolean encoderNormalized;
    private volatile boolean moduleSeen;
    private volatile boolean phoneModuleActive;
    private volatile long lastRxAtMs;
    private volatile boolean streamStarted;
    private volatile boolean startScheduled;
    private volatile int noFrameFails;
    private long lastForcedAtMs;
    private int portResetStreak;
    private static final long FORCE_COOLDOWN_MS = 20000;
    private volatile int frameCount;
    private volatile long byteCount;
    private static final java.util.concurrent.atomic.AtomicReference<com.carbridge.app.HuClient> ACTIVE = new java.util.concurrent.atomic.AtomicReference<>();
    private static volatile int procNetState;
    private volatile long lastTouchLogMs;
    private int touchWriteCount;
    private volatile android.os.Handler touchHandler;
    private android.os.HandlerThread touchThread;
    private volatile com.carbridge.app.HuClient.State state = com.carbridge.app.HuClient.State.IDLE;
    private volatile int width = 1920;
    private volatile int height = 720;
    private volatile int fps = DEFAULT_TARGET_FPS;
    private volatile boolean autoFit = true;
    private volatile int connectType = MAX_OBSERVATIONS;
    private volatile int portWaitMs = 25000;
    private volatile boolean autoLaunch = true;
    private volatile com.carbridge.app.HuClient.Step step = com.carbridge.app.HuClient.Step.WAITING_CHANNEL;
    private final java.net.Socket[] socks = new java.net.Socket[HU_SLOTS];
    private volatile int[] activePorts = com.carbridge.carplay.proto.HuProto.WIFI_PORTS;
    private volatile int settleMs = SETTLE_MIN_MS;
    private volatile boolean coldStart = true;
    private volatile com.carbridge.app.HuClient.Fault fault = com.carbridge.app.HuClient.Fault.NONE;
    private volatile com.carbridge.app.HuClient.Step faultAtStep = com.carbridge.app.HuClient.Step.WAITING_CHANNEL;

    /* JADX INFO: loaded from: HuClient$Fault.class */
    public enum Fault {
        NONE,
        EOF,
        RST,
        SILENT,
        STALL
    }

    /* JADX INFO: loaded from: HuClient$Sink.class */
    public interface Sink {
        void onState(com.carbridge.app.HuClient.State state, java.lang.String str);

        void onLog(java.lang.String str);

        void onVideo(byte[] bArr, int i, int i2);

        void onPhoneInfo(java.lang.String str);

        void onMetrics(int i, long j, long j2);

        void onEncoderInfo(int i, int i2, int i3, int i4, int i5, int i6);

        void onPhoneCallState(boolean z);

        void onStep(com.carbridge.app.HuClient.Step step);

        void onStepFailed(com.carbridge.app.HuClient.Step step, java.lang.String str);
    }

    /* JADX INFO: loaded from: HuClient$State.class */
    public enum State {
        IDLE,
        WAITING_PORT,
        CONNECTING,
        HANDSHAKING,
        STREAMING,
        ERROR,
        CLOSED
    }

    static /* synthetic */ int access$1804(com.carbridge.app.HuClient huClient) {
        int i = huClient.touchWriteCount + 1;
        huClient.touchWriteCount = i;
        return i;
    }

    static /* synthetic */ int access$1908(com.carbridge.app.HuClient huClient) {
        int i = huClient.hbGen;
        huClient.hbGen = i + 1;
        return i;
    }

    /* JADX INFO: loaded from: HuClient$Step.class */
    public enum Step {
        WAITING_CHANNEL("等待 Car+ 打开通道"),
        CONNECT_CHANNELS("连接 6 条通道"),
        VERSION("版本协商"),
        DEVICE_INFO("设备信息交换"),
        FEATURE_CONFIG("特征配置"),
        AUTH("鉴权"),
        ENCODER("编码器协商"),
        STREAMING("开始推流");

        public final java.lang.String label;

        Step(java.lang.String str) {
            this.label = str;
        }

        public com.carbridge.app.HuClient.Step next() {
            com.carbridge.app.HuClient.Step[] stepArrValues = values();
            if (ordinal() + 1 < stepArrValues.length) {
                return stepArrValues[ordinal() + 1];
            }
            return null;
        }
    }

    private boolean forceAllowed() {
        return java.lang.Math.max(0L, java.lang.System.currentTimeMillis() - this.lastForcedAtMs) >= 20000;
    }

    private void forceRestartCarlife(java.lang.String str) {
        this.lastForcedAtMs = java.lang.System.currentTimeMillis();
        this.noFrameFails = 0;
        log(str);
        forceStopCarlifeChannel();
        sleep(1500L);
    }

    private boolean tryForceRestart(java.lang.String str) {
        if (!forceAllowed()) {
            log("强拉冷却中（20000ms 窗口内已用过一次），本轮只等、不再 CLEAR_TASK —— 反复重置会打断 Car+ 自己的重建");
            return false;
        }
        forceRestartCarlife(str);
        return true;
    }

    public HuClient(android.content.Context context, com.carbridge.app.HuClient.Sink sink) {
        this.ctx = context.getApplicationContext();
        this.sink = sink;
    }

    public void setEncoderSize(int i, int i2, int i3) {
        this.width = i;
        this.height = i2;
        this.fps = i3;
        pushStreamSize();
        notifyEncoderInfo();
    }

    public void setAutoFit(boolean z) {
        this.autoFit = z;
        log("画质自动适配：" + (z ? "开启（跟随车机 /info）" : "关闭（使用手动参数）"));
        notifyEncoderInfo();
    }

    public void onCarDisplay(int i, int i2, int i3) {
        this.carW = i;
        this.carH = i2;
        this.carFps = i3;
        int[] iArrFit = com.carbridge.carplay.core.DisplayGeometry.fit(i, i2);
        if (iArrFit == null) {
            log("车机画布非法：" + i + "x" + i2 + "，忽略");
            return;
        }
        int iClampFps = com.carbridge.carplay.core.DisplayGeometry.clampFps(this.learnedFps > 0 ? java.lang.Math.min(this.learnedFps, i3) : java.lang.Math.min(i3, DEFAULT_TARGET_FPS));
        if (this.learnedFps > 0) {
            log("沿用上次协商到的帧率上限 @" + this.learnedFps + "（避免每次重连重新讨价还价）");
        }
        log("车机画布 " + i + "x" + i2 + "@" + i3 + " → 目标 " + iArrFit[0] + "x" + iArrFit[1] + "@" + iClampFps + "（自动适配" + (this.autoFit ? "开" : "关") + "）");
        if (!this.autoFit) {
            notifyEncoderInfo();
            return;
        }
        boolean z = false;
        if (this.width != iArrFit[0] || this.height != iArrFit[1]) {
            if (!this.streamStarted && this.firstFrameAt == 0 && this.encoderInitSentAtMs == 0) {
                this.width = iArrFit[0];
                this.height = iArrFit[1];
                z = true;
                log("采用车机画布尺寸 " + this.width + "x" + this.height + "（本次编码器初始化）");
            } else {
                log("车机画布与当前编码不同（" + this.width + "x" + this.height + " → " + iArrFit[0] + "x" + iArrFit[1] + "）：为不打断会话，分辨率下一次连接生效");
            }
        }
        if (this.fps != iClampFps) {
            if (this.streamStarted || this.firstFrameAt > 0) {
                requestLiveFrameRate(iClampFps);
            } else {
                this.fps = iClampFps;
                z = true;
                log("采用车机画布帧率 @" + this.fps + "（本次编码器初始化）");
            }
        }
        if (z) {
            notifyEncoderInfo();
        }
    }

    private void requestLiveFrameRate(int i) {
        if (i <= 0 || i == this.fps) {
            return;
        }
        int i2 = this.fps;
        this.fps = i;
        if (!this.streamStarted || this.socks[0] == null) {
            log("帧率目标从 @" + i2 + " 改为 @" + i + "（等待编码器初始化）");
            notifyEncoderInfo();
            return;
        }
        this.liveFrameRateChange = true;
        this.liveFrameRateChangeDone = false;
        this.frameRateChanging = true;
        this.encoderNormalized = false;
        boolean zSend = send(0, 98316, com.carbridge.carplay.proto.HuProto.videoFrameRate(i));
        if (!zSend) {
            this.liveFrameRateChange = false;
            this.frameRateChanging = false;
        }
        log((zSend ? "→ 动态帧率 " : "⚠ 动态帧率发送失败 ") + "@" + i2 + " → @" + i + "（0x1800C，不重连）");
        notifyEncoderInfo();
    }

    private void pushStreamSize() {
        com.carbridge.carplay.bridge.CarPlayBridge carPlayBridge = this.carPlayBridge;
        if (carPlayBridge == null) {
            return;
        }
        int i = this.encW > 0 ? this.encW : this.width;
        int i2 = this.encH > 0 ? this.encH : this.height;
        if (i <= 0 || i2 <= 0) {
            return;
        }
        carPlayBridge.setStreamSize(i, i2);
    }

    private void notifyEncoderInfo() {
        try {
            this.sink.onEncoderInfo(this.width, this.height, this.fps, this.encW, this.encH, this.encFps);
        } catch (java.lang.Throwable th) {
            com.carbridge.carplay.util.Diag.w("HuClient", th);
        }
    }

    public void setConnectType(int i) {
        this.connectType = i;
    }

    public void setAutoLaunch(boolean z) {
        this.autoLaunch = z;
    }

    public void setCarPlayBridge(com.carbridge.carplay.bridge.CarPlayBridge carPlayBridge) {
        this.carPlayBridge = carPlayBridge;
        pushStreamSize();
    }

    public com.carbridge.carplay.bridge.CarPlayBridge carPlayBridge() {
        return this.carPlayBridge;
    }

    public int streamWidth() {
        return this.width;
    }

    public int streamHeight() {
        return this.height;
    }

    public int streamFps() {
        return this.fps;
    }

    public int encodedWidth() {
        return this.encW;
    }

    public int encodedHeight() {
        return this.encH;
    }

    public int encodedFps() {
        return this.encFps;
    }

    public boolean autoFit() {
        return this.autoFit;
    }

    public void setPortWaitMs(int i) {
        this.portWaitMs = i;
    }

    public boolean isRunning() {
        return this.running;
    }

    public com.carbridge.app.HuClient.State state() {
        return this.state;
    }

    public int frameCount() {
        return this.frameCount;
    }

    public long byteCount() {
        return this.byteCount;
    }

    public void start() {
        if (this.running) {
            return;
        }
        com.carbridge.app.HuClient andSet = ACTIVE.getAndSet(this);
        if (andSet != null && andSet != this) {
            log("检测到另一个投屏客户端在运行，先停掉旧的（避免两个客户端抢 Car+）");
            andSet.stop();
        }
        this.running = true;
        new java.lang.Thread(new java.lang.Runnable() { // from class: com.carbridge.app.HuClient.1
            @Override // java.lang.Runnable
            public void run() {
                com.carbridge.app.HuClient.this.session();
            }
        }, "hu-session").start();
    }

    public void stop() {
        ACTIVE.compareAndSet(this, null);
        this.running = false;
        this.videoReading = false;
        this.audioReading = false;
        this.ttsReading = false;
        this.hbGen++;
        stopTouchThread();
        closeAll();
        setState(com.carbridge.app.HuClient.State.CLOSED, "已断开");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void closeAll() {
        this.videoReading = false;
        this.audioReading = false;
        this.ttsReading = false;
        this.mediaGen++;
        this.hbGen++;
        this.startScheduled = false;
        for (int i = 0; i < HU_SLOTS; i++) {
            closeQuietly(this.socks[i]);
            this.socks[i] = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void session() {
        boolean z = true;
        int i = 1;
        while (this.running) {
            this.startedAt = java.lang.System.currentTimeMillis();
            if (i > 1) {
                this.coldStart = false;
            }
            log("── 第 " + i + " 次尝试 ──");
            if (this.firstFrameAt > 0) {
                this.noFrameFails = 0;
            }
            this.firstFrameAt = 0L;
            this.encoderAtMs = 0L;
            this.encoderInitSentAtMs = 0L;
            this.frameRateChanging = false;
            this.liveFrameRateChange = false;
            this.liveFrameRateChangeDone = false;
            this.encoderNormalized = false;
            this.fault = com.carbridge.app.HuClient.Fault.NONE;
            this.faultAtStep = com.carbridge.app.HuClient.Step.WAITING_CHANNEL;
            this.moduleSeen = false;
            this.streamStarted = false;
            this.frameCount = 0;
            this.byteCount = 0L;
            this.encW = 0;
            this.encH = 0;
            this.encFps = 0;
            if (this.noFrameFails >= MAX_OBSERVATIONS) {
                if (forceAllowed()) {
                    forceRestartCarlife("连续 2 轮连上却一帧没来（对端在听却不回协议 / 视频被 RST 任一），判 Car+ 状态机卡住，强制重置状态机");
                    z = false;
                } else {
                    this.noFrameFails = 0;
                    log("强拉冷却中（20000ms 窗口内已用过一次），本轮先普通重连");
                }
            }
            if (this.autoLaunch && z) {
                launchCarlifeChannel(true);
                z = false;
            }
            if (!openAllChannels()) {
                this.portFails++;
                if (this.portFails >= 3) {
                    if (tryForceRestart("连续 " + this.portFails + " 轮等不到端口，强杀 Car+ 的状态机再重新拉起")) {
                        z = false;
                    }
                    this.portFails = 0;
                }
                if (i >= HU_SLOTS) {
                    log("等不到 Car+ 的 CarLife 端口，已重试 " + i + " 轮（可在 Car+ 里手动进一次车机界面再点重试）");
                }
                closeAll();
                sleep(2000L);
            } else {
                this.portFails = 0;
                try {
                    send(0, 98305, com.carbridge.carplay.proto.HuProto.protocolVersion());
                    log("→ HU_PROTOCOL_VERSION {major=4, minor=0}（6 条通道已就绪）");
                    setState(com.carbridge.app.HuClient.State.HANDSHAKING, "握手协商中");
                    startHeartbeat();
                    int i2 = 0;
                    int i3 = INITIAL_OBSERVE_MS;
                    while (this.running) {
                        long j = this.lastRxAtMs;
                        ctrlLoop(i3);
                        if (this.lastRxAtMs > j) {
                            i2 = 0;
                            i3 = STREAM_OBSERVE_MS;
                        } else {
                            java.lang.String strDiagnose = diagnose();
                            if (shouldReconnect(strDiagnose)) {
                                break;
                            }
                            i2++;
                            if (i2 >= MAX_OBSERVATIONS) {
                                if (this.fault == com.carbridge.app.HuClient.Fault.SILENT && forceAllowed()) {
                                    forceRestartCarlife("观测用尽，诊断 " + strDiagnose + " → 对端 accept 了连接却不进协议，判状态机卡住，就地重置状态机（不空等、不盲目重连）");
                                    z = false;
                                    break;
                                } else {
                                    log("观测用尽仍无进展，诊断 " + strDiagnose + "，拆掉重来");
                                    break;
                                }
                            }
                            log("观测 " + i2 + "/" + MAX_OBSERVATIONS + "：" + strDiagnose + " → 对端仍在，继续等，不重置");
                            this.fault = com.carbridge.app.HuClient.Fault.NONE;
                            i3 = OBSERVE_MS;
                        }
                    }
                    if (this.running) {
                        log("会话结束");
                    }
                } catch (java.lang.Throwable th) {
                    log("会话异常：" + th);
                }
                if (this.firstFrameAt == 0) {
                    this.noFrameFails++;
                    if (this.streamStarted && this.settleMs < SETTLE_MAX_MS) {
                        int i4 = this.settleMs;
                        this.settleMs = java.lang.Math.min(SETTLE_MAX_MS, this.settleMs + SETTLE_STEP_MS);
                        log("已走到开始推流却一帧没出（编码器是上一轮缓存），下一轮把连接前等待从 " + i4 + "ms 放宽到 " + this.settleMs + "ms");
                    }
                } else {
                    this.noFrameFails = 0;
                    if (this.settleMs != SETTLE_MIN_MS) {
                        log("已出帧，连接前等待回到 4500ms");
                        this.settleMs = SETTLE_MIN_MS;
                    }
                }
                closeAll();
                if (this.running) {
                    sleep(1800L);
                }
            }
            i++;
        }
        if (this.running) {
            setState(com.carbridge.app.HuClient.State.ERROR, "多次尝试都没连上");
        }
    }

    private void forceStopCarlifeChannel() {
        try {
            java.lang.Process processExec = java.lang.Runtime.getRuntime().exec(new java.lang.String[]{"/system/bin/am", "force-stop", "com.oplus.ocar"});
            boolean zWaitFor = processExec.waitFor(3L, java.util.concurrent.TimeUnit.SECONDS);
            int iExitValue = zWaitFor ? processExec.exitValue() : -1;
            if (!zWaitFor) {
                processExec.destroy();
            }
            log("am force-stop com.oplus.ocar → exit=" + iExitValue + (iExitValue == 0 ? "（成功）" : "（多半无 FORCE_STOP_PACKAGES，走 CLEAR_TASK 兜底）"));
        } catch (java.lang.Throwable th) {
            log("am force-stop 执行异常：" + th);
        }
        this.lastLaunchAtMs = 0L;
        launchCarlifeChannel(true);
    }

    private void launchCarlifeChannel() {
        launchCarlifeChannel(false);
    }

    private void launchCarlifeChannel(boolean z) {
        long jCurrentTimeMillis = java.lang.System.currentTimeMillis();
        if (!z && this.lastLaunchAtMs > 0 && jCurrentTimeMillis - this.lastLaunchAtMs < LAUNCH_COOLDOWN_MS) {
            log("距上次拉起 " + (jCurrentTimeMillis - this.lastLaunchAtMs) + "ms < " + LAUNCH_COOLDOWN_MS + "ms，跳过（反复拉起会重置 Car+ 服务）");
            return;
        }
        this.lastLaunchAtMs = jCurrentTimeMillis;
        for (java.lang.String[] strArr : new java.lang.String[][]{new java.lang.String[]{"com.oplus.ocar", "com.oplus.ocar.CarlifeActivity"}, new java.lang.String[]{"com.oplus.ocar", "com.oplus.ocar.connect.carlife.CarlifeAccessoryFoundActivity"}}) {
            try {
                android.content.Intent intent = new android.content.Intent();
                intent.setComponent(new android.content.ComponentName(strArr[0], strArr[1]));
                intent.addFlags(268435456);
                if (z) {
                    intent.addFlags(32768);
                }
                this.ctx.startActivity(intent);
                log((z ? "已 CLEAR_TASK 重开 " : "已拉起 ") + strArr[0] + "/" + strArr[1]);
                return;
            } catch (java.lang.Throwable th) {
                log("拉起 " + strArr[1] + " 失败：" + th);
            }
        }
        log("没有可用的 Car+ 入口，改为等端口自己出现");
    }

    private boolean openAllChannels() {
        setState(com.carbridge.app.HuClient.State.WAITING_PORT, "等待 Car+ 打开通道");
        enterStep(com.carbridge.app.HuClient.Step.WAITING_CHANNEL);
        int[] iArrWaitForPorts = waitForPorts();
        if (iArrWaitForPorts == null) {
            this.sink.onStepFailed(com.carbridge.app.HuClient.Step.WAITING_CHANNEL, "等不到 Car+ 打开端口");
            return false;
        }
        this.activePorts = iArrWaitForPorts;
        log("手机监听端口组：" + iArrWaitForPorts[0] + "/" + iArrWaitForPorts[1] + "/" + iArrWaitForPorts[MAX_OBSERVATIONS] + "/" + iArrWaitForPorts[3] + "/" + iArrWaitForPorts[4] + "/" + iArrWaitForPorts[5]);
        setState(com.carbridge.app.HuClient.State.CONNECTING, "连接 6 条通道");
        enterStep(com.carbridge.app.HuClient.Step.CONNECT_CHANNELS);
        for (int i = 0; i < HU_SLOTS; i++) {
            this.socks[i] = connect(iArrWaitForPorts[i]);
            if (this.socks[i] == null) {
                log("通道 " + i + "（" + iArrWaitForPorts[i] + "）连不上，重试一次");
                sleep(400L);
                this.socks[i] = connect(iArrWaitForPorts[i]);
            }
            if (this.socks[i] == null) {
                this.sink.onStepFailed(com.carbridge.app.HuClient.Step.CONNECT_CHANNELS, "通道 " + iArrWaitForPorts[i] + " 连不上");
                return false;
            }
        }
        for (int i2 = 0; i2 < HU_SLOTS; i2++) {
            log("已连接通道 " + channelName(i2) + " " + iArrWaitForPorts[i2]);
        }
        return true;
    }

    private int[] waitForPorts() {
        long jCurrentTimeMillis = java.lang.System.currentTimeMillis() + ((long) this.portWaitMs);
        boolean z = false;
        if (!procNetReadable()) {
            int i = this.coldStart ? COLD_SETTLE_MS : this.settleMs;
            long jMax = this.lastLaunchAtMs > 0 ? java.lang.Math.max(0L, java.lang.System.currentTimeMillis() - this.lastLaunchAtMs) : Long.MAX_VALUE;
            long j = jMax < ((long) i) ? ((long) i) - jMax : 0L;
            if (j > 0) {
                log("读不到 /proc/net/tcp，按默认端口组等 " + j + "ms 后直连");
                sleep((int) j);
            } else {
                log("读不到 /proc/net/tcp，按默认端口组直连");
            }
            return com.carbridge.carplay.proto.HuProto.WIFI_PORTS;
        }
        while (this.running && java.lang.System.currentTimeMillis() < jCurrentTimeMillis) {
            for (int[] iArr : new int[][]{com.carbridge.carplay.proto.HuProto.WIFI_PORTS, com.carbridge.carplay.proto.HuProto.LOCAL_PORTS}) {
                if (probePort(iArr[0])) {
                    int i2 = this.coldStart ? COLD_SETTLE_MS : this.settleMs;
                    long jMax2 = this.lastLaunchAtMs > 0 ? java.lang.Math.max(0L, java.lang.System.currentTimeMillis() - this.lastLaunchAtMs) : Long.MAX_VALUE;
                    long j2 = jMax2 < ((long) i2) ? ((long) i2) - jMax2 : 0L;
                    if (j2 > 0) {
                        log("端口 " + iArr[0] + " 已打开，距上次拉起还差 " + j2 + "ms，等够再连");
                        sleep((int) j2);
                    } else {
                        log("端口 " + iArr[0] + " 已打开（距上次拉起已超过 " + i2 + "ms），直接连");
                    }
                    if (!probePort(iArr[0])) {
                        log("稳定期内端口又关了，继续等");
                        break;
                    }
                    this.portResetStreak = 0;
                    return iArr;
                }
            }
            if (!z) {
                long jMax3 = this.lastLaunchAtMs > 0 ? java.lang.Math.max(0L, java.lang.System.currentTimeMillis() - this.lastLaunchAtMs) : Long.MAX_VALUE;
                if (jMax3 > LAUNCH_COOLDOWN_MS) {
                    z = true;
                    if (this.portResetStreak < 3) {
                        this.portResetStreak++;
                        log("距上次拉起 " + jMax3 + "ms 仍无任何端口，判监听器已被对端拆掉（它不会自己回来），立即重置而不空等（第 " + this.portResetStreak + " 次）");
                        tryForceRestart("重置监听器");
                    } else {
                        log("已连续 " + this.portResetStreak + " 次重置仍换不回监听器，停止重置、只等 —— 反复重启反而会打断它自己的重建");
                    }
                }
            }
            sleep(250L);
        }
        return null;
    }

    private boolean probePort(int i) {
        return isListening(i);
    }

    private static boolean isListening(int i) {
        return scanProcNet("/proc/net/tcp", i) || scanProcNet("/proc/net/tcp6", i);
    }

    private static boolean procNetReadable() {
        int i = procNetState;
        if (i == 0) {
            java.io.BufferedReader bufferedReader = null;
            try {
                bufferedReader = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream("/proc/net/tcp"), "UTF-8"));
                i = bufferedReader.readLine() != null ? 1 : -1;
                if (bufferedReader != null) {
                    try {
                        try { bufferedReader.close(); } catch (Exception ignored) { }
                    } catch (java.lang.Throwable th) {
                        com.carbridge.carplay.util.Diag.w("HuClient", th);
                    }
                }
            } catch (java.lang.Throwable th2) {
                i = -1;
                if (bufferedReader != null) {
                    try {
                        try { bufferedReader.close(); } catch (Exception ignored) { }
                    } catch (java.lang.Throwable th3) {
                        com.carbridge.carplay.util.Diag.w("HuClient", th3);
                    }
                }
            }
            procNetState = i;
        }
        return i > 0;
    }

    /* JADX WARN: Code duplicated, block: B:46:0x00d3 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    private static boolean scanProcNet(java.lang.String str, int i) {
        java.io.BufferedReader bufferedReader = null;
        try {
            try {
                bufferedReader = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(str), "UTF-8"));
                java.lang.String str2 = java.lang.String.format(java.util.Locale.ROOT, ":%04X", java.lang.Integer.valueOf(i));
                boolean z = true;
                while (true) {
                    java.lang.String line = bufferedReader.readLine();
                    if (line == null) {
                        if (bufferedReader == null) {
                            return false;
                        }
                        try {
                            try { bufferedReader.close(); } catch (Exception ignored) { }
                            return false;
                        } catch (java.lang.Throwable th) {
                            com.carbridge.carplay.util.Diag.w("HuClient", th);
                            return false;
                        }
                    }
                    if (z) {
                        z = false;
                    } else {
                        java.lang.String[] strArrSplit = line.trim().split("\\s+");
                        if (strArrSplit.length >= 4 && strArrSplit[1].endsWith(str2) && "0A".equalsIgnoreCase(strArrSplit[3])) {
                            if (bufferedReader != null) {
                                try {
                                    try { bufferedReader.close(); } catch (Exception ignored) { }
                                } catch (java.lang.Throwable th2) {
                                    com.carbridge.carplay.util.Diag.w("HuClient", th2);
                                }
                            }
                            return true;
                        }
                    }
                    if (bufferedReader != null) {
                        try {
                            try { bufferedReader.close(); } catch (Exception ignored) { }
                        } catch (java.lang.Throwable th3) {
                            com.carbridge.carplay.util.Diag.w("HuClient", th3);
                        }
                    }
                }
            } catch (java.lang.Throwable th4) {
                com.carbridge.carplay.util.Diag.w("HuClient", th4);
                if (bufferedReader == null) {
                    return false;
                }
                try {
                    try { bufferedReader.close(); } catch (Exception ignored) { }
                    return false;
                } catch (java.lang.Throwable th5) {
                    com.carbridge.carplay.util.Diag.w("HuClient", th5);
                    return false;
                }
            }
        } catch (java.lang.Throwable th6) {
            if (bufferedReader != null) {
                try { bufferedReader.close(); } catch (Exception ignored) { }
            }
            throw th6;
        }
    }

    private java.net.Socket connect(int i) {
        try {
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", i), SETTLE_STEP_MS);
            socket.setSoTimeout(0);
            socket.setTcpNoDelay(true);
            return socket;
        } catch (java.lang.Throwable th) {
            return null;
        }
    }

    private static java.lang.String channelName(int i) {
        switch (i) {
            case 0:
                return "cmd  ";
            case 1:
                return "video";
            case MAX_OBSERVATIONS /* 2 */:
                return "audio";
            case 3:
                return "tts  ";
            case 4:
                return "vr   ";
            default:
                return "touch";
        }
    }

    private void markFault(com.carbridge.app.HuClient.Fault fault) {
        if (this.fault != com.carbridge.app.HuClient.Fault.NONE) {
            return;
        }
        this.fault = fault;
        this.faultAtStep = this.step;
    }

    private static com.carbridge.app.HuClient.Fault classify(java.io.IOException iOException) {
        java.lang.String lowerCase = java.lang.String.valueOf(iOException.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (lowerCase.contains("reset")) {
            return com.carbridge.app.HuClient.Fault.RST;
        }
        if (lowerCase.contains("broken pipe") || lowerCase.contains("closed") || lowerCase.contains("stream closed")) {
            return com.carbridge.app.HuClient.Fault.EOF;
        }
        return com.carbridge.app.HuClient.Fault.SILENT;
    }

    private int alivePorts() {
        int i = 0;
        for (int i2 : com.carbridge.carplay.proto.HuProto.WIFI_PORTS) {
            if (probePort(i2)) {
                i++;
            }
        }
        return i;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public java.lang.String diagnose() {
        return "步骤=" + stepName(this.faultAtStep) + " 故障=" + this.fault + " Car+端口=" + alivePorts() + "/6 推流后模块状态=" + (this.moduleSeen ? "已收到" : "未收到");
    }

    private boolean shouldReconnect(java.lang.String str) {
        if (alivePorts() == 0) {
            log("诊断 " + str + " → 对端监听端口已全部消失，服务不在，重连");
            return true;
        }
        switch (this.fault.ordinal()) {
            case 1:
                log("诊断 " + str + " → 对端已结束会话，等它释放槽位再重连");
                return true;
            case MAX_OBSERVATIONS /* 2 */:
                log("诊断 " + str + " → 通道槽位冲突（对端重复连接会全关 socket），先等残留连接释放，不立刻重试");
                sleep(1200L);
                return true;
            case 3:
                log("诊断 " + str + " → 对端慢、读线程未挂上，**不重置**，继续观察");
                return false;
            default:
                log("诊断 " + str + " → 未见对端已死的证据，继续观察");
                return false;
        }
    }

    private static java.lang.String stepName(com.carbridge.app.HuClient.Step step) {
        return step == null ? "-" : step.name();
    }

    private boolean ctrlLoop(int i) {
        boolean z = false;
        if (i > 0) {
            try {
                if (this.socks[0] != null) {
                    this.socks[0].setSoTimeout(i);
                }
            } catch (java.lang.Throwable th) {
                if (this.running) {
                    log("控制通道异常：" + th);
                }
            }
        }
        while (this.running) {
            try {
                byte[] exact = readExact(this.socks[0], 8);
                if (exact == null) {
                    if (this.running) {
                        log("控制通道被对端关闭");
                    }
                    markFault(com.carbridge.app.HuClient.Fault.EOF);
                    return z;
                }
                int iCmdLength = com.carbridge.carplay.proto.HuProto.cmdLength(exact);
                int iCmdService = com.carbridge.carplay.proto.HuProto.cmdService(exact);
                byte[] exact2 = iCmdLength > 0 ? readExact(this.socks[0], iCmdLength) : new byte[0];
                if (exact2 == null) {
                    log("控制通道负载读取失败");
                    return z;
                }
                trace("RX", 0, iCmdService, exact2);
                this.lastRxAtMs = java.lang.System.currentTimeMillis();
                z = true;
                onCmd(iCmdService, exact2);
            } catch (java.net.SocketTimeoutException e) {
                markFault(com.carbridge.app.HuClient.Fault.SILENT);
                return z;
            } catch (java.io.IOException e2) {
                if (this.running) {
                    log("控制通道读失败：" + e2);
                }
                markFault(classify(e2));
                return z;
            }
        }
        return z;
    }

    private static java.lang.String moduleName(int i) {
        switch (i) {
            case 1:
                return "PHONE";
            case MAX_OBSERVATIONS /* 2 */:
                return "NAVI";
            case 3:
                return "MUSIC";
            case 4:
                return "VR";
            case 5:
                return "CONNECT";
            case HU_SLOTS /* 6 */:
                return "MIC";
            case 7:
            default:
                return "M" + i;
            case 8:
                return "CRUISE";
            case 9:
                return "CRUISE_FOLLOW";
        }
    }

    private void onCmd(int i, byte[] bArr) {
        java.lang.String str;
        switch (i) {
            case 65538:
                enterStep(com.carbridge.app.HuClient.Step.VERSION);
                log("← 版本匹配，回统计信息");
                send(0, 98343, com.carbridge.carplay.proto.HuProto.statistics());
                break;
            case 65540:
                enterStep(com.carbridge.app.HuClient.Step.DEVICE_INFO);
                java.lang.String str2 = com.carbridge.carplay.proto.HuProto.deviceField(bArr, 14) + " / " + com.carbridge.carplay.proto.HuProto.deviceField(bArr, 8) + " / " + bArr.length + "B";
                this.sink.onPhoneInfo(str2);
                log("← 手机设备信息：" + str2);
                send(0, 98307, com.carbridge.carplay.proto.HuProto.huInfo(this.width, this.height));
                log("→ HU_INFO（display=" + this.width + "x" + this.height + "）");
                break;
            case 65544:
                enterStep(com.carbridge.app.HuClient.Step.ENCODER);
                long jMax = this.encoderInitSentAtMs > 0 ? java.lang.Math.max(0L, java.lang.System.currentTimeMillis() - this.encoderInitSentAtMs) : -1L;
                int i2 = 0;
                int[] encoderInfo = com.carbridge.carplay.proto.HuProto.parseEncoderInfo(bArr);
                if (encoderInfo != null) {
                    i2 = encoderInfo[MAX_OBSERVATIONS];
                    log("← 手机编码参数 " + encoderInfo[0] + "x" + encoderInfo[1] + "@" + encoderInfo[MAX_OBSERVATIONS] + "（ENC_INIT 应答延迟 " + jMax + "ms）");
                    if (encoderInfo[0] > 0 && encoderInfo[1] > 0) {
                        this.encW = encoderInfo[0];
                        this.encH = encoderInfo[1];
                    }
                    if (encoderInfo[MAX_OBSERVATIONS] > 0) {
                        this.encFps = encoderInfo[MAX_OBSERVATIONS];
                    }
                    pushStreamSize();
                    notifyEncoderInfo();
                }
                boolean z = i2 <= 0 || i2 == this.fps;
                if (z) {
                    scheduleStartStreaming();
                } else if (this.streamStarted && this.liveFrameRateChangeDone) {
                    log("收到动态改帧率后的旧编码应答 @" + i2 + "（当前目标 @" + this.fps + "），忽略，不打断会话");
                } else if (!z && !this.frameRateChanging && !this.encoderNormalized) {
                    this.frameRateChanging = true;
                    send(0, 98316, com.carbridge.carplay.proto.HuProto.videoFrameRate(this.fps));
                    log("应答是 @" + i2 + "（手机短路回显旧值，编码器其实还在）→ 发 0x1800C 把帧率改成 @" + this.fps + "，等它回 0x1000D");
                } else if (this.encoderNormalized && i2 >= 20) {
                    int i3 = this.fps;
                    this.fps = i2;
                    this.learnedFps = i2;
                    this.liveFrameRateChange = false;
                    this.liveFrameRateChangeDone = true;
                    log("目标 @" + i3 + " 未被接受，回退到 Car+ 实际 @" + i2 + "（继续推流，避免重连循环）");
                    notifyEncoderInfo();
                    scheduleStartStreaming();
                } else {
                    java.lang.StringBuilder sbAppend = new java.lang.StringBuilder().append("应答不合格（");
                    if (z) {
                        str = "延迟仅 " + jMax + "ms，手机在短路回显、虚拟显示没建完";
                    } else {
                        str = "帧率 @" + i2 + "≠@" + this.fps + "，帧率归一化也没救回来";
                    }
                    log(sbAppend.append(str).append("）→ 不推流，立刻拆会话重连").toString());
                    closeAll();
                }
                break;
            case 65549:
                boolean z2 = this.liveFrameRateChange;
                this.liveFrameRateChange = false;
                this.liveFrameRateChangeDone = true;
                this.frameRateChanging = false;
                this.encoderNormalized = true;
                if (z2) {
                    log("← 运行中帧率变更完成 @" + this.fps + "，继续原视频流（不重发 ENC_INIT）");
                    notifyEncoderInfo();
                } else {
                    this.encoderInitSentAtMs = java.lang.System.currentTimeMillis();
                    send(0, 98311, com.carbridge.carplay.proto.HuProto.encoderInit(this.width, this.height, this.fps));
                    log("← 帧率变更已完成，重发编码器初始化取一致的 @" + this.fps);
                }
                break;
            case 65560:
                log("← 手机亮屏");
                break;
            case 65574:
                this.moduleSeen = true;
                java.util.List<int[]> moduleStatus = com.carbridge.carplay.proto.HuProto.parseModuleStatus(bArr);
                if (!moduleStatus.isEmpty()) {
                    java.lang.StringBuilder sb = new java.lang.StringBuilder();
                    boolean z3 = false;
                    for (int[] iArr : moduleStatus) {
                        if (sb.length() > 0) {
                            sb.append(' ');
                        }
                        sb.append(moduleName(iArr[0])).append('=').append(iArr[1]);
                        if (iArr[0] == 1 && iArr[1] != 0) {
                            z3 = true;
                        }
                    }
                    this.phoneModuleActive = z3;
                    log("← 模块状态 " + ((java.lang.Object) sb) + (z3 ? "  **通话中**" : ""));
                    try {
                        this.sink.onPhoneCallState(z3);
                    } catch (java.lang.Throwable th) {
                        com.carbridge.carplay.util.Diag.w("HuClient", th);
                        return;
                    }
                } else {
                    log("← 模块状态 " + bArr.length + "B " + com.carbridge.carplay.proto.HuProto.hex(bArr, 12));
                }
                break;
            case 65591:
                log("⚠ 对端上报连接异常 exceptionType=" + com.carbridge.carplay.proto.HuProto.hex(bArr, 8) + "（编码器初始化失败时会出现，值 1 = VIDEO_ENCODER_INIT 失败）");
                break;
            case 65611:
                enterStep(com.carbridge.app.HuClient.Step.AUTH);
                send(0, 98378, new byte[0]);
                this.encoderInitSentAtMs = java.lang.System.currentTimeMillis();
                send(0, 98311, com.carbridge.carplay.proto.HuProto.encoderInit(this.width, this.height, this.fps));
                log("→ 鉴权结果 + 编码参数 " + this.width + "x" + this.height + "@" + this.fps);
                break;
            case 65617:
                enterStep(com.carbridge.app.HuClient.Step.FEATURE_CONFIG);
                send(0, 98386, com.carbridge.carplay.proto.HuProto.featureConfig(this.connectType, this.autoFit));
                log("→ 特征配置（CONNECT_TYPE=" + this.connectType + (this.autoFit ? "，DISABLE_RESOLUTION_OPTION=1" : "") + "）");
                break;
            default:
                log("← 未知消息 " + i + "（0x" + java.lang.Integer.toHexString(i) + "）len=" + bArr.length + " " + com.carbridge.carplay.proto.HuProto.hex(bArr, 12));
                break;
        }
    }

    private void scheduleStartStreaming() {
        if (this.startScheduled || this.streamStarted) {
            return;
        }
        this.startScheduled = true;
        final int i = this.mediaGen;
        new java.lang.Thread(new java.lang.Runnable() { // from class: com.carbridge.app.HuClient.2

            @Override // java.lang.Runnable
            public void run() {
                try {
                    java.lang.Thread.sleep(600L);
                    if (HuClient.this.running && i == HuClient.this.mediaGen && HuClient.this.startScheduled) {
                        HuClient.this.startScheduled = false;
                        if (!HuClient.this.streamStarted) {
                            HuClient.this.streamStarted = true;
                            HuClient.this.log((HuClient.this.send(0, 98313, new byte[0]) ? "→ 开始推流" : "⚠ 投屏就绪后 START 发送失败") + "（已等投屏 " + com.carbridge.app.HuClient.CAST_READY_DELAY_MS + "ms，等对端上报模块状态 / 视频帧）");
                            HuClient.this.startVideoReader();
                            HuClient.this.startAudioReader();
                            HuClient.this.startTtsDrain();
                        }
                    }
                } catch (java.lang.InterruptedException e) {
                }
            }
        }, "hu-enc-start-g" + i).start();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startVideoReader() {
        if (this.videoReading) {
            return;
        }
        this.videoReading = true;
        final int i = this.mediaGen;
        new java.lang.Thread(new java.lang.Runnable() { // from class: com.carbridge.app.HuClient.3

            @Override // java.lang.Runnable
            public void run() {
                try {
                    HuClient.this.videoLoop(i);
                } finally {
                    if (i == HuClient.this.mediaGen) {
                        HuClient.this.videoReading = false;
                    }
                }
            }
        }, "hu-video-g" + i).start();
        log("视频读取线程已启动（gen=" + i + "）");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void videoLoop(int i) {
        java.lang.String str;
        java.net.Socket socket = this.socks[1];
        if (socket == null) {
            log("视频通道不存在");
            return;
        }
        setState(com.carbridge.app.HuClient.State.STREAMING, "投屏中");
        while (this.running && i == this.mediaGen) {
            try {
                try {
                    byte[] exact = readExact(socket, 12);
                    if (exact == null) {
                        if (this.running) {
                            log("视频通道被对端 FIN 关闭，重启会话");
                            closeAll();
                            return;
                        }
                        return;
                    }
                    int iMediaLength = com.carbridge.carplay.proto.HuProto.mediaLength(exact);
                    int iMediaTimestamp = com.carbridge.carplay.proto.HuProto.mediaTimestamp(exact);
                    com.carbridge.carplay.proto.HuProto.mediaService(exact);
                    if (iMediaLength <= 0 || iMediaLength > 8388608) {
                        log("视频帧长度异常 " + iMediaLength + "，重启会话");
                        closeAll();
                        return;
                    }
                    byte[] n = readN(socket, iMediaLength);
                    if (n == null) {
                        return;
                    }
                    this.lastRxAtMs = java.lang.System.currentTimeMillis();
                    if (this.firstFrameAt == 0) {
                        this.firstFrameAt = java.lang.System.currentTimeMillis();
                        enterStep(com.carbridge.app.HuClient.Step.STREAMING);
                        log("★ 首帧到达：len=" + iMediaLength + " 头 " + com.carbridge.carplay.proto.HuProto.hex(n, 12) + "（首帧延迟 " + (this.firstFrameAt - this.startedAt) + "ms）");
                    }
                    this.frameCount++;
                    this.byteCount += (long) iMediaLength;
                    com.carbridge.carplay.bridge.CarPlayBridge carPlayBridge = this.carPlayBridge;
                    if (carPlayBridge != null) {
                        carPlayBridge.submitVideo(n, iMediaLength, iMediaTimestamp);
                    }
                    this.sink.onVideo(n, iMediaLength, iMediaTimestamp);
                    if (this.frameCount % 60 == 0) {
                        this.sink.onMetrics(this.frameCount, this.byteCount, this.firstFrameAt - this.startedAt);
                    }
                } catch (java.lang.Throwable th) {
                    if (this.running) {
                        if (java.lang.String.valueOf(th).contains("Socket closed")) {
                            str = "视频通道被本端关闭（会话已在别处拆掉）";
                        } else {
                            str = "视频通道读异常，重启会话：" + th;
                        }
                        log(str);
                        closeAll();
                        return;
                    }
                    return;
                }
            } catch (java.lang.Throwable th2) {
                if (this.running) {
                    log("视频通道异常：" + th2);
                    return;
                }
                return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startAudioReader() {
        if (this.audioReading) {
            log("音频读取线程已在跑，跳过启动");
            return;
        }
        this.audioReading = true;
        final int i = this.mediaGen;
        new java.lang.Thread(new java.lang.Runnable() { // from class: com.carbridge.app.HuClient.4

            @Override // java.lang.Runnable
            public void run() {
                try {
                    HuClient.this.audioLoop(i);
                } finally {
                    if (i == HuClient.this.mediaGen) {
                        HuClient.this.audioReading = false;
                    }
                }
            }
        }, "hu-audio-g" + i).start();
        log("音频读取线程已启动（gen=" + i + "）");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void audioLoop(int i) {
        java.lang.String str;
        int i2 = 0;
        long j = 0;
        while (this.running && i == this.mediaGen) {
            try {
                java.net.Socket socket = this.socks[MAX_OBSERVATIONS];
                if (socket == null) {
                    log("音频通道不存在");
                    return;
                }
                byte[] n = readN(socket, 12);
                if (n == null) {
                    if (this.running) {
                        log("音频通道关闭（gen=" + i + "）");
                        return;
                    }
                    return;
                }
                int iMediaLength = com.carbridge.carplay.proto.HuProto.mediaLength(n);
                int iMediaTimestamp = com.carbridge.carplay.proto.HuProto.mediaTimestamp(n);
                int iMediaService = com.carbridge.carplay.proto.HuProto.mediaService(n);
                if (iMediaLength < 0 || iMediaLength > 1048576) {
                    log("音频包长度非法 " + iMediaLength + "，停止音频读取");
                    return;
                }
                byte[] n2 = iMediaLength == 0 ? new byte[0] : readN(socket, iMediaLength);
                if (n2 == null) {
                    return;
                }
                if (iMediaService == 196609) {
                    int[] mediaInit = parseMediaInit(n2);
                    java.lang.StringBuilder sbAppend = new java.lang.StringBuilder().append("← MEDIA_INIT 音频格式 ");
                    if (mediaInit != null) {
                        str = mediaInit[0] + "Hz/" + mediaInit[1] + "ch/" + mediaInit[MAX_OBSERVATIONS] + "bit";
                    } else {
                        str = "解析失败 " + com.carbridge.carplay.proto.HuProto.hex(n2, 16);
                    }
                    log(sbAppend.append(str).toString());
                } else if (iMediaService == 196614) {
                    i2++;
                    j += (long) iMediaLength;
                    if (i2 == 1) {
                        log("★ 音频数据首包 len=" + iMediaLength + " ts=" + iMediaTimestamp + " 头 " + com.carbridge.carplay.proto.HuProto.hex(n2, 16));
                    } else if (i2 == 200) {
                        log("音频数据已收 " + i2 + " 包 / " + j + "B");
                    }
                    com.carbridge.carplay.bridge.CarPlayBridge carPlayBridge = this.carPlayBridge;
                    if (carPlayBridge != null) {
                        if (this.phoneModuleActive) {
                            carPlayBridge.submitTelephonyAudio(n2, iMediaLength, iMediaTimestamp);
                        } else {
                            carPlayBridge.submitAudio(n2, iMediaLength, iMediaTimestamp);
                        }
                    }
                }
            } catch (java.lang.Throwable th) {
                if (this.running) {
                    log("音频通道异常：" + th);
                    return;
                }
                return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startTtsDrain() {
        if (this.ttsReading) {
            return;
        }
        this.ttsReading = true;
        final int i = this.mediaGen;
        new java.lang.Thread(new java.lang.Runnable() { // from class: com.carbridge.app.HuClient.5

            @Override // java.lang.Runnable
            public void run() {
                byte[] bArr = new byte[8192];
                long j = 0;
                int i2 = 0;
                try {
                    java.net.Socket socket = HuClient.this.socks[3];
                    if (socket == null) {
                        if (i == HuClient.this.mediaGen) {
                            HuClient.this.ttsReading = false;
                            return;
                        }
                        return;
                    }
                    java.io.InputStream inputStream = socket.getInputStream();
                    while (HuClient.this.running && i == HuClient.this.mediaGen) {
                        byte[] n = com.carbridge.app.HuClient.readN(socket, 12);
                        if (n == null) {
                            if (i == HuClient.this.mediaGen) {
                                HuClient.this.ttsReading = false;
                                return;
                            }
                            return;
                        }
                        int iMediaLength = com.carbridge.carplay.proto.HuProto.mediaLength(n);
                        if (iMediaLength < 0 || iMediaLength > 1048576) {
                            if (i == HuClient.this.mediaGen) {
                                HuClient.this.ttsReading = false;
                                return;
                            }
                            return;
                        }
                        int i3 = 0;
                        while (i3 < iMediaLength) {
                            int i4 = inputStream.read(bArr, 0, java.lang.Math.min(bArr.length, iMediaLength - i3));
                            if (i4 < 0) {
                                if (i == HuClient.this.mediaGen) {
                                    HuClient.this.ttsReading = false;
                                    return;
                                }
                                return;
                            }
                            i3 += i4;
                        }
                        j += (long) iMediaLength;
                        i2++;
                        if (i2 == 1) {
                            HuClient.this.log("← TTS 通道有数据（已排空，暂不转发）len=" + iMediaLength);
                        }
                    }
                    if (i == HuClient.this.mediaGen) {
                        HuClient.this.ttsReading = false;
                    }
                } catch (java.lang.Throwable th) {
                    if (i == HuClient.this.mediaGen) {
                        HuClient.this.ttsReading = false;
                    }
                }
                if (j > 0) {
                    HuClient.this.log("TTS 通道共排空 " + i2 + " 包 / " + j + "B");
                }
            }
        }, "hu-tts-drain").start();
    }

    private static int[] parseMediaInit(byte[] bArr) {
        int i = 0;
        int i2 = 0;
        int i3 = 0;
        int i4 = 0;
        while (i4 < bArr.length) {
            try {
                int i5 = i4;
                i4++;
                int i6 = bArr[i5] & 255;
                int i7 = i6 >>> 3;
                int i8 = i6 & 7;
                if (i8 == 0) {
                    int i9 = 0;
                    int i10 = 0;
                    while (i4 < bArr.length) {
                        int i11 = i4;
                        i4++;
                        int i12 = bArr[i11] & 255;
                        i9 |= (i12 & 127) << i10;
                        if ((i12 & 128) == 0) {
                            break;
                        }
                        i10 += 7;
                    }
                    if (i7 == 1) {
                        i = i9;
                    } else if (i7 == MAX_OBSERVATIONS) {
                        i2 = i9;
                    } else if (i7 == 3) {
                        i3 = i9;
                    }
                } else if (i8 == MAX_OBSERVATIONS) {
                    int i13 = 0;
                    int i14 = 0;
                    while (i4 < bArr.length) {
                        int i15 = i4;
                        i4++;
                        int i16 = bArr[i15] & 255;
                        i13 |= (i16 & 127) << i14;
                        if ((i16 & 128) == 0) {
                            break;
                        }
                        i14 += 7;
                    }
                    i4 += i13;
                } else {
                    return null;
                }
            } catch (java.lang.Throwable th) {
                return null;
            }
        }
        if (i > 0) {
            return new int[]{i, i2, i3};
        }
        return null;
    }

    public boolean sendHardKey(int i) {
        final java.net.Socket socket = this.socks[5];
        if (socket == null || !this.running) {
            log("硬键发送失败：TOUCH 通道没连上");
            return false;
        }
        int i2 = i;
        if (i2 == MAX_OBSERVATIONS && this.phoneModuleActive) {
            i2 = 3;
            log("车机按了接听键，但当前通话中 → 改发「挂断」");
        }
        final int i3 = i2;
        android.os.Handler handler = this.touchHandler;
        if (handler == null) {
            this.touchHandler = startTouchThread();
            handler = this.touchHandler;
        }
        handler.post(new java.lang.Runnable() { // from class: com.carbridge.app.HuClient.6

            @Override // java.lang.Runnable
            public void run() {
                try {
                    java.io.OutputStream outputStream = socket.getOutputStream();
                    byte[] bArrFrame = com.carbridge.carplay.proto.HuProto.frame(com.carbridge.app.HuClient.HU_SLOTS, 425992, com.carbridge.carplay.proto.HuProto.pbInt(1, i3));
                    synchronized (HuClient.this) {
                        outputStream.write(bArrFrame);
                        outputStream.flush();
                    }
                    HuClient.this.log("→ 车机硬键 " + com.carbridge.carplay.proto.HuProto.CarHardKey.name(i3) + "（keycode=" + i3 + "）写入 " + bArrFrame.length + "B");
                } catch (java.lang.Throwable th) {
                    HuClient.this.log("硬键发送失败：" + th);
                }
            }
        });
        return true;
    }

    public boolean sendTouch(final int i, final int i2, final int i3) {
        final java.net.Socket socket = this.socks[5];
        if (socket == null || !this.running) {
            return false;
        }
        android.os.Handler handler = this.touchHandler;
        if (handler == null) {
            this.touchHandler = startTouchThread();
            handler = this.touchHandler;
        }
        handler.post(new java.lang.Runnable() { // from class: com.carbridge.app.HuClient.7

            @Override // java.lang.Runnable
            public void run() {
                try {
                    java.io.OutputStream outputStream = socket.getOutputStream();
                    byte[] bArrFrame = com.carbridge.carplay.proto.HuProto.frame(com.carbridge.app.HuClient.HU_SLOTS, 425985, com.carbridge.carplay.proto.HuProto.touch(i, i2, i3));
                    synchronized (HuClient.this) {
                        outputStream.write(bArrFrame);
                        outputStream.flush();
                    }
                    long jCurrentTimeMillis = java.lang.System.currentTimeMillis();
                    if (jCurrentTimeMillis - HuClient.this.lastTouchLogMs > 1500) {
                        HuClient.this.lastTouchLogMs = jCurrentTimeMillis;
                        int iAccess$1804 = com.carbridge.app.HuClient.access$1804(HuClient.this);
                        if (iAccess$1804 <= 5 || iAccess$1804 % 200 == 0) {
                            HuClient.this.log("→ 触摸#" + iAccess$1804 + " action=" + i + " (" + i2 + "," + i3 + ") 写入 " + bArrFrame.length + "B CTRL(socks[5]=" + (socket.isClosed() ? "closed" : "ok") + ")");
                        }
                    }
                } catch (java.lang.Throwable th) {
                    HuClient.this.log("触摸发送失败：" + th);
                }
            }
        });
        return true;
    }

    public boolean sendSingleClick(final int i, final int i2) {
        final java.net.Socket socket = this.socks[5];
        if (socket == null || !this.running) {
            return false;
        }
        android.os.Handler handler = this.touchHandler;
        if (handler == null) {
            this.touchHandler = startTouchThread();
            handler = this.touchHandler;
        }
        handler.post(new java.lang.Runnable() { // from class: com.carbridge.app.HuClient.8

            @Override // java.lang.Runnable
            public void run() {
                try {
                    java.io.OutputStream outputStream = socket.getOutputStream();
                    byte[] bArrFrame = com.carbridge.carplay.proto.HuProto.frame(com.carbridge.app.HuClient.HU_SLOTS, 425989, com.carbridge.carplay.proto.HuProto.singleClick(i, i2));
                    synchronized (HuClient.this) {
                        outputStream.write(bArrFrame);
                        outputStream.flush();
                    }
                    HuClient.this.log("→ 单击指令 (" + i + "," + i2 + ") 写入 " + bArrFrame.length + "B");
                } catch (java.lang.Throwable th) {
                    HuClient.this.log("单击发送失败：" + th);
                }
            }
        });
        return true;
    }

    private synchronized android.os.Handler startTouchThread() {
        if (this.touchHandler != null) {
            return this.touchHandler;
        }
        this.touchThread = new android.os.HandlerThread("hu-touch");
        this.touchThread.start();
        this.touchHandler = new android.os.Handler(this.touchThread.getLooper());
        return this.touchHandler;
    }

    private synchronized void stopTouchThread() {
        android.os.Handler handler = this.touchHandler;
        this.touchHandler = null;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (this.touchThread != null) {
            this.touchThread.quitSafely();
            this.touchThread = null;
        }
    }

    private void startHeartbeat() {
        final int i = this.hbGen + 1;
        this.hbGen = i;
        new java.lang.Thread(new java.lang.Runnable() { // from class: com.carbridge.app.HuClient.9

            @Override // java.lang.Runnable
            public void run() {
                boolean z;
                int i2 = 0;
                int i3 = 0;
                while (HuClient.this.running && HuClient.this.hbGen == i) {
                    com.carbridge.app.HuClient.sleep(1000L);
                    if (HuClient.this.running && HuClient.this.hbGen == i) {
                        try {
                            boolean zSend = HuClient.this.send(1, 131074, new byte[0]);
                            HuClient.this.send(0, 131074, new byte[0]);
                            if (!zSend) {
                                i3++;
                                HuClient.this.log("视频通道心跳写失败 ×" + i3 + "，准备重连");
                                if (i3 >= 3) {
                                    com.carbridge.app.HuClient.access$1908(HuClient.this);
                                    HuClient.this.closeAll();
                                    return;
                                }
                            } else {
                                i3 = 0;
                                i2++;
                                if (i2 == 1 || i2 == com.carbridge.app.HuClient.DEFAULT_TARGET_FPS) {
                                    HuClient.this.log("心跳 ×" + i2 + "（VIDEO+CMD）");
                                }
                                if (HuClient.this.step == com.carbridge.app.HuClient.Step.ENCODER && HuClient.this.firstFrameAt == 0 && HuClient.this.encoderAtMs > 0) {
                                    long jMax = java.lang.Math.max(0L, java.lang.System.currentTimeMillis() - HuClient.this.encoderAtMs);
                                    if (HuClient.this.moduleSeen) {
                                        z = jMax > 20000;
                                    } else {
                                        z = jMax > com.carbridge.app.HuClient.NO_PROGRESS_WAIT_MS;
                                    }
                                    if (z) {
                                        java.lang.String strDiagnose = HuClient.this.diagnose();
                                        if (HuClient.this.moduleSeen) {
                                            HuClient.this.log("有进展（已收到模块状态）但 " + jMax + "ms 仍无首帧，停：" + strDiagnose);
                                        } else {
                                            HuClient.this.log("对端从未上报模块状态（编码链路未启动），" + jMax + "ms 判停：" + strDiagnose);
                                        }
                                        com.carbridge.app.HuClient.access$1908(HuClient.this);
                                        HuClient.this.closeAll();
                                        return;
                                    }
                                }
                            }
                        } catch (java.lang.Throwable th) {
                            HuClient.this.log("心跳失败：" + th);
                            return;
                        }
                    } else {
                        return;
                    }
                }
            }
        }, "hu-heartbeat").start();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized boolean send(int i, int i2, byte[] bArr) {
        java.net.Socket socket = this.socks[i];
        if (socket == null) {
            return false;
        }
        try {
            java.io.OutputStream outputStream = socket.getOutputStream();
            outputStream.write(com.carbridge.carplay.proto.HuProto.frame(com.carbridge.carplay.proto.HuProto.CHANNELS[i], i2, bArr));
            outputStream.flush();
            trace("TX", i, i2, bArr);
            return true;
        } catch (java.lang.Throwable th) {
            log("发送失败（service=" + i2 + "）：" + th);
            return false;
        }
    }

    private void trace(java.lang.String str, int i, int i2, byte[] bArr) {
        java.lang.String str2;
        long jMax = java.lang.Math.max(0L, java.lang.System.currentTimeMillis() - this.startedAt);
        int length = bArr == null ? 0 : bArr.length;
        if (length == 0) {
            str2 = "";
        } else {
            java.lang.StringBuilder sb = new java.lang.StringBuilder();
            int iMin = java.lang.Math.min(length, 64);
            for (int i3 = 0; i3 < iMin; i3++) {
                sb.append(java.lang.String.format("%02x", java.lang.Integer.valueOf(bArr[i3] & 255)));
            }
            if (length > iMin) {
                sb.append("..(").append(length).append("B)");
            }
            str2 = " " + ((java.lang.Object) sb);
        }
        log(java.lang.String.format("F +%dms %s %s svc=0x%08X len=%d%s", java.lang.Long.valueOf(jMax), str, channelName(i), java.lang.Integer.valueOf(i2), java.lang.Integer.valueOf(length), str2));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static byte[] readN(java.net.Socket socket, int i) {
        try {
            return readExact(socket, i);
        } catch (java.lang.Throwable th) {
            return null;
        }
    }

    private static byte[] readExact(java.net.Socket socket, int i) throws java.io.IOException {
        if (socket == null) {
            return null;
        }
        java.io.InputStream inputStream = socket.getInputStream();
        byte[] bArr = new byte[i];
        int i2 = 0;
        while (true) {
            int i3 = i2;
            if (i3 < i) {
                int i4 = inputStream.read(bArr, i3, i - i3);
                if (i4 <= 0) {
                    return null;
                }
                i2 = i3 + i4;
            } else {
                return bArr;
            }
        }
    }

    private static void closeQuietly(java.net.Socket socket) {
        if (socket != null) {
            try {
                try { socket.close(); } catch (Exception ignored) { }
            } catch (java.lang.Throwable th) {
                com.carbridge.carplay.util.Diag.w("HuClient", th);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void sleep(long j) {
        try {
            java.lang.Thread.sleep(j);
        } catch (java.lang.InterruptedException e) {
        }
    }

    private void setState(com.carbridge.app.HuClient.State state, java.lang.String str) {
        this.state = state;
        this.sink.onState(state, str);
    }

    private void enterStep(com.carbridge.app.HuClient.Step step) {
        if (this.step == step) {
            return;
        }
        this.step = step;
        if (step == com.carbridge.app.HuClient.Step.ENCODER) {
            this.encoderAtMs = java.lang.System.currentTimeMillis();
        }
        this.sink.onStep(step);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void log(java.lang.String str) {
        this.sink.onLog(str);
    }
}
