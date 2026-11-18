package com.carbridge.carplay.bt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本机蓝牙身份（真实 MAC）解析。
 *
 * <p>无线 CarPlay 里车机拿这个 MAC 做三处校验，缺一处就静默失败：
 * <ol>
 *   <li>iAP2 {@code 0x4300} 的 bluetoothTransportIdentifier、{@code 0x4E0E} 设备传输标识</li>
 *   <li>AirPlay SETUP / ANNOUNCE 的 {@code macAddress}</li>
 *   <li>{@code _carplay-ctrl._tcp} 的 TXT {@code id} —— 车机靠它匹配「刚配对的那台手机」，
 *       缺了就不发 ctrl 邀请，{@code 0x4301} 永远不到</li>
 * </ol>
 *
 * <p>Android 11+ 对普通应用封闭了它：{@code BluetoothAdapter.getAddress()} 只回
 * {@code 02:00:00:00:00:00}，{@code Settings.Secure.bluetooth_address} 也不再可读。
 * 所以按四级解析，靠前的优先：
 * <ol>
 *   <li>adapter.getAddress() —— Android ≤10 或部分 OEM 仍可用</li>
 *   <li>Settings.Secure / Settings.Global —— 少数系统仍可读</li>
 *   <li>root shell（{@code su -c settings get ...}）—— 有 root 时无需额外 App</li>
 *   <li>用户手填 —— 车机蓝牙列表里能看到本机地址，填一次永久记住</li>
 * </ol>
 *
 * <p>实时读到的值会缓存并记下来，之后没有 root 也能复用；手填只在实时值不存在时生效
 * （实时值永远优先）。手填地址会校验：拒绝占位值、全 0、组播位、本地管理位。
 */
public final class BtIdentity {

    /** Android 11+ 给普通应用的占位地址。 */
    public static final String PLACEHOLDER_MAC = "02:00:00:00:00:00";

    private static final String PREFS = "bt_identity";
    private static final String KEY_MANUAL = "manual_mac";
    private static final String KEY_REMEMBERED = "remembered_mac";
    private static final Pattern MAC = Pattern.compile("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}");

    /** 解析来源。 */
    public enum Source {
        ADAPTER("系统接口"),
        SETTINGS("系统设置"),
        ROOT("root 读取"),
        MANUAL("手动填写"),
        REMEMBERED("此前读取并保存"),
        NONE("未获取");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        boolean isLive() {
            return this == ADAPTER || this == SETTINGS || this == ROOT;
        }
    }

    /** 一次解析结果。{@link #mac} 为 null 表示拿不到。 */
    public static final class Resolved {
        public final String mac;
        public final Source source;

        Resolved(String mac, Source source) {
            this.mac = mac;
            this.source = source;
        }

        public boolean ok() {
            return mac != null;
        }

        /** 界面用的一行摘要。 */
        public String describe() {
            return mac == null ? "未获取" : mac + "（" + source.label() + "）";
        }
    }

    private static volatile Resolved cached;
    private static volatile long lastAttemptAt;
    private static final long RETRY_QUIET_MS = 10_000L;

    private BtIdentity() {
    }

    /** 解析（带 10 秒静默窗口，避免每次都开 shell）。 */
    public static Resolved resolve(Context ctx) {
        return resolve(ctx, false);
    }

    /**
     * @param forceRetry true = 忽略静默窗口立刻重试（用户刚点「重新读取」时用）
     */
    public static Resolved resolve(Context ctx, boolean forceRetry) {
        Resolved hit = cached;
        if (hit != null && hit.source.isLive()) return hit;
        long now = System.currentTimeMillis();
        if (!forceRetry && now - lastAttemptAt < RETRY_QUIET_MS) return stored(ctx);
        synchronized (BtIdentity.class) {
            hit = cached;
            if (hit != null && hit.source.isLive()) return hit;
            lastAttemptAt = now;
            Resolved live = live(ctx);
            Resolved result = choose(live, manualMac(ctx), rememberedMac(ctx));
            cached = result.source == Source.NONE ? null : result;
            return result;
        }
    }

    /** 只读缓存/手填，不开 shell；UI 线程可调用。 */
    public static Resolved peek(Context ctx) {
        Resolved hit = cached;
        if (hit != null) return hit;
        return choose(null, manualMac(ctx), rememberedMac(ctx));
    }

