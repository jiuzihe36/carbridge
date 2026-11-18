package com.carbridge.app;

import com.carbridge.carplay.core.DisplayGeometry;

/**
 * 画质参数的纯计算部分（从 MainActivity 抽出来的第一批）。
 *
 * <p>这里只放「给定输入就能算出结果」的东西：尺寸夹取、帧率档位解析、本次连接该请求什么参数。
 * 之所以先抽这些：它们没有 UI、没有 Context，改错了立刻会被单测和编译发现；
 * 带对话框和开关的那部分留在 Activity 里，等这一批稳定了再动。
 */
public final class QualityPrefs {

    /** 合法分辨率区间（比这更小的画布车机侧没法看，比这更大的没有车机支持）。 */
    public static final int MIN_DIM = 320;
    public static final int MAX_DIM = 3840;

    /** 可选帧率档位：CarLife 内容 30 帧足够，24 是低端车机的保底。 */
    public static final int[] FPS_OPTIONS = {20, 24, 30, 60, 90, 120};
    public static final int DEF_FPS_INDEX = 2;   // → 30

    private QualityPrefs() {
    }

    /** 越界夹到合法区间。 */
    public static int clampDim(int v) {
        if (v < MIN_DIM) return MIN_DIM;
        return v > MAX_DIM ? MAX_DIM : v;
    }

    /** 档位下标夹取（空数组返回 0，避免调用方再判一次）。 */
    public static int clampIndex(int i, int size) {
        if (size <= 0) return 0;
        if (i < 0) return 0;
        return i < size ? i : size - 1;
    }

    /** 文本框内容 → 合法尺寸；空/非法回落到默认值。 */
    public static int parseDim(String text, int fallback) {
        String s = text == null ? "" : text.trim();
        try {
            return clampDim(Integer.parseInt(s));
        } catch (NumberFormatException nfe) {
            return fallback;
        }
    }

    /**
     * 本次连接实际要请求的编码参数。
     *
     * <p>自动模式下优先用「车机 /info 声明的画布」——车机画布才是真正要看的那块屏，
     * 我们自己填的分辨率只是没有车机信息时的兜底。
     *
     * @return {width, height, fps}
     */
    public static int[] paramsForStart(boolean autoFit, int carW, int carH, int carFps,
                                       int encW, int encH, int fpsIndex) {
        int w = encW, h = encH;
        int f = FPS_OPTIONS[clampIndex(fpsIndex, FPS_OPTIONS.length)];
        if (autoFit && carW > 0 && carH > 0) {
            int[] fit = DisplayGeometry.fit(carW, carH);
            if (fit != null) {
                w = fit[0];
                h = fit[1];
                f = DisplayGeometry.clampFps(carFps);
            }
        }
        return new int[]{w, h, f};
    }
}
