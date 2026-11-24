package com.carbridge.app;
import com.carbridge.carplay.proto.HuProto;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;

import com.carbridge.carplay.bridge.CarPlayBridge;
import com.carbridge.carplay.core.AirPlaySession;
import com.carbridge.carplay.core.DisplayGeometry;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import com.carbridge.carplay.util.Diag;

/**
 * CarLife 车机侧（HU）会话：冒充车机接住 OPPO Car+。
 */
public final class HuClient {

    public interface Sink {
        void onState(State state, String detail);

        void onLog(String line);

        /** 收到一帧 H.264（Annex-B）。 */
        void onVideo(byte[] data, int len, int ptsMs);

        void onPhoneInfo(String info);

        void onMetrics(int frames, long bytes, long firstFrameDelayMs);

        /**
         * 编码器目标/实际参数。
         *
         * @param targetW/targetH/targetFps 向 Car+ 请求的参数
         * @param actualW/actualH/actualFps Car+ 实际应答的参数（0 = 还没应答）
         */
        void onEncoderInfo(int targetW, int targetH, int targetFps,
                           int actualW, int actualH, int actualFps);

        /**
         * 手机侧进入/退出通话（CarLife 的 {@code MD_MODULE_STATUS} 里 PHONE 模块活跃）。
         *
         * <p>用来给车机上报 CarPlay 的通话状态（iAP2 {@code 0x4155 CallStateUpdate}）——
         * 车机的 CarPlay app 收到后会 {@code sendMessageToCarlan} 把状态转进车机的
         * CarLife 层，车机自己的 HMI 和音频路由跟着这个走。
         */
        void onPhoneCallState(boolean active);

        /** 进入某一步（该步之前视为已完成）。 */
        void onStep(Step step);

        /** 触发一次错误（步骤失败）。 */
        void onStepFailed(Step step, String reason);
    }

    public enum State {IDLE, WAITING_PORT, CONNECTING, HANDSHAKING, STREAMING, ERROR, CLOSED}

    /** CarLife 连接的细分步骤，UI 用它渲染进度。 */
    public enum Step {
        WAITING_CHANNEL("等待 Car+ 打开通道"),
        CONNECT_CHANNELS("连接 6 条通道"),
        VERSION("版本协商"),
        DEVICE_INFO("设备信息交换"),
        FEATURE_CONFIG("特征配置"),
        AUTH("鉴权"),
        ENCODER("编码器协商"),
        STREAMING("开始推流");

        public final String label;

        Step(String label) {
            this.label = label;
        }

        /** 下一步；已是最后返回 null。 */
        public Step next() {
            Step[] all = values();
            return ordinal() + 1 < all.length ? all[ordinal() + 1] : null;
        }
    }

    private static final String TAG = "carbridge/HuClient";
    private static final int HU_SLOTS = 6;

    private final Context ctx;
    private final Sink sink;

    private volatile boolean running;
    /** 会话代际：每次 start()/stop() 自增，旧线程看到代际变了就立刻收手。 */
    private volatile int sessionGen;
    /** 当前会话线程（stop 时 interrupt + 短暂 join）。 */
    private volatile Thread sessionThread;
    private volatile State state = State.IDLE;
    private volatile int width = 1920, height = 720, fps = 30;
    /**
     * Car+ 实际能给到的帧率（上一次协商落定的值）。
     *
     * <p>它同时是「学会的值」：重连时直接从这个值起步，不再每次都从 60 重新谈一遍。
     * CarLife 画面主体是车机 UI（导航/音乐），30 帧完全够；一上来报 60 只会在编码器
     * 协商里来回讨价还价（实测目标 60 → 应答 20 → 实际 12），既慢又容易触发重连。
     */
    private volatile int learnedFps;
    /** 首次协商的默认目标帧率。 */
    private static final int DEFAULT_TARGET_FPS = 60;
    /** 手机**实际**的编码尺寸/帧率（来自 MD_VIDEO_ENCODER_INIT）；0 = 还没拿到。 */
    private volatile int encW, encH, encFps;
    /** 自动跟随车机 /info 的画布和帧率（UI 可关；关掉就完全用手动参数）。 */
    private volatile boolean autoFit = true;
    /** 最近一次车机 /info 声明的画布 + maxFPS；0 = 还没收到。 */
    private volatile int carW, carH, carFps;
    private volatile int connectType = 2;
    private volatile int portWaitMs = 25000;
    private volatile boolean autoLaunch = true;
        /**
     * 两次拉起 Car+ 之间的最小间隔。
     */
    private static final long LAUNCH_COOLDOWN_MS = 3000;
    /** 上次拉起 Car+ 的时刻（冷却用）。 */
    private volatile long lastLaunchAtMs;
    /** 连续等不到端口的轮数（≥3 轮判定服务卡死 → force-stop 重启）。 */
    private int portFails;

    /** CarPlay 发送端桥；为 null 时不推给车机（只做本机预览）。 */
    private volatile CarPlayBridge carPlayBridge;

    /** 当前连接步骤（UI 用）。 */
    private volatile Step step = Step.WAITING_CHANNEL;

    /** 六条通道的 socket。用 AtomicReferenceArray 而不是裸数组：连接/拆链线程写、
     *  心跳与触摸线程读，裸数组没有 happens-before，会读到 null 或已关闭的旧 socket。 */
    private final java.util.concurrent.atomic.AtomicReferenceArray<Socket> socks =
            new java.util.concurrent.atomic.AtomicReferenceArray<>(HU_SLOTS);
    private volatile int[] activePorts = HuProto.WIFI_PORTS;
    private volatile boolean videoReading;
    /** 音乐音频最后一次到达的时刻：用于判断「音乐是不是正闲着」。 */
    private volatile long lastMediaAudioAtMs;
    /** 「语音通道有数据」只打一次（两条通道共用）。 */
    private volatile int voiceLogged;
    /** 每条语音通道是否已经在读（9241/9242 各一个）。 */
    private final boolean[] voiceReading = new boolean[2];
    /** 音乐在播时被丢弃的导航语音包数（诊断用）。 */
    private volatile long ttsDropped;

    /** 收到的空帧（len=0）计数：CarLife 用它们当心跳，不能当故障。 */
    private volatile long emptyFrames;
    /** 音频读取线程在跑（9240 通道）。**不读会把手机侧的媒体发送堵死**。 */
    private volatile boolean audioReading;
    /**
     * 媒体读取线程的「会话代际」：每次 {@link #closeAll()} 自增。
     *
     * <p>旧线程阻塞在读上、要等套接字被关才退出；这期间若新会话已起线程，旧线程退出时
     * 若无条件把 {@code audioReading} 清回 false，就会让新旧两条线程同时抢读同一个 9240。
     * 用代际号比对即可保证「只有本代线程有权复位标志」。
     */
    private volatile int mediaGen;
    private volatile int hbGen;
        /**
     * 自「拉起 Car+」起要等多久才真正去连。**不是固定值，而是按观测自适应。
     */
    private volatile int settleMs = SETTLE_MIN_MS;

    /** 连接前等待的最短值。
     *
     * <p>4500ms 的依据：实测 6 秒 settle 时 20 次尝试**没有一次丢失监听端口**；
     * 而降到 1500ms 后出现两次端口全灭（其中一次 168 秒都没回来）。
     * 现场复现：1500ms 时首连 1 秒后 ocar 端口全灭、要重试 5 次才稳定（约 15 秒）。
     * 4500ms 只多等 3 秒，却省掉整轮重试，净收益为正。
     */
    private static final int SETTLE_MIN_MS = 4500;
    /** 每次观测到「开始推流却没出帧」后放宽的步长。 */
    private static final int SETTLE_STEP_MS = 1500;
    /** 放宽上限 —— 与历史上的 6 秒持平，保证最坏情况不劣于旧行为。 */
    private static final int SETTLE_MAX_MS = 6000;
    /**
     * 冷启动首轮的等待：ocar 首次 CLEAR_TASK 后要 8-10 秒才把 6 个端口开齐。
     *
     * <p>2026-11-23 实测复核：降到 5500ms 连跑 3 轮，**三次都「等不到端口」**，
     * 说明 8 秒不是拍脑袋，是 Car+ 冷启动的固有成本。想再压低只能让它别每次冷启动
     * （后台预热），而不是继续砍这个值。
     */
    private static final int COLD_SETTLE_MS = 8000;
    /** 本次 HuClient 实例的第一轮（冷启动）。 */
    private volatile boolean coldStart = true;

        /**
     * ENC_INIT 应答之后、发 {@code VIDEO_ENC
     * ODER_START(0x18009)} 之前必须等的时长。
     */
    private static final int CAST_READY_DELAY_MS = 600;

    /**
     * 首次观测窗口：发出 HU_PROTOCOL_VERSION 后先等多久看对端回不回。
     * 实测正常应答 100~110ms 到达，2 秒已是近 20 倍余量。
     */
    private static final int INITIAL_OBSERVE_MS = 2000;

    /**
     * 后续每次重新观测的窗口。
     *
     * <p>给得比首次宽，因为它的用途**不是「判负倒计时」**，而是「再给对端一次机会」——
     * 它回一帧就立刻推进。真正决定要不要重连的是 {@link Fault} 与端口观测。
     */
    private static final int OBSERVE_MS = 5000;

        /**
     * 最多重新观测几次。这是**安全上限**，不是判负依据；每次都会重新读端口、 重新定性故障，所以不会把「慢」误判成「死」。
     */
    private static final int MAX_OBSERVATIONS = 2;

    /** 对端已上报模块状态（**有进展**）时的等待上限：它在编码，只是视频还没到，多等。 */
    private static final long PROGRESS_WAIT_MS = 20_000;

    /** 对端一条进展都没有时的等待上限：它没开始，再等也不会有。 */
    private static final long NO_PROGRESS_WAIT_MS = 8_000;

    /**
     * 推流阶段的观察窗。
     *
     * <p>用 10s 是协议自己的判据（{@code no video data for 10s → heartbeat timeout}）。
     * **只在链路有动静时才用这个长窗**：一旦某窗一条帧都没有，立刻切回短窗进入诊断，
     * 所以它不会变成新的「死等」。
     */
    private static final int STREAM_OBSERVE_MS = 10_000;
    /** 视频通道多久没任何数据（含空帧）就告警。只告警，不拆会话（CarLife 变化驱动）。 */
    private static final long VIDEO_QUIET_WARN_MS = 15_000L;
    private volatile long startedAt;
    private volatile long firstFrameAt;
    /** 进入「编码器协商」步的时刻（协议看门狗：10s 无首帧则判超时重建会话）。 */
    private volatile long encoderAtMs;
    /**
     * 最近一次发出 {@code HU_VIDEO_ENCODER_INIT} 的时刻。
     *
     * <p>用来量对端 {@code MD_VIDEO_ENCODER_INIT} 的**应答延迟**，仅作**诊断信息**记进日志。
     * 它曾经是一道判负门（{@code ≥215ms 才放行}），已删除 —— 延迟只是代理指标，不是对端
     * 给出的事件，拿它判成败等于用定时撞库，永远优化不了延时。见 {@link Fault}。
     */
    private volatile long encoderInitSentAtMs;
    /**
     * 已发出 {@code HU_VIDEO_ENCODER_FRAME_RATE_CHANGE}、正在等手机回 {@code 0x1000D}。
     *
     * <p>防止归一化过程被自己的超时逻辑打断。
     */
    private volatile boolean frameRateChanging;
    /**
     * 运行中动态改帧率（车机 /info 在推流后才到）。
     *
     * <p>这时不能重发 ENC_INIT 重建编码器，只发 0x1800C 让 Car+ 的
     * {@code Recorder.changeFrameRate()} 生效；收到 0x1000D 后继续用原会话。
     */
    private volatile boolean liveFrameRateChange;
    private volatile boolean liveFrameRateChangeDone;
    /**
     * 本会话已完成一次帧率归一化（发过 {@code 0x1800C} 并收到对端回的 {@code 0x1000D}）。
     *
     * <p>作用是**防止重复归一化**：再问一次，对端只会回同一个值，没有意义。
     */
    private volatile boolean encoderNormalized;
    /**
     * 会话是怎么**断的 / 卡住的**。这是恢复决策的输入。
     *
     * <p>以前所有失败都被归成一句「超时 → 重置连接」，那就是拿重连去撞库：对端明明活着
     * 只是慢，也被自己掐掉，于是永远优化不了延时。这四种处境的正确动作恰好相反：
     */
    public enum Fault {
        /** 正常推进中。 */
        NONE,
        /** 对端主动 FIN（干净关闭）—— 它认为会话已结束，多半是自己结束了投屏。 */
        EOF,
        /**
         * 对端 RST。反编译 {@code ConnectManager$AcceptThread} 见到机制：每通道单槽位，
         * 对已占用通道再连一条会触发 {@code resetConnectManager()} 把**所有** socket 关掉。
         * 所以 RST 后**立刻重连必然再撞一次**，正确动作是等槽位释放，而不是马上重试。
         */
        RST,
        /**
         * TCP 通、但对端不回协议帧 —— 它的读线程还没挂上，属于「对端慢」。
         * **绝不能因此重置连接**，重置等于把它正在初始化的过程打断。
         */
        SILENT,
        /** 协议推进过、但之后没有下一段进展（例：已推流却一帧不来）。 */
        STALL,
    }

