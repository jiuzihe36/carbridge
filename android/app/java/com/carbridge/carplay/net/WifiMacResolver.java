package com.carbridge.carplay.net;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.carbridge.carplay.util.Diag;

/**
 * 本机在车机热点上的 Wi-Fi MAC —— AirPlay SETUP 的 {@code macAddress} 字段。
 *
 * <p>为什么不能填蓝牙 MAC：真 iPhone 在 SETUP 里填的是**自己在热点上的 Wi-Fi 地址**
 * （热点上的随机化地址），车机把它记成 {@code mAutoReConnectMacAddress}，用来把
 * 「热点上的这台终端」和「这条 CarPlay 会话」对上（艾瑞泽 8 车机日志，2026-11-02）。
 * 填蓝牙 MAC 时车机这一步就对不上，表现为会话能起、但车机侧的自动重连/会话归属错乱。
 *
 * <p>Android 11+ 普通应用读不到硬件地址，依次尝试：
 * <ol>
 *   <li>{@link NetworkInterface#getHardwareAddress()}</li>
 *   <li>{@code /sys/class/net/<if>/address} 直读</li>
 *   <li>root shell 读同一路径（有 root 时）</li>
 *   <li>由 EUI-64 形式的链路本地 IPv6 反推</li>
 * </ol>
 * 都失败返回 null —— 调用方保留原来的值，所以这条路永远不会比现状更差。
 *
 * <p>注意：热点上的地址通常是**随机化**的，本地管理位是置位的；这里必须接受它，
 * 与蓝牙地址那套「拒绝本地管理位」的校验不同。
 */
public final class WifiMacResolver {

    private static final Pattern MAC = Pattern.compile("(?i)\\b([0-9a-f]{2}:){5}[0-9a-f]{2}\\b");
    private static final Pattern IFACE = Pattern.compile("[A-Za-z0-9_.-]{1,32}");

    private WifiMacResolver() {
    }

    /**
     * @param localAddress 已连接 socket 的本端地址（同一接口就是 Wi-Fi 接口）
     * @return 小写冒号格式的 MAC；拿不到返回 null
     */
    public static String resolve(InetAddress localAddress) {
        if (localAddress == null) return null;
        NetworkInterface nif;
        try {
            nif = NetworkInterface.getByInetAddress(localAddress);
        } catch (Throwable t) {
            return null;
        }
        if (nif == null) return null;
        String name = null;
        try {
            name = nif.getName();
        } catch (Throwable tDiag) {
            Diag.w("WifiMacResolver.resolve", tDiag);
        }
        try {
            byte[] hw = nif.getHardwareAddress();
            String mac = format(hw);
            if (mac != null) return mac;
        } catch (Throwable tDiag) {
            Diag.w("WifiMacResolver.resolve#2", tDiag);
        }
        if (name != null && IFACE.matcher(name).matches()) {
            String direct = readPath("/sys/class/net/" + name + "/address");
            if (direct != null) return direct;
            String viaRoot = readPathAsRoot("/sys/class/net/" + name + "/address");
            if (viaRoot != null) return viaRoot;
        }
        try {
            List<InetAddress> addrs = Collections.list(nif.getInetAddresses());
            for (InetAddress a : addrs) {
                String mac = fromEui64(a);
                if (mac != null) return mac;
            }
        } catch (Throwable tDiag) {
            Diag.w("WifiMacResolver.resolve#3", tDiag);
        }
        return null;
    }

    private static String readPath(String path) {
        try {
            File f = new File(path);
            if (!f.canRead()) return null;
            return parse(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 有 root 时用 shell 身份读 sysfs；没有 su 会立刻失败返回 null。 */
    private static String readPathAsRoot(String path) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + path});
            if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) {
                p.destroy();
                return null;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line = r.readLine();
            r.close();
            return parse(line);
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable tDiag) {
                    Diag.w("WifiMacResolver.readPathAsRoot", tDiag);
                }
            }
        }
    }

    /** 文本里第一个可用的 MAC（拒绝全 0、全 F、组播位、系统占位）。 */
    static String parse(String text) {
        String mac = com.carbridge.carplay.util.MacText.firstIn(text);
        return mac == null ? null : mac.toLowerCase(java.util.Locale.US);
    }

    static String format(byte[] bytes) {
        String mac = com.carbridge.carplay.util.MacText.fromBytes(bytes);
        return mac == null ? null : mac.toLowerCase(java.util.Locale.US);
    }

    /** fe80::xxxx:xxff:fexx:xxxx → MAC（翻转 U/L 位）；随机接口标识的地址返回 null。 */
    static String fromEui64(InetAddress address) {
        String mac = com.carbridge.carplay.util.MacText.fromEui64(address);
        return mac == null ? null : mac.toLowerCase(java.util.Locale.US);
    }
}
