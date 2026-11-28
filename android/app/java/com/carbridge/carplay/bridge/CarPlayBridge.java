package com.carbridge.carplay.bridge;

import com.carbridge.carplay.core.AirPlayInfo;
import com.carbridge.carplay.core.AirPlaySession;
import com.carbridge.carplay.core.SplashFrame;
import com.carbridge.carplay.core.VideoPackets;
import com.carbridge.carplay.iap2.Iap2Ids;

/**
 * carbridge 桥：把 CarLife（手机 Car+）与 CarPlay（车机）两侧接起来。
 */
public final class CarPlayBridge implements AirPlaySession.Listener {

    public interface Sink {
        void onState(String state);

        void onLog(String line);

        /**
         * AirPlay 会话**失败**（不是正常关闭）。
         *
         * <p>用于让上层回到「发现」阶段重找端点：端点只报一次，
         * 没有这个出口的话 CarPlay 会永远卡在 AIRPLAY 状态、再也连不上。
         */
        void onAirPlayFailed(String reason);

        /** 车机触摸，已换算成 Car+ 投屏分辨率下的像素坐标。 */
        void onCarPlayTouch(int action, int x, int y);

        /**
         * 车机硬键（CarLife 键码，见 {@code HuProto.CarHardKey}）。
         *
         * <p>车机的方向盘/中控按键在 CarPlay 里走 HID 通路，翻译成 CarLife 的
         * {@code MSG_TOUCH_CAR_HARD_KEY_CODE} 发给手机，Car+ 再注入成系统按键事件。
         */
        void onCarHardKey(int carLifeKeycode);

        /** AirPlay 会话已进 STREAMING——画面真正开始往车机推了。 */
        void onCarPlayStreaming();

        /**
         * 车机 /info 声明的显示画布和最大帧率。
         *
         * <p>旧实现可以不覆盖；生产 UI 用它在 Car+ 启动前/运行中调整编码画布。
         */
        default void onCarPlayDisplay(int width, int height, int maxFps) {
        }
    }

    /** CarLife 的触摸动作值（与 HuProto.ACT_* 一致）。 */
    public static final int ACT_DOWN = 0;
    public static final int ACT_UP = 1;
    public static final int ACT_MOVE = 2;

    private final Sink sink;

    /** 跨线程：connect 在 NSD/iAP2 回调线程、disconnect 在主线程、submitVideo 在
     *  hu-video 线程 —— 无 volatile 读方可长期看到旧值（新会话不可见 = 黑屏静默 return）。 */
    private volatile AirPlaySession session;
    private AirPlayInfo info;
    private SplashFrame splash;

    /** Car+ 实际投屏分辨率（来自 MD_VIDEO_ENCODER_INIT）。 */
    private volatile int streamW = 1920;
    private volatile int streamH = 720;

    /** 上一次触摸状态，用于把 down/up 折叠成一次事件。 */
    private boolean lastDown;
    private int lastX = -1;
    private int lastY = -1;

    public CarPlayBridge(Sink sink) {
        this.sink = sink;
    }

    /** 车机要求关键帧时回调（由 MainActivity 接到 HuClient.requestKeyframe）。 */
    private volatile Runnable keyFrameRequester;

    public void setKeyFrameRequester(Runnable r) {
        this.keyFrameRequester = r;
    }

    @Override
    public void onForceKeyFrame() {
        Runnable r = keyFrameRequester;
        if (r != null) r.run();
    }

    public boolean isRunning() {
        AirPlaySession s = session;   // 局部拷贝：与主线程 disconnect 竞态时二次解引用会 NPE
        return s != null && s.state() == AirPlaySession.State.STREAMING;
    }

    public void setStreamSize(int w, int h) {
        if (w > 0 && h > 0) {
            streamW = w;
            streamH = h;
            log("Car+ 投屏分辨率 = " + w + "x" + h);
        }
    }

    // ------------------------------------------------------------------ 生命周期

