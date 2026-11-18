package com.carbridge.carplay.core;

/**
 * AirPlay `/setup` 的启动参数与车机 capabilities 常量。
 */
public final class AirPlaySetup {

    /**
     * 发送端 AirPlay 版本号，**运行时可改**（界面「广播版本」那一行）。
     *
     * <p>真 iPhone 在三个地方报同一个值：RTSP 的 {@code User-Agent}、SETUP/ANNOUNCE 的
     * {@code sourceVersion}/{@code srcvers}/{@code vv}、以及 {@code _carplay-ctrl._tcp} 的
     * TXT {@code srcvers}。三者不同步就是「自相矛盾的发送端」，所以这里做成一个可变值，
     * 由 {@link com.carbridge.carplay.CarPlayCtrlCompat} 统一设置（TXT 与协议一起变）。
     *
     * <p>默认 {@code 950.7.1}：2026-05-21 真机抓包里 iPhone 的 UA 就是这个值。
     */
    public static volatile String SOURCE_VERSION = "950.7.1";
    public static volatile String USER_AGENT = "AirPlay/" + SOURCE_VERSION;

    /** 设置版本号，同时把 User-Agent 一起对齐（两者必须一致）。 */
    public static void setSourceVersion(String version) {
        if (version == null) return;
        String v = version.trim();
        if (v.isEmpty() || v.length() > 32 || !v.matches("[0-9A-Za-z._-]+")) return;
        SOURCE_VERSION = v;
        USER_AGENT = "AirPlay/" + v;
    }

    /** 老代码里当常量用的那两处（默认值）。 */
    public static final String DEFAULT_SOURCE_VERSION = "950.7.1";
    public static final String OS_NAME = "iPhone OS";
    public static final String OS_VERSION = "27.1";
    public static final String OS_BUILD_VERSION = "24A94425";
    public static final String MODEL = "iPhone19,4";

    /** `kAirPlayEncryptionType_None`。⚠️ 必须是 1，**不是 0**（0 = Undefined，车机回 466）。 */
    public static final int ENCRYPTION_NONE = 1;

    private AirPlaySetup() {
    }

    // ---------------------------------------------------------------- 能力清单

    /**
     * 完整 iPhone 控制端能力位。来源：Apple `AirPlayCommon.h` /
     * `kAirPlayFeature_*` 常量集合，**全量 14 项**。
     *
     * 注：这些是**位号**（用于 OR 成 64 位 `features`），不是最终数值。
     */
    public static final class Features {
        private Features() {
        }

        /** 0x1 Video (AirPlay) */
        public static final long VIDEO = 1L << 0;
        /** 0x4 Photo (caching) */
        public static final long PHOTO = 1L << 2;
        /** 0x8 Slideshow */
        public static final long SLIDESHOW = 1L << 3;
        /** 0x10 Screen mirroring */
        public static final long SCREEN_MIRRORING = 1L << 4;
        /** 0x40 Audio */
        public static final long AUDIO = 1L << 6;
        /** 0x80 Audio redundant */
        public static final long AUDIO_REDUNDANT = 1L << 7;
        /** 0x200 FairPlay */
        public static final long FAIRPLAY = 1L << 9;
        /** 0x400 Audio meta-data */
        public static final long AUDIO_METADATA = 1L << 10;
        /** 0x800 FairPlay HTTP */
        public static final long FAIRPLAY_HTTP = 1L << 11;
        /** 0x4000 Progress events */
        public static final long PROGRESS_EVENTS = 1L << 14;
        /** 0x10000 AirPlay video codecs (H.264/H.265) */
        public static final long VIDEO_CODECS = 1L << 16;
        /** 0x20000 Audio formats (ALAC/AAC) */
        public static final long AUDIO_FORMATS = 1L << 17;
        /** 0x40000 Authentication (HKP/pairing) */
        public static final long AUTHENTICATION = 1L << 18;
        /** 0x80000 Metadata (media info) */
        public static final long METADATA = 1L << 19;
        /** 0x100000 Unified media control */
        public static final long UNIFIED_MEDIA_CONTROL = 1L << 20;
        /** 0x200000 Streams (multi-stream) */
        public static final long STREAMS = 1L << 21;
        /** 0x400000 Peer joins/leaves */
        public static final long PEER_JOINS = 1L << 22;
        /** 0x1000000 View area */
        public static final long VIEW_AREA = 1L << 24;
        /** 0x2000000 UI context */
        public static final long UI_CONTEXT = 1L << 25;
        /** 0x4000000 Audio formats (v2) */
        public static final long AUDIO_FORMATS_V2 = 1L << 26;
        /** 0x8000000 Audio formats (v3) */
        public static final long AUDIO_FORMATS_V3 = 1L << 27;
        /** 0x10000000 Video (v2) */
        public static final long VIDEO_V2 = 1L << 28;
        /** 0x20000000 Audio (v2) */
        public static final long AUDIO_V2 = 1L << 29;
        /** 0x80000000 Media control (v2) */
        public static final long MEDIA_CONTROL_V2 = 1L << 31;

