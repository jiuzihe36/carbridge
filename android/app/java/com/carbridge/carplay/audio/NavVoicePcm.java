package com.carbridge.carplay.audio;

/**
 * 导航语音（TTS/VR）PCM 的格式转换：16kHz 单声道 → 44.1kHz 立体声。
 *
 * <p>为什么要转：Car+ 的音乐音频是 44.1k 立体声（AirPlay 音频流协商的就是这个），
 * 而导航播报走的是另一条通道，格式是 **16kHz 单声道 16bit**。两者格式不同，
 * 直接混进同一条流会被车机当成噪声。
 *
 * <p>为什么放在纯类里：采样率换算最容易写错（差半个样本就有咔哒声），
 * 而这部分没有 Android 依赖，可以进 JVM 单测。
 */
public final class NavVoicePcm {

    public static final int SRC_RATE = 16000;
    public static final int DST_RATE = 44100;

    private NavVoicePcm() {
    }

    /**
     * 16k 单声道（小端 16bit）→ 44.1k 立体声（小端 16bit，左右相同）。
     *
     * <p>用线性插值而不是直接丢样本/重复样本：16k→44.1k 不是整数倍，
     * 重复样本会产生明显的金属噪声，线性插值足够平滑且开销极低。
     *
     * @return 立体声 PCM；输入非法时返回 null
     */
    public static byte[] mono16kToStereo44k(byte[] pcm) {
        if (pcm == null || pcm.length < 2) return null;
        int frames = pcm.length / 2;
        if (frames <= 0) return null;
        int outFrames = (int) ((long) frames * DST_RATE / SRC_RATE);
        if (outFrames <= 0) return null;
        byte[] out = new byte[outFrames * 4];   // 每帧 2 声道 × 2 字节

        for (int i = 0; i < outFrames; i++) {
            // 目标第 i 个样本对应源位置 = i * SRC/DST
            double srcPos = (double) i * SRC_RATE / DST_RATE;
            int i0 = (int) srcPos;
            int i1 = Math.min(i0 + 1, frames - 1);
            double frac = srcPos - i0;
            int s0 = readShort(pcm, i0);
            int s1 = readShort(pcm, i1);
            int v = (int) Math.round(s0 + (s1 - s0) * frac);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            writeShort(out, i * 4, v);         // L
            writeShort(out, i * 4 + 2, v);     // R
        }
        return out;
    }

    /** 一段 PCM 的时长（毫秒），用于日志与节流判断。 */
    public static int durationMs(int bytes, int rate, int channels, int bytesPerSample) {
        if (rate <= 0 || channels <= 0 || bytesPerSample <= 0) return 0;
        int frames = bytes / (channels * bytesPerSample);
        return (int) ((long) frames * 1000 / rate);
    }

    private static int readShort(byte[] b, int frame) {
        int off = frame * 2;
        if (off + 1 >= b.length) return 0;
        return (short) ((b[off] & 0xFF) | (b[off + 1] << 8));
    }

    private static void writeShort(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }
}
