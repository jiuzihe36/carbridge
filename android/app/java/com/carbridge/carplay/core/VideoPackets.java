package com.carbridge.carplay.core;

import com.carbridge.mediacore.H264;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * CarPlay 视频数据通道的 128 字节帧头与小包构造。
 */
public final class VideoPackets {

    public static final int HEADER_SIZE = 128;

    public static final int OPCODE_FRAME = 0;
    public static final int OPCODE_CONFIG = 1;
    public static final int OPCODE_KEEPALIVE = 2;
    public static final int OPCODE_KEEPALIVE_STATS = 5;

    public static final int FLAG_SHOW_HUD = 0x01;
    public static final int FLAG_RESPECT_TIMESTAMPS = 0x02;
    public static final int FLAG_CONFIG = 0x04;

    /** KeepAlive+stats 的固定 body 长度（42 字节的 bplist 空字典）。 */
    public static final int STATS_BODY_SIZE = 42;

    private VideoPackets() {
    }

    /** VideoConfig：携带 avcC，明文。 */
    public static byte[] videoConfig(int width, int height, byte[] avcC) {
        byte[] out = new byte[HEADER_SIZE + avcC.length];
        putIntLE(out, 0, avcC.length);
        out[4] = (byte) OPCODE_CONFIG;
        out[6] |= (byte) FLAG_CONFIG;
        putFloatLE(out, 16, width);
        putFloatLE(out, 20, height);
        System.arraycopy(avcC, 0, out, HEADER_SIZE, avcC.length);
        return out;
    }

    /**
     * VideoFrame：一个 access unit（AVCC），载荷需由调用方加密。
     *
     * @param ntpTimestamp NTP 32.32（自流开始的相对时间），写入 params[0]
     */
    public static byte[] videoFrame(byte[] avcc, long ntpTimestamp) {
        byte[] out = new byte[HEADER_SIZE + avcc.length];
        putIntLE(out, 0, avcc.length);
        out[4] = (byte) OPCODE_FRAME;
        putLongLE(out, 8, ntpTimestamp);
        System.arraycopy(avcc, 0, out, HEADER_SIZE, avcc.length);
        return out;
    }

    /**
     * VideoFrame（**配对会话**）：载荷用 ChaCha20-Poly1305 加密，AAD=完整 128 字节头，
     * 头的 body_size = 密文长度（明文 + 16B tag）。
     *
     * @return 128 字节头 ‖ 密文 ‖ 16B tag
     */
    public static byte[] videoFrameSealed(byte[] avcc, long ntpTimestamp, AirPlayStreamCrypto sealer)
            throws java.security.GeneralSecurityException {
        byte[] header = new byte[HEADER_SIZE];
        putIntLE(header, 0, avcc.length + AirPlayStreamCrypto.TAG_LEN);
        header[4] = (byte) OPCODE_FRAME;
        putLongLE(header, 8, ntpTimestamp);
        byte[] sealed = sealer.encryptVideo(header, avcc);
        byte[] out = new byte[HEADER_SIZE + sealed.length];
        System.arraycopy(header, 0, out, 0, HEADER_SIZE);
        System.arraycopy(sealed, 0, out, HEADER_SIZE, sealed.length);
        return out;
    }

    /** 仅头部。 */
    public static byte[] keepAlive(boolean withStats) {
        if (!withStats) {
            byte[] out = new byte[HEADER_SIZE];
            out[4] = (byte) OPCODE_KEEPALIVE;
            return out;
        }
        byte[] out = new byte[HEADER_SIZE + STATS_BODY_SIZE];
        putIntLE(out, 0, STATS_BODY_SIZE);
        out[4] = (byte) OPCODE_KEEPALIVE_STATS;
        putFloatLE(out, HEADER_SIZE - 4, 42.0f);
        byte[] emptyDict = Bplist.write(new LinkedHashMap<String, Object>());
        int copy = Math.min(emptyDict.length, STATS_BODY_SIZE);
        System.arraycopy(emptyDict, 0, out, HEADER_SIZE, copy);
        return out;
    }

    /** 从 access unit（Annex-B）里抽 SPS/PPS 构造 avcC；缺任一返回 null。 */
    public static byte[] avcCFromAnnexB(byte[] accessUnit) {
        byte[] sps = null;
        byte[] pps = null;
        List<byte[]> nals = H264.splitNals(accessUnit);
        for (byte[] nal : nals) {
            if (nal.length == 0) continue;
            int t = nal[0] & 0x1F;
            if (t == H264.NAL_SPS) sps = nal;
            else if (t == H264.NAL_PPS) pps = nal;
        }
        if (sps == null || pps == null) return null;
        return H264.avcC(sps, pps);
    }

    /** 从 Annex-B 访问单元里取 SPS 解析出的 {宽, 高}；没有 SPS 返回 null。 */
    public static int[] spsDimensions(byte[] accessUnit) {
        if (accessUnit == null) return null;
        for (byte[] nal : H264.splitNals(accessUnit)) {
            if (nal.length > 0 && (nal[0] & 0x1F) == H264.NAL_SPS) {
                int[] d = H264.spsDimensions(nal);
                if (d != null) return d;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- LE 写入

    static void putIntLE(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    static void putLongLE(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (8 * i));
    }

    static void putFloatLE(byte[] b, int off, float v) {
        putIntLE(b, off, Float.floatToIntBits(v));
    }
}
