package com.carbridge.carplay.core;

/**
 * 车机经 CarPlay 事件通道回传的 HID 报告解析。
 */
public final class HidInput {

    /** 常见默认逻辑上限（未从描述符解析出时使用）。 */
    public static final int DEFAULT_LOGICAL_MAX = 32767;

    // Consumer keypad usages
    public static final int KEY_PLAY = 0xB0;
    public static final int KEY_PAUSE = 0xB1;
    public static final int KEY_SCAN_NEXT = 0xB5;
    public static final int KEY_SCAN_PREVIOUS = 0xB6;
    // Telephony keypad usages
    public static final int KEY_HOOK_SWITCH = 0x20;
    public static final int KEY_FLASH = 0x21;
    public static final int KEY_DROP = 0x26;

    private HidInput() {
    }

    /** 一次触摸状态。x/y 已归一化到 0..1。 */
    public static final class Touch {
        public final float x;
        public final float y;
        public final boolean down;

        public Touch(float x, float y, boolean down) {
            this.x = x;
            this.y = y;
            this.down = down;
        }

        @Override
        public String toString() {
            return "Touch{x=" + x + ", y=" + y + ", down=" + down + '}';
        }
    }

    /**
     * 解析触摸报告，自动兼容两种布局（**按长度区分**）：
     *
     * <ul>
     *   <li><b>多点触控</b>（长度为 6 的倍数，如 CarPlay 标准 2 指 12 字节、DiPlay）：
     *       每指 6 字节 {@code [触点ID, 按下, X_lo, X_hi, Y_lo, Y_hi]}，取第 0 指。</li>
     *   <li><b>单指</b>（5 字节）：{@code [按下, X_lo, X_hi, Y_lo, Y_hi]}。</li>
     * </ul>
     *
     * ⚠️ 两种布局**差 1 字节偏移**。用错会得到 down 恒 false + 坐标被 clamp 成 1.0
     * （实测 DiPlay：按单指布局解 12 字节多点报告 → (1.0,1.0) down=false）。
     *
     * @param logicalMaxX X 的逻辑上限（HID 描述符 logical maximum）
     * @param logicalMaxY Y 的逻辑上限——**与 X 可能不同**（奥迪 MIB3：1412 / 654）
     */
    public static Touch parseTouch(byte[] report, int logicalMaxX, int logicalMaxY) {
        if (report == null || report.length < 5) {
            throw new IllegalArgumentException("触摸报告至少 5 字节，实际 "
                    + (report == null ? 0 : report.length));
        }
        int maxX = logicalMaxX > 0 ? logicalMaxX : DEFAULT_LOGICAL_MAX;
        int maxY = logicalMaxY > 0 ? logicalMaxY : DEFAULT_LOGICAL_MAX;
        final boolean multiTouch = report.length >= 6 && (report.length % 6) == 0;
        // 多点触控：第 0 字节是触点 ID，第 1 字节 bit0 才是按下；单指：第 0 字节就是按下
        boolean down = (report[multiTouch ? 1 : 0] & 0x01) != 0;
        int off = multiTouch ? 2 : 1;   // X 的起始位置
        int x = (report[off] & 0xFF) | ((report[off + 1] & 0xFF) << 8);
        int y = (report[off + 2] & 0xFF) | ((report[off + 3] & 0xFF) << 8);
        return new Touch(clamp01(x / (float) maxX), clamp01(y / (float) maxY), down);
    }

    /** X/Y 共用同一逻辑上限。 */
    public static Touch parseTouch(byte[] report, int logicalMax) {
        return parseTouch(report, logicalMax, logicalMax);
    }

    /** 用默认逻辑上限（32767）。 */
    public static Touch parseTouch(byte[] report) {
        return parseTouch(report, DEFAULT_LOGICAL_MAX, DEFAULT_LOGICAL_MAX);
    }

    /** 单字节按键报告：返回 usage，或 -1。 */
    public static int parseKey(byte[] report) {
        if (report == null || report.length == 0) return -1;
        int usage = report[0] & 0xFF;
        return usage == 0 ? -1 : usage;
    }

    /** 逻辑值（0..logicalMax）线性换算到目标像素。 */
    public static int scaleLogical(int logical, int targetMax, int logicalMax) {
        int max = logicalMax > 0 ? logicalMax : DEFAULT_LOGICAL_MAX;
        int v = Math.max(0, Math.min(max, logical));
        return Math.round(v * (targetMax - 1) / (float) max);
    }

    /** 用默认逻辑上限（32767）换算。 */
    public static int scaleLogical(int logical, int targetMax) {
        return scaleLogical(logical, targetMax, DEFAULT_LOGICAL_MAX);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
