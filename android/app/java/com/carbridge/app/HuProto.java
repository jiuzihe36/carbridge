package com.carbridge.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * CarLife HU（车机侧）协议常量 + 手写 protobuf 编码。
 *
 * 不用 protoc：手写的这几个字段都是最简单的 string / int32，编码 10 行就够，
 * 换来的好处是整个工程不需要 Gradle 与 protobuf 依赖。
 */
public final class HuProto {

    private HuProto() {
    }

    // ---------------------------------------------------------------- 通道

    /** 8 字节包头通道：CMD / TOUCH。 */
    public static final int CH_CMD = 1;
    public static final int CH_VIDEO = 2;
    public static final int CH_AUDIO = 3;
    public static final int CH_TTS = 4;
    public static final int CH_VR = 5;
    public static final int CH_TOUCH = 6;

    /** 无线组的 6 个端口（手机 Car+ 打开的一组）。 */
    public static final int[] WIFI_PORTS = {7240, 8240, 9240, 9241, 9242, 9340};
    /** USB / ADB 转发组的端口。 */
    public static final int[] LOCAL_PORTS = {7200, 8200, 9200, 9201, 9202, 9300};
    /** 通道顺序：与端口一一对应。 */
    public static final int[] CHANNELS = {CH_CMD, CH_VIDEO, CH_AUDIO, CH_TTS, CH_VR, CH_TOUCH};

    // ---------------------------------------------------------------- 消息 ID

    public static final int HU_PROTOCOL_VERSION = 0x00018001;   // 98305
    public static final int MD_VERSION_MATCH = 0x00010002;      // 65538
    public static final int HU_INFO = 0x00018003;               // 98307
    public static final int MD_INFO = 0x00010004;               // 65540
    public static final int HU_VIDEO_ENCODER_INIT = 0x00018007; // 98311
    public static final int MD_VIDEO_ENCODER_INIT = 0x00010008; // 65544
    public static final int HU_VIDEO_ENCODER_START = 0x00018009;// 98313
        /**
     * 帧率变更（HU→MD），载荷 {@code CarlifeVideoFrameRate{frameRate=1}}，
     * 手机回 {@link #MD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE}。
     */
    public static final int HU_VIDEO_ENCODER_FRAME_RATE_CHANGE = 0x0001800C; // 98316
    /** 帧率变更完成（MD→HU）。 */
    public static final int MD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE = 0x0001000D; // 65549
    public static final int MD_SCREEN_ON = 0x00010018;          // 65560
    public static final int MD_FOREGROUND = 0x0001001B;         // 65563
    public static final int MD_MIC_WAKEUP = 0x00010022;         // 65570
    public static final int MD_MODULE_STATUS = 0x00010026;      // 65574
    public static final int HU_STATISTICS = 0x00018027;         // 98343
    public static final int MD_AUTHEN_RESULT = 0x0001004B;      // 65611
        /**
     * 鉴权结果（HU→MD）。
     */
    public static final int HU_AUTHEN_RESULT = 0x0001804A;      // 98378
    /** 连接异常（MD→HU），载荷 {@code CarlifeConnectException{exceptionType}}。 */
    public static final int MD_CONNECT_EXCEPTION = 0x00010037;  // 65591
    public static final int MD_FEATURE_CONFIG_REQ = 0x00010051; // 65617
    public static final int HU_FEATURE_CONFIG_LIST = 0x00018052;// 98386
    public static final int MD_VIDEO_DATA = 0x00020001;         // 视频通道
    public static final int HU_HEARTBEAT = 0x00020002;          // 131074
    public static final int MD_MEDIA_DATA = 0x00030006;         // 音频通道
    /** 媒体流参数声明（音频通道首包）：{采样率, 声道, 位深}，**不是 PCM 数据**。 */
    public static final int MEDIA_INIT = 0x00030001;
    public static final int HU_TOUCH_ACTION = 0x00068001;       // 触摸
    public static final int HU_TOUCH_SINGLE_CLICK = 0x00068005; // 高层单击指令

