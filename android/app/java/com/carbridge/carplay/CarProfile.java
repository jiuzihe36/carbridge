package com.carbridge.carplay;

/**
 * 车机兼容参数。
 *
 * <p>优先使用车机在 {@code /info} 里上报的值；车机没上报时才回退到这里的默认值。
 */
public final class CarProfile {

    /** 车机型号（仅用于日志展示）。 */
    public static final String MODEL = "CarPlay Head Unit";

    /** 车机判定「这是 iPhone」的 SDP UUID，必须在配对时挂上。 */
    public static final String APPLE_DEVICE_EIR_UUID =
            "2d8d2466-e14d-451c-88bc-7301abea291a";

    /** 手机侧 iAP2 服务 UUID（标准）。 */
    public static final String APPLE_IAP2_UUID =
            "00000000-deca-fade-deca-deafdecacafe";

    /**
     * 部分车机使用的 iAP2 变体 UUID（段顺序与标准不同），与标准同时监听。
     */
    public static final String LEGACY_IAP2_UUID =
            "00000000-cade-defa-deca-deafdecacafe";

    /** 无线 CarPlay 的 setup UUID 对。 */
    public static final String SETUP_COMMON_UUID = APPLE_IAP2_UUID;
    public static final String SETUP_EIR_UUID = "843488ce-14dc-2a04-9727-575d50bf1fd3";

    /** 目标视频分辨率。 */
    public static final int WIDTH = 1920;
    public static final int HEIGHT = 720;

    /** 无线链路的音频打包间隔（毫秒）。车机没上报延迟时用它兜底。 */
    public static final int WIRELESS_AUDIO_MS = 80;

    /** 车机是否关闭了 MFi 认证。 */
    public static final boolean MFI_AUTH_REQUIRED = false;

    private CarProfile() {
    }

    /** 一行摘要（关于页 / 日志用）。 */
    public static String summary() {
        return MODEL + "｜" + WIDTH + "×" + HEIGHT + "｜音频 " + WIRELESS_AUDIO_MS + "ms";
    }
}