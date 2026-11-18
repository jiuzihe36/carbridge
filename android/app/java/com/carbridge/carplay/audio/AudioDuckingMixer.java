package com.carbridge.carplay.audio;

/**
 * 音乐 + 导航语音的手机侧混音器（带 ducking 增益包络）。
 *
 * <p>为什么在手机侧混：CarLife 把音乐(TTS/语音) 分成三条通道，真 iPhone 会由车机把音乐压低
 * 再播导航；但接收端对 {@code duckAudio} 和「第二条音频流」的支持很不可靠（mcarplay
 * 2026-10 实测：有的车机接受 SETUP 却完全不消费，6.5 秒后把整条会话拆掉）。
 * 所以把三路 PCM 在编码前混成一路，是对所有档位都成立的做法。
 *
 * <p>三条硬约束（都是踩过的）：
 * <ul>
 *   <li><b>绝不丢音乐样本</b>：RTP 时间戳按已发样本推进，丢样本会让时间轴比墙钟落后，
 *       累计到缓冲上限后每个包都迟到 —— 现场表现是「导航响过一次后整个会话没声音」；</li>
 *   <li><b>增益不能跳变</b>：逐样本线性走到目标增益，否则会有咔哒声；</li>
 *   <li><b>相加要限幅</b>：限幅计数暴露出来，方便判断是不是混得太狠。</li>
 * </ul>
 *
 * <p>纯逻辑（无 Android 依赖），可以进 JVM 单测。
 */
public final class AudioDuckingMixer {

    /** 导航语音把音乐压低多少（-15dB ≈ 0.178 倍）。 */
    public static final float DEFAULT_DUCK_GAIN = 0.178f;
    /** 压低/恢复的过渡时长，避免门限式突变的咔哒声。 */
    private static final int ATTACK_FRAMES = (int) (0.15 * 44100);   // 150ms
    private static final int RELEASE_FRAMES = (int) (0.30 * 44100);  // 300ms
    /** 提示音结束后再保持压低这么久，避免 TTS 按句分段的间隙里音乐来回起伏。 */
    private static final int HOLD_FRAMES = (int) (0.20 * 44100);

    private final float duckGain;
    /** 待混入的导航语音（立体声帧，short 交错）。 */
    private short[] prompt = new short[44100 * 2];
    private int promptFrames;
    /** 当前音乐增益与目标。 */
    private float gain = 1f;
    private float targetGain = 1f;
    private int holdFrames;
    private long clipped;
    private long promptOverflow;

    public AudioDuckingMixer() {
        this(DEFAULT_DUCK_GAIN);
    }

    public AudioDuckingMixer(float duckGain) {
        this.duckGain = Math.max(0.01f, Math.min(1f, duckGain));
    }

    /** 送一段导航语音（44.1k 立体声，short 交错的小端字节）。 */
    public synchronized void submitPrompt(byte[] pcm) {
        if (pcm == null || pcm.length < 4) return;
        int frames = pcm.length / 4;
        ensurePrompt(frames);
        for (int i = 0; i < frames; i++) {
            prompt[(promptFrames + i) * 2] = (short) ((pcm[i * 4] & 0xFF) | (pcm[i * 4 + 1] << 8));
            prompt[(promptFrames + i) * 2 + 1] = (short) ((pcm[i * 4 + 2] & 0xFF) | (pcm[i * 4 + 3] << 8));
        }
        promptFrames += frames;
        targetGain = duckGain;
        holdFrames = HOLD_FRAMES;
        // 提示音缓存上限 3 秒：超了丢最旧的，别让内存无限涨
        int maxFrames = 44100 * 3;
        if (promptFrames > maxFrames) {
            int drop = promptFrames - maxFrames;
            System.arraycopy(prompt, drop * 2, prompt, 0, (promptFrames - drop) * 2);
            promptFrames -= drop;
            promptOverflow += drop;
        }
    }

    /**
     * 把待播的导航语音混进一段音乐，返回**与输入等长**的 PCM。
     *
     * <p>等长很关键：调用方按这段的长度推进 RTP 时间戳，长度变了时间轴就漂。
     */
    public synchronized byte[] mixMusic(byte[] musicPcm, int len) {
        if (musicPcm == null || len < 4) return musicPcm;
        int frames = len / 4;
        byte[] out = new byte[len];
        int consumed = 0;
        for (int i = 0; i < frames; i++) {
            stepGain();
            int l = readShort(musicPcm, i * 4);
            int r = readShort(musicPcm, i * 4 + 2);
            l = (int) (l * gain);
            r = (int) (r * gain);
            if (consumed < promptFrames) {
                l += prompt[consumed * 2];
                r += prompt[consumed * 2 + 1];
                consumed++;
            }
            if (l > 32767 || l < -32768 || r > 32767 || r < -32768) clipped++;
            writeShort(out, i * 4, clamp(l));
            writeShort(out, i * 4 + 2, clamp(r));
        }
        // 尾部不足一帧的字节原样带过去：丢掉的会在车机上变成一声「咔」
        int tail = len - frames * 4;
        if (tail > 0) {
            System.arraycopy(musicPcm, frames * 4, out, frames * 4, tail);
        }
        if (consumed > 0) {
            System.arraycopy(prompt, consumed * 2, prompt, 0, (promptFrames - consumed) * 2);
            promptFrames -= consumed;
            holdFrames = HOLD_FRAMES;
        }
        // 没有待播提示且保持期过了 → 音乐淡回原音量
        if (promptFrames == 0) {
            if (holdFrames > 0) {
                holdFrames -= frames;
            } else {
                targetGain = 1f;
            }
        }
        return out;
    }

    /** 音乐静止时把提示音单独取出来（不经过音乐）。 */
    public synchronized byte[] drainPrompt() {
        if (promptFrames <= 0) return null;
        byte[] out = new byte[promptFrames * 4];
        for (int i = 0; i < promptFrames; i++) {
            writeShort(out, i * 4, prompt[i * 2]);
            writeShort(out, i * 4 + 2, prompt[i * 2 + 1]);
        }
        int frames = promptFrames;
        promptFrames = 0;
        holdFrames = HOLD_FRAMES;
        return out;
    }

    public synchronized boolean hasPrompt() {
        return promptFrames > 0;
    }

    public synchronized int pendingFrames() {
        return promptFrames;
    }

    public synchronized long clippedSamples() {
        return clipped;
    }

    public synchronized long overflowFrames() {
        return promptOverflow;
    }

    /** 当前音乐增益（诊断用）。 */
    public synchronized float currentGain() {
        return gain;
    }

    private void stepGain() {
        if (gain < targetGain) {
            gain = Math.min(targetGain, gain + (1f - duckGain) / ATTACK_FRAMES);
        } else if (gain > targetGain) {
            gain = Math.max(targetGain, gain - (1f - duckGain) / RELEASE_FRAMES);
        }
    }

    private void ensurePrompt(int extraFrames) {
        int need = (promptFrames + extraFrames) * 2;
        if (need <= prompt.length) return;
        int cap = Math.max(need, prompt.length * 2);
        short[] bigger = new short[cap];
        System.arraycopy(prompt, 0, bigger, 0, promptFrames * 2);
        prompt = bigger;
    }

    private static int readShort(byte[] b, int off) {
        return (short) ((b[off] & 0xFF) | (b[off + 1] << 8));
    }

    private static void writeShort(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static int clamp(int v) {
        return v > 32767 ? 32767 : (v < -32768 ? -32768 : v);
    }
}