    /** 手填地址；非法返回 false 且不写入。传 null 清除。 */
    public static boolean setManualMac(Context ctx, String input) {
        SharedPreferences p = prefs(ctx);
        if (input == null || input.trim().isEmpty()) {
            p.edit().remove(KEY_MANUAL).apply();
            invalidateManual();
            return true;
        }
        String mac = normalize(input);
        if (mac == null) return false;
        p.edit().putString(KEY_MANUAL, mac).apply();
        invalidateManual();
        return true;
    }

    public static String manualMac(Context ctx) {
        String v = prefs(ctx).getString(KEY_MANUAL, null);
        return v == null ? null : normalize(v);
    }

    public static String rememberedMac(Context ctx) {
        String v = prefs(ctx).getString(KEY_REMEMBERED, null);
        return v == null ? null : normalize(v);
    }

    /**
     * 规范化并校验地址：接受 {@code AA:BB:..}、{@code AA-BB-..}、空格分隔或 12 位连续十六进制。
     * 拒绝占位值、全 0/全 F、组播位与本地管理位（手填填错一个字符就会让车机永远不邀请）。
     */
    public static String normalize(String input) {
        return com.carbridge.carplay.util.MacText.canonicalStrict(input);
    }

    // ---------------------------------------------------------------- 内部

    private static Resolved live(Context ctx) {
        String mac = viaAdapter();
        if (mac != null) return new Resolved(mac, Source.ADAPTER);
        mac = viaSettings(ctx);
        if (mac != null) return new Resolved(mac, Source.SETTINGS);
        mac = viaRoot();
        if (mac != null) return new Resolved(mac, Source.ROOT);
        return null;
    }

    private static String viaAdapter() {
        try {
            BluetoothAdapter a = Bt.adapterDeprecated();
            if (a == null) return null;
            return normalize(a.getAddress());
        } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("BtIdentity", t);
            return null;
        }
    }

    private static String viaSettings(Context ctx) {
        if (ctx == null) return null;
        String v = read(ctx, true);
        if (v != null) return v;
        return read(ctx, false);
    }

    private static String read(Context ctx, boolean secure) {
        try {
            Object v = secure
                    ? Settings.Secure.getString(ctx.getContentResolver(), "bluetooth_address")
                    : Settings.Global.getString(ctx.getContentResolver(), "bluetooth_address");
            return v == null ? null : normalize((String) v);
        } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("BtIdentity", t);
            return null;
        }
    }

    /** 有 root 时用 shell 身份读系统设置；没有 su / 用户拒绝都会在 1.5s 内返回 null。 */
    private static String viaRoot() {
        String[] cmds = {
                "settings get secure bluetooth_address",
                "settings get global bluetooth_address",
        };
        for (String cmd : cmds) {
            String out = execSu(cmd);
            if (out != null) return out;
        }
        return null;
    }

    private static String execSu(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) {
                p.destroy();
                return null;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            if (line == null) return null;
            Matcher m = MAC.matcher(line);
            return m.find() ? normalize(m.group()) : null;
        } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("BtIdentity", t);
            return null;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("BtIdentity", t);
                }
            }
        }
    }

    /** 实时值优先；否则手填；否则此前保存的实时值。 */
    private static Resolved choose(Resolved live, String manual, String remembered) {
        if (live != null) return live;
        if (manual != null) return new Resolved(manual, Source.MANUAL);
        if (remembered != null) return new Resolved(remembered, Source.REMEMBERED);
        return new Resolved(null, Source.NONE);
    }

    private static Resolved stored(Context ctx) {
        Resolved hit = cached;
        if (hit != null) return hit;
        Resolved r = choose(null, manualMac(ctx), rememberedMac(ctx));
        if (r.source != Source.NONE) cached = r;
        return r;
    }

    private static void invalidateManual() {
        Resolved hit = cached;
        if (hit != null && hit.source.isLive()) return;   // 实时值不受手填影响
        cached = null;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 解析成功时把实时值记一份，之后没 root 也能用。 */
    public static void remember(Context ctx, Resolved r) {
        if (ctx == null || r == null || r.mac == null) return;
        if (!r.source.isLive()) return;
        prefs(ctx).edit().putString(KEY_REMEMBERED, r.mac).apply();
    }

    /** 蓝牙是否可用（UI 判断用；不发权限请求）。 */
    public static boolean bluetoothOn(Context ctx) {
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            return bm != null && bm.getAdapter() != null && bm.getAdapter().isEnabled();
        } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("BtIdentity", t);
            return false;
        }
    }
}