        /**
     * 车机硬键（CarLife 侧）。
     */
    public static final int HU_CAR_HARD_KEY_CODE = 0x00068008;

    /**
     * CarLife 的<b>小车机键码表</b>（{@code ServiceTypes.kt:358-408}）。
     *
     * <p>⚠️ <b>这不是 Android 的 ADB keycode</b>，别混用：
     * 例如 19 在这张表里是 {@code MUTE}（按静音），不是 {@code KEYCODE_DPAD_DOWN}。
     */
    public static final class CarHardKey {
        private CarHardKey() {
        }

        public static final int HOME = 0x01;
        public static final int PHONE_CALL = 0x02;
        public static final int PHONE_END = 0x03;
        public static final int PHONE_END_MUTE = 0x04;
        public static final int HFP = 0x05;
        public static final int SELECTOR_NEXT = 0x06;
        public static final int SELECTOR_PREVIOUS = 0x07;
        public static final int SETTING = 0x08;
        public static final int MEDIA = 0x09;
        public static final int RADIO = 0x0A;
        public static final int NAV = 0x0B;
        public static final int SRC = 0x0C;
        public static final int MODE = 0x0D;
        public static final int BACK = 0x0E;
        public static final int SEEK_SUB = 0x0F;
        public static final int SEEK_ADD = 0x10;
        public static final int VOLUME_SUB = 0x11;
        public static final int VOLUME_ADD = 0x12;
        public static final int MUTE = 0x13;
        public static final int OK = 0x14;
        public static final int MOVE_LEFT = 0x15;
        public static final int MOVE_RIGHT = 0x16;
        public static final int MOVE_UP = 0x17;
        public static final int MOVE_DOWN = 0x18;
        public static final int TEL = 0x1D;
        public static final int MAIN = 0x1E;
        public static final int MEDIA_START = 0x1F;
        public static final int MEDIA_STOP = 0x20;
        public static final int VR_START = 0x21;
        public static final int VR_STOP = 0x22;
        public static final int NUMBER_0 = 0x23;

        /** 可读名（日志用）。 */
        public static String name(int code) {
            switch (code) {
                case HOME: return "HOME";
                case PHONE_CALL: return "接听";
                case PHONE_END: return "挂断";
                case PHONE_END_MUTE: return "挂断静音";
                case HFP: return "HFP";
                case SELECTOR_NEXT: return "下一曲";
                case SELECTOR_PREVIOUS: return "上一曲";
                case SETTING: return "设置";
                case MEDIA: return "媒体";
                case RADIO: return "收音";
                case NAV: return "导航";
                case SRC: return "音源";
                case MODE: return "模式";
                case BACK: return "返回";
                case SEEK_SUB: return "快退";
                case SEEK_ADD: return "快进";
                case VOLUME_SUB: return "音量-";
                case VOLUME_ADD: return "音量+";
                case MUTE: return "静音";
                case OK: return "确定";
                case MOVE_LEFT: return "左";
                case MOVE_RIGHT: return "右";
                case MOVE_UP: return "上";
                case MOVE_DOWN: return "下";
                case TEL: return "电话";
                case MAIN: return "主界面";
                case MEDIA_START: return "播放";
                case MEDIA_STOP: return "暂停";
                case VR_START: return "语音";
                case VR_STOP: return "语音结束";
                default: return "键码" + code;
            }
        }
    }

    /** 触摸动作值（CarProjection / 官方 Utils）。 */
    public static final int ACT_DOWN = 0;
    public static final int ACT_UP = 1;
    public static final int ACT_MOVE = 2;

    // ---------------------------------------------------------------- 分帧

