package com.carbridge.carplay.core;

/**
 * CarPlay 时间同步（AirPlay NTP over RTCP）。
 */
public final class TimingResponder {

    /** 1900-01-01 到 1970-01-01 的秒数。 */
    public static final long NTP_EPOCH_OFFSET = 2208988800L;

    private TimingResponder() {
    }

    /** 是否像一条 timing 请求。 */
    public static boolean isTimingRequest(byte[] packet) {
        return packet != null && packet.length >= 32 && (packet[1] & 0xFF) == 0xD2;
    }

    /** 构造回复。 */
    public static byte[] reply(byte[] request, long nowNtp) {
        long origin = readLongBE(request, 24);
        byte[] out = new byte[32];
        out[0] = (byte) 0x80;
        out[1] = (byte) 0xD3;
        out[2] = 0x00;
        out[3] = 0x07;
        putLongBE(out, 4, 0L);        // rtp_ts
        putLongBE(out, 8, origin);    // t1 = request.t3
        putLongBE(out, 16, nowNtp);   // t2
        putLongBE(out, 24, nowNtp);   // t3
        return out;
    }

    /** 当前 NTP 32.32（用 System.nanoTime，单调）。 */
    public static long ntpNow(long startNanos) {
        long nanos = System.nanoTime() - startNanos;
        long seconds = nanos / 1000000000L;
        long frac = (nanos % 1000000000L) * 4294967296L / 1000000000L;
        return ((seconds + NTP_EPOCH_OFFSET) << 32) | (frac & 0xFFFFFFFFL);
    }

    private static void putLongBE(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (8 * (7 - i)));
    }

    private static long readLongBE(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
    }
}
