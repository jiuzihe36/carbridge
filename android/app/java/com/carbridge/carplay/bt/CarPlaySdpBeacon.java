package com.carbridge.carplay.bt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * CarPlay 入口的「认证」信标 —— 车机认我们靠的就是这里挂出来的 SDP 记录。
 */
public final class CarPlaySdpBeacon {

    /** 车机 Settings 判定「这是不是 iPhone」的唯一依据。 */
    public static final UUID APPLE_DEVICE_UUID =
            UUID.fromString(com.carbridge.carplay.CarProfile.APPLE_DEVICE_EIR_UUID);

    /**
     * iPhone 角色（发送端）的 iAP2 SDP UUID —— <strong>车机就是连这个服务</strong>。
     * 配件端（车机）用的是 {@code …decacaff}。
     */
    public static final UUID IPHONE_IAP2_UUID =
            UUID.fromString(com.carbridge.carplay.CarProfile.APPLE_IAP2_UUID);

        /**
     * 参考车机 车机实际连接的那个「笔误版」iAP2 UUID。
     */
    public static final UUID LEGACY_IAP2_UUID =
            UUID.fromString(com.carbridge.carplay.CarProfile.LEGACY_IAP2_UUID);

    /** 车机（配件端）的 iAP2 服务 UUID；主动连接时用。 */
    public static final UUID IPHONE_IAP2_ACCESSORY_UUID =
            UUID.fromString("00000000-deca-fade-deca-deafdecacaff");

    /**
     * 「Wireless iAP v2」的发送端 UUID。
     *
     * <p>CarWith &amp; CarPlay Bridge（com.baidu.carlife.xiaomi / 10021）和 OcarPlay
     * 都会挂这条记录；前者还会主动连配件端的 {@link #WIRELESS_IAP2_ACCESSORY_UUID}。
     * 真 iPhone 同时暴露这两组 iAP2 服务，车机可能从任意一组进来。
     */
    public static final UUID WIRELESS_IAP2_UUID =
            UUID.fromString("02030302-1d19-415f-86f2-22a2106a0a77");

    /**
     * 「Wireless iAP v2」的配件端 UUID —— 车机侧的服务。
     * 参考实现用 {@code createRfcommSocketToServiceRecord()} 主动连它。
     */
    public static final UUID WIRELESS_IAP2_ACCESSORY_UUID =
            UUID.fromString("02030302-1d19-415f-86f2-22a2106a0a78");

    /** 回调到 App 日志。 */
    public interface Log {
        void onLog(String line);
    }

    /** iAP2 连接的接收方（CarPlay 发送端）。 */
    public interface Iap2Sink {
        void onIap2Socket(BluetoothSocket socket);
    }

    private static final String SERVICE_NAME = "CarPlay";
    private static final String IAP2_SERVICE_NAME = "iAP2";
    private static final String LEGACY_IAP2_SERVICE_NAME = "iAP2 (Desay)";
    private static final String WIRELESS_IAP2_SERVICE_NAME = "Wireless iAP v2";

    /** 已注册的 SDP 记录（持有 server socket 才能让记录一直存在）。 */
    private static final List<BluetoothServerSocket> SERVERS = new ArrayList<BluetoothServerSocket>();
    /** 车机若真的连上来，把 socket 挂住别断，保持 SDP 记录有效。 */
    private static final List<BluetoothSocket> HELD = new ArrayList<BluetoothSocket>();
    /** 最多挂住多少个「只保持不应答」的 socket（信标 24/7 常驻，不能无限涨）。 */
    private static final int MAX_HELD = 4;

    private static volatile boolean started;
    /** 信标注册成功的时刻（用于估算 SDP 同步进度）。 */
    private static volatile long startedAtMs;
    /** 实际注册成功的 SDP UUID（自检必须按真实名单报，不能写死「已挂 4 条」）。 */
    private static final List<UUID> REGISTERED = new ArrayList<UUID>();

    /** 四条监听线程：用于「监听是否还活着」的自检（线程死掉时车机能连上但没人应答）。 */
    private static final java.util.List<Thread> ACCEPT_THREADS = new java.util.ArrayList<>();

