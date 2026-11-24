package com.carbridge.carplay.core;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.util.List;
import com.carbridge.carplay.util.Diag;

/**
 * AAC-LC 编码器的<strong>自检</strong>：编一段正弦波，再解回来看峰值。
 */
public final class AacSelfTest {

    private AacSelfTest() {
    }

    /** 自检结果。 */
    public static final class Result {
        public final boolean ok;
        public final String detail;
        /** 编出来的 AAC 帧数。 */
        public final int frames;
        /** 解码后峰值（原始峰值是 20000）。 */
        public final int decodedPeak;

        Result(boolean ok, String detail, int frames, int decodedPeak) {
            this.ok = ok;
            this.detail = detail;
            this.frames = frames;
            this.decodedPeak = decodedPeak;
        }
    }

    /**
     * 跑一次自检。
     *
     * @param sampleRate 采样率（要和实际送流的一致）
     * @param channels   声道数
     */
    public static Result run(int sampleRate, int channels) {
        final int samples = sampleRate / 2;          // 0.5 秒
        final int inputPeak = 20000;
        AacLcEncoder enc = new AacLcEncoder(sampleRate, channels, 192000);
        try {
            enc.start();
        } catch (Throwable t) {
            return new Result(false, "编码器起不来: " + t, 0, 0);
        }
        try {
            // 440Hz 正弦波，小端 16bit 立体声（与 Car+ MEDIA 一致）
            byte[] pcm = new byte[samples * channels * 2];
            for (int i = 0; i < samples; i++) {
                short v = (short) (Math.sin(i * 2.0 * Math.PI * 440.0 / sampleRate) * inputPeak);
                for (int c = 0; c < channels; c++) {
                    int off = (i * channels + c) * 2;
                    pcm[off] = (byte) (v & 0xFF);
                    pcm[off + 1] = (byte) ((v >> 8) & 0xFF);
                }
            }
            List<byte[]> frames = enc.encode(pcm, pcm.length);
            // 编码器有缓冲，再喂一次把尾帧逼出来
            frames.addAll(enc.encode(new byte[0], 0));
            if (frames.isEmpty()) {
                return new Result(false, "编码器没有产出任何帧", 0, 0);
            }
            int peak = decodePeak(enc.codecConfig(), frames, sampleRate, channels);
            boolean ok = peak > inputPeak / 4;   // 至少还原出四分之一幅度
            return new Result(ok,
                    "编码 " + frames.size() + " 帧，解码峰值 " + peak + "（原始 " + inputPeak + "）"
                            + (ok ? " → 编码器正常" : " → 解码出来几乎是静音，编码器有问题"),
                    frames.size(), peak);
        } catch (Throwable t) {
            return new Result(false, "自检异常: " + t, 0, 0);
        } finally {
            enc.stop();
        }
    }

    /** 解码 AAC 帧，返回峰值幅度。 */
    private static int decodePeak(byte[] asc, List<byte[]> frames, int sampleRate, int channels) {
        if (asc == null || asc.length == 0) return 0;
        MediaCodec dec = null;
        try {
            MediaFormat fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(asc));
            dec = MediaCodec.createDecoderByType("audio/mp4a-latm");
            dec.configure(fmt, null, null, 0);
            dec.start();
            int peak = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (byte[] frame : frames) {
                int inIdx;
                while (true) {
                    inIdx = dec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) break;
                }
                ByteBuffer in = dec.getInputBuffer(inIdx);
                in.clear();
                in.put(frame);
                dec.queueInputBuffer(inIdx, 0, frame.length, 0, 0);
                // 抽干输出
                while (true) {
                    int out = dec.dequeueOutputBuffer(info, 10_000);
                    if (out == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (out < 0) continue;
                    if (info.size > 0) {
                        ByteBuffer ob = dec.getOutputBuffer(out);
                        if (ob != null) {
                            byte[] pcm = new byte[info.size];
                            ob.position(info.offset);
                            ob.get(pcm);
                            for (int i = 0; i + 1 < pcm.length; i += 2) {
                                int s = (short) ((pcm[i] & 0xFF) | ((pcm[i + 1] & 0xFF) << 8));
                                int a = Math.abs(s);
                                if (a > peak) peak = a;
                            }
                        }
                    }
                    dec.releaseOutputBuffer(out, false);
                }
            }
            return peak;
        } catch (Throwable t) {
            return 0;
        } finally {
            if (dec != null) {
                try {
                    dec.stop();
                } catch (Throwable tDiag) {
                    Diag.w("AacSelfTest.decodePeak", tDiag);
                }
                try {
                    dec.release();
                } catch (Throwable tDiag) {
                    Diag.w("AacSelfTest.decodePeak#2", tDiag);
                }
            }
        }
    }
}
