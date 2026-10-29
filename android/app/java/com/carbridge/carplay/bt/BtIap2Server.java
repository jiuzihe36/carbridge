package com.carbridge.carplay.bt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.ParcelUuid;

import com.carbridge.app.CarPairing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 无线 CarPlay 的蓝牙 RFCOMM 传输层。
 */
public final class BtIap2Server {

    /** iPhone 侧（发送端）监听的 SDP UUID。 */
    public static final UUID IPHONE_SDP_UUID =
            UUID.fromString("00000000-deca-fade-deca-deafdecacafe");

    public interface Listener {
        void onLog(String msg);

        /** 车机连上来了，给出这个会话的输入输出流（不要在这里做耗时操作）。 */
        void onConnected(BluetoothSocket socket);

        void onDisconnected(String reason);
    }

    private final Listener listener;
    private volatile boolean running;
    private volatile BluetoothSocket active;
    /** 一轮只允许一个 RFCOMM 会话：入站/出站可能同时连上，先到先得。 */
    private final Object sessionLock = new Object();
    private volatile boolean sessionOpen;
    /** 当前会话建立时刻：用于判定「半死会话」（对端掉线但没有 FIN）。 */
    private volatile long sessionStartedAt;
    /** 当前会话是否收到过车机的任何字节（用来区分「真在握手」和「哑会话」）。 */
    private volatile boolean sessionReceivedBytes;
    /** 最近一次收到车机字节的时刻 —— 用它判断会话是不是「还活着」。 */
    private volatile long lastRxAt;

    /**
     * 车机识别探测开关。
     *
     * <p>车机不把手机当 iPhone 时，手机可以反过来主动连车机的配件端 iAP2 服务。
     * 这不会替代监听；它只是多一条“先碰一下车机”的路径，车机因此刷新 SDP /
     * 进入 iAP2 后，再走正常的认证。OcarPlay 的 active fallback 也是这个思路。
     */
    private volatile boolean probeEnabled;
    private volatile Thread probeThread;
    /** 探测中正在 connect 的临时 socket；关闭探测时用它打断阻塞的 connect。 */
    private volatile BluetoothSocket probeSocket;
    private static final long PROBE_INTERVAL_MS = 2500L;
    private static final long PROBE_CONNECT_TIMEOUT_MS = 9000L;

    public BtIap2Server(Listener listener) {
        this.listener = listener;
    }

    /** 开关车机识别探测。关闭只打断探测，不影响已经建立的 iAP2 会话。 */
    public void setProbeEnabled(boolean enabled) {
        probeEnabled = enabled;
        listener.onLog(enabled
                ? "蓝牙主动探测：开启（每 2.5s 尝试车机配件端，直到回连/认证）"
                : "蓝牙主动探测：关闭");
        if (!running) return;
        if (enabled) {
            startProbeLoop();
        } else {
            stopProbeNow();
        }
    }

    public boolean isProbeEnabled() {
        return probeEnabled;
    }