    /** 当前的 iAP2 接收方（CarPlay 发送端启动时挂上来）。 */
    private static volatile Iap2Sink iap2Sink;
        /**
     * CarPlay 发送端是否正在等车机连接。
     */
    private static volatile boolean activeSessionWanted;
    /** 车机在发送端就绪之前就连上来了 —— 存着，等发送端起来时交出去。 */
    private static BluetoothSocket pendingIap2;
    /** 上面那个 socket 存进来的时间（太旧说明车机早走了，不能交出去）。 */
    private static long pendingIap2At;
    /** 暂存的 iAP2 socket 最多留这么久。 */
    private static final long PENDING_IAP2_MAX_AGE_MS = 120_000L;

    private CarPlaySdpBeacon() {
    }

    /**
     * 让本机在蓝牙 inquiry 里**可被发现**。
     *
     * <p>为什么需要：车机的 CarPlay app 是靠**经典蓝牙扫描**发现手机的
     * （{@code performWirelessCarplayScan}，从车机 cdex 的字符串表还原出来的流程）。
     * 安卓手机默认只在「设置 → 蓝牙」页面打开时可被发现；<strong>已配对设备例外</strong>
     * （仍会响应 inquiry）。
     *
     * <p>⚠️ Android 14+ 起 {@code setDiscoverable} 已从框架里<strong>整个删掉</strong>
     * （实测 {@code NoSuchMethodException}），所以这条路在新宿主上走不通 ——
     * 只能靠「已配对」或用户手动打开手机蓝牙设置页。方法保留：老系统上仍有用。
     *
     * @param seconds 可被发现的时间窗
     * @return true = 成功
     */
    public static boolean setDiscoverable(int seconds) {
        try {
            android.bluetooth.BluetoothAdapter adapter =
                    android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return false;
            java.lang.reflect.Method m = adapter.getClass()
                    .getMethod("setDiscoverable", int.class, android.os.Bundle.class);
            m.invoke(adapter, seconds, null);
            return true;
        } catch (Throwable t) {
            android.util.Log.i("carbridge", "setDiscoverable 失败: " + t);
            return false;
        }
    }

    public static boolean isStarted() {
        return started;
    }

