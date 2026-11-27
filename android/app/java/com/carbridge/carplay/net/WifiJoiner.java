package com.carbridge.carplay.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;

import java.net.InetAddress;

/**
 * 加入车机热点（iAP2 的 0x5703 拿到 SSID/密码/信道/安全类型后用）。
 *
 * <p>为什么整类压制 deprecation/rawtypes：Android 9–12 上要「连到指定 SSID」只有
 * {@code android.net.wifi.WifiConfiguration} 这条老路可用；{@code WifiNetworkSpecifier} 是点对点请求，
 * 会把系统弹窗拉起来并且拿不到 DHCP 结果，车机侧等不到我们上线。新 API 只在 13+ 才能
 * 平滑替代，等确认车机最低版本再迁移。这里把警告收敛在这一处，别处再出现就说明是新代码引入的。
 */
@SuppressWarnings({"deprecation", "rawtypes", "unchecked"})
public final class WifiJoiner {

    public interface Listener {
        void onLog(String msg);

        /**
         * 已连上并绑定到该网络；随后的 mDNS 组播才走这个网络。
         *
         * @param bssid 实际连上的 AP 的 BSSID（形如 "00:11:22:33:44:55"），
         *               拿不到就传 null —— 用来和车机 EIR 里播的 BSSID 对账。
         */
        void onNetworkBound(Network network, String localIp, String bssid);

        void onFailure(String reason);
    }

    private final Context ctx;
    private final Listener listener;
    private ConnectivityManager.NetworkCallback netCallback;
    private Network bound;
    /** 置位后兜底线程立刻收手，避免 release 之后还回调 listener。 */
    private volatile boolean released;
    /** 车机 EIR 里的 BSSID（判别「是不是车机那个 AP」用）。 */
    private String expectedBssid;

