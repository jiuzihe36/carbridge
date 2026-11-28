package com.carbridge.carplay;

import android.content.Context;
import android.content.SharedPreferences;

import com.carbridge.carplay.bt.Bt;

/**
 * 身份名的**单一真源**：iAP2 识别（0x1D01/0x4E09/0x4C01）、mDNS 实例名、
 * 以及（可选的）系统蓝牙适配器名，三处都从这里取，避免各写各的。
 *
 * <p>以前的问题：identify 里写死 {@code "iPhone"}，系统蓝牙名是用户的手机名，
 * mDNS 实例名又写死 {@code "iPhone"} —— 车机在设置页、配对列表、CarPlay 里
 * 看到的是三个不同的名字。
 *
 * <p>三种模式：
 * <ul>
 *   <li>{@link #MODE_FOLLOW}（默认）跟随本机蓝牙名 —— 三处天然一致；</li>
 *   <li>{@link #MODE_IPHONE} 固定 {@code "iPhone"}；</li>
 *   <li>{@link #MODE_CUSTOM} 自定义文本。</li>
 * </ul>
 * 另有一个「同时把系统蓝牙名改为该名称」开关：仅在显式打开时才会去改适配器名
 * （会改动用户手机全局蓝牙名，默认关）。
 */
public final class IdentityName {

    public static final String PREF = "carbridge_identity_name";
    public static final String KEY_MODE = "mode";
    public static final String KEY_CUSTOM = "custom";
    public static final String KEY_SYNC_BT = "sync_bt_name";

    public static final int MODE_FOLLOW = IdentityNamePolicy.MODE_FOLLOW;
    public static final int MODE_IPHONE = IdentityNamePolicy.MODE_IPHONE;
    public static final int MODE_CUSTOM = IdentityNamePolicy.MODE_CUSTOM;

    /** 兜底名：三种模式都取不到有效值时用它（车机侧看到的名字总不能是空的）。 */
    public static final String FALLBACK = IdentityNamePolicy.FALLBACK;

    private IdentityName() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static int mode(Context c) {
        // 默认 iPhone：本 App 的目的就是让车机把手机当 iPhone ——「跟随蓝牙名」虽然
        // 天然三处一致，但会把用户手机名（如 "ace5"）报给车机，反而丢了伪装。
        return prefs(c).getInt(KEY_MODE, MODE_IPHONE);
    }

    public static String custom(Context c) {
        String s = prefs(c).getString(KEY_CUSTOM, "");
        return s == null ? "" : s;
    }

    public static boolean syncSystemName(Context c) {
        return prefs(c).getBoolean(KEY_SYNC_BT, false);
    }

    public static void save(Context c, int mode, String custom, boolean syncBt) {
        prefs(c).edit()
                .putInt(KEY_MODE, mode)
                .putString(KEY_CUSTOM, custom == null ? "" : custom.trim())
                .putBoolean(KEY_SYNC_BT, syncBt)
                .apply();
    }

    /** 纯逻辑委托（可单测的部分在 {@link IdentityNamePolicy}）。 */
    public static String resolve(int mode, String custom, String btName) {
        return IdentityNamePolicy.resolve(mode, custom, btName);
    }

    /** 当前生效的身份名（读蓝牙名需要 BLUETOOTH_CONNECT，取不到就用兜底）。 */
    public static String resolve(Context c) {
        String bt = null;
        try {
            android.bluetooth.BluetoothAdapter a = Bt.adapter(c);
            if (a != null) bt = a.getName();
        } catch (Throwable t) {
            com.carbridge.carplay.util.Diag.w("IdentityName.adapterName", t);
        }
        return resolve(mode(c), custom(c), bt);
    }

    /** 界面上显示的一行说明。 */
    public static String describe(Context c) {
        int m = mode(c);
        if (m == MODE_IPHONE) {
            return syncSystemName(c) ? "iPhone（已同步系统蓝牙名）" : "iPhone";
        }
        if (m == MODE_CUSTOM) {
            return custom(c) + (syncSystemName(c) ? "（已同步系统蓝牙名）" : "");
        }
        return resolve(c) + "（跟随蓝牙名）";
    }

    /**
     * 若用户开启了「同步系统蓝牙名」，把适配器名改成身份名。
     *
     * <p>只在显式开关下执行；改不动（权限/系统拒绝）只记日志，不影响主链路。
     *
     * @return 是否真的改了
     */
    public static boolean applySystemNameIfEnabled(Context c) {
        if (!syncSystemName(c)) return false;
        String want = resolve(c);
        try {
            android.bluetooth.BluetoothAdapter a = Bt.adapter(c);
            if (a == null) return false;
            String now = a.getName();
            if (want.equals(now)) return false;
            boolean ok = a.setName(want);
            android.util.Log.i("carbridge", ok
                    ? "系统蓝牙名已改为「" + want + "」（原「" + now + "」）"
                    : "系统蓝牙名修改未生效（系统拒绝）");
            return ok;
        } catch (Throwable t) {
            // Android 12+ 需要 BLUETOOTH_CONNECT 运行时权限
            android.util.Log.w("carbridge", "修改系统蓝牙名失败: " + t);
            return false;
        }
    }
}