    /**
     * 幂等启动。返回是否已成功挂上 SDP 记录。
     *
     * @param adapter 蓝牙适配器（可为 null）
     * @param log     日志出口（可为 null）
     */
    public static synchronized boolean start(BluetoothAdapter adapter, Log log) {
        if (started) {
            // started 只代表「曾经注册过」。监听线程死掉后 server socket 已经失效，
            // 但标志位还是 true —— 以前这里直接 return，导致信标永远起不来。
            if (acceptLoopsAlive() && REGISTERED.size() >= 3) return true;
            emit(log, "SDP 信标: 记录的 UUID 不全或监听线程已死 → 重新注册");
            reset();
        }
        if (adapter == null) {
            emit(log, "SDP 信标: 蓝牙适配器不可用");
            return false;
        }
        try {
            if (!adapter.isEnabled()) {
                emit(log, "SDP 信标: 蓝牙未开启，等开启后再注册");
                return false;
            }
            // ① 车机认「这是 iPhone」的标记
            BluetoothServerSocket server = adapter.listenUsingRfcommWithServiceRecord(
                    SERVICE_NAME, APPLE_DEVICE_UUID);
            SERVERS.add(server);
            REGISTERED.add(APPLE_DEVICE_UUID);
            // ② 车机真正会连过来的 iAP2 服务 —— 必须和 ① 同时在场
            BluetoothServerSocket iap2 = adapter.listenUsingRfcommWithServiceRecord(
                    IAP2_SERVICE_NAME, IPHONE_IAP2_UUID);
            SERVERS.add(iap2);
            REGISTERED.add(IPHONE_IAP2_UUID);
            // ③ 参考车机 的车机实际连接的是「笔误版」UUID：
            //    00000000-CADE-DEFA-DECA-DEAFDECACAFE。
            //    这条和标准 …deca-fade…CAFE 是两条独立的 SDP 记录，必须同时挂着；
            //    车机是客户端，只认它自己硬编码的那一条。注册失败说明这台车
            //    的核心入口不可用，直接走外层失败，不能假装已经就绪。
            BluetoothServerSocket desayIap2 = adapter.listenUsingRfcommWithServiceRecord(
                    LEGACY_IAP2_SERVICE_NAME, LEGACY_IAP2_UUID);
            SERVERS.add(desayIap2);
            REGISTERED.add(LEGACY_IAP2_UUID);
            // ④ 真 iPhone 还会暴露 Wireless iAP v2（02030302…0a77）。
            //    CarWith Bridge 常驻挂这条；车机从这一组进来时也要能接住。
            //    它不是车机 Settings 的判定项，注册失败（例如系统里已有同名记录）
            //    不能把前两条必需的记录一起拖下水，所以单独降级。
            BluetoothServerSocket wireless = null;
            try {
                wireless = adapter.listenUsingRfcommWithServiceRecord(
                        WIRELESS_IAP2_SERVICE_NAME, WIRELESS_IAP2_UUID);
                SERVERS.add(wireless);
                REGISTERED.add(WIRELESS_IAP2_UUID);
            } catch (Throwable t) {
                emit(log, "SDP 信标: Wireless iAP v2 记录可选，注册失败（继续用前三条）: " + t);
            }

            started = true;
            startedAtMs = System.currentTimeMillis();
            ACCEPT_THREADS.clear();
            // 车机 Settings 用的标记入口：空闲时只挂住；CarPlay 启动后也交给 iAP2。
            spawn(new AcceptLoop(server, log,
                    AcceptLoop.ROLE_HANDOFF_IF_ACTIVE, APPLE_DEVICE_UUID), "carplay-sdp-beacon");
            // 车机真正会连的 iAP2 服务：永远交给发送端（没启动时先暂存）。
            spawn(new AcceptLoop(iap2, log,
                    AcceptLoop.ROLE_HANDOFF, IPHONE_IAP2_UUID), "carplay-iap2-beacon");
            // 目标车机的笔误版入口：和标准 iAP2 一样，车机一连上就交给发送端。
            spawn(new AcceptLoop(desayIap2, log,
                    AcceptLoop.ROLE_HANDOFF, LEGACY_IAP2_UUID), "carplay-desay-iap2-beacon");
            // Wireless iAP v2：空闲时挂住，CarPlay 启动后也交给 iAP2。
            if (wireless != null) {
                spawn(new AcceptLoop(wireless, log,
                        AcceptLoop.ROLE_HANDOFF_IF_ACTIVE, WIRELESS_IAP2_UUID),
                        "carplay-wireless-iap2-beacon");
            }
            emit(log, "✓ CarPlay SDP 信标已注册（" + REGISTERED.size() + " 条）: "
                    + describeRegistered());
            emit(log, "  （车机配对那一次会一次性学到这些 UUID；CarWith Bridge 也常挂 02030302）");
            return true;
        } catch (Throwable e) {
            // ⚠️ 部分失败也要收干净：① 已注册的第一条记录如果留着，
            //    下次 start() 会再挂一条 → SDP 里出现重复记录，而且旧 server socket 泄漏。
            reset();
            emit(log, "✗ SDP 信标注册失败: " + e);
            return false;
        }
    }

    /**
     * 挂上 iAP2 接收方（CarPlay 发送端启动时调用）。
     *
     * <p>信标一直挂着这条 SDP 记录，所以车机随时能连过来；
     * 发送端没就绪时车机连上来的连接先存着，等发送端起来再交出去。
     */
    public static synchronized void attachIap2Sink(Iap2Sink sink) {
        iap2Sink = sink;
        activeSessionWanted = sink != null;
        // 发送端停了：保留暂存 socket，等下一次启动时再按年龄判断；
        // 这里不能把 pending 清空，否则 socket 既没交出去也没关闭。
        if (sink == null) return;
        BluetoothSocket pending = pendingIap2;
        long at = pendingIap2At;
        pendingIap2 = null;
        pendingIap2At = 0L;
        if (pending != null) {
            // 车机可能早就断开/走远了：太旧的 socket 交出去只会让新会话一上来就失败。
            if (System.currentTimeMillis() - at > PENDING_IAP2_MAX_AGE_MS) {
                emit(null, "CarPlay 发送端就绪，但暂存的车机 iAP2 连接已过期（"
                        + ((System.currentTimeMillis() - at) / 1000) + "s），丢弃等它重连");
                try {
                    pending.close();
                } catch (Throwable ignored) {
                }
                return;
            }
            emit(null, "CarPlay 发送端就绪，把车机早先连上的 iAP2 会话交出去");
            sink.onIap2Socket(pending);
        }
    }

