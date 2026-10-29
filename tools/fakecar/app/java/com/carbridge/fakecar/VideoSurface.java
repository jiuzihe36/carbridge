package com.carbridge.fakecar;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.nio.ByteBuffer;

/**
 * 假车机的 H.264 解码渲染：MediaCodec 解屏幕流，画到 SurfaceView。
 */
public final class VideoSurface implements SurfaceHolder.Callback {

    public interface Log {
        void onLog(String line);
    }

    private final SurfaceView view;
    private final Log log;

    /** surface 由主线程（surfaceCreated/Destroyed）和视频线程（configureCodec）共享 → volatile。 */
    private volatile Surface surface;
    private MediaCodec codec;
    private boolean configured;
    private long frameTs;
    private int renderedFrames;
    private int fedFrames;
    /** 缓存的 SPS（含 00 00 00 01 起始码）——surface 重建时复用。 */
    private byte[] csd0;
    /** 缓存的 PPS（含起始码）。 */
    private byte[] csd1;
    private int width;
    private int height;

    public VideoSurface(SurfaceView view, Log log) {
        this.view = view;
        this.log = log;
        // ⚠️ 部分 ROM（Treble GSI 实测）挖洞失效：surface 被窗口背景盖住 → 黑屏。
        //    强制视频层置于窗口之上，绕过 punch-through。
        view.setZOrderOnTop(true);
        view.getHolder().setFormat(android.graphics.PixelFormat.OPAQUE);
        view.getHolder().addCallback(this);
    }

    // ------------------------------------------------------------- SurfaceHolder.Callback

