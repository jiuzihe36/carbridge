package com.carbridge.carplay.core;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import com.carbridge.carplay.util.Diag;

/**
 * PCM → AAC-LC 编码器（CarPlay {@code type=102} MainHighAudio 通路）。
 */
public final class AacLcEncoder {

    /** AAC-LC 一帧固定 1024 个采样（每声道）。 */
    public static final int FRAME_SAMPLES = 1024;

    private static final String MIME = "audio/mp4a-latm";
    private static final long TIMEOUT_US = 10_000L;

    private final int sampleRate;
    private final int channels;
    private final int bitRate;
    private final int frameBytes;

    private MediaCodec codec;
    private boolean started;
    /** 编码器的 AudioSpecificConfig（decoder 需要它才能解）。 */
    private byte[] codecConfig;
    /** 一帧的小端 PCM 缓冲。 */
    private final byte[] frameBuf;
    private int frameFill;
    /** 上次剩下的半个样本（奇数长度输入用）。 */
    private final byte[] pendingByte = new byte[1];
    private int pendingLen;
    /** 编码输出的裸 AAC 帧。 */
    private final List<byte[]> out = new ArrayList<byte[]>();
    /** 累计喂进去的帧数 / 丢掉的帧数（诊断用）。 */
    private int queuedFrames;
    private int droppedFrames;

    public AacLcEncoder(int sampleRate, int channels, int bitRate) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitRate = bitRate;
        this.frameBytes = FRAME_SAMPLES * channels * 2;
        this.frameBuf = new byte[frameBytes];
    }

    /** 起编码器。失败抛异常（调用方要能降级回 PCM）。 */
    public synchronized void start() throws Exception {
        if (started) return;
        MediaFormat fmt = MediaFormat.createAudioFormat(MIME, sampleRate, channels);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameBytes * 2);
        codec = MediaCodec.createEncoderByType(MIME);
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        started = true;
        android.util.Log.i("carbridge", "AAC 编码器已启动: " + codec.getName()
                + " " + sampleRate + "Hz/" + channels + "ch/" + bitRate + "bps");
    }

    /** 编码器是否可用。 */
    public synchronized boolean isStarted() {
        return started;
    }

    /** 编码器的 AudioSpecificConfig（自检解码时要用）。 */
    public synchronized byte[] codecConfig() {
        return codecConfig;
    }

    /**
     * 喂一段 PCM（<strong>小端</strong>，与 Car+ MEDIA 通道一致），返回产出的裸 AAC 帧。
     *
     * <p>返回的 list 可能为空（这一段不够一帧，或编码器还在缓冲）。
     */
    public synchronized List<byte[]> encode(byte[] pcm, int len) {
        out.clear();
        if (!started || codec == null || pcm == null || len <= 0) {
            return new ArrayList<byte[]>();
        }
        try {
            int i = 0;
            if (pendingLen == 1 && len > 0) {
                // 上次剩了半个样本，补齐
                frameBuf[frameFill++] = pcm[0];
                frameBuf[frameFill++] = pendingByte[0];
                pendingLen = 0;
                i = 1;
                if (frameFill >= frameBytes) {
                    queueFrame();
                    frameFill = 0;
                }
            }
            for (; i + 1 < len; i += 2) {
                frameBuf[frameFill++] = pcm[i];
                frameBuf[frameFill++] = pcm[i + 1];
                if (frameFill >= frameBytes) {
                    queueFrame();
                    frameFill = 0;
                    // ⚠️ 必须**喂一帧抽一次**。攒着一起喂会耗尽编码器的输入缓冲
                    // （MediaCodec 输入缓冲通常只有 4~8 个），后面的帧全被丢掉。
                    drain();
                }
            }
            if (i < len) {
                pendingByte[0] = pcm[i];
                pendingLen = 1;
            }
            drain();
        } catch (Throwable t) {
            // 编码异常绝不能把音频线程带崩：停掉编码器，调用方可降级
            android.util.Log.w("carbridge", "AAC 编码异常: " + t, t);
            stop();
        }
        if (out.isEmpty() && queuedFrames > 0 && droppedFrames == 0) {
            android.util.Log.i("carbridge", "AAC 编码器喂了 " + queuedFrames
                    + " 帧却没吐出任何输出（dequeueOutputBuffer 没等到）");
        }
        // ⚠️ 必须返回**副本**：out 是内部复用列表，下次 encode() 会 clear() 它，
        //    直接返回会让调用方手里的列表凭空变空。
        return new ArrayList<byte[]>(out);
    }

    /** 停掉编码器。 */
    public synchronized void stop() {
        started = false;
        frameFill = 0;
        pendingLen = 0;
        if (codec != null) {
            try {
                codec.stop();
            } catch (Throwable tDiag) {
                Diag.w("AacLcEncoder.stop", tDiag);
            }
            try {
                codec.release();
            } catch (Throwable tDiag) {
                Diag.w("AacLcEncoder.stop#2", tDiag);
            }
            codec = null;
        }
    }

    // ---------------------------------------------------------------- 内部

    private void queueFrame() {
        if (codec == null) return;
        int idx = codec.dequeueInputBuffer(TIMEOUT_US);
        if (idx < 0) {
            // 没有空输入缓冲：先抽干输出再试一次
            drain();
            idx = codec.dequeueInputBuffer(TIMEOUT_US);
        }
        if (idx < 0) {
            if (droppedFrames < 3) {
                droppedFrames++;
                android.util.Log.i("carbridge", "AAC 编码器没有空输入缓冲，丢帧 " + droppedFrames);
            }
            return;   // 还是没有：丢掉这一帧（会丢音，但不至于卡死）
        }
        ByteBuffer in = codec.getInputBuffer(idx);
        if (in == null) {
            android.util.Log.i("carbridge", "AAC 编码器 getInputBuffer 返回 null");
            return;
        }
        in.clear();
        in.put(frameBuf, 0, frameBytes);
        codec.queueInputBuffer(idx, 0, frameBytes, System.nanoTime() / 1000L, 0);
        queuedFrames++;
    }

    private void drain() {
        if (codec == null) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int idx = codec.dequeueOutputBuffer(info, TIMEOUT_US);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue;
            }
            if (idx < 0) {
                continue;
            }
            try {
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // AudioSpecificConfig：不是音频帧，留着给自检解码用
                    if (info.size > 0) {
                        ByteBuffer cb = codec.getOutputBuffer(idx);
                        if (cb != null) {
                            codecConfig = new byte[info.size];
                            cb.position(info.offset);
                            cb.get(codecConfig);
                        }
                    }
                    continue;      // 释放交给下面的 finally（**只能释放一次**）
                }
                if (info.size > 0) {
                    ByteBuffer buf = codec.getOutputBuffer(idx);
                    if (buf != null) {
                        byte[] frame = new byte[info.size];
                        buf.position(info.offset);
                        buf.get(frame);
                        out.add(frame);
                    }
                }
            } finally {
                // ⚠️ 每个输出缓冲只能 release 一次。重复 release 会抛
                // 「client does not own the buffer #N」。
                codec.releaseOutputBuffer(idx, false);
            }
        }
    }
}
