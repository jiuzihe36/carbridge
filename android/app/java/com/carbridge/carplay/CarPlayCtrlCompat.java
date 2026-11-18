package com.carbridge.carplay;

import android.content.Context;
import android.content.SharedPreferences;

import com.carbridge.carplay.core.AirPlaySetup;

/**
 * 发送端 AirPlay 版本号（一个值，三处同时生效）。
 *
 * <p>真 iPhone 在三个地方报同一个版本号：
 * <ol>
 *   <li>RTSP 的 {@code User-Agent: AirPlay/950.7.1}</li>
 *   <li>SETUP / ANNOUNCE 里的 {@code sourceVersion} / {@code srcvers} / {@code vv}</li>
 *   <li>{@code _carplay-ctrl._tcp} 的 TXT {@code srcvers}</li>
 * </ol>
 * 三者不同步就是「自相矛盾的发送端」。所以这里只留一个值，改一处全部跟着变。
 *
 * <p><b>默认 950.7.1 的依据：</b>2026-05-21 的真机抓包（CPC200-CCPA 记录）里，
 * iPhone 侧 {@code User-Agent} 是 {@code AirPlay/950.7.1}，它广播的 ctrl TXT 也是
 * {@code srcvers=950.7.1}。也就是说 950.7.1 就是当前 iPhone AirPlay 栈在用的值。
 *
 * <p>另一条独立证据：奥迪 MIB3 3315 的 A/B —— TXT 填 566.25.21 时车机连 ctrl-int
 * 探测都不发、0x4301 永不到达；填 950.7.1 时同一台车 0.65 秒内就下发。
 *
 * <p>值可在界面改（预设 + 自定义），改完下次连接生效，不用重新打包。
 */
public final class CarPlayCtrlCompat {

    /** 当前 iPhone 的 AirPlay 栈版本（真机抓包实证）。 */
    public static final String DEFAULT = "950.7.1";

    /** 旧版 carbridge 用的发送端栈版本（部分车机/接收端只认 566 系时回退用）。 */
    public static final String LEGACY_SENDER = "566.25.21";

    /** 老接收端（MIB2 一类）常见的 AirPlay 栈版本。 */
    public static final String LEGACY_RECEIVER = "320.17.1";

    /** 内置预设（界面直接列出来）。 */
    public static final String[] PRESETS = {DEFAULT, LEGACY_SENDER, LEGACY_RECEIVER};

    /** 旧名保留。 */
    public static final String MODE_950 = DEFAULT;
    public static final String MODE_FOLLOW = LEGACY_SENDER;

    private static final String PREFS = "ctrl_compat";
    private static final String KEY_VALUE = "srcvers_value";
    private static final String KEY_MODE_LEGACY = "srcvers_mode";

    private CarPlayCtrlCompat() {
    }

    /** 当前版本号（存的值，非法/空就回默认）。 */
    public static String srcvers(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String v = p.getString(KEY_VALUE, null);
        if (v == null) {
            // 旧版本只存了「模式」，迁移成具体值
            String legacy = p.getString(KEY_MODE_LEGACY, null);
            v = "follow".equals(legacy) ? legacy : DEFAULT;
            p.edit().putString(KEY_VALUE, v).apply();
        }
        return isValidValue(v) ? v : DEFAULT;
    }

    /** 把当前值写进 AirPlay 协议栈（User-Agent 与 SETUP 里的 sourceVersion 一起对齐）。 */
    public static void apply(Context ctx) {
        AirPlaySetup.setSourceVersion(srcvers(ctx));
    }

    /** 设置值；非法返回 false 且不写入。 */
    public static boolean setValue(Context ctx, String value) {
        if (value == null) return false;
        String v = value.trim();
        if (!isValidValue(v)) return false;
        prefs(ctx).edit().putString(KEY_VALUE, v).apply();
        AirPlaySetup.setSourceVersion(v);
        return true;
    }

    /** 界面显示用（带中文说明）。 */
    public static String label(Context ctx) {
        String v = srcvers(ctx);
        if (DEFAULT.equals(v)) return v + "（当前 iPhone）";
        if (LEGACY_SENDER.equals(v)) return v + "（旧版发送端）";
        if (LEGACY_RECEIVER.equals(v)) return v + "（老接收端）";
        return v + "（自定义）";
    }

    /** 预设的界面文案（与 {@link #PRESETS} 一一对应）。 */
    public static String presetLabel(Context ctx, String preset) {
        if (DEFAULT.equals(preset)) return preset + " · 当前 iPhone（推荐）";
        if (LEGACY_SENDER.equals(preset)) return preset + " · 旧版发送端";
        if (LEGACY_RECEIVER.equals(preset)) return preset + " · 老接收端";
        return preset;
    }

    /**
     * 版本号形状校验：{@code 950.7.1} / {@code 320.17} / {@code 566.25.21} 这类；
     * 只允许数字、字母、点、下划线、连字符，最长 32。
     */
    public static boolean isValidValue(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.isEmpty() || v.length() > 32) return false;
        return v.matches("[0-9A-Za-z._-]+");
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
