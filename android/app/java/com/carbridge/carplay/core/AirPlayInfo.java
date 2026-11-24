package com.carbridge.carplay.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 解析 AirPlay `/info` 的响应（bplist）。
 */
public final class AirPlayInfo {

    /** 一个车机显示器。 */
    public static final class Display {
        public final int widthPixels;
        public final int heightPixels;
        public final String uuid;
        public final int maxFps;

        Display(int widthPixels, int heightPixels, String uuid, int maxFps) {
            this.widthPixels = widthPixels;
            this.heightPixels = heightPixels;
            this.uuid = uuid;
            this.maxFps = maxFps;
        }

        @Override
        public String toString() {
            return "Display{" + widthPixels + "x" + heightPixels + ", fps=" + maxFps + ", uuid=" + uuid + "}";
        }
    }

    /** 一个车机声明的 HID 设备。 */
    public static final class HidDevice {
        public final String uuid;
        public final String name;
        public final byte[] descriptor;

        HidDevice(String uuid, String name, byte[] descriptor) {
            this.uuid = uuid;
            this.name = name;
            this.descriptor = descriptor;
        }

        /** 该设备触摸坐标逻辑上限 {maxX, maxY}；解析不出返回 null。 */
        public int[] touchAxisMaxima() {
            return HidDescriptor.touchAxisMaxima(descriptor);
        }

        /** 该设备的触摸 Report ID（报告首字节）；没有 Report ID 返回 0。 */
        public int touchReportId() {
            return HidDescriptor.touchReportId(descriptor);
        }

        /**
         * 该设备是不是「按键设备」（方向盘/中控的 Consumer Control 键）。
         *
         * <p>触摸屏没有 Consumer 页 → 返回 null；按键设备返回 Usage 映射表。
         */
        public HidKeyMap keyMap() {
            return HidKeyMap.parse(descriptor);
        }

        @Override
        public String toString() {
            return "HidDevice{uuid=" + uuid + ", name=" + name
                    + ", desc=" + (descriptor == null ? 0 : descriptor.length) + "B}";
        }
    }

        /**
     * 一个音频格式档位（车机 {@code /info} 的 {@code audioFormats[]} 一项）。
     */
    public static final class AudioFormat {
        /** 流类型：100=MainAudio(PCM 通路)，102=MainHighAudio(AAC-LC 通路)。 */
        public final long type;
        /** 音频类型：media / compatibility / default …，SETUP 时要原样回给车机。 */
        public final String audioType;
        /** 车机支持的**输出**格式位图。 */
        public final long outputFormats;
        /** 车机支持的输入格式位图。 */
        public final long inputFormats;
        /** 同一 (type, audioType) 档的输出延迟（微秒），没有就是 -1。 */
        public final long outputLatencyMicros;

        AudioFormat(long type, String audioType, long outputFormats, long inputFormats,
                    long outputLatencyMicros) {
            this.type = type;
            this.audioType = audioType;
            this.outputFormats = outputFormats;
            this.inputFormats = inputFormats;
            this.outputLatencyMicros = outputLatencyMicros;
        }

        public boolean supportsPcm44k() {
            return (outputFormats & (1L << 11)) != 0;
        }

        public boolean supportsPcm48k() {
            return (outputFormats & (1L << 15)) != 0;
        }

        public boolean supportsAacLc44k() {
            return (outputFormats & (1L << 22)) != 0;
        }

        @Override
        public String toString() {
            return "AudioFmt{type=" + type + ", audioType=" + audioType
                    + ", out=0x" + Long.toHexString(outputFormats)
                    + (outputLatencyMicros >= 0 ? ", lat=" + outputLatencyMicros + "us" : "")
                    + "}";
        }
    }

    private final List<Display> displays = new ArrayList<Display>();
    private final List<HidDevice> hidDevices = new ArrayList<HidDevice>();
    private final List<AudioFormat> audioFormats = new ArrayList<AudioFormat>();
    public boolean keepAliveSendStatsAsBody;
    public String rawDeviceId = "";

    private AirPlayInfo() {
    }

