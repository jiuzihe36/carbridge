package com.carbridge.carplay;

import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.os.Handler;
import android.os.Looper;

import com.carbridge.carplay.bt.AppleDeviceIe;
import com.carbridge.carplay.bt.BtEirSniffer;
import com.carbridge.carplay.bt.BtIap2Server;
import com.carbridge.carplay.bt.BtIdentity;
import com.carbridge.carplay.iap2.Iap2Csm;
import com.carbridge.carplay.iap2.Iap2LinkEngine;
import com.carbridge.carplay.iap2.Iap2SenderSession;
import com.carbridge.carplay.net.CtrlIntServer;
import com.carbridge.carplay.net.MdnsCarPlay;
import com.carbridge.carplay.net.WifiJoiner;
import com.carbridge.carplay.util.Bg;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * 无线 CarPlay 发送端的**顶层编排**：把各模块串成一条完整会话。
 */
public final class CarPlayWirelessTx {

    public interface Listener {
        void onState(State state);

        void onLog(String line);

        /** 发现车机 AirPlay 端点，可以交给 CarPlayBridge.connect()。 */
        void onCarPlayEndpoint(String host, int port);

        /** 进入某一步（该步之前视为已完成）。 */
        void onStep(Step step);

        /** 某一步失败。 */
        void onStepFailed(Step step, String reason);
    }

    public enum State {
        IDLE, LISTENING_BT, IAP2, JOINING_WIFI, DISCOVERING, AIRPLAY, ERROR
    }

    /** CarPlay 无线侧的细分步骤，UI 用它渲染进度。 */
    public enum Step {
        BT_LISTEN("蓝牙监听"),
        IAP2_LINK("iAP2 链路握手"),
        IAP2_IDENTIFY("识别 / MFi 认证"),
        WIFI_JOIN("加入车机热点"),
        MDNS("mDNS 发现"),
        AIRPLAY_SESSION("AirPlay 会话"),
        CARPLAY_STREAM("推流中");

        public final String label;

        Step(String label) {
            this.label = label;
        }
    }

    private final Context ctx;
    private final Listener listener;

    /** 协议核心里不放定时器（需求：状态机不能靠定时器驱动），超时由这一层驱动。 */
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 0x5703 兜底窗口：车机不给热点凭据（新协议把 AP 信息放 0x4301）也得往下走。 */
    private static final long WIFI_CONFIG_TIMEOUT_MS = 8000L;

        /**
     * 收到 0x5703 之后等这么久再入网。
     */
    private static final long WIFI_SETTLE_MS = 800L;

    /**
     * 入网后多久还没在 mDNS 上看到车机，就判定「可能连到了旧 AP」。
     *
     * <p>取值要够大：车机要等我们广播 ctrl 才发邀请，车机开 AirPlay 端点也有延迟，
     * 太短会误触发重连（白白多花几秒）。25s 是「车机明显不对劲」和「别乱重试」之间的折中。
     */
    private static final long MDNS_WATCHDOG_MS = 25000L;

    /** 入网重试上限（每次重试都先向车机重新要一次热点凭据）。 */
    private static final int WIFI_RETRY_MAX = 2;

    /** 0x5703 没来时，最多再等 EIR 扫描多久（毫秒）。 */
    private static final long EIR_WAIT_MS = 10000L;

    /** 车机连上但 iAP2 没进展时，多久后给一条告警（只告警，不重置）。 */
    private static final long IAP2_STALL_WARN_MS = 20000L;

    /** 监听线程自检周期（只检查线程存活，不做状态猜测）。 */
    private static final long LISTENER_CHECK_MS = 20000L;

    private BtIap2Server btServer;
    /** 车机识别探测（UI 开关；只影响蓝牙主动探测，不影响 iAP2/AirPlay 状态机）。 */
    private volatile boolean btProbeEnabled;
    private volatile Iap2SenderSession iap2;
    private WifiJoiner wifi;
    private MdnsCarPlay mdns;
    private CtrlIntServer ctrlServer;
    /** 扫车机 EIR 用（拿热点信息当冗余备份 + 拿 BSSID 对账）。 */
    private BtEirSniffer eirSniffer;
    /**
     * 按蓝牙地址存下来的 Apple Device IE。
     *
     * <p><strong>只用车机那一份</strong>：ACTION_FOUND 是普通广播（同 UID 之外也能伪造），
     * 拿别人的 EIR 去连热点 = 连到 rogue AP。所以按地址存，用的时候只取车机地址那条。
     */
    private final java.util.Map<String, AppleDeviceIe> eirByAddr =
            new java.util.concurrent.ConcurrentHashMap<String, AppleDeviceIe>();
    /** 车机地址（RFCOMM 对端 / 已配对设备），用来挑出车机那一份 EIR。 */
    private volatile String carBtAddress;
    /** 车机 EIR 里的热点信息（按地址挑出来的那份）。 */
    private AppleDeviceIe eirAp;
    /** 最近一次收到的热点凭据（0x5703）。 */
    private volatile Iap2SenderSession.WifiConfig pendingWifi;
    /** 入网重试次数。 */
    private int wifiRetry;
    /** 实际连上的 AP 的 BSSID。 */
    private String joinedBssid;
    /** 入网准备（mDNS 广播/发现）是否已执行过；重复回调不再重置状态。 */
    private volatile boolean networkPrepared;
    /** AirPlay 会话是否已建立（0x4301 端点拿到 → true；会话失败/结束时 false）。
     *  用它守护所有"延迟入网 / 重复凭据 / 重复入网回调"，避免把已经建立的会话降级。
     *  ⚠️ 写方是网络/会话回调线程，读方是主线程看门狗 —— 必须 volatile。 */
    private volatile boolean airplaySessionActive;
    /** mDNS 发现用的本地网段前缀（重连时复用）。 */
    private String airplayPrefix;
    /** 通话音频是否已经路由到车机（避免重复连 HFP）。 */
    private boolean callAudioRouted;
    private String realBtMac;

    private volatile State state = State.IDLE;
    private volatile Step step;
    private volatile BluetoothSocket activeSocket;

    /** 当前 CarPlay 步骤（UI 用）。 */
    public Step currentStep() {
        return step;
    }