    private volatile Fault fault = Fault.NONE;
    /** 卡住时停在哪一步 —— 决定「该做哪个动作」。 */
    private volatile Step faultAtStep = Step.WAITING_CHANNEL;
    /**
     * 推流后收到过 {@code MD_MODULE_STATUS 0x10026} 没有。
     *
     * <p>这是**编码器真的在跑**的外部证据（手机自己报上来的事件，不是我们按时间猜的）。
     * 收到过 = 对端确实在编码，只是视频还没到，应当继续等；没收到 = 对端压根没开始，
     * 再等下去也不会有。看门狗据此区分「有进展地等」和「真卡死」。
     */
    private volatile boolean moduleSeen;
    /**
     * 手机侧是否正在通话（CarLife 的 {@code MD_MODULE_STATUS} 里 PHONE 模块活跃）。
     *
     * <p>CarLife 协议没有独立的通话音频通道 —— 通话声混在 MEDIA 通道里。
     * 只有靠这个外部信号才能把 MEDIA 的那段判成「通话声」，
     * 转进 CarPlay 的 {@code audioType=telephony} 流（真 iPhone 就是这么分的）。
     */
    private volatile boolean phoneModuleActive;    /**
     * **任意通道**最近一次收到帧的时刻。
     *
     * <p>这是「链路还在动」的唯一客观信号，与计时器无关：CMD 上的心跳应答、视频通道上的
     * 画面帧，任何一个都算。看门狗与观察窗都靠它判断停滞，而不是靠某个固定超时到没到点。
     */
    private volatile long lastRxAtMs;
    /**
     * 本轮是否已经发出 {@code 0x18009（HU_VIDEO_ENCODER_START）}。
     *
     * <p>用途只有一个：把「**真的走到开始推流、却一帧没出**」和「还没走到那一步就失败了」
     * 区分开。前者说明对端的编码器是上一轮的缓存（虚拟显示没重建），才值得放宽 settle；
     * 后者与 settle 无关，不该拿它去调等待时间。
     */
    private volatile boolean streamStarted;
    /** 是否已经安排过「等投屏就绪再发 START」，避免重复排线程。 */
    private volatile boolean startScheduled;
        /**
     * **连上了 6 条通道、却一帧视频都没来** 的连续轮数（≥2 轮 → 强制重启 Car+）。
     */
    private volatile int noFrameFails;
        /**
     * 上次强拉（CLEAR_TASK 重置 Car+ 状态机）的时刻。
     */
    private long lastForcedAtMs;
    /**
     * 连续「判监听器被拆掉 → 重置」却没有换回任何端口的次数。
     *
     * <p>用途是**防止把对端越修越坏**：实测 run4 里连续 6 次重置、168 秒，
     * 监听器一次都没回来 —— 反复 CLEAR_TASK 很可能正是在不停打断 Car+ 服务自己的重建。
     * 所以连续 3 次无效后就改为「只等」，不再动手。
     */
    private int portResetStreak;
    /** 强拉冷却窗口。窗口内不重复 CLEAR_TASK，防止自己变成自噬循环。 */
    private static final long FORCE_COOLDOWN_MS = 20_000;
    private volatile int frameCount;
    private volatile long byteCount;

    /**
     * 现在能不能强拉 Car+。冷却内不给，冷却外永远给 —— 不设生命周期配额。
     */
    private boolean forceAllowed() {
        return Math.max(0L, System.currentTimeMillis() - lastForcedAtMs) >= FORCE_COOLDOWN_MS;
    }

    /**
     * 强制重置 Car+ 的**状态机**（CLEAR_TASK 重开）。
     *
     * <p>必须用它而不是普通 {@code am start}：它不吃冷却，而且 CLEAR_TASK 会把**残留监听器
     * 也一起退掉**（实测 17:54:42 之后端口立刻消失），这样 {@link #waitForPorts} 等到的才是
     * 干净的新服务，不会 settle 一过就连到还没初始化完的旧端口上。
     */
    private void forceRestartCarlife(String why) {
        lastForcedAtMs = System.currentTimeMillis();
        noFrameFails = 0;
        log(why);
        forceStopCarlifeChannel();   // 内部已 CLEAR_TASK 重开，调用方本轮不用再拉起
        sleep(1500);
    }

    /**
     * 冷却窗口内只允许强拉一次；窗口内再触发就**只等不动手**。
     *
     * @return true = 真的重置了；false = 冷却中，本轮什么都没做
     */
    private boolean tryForceRestart(String why) {
        if (!forceAllowed()) {
            log("强拉冷却中（" + FORCE_COOLDOWN_MS + "ms 窗口内已用过一次），"
                    + "本轮只等、不再 CLEAR_TASK —— 反复重置会打断 Car+ 自己的重建");
            return false;
        }
        forceRestartCarlife(why);
        return true;
    }

    public HuClient(Context ctx, Sink sink) {
        this.ctx = ctx.getApplicationContext();
        this.sink = sink;
    }

    // ------------------------------------------------------------- 配置

    public void setEncoderSize(int w, int h, int f) {
        width = w;
        height = h;
        fps = f;
        pushStreamSize();
        notifyEncoderInfo();
    }

    /** 自动跟随车机画布（默认开）。关闭后完全使用手动宽高/fps。 */
    public void setAutoFit(boolean enabled) {
        autoFit = enabled;
        log("画质自动适配：" + (enabled ? "开启（跟随车机 /info）" : "关闭（使用手动参数）"));
        notifyEncoderInfo();
    }

        /**
     * 车机 /info 到达：把车机声明的画布 + maxFPS 作为 Car+ 的目标。
     */
    public void onCarDisplay(int w, int h, int maxFps) {
        carW = w;
        carH = h;
        carFps = maxFps;
        int[] fit = DisplayGeometry.fit(w, h);
        if (fit == null) {
            log("车机画布非法：" + w + "x" + h + "，忽略");
            return;
        }
        int wanted = learnedFps > 0 ? Math.min(learnedFps, maxFps)
                : Math.min(maxFps, DEFAULT_TARGET_FPS);
        int targetFps = DisplayGeometry.clampFps(wanted);
        if (learnedFps > 0) {
            log("沿用上次协商到的帧率上限 @" + learnedFps + "（避免每次重连重新讨价还价）");
        }
        log("车机画布 " + w + "x" + h + "@" + maxFps
                + " → 目标 " + fit[0] + "x" + fit[1] + "@" + targetFps
                + "（自动适配" + (autoFit ? "开" : "关") + "）");
        if (!autoFit) {
            notifyEncoderInfo();
            return;
        }

        boolean changed = false;
        if (width != fit[0] || height != fit[1]) {
            if (!streamStarted && firstFrameAt == 0 && encoderInitSentAtMs == 0) {
                width = fit[0];
                height = fit[1];
                changed = true;
                log("采用车机画布尺寸 " + width + "x" + height + "（本次编码器初始化）");
            } else {
                log("车机画布与当前编码不同（" + width + "x" + height + " → "
                        + fit[0] + "x" + fit[1] + "）：为不打断会话，分辨率下一次连接生效");
            }
        }

        if (fps != targetFps) {
            if (streamStarted || firstFrameAt > 0) {
                requestLiveFrameRate(targetFps);
            } else {
                fps = targetFps;
                changed = true;
                log("采用车机画布帧率 @" + fps + "（本次编码器初始化）");
            }
        }
        if (changed) {
            notifyEncoderInfo();
        }
    }

    /**
     * 运行中改帧率：发 0x1800C，不重建编码器。
     *
     * <p>Car+ 的 {@code Recorder.changeFrameRate()} 会写 {@code mDestFrameRate}，
     * 这是它内部唯一的运行中帧率入口；收到 0x1000D 后继续原视频流。
     */
    private void requestLiveFrameRate(int newFps) {
        if (newFps <= 0 || newFps == fps) {
            return;
        }
        int old = fps;
        fps = newFps;
        if (!streamStarted || socks.get(0) == null) {
            log("帧率目标从 @" + old + " 改为 @" + newFps + "（等待编码器初始化）");
            notifyEncoderInfo();
            return;
        }
        liveFrameRateChange = true;
        liveFrameRateChangeDone = false;
        frameRateChanging = true;
        encoderNormalized = false;
        boolean sent = send(0, HuProto.HU_VIDEO_ENCODER_FRAME_RATE_CHANGE,
                HuProto.videoFrameRate(newFps));
        if (!sent) {
            liveFrameRateChange = false;
            frameRateChanging = false;
            fps = old;   // 发送失败不能把 fps 留在新值上：触摸换算和 UI 都读它
        }
        log((sent ? "→ 动态帧率 " : "⚠ 动态帧率发送失败 ")
                + "@" + old + " → @" + newFps + "（0x1800C，不重连）");
        notifyEncoderInfo();
    }

        /**
     * 把「当前实际的投屏分辨率」告诉 CarPlay 桥。
     */
    private void pushStreamSize() {
        CarPlayBridge b = carPlayBridge;
        if (b == null) return;
        int w = encW > 0 ? encW : width;
        int h = encH > 0 ? encH : height;
        if (w > 0 && h > 0) b.setStreamSize(w, h);
    }

