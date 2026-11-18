package com.carbridge.carplay.core;

import java.util.Locale;

/**
 * 「下一次连接」用多大分辨率、多少帧率的自适应决策。
 *
 * <p>输入车机画布、当前编码参数、本轮实测帧率和本轮已推流时长，输出一条建议
 * （w/h/fps + 中文原因）。纯 Java，不依赖 Android 也不碰编码器：实测帧率由调用方
 * 统计后传进来，本类只做判定、不持有状态，方便在 JVM 上单测。
 *
 * <p>判定顺序：会话时长 → 画布 → 帧率。三条规则互斥，一次调用最多只动一个维度
 * （要么换分辨率、要么换帧率），实车出问题时才归因得清楚。
 */
public final class AdaptiveResolution {

    /**
     * 会话短于 8 秒不下结论。
     *
     * <p>刚连上时编码器还在热身：首个 IDR 帧、码率爬坡、车机侧缓冲建立都会把前几秒的
     * 实测帧率拉低，这时候降档属于误判。8 秒大约覆盖两个关键帧间隔（常见 GOP 1~4s）
     * 加一次车机重缓冲，短于此的样本系统性偏低。
     */
    public static final long MIN_SESSION_SECONDS = 8L;

    /**
     * 实测帧率低于目标的 60% 就降一档。
     *
     * <p>60fps 掉到 36fps 以下时，车机上横向拖动的拖影已经很扎眼；再维持原档位只会让
     * 车机侧缓冲越积越多，最后表现为「越用越卡」。60% 也是「明显掉帧」与「调度抖动」
     * 之间的经验分界。
     */
    public static final int LOW_FPS_PERCENT = 60;

    /**
     * 实测帧率高于目标的 90% 才允许升一档。
     *
     * <p>留 10% 余量给编码器和系统调度抖动，避免刚好跑满时被偶尔的掉帧踢回降档。
     * 60%~90% 这段是死区（迟滞区间）：既不够差到该降、也不够好到该升，就不动，
     * 防止在临界点上来回抖，每次都触发重连。
     */
    public static final int HIGH_FPS_PERCENT = 90;

    /**
     * 帧率阶梯的最底档。
     *
     * <p>低于 24fps 的 CarPlay 画面在车机上已不可用（导航箭头拖动明显一顿一顿），
     * 而且编码器再往下降帧率省不了多少码率，收益远小于体验损失，所以 24 封底。
     */
    public static final int MIN_TIER_FPS = 24;

    /** 帧率阶梯，从高到低。只在这些档位之间走，避免出现 37fps 之类的碎片值。 */
    private static final int[] TIERS = {60, 45, 30, MIN_TIER_FPS};

    private AdaptiveResolution() {
    }

