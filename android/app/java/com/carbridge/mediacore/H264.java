package com.carbridge.mediacore;

import java.util.ArrayList;
import java.util.List;

/**
 * H.264 Annex-B 工具：NAL 拆分、关键帧判断、Annex-B→AVCC 转换、SPS 尺寸解析。
 *
 * CarLife 的 VIDEO 通道是 Annex-B 码流；CarPlay 要 AVCC（每个 NAL 前 4 字节大端长度）。
 * 纯 Java，不依赖 Android API，方便在桌面 JVM 上单测。
 */
public final class H264 {

    public static final int NAL_SLICE = 1;
    public static final int NAL_IDR = 5;
    public static final int NAL_SEI = 6;
    public static final int NAL_SPS = 7;
    public static final int NAL_PPS = 8;
    public static final int NAL_AUD = 9;

    private H264() {
    }

    /** 拆分 Annex-B 为不带起始码的 NAL 列表。 */
    public static List<byte[]> splitNals(byte[] annexB) {
        List<int[]> starts = new ArrayList<int[]>();   // {startOffset, startCodeLen}
        int i = 0;
        while (i + 3 < annexB.length) {
            if (annexB[i] == 0 && annexB[i + 1] == 0
                    && (annexB[i + 2] == 1
                        || (annexB[i + 2] == 0 && i + 3 < annexB.length && annexB[i + 3] == 1))) {
                int scLen = (annexB[i + 2] == 1) ? 3 : 4;
                starts.add(new int[]{i + scLen, scLen});
                i += scLen;
            } else {
                i++;
            }
        }
        List<byte[]> out = new ArrayList<byte[]>(starts.size());
        for (int k = 0; k < starts.size(); k++) {
            int start = starts.get(k)[0];
            int end = (k + 1 < starts.size()) ? starts.get(k + 1)[0] - starts.get(k + 1)[1] : annexB.length;
            if (end > start) {
                byte[] nal = new byte[end - start];
                System.arraycopy(annexB, start, nal, 0, nal.length);
                out.add(nal);
            }
        }
        return out;
    }

    public static List<Integer> nalTypes(byte[] annexB) {
        List<Integer> out = new ArrayList<Integer>(8);
        for (byte[] nal : splitNals(annexB)) {
            if (nal.length > 0) out.add(nal[0] & 0x1F);
        }
        return out;
    }

    public static boolean isKeyFrame(byte[] annexB) {
        return nalTypes(annexB).contains(NAL_IDR);
    }

    public static boolean hasSps(byte[] annexB) {
        return nalTypes(annexB).contains(NAL_SPS);
    }

