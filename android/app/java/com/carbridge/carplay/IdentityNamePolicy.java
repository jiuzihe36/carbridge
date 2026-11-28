package com.carbridge.carplay;

/**
 * 身份名的**纯逻辑**（无 Android 依赖，可直接 JVM 单测）。
 *
 * <p>三种模式：跟随本机蓝牙名 / 固定 iPhone / 自定义文本。任何模式下取不到有效值
 * 都回落 {@link #FALLBACK}（车机侧看到的名字不能是空的）。
 */
public final class IdentityNamePolicy {

    public static final int MODE_FOLLOW = 0;
    public static final int MODE_IPHONE = 1;
    public static final int MODE_CUSTOM = 2;

    public static final String FALLBACK = "iPhone";

    private IdentityNamePolicy() {
    }

    /** 按「模式 + 自定义文本 + 当前蓝牙名」算出身份名。 */
    public static String resolve(int mode, String custom, String btName) {
        String bt = btName == null ? "" : btName.trim();
        switch (mode) {
            case MODE_IPHONE:
                return FALLBACK;
            case MODE_CUSTOM: {
                String s = custom == null ? "" : custom.trim();
                return s.isEmpty() ? FALLBACK : s;
            }
            case MODE_FOLLOW:
            default:
                return bt.isEmpty() ? FALLBACK : bt;
        }
    }
}