    public CarPlayWirelessTx(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public State state() {
        return state;
    }

    /**
     * 开关「车机识别探测」。
     *
     * <p>开启后 {@link BtIap2Server} 会持续主动连车机的配件端 iAP2，
     * 直到车机回连/认证成功；关闭只停止探测，已经建立的会话不受影响。
     */
    public void setBtProbeEnabled(boolean enabled) {
        btProbeEnabled = enabled;
        BtIap2Server s = btServer;
        if (s != null) {
            s.setProbeEnabled(enabled);
        } else {
            log("蓝牙主动探测：" + (enabled ? "已记录，发送端启动后生效" : "关闭"));
        }
    }

    public boolean isBtProbeEnabled() {
        return btProbeEnabled;
    }

    // ================================================================== 生命周期

    public synchronized void start() {
        if (state != State.IDLE && state != State.ERROR) return;
        if (state == State.ERROR) {
            // ⚠️ 从 ERROR 重入必须先清上一轮的残留（与 stop() 的资源清理对称）：
            //    旧 btServer 仍在跑（会再回调 onCarConnected 破坏单会话不变量）、
            //    旧 iap2 的 authTimer 还活着、旧 RFCOMM socket 不关车机不再重连 ——
            //    以前直接盖过去，永久卡在「等待车机回连」。
            if (mdns != null) { mdns.shutdown(); mdns = null; }
            if (ctrlServer != null) { ctrlServer.stop(); ctrlServer = null; }
            if (wifi != null) { wifi.release(); wifi = null; }
            if (iap2 != null) { iap2.close(); iap2 = null; }
            if (btServer != null) { btServer.stop(); btServer = null; }
            BluetoothSocket stale = activeSocket;
            activeSocket = null;
            if (stale != null) {
                try {
                    stale.close();
                } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("CarPlayWirelessTx", t); }
            }
            airplaySessionActive = false;
            networkPrepared = false;
        }
        setState(State.LISTENING_BT);
        enterStep(Step.BT_LISTEN);
        BtIdentity.Resolved identity = BtIdentity.resolve(ctx);
        BtIdentity.remember(ctx, identity);
        realBtMac = identity.mac;
        log("本机蓝牙身份：name=" + btName() + " mac="
                + (realBtMac == null ? "（读不到）" : realBtMac + "（" + identity.source.label() + "）"));
        if (realBtMac == null) {
            log("⚠ 拿不到真实蓝牙 MAC：Android 11+ 应用层受限。"
                    + "ctrl mDNS 的 TXT id 会缺失 → 车机不会邀请 CarPlay。"
                    + "到「本机身份 → 蓝牙地址」手动填写一次（车机蓝牙列表里能看到它）。");
        }
        btServer = new BtIap2Server(new BtIap2Server.Listener() {
            @Override public void onLog(String msg) { log(msg); }
            @Override public void onConnected(BluetoothSocket socket) { onCarConnected(socket); }
            @Override public void onDisconnected(String reason) {
                log("蓝牙断开: " + reason);
                onLinkLost("蓝牙断开");
            }
        });
        // 先记录开关再启动：startActiveConnect() 会据此选择「两次尝试」或「持续探测」。
        btServer.setProbeEnabled(btProbeEnabled);
        btServer.start();
        log("已开始等待车机蓝牙回连");
        main.removeCallbacks(listenerWatchdog);
        main.postDelayed(listenerWatchdog, LISTENER_CHECK_MS);
        // ⚠️ EIR 扫描**不在这里起**：一次 inquiry 要占十几秒射频，
        // 而它只在「要热点凭据」时才有价值（0x5703 不到时的备份 + BSSID 对账）。
        // 等 iAP2 进入「索要 WiFi 配置」时再起（见 onState 回调）。
    }

        /**
     * 开始扫车机的 EIR。
     */
    private void startEirScan() {
        if (eirSniffer != null) return;
        // 车机地址已知（RFCOMM 对端）→ 传进去，扫完能明确回答「车机到底在不在扫描里」，
        // 而不是只报「没扫到 Apple Device IE」。
        final String carHint = carBtAddress != null ? carBtAddress : "";
        eirSniffer = new BtEirSniffer(ctx,
                new BtEirSniffer.Listener() {
                    @Override public void onLog(String line) { log(line); }
                    @Override public void onAppleDeviceIe(String name, String addr,
                            AppleDeviceIe ie) {
                        if (addr != null) {
                            eirByAddr.put(addr.toUpperCase(java.util.Locale.US), ie);
                        }
                        // 只有车机那一份才算数（防伪造的 ACTION_FOUND）
                        if (isCarAddress(addr)) {
                            eirAp = ie;
                            log("车机 EIR 的 Apple Device IE：" + ie);
                            // 还没拿到 0x5703 的话，EIR 里的信息可以让我们先入网
                            if (pendingWifi == null && ie.hasHotspot()) {
                                log("用 EIR 里的热点信息入网（0x5703 还没来）");
                                scheduleWifiJoin(0L);
                            }
                        }
                    }
                }, 35000L, carHint);
        eirSniffer.start();
    }

    /** 这个蓝牙地址是不是车机。 */
    private boolean isCarAddress(String addr) {
        if (addr == null || addr.isEmpty()) return false;
        if (carBtAddress != null && carBtAddress.equalsIgnoreCase(addr)) return true;
        try {
            android.bluetooth.BluetoothDevice car = com.carbridge.carplay.bt.CarPairing.findCar();
            return car != null && addr.equalsIgnoreCase(car.getAddress());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取出车机那一份 EIR（按地址挑，防止用别人伪造的 EIR）。 */
    private AppleDeviceIe eirOfCar() {
        if (carBtAddress != null) {
            AppleDeviceIe ie = eirByAddr.get(carBtAddress.toUpperCase(java.util.Locale.US));
            if (ie != null) return ie;
        }
        try {
            android.bluetooth.BluetoothDevice car = com.carbridge.carplay.bt.CarPairing.findCar();
            if (car != null) {
                return eirByAddr.get(car.getAddress().toUpperCase(java.util.Locale.US));
            }
        } catch (Throwable t) {
        }
        return eirAp;
    }

    /** 收掉 EIR 扫描（信息拿到了就别占着蓝牙射频）。 */
    private void stopEirScan() {
        if (eirSniffer != null) {
            eirSniffer.stop();
            eirSniffer = null;
        }
    }

    public synchronized void stop() {
        stopCallWatcher();
        // 退出车机模式时通话可能还在进行：不停止上行采集就等于麦克风一直被占用，
        // 而且 setVoiceCallMuted(true) 没还原 → 用户回到手机通话仍是静音。
        stopTelephonyUplink();
        setState(State.IDLE);
        main.removeCallbacks(wifiTimeout);
        main.removeCallbacks(wifiTimeoutFinal);
        main.removeCallbacks(iap2StallWatchdog);
        main.removeCallbacks(wifiJoinTask);
        main.removeCallbacks(mdnsWatchdog);
        main.removeCallbacks(listenerWatchdog);
        if (eirSniffer != null) { eirSniffer.stop(); eirSniffer = null; }
        eirAp = null;
        eirByAddr.clear();
        carBtAddress = null;
        pendingWifi = null;
        wifiRetry = 0;
        joinedBssid = null;
        // 会话标志要跟着停：留着 true 会让下一次「延迟入网/重复凭据」全被当成
        // 「已经在投屏」而跳过，表现是停了再开连不上热点。
        airplaySessionActive = false;
        networkPrepared = false;
        // 退出车机模式：把本机铃声音量还回去（静音过的话）
        com.carbridge.carplay.audio.CarAudioRouter.restoreRingVolume(ctx,
                new com.carbridge.carplay.audio.CarAudioRouter.Log() {
                    @Override
                    public void onLog(String line) {
                        log(line);
                    }
                });
        callAudioRouted = false;
        if (mdns != null) { mdns.shutdown(); mdns = null; }
        if (ctrlServer != null) { ctrlServer.stop(); ctrlServer = null; }
        if (wifi != null) { wifi.release(); wifi = null; }
        if (iap2 != null) { iap2.close(); iap2 = null; }
        if (btServer != null) { btServer.stop(); btServer = null; }
        // ⚠️ 必须真的关掉 RFCOMM socket：只置空的话车机会以为链路还在、不再重连
        BluetoothSocket sock = activeSocket;
        activeSocket = null;
        if (sock != null) {
            try {
                sock.close();
            } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("CarPlayWirelessTx", t);
            }
        }
    }

    // ================================================================== 蓝牙 → iAP2

    private void onCarConnected(BluetoothSocket socket) {
        // ⚠️ 必须记住 socket：链路断开时要真的关掉，否则车机认为链路还在、不会重连
        activeSocket = socket;
        try {
            android.bluetooth.BluetoothDevice peer = socket.getRemoteDevice();
            java.io.InputStream in = socket.getInputStream();
            java.io.OutputStream out = socket.getOutputStream();
            if (in == null || out == null) {
                // socket 已被对端关掉（没有流）→ 绝不能当会话用：
                // 否则 iAP2 一启动就在 send 上 NPE，而蓝牙层还认为「已有会话」。
                log("车机连接无效（socket 无输入/输出流），丢弃并继续等待");
                closeActiveSocket();
                if (btServer != null) btServer.sessionEnded();
                setState(State.LISTENING_BT);
                return;
            }
            onCarStream(in, out,
                    peer == null ? null : peer.getAddress(),
                    peer == null ? null : peer.getName());
        } catch (Throwable t) {
            log("iAP2 建立失败: " + describe(t));
            onLinkLost("iAP2 建立失败");
        }
    }

    /** 关掉当前 RFCOMM socket（幂等）。 */
    private void closeActiveSocket() {
        BluetoothSocket sock = activeSocket;
        activeSocket = null;
        if (sock != null) {
            try {
                sock.close();
            } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("CarPlayWirelessTx", t);
            }
        }
    }

