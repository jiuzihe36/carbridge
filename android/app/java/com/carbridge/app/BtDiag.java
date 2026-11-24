package com.carbridge.app;
import com.carbridge.carplay.audio.CarAudioRouter;
import com.carbridge.carplay.bt.CarPairing;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.Parcelable;

import java.util.Set;
import com.carbridge.carplay.util.Diag;

/**
 * 蓝牙诊断：搞清楚「车机到底看见了什么」。
 */
public final class BtDiag {

    /** 日志出口。 */
    public interface Log {
        void onLog(String line);
    }

    /** 多久之后注销广播接收器（SDP 查询可能很慢，尤其对方不在身边）。 */
    private static final long TIMEOUT_MS = 25_000L;

    private BtDiag() {
    }

    /** 跑一次完整诊断，结果直接进 App 日志（用户导出日志给我就行）。 */
    public static void run(final Context ctx, final Log log) {
        final BluetoothAdapter adapter = com.carbridge.carplay.bt.Bt.adapter(ctx);
        if (adapter == null) {
            log.onLog("✗ 没有蓝牙适配器");
            return;
        }
        log.onLog("── 车机档案：" + com.carbridge.carplay.CarProfile.summary() + " ──");
        final Set<BluetoothDevice> bonded = adapter.getBondedDevices();

        // ⚠️ 这里的 getUuids() 是**本机看到的对方**（车机暴露的服务），
        //    和车机判定 CarPlay 用的那份**不是同一份**。车机判的是
        //    「车机缓存的、本机的 SDP 名单」，我们从手机上读不到。
        //    以前这里写「车机判定用的就是这一份」，会把人带偏。
        log.onLog("── 已配对设备的 SDP 名单（**本机看到的对方服务**）──");
        for (BluetoothDevice d : bonded) {
            log.onLog("• " + name(d) + "  [" + d.getAddress() + "]");
            log.onLog("    getUuids(): " + join(d.getUuids()));
        }
        if (bonded.isEmpty()) {
            log.onLog("（没有已配对设备）");
            return;
        }

        // 本机的两条信标 —— 这才是车机要的东西
        log.onLog("── 本机 SDP 信标（车机判 CarPlay 用的那份的来源）──");
        log.onLog("  Settings 判定: " + com.carbridge.carplay.CarProfile.APPLE_DEVICE_EIR_UUID);
        log.onLog("  Carplay app 判定: " + com.carbridge.carplay.CarProfile.APPLE_IAP2_UUID);
        log.onLog("  信标状态: " + (com.carbridge.carplay.bt.CarPlaySdpBeacon.isStarted()
                ? "✓ 已注册（配对时车机能学到这两条）"
                : "✗ 没注册 → 车机这次配对学不到，必须重新配对"));
        log.onLog("  ⚠️ 车机只在**配对那一刻**对表；之后重连不会重查。"
                + "配对前信标必须已在跑。");

    // 车机识别 + 解绑能力：这两件事决定「重新配对」这条路走不走得通。
        BluetoothDevice car = CarPairing.findCar();
        if (car == null) {
            log.onLog("• 没在已配对设备里认出车机（CoD 车载音频 / 名字含 CarPlay / 已知地址）");
            log.onLog("  ── 已配对设备清单（认车机靠它）──");
            for (BluetoothDevice d : bonded) {
                String dn = null;
                try {
                    dn = d.getName();
                } catch (Throwable tDiag) {
                    Diag.w("BtDiag.run", tDiag);
                }
                int dcod = 0;
                try {
                    dcod = d.getBluetoothClass() == null ? 0
                            : d.getBluetoothClass().getDeviceClass();
                } catch (Throwable tDiag) {
                    Diag.w("BtDiag.run#2", tDiag);
                }
                log.onLog("  • " + (dn == null ? "(无名)" : dn) + " [" + d.getAddress()
                        + "] CoD=0x" + Integer.toHexString(dcod));
            }
        } else {
            String carName = name(car);
            int carCod = car.getBluetoothClass() == null
                    ? 0 : car.getBluetoothClass().getDeviceClass();
            log.onLog("• 认出车机: " + carName + " [" + car.getAddress() + "]"
                    + " CoD=0x" + Integer.toHexString(carCod));
        }
        log.onLog("• 解除配对能力（removeBond）: " + (CarPairing.canForget() ? "可用" : "不可用"));

        // 通话音频：手动连一次车机的 HFP —— car 模式下通话声要上车机，全靠这条通路
        try {
            CarAudioRouter.connectCarHeadset(ctx, car, new CarAudioRouter.Log() {
                @Override
                public void onLog(String line) {
                    log.onLog("  " + line);
                }
            });
        } catch (Throwable t) {
            log.onLog("• 通话音频(HFP) 异常: " + t);
        }
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            android.media.AudioDeviceInfo cd = am == null ? null : am.getCommunicationDevice();
            log.onLog("• 当前通话输出设备: " + (cd == null ? "null（=本机听筒/扬声器）"
                    : cd.getType() + " " + cd.getAddress() + " " + cd.getProductName()));
        } catch (Throwable t) {
            log.onLog("• 读通话输出设备失败: " + t);
        }