    /**
     * 给出下一次连接的建议参数。
     *
     * @param canvasW       车机声明的画布宽（px）
     * @param canvasH       车机声明的画布高（px）
     * @param canvasFps     车机声明的画布帧率
     * @param currentW      当前实际编码宽
     * @param currentH      当前实际编码高
     * @param currentFps    当前目标编码帧率（不是实测值）
     * @param measuredFps   本轮实测平均帧率（调用方统计；&lt;=0 表示没有有效采样）
     * @param sessionSeconds 本轮已推流时长（秒）
     * @return 建议；会话不足 {@link #MIN_SESSION_SECONDS} 秒、或画布与当前画布都无法使用时返回 null
     */
    public static Suggestion suggest(int canvasW, int canvasH, int canvasFps,
                                     int currentW, int currentH, int currentFps,
                                     double measuredFps, long sessionSeconds) {
        // 1) 样本太短：宁可不下结论，也不要拿热身的低帧率去降档。
        if (sessionSeconds < MIN_SESSION_SECONDS) {
            return null;
        }

        int[] canvas = DisplayGeometry.fit(canvasW, canvasH);
        boolean currentUsable = currentW >= DisplayGeometry.MIN_DIM
                && currentH >= DisplayGeometry.MIN_DIM;
        if (canvas == null && !currentUsable) {
            // 两边都没有可用画布，给不出任何有意义的建议（保持现状也等于没建议）。
            return null;
        }

        // 车机声明多少帧率，最多就用到多少；画布非法时退回 Car+ 自己的上限。
        int allowedMaxFps = canvas != null
                ? DisplayGeometry.clampFps(canvasFps)
                : DisplayGeometry.MAX_FPS;

        // 2) 画布优先：车机声明的原生模式是它自己验证过的，先对齐分辨率。
        //    帧率跟着画布一起采纳（同一套模式），不在换分辨率的同时再升降档，
        //    否则两个变量一起动，实车上分不清是谁带来的问题；帧率由下一轮规则收敛。
        if (canvas != null && (canvas[0] != currentW || canvas[1] != currentH)) {
            int fps = DisplayGeometry.clampFps(canvasFps);
            return new Suggestion(canvas[0], canvas[1], fps, true,
                    "车机画布 " + canvas[0] + "x" + canvas[1] + "@" + fps + "fps，当前 "
                            + currentW + "x" + currentH + "@" + currentFps
                            + "fps，下一次连接改用画布原生模式");
        }

        // 画布已对齐（或画布非法被忽略），这一刻的编码尺寸就是当前尺寸。
        int baseW = currentW;
        int baseH = currentH;

        // 3) 帧率：死区外才动，且只在阶梯上走一档。
        int targetFps = currentFps > 0 ? DisplayGeometry.clampFps(currentFps) : allowedMaxFps;
        if (measuredFps <= 0) {
            // 没有采样就不猜：保持，等下一轮有数据再说。
            return new Suggestion(baseW, baseH, targetFps, false,
                    "本轮没有有效帧率采样（" + fmt(measuredFps) + "fps），保持当前参数");
        }

        double ratio = measuredFps / (double) targetFps;
        int percent = (int) Math.round(ratio * 100.0);

        if (percent < LOW_FPS_PERCENT) {
            int next = downTier(targetFps);
            if (next < targetFps) {
                return new Suggestion(baseW, baseH, next, true,
                        "实测 " + fmt(measuredFps) + "fps 只有目标 " + targetFps + "fps 的 "
                                + percent + "%（<" + LOW_FPS_PERCENT + "%），降一档 "
                                + targetFps + "→" + next);
            }
            // 已经在底档，降无可降：保持，让调用方去查带宽/编码器，而不是继续压帧率。
            return new Suggestion(baseW, baseH, targetFps, false,
                    "实测 " + fmt(measuredFps) + "fps 低于目标 " + targetFps + "fps，但已在最低档 "
                            + MIN_TIER_FPS + "fps，保持");
        }

        if (percent >= HIGH_FPS_PERCENT) {
            int next = upTier(targetFps);
            if (next > targetFps && next <= allowedMaxFps) {
                return new Suggestion(baseW, baseH, next, true,
                        "实测 " + fmt(measuredFps) + "fps 达到目标 " + targetFps + "fps 的 "
                                + percent + "%（≥" + HIGH_FPS_PERCENT + "%）且车机允许到 "
                                + allowedMaxFps + "fps，升一档 " + targetFps + "→" + next);
            }
            if (next > targetFps) {
                return new Suggestion(baseW, baseH, targetFps, false,
                        "实测 " + fmt(measuredFps) + "fps 已跑满目标 " + targetFps
                                + "fps，但车机上限 " + allowedMaxFps + "fps，无法升档，保持");
            }
            // 已经是阶梯顶档。
            return new Suggestion(baseW, baseH, targetFps, false,
                    "实测 " + fmt(measuredFps) + "fps 跑满目标 " + targetFps
                            + "fps，已在最高档，保持");
        }

        return new Suggestion(baseW, baseH, targetFps, false,
                "实测 " + fmt(measuredFps) + "fps 处于目标 " + targetFps + "fps 的 "
                        + LOW_FPS_PERCENT + "%~" + HIGH_FPS_PERCENT + "% 死区，保持不动");
    }

    /** 阶梯上比 fps 低一档；已在底档或更低时原样返回。 */
    public static int downTier(int fps) {
        for (int i = 0; i < TIERS.length; i++) {
            if (TIERS[i] < fps) {
                return TIERS[i];
            }
        }
        return fps;
    }

    /** 阶梯上比 fps 高一档；已在顶档或更高时原样返回。 */
    public static int upTier(int fps) {
        for (int i = TIERS.length - 1; i >= 0; i--) {
            if (TIERS[i] > fps) {
                return TIERS[i];
            }
        }
        return fps;
    }

    /** 一位小数，固定用 US 格式，别让车机语言环境把小数点变成逗号。 */
    private static String fmt(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    /** 一条建议：下一次连接该用的参数 + 为什么（直接可以进日志/上报）。 */
    public static final class Suggestion {

        public final int width;
        public final int height;
        public final int fps;
        /** 与当前参数相比是否有变化；false 时调用方不必为它重连。 */
        public final boolean changed;
        public final String reason;

        public Suggestion(int width, int height, int fps, boolean changed, String reason) {
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.changed = changed;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return width + "x" + height + "@" + fps + "fps"
                    + (changed ? "" : "(不变)") + "：" + reason;
        }
    }
}
