package com.carbridge.app;

import java.util.Locale;

/**
 * 实时指标的**纯格式化**（从 MainActivity.updateMetrics 抽出来的）。
 *
 * <p>为什么要单独一个类：这几行数字是最容易被"顺手改一下"的地方，而它们错了
 * 用户只会看到"数字不对"，日志里什么都没有。抽出来之后每个格式都有断言钉住。
 *
 * <p>约定：无数据一律返回 {@code null}，由界面层换成占位符（"—"）；
 * 这样本类不依赖 R.string，能在 JVM 里直接测。
 */
public final class MetricsText {

    private MetricsText() {
    }

    /** 帧率文本，如 {@code "12 fps"}；无数据返回 null。 */
    public static String fps(double fpsValue) {
        if (fpsValue <= 0) return null;
        return String.format(Locale.US, "%.0f fps", fpsValue);
    }

    /** 分辨率文本，如 {@code "1920×720"}；宽高非法返回 null。 */
    public static String resolution(int w, int h) {
        if (w <= 0 || h <= 0) return null;
        return w + "×" + h;
    }

    /**
     * 码率（Mbps）：用「累计字节 / 帧数 × 瞬时帧率」估算。
     *
     * <p>为什么不用累计时间：CarLife 的首帧前有几百毫秒空窗，用时间算会把码率压低。
     * 按帧平均再乘当前帧率，跟用户看到的画面质量更接近。
     */
    public static float mbps(long totalBytes, long frames, double fpsValue) {
        if (frames <= 0 || fpsValue <= 0 || totalBytes <= 0) return 0f;
        float bytesPerFrame = (float) totalBytes / Math.max(1L, frames);
        return bytesPerFrame * (float) fpsValue * 8f / 1000000f;
    }

    /** 码率文本，如 {@code "0.6 Mbps"}；无数据返回 null。 */
    public static String mbpsText(long totalBytes, long frames, double fpsValue) {
        if (frames <= 0 || fpsValue <= 0 || totalBytes <= 0) return null;
        return String.format(Locale.US, "%.1f Mbps", mbps(totalBytes, frames, fpsValue));
    }

    /**
     * 详情行：{@code 帧数 120 · 累计 3.4 MB · 首帧 1.20s}。
     *
     * <p>首帧延迟为 0 表示还没拿到首帧，这一项就不显示——显示 "0.00s" 会让人以为
     * 首帧是瞬间到的。
     */
    public static String detail(long frames, long totalBytes, long firstFrameDelayMs) {
        if (totalBytes <= 0) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("帧数 ").append(frames);
        sb.append("  ·  累计 ").append(String.format(Locale.US, "%.1f", totalBytes / 1048576.0)).append(" MB");
        if (firstFrameDelayMs > 0) {
            sb.append("  ·  首帧 ")
                    .append(String.format(Locale.US, "%.2f", firstFrameDelayMs / 1000.0)).append('s');
        }
        return sb.toString();
    }
}
