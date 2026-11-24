package com.carbridge.carplay.net;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;

import com.carbridge.carplay.CarPlayCtrlCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.carbridge.carplay.util.Diag;

/**
 * 无线 CarPlay 的 mDNS/Bonjour 层。
 */
public final class MdnsCarPlay {

    public static final String SERVICE_TYPE_CTRL = "_carplay-ctrl._tcp.";
    public static final String SERVICE_TYPE_AIRPLAY = "_airplay._tcp.";

    public interface Listener {
        void onLog(String msg);

        /** 发现一台车机的 AirPlay 端点。 */
        void onAirPlayEndpoint(String host, int port, Map<String, String> txt);
    }

    private final NsdManager nsd;
    private final Listener listener;

    private NsdServiceInfo ctrlInfo;
    private NsdManager.RegistrationListener ctrlListener;
    private NsdManager.DiscoveryListener discoveryListener;

    /** 每轮发现是否已上报过端点（防重复回调起多个会话）。 */
    private volatile boolean endpointReported;

    public void resetReported() {
        endpointReported = false;
    }

    public MdnsCarPlay(NsdManager nsd, Listener listener) {
        this.nsd = nsd;
        this.listener = listener;
    }

    // ================================================================== 广播 _carplay-ctrl._tcp

    /** 注册 `_carplay-ctrl._tcp`。**必须在 bindProcessToNetwork 之后调用。**
     *
     * 只注册一次：NsdManager 的 unregister 是**异步**的，反复 register/register
     * 会因为旧的还没注销完而产生 `carbridge (2)`、`carbridge (3)` 这类重复名（实测）。
     *
     * @param port  控制通道端口（车机反连这个端口）
     * @param realMac 真实蓝牙 MAC；占位值会导致车机不邀请。
     */
    public void advertiseCtrl(int port, String realMac) {
        advertiseCtrl(port, realMac, CarPlayCtrlCompat.MODE_950);
    }

    /**
     * @param srcvers 写进 TXT 的协议版本。见 {@link CarPlayCtrlCompat}：
     *                它和 RTSP 的 sourceVersion 不是同一个字段，不能混用。
     */
    public void advertiseCtrl(int port, String realMac, String srcvers) {
        if (ctrlInfo != null) {
            // 已注册过就复用（换端口才需要重来）
            return;
        }
        unregisterCtrl();
        NsdServiceInfo info = new NsdServiceInfo();
        // 实例名用 "iPhone"：真 iPhone 就是这么广播的，车机可能按名字识别。
        info.setServiceName("iPhone");
        info.setServiceType(SERVICE_TYPE_CTRL);
        info.setPort(port);
        if (realMac != null) {
            try {
                info.setAttribute("id", realMac);
            } catch (Throwable t) {
                // 低版本 NsdManager 没有 setAttribute，用反射兜底
                setTxtByReflection(info, "id", realMac);
            }
        }
        // ⚠️ 这里**不是** RTSP 的 Source-Version。真车 A/B（奥迪 MIB3 3315）：
        //    填 566.25.21 时车机连 ctrl-int 探测都不发、0x4301 永不到达；
        //    填 950.7.1 时同一台车 0.65 秒内就下发 0x4301。
        try {
            info.setAttribute("srcvers", srcvers);
        } catch (Throwable t) {
            setTxtByReflection(info, "srcvers", srcvers);
        }
        ctrlInfo = info;

        final NsdManager.RegistrationListener l = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                // 车机在 WiFi 上唯一能看到的手机身份就是这几个 TXT 字段。
                // 以前只打名字和端口，`id` 缺失（拿不到真实 MAC）时日志里完全看不出来，
                // 「车机点 CarPlay 没反应」就无从归因 —— 这里每次都打全。
                listener.onLog("已广播 " + SERVICE_TYPE_CTRL + " " + serviceInfo.getServiceName()
                        + " 端口 " + port
                        + " · id=" + (realMac == null ? "（缺失）" : realMac)
                        + " · srcvers=" + srcvers);
                if (realMac == null) {
                    listener.onLog("⚠ TXT 里没有 id（本机真实蓝牙 MAC）→ 车机无法匹配本机，"
                            + "不会发 CarPlay 邀请。到「本机身份 → 蓝牙地址」填写一次。");
                }
            }