    /** 组一帧。CMD / TOUCH 用 8 字节包头，媒体通道用 12 字节包头（都是大端）。 */
    public static byte[] frame(int channel, int serviceType, byte[] body) {
        byte[] b = body == null ? new byte[0] : body;
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length + 12);
        if (channel == CH_CMD || channel == CH_TOUCH) {
            out.write((b.length >>> 8) & 0xFF);
            out.write(b.length & 0xFF);
            out.write(0);
            out.write(0);
        } else {
            out.write((b.length >>> 24) & 0xFF);
            out.write((b.length >>> 16) & 0xFF);
            out.write((b.length >>> 8) & 0xFF);
            out.write(b.length & 0xFF);
            int ts = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
            out.write((ts >>> 24) & 0xFF);
            out.write((ts >>> 16) & 0xFF);
            out.write((ts >>> 8) & 0xFF);
            out.write(ts & 0xFF);
        }
        out.write((serviceType >>> 24) & 0xFF);
        out.write((serviceType >>> 16) & 0xFF);
        out.write((serviceType >>> 8) & 0xFF);
        out.write(serviceType & 0xFF);
        out.write(b, 0, b.length);
        return out.toByteArray();
    }

    /** CMD 包头：u16 len | u16 reserved | u32 service。 */
    public static int cmdLength(byte[] head) {
        return ((head[0] & 0xFF) << 8) | (head[1] & 0xFF);
    }

    public static int cmdService(byte[] head) {
        return ((head[4] & 0xFF) << 24) | ((head[5] & 0xFF) << 16)
                | ((head[6] & 0xFF) << 8) | (head[7] & 0xFF);
    }

    /** 媒体包头：u32 len | u32 ts | u32 service。 */
    public static int mediaLength(byte[] head) {
        return ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16)
                | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
    }

    public static int mediaTimestamp(byte[] head) {
        return ((head[4] & 0xFF) << 24) | ((head[5] & 0xFF) << 16)
                | ((head[6] & 0xFF) << 8) | (head[7] & 0xFF);
    }

    public static int mediaService(byte[] head) {
        return ((head[8] & 0xFF) << 24) | ((head[9] & 0xFF) << 16)
                | ((head[10] & 0xFF) << 8) | (head[11] & 0xFF);
    }

    // ---------------------------------------------------------------- 各消息负载

    /** 版本协商：实测 Car+ 要 4.0（1.1 也能拿到匹配，但后面会停）。 */
    public static byte[] protocolVersion() {
        return join(pbInt(1, 4), pbInt(2, 0));
    }

    /** 统计信息：手机据此认为「车机在报数据」。 */
    public static byte[] statistics() {
        return join(pbString(1, "12345678"), pbString(2, "4.0.0"), pbInt(3, 16), pbString(4, "20029999"),
                pbInt(5, 0), pbInt(6, 0), pbInt(7, 0));
    }

    /**
     * 车机设备信息：21 个字段。field 8 = display 必填，手机按它决定编码尺寸；
     * 缺了它手机不推流（实测）。
     */
    public static byte[] huInfo(int width, int height) {
        String[] vals = {
                "Android", "universal", "unknown", "baidu",
                "arm64-v8a", "armeabi-v7a", "carlife",
                width + "x" + height,
                "baidu/carlife/carlife:11/RQ3A.211001.001/1:user/release-keys",
                "universal", "carlife-build", "carlife", "unknown",
                "1.0", "baidu", "carlife", "0", "0", "0", "0", "0",
        };
        byte[][] parts = new byte[vals.length][];
        for (int i = 0; i < vals.length; i++) {
            parts[i] = pbString(i + 1, vals[i]);
        }
        return join(parts);
    }

    /** 特征配置：USB_MTU / I_FRAME_INTERVAL / CONNECT_TYPE。 */
    public static byte[] featureConfig(int connectType) {
        return featureConfig(connectType, false);
    }

        /**
     * 特征配置。
     */
    public static byte[] featureConfig(int connectType, boolean disableResolutionOption) {
        byte[] resolutionOption = disableResolutionOption
                ? pbMsg(2, join(pbString(1, "DISABLE_RESOLUTION_OPTION"), pbInt(2, 1)))
                : new byte[0];
        return join(
                pbInt(1, disableResolutionOption ? 4 : 3),
                pbMsg(2, join(pbString(1, "USB_MTU"), pbInt(2, 16384))),
                pbMsg(2, join(pbString(1, "I_FRAME_INTERVAL"), pbInt(2, 300))),
                pbMsg(2, join(pbString(1, "CONNECT_TYPE"), pbInt(2, connectType))),
                resolutionOption,
                pbString(4, "CarLife_PC"),
                pbString(5, "00:11:22:33:44:55"));
    }

        /**
     * 模块 ID（{@code CarlifeModuleStatus
     * List.moduleStatus[].moduleID}）。
     */
    public static final int MODULE_PHONE = 1;
    public static final int MODULE_NAVI = 2;
    public static final int MODULE_MUSIC = 3;
    public static final int MODULE_VR = 4;
    public static final int MODULE_CONNECT = 5;
    public static final int MODULE_MIC = 6;

    /**
     * 解析 {@code MD_MODULE_STATUS 0x00010026}：
     * {@code CarlifeModuleStatusList{cnt=1, moduleStatus[]{moduleID=1, statusID=2}}。
     *
     * <p>⚠️ protobuf 的每个字段前面有**标签字节**（field&lt;&lt;3 | wireType），
     * 必须按标签解析，不能把标签当值读（否则 cnt 会读成 8 而不是 1）。
     *
     * @return {moduleID, statusID} 列表（解析不出就是空）
     */
    public static java.util.List<int[]> parseModuleStatus(byte[] body) {
        java.util.List<int[]> out = new java.util.ArrayList<int[]>();
        if (body == null || body.length == 0) return out;
        try {
            int[] p = new int[]{0};
            while (p[0] < body.length) {
                long tag = pbVarint(body, p);
                int field = (int) (tag >>> 3);
                int wire = (int) (tag & 7);
                if (wire == 0) {
                    long v = pbVarint(body, p);
                    if (field == 1) {
                        int cnt = (int) v;
                        for (int i = 0; i < cnt; i++) {
                            if (p[0] >= body.length) break;
                            long sub = pbVarint(body, p);       // 子消息的标签
                            if ((sub & 7) != 2) break;
                            int len = (int) pbVarint(body, p);
                            int end = p[0] + len;
                            if (len <= 0 || end > body.length) break;
                            int[] q = new int[]{p[0]};
                            int mid = 0;
                            int sid = 0;
                            while (q[0] < end) {
                                long t2 = pbVarint(body, q);
                                int f2 = (int) (t2 >>> 3);
                                int w2 = (int) (t2 & 7);
                                if (w2 == 0) {
                                    long v2 = pbVarint(body, q);
                                    if (f2 == 1) mid = (int) v2;
                                    else if (f2 == 2) sid = (int) v2;
                                } else if (w2 == 2) {
                                    int l2 = (int) pbVarint(body, q);
                                    q[0] += l2;
                                } else if (w2 == 5) {
                                    q[0] += 4;
                                } else if (w2 == 1) {
                                    q[0] += 8;
                                } else {
                                    break;
                                }
                            }
                            out.add(new int[]{mid, sid});
                            p[0] = end;
                        }
                    }
                } else if (wire == 2) {
                    int len = (int) pbVarint(body, p);
                    p[0] += len;
                } else if (wire == 5) {
                    p[0] += 4;
                } else if (wire == 1) {
                    p[0] += 8;
                } else {
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 读一个 protobuf varint（就地推进 p[0]）。 */
    private static long pbVarint(byte[] b, int[] p) {
        long r = 0;
        int shift = 0;
        while (p[0] < b.length) {
            int x = b[p[0]++] & 0xFF;
            r |= ((long) (x & 0x7F)) << shift;
            if ((x & 0x80) == 0) break;
            shift += 7;
        }
        return r;
    }

    /** 编码参数：{width, height, frameRate} 三个字段都 required。 */
    public static byte[] encoderInit(int w, int h, int fps) {
        return join(pbInt(1, w), pbInt(2, h), pbInt(3, fps));
    }

    /** 帧率变更：{@code CarlifeVideoFrameRate{frameRate=1}}。 */
    public static byte[] videoFrameRate(int fps) {
        return pbInt(1, fps);
    }

    /** 触摸事件：{action, x, y}。 */
    public static byte[] touch(int action, int x, int y) {
        return join(pbInt(1, action), pbInt(2, x), pbInt(3, y));
    }

    /** 高层单击：CarlifeTouchSinglePoint {x=1, y=2}。 */
    public static byte[] singleClick(int x, int y) {
        return join(pbInt(1, x), pbInt(2, y));
    }

    /** 解析 {width, height, fps}，解析不出来返回 null。 */
    public static int[] parseEncoderInfo(byte[] d) {
        int w = 0, h = 0, f = 0, i = 0;
        try {
            while (i < d.length) {
                int tag = d[i++] & 0xFF;
                int field = tag >>> 3, wire = tag & 7;
                if (wire == 0) {
                    int v = 0, shift = 0;
                    while (i < d.length) {
                        int c = d[i++] & 0xFF;
                        v |= (c & 0x7F) << shift;
                        if ((c & 0x80) == 0) {
                            break;
                        }
                        shift += 7;
                    }
                    if (field == 1) {
                        w = v;
                    } else if (field == 2) {
                        h = v;
                    } else if (field == 3) {
                        f = v;
                    }
                } else if (wire == 2) {
                    int len = 0, shift = 0;
                    while (i < d.length) {
                        int c = d[i++] & 0xFF;
                        len |= (c & 0x7F) << shift;
                        if ((c & 0x80) == 0) {
                            break;
                        }
                        shift += 7;
                    }
                    i += len;
                } else {
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return (w > 0 && h > 0) ? new int[]{w, h, f > 0 ? f : 30} : null;
    }

    /** 取 MD_INFO 里的字符串字段，用于日志（display 在 field 8）。 */
    public static String deviceField(byte[] d, int want) {
        int i = 0;
        try {
            while (i < d.length) {
                int tag = d[i++] & 0xFF;
                int field = tag >>> 3, wire = tag & 7;
                if (wire == 0) {
                    while (i < d.length && (d[i++] & 0x80) != 0) {
                        // skip varint
                    }
                } else if (wire == 2) {
                    int len = 0, shift = 0;
                    while (i < d.length) {
                        int c = d[i++] & 0xFF;
                        len |= (c & 0x7F) << shift;
                        if ((c & 0x80) == 0) {
                            break;
                        }
                        shift += 7;
                    }
                    if (field == want) {
                        return new String(d, i, len, StandardCharsets.UTF_8);
                    }
                    i += len;
                } else {
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public static String hex(byte[] d, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(d.length, max); i++) {
            sb.append(String.format("%02X ", d[i]));
        }
        if (d.length > max) {
            sb.append("…");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 手写 protobuf

    static byte[] varint(int v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(5);
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
        return out.toByteArray();
    }

    public static byte[] pbInt(int field, int value) {
        return concat(varint(field << 3), varint(value));
    }

    public static byte[] pbString(int field, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return concat(concat(varint((field << 3) | 2), varint(b.length)), b);
    }

    public static byte[] pbMsg(int field, byte[] inner) {
        return concat(concat(varint((field << 3) | 2), varint(inner.length)), inner);
    }

    /** 把多段拼成一个 byte[]（Java 里 byte[] 没有 + 运算符）。 */
    public static byte[] join(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] r = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, r, off, p.length);
            off += p.length;
        }
        return r;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