    @Override
    public synchronized void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        emit("Surface 已创建" + (surface != null && surface.isValid() ? "（有效）" : "（无效）"));
        // 锁屏唤醒 / 切后台回来：用缓存的 csd 重新配解码器，画面即可恢复
        configureCodec();
    }

    @Override
    public synchronized void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public synchronized void surfaceDestroyed(SurfaceHolder holder) {
        emit("Surface 已销毁（锁屏/切后台），保留 csd 等重建");
        surface = null;
        releaseCodec();   // 只释放解码器，csd0/csd1/width/height 留着
    }

    // ------------------------------------------------------------- 视频喂入

    public synchronized void onConfig(byte[] avcC) {
        int[] sps = parseAvcC(avcC, true);
        int[] pps = parseAvcC(avcC, false);
        if (sps == null || pps == null) {
            emit("视频解码：avcC 解析失败（无 SPS/PPS）");
            return;
        }
        byte[] spsNal = new byte[sps[1]];
        System.arraycopy(avcC, sps[0], spsNal, 0, sps[1]);
        byte[] ppsNal = new byte[pps[1]];
        System.arraycopy(avcC, pps[0], ppsNal, 0, pps[1]);

        int[] dims = parseSps(spsNal);
        int w = dims[0] > 0 ? dims[0] : 1920;
        int h = dims[1] > 0 ? dims[1] : 720;

        // 缓存 csd（带起始码，Annex-B 对解码器最兼容），供 surface 重建复用
        byte[] sc = {(byte) 0, 0, 0, 1};
        csd0 = concat(sc, spsNal);
        csd1 = concat(sc, ppsNal);
        width = w;
        height = h;

        configureCodec();
    }

    public synchronized void onFrame(byte[] avcc) {
        if (!configured || codec == null) {
            // 兜底：surface 重建后还没重新配置（或之前 surface 无效没配上）
            if (csd0 != null && csd1 != null) {
                configureCodec();
            }
            if (!configured || codec == null) return;
        }
        try {
            // AVCC（4 字节长度）→ Annex-B（起始码），对解码器最兼容
            byte[] annexB = avccToAnnexB(avcc);
            if (annexB == null) return;
            int inIndex = codec.dequeueInputBuffer(10000);
            if (inIndex < 0) return;
            ByteBuffer inBuf = codec.getInputBuffer(inIndex);
            if (inBuf == null || inBuf.capacity() < annexB.length) {
                codec.queueInputBuffer(inIndex, 0, 0, 0, 0);
                return;
            }
            inBuf.clear();
            inBuf.put(annexB);
            codec.queueInputBuffer(inIndex, 0, annexB.length, frameTs++, 0);
            fedFrames++;
            if (fedFrames == 1 || fedFrames % 120 == 0) {
                emit("已喂给解码器 " + fedFrames + " 帧（渲染 " + renderedFrames + " 帧）");
            }
        } catch (Exception e) {
            emit("喂帧失败: " + e);
        }
        drainOutput();
    }

    /** 用缓存的 csd + 当前 surface 配置解码器。csd 没就绪或 surface 无效则跳过。 */
    private void configureCodec() {
        if (csd0 == null || csd1 == null) return;
        if (surface == null || !surface.isValid()) {
            emit("Surface 无效，解码器暂缓配置（等 surfaceCreated 兜底）");
            return;
        }
        releaseCodec();
        try {
            codec = MediaCodec.createDecoderByType("video/avc");
            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", width, height);
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(csd1));
            codec.configure(fmt, surface, null, 0);
            codec.start();
            configured = true;
            renderedFrames = 0;
            fedFrames = 0;
            emit("视频解码器已启动 " + width + "x" + height);
        } catch (Exception e) {
            emit("视频解码器配置失败: " + e);
            releaseCodec();
        }
    }

    /** AVCC（每 NAL 前 4 字节大端长度）→ Annex-B（00 00 00 01 起始码）。 */
    private static byte[] avccToAnnexB(byte[] avcc) {
        int nals = 0;
        int off = 0;
        while (off + 4 <= avcc.length) {
            int n = ((avcc[off] & 0xFF) << 24) | ((avcc[off + 1] & 0xFF) << 16)
                    | ((avcc[off + 2] & 0xFF) << 8) | (avcc[off + 3] & 0xFF);
            off += 4;
            if (n <= 0 || off + n > avcc.length) return null;
            off += n;
            nals++;
        }
        if (nals == 0 || off != avcc.length) return null;
        // 总长 = 各 NAL 长度 + 每个起始码 4 字节
        int total = avcc.length + nals * 4;
        byte[] out = new byte[total];
        int o = 0;
        off = 0;
        while (off + 4 <= avcc.length) {
            int n = ((avcc[off] & 0xFF) << 24) | ((avcc[off + 1] & 0xFF) << 16)
                    | ((avcc[off + 2] & 0xFF) << 8) | (avcc[off + 3] & 0xFF);
            off += 4;
            out[o++] = 0;
            out[o++] = 0;
            out[o++] = 0;
            out[o++] = 1;
            System.arraycopy(avcc, off, out, o, n);
            o += n;
            off += n;
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private void drainOutput() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int outIndex = codec.dequeueOutputBuffer(info, 0);
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                emit("视频输出格式已确定 " + codec.getOutputFormat().getString(MediaFormat.KEY_MIME));
                continue;
            }
            if (outIndex < 0) break;
            // 渲染到 Surface（true = render）
            codec.releaseOutputBuffer(outIndex, true);
            renderedFrames++;
            if (renderedFrames == 1 || renderedFrames % 120 == 0) {
                emit("已渲染 " + renderedFrames + " 帧到画面");
            }
        }
    }

    public synchronized void release() {
        releaseCodec();
        csd0 = null;
        csd1 = null;
    }

    private void releaseCodec() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
            codec = null;
        }
        configured = false;
    }

    private void emit(String line) {
        if (log != null) log.onLog(line);
    }

    // ------------------------------------------------------------- avcC 解析

    /** 从 avcC 配置里抽 SPS（sps=true）或 PPS（sps=false）。返回 {offset, length} 或 null。 */
    private static int[] parseAvcC(byte[] b, boolean sps) {
        if (b == null || b.length < 7 || b[0] != 1) return null;
        int off = 5;   // 跳过 configVersion/profile/compat/level + 0xFF
        int numSps = b[off] & 0x1F;
        off++;
        int[] result = null;
        for (int i = 0; i < numSps; i++) {
            if (off + 2 > b.length) return null;
            int len = ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
            off += 2;
            if (i == 0 && sps) result = new int[]{off, len};
            off += len;
        }
        if (off >= b.length) return result;
        int numPps = b[off] & 0xFF;
        off++;
        for (int i = 0; i < numPps; i++) {
            if (off + 2 > b.length) return null;
            int len = ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
            off += 2;
            if (i == 0 && !sps) result = new int[]{off, len};
            off += len;
        }
        return result;
    }

    // ------------------------------------------------------------- SPS 宽高解析

    /** 从 SPS NAL（不含 4 字节长度、不含 start code）解析宽高。返回 {width, height}。 */
    private static int[] parseSps(byte[] sps) {
        try {
            // 跳过 NAL 头 1 字节
            BitReader br = new BitReader(sps, 1);
            int profileIdc = br.u(8);
            br.u(8);   // constraints + reserved
            br.u(8);   // level_idc
            br.ue();   // seq_parameter_set_id
            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122
                    || profileIdc == 244 || profileIdc == 44 || profileIdc == 83
                    || profileIdc == 86 || profileIdc == 118 || profileIdc == 128
                    || profileIdc == 138 || profileIdc == 139 || profileIdc == 134
                    || profileIdc == 135) {
                int chroma = br.ue();
                if (chroma == 3) br.u(1);
                br.ue();   // bit_depth_luma_minus8
                br.ue();   // bit_depth_chroma_minus8
                br.u(1);   // qpprime_y_zero_transform_bypass
                if (br.u(1) == 1) {   // seq_scaling_matrix_present
                    int count = chroma != 3 ? 8 : 12;
                    for (int i = 0; i < count; i++) {
                        if (br.u(1) == 1) {
                            int size = i < 6 ? 16 : 64;
                            int last = 8, next = 8;
                            for (int j = 0; j < size; j++) {
                                if (next != 0) next = (last + br.se() + 256) % 256;
                                last = next == 0 ? last : next;
                            }
                        }
                    }
                }
            }
            br.ue();   // log2_max_frame_num_minus4
            int pocType = br.ue();
            if (pocType == 0) {
                br.ue();   // log2_max_pic_order_cnt_lsb_minus4
            } else if (pocType == 1) {
                br.u(1);   // delta_pic_order_always_zero
                br.se();   // offset_for_non_ref_pic
                br.se();   // offset_for_top_to_bottom
                int n = br.ue();   // num_ref_frames_in_pic_order_cnt_cycle
                for (int i = 0; i < n; i++) br.se();
            }
            br.ue();   // max_num_ref_frames
            br.u(1);   // gaps_in_frame_num_value_allowed_flag
            int widthMbs = br.ue() + 1;
            int heightMapUnits = br.ue() + 1;
            int frameMbsOnly = br.u(1);
            if (frameMbsOnly == 0) br.u(1);   // mb_adaptive_frame_field_flag
            br.u(1);   // direct_8x8_inference_flag
            int cropFlag = br.u(1);
            int cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
            if (cropFlag == 1) {
                cropLeft = br.ue();
                cropRight = br.ue();
                cropTop = br.ue();
                cropBottom = br.ue();
            }
            int width = widthMbs * 16;
            int height = (2 - frameMbsOnly) * heightMapUnits * 16;
            int cropUnitX = 1, cropUnitY = 2 - frameMbsOnly;
            // chroma 420：crop 单位按 chroma_array_type 缩放（这里按最常见 4:2:0）
            width -= (cropLeft + cropRight) * cropUnitX;
            height -= (cropTop + cropBottom) * cropUnitY;
            return new int[]{Math.max(1, width), Math.max(1, height)};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    /** 极简 bit reader（大端，支持 ue/se Exp-Golomb）。 */
    private static final class BitReader {
        private final byte[] data;
        private int bytePos;
        private int bitPos;

        BitReader(byte[] data, int startByte) {
            this.data = data;
            this.bytePos = startByte;
            this.bitPos = 0;
        }

        int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | bit();
            }
            return v;
        }

        int ue() {
            int zeros = 0;
            while (bit() == 0) zeros++;
            return (1 << zeros) - 1 + (zeros == 0 ? 0 : u(zeros));
        }

        int se() {
            int ue = ue();
            int k = (ue + 1) / 2;
            return (ue % 2 == 0) ? -k : k;
        }

        private int bit() {
            if (bytePos >= data.length) return 0;
            int b = (data[bytePos] >> (7 - bitPos)) & 1;
            bitPos++;
            if (bitPos == 8) {
                bitPos = 0;
                bytePos++;
            }
            return b;
        }
    }
}