    /** 异常摘要：类型 + 消息 + 前几帧（现场排查用）。 */
    private static String describe(Throwable t) {
        if (t == null) return "null";
        StringBuilder b = new StringBuilder();
        b.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        StackTraceElement[] st = t.getStackTrace();
        if (st != null) {
            for (int i = 0; i < st.length && i < 6; i++) {
                b.append(" <- ").append(st[i]);
            }
        }
        return b.toString();
    }

    /**
     * 会话入口：蓝牙直连（主动连接）和 :beacon 中继两条路共用。
     *
     * <p>:beacon 进程负责保持监听通道稳定；这里只拿到字节流与对端身份。
     */
    private void onCarStream(final InputStream in, final OutputStream out,
                             String peerAddr, String peerName) {
        // ⚠️ 推流中（airplaySessionActive）车机蓝牙回连是**设计内**场景（车机连上无线
        //    CarPlay 后会关蓝牙再开回来）：此时不能把 state 从 AIRPLAY 打回 IAP2，
        //    否则 onCarPlayEndpoint 的防重入被绕过 → 起第二条并发 AirPlay 会话 →
        //    车机把两条全 reset。
        if (!airplaySessionActive) {
            setState(State.IAP2);
        }
        enterStep(Step.IAP2_LINK);
        scheduleIap2StallWatchdog();
        // 每条新链路都是一次新机会：入网重试预算属于「本轮会话」，不该跨轮累计。
        wifiRetry = 0;
        joinedBssid = null;
        // 车机自己连过来的 → **这就是车机蓝牙身份最可靠的来源**
        // （名字/CoD 都会变：实测车机重新配对后 CoD 变成 0x001F00，靠名字/CoD 会认错）。
        if (peerAddr != null && !peerAddr.isEmpty()) {
            try {
                log("车机蓝牙已连上: " + (peerName == null || peerName.isEmpty() ? "?" : peerName)
                        + " [" + peerAddr + "]");
                com.carbridge.carplay.bt.CarPairing.rememberCarAddress(peerAddr);
                // 记下车机地址 → 之后只认这台设备的 EIR（防伪造）
                carBtAddress = peerAddr;
                // 通话音频交给这台设备的 HFP 通路
                android.bluetooth.BluetoothAdapter a =
                        com.carbridge.carplay.bt.Bt.adapter(CarPlayWirelessTx.this.ctx);
                if (a != null) {
                    android.bluetooth.BluetoothDevice d = a.getRemoteDevice(peerAddr);
                    if (d != null) routeCallAudioToCar(d);
                }
            } catch (Throwable t) {
                log("取车机蓝牙身份失败: " + t);
            }
        }
        try {
            Iap2LinkEngine.Transport transport = new Iap2LinkEngine.Transport() {
                @Override public void send(byte[] bytes) {
                    try {
                        synchronized (out) {
                            out.write(bytes);
                            out.flush();
                        }
                    } catch (Exception e) {
                        log("iAP2 发送失败: " + e);
                    }
                }
            };

            final Iap2SenderSession[] holder = new Iap2SenderSession[1];
            Iap2SenderSession session = new Iap2SenderSession(new Iap2SenderSession.Listener() {
                        @Override public void onIdentified() { markIdentifying(); }

                        @Override public void onState(Iap2SenderSession.State s) {
                            if (iap2 != holder[0]) return;   // 旧会话的迟到回调，忽略
                            log("iAP2: " + s);
                            if (s == Iap2SenderSession.State.AUTHENTICATING
                                    || s == Iap2SenderSession.State.REQUESTING_WIFI) {
                                enterStep(Step.IAP2_IDENTIFY);
                            }
                            if (s == Iap2SenderSession.State.REQUESTING_WIFI) {
                                scheduleWifiTimeout();
                                // 这时候才需要热点凭据 → 这时才起 EIR 扫描
                                // （0x5703 缺席时它是唯一的凭据来源；顺带拿 BSSID 对账）
                                startEirScan();
                                // 车机自报的身份（iAP2 识别信息）——比蓝牙名字可靠
                                Iap2SenderSession sess = iap2;
                                if (sess != null && sess.carModel() != null) {
                                    log("车机身份（iAP2 自报）：" + sess.carModel()
                                            + " / " + sess.carSerial());
                                }
                            }
                            if (s == Iap2SenderSession.State.AUTHENTICATING) {
                                // 挑战超时由 Iap2SenderSession 内部定时器驱动（带 token 幂等）。
                                // 这里以前还挂了一条 500ms 的外部看门狗 → 它一上来就
                                // retryChallenge()，把本档 3000ms 的等待砍成 500ms：
                                // 车机对 20B 的应答回来时 lastChallenge 已经换成 32B，验签必失败。
                                log("MFi 挑战已发出，等车机应答（超时由会话内部回退档位）");
                            }
                            if (s == Iap2SenderSession.State.ERROR) {
                                // 车机明确拒绝（识别/认证失败）→ 结束这一轮，等它重连
                                log("iAP2 会话失败，回到等待车机回连");
                                onLinkLost("iAP2 失败");
                            }
                        }
                        @Override public void onLog(String m) { log(m); }
                        @Override public void onWifiConfig(Iap2SenderSession.WifiConfig c) {
                            if (iap2 != holder[0]) return;
                            onWifiCredentials(c);
                        }
                        @Override public void onCarPlayEndpoint(Iap2SenderSession.Endpoint e) {
                            if (iap2 != holder[0]) return;
                            onEndpointFromIap2(e);
                        }
                    },
                    transport,
                    new Iap2LinkEngine.Listener() {
                        @Override public void onState(Iap2LinkEngine.State s) { log("iAP2 链路: " + s); }
                        @Override public void onMessage(Iap2Csm.Message m) { }
                        @Override public void onLog(String m) { }
                    });

            // 车机认的是「本机是谁」：蓝牙名 + 真实蓝牙 MAC。
            // 这两个值会出现在 0x4E09 / 0x4300 / 0x4E0E 里，车机会校验。
            //
            // ⚠️ 必须用**局部变量 session**，不能用字段 iap2：
            //    此刻 iap2 还是 null（上一轮 onLinkLost 清过），
            //    之前写成 iap2.setDeviceIdentity(...) 会让每个新会话都在这里 NPE，
            //    现场表现就是「车机连上了但 iAP2 一点反应都没有」。
            session.setDeviceIdentity(btName(), realBtMac);

            // ⚠️ 替换旧会话时必须把旧的真正关掉：它的 readLoop 醒来时 iap2 已指向新会话，
            //    `if (iap2 == session)` 不成立 → onLinkLost 不跑 → close() 永远不执行，
            //    每替换一次就漏一条 authTimer 线程 + 一份引擎状态。
            //    顺序不能反：先登记新会话，再关旧的（否则旧 readLoop 的 onLinkLost 会拆掉新 socket）。
            Iap2SenderSession prev = iap2;
            holder[0] = session;
            iap2 = session;
            if (prev != null && prev != session) {
                try {
                    prev.close();
                    log("旧的 iAP2 会话已关闭（被新会话替换）");
                } catch (Throwable tDiag) {
                    com.carbridge.carplay.util.Diag.w("CarPlayWirelessTx.replaceIap2", tDiag);
                }
            }
            session.start();
            readLoop(in, session);
            // ⚠️ 链路层握手（SYN/SYN|ACK → NORMAL）没有超时：半死 socket 上引擎永远
            //    停在 DETECTING、读循环永久阻塞，而车机自认为已连上不会再来。
            //    8 秒还没进 IDENTIFYING（onLinkUp 才置）就拆掉这条半死链路，等车机重连。
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (iap2 != session) return;   // 已被更新的会话替换，别误伤
                    if (session.state() == Iap2SenderSession.State.IDLE) {
                        log("⚠ iAP2 链路握手 8s 未完成 → 拆掉半死链路，等车机重连");
                        onLinkLost("iAP2 握手超时");
                    }
                }
            }, 8000);
        } catch (Throwable t) {
            // ⚠️ 这里必须走 onLinkLost（它会 sessionEnded + 关 socket），
            //    只置 ERROR 的话蓝牙层会一直认为「已有会话」→ 车机再连被静默丢弃。
            log("iAP2 建立失败: " + describe(t));
            onLinkLost("iAP2 建立失败");
        }
    }

    /** 本机蓝牙名（0x4E09 设备信息用；拿不到就报 "iPhone"）。 */
    private String btName() {
        try {
            android.bluetooth.BluetoothAdapter a =
                    com.carbridge.carplay.bt.Bt.adapter(this.ctx);
            if (a != null && a.getName() != null && !a.getName().isEmpty()) {
                return a.getName();
            }
        } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("CarPlayWirelessTx", t);
        }
        return "iPhone";
    }

    // ---------------------------------------------------------------- 兜底看门狗

    /** 0x5703 一直没来 → 照样发 0x4300，让车机在 0x4301 里给 AP 信息。 */
    private void scheduleWifiTimeout() {
        main.removeCallbacks(wifiTimeout);
        main.postDelayed(wifiTimeout, WIFI_CONFIG_TIMEOUT_MS);
    }

    private final Runnable wifiTimeout = new Runnable() {
        @Override
        public void run() {
            Iap2SenderSession s = iap2;
            if (s == null) return;
            if (s.state() != Iap2SenderSession.State.REQUESTING_WIFI) return;
            // ② EIR 冗余备份：0x5703 没来，但 EIR 里可能有热点信息。
            //    EIR 扫描还在跑就再等等它（一次 inquiry 约 12s），
            //    拿到了就用 EIR 的入网；扫完还没有才放弃。
            if (eirOfCar() == null && eirSniffer != null) {
                log("0x5703 超时未到，等 EIR 扫描的结果（最多再等 " + (EIR_WAIT_MS / 1000) + "s）");
                main.removeCallbacks(wifiTimeoutFinal);
                main.postDelayed(wifiTimeoutFinal, EIR_WAIT_MS);
                return;
            }
            AppleDeviceIe carEir = eirOfCar();
            if (carEir != null && carEir.hasHotspot()) {
                log("0x5703 超时未到，但 EIR 里有热点信息 → 用 EIR 的入网");
                s.onWifiConfigTimeout();     // 同时让车机走 0x4300/0x4301
                scheduleWifiJoin(0L);
                return;
            }
            s.onWifiConfigTimeout();
        }
    };

    /** 等 EIR 扫描的最终结果。 */
    private final Runnable wifiTimeoutFinal = new Runnable() {
        @Override
        public void run() {
            Iap2SenderSession s = iap2;
            if (s == null) return;
            if (s.state() != Iap2SenderSession.State.REQUESTING_WIFI) return;
            AppleDeviceIe carEir = eirOfCar();
            if (carEir != null && carEir.hasHotspot()) {
                log("EIR 里有热点信息 → 用 EIR 的入网");
                s.onWifiConfigTimeout();
                scheduleWifiJoin(0L);
                return;
            }
            log("EIR 也没扫到热点信息，0x5703 与 EIR 都没有凭据");
            s.onWifiConfigTimeout();
        }
    };

    /**
     * 车机连上了但 iAP2 一直没进展 → 告警。
     *
     * <p>⚠️ <b>只告警，不自动重置</b>：需求明确「不能一超时立刻重置」。
     * 这里给的是「现场看得见的解释」，而不是又一轮自动重试 ——
     * 真正断链时 {@link #onLinkLost} 会接管（那是车机真的走了）。
     */
    private void scheduleIap2StallWatchdog() {
        main.removeCallbacks(iap2StallWatchdog);
        main.postDelayed(iap2StallWatchdog, IAP2_STALL_WARN_MS);
    }

    /**
     * 监听自检：accept 线程一旦死掉，车机 connect() 仍会成功（内核队列还在），
     * 但没有人 accept → 「通道能打开、iAP2 一点反应也没有」。
     * 这里只检查**线程是否还活着**，不做任何基于时间的状态猜测。
     */
    private final Runnable listenerWatchdog = new Runnable() {
        @Override
        public void run() {
            if (state != State.LISTENING_BT) return;
            if (!com.carbridge.carplay.bt.CarPlaySdpBeacon.acceptLoopsAlive()) {
                android.bluetooth.BluetoothAdapter a =
                        com.carbridge.carplay.bt.Bt.adapter(CarPlayWirelessTx.this.ctx);
                boolean ok = com.carbridge.carplay.bt.CarPlaySdpBeacon
                        .reviveIfDead(a, new com.carbridge.carplay.bt.CarPlaySdpBeacon.Log() {
                            @Override public void onLog(String line) { log(line); }
                        });
                log(ok ? "SDP 信标已重新注册，继续等车机回连" : "SDP 信标重新注册失败，稍后再试");
            }
            main.postDelayed(this, LISTENER_CHECK_MS);
        }
    };

    private final Runnable iap2StallWatchdog = new Runnable() {
        @Override
        public void run() {
            if (state == State.IDLE || state == State.LISTENING_BT) return;
            if (state == State.AIRPLAY || state == State.DISCOVERING) return;
            log("⚠ 车机已连上但 iAP2 还没进展（已过 " + (IAP2_STALL_WARN_MS / 1000)
                    + "s）：看上面的日志确认卡在哪一步（识别 / MFi 挑战 / 索要热点配置）");
        }
    };

    private void readLoop(final InputStream in, final Iap2SenderSession session) {
        // 阻塞在 in.read() 上的读循环：对端不发数据就一直挂着，放池里免得每次重连新建线程
        Bg.run("wtx-iap2-read", new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[4096];
                while (state != State.IDLE && iap2 == session) {
                    try {
                        int n = in.read(buf);
                        if (n <= 0) break;
                        if (btServer != null) btServer.notifyRx();
                        byte[] chunk = new byte[n];
                        System.arraycopy(buf, 0, chunk, 0, n);
                        // ⚠️ 必须喂给**本会话**（闭包），不能读 iap2 字段：换代窗口里
                        //    旧 socket 来字节时 iap2 已指向新会话，旧链路的帧（序号体系
                        //    不同）会污染新会话状态机。旧会话引擎已 CLOSED，会自然丢弃。
                        session.onBytes(chunk);
                    } catch (Exception e) {
                        if (state != State.IDLE) log("iAP2 读失败: " + e);
                        break;
                    }
                }
                // 读循环退出 = 车机那边断了（或者 socket 被关）。
                // ⚠️ 只有「当前会话」才允许收尾：僵死会话被新连接替换时，
                //    旧读循环退出不能把新会话一起拆掉。
                if (iap2 == session) {
                    onLinkLost("iAP2 链路断开");
                }
            }
        });
    }

    /**
     * 车机侧的链路断了：把 iAP2 会话收掉，回到「等车机回连」。
     *
     * <p>蓝牙监听服务（{@code BtIap2Server}）一直开着，车机会自己重连，
     * 重连后 {@link #onCarConnected} 会起一个全新的 iAP2 会话。
     */
    private void onLinkLost(String reason) {
        if (state == State.IDLE) return;
        log("车机链路断开（" + reason + "）→ 回到等待车机回连");
        main.removeCallbacks(iap2StallWatchdog);
        // ⚠️ 关键：链路一断，把「延迟入网 / 热点兜底 / mDNS 看门狗」全部作废。
        //    否则 0x5703 排的那个 wifiJoinTask 会在链路断开后重新触发 JOINING_WIFI，
        //    把状态机从 LISTENING_BT 拖进 DISCOVERING → mDNS 空等死循环（现场卡在「mDNS 发现」）。
        main.removeCallbacks(wifiJoinTask);
        main.removeCallbacks(wifiTimeout);
        main.removeCallbacks(wifiTimeoutFinal);
        main.removeCallbacks(mdnsWatchdog);
        Iap2SenderSession s = iap2;
        iap2 = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("CarPlayWirelessTx", t);
            }
        }
        // ⚠️ **必须把 RFCOMM socket 真的关掉**：以前这里只把引用置空，
        //    车机那边看到链路还开着就不会重连（它会一直等我们发数据），
        //    我们这边也不会再收到 accept → 表现为「卡在等车机回连，永远等不到」。
        BluetoothSocket sock = activeSocket;
        activeSocket = null;
        if (sock != null) {
            try {
                sock.close();
            } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("CarPlayWirelessTx", t);
            }
        }
        // 允许蓝牙层接收下一路入站/出站连接（双通道只会有一个会话）。
        if (btServer != null) btServer.sessionEnded();
        // 新一轮链路要重新走一遍「把通话音频交给车机」——
        // 否则第一轮 HFP 没连上时，后面永远不再尝试（callAudioRouted 是粘的）。
        callAudioRouted = false;
        // ⚠️ 车机连上无线 CarPlay 后会主动关掉蓝牙，这时的链路断开**不代表投屏结束**：
        //    以前无条件 setState(LISTENING_BT)，把正在推流的终态改写成「等蓝牙回连」，
        //    之后 AirPlay 真挂了也没有任何恢复路径（onAirPlaySessionFailed 用 state 判据）。
        if (airplaySessionActive) {
            log("车机蓝牙链路断开，但 AirPlay 会话仍在 → 保持推流状态");
        } else {
            setState(State.LISTENING_BT);
            enterStep(Step.BT_LISTEN);
        }
        main.removeCallbacks(listenerWatchdog);
        main.postDelayed(listenerWatchdog, LISTENER_CHECK_MS);
    }

    // ================================================================== WiFi → mDNS → AirPlay

        /**
     * 收到 0x5703（车机给的热点凭据）。
     */
    private void onWifiCredentials(Iap2SenderSession.WifiConfig c) {
        // 车机可能重复下发 0x5703；AirPlay 会话已建立时再走一遍入网会把状态机
        // 降级回 DISCOVERING（UI 退回「mDNS 发现」+ 看门狗重复要凭据的死循环）。
        if (airplaySessionActive) {
            log("AirPlay 会话已建立，忽略重复热点凭据（保持会话不降级）");
            return;
        }
        pendingWifi = c;
        log("收到热点凭据：" + c
                + (eirOfCar() != null ? "；EIR 备份：" + eirOfCar() : ""));
        scheduleWifiJoin(WIFI_SETTLE_MS);
    }

    /** 安排入网。可重入：后到的凭据会顶掉先前的等待（避免用旧凭据入网）。 */
    private void scheduleWifiJoin(long delayMs) {
        main.removeCallbacks(wifiJoinTask);
        main.postDelayed(wifiJoinTask, delayMs);
    }

    private final Runnable wifiJoinTask = new Runnable() {
        @Override
        public void run() {
            // 0x5703 → 延迟入网（300ms+）。若期间 0x4301 已经把 AirPlay 会话拉起来，
            // 这个任务必须放弃——否则 JOINING_WIFI 会冲掉 AIRPLAY 状态，随后入网回调
            // 再把状态机降级回 DISCOVERING/MDNS（UI 看起来「推流中却退回 mDNS 发现」）。
            if (airplaySessionActive) {
                log("AirPlay 会话已建立，跳过延迟入网任务（保持会话）");
                return;
            }
            // 入网只对「iAP2 还活着」有意义。
            // ⚠️ 以前要求 state == IAP2 —— 但 AirPlay 失败后状态是 DISCOVERING，
            //    车机按 0x5702 回的 0x5703 到这里就被丢弃 → 重新入网永不执行，
            //    只能干等 mDNS。入网与「iAP2 链路握手态」不是一回事。
            // 链路真的断了（iap2 已清或回到 LISTENING_BT）才放弃，
            // 否则会把 LISTENING_BT 拖回 JOINING_WIFI→DISCOVERING→mDNS 空等。
            boolean joinable = state == State.IAP2 || state == State.DISCOVERING
                    || state == State.JOINING_WIFI;
            if (iap2 == null || !joinable) {
                log("iAP2 会话已结束，放弃过期入网任务（保持等车机回连）");
                return;
            }
            Iap2SenderSession.WifiConfig c = pendingWifi;
            String ssid = c != null ? c.ssid : null;
            String password = c != null ? c.password : null;
            int security = c != null ? c.security : 0;
            int channel = c != null ? c.channel : 0;

            // ── ② EIR 冗余备份：0x5703 缺什么，就用 EIR 里的补什么 ──
            //    （只用车机自己那一份，见 eirOfCar()）
            AppleDeviceIe carEir = eirOfCar();
            if (carEir != null) {
                if ((ssid == null || ssid.isEmpty()) && carEir.ssid != null) {
                    ssid = carEir.ssid;
                    log("0x5703 没给 SSID，用 EIR 里的补上");
                }
                if ((password == null || password.isEmpty())
                        && carEir.password != null && !carEir.password.isEmpty()) {
                    password = carEir.password;
                    log("0x5703 没给密码，用 EIR 里的补上");
                }
                if (carEir.ssid != null && c != null && c.ssid != null
                        && !carEir.ssid.equals(c.ssid)) {
                    log("注意：EIR 里的 SSID(" + carEir.ssid + ") 与 0x5703 的("
                            + c.ssid + ") 不一致 → 车机很可能刚重启过热点");
                }
            }

            if (ssid == null || ssid.isEmpty()) {
                log("拿不到车机热点 SSID（0x5703 与 EIR 都没有）");
                failStep(Step.WIFI_JOIN, "拿不到车机热点 SSID（0x5703 与 EIR 均无）");
                setState(State.ERROR);
                return;
            }

            // 0x5703 可能重复到达。上一个 joiner 的 specifier 回调若还挂着，Android 会因为
            // "同一应用同时只留一个 specifier 请求"把新的**直接**回 onUnavailable —— 那是误报。
            if (wifi != null) { wifi.release(); wifi = null; }
            setState(State.JOINING_WIFI);
            enterStep(Step.WIFI_JOIN);
            wifi = new WifiJoiner(ctx, new WifiJoiner.Listener() {
                @Override public void onLog(String msg) { log(msg); }
                @Override public void onNetworkBound(android.net.Network network,
                        String localIp, String bssid) {
                    onNetworkReady(localIp, bssid);
                }
                @Override public void onFailure(String reason) {
                    onWifiJoinFailed(reason);
                }
            });
            wifi.join(ssid, password, security, channel,
                    carEir != null ? carEir.bssid : null);
        }
    };

    /**
     * 入网失败 → 先向车机<strong>重新要一次</strong>热点凭据再试。
     *
     * <p>车机重启过热点时，我们手上的密码就是旧的 → 必然连不上；
     * 重新要一次（0x5702）拿到当前凭据才有机会。
     */
    private void onWifiJoinFailed(String reason) {
        log("入网失败: " + reason);
        if (wifiRetry < WIFI_RETRY_MAX) {
            log("向车机重新要一次热点凭据再试（第 " + (wifiRetry + 1) + " 次）");
            rejoinWifi("入网失败");
            return;
        }
        failStep(Step.WIFI_JOIN, reason);
        setState(State.ERROR);
    }

    /**
     * 重新向车机要热点凭据（0x5702），拿到新的 0x5703 后重新入网。
     *
     * <p>车机不一定回 —— 所以同时挂一个兜底：3 秒后直接用手上（可能是 EIR 里的）
     * 凭据再入网一次。
     */
    private void rejoinWifi(String reason) {
        wifiRetry++;
        joinedBssid = null;
        if (wifi != null) { wifi.release(); wifi = null; }
        Iap2SenderSession s = iap2;
        if (s != null) {
            s.requestWifiConfiguration();
        } else {
            // iAP2 已经断了：没有 0x5702 可发，也没有 0x4301 端点可拿，
            // 再兜底入网也是白等 mDNS。直接回「等车机蓝牙回连」重来。
            log("iAP2 已断，无法重新要凭据 → 回到等待车机回连");
            onLinkLost("重新入网时发现 iAP2 已断");
            return;
        }
        // 车机回 0x5703 → onWifiCredentials → scheduleWifiJoin（会顶掉这个兜底）
        // ⚠️ 先摘掉在途的同一任务：Handler 允许同实例重复入队，两个入网任务并存会让
        //    后到的 wifi.release() 把前一个进行中的 joiner 杀掉（抖动一次烧光重试预算）。
        main.removeCallbacks(wifiJoinTask);
        main.postDelayed(wifiJoinTask, 3000L);
        log("已请求新的热点凭据（" + reason + "），3 秒后兜底再入网");
    }

    private void onNetworkReady(String localIp, String bssid) {
        joinedBssid = bssid;
        // ── ① BSSID 对账：我们连的是不是车机那个 AP？──
        AppleDeviceIe carEir = eirOfCar();
        if (bssid != null && carEir != null && carEir.bssid != null) {
            if (carEir.bssid.equalsIgnoreCase(bssid)) {
                log("BSSID 一致（" + bssid + "），确认是车机的热点");
            } else {
                log("⚠ BSSID 对不上：我们连的是 " + bssid + "，车机 EIR 里播的是 "
                        + carEir.bssid + " → 车机很可能重启过热点（凭据可能是旧的）");
            }
        }
        boolean keepSession = airplaySessionActive;
        if (keepSession && networkPrepared) {
            log("AirPlay 会话已建立，忽略重复入网回调（不降级到 mDNS）");
            return;
        }
        networkPrepared = true;
        if (keepSession) {
            log("AirPlay 会话已建立，补做 mDNS 广播/发现但不降级状态");
        } else {
            setState(State.DISCOVERING);
            enterStep(Step.MDNS);
        }
        NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) {
            log("拿不到 NsdManager");
            failStep(Step.MDNS, "拿不到 NsdManager");
            setState(State.ERROR);
            return;
        }
        // ⚠️ 复用已有实例：每次都 new 会导致 ctrl 重复注册，产生 carbridge (2)/(3) 这类名字
        if (mdns == null) {
            mdns = new MdnsCarPlay(nsd, new MdnsCarPlay.Listener() {
                @Override public void onLog(String msg) { log(msg); }
                @Override public void onAirPlayEndpoint(String host, int port, Map<String, String> txt) {
                    onCarPlayEndpoint(host, port);
                }
            });
        }
        // ⚠️ ctrl 广播必须在 bindProcessToNetwork 之后（组播跟随绑定的网络）
        // ⚠️ ctrl-int 端口上**必须真的有人在听**：车机会反连
        //    GET /ctrl-int/1/connect 邀请我们，没 listener 它就拿不到邀请 → 双向死锁。
        if (ctrlServer == null) {
            ctrlServer = new CtrlIntServer(new CtrlIntServer.Listener() {
                @Override public void onLog(String line) { log(line); }
            });
        }
        int ctrlPort = ctrlServer.start(CtrlIntServer.DEFAULT_PORT);
        if (ctrlPort < 0) {
            ctrlPort = CtrlIntServer.DEFAULT_PORT;   // 起不来也要广播，让车机来连（会失败，但有日志）
        }
        // 本轮会话的版本号：TXT 与 RTSP/SETUP 用同一个值（改完设置不用重启 App）
        CarPlayCtrlCompat.apply(ctx);
        mdns.advertiseCtrl(ctrlPort, realBtMac, CarPlayCtrlCompat.srcvers(ctx));
        airplayPrefix = MdnsCarPlay.localPrefix(parseAddr(localIp));
        mdns.discoverAirPlay(airplayPrefix);
        // 入网完成 → 收掉 EIR 扫描（信息拿到了就别占着蓝牙射频）
        stopEirScan();
        // 看门狗：入网成功却在 mDNS 上迟迟看不到车机 → 多半是连到了旧 AP
        main.removeCallbacks(mdnsWatchdog);
        main.postDelayed(mdnsWatchdog, MDNS_WATCHDOG_MS);
    }

    /** mDNS 迟迟看不到车机 → 重新要凭据 + 重新入网（最多 WIFI_RETRY_MAX 次）。 */
    private final Runnable mdnsWatchdog = new Runnable() {
        @Override
        public void run() {
            if (state != State.DISCOVERING) return;   // 已经连上车机了
            if (wifiRetry >= WIFI_RETRY_MAX) {
                log("mDNS 一直没看到车机，重试次数用完（" + wifiRetry + "）");
                // 车机用 0x4301 直接给端点时**根本不广播 _airplay._tcp**，mDNS 等不到是正常的。
                // iAP2 还活着 → 留在这里等车机重新下发端点；
                // iAP2 也断了 → 整条链路都没了，回到「等车机蓝牙回连」，别卡死在 mDNS。
                if (iap2 == null) {
                    log("iAP2 链路已断 → 回到等待车机回连，不再空等 mDNS");
                    onLinkLost("mDNS 超时且 iAP2 已断");
                }
                return;
            }
            log("mDNS 上一直没看到车机 → 重新向车机要热点凭据并重新入网");
            rejoinWifi("mDNS 超时未见车机");
        }
    };

    private void onEndpointFromIap2(Iap2SenderSession.Endpoint e) {
        // 车机不发 0x4301 时不会走到这里（那时走 mDNS）
        onCarPlayEndpoint(e.host, e.port);
    }

    /** 连续失败后的等待：0 / 2s / 5s / 10s / 20s 封顶。 */
    private static long backoffMs(int streak) {
        switch (streak) {
            case 0:
            case 1: return 0;
            case 2: return 2000;
            case 3: return 5000;
            case 4: return 10000;
            default: return 20000;
        }
    }

    private void requestEndpointAgain() {
        Iap2SenderSession s = iap2;
        if (s == null) return;
        try {
            log("向车机重新请求热点配置/端点（0x5702）");
            s.requestWifiConfiguration();
        } catch (Throwable t) {
            log("重新请求端点失败: " + t);
        }
    }

    /** AirPlay 会话挂了（车机关了连接 / 心跳断了 / 密文流被打乱）。 */
    public void onAirPlaySessionFailed(String reason) {
        boolean wasActive = airplaySessionActive;
        airplaySessionActive = false;
        // ⚠️ 不能用 state 判断「要不要恢复」：车机连上无线 CarPlay 后会关掉蓝牙，
        //    onLinkLost 会把 state 改成 LISTENING_BT —— 那时 AirPlay 明明还在推流。
        //    以前这里 `if (state != AIRPLAY) return;` 于是整段恢复被吞：
        //    画面冻住、不重发 0x5702、UI 停在「蓝牙监听」，只能等车机自己开回蓝牙。
        // 标志位与 state 不一致时（理论上不该发生）仍按旧逻辑只处理 AIRPLAY 态
        if (!wasActive && state != State.AIRPLAY) return;
        if (state == State.IDLE) return;   // 用户已经手动停了，别自己爬起来
        log("AirPlay 会话结束（" + reason + "）→ 回到发现阶段重找端点");
        setState(State.DISCOVERING);
        enterStep(Step.AIRPLAY_SESSION);
        // iAP2 还活着的话，主动让车机重新下发端点：
        // 0x5702 → 车机 0x5703 → 我们 0x4300 → 车机 0x4301，比干等 mDNS 快得多。
        Iap2SenderSession s = iap2;
        if (s != null) {
            airplayFailStreak++;
            long delay = backoffMs(airplayFailStreak);
            if (delay > 0) {
                log("AirPlay 连续失败 " + airplayFailStreak + " 次 → 等 " + delay + "ms 再要端点（别追着车机打）");
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        requestEndpointAgain();
                    }
                }, delay);
            } else {
                requestEndpointAgain();
            }
        } else {
            // iAP2 已经断了：没有 0x5702 可发、也拿不到 0x4301 端点。
            // 车机用 0x4301 给端点时并不广播 _airplay._tcp，mDNS 只会空等。
            log("iAP2 已断，无法重新要端点 → 回到等待车机回连");
            onLinkLost("AirPlay 失败且 iAP2 已断");
            return;
        }
        if (mdns == null) {
            log("mDNS 还没起来，无法重新发现端点");
            return;
        }
        mdns.resetReported();
        if (airplayPrefix != null) {
            mdns.discoverAirPlay(airplayPrefix);
            log("已重新开始发现 _airplay._tcp");
        }
        // 看门狗：重连期间一直没端点 → 重新入网
        main.removeCallbacks(mdnsWatchdog);
        main.postDelayed(mdnsWatchdog, MDNS_WATCHDOG_MS);
    }

    private void onCarPlayEndpoint(String host, int port) {
        // ⚠️ mDNS 每次发现/刷新都会回调，不加防重入会同时起多个 AirPlaySession，
        // 车机会把并发连接全 reset（实测 4 个并发 → 全 Connection reset）。
        // ⚠️ 判据必须是 airplaySessionActive 而不只是 state：推流中车机蓝牙回连会把
        //    state 从 AIRPLAY 打回 IAP2（见 onCarStream），只看 state 防重入就失效。
        if (state == State.AIRPLAY || airplaySessionActive) {
            log("已在 AIRPLAY 阶段，忽略重复端点 " + host + ":" + port);
            return;
        }
        main.removeCallbacks(mdnsWatchdog);
        airplaySessionActive = true;
        setState(State.AIRPLAY);
        enterStep(Step.AIRPLAY_SESSION);
        log("得到车机 AirPlay 端点 " + host + ":" + port);
        // 车机在线了 → 把**通话音频**也交给它（BT HFP 通路）
        routeCallAudioToCar();
        listener.onCarPlayEndpoint(host, port);
    }

        /**
     * 把通话音频交给车机。
     */
    private void routeCallAudioToCar() {
        routeCallAudioToCar(com.carbridge.carplay.bt.CarPairing.findCar());
    }

        /**
     * 把通话音频交给车机。
     */
    private void routeCallAudioToCar(android.bluetooth.BluetoothDevice car) {
        if (car == null) {
            log("通话音频：没找到车机设备（先重新配对）");
            return;
        }
        if (callAudioRouted) return;
        // 只有真的把请求发出去才算「已处理」——失败要允许下一次（AirPlay 端点就绪时）重试。
        callAudioRouted = com.carbridge.carplay.audio.CarAudioRouter.connectCarHeadset(ctx, car,
                new com.carbridge.carplay.audio.CarAudioRouter.Log() {
                    @Override
                    public void onLog(String line) {
                        log(line);
                    }
                });
        if (!callAudioRouted) {
            log("通话音频：这一轮没发起 HFP（稍后拿到 AirPlay 端点时会再试）");
        }
    }

    /** 把音频路由模块的日志并进会话日志。 */
    private com.carbridge.carplay.audio.CarAudioRouter.Log audioLog() {
        if (audioLogAdapter == null) {
            audioLogAdapter = new com.carbridge.carplay.audio.CarAudioRouter.Log() {
                @Override public void onLog(String line) { log(line); }
            };
        }
        return audioLogAdapter;
    }

    private com.carbridge.carplay.audio.CarAudioRouter.Log audioLogAdapter;
    /** 直接监听系统音频模式来判断通话（Car+ 有时不上报）。 */
    private com.carbridge.carplay.audio.PhoneCallWatcher callWatcher;

    /** 通话音频采集（走 AirPlay telephony 流）。 */
    private com.carbridge.carplay.audio.CallAudioCapture callCapture;

    /**
     * 开 telephony 上行（手机 → 车机喇叭）。
     *
     * @return true = 已开始采集；false = 车机没有 telephony 流，调用方应退回 HFP
     */
    private boolean startTelephonyUplink() {
        // HFP 连上了就别再走 telephony：那条是「车机不给 HFP」时的备胎，
        // 两路一起送会让车机放两遍（回声）。SCO 本身就会让手机端静音。
        if (com.carbridge.carplay.audio.CarAudioRouter.isHfpConnected()) {
            log("通话音频：HFP 已连上车机 → 通话音频整路走 SCO（手机端自动静音，不开 telephony 流）");
            return true;
        }
        com.carbridge.carplay.core.AirPlaySession ap = airPlaySession();
        if (ap == null || !ap.hasTelephonyStream()) {
            log("通话音频：车机没有 telephony 音频流（/info 未声明 100/telephony）");
            return false;
        }
        if (callCapture == null) {
            callCapture = new com.carbridge.carplay.audio.CallAudioCapture(
                    new com.carbridge.carplay.audio.CallAudioCapture.Sink() {
                        @Override public void onPcm(byte[] pcm, int len, long ptsMs) {
                            com.carbridge.carplay.core.AirPlaySession s = airPlaySession();
                            if (s != null) s.submitTelephonyAudio(
                                    len == pcm.length ? pcm : java.util.Arrays.copyOf(pcm, len), ptsMs);
                        }
                    },
                    new com.carbridge.carplay.audio.CallAudioCapture.Log() {
                        @Override public void onLog(String line) { log(line); }
                    });
        }
        log("通话音频：走 AirPlay telephony 流（真 iPhone 的方式）");
        if (!callCapture.start(ctx)) {
            // ⚠️ 采不起来（没权限/格式不支持/被占用）就不要压本机通话音量、也不要
            //    谎报已路由 —— 返回 false 让调用方回落到 HFP，否则这通电话两头都可能没声。
            log("⚠ 通话采集没起来（权限/格式/占用）→ 本通电话改用 HFP 备选");
            return false;
        }
        // 系统仍然会把通话声从听筒放出来，一并压掉，否则「车机和手机同时出声」。
        // 只在采集真的成功后才压：失败时压掉就没人能出声了。
        com.carbridge.carplay.audio.CarAudioRouter.setVoiceCallMuted(ctx, true, audioLog());
        return true;
    }

    private void stopTelephonyUplink() {
        if (callCapture != null) {
            callCapture.stop();
        }
        com.carbridge.carplay.audio.CarAudioRouter.setVoiceCallMuted(ctx, false, audioLog());
    }

    /** 取当前 AirPlay 会话（bridge 里握着的那个）。 */
    private com.carbridge.carplay.core.AirPlaySession airPlaySession() {
        return com.carbridge.carplay.core.AirPlaySession.current();
    }

    /**
     * 连续 AirPlay 失败次数：用于退避。
     *
     * <p>车机在抖动（车机自己重启 / 网络顿一下）时会连着失败好几次；
     * 每次都立刻发 0x5702 重新要端点，等于追着车机打，反而拖长它恢复的时间。
     */
    private int airplayFailStreak;

    /** 上一次通话状态：用于去重（同一次通话会被回调多次）。 */
    private boolean callStateKnown;
    private boolean lastCallState;

    /**
     * 上报通话状态给车机（iAP2 {@code 0x4155 CallStateUpdate}）。
     *
     * <p>车机的 CarPlay app 收到后会 {@code sendMessageToCarlan} 把 CarPlay 的通话状态
     * 转进车机的 CarLife 层（{@code CarPlayCarLanManager}）—— 车机自己的 HMI 和
     * 音频路由就是跟着这个状态走的。
     */
    public void reportCallState(boolean active) {
        // CarLife 的监听会为同一次通话回调多次；不去重的话音频诊断会连打两遍
        // （真机日志里「通话结束」那几行就是这么重复的）。
        if (callStateKnown && lastCallState == active) return;
        callStateKnown = true;
        lastCallState = active;
        Iap2SenderSession s = iap2;
        if (s == null) return;
        s.setPhoneCallActive(active);
        // 通话开始 → 若之前 HFP 没连上，再试一次把通话音频交给车机。
        // 否则现场就是「打电话时手机发音」（HFP 在会话建立时被拒、又没人重试）。
        if (active) {
            // 通话开始：先打一次音频现场（HFP 连没连、SCO 在不在、系统把谁当通话设备），
            // 再按「首选 telephony 流 → 备选 HFP」的顺序把声音送出去。
            com.carbridge.carplay.audio.CarAudioRouter.describeRoute(ctx, audioLog());
            if (startTelephonyUplink()) {
                // telephony 流可用：这是真 iPhone 走的路，优先它，不再去抢 HFP
            } else if (!callAudioRouted) {
                log("车机没有 telephony 音频流 → 退回尝试 HFP");
                routeCallAudioToCar();
            }
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    com.carbridge.carplay.audio.CarAudioRouter.describeRoute(ctx, audioLog());
                }
            }, 1500);
        } else {
            stopTelephonyUplink();
            // 通话结束：还原默认通话设备，并把还原后的现场也打出来 ——
            // 「打完电话掉线」时有这一行才能判断是路由没还原还是车机自己断的。
            com.carbridge.carplay.audio.CarAudioRouter.clearCommunicationDevice(ctx, audioLog());
            callAudioRouted = false;
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    com.carbridge.carplay.audio.CarAudioRouter.describeRoute(ctx, audioLog());
                    log("通话结束后的会话状态：" + state);
                }
            }, 1500);
        }
    }

    // ================================================================== 内部

    private static java.net.InetAddress parseAddr(String host) {
        if (host == null) return null;
        try {
            return java.net.InetAddress.getByName(host);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 状态变化 → 同步推进进度条。
     *
     * <p>⚠️ 以前只有 {@link Step#CARPLAY_STREAM} 有 enterStep 调用，其余步骤
     * （链路握手 / 识别 / 热点 / mDNS / AirPlay 会话）**从来没人推进**，
     * 于是进度页永远停在「蓝牙监听 进行中」，剩下全是「待开始」——
     * 明明已经连上、甚至已经在推流，界面却显示卡在第一步。
     *
     * <p>这里把 State 映射成 Step（状态驱动，不引入定时器）。
     */
    private void setState(State s) {
        if (state == s) return;
        state = s;
        switch (s) {
            case LISTENING_BT:  enterStep(Step.BT_LISTEN); break;
            case IAP2:          enterStep(step == Step.BT_LISTEN ? Step.IAP2_LINK : step); break;
            case JOINING_WIFI:  enterStep(Step.WIFI_JOIN); break;
            case DISCOVERING:   enterStep(Step.MDNS); break;
            case AIRPLAY:       enterStep(Step.AIRPLAY_SESSION); break;
            default: break;      // IDLE / ERROR 不推进（ERROR 由 failStep 负责）
        }
        listener.onState(s);
    }

    /** iAP2 进入识别阶段（收到车机身份）→ 推进到「识别 / MFi 认证」。 */
    public void markIdentifying() {
        if (state == State.IAP2 || state == State.LISTENING_BT) {
            enterStep(Step.IAP2_IDENTIFY);
        }
    }

    private void enterStep(Step s) {
        if (step == s) return;
        step = s;
        listener.onStep(s);
    }

    private void failStep(Step s, String reason) {
        listener.onStepFailed(s, reason);
    }

    /**
     * AirPlay 会话进入 STREAMING → 推进到终态步「推流中」。
     *
     * <p>{@link Step#CARPLAY_STREAM} 以前只有声明、没有任何 enterStep 调用，
     * 于是进度页那行永远是 ○ 待开始，整体进度也卡在 14/15（与真卡住无法区分）。
     */
    public void markStreaming() {
        enterStep(Step.CARPLAY_STREAM);
        airplayFailStreak = 0;      // 推流成功 → 退避清零
        startCallWatcher();
    }

    /**
     * 开始监听系统通话状态。
     *
     * <p>不依赖 Car+ 上报：实测它有时根本不发通话事件，导致「通话开始」分支不执行，
     * 声音就一直留在手机上。音频模式变化是最可靠的信号。
     */
    private void startCallWatcher() {
        if (callWatcher != null) return;
        callWatcher = new com.carbridge.carplay.audio.PhoneCallWatcher(ctx,
                new com.carbridge.carplay.audio.PhoneCallWatcher.Listener() {
                    @Override public void onCallActive(boolean active) {
                        // 与 CarLife 上报走同一条处理链，reportCallState 内部会去重
                        reportCallState(active);
                    }
                },
                new com.carbridge.carplay.audio.PhoneCallWatcher.Log() {
                    @Override public void onLog(String line) { log(line); }
                });
        callWatcher.start();
    }

    private void stopCallWatcher() {
        if (callWatcher != null) {
            callWatcher.stop();
            callWatcher = null;
        }
    }

    private void log(String m) {
        listener.onLog(m);
    }
}
