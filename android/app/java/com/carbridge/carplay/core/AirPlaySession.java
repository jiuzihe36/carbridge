package com.carbridge.carplay.core;

import com.carbridge.carplay.pairing.ControlCipher;
import com.carbridge.carplay.pairing.Ed25519;
import com.carbridge.carplay.pairing.IdentityStore;
import com.carbridge.carplay.pairing.PairSetupSession;
import com.carbridge.carplay.pairing.PairVerifySession;
import com.carbridge.mediacore.H264;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.carbridge.carplay.util.Diag;

/**
 * AirPlay 主会话（CarPlay 发送端）：把 CarLife 收到的 H.264 推给车机。
 */
public final class AirPlaySession {

    /** 本次会话用的 Wi-Fi MAC（SETUP 的 macAddress 字段）；只解析一次。 */
    private volatile String sessionWifiMac;
    private boolean wifiMacResolved;

    public interface Listener {
        void onState(State state);

        void onLog(String msg);

        /** 车机触摸，坐标已归一化 0..1。 */
        void onTouch(float normX, float normY, boolean down);

        void onHardKey(int usage);

        void onDisplaySize(int width, int height);

        /**
         * 车机 /info 声明的显示画布 + 最大帧率。
         *
         * <p>默认实现只转发宽高，保证旧监听器（自检脚本）不用改；
         * 生产实现应覆盖它，把 maxFPS 一起用于 Car+ 画布选择。
         */
        default void onDisplaySize(int width, int height, int maxFps) {
            onDisplaySize(width, height);
        }

        /**
         * 车机要求关键帧（解码器失步/黑屏恢复）。
         *
         * <p>默认空实现：只有能向上游（CarLife/Car+）催 IDR 的实现才需要覆盖。
         * 生产实现应把它接到 {@code HuClient.requestKeyframe()}。
         */
        default void onForceKeyFrame() {
        }
    }

    public enum State { IDLE, CONNECTING, SETTING_UP, STREAMING, ERROR, CLOSED }

    private final String host;
    private final int port;
    private final Listener listener;

    private Socket control;
    private Socket eventChannel;
    /** 事件通道密钥：对端 Events-Write → 我方读；我方 Events-Read → 对端读（方向交叉）。 */
    private volatile ControlCipher eventReadCipher;
    private volatile ControlCipher eventWriteCipher;
    /** 收到的车机触摸计数（只用于打首条日志，确认事件通道通了）。 */
    private int hidReceived;
    /**
     * 本次会话的 UUID —— SETUP / ANNOUNCE / 各条流的 SETUP **共用同一个**，
     * 真 iPhone 就是这样（车机靠它把一次会话的各条流串起来）。
     */
    private final String sessionUuid = java.util.UUID.randomUUID()
            .toString().toUpperCase(Locale.US);
    /** 事件通道已记录的命令条数（诊断限流）。 */
    private int eventRxLogged;
    /** 事件循环逐行诊断的消息条数（前 3 条全打，之后只留摘要行）。 */
    private int eventDetailCnt;
    /** 按时间限流的类型日志：上次打印时刻与累计条数。 */
    private long lastTypeLogMs;
    private int typeCount;
    /** changeModes 乒乓限流：同一 params 签名 2 秒内不重复回 modesChanged。 */
    private volatile String lastChangeModesSig;
    private volatile long lastChangeModesAtMs;
    private int changeModesSuppressed;
    /** 视频通道（会话线程建立，视频线程用）—— 必须 volatile，否则视频线程可能永远看到 null。 */
    private volatile Socket videoChannel;

    private DatagramSocket timingSocket;
    private DatagramSocket keepAliveSocket;
    private DatagramSocket audioSocket;

    // ── 音频推流状态（UDP → audioPort） ──
    /** 音频流的 streamConnectionID——**必须与 SETUP 里发的、车机派生密钥用的一致**。 */
    private long audioConnId;
    /** 配对会话的音频流加密器；null=未配对（车机不收明文音频）。 */
    private volatile AirPlayStreamCrypto audioSealer;
    /** 音频 RTP 打包器（PT=100、352 帧/包）。 */
    private RtpAudio audioPacker;
    /** 音频远端地址（解析一次，避免每个包都查 DNS）。 */
    private volatile java.net.InetAddress audioAddr;
    /** 攒不足 352 帧（1408B）就先存着——RTP 每包必须正好 352 帧。 */
    private byte[] audioAccum = new byte[RtpAudio.BYTES_PER_PACKET * 4];
    /** 音频累积长度：音频线程写、会话线程读 → volatile。 */
    private volatile int audioAccumLen;
    /** 音频 RTP 时间戳（48kHz 时钟，每发一包 +352；跨线程读 → volatile）。 */
    private volatile long audioRtpTs;
    /** 音频包计数（首包/定期日志）。 */
    private long audioPackets;
    /** 音频通道已出错，停止刷日志。 */
    private volatile boolean audioBroken;

    /** 车机 /info（会话线程写，事件线程读）→ volatile。 */
    private volatile AirPlayInfo info;
    /** 视频加密器（会话线程建、视频线程用）→ volatile。 */
    private volatile AirPlayCrypto videoCrypto;
    /** 视频流加密器（ChaCha20-Poly1305，与 {@link #videoCrypto} 二选一；跨线程读 → volatile）。 */
    private volatile AirPlayStreamCrypto videoSealer;

    /** 缓存的 SPS+PPS（avcC）与尺寸——CarLife 可能只在流开头发一次，必须提前缓存。 */
    private volatile byte[] cachedAvcC;
    private volatile int cachedW;
    private volatile int cachedH;
    /** 有待发出的 OP_VIDEO_CONFIG（新流或新配置）。 */
    private volatile boolean configDirty = true;
    /**
     * 品牌开屏画面（Car+ 没帧时兜底显示）；null 表示不启用。
     */
    private volatile SplashFrame splash;
    /** 车机 /info 声明的显示画布（开屏帧按它定尺寸）。 */
    private volatile int displayW;
    private volatile int displayH;
    /** 最近一次收到 CarLife 帧的时间戳（判断 Car+ 是否断流）。 */
    private volatile long lastVideoAt;
    /** 开屏帧是否已发过（只发一次 + 周期兜底，避免每帧都刷）。 */
    private volatile boolean splashSent;
    /**
     * 车机解码器当前用的是「开屏」的 SPS/PPS（而非 Car+ 的）。
     *
     * <p>开屏和 Car+ 是两套不同编码器，SPS/PPS 必然不同。Car+ 帧回来时必须强制
     * 重新发 Car+ 的 config，否则车机拿开屏 SPS 解 Car+ 帧 → 花屏（同分辨率时
     * avcC 不变、isNew=false 不会自动触发重发，实测会卡在花屏）。
     */
    private volatile boolean splashConfigActive;
        /**
     * 视频发送锁：串行化「加密 + 写 socket」。
     */
    private final Object videoSendLock = new Object();
    /**
     * 当前这次视频写开始的时间（0 = 没在写）。
     *
     * <p>为什么要记：Java 的 socket 写没有超时。车机不读（解码卡住 / Wi-Fi 断一段但
     * TCP 没 RST）时 {@code out.write} 会永久阻塞，而它持有 {@link #videoSendLock} ——
     * 下一秒心跳线程要发 keepalive 也会卡在锁上，/feedback 停发，看起来像「还在推流」。
     * 看门狗据此把视频通道关掉，让阻塞的写抛异常走既有的重连分支。
     */
    private volatile long videoWriteStartMs;
    /** 最近一次视频通道写完的时刻（诊断用）。 */
    private volatile long lastVideoPacketAtMs;
    /** 控制/事件通道写开始时刻（同 videoWriteStartMs：Java socket 写没有超时，
     *  车机不读控制通道时 out.write 会持 controlLock 永久阻塞 —— /feedback 停发、
     *  changeModes 回发堵死；看门狗超时关通道让阻塞写抛异常走重连）。 */
    private volatile long controlWriteStartMs;
    private volatile long eventWriteStartMs;
    /**
     * 新流先等一个 IDR 关键帧再开始推。
     *
     * <p>Car+/CarLife 的码流是连续的，车机（或假车机）中途接入时只会拿到 P 帧
     * ——没有参考帧解码器起不来，表现为黑屏。真 iPhone 也是等关键帧才开始推，
     * 所以这里丢掉 IDR 之前的 P 帧，从关键帧起推。
     */
    private volatile boolean waitingForKeyFrame = true;
    /** 0=无 SPS 1=有 SPS 缺 PPS 2=已缓存；仅用于去重日志。 */
    private int lastCfgState = -1;
    /** pair-verify 的共享秘密（派生数据流密钥用）。 */
    private volatile byte[] pairSharedSecret;

    /** 视频通道已断（Broken pipe 等），置位后停止推流与刷日志。 */
    private volatile boolean videoBroken;
    /** 因单帧异常被丢掉的帧数（不算通道故障）。 */
    private volatile int droppedVideoFrames;

    /** 是否已配对。配对后控制通道走 ChaCha20-Poly1305（带 tag、AAD=长度头），`SETUP /setup` 省略 et。 */
    private volatile boolean paired;
    private volatile ControlCipher controlEncryptor;
    private volatile ControlCipher controlDecryptor;
    /** 配对后常驻的解密输入流（缓存解出来的明文，避免丢弃响应之后的字节）。 */
    private volatile DecryptedStream controlPlain;

    /** pair-setup 的配对码（DiPlay 固定 3939；真车可由 0x4301/车机界面提供，null→默认）。 */
    private volatile String setupCode;

    /** 本次成功配对使用的 id（公钥 hex）。便于 UI 显示与后续比对。 */
    private volatile String lastPairingId;

    /**
     * RTSP 的 CSeq 计数器。<b>三个线程共用</b>（会话 / keepalive / 事件）→ 必须原子。
     */
    private final java.util.concurrent.atomic.AtomicInteger cseq =
            new java.util.concurrent.atomic.AtomicInteger(1);

    /**
     * 控制通道的串行锁。
     *
     * <p>会话线程、keepalive 线程、事件线程共用一条 RTSP 连接，
     * 「写请求 + 读响应」必须整体串行，否则响应会串台、加密流状态会被并发写乱。
     */
    private final Object controlLock = new Object();
    private long streamConnectionId;
    private long streamStartNanos;
    private volatile boolean running;
    private int audioPort = -1;
    /** 本次会话选中的音频档位（诊断用）。 */
    private long audioFormatBit = 0x800L;
    private long audioStreamType = 100L;
    private String audioTypeName = "media";
    // ── 心跳健康度（长时间会话的可观测性）──
    private long feedbackOkCount;
    private long feedbackFailCount;
    private long lastLivenessLogMs;
    private long lastSampleTimeSeen = -1;
    private int sampleStallSec;
    private long sampleStallWarnedAtMs;

    /** 车机 /feedback 报的音频流消费进度（样本数），0 = 一个都没消费。 */
    private long lastAudioSampleTime = -1;
    /** 连续多少秒 sampleTime 仍为 0。 */
    private int audioNotConsumedSec;
    /** 音频载荷是否走 AAC-LC。 */
    private boolean audioUseAac;
    /** AAC 编码器（仅 AAC 通路时存在）。 */
    private AacLcEncoder aacEncoder;
    // ── 通话流（audioType=telephony，单声道）─────────────────────────────
    private int telephonyPort = -1;
    private DatagramSocket telephonySocket;
    private InetAddress telephonyAddr;
    private RtpAudio telephonyPacker;
    private AirPlayStreamCrypto telephonySealer;
    private long telephonyConnId;
    private byte[] telephonyAccum = new byte[RtpAudio.BYTES_PER_PACKET * 4];
    /** 通话音频累积长度/时间戳：跨线程 → volatile。 */
    private volatile int telephonyAccumLen;
    private volatile long telephonyRtpTs;
    private long telephonyPackets;
    /** 采样率：与 Car+ 的 MEDIA 一致（44100Hz 立体声）。 */
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_BITRATE = 192000;

    /** 看门狗观测点：任何 log() 都算一次进展；卡住 8s 就 dump 会话线程栈。 */
    private volatile long lastProgressMs = System.currentTimeMillis();
    private volatile Thread sessionThread;
    private volatile long lastDumpMs;

