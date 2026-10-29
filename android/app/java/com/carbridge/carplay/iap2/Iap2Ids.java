package com.carbridge.carplay.iap2;

/**
 * iAP2 控制会话（CSM）消息 ID 与 0x1D01 身份字段。
 *
 * 依据：docs/RESEARCH-CARDECK.md §3.4。
 */
public final class Iap2Ids {

    private Iap2Ids() {
    }

    // 识别
    public static final int START_IDENTIFICATION = 0x1D00;
    public static final int IDENTIFICATION_INFORMATION = 0x1D01;
    public static final int IDENTIFICATION_ACCEPTED = 0x1D02;
    public static final int IDENTIFICATION_REJECTED = 0x1D03;

    // MFi 认证（手机是验证方，可不校验直接接受）
    public static final int AUTH_INFORMATION = 0xAA00;
    public static final int AUTH_CERTIFICATE = 0xAA01;
    public static final int AUTH_CHALLENGE_RESPONSE = 0xAA02;
    public static final int AUTH_RESPONSE = 0xAA03;
    public static final int AUTH_FAILED = 0xAA04;
    public static final int AUTH_SUCCEEDED = 0xAA05;
    public static final int AUTH_STATUS = 0xAA06;

    // 无线 CarPlay
    public static final int REQUEST_ACCESSORY_WIFI_CONFIGURATION = 0x5702;
    public static final int ACCESSORY_WIFI_CONFIGURATION = 0x5703;

    // 会话启动
    public static final int CARPLAY_AVAILABILITY = 0x4300;
    public static final int CARPLAY_START_SESSION = 0x4301;

    // 设备/传输
    public static final int WIRELESS_CARPLAY_UPDATE = 0x4E0D;
    public static final int DEVICE_TRANSPORT_IDENTIFIER = 0x4E0E;

    /**
     * 设备状态问候（手机 → 车机）。
     *
     * <p>CarDeck 实测（Audi MIB3）：<strong>缺这批消息时，车机对 0x4300 只回链路 ACK、
     * 不出 0x4301</strong> —— 症状就是「车机一点反应也没有」。
     * 顺序按真 iPhone 实录：0x4E0D → 0x4E0A → 0x4E09 → 0x4E0B → 0x4E0C → 0x4300。
     */
    public static final int DEVICE_INFORMATION_UPDATE = 0x4E09;  // 设备名
    public static final int DEVICE_LANGUAGE_UPDATE = 0x4E0A;      // 语言
    public static final int DEVICE_TIME_UPDATE = 0x4E0B;          // 时间（u64 epoch + u16 tz + u8 dst）
    public static final int DEVICE_UUID_UPDATE = 0x4E0C;          // 设备 UUID

    // 位置信息
    public static final int START_LOCATION_INFORMATION = 0xFFFA;

    // 车机的订阅请求（车机 → 手机）与其应答（手机 → 车机）
    public static final int START_MEDIA_LIBRARY_INFORMATION = 0x4C00;
    public static final int MEDIA_LIBRARY_INFORMATION = 0x4C01;
    public static final int START_MEDIA_LIBRARY_UPDATES = 0x4C03;
    public static final int MEDIA_LIBRARY_UPDATE = 0x4C04;
    public static final int START_BLUETOOTH_CONNECTION_UPDATES = 0x4E03;
    public static final int BLUETOOTH_CONNECTION_UPDATE = 0x4E04;
    public static final int START_NOW_PLAYING_UPDATES = 0x5000;
    public static final int NOW_PLAYING_UPDATE = 0x5001;
    public static final int STOP_NOW_PLAYING_UPDATES = 0x5002;
    public static final int START_POWER_UPDATES = 0xAE00;
    public static final int POWER_UPDATE = 0xAE01;
    public static final int START_CALL_STATE_UPDATES = 0x4154;
    public static final int CALL_STATE_UPDATE = 0x4155;

    // HID
    public static final int START_HID = 0x6800;
    public static final int DEVICE_HID_REPORT = 0x6801;