    public WifiJoiner(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public Network boundNetwork() {
        return bound;
    }

    /** 连接车机热点。密码可能是明文（奥迪实测）或 null（开放网络）。 */
    public void join(String ssid, String password, int security, int channel) {
        join(ssid, password, security, channel, null);
    }

    /**
     * 连接车机热点。
     *
     * @param expectedBssid 车机 EIR 里播的热点 BSSID（可空）。
     *        有了它就能在<b>读不到 SSID</b>（缺定位权限）时仍然判断
     *        「当前连的这个网络是不是车机那个」—— 否则只能「只要在 WiFi 上就当是热点」，
     *        会把进程绑到用户自己的家庭 WiFi 上，车机永远找不到我们。
     */
    public void join(String ssid, String password, int security, int channel,
                     String expectedBssid) {
        if (ssid == null || ssid.isEmpty()) {
            listener.onFailure("SSID 为空（0x5703 解析失败）");
            return;
        }
        this.expectedBssid = expectedBssid;
        ConnectivityManager cm =
                (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            listener.onFailure("拿不到 ConnectivityManager");
            return;
        }

        // ⚠️ **已经连着目标 SSID 就直接复用**，不要再走 specifier：
        //   1) 省一次系统弹窗（Android 不支持程序化传密码）；
        //   2) 0x5703 里报的安全类型不可信——DiPlay 报 sec=0（开放），实测热点是
        //      WPA3-SAE，按开放建 specifier 会不匹配，requestNetwork 直接 onUnavailable。
        if (isConnectedTo(cm, ssid, false, expectedBssid)) {
            Network active = cm.getActiveNetwork();
            if (active != null) {
                listener.onLog("已连着热点 " + ssid + "，复用当前网络（跳过 specifier）");
                bindProcess(active);
                return;
            }
        }
        listener.onLog("尝试加入车机热点 SSID=" + ssid + " ch=" + channel + " sec=" + security);
        if (Build.VERSION.SDK_INT >= 29) {
            joinSpecifier(cm, ssid, password, security);
        } else {
            joinLegacy(ssid, password);
        }
    }

    /**
     * 当前 Wi-Fi 是否已连接到目标 SSID。
     *
     * @param quiet 轮询兜底时传 true —— 里面两处 onLog 是"状态变化才值得打"的话，
     *              每 400ms 打一次会把会话日志刷爆。
     * @param expectedBssid 车机 EIR 里的 BSSID；SSID 读不到时用它来判别。
     */
    private boolean isConnectedTo(ConnectivityManager cm, String ssid, boolean quiet,
                                  String expectedBssid) {
        // 1) 精确比 SSID —— 需要 ACCESS_FINE_LOCATION，否则 getSSID() 返回 <unknown ssid>
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
            if (info != null) {
                String cur = info.getSSID();
                if (cur != null) {
                    cur = cur.replace("\"", "").trim();
                    if (cur.equals(ssid)) return true;
                    if (!cur.isEmpty() && !cur.contains("unknown")) {
                        // 明确连着**别的** WiFi，别复用
                        if (!quiet) {
                            listener.onLog("当前连着 " + cur + " 而非 " + ssid + "，需重新入网");
                        }
                        return false;
                    }
                }
            }
        } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("WifiJoiner", t);
        }
        // 1b) SSID 读不到（缺定位权限）→ 退到 BSSID 对账。
        //     车机 EIR 里播的 BSSID 是权威的「车机那个 AP」标识。
        if (expectedBssid != null && !expectedBssid.isEmpty()) {
            String cur = currentBssid();
            if (cur != null) {
                if (cur.equalsIgnoreCase(expectedBssid)) {
                    if (!quiet) {
                        listener.onLog("SSID 读不到，但 BSSID 一致（" + cur + "），按车机热点处理");
                    }
                    return true;
                }
                if (!quiet) {
                    listener.onLog("当前连的 BSSID " + cur + " 与车机 EIR 的 "
                            + expectedBssid + " 不符，需重新入网");
                }
                return false;
            }
        }
        // 2) 拿不到 SSID（缺定位权限）时兜底：只要已经在 WiFi 上就复用。
        //    前提是设备同一时刻只会连一个热点 —— 这正是本项目（单一车机热点）的场景。
        //    ⚠️ 但如果连的是用户自己的 WiFi，就会把进程绑错网络 → 车机永远找不到我们。
        //    这时宁可**不复用**、老老实实走一次入网（系统可能弹一次窗）。
        try {
            Network active = cm.getActiveNetwork();
            if (active != null) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(active);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    if (!quiet) {
                        listener.onLog("SSID 不可读（缺定位权限），但已在 WiFi 上，按已连接复用");
                    }
                    return true;
                }
            }
        } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("WifiJoiner", t);
        }
        return false;
    }

    /** Android 10+：WifiNetworkSpecifier，系统弹窗让用户确认密码。 */
    private void joinSpecifier(ConnectivityManager cm, String ssid, String password, int security) {
        try {
            android.net.wifi.WifiNetworkSpecifier.Builder b =
                    new android.net.wifi.WifiNetworkSpecifier.Builder();
            b.setSsid(ssid);
            // ⚠️ sec=0 是**开放网络**（DiPlay 实测：sec=0 但仍在 pwd 字段塞了占位密码）。
            // 开放网络必须**不设 passphrase**，否则 specifier 匹配不上 → 永远进不去。
            boolean open = (security == 0);
            if (!open && password != null && !password.isEmpty()) {
                b.setWpa2Passphrase(password);
            } else if (open) {
                listener.onLog("开放网络，不设 passphrase");
            }
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(b.build())
                    .build();

            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    // ⚠️ release() 之后迟到的系统回调会把进程重新绑回车机热点并回调上层
                    //    → stop 之后 mDNS/ctrl 整套复活（幽灵会话）。unregisterNetworkCallback
                    //    挡不住已在派发的回调，必须自查。
                    if (released) return;
                    bound = network;
                    bindProcess(network);
                }

                @Override public void onUnavailable() {
                    // ⚠️ **onUnavailable 不等于入网失败。** 系统扫描到的目标与 specifier
                    //    对不上时会**立刻**回调（实测 sec=0 报开放、热点其实不是开放网络），
                    //    而同一时刻系统又可能按**已保存的网络**自己连上——直接 fail 就是误报
                    //    ERROR（实测 17:37:49 那次：报 ERROR，随后链路照样跑通了）。
                    //    所以先给系统几秒自己连，连上了就照常绑定往下走。
                    waitForSavedNetwork(ssid);
                }
            };
            cm.requestNetwork(req, netCallback);
            listener.onLog("已发起系统入网请求，" + (open ? "开放网络" : "请在弹窗里确认密码"));
        } catch (Throwable t) {
            listener.onFailure("入网失败: " + t);
        }
    }

    /**
     * {@link ConnectivityManager.NetworkCallback#onUnavailable()} 之后的兜底轮询。
     *
     * <p>绑定到已保存热点要 2~5s，等 8s 给足余量；真没连上才报失败（比原来只慢这一次）。
     * 独立线程跑，不占 ConnectivityManager 的回调线程。
     */
    private void waitForSavedNetwork(final String ssid) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final long deadline = SystemClock.uptimeMillis() + 8000;
                while (!released && SystemClock.uptimeMillis() < deadline) {
                    try {
                        ConnectivityManager cm = (ConnectivityManager)
                                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                        if (cm != null && isConnectedTo(cm, ssid, true, expectedBssid)) {
                            Network active = cm.getActiveNetwork();
                            if (active != null) {
                                listener.onLog("specifier 未匹配，但系统已自行连上 " + ssid
                                        + " → 改走兜底绑定");
                                bindProcess(active);
                                return;
                            }
                        }
                        Thread.sleep(400);
                    } catch (InterruptedException ie) {
                        return;
                    } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("WifiJoiner", t);
                    }
                }
                if (!released) {
                    listener.onFailure("系统弹窗取消或密码错误（已等 8s，热点仍未连上）");
                }
            }
        }, "wifi-join-fallback").start();
    }

    /** Android 9 及以下：WifiManager 直连（需要 CHANGE_NETWORK_STATE）。 */
    private void joinLegacy(String ssid, String password) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                listener.onFailure("拿不到 WifiManager");
                return;
            }
            android.net.wifi.WifiConfiguration cfg = new android.net.wifi.WifiConfiguration();
            cfg.SSID = "\"" + ssid + "\"";
            if (password == null || password.isEmpty()) {
                cfg.allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE);
            } else {
                cfg.preSharedKey = "\"" + password + "\"";
            }
            int netId = wm.addNetwork(cfg);
            if (netId == -1) {
                listener.onFailure("android.net.wifi.WifiConfiguration 被拒");
                return;
            }
            wm.disconnect();
            wm.enableNetwork(netId, true);
            wm.reconnect();
            listener.onLog("已发起低版本入网 " + ssid);
        } catch (Throwable t) {
            listener.onFailure("低版本入网失败: " + t);
        }
    }

    /**
     * 绑定进程到该网络。**必须在广播 ctrl mDNS 之前调用**——
     * 组播跟随进程绑定的网络，不绑就走默认网络（车机热点），车机收不到。
     */
    private void bindProcess(Network network) {
        if (released) return;   // 迟到的回调不许复活已释放的入网（同 onAvailable 的守卫）
        try {
            boolean ok = ConnectivityManager.setProcessDefaultNetwork(network);
            String ip = linkAddressOf(network);
            String bssid = currentBssid();
            if (ok) {
                listener.onNetworkBound(network, ip, bssid);
                listener.onLog("进程已绑定到车机热点，本机 IP=" + ip
                        + (bssid != null ? "，AP BSSID=" + bssid : ""));
            } else {
                listener.onFailure("setProcessDefaultNetwork 失败");
            }
        } catch (Throwable t) {
            listener.onFailure("绑定网络失败: " + t);
        }
    }

    /**
     * 当前连着的 AP 的 BSSID。
     *
     * <p>需要定位权限（{@code ACCESS_FINE_LOCATION}），拿不到就返回 null。
     * 用来和车机 EIR 里播的 BSSID 对账：车机检测到新 iPhone 会重启热点，
     * 我们要是连到了重启前的旧 AP，BSSID 就会对不上。
     */
    private String currentBssid() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    ctx.getSystemService(Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
            if (info == null) return null;
            String b = info.getBSSID();
            if (b == null) return null;
            b = b.trim();
            if (b.isEmpty() || "02:00:00:00:00:00".equals(b)) return null;
            return b.toUpperCase(java.util.Locale.US);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取该网络上本机的 IP；拿不到返回"未知"。 */
    private String linkAddressOf(Network network) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.LinkProperties lp = cm != null ? cm.getLinkProperties(network) : null;
            if (lp == null || lp.getLinkAddresses().isEmpty()) return "未知";
            InetAddress addr = lp.getLinkAddresses().get(0).getAddress();
            return addr != null ? addr.getHostAddress() : "未知";
        } catch (Throwable t) {
            return "未知";
        }
    }

    public void release() {
        released = true;   // 先立flag，兜底线程下一轮就不再回调
        ConnectivityManager cm =
                (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && netCallback != null) {
            try {
                cm.unregisterNetworkCallback(netCallback);
            } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("WifiJoiner", t);
            }
        }
        netCallback = null;
        bound = null;
        try {
            ConnectivityManager.setProcessDefaultNetwork(null);
        } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("WifiJoiner", t);
        }
    }
}