    public AirPlaySession(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    public AirPlayInfo info() {
        return info;
    }

    /** 注入品牌开屏画面（Car+ 没帧时兜底）；传 null 关闭。 */
    public void setSplash(SplashFrame splash) {
        this.splash = splash;
    }

    /**
     * 接上配对会话的控制通道加密器。传 null 表示未配对（走旧式 et=1 明文路径）。
     *
     * ⚠️ 必须在 {@link #start()} 之前调用——`SETUP /setup` 的 et 字段据此决定。
     */
    public void setControlCipher(ControlCipher encryptor, ControlCipher decryptor) {
        this.controlEncryptor = encryptor;
        this.controlDecryptor = decryptor;
        this.controlPlain = null;   // 换密钥必须换解密流，否则残留旧明文
        this.paired = encryptor != null && decryptor != null;
        log(paired ? "控制通道：已配对（ChaCha20-Poly1305 加密）" : "控制通道：未配对（明文）");
    }

    public boolean isPaired() {
        return paired;
    }

    /** 配对后使用的 id（公钥 hex）。未配对时为 null。 */
    public String pairingId() {
        return lastPairingId;
    }

    /** 设置 pair-setup 配对码；null 或空则用默认（DiPlay 的 3939）。 */
    public void setSetupCode(String code) {
        this.setupCode = (code == null || code.isEmpty()) ? null : code;
    }

    public State state() {
        return state;
    }

    private volatile State state = State.IDLE;

    // ================================================================== 生命周期

    public synchronized void start() {
        if (running) return;
        // 同实例复用：CLOSED 是终态（setState 拒绝从 CLOSED 再变），
        // 不重置就会「日志说已建立、submitVideo 一行就 return」。
        state = State.IDLE;
        CURRENT = this;
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                // ⚠️ 这里必须用外部类限定，否则 `run()` 会解析成 Runnable.run() 自身 → 无限递归
                AirPlaySession.this.run();
            }
        }, "airplay-session").start();
        startWatchdog();
    }

    /**
     * 会话看门狗：协商阶段（CONNECTING/SETTING_UP）超过 8s 没有任何日志进展，
     * 就 dump 会话线程栈到日志 —— 「日志停在上一步」时不用猜卡在哪一行。
     */
    private void startWatchdog() {
        Thread w = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                    if (!running) return;
                    State s = state;
                    if (s == State.ERROR || s == State.CLOSED) {
                        continue;
                    }
                    // 控制/事件通道写阻塞自愈（任何状态都查：SETUP 期也可能卡）：
                    // 超时关通道，让阻塞的 write 抛异常走既有重连分支。
                    long cws = controlWriteStartMs;
                    if (cws > 0 && System.currentTimeMillis() - cws > 8000) {
                        log("⚠ 控制通道写阻塞 " + (System.currentTimeMillis() - cws)
                                + "ms（车机不读）→ 关闭控制通道，让会话重连");
                        closeQuietly(control);
                        controlPlain = null;
                        controlWriteStartMs = 0;
                    }
                    long ews = eventWriteStartMs;
                    if (ews > 0 && System.currentTimeMillis() - ews > 8000) {
                        log("⚠ 事件通道写阻塞 " + (System.currentTimeMillis() - ews)
                                + "ms（车机不读）→ 关闭事件通道（触摸本会话停用，画面继续）");
                        closeQuietly(eventChannel);
                        eventWriteStartMs = 0;
                    }
                    if (s == State.STREAMING) {
                        // 写阻塞自愈：超过 2 秒还没写完 → 车机那头不读了。
                        // 关掉视频通道让阻塞的 write 抛异常，run()/心跳按既有分支重建会话。
                        long ws = videoWriteStartMs;
                        if (ws > 0 && System.currentTimeMillis() - ws > 2000) {
                            log("⚠ 视频通道写阻塞 " + (System.currentTimeMillis() - ws)
                                    + "ms（车机不读）→ 关闭视频通道，让会话重连");
                            closeQuietly(videoChannel);
                            videoOut = null;
                            videoWriteStartMs = 0;
                        }
                        continue;
                    }
                    long now = System.currentTimeMillis();
                    long quiet = now - lastProgressMs;
                    if (quiet < 8000 || now - lastDumpMs < 15000) {
                        continue;
                    }
                    lastDumpMs = now;
                    dumpStuckThreads(quiet);
                }
            }
        }, "airplay-watchdog");
        w.setDaemon(true);
        w.start();
    }

    private void dumpStuckThreads(long quietMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠ 会话 ").append(quietMs / 1000).append("s 无进展，当前状态 ").append(state);
        Thread t = sessionThread;
        if (t != null) {
            sb.append("\n[").append(t.getName()).append("] ").append(t.getState());
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < st.length && i < 14; i++) {
                sb.append("\n  at ").append(st[i]);
            }
        }
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread th = e.getKey();
            if (th == t || th == Thread.currentThread()) continue;
            if (!th.getName().startsWith("airplay")) continue;
            sb.append("\n[").append(th.getName()).append("] ").append(th.getState());
            StackTraceElement[] st = e.getValue();
            for (int i = 0; i < st.length && i < 5; i++) {
                sb.append("\n  at ").append(st[i]);
            }
        }
        log(sb.toString());
    }

    public void stop() {
        if (CURRENT == this) CURRENT = null;
        running = false;
        // ⚠️ 先把终态钉死，再去关 socket：关 socket 会立刻唤醒阻塞在 read 上的会话线程，
        //    它的 catch 以前会无条件 setState(ERROR) —— 用户手动断开就被上报成故障，
        //    上层收到 onAirPlayFailed 会重发 0x5702，把刚被断开的车机又拉起重连。
        setState(State.CLOSED);
        closeAllResources();
    }

    /** 关闭本轮打开的全部 socket/编码器（不触碰状态机）。stop() 与失败清理共用。 */
    private void closeAllResources() {
        closeQuietly(control);
        // 同实例再 start() 时不能沿用上一轮的解密流（它绑在旧 socket 上）
        controlPlain = null;
        closeQuietly(eventChannel);
        closeQuietly(videoChannel);
        videoOut = null;
        if (timingSocket != null) timingSocket.close();
        if (keepAliveSocket != null) keepAliveSocket.close();
        if (audioSocket != null) audioSocket.close();
        if (telephonySocket != null) telephonySocket.close();
        if (aacEncoder != null) { aacEncoder.stop(); aacEncoder = null; }
    }

    private void run() {
        sessionThread = Thread.currentThread();
        try {
            setState(State.CONNECTING);
            startTimingResponder();
            control = new Socket();
            control.connect(new InetSocketAddress(host, port), 5000);
            control.setTcpNoDelay(true);
            // ⚠️ 必须设读超时：否则对端不回（或回一半）时会**永久阻塞**在 read，
            // 表现为日志停在上一步、既没有 SETUP ok 也没有异常。实测踩过。
            control.setSoTimeout(8000);
            log("TCP 已连 " + host + ":" + port + "，开始配对阶段");

            // 配对（若需要）：必须在 SETUP /setup 之前。先 verify，不行再 pair-setup 注册。
            pair();
            log("配对阶段结束 paired=" + paired + "，准备 SETUP /setup");

            // ⚠️ **ANNOUNCE 必须有**（真 iPhone 的顺序：Bonjour → Conn → Auth → Ann → Setup）。
            //    目标车机 车机自己的 AirPlay 接收端在会话结束时会打一行总结：
            //      "AirPlay session started: From=%s D=0x%012llx A=%##a T=%s C=%s L=%u ms
            //       Bonjour=%u ms Conn=%u ms Auth=%u ms Ann=%u ms Setup=%u ms ..."
            //    —— 里面的 **Ann** 就是它处理我们 ANNOUNCE 花的时长。
            //    少了这一步，车机拿不到「你是谁、你有什么能力」的**第一份**声明。
            announce();

            byte[] reply = setup();
            int eventPort = asInt(pick(reply, "eventPort"), 0);
            int keepAlivePort = asInt(pick(reply, "keepAlivePort"), 0);
            log("SETUP ok: eventPort=" + eventPort + " keepAlivePort=" + keepAlivePort);
            long inviteAge = com.carbridge.carplay.net.CtrlIntServer.lastInviteAgeMs();
            if (inviteAge < 0) {
                log("车机本轮还没邀请过我们（没有 GET /ctrl-int/1/connect）→ 走的是 0x4301/mDNS 路径");
            } else if (inviteAge > 20000) {
                log("⚠ 车机邀请已过 " + (inviteAge / 1000) + " 秒才建会话"
                        + "（现场观察邀请窗口约 20 秒）→ 车机可能已放弃这一轮，"
                        + "若出现黑屏/不出声优先怀疑这个");
            } else {
                log("车机邀请年龄 " + inviteAge + "ms（在窗口内）");
            }

            info = AirPlayInfo.parse(Bplist.read(requestRaw("GET", "/info", Bplist.write(new LinkedHashMap<String, Object>()))));
            AirPlayInfo.Display d = info.primaryDisplay();
            if (d == null) {
                // /info 偶尔会「回了但没带 displays」（车机刚起来、或控制通道半闭）。
                // 直接拿 1200x512 兜底会让画面尺寸完全不对，而用户只看到「画面糊/被拉伸」。
                // 先重试一次——这一步很便宜，能救回绝大多数瞬时失败。
                log("WARN: /info 没给 displays，300ms 后重试一次");
                try {
                    Thread.sleep(300);
                    info = AirPlayInfo.parse(Bplist.read(
                            requestRaw("GET", "/info", Bplist.write(new LinkedHashMap<String, Object>()))));
                    d = info.primaryDisplay();
                } catch (Throwable t) {
                    com.carbridge.carplay.util.Diag.w("AirPlaySession.infoRetry", t);
                }
                if (d == null) {
                    log("✗ 车机两次都没给画布 → 用 1200x512 兜底，画面可能被拉伸；"
                            + "请把这份日志发出来（需要确认该车 /info 的真实字段）");
                }
            }
            if (d != null) {
                log("车机显示 " + d.widthPixels + "x" + d.heightPixels
                        + " maxFPS=" + d.maxFps + " uuid=" + d.uuid
                        + " keepAliveSendStatsAsBody=" + info.keepAliveSendStatsAsBody);
                listener.onDisplaySize(d.widthPixels, d.heightPixels, d.maxFps);
                displayW = d.widthPixels;
                displayH = d.heightPixels;
            } else {
                log("WARN: /info 没有 displays，用 1200x512 兜底");
                displayW = 1200;
                displayH = 512;
            }
            // ⚠️ **抓一份车机的 HID 设备清单**（目标车机 的触摸上限/按键表全靠它）。
            //    坐标逻辑上限、按键 Usage 数组都从描述符里解析，解析不出来就只能瞎猜。
            dumpHidDevices();

            // ⚠️ 车机不一定给 eventPort（个别车型/固件没有事件通道）。
            //    以前无条件 openEventChannel(0) → 连端口 0 直接抛异常 →
            //    整个 AirPlay 会话 ERROR，连画面都推不出去。
            if (eventPort > 0) {
                openEventChannel(eventPort);
            } else {
                log("SETUP 没给 eventPort → 车机不会回传触摸/硬键，先继续推流");
            }
            streamStartNanos = System.nanoTime();

            // RECORD 必须在 modesChanged 之前（CarDeck 实测顺序）
            requestRaw("RECORD", "/", null);
            sendModesChanged();
            setState(State.SETTING_UP);

            openScreenStream(d);
            // ⚠️ 音频流建立失败不能拖死视频：车机忙/不认第二个流时 requestRaw 会抛，
            //    以前它和 openScreenStream 平铺在同一个 try 里 → 整条会话 ERROR → 黑屏。
            try {
                openAudioStream();
            } catch (Throwable ta) {
                log("⚠ 音频流建立失败（视频继续，本会话无声）: " + ta);
            }

            startKeepalives(keepAlivePort);
            setState(State.STREAMING);
            log("AirPlay 会话已建立，开始推流");
        } catch (Throwable t) {
            // 主动 stop() 时 socket 被关，读线程也会走到这里 —— 那不是故障，
            // 不能把已写好的 CLOSED 终态覆盖成 ERROR（否则上层会立刻重连）。
            if (running) {
                log("AirPlay 会话失败: " + t);
                setState(State.ERROR);
            }
            // ⚠️ 失败也要把已经打开的资源关掉：否则 control 保持 ESTABLISHED、
            //    timing 线程继续应答 —— 车机看到「半活会话」可能不再重新邀请，
            //    而且 running 恒 true 会把同实例的再次 start() 挡死（if (running) return）。
            running = false;
            if (CURRENT == this) CURRENT = null;
            closeAllResources();
        }
    }

    // ================================================================== 各步骤

    private void startTimingResponder() throws IOException {
        timingSocket = new DatagramSocket(0);
        final DatagramSocket s = timingSocket;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[128];
                while (running && s != null && !s.isClosed()) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                        // ⚠️ 必须先看真实包长：isTimingRequest 检查的是 128B 固定缓冲，
                        //    一个 <32B 的短垃圾包（扫描包）也能过 buf 检查，然后 reply()
                        //    在 readLongBE(24) 上抛 AIOOBE —— 一条扫描包就把 timing 线程打死。
                        if (p.getLength() >= 32 && TimingResponder.isTimingRequest(buf)) {
                            byte[] req = new byte[p.getLength()];
                            System.arraycopy(buf, 0, req, 0, p.getLength());
                            // ⚠️ 用 TimingResponder（已按 RFC 3550 的 NTP 32.32 算好），
                            // 不要在这里手写：原来内联那版把 now 算成了
                            // 「微秒 + 纪元秒」而不是 (纪元秒 << 32 | 小数)，
                            // 时间戳的高 32 位全是 0 → 车机拿到的时钟是错的。
                            byte[] rep = TimingResponder.reply(req,
                                    TimingResponder.ntpNow(streamStartNanos));
                            s.send(new DatagramPacket(rep, rep.length, p.getAddress(), p.getPort()));
                        }
                    } catch (Exception e) {
                        // socket 已关（stop 触发的收包失败）才退出；包级异常丢弃该包继续应答，
                        // 时钟应答一停车机就会失去同步。
                        if (!running || s == null || s.isClosed()) {
                            break;
                        }
                        log("timing 响应失败（丢弃该包，继续应答）: " + e);
                    }
                }
            }
        }, "airplay-timing");
        t.setDaemon(true);
        t.start();
    }

        /**
     * 配对编排：<b>先 pair-verify</b>（快路径），
     * 对端没有我们的身份时 <b>退到 pair-setup 注册</b>，再 pair-verify 一次。
     */
    private void pair() {
        if (paired) return;
        if (doPairVerify()) return;          // 已配对，verify 直接成

        log("pair-verify 未通过 → 尝试 pair-setup 注册身份");
        if (doPairSetup()) {
            log("pair-setup 完成，重新 pair-verify");
            doPairVerify();
        } else {
            log("pair-setup 失败，继续走匿名 et=1 路径");
        }
    }

    /** 跑一轮 pair-verify（M1→M4），成功则启用控制加密并返回 true。 */
    private boolean doPairVerify() {
        try {
            final java.util.concurrent.atomic.AtomicReference<byte[]> pending =
                    new java.util.concurrent.atomic.AtomicReference<byte[]>();
            final PairVerifySession session = new PairVerifySession(new PairVerifySession.Listener() {
                @Override public void onLog(String msg) { log(msg); }
                @Override public void onTlvOut(byte[] tlv) { pending.set(tlv); }
            }, identity());

            session.start();
            byte[] m1 = pending.getAndSet(null);
            if (m1 == null) { log("pair-verify M1 未生成"); return false; }
            byte[] m2 = requestTlv8("/pair-verify", m1, false);
            session.onTlv(m2);
            if (session.state() == PairVerifySession.State.FAILED) return false;

            byte[] m3 = pending.getAndSet(null);
            if (m3 == null) { log("pair-verify M3 未生成"); return false; }
            // M3 仍是明文（对端还没启用 cipher）
            byte[] m4 = requestTlv8("/pair-verify", m3, false);
            session.onTlv(m4);
            if (session.state() != PairVerifySession.State.VERIFIED) {
                log("pair-verify 未完成（对端配对库里可能没有我们）");
                return false;
            }
            // ⚠️ 对端写完 M4 才激活 cipher —— 我们这里切换，正好对上
            setControlCipher(session.writeCipher(), session.readCipher());
            paired = true;
            lastPairingId = session.pairingId();
            pairSharedSecret = session.sharedSecret();
            log("★ pair-verify 成功，控制通道转加密（id=" + shortId(lastPairingId) + "）");
            return true;
        } catch (Throwable t) {
            log("pair-verify 异常: " + t);
            return false;
        }
    }

    /** 跑一轮 pair-setup（M1→M6，SRP-6a），成功返回 true。配对码可来自车机（null→默认）。 */
    private boolean doPairSetup() {
        try {
            final java.util.concurrent.atomic.AtomicReference<byte[]> pending =
                    new java.util.concurrent.atomic.AtomicReference<byte[]>();
            PairSetupSession session = new PairSetupSession(new PairSetupSession.Listener() {
                @Override public void onLog(String msg) { log(msg); }
                @Override public void onTlvOut(byte[] tlv) { pending.set(tlv); }
            }, identity(), setupCode);

            session.start();
            // M1 → M3 → M5 共三轮，每轮拿上一条待发消息换一条响应
            for (int round = 0; round < 4; round++) {
                byte[] out = pending.getAndSet(null);
                if (out == null) break;
                if (session.state() == PairSetupSession.State.FAILED
                        || session.state() == PairSetupSession.State.PAIRED) break;
                byte[] reply = requestTlv8("/pair-setup", out, false);
                session.onTlv(reply);
                if (session.state() == PairSetupSession.State.FAILED) return false;
            }
            boolean ok = session.state() == PairSetupSession.State.PAIRED;
            if (ok) lastPairingId = session.pairingId();
            return ok;
        } catch (Throwable t) {
            log("pair-setup 异常: " + t);
            return false;
        }
    }

    private static String shortId(String id) {
        if (id == null) return "?";
        return id.length() <= 8 ? id : id.substring(0, 8) + "…";
    }

    /** 发一条 TLV8 配对消息并读响应。pair-verify 请求要带 Apple 标识头，否则 403。 */
    private byte[] requestTlv8(String path, byte[] body, boolean verify) throws IOException {
        synchronized (controlLock) {   // 同 requestRaw：控制通道整体串行
            return requestTlv8Locked(path, body, verify);
        }
    }

    private byte[] requestTlv8Locked(String path, byte[] body, boolean verify) throws IOException {
        int thisCseq = cseq.getAndIncrement();
        String[] extra = verify
                ? new String[]{"X-Apple-HKP: 2", "X-Apple-PD: 1"}
                : new String[]{"X-Apple-HKP: 2"};
        OutputStream out = control.getOutputStream();
        out.write(RtspMessage.request("POST", path, thisCseq, body,
                "application/pairing+tlv8", extra));
        out.flush();
        RtspMessage.Response r = readControlResponseFor(thisCseq, "TLV8 " + path);
        if (r == null) throw new IOException("无响应: " + path);
        if (!r.isSuccess()) log("配对 RTSP " + r.status + " for " + path);
        // 诊断：把原始 body 打出来，便于判断对端回的是 TLV8 还是 RTSP 错误页
        log("  " + path + " status=" + r.status + " cseq=" + r.cseq
                + " body=" + r.body.length + "B " + hex(r.body, 48));
        return r.body;
    }

    private static String hex(byte[] b, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(b.length, max); i++) {
            sb.append(String.format("%02X", b[i]));
        }
        if (b.length > max) sb.append("…");
        return sb.toString();
    }

    /** 我方长期身份。优先从持久化存储读；没有则临时生成（不落盘）。 */
    private Ed25519.KeyPair identity() {
        if (identityCache != null) return identityCache;
        Ed25519.KeyPair stored = identityStore != null ? identityStore.load() : null;
        if (stored != null) {
            identityCache = stored;
            log("读到已保存的配对身份");
            return stored;
        }
        identityCache = Ed25519.generate();
        if (identityStore != null) {
            identityStore.save(identityCache);
            log("生成并保存新的配对身份");
        } else {
            log("生成临时配对身份（未持久化，下次会重新生成）");
        }
        return identityCache;
    }

    /** 配对身份的持久化存储；null 表示不持久化。 */
    private static volatile IdentityStore identityStore;

    /** 设置持久化存储（应在会话开始前调用一次）。 */
    public static void setIdentityStore(IdentityStore store) {
        identityStore = store;
        identityCache = null;   // 换存储后重新读
    }

    /**
     * 忘掉已保存的配对身份（换车 / 配对卡死时用）。
     *
     * <p>清掉之后下一次会话会重新生成一对 Ed25519 身份并走完整的 pair-setup，
     * 车机那边也会把它当成新设备重新登记。
     */
    public static boolean forgetIdentity() {
        IdentityStore store = identityStore;
        identityCache = null;
        if (store == null) return false;
        try {
            store.clear();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Ed25519.KeyPair identityCache;

    /**
     * SETUP 里的 macAddress：用**热点 Wi-Fi 的 MAC**，不是蓝牙 MAC。
     *
     * <p>真 iPhone 的 SETUP 里这个字段是 Wi-Fi 侧地址（车机用它和自己记录的地址对账）；
     * 我们以前一律填蓝牙 MAC，部分车机会当成“不是同一台设备”而不发邀请。
     * 解析不到时回落到原来的蓝牙 MAC，避免整条链路因为一个字段失败。
     */
    private String setupMacAddress() {
        if (!wifiMacResolved) {
            wifiMacResolved = true;
            try {
                sessionWifiMac = com.carbridge.carplay.net.WifiMacResolver.resolve(
                        control != null ? control.getLocalAddress() : null);
            } catch (Throwable t) {
                com.carbridge.carplay.util.Diag.w("AirPlaySession.wifiMac", t);
            }
            if (sessionWifiMac != null) {
                log("AirPlay macAddress = 热点 Wi-Fi 地址 " + sessionWifiMac + "（与真 iPhone 同字段）");
            } else {
                log("AirPlay macAddress = 蓝牙 MAC（读不到热点 Wi-Fi 地址，沿用旧值）");
            }
        }
        String mac = sessionWifiMac;
        return mac != null ? mac : AirPlaySetup.macAddress();
    }

    private byte[] setup() throws IOException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        // ⚠️ 版本号三处同源：SOURCE_VERSION 是 volatile（界面可切广播版本），
        //    同一条 SETUP 必须快照一次再复用，不能分三次读把自己报成两个版本。
        final String sourceVersion = AirPlaySetup.SOURCE_VERSION;
        // ⚠️ deviceID 与 macAddress 必须是**真 iPhone 的那一对**：
        //    deviceID = UUID 形式、macAddress = 真实蓝牙 MAC，
        //    且与 iAP2 的 0x4E0C DeviceUUID 是**同一个** UUID ✓（见 AirPlaySetup.deviceIdFromMac）。
        String devId = AirPlaySetup.deviceId();
        body.put("deviceID", devId);
        body.put("macAddress", setupMacAddress());
        body.put("model", AirPlaySetup.MODEL);
        body.put("name", "iPhone");
        body.put("osName", AirPlaySetup.OS_NAME);
        body.put("osVersion", AirPlaySetup.OS_VERSION);
        body.put("osBuildVersion", AirPlaySetup.OS_BUILD_VERSION);
        body.put("sourceVersion", sourceVersion);
        body.put("sessionUUID", sessionUuid);
        body.put("sessionCorrelationUUID", sessionUuid);
        body.put("timingPort", (long) timingSocket.getLocalPort());
        body.put("statsCollectionEnabled", false);
        body.put("updateSessionRequest", false);
        body.put("keepAliveLowPower", true);
        // 未配对：et 必须是 1（None）；0 是 Undefined，车机会回 466 Key Management Error。
        // 已配对：省略 et（orangecar §6.2：post-pairing SETUP 不带 et）。
        if (!paired) {
            body.put("et", (long) AirPlaySetup.ENCRYPTION_NONE);
        }
        body.put("isScreenMirroringSession", false);
        // ⚠️⚠️ **features 必须是完整 iPhone 能力清单，不能是空数组**。
        //
        // 目标车机 车机自己的 AirPlay 接收端（/product/lib64/libAirPlay_R14_vehicle.so）
        // 里 AirPlayGetFeatures() 是这么算的：
        //     return CFObjectGetPropertyInt64Sync(setup, "features") | 0x6104040280;
        // 那个 0x6104040280 是**车机自己的基线**，它的位只有：
        //     bit7 AudioRedundant / bit9 FairPlay / bit18 Authentication
        //     / bit26 AudioFormatsV2（+ 高段的 bit32/37/38）
        // —— **没有 Video、没有 ScreenMirroring、没有 Audio、没有 VideoCodecs**！
        // 也就是说：视频/音频/投屏这些能力**全靠手机在 features 里报上来**。
        // 报空数组 = 手机只给车机贡献「加密+冗余音频」，视频管线根本不启用。
        //
        // 高段（第二个元素）用 0x61：正好等于车机基线在高段的三个位（32/37/38）。
        body.put("features", featuresList());
        // 下面几个是车机 key 表里有、而我们没发的（真 iPhone 都会带）
        body.put("manufacturer", "Apple Inc.");
        body.put("srcvers", sourceVersion);
        body.put("statusFlags", 0L);
        body.put("firmwareRevision", "1.0");
        body.put("hardwareRevision", "1.0");
        body.put("extendedFeatures", AirPlaySetup.Features.full()[1]);
        body.put("limitedUI", Boolean.FALSE);
        body.put("vv", sourceVersion);
        byte[] bodyBytes = Bplist.write(body);
        log("SETUP body 就绪 " + bodyBytes.length + "B paired=" + paired
                + " features=" + Long.toHexString(AirPlaySetup.Features.full()[0]));
        return requestRaw("SETUP", "/setup", bodyBytes);
    }

        /**
     * ANNOUNCE —— 真 iPhone 在配对后、SETUP 前发的那条 RTSP。
     */
    private void announce() {
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            // 同 setup()：一条报文内版本号必须同源（快照一次再复用）
            final String sourceVersion = AirPlaySetup.SOURCE_VERSION;
            body.put("deviceID", AirPlaySetup.deviceId());
            body.put("macAddress", setupMacAddress());
            body.put("model", AirPlaySetup.MODEL);
            body.put("name", "iPhone");
            body.put("manufacturer", "Apple Inc.");
            body.put("osName", AirPlaySetup.OS_NAME);
            body.put("osVersion", AirPlaySetup.OS_VERSION);
            body.put("osBuildVersion", AirPlaySetup.OS_BUILD_VERSION);
            body.put("sourceVersion", sourceVersion);
            body.put("srcvers", sourceVersion);
            body.put("protovers", 1L);
            body.put("statusFlags", 0L);
            body.put("features", featuresList());
            byte[] bodyBytes = Bplist.write(body);
            requestRaw("ANNOUNCE", "/", bodyBytes);
            log("已发送 ANNOUNCE（设备身份 + 能力清单，" + bodyBytes.length + "B）");
        } catch (Throwable t) {
            log("ANNOUNCE 失败（不致命，继续 SETUP）: " + t);
        }
    }

    /**
     * {@code features} 数组：两个 64 位段（低段 + 高段）。
     *
     * <p>真 iPhone 报的是**数组**（不是单个整数），车机按「低段 OR 车机基线」、
     * 「高段 OR 车机基线高段」分别算 ✓。
     */
    public static ArrayList<Object> featuresList() {
        long[] full = AirPlaySetup.Features.full();
        ArrayList<Object> list = new ArrayList<Object>();
        // ⚠️ 用 Bplist.U64（写满 8 字节）：普通整数是「有符号 + 最小宽度」，
        //    我们的低段 bit31 是置的（MediaControlV2），按 4 字节写会被对端
        //    符号扩展成高 32 位全 1 → 能力位全乱。
        list.add(new Bplist.U64(full[0]));
        list.add(new Bplist.U64(full[1]));
        return list;
    }

    /** 事件通道：**我方连到车机的 eventPort**（车机 openEvent 起的 ServerSocket），车机 accept 后往这条连接上发 /command（触摸、changeModes…）。 */
    private void openEventChannel(int eventPort) throws IOException {
        eventChannel = new Socket();
        eventChannel.connect(new InetSocketAddress(host, eventPort), 3000);
        eventChannel.setTcpNoDelay(true);

        // ⚠️ 事件通道跟控制通道一样是**配对后加密**的：DiPlay acceptEvent 里
        // eventCipher = ControlCipher(Events-Read, Events-Write)，收发都是 cipher.encrypt(...)。
        // 按明文 readLine 去读，读到的是密文二进制、永远凑不出 RTSP 头；而我们回的明文 200
        // 它也解不开 → 它 decrypt failed 就 break（close 时 setSoLinger(0) 直接发 RST）→
        // 我方看到 Connection reset，触摸永远到不了手机（真机实测车机 touch n=0）。
        eventReadCipher = null;
        eventWriteCipher = null;
        if (pairSharedSecret != null) {
            try {
                // 方向交叉：对端用 Events-Write 写 → 我方用它解；我方用 Events-Read 写 → 对端解
                eventReadCipher = ControlCipher.events(pairSharedSecret, true, false);
                eventWriteCipher = ControlCipher.events(pairSharedSecret, false, true);
                log("事件通道：加密（Events-Salt / ChaCha20-Poly1305）");
            } catch (Exception e) {
                log("事件通道密钥派生失败，退回明文: " + e);
            }
        } else {
            log("事件通道：明文（未配对，对端通常会拒绝）");
        }

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                eventLoop(eventChannel);
            }
        }, "airplay-events");
        t.setDaemon(true);
        t.start();
    }

    private void sendModesChanged() throws IOException {
        List<Object> resources = new ArrayList<Object>();
        resources.add(resource(AirPlaySetup.RESOURCE_MAIN_SCREEN));
        resources.add(resource(AirPlaySetup.RESOURCE_MAIN_AUDIO));

        List<Object> appStates = new ArrayList<Object>();
        appStates.add(appState(AirPlaySetup.APP_STATE_PHONE_CALL, 0));
        appStates.add(appState(AirPlaySetup.APP_STATE_TURN_BY_TURN, 0));
        appStates.add(appState(AirPlaySetup.APP_STATE_SPEECH, 0));

        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("resources", resources);
        params.put("appStates", appStates);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("type", "modesChanged");
        body.put("params", params);

        requestRaw("POST", "/command", Bplist.write(body));
        log("已发 modesChanged（MainScreen+MainAudio 归手机）");
    }

        /**
     * 看车机到底有没有在消费我们的音频流。
     */
    private void trackAudioConsumption(byte[] feedbackBody) {
        if (feedbackBody == null || feedbackBody.length == 0) return;
        long sampleTime = -1;
        try {
            Object root = Bplist.read(feedbackBody);
            for (Object o : AirPlayInfo.asList(root)) {
                if (!(o instanceof java.util.Map)) continue;
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> m = (java.util.Map<String, Object>) o;
                if (AirPlayInfo.asLong(m.get("type"), -1) != audioStreamType) continue;
                sampleTime = AirPlayInfo.asLong(m.get("sampleTime"), -1);
                break;
            }
        } catch (Throwable tDiag) {
            Diag.w("AirPlaySession.trackAudioConsumption", tDiag);
            return;
        }
        if (sampleTime < 0) return;
        // 停滞检测：车机一开始消费、后来卡住不动（不等于 0），
        // 说明它的解码/播放缓冲堵了，声音会「越走越晚」最终断掉。
        if (lastSampleTimeSeen >= 0 && sampleTime == lastSampleTimeSeen && sampleTime > 0) {
            sampleStallSec++;
            long now = System.currentTimeMillis();
            if (sampleStallSec >= 10 && (now - sampleStallWarnedAtMs > 60_000)) {
                sampleStallWarnedAtMs = now;
                log("⚠ 车机音频消费停滞：sampleTime 连续 " + sampleStallSec
                        + " 秒停在 " + sampleTime + "（声音会越走越晚，最终可能断）");
            }
        } else {
            sampleStallSec = 0;
        }
        lastSampleTimeSeen = sampleTime;
        lastAudioSampleTime = sampleTime;
        if (sampleTime == 0) {
            audioNotConsumedSec++;
            if (audioNotConsumedSec == 5) {
                log("⚠️ 车机 /feedback 报音频流 sampleTime=0 —— **车机一个样本都没消费**！"
                        + "档位=" + audioProfileLabel() + "；我们发的是 PCM，车机可能只吃 AAC-LC");
            }
        } else {
            if (audioNotConsumedSec > 0) {
                log("✓ 车机开始消费音频了（sampleTime=" + sampleTime + "）");
            }
            audioNotConsumedSec = 0;
        }
    }


    /**
     * 每 60 秒打一行心跳健康度。
     *
     * <p>长会话里最怕的是「日志只剩 /feedback 在刷，但看不出车机到底还认不认我们」。
     * 这一行把三件事放在一起：控制通道成没成、车机有没有在消费音频、视频发了多少帧。
     */
    private void logLivenessIfDue() {
        long now = System.currentTimeMillis();
        if (lastLivenessLogMs == 0) {
            lastLivenessLogMs = now;
            return;
        }
        if (now - lastLivenessLogMs < 60_000) return;
        lastLivenessLogMs = now;
        log("心跳健康：成功 " + feedbackOkCount + " 次 / 失败 " + feedbackFailCount
                + " 次；音频 sampleTime=" + lastSampleTimeSeen
                + "（" + (lastSampleTimeSeen <= 0 ? "车机没在消费" : "车机在消费") + "）"
                + "；视频已发 " + vidTotal + " 帧");
    }

    private void openScreenStream(AirPlayInfo.Display d) throws IOException {        streamConnectionId = (long) (Math.random() * Long.MAX_VALUE) + 1;
        Map<String, Object> stream = new LinkedHashMap<String, Object>();
        stream.put("type", (long) AirPlaySetup.STREAM_SCREEN);
        stream.put("streamConnectionID", streamConnectionId);
        stream.put("latencyMs", 25L);
        stream.put("uuid", d != null ? d.uuid : "0");
        // 真 iPhone 在**每条流的 SETUP 里也报能力清单**（目标车机 的 AirPlay 库
        // 在流协商那组键里也有一份 "features"）。少了它，车机可能按
        // 「这条流不支持 xx」处理。
        stream.put("features", featuresList());
        stream.put("sessionUUID", sessionUuid);

        List<Object> streams = new ArrayList<Object>();
        streams.add(stream);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("streams", streams);
        // 真 iPhone 在流 SETUP 的外层也带上设备身份
        body.put("deviceID", AirPlaySetup.deviceId());
        body.put("sessionUUID", sessionUuid);
        body.put("macAddress", setupMacAddress());
        if (d != null && d.widthPixels > 0 && d.heightPixels > 0) {
            body.put("widthPixels", (long) d.widthPixels);
            body.put("heightPixels", (long) d.heightPixels);
        }

        byte[] reply = requestRaw("SETUP", "/", Bplist.write(body));
        int dataPort = findDataPort(reply, AirPlaySetup.STREAM_SCREEN);
        if (dataPort <= 0) {
            // 没 dataPort 就别往下走：连端口 0 会得到一个看不懂的异常，
            // 现场只会看到「AirPlay 会话失败: ...」而不知道是 SETUP 没答应。
            throw new IOException("屏幕流 SETUP 没回 dataPort（reply=" + reply.length + "B）");
        }
        videoChannel = new Socket();
        videoChannel.connect(new InetSocketAddress(host, dataPort), 3000);
        videoChannel.setTcpNoDelay(true);
        videoOut = null;

        // ⚠️ 加密路径必须跟会话配对状态一致：配对会话走 ChaCha20-Poly1305
        // （key=HKDF(shared,"DataStream-Salt<connId>","DataStream-Output-Encryption-Key")），
        // 旧式 et=1 免配对才走 AES-128-CTR。用错路径对端第一帧就 BAD_DECRYPT 拆会话（实测）。
        videoSealer = null;
        videoCrypto = null;
        videoBroken = false;
        // 新流必须（重新）发一次解码配置，否则车机解码器不启动 → 黑屏
        configDirty = true;
        // 新流也重置关键帧等待：从 IDR 起推，车机解码器才有参考帧
        waitingForKeyFrame = true;
        // 新流重置开屏状态：Car+ 没帧时会重新发一次开屏占位
        splashSent = false;
        lastVideoAt = 0;
        try {
            if (paired && pairSharedSecret != null) {
                videoSealer = new AirPlayStreamCrypto(
                        AirPlayStreamCrypto.derive(pairSharedSecret, streamConnectionId));
                log("视频流加密：配对路径 ChaCha20-Poly1305（connId=" + streamConnectionId + "）");
            } else {
                videoCrypto = new AirPlayCrypto(streamConnectionId);
                log("视频流加密：旧式 AES-128-CTR（et=1，未配对）");
            }
        } catch (Exception e) {
            throw new IOException("视频流加密初始化失败: " + e);
        }
        log("屏幕流已建 dataPort=" + dataPort + " connId=" + streamConnectionId);
    }

    private void openAudioStream() throws IOException {
        // connId 必须与车机侧派生密钥用的一致：车机按我方 SETUP 里的
        // streamConnectionID 算 DataStream-Salt<id>，算错 = 解密失败 = 无声
        audioConnId = (long) (Math.random() * Long.MAX_VALUE) + 1;

        // ⚠️ 音频档位必须**按车机 /info 声明的来**，不能写死。
        // CarDeck 实测：车机按 (type, audioType) 分档声明各���格式位图，
        // 同一台车机 type=100 可能是 compatibility、type=102 才是 media；
        // 而且有些车机（Honda）接受 PCM 档、SETUP 回 200 给 dataPort，
        // 却**一个样本都不消费**（/feedback 的 sampleTime 恒为 0）。
        // 真 iPhone 的媒体音频走的是 **type=102 + AAC-LC 44.1k**。
        AirPlayInfo.AudioFormat pick = pickAudioFormat();
        long streamType = AirPlaySetup.STREAM_AUDIO;
        String audioType = "media";
        long formatBit = 0x800L;          // PCM 44.1k 立体声
        // 车机没上报延迟时按**车机档案**兜底（目标车机 的 CONF_WIRELESS_AUDIO_TIME = 80ms），
        // 而不是拍脑袋的 100ms —— 报得不准车机会按自己的节奏收包，对不上就丢。
        long latencyMs = com.carbridge.carplay.CarProfile.WIRELESS_AUDIO_MS;
        boolean useAac = false;
        if (pick != null) {
            streamType = pick.type;
            if (pick.audioType != null && !pick.audioType.isEmpty()) audioType = pick.audioType;
            latencyMs = pick.outputLatencyMicros > 0 ? pick.outputLatencyMicros / 1000L
                    : com.carbridge.carplay.CarProfile.WIRELESS_AUDIO_MS;
            // ⚠️ 报给车机的是**我们实际用的那一位**，不是车机声明的整张表。
            //   bit22(0x400000) = AAC-LC 44.1k，bit11(0x800) = PCM 44.1k。
            if (pick.supportsAacLc44k()) {
                useAac = true;
                formatBit = 1L << 22;
            } else if (pick.supportsPcm44k()) {
                formatBit = 1L << 11;
            } else {
                formatBit = pick.outputFormats;
            }
        }

        // 载荷编码：车机声明了 AAC-LC 就走 AAC（真 iPhone 的通路），
        // 否则走 PCM。载荷不匹配 = 车机收包但不消费（sampleTime 恒 0）。
        if (useAac) {
            try {
                aacEncoder = new AacLcEncoder(AUDIO_SAMPLE_RATE, 2, AUDIO_BITRATE);
                aacEncoder.start();
            } catch (Throwable t) {
                aacEncoder = null;
                useAac = false;
                formatBit = 1L << 11;      // 降级回 PCM
                log("✗ AAC 编码器起不来，降级回 PCM: " + t);
            }
        }
        audioUseAac = useAac;
        audioFormatBit = formatBit;
        audioStreamType = streamType;
        audioTypeName = audioType;
        log("音频档位：type=" + streamType + " audioType=" + audioType
                + " fmt=0x" + Long.toHexString(formatBit)
                + (audioUseAac ? "（AAC-LC 裸帧，一包一帧）" : "（PCM 大端，352 帧/包）")
                + " 延迟=" + latencyMs + "ms");

        Map<String, Object> stream = new LinkedHashMap<String, Object>();
        stream.put("type", streamType);
        stream.put("audioType", audioType);
        // ⚠️ audioFormat 是位图（catplay AudioFormat）：
        //   bit11(0x800)=PCM44.1k 立体声，bit15(0x8000)=PCM48k 立体声，
        //   bit22(0x400000)=AAC-LC 44.1k。**要报车机声明的那一位，不是整张表。**
        // Car+ 的 MEDIA_INIT 实测是 44100Hz/2ch/16bit，采样率必须对得上，
        // 否则车机按错的时钟解析 RTP（352 帧的包间隔算错）→ 累积漂移、爆音。
        stream.put("audioFormat", formatBit);
        stream.put("streamConnectionID", audioConnId);
        stream.put("audioLatencyMs", latencyMs);
        stream.put("supportsRTPPacketRedundancy", false);
        // 真 iPhone 也会在流的 SETUP 里报能力清单 + 会话 UUID
        stream.put("features", featuresList());
        stream.put("sessionUUID", sessionUuid);
        // 冗余音频：车机 key 表里的名字是 redundantAudio（我们确实不做冗余）
        stream.put("redundantAudio", Boolean.FALSE);
        stream.put("supportsHighAccuracyTimestamps", true);

        List<Object> streams = new ArrayList<Object>();
        streams.add(stream);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("streams", streams);

        byte[] reply = requestRaw("SETUP", "/", Bplist.write(body));
        audioPort = findDataPort(reply, (int) streamType);
        if (audioPort <= 0) {
            // 不抛：视频还能跑，没声音总比整条会话挂掉强（日志里要能看出来）。
            log("⚠️ 音频流 SETUP 没回 dataPort（type=" + streamType
                    + "）→ 车机不会出声，视频继续");
        }
        audioSocket = new DatagramSocket();
        audioAddr = InetAddress.getByName(host);
        audioPacker = new RtpAudio((int) (Math.random() * 65535), (int) streamType);
        audioAccumLen = 0;
        audioRtpTs = 0;
        audioPackets = 0;
        audioBroken = false;
        audioSealer = null;
        try {
            // 车机侧（DiPlay onAudio → dataStreamKey）派生规则：
            // HKDF(shared, "DataStream-Salt"+connId, "DataStream-Output-Encryption-Key")
            // 与我方 AirPlayStreamCrypto.derive 完全一致
            if (paired && pairSharedSecret != null) {
                audioSealer = new AirPlayStreamCrypto(
                        AirPlayStreamCrypto.derive(pairSharedSecret, audioConnId));
                log("音频流加密：配对路径 ChaCha20-Poly1305（connId=" + audioConnId + "）");
            } else {
                log("⚠️ 未配对，音频流无加密（车机可能不收明文音频）");
            }
        } catch (Exception e) {
            throw new IOException("音频流加密初始化失败: " + e);
        }
        log("音频流已建 dataPort=" + audioPort + " connId=" + audioConnId
                + " addr=" + audioAddr.getHostAddress()
                + " 档位=" + audioProfileLabel());

        // ── 通话流（telephony）────────────────────────────────────────────
        // 真 iPhone 会**同时**开好几条音频流，键是 (type, audioType)：
        //   100/media（音乐）、100/default（导航语音）、100/telephony（**通话声**）…
        // DiPlay（真实 CarPlay 接收端）的注释原话：
        //   "music, guidance and Siri can all arrive as type 100;
        //    only the same (type, audioType) pair replaces a previous stream."
        // 而 telephony 档声明的是 **单声道** PCM（0x400）或 Opus。
        // ⇒ 只开 100/media 一条，车机的通话 UI 就没有音频通路。
        openTelephonyStream();
    }

    /**
     * 开一条 {@code 100/telephony} 音频流（通话声专用，单声道 PCM）。
     *
     * <p>车机没声明 telephony 档就跳过（不硬开，免得车机不认）。
     */
    private void openTelephonyStream() {
        if (info == null) return;
        AirPlayInfo.AudioFormat tel = null;
        for (AirPlayInfo.AudioFormat f : info.audioFormats()) {
            if ("telephony".equalsIgnoreCase(f.audioType)) {
                tel = f;
                break;
            }
        }
        if (tel == null) {
            log("车机未声明 telephony 音频档 → 不开通话流（通话声只能走车机 HFP 通路）");
            return;
        }
        // 0x400 = PCM 44.1kHz **单声道**（telephony 档常用）
        long fmt = (tel.outputFormats & (1L << 10)) != 0 ? (1L << 10) : tel.outputFormats;
        long connId = (long) (Math.random() * Long.MAX_VALUE) + 1;
        try {
            Map<String, Object> stream = new LinkedHashMap<String, Object>();
            stream.put("type", tel.type);
            stream.put("audioType", "telephony");
            stream.put("audioFormat", fmt);
            stream.put("streamConnectionID", connId);
            stream.put("features", featuresList());
            stream.put("sessionUUID", sessionUuid);
            stream.put("audioLatencyMs", (long) com.carbridge.carplay.CarProfile.WIRELESS_AUDIO_MS);
            stream.put("supportsRTPPacketRedundancy", false);
            stream.put("supportsHighAccuracyTimestamps", true);
            List<Object> streams = new ArrayList<Object>();
            streams.add(stream);
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("streams", streams);

            byte[] reply = requestRaw("SETUP", "/", Bplist.write(body));
            telephonyPort = findDataPort(reply, (int) tel.type);
            telephonySocket = new DatagramSocket();
            telephonyAddr = InetAddress.getByName(host);
            telephonyPacker = new RtpAudio((int) (Math.random() * 65535), (int) tel.type);
            if (paired && pairSharedSecret != null) {
                telephonySealer = new AirPlayStreamCrypto(
                        AirPlayStreamCrypto.derive(pairSharedSecret, connId));
            }
            telephonyConnId = connId;
            log("✓ 通话音频流已建 type=" + tel.type + " audioType=telephony fmt=0x"
                    + Long.toHexString(fmt) + " dataPort=" + telephonyPort + " connId=" + connId);
        } catch (Throwable t) {
            log("通话音频流开不起来（车机可能不收 telephony 档）: " + t);
        }
    }

    /** 车机声明的音频档位表（诊断用）。 */
    private String audioProfileLabel() {
        StringBuilder sb = new StringBuilder();
        if (info != null) {
            for (AirPlayInfo.AudioFormat f : info.audioFormats()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(f);
            }
        }
        if (sb.length() == 0) sb.append("(车机 /info 未声明 audioFormats)");
        return sb.toString();
    }

        /**
     * 从车机声明里挑一个我们能发的档位。
     */
    private AirPlayInfo.AudioFormat pickAudioFormat() {
        if (info == null) return null;
        List<AirPlayInfo.AudioFormat> all = info.audioFormats();
        if (all.isEmpty()) {
            log("WARN: 车机 /info 没声明 audioFormats，按 PCM44.1k 兜底");
            return null;
        }
        for (AirPlayInfo.AudioFormat f : all) {
            if (f.type == 102 && f.supportsAacLc44k()) {
                log("车机音频档位：选 " + f + "（type=102 + AAC-LC，真 iPhone 的通路）");
                return f;
            }
        }
        for (AirPlayInfo.AudioFormat f : all) {
            if (f.supportsAacLc44k()) {
                log("车机音频档位：选 " + f + "（AAC-LC）");
                return f;
            }
        }
        for (AirPlayInfo.AudioFormat f : all) {
            if (f.supportsPcm44k()) {
                log("车机音频档位：选 " + f + "（PCM 44.1k，与 Car+ MEDIA 采样率一致）");
                return f;
            }
        }
        for (AirPlayInfo.AudioFormat f : all) {
            if (f.supportsPcm48k()) {
                log("⚠️ 车机只声明 PCM 48k（" + f + "），而 Car+ 的 MEDIA 是 44.1k，"
                        + "采样率不匹配会导致车机按错时钟解析");
            }
        }
        log("车机音频档位：没有可用的档位，退回默认（type=100/media/PCM44.1k）");
        return null;
    }

    private void startKeepalives(final int keepAlivePort) throws IOException {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int n = 0;
                int failures = 0;
                int loopErrors = 0;
                while (running) {
                    try {
                        Thread.sleep(1000);
                        if (!running) return;
                        n++;
                        try {
                            byte[] fb = requestRaw("POST", "/feedback", null);
                            trackAudioConsumption(fb);
                            failures = 0;
                            feedbackOkCount++;
                            logLivenessIfDue();
                        } catch (Exception e) {
                            failures++;
                            feedbackFailCount++;
                            // ⚠️ 会话 ERROR 时 running 仍为 true（stop() 未必被调用），
                            // 不设上限会对着已死的 socket 每秒刷屏（实测刷 53 次）。
                            if (failures >= 3) {
                                log("/feedback 连续 " + failures + " 次失败，判定控制通道已断，停止心跳: " + e);
                                closeQuietly(control);
                                if (running) setState(State.ERROR);
                                return;
                            }
                            log("/feedback 失败: " + e);
                        }
                        if (videoChannel != null && !videoChannel.isClosed()) {
                            sendVideoPacket(VideoPackets.keepAlive(info != null && info.keepAliveSendStatsAsBody));
                            // Car+ 断流（>3s 无帧）时，用品牌开屏兜底，避免车机黑屏
                            if (n % 2 == 0 && Math.max(0L, System.currentTimeMillis() - lastVideoAt) > 3000) {
                                sendSplashFrame();
                            }
                        }
                        if (keepAlivePort > 0 && keepAliveSocket != null) {
                            byte[] ping = new byte[4];
                            keepAliveSocket.send(new DatagramPacket(ping, ping.length,
                                    InetAddress.getByName(host), keepAlivePort));
                        }
                        if (n == 1 || n == 30) log("心跳 ×" + n);
                    } catch (InterruptedException ie) {
                        return;
                    } catch (Exception e) {
                        // 这里抓的是「非 /feedback」的异常（视频 keepalive 写已关通道、
                        // DNS 解析失败、UDP 发送失败…）。以前直接 return → 心跳线程死掉但
                        // running 仍是 true：车机等不到心跳超时断流，UI 却一直显示「推流中」。
                        loopErrors++;
                        if (loopErrors <= 3 || loopErrors % 60 == 0) {
                            log("心跳循环异常（第 " + loopErrors + " 次，继续跑）: " + e);
                        }
                        if (loopErrors >= 600) {          // 约 10 分钟都在错：判死
                            log("心跳连续异常 " + loopErrors + " 次，判定会话不可用");
                            if (running) setState(State.ERROR);
                            return;
                        }
                        continue;
                    }
                }
            }
        }, "airplay-keepalive");
        t.setDaemon(true);
        t.start();
        keepAliveSocket = new DatagramSocket();
    }

    // ================================================================== 视频

    /** 把 CarLife 的一帧 H.264 推给车机。 */
    public void submitVideo(byte[] annexB, long ptsMs) {
        // ⚠️ **无论是否在推流，都要先缓存解码配置**：CarLife/Car+ 只在流开头（或码流
        //    重置时）发一次 SPS+PPS，而 AirPlay 会话常常在那之后才建好。若等到
        //    STREAMING 才解析，就永远抓不到 PPS → 发不出 OP_VIDEO_CONFIG → 车机没 avcC
        //    → 解码器不启动 → 黑屏（真机实测：DiPlay 只有 video first decrypted frame，
        //    没有 video codec config）。
        try {
            cacheCodecConfig(annexB);
        } catch (Throwable t) {
            // 畸形帧的解析异常不能逃出视频回调线程（一帧坏 ≠ 链路死）
            Diag.w("AirPlaySession.cacheCodecConfig", t);
        }

        // Car+ 有真实帧了：记录时间戳，用于「断流则切回开屏」判断
        lastVideoAt = System.currentTimeMillis();
        splashSent = false;

        // ⚠️ 开屏期间车机解码器用的是开屏 SPS/PPS；Car+ 帧回来必须重新发 Car+ config
        //    并重新等 IDR（解码器刚被开屏洗过，P 帧没参考帧解不出）
        if (splashConfigActive && cachedAvcC != null) {
            configDirty = true;
            splashConfigActive = false;
            waitingForKeyFrame = true;
        }

        if (state != State.STREAMING || videoChannel == null) return;
        if (videoBroken) return;   // 通道已死，别再刷日志
        try {
            synchronized (videoSendLock) {
                // 新建流或新拿到 SPS+PPS → 先发一次配置，解码器才能起
                if (configDirty && cachedAvcC != null) {
                    sendVideoPacket(VideoPackets.videoConfig(cachedW, cachedH, cachedAvcC));
                    configDirty = false;
                    log("已发 OP_VIDEO_CONFIG avcC=" + cachedAvcC.length + "B " + cachedW + "x" + cachedH);
                }
                // ⚠️ 等 IDR：丢掉关键帧之前的 P 帧（车机解码器没有参考帧无法起步）
                if (waitingForKeyFrame) {
                    if (!H264.isKeyFrame(annexB)) return;
                    waitingForKeyFrame = false;
                    log("等到 IDR 关键帧，开始推视频帧");
                }
                byte[] avcc = H264.annexBToAvcc(annexB);
                if (avcc == null) return;
                long ntp = ntpTimestamp();
                long t0 = System.nanoTime();
                if (videoSealer != null) {
                    sendVideoPacket(VideoPackets.videoFrameSealed(avcc, ntp, videoSealer));
                } else {
                    if (videoCrypto != null) {
                        avcc = videoCrypto.process(avcc);
                    }
                    sendVideoPacket(VideoPackets.videoFrame(avcc, ntp));
                }
                trackVideoLatency(System.nanoTime() - t0);
            }
        } catch (Exception e) {
            if (videoBroken) return;
            if (!(e instanceof java.io.IOException)) {
                // 加密/封装/数组越界这类是「这一帧的问题」，不是通道死了。
                // 以前一律置 videoBroken，一帧异常就永久停推，只能重连才恢复。
                droppedVideoFrames++;
                if (droppedVideoFrames <= 3 || droppedVideoFrames % 100 == 0) {
                    log("丢弃异常视频帧（第 " + droppedVideoFrames + " 帧）: " + e);
                }
                return;
            }
            videoBroken = true;
            // 通道已断：关掉避免每帧重复报 Broken pipe（实测每秒刷屏）
            log("视频通道断开，停止推流: " + e);
            closeQuietly(videoChannel);
            videoChannel = null;
            videoOut = null;
            setState(State.ERROR);
        }
    }

    /**
     * 发一帧品牌开屏画面（Car+ 还没给帧 / 断流时的兜底）。
     *
     * <p>开屏帧是自编码的 SPS+PPS+IDR，独立于 Car+ 的码流。发它时要带自己的
     * avcC 配置，车机解码器才能起；之后 Car+ 帧一来（cacheCodecConfig 发现配置
     * 变化会重新置 configDirty），会自动切回真实画面。
     */
    private void sendSplashFrame() {
        SplashFrame sf = splash;
        if (sf == null) return;
        int w = displayW > 0 ? displayW : (cachedW > 0 ? cachedW : 1920);
        int h = displayH > 0 ? displayH : (cachedH > 0 ? cachedH : 720);
        SplashFrame.Frame f = sf.frame(w, h);
        if (f == null) return;
        try {
            synchronized (videoSendLock) {
                if (configDirty || !splashSent) {
                    sendVideoPacket(VideoPackets.videoConfig(f.width, f.height, f.avcC));
                    configDirty = false;
                    splashConfigActive = true;   // 车机现在用的是开屏 SPS/PPS
                }
                byte[] avcc = H264.annexBToAvcc(f.annexB);
                if (avcc == null) return;
                long ntp = ntpTimestamp();
                if (videoSealer != null) {
                    sendVideoPacket(VideoPackets.videoFrameSealed(avcc, ntp, videoSealer));
                } else {
                    if (videoCrypto != null) {
                        avcc = videoCrypto.process(avcc);
                    }
                    sendVideoPacket(VideoPackets.videoFrame(avcc, ntp));
                }
                if (!splashSent) {
                    splashSent = true;
                    log("已发品牌开屏画面（Car+ 尚未给帧，先显示 logo 占位）");
                }
            }
        } catch (Exception e) {
            // 开屏发送失败不致命（心跳线程里，别把心跳带崩）
        }
    }

        /**
     * 手机侧一段音频载荷 → 攒满 352 帧打成 RTP → 加密 → UDP 发给车机。
     */
    public void submitAudio(byte[] pcm, long ptsMs) {
        if (state != State.STREAMING) return;
        DatagramSocket sock = audioSocket;
        if (sock == null || audioAddr == null || audioPort <= 0 || audioPacker == null) return;
        if (audioBroken) return;
        if (pcm == null || pcm.length == 0) return;

        try {
            if (audioUseAac && (aacEncoder == null || !aacEncoder.isStarted())) {
                // 协商的是 AAC，编码器却停了（AacLcEncoder 内部异常会自行 stop()）。
                // 以前这里会静默走 PCM 分支：同一条流里塞 PCM，车机按 AAC 解 → 噪音/静音。
                // 正确做法是停掉本会话的音频；真要降级 PCM，必须用新 connId 重新 SETUP。
                if (!audioBroken) {
                    audioBroken = true;
                    log("⚠ AAC 编码器已失效 → 本会话停止推音频（重连后才可能降级 PCM）");
                }
                return;
            }
            if (audioUseAac && aacEncoder != null && aacEncoder.isStarted()) {
                submitAac(sock, pcm);
                return;
            }
            // 攒进缓冲（必要时扩容）
            if (audioAccumLen + pcm.length > audioAccum.length) {
                byte[] bigger = new byte[Math.max(audioAccum.length * 2, audioAccumLen + pcm.length + 4096)];
                System.arraycopy(audioAccum, 0, bigger, 0, audioAccumLen);
                audioAccum = bigger;
            }
            System.arraycopy(pcm, 0, audioAccum, audioAccumLen, pcm.length);
            audioAccumLen += pcm.length;

            final int need = RtpAudio.BYTES_PER_PACKET;
            while (audioAccumLen >= need) {
                long ts = audioRtpTs;
                // ⚠️ **字节序：小端 → 大端**。Car+ 发的是 Android 原生小端 PCM，
                // 而车机侧（DiPlay AndroidMediaSink:2233）收到后会做一次
                // `byteSwapS16()` 再写 AudioTrack——它假定收到的是大端（AirPlay 约定）。
                // 不转换就等于把小端当大端再交换一次 → 全是杂音（真机实测）。
                byte[] le = new byte[need];
                System.arraycopy(audioAccum, 0, le, 0, need);
                swap16(le);
                byte[] rtp = audioPacker.packet(le, 0, need, ts);
                byte[] wire;
                if (audioSealer != null) {
                    byte[] header = new byte[12];
                    System.arraycopy(rtp, 0, header, 0, 12);
                    byte[] sealed = audioSealer.encryptAudio((int) ts, 0,
                            java.util.Arrays.copyOfRange(rtp, 12, rtp.length));
                    wire = new byte[header.length + sealed.length];
                    System.arraycopy(header, 0, wire, 0, header.length);
                    System.arraycopy(sealed, 0, wire, header.length, sealed.length);
                } else {
                    wire = rtp;
                }
                sock.send(new java.net.DatagramPacket(wire, wire.length, audioAddr, audioPort));
                audioRtpTs += RtpAudio.FRAMES_PER_PACKET;
                audioPackets++;
                // 挪走已发的部分
                System.arraycopy(audioAccum, need, audioAccum, 0, audioAccumLen - need);
                audioAccumLen -= need;
                if (audioPackets == 1) {
                    log("★ 音频首包已发 wire=" + wire.length + "B 352帧 connId=" + audioConnId
                            + (audioSealer != null ? "（加密）" : "（明文）"));
                } else if (audioPackets == 500) {
                    log("音频已发 " + audioPackets + " 包");
                }
            }
        } catch (Exception e) {
            if (audioBroken) return;
            audioBroken = true;
            log("音频发送失败，停止推音频: " + e);
        }
    }

    /**
     * AAC-LC 通路：PCM → 编码 → <strong>一包一帧</strong>的裸 AAC RTP。
     *
     * <p>⚠️ AAC 通路<strong>不换序</strong>：比特流不是 16bit 样本序列，换序会搅烂。
     * 而且喂给 {@code MediaCodec} 的必须是小端 —— Car+ 的 MEDIA 本来就是小端，直接喂。
     */
    private void submitAac(DatagramSocket sock, byte[] pcm) throws Exception {
        List<byte[]> frames = aacEncoder.encode(pcm, pcm.length);
        for (byte[] frame : frames) {
            if (frame == null || frame.length == 0) continue;
            long ts = audioRtpTs;
            byte[] rtp = audioPacker.packet(frame, 0, frame.length, ts);
            byte[] wire;
            if (audioSealer != null) {
                byte[] header = new byte[12];
                System.arraycopy(rtp, 0, header, 0, 12);
                byte[] sealed = audioSealer.encryptAudio((int) ts, 0,
                        java.util.Arrays.copyOfRange(rtp, 12, rtp.length));
                wire = new byte[header.length + sealed.length];
                System.arraycopy(header, 0, wire, 0, header.length);
                System.arraycopy(sealed, 0, wire, header.length, sealed.length);
            } else {
                wire = rtp;
            }
            sock.send(new java.net.DatagramPacket(wire, wire.length, audioAddr, audioPort));
            audioRtpTs += RtpAudio.FRAMES_PER_PACKET_AAC;
            audioPackets++;
            if (audioPackets == 1) {
                log("★ AAC 音频首帧已发 wire=" + wire.length + "B raw=" + frame.length
                        + "B connId=" + audioConnId
                        + (audioSealer != null ? "（加密）" : "（明文）"));
            } else if (audioPackets == 200) {
                log("AAC 音频已发 " + audioPackets + " 帧");
            }
        }
    }

    /**
     * 当前活跃的会话（同一时刻只可能有一个）。
     *
     * <p>给「通话音频」这类旁路用的：它们只拿到 Context，拿不到 Activity 手里那个
     * CarPlayBridge 实例，但有会话在跑时就必须能把 PCM 塞进去。
     */
    private static volatile AirPlaySession CURRENT;

    public static AirPlaySession current() {
        return CURRENT;
    }

    /** 车机是否声明并已建立起 telephony 音频流（通话音频走这条，HFP 是备选）。 */
    public boolean hasTelephonyStream() {
        return state == State.STREAMING && telephonySocket != null
                && telephonyAddr != null && telephonyPort > 0 && telephonyPacker != null;
    }

    /** 提交**通话声**（audioType=telephony 的那条流）。 */
    /**
     * 送一段通话 PCM。
     *
     * <p>⚠️ synchronized：telephony 流有**两个生产者** —— HuClient 在通话中把 9240 的
     * MEDIA 直接转进来，CallAudioCapture 的采集线程也往里送。accumulator 与 RTP 时间戳
     * 的自增都是非原子复合操作，不加锁会字节交错 → 车机听到断续噪音/回声。
     */
    public synchronized void submitTelephonyAudio(byte[] pcm, long ptsMs) {
        if (state != State.STREAMING) return;
        if (telephonySocket == null || telephonyAddr == null || telephonyPort <= 0
                || telephonyPacker == null || pcm == null || pcm.length == 0) {
            return;
        }
        try {
            // 下混：每帧取 (L+R)/2，输出仍是小端（稍后整体换序成大端）
            int frames = pcm.length / 4;
            if (frames <= 0) return;
            if (telephonyAccumLen + frames * 2 > telephonyAccum.length) {
                byte[] bigger = new byte[Math.max(telephonyAccum.length * 2,
                        telephonyAccumLen + frames * 2 + 4096)];
                System.arraycopy(telephonyAccum, 0, bigger, 0, telephonyAccumLen);
                telephonyAccum = bigger;
            }
            byte[] mono = new byte[frames * 2];
            for (int i = 0; i < frames; i++) {
                int l = (short) le16(pcm, i * 4);
                int r = (short) le16(pcm, i * 4 + 2);
                int v = (l + r) / 2;
                mono[i * 2] = (byte) (v & 0xFF);
                mono[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
            }
            System.arraycopy(mono, 0, telephonyAccum, telephonyAccumLen, mono.length);
            telephonyAccumLen += mono.length;

            final int need = RtpAudio.FRAMES_PER_PACKET * 2;   // 单声道：352 帧 × 2B
            while (telephonyAccumLen >= need) {
                long ts = telephonyRtpTs;
                byte[] be = new byte[need];
                System.arraycopy(telephonyAccum, 0, be, 0, need);
                swap16(be);                       // 小端 → 大端（AirPlay 约定）
                byte[] rtp = telephonyPacker.packet(be, 0, need, ts);
                byte[] wire;
                if (telephonySealer != null) {
                    byte[] header = new byte[12];
                    System.arraycopy(rtp, 0, header, 0, 12);
                    byte[] sealed = telephonySealer.encryptAudio((int) ts, 0,
                            java.util.Arrays.copyOfRange(rtp, 12, rtp.length));
                    wire = new byte[header.length + sealed.length];
                    System.arraycopy(header, 0, wire, 0, header.length);
                    System.arraycopy(sealed, 0, wire, header.length, sealed.length);
                } else {
                    wire = rtp;
                }
                telephonySocket.send(new java.net.DatagramPacket(
                        wire, wire.length, telephonyAddr, telephonyPort));
                telephonyRtpTs += RtpAudio.FRAMES_PER_PACKET;
                telephonyPackets++;
                System.arraycopy(telephonyAccum, need, telephonyAccum, 0, telephonyAccumLen - need);
                telephonyAccumLen -= need;
                if (telephonyPackets == 1) {
                    log("★ 通话音频首包已发 wire=" + wire.length + "B 单声道352帧 connId=" + telephonyConnId);
                }
            }
        } catch (Exception e) {
            log("通话音频发送失败: " + e);
        }
    }

    /** 读小端 16 位样本（CarLife MEDIA 通道的 PCM 是 Android 原生小端）。 */
    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    /** 逐 16 位样本交换字节（小端 PCM ↔ 大端 PCM）。 */
    private static void swap16(byte[] b) {        for (int i = 0; i + 1 < b.length; i += 2) {
            byte t = b[i];
            b[i] = b[i + 1];
            b[i + 1] = t;
        }
    }

    /**
     * 由桥接层注入**会话建立之前**就抓到的 SPS+PPS。
     *
     * <p>Car+ 只在 CarLife 流开头发一次解码配置；而 AirPlay 会话是之后才建的，
     * 桥接层在会话为 null 时会丢帧——不在那里缓存的话，会话内永远等不到 PPS，
     * 发不出 OP_VIDEO_CONFIG → 车机解码器不启动 → 黑屏（真机实测）。</p>
     */
    public void setCodecConfig(byte[] avcC, int width, int height) {
        if (avcC == null || avcC.length == 0) return;
        cachedAvcC = avcC;
        cachedW = width;
        cachedH = height;
        configDirty = true;
        lastCfgState = 2;
        log("注入解码配置 avcC=" + avcC.length + "B " + width + "x" + height);
    }

    /**
     * 抓取并缓存 SPS+PPS（构造 avcC）。与推流状态无关。
     * 状态变化时打一条日志，便于判断车机为何不显示画面。
     */
    private void cacheCodecConfig(byte[] annexB) {
        byte[] avcC = VideoPackets.avcCFromAnnexB(annexB);
        if (avcC != null) {
            boolean isNew = cachedAvcC == null
                    || !java.util.Arrays.equals(cachedAvcC, avcC);
            cachedAvcC = avcC;
            int[] sps = spsOf(annexB);
            if (sps != null) {
                cachedW = sps[0];
                cachedH = sps[1];
            }
            if (isNew) {
                configDirty = true;
                lastCfgState = 2;
                log("缓存解码配置 SPS+PPS → avcC=" + avcC.length + "B " + cachedW + "x" + cachedH);
            }
        } else if (hasSps(annexB)) {
            if (lastCfgState != 1) {          // 只在状态变化时报，避免刷屏
                lastCfgState = 1;
                log("⚠ 帧含 SPS 但缺 PPS，暂无法构建 avcC（等 PPS）");
            }
        } else if (lastCfgState != 0 && cachedAvcC == null) {
            lastCfgState = 0;
        }
    }

    private boolean hasSps(byte[] annexB) {
        return H264.hasSps(annexB);
    }

    private int[] spsOf(byte[] annexB) {
        for (byte[] nal : H264.splitNals(annexB)) {
            if (nal.length > 0 && (nal[0] & 0x1F) == H264.NAL_SPS) {
                int[] d = H264.spsDimensions(nal);
                if (d != null) return d;
            }
        }
        return null;
    }

    private long ntpTimestamp() {
        long micros = (System.nanoTime() - streamStartNanos) / 1000;
        // ⚠️ 不能写 (micros << 32) / 1e6：micros 超过 2^31（≈35.8 分钟）左移就溢出成负数，
        // 视频 RTP 时间戳变负、车机时钟错乱。拆成「秒 + 小数」两段，全部落在 64 位内。
        long secs = micros / 1000000L;
        long frac = ((micros % 1000000L) << 32) / 1000000L;
        return (secs << 32) | (frac & 0xFFFFFFFFL);
    }

    /** 视频转发耗时统计（每 5 秒一条）：区分「app 转发慢」还是「上游/网络慢」。 */
    private long vidCount, vidSumNanos, vidMaxNanos, vidLastLogMs, vidLastArriveNanos;
    private long vidGapSumNanos, vidGapMaxNanos, vidGapCount;
    /** 1 秒滑动桶里最多收到多少帧 —— 用来区分「内容静止所以帧少」和「上游被限速」。 */
    private long vidSecStartMs, vidSecCount, vidSecMax;
    /** 整个会话累计发出的视频帧数（只增不减，给心跳汇总用）。 */
    private long vidTotal;

    private void trackVideoLatency(long nanos) {
        long nowNs = System.nanoTime();
        if (vidLastArriveNanos != 0) {
            long gap = nowNs - vidLastArriveNanos;
            vidGapSumNanos += gap;
            vidGapMaxNanos = Math.max(vidGapMaxNanos, gap);
            vidGapCount++;
        }
        vidLastArriveNanos = nowNs;
        long nowMsSec = System.currentTimeMillis();
        if (vidSecStartMs == 0) {
            vidSecStartMs = nowMsSec;
        } else if (nowMsSec - vidSecStartMs >= 1000) {
            vidSecMax = Math.max(vidSecMax, vidSecCount);
            vidSecStartMs = nowMsSec;
            vidSecCount = 0;
        }
        vidSecCount++;
        vidTotal++;
        vidCount++;
        vidSumNanos += nanos;
        vidMaxNanos = Math.max(vidMaxNanos, nanos);
        long nowMs = System.currentTimeMillis();
        if (vidLastLogMs == 0) {
            vidLastLogMs = nowMs;
            return;
        }
        if (nowMs - vidLastLogMs >= 5000 && vidCount > 0) {
            log(String.format(java.util.Locale.US,
                    "视频转发 %.2fms/帧（峰 %.2fms）· 帧间隔 %.1fms（峰 %.1fms）· %d 帧/5s · 峰值 %d 帧/秒",
                    vidSumNanos / 1e6 / vidCount, vidMaxNanos / 1e6,
                    vidGapCount > 0 ? vidGapSumNanos / 1e6 / vidGapCount : 0,
                    vidGapMaxNanos / 1e6, vidCount, vidSecMax));
            vidLastLogMs = nowMs;
            vidCount = vidSumNanos = vidMaxNanos = 0;
            vidGapSumNanos = vidGapMaxNanos = vidGapCount = 0;
            vidSecMax = 0;
        }
    }

    /** 视频通道输出流缓存（每帧 getOutputStream 会多一次分配）。 */
    private OutputStream videoOut;

    private void sendVideoPacket(byte[] packet) throws IOException {
        // ⚠️ 必须自己拿锁：视频线程在 synchronized(videoSendLock) 里调它，但
        // **心跳线程是裸调的**（每秒一次 keepalive）。两条线程同时写同一个
        // OutputStream 时，大帧分多次 write 会交错，车机收到坏帧；
        // 锁可重入，已经在锁里的调用方不受影响。
        synchronized (videoSendLock) {
            OutputStream out = videoOut;
            if (out == null) {
                out = videoChannel.getOutputStream();
                videoOut = out;
            }
            videoWriteStartMs = System.currentTimeMillis();
            try {
                out.write(packet);
                out.flush();
            } finally {
                videoWriteStartMs = 0;
                lastVideoPacketAtMs = System.currentTimeMillis();
            }
        }
    }

    // ================================================================== 事件通道


    /** 事件消息 body 上限：真实 plist 只有几 KB，超过它一定是长度字段坏了。 */
    private static final int MAX_EVENT_BODY = 64 * 1024;

    /** 解析失败就沿用默认值（事件通道的 header 不值得为一个坏字段打断整条流）。 */
    private static int parseIntSafe(String v, int def) {
        try {
            return Integer.parseInt(v);
        } catch (Throwable t) {
            return def;
        }
    }

    /** 跑一个一次性后台任务（不需要返回值、不占调用线程）。 */
    private static void fireAndForget(Runnable r) {
        Thread t = new Thread(r, "airplay-bg");
        t.setDaemon(true);
        t.start();
    }

    private void eventLoop(Socket s) {
        try {
            // 有读密钥就先解密再当明文解析（半帧由 DecryptedStream/ControlCipher 缓冲）
            ControlCipher readCipher = eventReadCipher;
            InputStream raw = s.getInputStream();
            InputStream in = readCipher != null ? new DecryptedStream(raw, readCipher) : raw;
            log("事件循环已启动 enc=" + (readCipher != null)
                    + " local=" + s.getLocalPort() + " remote=" + s.getPort());
            while (running) {
                String statusLine = readLine(in);
                if (statusLine == null) {
                    log("事件循环：输入流结束（对端关闭）");
                    break;
                }
                // 两档留痕：前 3 条**逐行全打**（验证 header/body 不再错位），之后只留
                // 每条一行的结果摘要；逐行档是当初定位「Content-Length 处提前 readN
                // 吃掉 CSeq 行」那个 bug 的证据，保留但不再随会话累积刷屏。
                final boolean detail = eventDetailCnt < 3;
                if (detail) {
                    log("事件循环状态行[" + eventRxLogged + "]: " + statusLine);
                }
                eventRxLogged++;
                int cseq = -1;
                String path = "";
                String method = "";
                String[] parts = statusLine.split(" ");
                if (parts.length >= 2) {
                    method = parts[0];
                    path = parts[1];
                }
                String line;
                byte[] body = null;
                int contentLen = -1;
                int headerCount = 0;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    if (detail) {
                        // 非 ASCII 说明流里混进了明文之外的字节（帧封装残留），打印 hex 定性
                        boolean ascii = true;
                        for (int i = 0; i < line.length(); i++) {
                            if (line.charAt(i) < 0x20 || line.charAt(i) > 0x7E) {
                                ascii = false;
                                break;
                            }
                        }
                        log("事件循环: header[" + headerCount + "] = "
                                + (ascii ? line : ("HEX " + fullHex(line.getBytes("ISO-8859-1")))));
                    }
                    headerCount++;
                    if (line.toLowerCase(Locale.US).startsWith("cseq:")) {
                        // ⚠️ 不能直接 parseInt：一条 `CSeq: abc` 就会抛 NumberFormatException
                        // 逃出整个 while，事件线程直接死掉 —— 之后这个会话再也收不到触摸/硬键。
                        cseq = parseIntSafe(line.substring(5).trim(), cseq);
                    } else if (line.toLowerCase(Locale.US).startsWith("content-length:")) {
                        contentLen = parseIntSafe(line.substring(15).trim(), contentLen);
                    }
                }
                // ⚠️ body 必须在 **header 全部读完之后** 才读。DiPlay 的 head 顺序是
                // Content-Type → Content-Length → CSeq → 空行；若在读到 Content-Length 时
                // 立刻 readN，就会把后面的 `CSeq: N\r\n`+`\r\n` 一起吃进 body，
                // 于是 body 少读 N 字节、真实 body 的尾巴（bplist 尾部）留在流里，
                // 粘到下一条状态行前面 → 从第二条起全部错位，触摸永远解析不出（真机实测）。
                if (contentLen > MAX_EVENT_BODY) {
                    // 头已消费、长度不可信 → 这条流没法再对齐，只能停掉事件通道（画面继续）
                    log("事件通道 body 长度异常（" + contentLen + "B，上限 " + MAX_EVENT_BODY
                            + "）→ 事件流已错位，停止事件通道（触摸/硬键本会话不可用）");
                    break;
                }
                if (contentLen > 0) {
                    if (detail) {
                        log("事件循环: header 读完（" + headerCount + " 行），读 body len=" + contentLen);
                    }
                    body = readN(in, contentLen);
                    if (detail) {
                        log("事件循环: body 读到 " + (body == null ? -1 : body.length) + "B");
                    }
                }
                if (detail) {
                    log("事件循环: 准备回 200 cseq=" + cseq);
                }
                // ⚠️ 车机命令必须走事件通道 socket（独立 CSeq），且必须每条都回 200
                OutputStream out = s.getOutputStream();
                // 对端没带 CSeq（或解析失败）时不能回字面 `CSeq: -1`，省略该头
                byte[] resp = (cseq >= 0
                        ? "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n\r\n"
                        : "RTSP/1.0 200 OK\r\n\r\n").getBytes("US-ASCII");
                ControlCipher writeCipher = eventWriteCipher;
                if (writeCipher != null) {
                    resp = writeCipher.encrypt(resp);   // 对端按密文读，明文 200 它解不开
                }
                eventWriteStartMs = System.currentTimeMillis();
                try {
                    out.write(resp);
                    out.flush();
                } finally {
                    eventWriteStartMs = 0;
                }
                if (eventRxLogged < 20) {
                    log("事件循环: 已回 200 cseq=" + cseq + " resp=" + resp.length + "B");
                }
                if (detail) {
                    eventDetailCnt++;
                }

                if (body != null && body.length > 0) {
                    Object o = null;
                    String err = null;
                    try {
                        o = Bplist.read(body);
                    } catch (Exception e) {
                        err = String.valueOf(e);
                    }
                    if (o instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) o;
                        String type = String.valueOf(m.get("type"));
                        // 按时间限流（而非计数），保证长时间运行也能持续看到到达的类型。
                        // 稳定后收敛为 30s 一条——高频事件（如 hidSendReport）会刷屏
                        long nowMs = System.currentTimeMillis();
                        if (nowMs - lastTypeLogMs > 30_000) {
                            lastTypeLogMs = nowMs;
                            typeCount++;
                            log("事件通道收到 type=" + type + " body=" + body.length
                                    + "B（累计 " + typeCount + " 条）");
                        }
                        if ("hidSendReport".equals(type)) {
                            // 事件线程只解析 + 投递：真正的 socket 写发生在
                            // HuClient.sendTouch 的触摸线程里（见那边的注释），
                            // 所以这里不会阻塞后续 /command 的应答。
                            handleHid(m);
                        } else if ("changeModes".equals(type)) {
                            // params 是车机想要的资源分配。我们只支持「屏/音频归手机」，
                            // 但车机坚持请求时无条件顶回会形成 changeModes↔modesChanged 乒乓 ——
                            // 同一签名 2 秒内不重复回（正常路径不受影响，风暴被掐掉）。
                            final String sig = String.valueOf(m.get("params"));
                            final long cmNowMs = System.currentTimeMillis();
                            boolean dup = sig.equals(lastChangeModesSig)
                                    && (cmNowMs - lastChangeModesAtMs) < 2000;
                            if (dup) {
                                changeModesSuppressed++;
                            } else {
                                lastChangeModesSig = sig;
                                lastChangeModesAtMs = cmNowMs;
                                log("changeModes params=" + sig
                                        + (changeModesSuppressed > 0
                                                ? ("（其间忽略重复 " + changeModesSuppressed + " 条）") : ""));
                                changeModesSuppressed = 0;
                                // ⚠️ sendModesChanged() 要走控制通道（最长 8s 超时）。
                                //    在事件线程里同步做，会把后续触摸应答全堵住；挪到独立线程。
                                fireAndForget(new Runnable() {
                                    @Override public void run() {
                                        try {
                                            sendModesChanged();
                                        } catch (java.io.IOException e) {
                                            log("changeModes 回发失败: " + e);
                                        }
                                    }
                                });
                            }
                        } else if ("forceKeyFrame".equals(type)) {
                            // 车机解码器失步要关键帧：置回等待位，让下一个 IDR 先补
                            // OP_VIDEO_CONFIG 再推帧 —— 否则黑屏/花屏要等到下一个自然 IDR。
                            configDirty = true;
                            waitingForKeyFrame = true;
                            log("车机要求关键帧 → 等下一个 IDR（先补 OP_VIDEO_CONFIG）");
                            // 同时向上游催一个 IDR：只等自然 GOP 的话，黑屏恢复时延不可控
                            try {
                                listener.onForceKeyFrame();
                            } catch (Throwable tDiag) {
                                Diag.w("AirPlaySession.onForceKeyFrame", tDiag);
                            }
                        } else if ("requestUI".equals(type)) {
                            // requestUI = 车机请求显示投射界面。我们的资源归属恒为「归手机」，
                            // 按 changeModes 同款回应 modesChanged 重申（声明了 UI_CONTEXT 就要有实现）。
                            log("车机 requestUI → 重申资源归手机（modesChanged）");
                            fireAndForget(new Runnable() {
                                @Override public void run() {
                                    try {
                                        sendModesChanged();
                                    } catch (java.io.IOException e) {
                                        log("requestUI 回发失败: " + e);
                                    }
                                }
                            });
                        }
                    } else if (eventRxLogged < 8) {
                        eventRxLogged++;
                        log("事件通道 body 解析失败 body=" + body.length + "B err=" + err
                                + " head=" + headHex(body));
                    }
                }
            }
        } catch (Exception e) {
            if (running) log("事件通道异常: " + e);
        }
    }

    private void handleHid(Map<String, Object> m) {
        Object r = m.get("hidReport");
        if (!(r instanceof byte[])) return;
        byte[] report = (byte[]) r;

        // ⚠️ 车机可能声明**多个** HID 设备：触摸屏（eso_touchScreen）和按键设备
        //    （eso_playback）。报告里的 uuid 决定这是谁的报告 —— 必须按 uuid 分流，
        //    否则按键报告会被当成触摸（长度 1~2 字节，坐标全乱）。
        String uuid = asString(m.get("uuid"));
        AirPlayInfo.HidDevice keyDevice = null;
        AirPlayInfo.HidDevice touchDevice = null;
        if (info != null) {
            for (AirPlayInfo.HidDevice hd : info.hidDevices()) {
                if (hd.keyMap() != null) {
                    if (keyDevice == null) keyDevice = hd;
                } else if (touchDevice == null) {
                    touchDevice = hd;
                }
            }
        }
        // uuid 对不上就按「报告形状」兜底：
        // ⚠️ **绝不能把触摸报告当按键报告**（触摸 5+ 字节，按键 1~2 字节）——
        //    误判的话，点一下屏幕就会触发「下一曲」这种。
        AirPlayInfo.HidDevice dev = null;
        if (uuid != null && info != null) {
            for (AirPlayInfo.HidDevice hd : info.hidDevices()) {
                if (uuid.equals(hd.uuid)) { dev = hd; break; }
            }
        }
        if (dev == null) {
            dev = report.length <= 2 ? keyDevice : touchDevice;
        }

        // ── 按键报告 ──
        if (dev != null && dev.keyMap() != null) {
            HidKeyMap map = dev.keyMap();
            int[] pressed = map.pressedUsages(report);
            if (pressed.length == 0) return;      // 全部松开
            for (int usage : pressed) {
                log("★ 车机硬键 usage=0x" + Integer.toHexString(usage)
                        + "（" + keyName(usage) + "）");
                listener.onHardKey(usage);
            }
            return;
        }
        // 没有描述符可依（/info 还没来）→ 退到「字节即 Usage ID」的直读语义
        if (info == null && report.length <= 2) {
            int usage = HidInput.parseKey(report);
            if (usage > 0) {
                log("★ 车机硬键 usage=0x" + Integer.toHexString(usage)
                        + "（" + keyName(usage) + "，按直读语义）");
                listener.onHardKey(usage);
                return;
            }
        }

        // ── 触摸报告 ──
        // ⚠️ 逻辑上限与 Report ID 都来自 HID 描述符，不能写死。
        //    Report ID：声明了就必须剥掉报告首字节，否则坐标整体错一位。
        int maxX = HidInput.DEFAULT_LOGICAL_MAX;
        int maxY = HidInput.DEFAULT_LOGICAL_MAX;
        int reportId = 0;
        AirPlayInfo.HidDevice axisDev = null;
        // 优先用「报告所属设备」的描述符：多 HID 设备时不能拿第一台的轴上限套另一台。
        if (dev != null && dev.touchAxisMaxima() != null) {
            axisDev = dev;
        } else if (info != null) {
            for (AirPlayInfo.HidDevice hd : info.hidDevices()) {
                if (hd.touchAxisMaxima() != null) {
                    axisDev = hd;
                    break;
                }
            }
        }
        if (axisDev != null) {
            int[] ax = axisDev.touchAxisMaxima();
            maxX = ax[0];
            maxY = ax[1];
            reportId = axisDev.touchReportId();
        }
        HidInput.Touch t;
        try {
            t = HidInput.parseTouch(report, maxX, maxY, reportId);
        } catch (Throwable e) {
            log("车机触摸报告解析失败（" + report.length + "B）: " + e);
            return;
        }
        // 前后各打几条 + 之后每 100 条一条：既避免高频刷屏，又能在现场/回归时确认
        // 「触摸还在不在」。原来只打第一条，导致排查时完全看不到后续触摸
        //（锁屏后判断「触摸有没有效」就是这么被误导的）。
        long n = hidReceived++;
        if (n < 5 || n % 100 == 0) {
            log("★ 收到车机触摸 #" + (n + 1) + " report=" + report.length + "B → ("
                    + t.x + "," + t.y + ") down=" + t.down);
        }
        listener.onTouch(t.x, t.y, t.down);
    }

    /**
     * 把车机的 HID 设备清单打出来（一次性）。
     *
     * <p>触摸的坐标逻辑上限、按键的 Usage 数组**都从描述符里解析**，
     * 解析不出来就只能用默认值（32767 / 直读语义）→ 触摸偏移、按键认不出。
     * 所以这里把 uuid / 名字 / 描述符原文都打出来，方便对照车机型号把默认值钉死。
     */
    private void dumpHidDevices() {
        if (info == null) return;
        List<AirPlayInfo.HidDevice> devs = info.hidDevices();
        if (devs == null || devs.isEmpty()) {
            log("车机没有声明 HID 设备（没有触摸/按键）");
            return;
        }
        log("── 车机 HID 设备 " + devs.size() + " 个 ──");
        for (AirPlayInfo.HidDevice hd : devs) {
            int[] ax = hd.touchAxisMaxima();
            HidKeyMap km = hd.keyMap();
            int rid = hd.touchReportId();
            log("  • uuid=" + hd.uuid + " name=" + hd.name
                    + " 描述符 " + (hd.descriptor == null ? 0 : hd.descriptor.length) + "B"
                    + (ax != null ? " 触摸上限=" + ax[0] + "×" + ax[1] : " 无触摸轴")
                    + (rid > 0 ? " 触摸ReportID=" + rid : "")
                    + (km != null ? " 按键=" + (km.array ? "Array" : "位图")
                        + " 页=" + (km.page == 0x0C ? "Consumer" : "Telephony")
                        + " usage=" + km.usages.length + "项" : " 无按键表")
                    + " 描述符原文=" + hex(hd.descriptor, 96));
        }
    }

    /** HID usage 的可读名（日志用）。 */    private static String keyName(int usage) {
        switch (usage) {
            case 0xB0: return "播放";
            case 0xB1: return "暂停";
            case 0xB3: return "快进";
            case 0xB4: return "快退";
            case 0xB5: return "下一曲";
            case 0xB6: return "上一曲";
            case 0xB9: return "停止";
            case 0xBC: return "循环";
            case 0xE9: return "音量+";
            case 0xEA: return "音量-";
            case 0x20: return "接听/挂断";
            case 0x21: return "闪断";
            case 0x26: return "挂断";
            default: return "未知";
        }
    }

    // ================================================================== RTSP

    /** 读写一条控制消息（加锁版本见 {@link #requestRawLocked}）。 */
    private byte[] requestRaw(String method, String path, byte[] body) throws IOException {
        synchronized (controlLock) {
            return requestRawLocked(method, path, body);
        }
    }

    private byte[] requestRawLocked(String method, String path, byte[] body) throws IOException {
        int thisCseq = cseq.getAndIncrement();
        OutputStream out = control.getOutputStream();
        // ⚠️ 这条必须打在加密/写出之前：上次卡点（加密后写出前线程消失）就是靠它定位的。
        // /feedback 每 2s 一次，是稳定运行期的日志大头 → 按 30s 限流，且请求/响应成对留痕；
        // 其余方法（SETUP/TEARDOWN 等）低频且关键，始终全打。
        final boolean verbose;
        if ("/feedback".equals(path)) {
            long nowMs = System.currentTimeMillis();
            verbose = nowMs - lastFbLogMs > 30_000;
            if (verbose) {
                lastFbLogMs = nowMs;
            }
        } else {
            verbose = true;
        }
        if (verbose) {
            log("→ " + method + " " + path + " cseq=" + thisCseq
                    + " body=" + (body == null ? 0 : body.length) + "B"
                    + (paired ? "（整条加密）" : ""));
        }
        // 1) 先组出完整明文报文
        byte[] plainWire = RtspMessage.request(method, path, thisCseq, body);
        // 2) 配对后整条加密
        byte[] wire = plainWire;
        ControlCipher enc = controlEncryptor;
        if (paired && enc != null) {
            try {
                wire = enc.encrypt(plainWire);
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException("控制通道加密失败: " + e);
            }
        }
        controlWriteStartMs = System.currentTimeMillis();
        try {
            out.write(wire);
            out.flush();
        } finally {
            controlWriteStartMs = 0;
        }

        // 3) 响应：配对后同样是整条密文，边读边解出明文再解析
        RtspMessage.Response r = readControlResponseFor(thisCseq, method + " " + path);
        if (r == null) throw new IOException("无响应: " + method + " " + path);
        if (verbose) {
            log("← " + method + " " + path + " status=" + r.status + " cseq=" + r.cseq
                    + " body=" + r.body.length + "B");
        }
        if (!r.isSuccess() && r.status != 455) {
            // 非 2xx 要留痕（真实车机异常时是重要信号），但 DiPlay 侧有已知缺陷会
            // 对 /feedback 稳定回 500（BplistCodec.encodeUnsignedInt 对 bitLength 33~39
            // 的值算出负下标），每秒一条会刷屏 → 抽样：前 3 条 + 之后 60s 一条。
            long nowErr = System.currentTimeMillis();
            if (errLogged < 3 || nowErr - lastErrLogMs > 60_000) {
                errLogged++;
                lastErrLogMs = nowErr;
                log("RTSP " + r.status + " for " + method + " " + path
                        + "（第 " + errLogged + " 次）");
            }
            // 466 Key Management Error：车机拒绝我们的加密方式（et=1 None）。
            // 通常意味着它只接受 MFi-SAP(et=4)/FairPlay(et=3,5) —— 这两种的密钥
            // 派生在 MFi 协处理器 / Apple 授权模块里，纯软件无法实现。给一条一眼能懂的诊断。
            if (r.status == 466 && "/setup".equals(path)) {
                log("⚠ 车机回 466 Key Management Error（拒绝 et=1 加密）：它可能只接受"
                        + " MFi-SAP(et=4)/FairPlay(et=3,5)，两者需 MFi 协处理器/Apple 授权，纯软件无法实现。");
            }
        }
        // 响应体已在 readControlResponse 里随整条报文解密，这里直接用
        return r.body;
    }

    /** /feedback 日志的限流时刻（30s 一条）。 */
    private long lastFbLogMs;
    /** 非 2xx 响应的抽样计数与上次记录时刻。 */
    private int errLogged;
    private long lastErrLogMs;

    /**
     * 读一条**属于这次请求**的控制响应。
     *
     * <p>为什么要校验 CSeq：控制通道是一次超时就会错位的流。之前某个请求 8 秒超时后，
     * 车机迟到的响应还留在流里，下一个请求会把别人的响应当成自己的 —— 从此所有响应
     * 永久错位（SETUP 拿到 /info 的 body 之类），而且不会触发重连。
     *
     * <p>对端不带 CSeq（-1）时直接接受：有些实现的事件通道响应确实没有这一行，
     * 不能因为缺字段就判死。
     */
    private RtspMessage.Response readControlResponseFor(int expectedCseq, String what) throws IOException {
        for (int i = 0; i < 4; i++) {
            RtspMessage.Response r = readControlResponse();
            if (r == null) return null;
            if (r.cseq == expectedCseq) return r;
            // ⚠️ 不能放行 cseq<0：这条是**控制通道**，每条响应都该带 CSeq。
            //    放行等于把上一条请求的残留（半行 `CSeq: N` 会被解析成 status=5/cseq=-1）
            //    当成这次的响应交出去 —— 调用方拿到空 body，/feedback 还会把失败当成功。
            log("⚠ 丢弃错位响应（cseq=" + r.cseq + "，期望 " + expectedCseq + "）：" + what);
        }
        throw new IOException("控制通道响应持续错位（CSeq 连续不匹配）：" + what);
    }

    /**
     * 读一条控制响应。
     *
     * <p>未配对：直接从套接字读明文。已配对：先经 {@link #controlPlain} 解密流把密文
     * 解成明文再解析 —— 用<b>常驻</b>流对象，避免一次响应里多带的字节被丢掉。</p>
     */
    private RtspMessage.Response readControlResponse() throws IOException {
        if (paired && controlDecryptor != null) {
            if (controlPlain == null) {
                controlPlain = new DecryptedStream(control.getInputStream(), controlDecryptor);
            }
            return RtspMessage.readResponse(controlPlain);
        }
        return RtspMessage.readResponse(control.getInputStream());
    }

    /**
     * 「先解密、再当明文读」的输入流 —— 对齐 DiPlay 收包路径（先 decrypt 流、再 parse RTSP）。
     *
     * <p>{@link ControlCipher#decrypt} 内部会缓冲半帧，返回空表示「字节不够，继续读」。</p>
     *
     * <p>非静态内部类：便于直接调用外层 {@code log()}，保持协议层可在桌面自检（不依赖 android.util）。</p>
     */
    private final class DecryptedStream extends java.io.InputStream {
        private final java.io.InputStream in;
        private final ControlCipher cipher;
        private byte[] plain = new byte[0];
        private int pos;
        private boolean eof;
        private int frameLogged;

        DecryptedStream(java.io.InputStream in, ControlCipher cipher) {
            this.in = in;
            this.cipher = cipher;
        }

        private boolean fill() throws IOException {
            while (pos >= plain.length) {
                if (eof) return false;
                byte[] tmp = new byte[8192];
                int n = in.read(tmp);
                if (n < 0) {
                    eof = true;
                    return false;
                }
                byte[] dec;
                try {
                    dec = cipher.decrypt(java.util.Arrays.copyOf(tmp, n));
                } catch (Exception e) {
                    throw new IOException("控制通道解密失败: " + e);
                }
                // 帧级诊断：看清每个解密帧的明文边界，判断多余字节是「对端发的」还是「解密产出的」
                if (frameLogged < 6) {
                    frameLogged++;
                    log("事件解密帧#" + frameLogged + " raw=" + n + "B plain=" + dec.length
                            + "B head=" + headHex(dec)
                            + (dec.length > 16 ? " tail=" + tailHex(dec) : ""));
                }
                plain = dec;
                pos = 0;
                // dec 为空 = 半帧，继续读
            }
            return true;
        }

        @Override
        public int read() throws IOException {
            if (!fill()) return -1;
            return plain[pos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!fill()) return -1;
            int n = Math.min(len, plain.length - pos);
            System.arraycopy(plain, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    // ================================================================== 工具

    private Object pick(byte[] plist, String key) {
        Object o = Bplist.read(plist);
        if (o instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            return m.get(key);
        }
        return null;
    }

    private int findDataPort(byte[] plist, int streamType) throws IOException {
        Object o = Bplist.read(plist);
        if (!(o instanceof Map)) return 0;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) o;
        Object streams = m.get("streams");
        if (!(streams instanceof List)) return 0;
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) streams;
        for (Object s : list) {
            if (!(s instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> sm = (Map<String, Object>) s;
            if (asInt(sm.get("type"), -1) == streamType) {
                return asInt(sm.get("dataPort"), 0);
            }
        }
        return 0;
    }

    private static Map<String, Object> resource(int id) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("resourceID", (long) id);
        m.put("entity", (long) AirPlaySetup.ENTITY_CONTROLLER);
        m.put("permanentEntity", (long) AirPlaySetup.ENTITY_CONTROLLER);
        return m;
    }

    private static Map<String, Object> appState(int id, int entity) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("appStateID", (long) id);
        m.put("entity", (long) entity);
        if (id == AirPlaySetup.APP_STATE_SPEECH) m.put("speechMode", -1L);
        return m;
    }

    /** 便于测试：构造一个 modesChanged 的 bplist。 */
    public static byte[] modesChangedBody() {
        List<Object> resources = new ArrayList<Object>();
        resources.add(resource(AirPlaySetup.RESOURCE_MAIN_SCREEN));
        resources.add(resource(AirPlaySetup.RESOURCE_MAIN_AUDIO));
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("resources", resources);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("type", "modesChanged");
        body.put("params", params);
        return Bplist.write(body);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().toUpperCase(Locale.US);
    }

    private static String macFromHost(String h) {
        int hsh = Math.abs((h + h).hashCode());
        return String.format(Locale.US, "%02X:%02X:%02X:%02X:%02X:%02X",
                (hsh >> 24) & 0xFF, (hsh >> 16) & 0xFF, (hsh >> 8) & 0xFF,
                hsh & 0xFF, (hsh >> 4) & 0xFF, hsh & 0xFF);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(64);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                byte[] arr = b.toByteArray();
                int n = arr.length;
                if (n > 0 && arr[n - 1] == '\r') n--;
                return new String(arr, 0, n, "US-ASCII");
            }
            b.write(c);
            // ⚠️ 单行上限：密钥协商失败退回明文读密文、或对端只发字节不发换行时，
            //    没有这个上限缓冲只增不减直至 OOM（控制通道 RtspMessage.readLine 有同款）。
            if (b.size() > 64 * 1024) {
                throw new IOException("事件通道头行超过 64KB，流已错位");
            }
        }
        return b.size() == 0 ? null : new String(b.toByteArray(), "US-ASCII");
    }

    /** 取前若干字节的 hex，用于诊断解析失败的 body。 */
    private static String headHex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(16, b.length);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", b[i]));
        }
        return sb.toString();
    }

    /** 末尾若干字节的 hex，用于诊断解密帧尾部是否有多余字节。 */
    private static String tailHex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, b.length - 24);
        for (int i = start; i < b.length; i++) {
            sb.append(String.format("%02x", b[i]));
        }
        return sb.toString();
    }

    /** 全量 hex（限 96 字节防刷屏），用于诊断帧间残留字节。 */
    private static String fullHex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(96, b.length);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", b[i]));
        }
        return sb.toString() + " (len=" + b.length + ")";
    }

    private static byte[] readN(InputStream in, int len) throws IOException {
        byte[] out = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(out, off, len - off);
            if (n < 0) throw new IOException("截断");
            off += n;
        }
        return out;
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try {
                return Integer.parseInt(((String) o).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    /**
     * 取字符串值。
     *
     * <p>HID 事件的 {@code uuid} 字段两种车机给法不一样：字符串（{@code "0"}）或数字（{@code 0}），
     * 这里统一成字符串再比对。
     */
    private static String asString(Object o) {
        if (o == null) return null;
        if (o instanceof String) {
            String s = ((String) o).trim();
            return s.isEmpty() ? null : s;
        }
        if (o instanceof Number) return String.valueOf(o);
        return null;
    }

    private static long readLongBE(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
    }

    private static void putLongBE(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (8 * (7 - i)));
    }

    private synchronized void setState(State s) {
        if (state == s) return;
        // CLOSED 是终态：会话线程被 socket close 唤醒后走 catch 会把状态写成 ERROR，
        // 于是「已关闭」被复活成「错误」，依赖 state 做判断的地方全都错位。
        if (state == State.CLOSED) return;
        state = s;
        listener.onState(s);
    }

    private void log(String msg) {
        lastProgressMs = System.currentTimeMillis();
        listener.onLog(msg);
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Exception tDiag) {
                Diag.w("AirPlaySession.closeQuietly", tDiag);
            }
        }
    }
}