    /** 起一条监听线程并登记（便于自检）。 */
    private static void spawn(AcceptLoop loop, String name) {
        Thread t = new Thread(loop, name);
        t.setDaemon(true);
        t.start();
        ACCEPT_THREADS.add(t);
    }

        /**
     * 监听线程是否全部存活。
     */
    public static synchronized boolean acceptLoopsAlive() {
        if (!started || ACCEPT_THREADS.size() < 3) return false;
        for (Thread t : ACCEPT_THREADS) {
            if (t == null || !t.isAlive()) return false;
        }
        return true;
    }

    /** 监听线程挂了就重新注册（幂等）。返回是否健康。 */
    public static synchronized boolean reviveIfDead(BluetoothAdapter adapter, Log log) {
        if (started && acceptLoopsAlive() && REGISTERED.size() >= 3) return true;
        emit(log, "⚠ SDP 信标不健康（线程死亡或 UUID 不全）→ 重新注册");
        reset();
        return start(adapter, log);
    }

    /** 蓝牙被关掉后记录会失效，重新开时调用。 */
    public static synchronized void reset() {
        for (BluetoothServerSocket s : SERVERS) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
        SERVERS.clear();
        REGISTERED.clear();
        ACCEPT_THREADS.clear();
        HELD.clear();
        if (pendingIap2 != null) {
            try {
                pendingIap2.close();
            } catch (Throwable ignored) {
            }
        }
        pendingIap2 = null;
        pendingIap2At = 0L;
        activeSessionWanted = false;
        started = false;
        startedAtMs = 0L;
    }

    /**
     * 识别自检：返回「车机能否认出本机」的诊断结果。
     *
     * <p>车机认 iPhone 的唯一判据是 {@code getUuids()} 里有 2d8d2466（CommonUtil.isSupportCarPlay），
     * 所以自检只回答三件事：蓝牙开了没、信标挂了没、挂的这 4 条 UUID 是不是齐的。
     * SDP 数据库有 2–5 分钟同步延迟，所以还附上「信标已就绪多久」，帮助判断是否要再等。
     */
    public static String diagnose(BluetoothAdapter adapter) {
        if (adapter == null || !adapter.isEnabled()) {
            return "✗ 蓝牙未开启。请先打开蓝牙，再重新打开本 App。";
        }
        if (!started) {
            return "✗ SDP 信标未注册。请重新打开本 App（或点「开始连接」）。";
        }
        long upSec = (System.currentTimeMillis() - startedAtMs) / 1000L;
        boolean threadsOk = acceptLoopsAlive();
        StringBuilder sb = new StringBuilder();
        sb.append("✓ 蓝牙已开启\n");
        sb.append("✓ SDP 信标已注册，运行 ").append(upSec).append(" 秒\n");
        sb.append("✓ 实际挂上 ").append(REGISTERED.size()).append(" 条记录：\n");
        for (UUID u : REGISTERED) {
            sb.append("   · ").append(label(u)).append("\n");
        }
        boolean mandatory = REGISTERED.contains(APPLE_DEVICE_UUID)
                && REGISTERED.contains(IPHONE_IAP2_UUID)
                && REGISTERED.contains(LEGACY_IAP2_UUID);
        if (!mandatory) {
            sb.append("✗ 关键记录不全（2d8d2466 / deca-fade / cade-defa 必须都在）\n");
        }
        sb.append(threadsOk ? "✓ 监听线程存活\n" : "✗ 监听线程已死（请重新打开 App）\n");
        sb.append("\n结论：");
        if (threadsOk && mandatory) {
            sb.append("信标正常。去车机：① 蓝牙里删掉本机 ② 重新配对 ③ 回桌面点「Apple CarPlay」。\n")
              .append("车机只在配对那一刻读 SDP；配对完之后再等，缓存不会自己补上。\n")
              .append("点了没反应：等 20 秒再点一次（车机要先查一遍蓝牙服务）；\n")
              .append("仍不行就先重启一次车机，再删双方配对重新配。");
        } else if (!threadsOk) {
            sb.append("监听线程已死，车机能连上但没人应答。请重新打开 App，再重新配对车机。");
        } else {
            sb.append("关键 UUID 没挂全，车机会认不出/连不上。请重新打开 App；仍然不全就重启手机蓝牙后再试。");
        }
        return sb.toString();
    }