    /** 把目标/实际编码参数送到 UI（不参与协议判定）。 */
    private void notifyEncoderInfo() {
        try {
            sink.onEncoderInfo(width, height, fps, encW, encH, encFps);
        } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("HuClient", t);
        }
    }

    public void setConnectType(int t) {
        connectType = t;
    }

    public void setAutoLaunch(boolean b) {
        autoLaunch = b;
    }

    /** 接上 CarPlay 发送端桥；为 null 表示只做本机预览。 */
    public void setCarPlayBridge(CarPlayBridge bridge) {
        carPlayBridge = bridge;
        // 桥是后创建的 → 这里补一次尺寸（否则触摸换算还是默认 1920×720）
        pushStreamSize();
    }

    public CarPlayBridge carPlayBridge() {
        return carPlayBridge;
    }

    /** 当前向手机声明的编码分辨率（CarPlay 触摸坐标要按它换算）。 */
    public int streamWidth() {
        return width;
    }

    public int streamHeight() {
        return height;
    }

    public int streamFps() {
        return fps;
    }

    /** Car+ 实际应答的编码宽度；0 = 还没应答。 */
    public int encodedWidth() {
        return encW;
    }

    public int encodedHeight() {
        return encH;
    }

    public int encodedFps() {
        return encFps;
    }

    public boolean autoFit() {
        return autoFit;
    }

    public void setPortWaitMs(int ms) {
        portWaitMs = ms;
    }

    public boolean isRunning() {
        return running;
    }

    public State state() {
        return state;
    }

    public int frameCount() {
        return frameCount;
    }

    public long byteCount() {
        return byteCount;
    }

    // ------------------------------------------------------------- 生命周期

        /**
     * 进程内唯一在跑的客户端。
     */
    private static final java.util.concurrent.atomic.AtomicReference<HuClient> ACTIVE =
            new java.util.concurrent.atomic.AtomicReference<HuClient>();

    public synchronized void start() {
        if (running) {
            return;
        }
        HuClient prev = ACTIVE.getAndSet(this);
        if (prev != null && prev != this) {
            log("检测到另一个投屏客户端在运行，先停掉旧的（避免两个客户端抢 Car+）");
            prev.stop();
        }
        running = true;
        final int gen = ++sessionGen;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                session(gen);
            }
        }, "hu-session");
        sessionThread = t;
        t.start();
    }

    public synchronized void stop() {
        ACTIVE.compareAndSet(this, null);
        running = false;
        // ⚠️ 让会话线程立刻换代退出：以前只置 running=false，旧线程还在
        // waitForPorts 的盲等里睡着（最长 8 秒），醒来后照样把 6 条 socket 连上；
        // 这批 socket 建在 closeAll 之后、没人关，一直占着 Car+ 的通道槽位，
        // 用户「断开 → 立刻重连」就会撞 resetConnectManager → 连环断流。
        sessionGen++;
        Thread t = sessionThread;
        sessionThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(300);          // 给它一点时间退出；超时也不阻塞 UI
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        videoReading = false;
        audioReading = false;
        hbGen++;
        stopTouchThread();
        closeAll();
        setState(State.CLOSED, "已断开");
    }

    /** 关掉全部通道（每次尝试之间都会调用）。 */
    private void closeAll() {
        videoReading = false;
        audioReading = false;
        // ⚠️ 语音通道的「有人在读」闩锁必须跟着换代一起清：它的 finally 只在
        // gen == mediaGen 时才复位，而 closeAll 正好把 mediaGen++ 了 —— 不清这两格，
        // 第一次重连之后 9241/9242 就再也没人读（缓冲填满 → 反压 Car+ 写线程），
        // 而日志里一点异常都没有。
        voiceReading[0] = false;
        voiceReading[1] = false;
        mediaGen++;                    // 换代：旧的媒体读取线程据此自行退出、且不再动标志
        hbGen++;                       // 让这一轮的心跳线程自己退出
        startScheduled = false;        // 取消还没发出的 START
        for (int i = 0; i < HU_SLOTS; i++) {
            closeQuietly(socks.get(i));
            socks.set(i, null);
        }
    }

    private void session(int gen) {
        // 每个周期只拉起 Car+ 一次。
        //
        // 以前的条件是 `firstFrameAt == 0`（=「本周期还没出过画面」），结果每一轮失败都会
        // 重新 am start 一次 Car+，而 Car+ 被拉起后要重启 CarLife 服务、又要 ~4.5s 才就绪，
        // 于是每轮都得再付一遍 settle —— 自己把自己拖成 100 多秒。
        //
        // 拉起一次之后的重试直接连：手机若回 @20，那是组件进程里 mInited 为 true 的
        // 短路回显，走 0x1800C 帧率归一化纠正即可，不需要靠重启进程重置。
        boolean needLaunch = true;
        // ⚠️ 不能写死 attempt<=5：锁屏/网络抖动一次就耗尽配额，然后永久放弃
        // （现象就是「锁屏后失去连接，再也不会自己回来」）。running 期间一直重试。
        for (int attempt = 1; running && gen == sessionGen; attempt++) {
            // 每次尝试单独计时：否则重连几轮之后，「首帧延迟」会把重连耗时也算进去
            // （现场看到 2-3s 变成 10s+，其实真实首帧仍是几秒）。
            startedAt = System.currentTimeMillis();
            lastVideoRxAtMs = startedAt;
            if (attempt > 1) coldStart = false;
            log("── 第 " + attempt + " 次尝试 ──");
            boolean prevStreamed = firstFrameAt > 0;
            if (prevStreamed) {
                // 这个周期曾经出过画面：失败计数清零，别拿上一轮的失败去误杀当前状态。
                // （强拉不再需要在这里恢复额度 —— 它现在按冷却窗口走，见 lastForcedAtMs。）
                noFrameFails = 0;
            }
            firstFrameAt = 0;
            encoderAtMs = 0;           // 看门狗清零，别拿上一轮的时刻误判超时
            encoderInitSentAtMs = 0;   // ENC_INIT 归一化状态同样每轮清零
            frameRateChanging = false;
            liveFrameRateChange = false;
            liveFrameRateChangeDone = false;
            encoderNormalized = false;
            fault = Fault.NONE;        // 每轮重新观测，上一轮的故障定性不带过来
            faultAtStep = Step.WAITING_CHANNEL;
            moduleSeen = false;
            streamStarted = false;     // 本轮还没走到「开始推流」，别用上一轮的去判 settle
            frameCount = 0;
            byteCount = 0;
            encW = 0;
            encH = 0;
            encFps = 0;
            // ── 升级：连上却出不来画面，重试到第 2 轮就别再耗了 ──
            if (noFrameFails >= 2) {
                if (forceAllowed()) {
                    forceRestartCarlife("连续 2 轮连上却一帧没来"
                            + "（对端在听却不回协议 / 视频被 RST 任一），"
                            + "判 Car+ 状态机卡住，强制重置状态机");
                    needLaunch = false;
                } else {
                    // 冷却内不重复拉，但**不放弃**：额度是按时间自动回来的，
                    // 不像以前那样「3 次用完就永久退回普通重连」然后一路空转。
                    noFrameFails = 0;
                    log("强拉冷却中（" + FORCE_COOLDOWN_MS + "ms 窗口内已用过一次），"
                            + "本轮先普通重连");
                }
            }
            // 每一轮都拉起 Car+（只在正在推流时才不去打扰它）。
            //
            // 实测铁证（21:49 那段，Car+ 日志与本类日志逐秒对齐）：
            //   am start Car+ → Car+ 打 `Start carlife service`（新 pid）→ 我们拿到 @30
            //   被冷却跳过   → Car+ 复用旧服务（显示已销毁）          → 我们拿到 @20
            // @30/@20 与「有没有 am start」是 5/5 完美对应，而 @20 这种状态实测 38 次 0 成功。
            // 也就是说：**不拉起的那一轮必输**。所以这里绝不能用「端口在听就不拉起」去抑制——
            // 端口开着恰恰说明 Car+ 还留着上一轮的服务，那种服务给不出 @30。
            if (autoLaunch && needLaunch) {
                // 每个周期开头用 **CLEAR_TASK** 重开，而不是普通 am start。
                //
                // 6 次冷启动的对照很干脆：Car+ 状态干净时**首轮即成**（9 秒 / 1 轮，
                // ENC_INIT 延迟 261ms、242ms）；Car+ 带着残留投屏状态时首轮必败
                // （延迟 137~190ms = 虚拟显示已死，编码器建在死 surface 上），
                // 于是空转十几轮，最后靠 escalation 才把它清干净 —— 白花一百多秒。
                //
                // 既然「先清干净」能一轮过，就把它放到开头，省掉中间那十几个来回。
                launchCarlifeChannel(true);
                needLaunch = false;
            }
            if (!openAllChannels(gen)) {
                portFails++;
                if (portFails >= 3) {
                    // 连续 3 轮等不到端口 = Car+ 的 CarLife 服务卡死了。
                    //
                    // ⚠️ 但**必须走冷却**：实测在「端口一直起不来」的环境里，每 3 轮
                    // CLEAR_TASK 一次、连做十几次，会把 Car+ 的状态机彻底打坏
                    // （之后 `castCallback=null`、`START_CONNECT → Time out`、
                    //  端口再也不监听，只能重启手机才能恢复）。
                    boolean restarted = tryForceRestart(
                            "连续 " + portFails + " 轮等不到端口，强杀 Car+ 的状态机再重新拉起");
                    if (restarted) {
                        needLaunch = false;   // 内部已 CLEAR_TASK 重开，下轮不用再拉起
                    }
                    portFails = 0;
                }
                if (attempt >= 6) {
                    // ⚠️ 这里**不要**报 ERROR：重连循环还在跑（`attempt` 不设上限），
                    // 报完 ERROR 下一轮又会切回「等待 Car+ 打开通道」→ UI 在两个状态间
                    // 来回跳，看起来像「一会儿正常一会儿出错」。改成一条提示性的日志。
                    log("等不到 Car+ 的 CarLife 端口，已重试 " + attempt
                            + " 轮（可在 Car+ 里手动进一次车机界面再点重试）");
                }
                closeAll();
                sleep(2000);
                continue;
            }
            portFails = 0;              // 连上了就清零，别把偶发失败累加成误杀
            try {
                send(0, HuProto.HU_PROTOCOL_VERSION, HuProto.protocolVersion());
                log("→ HU_PROTOCOL_VERSION {major=4, minor=0}（6 条通道已就绪）");
                setState(State.HANDSHAKING, "握手协商中");
                startHeartbeat();
                // ── 握手推进与推流读，走**同一条有兜底的观察窗路径** ──
                //
                // 这里曾经是两段：握手阶段 ctrlLoop(2s) 有观测兜底，推流阶段却是
                // ctrlLoop(0, …) 的**无超时死读**。于是对端只要在「版本匹配应答之后、
                // 首帧之前」的任何一步停下，线程就挂在同步读上 —— 实测的 245.8s /
                // 155s / 87s 静默空窗全是这么来的：旧的快速判负已经删掉，新兜底却
                // 只装在了前半段，后半段完全没人管。
                //
                // 现在统一：每个窗结束就重新读状态、重新定性。**有帧就继续，没帧才谈动作**，
                // 动作由诊断证据决定（EOF / RST / SILENT 各不相同），不由计时器决定。
                int stalls = 0;
                int windowMs = INITIAL_OBSERVE_MS;      // 首窗短：先看对端答不答得上
                while (running) {
                    long before = lastRxAtMs;
                    ctrlLoop(windowMs);
                    // ⚠️ 视频要单独判活：CMD 心跳会一直刷新 lastRxAtMs，
                    //    视频停摆（连 len==0 空帧都不再出现）不会体现在全局活性上，
                    //    以前的表现就是「日志一直在刷心跳、画面冻住、永不重连」。
                    //    这里只告警不改行为：CarLife 是变化驱动的，静止画面本来就可能帧少。
                    if (streamStarted) {
                        long nowMs = System.currentTimeMillis();
                        long vAge = Math.max(0L, nowMs - lastVideoRxAtMs);
                        if (vAge > VIDEO_QUIET_WARN_MS && nowMs - lastVideoWarnMs > 60_000) {
                            lastVideoWarnMs = nowMs;
                            log("⚠ 视频通道 " + (vAge / 1000) + "s 没有任何数据（含空帧）"
                                    + "—— 画面可能已停；CMD 心跳仍在，判定为可疑但不拆会话");
                        }
                    }
                    // 判「在不在动」看**任意通道**最近一帧的时刻，而不是只看 CMD：
                    // 推流时视频帧一直在走、CMD 可以很稀疏，只看 CMD 会把正常会话判成停滞。
                    if (lastRxAtMs > before) {
                        stalls = 0;
                        windowMs = STREAM_OBSERVE_MS;   // 在动 → 放长窗，别打断会话
                        continue;
                    }
                    // ── 一个窗内一条帧都没有 = 停滞。先定性，再决定动作。 ──
                    String diag = diagnose();
                    if (shouldReconnect(diag)) {
                        break;                          // 有证据：对端确实不在了
                    }
                    if (++stalls >= MAX_OBSERVATIONS) {
                        // ── SILENT 的诊断结论是**对端状态机卡住**，不是「它慢」 ──
                        //
                        // SILENT = TCP accept 了、6 个端口都在听，却一条协议帧都不发。
                        // 网络没问题，也不是初始化还没完（已经连着观察了几个窗）。
                        // 反编译见机制：ConnectSocket accept 之后要由 HandlerCenter 派发
                        // 0x1003(CONNECTING) 才开始走消息分发 —— 没派发就永远静默。
                        //
                        // 所以该做的是**重置状态机（CLEAR_TASK）**，不是重连：
                        // 重连只是在同一个卡死状态上再撞一次。实测依据：SILENT 观测
                        // 11 次、**7/7 轮全无进展、一次都没自愈**；而 CLEAR_TASK 之后
                        // 下一次尝试一把过（ENC_INIT 266/260ms）。
                        if (fault == Fault.SILENT && forceAllowed()) {
                            forceRestartCarlife("观测用尽，诊断 " + diag
                                    + " → 对端 accept 了连接却不进协议，判状态机卡住，"
                                    + "就地重置状态机（不空等、不盲目重连）");
                            needLaunch = false;
                        } else {
                            log("观测用尽仍无进展，诊断 " + diag + "，拆掉重来");
                        }
                        break;
                    }
                    log("观测 " + stalls + "/" + MAX_OBSERVATIONS + "：" + diag
                            + " → 对端仍在，继续等，不重置");
                    fault = Fault.NONE;                 // 重新观测，允许下一次定性
                    windowMs = OBSERVE_MS;              // 没在动 → 回到短窗
                }
                if (running) {
                    log("会话结束");
                }
            } catch (Throwable t) {
                log("会话异常：" + t);
            }
            // 连上了 6 条通道、却一帧都没来 → 记一次。三种失败都算进同一个计数：
            // @20 残留 / 握手无应答 / 视频通道被 RST —— 它们的共同出路都是「重启 Car+」，
            // 只数 @20 的第一版让另两种能无限轮下去（实测某周期 6 轮全失败、升级一次没触发）。
            if (firstFrameAt == 0) {
                noFrameFails++;
                // ── 自适应 settle：只认「已经走到开始推流、却一帧没出」这一种观测 ──
                //
                // 它说明对端的编码器是上一轮的缓存（虚拟显示没重建），此时多给它一点时间
                // 才有意义。反过来，如果本轮连 ENC_START 都没走到（连接期 RST、握手无应答），
                // 那跟连得多早无关，**不该**拿它去放宽等待，否则只会越等越慢。
                if (streamStarted && settleMs < SETTLE_MAX_MS) {
                    int before = settleMs;
                    settleMs = Math.min(SETTLE_MAX_MS, settleMs + SETTLE_STEP_MS);
                    log("已走到开始推流却一帧没出（编码器是上一轮缓存），"
                            + "下一轮把连接前等待从 " + before + "ms 放宽到 " + settleMs + "ms");
                }
            } else {
                noFrameFails = 0;      // 出过画面就别再拿失败次数去误杀
                // 出帧成功 → 说明当前等待足够了，立刻回到最短，不为下次白等
                if (settleMs != SETTLE_MIN_MS) {
                    log("已出帧，连接前等待回到 " + SETTLE_MIN_MS + "ms");
                    settleMs = SETTLE_MIN_MS;
                }
            }
            closeAll();
            if (running) {
                sleep(1800);
            }
        }
        if (running) {
            setState(State.ERROR, "多次尝试都没连上");
        }
    }

        /**
     * 服务卡死时的升级恢复：force-stop com.oplus.ocar（清 UI 进程与 :co
     * nnect 进程的状态机）， 再 CLEAR_TASK 重开 CarlifeActivity。
     */
    private void forceStopCarlifeChannel() {
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"/system/bin/am", "force-stop", "com.oplus.ocar"});
            int code = p.waitFor();
            log("am force-stop com.oplus.ocar → exit=" + code
                    + (code == 0 ? "（成功）" : "（多半无 FORCE_STOP_PACKAGES，走 CLEAR_TASK 兜底）"));
        } catch (Throwable t) {
            log("am force-stop 执行异常：" + t);
        }
        // 兜底：CLEAR_TASK 重开 CarlifeActivity（必定可用，普通权限）
        lastLaunchAtMs = 0;
        launchCarlifeChannel(true);
    }

    /**
     * 直接起 CarLife 的**服务**（不走 Activity）。
     *
     * <p>为什么必须这一步：锁屏时系统会拦掉「后台启动 Activity」，于是
     * {@link #launchCarlifeChannel} 的 am start 全部无效，Car+ 的 6 个端口永远不开 ——
     * 现场就是「锁屏后连不上 / 假车机不能触摸」。而**启动服务不受这个限制**。
     *
     * <p>动作名与服务名从 Car+ 的 AndroidManifest 里取：
     * {@code com.oplus.ocar.action.START_CARLIFE_SERVICE} →
     * {@code com.oplus.ocar/.connect.carlife.CarlifeCastManagerService}。
     */
    private boolean startCarlifeService() {
        try {
            Intent i = new Intent("com.oplus.ocar.action.START_CARLIFE_SERVICE");
            i.setComponent(new ComponentName("com.oplus.ocar",
                    "com.oplus.ocar.connect.carlife.CarlifeCastManagerService"));
            ctx.startService(i);
            log("已启动 CarLife 服务（锁屏下同样有效）");
            return true;
        } catch (Throwable t) {
            log("启动 CarLife 服务失败：" + t);
            return false;
        }
    }

    /** 当前是否处于锁屏（诊断用：锁屏时只有服务启动这条路走得通）。 */
    private boolean isKeyguardLocked() {
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager)
                    ctx.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 拉起 Car+ 的车机通道入口（exported，无需 root）。 */
    private void launchCarlifeChannel() {
        launchCarlifeChannel(false);
    }

    private void launchCarlifeChannel(boolean clearTask) {
        // 先起服务：这条路锁屏也能用，而且幂等（已经在跑就什么都不做）。
        // 后面那句 am start 只在解锁状态才有效，锁屏时会被系统静默拒绝。
        startCarlifeService();
        if (isKeyguardLocked()) {
            log("当前是锁屏状态：只用服务启动，跳过 Activity 拉起（后台启动 Activity 会被系统拒绝）");
            lastLaunchAtMs = System.currentTimeMillis();
            return;
        }
        // 冷却：反复 am start 会重置正在初始化的 Car+ 服务，等于自己把它掐死
        long now = System.currentTimeMillis();
        if (!clearTask && lastLaunchAtMs > 0 && now - lastLaunchAtMs < LAUNCH_COOLDOWN_MS) {
            log("距上次拉起 " + (now - lastLaunchAtMs) + "ms < " + LAUNCH_COOLDOWN_MS
                    + "ms，跳过（反复拉起会重置 Car+ 服务）");
            return;
        }
        lastLaunchAtMs = now;
        String[][] entries = {
                {"com.oplus.ocar", "com.oplus.ocar.CarlifeActivity"},
                {"com.oplus.ocar", "com.oplus.ocar.connect.carlife.CarlifeAccessoryFoundActivity"},
        };
        for (String[] e : entries) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(e[0], e[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (clearTask) {
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                }
                ctx.startActivity(i);
                log((clearTask ? "已 CLEAR_TASK 重开 " : "已拉起 ") + e[0] + "/" + e[1]);
                return;
            } catch (Throwable t) {
                log("拉起 " + e[1] + " 失败：" + t);
            }
        }
        log("没有可用的 Car+ 入口，改为等端口自己出现");
    }

    /** 等手机开端口 → 一次性把 6 条通道全连上。 */
    private boolean openAllChannels(int gen) {
        setState(State.WAITING_PORT, "等待 Car+ 打开通道");
        enterStep(Step.WAITING_CHANNEL);
        int[] ports = waitForPorts(gen);
        if (ports == null) {
            // 用户已经点了断开时不要报「失败」：那不是失败，是被取消
            if (running && gen == sessionGen) {
                sink.onStepFailed(Step.WAITING_CHANNEL, "等不到 Car+ 打开端口");
            }
            return false;
        }
        if (!running || gen != sessionGen) return false;
        activePorts = ports;
        log("手机监听端口组：" + ports[0] + "/" + ports[1] + "/" + ports[2]
                + "/" + ports[3] + "/" + ports[4] + "/" + ports[5]);
        setState(State.CONNECTING, "连接 6 条通道");
        enterStep(Step.CONNECT_CHANNELS);
        for (int i = 0; i < HU_SLOTS; i++) {
            if (!running || gen != sessionGen) return false;
            socks.set(i, connect(ports[i]));
            if (socks.get(i) == null) {
                log("通道 " + i + "（" + ports[i] + "）连不上，重试一次");
                sleep(400);
                if (!running || gen != sessionGen) return false;
                socks.set(i, connect(ports[i]));
            }
            if (socks.get(i) == null) {
                sink.onStepFailed(Step.CONNECT_CHANNELS, "通道 " + ports[i] + " 连不上");
                return false;
            }
        }
        for (int i = 0; i < HU_SLOTS; i++) {
            log("已连接通道 " + channelName(i) + " " + ports[i]);
        }
        return true;
    }

    /**
     * 轮询两组端口，返回正在监听的那一组。
     * 端口刚打开时那台监听者还没把会话装好——立刻连会被它当成无效连接丢掉，
     * 所以发现端口后先让它稳定一下再连（实测：立刻连 = 被 reset / 全程无回应）。
     */
    private int[] waitForPorts(int gen) {
        long waitStart = System.currentTimeMillis();
        long deadline = waitStart + portWaitMs;
        boolean relaunched = false;

        // ── 兜底：读不到 /proc/net/tcp ──
        //
        // 极少数系统/SELinux 配置下读不到内核 LISTEN 表，这时无法观测端口。
        // 退化为「按默认端口组直接连」：连接失败本身没有副作用，只要**一次会话里
        // 每条通道只连一次**，就不会触发对方的 isAlreadyConnected → resetConnectManager。
        if (!procNetReadable()) {
            int settle = coldStart ? COLD_SETTLE_MS : settleMs;
            long sinceLaunch = lastLaunchAtMs > 0
                    ? Math.max(0L, System.currentTimeMillis() - lastLaunchAtMs) : Long.MAX_VALUE;
            long w = sinceLaunch < settle ? settle - sinceLaunch : 0;
            if (w > 0) {
                log("读不到 /proc/net/tcp，按默认端口组等 " + w + "ms 后直连");
                sleep((int) w);
            } else {
                log("读不到 /proc/net/tcp，按默认端口组直连");
            }
            // 盲等期间用户可能已经点了「断开」：别再往下连（连上的 socket 没人关）
            return (running && gen == sessionGen) ? HuProto.WIFI_PORTS : null;
        }

        while (running && gen == sessionGen && System.currentTimeMillis() < deadline) {
            for (int[] set : new int[][]{HuProto.WIFI_PORTS, HuProto.LOCAL_PORTS}) {
                if (probePort(set[0])) {
                    // 只在**真的刚拉起过 Car+** 时才等，且只等这一次。
                    //
                    // 以前写的是 `Math.max(waitStart, lastLaunchAtMs) + settleMs`，
                    // 而 waitStart 每轮都刷新 —— 等价于「每一轮都把 6 秒从头再走一遍」。
                    // 一轮 8 秒、十轮就是 100 多秒，这不是手机慢，是自己的计时器写错了。
                    //
                    // 正确语义：拉起 Car+ 后它要 ~4.5s 才把服务备好，所以**只等这一回**；
                    // 之后的重试 Car+ 早已就绪，直接连。手机若回 @20，那是组件进程里
                    // mInited 为 true 的短路回显，交给 0x1800C 帧率归一化纠正，
                    // 不需要靠重启进程来重置。
                    // 冷启动那 8 秒原本是「盲等」：读不到 /proc 时唯一能做的猜测。
                    // 现在能读到端口表了（直接读或 root 兜底），就按**六个端口是否开齐**判：
                    // 齐了只等 SETTLE_MIN_MS（4500ms 的下限有实测依据：1500ms 会让 ocar 端口全灭），
                    // 没齐才退回冷启动的 8 秒。
                    boolean allUp = allPortsListening(set);
                    int settle = allUp ? SETTLE_MIN_MS : (coldStart ? COLD_SETTLE_MS : settleMs);
                    long sinceLaunch = lastLaunchAtMs > 0
                            ? Math.max(0L, System.currentTimeMillis() - lastLaunchAtMs) : Long.MAX_VALUE;
                    if (allUp) {
                        log("六个端口已开齐（距拉起 " + (sinceLaunch == Long.MAX_VALUE ? "?" : sinceLaunch + "ms")
                                + "）→ 按最短等待 " + SETTLE_MIN_MS + "ms 连");
                    }
                    long w = sinceLaunch < settle ? settle - sinceLaunch : 0;
                    if (w > 0) {
                        log("端口 " + set[0] + " 已打开，距上次拉起还差 " + w + "ms，等够再连");
                        sleep((int) w);
                        if (!running || gen != sessionGen) return null;
                    } else {
                        log("端口 " + set[0] + " 已打开（距上次拉起已超过 " + settle
                                + "ms），直接连");
                    }
                    // ⚠️ 只复查 set[0]。以前这里还探 set[5]=9340（触摸通道），
                    // 那是拿一条**立刻就关**的连接去占 Car+ 的触摸槽位，真正的触摸
                    // 连接反倒排到第二条——和音频/视频通道踩的是同一类坑。
                    if (!probePort(set[0])) {
                        log("稳定期内端口又关了，继续等");
                        break;
                    }
                    portResetStreak = 0;    // 端口回来了 → 重置连败计数
                    return set;
                }
            }
            // ── 端口全无：不要空等 9 秒 ──
            //
            // 实测（6 份日志逐行对时）：RST 之后 6 个监听端口**全部消失**，而且它们
            // **不会自己回来** —— 每一次都是显式重新拉起 Car+ 之后约 1 秒才恢复。
            // 原来的「等 9 秒再拉」在 run1/2/3/5/6 里全部吃满，纯白扔；而拉起本身只要 1 秒。
            //
            // 所以这里改成**观测驱动**：距上次拉起超过 3 秒仍一个端口都没有，
            // 就判「监听器已被对端自己拆掉」，立刻重置 —— 不再拿固定 9 秒去猜。
            // 3 秒的依据：实测「拉起 → 端口出现」全部落在 1~2 秒内（最长一例 2 秒），
            // 3 秒既留足余量，又不会把正在起来的服务反复重置。
            if (!relaunched) {
                long sinceLaunch = lastLaunchAtMs > 0
                        ? Math.max(0L, System.currentTimeMillis() - lastLaunchAtMs) : Long.MAX_VALUE;
                if (sinceLaunch > 3000) {
                    relaunched = true;
                    if (portResetStreak < 3) {
                        // ⚠️ 只有**真的执行了重置**才计数：tryForceRestart 在冷却窗口内
                        //    会直接返回 false（什么都没做），以前按「调用即计数」，
                        //    冷却里空喊两次就把 streak 推到 3 → 之后永久「只等」，
                        //    而端口按注释根本不会自己回来 = 会话彻底卡死。
                        boolean restarted = tryForceRestart("重置监听器");
                        if (restarted) {
                            portResetStreak++;
                            log("距上次拉起 " + sinceLaunch + "ms 仍无任何端口，"
                                    + "判监听器已被对端拆掉（它不会自己回来），立即重置而不空等"
                                    + "（第 " + portResetStreak + " 次）");
                        } else {
                            log("距上次拉起 " + sinceLaunch + "ms 仍无任何端口，但强拉冷却中 → "
                                    + "本轮不计数，继续等端口（冷却结束会自动再试）");
                        }
                    } else {
                        // 连续 3 次重置都换不回监听器 → 再重置也不会更有效，
                        // 而且反复 CLEAR_TASK 会不停打断 Car+ 服务自己的重建过程。
                        // 这里改成「只等」，把决定权交回给对端。
                        log("已连续 " + portResetStreak + " 次重置仍换不回监听器，"
                                + "停止重置、只等 —— 反复重启反而会打断它自己的重建");
                    }
                }
            }
            sleep(250);
        }
        return null;
    }

        /**
     * 端口是否在 LISTEN —— **只读 /proc/net/tcp，绝不建立 TCP 连接**。
     */
    private boolean probePort(int port) {
        return isListening(port);
    }

    private boolean isListening(int port) {
        return scanProcNet("/proc/net/tcp", port) || scanProcNet("/proc/net/tcp6", port);
    }

    /** 一组端口（6 条通道）是不是全部在 LISTEN —— 端口齐了才算 Car+ 的服务备好。 */
    private boolean allPortsListening(int[] set) {
        for (int p : set) {
            if (!isListening(p)) return false;
        }
        return true;
    }

    // ── /proc/net/tcp 读取：直接读不到时用 root 兜底 ──────────────────────
    //
    // Android 10+ 的 SELinux 不给普通应用读 /proc/net/tcp。以前读不到就退化成
    // 「盲等 8 秒再直连」—— 而 Car+ 冷启动其实 5~10 秒才把 6 个端口开齐，
    // 这个盲等是首帧 9 秒里最大的一块。有 root 时 `su -c cat` 能读到，
    // 于是可以改成「等端口齐了 + 4.5 秒下限」，而不是死等 8 秒。
    private static final long PROC_NET_TTL_MS = 120;   // 表很小，但对 su 要省着点 fork
    private static volatile long procNetCacheAt;
    private static volatile String procNetTcpCache;
    private static volatile String procNetTcp6Cache;

    /** 直接读 + root 兜底；结果按 TTL 缓存（每次轮询都会调）。 */
    private String readProcNet(String path) {
        long now = System.currentTimeMillis();
        boolean tcp6 = path.endsWith("6");
        if (now - procNetCacheAt < PROC_NET_TTL_MS) {
            String c = tcp6 ? procNetTcp6Cache : procNetTcpCache;
            if (c != null) return c;
        }
        String content = readFileQuietly(path);
        if (content == null) content = readViaRoot(path);
        if (tcp6) procNetTcp6Cache = content; else procNetTcpCache = content;
        procNetCacheAt = now;
        return content;
    }

    private String readFileQuietly(String path) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(path);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(4096);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable tDiag) { Diag.w("HuClient.readFileQuietly", tDiag); }
        }
    }

    /** `su -c cat /proc/net/tcp`；拿不到 root 就返回 null（只尝试一次，失败即熔断）。 */
    private static volatile int rootCatState;   // 0=未知 1=可用 -1=不可用
    private String readViaRoot(String path) {
        if (rootCatState < 0) return null;
        Process p = null;
        try {
            // 必须用绝对路径：应用进程的 PATH 不一定含 /system/bin，
            // 直接 exec("su") 会报 error=2 No such file or directory（实测 ColorOS 17）。
            String su = null;
            for (String cand : new String[]{"/system/bin/su", "/debug_ramdisk/su",
                    "/data/adb/ksu/bin/su", "/sbin/su", "/system/xbin/su"}) {
                if (new java.io.File(cand).exists()) { su = cand; break; }
            }
            if (su == null) su = "su";
            p = Runtime.getRuntime().exec(new String[]{su, "-c", "cat " + path});
            final Process fp = p;
            final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(4096);
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    java.io.InputStream in = fp.getInputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    try {
                        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    } catch (Throwable tDiag) { Diag.w("HuClient.run", tDiag); }
                }
            }, "proc-net-root");
            reader.setDaemon(true);
            reader.start();
            boolean done = p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                try { p.destroy(); } catch (Throwable tDiag) { Diag.w("HuClient.readViaRoot", tDiag); }
                if (rootCatState == 0) log("root 读 /proc/net/tcp 超时 → 本机退化为盲等端口");
                rootCatState = -1;
                return null;
            }
            reader.join(500);
            String out = bos.toString("UTF-8");
            if (out == null || out.indexOf(':') < 0) {   // 空的/报错输出
                if (rootCatState == 0) {
                    log("root 读 /proc/net/tcp 失败（su 被拒或输出为空）→ 本机退化为盲等端口");
                }
                rootCatState = -1;
                return null;
            }
            if (rootCatState == 0) {
                log("已通过 root 读取 /proc/net/tcp → 可以用「六个端口齐」代替盲等");
            }
            rootCatState = 1;
            return out;
        } catch (Throwable t) {
            if (rootCatState == 0) {
                log("root 读 /proc/net/tcp 不可用（" + t + "）→ 退化为盲等端口；"
                        + "要在 SukiSU/KernelSU 里给本应用授权 root 才能用上端口观测");
            }
            rootCatState = -1;
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable tDiag) { Diag.w("HuClient.readViaRoot#2", tDiag); }
        }
    }

    /** /proc/net/tcp 是否可读（只用来看是否需要退化到「直连」模式）。0=未知 1=可读 -1=不可读 */
    private static volatile int procNetState;

    private boolean procNetReadable() {
        int st = procNetState;
        if (st == 0) {
            String c = readProcNet("/proc/net/tcp");
            st = (c != null && c.length() > 0) ? 1 : -1;
            procNetState = st;
        }
        return st > 0;
    }

    /** 扫一行一个 socket 的表：{@code sl local_address rem_address st ...}。 */
    private boolean scanProcNet(String path, int port) {
        String content = readProcNet(path);
        if (content == null) return false;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new java.io.StringReader(content));
            String needle = String.format(java.util.Locale.ROOT, ":%04X", port);
            String line;
            boolean header = true;
            while ((line = r.readLine()) != null) {
                if (header) {           // 跳过标题行
                    header = false;
                    continue;
                }
                String[] f = line.trim().split("\\s+");
                if (f.length < 4) {
                    continue;
                }
                if (!f[1].endsWith(needle)) {
                    continue;
                }
                if ("0A".equalsIgnoreCase(f[3])) {      // TCP_LISTEN
                    return true;
                }
            }
        } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("HuClient", t);
            // 读不到（旧系统/SELinux）就当作没在听：调用方会走「等端口」分支，
            // 不会再退化成 TCP 探测。
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("HuClient", t);
                }
            }
        }
        return false;
    }

    private Socket connect(int port) {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            s.setSoTimeout(0);
            s.setTcpNoDelay(true);
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String channelName(int i) {
        switch (i) {
            case 0:
                return "cmd  ";
            case 1:
                return "video";
            case 2:
                return "audio";
            case 3:
                return "tts  ";
            case 4:
                return "vr   ";
            default:
                return "touch";
        }
    }

    // ------------------------------------------------------------- 故障定性与诊断

    /** 记录故障性质与发生时停在哪一步。已有定性时不覆盖（第一现场最有价值）。 */
    private void markFault(Fault f) {
        if (fault != Fault.NONE) {
            return;
        }
        fault = f;
        faultAtStep = step;
    }

    /** 把底层 IO 异常翻译成可决策的故障类别。 */
    private static Fault classify(java.io.IOException ioe) {
        String m = String.valueOf(ioe.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (m.contains("reset")) {
            return Fault.RST;
        }
        if (m.contains("broken pipe") || m.contains("closed") || m.contains("stream closed")) {
            return Fault.EOF;
        }
        return Fault.SILENT;
    }

    /** Car+ 的 6 个监听端口当前有几个在听 —— 「对端服务还在不在」的硬证据。 */
    private int alivePorts() {
        int alive = 0;
        for (int p : HuProto.WIFI_PORTS) {
            if (probePort(p)) {
                alive++;
            }
        }
        return alive;
    }

    /**
     * 卡住时的**诊断**：先观测「走到哪一步 / 怎么断的 / 对端还活着吗」，再谈动作。
     *
     * <p>输入全部来自**观测**，没有一项来自「计时器到点了」——这才是可继续优化延时的
     * 架构：把等待换成观察，而不是把观察换成更短的超时。
     */
    private String diagnose() {
        return "步骤=" + stepName(faultAtStep)
                + " 故障=" + fault
                + " Car+端口=" + alivePorts() + "/6"
                + " 推流后模块状态=" + (moduleSeen ? "已收到" : "未收到");
    }

        /**
     * 根据诊断决定是否**拆会话重连**。返回 {@code false} = 保持现状继续观察。
     */
    private boolean shouldReconnect(String diag) {
        // 端口掉光 = 对端服务确实没了，这是最硬的证据，任何故障类别都应重连
        if (alivePorts() == 0) {
            log("诊断 " + diag + " → 对端监听端口已全部消失，服务不在，重连");
            return true;
        }
        switch (fault) {
            case EOF:
                log("诊断 " + diag + " → 对端已结束会话，等它释放槽位再重连");
                return true;
            case RST:
                log("诊断 " + diag + " → 通道槽位冲突（对端重复连接会全关 socket），"
                        + "先等残留连接释放，不立刻重试");
                sleep(1200);
                return true;
            case SILENT:
                log("诊断 " + diag + " → 对端慢、读线程未挂上，**不重置**，继续观察");
                return false;
            default:
                log("诊断 " + diag + " → 未见对端已死的证据，继续观察");
                return false;
        }
    }

    private static String stepName(Step s) {
        return s == null ? "-" : s.name();
    }

    // ------------------------------------------------------------- CMD 循环

    /**
     * 读控制通道一段**观察窗**。
     *
     * <p>以前这里有个「读到第一条帧就 {@code setSoTimeout(0)} 永久阻塞」的写法，配上
     * {@code ctrlLoop(0, …)} 的推流调用 —— 结果对端只要在**任何一步**停下，线程就挂在
     * 同步读上：实测出现过 245.8s / 155s / 87s 的静默空窗（旧的快速判负删掉了，
     * 新兜底却只装在握手前半段，推流后半段完全没人管）。
     *
     * <p>现在**永远有界**：窗口一到就返回，把「要不要继续等」的判断交回上层的观察窗循环。
     *
     * @param windowMs 观察窗长度
     * @return 本窗内是否收到过至少一条 CMD
     */
    private boolean ctrlLoop(int windowMs) {
        boolean answered = false;
        try {
            if (windowMs > 0 && socks.get(0) != null) {
                socks.get(0).setSoTimeout(windowMs);
            }
            while (running) {
                // ⚠️ 头和体必须在**同一个** try 里：
                //    以前只包住 8 字节头，体读（长度可达 8MB）一旦超时/失败会落到外层
                //    catch —— 头已经消费、体只读了一半，CMD 帧没有 magic，无法就地重同步；
                //    之后就把体中间的字节当新头解，service/len 全是垃圾，
                //    而且这些垃圾包还会刷新 lastRxAtMs，上层永远判「在对端在动」。
                boolean headRead = false;
                try {
                    byte[] head = readExact(socks.get(0), 8);
                    if (head == null) {
                        if (running) log("控制通道被对端关闭");
                        markFault(Fault.EOF);
                        return answered;
                    }
                    headRead = true;
                    int len = HuProto.cmdLength(head);
                    int service = HuProto.cmdService(head);
                    byte[] body = len > 0 ? readExact(socks.get(0), len) : new byte[0];
                    if (body == null) {
                        log("控制通道负载读取失败（头已消费 " + len + "B）→ 流已错位，重建会话");
                        markFault(Fault.STALL);
                        closeAll();
                        return answered;
                    }
                    trace("RX", 0, service, body);
                    lastRxAtMs = System.currentTimeMillis();
                    answered = true;
                    onCmd(service, body);
                } catch (java.net.SocketTimeoutException te) {
                    if (headRead) {
                        // 半条帧：不能继续在这条流上解下一条，只能重建
                        log("控制通道负载读超时（头已消费）→ 流已错位，判链路故障并重建会话");
                        markFault(Fault.STALL);
                        closeAll();
                    } else {
                        // TCP 是通的、对端只是不回 —— 不是「死」，是「这段窗内没有动静」。
                        // 定性为 SILENT 交给上层决定要不要继续等，**不能**当成失败去重连。
                        markFault(Fault.SILENT);
                    }
                    return answered;
                } catch (java.io.IOException ioe) {
                    if (running) log("控制通道读失败：" + ioe);
                    markFault(classify(ioe));
                    if (headRead) closeAll();   // 半条帧同样必须重建
                    return answered;
                }
            }
        } catch (Throwable t) {
            if (running) {
                log("控制通道异常：" + t + " → 判链路故障并重建会话");
            }
            markFault(Fault.SILENT);
            closeAll();
        }
        return answered;
    }

    /**
     * 模块 ID → 名字（CarLife 协议，见 carbox 的协议取证
     * {@code Input/WirelessCarLifePlus/SPEC.md} §5）。
     */
    private static String moduleName(int id) {
        switch (id) {
            case HuProto.MODULE_PHONE: return "PHONE";
            case HuProto.MODULE_NAVI: return "NAVI";
            case HuProto.MODULE_MUSIC: return "MUSIC";
            case HuProto.MODULE_VR: return "VR";
            case HuProto.MODULE_CONNECT: return "CONNECT";
            case HuProto.MODULE_MIC: return "MIC";
            case 8: return "CRUISE";
            case 9: return "CRUISE_FOLLOW";
            default: return "M" + id;
        }
    }

    private void onCmd(int service, byte[] body) {
        switch (service) {
            case HuProto.MD_VERSION_MATCH:
                enterStep(Step.VERSION);
                log("← 版本匹配，回统计信息");
                send(0, HuProto.HU_STATISTICS, HuProto.statistics());
                break;
            case HuProto.MD_INFO: {
                enterStep(Step.DEVICE_INFO);
                String info = HuProto.deviceField(body, 14) + " / "
                        + HuProto.deviceField(body, 8) + " / " + body.length + "B";
                sink.onPhoneInfo(info);
                log("← 手机设备信息：" + info);
                send(0, HuProto.HU_INFO, HuProto.huInfo(width, height));
                log("→ HU_INFO（display=" + width + "x" + height + "）");
                break;
            }
            case HuProto.MD_FEATURE_CONFIG_REQ:
                enterStep(Step.FEATURE_CONFIG);
                send(0, HuProto.HU_FEATURE_CONFIG_LIST, HuProto.featureConfig(connectType, autoFit));
                log("→ 特征配置（CONNECT_TYPE=" + connectType
                        + (autoFit ? "，DISABLE_RESOLUTION_OPTION=1" : "") + "）");
                break;
            case HuProto.MD_AUTHEN_RESULT:
                enterStep(Step.AUTH);
                send(0, HuProto.HU_AUTHEN_RESULT, new byte[0]);
                encoderInitSentAtMs = System.currentTimeMillis();
                send(0, HuProto.HU_VIDEO_ENCODER_INIT,
                        HuProto.encoderInit(width, height, fps));
                log("→ 鉴权结果 + 编码参数 " + width + "x" + height + "@" + fps);
                break;
            case HuProto.MD_VIDEO_ENCODER_INIT: {
                enterStep(Step.ENCODER);
                // 应答延迟只作**诊断信息**记进日志，不参与任何判定 —— 判负门已删，
                // 见「历史备忘：判负门已删除」。判定一律基于对端给的事件。
                long latency = encoderInitSentAtMs > 0
                        ? Math.max(0L, System.currentTimeMillis() - encoderInitSentAtMs) : -1;
                int gotFps = 0;
                int[] p = HuProto.parseEncoderInfo(body);
                if (p != null) {
                    gotFps = p[2];
                    log("← 手机编码参数 " + p[0] + "x" + p[1] + "@" + p[2]
                            + "（ENC_INIT 应答延迟 " + latency + "ms）");
                    // 手机**实际**的编码尺寸 = 触摸坐标的换算基准
                    if (p[0] > 0 && p[1] > 0) {
                        encW = p[0];
                        encH = p[1];
                    }
                    if (p[2] > 0) {
                        encFps = p[2];
                    }
                    pushStreamSize();
                    notifyEncoderInfo();
                }
                // 帧率判据：**等于或高于**目标都算合格。
                //
                // 以前是严格相等（gotFps == fps）。用户在 Car+ 里把帧率设成 60 之后，
                // 我们请求 30 就会判「不合格」→ 发 0x1800C 主动把它**降回 30**
                // —— 正好把用户想要的高帧率掐掉。实际能给的比我们要的多，是好事。
                boolean fpsOk = gotFps <= 0 || gotFps >= fps;
                if (fpsOk && gotFps > fps) {
                    int capped = DisplayGeometry.clampFps(gotFps);
                    log("Car+ 实际 @" + gotFps + " 高于目标 @" + fps + " → 按实际 @" + capped + " 走（不降档）");
                    fps = capped;
                    learnedFps = capped;
                }
                // ⚠️ 这里以前有一道「ENC_INIT 应答延迟 ≥215ms 才放行」的门。
                // 那是**拿定时去猜结局**（撞库）：延迟只是「有没有真干活」的代理指标，
                // 既不是对端给出的事件，也无法据此优化延时 —— 你说的问题正在这里。已删除。
                //
                // 现在只认对端**自己说的话**：帧率对不对（0x10008 载荷）是事件；
                // 发出 0x18009 之后回不回 MD_MODULE_STATUS(0x10026) 和视频帧也是事件。
                // 要不要继续等，由这些事件决定，不由计时器决定。
                if (fpsOk) {
                    scheduleStartStreaming();
                    break;
                }
                // 运行中动态改帧率后，Car+ 可能还会回一条旧参数的 0x10008。
                // 此时会话已经在推流，不能把它当成失败去拆链。
                if (streamStarted && liveFrameRateChangeDone) {
                    log("收到动态改帧率后的旧编码应答 @" + gotFps
                            + "（当前目标 @" + fps + "），忽略，不打断会话");
                    break;
                }
                // ── 帧率归一化：手机短路回显 @20 时，编码器其实还在 ──
                //
                // 反编译 com.baidu.carlife.oppo 的 VideoMsgHandler 得知 @20 的来源是：
                //   if (mInited) { sendVideoSize(wEnc, hEnc, Recorder.mDestFrameRate); return; }
                //   // mInited 是 static boolean，mDestFrameRate 是 static int、初值 20
                // 也就是进程只要连过一次，之后每次 0x18007 都被短路回吐这个静态的 20。
                // 之前试过「重发 ENC_INIT 想把它问醒」，41 次 0 次救回 —— 正因为它只读不写。
                //
                // 而 0x1800C 走 Recorder.changeFrameRate(f)，是写这个字段的唯一入口。
                // 发它把 20 改成我们要的值，再重发 0x18007，应答就一致了。
                if (!fpsOk && !frameRateChanging && !encoderNormalized) {
                    frameRateChanging = true;
                    send(0, HuProto.HU_VIDEO_ENCODER_FRAME_RATE_CHANGE,
                            HuProto.videoFrameRate(fps));
                    log("应答是 @" + gotFps + "（手机短路回显旧值，编码器其实还在）"
                            + "→ 发 0x1800C 把帧率改成 @" + fps + "，等它回 0x1000D");
                    break;
                }
                // 归一化后仍然不一致：不要再无限重连。OcarPlay 的做法是接受
                // 编码器/车机实际能给的帧率（例如目标 60、实际 30），把真实值
                // 告诉 UI；只有拿到 <20fps 这种明显异常的应答才拆会话重连。
                if (encoderNormalized && gotFps >= DisplayGeometry.MIN_FPS) {
                    int old = fps;
                    fps = gotFps;
                    learnedFps = gotFps;      // 下次重连直接从这儿起步
                    liveFrameRateChange = false;
                    liveFrameRateChangeDone = true;
                    log("目标 @" + old + " 未被接受，回退到 Car+ 实际 @" + gotFps
                            + "（继续推流，避免重连循环）");
                    notifyEncoderInfo();
                    scheduleStartStreaming();
                    break;
                }
                // 归一化也救不回来，或者问题不是帧率而是延迟太短 → 只能拆掉重连。
                log("应答不合格（" + (fpsOk
                                ? "延迟仅 " + latency + "ms，手机在短路回显、虚拟显示没建完"
                                : "帧率 @" + gotFps + "≠@" + fps + "，帧率归一化也没救回来")
                        + "）→ 不推流，立刻拆会话重连");
                closeAll();          // 触发 session() 的重连循环，顺带计入 noFrameFails
                break;
            }
            case HuProto.MD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE: {
                boolean live = liveFrameRateChange;
                liveFrameRateChange = false;
                liveFrameRateChangeDone = true;
                frameRateChanging = false;
                encoderNormalized = true;
                if (live) {
                    log("← 运行中帧率变更完成 @" + fps + "，继续原视频流（不重发 ENC_INIT）");
                    notifyEncoderInfo();
                    break;
                }
                encoderInitSentAtMs = System.currentTimeMillis();
                send(0, HuProto.HU_VIDEO_ENCODER_INIT,
                        HuProto.encoderInit(width, height, fps));
                log("← 帧率变更已完成，重发编码器初始化取一致的 @" + fps);
                break;
            }
            case HuProto.MD_CONNECT_EXCEPTION:
                // 手机唯一的「我这边出错了」通道（sendVideoException → CarlifeConnectException）。
                // 以前完全没有处理，它只会被记成「未知消息」然后丢掉。
                log("⚠ 对端上报连接异常 exceptionType=" + HuProto.hex(body, 8)
                        + "（编码器初始化失败时会出现，值 1 = VIDEO_ENCODER_INIT 失败）");
                break;
            case HuProto.MD_SCREEN_ON:
                log("← 手机亮屏");
                break;
            case HuProto.MD_MODULE_STATUS:
                // 对端主动上报模块状态 = 它的编码链路确实在跑。
                // 这是**外部事件**，不是我们按时间猜的，看门狗据此区分
                // 「有进展地等」和「真卡死」。
                moduleSeen = true;
                // 模块状态里 **PHONE(1) 活跃 = 手机侧正在通话**。
                // CarLife 没有独立的通话音频通道，通话声混在 MEDIA 里；
                // 只有靠这个信号才能把 MEDIA 的那段判成「通话声」，
                // 转进 CarPlay 的 telephony 流（见 CarPlayBridge.submitTelephonyAudio）。
                java.util.List<int[]> mods = HuProto.parseModuleStatus(body);
                if (!mods.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    boolean phoneActive = false;
                    for (int[] m : mods) {
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(moduleName(m[0])).append('=').append(m[1]);
                        if (m[0] == HuProto.MODULE_PHONE && m[1] != 0) phoneActive = true;
                    }
                    phoneModuleActive = phoneActive;
                    log("← 模块状态 " + sb + (phoneActive ? "  **通话中**" : ""));
                    try {
                        sink.onPhoneCallState(phoneActive);
                    } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("HuClient", t);
                    }
                } else {
                    log("← 模块状态 " + body.length + "B " + HuProto.hex(body, 12));
                }
                break;
            default:
                log("← 未知消息 " + service + "（0x" + Integer.toHexString(service)
                        + "）len=" + body.length + " " + HuProto.hex(body, 12));
                break;
        }
    }

    // ------------------------------------------------------------- 视频

        /**
     * 等对端把虚拟显示建好，再发 {@code 0x18009} 并挂上媒体读取线程。
     */
    private void scheduleStartStreaming() {
        if (startScheduled || streamStarted) {
            return;
        }
        startScheduled = true;
        final int gen = mediaGen;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(CAST_READY_DELAY_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (!running || gen != mediaGen || !startScheduled) {
                    return;                       // 会话已换代，这一枪不能开
                }
                startScheduled = false;
                if (streamStarted) {
                    return;
                }
                streamStarted = true;
                boolean ok = send(0, HuProto.HU_VIDEO_ENCODER_START, new byte[0]);
                log((ok ? "→ 开始推流" : "⚠ 投屏就绪后 START 发送失败")
                        + "（已等投屏 " + CAST_READY_DELAY_MS + "ms，等对端上报模块状态 / 视频帧）");
                startVideoReader();
                startAudioReader();
                startVoiceDrain(3, "导航语音");     // 9241 TTS
                startVoiceDrain(4, "语音助手");     // 9242 VR（以前根本没读，会反压 Car+）
            }
        }, "hu-enc-start-g" + gen).start();
    }

    private void startVideoReader() {
        if (videoReading) {
            return;       // 同一会话里 视频就绪/编码器就绪 两条消息都会调这里，正常情况
        }
        videoReading = true;
        final int gen = mediaGen;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    videoLoop(gen);
                } finally {
                    // 同音频线程：只有本代有权复位，避免旧线程晚退把新代标志清掉
                    if (gen == mediaGen) {
                        videoReading = false;
                    }
                }
            }
        }, "hu-video-g" + gen).start();
        log("视频读取线程已启动（gen=" + gen + "）");
    }

    private void videoLoop(final int gen) {
        Socket s = socks.get(1);
        if (s == null) {
            log("视频通道不存在");
            return;
        }
        setState(State.STREAMING, "投屏中");
        try {
            // gen 守卫：会话换代即退出，别把上一代的关闭事件算到新会话头上
            while (running && gen == mediaGen) {
                byte[] head;
                try {
                    head = readExact(s, 12);
                } catch (Throwable t) {
                    // 「读不到」有两条路：**异常**（RST 硬拒 / 本端 closeAll）走这里，
                    // **返回 null** = 对端正常 FIN。以前 readN 把两者吞成同一句
                    // 「视频通道关闭」，导致手机主动收流 vs 手机硬拒无法区分。
                    if (running) {
                        log(String.valueOf(t).contains("Socket closed")
                                ? "视频通道被本端关闭（会话已在别处拆掉）"
                                : "视频通道读异常，重启会话：" + t);
                        closeAll();
                    }
                    return;
                }
                if (head == null) {
                    if (running) {
                        log("视频通道被对端 FIN 关闭，重启会话");
                        closeAll();
                    }
                    return;
                }
                int len = HuProto.mediaLength(head);
                int ts = HuProto.mediaTimestamp(head);
                int service = HuProto.mediaService(head);
                if (len == 0) {
                    // ⚠️ len==0 是 CarLife 的**空帧/心跳**，不是故障。
                    // 以前把它当致命错误 → closeAll() → 车机侧整个会话被我们拆掉，
                    // 重连后又遇到空帧 → 再断……现场表现就是「Car+ 总是自己断联」。
                    // 空帧是「这条通道还活着」的证据，必须刷新活性时间戳，
                    // 否则画面静止时（Car+ 只发空帧）会被外层观察窗判成停滞。
                    lastRxAtMs = System.currentTimeMillis();
                    lastVideoRxAtMs = System.currentTimeMillis();
                    emptyFrames++;
                    if (emptyFrames == 1 || emptyFrames % 200 == 0) {
                        log("视频空帧（len=0）已跳过 " + emptyFrames + " 个（心跳/占位，不断会话）");
                    }
                    continue;
                }
                if (len < 0 || len > 8 * 1024 * 1024) {
                    log("视频帧长度非法 " + len + "，重启会话");
                    closeAll();
                    return;
                }
                byte[] payload = readN(s, len);
                if (payload == null) {
                    // 帧体读到一半就断了：线程静默退出会让「视频已死」这件事无人知晓
                    // （CMD 心跳还会一直刷新活性，外层永不判停）→ 黑屏且不重连。
                    if (running && gen == mediaGen) {
                        log("视频帧体读取失败（半包/断开）→ 判链路故障并重建会话");
                        markFault(Fault.EOF);
                        closeAll();
                    }
                    return;
                }
                lastRxAtMs = System.currentTimeMillis();   // 视频帧也算「链路在动」
                if (firstFrameAt == 0) {
                    firstFrameAt = System.currentTimeMillis();
                    enterStep(Step.STREAMING);
                    log("★ 首帧到达：len=" + len + " 头 " + HuProto.hex(payload, 12)
                            + "（首帧延迟 " + (firstFrameAt - startedAt) + "ms）");
                }
                lastVideoRxAtMs = System.currentTimeMillis();
                frameCount++;
                byteCount += len;
                // 交给 CarPlay 发送端推给车机（bridge 为 null 时只做本机预览）
                CarPlayBridge bridge = carPlayBridge;
                if (bridge != null) {
                    bridge.submitVideo(payload, len, ts);
                }
                sink.onVideo(payload, len, ts);
                if (frameCount % 60 == 0) {
                    sink.onMetrics(frameCount, byteCount, firstFrameAt - startedAt);
                }
            }
        } catch (Throwable t) {
            if (running && gen == mediaGen) {
                // 帧体读取、sink 回调（本机预览）任何一步抛异常都会让线程静默退出，
                // 而 CMD 心跳还在刷新活性 → 黑屏且永不重连。按整轮故障处理。
                log("视频通道异常：" + t + " → 判链路故障并重建会话");
                markFault(Fault.SILENT);
                closeAll();
            } else if (running) {
                log("视频通道异常（旧会话，忽略）：" + t);
            }
        }
    }

    // ------------------------------------------------------------- 音频

    /**
     * 起音频读取线程（9240 = MEDIA 通道）。
     *
     * <p>⚠️ 以前连上 9240 却**从不读**：手机往里持续推媒体数据，全堵在 TCP 接收缓冲，
     * 既没声音、又会反压手机侧的媒体发送线程。车机无声的根因之一就在这。
     */
    private void startAudioReader() {
        if (audioReading) {
            // 留痕：这里静默返回曾让「上一代线程晚退后新会话再也没起音频线程」无从排查
            log("音频读取线程已在跑，跳过启动");
            return;
        }
        audioReading = true;
        final int gen = mediaGen;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    audioLoop(gen);
                } finally {
                    // 只有「本代线程」才能复位标志：旧代线程晚退若把新代的标志清了，
                    // 下次 startAudioReader 会重复起线程，两条线程抢读同一个 9240。
                    if (gen == mediaGen) {
                        audioReading = false;
                    }
                }
            }
        }, "hu-audio-g" + gen).start();
        log("音频读取线程已启动（gen=" + gen + "）");
    }

        /**
     * 读 MEDIA 通道，把音频载荷交给 CarPlay 发送端。
     */
    private void audioLoop(final int gen) {
        int dataPkts = 0;
        long bytes = 0;
        try {
            // gen 守卫：会话一旦被 closeAll() 换代就退出，避免旧线程永远占着
            // audioReading（其阻塞读在套接字被关后才会返回，期间新会话起不来音频线程）
            while (running && gen == mediaGen) {
                Socket s = socks.get(2);
                if (s == null) {
                    // ⚠️ 不能裸 return：线程退出后 audioReading 会被复位，而
                    //    startAudioReader() 只在「开始推流」那一步调用一次 → 没人再起线程，
                    //    9240 不再被读，接收缓冲填满反压 Car+ 写线程。画面正常、车机永久无声。
                    log("音频通道不存在 → 判链路故障并重建会话");
                    markFault(Fault.EOF);
                    closeAll();
                    return;
                }
                byte[] head = readN(s, 12);
                if (head == null) {
                    if (running && gen == mediaGen) {
                        // 音频通道死了但视频还在跑时，现场是「车机永久无声、日志还显示推流中」。
                        // 六条通道是一条会话，任何一条断都按整轮故障处理。
                        log("音频通道关闭（gen=" + gen + "）→ 判链路故障并重建会话");
                        markFault(Fault.EOF);
                        closeAll();
                    }
                    return;
                }
                int len = HuProto.mediaLength(head);
                int ts = HuProto.mediaTimestamp(head);
                int service = HuProto.mediaService(head);
                if (len < 0 || len > 1024 * 1024) {
                    // 长度非法意味着这条流已经错位，继续读只会读出垃圾
                    log("音频包长度非法 " + len + " → 判链路故障并重建会话");
                    markFault(Fault.SILENT);
                    closeAll();
                    return;
                }
                byte[] payload = len == 0 ? new byte[0] : readN(s, len);
                if (payload == null) {
                    if (running && gen == mediaGen) {
                        log("音频包体读取失败（半包/断开）→ 判链路故障并重建会话");
                        markFault(Fault.EOF);
                        closeAll();
                    }
                    return;
                }
                if (service == HuProto.MEDIA_INIT) {
                    // 参数声明：解析出采样率/声道/位深，用于核对与车机申报的 audioFormat 是否一致
                    int[] fmt = parseMediaInit(payload);
                    log("← MEDIA_INIT 音频格式 " + (fmt != null
                            ? fmt[0] + "Hz/" + fmt[1] + "ch/" + fmt[2] + "bit"
                            : "解析失败 " + HuProto.hex(payload, 16)));
                    // ⚠️ 下游按 44.1k/立体声/16bit 封包（SETUP 里也是这么报的）。
                    //    源若不是这个格式，会被整体变速/变调或下混错误 —— 至少要在日志里
                    //    说清楚，而不是只打印「收到 48000Hz」让人自己去推。
                    if (fmt != null && (fmt[0] != 44100 || fmt[1] != 2 || fmt[2] != 16)) {
                        log("⚠ 源音频格式与下游封装不一致（" + fmt[0] + "Hz/" + fmt[1]
                                + "ch/" + fmt[2] + "bit vs 44100/2/16）：可能变速/变调，"
                                + "需重采样才能彻底修好（当前未做）");
                    }
                    continue;
                }
                if (service != HuProto.MD_MEDIA_DATA) {
                    continue;   // 其它媒体服务（TTS 等）暂不转发
                }
                dataPkts++;
                bytes += len;
                lastMediaAudioAtMs = System.currentTimeMillis();
                if (dataPkts == 1) {
                    log("★ 音频数据首包 len=" + len + " ts=" + ts
                            + " 头 " + HuProto.hex(payload, 16));
                } else if (dataPkts == 200) {
                    log("音频数据已收 " + dataPkts + " 包 / " + bytes + "B");
                }
                CarPlayBridge bridge = carPlayBridge;
                if (bridge != null) {
                    if (phoneModuleActive) {
                        // 通话中：这段 MEDIA 音频是**通话声** → 走 CarPlay 的 telephony 流
                        // （真 iPhone 就是单独开一条 audioType=telephony 的流，单声道）。
                        bridge.submitTelephonyAudio(payload, len, ts);
                    } else {
                        // 音乐与导航在手机侧混好再送：接收端对 duckAudio / 第二条流不可靠，
                        // 而混音发生在编码之前，对任何档位都成立。
                        // 直接送：Car+ 抓的就是系统混音，导航语音和「压低音乐」都已经在里面
                        // （实测 2026-11-19：高德播报时 Android 把音乐 VolumeShaper 压到 0.2，
                        //  Car+ 抓到的流里同时有语音 —— 我们不需要自己混）。
                        bridge.submitAudio(payload, len, ts);
                    }
                }
            }
        } catch (Throwable t) {
            if (running && gen == mediaGen) {
                // 和视频通道同一套处理：异常退出后 audioReading 会被 finally 复位，
                // 而 startAudioReader() 只由「开始推流」那一步调用 → 没人再起线程。
                // 结果是画面正常、车机永久无声，还会反压 Car+ 的发送线程。
                log("音频通道异常：" + t + " → 判链路故障并重建会话");
                markFault(Fault.EOF);
                closeAll();
            } else if (running) {
                log("音频通道异常（旧会话，忽略）：" + t);
            }
        }
    }

    /**
     * 语音通道（9241 TTS / 9242 语音助手）：读出来 → 混进车机音频流。
     *
     * <p>两条都必须读：CarLife 会把导航播报和语音助手提示分别推到 9241/9242，
     * **不读的那个端口接收缓冲会填满，反压 Car+ 自己的写线程**（和音频通道同源的坑）。
     * 9242 以前根本没读——只有 9241 在读，属于漏了一条。
     *
     * <p>**只读不转发**：Car+ 抓到的是系统混音，导航语音/压低音乐都已经在 9240 那条
     * 音乐流里了（2026-11-19 实测：高德播报时 Android 把音乐压到 0.2，同一路流里
     * 同时有语音）。这里读的目的是别让接收缓冲填满反压 Car+，读出来丢掉即可。
     */
    private void startVoiceDrain(final int slot, final String label) {
        final int idx = slot - 3;
        if (idx < 0 || idx >= voiceReading.length || voiceReading[idx]) {
            return;
        }
        voiceReading[idx] = true;
        final int gen = mediaGen;
        new Thread(new Runnable() {
            @Override
            public void run() {
                long drained = 0;
                int pkts = 0;
                // 语音通道的源格式：跟音乐通道一样由 MEDIA_INIT 声明，默认 16k 单声道
                int rate = 16000;
                int channels = 1;
                try {
                    Socket s = socks.get(slot);
                    if (s == null) {
                        return;
                    }
                    java.io.InputStream in = s.getInputStream();
                    while (running && gen == mediaGen) {
                        byte[] head = readN(s, 12);
                        if (head == null) {
                            return;
                        }
                        int len = HuProto.mediaLength(head);
                        if (len < 0 || len > 1024 * 1024) {
                            return;
                        }
                        byte[] payload = len == 0 ? new byte[0] : new byte[len];
                        int got = 0;
                        while (got < len) {
                            int n = in.read(payload, got, len - got);
                            if (n < 0) {
                                return;
                            }
                            got += n;
                        }
                        drained += len;
                        pkts++;

                        if (HuProto.mediaService(head) == HuProto.MEDIA_INIT) {
                            int[] fmt = parseMediaInit(payload);
                            if (fmt != null && fmt[0] > 0) {
                                log("← " + label + "通道 MEDIA_INIT：" + fmt[0] + "Hz/" + fmt[1]
                                        + "ch/" + fmt[2] + "bit（只排空，不转发）");
                            }
                            continue;
                        }
                        if (len > 0 && voiceLogged == 0) {
                            voiceLogged = 1;
                            log("← " + label + "通道有数据 len=" + len
                                    + "（Car+ 已在 9240 混好，这里只排空防反压）");
                        }
                    }
                } catch (Throwable t) {
                    // 通道关闭是正常收尾，不刷屏
                } finally {
                    if (gen == mediaGen) {
                        voiceReading[idx] = false;
                    }
                }
                if (drained > 0) {
                    log(label + "通道共 " + pkts + " 包 / " + drained + "B");
                }
            }
        }, "hu-voice-" + slot).start();
    }

    /**
     * 解析 {@code MEDIA_INIT}：protobuf {1:采样率, 2:声道, 3:位深}。
     *
     * @return {rate, channels, bits}，解析不出返回 null
     */
    private static int[] parseMediaInit(byte[] d) {
        int rate = 0, ch = 0, bits = 0;
        int i = 0;
        try {
            while (i < d.length) {
                int tag = d[i++] & 0xFF;
                int field = tag >>> 3, wire = tag & 7;
                if (wire == 0) {
                    int v = 0, shift = 0;
                    while (i < d.length) {
                        int c = d[i++] & 0xFF;
                        v |= (c & 0x7F) << shift;
                        if ((c & 0x80) == 0) break;
                        shift += 7;
                    }
                    if (field == 1) rate = v;
                    else if (field == 2) ch = v;
                    else if (field == 3) bits = v;
                } else if (wire == 2) {
                    int n = 0, shift = 0;
                    while (i < d.length) {
                        int c = d[i++] & 0xFF;
                        n |= (c & 0x7F) << shift;
                        if ((c & 0x80) == 0) break;
                        shift += 7;
                    }
                    i += n;
                } else {
                    return null;
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return rate > 0 ? new int[]{rate, ch, bits} : null;
    }

    // ------------------------------------------------------------- 触摸

    /**
     * 触摸通道专用写锁。
     *
     * <p>⚠️ 以前和 {@code send()} 共用 {@code synchronized (HuClient.this)}：一次触摸写
     * 卡住（车机不读 9340）会把心跳与全部 CMD 应答一起堵在这把锁上，整个会话僵死。
     * 触摸只写 socks[5]，没有和别的通道共享的数据，独立一把锁就够。
     */
    private final Object touchWriteLock = new Object();

    /**
     * 把一帧写进 TOUCH 通道（必须在 touch 线程上调用）。
     *
     * <p>⚠️ socket **在执行的这一刻现取**，不能用入队时捕获的那个：断开/重连之后
     * 旧的 socket 已经关了，往它写只会失败；而调用方早就返回了 true。
     */
    private boolean writeTouchFrame(byte[] frame, String what) {
        Socket s = socks.get(5);
        if (s == null || s.isClosed()) {
            return false;
        }
        try {
            OutputStream out = s.getOutputStream();
            synchronized (touchWriteLock) {
                out.write(frame);
                out.flush();
            }
            return true;
        } catch (Throwable t) {
            log(what + "发送失败：" + t);
            return false;
        }
    }

    /**
     * 发车机硬键到手机（CarLife TOUCH 通道 {@code 0x00068008}）。
     *
     * <p>载荷 = protobuf {@code CarlifeCarHardKeyCode{ required int32 keycode = 1 }}。
     * 键码用 <b>CarLife 自己的表</b>（{@link HuProto.CarHardKey}），
     * <b>不是</b> Android 的 ADB keycode。
     *
     * <p>走的是 TOUCH 通道（和触摸同一条），所以复用触摸那条 HandlerThread，
     * 避免阻塞 UI 线程。
     *
     * @param carLifeKeycode CarLife 键码（见 {@link HuProto.CarHardKey}）
     * @return true = 已排队发送
     */
    public boolean sendHardKey(final int carLifeKeycode) {
        final Socket s = socks.get(5);
        if (s == null || !running) {
            log("硬键发送失败：TOUCH 通道没连上");
            return false;
        }
        // 「接听/挂断」是二义键（CarPlay 的 Telephony Hook Switch 0x20）：
        // 通话中 → 挂断；空闲 → 接听。用 CarLife 的 MODULE_STATUS 里的 PHONE 模块判活。
        int key = carLifeKeycode;
        if (key == HuProto.CarHardKey.PHONE_CALL && phoneModuleActive) {
            key = HuProto.CarHardKey.PHONE_END;
            log("车机按了接听键，但当前通话中 → 改发「挂断」");
        }
        final int sendKey = key;
        Handler h = touchHandler;
        if (h == null) {
            touchHandler = startTouchThread();
            h = touchHandler;
        }
        byte[] frame = HuProto.frame(HuProto.CH_TOUCH, HuProto.HU_CAR_HARD_KEY_CODE,
                HuProto.pbInt(1, sendKey));
        boolean posted = h.post(new Runnable() {
            @Override
            public void run() {
                if (writeTouchFrame(frame, "硬键")) {
                    log("→ 车机硬键 " + HuProto.CarHardKey.name(sendKey)
                            + "（keycode=" + sendKey + "）写入 " + frame.length + "B");
                }
            }
        });
        if (!posted) {
            log("硬键未入队（触摸线程已退出），丢弃本条");
            return false;
        }
        return true;
    }

    public boolean sendTouch(final int action, final int x, final int y) {
        final Socket s = socks.get(5);
        if (s == null || !running) {
            return false;
        }
        // ⚠️ socket 写是阻塞 IO，不能放在 UI 线程（会 NetworkOnMainThreadException）
        Handler h = touchHandler;
        if (h == null) {
            touchHandler = startTouchThread();
            h = touchHandler;
        }
        byte[] frame = HuProto.frame(HuProto.CH_TOUCH, HuProto.HU_TOUCH_ACTION,
                HuProto.touch(action, x, y));
        boolean posted = h.post(new Runnable() {
            @Override
            public void run() {
                if (!writeTouchFrame(frame, "触摸")) {
                    return;
                }
                // 限流 + 抽样：计数只在限流命中时涨，避免未命中时重复命中同一档
                long now = System.currentTimeMillis();
                if (now - lastTouchLogMs > 1500) {
                    lastTouchLogMs = now;
                    int n = ++touchWriteCount;
                    if (n <= 5 || n % 200 == 0) {
                        Socket cur = socks.get(5);
                        log("→ 触摸#" + n + " action=" + action + " (" + x + "," + y
                                + ") 写入 " + frame.length + "B CTRL(socks[5]="
                                + (cur == null || cur.isClosed() ? "closed" : "ok") + ")");
                    }
                }
            }
        });
        if (!posted) {
            return false;
        }
        return true;
    }

    /** 最近一次从视频通道读到任何数据（含 len==0 空帧）的时刻。 */
    private volatile long lastVideoRxAtMs;
    /** 视频静默告警的限流时刻（每 60s 最多一条）。 */
    private volatile long lastVideoWarnMs;

    /** 触摸写出确认日志的限流时刻与累计次数。 */
    private volatile long lastTouchLogMs;
    private int touchWriteCount;

    /**
     * 高层单击指令（0x00068005 + CarlifeTouchSinglePoint{x,y}）。
     * 用于试探真实 Car+ 是否只认专用/高层触摸消息，而非合并式的 0x00068001。
     */
    public boolean sendSingleClick(final int x, final int y) {
        final Socket s = socks.get(5);
        if (s == null || !running) {
            return false;
        }
        Handler h = touchHandler;
        if (h == null) {
            touchHandler = startTouchThread();
            h = touchHandler;
        }
        byte[] frame = HuProto.frame(HuProto.CH_TOUCH, HuProto.HU_TOUCH_SINGLE_CLICK,
                HuProto.singleClick(x, y));
        boolean posted = h.post(new Runnable() {
            @Override
            public void run() {
                if (writeTouchFrame(frame, "单击")) {
                    log("→ 单击指令 (" + x + "," + y + ") 写入 " + frame.length + "B");
                }
            }
        });
        if (!posted) {
            log("单击未入队（触摸线程已退出），丢弃本条");
            return false;
        }
        return true;
    }

    /** 触摸 Handler（UI 线程建、AirPlay 事件线程用）→ volatile。 */
    private volatile Handler touchHandler;
    private HandlerThread touchThread;

    private synchronized Handler startTouchThread() {
        if (touchHandler != null) return touchHandler;
        touchThread = new HandlerThread("hu-touch");
        touchThread.start();
        touchHandler = new Handler(touchThread.getLooper());
        return touchHandler;
    }

    private synchronized void stopTouchThread() {
        Handler h = touchHandler;
        touchHandler = null;
        if (h != null) h.removeCallbacksAndMessages(null);
        if (touchThread != null) {
            touchThread.quitSafely();
            touchThread = null;
        }
    }

    // ------------------------------------------------------------- 心跳

    /** 每秒一次 131074；不发手机侧会认为车机掉了。 */
    private void startHeartbeat() {
        final int gen = ++hbGen;
        new Thread(new Runnable() {
            @Override
            public void run() {
                int n = 0;
                int fails = 0;
                while (running && hbGen == gen) {
                    sleep(1000);
                    if (!running || hbGen != gen) {
                        return;
                    }
                    try {
                        // ⚠️ **心跳主通道是 VIDEO(8240)，不是 CMD(7240)**。
                        // 参考实现 WirelessCarLifePlus/src/session.cpp:1420：
                        //   if (state_ >= Established && tx_.channelOpen(ch::VIDEO))
                        //       tx_.send(ch::VIDEO, msg::VIDEO_HEARTBEAT);
                        // 手机侧模拟器 mdsim.py:751 也是在 VIDEO 通道收「车机视频通道心跳」。
                        // 以前只发 CMD → 8240 全程只读不写，手机按规范等不到车机心跳
                        // 就不推视频（session.cpp:398 收到 VIDEO 心跳才当链路活着），
                        // 进度页永远停在「编码器协商」、首帧永远不来（真机实测）。
                        boolean videoOk = send(1, HuProto.HU_HEARTBEAT, new byte[0]);
                        // CMD 侧保留一路：现有会话一直靠它保活，不因改动引入新变量
                        send(0, HuProto.HU_HEARTBEAT, new byte[0]);
                        // ⚠️ 心跳写失败必须拆连接触发重连——以前这里静默吞掉，
                        // 对端早已把通道踢了，本端却还以为在推流（锁屏断连的放大器）
                        if (!videoOk) {
                            fails++;
                            log("视频通道心跳写失败 ×" + fails + "，准备重连");
                            if (fails >= 3) {
                                hbGen++;                 // 让本线程退出
                                closeAll();              // 触发 session() 的重连循环
                                return;
                            }
                            continue;
                        }
                        fails = 0;
                        n++;
                        if (n == 1 || n == 30) {
                            log("心跳 ×" + n + "（VIDEO+CMD）");
                        }
                        // 协议看门狗：**判不判停，看对端有没有给出进展，不看到点没到点。**
                        //
                        // 收到 MD_MODULE_STATUS(0x10026) = 对端自报编码链路在跑 —— 有进展，
                        // 视频帧只是还没到，**继续等，别打断它**；
                        // 一条都没有 = 对端压根没开始，再等也不会有，这时才停并先诊断。
                        if (step == Step.ENCODER && firstFrameAt == 0 && encoderAtMs > 0) {
                            long waited = Math.max(0L, System.currentTimeMillis() - encoderAtMs);
                            boolean stalled = moduleSeen
                                    ? waited > PROGRESS_WAIT_MS
                                    : waited > NO_PROGRESS_WAIT_MS;
                            if (stalled) {
                                String diag = diagnose();
                                if (moduleSeen) {
                                    log("有进展（已收到模块状态）但 " + waited
                                            + "ms 仍无首帧，停：" + diag);
                                } else {
                                    log("对端从未上报模块状态（编码链路未启动），" + waited
                                            + "ms 判停：" + diag);
                                }
                                hbGen++;
                                closeAll();
                                return;
                            }
                        }
                    } catch (Throwable t) {
                        log("心跳失败：" + t);
                        return;
                    }
                }
            }
        }, "hu-heartbeat").start();
    }

    // ------------------------------------------------------------- 底层

    /**
     * 往第 slot 条通道发一包。
     *
     * @return true=写成功；false=通道为空或写失败（对端可能已断）
     */
    private synchronized boolean send(int slot, int serviceType, byte[] body) {
        Socket s = socks.get(slot);
        if (s == null) {
            return false;
        }
        try {
            OutputStream out = s.getOutputStream();
            byte[] framed = HuProto.frame(HuProto.CHANNELS[slot], serviceType, body);
            out.write(framed);
            out.flush();
            trace("TX", slot, serviceType, body);
            return true;
        } catch (Throwable t) {
            log("发送失败（service=" + serviceType + "）：" + t);
            return false;
        }
    }

        /**
     * 逐帧追踪：毫秒级时序 + 通道 + service id + 载荷字节。
     */
    /** 逐帧追踪开关。默认关：跑一天光这一项就占日志的 88%（实测 37k 行里 33k 行是它）。 */
    public static volatile boolean verboseTrace = false;

    private void trace(String dir, int slot, int service, byte[] body) {
        if (!verboseTrace) return;
        long ms = Math.max(0L, System.currentTimeMillis() - startedAt);
        int n = body == null ? 0 : body.length;
        String hex;
        if (n == 0) {
            hex = "";
        } else {
            StringBuilder sb = new StringBuilder();
            int show = Math.min(n, 64);
            for (int i = 0; i < show; i++) {
                sb.append(String.format("%02x", body[i] & 0xff));
            }
            if (n > show) {
                sb.append("..(").append(n).append("B)");
            }
            hex = " " + sb;
        }
        log(String.format("F +%dms %s %s svc=0x%08X len=%d%s",
                ms, dir, channelName(slot), service, n, hex));
    }

    private static byte[] readN(Socket s, int n) {
        try {
            return readExact(s, n);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读满 n 字节；抛出 SocketTimeoutException / IOException 交给调用方判断。 */
    private static byte[] readExact(Socket s, int n) throws java.io.IOException {
        if (s == null) {
            return null;
        }
        InputStream in = s.getInputStream();
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r <= 0) {
                return null;
            }
            off += r;
        }
        return buf;
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("HuClient", t);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void setState(State s, String detail) {
        // ⚠️ 已经停止/断开之后，旧会话线程仍可能在收尾时调 setState(HANDSHAKING/STREAMING)：
        //    以前会把界面从「已断开」拉回「正在投屏」，用户以为连接还活着。
        //    CLOSED 本身要放行（stop() 就是在 running=false 之后写的）。
        if (s != State.CLOSED && !running) return;
        state = s;
        sink.onState(s, detail);
    }

    private void enterStep(Step s) {
        if (step == s) return;
        step = s;
        if (s == Step.ENCODER) {
            encoderAtMs = System.currentTimeMillis();   // 看门狗计时起点
        }
        sink.onStep(s);
    }

    private void log(String line) {
        sink.onLog(line);
    }
}