        // AAC 编码器自检：编一段正弦波再解回来。
        // 「车机无声」有两种完全不同的原因，这一步能当场排除「我们的比特流是坏的」。
        try {
            com.carbridge.carplay.core.AacSelfTest.Result r =
                    com.carbridge.carplay.core.AacSelfTest.run(44100, 2);
            log.onLog("• AAC-LC 编码器自检: " + (r.ok ? "✓ " : "✗ ") + r.detail);
        } catch (Throwable t) {
            log.onLog("• AAC-LC 编码器自检异常: " + t);
        }

        dumpBtApiShape(log);

        // ── 扫车机 EIR：拿热点信息（Apple Device IE）──
        scanEir(ctx, log);

        log.onLog("── 强制重查 SDP：fetchUuidsWithSdp() ──");        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                BluetoothDevice d = com.carbridge.carplay.bt.Bt.deviceFrom(i);
                ParcelUuid[] u = com.carbridge.carplay.bt.Bt.uuidsFrom(i);
                if (d == null) return;
                log.onLog("• SDP 重查 " + name(d) + " [" + d.getAddress() + "]: " + join(u));
                log.onLog("   " + verdict(u));
            }
        };
        try {
            IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_UUID);
            // ⚠️ 用 **application context** 注册：原来的 ctx 是 Activity，
            //    Activity 在这 25 秒窗口里销毁的话 receiver 就泄漏了。
            Context appCtx = ctx.getApplicationContext();
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            } else {
                appCtx.registerReceiver(receiver, f);
            }
        } catch (Throwable t) {
            log.onLog("✗ 注册 ACTION_UUID 广播失败：" + t);
            return;
        }
        for (BluetoothDevice d : bonded) {
            try {
                d.fetchUuidsWithSdp();
            } catch (Throwable t) {
                log.onLog("• " + name(d) + " 重查失败：" + t);
            }
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    ctx.getApplicationContext().unregisterReceiver(receiver);
                } catch (Throwable tDiag) {
                    Diag.w("BtDiag.run#3", tDiag);
                }
                log.onLog("── 诊断结束 ──");
            }
        }, TIMEOUT_MS);
    }

    /**
     * 只跑 EIR 扫描（车机热点信息 / Apple Device IE）。
     *
     * <p>命令行入口：{@code adb shell am start -n com.carbridge.app/.MainActivity --es diag eir}
     */
    public static void scanEirOnly(final Context ctx, final Log log) {
        scanEir(ctx, log);
    }

        /**
     * 扫一轮 EIR，看能不能从车机的广播里读出「Apple Device IE」（热点 BSSID/SSID/密码）。
     */
    private static void scanEir(final Context ctx, final Log log) {
        log.onLog("── EIR 扫描（找车机的 Apple Device IE）──");
        // 已知车机地址 → 扫描完能明确回答「车机到底在不在」
        String carHint = "";
        try {
            android.bluetooth.BluetoothDevice car = CarPairing.findCar();
            if (car != null) carHint = car.getAddress();
        } catch (Throwable tDiag) {
            Diag.w("BtDiag.scanEir", tDiag);
        }
        final String hint = carHint;
        com.carbridge.carplay.bt.BtEirSniffer sniffer =
                new com.carbridge.carplay.bt.BtEirSniffer(ctx,
                new com.carbridge.carplay.bt.BtEirSniffer.Listener() {
                    @Override
                    public void onLog(String line) {
                        log.onLog("• " + line);
                    }
                    @Override
                    public void onAppleDeviceIe(String name, String addr,
                            com.carbridge.carplay.bt.AppleDeviceIe ie) {
                        log.onLog("★ 找到车机的 Apple Device IE：" + name + " [" + addr
                                + "] " + ie);
                    }
                }, 35000L, hint);
        sniffer.start();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                sniffer.stop();
                log.onLog("── EIR 扫描结束 ──");
            }
        }, 40_000L);
    }

    private static String name(BluetoothDevice d) {        try {
            String n = d.getName();
            return n == null ? "(无名)" : n;
        } catch (Throwable t) {
            return "(无名)";
        }
    }

    /**
     * 给一份对端 SDP 名单下结论。
     *
     * <p>依据（mcarplay 在奇瑞艾瑞泽8 上的实测）：车机自己若支持无线 CarPlay，SDP 里通常没有
     * {@code …decacaff}（那是**配件端**才宣告的），但会有它自己的入口服务，例如奇瑞/德赛的
     * {@code ec884348-cd41-40a2-9727-575d50bf1fd3}。所以“没有 cacaff”绝不能读成“车机不支持”，
     * 那只是缺一条配件侧 UUID。
     */
    private static String verdict(ParcelUuid[] uuids) {
        if (uuids == null || uuids.length == 0) {
            return "→ 该设备 SDP 为空：它要么不是车机，要么配对时没暴露服务";
        }
        boolean carAccessory = false;
        boolean cheryEntry = false;
        for (ParcelUuid p : uuids) {
            String s = p.toString().toLowerCase(java.util.Locale.US);
            if (s.startsWith("00000000-deca-fade-deca-deafdecacaff")) carAccessory = true;
            if (s.startsWith("ec884348-cd41-40a2-9727-575d50bf1fd3")) cheryEntry = true;
        }
        if (cheryEntry) {
            return "→ 奇瑞/德赛车机入口（ec884348）在。这就是它的无线 CarPlay 服务，"
                    + "回连会打到本机 …decacafe；若一直没动静，先重启中控（车机 CarPlay 进程会崩）";
        }
        if (carAccessory) {
            return "→ 对端宣告了 …decacaff（配件端 iAP2），可以等它主动回连";
        }
        return "→ 既无 …decacaff 也无 ec884348：更像普通蓝牙设备（耳机/手机），"
                + "不是支持无线 CarPlay 的车机";
    }

    private static String join(ParcelUuid[] uuids) {
        if (uuids == null) return "null";
        if (uuids.length == 0) return "(空)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < uuids.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(uuids[i].toString());
        }
        return sb.toString();
    }

    /**
     * 把这台机器上**真实存在**的蓝牙框架 API 形状 dump 出来。
     *
     * <p>为什么要 dump：Android 15 把 {@code BluetoothProfile$Listener} 整个删了，
     * HFP 代理入口的形状随 ROM 变（getProfileProxy 的参数个数/类型都不同），
     * 写死签名必错。**运行时看一眼**最靠谱。
     */
    private static void dumpBtApiShape(final Log log) {
        log.onLog("── 本机蓝牙框架 API 形状（Android 版本相关）──");
        try {
            Class<?> adapter = android.bluetooth.BluetoothAdapter.class;
            for (java.lang.reflect.Method m : adapter.getDeclaredMethods()) {
                String n = m.getName();
                if (!n.toLowerCase(java.util.Locale.US).contains("profile")) continue;
                log.onLog("  BluetoothAdapter." + n + "(" + types(m.getParameterTypes())
                        + ") → " + m.getReturnType().getSimpleName()
                        + (java.lang.reflect.Modifier.isPublic(m.getModifiers()) ? " [public]" : " [非public]"));
            }
        } catch (Throwable t) {
            log.onLog("  BluetoothAdapter 反射失败: " + t);
        }
        try {
            Class<?> hs = android.bluetooth.BluetoothHeadset.class;
            for (java.lang.reflect.Constructor<?> c : hs.getDeclaredConstructors()) {
                log.onLog("  BluetoothHeadset.<init>(" + types(c.getParameterTypes()) + ")");
            }
            for (java.lang.reflect.Method m : hs.getDeclaredMethods()) {
                String n = m.getName();
                if (!n.equals("connect") && !n.equals("disconnect")) continue;
                log.onLog("  BluetoothHeadset." + n + "(" + types(m.getParameterTypes())
                        + ") → " + m.getReturnType().getSimpleName()
                        + (java.lang.reflect.Modifier.isPublic(m.getModifiers()) ? " [public]" : " [非public]"));
            }
        } catch (Throwable t) {
            log.onLog("  BluetoothHeadset 反射失败: " + t);
        }
        for (String cn : new String[]{"android.bluetooth.BluetoothProfile$Listener",
                "android.bluetooth.BluetoothProfile$ServiceListener",
                "android.bluetooth.IBluetoothProfileCallback"}) {
            try {
                Class<?> c = Class.forName(cn);
                StringBuilder sb = new StringBuilder();
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(m.getName()).append("(").append(types(m.getParameterTypes())).append(")");
                }
                log.onLog("  " + cn + " 存在: " + sb);
            } catch (Throwable t) {
                log.onLog("  " + cn + " 不存在");
            }
        }
    }

    private static String types(Class<?>[] ps) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> p : ps) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.getSimpleName());
        }
        return sb.toString();
    }

}