    public List<Display> displays() {
        return Collections.unmodifiableList(displays);
    }

    public Display primaryDisplay() {
        return displays.isEmpty() ? null : displays.get(0);
    }

    public List<HidDevice> hidDevices() {
        return Collections.unmodifiableList(hidDevices);
    }

    public List<AudioFormat> audioFormats() {
        return Collections.unmodifiableList(audioFormats);
    }

    // ---------------------------------------------------------------- 解析

    /** 从已解析的 bplist 根（Map）构造；不合法返回一个空对象而不是 null。 */
    public static AirPlayInfo parse(Object root) {
        AirPlayInfo info = new AirPlayInfo();
        if (!(root instanceof Map)) {
            return info;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) root;

        info.rawDeviceId = asString(map.get("deviceid"));

        for (Object d : asList(map.get("displays"))) {
            if (!(d instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> dm = (Map<String, Object>) d;
            info.displays.add(new Display(
                    asInt(dm.get("widthPixels"), 0),
                    asInt(dm.get("heightPixels"), 0),
                    asString(dm.get("uuid")),
                    asInt(dm.get("maxFPS"), 30)));
        }

        for (Object h : asList(map.get("hidDevices"))) {
            if (!(h instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> hm = (Map<String, Object>) h;
            // uuid 在不同车机上类型不同：奥迪是字符串 "0"/"1"，DiPlay 是整数
            info.hidDevices.add(new HidDevice(
                    asString(hm.get("uuid")),
                    asString(hm.get("name")),
                    asBytes(hm.get("hidDescriptor"))));
        }

        for (Object a : asList(map.get("audioFormats"))) {
            if (!(a instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> am = (Map<String, Object>) a;
            long type = asLong(am.get("type"), 0);
            String audioType = asString(am.get("audioType"));
            long out = asLong(am.get("audioOutputFormats"), 0);
            long in = asLong(am.get("audioInputFormats"), 0);
            info.audioFormats.add(new AudioFormat(type, audioType, out, in, -1));
        }

        // audioLatencies：按 (type, audioType) 给出延迟，用来配上面的格式档
        for (Object l : asList(map.get("audioLatencies"))) {
            if (!(l instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> lm = (Map<String, Object>) l;
            long type = asLong(lm.get("type"), 0);
            String audioType = asString(lm.get("audioType"));
            long lat = asLong(lm.get("outputLatencyMicros"), -1);
            for (int i = 0; i < info.audioFormats.size(); i++) {
                AudioFormat af = info.audioFormats.get(i);
                if (af.type == type && af.audioType.equals(audioType) && af.outputLatencyMicros < 0) {
                    info.audioFormats.set(i, new AudioFormat(af.type, af.audioType,
                            af.outputFormats, af.inputFormats, lat));
                }
            }
        }

        Object k = map.get("keepAliveSendStatsAsBody");
        info.keepAliveSendStatsAsBody = k instanceof Boolean && ((Boolean) k).booleanValue();
        return info;
    }

    // ---------------------------------------------------------------- 类型宽容

    static List<Object> asList(Object o) {
        if (o instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) o;
            return l;
        }
        return Collections.emptyList();
    }

    public static String asString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    public static byte[] asBytes(Object o) {
        return o instanceof byte[] ? (byte[]) o : new byte[0];
    }

    /**
     * 车机可能把数字当字符串下发（`"100"` / `"0x8000"`），这里统一解析；
     * 解析不出返回 def。
     */
    public static int asInt(Object o, int def) {
        long v = asLong(o, Long.MIN_VALUE);
        return v == Long.MIN_VALUE ? def : (int) v;
    }

    public static long asLong(Object o, long def) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) {
            String s = ((String) o).trim();
            if (s.isEmpty()) return def;
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                // 试 0x 十六进制
                String h = s.toLowerCase(java.util.Locale.US);
                if (h.startsWith("0x")) {
                    try {
                        return Long.parseLong(h.substring(2), 16);
                    } catch (NumberFormatException ignored2) {
                        return def;
                    }
                }
                return def;
            }
        }
        return def;
    }
}