        /**
         * 完整清单（CarDeck 实测：**必须全量**，否则交集可能为空、视频不启用）。
         * 第二个 64 位段（高 64 位）按 Apple 约定另有一组，这里先按单段发。
         */
        public static long[] full() {
            return new long[]{
                    VIDEO | PHOTO | SLIDESHOW | SCREEN_MIRRORING | AUDIO | AUDIO_REDUNDANT
                            | FAIRPLAY | AUDIO_METADATA | FAIRPLAY_HTTP | PROGRESS_EVENTS
                            | VIDEO_CODECS | AUDIO_FORMATS | AUTHENTICATION | METADATA
                            | UNIFIED_MEDIA_CONTROL | STREAMS | PEER_JOINS | VIEW_AREA
                            | UI_CONTEXT | AUDIO_FORMATS_V2 | AUDIO_FORMATS_V3 | VIDEO_V2
                            | AUDIO_V2 | MEDIA_CONTROL_V2,
                    0x0000_0000_0000_0061L,  // 低段第二个值（与车机报告对齐）
            };
        }

        /** 只提 videoPlayback 的「坏」清单（CarDeck 记录的踩坑写法，留作反例）。 */
        public static long[] tooNarrow() {
            return new long[]{VIDEO | VIDEO_CODECS, 0};
        }
    }

    // ---------------------------------------------------------------- 128 字节帧

    /** `modesChanged` 的 `resources` 里 resourceID 含义。 */
    public static final int RESOURCE_MAIN_SCREEN = 1;
    public static final int RESOURCE_MAIN_AUDIO = 2;

    /** resource entity 含义：0=none, 1=controller(phone), 2=accessory(car)。 */
    public static final int ENTITY_NONE = 0;
    public static final int ENTITY_CONTROLLER = 1;
    public static final int ENTITY_ACCESSORY = 2;

    /** appStateID。 */
    public static final int APP_STATE_PHONE_CALL = 2;
    public static final int APP_STATE_TURN_BY_TURN = 3;
    public static final int APP_STATE_SPEECH = 1;

    /** 流类型。 */
    public static final int STREAM_SCREEN = 110;
    public static final int STREAM_AUDIO = 100;

    /** RTCP timing：请求 PT 0xD2 / 应答 PT 0xD3。 */
    public static final int RTCP_TIMING_REQUEST_PT = 0xD2;
    public static final int RTCP_TIMING_REPLY_PT = 0xD3;

    // ------------------------------------------------------------------ 设备标识

        /**
     * 由蓝牙 MAC 派生 iPhone 的 DeviceID（UUID 形式）。
     */
    public static String deviceIdFromMac(String mac) {
        if (mac == null || mac.isEmpty()) {
            return "00000000-0000-0000-0000-000000000000";
        }
        try {
            return java.util.UUID.nameUUIDFromBytes(
                    mac.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .toString().toUpperCase(java.util.Locale.US);
        } catch (Throwable t) {
            return "00000000-0000-0000-0000-000000000000";
        }
    }

    /**
     * 本机在两条协议里的统一身份（由 App 启动时设一次）。
     *
     * <p>取值 = {@code deviceID}（UUID 形式）+ {@code macAddress}（真实蓝牙 MAC）。
     */
    private static volatile String[] deviceIdentity;

    /** 设置设备身份（App 启动时调一次）。 */
    public static void setDeviceIdentity(String macAddress) {
        deviceIdentity = new String[]{deviceIdFromMac(macAddress), macAddress};
    }

    /** {@code deviceID}（UUID 形式）。 */
    public static String deviceId() {
        String[] id = deviceIdentity;
        return id != null ? id[0] : deviceIdFromMac(null);
    }

    /** {@code macAddress}（真实蓝牙 MAC）。 */
    public static String macAddress() {
        String[] id = deviceIdentity;
        return id != null ? id[1] : "00:00:00:00:00:00";
    }
}
