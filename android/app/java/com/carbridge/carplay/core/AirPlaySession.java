package com.carbridge.carplay.core;

/* JADX INFO: loaded from: AirPlaySession.class */
public final class AirPlaySession {
    private final java.lang.String host;
    private final int port;
    private final com.carbridge.carplay.core.AirPlaySession.Listener listener;
    private java.net.Socket control;
    private java.net.Socket eventChannel;
    private volatile com.carbridge.carplay.pairing.ControlCipher eventReadCipher;
    private volatile com.carbridge.carplay.pairing.ControlCipher eventWriteCipher;
    private int hidReceived;
    private int eventRxLogged;
    private int eventDetailCnt;
    private long lastTypeLogMs;
    private int typeCount;
    private volatile java.net.Socket videoChannel;
    private java.net.DatagramSocket timingSocket;
    private java.net.DatagramSocket keepAliveSocket;
    private java.net.DatagramSocket audioSocket;
    private long audioConnId;
    private volatile com.carbridge.carplay.core.AirPlayStreamCrypto audioSealer;
    private com.carbridge.carplay.core.RtpAudio audioPacker;
    private volatile java.net.InetAddress audioAddr;
    private volatile int audioAccumLen;
    private volatile long audioRtpTs;
    private long audioPackets;
    private volatile boolean audioBroken;
    private volatile com.carbridge.carplay.core.AirPlayInfo info;
    private volatile com.carbridge.carplay.core.AirPlayCrypto videoCrypto;
    private volatile com.carbridge.carplay.core.AirPlayStreamCrypto videoSealer;
    private volatile byte[] cachedAvcC;
    private volatile int cachedW;
    private volatile int cachedH;
    private volatile com.carbridge.carplay.core.SplashFrame splash;
    private volatile int displayW;
    private volatile int displayH;
    private volatile long lastVideoAt;
    private volatile boolean splashSent;
    private volatile boolean splashConfigActive;
    private volatile byte[] pairSharedSecret;
    private volatile boolean videoBroken;
    private volatile boolean paired;
    private volatile com.carbridge.carplay.pairing.ControlCipher controlEncryptor;
    private volatile com.carbridge.carplay.pairing.ControlCipher controlDecryptor;
    private volatile com.carbridge.carplay.core.AirPlaySession.DecryptedStream controlPlain;
    private volatile java.lang.String setupCode;
    private volatile java.lang.String lastPairingId;
    private long streamConnectionId;
    private long streamStartNanos;
    private boolean running;
    private int audioNotConsumedSec;
    private boolean audioUseAac;
    private com.carbridge.carplay.core.AacLcEncoder aacEncoder;
    private java.net.DatagramSocket telephonySocket;
    private java.net.InetAddress telephonyAddr;
    private com.carbridge.carplay.core.RtpAudio telephonyPacker;
    private com.carbridge.carplay.core.AirPlayStreamCrypto telephonySealer;
    private long telephonyConnId;
    private volatile int telephonyAccumLen;
    private volatile long telephonyRtpTs;
    private long telephonyPackets;
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_BITRATE = 192000;
    private volatile java.lang.Thread sessionThread;
    private volatile long lastDumpMs;
    private static volatile com.carbridge.carplay.pairing.IdentityStore identityStore;
    private static volatile com.carbridge.carplay.pairing.Ed25519.KeyPair identityCache;
    private volatile java.lang.String sessionWifiMac;
    private boolean wifiMacResolved;
    private long vidCount;
    private long vidSumNanos;
    private long vidMaxNanos;
    private long vidLastLogMs;
    private long vidLastArriveNanos;
    private long vidGapSumNanos;
    private long vidGapMaxNanos;
    private long vidGapCount;
    private java.io.OutputStream videoOut;
    private long lastFbLogMs;
    private int errLogged;
    private long lastErrLogMs;
    private final java.lang.String sessionUuid = java.util.UUID.randomUUID().toString().toUpperCase(java.util.Locale.US);
    private byte[] audioAccum = new byte[5632];
    private volatile boolean configDirty = true;
    private final java.lang.Object videoSendLock = new java.lang.Object();
    private volatile boolean waitingForKeyFrame = true;
    private int lastCfgState = -1;
    private final java.util.concurrent.atomic.AtomicInteger cseq = new java.util.concurrent.atomic.AtomicInteger(1);
    private final java.lang.Object controlLock = new java.lang.Object();
    private int audioPort = -1;
    private long audioFormatBit = 2048;
    private long audioStreamType = 100;
    private java.lang.String audioTypeName = "media";
    private long lastAudioSampleTime = -1;
    private int telephonyPort = -1;
    private byte[] telephonyAccum = new byte[5632];
    private volatile long lastProgressMs = java.lang.System.currentTimeMillis();
    private volatile com.carbridge.carplay.core.AirPlaySession.State state = com.carbridge.carplay.core.AirPlaySession.State.IDLE;

    /* JADX INFO: loaded from: AirPlaySession$State.class */
    public enum State {
        IDLE,
        CONNECTING,
        SETTING_UP,
        STREAMING,
        ERROR,
        CLOSED
    }

    /* JADX INFO: loaded from: AirPlaySession$Listener.class */
    public interface Listener {
        void onState(com.carbridge.carplay.core.AirPlaySession.State state);

        void onLog(java.lang.String str);

        void onTouch(float f, float f2, boolean z);

        void onHardKey(int i);

        void onDisplaySize(int i, int i2);

        default void onDisplaySize(int i, int i2, int i3) {
            onDisplaySize(i, i2);
        }
    }

    public AirPlaySession(java.lang.String str, int i, com.carbridge.carplay.core.AirPlaySession.Listener listener) {
        this.host = str;
        this.port = i;
        this.listener = listener;
    }

    public com.carbridge.carplay.core.AirPlayInfo info() {
        return this.info;
    }

    public void setSplash(com.carbridge.carplay.core.SplashFrame splashFrame) {
        this.splash = splashFrame;
    }

    public void setControlCipher(com.carbridge.carplay.pairing.ControlCipher controlCipher, com.carbridge.carplay.pairing.ControlCipher controlCipher2) {
        this.controlEncryptor = controlCipher;
        this.controlDecryptor = controlCipher2;
        this.controlPlain = null;
        this.paired = (controlCipher == null || controlCipher2 == null) ? false : true;
        log(this.paired ? "控制通道：已配对（ChaCha20-Poly1305 加密）" : "控制通道：未配对（明文）");
    }

    public boolean isPaired() {
        return this.paired;
    }

    public java.lang.String pairingId() {
        return this.lastPairingId;
    }

    public void setSetupCode(java.lang.String str) {
        this.setupCode = (str == null || str.isEmpty()) ? null : str;
    }

    public com.carbridge.carplay.core.AirPlaySession.State state() {
        return this.state;
    }

    public void start() {
        if (this.running) {
            return;
        }
        this.running = true;
        com.carbridge.carplay.util.Bg.run("ap-session", new java.lang.Runnable() { // from class: com.carbridge.carplay.core.AirPlaySession.1
            @Override // java.lang.Runnable
            public void run() {
                com.carbridge.carplay.core.AirPlaySession.this.run();
            }
        });
        startWatchdog();
    }

