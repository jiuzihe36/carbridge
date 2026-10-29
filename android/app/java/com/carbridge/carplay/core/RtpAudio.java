package com.carbridge.carplay.core;

/**
 * CarPlay 音频流的 RTP 打包（UDP）。
 */
public final class RtpAudio {

    /** PCM 通路（type=100）。 */
    public static final int PAYLOAD_TYPE_PCM = 100;
    /** AAC-LC 通路（type=102）。 */
    public static final int PAYLOAD_TYPE_AAC = 102;

    /** PCM 每包 352 帧（= 1408 字节，44.1kHz 下约 7.99ms）。 */
    public static final int FRAMES_PER_PACKET = 352;
    /** AAC-LC 一包一帧，1024 采样。 */
    public static final int FRAMES_PER_PACKET_AAC = 1024;
    public static final int CHANNELS = 2;

    /** PCM 每包字节数（16bit）。 */
    public static final int BYTES_PER_PACKET = FRAMES_PER_PACKET * CHANNELS * 2;

    private final int payloadType;
    private int seq;

    public RtpAudio(int initialSeq) {
        this(initialSeq, PAYLOAD_TYPE_PCM);
    }

    public RtpAudio(int initialSeq, int payloadType) {
        this.seq = initialSeq & 0xFFFF;
        this.payloadType = payloadType;
    }

    public int payloadType() {
        return payloadType;
    }

    /** 打包一包：12 字节 RTP 头 + 载荷。 */
    public byte[] packet(byte[] payload, int offset, int length, long rtpTimestamp) {
        byte[] out = new byte[12 + length];
        out[0] = (byte) 0x80;
        out[1] = (byte) (payloadType & 0x7F);
        out[2] = (byte) (seq >>> 8);
        out[3] = (byte) seq;
        putIntBE(out, 4, (int) rtpTimestamp);
        putIntBE(out, 8, 0); // SSRC
        System.arraycopy(payload, offset, out, 12, length);
        seq = (seq + 1) & 0xFFFF;
        return out;
    }

    public int nextSeq() {
        return seq;
    }

    private static void putIntBE(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