            @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                listener.onLog("已停止广播 _carplay-ctrl");
            }

            @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                listener.onLog("_carplay-ctrl 广播失败 code=" + errorCode);
            }

            @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            }
        };
        ctrlListener = l;
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l);
        } catch (Throwable t) {
            listener.onLog("注册 _carplay-ctrl 异常: " + t);
        }
    }

    public void unregisterCtrl() {
        NsdManager.RegistrationListener l = ctrlListener;
        ctrlListener = null;
        NsdServiceInfo i = ctrlInfo;
        ctrlInfo = null;
        if (l != null && i != null) {
            try {
                nsd.unregisterService(l);   // 异步，listener 不能复用
            } catch (Throwable tDiag) {
                Diag.w("MdnsCarPlay.unregisterCtrl", tDiag);
            }
        }
    }

    // ================================================================== 发现 _airplay._tcp

    /**
     * 开始发现车机的 `_airplay._tcp`。
     *
     * @param localPrefix 本机网段前缀（如 "10.174.189."），只接受匹配的结果，
     *                    防止把手机热点共享出去的电脑 AirPlay 当成车机。
     */
    public void discoverAirPlay(final String localPrefix) {
        stopDiscovery();
        endpointReported = false;
        final NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) {
                listener.onLog("开始发现 " + serviceType);
            }

            @Override public void onServiceFound(NsdServiceInfo found) {
                resolve(found, localPrefix);
            }

            @Override public void onServiceLost(NsdServiceInfo lost) {
                listener.onLog("服务消失: " + lost.getServiceName());
            }

            @Override public void onDiscoveryStopped(String serviceType) {
            }

            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                listener.onLog("_airplay 开始发现失败 code=" + errorCode);
            }

            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                listener.onLog("停止 _airplay 发现失败 code=" + errorCode);
            }
        };
        discoveryListener = l;
        try {
            nsd.discoverServices(SERVICE_TYPE_AIRPLAY, NsdManager.PROTOCOL_DNS_SD, l);
        } catch (Throwable t) {
            listener.onLog("开始发现 _airplay 异常: " + t);
        }
    }

    @SuppressWarnings("deprecation")   // minSdk 26：registerServiceInfoCallback/getHostAddresses 要 API 34
    private void resolve(final NsdServiceInfo found, final String localPrefix) {
        nsd.resolveService(found, new NsdManager.ResolveListener() {
            @Override public void onServiceResolved(NsdServiceInfo info) {
                String host = info.getHost() != null ? info.getHost().getHostAddress() : null;
                int port = info.getPort();
                if (host == null) {
                    listener.onLog("_airplay 解析不出 host");
                    return;
                }
                if (localPrefix != null && !localPrefix.isEmpty()
                        && !host.startsWith(localPrefix)) {
                    listener.onLog("跳过 _airplay " + host + "（不在本机网段 " + localPrefix + "）");
                    return;
                }
                Map<String, String> txt = new LinkedHashMap<String, String>();
                try {
                    Map<String, byte[]> attrs = info.getAttributes();
                    if (attrs != null) {
                        for (Map.Entry<String, byte[]> e : attrs.entrySet()) {
                            txt.put(e.getKey(),
                                    e.getValue() == null ? "" : new String(e.getValue(), "UTF-8"));
                        }
                    }
                } catch (Throwable t) {
                    // ⚠️ TXT 读不出来 = 车机会不邀请我们（id/srcvers 缺失），
                    //    这种失败原来被静默吞掉，现场只看得到「连不上」。
                    listener.onLog("⚠ 读 _airplay._tcp 的 TXT 失败（车机可能因此不邀请）: " + t);
                }
                // ⚠️ NsdManager 对同一服务的每次发现/刷新都会回调，重复上报会起多个会话
                if (endpointReported) {
                    listener.onLog("重复的 _airplay 发现，忽略 " + host + ":" + port);
                    return;
                }
                endpointReported = true;
                listener.onLog("发现车机 AirPlay " + host + ":" + port + " TXT=" + txt.keySet());
                listener.onAirPlayEndpoint(host, port, txt);
            }

            @Override public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                listener.onLog("_airplay 解析失败 code=" + errorCode);
            }
        });
    }

    public void stopDiscovery() {
        NsdManager.DiscoveryListener l = discoveryListener;
        discoveryListener = null;
        if (l != null) {
            try {
                nsd.stopServiceDiscovery(l);
            } catch (Throwable tDiag) {
                Diag.w("MdnsCarPlay.stopDiscovery", tDiag);
            }
        }
    }

    public void shutdown() {
        unregisterCtrl();
        stopDiscovery();
    }

    // ================================================================== 兼容

    /** 低版本 NsdManager 没有 setAttribute，用反射设置 TXT。 */
    @SuppressWarnings("unchecked")
    private static void setTxtByReflection(NsdServiceInfo info, String key, String value) {
        try {
            java.lang.reflect.Field f = NsdServiceInfo.class.getDeclaredField("mTxtRecord");
            f.setAccessible(true);
            Map<String, byte[]> map = (Map<String, byte[]>) f.get(info);
            if (map == null) {
                map = new LinkedHashMap<String, byte[]>();
                f.set(info, map);
            }
            map.put(key, value.getBytes("UTF-8"));
        } catch (Throwable t) {
            // 反射失败就只能放弃 TXT，车机可能不邀请
        }
    }

    /** 取本机某网卡的 /24 前缀，用于过滤 mDNS 结果。 */
    public static String localPrefix(java.net.InetAddress addr) {
        if (addr == null) return null;
        byte[] b = addr.getAddress();
        if (b == null || b.length != 4) return null;
        return (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + ".";
    }

    /** 供 UI：列出发现到的服务名（调试用）。 */
    public static List<String> describe(List<Map<String, String>> txts) {
        List<String> out = new ArrayList<String>();
        for (Map<String, String> t : txts) out.add(t.toString());
        return out;
    }
}