    private void startWatchdog() {
        com.carbridge.carplay.util.Bg.run("ap-watchdog", new java.lang.Runnable() { // from class: com.carbridge.carplay.core.AirPlaySession.2
            @Override // java.lang.Runnable
            public void run() {
                while (com.carbridge.carplay.core.AirPlaySession.this.running) {
                    try {
                        java.lang.Thread.sleep(3000L);
                        if (!com.carbridge.carplay.core.AirPlaySession.this.running) {
                            return;
                        }
                        com.carbridge.carplay.core.AirPlaySession.State state = com.carbridge.carplay.core.AirPlaySession.this.state;
                        if (state != com.carbridge.carplay.core.AirPlaySession.State.STREAMING && state != com.carbridge.carplay.core.AirPlaySession.State.ERROR && state != com.carbridge.carplay.core.AirPlaySession.State.CLOSED) {
                            long jCurrentTimeMillis = java.lang.System.currentTimeMillis();
                            long j = jCurrentTimeMillis - com.carbridge.carplay.core.AirPlaySession.this.lastProgressMs;
                            if (j >= 8000 && jCurrentTimeMillis - com.carbridge.carplay.core.AirPlaySession.this.lastDumpMs >= 15000) {
                                com.carbridge.carplay.core.AirPlaySession.this.lastDumpMs = jCurrentTimeMillis;
                                com.carbridge.carplay.core.AirPlaySession.this.dumpStuckThreads(j);
                            }
                        }
                    } catch (java.lang.InterruptedException e) {
                        return;
                    }
                }
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dumpStuckThreads(long j) {
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        sb.append("⚠ 会话 ").append(j / 1000).append("s 无进展，当前状态 ").append(this.state);
        java.lang.Thread thread = this.sessionThread;
        if (thread != null) {
            sb.append("\n[").append(thread.getName()).append("] ").append(thread.getState());
            java.lang.StackTraceElement[] stackTrace = thread.getStackTrace();
            for (int i = 0; i < stackTrace.length && i < 14; i++) {
                sb.append("\n  at ").append(stackTrace[i]);
            }
        }
        for (java.util.Map.Entry<java.lang.Thread, java.lang.StackTraceElement[]> entry : java.lang.Thread.getAllStackTraces().entrySet()) {
            java.lang.Thread key = entry.getKey();
            if (key != thread && key != java.lang.Thread.currentThread() && key.getName().startsWith("carbridge-")) {
                sb.append("\n[").append(key.getName()).append("] ").append(key.getState());
                java.lang.StackTraceElement[] value = entry.getValue();
                for (int i2 = 0; i2 < value.length && i2 < 5; i2++) {
                    sb.append("\n  at ").append(value[i2]);
                }
            }
        }
        log(sb.toString());
    }

    public void stop() {
        this.running = false;
        closeQuietly(this.control);
        closeQuietly(this.eventChannel);
        closeQuietly(this.videoChannel);
        this.videoOut = null;
        if (this.timingSocket != null) {
            try { this.timingSocket.close(); } catch (Exception ignored) { }
        }
        if (this.keepAliveSocket != null) {
            try { this.keepAliveSocket.close(); } catch (Exception ignored) { }
        }
        if (this.audioSocket != null) {
            try { this.audioSocket.close(); } catch (Exception ignored) { }
        }
        if (this.telephonySocket != null) {
            try { this.telephonySocket.close(); } catch (Exception ignored) { }
        }
        if (this.aacEncoder != null) {
            this.aacEncoder.stop();
            this.aacEncoder = null;
        }
        setState(com.carbridge.carplay.core.AirPlaySession.State.CLOSED);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void run() {
        this.sessionThread = java.lang.Thread.currentThread();
        try {
            setState(com.carbridge.carplay.core.AirPlaySession.State.CONNECTING);
            startTimingResponder();
            this.control = new java.net.Socket();
            this.control.connect(new java.net.InetSocketAddress(this.host, this.port), 5000);
            this.control.setTcpNoDelay(true);
            this.control.setSoTimeout(8000);
            log("TCP 已连 " + this.host + ":" + this.port + "，开始配对阶段");
            pair();
            log("配对阶段结束 paired=" + this.paired + "，准备 SETUP /setup");
            announce();
            byte[] upVar = setup();
            int iAsInt = asInt(pick(upVar, "eventPort"), 0);
            int iAsInt2 = asInt(pick(upVar, "keepAlivePort"), 0);
            log("SETUP ok: eventPort=" + iAsInt + " keepAlivePort=" + iAsInt2);
            this.info = com.carbridge.carplay.core.AirPlayInfo.parse(com.carbridge.carplay.core.Bplist.read(requestRaw("GET", "/info", com.carbridge.carplay.core.Bplist.write(new java.util.LinkedHashMap()))));
            com.carbridge.carplay.core.AirPlayInfo.Display displayPrimaryDisplay = this.info.primaryDisplay();
            if (displayPrimaryDisplay != null) {
                log("车机显示 " + displayPrimaryDisplay.widthPixels + "x" + displayPrimaryDisplay.heightPixels + " maxFPS=" + displayPrimaryDisplay.maxFps + " uuid=" + displayPrimaryDisplay.uuid + " keepAliveSendStatsAsBody=" + this.info.keepAliveSendStatsAsBody);
                this.listener.onDisplaySize(displayPrimaryDisplay.widthPixels, displayPrimaryDisplay.heightPixels, displayPrimaryDisplay.maxFps);
                this.displayW = displayPrimaryDisplay.widthPixels;
                this.displayH = displayPrimaryDisplay.heightPixels;
            } else {
                log("WARN: /info 没有 displays，用 1200x512 兜底");
                this.displayW = 1200;
                this.displayH = 512;
            }
            dumpHidDevices();
            if (iAsInt > 0) {
                openEventChannel(iAsInt);
            } else {
                log("SETUP 没给 eventPort → 车机不会回传触摸/硬键，先继续推流");
            }
            this.streamStartNanos = java.lang.System.nanoTime();
            requestRaw("RECORD", "/", null);
            sendModesChanged();
            setState(com.carbridge.carplay.core.AirPlaySession.State.SETTING_UP);
            openScreenStream(displayPrimaryDisplay);
            openAudioStream();
            startKeepalives(iAsInt2);
            setState(com.carbridge.carplay.core.AirPlaySession.State.STREAMING);
            log("AirPlay 会话已建立，开始推流");
        } catch (java.lang.Throwable th) {
            log("AirPlay 会话失败: " + th);
            setState(com.carbridge.carplay.core.AirPlaySession.State.ERROR);
        }
    }

    private void startTimingResponder() throws java.io.IOException {
        this.timingSocket = new java.net.DatagramSocket(0);
        final java.net.DatagramSocket datagramSocket = this.timingSocket;
        com.carbridge.carplay.util.Bg.run("ap-timing", new java.lang.Runnable() { // from class: com.carbridge.carplay.core.AirPlaySession.3

            @Override // java.lang.Runnable
            public void run() {
                byte[] bArr = new byte[128];
                while (AirPlaySession.this.running && datagramSocket != null && !datagramSocket.isClosed()) {
                    java.net.DatagramPacket datagramPacket = new java.net.DatagramPacket(bArr, bArr.length);
                    try {
                        datagramSocket.receive(datagramPacket);
                        if (com.carbridge.carplay.core.TimingResponder.isTimingRequest(bArr)) {
                            byte[] bArr2 = new byte[datagramPacket.getLength()];
                            java.lang.System.arraycopy(bArr, 0, bArr2, 0, datagramPacket.getLength());
                            byte[] bArrReply = com.carbridge.carplay.core.TimingResponder.reply(bArr2, com.carbridge.carplay.core.TimingResponder.ntpNow(AirPlaySession.this.streamStartNanos));
                            datagramSocket.send(new java.net.DatagramPacket(bArrReply, bArrReply.length, datagramPacket.getAddress(), datagramPacket.getPort()));
                        }
                    } catch (java.lang.Exception e) {
                        if (AirPlaySession.this.running) {
                            AirPlaySession.this.log("timing 响应失败: " + e);
                            return;
                        }
                        return;
                    }
                }
            }
        });
    }

    private void pair() {
        if (this.paired || doPairVerify()) {
            return;
        }
        log("pair-verify 未通过 → 尝试 pair-setup 注册身份");
        if (doPairSetup()) {
            log("pair-setup 完成，重新 pair-verify");
            doPairVerify();
        } else {
            log("pair-setup 失败，继续走匿名 et=1 路径");
        }
    }

    private boolean doPairVerify() {
        try {
            final java.util.concurrent.atomic.AtomicReference atomicReference = new java.util.concurrent.atomic.AtomicReference();
            com.carbridge.carplay.pairing.PairVerifySession pairVerifySession = new com.carbridge.carplay.pairing.PairVerifySession(new com.carbridge.carplay.pairing.PairVerifySession.Listener() { // from class: com.carbridge.carplay.core.AirPlaySession.4

                public void onLog(java.lang.String str) {
                    AirPlaySession.this.log(str);
                }

                public void onTlvOut(byte[] bArr) {
                    atomicReference.set(bArr);
                }
            }, identity());
            pairVerifySession.start();
            byte[] bArr = (byte[]) atomicReference.getAndSet(null);
            if (bArr == null) {
                log("pair-verify M1 未生成");
                return false;
            }
            pairVerifySession.onTlv(requestTlv8("/pair-verify", bArr, false));
            if (pairVerifySession.state() == com.carbridge.carplay.pairing.PairVerifySession.State.FAILED) {
                return false;
            }
            byte[] bArr2 = (byte[]) atomicReference.getAndSet(null);
            if (bArr2 == null) {
                log("pair-verify M3 未生成");
                return false;
            }
            pairVerifySession.onTlv(requestTlv8("/pair-verify", bArr2, false));
            if (pairVerifySession.state() != com.carbridge.carplay.pairing.PairVerifySession.State.VERIFIED) {
                log("pair-verify 未完成（对端配对库里可能没有我们）");
                return false;
            }
            setControlCipher(pairVerifySession.writeCipher(), pairVerifySession.readCipher());
            this.paired = true;
            this.lastPairingId = pairVerifySession.pairingId();
            this.pairSharedSecret = pairVerifySession.sharedSecret();
            log("★ pair-verify 成功，控制通道转加密（id=" + shortId(this.lastPairingId) + "）");
            return true;
        } catch (java.lang.Throwable th) {
            log("pair-verify 异常: " + th);
            return false;
        }
    }

    private boolean doPairSetup() {
        byte[] bArr;
        try {
            final java.util.concurrent.atomic.AtomicReference atomicReference = new java.util.concurrent.atomic.AtomicReference();
            com.carbridge.carplay.pairing.PairSetupSession pairSetupSession = new com.carbridge.carplay.pairing.PairSetupSession(new com.carbridge.carplay.pairing.PairSetupSession.Listener() { // from class: com.carbridge.carplay.core.AirPlaySession.5

                public void onLog(java.lang.String str) {
                    AirPlaySession.this.log(str);
                }

                public void onTlvOut(byte[] bArr2) {
                    atomicReference.set(bArr2);
                }
            }, identity(), this.setupCode);
            pairSetupSession.start();
            for (int i = 0; i < 4 && (bArr = (byte[]) atomicReference.getAndSet(null)) != null && pairSetupSession.state() != com.carbridge.carplay.pairing.PairSetupSession.State.FAILED && pairSetupSession.state() != com.carbridge.carplay.pairing.PairSetupSession.State.PAIRED; i++) {
                pairSetupSession.onTlv(requestTlv8("/pair-setup", bArr, false));
                if (pairSetupSession.state() == com.carbridge.carplay.pairing.PairSetupSession.State.FAILED) {
                    return false;
                }
            }
            boolean z = pairSetupSession.state() == com.carbridge.carplay.pairing.PairSetupSession.State.PAIRED;
            if (z) {
                this.lastPairingId = pairSetupSession.pairingId();
            }
            return z;
        } catch (java.lang.Throwable th) {
            log("pair-setup 异常: " + th);
            return false;
        }
    }

    private static java.lang.String shortId(java.lang.String str) {
        if (str == null) {
            return "?";
        }
        return str.length() <= 8 ? str : str.substring(0, 8) + "…";
    }

    private byte[] requestTlv8(java.lang.String str, byte[] bArr, boolean z) throws java.io.IOException {
        byte[] bArrRequestTlv8Locked;
        synchronized (this.controlLock) {
            bArrRequestTlv8Locked = requestTlv8Locked(str, bArr, z);
        }
        return bArrRequestTlv8Locked;
    }

    private byte[] requestTlv8Locked(java.lang.String str, byte[] bArr, boolean z) throws java.io.IOException {
        java.lang.String[] strArr;
        int andIncrement = this.cseq.getAndIncrement();
        if (z) {
            strArr = new java.lang.String[]{"X-Apple-HKP: 2", "X-Apple-PD: 1"};
        } else {
            strArr = new java.lang.String[]{"X-Apple-HKP: 2"};
        }
        java.lang.String[] strArr2 = strArr;
        java.io.OutputStream outputStream = this.control.getOutputStream();
        outputStream.write(com.carbridge.carplay.core.RtspMessage.request("POST", str, andIncrement, bArr, "application/pairing+tlv8", strArr2));
        outputStream.flush();
        com.carbridge.carplay.core.RtspMessage.Response response = com.carbridge.carplay.core.RtspMessage.readResponse(this.control.getInputStream());
        if (response == null) {
            throw new java.io.IOException("无响应: " + str);
        }
        if (!response.isSuccess()) {
            log("配对 RTSP " + response.status + " for " + str);
        }
        log("  " + str + " status=" + response.status + " cseq=" + response.cseq + " body=" + response.body.length + "B " + hex(response.body, 48));
        return response.body;
    }

    private static java.lang.String hex(byte[] bArr, int i) {
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        for (int i2 = 0; i2 < java.lang.Math.min(bArr.length, i); i2++) {
            sb.append(java.lang.String.format("%02X", java.lang.Byte.valueOf(bArr[i2])));
        }
        if (bArr.length > i) {
            sb.append("…");
        }
        return sb.toString();
    }

    private com.carbridge.carplay.pairing.Ed25519.KeyPair identity() {
        if (identityCache != null) {
            return identityCache;
        }
        com.carbridge.carplay.pairing.Ed25519.KeyPair keyPairLoad = identityStore != null ? identityStore.load() : null;
        if (keyPairLoad != null) {
            identityCache = keyPairLoad;
            log("读到已保存的配对身份");
            return keyPairLoad;
        }
        identityCache = com.carbridge.carplay.pairing.Ed25519.generate();
        if (identityStore != null) {
            identityStore.save(identityCache);
            log("生成并保存新的配对身份");
        } else {
            log("生成临时配对身份（未持久化，下次会重新生成）");
        }
        return identityCache;
    }

    public static void setIdentityStore(com.carbridge.carplay.pairing.IdentityStore identityStore2) {
        identityStore = identityStore2;
        identityCache = null;
    }

    public static boolean forgetIdentity() {
        com.carbridge.carplay.pairing.IdentityStore identityStore2 = identityStore;
        identityCache = null;
        if (identityStore2 == null) {
            return false;
        }
        try {
            identityStore2.clear();
            return true;
        } catch (java.lang.Throwable th) {
            return false;
        }
    }

    private java.lang.String setupMacAddress() {
        if (!this.wifiMacResolved) {
            this.wifiMacResolved = true;
            try {
                this.sessionWifiMac = com.carbridge.carplay.net.WifiMacResolver.resolve(this.control != null ? this.control.getLocalAddress() : null);
            } catch (java.lang.Throwable th) {
            }
            if (this.sessionWifiMac != null) {
                log("AirPlay macAddress = 热点 Wi-Fi 地址 " + this.sessionWifiMac + "（与真 iPhone 同字段）");
            } else {
                log("AirPlay macAddress = 蓝牙 MAC（读不到热点 Wi-Fi 地址，沿用旧值）");
            }
        }
        java.lang.String str = this.sessionWifiMac;
        return str != null ? str : com.carbridge.carplay.core.AirPlaySetup.macAddress();
    }

    private byte[] setup() throws java.io.IOException {
        java.util.LinkedHashMap linkedHashMap = new java.util.LinkedHashMap();
        linkedHashMap.put("deviceID", com.carbridge.carplay.core.AirPlaySetup.deviceId());
        linkedHashMap.put("macAddress", setupMacAddress());
        linkedHashMap.put("model", "iPhone19,4");
        linkedHashMap.put("name", "iPhone");
        linkedHashMap.put("osName", "iPhone OS");
        linkedHashMap.put("osVersion", "27.1");
        linkedHashMap.put("osBuildVersion", "24A94425");
        linkedHashMap.put("sourceVersion", com.carbridge.carplay.core.AirPlaySetup.SOURCE_VERSION);
        linkedHashMap.put("sessionUUID", this.sessionUuid);
        linkedHashMap.put("sessionCorrelationUUID", this.sessionUuid);
        linkedHashMap.put("timingPort", java.lang.Long.valueOf(this.timingSocket.getLocalPort()));
        linkedHashMap.put("statsCollectionEnabled", false);
        linkedHashMap.put("updateSessionRequest", false);
        linkedHashMap.put("keepAliveLowPower", true);
        if (!this.paired) {
            linkedHashMap.put("et", 1L);
        }
        linkedHashMap.put("isScreenMirroringSession", false);
        linkedHashMap.put("features", featuresList());
        linkedHashMap.put("manufacturer", "Apple Inc.");
        linkedHashMap.put("srcvers", com.carbridge.carplay.core.AirPlaySetup.SOURCE_VERSION);
        linkedHashMap.put("statusFlags", 0L);
        linkedHashMap.put("firmwareRevision", "1.0");
        linkedHashMap.put("hardwareRevision", "1.0");
        linkedHashMap.put("extendedFeatures", java.lang.Long.valueOf(com.carbridge.carplay.core.AirPlaySetup.Features.full()[1]));
        linkedHashMap.put("limitedUI", java.lang.Boolean.FALSE);
        linkedHashMap.put("vv", com.carbridge.carplay.core.AirPlaySetup.SOURCE_VERSION);
        byte[] bArrWrite = com.carbridge.carplay.core.Bplist.write(linkedHashMap);
        log("SETUP body 就绪 " + bArrWrite.length + "B paired=" + this.paired + " features=" + java.lang.Long.toHexString(com.carbridge.carplay.core.AirPlaySetup.Features.full()[0]));
        return requestRaw("SETUP", "/setup", bArrWrite);
    }

    private void announce() {
        try {
            java.util.LinkedHashMap linkedHashMap = new java.util.LinkedHashMap();
            linkedHashMap.put("deviceID", com.carbridge.carplay.core.AirPlaySetup.deviceId());
            linkedHashMap.put("macAddress", setupMacAddress());
            linkedHashMap.put("model", "iPhone19,4");
            linkedHashMap.put("name", "iPhone");
            linkedHashMap.put("manufacturer", "Apple Inc.");
            linkedHashMap.put("osName", "iPhone OS");
            linkedHashMap.put("osVersion", "27.1");
            linkedHashMap.put("osBuildVersion", "24A94425");
            linkedHashMap.put("sourceVersion", com.carbridge.carplay.core.AirPlaySetup.SOURCE_VERSION);
            linkedHashMap.put("srcvers", com.carbridge.carplay.core.AirPlaySetup.SOURCE_VERSION);
            linkedHashMap.put("protovers", 1L);
            linkedHashMap.put("statusFlags", 0L);
            linkedHashMap.put("features", featuresList());
            byte[] bArrWrite = com.carbridge.carplay.core.Bplist.write(linkedHashMap);
            requestRaw("ANNOUNCE", "/", bArrWrite);
            log("已发送 ANNOUNCE（设备身份 + 能力清单，" + bArrWrite.length + "B）");
        } catch (java.lang.Throwable th) {
            log("ANNOUNCE 失败（不致命，继续 SETUP）: " + th);
        }
    }

    public static java.util.ArrayList<java.lang.Object> featuresList() {
        long[] jArrFull = com.carbridge.carplay.core.AirPlaySetup.Features.full();
        java.util.ArrayList<java.lang.Object> arrayList = new java.util.ArrayList<>();
        arrayList.add(new com.carbridge.carplay.core.Bplist.U64(jArrFull[0]));
        arrayList.add(new com.carbridge.carplay.core.Bplist.U64(jArrFull[1]));
        return arrayList;
    }

    private void openEventChannel(int i) throws java.io.IOException {
        this.eventChannel = new java.net.Socket();
        this.eventChannel.connect(new java.net.InetSocketAddress(this.host, i), 3000);
        this.eventChannel.setTcpNoDelay(true);
        this.eventReadCipher = null;
        this.eventWriteCipher = null;
        if (this.pairSharedSecret != null) {
            try {
                this.eventReadCipher = com.carbridge.carplay.pairing.ControlCipher.events(this.pairSharedSecret, true, false);
                this.eventWriteCipher = com.carbridge.carplay.pairing.ControlCipher.events(this.pairSharedSecret, false, true);
                log("事件通道：加密（Events-Salt / ChaCha20-Poly1305）");
            } catch (java.lang.Exception e) {
                log("事件通道密钥派生失败，退回明文: " + e);
            }
        } else {
            log("事件通道：明文（未配对，对端通常会拒绝）");
        }
        com.carbridge.carplay.util.Bg.run("ap-event", new java.lang.Runnable() { // from class: com.carbridge.carplay.core.AirPlaySession.6
            @Override // java.lang.Runnable
            public void run() {
                com.carbridge.carplay.core.AirPlaySession.this.eventLoop(com.carbridge.carplay.core.AirPlaySession.this.eventChannel);
            }
        });
    }

    private void sendModesChanged() throws java.io.IOException {
        java.util.ArrayList arrayList = new java.util.ArrayList();
        arrayList.add(resource(1));
        arrayList.add(resource(2));
        java.util.ArrayList arrayList2 = new java.util.ArrayList();
        arrayList2.add(appState(2, 0));
        arrayList2.add(appState(3, 0));
        arrayList2.add(appState(1, 0));
        java.util.LinkedHashMap linkedHashMap = new java.util.LinkedHashMap();
        linkedHashMap.put("resources", arrayList);
        linkedHashMap.put("appStates", arrayList2);
        java.util.LinkedHashMap linkedHashMap2 = new java.util.LinkedHashMap();
        linkedHashMap2.put("type", "modesChanged");
        linkedHashMap2.put("params", linkedHashMap);
        requestRaw("POST", "/command", com.carbridge.carplay.core.Bplist.write(linkedHashMap2));
        log("已发 modesChanged（MainScreen+MainAudio 归手机）");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void trackAudioConsumption(byte[] bArr) {
        if (bArr == null || bArr.length == 0) {
            return;
        }
        long jAsLong = -1;
        try {
            for (java.lang.Object obj : com.carbridge.carplay.core.AirPlayInfo.asList(com.carbridge.carplay.core.Bplist.read(bArr))) {
                if (obj instanceof java.util.Map) {
                    java.util.Map map = (java.util.Map) obj;
                    if (com.carbridge.carplay.core.AirPlayInfo.asLong(map.get("type"), -1L) == this.audioStreamType) {
                        jAsLong = com.carbridge.carplay.core.AirPlayInfo.asLong(map.get("sampleTime"), -1L);
                        break;
                    }
                }
            }
            if (jAsLong < 0) {
                return;
            }
            this.lastAudioSampleTime = jAsLong;
            if (jAsLong == 0) {
                this.audioNotConsumedSec++;
                if (this.audioNotConsumedSec == 5) {
                    log("⚠️ 车机 /feedback 报音频流 sampleTime=0 —— **车机一个样本都没消费**！档位=" + audioProfileLabel() + "；我们发的是 PCM，车机可能只吃 AAC-LC");
                    return;
                }
                return;
            }
            if (this.audioNotConsumedSec > 0) {
                log("✓ 车机开始消费音频了（sampleTime=" + jAsLong + "）");
            }
            this.audioNotConsumedSec = 0;
        } catch (java.lang.Throwable th) {
        }
    }

    private void openScreenStream(com.carbridge.carplay.core.AirPlayInfo.Display display) throws java.io.IOException {
        this.streamConnectionId = ((long) (java.lang.Math.random() * 9.223372036854776E18d)) + 1;
        java.util.LinkedHashMap linkedHashMap = new java.util.LinkedHashMap();
        linkedHashMap.put("type", 110L);
        linkedHashMap.put("streamConnectionID", java.lang.Long.valueOf(this.streamConnectionId));
        linkedHashMap.put("latencyMs", 25L);
        linkedHashMap.put("uuid", display != null ? display.uuid : "0");
        linkedHashMap.put("features", featuresList());
        linkedHashMap.put("sessionUUID", this.sessionUuid);
        java.util.ArrayList arrayList = new java.util.ArrayList();
        arrayList.add(linkedHashMap);
        java.util.LinkedHashMap linkedHashMap2 = new java.util.LinkedHashMap();
        linkedHashMap2.put("streams", arrayList);
        linkedHashMap2.put("deviceID", com.carbridge.carplay.core.AirPlaySetup.deviceId());
        linkedHashMap2.put("sessionUUID", this.sessionUuid);
        linkedHashMap2.put("macAddress", setupMacAddress());
        if (display != null && display.widthPixels > 0 && display.heightPixels > 0) {
            linkedHashMap2.put("widthPixels", java.lang.Long.valueOf(display.widthPixels));
            linkedHashMap2.put("heightPixels", java.lang.Long.valueOf(display.heightPixels));
        }
        byte[] bArrRequestRaw = requestRaw("SETUP", "/", com.carbridge.carplay.core.Bplist.write(linkedHashMap2));
        int iFindDataPort = findDataPort(bArrRequestRaw, 110);
        if (iFindDataPort <= 0) {
            throw new java.io.IOException("屏幕流 SETUP 没回 dataPort（reply=" + bArrRequestRaw.length + "B）");
        }
        this.videoChannel = new java.net.Socket();
        this.videoChannel.connect(new java.net.InetSocketAddress(this.host, iFindDataPort), 3000);
        this.videoChannel.setTcpNoDelay(true);
        this.videoOut = null;
        this.videoSealer = null;
        this.videoCrypto = null;
        this.videoBroken = false;
        this.configDirty = true;
        this.waitingForKeyFrame = true;
        this.splashSent = false;
        this.lastVideoAt = 0L;
        try {
            if (this.paired && this.pairSharedSecret != null) {
                this.videoSealer = new com.carbridge.carplay.core.AirPlayStreamCrypto(com.carbridge.carplay.core.AirPlayStreamCrypto.derive(this.pairSharedSecret, this.streamConnectionId));
                log("视频流加密：配对路径 ChaCha20-Poly1305（connId=" + this.streamConnectionId + "）");
            } else {
                this.videoCrypto = new com.carbridge.carplay.core.AirPlayCrypto(this.streamConnectionId);
                log("视频流加密：旧式 AES-128-CTR（et=1，未配对）");
            }
            log("屏幕流已建 dataPort=" + iFindDataPort + " connId=" + this.streamConnectionId);
        } catch (java.lang.Exception e) {
            throw new java.io.IOException("视频流加密初始化失败: " + e);
        }
    }

    private void openAudioStream() throws java.io.IOException {
        this.audioConnId = ((long) (java.lang.Math.random() * 9.223372036854776E18d)) + 1;
        com.carbridge.carplay.core.AirPlayInfo.AudioFormat audioFormatPickAudioFormat = pickAudioFormat();
        long j = 100;
        java.lang.String str = "media";
        long j2 = 2048;
        long j3 = 80;
        boolean z = false;
        if (audioFormatPickAudioFormat != null) {
            j = audioFormatPickAudioFormat.type;
            if (audioFormatPickAudioFormat.audioType != null && !audioFormatPickAudioFormat.audioType.isEmpty()) {
                str = audioFormatPickAudioFormat.audioType;
            }
            j3 = audioFormatPickAudioFormat.outputLatencyMicros > 0 ? audioFormatPickAudioFormat.outputLatencyMicros / 1000 : 80L;
            if (audioFormatPickAudioFormat.supportsAacLc44k()) {
                z = true;
                j2 = 4194304;
            } else if (audioFormatPickAudioFormat.supportsPcm44k()) {
                j2 = 2048;
            } else {
                j2 = audioFormatPickAudioFormat.outputFormats;
            }
        }
        if (z) {
            try {
                this.aacEncoder = new com.carbridge.carplay.core.AacLcEncoder(AUDIO_SAMPLE_RATE, 2, AUDIO_BITRATE);
                this.aacEncoder.start();
            } catch (java.lang.Throwable th) {
                this.aacEncoder = null;
                z = false;
                j2 = 2048;
                log("✗ AAC 编码器起不来，降级回 PCM: " + th);
            }
        }
        this.audioUseAac = z;
        this.audioFormatBit = j2;
        this.audioStreamType = j;
        this.audioTypeName = str;
        log("音频档位：type=" + j + " audioType=" + str + " fmt=0x" + java.lang.Long.toHexString(j2) + (this.audioUseAac ? "（AAC-LC 裸帧，一包一帧）" : "（PCM 大端，352 帧/包）") + " 延迟=" + j3 + "ms");
        java.util.LinkedHashMap linkedHashMap = new java.util.LinkedHashMap();
        linkedHashMap.put("type", java.lang.Long.valueOf(j));
        linkedHashMap.put("audioType", str);
        linkedHashMap.put("audioFormat", java.lang.Long.valueOf(j2));
        linkedHashMap.put("streamConnectionID", java.lang.Long.valueOf(this.audioConnId));
        linkedHashMap.put("audioLatencyMs", java.lang.Long.valueOf(j3));
        linkedHashMap.put("supportsRTPPacketRedundancy", false);
        linkedHashMap.put("features", featuresList());
        linkedHashMap.put("sessionUUID", this.sessionUuid);
        linkedHashMap.put("redundantAudio", java.lang.Boolean.FALSE);
        linkedHashMap.put("supportsHighAccuracyTimestamps", true);
        java.util.ArrayList arrayList = new java.util.ArrayList();
        arrayList.add(linkedHashMap);
        java.util.LinkedHashMap linkedHashMap2 = new java.util.LinkedHashMap();
        linkedHashMap2.put("streams", arrayList);
        this.audioPort = findDataPort(requestRaw("SETUP", "/", com.carbridge.carplay.core.Bplist.write(linkedHashMap2)), (int) j);
        if (this.audioPort <= 0) {
            log("⚠️ 音频流 SETUP 没回 dataPort（type=" + j + "）→ 车机不会出声，视频继续");
        }
        this.audioSocket = new java.net.DatagramSocket();
        this.audioAddr = java.net.InetAddress.getByName(this.host);
        this.audioPacker = new com.carbridge.carplay.core.RtpAudio((int) (java.lang.Math.random() * 65535.0d), (int) j);
        this.audioAccumLen = 0;
        this.audioRtpTs = 0L;
        this.audioPackets = 0L;
        this.audioBroken = false;
        this.audioSealer = null;
        try {
            if (this.paired && this.pairSharedSecret != null) {
                this.audioSealer = new com.carbridge.carplay.core.AirPlayStreamCrypto(com.carbridge.carplay.core.AirPlayStreamCrypto.derive(this.pairSharedSecret, this.audioConnId));
                log("音频流加密：配对路径 ChaCha20-Poly1305（connId=" + this.audioConnId + "）");
            } else {
                log("⚠️ 未配对，音频流无加密（车机可能不收明文音频）");
            }
            log("音频流已建 dataPort=" + this.audioPort + " connId=" + this.audioConnId + " addr=" + this.audioAddr.getHostAddress() + " 档位=" + audioProfileLabel());
            openTelephonyStream();
        } catch (java.lang.Exception e) {
            throw new java.io.IOException("音频流加密初始化失败: " + e);
        }
    }

    private void openTelephonyStream() {
        if (this.info == null) {
            return;
        }
        com.carbridge.carplay.core.AirPlayInfo.AudioFormat audioFormat = null;
        for (com.carbridge.carplay.core.AirPlayInfo.AudioFormat audioFormat2 : this.info.audioFormats()) {
            if ("telephony".equalsIgnoreCase(audioFormat2.audioType)) {
                audioFormat = audioFormat2;
                break;
            }
        }
        if (audioFormat == null) {
            log("车机未声明 telephony 音频档 → 不开通话流（通话声只能走车机 HFP 通路）");
            return;
        }
        long j = (audioFormat.outputFormats & 1024) != 0 ? 1024L : audioFormat.outputFormats;
        long jRandom = ((long) (java.lang.Math.random() * 9.223372036854776E18d)) + 1;
        try {
            java.util.LinkedHashMap linkedHashMap = new java.util.LinkedHashMap();
            linkedHashMap.put("type", java.lang.Long.valueOf(audioFormat.type));
            linkedHashMap.put("audioType", "telephony");
            linkedHashMap.put("audioFormat", java.lang.Long.valueOf(j));
            linkedHashMap.put("streamConnectionID", java.lang.Long.valueOf(jRandom));
            linkedHashMap.put("features", featuresList());
            linkedHashMap.put("sessionUUID", this.sessionUuid);
            linkedHashMap.put("audioLatencyMs", 80L);
            linkedHashMap.put("supportsRTPPacketRedundancy", false);
            linkedHashMap.put("supportsHighAccuracyTimestamps", true);
            java.util.ArrayList arrayList = new java.util.ArrayList();
            arrayList.add(linkedHashMap);
            java.util.LinkedHashMap linkedHashMap2 = new java.util.LinkedHashMap();
            linkedHashMap2.put("streams", arrayList);
            this.telephonyPort = findDataPort(requestRaw("SETUP", "/", com.carbridge.carplay.core.Bplist.write(linkedHashMap2)), (int) audioFormat.type);
            this.telephonySocket = new java.net.DatagramSocket();
            this.telephonyAddr = java.net.InetAddress.getByName(this.host);
            this.telephonyPacker = new com.carbridge.carplay.core.RtpAudio((int) (java.lang.Math.random() * 65535.0d), (int) audioFormat.type);
            if (this.paired && this.pairSharedSecret != null) {
                this.telephonySealer = new com.carbridge.carplay.core.AirPlayStreamCrypto(com.carbridge.carplay.core.AirPlayStreamCrypto.derive(this.pairSharedSecret, jRandom));
            }
            this.telephonyConnId = jRandom;
            log("✓ 通话音频流已建 type=" + audioFormat.type + " audioType=telephony fmt=0x" + java.lang.Long.toHexString(j) + " dataPort=" + this.telephonyPort + " connId=" + jRandom);
        } catch (java.lang.Throwable th) {
            log("通话音频流开不起来（车机可能不收 telephony 档）: " + th);
        }
    }

    private java.lang.String audioProfileLabel() {
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        if (this.info != null) {
            for (com.carbridge.carplay.core.AirPlayInfo.AudioFormat audioFormat : this.info.audioFormats()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(audioFormat);
            }
        }
        if (sb.length() == 0) {
            sb.append("(车机 /info 未声明 audioFormats)");
        }
        return sb.toString();
    }

    private com.carbridge.carplay.core.AirPlayInfo.AudioFormat pickAudioFormat() {
        if (this.info == null) {
            return null;
        }
        java.util.List<com.carbridge.carplay.core.AirPlayInfo.AudioFormat> listAudioFormats = this.info.audioFormats();
        if (listAudioFormats.isEmpty()) {
            log("WARN: 车机 /info 没声明 audioFormats，按 PCM44.1k 兜底");
            return null;
        }
        for (com.carbridge.carplay.core.AirPlayInfo.AudioFormat audioFormat : listAudioFormats) {
            if (audioFormat.type == 102 && audioFormat.supportsAacLc44k()) {
                log("车机音频档位：选 " + audioFormat + "（type=102 + AAC-LC，真 iPhone 的通路）");
                return audioFormat;
            }
        }
        for (com.carbridge.carplay.core.AirPlayInfo.AudioFormat audioFormat2 : listAudioFormats) {
            if (audioFormat2.supportsAacLc44k()) {
                log("车机音频档位：选 " + audioFormat2 + "（AAC-LC）");
                return audioFormat2;
            }
        }
        for (com.carbridge.carplay.core.AirPlayInfo.AudioFormat audioFormat3 : listAudioFormats) {
            if (audioFormat3.supportsPcm44k()) {
                log("车机音频档位：选 " + audioFormat3 + "（PCM 44.1k，与 Car+ MEDIA 采样率一致）");
                return audioFormat3;
            }
        }
        for (com.carbridge.carplay.core.AirPlayInfo.AudioFormat audioFormat4 : listAudioFormats) {
            if (audioFormat4.supportsPcm48k()) {
                log("⚠️ 车机只声明 PCM 48k（" + audioFormat4 + "），而 Car+ 的 MEDIA 是 44.1k，采样率不匹配会导致车机按错时钟解析");
            }
        }
        log("车机音频档位：没有可用的档位，退回默认（type=100/media/PCM44.1k）");
        return null;
    }

    private void startKeepalives(final int i) throws java.io.IOException {
        com.carbridge.carplay.util.Bg.run("ap-keepalive", new java.lang.Runnable() { // from class: com.carbridge.carplay.core.AirPlaySession.7

            @Override // java.lang.Runnable
            public void run() {
                int i2 = 0;
                int i3 = 0;
                while (AirPlaySession.this.running) {
                    try {
                        java.lang.Thread.sleep(1000L);
                        if (!AirPlaySession.this.running) {
                            return;
                        }
                        i2++;
                        try {
                            AirPlaySession.this.trackAudioConsumption(AirPlaySession.this.requestRaw("POST", "/feedback", null));
                            i3 = 0;
                        } catch (java.lang.Exception e) {
                            i3++;
                            if (i3 >= 3) {
                                AirPlaySession.this.log("/feedback 连续 " + i3 + " 次失败，判定控制通道已断，停止心跳: " + e);
                                com.carbridge.carplay.core.AirPlaySession.closeQuietly(AirPlaySession.this.control);
                                if (AirPlaySession.this.running) {
                                    AirPlaySession.this.setState(com.carbridge.carplay.core.AirPlaySession.State.ERROR);
                                    return;
                                }
                                return;
                            }
                            AirPlaySession.this.log("/feedback 失败: " + e);
                        }
                        if (AirPlaySession.this.videoChannel != null && !AirPlaySession.this.videoChannel.isClosed()) {
                            AirPlaySession.this.sendVideoPacket(com.carbridge.carplay.core.VideoPackets.keepAlive(AirPlaySession.this.info != null && AirPlaySession.this.info.keepAliveSendStatsAsBody));
                            if (i2 % 2 == 0 && java.lang.System.currentTimeMillis() - AirPlaySession.this.lastVideoAt > 3000) {
                                AirPlaySession.this.sendSplashFrame();
                            }
                        }
                        if (i > 0 && AirPlaySession.this.keepAliveSocket != null) {
                            byte[] bArr = new byte[4];
                            AirPlaySession.this.keepAliveSocket.send(new java.net.DatagramPacket(bArr, bArr.length, java.net.InetAddress.getByName(AirPlaySession.this.host), i));
                        }
                        if (i2 == 1 || i2 == 30) {
                            AirPlaySession.this.log("心跳 ×" + i2);
                        }
                    } catch (java.lang.InterruptedException e2) {
                        return;
                    } catch (java.lang.Exception e3) {
                        if (AirPlaySession.this.running) {
                            AirPlaySession.this.log("心跳失败: " + e3);
                            return;
                        }
                        return;
                    }
                }
            }
        });
        this.keepAliveSocket = new java.net.DatagramSocket();
    }

    public void submitVideo(byte[] bArr, long j) {
        cacheCodecConfig(bArr);
        this.lastVideoAt = java.lang.System.currentTimeMillis();
        this.splashSent = false;
        if (this.splashConfigActive && this.cachedAvcC != null) {
            this.configDirty = true;
            this.splashConfigActive = false;
            this.waitingForKeyFrame = true;
        }
        if (this.state != com.carbridge.carplay.core.AirPlaySession.State.STREAMING || this.videoChannel == null || this.videoBroken) {
            return;
        }
        try {
            synchronized (this.videoSendLock) {
                if (this.configDirty && this.cachedAvcC != null) {
                    sendVideoPacket(com.carbridge.carplay.core.VideoPackets.videoConfig(this.cachedW, this.cachedH, this.cachedAvcC));
                    this.configDirty = false;
                    log("已发 OP_VIDEO_CONFIG avcC=" + this.cachedAvcC.length + "B " + this.cachedW + "x" + this.cachedH);
                }
                if (this.waitingForKeyFrame) {
                    if (!com.carbridge.mediacore.H264.isKeyFrame(bArr)) {
                        return;
                    }
                    this.waitingForKeyFrame = false;
                    log("等到 IDR 关键帧，开始推视频帧");
                }
                byte[] bArrAnnexBToAvcc = com.carbridge.mediacore.H264.annexBToAvcc(bArr);
                if (bArrAnnexBToAvcc == null) {
                    return;
                }
                long jNtpTimestamp = ntpTimestamp();
                long jNanoTime = java.lang.System.nanoTime();
                if (this.videoSealer != null) {
                    sendVideoPacket(com.carbridge.carplay.core.VideoPackets.videoFrameSealed(bArrAnnexBToAvcc, jNtpTimestamp, this.videoSealer));
                } else {
                    if (this.videoCrypto != null) {
                        bArrAnnexBToAvcc = this.videoCrypto.process(bArrAnnexBToAvcc);
                    }
                    sendVideoPacket(com.carbridge.carplay.core.VideoPackets.videoFrame(bArrAnnexBToAvcc, jNtpTimestamp));
                }
                trackVideoLatency(java.lang.System.nanoTime() - jNanoTime);
            }
        } catch (java.lang.Exception e) {
            if (this.videoBroken) {
                return;
            }
            this.videoBroken = true;
            log("视频通道断开，停止推流: " + e);
            closeQuietly(this.videoChannel);
            this.videoChannel = null;
            this.videoOut = null;
            setState(com.carbridge.carplay.core.AirPlaySession.State.ERROR);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendSplashFrame() {
        int i;
        int i2;
        com.carbridge.carplay.core.SplashFrame splashFrame = this.splash;
        if (splashFrame == null) {
            return;
        }
        if (this.displayW > 0) {
            i = this.displayW;
        } else {
            i = this.cachedW > 0 ? this.cachedW : 1920;
        }
        int i3 = i;
        if (this.displayH > 0) {
            i2 = this.displayH;
        } else {
            i2 = this.cachedH > 0 ? this.cachedH : 720;
        }
        com.carbridge.carplay.core.SplashFrame.Frame frame = splashFrame.frame(i3, i2);
        if (frame == null) {
            return;
        }
        try {
            synchronized (this.videoSendLock) {
                if (this.configDirty || !this.splashSent) {
                    sendVideoPacket(com.carbridge.carplay.core.VideoPackets.videoConfig(frame.width, frame.height, frame.avcC));
                    this.configDirty = false;
                    this.splashConfigActive = true;
                }
                byte[] bArrAnnexBToAvcc = com.carbridge.mediacore.H264.annexBToAvcc(frame.annexB);
                if (bArrAnnexBToAvcc == null) {
                    return;
                }
                long jNtpTimestamp = ntpTimestamp();
                if (this.videoSealer != null) {
                    sendVideoPacket(com.carbridge.carplay.core.VideoPackets.videoFrameSealed(bArrAnnexBToAvcc, jNtpTimestamp, this.videoSealer));
                } else {
                    if (this.videoCrypto != null) {
                        bArrAnnexBToAvcc = this.videoCrypto.process(bArrAnnexBToAvcc);
                    }
                    sendVideoPacket(com.carbridge.carplay.core.VideoPackets.videoFrame(bArrAnnexBToAvcc, jNtpTimestamp));
                }
                if (!this.splashSent) {
                    this.splashSent = true;
                    log("已发品牌开屏画面（Car+ 尚未给帧，先显示 logo 占位）");
                }
            }
        } catch (java.lang.Exception e) {
        }
    }

    public void submitAudio(byte[] bArr, long j) {
        java.net.DatagramSocket datagramSocket;
        byte[] bArr2;
        if (this.state != com.carbridge.carplay.core.AirPlaySession.State.STREAMING || (datagramSocket = this.audioSocket) == null || this.audioAddr == null || this.audioPort <= 0 || this.audioPacker == null || this.audioBroken || bArr == null || bArr.length == 0) {
            return;
        }
        try {
            if (this.audioUseAac && this.aacEncoder != null && this.aacEncoder.isStarted()) {
                submitAac(datagramSocket, bArr);
                return;
            }
            if (this.audioAccumLen + bArr.length > this.audioAccum.length) {
                byte[] bArr3 = new byte[java.lang.Math.max(this.audioAccum.length * 2, this.audioAccumLen + bArr.length + 4096)];
                java.lang.System.arraycopy(this.audioAccum, 0, bArr3, 0, this.audioAccumLen);
                this.audioAccum = bArr3;
            }
            java.lang.System.arraycopy(bArr, 0, this.audioAccum, this.audioAccumLen, bArr.length);
            this.audioAccumLen += bArr.length;
            while (this.audioAccumLen >= 1408) {
                long j2 = this.audioRtpTs;
                byte[] bArr4 = new byte[1408];
                java.lang.System.arraycopy(this.audioAccum, 0, bArr4, 0, 1408);
                swap16(bArr4);
                byte[] bArrPacket = this.audioPacker.packet(bArr4, 0, 1408, j2);
                if (this.audioSealer != null) {
                    byte[] bArr5 = new byte[12];
                    java.lang.System.arraycopy(bArrPacket, 0, bArr5, 0, 12);
                    byte[] bArrEncryptAudio = this.audioSealer.encryptAudio((int) j2, 0, java.util.Arrays.copyOfRange(bArrPacket, 12, bArrPacket.length));
                    bArr2 = new byte[bArr5.length + bArrEncryptAudio.length];
                    java.lang.System.arraycopy(bArr5, 0, bArr2, 0, bArr5.length);
                    java.lang.System.arraycopy(bArrEncryptAudio, 0, bArr2, bArr5.length, bArrEncryptAudio.length);
                } else {
                    bArr2 = bArrPacket;
                }
                datagramSocket.send(new java.net.DatagramPacket(bArr2, bArr2.length, this.audioAddr, this.audioPort));
                this.audioRtpTs += 352;
                this.audioPackets++;
                java.lang.System.arraycopy(this.audioAccum, 1408, this.audioAccum, 0, this.audioAccumLen - 1408);
                this.audioAccumLen -= 1408;
                if (this.audioPackets == 1) {
                    log("★ 音频首包已发 wire=" + bArr2.length + "B 352帧 connId=" + this.audioConnId + (this.audioSealer != null ? "（加密）" : "（明文）"));
                } else if (this.audioPackets == 500) {
                    log("音频已发 " + this.audioPackets + " 包");
                }
            }
        } catch (java.lang.Exception e) {
            if (this.audioBroken) {
                return;
            }
            this.audioBroken = true;
            log("音频发送失败，停止推音频: " + e);
        }
    }

    private void submitAac(java.net.DatagramSocket datagramSocket, byte[] bArr) throws java.lang.Exception {
        byte[] bArr2;
        for (byte[] bArr3 : this.aacEncoder.encode(bArr, bArr.length)) {
            if (bArr3 != null && bArr3.length != 0) {
                long j = this.audioRtpTs;
                byte[] bArrPacket = this.audioPacker.packet(bArr3, 0, bArr3.length, j);
                if (this.audioSealer != null) {
                    byte[] bArr4 = new byte[12];
                    java.lang.System.arraycopy(bArrPacket, 0, bArr4, 0, 12);
                    byte[] bArrEncryptAudio = this.audioSealer.encryptAudio((int) j, 0, java.util.Arrays.copyOfRange(bArrPacket, 12, bArrPacket.length));
                    bArr2 = new byte[bArr4.length + bArrEncryptAudio.length];
                    java.lang.System.arraycopy(bArr4, 0, bArr2, 0, bArr4.length);
                    java.lang.System.arraycopy(bArrEncryptAudio, 0, bArr2, bArr4.length, bArrEncryptAudio.length);
                } else {
                    bArr2 = bArrPacket;
                }
                datagramSocket.send(new java.net.DatagramPacket(bArr2, bArr2.length, this.audioAddr, this.audioPort));
                this.audioRtpTs += 1024;
                this.audioPackets++;
                if (this.audioPackets == 1) {
                    log("★ AAC 音频首帧已发 wire=" + bArr2.length + "B raw=" + bArr3.length + "B connId=" + this.audioConnId + (this.audioSealer != null ? "（加密）" : "（明文）"));
                } else if (this.audioPackets == 200) {
                    log("AAC 音频已发 " + this.audioPackets + " 帧");
                }
            }
        }
    }

    public void submitTelephonyAudio(byte[] bArr, long j) {
        byte[] bArr2;
        if (this.state != com.carbridge.carplay.core.AirPlaySession.State.STREAMING || this.telephonySocket == null || this.telephonyAddr == null || this.telephonyPort <= 0 || this.telephonyPacker == null || bArr == null || bArr.length == 0) {
            return;
        }
        try {
            int length = bArr.length / 4;
            if (length <= 0) {
                return;
            }
            if (this.telephonyAccumLen + (length * 2) > this.telephonyAccum.length) {
                byte[] bArr3 = new byte[java.lang.Math.max(this.telephonyAccum.length * 2, this.telephonyAccumLen + (length * 2) + 4096)];
                java.lang.System.arraycopy(this.telephonyAccum, 0, bArr3, 0, this.telephonyAccumLen);
                this.telephonyAccum = bArr3;
            }
            byte[] bArr4 = new byte[length * 2];
            for (int i = 0; i < length; i++) {
                int iLe16 = (((short) le16(bArr, i * 4)) + ((short) le16(bArr, (i * 4) + 2))) / 2;
                bArr4[i * 2] = (byte) (iLe16 & 255);
                bArr4[(i * 2) + 1] = (byte) ((iLe16 >> 8) & 255);
            }
            java.lang.System.arraycopy(bArr4, 0, this.telephonyAccum, this.telephonyAccumLen, bArr4.length);
            this.telephonyAccumLen += bArr4.length;
            while (this.telephonyAccumLen >= 704) {
                long j2 = this.telephonyRtpTs;
                byte[] bArr5 = new byte[704];
                java.lang.System.arraycopy(this.telephonyAccum, 0, bArr5, 0, 704);
                swap16(bArr5);
                byte[] bArrPacket = this.telephonyPacker.packet(bArr5, 0, 704, j2);
                if (this.telephonySealer != null) {
                    byte[] bArr6 = new byte[12];
                    java.lang.System.arraycopy(bArrPacket, 0, bArr6, 0, 12);
                    byte[] bArrEncryptAudio = this.telephonySealer.encryptAudio((int) j2, 0, java.util.Arrays.copyOfRange(bArrPacket, 12, bArrPacket.length));
                    bArr2 = new byte[bArr6.length + bArrEncryptAudio.length];
                    java.lang.System.arraycopy(bArr6, 0, bArr2, 0, bArr6.length);
                    java.lang.System.arraycopy(bArrEncryptAudio, 0, bArr2, bArr6.length, bArrEncryptAudio.length);
                } else {
                    bArr2 = bArrPacket;
                }
                this.telephonySocket.send(new java.net.DatagramPacket(bArr2, bArr2.length, this.telephonyAddr, this.telephonyPort));
                this.telephonyRtpTs += 352;
                this.telephonyPackets++;
                java.lang.System.arraycopy(this.telephonyAccum, 704, this.telephonyAccum, 0, this.telephonyAccumLen - 704);
                this.telephonyAccumLen -= 704;
                if (this.telephonyPackets == 1) {
                    log("★ 通话音频首包已发 wire=" + bArr2.length + "B 单声道352帧 connId=" + this.telephonyConnId);
                }
            }
        } catch (java.lang.Exception e) {
            log("通话音频发送失败: " + e);
        }
    }

    private static int le16(byte[] bArr, int i) {
        return (bArr[i] & 255) | ((bArr[i + 1] & 255) << 8);
    }

    private static void swap16(byte[] bArr) {
        for (int i = 0; i + 1 < bArr.length; i += 2) {
            byte b = bArr[i];
            bArr[i] = bArr[i + 1];
            bArr[i + 1] = b;
        }
    }

    public void setCodecConfig(byte[] bArr, int i, int i2) {
        if (bArr == null || bArr.length == 0) {
            return;
        }
        this.cachedAvcC = bArr;
        this.cachedW = i;
        this.cachedH = i2;
        this.configDirty = true;
        this.lastCfgState = 2;
        log("注入解码配置 avcC=" + bArr.length + "B " + i + "x" + i2);
    }

    private void cacheCodecConfig(byte[] bArr) {
        byte[] bArrAvcCFromAnnexB = com.carbridge.carplay.core.VideoPackets.avcCFromAnnexB(bArr);
        if (bArrAvcCFromAnnexB == null) {
            if (hasSps(bArr)) {
                if (this.lastCfgState != 1) {
                    this.lastCfgState = 1;
                    log("⚠ 帧含 SPS 但缺 PPS，暂无法构建 avcC（等 PPS）");
                    return;
                }
                return;
            }
            if (this.lastCfgState != 0 && this.cachedAvcC == null) {
                this.lastCfgState = 0;
                return;
            }
            return;
        }
        boolean z = this.cachedAvcC == null || !java.util.Arrays.equals(this.cachedAvcC, bArrAvcCFromAnnexB);
        this.cachedAvcC = bArrAvcCFromAnnexB;
        int[] iArrSpsOf = spsOf(bArr);
        if (iArrSpsOf != null) {
            this.cachedW = iArrSpsOf[0];
            this.cachedH = iArrSpsOf[1];
        }
        if (z) {
            this.configDirty = true;
            this.lastCfgState = 2;
            log("缓存解码配置 SPS+PPS → avcC=" + bArrAvcCFromAnnexB.length + "B " + this.cachedW + "x" + this.cachedH);
        }
    }

    private boolean hasSps(byte[] bArr) {
        return com.carbridge.mediacore.H264.hasSps(bArr);
    }

    private int[] spsOf(byte[] bArr) {
        int[] iArrSpsDimensions;
        for (byte[] bArr2 : com.carbridge.mediacore.H264.splitNals(bArr)) {
            if (bArr2.length > 0 && (bArr2[0] & 31) == 7 && (iArrSpsDimensions = com.carbridge.mediacore.H264.spsDimensions(bArr2)) != null) {
                return iArrSpsDimensions;
            }
        }
        return null;
    }

    private long ntpTimestamp() {
        return (((java.lang.System.nanoTime() - this.streamStartNanos) / 1000) << 32) / 1000000;
    }

    private void trackVideoLatency(long j) {
        long jNanoTime = java.lang.System.nanoTime();
        if (this.vidLastArriveNanos != 0) {
            long j2 = jNanoTime - this.vidLastArriveNanos;
            this.vidGapSumNanos += j2;
            this.vidGapMaxNanos = java.lang.Math.max(this.vidGapMaxNanos, j2);
            this.vidGapCount++;
        }
        this.vidLastArriveNanos = jNanoTime;
        this.vidCount++;
        this.vidSumNanos += j;
        this.vidMaxNanos = java.lang.Math.max(this.vidMaxNanos, j);
        long jCurrentTimeMillis = java.lang.System.currentTimeMillis();
        if (this.vidLastLogMs == 0) {
            this.vidLastLogMs = jCurrentTimeMillis;
            return;
        }
        if (jCurrentTimeMillis - this.vidLastLogMs >= 5000 && this.vidCount > 0) {
            java.util.Locale locale = java.util.Locale.US;
            java.lang.Object[] objArr = new java.lang.Object[5];
            objArr[0] = java.lang.Double.valueOf((this.vidSumNanos / 1000000.0d) / this.vidCount);
            objArr[1] = java.lang.Double.valueOf(this.vidMaxNanos / 1000000.0d);
            objArr[2] = java.lang.Double.valueOf(this.vidGapCount > 0 ? (this.vidGapSumNanos / 1000000.0d) / this.vidGapCount : 0.0d);
            objArr[3] = java.lang.Double.valueOf(this.vidGapMaxNanos / 1000000.0d);
            objArr[4] = java.lang.Long.valueOf(this.vidCount);
            log(java.lang.String.format(locale, "视频转发 %.2fms/帧（峰 %.2fms）· 帧间隔 %.1fms（峰 %.1fms）· %d 帧/5s", objArr));
            this.vidLastLogMs = jCurrentTimeMillis;
            this.vidMaxNanos = 0L;
            this.vidSumNanos = 0L;
            this.vidCount = 0L;
            this.vidGapCount = 0L;
            this.vidGapMaxNanos = 0L;
            this.vidGapSumNanos = 0L;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendVideoPacket(byte[] bArr) throws java.io.IOException {
        java.io.OutputStream outputStream = this.videoOut;
        if (outputStream == null) {
            outputStream = this.videoChannel.getOutputStream();
            this.videoOut = outputStream;
        }
        outputStream.write(bArr);
        outputStream.flush();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Code duplicated, block: B:111:0x041a  */
    /* JADX WARN: Code duplicated, block: B:137:? A[RETURN, SYNTHETIC] */
    public void eventLoop(java.net.Socket socket) {
        try {
            com.carbridge.carplay.pairing.ControlCipher controlCipher = this.eventReadCipher;
            java.io.InputStream inputStream = socket.getInputStream();
            java.io.InputStream decryptedStream = controlCipher != null ? new com.carbridge.carplay.core.AirPlaySession.DecryptedStream(inputStream, controlCipher) : inputStream;
            log("事件循环已启动 enc=" + (controlCipher != null) + " local=" + socket.getLocalPort() + " remote=" + socket.getPort());
            while (this.running) {
                java.lang.String line = readLine(decryptedStream);
                if (line == null) {
                    log("事件循环：输入流结束（对端关闭）");
                    break;
                }
                boolean z = this.eventDetailCnt < 3;
                if (z) {
                    log("事件循环状态行[" + this.eventRxLogged + "]: " + line);
                }
                this.eventRxLogged++;
                int i = -1;
                java.lang.String[] strArrSplit = line.split(" ");
                if (strArrSplit.length >= 2) {
                    java.lang.String str = strArrSplit[0];
                    java.lang.String str2 = strArrSplit[1];
                }
                byte[] n = null;
                int i2 = -1;
                int i3 = 0;
                while (true) {
                    java.lang.String line2 = readLine(decryptedStream);
                    if (line2 == null || line2.isEmpty()) {
                        break;
                    }
                    if (z) {
                        boolean z2 = true;
                        for (int i4 = 0; i4 < line2.length(); i4++) {
                            if (line2.charAt(i4) < ' ' || line2.charAt(i4) > '~') {
                                z2 = false;
                                break;
                            }
                        }
                        log("事件循环: header[" + i3 + "] = " + (z2 ? line2 : "HEX " + fullHex(line2.getBytes("ISO-8859-1"))));
                    }
                    i3++;
                    if (line2.toLowerCase(java.util.Locale.US).startsWith("cseq:")) {
                        i = java.lang.Integer.parseInt(line2.substring(5).trim());
                    } else if (line2.toLowerCase(java.util.Locale.US).startsWith("content-length:")) {
                        i2 = java.lang.Integer.parseInt(line2.substring(15).trim());
                    }
                }
                if (i2 > 0) {
                    if (z) {
                        log("事件循环: header 读完（" + i3 + " 行），读 body len=" + i2);
                    }
                    n = readN(decryptedStream, i2);
                    if (z) {
                        log("事件循环: body 读到 " + (n == null ? -1 : n.length) + "B");
                    }
                }
                if (z) {
                    log("事件循环: 准备回 200 cseq=" + i);
                }
                java.io.OutputStream outputStream = socket.getOutputStream();
                byte[] bytes = ("RTSP/1.0 200 OK\r\nCSeq: " + i + "\r\n\r\n").getBytes("US-ASCII");
                com.carbridge.carplay.pairing.ControlCipher controlCipher2 = this.eventWriteCipher;
                if (controlCipher2 != null) {
                    bytes = controlCipher2.encrypt(bytes);
                }
                outputStream.write(bytes);
                outputStream.flush();
                if (this.eventRxLogged < 20) {
                    log("事件循环: 已回 200 cseq=" + i + " resp=" + bytes.length + "B");
                }
                if (z) {
                    this.eventDetailCnt++;
                }
                if (n != null && n.length > 0) {
                    java.lang.Object obj = null;
                    java.lang.String strValueOf = null;
                    try {
                        obj = com.carbridge.carplay.core.Bplist.read(n);
                    } catch (java.lang.Exception e) {
                        strValueOf = java.lang.String.valueOf(e);
                    }
                    if (obj instanceof java.util.Map) {
                        java.util.Map<java.lang.String, java.lang.Object> map = (java.util.Map) obj;
                        java.lang.String strValueOf2 = java.lang.String.valueOf(map.get("type"));
                        long jCurrentTimeMillis = java.lang.System.currentTimeMillis();
                        if (jCurrentTimeMillis - this.lastTypeLogMs > 30000) {
                            this.lastTypeLogMs = jCurrentTimeMillis;
                            this.typeCount++;
                            log("事件通道收到 type=" + strValueOf2 + " body=" + n.length + "B（累计 " + this.typeCount + " 条）");
                        }
                        if ("hidSendReport".equals(strValueOf2)) {
                            handleHid(map);
                        } else if ("changeModes".equals(strValueOf2)) {
                            sendModesChanged();
                        } else if ("forceKeyFrame".equals(strValueOf2)) {
                            log("车机要求关键帧");
                        } else if ("requestUI".equals(strValueOf2)) {
                            log("车机 requestUI");
                        }
                    } else if (this.eventRxLogged < 8) {
                        this.eventRxLogged++;
                        log("事件通道 body 解析失败 body=" + n.length + "B err=" + strValueOf + " head=" + headHex(n));
                    }
                }
                if (this.running) {
                    log("事件通道异常");
                }
            }
        } catch (java.lang.Exception e2) {
            if (this.running) {
                log("事件通道异常: " + e2);
            }
        }
    }

    private void handleHid(java.util.Map<java.lang.String, java.lang.Object> map) {
        int key;
        java.lang.Object obj = map.get("hidReport");
        if (obj instanceof byte[]) {
            byte[] bArr = (byte[]) obj;
            java.lang.String strAsString = asString(map.get("uuid"));
            com.carbridge.carplay.core.AirPlayInfo.HidDevice hidDevice = null;
            com.carbridge.carplay.core.AirPlayInfo.HidDevice hidDevice2 = null;
            if (this.info != null) {
                for (com.carbridge.carplay.core.AirPlayInfo.HidDevice hidDevice3 : this.info.hidDevices()) {
                    if (hidDevice3.keyMap() != null) {
                        if (hidDevice == null) {
                            hidDevice = hidDevice3;
                        }
                    } else if (hidDevice2 == null) {
                        hidDevice2 = hidDevice3;
                    }
                }
            }
            com.carbridge.carplay.core.AirPlayInfo.HidDevice hidDevice4 = null;
            if (strAsString != null && this.info != null) {
                for (com.carbridge.carplay.core.AirPlayInfo.HidDevice hidDevice5 : this.info.hidDevices()) {
                    if (strAsString.equals(hidDevice5.uuid)) {
                        hidDevice4 = hidDevice5;
                        break;
                    }
                }
            }
            if (hidDevice4 == null) {
                hidDevice4 = bArr.length <= 2 ? hidDevice : hidDevice2;
            }
            if (hidDevice4 != null && hidDevice4.keyMap() != null) {
                int[] iArrPressedUsages = hidDevice4.keyMap().pressedUsages(bArr);
                if (iArrPressedUsages.length == 0) {
                    return;
                }
                for (int i : iArrPressedUsages) {
                    log("★ 车机硬键 usage=0x" + java.lang.Integer.toHexString(i) + "（" + keyName(i) + "）");
                    this.listener.onHardKey(i);
                }
                return;
            }
            if (this.info == null && bArr.length <= 2 && (key = com.carbridge.carplay.core.HidInput.parseKey(bArr)) > 0) {
                log("★ 车机硬键 usage=0x" + java.lang.Integer.toHexString(key) + "（" + keyName(key) + "，按直读语义）");
                this.listener.onHardKey(key);
                return;
            }
            int i2 = 32767;
            int i3 = 32767;
            if (this.info != null) {
                java.util.Iterator it = this.info.hidDevices().iterator();
                while (it.hasNext()) {
                    int[] iArr = ((com.carbridge.carplay.core.AirPlayInfo.HidDevice) it.next()).touchAxisMaxima();
                    if (iArr != null) {
                        i2 = iArr[0];
                        i3 = iArr[1];
                        break;
                    }
                }
            }
            try {
                com.carbridge.carplay.core.HidInput.Touch touch = com.carbridge.carplay.core.HidInput.parseTouch(bArr, i2, i3);
                int i4 = this.hidReceived;
                this.hidReceived = i4 + 1;
                if (i4 == 0) {
                    log("★ 收到车机触摸 report=" + bArr.length + "B → (" + touch.x + "," + touch.y + ") down=" + touch.down);
                }
                this.listener.onTouch(touch.x, touch.y, touch.down);
            } catch (java.lang.Throwable th) {
                log("车机触摸报告解析失败（" + bArr.length + "B）: " + th);
            }
        }
    }

    private void dumpHidDevices() {
        java.lang.String str;
        if (this.info == null) {
            return;
        }
        java.util.List<com.carbridge.carplay.core.AirPlayInfo.HidDevice> listHidDevices = this.info.hidDevices();
        if (listHidDevices == null || listHidDevices.isEmpty()) {
            log("车机没有声明 HID 设备（没有触摸/按键）");
            return;
        }
        log("── 车机 HID 设备 " + listHidDevices.size() + " 个 ──");
        for (com.carbridge.carplay.core.AirPlayInfo.HidDevice hidDevice : listHidDevices) {
            int[] iArr = hidDevice.touchAxisMaxima();
            com.carbridge.carplay.core.HidKeyMap hidKeyMapKeyMap = hidDevice.keyMap();
            java.lang.StringBuilder sbAppend = new java.lang.StringBuilder().append("  • uuid=").append(hidDevice.uuid).append(" name=").append(hidDevice.name).append(" 描述符 ").append(hidDevice.descriptor == null ? 0 : hidDevice.descriptor.length).append("B").append(iArr != null ? " 触摸上限=" + iArr[0] + "×" + iArr[1] : " 无触摸轴");
            if (hidKeyMapKeyMap != null) {
                str = " 按键=" + (hidKeyMapKeyMap.array ? "Array" : "位图") + " 页=" + (hidKeyMapKeyMap.page == 12 ? "Consumer" : "Telephony") + " usage=" + hidKeyMapKeyMap.usages.length + "项";
            } else {
                str = " 无按键表";
            }
            log(sbAppend.append(str).append(" 描述符原文=").append(hex(hidDevice.descriptor, 96)).toString());
        }
    }

    private static java.lang.String keyName(int i) {
        switch (i) {
            case 32:
                return "接听/挂断";
            case 33:
                return "闪断";
            case 38:
                return "挂断";
            case 176:
                return "播放";
            case 177:
                return "暂停";
            case 179:
                return "快进";
            case 180:
                return "快退";
            case 181:
                return "下一曲";
            case 182:
                return "上一曲";
            case 185:
                return "停止";
            case 188:
                return "循环";
            case 233:
                return "音量+";
            case 234:
                return "音量-";
            default:
                return "未知";
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public byte[] requestRaw(java.lang.String str, java.lang.String str2, byte[] bArr) throws java.io.IOException {
        byte[] bArrRequestRawLocked;
        synchronized (this.controlLock) {
            bArrRequestRawLocked = requestRawLocked(str, str2, bArr);
        }
        return bArrRequestRawLocked;
    }

    private byte[] requestRawLocked(java.lang.String str, java.lang.String str2, byte[] bArr) throws java.io.IOException {
        boolean z;
        int andIncrement = this.cseq.getAndIncrement();
        java.io.OutputStream outputStream = this.control.getOutputStream();
        if ("/feedback".equals(str2)) {
            long jCurrentTimeMillis = java.lang.System.currentTimeMillis();
            z = jCurrentTimeMillis - this.lastFbLogMs > 30000;
            if (z) {
                this.lastFbLogMs = jCurrentTimeMillis;
            }
        } else {
            z = true;
        }
        if (z) {
            log("→ " + str + " " + str2 + " cseq=" + andIncrement + " body=" + (bArr == null ? 0 : bArr.length) + "B" + (this.paired ? "（整条加密）" : ""));
        }
        byte[] bArrRequest = com.carbridge.carplay.core.RtspMessage.request(str, str2, andIncrement, bArr);
        byte[] bArrEncrypt = bArrRequest;
        com.carbridge.carplay.pairing.ControlCipher controlCipher = this.controlEncryptor;
        if (this.paired && controlCipher != null) {
            try {
                bArrEncrypt = controlCipher.encrypt(bArrRequest);
            } catch (java.security.GeneralSecurityException e) {
                throw new java.io.IOException("控制通道加密失败: " + e);
            }
        }
        outputStream.write(bArrEncrypt);
        outputStream.flush();
        com.carbridge.carplay.core.RtspMessage.Response controlResponse = readControlResponse();
        if (controlResponse == null) {
            throw new java.io.IOException("无响应: " + str + " " + str2);
        }
        if (z) {
            log("← " + str + " " + str2 + " status=" + controlResponse.status + " cseq=" + controlResponse.cseq + " body=" + controlResponse.body.length + "B");
        }
        if (!controlResponse.isSuccess() && controlResponse.status != 455) {
            long jCurrentTimeMillis2 = java.lang.System.currentTimeMillis();
            if (this.errLogged < 3 || jCurrentTimeMillis2 - this.lastErrLogMs > 60000) {
                this.errLogged++;
                this.lastErrLogMs = jCurrentTimeMillis2;
                log("RTSP " + controlResponse.status + " for " + str + " " + str2 + "（第 " + this.errLogged + " 次）");
            }
            if (controlResponse.status == 466 && "/setup".equals(str2)) {
                log("⚠ 车机回 466 Key Management Error（拒绝 et=1 加密）：它可能只接受 MFi-SAP(et=4)/FairPlay(et=3,5)，两者需 MFi 协处理器/Apple 授权，纯软件无法实现。");
            }
        }
        return controlResponse.body;
    }

    private com.carbridge.carplay.core.RtspMessage.Response readControlResponse() throws java.io.IOException {
        if (this.paired && this.controlDecryptor != null) {
            if (this.controlPlain == null) {
                this.controlPlain = new com.carbridge.carplay.core.AirPlaySession.DecryptedStream(this.control.getInputStream(), this.controlDecryptor);
            }
            return com.carbridge.carplay.core.RtspMessage.readResponse(this.controlPlain);
        }
        return com.carbridge.carplay.core.RtspMessage.readResponse(this.control.getInputStream());
    }

    /* JADX INFO: loaded from: AirPlaySession$DecryptedStream.class */
    private final class DecryptedStream extends java.io.InputStream {
        private final java.io.InputStream in;
        private final com.carbridge.carplay.pairing.ControlCipher cipher;
        private byte[] plain = new byte[0];
        private int pos;
        private boolean eof;
        private int frameLogged;

        DecryptedStream(java.io.InputStream inputStream, com.carbridge.carplay.pairing.ControlCipher controlCipher) {
            this.in = inputStream;
            this.cipher = controlCipher;
        }

        private boolean fill() throws java.io.IOException {
            while (this.pos >= this.plain.length) {
                if (this.eof) {
                    return false;
                }
                byte[] bArr = new byte[8192];
                int i = this.in.read(bArr);
                if (i < 0) {
                    this.eof = true;
                    return false;
                }
                try {
                    byte[] bArrDecrypt = this.cipher.decrypt(java.util.Arrays.copyOf(bArr, i));
                    if (this.frameLogged < 6) {
                        this.frameLogged++;
                        com.carbridge.carplay.core.AirPlaySession.this.log("事件解密帧#" + this.frameLogged + " raw=" + i + "B plain=" + bArrDecrypt.length + "B head=" + com.carbridge.carplay.core.AirPlaySession.headHex(bArrDecrypt) + (bArrDecrypt.length > 16 ? " tail=" + com.carbridge.carplay.core.AirPlaySession.tailHex(bArrDecrypt) : ""));
                    }
                    this.plain = bArrDecrypt;
                    this.pos = 0;
                } catch (java.lang.Exception e) {
                    throw new java.io.IOException("控制通道解密失败: " + e);
                }
            }
            return true;
        }

        @Override // java.io.InputStream
        public int read() throws java.io.IOException {
            if (!fill()) {
                return -1;
            }
            byte[] bArr = this.plain;
            int i = this.pos;
            this.pos = i + 1;
            return bArr[i] & 255;
        }

        @Override // java.io.InputStream
        public int read(byte[] bArr, int i, int i2) throws java.io.IOException {
            if (i2 == 0) {
                return 0;
            }
            if (!fill()) {
                return -1;
            }
            int iMin = java.lang.Math.min(i2, this.plain.length - this.pos);
            java.lang.System.arraycopy(this.plain, this.pos, bArr, i, iMin);
            this.pos += iMin;
            return iMin;
        }
    }

    private java.lang.Object pick(byte[] bArr, java.lang.String str) {
        java.lang.Object obj = com.carbridge.carplay.core.Bplist.read(bArr);
        if (obj instanceof java.util.Map) {
            return ((java.util.Map) obj).get(str);
        }
        return null;
    }

    private int findDataPort(byte[] bArr, int i) throws java.io.IOException {
        java.lang.Object obj = com.carbridge.carplay.core.Bplist.read(bArr);
        if (!(obj instanceof java.util.Map)) {
            return 0;
        }
        java.lang.Object obj2 = ((java.util.Map) obj).get("streams");
        if (!(obj2 instanceof java.util.List)) {
            return 0;
        }
        for (java.lang.Object obj3 : (java.util.List) obj2) {
            if (obj3 instanceof java.util.Map) {
                java.util.Map map = (java.util.Map) obj3;
                if (asInt(map.get("type"), -1) == i) {
                    return asInt(map.get("dataPort"), 0);
                }
            }
        }
        return 0;
    }

    private static java.util.Map<java.lang.String, java.lang.Object> resource(int i) {
        java.util.LinkedHashMap linkedHashMap = new java.util.LinkedHashMap();
        linkedHashMap.put("resourceID", java.lang.Long.valueOf(i));
        linkedHashMap.put("entity", 1L);
        linkedHashMap.put("permanentEntity", 1L);
        return linkedHashMap;
    }

    private static java.util.Map<java.lang.String, java.lang.Object> appState(int i, int i2) {
        java.util.LinkedHashMap linkedHashMap = new java.util.LinkedHashMap();
        linkedHashMap.put("appStateID", java.lang.Long.valueOf(i));
        linkedHashMap.put("entity", java.lang.Long.valueOf(i2));
        if (i == 1) {
            linkedHashMap.put("speechMode", -1L);
        }
        return linkedHashMap;
    }

    public static byte[] modesChangedBody() {
        java.util.ArrayList arrayList = new java.util.ArrayList();
        arrayList.add(resource(1));
        arrayList.add(resource(2));
        java.util.LinkedHashMap linkedHashMap = new java.util.LinkedHashMap();
        linkedHashMap.put("resources", arrayList);
        java.util.LinkedHashMap linkedHashMap2 = new java.util.LinkedHashMap();
        linkedHashMap2.put("type", "modesChanged");
        linkedHashMap2.put("params", linkedHashMap);
        return com.carbridge.carplay.core.Bplist.write(linkedHashMap2);
    }

    private static java.lang.String uuid() {
        return java.util.UUID.randomUUID().toString().toUpperCase(java.util.Locale.US);
    }

    private static java.lang.String macFromHost(java.lang.String str) {
        int iAbs = java.lang.Math.abs((str + str).hashCode());
        return java.lang.String.format(java.util.Locale.US, "%02X:%02X:%02X:%02X:%02X:%02X", java.lang.Integer.valueOf((iAbs >> 24) & 255), java.lang.Integer.valueOf((iAbs >> 16) & 255), java.lang.Integer.valueOf((iAbs >> 8) & 255), java.lang.Integer.valueOf(iAbs & 255), java.lang.Integer.valueOf((iAbs >> 4) & 255), java.lang.Integer.valueOf(iAbs & 255));
    }

    private static java.lang.String readLine(java.io.InputStream inputStream) throws java.io.IOException {
        java.io.ByteArrayOutputStream byteArrayOutputStream = new java.io.ByteArrayOutputStream(64);
        while (true) {
            int i = inputStream.read();
            if (i != -1) {
                if (i == 10) {
                    byte[] byteArray = byteArrayOutputStream.toByteArray();
                    int length = byteArray.length;
                    if (length > 0 && byteArray[length - 1] == 13) {
                        length--;
                    }
                    return new java.lang.String(byteArray, 0, length, "US-ASCII");
                }
                byteArrayOutputStream.write(i);
            } else {
                if (byteArrayOutputStream.size() == 0) {
                    return null;
                }
                return new java.lang.String(byteArrayOutputStream.toByteArray(), "US-ASCII");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static java.lang.String headHex(byte[] bArr) {
        if (bArr == null) {
            return "null";
        }
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        int iMin = java.lang.Math.min(16, bArr.length);
        for (int i = 0; i < iMin; i++) {
            sb.append(java.lang.String.format("%02x", java.lang.Byte.valueOf(bArr[i])));
        }
        return sb.toString();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static java.lang.String tailHex(byte[] bArr) {
        if (bArr == null) {
            return "null";
        }
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        for (int iMax = java.lang.Math.max(0, bArr.length - 24); iMax < bArr.length; iMax++) {
            sb.append(java.lang.String.format("%02x", java.lang.Byte.valueOf(bArr[iMax])));
        }
        return sb.toString();
    }

    private static java.lang.String fullHex(byte[] bArr) {
        if (bArr == null) {
            return "null";
        }
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        int iMin = java.lang.Math.min(96, bArr.length);
        for (int i = 0; i < iMin; i++) {
            sb.append(java.lang.String.format("%02x", java.lang.Byte.valueOf(bArr[i])));
        }
        return sb.toString() + " (len=" + bArr.length + ")";
    }

    private static byte[] readN(java.io.InputStream inputStream, int i) throws java.io.IOException {
        byte[] bArr = new byte[i];
        int i2 = 0;
        while (true) {
            int i3 = i2;
            if (i3 < i) {
                int i4 = inputStream.read(bArr, i3, i - i3);
                if (i4 < 0) {
                    throw new java.io.IOException("截断");
                }
                i2 = i3 + i4;
            } else {
                return bArr;
            }
        }
    }

    private static int asInt(java.lang.Object obj, int i) {
        if (obj instanceof java.lang.Number) {
            return ((java.lang.Number) obj).intValue();
        }
        if (obj instanceof java.lang.String) {
            try {
                return java.lang.Integer.parseInt(((java.lang.String) obj).trim());
            } catch (java.lang.NumberFormatException e) {
            }
        }
        return i;
    }

    private static java.lang.String asString(java.lang.Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof java.lang.String) {
            java.lang.String strTrim = ((java.lang.String) obj).trim();
            if (strTrim.isEmpty()) {
                return null;
            }
            return strTrim;
        }
        if (obj instanceof java.lang.Number) {
            return java.lang.String.valueOf(obj);
        }
        return null;
    }

    private static long readLongBE(byte[] bArr, int i) {
        long j = 0;
        for (int i2 = 0; i2 < 8; i2++) {
            j = (j << 8) | (((long) bArr[i + i2]) & 255);
        }
        return j;
    }

    private static void putLongBE(byte[] bArr, int i, long j) {
        for (int i2 = 0; i2 < 8; i2++) {
            bArr[i + i2] = (byte) (j >>> (8 * (7 - i2)));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setState(com.carbridge.carplay.core.AirPlaySession.State state) {
        if (this.state == state) {
            return;
        }
        this.state = state;
        this.listener.onState(state);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void log(java.lang.String str) {
        this.lastProgressMs = java.lang.System.currentTimeMillis();
        this.listener.onLog(str);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void closeQuietly(java.net.Socket socket) {
        if (socket != null) {
            try {
                try { socket.close(); } catch (Exception ignored) { }
            } catch (java.lang.Exception e) {
            }
        }
    }
}