        /**
     * 真实蓝牙 MAC。**多级回退**（Android 11+ 应用层拿不到）： <ol> <li>隐藏 API {@code BluetoothAdapter.getAddress()}（旧系统有效）</li> <li>{@code Settings.Secure.bluetooth
     * _address}（部分机型可读）</li> <li>{@code Settings.Global.bluetooth_address}</li> </ol> 都拿不到返回 null——此时 ctrl mDNS 的 TXT id 会缺失，车机可能不邀请。
     */
    public static String realBluetoothAddress(Context ctx) {
        // 1) 隐藏 API
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a != null) {
                String mac = a.getAddress();
                if (isRealMac(mac)) return mac;
            }
        } catch (Throwable ignored) {
        }
        // 2) Settings.Secure
        String mac = readSetting(ctx, true);
        if (isRealMac(mac)) return mac;
        // 3) Settings.Global
        mac = readSetting(ctx, false);
        return isRealMac(mac) ? mac : null;
    }

    private static String readSetting(Context ctx, boolean secure) {
        if (ctx == null) return null;
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            return secure
                    ? android.provider.Settings.Secure.getString(cr, "bluetooth_address")
                    : android.provider.Settings.Global.getString(cr, "bluetooth_address");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 过滤占位值与非法值。 */
    private static boolean isRealMac(String mac) {
        if (mac == null || mac.isEmpty()) return false;
        if ("02:00:00:00:00:00".equals(mac)) return false;
        return mac.matches("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}");
    }

        /**
     * 启动发送端。
     */
    public void start() {
        if (running) return;
        running = true;
        sessionOpen = false;
        listener.onLog("CarPlay iAP2：发送端就绪（监听 + 主动连接双通道）");
        CarPlaySdpBeacon.attachIap2Sink(new CarPlaySdpBeacon.Iap2Sink() {
            @Override
            public void onIap2Socket(BluetoothSocket socket) {
                deliver(socket, "车机回连");
            }
        });
        startActiveConnect();
    }

    public void stop() {
        running = false;
        sessionOpen = false;
        probeEnabled = false;
        stopProbeNow();
        CarPlaySdpBeacon.attachIap2Sink(null);
        closeQuietly(active);
        active = null;
    }

    /**
     * 当前 iAP2 会话结束（链路断开 / iAP2 失败）时由发送端调用。
     * 清掉“已占用”标记，允许下一轮入站或出站连接。
     */
    /** 发送端每收到车机字节就调用一次（只用于判断会话是否真的在跑）。 */
    public void notifyRx() {
        sessionReceivedBytes = true;
        lastRxAt = System.currentTimeMillis();
    }

    public void sessionEnded() {
        synchronized (sessionLock) {
            sessionOpen = false;
            active = null;
        }
        // 认证/链路失败后，探测还开着的话继续碰车机；不再依赖定时器猜状态。
        if (running && probeEnabled) {
            startProbeLoop();
        }
    }

    /**
     * 收下一个 RFCOMM socket。入站和出站是并行的，只允许第一个会话：
     * 另一路晚到的 socket 直接关掉，避免车机看到两个 iAP2 链路。
     */
    private void deliver(BluetoothSocket socket, String source) {
        if (socket == null) return;
        // ⚠️ accept 到的 socket 可能**已经被对端关掉**（车机会并发开好几条，其中一部分立刻关闭；
        //    积压队列里的旧连接也会这样）。这种 socket 的 getInputStream/getOutputStream
        //    返回 null，后面一 send 就 NPE，而 sessionOpen 已经置 true →
        //    之后车机再连进来全被当成「重复连接」静默丢弃 = 现场「连上但没反应」。
        //    所以：拿不到流的 socket 根本不算会话，直接关掉继续等。
        try {
            if (!socket.isConnected()
                    || socket.getInputStream() == null
                    || socket.getOutputStream() == null) {
                listener.onLog("丢弃无效的 " + source + " 连接（socket 已断/无流）");
                closeQuietly(socket);
                return;
            }
        } catch (Throwable t) {
            listener.onLog("丢弃异常的 " + source + " 连接：" + t);
            closeQuietly(socket);
            return;
        }
        BluetoothSocket old = null;
        synchronized (sessionLock) {
            if (!running) {
                closeQuietly(socket);
                return;
            }
            if (sessionOpen) {
                // ⚠️ 车机掉线时不一定有 FIN：读循环会一直阻塞，sessionOpen 永远是 true，
                //    车机再连进来就会被静默忽略（现场表现就是「连上但没反应」）。
                long now = System.currentTimeMillis();
                long age = now - sessionStartedAt;
                // 判定旧会话是否已死（车机掉线时不一定有 FIN）：
                //  ① 收到过数据 → 看「多久没收到数据」：10s 没有任何入向字节才算僵死。
                //     ⚠️ 不能用「建立时长」判断：AirPlay 阶段会话可能已经跑了几分钟，
                //        车机再开一条连接不该把正在用的会话拆掉。
                //  ② 一个字节都没收到 → 1.5s 就判定是死 socket（车机并发开的、
                //     随后被它自己关掉的那种），让它的重试能被接住。
                //     1.5s 的门槛是为了不误杀「同一瞬间并发开的几条」里的第一条。
                boolean noTraffic = !sessionReceivedBytes;
                long idle = noTraffic ? age : (now - lastRxAt);
                long limit = noTraffic ? 1_500L : 10_000L;
                if (idle > limit) {
                    listener.onLog("旧 iAP2 会话" + (noTraffic ? "未收到任何数据"
                            : "已 " + (idle / 1000) + "s 无入向数据") + " → 判定僵死，替换");
                    // ⚠️ 只记下旧 socket、**先不关**：等新会话接管（onCarStream 把 iap2
                    //    指到新会话）之后再关，否则旧读循环此刻醒来会看到 iap2 还是自己、
                    //    于是触发 onLinkLost → sessionEnded 把刚登记的新会话 bookkeeping 冲掉。
                    old = active;
                } else {
                    listener.onLog("忽略重复的 " + source + " 连接（已有 iAP2 会话）");
                    closeQuietly(socket);
                    return;
                }
            }
            sessionOpen = true;
            sessionStartedAt = System.currentTimeMillis();
            lastRxAt = sessionStartedAt;
            sessionReceivedBytes = false;
            active = socket;
        }
        BluetoothDevice dev = null;
        try {
            dev = socket.getRemoteDevice();
        } catch (Throwable ignored) {
        }
        String name = dev == null ? "?" : String.valueOf(dev.getName());
        String addr = dev == null ? "?" : dev.getAddress();
        listener.onLog(source + "成功 " + name + " [" + addr + "]");
        try {
            listener.onConnected(socket);
        } catch (Throwable t) {
            synchronized (sessionLock) {
                sessionOpen = false;
                active = null;
            }
            closeQuietly(socket);
            closeQuietly(old);
            listener.onDisconnected("onConnected 异常: " + t);
            return;
        }
        closeQuietly(old);
    }

        /**
     * 主动连车机的配件端 iAP2 服务。
     */
    private void startActiveConnect() {
        if (probeEnabled) {
            // 诊断模式：持续主动连车机（默认关闭）
            startProbeLoop();
            return;
        }
        // ⚠️ 这辆车（参考车机）是**车机主动连手机**。手机反向 paging 只会白占射频；
        //    车机暂时不可达时还会把 RFCOMM 栈卡死（历史故障：之后所有入站连接都收不到）。
        //    所以默认只挂监听、等车机回连，不做任何反向连接尝试。
        listener.onLog("等待车机回连（不做反向连接）");
    }

        /**
     * 持续主动碰车机，直到车机回连/进入 iAP2，或用户关闭探测。
     */
    private void startProbeLoop() {
        Thread old = probeThread;
        if (old != null && old.isAlive()) return;
        if (!running || !probeEnabled) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                probeLoop();
            }
        }, "carplay-bt-probe");
        t.setDaemon(true);
        probeThread = t;
        t.start();
    }

    private void stopProbeNow() {
        Thread t = probeThread;
        probeThread = null;
        BluetoothSocket s = probeSocket;
        probeSocket = null;
        closeQuietly(s);
        if (t != null) t.interrupt();
    }

    private void probeLoop() {
        int round = 0;
        listener.onLog("蓝牙主动探测：循环开始（每轮 " + PROBE_INTERVAL_MS
                + "ms，先探 …caff / 02030302…0a78 / CADE）");
        while (running && probeEnabled && !sessionOpen) {
            BluetoothDevice car = CarPairing.findCar();
            if (car == null) {
                listener.onLog("蓝牙主动探测：没有已配对车机，3 秒后重试（先去系统蓝牙配对）");
                if (!sleepQuietly(3000)) return;
                continue;
            }
            round++;
            listener.onLog("蓝牙主动探测第 " + round + " 轮：" + safeName(car));
            try {
                boolean started = car.fetchUuidsWithSdp();
                listener.onLog("  车机 SDP 重查：" + (started ? "已发起" : "被拒绝/忙"));
            } catch (Throwable t) {
                listener.onLog("  车机 SDP 重查失败：" + t.getClass().getSimpleName());
            }
            logCarUuids(car);

            boolean connected = false;
            connected = probeConnect(car, CarPlaySdpBeacon.IPHONE_IAP2_ACCESSORY_UUID);
            if (!connected && running && probeEnabled && !sessionOpen) {
                connected = probeConnect(car, CarPlaySdpBeacon.WIRELESS_IAP2_ACCESSORY_UUID);
            }
            if (!connected && running && probeEnabled && !sessionOpen) {
                // 目标车机固件把手机侧 CADE 当客户端入口；也试一次，
                // 万一车机的蓝牙栈同时挂了这条服务，能提前把链路接起来。
                connected = probeConnect(car, CarPlaySdpBeacon.LEGACY_IAP2_UUID);
            }
            if (connected || !running || !probeEnabled || sessionOpen) break;
            if (!sleepQuietly(PROBE_INTERVAL_MS)) break;
        }
        if (probeThread == Thread.currentThread()) {
            probeThread = null;
        }
        listener.onLog("蓝牙主动探测：循环结束（"
                + (!running ? "发送端已停" : sessionOpen ? "已进入 iAP2" : "开关关闭") + "）");
    }

    /**
     * 单次主动连接。connect() 没有超时，所以另起一个看门狗线程，9 秒还没连上就
     * close 掉 socket，让阻塞的 connect 抛异常退出；否则关探测时线程会卡死。
     */
    private boolean probeConnect(final BluetoothDevice car, final UUID uuid) {
        if (!running || !probeEnabled || sessionOpen) return false;
        BluetoothSocket socket = null;
        final AtomicBoolean connected = new AtomicBoolean(false);
        Thread watchdog = null;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                try {
                    adapter.cancelDiscovery();
                } catch (Throwable ignored) {
                }
            }
            socket = car.createRfcommSocketToServiceRecord(uuid);
            final BluetoothSocket guarded = socket;
            watchdog = new Thread(new Runnable() {
                @Override
                public void run() {
                    if (!sleepQuietly(PROBE_CONNECT_TIMEOUT_MS)) return;
                    if (!connected.get()) {
                        closeQuietly(guarded);
                    }
                }
            }, "carplay-bt-probe-timeout");
            watchdog.setDaemon(true);
            watchdog.start();
            probeSocket = socket;
            listener.onLog("  → 主动连 " + shortUuid(uuid));
            socket.connect();
            connected.set(true);
            probeSocket = null;
            if (!running || !probeEnabled || sessionOpen) {
                closeQuietly(socket);
                return false;
            }
            listener.onLog("  ✓ 主动连上 " + shortUuid(uuid) + "，交给 iAP2");
            deliver(socket, "主动探测 " + shortUuid(uuid));
            return sessionOpen;
        } catch (Throwable e) {
            closeQuietly(socket);
            probeSocket = null;
            if (running && probeEnabled && !sessionOpen) {
                listener.onLog("  ✗ " + shortUuid(uuid) + " 未连上："
                        + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " " + e.getMessage()));
            }
            return false;
        } finally {
            if (watchdog != null) watchdog.interrupt();
        }
    }

    private void logCarUuids(BluetoothDevice car) {
        try {
            ParcelUuid[] uuids = car.getUuids();
            if (uuids == null || uuids.length == 0) {
                listener.onLog("  车机 SDP 缓存：空");
                return;
            }
            StringBuilder sb = new StringBuilder("  车机 SDP 缓存(" + uuids.length + "): ");
            for (ParcelUuid u : uuids) {
                if (u != null) sb.append(u.getUuid()).append(' ');
            }
            listener.onLog(sb.toString().trim());
        } catch (Throwable t) {
            listener.onLog("  读取车机 SDP 缓存失败：" + t.getClass().getSimpleName());
        }
    }

    private static String shortUuid(UUID uuid) {
        String s = uuid.toString();
        if (CarPlaySdpBeacon.IPHONE_IAP2_ACCESSORY_UUID.equals(uuid)) return "…caff";
        if (CarPlaySdpBeacon.WIRELESS_IAP2_ACCESSORY_UUID.equals(uuid)) return "02030302…0a78";
        if (CarPlaySdpBeacon.LEGACY_IAP2_UUID.equals(uuid)) return "CADE";
        return s.length() > 12 ? s.substring(0, 12) + "…" : s;
    }

    private static boolean sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Thread connectThread(final BluetoothDevice car, final UUID uuid) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                if (!running || sessionOpen) return;
                BluetoothSocket socket = null;
                try {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter != null) {
                        try {
                            adapter.cancelDiscovery();
                        } catch (Throwable ignored) {
                        }
                    }
                    socket = car.createRfcommSocketToServiceRecord(uuid);
                    socket.connect();
                    if (!running || sessionOpen) {
                        closeQuietly(socket);
                        return;
                    }
                    deliver(socket, "主动连接 " + uuid);
                } catch (Throwable e) {
                    closeQuietly(socket);
                    if (running && !sessionOpen) {
                        listener.onLog("主动连接失败 " + uuid + ": "
                                + e.getClass().getSimpleName()
                                + (e.getMessage() == null ? "" : " " + e.getMessage()));
                    }
                }
            }
        }, "carplay-connect-" + uuid.toString().substring(0, 8));
        t.setDaemon(true);
        return t;
    }

    private static String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null || n.isEmpty() ? d.getAddress() : n;
        } catch (Throwable t) {
            return d.getAddress();
        }
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }
}