    /** 连到车机的 AirPlay 端点（来自 iAP2 的 0x4301，或 mDNS 发现的 _airplay._tcp）。 */
    public void connect(String host, int port) {
        disconnect();
        session = new AirPlaySession(host, port, this);
        // 触摸折叠状态跟着会话走：旧会话丢了 UP 时（lastDown=true），新会话的第一个
        // 同坐标 DOWN 会被折叠成「状态没变」直接丢掉 —— 车机丢一次点按。
        lastDown = false;
        lastX = -1;
        lastY = -1;
        // 会话建立前抓到的 SPS+PPS 必须先灌进去，否则会话内等不到 PPS、发不出配置
        byte[] avcC = cachedAvcC;
        if (avcC != null) {
            session.setCodecConfig(avcC, cachedW, cachedH);
        }
        // Car+ 还没给帧时，用品牌开屏兜底（避免车机黑屏）
        if (splash != null) {
            session.setSplash(splash);
        }
        session.start();
        sink.onState("CarPlay: 连接 " + host + ":" + port);
    }

    /** 注入品牌开屏画面生成器（可选，null 则不显示开屏）。 */
    public void setSplash(SplashFrame splash) {
        this.splash = splash;
    }

    public void disconnect() {
        AirPlaySession s = session;
        session = null;
        if (s != null) {
            s.stop();
        }
    }

    // ------------------------------------------------------------------ 数据面

    /** 会话建立**之前**抓到的解码配置（Car+ 只在流开头发一次 SPS+PPS）。 */
    private volatile byte[] cachedAvcC;
    private volatile int cachedW;
    private volatile int cachedH;

    /** 从 HuClient.onVideo 调用：一帧 H.264（Annex-B）推给车机。 */
    public void submitVideo(byte[] data, int len, int ptsMs) {
        AirPlaySession s = session;
        // HuClient 传的是读缓冲，len 之后是脏数据，必须裁剪
        byte[] frame = data;
        if (data.length != len) {
            frame = new byte[len];
            System.arraycopy(data, 0, frame, 0, len);
        }

        // ⚠️ **必须在会话判空之前抓配置**：CarPlay 会话往往在 CarLife 首帧之后才建立，
        // 而 Car+ 只在流开头发一次 SPS+PPS。会话为 null 时直接 return 会把配置永久丢掉，
        // 之后全是 P 帧 → 车机拿不到 avcC → 解码器不启动 → 黑屏（真机实测）。
        byte[] avcC = VideoPackets.avcCFromAnnexB(frame);
        if (avcC != null) {
            cachedAvcC = avcC;
            int[] d = VideoPackets.spsDimensions(frame);
            if (d != null) {
                cachedW = d[0];
                cachedH = d[1];
            }
            if (s != null) {
                s.setCodecConfig(avcC, cachedW, cachedH);   // 会话内也会记录，便于重发
            }
            // ⚠️ 继续把这一帧下发（SPS+PPS 帧通常还带 IDR，解码器起步最需要它）
        }

        if (s == null) return;
        s.submitVideo(frame, ptsMs);
    }

    /**
     * 从 HuClient.audioLoop 调用：一段手机侧音频载荷 → 转给车机。
     *
     * <p>⚠️ 与视频同理，HuClient 传的是读缓冲，{@code len} 之后是脏数据，必须裁剪。
     */
    public void submitAudio(byte[] data, int len, int ptsMs) {
        AirPlaySession s = session;
        if (s == null) return;
        byte[] pcm = data;
        if (data.length != len) {
            pcm = new byte[len];
            System.arraycopy(data, 0, pcm, 0, len);
        }
        s.submitAudio(pcm, ptsMs);
    }

        /**
     * 提交**通话声**（CarLife MEDIA 里、PHONE 模块活跃时的那段音频）。
     */
    public void submitTelephonyAudio(byte[] data, int len, int ptsMs) {
        AirPlaySession s = session;
        if (s == null) return;
        byte[] pcm = data;
        if (data.length != len) {
            pcm = new byte[len];
            System.arraycopy(data, 0, pcm, 0, len);
        }
        s.submitTelephonyAudio(pcm, ptsMs);
    }

