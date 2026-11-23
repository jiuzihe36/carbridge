package com.carbridge.car;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 车机侧「一键解锁」：手机自己连车机热点、自己 adb 进车机、把 CarPlay 后门写上。
 *
 * <p>为什么要这条路：车机的 CarPlay 应用判「这台手机算不算 CarPlay 设备」有两条路——
 * ① 配对那一刻的 SDP 缓存里有 {@code 2d8d2466}；② {@code device.getAddress()} 等于
 * 系统属性 {@code persist.sys.wirelesscarplay.address}（这条<b>不检查 UUID</b>）。
 * 第 ② 条是车机自己的逻辑，写进去之后即使「支持无线 CarPlay」那行还是灰的，
 * CarPlay 列表里也会出现这台手机，点它就走 {@code connectBT} → 手机的 CADE iAP2。
 *
 * <p>为什么要内置 adb：用户不方便把电脑带到车上。车机的 adbd 之前已经信任过
 * Mac 那把密钥，把同一把打进 assets，连上去就不用在车机屏幕点「允许调试」。
 *
 * <p>这条流程只在用户点按钮时跑，不放进常驻路径。
 */
public final class CarSideSetup {

    public interface Log {
        void log(String line);
    }

    /** 车机侧属性：记住「上一次的 CarPlay 手机」，CarPlay 应用拿它绕过 UUID 检查。 */
    public static final String PROP = "persist.sys.wirelesscarplay.address";
    /** 车机 CarPlay 应用（德赛）。 */
    public static final String CARPLAY_PKG = "com.desaysv.vehicle.carplayapp";
    /** 车机在热点里的固定地址（实测），扫不到时才用它兜底。 */
    public static final String DEFAULT_CAR_IP = "192.168.33.92";
    public static final int ADB_PORT = 5555;

    private CarSideSetup() {
    }

    /** 一次执行的结果。 */
    public static final class Result {
        public final boolean ok;
        public final String carIp;
        public final String message;

        Result(boolean ok, String carIp, String message) {
            this.ok = ok;
            this.carIp = carIp;
            this.message = message;
        }
    }

    /**
     * 车机 shell 的执行器：能拿到 root 就用 {@code su -c} 包一层。
     *
     * <p>为什么要这层：{@code persist.sys.*} 这类属性在多数 Android 上只允许 system/root 写，
     * 而 adb shell 是 shell 用户 —— 直接 {@code setprop} 在不少机型会回
     * {@code failed to set property}。车机是 Android 9 的开发版（userdebug/eng 很常见），
     * 先试一次 {@code su -c id}，成不成都写进日志，用户和我都能看到走的是哪条路。
     */
    private static final class Sh {
        private final AdbClient client;
        private final Log log;
        private boolean root;
        private boolean probed;

        Sh(AdbClient client, Log log) {
            this.client = client;
            this.log = log;
        }

        private void probe() {
            if (probed) return;
            probed = true;
            try {
                String id = client.shell("id").trim();
                if (id.contains("uid=0")) {
                    root = true;
                    log.log("  shell 已经是 root（" + oneLine(id) + "）");
                    return;
                }
                String suId = client.shell("su -c id 2>&1").trim();
                if (suId.contains("uid=0")) {
                    root = true;
                    log.log("  车机有 su：后续命令用 su -c 执行");
                } else {
                    log.log("  车机没有 su（su -c id → " + oneLine(suId) + "）");
                }
            } catch (Throwable t) {
                log.log("  root 探测失败：" + t);
            }
        }

        /** 执行命令，返回合并输出（用于抓日志这类要读结果的）。 */
        String run(String cmd) throws Exception {
            probe();
            if (!root) return client.shell(cmd);
            return client.shell("su -c \"" + cmd.replace("\"", "\\\"") + "\" 2>&1");
        }

        /** 执行命令并返回一行可读的日志文本（用于 setprop / am 这类只看成败的）。 */
        String runOn(String cmd) {
            try {
                String out = run(cmd).trim();
                return cmd + (out.length() == 0 ? "  → 已执行" : "  → " + oneLine(out));
            } catch (Throwable t) {
                return cmd + "  → 失败：" + t;
            }
        }
    }