    /** 自检里给 UUID 配一句人话，别让用户对着一串十六进制猜。 */
    private static String label(UUID u) {
        if (APPLE_DEVICE_UUID.equals(u)) return "2d8d2466（车机认 iPhone 的标记）";
        if (IPHONE_IAP2_UUID.equals(u)) return "00000000-deca-fade（标准 iAP2）";
        if (LEGACY_IAP2_UUID.equals(u)) return "00000000-cade-defa（参考车机笔误版）";
        if (WIRELESS_IAP2_UUID.equals(u)) return "02030302-1d19（Wireless iAP v2）";
        return u.toString();
    }

    /** 注册日志用：把实际挂上的 UUID 拼成一行。 */
    private static String describeRegistered() {
        StringBuilder sb = new StringBuilder();
        for (UUID u : REGISTERED) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(u);
        }
        return sb.toString();
    }

    private static void emit(Log log, String line) {
        android.util.Log.i("carbridge", line);
        if (log != null) log.onLog(line);
    }

    private static final class AcceptLoop implements Runnable {
        static final int ROLE_HANDOFF = 0;
        static final int ROLE_HANDOFF_IF_ACTIVE = 1;
        static final int ROLE_HOLD = 2;

        private final BluetoothServerSocket server;
        private final Log log;
        private final int role;
        private final UUID uuid;

        AcceptLoop(BluetoothServerSocket server, Log log, int role, UUID uuid) {
            this.server = server;
            this.log = log;
            this.role = role;
            this.uuid = uuid;
        }

        @Override
        public void run() {
            emit(log, "SDP 信标监听中 [" + uuid + "]");
            while (true) {
                try {
                    BluetoothSocket socket = server.accept();
                    if (socket == null) return;          // server 被关掉了
                    if (role == ROLE_HANDOFF
                            || (role == ROLE_HANDOFF_IF_ACTIVE && activeSessionWanted)) {
                        handoff(socket);
                    } else {
                        hold(socket);
                    }
                } catch (Throwable e) {
                    emit(log, "SDP 信标线程结束 [" + uuid + "]: " + e);
                    return;
                }
            }
        }

        /** 把车机连接交给发送端；发送端没起来就先暂存。 */
        private void handoff(BluetoothSocket socket) {
            Iap2Sink sink = iap2Sink;
            if (sink != null) {
                emit(log, "CarPlay iAP2: 车机从 " + uuid + " 连上来了，交给发送端");
                sink.onIap2Socket(socket);
                return;
            }
            synchronized (CarPlaySdpBeacon.class) {
                // 车机在发送端没起来时可能连多次：只留最新的一个，
                // 旧的必须关掉，否则 fd 泄漏 + 车机以为会话还活着。
                if (pendingIap2 != null) {
                    try {
                        pendingIap2.close();
                    } catch (Throwable ignored) {
                    }
                }
                pendingIap2 = socket;
                pendingIap2At = System.currentTimeMillis();
            }
            emit(log, "CarPlay iAP2: 车机已从 " + uuid + " 连上（发送端还没起来，先存着）");
        }

        /** 只挂住，不做任何应答（空闲时的 SDP 探测）。 */
        private void hold(BluetoothSocket socket) {
            synchronized (HELD) {
                // ⚠️ 信标是 24/7 常驻的：车机每探测一次就多挂一个 socket，
                // 而这些 socket 一直不关 → 慢速泄漏（fd + 内存）。
                // 只保留最近的若干个，多的立刻关掉。
                while (HELD.size() >= MAX_HELD) {
                    BluetoothSocket old = HELD.remove(0);
                    try {
                        if (old != null) old.close();
                    } catch (Throwable ignored) {
                    }
                }
                HELD.add(socket);            // 挂住，不做任何应答
            }
            emit(log, "SDP 信标: 车机探测到 " + uuid + "（仅保持）");
        }
    }
}