    // ------------------------------------------------------------------ AirPlaySession.Listener

    @Override
    public void onState(AirPlaySession.State state) {
        sink.onState("CarPlay: " + state);
        // 终态步「推流中」此前全工程没有任何 enterStep 调用，进度页永远停在
        // 「AirPlay 会话 ●」、流向图也进不了 DONE（carPlayStep 判终态判不上）。
        if (state == AirPlaySession.State.STREAMING) {
            sink.onCarPlayStreaming();
        }
        // 失败（不是正常 stop 的 CLOSED）→ 通知上层重连
        if (state == AirPlaySession.State.ERROR) {
            sink.onAirPlayFailed("AirPlay 会话进入 ERROR");
        }
    }

    @Override
    public void onLog(String msg) {
        sink.onLog(msg);
    }

    @Override
    public void onTouch(float normX, float normY, boolean down) {
        // 归一化 0..1 → Car+ 投屏分辨率
        int x = Math.max(0, Math.min(streamW - 1, Math.round(normX * (streamW - 1))));
        int y = Math.max(0, Math.min(streamH - 1, Math.round(normY * (streamH - 1))));

        int action;
        if (down && !lastDown) {
            action = ACT_DOWN;
        } else if (!down && lastDown) {
            action = ACT_UP;
        } else if (down && (x != lastX || y != lastY)) {
            action = ACT_MOVE;
        } else {
            return; // 状态与坐标都没变，不发
        }
        lastDown = down;
        lastX = x;
        lastY = y;
        // 前后各打几条 + 每 100 条一条：高频触摸不刷屏，但「现在还有没有在转交」
        // 必须看得出来（只打首条会让锁屏/断链排查完全失去依据）。
        long nf = touchForwarded++;
        if (nf < 5 || nf % 100 == 0) {
            log("★ 车机触摸已转交 Car+ #" + (nf + 1) + " action=" + action + " (" + x + "," + y
                    + ") stream=" + streamW + "x" + streamH);
        }
        // 动作分布计数：DOWN/UP/MOVE 都要发得出去——只发 DOWN 不发 UP，车机侧不会 dispatchGesture
        if (action == ACT_DOWN) {
            actDown++;
        } else if (action == ACT_UP) {
            actUp++;
        } else {
            actMove++;
        }

        // ── 手势轨迹：DOWN 记起点，UP 打完整轨迹，用于判定滑动是否真的发出去、幅度够不够 ──
        if (action == ACT_DOWN) {
            gStartX = x;
            gStartY = y;
            gMoveCnt = 0;
            gStartMs = System.currentTimeMillis();
        } else if (action == ACT_MOVE) {
            gMoveCnt++;
        } else if (action == ACT_UP) {
            long dur = Math.max(0L, System.currentTimeMillis() - gStartMs);
            int dx = x - gStartX;
            int dy = y - gStartY;
            // 位移 <3px 基本是点按；滑动要有明显位移，否则车机侧看不出是 swipe
            String kind = (Math.abs(dx) >= 20 || Math.abs(dy) >= 20) ? "滑动" : "点按";
            // 触摸已验证通过，手势轨迹只作抽样留痕：前 5 条全打，之后每 100 条一条
            gTotal++;
            if (gTotal <= 5 || gTotal % 100 == 0) {
                log("→ 手势#" + gTotal + "[" + kind + "] (" + gStartX + "," + gStartY + ") → ("
                        + x + "," + y + ") Δ(" + dx + "," + dy + ") move=" + gMoveCnt
                        + " 时长=" + dur + "ms");
            }
        }

        // 动作分布是低频汇总：30s 一条即可（2s 一条在正常操作时刷屏）
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastActLogMs > 30_000) {
            lastActLogMs = nowMs;
            log("→ 触摸动作分布 down=" + actDown + " up=" + actUp + " move=" + actMove
                    + "（lastDown=" + lastDown + "）");
        }
        sink.onCarPlayTouch(action, x, y);
    }

    /** 手势轨迹统计（DOWN 起点 / MOVE 计数，UP 时汇总打日志）。 */
    private int gStartX;
    private int gStartY;
    private int gMoveCnt;
    private long gStartMs;
    /** 手势总数（日志抽样用：前 5 条全打，之后每 100 条一条）。 */
    private int gTotal;

    /** 已转发的车机触摸数（首条日志用）。 */
    private int touchForwarded;
    /** 动作分布计数（诊断：确认 DOWN/UP/MOVE 都发得出去）。 */
    private int actDown;
    private int actUp;
    private int actMove;
    private long lastActLogMs;

    @Override
    public void onHardKey(int usage) {
        // CarPlay 的 HID usage → CarLife 的硬键码（两张表完全不同，见 CarHardKeyMap）
        int carLife = CarHardKeyMap.toCarLifeKeycode(usage);
        if (carLife <= 0) {
            log("车机硬键 usage=0x" + Integer.toHexString(usage) + "：没有对应的 CarLife 键码，丢弃");
            return;
        }
        log("车机硬键 usage=0x" + Integer.toHexString(usage) + " → CarLife 键码 "
                + carLife + "（" + com.carbridge.carplay.proto.HuProto.CarHardKey.name(carLife) + "）");
        sink.onCarHardKey(carLife);
    }

    @Override
    public void onDisplaySize(int width, int height) {
        AirPlaySession s = session;
        AirPlayInfo i = s != null ? s.info() : null;
        AirPlayInfo.Display d = i != null ? i.primaryDisplay() : null;
        int maxFps = d != null ? d.maxFps : 0;
        onDisplaySize(width, height, maxFps);
    }

    @Override
    public void onDisplaySize(int width, int height, int maxFps) {
        info = session != null ? session.info() : null;
        log("车机 CarPlay 显示 " + width + "x" + height + " maxFPS=" + maxFps
                + (info != null && !info.hidDevices().isEmpty()
                ? " HID 设备 " + info.hidDevices().size() + " 个" : ""));
        sink.onCarPlayDisplay(width, height, maxFps);
    }

    private void log(String m) {
        sink.onLog(m);
    }

    /** 供调试：当前会话的 CarPlay 显示信息。 */
    public String describe() {
        AirPlaySession s = session;
        if (s == null) return "未连接";
        AirPlayInfo i = s.info();
        AirPlayInfo.Display d = i != null ? i.primaryDisplay() : null;
        return d == null ? "无显示信息" : d.toString();
    }

    /** 供 UI 显示：CarPlay 阶段提示。 */
    public static String stageOf(AirPlaySession.State s) {
        switch (s) {
            case CONNECTING: return "握手";
            case SETTING_UP: return "协商";
            case STREAMING: return "推流中";
            case ERROR: return "出错";
            case CLOSED: return "已断开";
            default: return "未开始";
        }
    }

    /** iAP2 侧拿到 WiFi 凭据后的下一步提示（留给 UI）。 */
    public static String wifiHint(String ssid, int channel, int security) {
        return "车机热点 " + ssid + "（信道 " + channel + "，安全 " + security + "）";
    }

    /** 供 UI 显示：当前处于哪一段 CarPlay 协议。 */
    public static String phaseHint(int phase) {
        switch (phase) {
            case 0: return "iAP2 链路";
            case 1: return "识别";
            case 2: return "MFi 认证";
            case 3: return "WiFi 凭据";
            case 4: return "AirPlay";
            default: return "未知";
        }
    }

    /** 未使用的占位，保持与 Iap2Ids 的引用关系清晰。 */
    static int unused() {
        return Iap2Ids.CARPLAY_START_SESSION;
    }
}
