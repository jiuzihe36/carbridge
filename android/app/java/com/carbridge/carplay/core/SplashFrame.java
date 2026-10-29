package com.carbridge.carplay.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * 品牌开屏画面：Car+ 还没把真实画面送过来之前，
 * CarPlay 侧先投一张 「carbridge 名称 + logo」的静态开屏（仿车机开机主页），避免车机黑屏。
 */
public final class SplashFrame {

    /** 一次编码的结果：avcC 配置 + 含 SPS/PPS/IDR 的完整 Annex-B 帧。 */
    public static final class Frame {
        public final byte[] avcC;
        public final int width;
        public final int height;
        public final byte[] annexB;

        Frame(byte[] avcC, int width, int height, byte[] annexB) {
            this.avcC = avcC;
            this.width = width;
            this.height = height;
            this.annexB = annexB;
        }
    }

    private static final byte[] START_CODE = {0, 0, 0, 1};

    private final Bitmap logo;
    private final String brand;
    private final String subtitle;
    private volatile Frame cached;

    public SplashFrame(Bitmap logo, String brand, String subtitle) {
        this.logo = logo;
        this.brand = brand == null ? "" : brand;
        this.subtitle = subtitle == null ? "" : subtitle;
    }

    /** 取（或懒生成）某分辨率下的开屏帧。编码失败返回 null。 */
    public Frame frame(int width, int height) {
        Frame c = cached;
        if (c != null && c.width == width && c.height == height) return c;
        synchronized (this) {
            c = cached;
            if (c != null && c.width == width && c.height == height) return c;
            Frame f = generate(width, height);
            cached = f;
            return f;
        }
    }

    // ================================================================== 生成

