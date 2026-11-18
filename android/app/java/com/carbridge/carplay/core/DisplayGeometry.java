package com.carbridge.carplay.core;

/**
 * 车机画布与 Car+ 编码参数的对齐规则。
 */
public final class DisplayGeometry {

    public static final int MAX_LONG_EDGE = 1920;
    public static final int MAX_FPS = 120;
    public static final int MIN_FPS = 20;
    public static final int MIN_DIM = 8;

    private DisplayGeometry() {
    }

    /**
     * 把车机画布缩放到 Car+ 可接受的范围。
     *
     * @return {width, height}；参数非法时返回 null
     */
    public static int[] fit(int width, int height) {
        if (width < MIN_DIM || height < MIN_DIM) {
            return null;
        }
        int max = Math.max(width, height);
        if (max <= MAX_LONG_EDGE) {
            // 保留车机的偶数宽高；奇数只可能是车机声明错误，向下取偶最安全。
            return new int[]{Math.max(MIN_DIM, width & ~1), Math.max(MIN_DIM, height & ~1)};
        }
        double scale = (double) MAX_LONG_EDGE / (double) max;
        int w = (int) Math.floor(width * scale);
        int h = (int) Math.floor(height * scale);
        w = Math.max(MIN_DIM, (w / 8) * 8);
        h = Math.max(MIN_DIM, (h / 8) * 8);
        return new int[]{w, h};
    }

    /** 车机声明的 maxFPS 夹到 Car+ 实际可用的范围。 */
    public static int clampFps(int fps) {
        if (fps <= 0) {
            return 30;
        }
        return Math.max(MIN_FPS, Math.min(MAX_FPS, fps));
    }

    /** 是否满足“精确原生画布”的约束（偶数、8..1920、1..60）。 */
    public static boolean isExact(int width, int height, int fps) {
        return width >= MIN_DIM && height >= MIN_DIM
                && width <= MAX_LONG_EDGE && height <= MAX_LONG_EDGE
                && (width & 1) == 0 && (height & 1) == 0
                && fps >= 1 && fps <= MAX_FPS;
    }
}