    // 0x1D01 身份字段 id
    public static final class Id {
        public static final int NAME = 0;
        public static final int MODEL_IDENTIFIER = 1;
        public static final int MANUFACTURER = 2;
        public static final int SERIAL_NUMBER = 3;
        public static final int FIRMWARE_VERSION = 4;
        public static final int HARDWARE_VERSION = 5;
        /** 配件发出的消息 ID 列表（u16[]）。 */
        public static final int MESSAGES_SENT = 6;
        /** 配件接收的消息 ID 列表（u16[]）。 */
        public static final int MESSAGES_RECEIVED = 7;
        public static final int POWER_PROVIDING = 8;
        public static final int MAX_CURRENT = 9;
        public static final int EA_PROTOCOL = 10;
        public static final int LANGUAGE = 12;
        public static final int LANGUAGE_LIST = 13;
        public static final int USB_TRANSPORT_COMPONENT = 16;
        public static final int BLUETOOTH_TRANSPORT_COMPONENT = 17;
        public static final int HID_COMPONENT = 18;
        /** 无线 CarPlay 组件（含 SSID）。 */
        public static final int WIRELESS_CARPLAY_COMPONENT = 24;
    }

    /** 把消息 ID 命名，便于日志。 */
    public static String name(int id) {
        switch (id) {
            case START_IDENTIFICATION: return "StartIdentification";
            case IDENTIFICATION_INFORMATION: return "IdentificationInformation";
            case IDENTIFICATION_ACCEPTED: return "IdentificationAccepted";
            case IDENTIFICATION_REJECTED: return "IdentificationRejected";
            case AUTH_INFORMATION: return "AuthInformation";
            case AUTH_CERTIFICATE: return "AuthCertificate";
            case AUTH_CHALLENGE_RESPONSE: return "AuthChallengeResponse";
            case AUTH_RESPONSE: return "AuthResponse";
            case AUTH_SUCCEEDED: return "AuthSucceeded";
            case REQUEST_ACCESSORY_WIFI_CONFIGURATION: return "RequestAccessoryWiFiConfiguration";
            case ACCESSORY_WIFI_CONFIGURATION: return "AccessoryWiFiConfiguration";
            case CARPLAY_AVAILABILITY: return "CarPlayAvailability";
            case CARPLAY_START_SESSION: return "CarPlayStartSession";
            case WIRELESS_CARPLAY_UPDATE: return "WirelessCarPlayUpdate";
            case DEVICE_TRANSPORT_IDENTIFIER: return "DeviceTransportIdentifier";
            case DEVICE_INFORMATION_UPDATE: return "DeviceInformationUpdate";
            case DEVICE_LANGUAGE_UPDATE: return "DeviceLanguageUpdate";
            case DEVICE_TIME_UPDATE: return "DeviceTimeUpdate";
            case DEVICE_UUID_UPDATE: return "DeviceUUIDUpdate";
            case START_LOCATION_INFORMATION: return "StartLocationInformation";
            case START_MEDIA_LIBRARY_INFORMATION: return "StartMediaLibraryInformation";
            case MEDIA_LIBRARY_INFORMATION: return "MediaLibraryInformation";
            case START_MEDIA_LIBRARY_UPDATES: return "StartMediaLibraryUpdates";
            case MEDIA_LIBRARY_UPDATE: return "MediaLibraryUpdate";
            case START_BLUETOOTH_CONNECTION_UPDATES: return "StartBluetoothConnectionUpdates";
            case BLUETOOTH_CONNECTION_UPDATE: return "BluetoothConnectionUpdate";
            case START_NOW_PLAYING_UPDATES: return "StartNowPlayingUpdates";
            case NOW_PLAYING_UPDATE: return "NowPlayingUpdate";
            case STOP_NOW_PLAYING_UPDATES: return "StopNowPlayingUpdates";
            case START_POWER_UPDATES: return "StartPowerUpdates";
            case POWER_UPDATE: return "PowerUpdate";
            case START_CALL_STATE_UPDATES: return "StartCallStateUpdates";
            case CALL_STATE_UPDATE: return "CallStateUpdate";
            case START_HID: return "StartHID";
            case DEVICE_HID_REPORT: return "DeviceHIDReport";
            default: return "0x" + Integer.toHexString(id);
        }
    }
}