    /**
     * Annex-B → AVCC：每个 NAL 前加 4 字节大端长度，丢弃 AUD。
     *
     * @return null 表示没有可用 NAL。
     */
    public static byte[] annexBToAvcc(byte[] annexB) {
        List<byte[]> kept = new ArrayList<byte[]>();
        int total = 0;
        for (byte[] nal : splitNals(annexB)) {
            if (nal.length == 0) continue;
            if ((nal[0] & 0x1F) == NAL_AUD) continue;
            kept.add(nal);
            total += 4 + nal.length;
        }
        if (kept.isEmpty()) return null;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] nal : kept) {
            out[off] = (byte) (nal.length >>> 24);
            out[off + 1] = (byte) (nal.length >>> 16);
            out[off + 2] = (byte) (nal.length >>> 8);
            out[off + 3] = (byte) nal.length;
            off += 4;
            System.arraycopy(nal, 0, out, off, nal.length);
            off += nal.length;
        }
        return out;
    }

    /** 构造 avcC：`01 sps[1] sps[2] sps[3] FF E1 <len16> SPS 01 <len16> PPS`。 */
    public static byte[] avcC(byte[] sps, byte[] pps) {
        int size = 5 + 3 + sps.length + 3 + pps.length;
        byte[] out = new byte[size];
        int o = 0;
        out[o++] = 0x01;
        out[o++] = sps[1];
        out[o++] = sps[2];
        out[o++] = sps[3];
        out[o++] = (byte) 0xFF;
        out[o++] = (byte) 0xE1;
        out[o++] = (byte) (sps.length >> 8);
        out[o++] = (byte) sps.length;
        System.arraycopy(sps, 0, out, o, sps.length);
        o += sps.length;
        out[o++] = 0x01;
        out[o++] = (byte) (pps.length >> 8);
        out[o++] = (byte) pps.length;
        System.arraycopy(pps, 0, out, o, pps.length);
        return out;
    }

    /** 解析 SPS 得到 {width, height}；失败返回 null。 */
    public static int[] spsDimensions(byte[] spsNal) {
        try {
            byte[] rbsp = unescape(spsNal, 1);
            BitReader r = new BitReader(rbsp);
            int profileIdc = r.u(8);
            r.u(8);
            r.u(8);
            r.ue(); // seq_parameter_set_id

            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244
                    || profileIdc == 44 || profileIdc == 83 || profileIdc == 86 || profileIdc == 118
                    || profileIdc == 128 || profileIdc == 138 || profileIdc == 139 || profileIdc == 134
                    || profileIdc == 135) {
                int chromaFormatIdc = r.ue();
                if (chromaFormatIdc == 3) r.u(1);
                r.ue();
                r.ue();
                r.u(1);
                if (r.u(1) == 1) {
                    int n = (chromaFormatIdc != 3) ? 8 : 12;
                    for (int i = 0; i < n; i++) {
                        if (r.u(1) == 1) skipScalingList(r, i < 6 ? 16 : 64);
                    }
                }
            }

            r.ue(); // log2_max_frame_num_minus4
            int pocType = r.ue();
            if (pocType == 0) {
                r.ue();
            } else if (pocType == 1) {
                r.u(1);
                r.se();
                r.se();
                int num = r.ue();
                for (int i = 0; i < num; i++) r.se();
            }
            r.ue(); // max_num_ref_frames
            r.u(1); // gaps_in_frame_num_value_allowed_flag

            int picWidthInMbs = r.ue() + 1;
            int picHeightInMapUnits = r.ue() + 1;
            int frameMbsOnly = r.u(1);
            if (frameMbsOnly == 0) r.u(1);
            r.u(1); // direct_8x8_inference_flag

            int cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
            if (r.u(1) == 1) {
                cropLeft = r.ue();
                cropRight = r.ue();
                cropTop = r.ue();
                cropBottom = r.ue();
            }

            int width = picWidthInMbs * 16 - (cropLeft + cropRight) * 2;
            int height = (2 - frameMbsOnly) * picHeightInMapUnits * 16 - (cropTop + cropBottom) * 2;
            if (width <= 0 || height <= 0) return null;
            return new int[]{width, height};
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 内部

    private static void skipScalingList(BitReader r, int size) {
        int lastScale = 8;
        int nextScale = 8;
        for (int j = 0; j < size; j++) {
            if (nextScale != 0) {
                int delta = r.se();
                nextScale = (lastScale + delta + 256) % 256;
            }
            if (nextScale != 0) lastScale = nextScale;
        }
    }

    private static byte[] unescape(byte[] nal, int from) {
        byte[] out = new byte[nal.length];
        int o = 0;
        int zeros = 0;
        for (int i = from; i < nal.length; i++) {
            int b = nal[i] & 0xFF;
            if (zeros >= 2 && b == 0x03) {
                zeros = 0;
                continue;
            }
            out[o++] = (byte) b;
            zeros = (b == 0) ? zeros + 1 : 0;
        }
        byte[] trimmed = new byte[o];
        System.arraycopy(out, 0, trimmed, 0, o);
        return trimmed;
    }

    private static final class BitReader {
        private final byte[] data;
        private int pos;

        BitReader(byte[] data) {
            this.data = data;
        }

        int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) v = (v << 1) | bit();
            return v;
        }

        int ue() {
            int zeros = 0;
            while (bit() == 0) zeros++;
            if (zeros == 0) return 0;
            return (1 << zeros) - 1 + u(zeros);
        }

        int se() {
            int k = ue();
            return (k % 2 == 0) ? -(k / 2) : (k + 1) / 2;
        }

        private int bit() {
            int byteIndex = pos >>> 3;
            if (byteIndex >= data.length) throw new IndexOutOfBoundsException("SPS 越界");
            int b = (data[byteIndex] >>> (7 - (pos & 7))) & 1;
            pos++;
            return b;
        }
    }
}