    private Frame generate(int width, int height) {
        MediaCodec codec = null;
        try {
            // —— 先把品牌开屏画到离屏 Bitmap ——
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            drawBrand(new Canvas(bmp), width, height);

            // —— MediaCodec 编码：标准 YUV420 输入（⚠️ 不能用 lockCanvas +
            //    createInputSurface：软件绘制路径与硬件编码器不兼容，编出来是黑的）——
            codec = MediaCodec.createEncoderByType("video/avc");
            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", width, height);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0);   // 全 I 帧
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            // 喂一帧 YUV
            int inIdx = codec.dequeueInputBuffer(20_000);
            if (inIdx < 0) {
                android.util.Log.w("carbridge", "开屏编码：拿不到输入缓冲");
                return null;
            }
            fillYuv420(codec, inIdx, bmp, width, height);
            codec.queueInputBuffer(inIdx, 0, width * height * 3 / 2, 0, 0);
            // 再喂一个 EOS，让编码器把这一帧吐出来
            int eosIdx = codec.dequeueInputBuffer(20_000);
            if (eosIdx >= 0) {
                codec.queueInputBuffer(eosIdx, 0, 0, 0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }

            // —— 收输出：SPS/PPS + IDR ——
            byte[] spsPps = null;
            ByteArrayOutputStream frames = new ByteArrayOutputStream();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long drainStart = System.currentTimeMillis();
            while (true) {
                // ⚠️ 整体加超时：开屏编码在心跳线程里跑，卡住会把心跳一起拖死
                //    （/feedback 停 → 会话被判死 → 重连循环）。2s 拿不到就放弃。
                if (System.currentTimeMillis() - drainStart > 3000) {
                    android.util.Log.w("carbridge", "开屏编码超时，放弃");
                    return null;
                }
                int idx = codec.dequeueOutputBuffer(bi, 2000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    ByteBuffer csd0 = of.getByteBuffer("csd-0");
                    ByteBuffer csd1 = of.getByteBuffer("csd-1");
                    if (csd0 != null && csd1 != null) {
                        spsPps = concat(START_CODE, toBytes(csd0), START_CODE, toBytes(csd1));
                    }
                    continue;
                }
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (idx < 0) break;
                ByteBuffer bb = codec.getOutputBuffer(idx);
                if (bb != null && bi.size > 0) {
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // 部分编码器把 csd 直接放在这个 buffer 里（起始码格式）
                        byte[] d = new byte[bi.size];
                        bb.position(bi.offset);
                        bb.get(d);
                        spsPps = d;
                    } else {
                        byte[] d = new byte[bi.size];
                        bb.position(bi.offset);
                        bb.get(d);
                        frames.write(d, 0, d.length);
                    }
                }
                boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(idx, false);
                if (eos) break;
            }

            if (spsPps == null || frames.size() == 0) {
                android.util.Log.w("carbridge", "开屏编码：没拿到 SPS/PPS 或帧");
                return null;
            }
            byte[] annexB = concat(spsPps, frames.toByteArray());
            byte[] avcC = VideoPackets.avcCFromAnnexB(annexB);
            if (avcC == null || avcC.length == 0) {
                android.util.Log.w("carbridge", "开屏编码：avcC 提取失败");
                return null;
            }
            android.util.Log.i("carbridge", "开屏画面已编码 " + width + "x" + height
                    + " avcC=" + avcC.length + "B 帧=" + annexB.length + "B");
            return new Frame(avcC, width, height, annexB);
        } catch (Throwable t) {
            android.util.Log.w("carbridge", "开屏画面编码失败: " + t);
            return null;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                }
                try {
                    codec.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Bitmap(RGBA) → 编码器输入缓冲（YUV420，按 Image 的 stride/pixelStride 写）。 */
    private static void fillYuv420(MediaCodec codec, int index, Bitmap bmp, int w, int h) {
        android.media.Image img = codec.getInputImage(index);
        if (img == null) throw new IllegalStateException("getInputImage 返回 null");
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);

        android.media.Image.Plane[] planes = img.getPlanes();
        // ⚠️ 个别设备 YUV420Flexible 可能只给 2 个 plane（半平面 NV12：Y + 交错 UV），
        //    此时 planes[2] 会越界；放弃编码让上层降级。
        if (planes.length < 3) {
            throw new IllegalStateException("YUV plane 数异常: " + planes.length);
        }
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int yPix = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride();
        int uPix = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride();
        int vPix = planes[2].getPixelStride();

        // Y 平面（逐像素）
        for (int row = 0; row < h; row++) {
            int py = row * yRow;
            int src = row * w;
            for (int col = 0; col < w; col++) {
                int c = px[src + col];
                int r = (c >>> 16) & 0xFF, g = (c >>> 8) & 0xFF, b = c & 0xFF;
                int y = (77 * r + 150 * g + 29 * b) >> 8;
                yBuf.put(py + col * yPix, (byte) (y > 255 ? 255 : y));
            }
        }
        // U/V 平面（2x2 下采样；兼容 I420(pixelStride=1) 与 NV12/NV21(pixelStride=2)）
        for (int row = 0; row < h / 2; row++) {
            int pu = row * uRow;
            int pv = row * vRow;
            int src = (row * 2) * w;
            for (int col = 0; col < w / 2; col++) {
                int c00 = px[src + col * 2];
                int c01 = px[src + col * 2 + 1];
                int c10 = px[src + w + col * 2];
                int c11 = px[src + w + col * 2 + 1];
                // 4 像素平均值（明确加括号，避免移位/按位与优先级坑）
                int r = (((c00 >>> 16) & 0xFF) + ((c01 >>> 16) & 0xFF)
                        + ((c10 >>> 16) & 0xFF) + ((c11 >>> 16) & 0xFF)) >> 2;
                int g = (((c00 >>> 8) & 0xFF) + ((c01 >>> 8) & 0xFF)
                        + ((c10 >>> 8) & 0xFF) + ((c11 >>> 8) & 0xFF)) >> 2;
                int b = ((c00 & 0xFF) + (c01 & 0xFF) + (c10 & 0xFF) + (c11 & 0xFF)) >> 2;
                int u = ((-43 * r - 85 * g + 128 * b) >> 8) + 128;
                int v = ((128 * r - 107 * g - 21 * b) >> 8) + 128;
                uBuf.put(pu + col * uPix, (byte) clamp(u));
                vBuf.put(pv + col * vPix, (byte) clamp(v));
            }
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** 画品牌开屏：深色底 + 居中 logo + 品牌名 + 副标题。 */
    private void drawBrand(Canvas c, int w, int h) {
        c.drawColor(Color.rgb(14, 14, 16));   // 深色底

        float cx = w / 2f;
        float cy = h / 2f;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // logo：缩放保持比例，占画布高度的 ~34%
        float logoSize = h * 0.34f;
        if (logo != null && logo.getWidth() > 0 && logo.getHeight() > 0) {
            float scale = Math.min(logoSize / logo.getWidth(), logoSize / logo.getHeight());
            int dw = Math.round(logo.getWidth() * scale);
            int dh = Math.round(logo.getHeight() * scale);
            // 品牌名放 logo 下方，所以 logo 整体上移
            float top = cy - logoSize * 0.9f;
            c.drawBitmap(Bitmap.createScaledBitmap(logo, dw, dh, true),
                    cx - dw / 2f, top, p);
        }

        // 品牌名
        p.setColor(Color.WHITE);
        p.setTextSize(h * 0.14f);
        p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        float brandY = cy + logoSize * 0.35f;
        c.drawText(brand, cx, brandY, p);

        // 副标题
        p.setColor(Color.rgb(140, 140, 148));
        p.setTextSize(h * 0.055f);
        p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        c.drawText(subtitle, cx, brandY + h * 0.09f, p);
    }

    // ================================================================== 工具

    private static byte[] toBytes(ByteBuffer bb) {
        ByteBuffer dup = bb.duplicate();
        byte[] out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }
}