    /**
     * 跑一次完整流程（阻塞，调用方必须放在后台线程）。
     *
     * @param phoneMac 本机蓝牙 MAC —— 车机就是拿它跟配对列表对名字
     */
    public static Result run(Context ctx, String phoneMac, Log log) {
        if (phoneMac == null || phoneMac.length() == 0) {
            return new Result(false, null, "读不到本机蓝牙地址，先在「本机身份」里填一次");
        }
        String pem;
        String pub;
        try {
            pem = readAsset(ctx, "adbkey");
            pub = readAsset(ctx, "adbkey.pub");
        } catch (Throwable t) {
            return new Result(false, null, "安装包里没有 adb 密钥（assets/adbkey 缺失）");
        }

        List<String> candidates = candidates(log);
        if (candidates.isEmpty()) {
            return new Result(false, null, "没找到车机：手机要先连上车机的 Wi-Fi 热点（xiaohu）");
        }

        AdbClient client = new AdbClient(new AdbClient.Log() {
            @Override
            public void log(String line) {
                log.log(line);
            }
        });
        String target = null;
        Throwable last = null;
        for (String ip : candidates) {
            try {
                log.log("尝试连接车机 " + ip + ":" + ADB_PORT + " …");
                client.connect(ip, ADB_PORT, pem, pub, 2500);
                target = ip;
                break;
            } catch (Throwable t) {
                last = t;
                log.log("  ✗ " + ip + " 连不上：" + brief(t));
            }
        }
        if (target == null) {
            return new Result(false, null, "连不上车机 adb：" + brief(last));
        }

        try {
            log.log("── 车机信息 ──");
            log.log("  型号：" + oneLine(client.shell("getprop ro.product.model")));
            log.log("  固件：" + oneLine(client.shell("getprop ro.build.display.id")));
            String buildType = oneLine(client.shell("getprop ro.build.type"));
            log.log("  构建类型：" + buildType + "（userdebug/eng 才有机会 su）");
            log.log("  当前 " + PROP + " = "
                    + oneLine(client.shell("getprop " + PROP)));

            // adb shell 是 shell 用户；persist.sys.* 在多数机型只允许 system/root 写。
            // 先探测能不能拿到 root，拿得到就用 su 包一层——车机是 Android 9 的开发版，
            // 这一步成的概率不低，比"写不进去就报错"有用。
            Sh sh = new Sh(client, log);
            log.log("── 写入后门 ──");
            log.log("  " + sh.runOn("setprop " + PROP + " " + phoneMac));
            String readBack = oneLine(client.shell("getprop " + PROP));
            log.log("  读回：" + readBack);
            boolean written = phoneMac.equalsIgnoreCase(readBack);
            if (!written) {
                return new Result(false, target,
                        "setprop 没生效（读回 " + readBack + "）——车机不接受 shell（或 su）写这条属性，"
                                + "下一步只能走 carforce：直接绑 CarplayService 调 connectWirelessCarplayItem");
            }

            log.log("── 重启车机 CarPlay 应用，让它重读设备列表 ──");
            sh.runOn("am force-stop " + CARPLAY_PKG);
            Thread.sleep(800);
            sh.runOn("am start --user 0 -n " + CARPLAY_PKG + "/.MainActivity");
            log.log("  已重启 " + CARPLAY_PKG);

            log.log("── 车机最近的 CarPlay 判定日志 ──");
            String logs = sh.run(
                    "logcat -d -v time -t 200 | grep -iE 'isSupportCarplay|connectWirelessCarplay|connectBT|New Iphone'");
            if (logs == null || logs.trim().length() == 0) {
                log.log("  （没有匹配到判定日志）");
            } else {
                for (String line : tail(logs, 8)) log.log("  " + line);
            }
            return new Result(true, target,
                    "车机后门已写入（" + target + "）：到车机上点 Apple CarPlay 列表里的这台手机");
        } catch (Throwable t) {
            return new Result(false, target, "车机命令执行失败：" + brief(t));
        } finally {
            client.close();
        }
    }

    /**
     * 候选车机地址：先扫本机所在网段的 5555，扫不到再退回实测固定地址。
     *
     * <p>为什么扫而不是写死：车机热点重启后 DHCP 段可能变，写死一个 IP 现场就会「找不到车机」。
     */
    private static List<String> candidates(Log log) {
        List<String> out = new ArrayList<String>();
        String local = wifiIpv4();
        if (local == null) {
            log.log("手机当前不在 Wi-Fi 上（车机热点没连）");
            return out;
        }
        String prefix = local.substring(0, local.lastIndexOf('.') + 1);
        log.log("本机 Wi-Fi 地址 " + local + "，扫描 " + prefix + "x 的 5555 …");
        out.addAll(scanSubnet(prefix));
        if (!out.contains(DEFAULT_CAR_IP) && scanOne(DEFAULT_CAR_IP)) {
            out.add(DEFAULT_CAR_IP);
        }
        log.log("发现 " + out.size() + " 个开着 adb 的设备");
        return out;
    }

    private static List<String> scanSubnet(String prefix) {
        final List<String> hits = Collections.synchronizedList(new ArrayList<String>());
        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + i;
            futures.add(pool.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    if (scanOne(ip)) hits.add(ip);
                    return null;
                }
            }));
        }
        try {
            for (Future<?> f : futures) {
                try {
                    f.get(3, TimeUnit.SECONDS);
                } catch (Throwable ignored) {
                }
            }
        } finally {
            pool.shutdownNow();
        }
        List<String> sorted = new ArrayList<String>(hits);
        Collections.sort(sorted, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return ipTail(a) - ipTail(b);
            }
        });
        return sorted;
    }

    private static int ipTail(String ip) {
        try {
            return Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1));
        } catch (Throwable t) {
            return 999;
        }
    }

    private static boolean scanOne(String ip) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, ADB_PORT), 350);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 取 wlan 的 IPv4（不依赖已废弃的 WifiManager.getConnectionInfo）。 */
    private static String wifiIpv4() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                String name = ni.getName();
                if (name == null || !name.startsWith("wlan")) continue;
                if (!ni.isUp()) continue;
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    String ip = a.getHostAddress();
                    if (a instanceof java.net.Inet4Address && ip != null
                            && !ip.startsWith("127.")) {
                        return ip;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String readAsset(Context ctx, String name) throws Exception {
        InputStream in = ctx.getAssets().open(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace("\r", "").replace("\n", " ").trim();
    }

    private static List<String> tail(String s, int n) {
        String[] lines = s.split("\n");
        List<String> out = new ArrayList<String>();
        int start = Math.max(0, lines.length - n);
        for (int i = start; i < lines.length; i++) {
            if (lines[i].trim().length() > 0) out.add(lines[i].trim());
        }
        return out;
    }

    private static String brief(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : "：" + m);
    }
}
