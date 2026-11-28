package com.carbridge.carplay.iap2;

import java.util.List;
import java.util.Locale;
import com.carbridge.carplay.util.Diag;

/**
 * iAP2 控制会话（发送端 / iPhone 角色）：识别 → MF
 * i 认证 → 取 WiFi 凭据 → 取 CarPlay 端点。
 */
public final class Iap2SenderSession {

    public interface Listener {
        void onState(State state);

        void onLog(String msg);

        /** 从 0x5703 拿到车机热点凭据（加入热点后才能走 mDNS / AirPlay）。 */
        void onWifiConfig(WifiConfig config);

        /** 从 0x4301 拿到 CarPlay 会话端点；车机不发 0x4301 时不会触发（走 mDNS）。 */
        void onCarPlayEndpoint(Endpoint endpoint);

        /** 收到车机身份（0x1D01）→ 进入识别/MFi 阶段，进度条据此推进。 */
        void onIdentified();
    }

    public enum State {
        IDLE, IDENTIFYING, AUTHENTICATING, REQUESTING_WIFI, STARTING_SESSION, READY, CLOSED,
        /** 明确失败（车机拒绝识别 / 认证失败 / 挑战被全部拒）。上层据此重连。 */
        ERROR
    }

    /** 车机退订了 now-playing 更新（0x5002）后不要再发。 */
    private volatile boolean nowPlayingUpdatesStopped;

    /** 0x5703 的车机热点凭据（明文 TLV）。 */
    public static final class WifiConfig {
        public final String ssid;
        public final String password;
        public final int channel;
        /** 0=None 1=WEP 2=WPA/WPA2 3=WPA3 过渡 4=WPA3 */
        public final int security;

        public WifiConfig(String ssid, String password, int channel, int security) {
            this.ssid = ssid;
            this.password = password;
            this.channel = channel;
            this.security = security;
        }

        @Override
        public String toString() {
            return "Wifi{ssid=" + ssid + ", ch=" + channel + ", sec=" + security + ", pwd="
                    + (password == null ? "null" : password.length() + "B") + "}";
        }
    }

    /** 0x4301 的 CarPlay 会话端点。 */
    public static final class Endpoint {
        public final String host;
        public final int port;
        public final String deviceId;

        public Endpoint(String host, int port, String deviceId) {
            this.host = host;
            this.port = port;
            this.deviceId = deviceId;
        }

        @Override
        public String toString() {
            return "Endpoint{" + host + ":" + port + ", id=" + deviceId + "}";
        }
    }

    /**
     * MFi 挑战长度回退序列。
     *
     * <p>⚠️ 没有 universally 安全的首发长度：DiPlay（Carlinkit AuthV3 EC 身份）只接受精确
     * 32B，收到 20B 直接断链；奥迪 MIB3 反过来，对 32B 静默/拆链、对 20B 秒答。
     * 所以顺序本身保护不了任何一端，真正的保险是「被拒档位记忆」（{@link #REJECTED_CHALLENGE_LEVELS}：
     * 挑战在途时链路被拆 → 记住本档，下条链路跳过）。本车（Chery）实测首发 20B 即应答，保持现状。
     */
    private static final int[] CHALLENGE_LENGTHS = {20, 32, 16};
    /** 每个档位的等待时长（与 CHALLENGE_LENGTHS 一一对应）。 */
    private static final int[] CHALLENGE_TIMEOUT_MS = {3000, 6000, 3000};

    private final Listener listener;
    private final Iap2LinkEngine engine;

    /** 设备名（0x4E09 设备信息）。真 iPhone 报的是自己的蓝牙名。 */
    private String deviceName = "iPhone";
    /** 真实蓝牙 MAC —— 车机会校验它（0x4300 / 0x4E0E 都带它）。 */
    private String deviceMac = "";
    /** 是否正在通话（0x4155 的状态）。 */
    private boolean phoneCallActive;
    /** 车机自报的型号（iAP2 识别信息里的 ModelIdentifier，目标车机 = "HeadUnit"）。 */
    private String carModel;
    /** 车机自报的序列号（目标车机 = "0000000000000"）。 */
    private String carSerial;

    /**
     * 会话状态。⚠️ volatile 不是装饰：写方是 iAP2 读线程 / 定时器线程，
     * 读方是主线程看门狗（wifiTimeout、challengeWatchdog），JMM 下不加会读到旧值 ——
     * 现场表现就是「看着门狗时它以为还在 AUTHENTICATING，漏发 0x4300」。
     */
    private volatile State state = State.IDLE;
    /** 挑战档位与「已发出」标志：同样跨线程读写（authTimer → 主线程看门狗）。 */
    private volatile int challengeIndex = -1;
    private volatile boolean challengeSent;
    private volatile long challengeSentAtMs;
    /**
     * 被拒的挑战长度档位（**进程级**静态记忆）：会话每轮新建，实例字段会随着会话销毁
     * 丢失记忆 → 下轮又从被拒的档位发起 → 永久失败循环。static 才能跨会话生效。
     * 只在「挑战在途时链路被对端拆掉」时记录（这是「这个长度把对端打死了」的强信号）。
     */
    private static final java.util.Set<Integer> REJECTED_CHALLENGE_LEVELS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * 是否验证车机 MFi 签名（严格模式）。
     *
     * <p>默认 false（宽松）：车机 {@code CONF_AUTHENTICATION=false} 时不验签也能通；
     * 对严格车机（要求真 iPhone 级验签）置 true 后，用证书公钥验 0xAA03 签名，
     * 失败则回 0xAA04 拒绝而非 0xAA05 接受。
     */
    private volatile boolean verifyMfiSignature = false;
    /** 从 0xAA01 证书解析出的车机 EC 公钥（P-256，验签用）。 */
    private java.security.PublicKey peerEcKey;
    /** 证书是否落在 Apple 的 OID 体系里（判定「是不是 Apple 签发的配件」）。 */
    /** 已经打印过多少条入站消息（前 24 条全打，之后只打未识别的）。 */
    private int rxMsgLogged;
    private boolean peerCertApple;

    /** 从 0xAA01 证书解析出的车机 RSA 公钥（验签用）。 */
    private MfiCrypto.RsaKey peerRsaKey;
    /** 上一次发出的挑战（验签要拿它算 SHA-1 比对）。 */
    private byte[] lastChallenge;

    /** 内建定时器：挑战超时与认证后看门狗，不依赖上层 watchdog。
     *  双线程：发挑战的任务可能被蓝牙写堵（对端不读时 RFCOMM 写会阻塞），
     *  单线程会把识别/认证后两只看门狗一起拖晚。任务间有 token 与状态闸，并发安全。 */
    private final java.util.concurrent.ScheduledExecutorService authTimer =
            java.util.concurrent.Executors.newScheduledThreadPool(2,
                    new java.util.concurrent.ThreadFactory() {
                        @Override public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "iap2-auth-timer");
                            t.setDaemon(true);
                            return t;
                        }
                    });
    /** 每次发挑战自增，用来判定「定时器回来时还是不是同一轮」。 */
    private volatile int challengeToken;
    /** 0xAA00 → 0xAA01 的重发计数（看门狗用，每轮认证归零）。 */
    private int authCertRetries;
    /** 挑战梯当前走到第几步（-1=未开始）；EC 证书会从 32B 起跑，所以不能直接用档位下标。 */
    private int challengeStep = -1;
    /** 本端主动关闭中（close()→CLOSED）：链路被拆不能算「挑战档位被拒」。 */
    private volatile boolean closing;
    /** 认证完成前早到的 0x5703 凭据缓存（认证成功后按正常流程处理）。 */
    private volatile WifiConfig earlyWifiConfig;
    /** 上一份 0xAA03 签名（重放检测）与它对应的挑战。 */
    private volatile byte[] lastResponseSig;
    private volatile byte[] lastResponseFor;
    /** 认证后是否真的等到了 0x5703 / 0x4301。 */
    private volatile boolean wifiConfigSeen;
    private volatile boolean endpointSeen;
    /** 设备状态问候 / 0x4300 每条链路只发一次（onLinkUp 重同步时归零）；跨线程读写 → volatile。 */
    private volatile boolean greeted;
    /**
     * 0x4300（CarPlayAvailability）每轮凭据**至多一次**。
     *
     * <p>它是「询问车机能否开始 CarPlay」的全局请求，不是可重放的订阅：
     * 超时兜底和晚到的 0x5703 都会走到发送点，没有闸门时同一轮会发两条语义相同的请求。
     * 车机对这种重复请求没有定义行为（mcarplay 在真车上有过 0x4300 不再 ACK 的记录）。
     * 「一轮」= 一份 0x5703 凭据：新一轮凭据到达时闸门重置（见 onWifiConfiguration）。
     */
    private volatile boolean carPlayAvailabilitySent;
    /** 车机在 0x1D01 里声明「能收哪些手机消息」（id 0x0007）。 */
    private final java.util.Set<Integer> accessoryAccepts = new java.util.HashSet<Integer>();
    /** 车机在 0x1D01 里声明「它会发哪些消息」（id 0x0006）。 */
    private final java.util.Set<Integer> accessorySends = new java.util.HashSet<Integer>();
    /** 车机给没给这份清单（没给就不设门禁）。 */
    private boolean acceptsListDeclared;
    /** 车机的定位组件里有没有 GPGSV（决定 0xFFFA 要不要带 param 3）。 */
    private boolean carOffersGpgsv;

    public Iap2SenderSession(Listener listener, Iap2LinkEngine.Transport linkTransport,
                             Iap2LinkEngine.Listener linkListener) {
        this.listener = listener;
        this.engine = new Iap2LinkEngine(linkTransport, new Iap2LinkEngine.Listener() {
            @Override public void onState(Iap2LinkEngine.State s) {
                linkListener.onState(s);
                // ⚠️ 链路层被拆（车机发 RST / 蓝牙断开 / 写失败）时必须把会话状态一起落下来。
                //    以前只处理 NORMAL：RST 之后 Iap2SenderSession.state() 仍停在 READY，
                //    上层据此以为「还连着」，于是不重连、也不再发任何东西 —— 现场表现就是
                //    「画面停住、日志里最后一句是收到某条消息」。（假车机测试台一次跑出来的）
                if (s == Iap2LinkEngine.State.NORMAL) onLinkUp();
                else if (s == Iap2LinkEngine.State.CLOSED) {
                    // 挑战在途时链路被拆 = 这个长度把对端打死了（DiPlay 实录：收到 20B 直接断链）。
                    // 记住本档（进程级），下条链路直接跳过它，否则每轮都从同一档发起 = 永久失败循环。
                    // ⚠️ 但**本端主动关闭**（换会话 close()/prev.close()）与握手前掉线不算：
                    //    以前无条件记录 —— 一次正常的会话替换就能把好档位永久封禁。
                    if (state == State.AUTHENTICATING && challengeSent && !closing) {
                        rejectCurrentChallengeLevel();
                    }
                    setState(State.CLOSED);
                }
            }
            @Override public void onMessage(Iap2Csm.Message m) {
                linkListener.onMessage(m);
                Iap2SenderSession.this.onMessage(m);
            }
            @Override public void onLog(String m) { linkListener.onLog(m); }
        }, Iap2LinkEngine.Role.INITIATOR);
    }

    public State state() { return state; }

    /** 车机自报的型号（iAP2 识别信息）；没拿到返回 null。 */
    public String carModel() { return carModel; }

    /** 车机自报的序列号；没拿到返回 null。 */
    public String carSerial() { return carSerial; }
    public Iap2LinkEngine engine() { return engine; }

        /**
     * 设置「本机是谁」。
     */
    public void setDeviceIdentity(String name, String mac) {
        if (name != null && !name.isEmpty()) this.deviceName = name;
        if (mac != null) this.deviceMac = mac;
    }

    /** 启用/关闭 MFi 严格验签（默认关闭，宽松模式兼容 {@code CONF_AUTHENTICATION=false} 车机）。 */
    public void setVerifyMfiSignature(boolean verify) {
        this.verifyMfiSignature = verify;
    }

    /** 是否解析出了车机证书的 RSA 公钥（用于诊断「证书能不能验签」）。 */
    public boolean hasPeerCertificate() {
        return peerRsaKey != null;
    }

        /**
     * 报告通话状态（CarPlay 的 CallStateUpdate，0x4155）。
     */
    public void setPhoneCallActive(boolean active) {
        if (phoneCallActive == active) return;
        phoneCallActive = active;
        engine.sendCsm(Iap2Ids.CALL_STATE_UPDATE, Iap2Csm.u8(2, active ? 1 : 0));
        log("上报 CallStateUpdate (0x4155) = " + (active ? "通话中" : "已挂断"));
    }

        /**
     * 认证后的「设备状态问候」。
     */
    private void sendDeviceGreeting() {
        if (greeted) return;
        greeted = true;
        long epochSeconds = System.currentTimeMillis() / 1000L;

        // ⚠️ 顺序按真 iPhone 实录：0x4E0D → 0x4E0A → 0x4E09 → 0x4E0B → 0x4E0C → 0x4300。
        //    0x4E0D（WirelessCarPlayUpdate）必须**第一个**发：它声明「我是无线 CarPlay 设备」，
        //    车机要等这条才启用无线 CarPlay 路径；以前把它排在最后，严格的车机可能
        //    在收到它之前就已经放弃无线 CarPlay（现场「车机一点反应也没有」）。
        // ⚠️ 这五条**不按车机的可收清单过滤**：真 iPhone 无条件发，
        //    而车机的清单常常不完整（奥迪 MIB3 3315 的可收清单只有 5 项，
        //    0x4E0D/0x4E0B/0x4E0E 都不在里面，但车机逐条 ACK 到位 ——
        //    mcarplay 同日真车日志）。以前按清单门禁，没声明就被跳过：
        //    车机收到的是「半个设备」，严格的车机就此不再往下走。
        engine.sendCsm(Iap2Ids.WIRELESS_CARPLAY_UPDATE, Iap2Csm.u8(0, 1));
        engine.sendCsm(Iap2Ids.DEVICE_LANGUAGE_UPDATE, Iap2Csm.paramString(0, deviceLanguage()));
        engine.sendCsm(Iap2Ids.DEVICE_INFORMATION_UPDATE, Iap2Csm.paramString(0, deviceName));
        engine.sendCsm(Iap2Ids.DEVICE_TIME_UPDATE, Iap2Csm.concat(
                Iap2Csm.paramUInt(0, epochSeconds, 8),
                // 真 iPhone 报的是**本地时区偏移**（分钟，带符号的 u16 补码），
                // 不是 0。以前写死 0 = 告诉车机「本机在 UTC」。
                Iap2Csm.paramUInt(1, timezoneOffsetMinutes() & 0xFFFF, 2),
                Iap2Csm.u8(2, 0)));
        engine.sendCsm(Iap2Ids.DEVICE_UUID_UPDATE, Iap2Csm.paramString(0, deviceUuid()));
        // 0x4E0E：车机即使没声明也会记一条日志后忽略，不致命，所以照发。
        engine.sendCsm(Iap2Ids.DEVICE_TRANSPORT_IDENTIFIER, Iap2Csm.concat(
                Iap2Csm.paramString(0, deviceMac),
                Iap2Csm.paramString(1, usbTransportIdentifier())));
        if (allowed(Iap2Ids.START_LOCATION_INFORMATION)) {
            // 每个句子类型一个**空标志**参数：1=GGA 2=RMC 3=GSV（车机声明才有）4=PASCD
            byte[] body = Iap2Csm.concat(
                    Iap2Csm.param(1, new byte[0]),
                    Iap2Csm.param(2, new byte[0]));
            if (carOffersGpgsv) {
                body = Iap2Csm.concat(body, Iap2Csm.param(3, new byte[0]));
            }
            body = Iap2Csm.concat(body, Iap2Csm.param(4, new byte[0]));
            engine.sendCsm(Iap2Ids.START_LOCATION_INFORMATION, body);
        }
        log("发送设备状态问候 0x4E0D/0x4E0A/0x4E09/0x4E0B/0x4E0C/0x4E0E/0xFFFA"
                + "（name=" + deviceName + " mac=" + deviceMac + "）");
    }

    /**
     * 车机会校验的「硬件标识」之二：USB 传输标识。
     * 真 iPhone 报的是 24 位大写十六进制（真机日志：
     * {@code usbTransportIdentifier=0000815000190xxxxxxxxxxx}），我们用 MAC 派生一个同形状的。
     */
    private String usbTransportIdentifier() {
        // 真 iPhone 的 usbTransportIdentifier 形如 0000815000190xxxxxxx：
        // 前缀 0000815 是 Apple 的板型前缀，后面跟设备唯一号。以前直接用 MAC 派生，
        // 形状对（24 位大写十六进制）但前缀不像 Apple，个别车机会拿它做二次校验。
        StringBuilder s = new StringBuilder("000081500");
        for (char c : deviceMac.toCharArray()) {
            if (Character.digit(c, 16) >= 0) s.append(Character.toUpperCase(c));
        }
        while (s.length() < 24) s.append('0');
        return s.substring(0, 24);
    }

    /** 车机在 0x1D01 里声明「能收哪些手机消息」（id 0x0007）。 */
    private boolean allowed(int messageId) {
        if (!acceptsListDeclared) return true;      // 车机没给清单 → 都发
        return accessoryAccepts.contains(messageId);
    }

    /** 报给车机的语言：跟系统走（真 iPhone 报自己的 locale），拿不到退回 en。 */
    private static String deviceLanguage() {
        try {
            java.util.Locale l = java.util.Locale.getDefault();
            String lang = l.getLanguage();
            if (lang == null || lang.isEmpty()) return "en";
            String country = l.getCountry();
            return country == null || country.isEmpty() ? lang : lang + "-" + country;
        } catch (Throwable t) {
            return "en";
        }
    }

    /** 本地时区相对 UTC 的偏移（分钟）。东八区 = +480。 */
    private static int timezoneOffsetMinutes() {
        try {
            return java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 两份清单里是否声明了任一 EA 消息 —— 用来把「走 EA 通道的车机」和「纯 CarPlay 车机」分开。 */
    private boolean declaresAnyEaMessage() {
        int[] ea = {Iap2Ids.EA_OPEN_SESSION, Iap2Ids.EA_CLOSE_SESSION, Iap2Ids.EA_SESSION_STATUS};
        for (int id : ea) {
            if (accessoryAccepts.contains(id) || accessorySends.contains(id)) return true;
        }
        return false;
    }
    /** 由真实蓝牙 MAC 派生的稳定设备 UUID（CarDeck 同款）。 */
    private String deviceUuid() {
        // ⚠️ 必须与 AirPlay SETUP 的 deviceID **完全一致**（真 iPhone 两处同值）：
        //    都是「由蓝牙 MAC 派生的 UUID」。车机会把两者对上。
        if (deviceMac == null || deviceMac.isEmpty()) return "";
        return com.carbridge.carplay.core.AirPlaySetup.deviceIdFromMac(deviceMac);
    }

    /**
     * 0x4300 CarPlayAvailability —— 带真实蓝牙 MAC。
     *
     * <p>外层是一个参数组：id=1，值里是「标志(0)=1」和「蓝牙 MAC(1)」。
     */
    /**
     * 0x4300 发送闸门（原子过闸）：onWifiConfigTimeout（定时器线程）与
     * onWifiConfiguration（iAP2 读线程）会并发到达这里，非原子的检查+置位会双发。
     */
    private synchronized boolean claimCarPlayAvailability() {
        if (carPlayAvailabilitySent) return false;
        carPlayAvailabilitySent = true;
        return true;
    }

    private void sendCarPlayAvailability() {
        if (!claimCarPlayAvailability()) {
            log("0x4300 本轮已发过，跳过重复请求");
            return;
        }
        sendDeviceGreeting();
        byte[] body = Iap2Csm.group(1,
                Iap2Csm.u8(0, 1),
                Iap2Csm.paramString(1, deviceMac));        engine.sendCsm(Iap2Ids.CARPLAY_AVAILABILITY, body);
        log("发送 CarPlayAvailability (0x4300)，携带蓝牙 MAC " + deviceMac);
    }

    /**
     * 0x5703 一直没来时的兜底：新协议车机把 AP 信息放在 0x4301 里，
     * 等不到热点凭据也得往下走（CarDeck：0x5703 缺席时改走 0x4300/0x4301）。
     */
    public void onWifiConfigTimeout() {
        if (state != State.REQUESTING_WIFI) return;
        log("0x5703 超时未到，改走 0x4300/0x4301（AP 信息由车机在 0x4301 里给）");
        // 车机源码（BluetoothSettingFragment.selectCarplay）里有一条硬条件：
        //   点「CarPlay」时它先查 WifiApMgr.isApEnable()，热点没开就只弹一句提示、
        //   **根本不发起连接** —— 我们这边看到的就是「一直等 0x5703 等不到」。
        log("   ↳ 如果车机上点 CarPlay 只弹了一句提示：请先打开车机的「车载热点 / Wi-Fi 热点」再点一次");
        setState(State.STARTING_SESSION);
        sendCarPlayAvailability();
    }

        /**
     * 重新要一次热点凭据（再发一遍 0x5702）。
     */
    public void requestWifiConfiguration() {
        if (state == State.CLOSED) return;
        log("重新请求热点配置（0x5702）");
        setState(State.REQUESTING_WIFI);
        engine.sendCsm(Iap2Ids.REQUEST_ACCESSORY_WIFI_CONFIGURATION, new byte[0]);
    }

    public void start() { engine.start(); }
    public void onBytes(byte[] data) { engine.onBytes(data); }
    public void close() {
        closing = true;   // 先立标记：链路因此被拆不能算「挑战档位被拒」
        try { authTimer.shutdownNow(); } catch (Throwable tDiag) { Diag.w("Iap2SenderSession.close", tDiag); } engine.close(); setState(State.CLOSED); }

    // ================================================================== 链路起来后

    private void onLinkUp() {
        // 每轮链路的「只发一次」标志全部归零：重同步（对端重启链路层）后
        // 车机等的是一次完整的重新识别 + 问候，沿用旧标志它永远等不到。
        greeted = false;
        carPlayAvailabilitySent = false;
        wifiConfigSeen = false;
        endpointSeen = false;
        challengeIndex = -1;
        challengeStep = -1;
        challengeSent = false;
        challengeToken++;            // 作废上一轮还没跑完的挑战定时器
        setState(State.IDENTIFYING);
        engine.sendCsm(Iap2Ids.START_IDENTIFICATION, new byte[0]);
        // 识别看门狗：车机 15s 内不回 0x1D01，说明链路通了但它不说话（或说了我们解析不了）。
        // 以前这种情况会一直停在 IDENTIFYING，界面看着像卡住。
        try {
            authTimer.schedule(new Runnable() {
                @Override public void run() {
                    if (state == State.IDENTIFYING) {
                        log("⚠ 发出 0x1D00 后 15s 没收到 0x1D01 —— 车机没回应识别");
                        listener.onLog("车机没有任何识别回应：确认车机已打开 CarPlay 且选中本机");
                        setState(State.ERROR);
                    }
                }
            }, 15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable tDiag) {
            Diag.w("Iap2SenderSession.onLinkUp", tDiag);
        }
        log("发送 StartIdentification (0x1D00)");
    }

    private void onMessage(Iap2Csm.Message m) {
        // 全量留痕（前 24 条）：卡在认证时，最需要知道「车机到底发了哪几条」。
        // 以前只打未识别的消息，于是「证书没到」和「证书到了但没解析出来」长得一模一样。
        if (rxMsgLogged < 24) {
            rxMsgLogged++;
            log("← iAP2 消息 " + Iap2Ids.name(m.messageId) + " (0x"
                    + Integer.toHexString(m.messageId) + ") " + m.body.length + "B");
        }
        switch (m.messageId) {
            case Iap2Ids.START_IDENTIFICATION:
                onStartIdentification();
                break;
            case Iap2Ids.IDENTIFICATION_INFORMATION:
                onIdentificationInformation(m);
                break;
            case Iap2Ids.AUTH_CERTIFICATE:
                onAuthCertificate(m);
                break;
            case Iap2Ids.AUTH_RESPONSE:
                onAuthResponse(m);
                break;
            case Iap2Ids.ACCESSORY_WIFI_CONFIGURATION:
                onWifiConfiguration(m);
                break;
            case Iap2Ids.CARPLAY_START_SESSION:
                onCarPlayStartSession(m);
                break;
            case Iap2Ids.START_HID:
                log("收到 StartHID (0x6800)");
                break;
            case Iap2Ids.START_POWER_UPDATES:
                answerPowerUpdates();
                break;
            case Iap2Ids.START_NOW_PLAYING_UPDATES:
                answerNowPlaying();
                break;
            case Iap2Ids.START_MEDIA_LIBRARY_INFORMATION:
                answerMediaLibrary();
                break;
            case Iap2Ids.START_MEDIA_LIBRARY_UPDATES:
                answerMediaLibraryUpdates();
                break;
            case Iap2Ids.START_BLUETOOTH_CONNECTION_UPDATES:
                answerBluetoothConnections();
                break;
            case Iap2Ids.START_CALL_STATE_UPDATES:
                // 应答要报**当前真实状态**：车机订阅瞬间若正在通话，
                // 报 Disconnected 会让它的通话 UI / 音频路由走反。
                engine.sendCsm(Iap2Ids.CALL_STATE_UPDATE, Iap2Csm.u8(2, phoneCallActive ? 1 : 0));
                log("应答 CallStateUpdate (0x4155) = " + (phoneCallActive ? "通话中" : "Disconnected"));
                break;
            case Iap2Ids.IDENTIFICATION_REJECTED:
                // 车机拒绝我们的识别信息（多半是设备信息/HW 标识对不上）
                onFatalMessage(m.messageId, "设备识别");
                break;
            case Iap2Ids.AUTH_FAILED:
                onFatalMessage(m.messageId, "MFi 认证");
                break;
            case Iap2Ids.AUTH_STATUS:
                // 认证状态（成功/失败），只作日志
                log("收到 MFi AuthStatus（" + m.body.length + "B）");
                break;
            case Iap2Ids.STOP_NOW_PLAYING_UPDATES:
                // 车机不再要 now-playing 更新 → 停发（否则一直空转）
                nowPlayingUpdatesStopped = true;
                log("车机停止订阅 now-playing 更新，停止上报");
                break;
            case Iap2Ids.DEVICE_HID_REPORT:
                // 设备侧主动上报的 HID 报告（另一条按键/触摸通路）
                log("收到 DeviceHIDReport（" + m.body.length + "B）");
                break;
            default:
                log("未处理消息 " + Iap2Ids.name(m.messageId) + " (" + m.body.length + "B)");
                break;
        }
    }

    /**
     * 车机明确告诉我们「不行」的消息 —— 必须有终态，
     * 否则状态会永远停在中间态（界面看着像卡死，也不会重试）。
     */
    private void onFatalMessage(int messageId, String what) {
        log("车机拒绝：" + what + "（" + Iap2Ids.name(messageId) + "）");
        listener.onLog("车机拒绝了" + what + " → 结束本次 iAP2 会话");
        setState(State.ERROR);
    }

    // ---------------------------------------------------------------- 订阅应答
    // 车机在认证后会连着发好几个「Start*」订阅请求，**每一个都要应答**，
    // 漏答一个车机就不再推进（表现为「认证过了但不出画面」）。

    /** 0xAE00 → 两条 0xAE01：最大电流 1000 mA，然后「会充电」。 */
    private void answerPowerUpdates() {
        engine.sendCsm(Iap2Ids.POWER_UPDATE, Iap2Csm.paramUInt(0, 1000, 2));
        engine.sendCsm(Iap2Ids.POWER_UPDATE, Iap2Csm.u8(1, 1));
        log("应答 PowerUpdate (0xAE01) ×2：max_current=1000mA / will_charge=true");
    }

    /**
     * 0x5000 → 0x5001「重置」状态。
     *
     * <p>注意嵌套：playback_status(id0)、app 名(id7)、bundle id(id16) 都是
     * <strong>组 1 里的子参数</strong>，不是 0x5001 的顶层参数。
     * 嵌套错了车机会在 CSM 层丢掉这条更新并拆链（orangecar 注释原文）。
     */
    private void answerNowPlaying() {
        if (nowPlayingUpdatesStopped) {
            // 车机已经退订（0x5002）→ 别再发，免得车机嫌我们多嘴
            return;
        }
        byte[] group = Iap2Csm.concat(
                Iap2Csm.u8(0, 0),           // playback_status = Stopped
                Iap2Csm.paramString(7, ""), // app 名
                Iap2Csm.paramString(16, "")); // bundle id
        engine.sendCsm(Iap2Ids.NOW_PLAYING_UPDATE, Iap2Csm.group(1, group));
        log("应答 NowPlayingUpdate (0x5001) reset");
    }

    /** 0x4C00 → 0x4C01：报一个空的本机媒体库（名字 "iPhone"、UID "1"、类型 LocalDevice）。 */
    private void answerMediaLibrary() {
        byte[] group = Iap2Csm.concat(
                Iap2Csm.paramString(0, deviceName),  // MediaLibraryName
                Iap2Csm.paramString(1, "1"),         // MediaLibraryUniqueIdentifier
                Iap2Csm.u8(2, 0));                    // MediaLibraryType = LocalDevice
        engine.sendCsm(Iap2Ids.MEDIA_LIBRARY_INFORMATION, Iap2Csm.group(0, group));
        log("应答 MediaLibraryInformation (0x4C01) name=" + deviceName + " uid=1");
    }

    /** 0x4C03 → 0x4C04：同一个库，版本 "1"，没有条目。 */
    private void answerMediaLibraryUpdates() {
        engine.sendCsm(Iap2Ids.MEDIA_LIBRARY_UPDATE, Iap2Csm.concat(
                Iap2Csm.paramString(0, "1"),   // MediaLibraryUniqueIdentifier
                Iap2Csm.paramString(1, "1"))); // MediaLibraryRevision
        log("应答 MediaLibraryUpdate (0x4C04) uid=1 rev=1");
    }

    /**
     * 0x4E03 → 0x4E04：报「本机当前连着的蓝牙 profile」。
     *
     * <p>盒子固件实证（audit10/C-13）：真 iPhone 会按序枚举九个 profile
     * （HFP / PBAP / AVRCP / A2DP / HID / iAP2 / PAN-NAP / MAP / PAN-client）。
     * 我们以前回**空表** —— 在「按 profile 判能力」的车机上等价于「这台手机没有蓝牙功能」。
     * 车机的 CarPlay 界面正靠这张表决定来电/短信/媒体图标是否可用。
     *
     * <p>编码：bluetoothComponentIdentifier(u16)=1，connectedBluetoothProfiles 为
     * u16 数组（按 iAP2 枚举顺序 0..8）。车机不认就忽略，不影响主链路。
     */
    private void answerBluetoothConnections() {
        byte[] profiles = new byte[9 * 2];
        for (int i = 0; i < 9; i++) {
            profiles[i * 2] = 0;                    // 高字节
            profiles[i * 2 + 1] = (byte) i;         // 低字节：profile id
        }
        engine.sendCsm(Iap2Ids.BLUETOOTH_CONNECTION_UPDATE, Iap2Csm.concat(
                Iap2Csm.paramUInt(0, 1, 2),     // BluetoothTransportComponentIdentifier = 1
                Iap2Csm.param(1, profiles)));   // ConnectedBluetoothProfiles = 9 项
        log("应答 BluetoothConnectionUpdate (0x4E04) component=1 profiles=9 项"
                + "（HFP/PBAP/AVRCP/A2DP/HID/iAP2/PAN-NAP/MAP/PAN-client）");
    }

    // ---------------------------------------------------------------- 识别

    private void onIdentificationInformation(Iap2Csm.Message m) {
        List<Iap2Csm.Param> params = m.params();
        Iap2Csm.Param name = Iap2Csm.find(params, Iap2Ids.Id.NAME);
        Iap2Csm.Param sent = Iap2Csm.find(params, Iap2Ids.Id.MESSAGES_SENT);
        Iap2Csm.Param received = Iap2Csm.find(params, Iap2Ids.Id.MESSAGES_RECEIVED);
        log("收到 IdentificationInformation：name=" + (name == null ? "?" : name.asString())
                + " 可收 " + (received == null ? "?" : received.value.length / 2) + " 项 /"
                + " 可发 " + (sent == null ? "?" : sent.value.length / 2) + " 项");

        // ⚠️ **车机的身份就在这条消息里**（这是车机作为 iAP2 主机报出来的配件信息），
        //    比蓝牙名字可靠得多 —— 蓝牙名用户能改，CoD 重新配对后会变，
        //    而这里的 ModelIdentifier/SerialNumber 是车机固件里写死的。
        //    目标车机 的 /product/etc/vehicle.ipod.conf 里就是：
        //      <Name value="CarPlay"/> <ModelNumber value="HeadUnit"/>
        //      <Manufacture value="VENDOR"/> <SerialNumber value="0000000000000"/>
        Iap2Csm.Param model = Iap2Csm.find(params, Iap2Ids.Id.MODEL_IDENTIFIER);
        Iap2Csm.Param maker = Iap2Csm.find(params, Iap2Ids.Id.MANUFACTURER);
        Iap2Csm.Param serial = Iap2Csm.find(params, Iap2Ids.Id.SERIAL_NUMBER);
        Iap2Csm.Param fw = Iap2Csm.find(params, Iap2Ids.Id.FIRMWARE_VERSION);
        if (model != null || serial != null) {
            carModel = model == null ? null : model.asString();
            carSerial = serial == null ? null : serial.asString();
            log("  车机身份：model=" + carModel + " serial=" + carSerial
                    + " maker=" + (maker == null ? "?" : maker.asString())
                    + " fw=" + (fw == null ? "?" : fw.asString()));
            listener.onIdentified();
        }
        // 无线 CarPlay 组件（id 24）里带车机热点信息
        Iap2Csm.Param wireless = Iap2Csm.find(params, Iap2Ids.Id.WIRELESS_CARPLAY_COMPONENT);
        if (wireless != null) {
            log("  无线 CarPlay 组件 " + wireless.value.length + "B（" + hex(wireless.value, 32) + "）");
        }

        // 车机声明「它能收哪些手机消息」（id 0x0007，u16[] 消息 ID）。
        // 没在清单里的消息车机会丢弃（真机日志：「Unrecognized iAP2 message received」），
        // 所以后面所有的主动上报都要先过这道门禁。
        if (received != null) {
            acceptsListDeclared = true;
            accessoryAccepts.clear();
            byte[] v = received.value;
            for (int i = 0; i + 1 < v.length; i += 2) {
                accessoryAccepts.add(((v[i] & 0xFF) << 8) | (v[i + 1] & 0xFF));
            }
            log("  车机 want-list：" + describeMessageIds(received));
        }
        // 车机声明「它会发哪些消息」（id 0x0006，u16[]）。它决定 0x4300 该不该提前发。
        if (sent != null) {
            accessorySends.clear();
            byte[] v = sent.value;
            for (int i = 0; i + 1 < v.length; i += 2) {
                accessorySends.add(((v[i] & 0xFF) << 8) | (v[i + 1] & 0xFF));
            }
        }
        // 车机的定位组件（id 0x0016）里声明了哪些定位句子。
        Iap2Csm.Param loc = Iap2Csm.find(params, 0x0016);
        if (loc != null) {
            String sentences = loc.asString();
            carOffersGpgsv = sentences != null
                    && (sentences.contains("GPGSV") || sentences.contains("GPGGA"));
        }

        // 能力清单（0x1D01 的 id 6/7）是后续一切的依据：
        // 例如奥迪不发 0x4301，那么就不能等 CarPlayStartSession，只能靠 mDNS。
        engine.sendCsm(Iap2Ids.IDENTIFICATION_ACCEPTED, new byte[0]);
        log("发送 IdentificationAccepted (0x1D02)");

        // ⚠️ MFi 认证由**手机发起**：先请对端给证书（orangecar 真车抓包确认）。
        // 只被动等 0xAA01 会永远卡住（DiPlay 实测）。
        setState(State.AUTHENTICATING);
        // 新一轮认证：挑战梯与在途标记全部归零（重复 0x1D01 重进这里时不能从上一档续跑）
        challengeIndex = -1;
        challengeStep = -1;
        challengeSent = false;
        authCertRetries = 0;
        engine.sendCsm(Iap2Ids.AUTH_INFORMATION, new byte[0]);
        log("发送 RequestAuthCertificate (0xAA00)");
        // ⚠️ 0xAA01 等待超时：以前这里没有任何超时 —— 车机不回证书就永远卡在
        //    AUTHENTICATING（15s 看门狗只判 IDENTIFYING，stall 看门狗只打日志）。
        //    超时先重发一次 0xAA00；再超时就明确失败，别让界面假装在忙。
        scheduleAuthCertWatchdog();
    }

    /** 0xAA01（认证证书）等待看门狗：5s 未到先重发一次，再 5s 未到判失败。 */
    private void scheduleAuthCertWatchdog() {
        try {
            authTimer.schedule(new Runnable() {
                @Override public void run() {
                    if (state != State.AUTHENTICATING || challengeSent) return;   // 证书已到/已推进
                    if (closing) return;
                    if (authCertRetries < 1) {
                        authCertRetries++;
                        log("⚠ 0xAA00 发出 5s 没收到 0xAA01（认证证书）→ 重发一次请求");
                        engine.sendCsm(Iap2Ids.AUTH_INFORMATION, new byte[0]);
                        scheduleAuthCertWatchdog();
                    } else {
                        log("⚠ 两次 0xAA00 都没回证书 —— 车机没在做 MFi 认证（或消息 ID 不对）");
                        listener.onLog("车机没回认证证书（0xAA01）：确认车机 CarPlay 已启用");
                        setState(State.ERROR);
                    }
                }
            }, 5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable tDiag) {
            Diag.w("Iap2SenderSession.authCertWatchdog", tDiag);
        }
    }

        /**
     * 车机反过来要求手机自报家门（0x1D00）。
     */
    private void onStartIdentification() {
        engine.sendCsm(Iap2Ids.IDENTIFICATION_INFORMATION, buildOurIdentification());
        log("车机要求我方识别信息 → 回 0x1D01（name=iPhone model=iPhone19,4 serial="
                + identificationSerial() + "）");
    }

    /** 手机侧 0x1D01：iPhone 身份 + **transport 组件表**（真 iPhone 一定带，我们以前只发身份串）。
     *
     * <p>盒子固件（audit9/dec/ 反汇编，audit10/B）实证：iPhone 角色的 IdentifyInfo 里
     * 并列三条 transport 组件（USBHost id=0 / Bluetooth id=1 / Wireless id=2），
     * 每条带「标识/名字/supports-iAP2/MAC」四个子项；「支持无线 CarPlay」不是单独布尔，
     * 而是 **wireless 组存在与否** 表达的。车机侧解析组件表决定 0x5702/0x5703 与 0x4301 的时序，
     * 我们以前完全不发组件 → 车机只能靠猜。
     */
    private byte[] buildOurIdentification() {
        return Iap2Csm.concat(
                Iap2Csm.paramString(Iap2Ids.Id.NAME, "iPhone"),
                Iap2Csm.paramString(Iap2Ids.Id.MODEL_IDENTIFIER, "iPhone19,4"),
                Iap2Csm.paramString(Iap2Ids.Id.MANUFACTURER, "Apple Inc."),
                Iap2Csm.paramString(Iap2Ids.Id.SERIAL_NUMBER, identificationSerial()),
                Iap2Csm.paramString(Iap2Ids.Id.FIRMWARE_VERSION, "27.1"),
                Iap2Csm.paramString(Iap2Ids.Id.HARDWARE_VERSION, "iPhone19,4"),
                Iap2Csm.paramString(Iap2Ids.Id.LANGUAGE, "zh-CN"),
                Iap2Csm.paramString(Iap2Ids.Id.LANGUAGE_LIST, "zh-CN"),
                // 供电能力：手机不向车机供电（0=无）
                Iap2Csm.paramUInt(8, 0, 1),
                // 蓝牙 transport 组件（id=1）：名字 + supports iAP2 + 本机真实蓝牙 MAC。
                // MAC 必须与 hci0 一致 —— 车机的重连/去重按它索引。
                Iap2Csm.group(Iap2Ids.Id.BLUETOOTH_TRANSPORT_COMPONENT,
                        Iap2Csm.paramUInt(0, 1, 2),                       // transportComponentIdentifier
                        Iap2Csm.paramString(1, "IAP2-Bluetooth"),         // transportComponentName
                        Iap2Csm.u8(2, 1),                                 // transportSupportsiAP2Connection
                        Iap2Csm.paramString(3, deviceMac == null ? "" : deviceMac)),
                // 无线 CarPlay transport 组件（id=2）：**它的存在就是「支持无线」的声明**
                Iap2Csm.group(Iap2Ids.Id.WIRELESS_CARPLAY_COMPONENT,
                        Iap2Csm.paramUInt(0, 2, 2),
                        Iap2Csm.paramString(1, "IAP2-Wireless"),
                        Iap2Csm.u8(2, 1),                                 // transportSupportsiAP2Connection
                        Iap2Csm.u8(3, 1)));                               // transportSupportsCarPlay
    }

    /**
     * 稳定序列号：从真实蓝牙 MAC 派生，车机重连/重配后不变。
     * Apple 序列号通常 12 位；这里用 F2L + MAC 后 9 位十六进制。
     */
    private String identificationSerial() {
        String mac = deviceMac == null ? "" : deviceMac.replace(":", "").replace("-", "");
        if (mac.length() >= 9) {
            return "F2L" + mac.substring(mac.length() - 9).toUpperCase(Locale.US);
        }
        return "F2L000000000";
    }

    // ---------------------------------------------------------------- MFi

    private void onAuthCertificate(Iap2Csm.Message m) {
        setState(State.AUTHENTICATING);
        // 解析证书，提取 RSA 公钥（严格模式验签用）
        if ((peerRsaKey == null && peerEcKey == null) && !m.params().isEmpty()) {
            Iap2Csm.Param cert = m.params().get(0);
            peerRsaKey = MfiCrypto.parseCertificate(cert.value);
            if (peerRsaKey == null) {
                // 很多车机（DiPlay AuthV3、Apple 近年 MFi）用 ECC，以前这里只认 RSA，
                // 于是永远「未解析出公钥」→ 严格模式根本没法验签。
                peerEcKey = MfiCrypto.parseEcPublicKey(cert.value);
            }
            peerCertApple = MfiCrypto.looksAppleIssued(cert.value);
            long[] valid = MfiCrypto.certValidity(cert.value);
            if (valid != null) {
                long now = System.currentTimeMillis();
                boolean expired = now > valid[1];
                boolean notYet = now < valid[0];
                log("证书有效期：" + fmtTime(valid[0]) + " ~ " + fmtTime(valid[1])
                        + (expired ? " · 已过期" : notYet ? " · 尚未生效" : " · 未过期"));
                if ((expired || notYet) && verifyMfiSignature) {
                    log("严格模式：证书" + (expired ? "已过期" : "尚未生效") + "，回 0xAA04");
                    engine.sendCsm(Iap2Ids.AUTH_FAILED, new byte[0]);
                    setState(State.ERROR);
                    return;
                }
            } else {
                log("证书里没解析出有效期（裸公钥/占位证书的可能性大）");
            }
            // ⚠️ 写清楚「验了什么、没验什么」：这里只提取公钥 + 形状/有效期探针，
            //    **不验证书签发链**（没有 Apple 根证书可钉）。以前日志打「Apple OID 体系 ·
            //    有效」容易让人以为链已验过 —— 实际只是提示，不是信任锚。
            log("收到 MFi 证书 " + cert.value.length + "B → "
                    + (peerRsaKey != null ? peerRsaKey.toString()
                       : peerEcKey != null ? ("EC-" + peerEcKey.getAlgorithm())
                       : "未解析出公钥")
                    + (peerCertApple ? " · 含 Apple OID 弧（仅提示，未验签发链）"
                                     : " · 非 Apple OID（自制/占位证书）"));
        }
        // 认证已完成（challengeSent 已复位）后车机重发 0xAA01 时不能再发挑战；
        // 只有还在认证中才允许发起/续发挑战。
        if (!challengeSent && state == State.AUTHENTICATING) {
            sendNextChallenge();
        }
    }

    /**
     * 发一个 MFi 挑战。**没有 universally 安全的首发长度**（DiPlay 只收 32B、奥迪只收 20B，
     * 见 CHALLENGE_LENGTHS 上的注释），靠档位回退 + 被拒档位进程级记忆收敛。
     *
     * <p>synchronized：onAuthCertificate（读线程）与 retryChallenge（定时器线程）会并发
     * 到达，非同步的「if (!challengeSent) → 发」会双发挑战，验签必失败。
     */
    private synchronized void sendNextChallenge() {
        // EC 证书（AuthV3 裸签）只吃 32B 挑战：优先 32，再退 20/16。
        // RSA 老车机走 20 起步的历史顺序不动（DiPlay 实测 20B 是它的可用档）。
        // challengeStep = 梯子上的第几步；challengeIndex = 实际档位下标（超时/拒绝
        // 判定仍按档位下标走，两者不能混用）。
        int[] order = peerEcKey != null
                ? new int[]{1, 0, 2}
                : new int[]{0, 1, 2};
        int step = challengeStep + 1;
        while (step < order.length && REJECTED_CHALLENGE_LEVELS.contains(order[step])) step++;
        if (step >= order.length) {
            log("MFi 挑战长度全部被拒，放弃认证");
            // ⚠️ 以前这里只是 return：状态永远停在 AUTHENTICATING，
            //    上层既看不到失败、也不会重试 → 界面像卡死。
            //    现在给一个明确的终态。
            setState(State.ERROR);
            listener.onLog("MFi 认证失败：车机把我们所有长度的挑战都拒了（32/20/16B）");
            return;
        }
        int idx = order[step];
        challengeStep = step;
        challengeIndex = idx;
        byte[] challenge = new byte[CHALLENGE_LENGTHS[idx]];
        new java.security.SecureRandom().nextBytes(challenge);   // 真 iPhone 用随机挑战
        lastChallenge = challenge;
        engine.sendCsm(Iap2Ids.AUTH_CHALLENGE_RESPONSE, Iap2Csm.param(0, challenge));
        challengeSent = true;
        challengeSentAtMs = System.currentTimeMillis();
        final int token = ++challengeToken;
        // 内建超时：到点还停在同一个 token 就自己回退下一档（上层 watchdog 没调也不会卡死）
        try {
            authTimer.schedule(new Runnable() {
                @Override public void run() {
                    if (challengeSent && token == challengeToken) retryChallenge();
                }
            }, CHALLENGE_TIMEOUT_MS[idx], java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable tDiag) {
            Diag.w("Iap2SenderSession.sendNextChallenge", tDiag);
        }
        log("发送 MFi 挑战 " + challenge.length + "B（等待最多 " + CHALLENGE_TIMEOUT_MS[idx] + "ms）");
    }

    /**
     * 看门狗调用：超时没收到 0xAA03 就换下一个长度档位重发。
     * 必须由外部驱动（协议核心里没有定时器），用 {@link #currentChallengeTimeoutMs()} 定时。
     */
    public synchronized void retryChallenge() {
        if (!challengeSent || challengeIndex < 0) return;
        log("MFi 挑战 " + CHALLENGE_LENGTHS[challengeIndex] + "B 超时，回退下一档");
        challengeSent = false;
        sendNextChallenge();
    }

    private void onAuthResponse(Iap2Csm.Message m) {
        // ⚠️ 状态闸：0xAA03 只在「认证中且挑战在途」时有意义。
        //    以前不看状态：车机重发同一 0xAA03 时，严格模式判重放 → 直接 0xAA04+ERROR 拆链；
        //    宽松模式把已 READY 的会话倒退成 REQUESTING_WIFI 并重发 0x5702 ——
        //    一个可忽略的重复消息把整轮认证/寻找端点的进度全推翻。
        if (state != State.AUTHENTICATING || !challengeSent) {
            log("忽略 0xAA03：当前不在等待挑战响应（state=" + state
                    + " challengeSent=" + challengeSent + "）");
            return;
        }
        boolean accepted = true;
        Iap2Csm.Param sig0 = m.params().isEmpty() ? null : m.params().get(0);
        if (sig0 != null) {
            // 重放检测：同一个挑战收到一份一模一样的签名 = 车机在回放
            boolean replay = lastResponseSig != null && lastResponseFor == lastChallenge
                    && java.util.Arrays.equals(sig0.value, lastResponseSig);
            if (replay) {
                log("⚠ 0xAA03 与上一份完全相同（挑战未变）→ 判定为重放");
                if (verifyMfiSignature) {
                    engine.sendCsm(Iap2Ids.AUTH_FAILED, new byte[0]);
                    setState(State.ERROR);
                    listener.onLog("MFi 认证失败：车机回放了旧签名");
                    return;
                }
            }
            lastResponseSig = sig0.value.clone();
            lastResponseFor = lastChallenge;
            // 形状：RSA 必须等于模长字节；ECDSA 必须是 DER SEQUENCE 或 64B raw
            boolean shapeOk = MfiCrypto.signatureShapeOk(sig0.value, peerRsaKey, peerEcKey);
            if (!shapeOk) {
                log("⚠ 签名形状不符（" + sig0.value.length + "B）—— 对面可能没按 MFi 签");
            }
        }
        if (verifyMfiSignature) {
            // ⚠️ 严格模式必须两种身份都验：以前这里硬要求 peerRsaKey != null，
            //    EC 证书（DiPlay AuthV3、Apple 近年 MFi）的车机会被**一律误拒**——
            //    宽松模式能通的机器，开严格模式反而拒绝，语义完全反了。
            Iap2Csm.Param sig = m.params().isEmpty() ? null : m.params().get(0);
            boolean ok = false;
            String how = "无公钥";
            if (sig != null && lastChallenge != null) {
                if (peerRsaKey != null) {
                    ok = MfiCrypto.verifySignature(lastChallenge, sig.value, peerRsaKey);
                    how = peerRsaKey.toString();
                } else if (peerEcKey != null) {
                    ok = MfiCrypto.verifyEcSignature(lastChallenge, sig.value, peerEcKey);
                    how = "EC-" + peerEcKey.getAlgorithm();
                }
            }
            accepted = ok;
            log("MFi 验签：" + (ok ? "通过" : "失败")
                    + "（签名 " + (sig == null ? "?" : sig.value.length) + "B，"
                    + how + "）");
        } else {
            // 宽松模式也要「探测性验签」：结果只进日志，不影响放行。
            // 现场靠这一行就能区分「车机真的签了」和「随便回了个包」。
            Iap2Csm.Param sig = m.params().isEmpty() ? null : m.params().get(0);
            boolean probe = false;
            String how = "无公钥";
            if (sig != null && lastChallenge != null) {
                if (peerRsaKey != null) {
                    probe = MfiCrypto.verifySignature(lastChallenge, sig.value, peerRsaKey);
                    how = peerRsaKey.toString();
                } else if (peerEcKey != null) {
                    probe = MfiCrypto.verifyEcSignature(lastChallenge, sig.value, peerEcKey);
                    how = "EC";
                }
            }
            log("收到 MFi AuthResponse（签名 " + (sig == null ? "?" : sig.value.length) + "B）"
                    + " · 探测验签=" + (probe ? "通过" : "不通过") + "（" + how + "）"
                    + " · 宽松模式仍然放行");
        }
        if (!accepted) {
            engine.sendCsm(Iap2Ids.AUTH_FAILED, new byte[0]);   // 0xAA04
            log("MFi 认证被拒绝（验签失败），回 0xAA04");
            setState(State.ERROR);
            listener.onLog("MFi 认证失败：车机签名验签不过");
            return;
        }
        engine.sendCsm(Iap2Ids.AUTH_SUCCEEDED, new byte[0]);
        challengeSent = false;
        // 认证成功 = 挑战档位是对的：把「被拒档位」进程级记忆清空，
        // 避免一次历史瞬断把唯一能用的档位永久拉黑（下次会话重新从首发档开始）。
        if (!REJECTED_CHALLENGE_LEVELS.isEmpty()) {
            log("认证成功 → 清空被拒挑战档位记忆 " + REJECTED_CHALLENGE_LEVELS);
            REJECTED_CHALLENGE_LEVELS.clear();
        }
        // 真 iPhone 在 0xAA05 之后约 0.3s 推一串「设备状态问候」——
        // 车机会等这批消息才往下走，晚了它就不认我们了。
        sendDeviceGreeting();
        setState(State.REQUESTING_WIFI);
        engine.sendCsm(Iap2Ids.REQUEST_ACCESSORY_WIFI_CONFIGURATION, new byte[0]);
        log("发送 RequestAccessoryWiFiConfiguration (0x5702)");
        // 认证前早到的 0x5703：认证已通过，补处理（凭据不丢）
        WifiConfig early = earlyWifiConfig;
        earlyWifiConfig = null;
        if (early != null) {
            log("补处理认证前早到的 0x5703：" + early);
            applyWifiConfig(early);
        }
        // 认证后看门狗：真车机拿到 0xAA05 会马上给 0x5703 或（新势力形态）直接回 0x4301。
        // 两边都没有说明它在认证后反悔了 —— 以前这种会话会静默停住，看不出原因。
        try {
            authTimer.schedule(new Runnable() {
                @Override public void run() {
                    if (!wifiConfigSeen && !endpointSeen) {
                        log("⚠ 认证后 12s 内既没收到 0x5703 也没收到 0x4301 —— 车机在认证后停了");
                        listener.onLog("认证已通过，但车机既没给热点凭据也没发起 CarPlay（可能被车机侧策略拦下）");
                    }
                }
            }, 12, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable tDiag) {
            Diag.w("Iap2SenderSession.onAuthResponse", tDiag);
        }

        // 正向能力判据：车机声明「能收 0x4300、会发 0x4301」，且完全不参与 0x57xx、
        // 也不声明任何 EA 消息 —— 这种车机不会回 0x5703，等超时兜底时 CarPlay 窗口
        // 往往已经关了（mcarplay 在 BMW iD9 / 上汽 AH4EM 形态上实测：按「清单没声明
        // 就跳过」的那一版 4 轮会话一次都没真正发出 0x4300）。
        // 只影响时序：0x4300 本来每轮必发，这里只是提前到凭据窗口内，且仍受「至多一次」闸门。
        boolean earlyCarPlayAvailability =
                accessoryAccepts.contains(Iap2Ids.CARPLAY_AVAILABILITY)
                        && accessorySends.contains(Iap2Ids.CARPLAY_START_SESSION)
                        && !accessoryAccepts.contains(Iap2Ids.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
                        && !accessorySends.contains(Iap2Ids.ACCESSORY_WIFI_CONFIGURATION)
                        && !declaresAnyEaMessage();
        if (earlyCarPlayAvailability) {
            log("车机画像：只收 0x4300 / 只发 0x4301、不参与 0x57xx → 立即发 0x4300，不等 0x5703");
            sendCarPlayAvailability();
        }
    }

    // ---------------------------------------------------------------- WiFi 凭据

    private void onWifiConfiguration(Iap2Csm.Message m) {
        wifiConfigSeen = true;
        List<Iap2Csm.Param> params = m.params();
        // 自适应解析：不写死某台车，按「多布局探测 + 合理性校验」择优（真 iPhone 策略）。
        WifiConfig cfg = parseWifi(params);
        if (cfg.ssid == null || cfg.ssid.isEmpty()) {
            log("0x5703 未解析出 SSID（参数 " + params.size() + " 项，paramId "
                    + describeIds(params) + "），AirPlay 端点改用 mDNS 发现");
        }
        log("收到 0x5703：" + cfg);
        if (state == State.IDENTIFYING || state == State.AUTHENTICATING
                || state == State.IDLE) {
            // 认证完成前到达的凭据（车机正常不该这么早发）：缓存，认证成功后再处理。
            // 以前直接往下走：状态被提前推进到 STARTING_SESSION、0x4300 提前发，
            // 认证成功后又发一遍 —— 车机侧看到重复请求 + 状态倒退。
            log("0x5703 在认证完成前到达 → 缓存凭据，认证成功后处理");
            earlyWifiConfig = cfg;
            return;
        }
        applyWifiConfig(cfg);
    }

    /** 0x5703 凭据的落地处理（认证后的正常路径与「早到缓存」共用）。 */
    private void applyWifiConfig(WifiConfig cfg) {
        listener.onWifiConfig(cfg);
        if (state == State.READY) {
            // 没主动再要过凭据时晚到的重复 0x5703（重请求应答等）：只更新凭据，
            // 不把 READY 打回 STARTING_SESSION、也不重发 0x4300（上层在恢复期会重复入网）。
            log("已在 READY，晚到的 0x5703 只更新凭据（不动状态、不重发 0x4300）");
            return;
        }
        // 新一轮凭据（首轮或恢复期的主动重要）：0x4300 闸门按「每轮凭据一次」重置，
        // 否则恢复期永远发不出第二条 0x4300、车机不会回 0x4301。
        carPlayAvailabilitySent = false;
        setState(State.STARTING_SESSION);
        sendCarPlayAvailability();
    }

    /** 0x5703 的自适应解析搬到了 {@link WifiConfigParser}（纯逻辑、有单测）。 */
    private static WifiConfig parseWifi(List<Iap2Csm.Param> params) {
        WifiConfigParser.Cfg c = WifiConfigParser.parse(params);
        return new WifiConfig(c.ssid, c.password, c.channel, c.security);
    }

    // ---------------------------------------------------------------- 会话端点

    private void onCarPlayStartSession(Iap2Csm.Message m) {
        // 认证完成前的端点（0x4301）没有意义：车机正常在认证后才发。忽略并留痕。
        if (state == State.IDENTIFYING || state == State.AUTHENTICATING
                || state == State.IDLE) {
            log("忽略 0x4301：认证尚未完成（state=" + state + "）");
            return;
        }
        endpointSeen = true;
        List<Iap2Csm.Param> params = m.params();
        log("收到 CarPlayStartSession (0x4301)，" + params.size() + " 个参数");
        // 先把每个参数原样打出来，便于对照真实布局（不同车机不一致）
        for (Iap2Csm.Param p : params) {
            log("  0x4301 param id=" + p.paramId + " len=" + p.value.length
                    + " hex=" + hex(p.value, 24));
        }
        // 自适应解析 0x4301：多布局探测 + 校验（host 是 IPv4、port 1..65535）。
        // 真 iPhone 不写死一种布局，而是逐个尝试：
        //   布局1（CarDeck/车机）：param1=无线组(子参数3=IP 列表)，param2=端口u32，param3=设备ID
        //   布局2（旧占位）：      param0=IP，param1=端口，param2=设备ID
        String host = null;
        int port = 0;
        String deviceId = "";

        // 布局 1：无线组（param1）→ 子参数：0=SSID、1=passphrase、2=channel、3=IP 列表、4=securityType
        Iap2Csm.Param wireless = Iap2Csm.find(params, 1);
        String wSsid = null, wPass = null;
        int wChannel = 0, wSecurity = 0;
        if (wireless != null) {
            for (Iap2Csm.Param sp : wireless.asNested()) {
                switch (sp.paramId) {
                    case 0:
                        wSsid = sp.asString();
                        break;
                    case 1:
                        wPass = sp.asString();
                        break;
                    case 2:
                        wChannel = (int) sp.asUInt();
                        break;
                    case 3: {
                        String ip = sp.asString();
                        if (isIpv4(ip) && host == null) host = ip;
                        break;
                    }
                    case 4:
                        wSecurity = (int) sp.asUInt();
                        break;
                    default:
                        break;
                }
            }
            Iap2Csm.Param pp = Iap2Csm.find(params, 2);
            if (pp != null) port = (int) pp.asUInt();
            Iap2Csm.Param dp = Iap2Csm.find(params, 3);
            if (dp != null) deviceId = dp.asString();
        }
        // ⚠️ 只答 0x4301、不答 0x5703 的车机：AP 凭据就在无线组里。
        //    以前这里只取 IP，SSID/密码全丢 → WifiJoiner 见空 SSID 直接返回，
        //    车机发完 0x4300 就再也走不下去（日志却显示已拿到端点）。
        if (!wifiConfigSeen && wSsid != null && !wSsid.isEmpty()) {
            log("0x4301 无线组带 AP 凭据（SSID=" + wSsid + " ch=" + wChannel
                    + " sec=" + wSecurity + "）→ 交给入网");
            listener.onWifiConfig(new WifiConfig(wSsid, wPass == null ? "" : wPass,
                    wChannel, wSecurity));
        }

        // 布局 2：param0=IP、param1=端口、param2=设备ID（某些车机的扁平布局）
        if (host == null || port <= 0) {
            Iap2Csm.Param h0 = Iap2Csm.find(params, 0);
            Iap2Csm.Param p1 = Iap2Csm.find(params, 1);
            Iap2Csm.Param d2 = Iap2Csm.find(params, 2);
            if (h0 != null && isIpv4(h0.asString())) {
                host = h0.asString();
                if (p1 != null) port = (int) p1.asUInt();
                if (d2 != null) deviceId = d2.asString();
            }
        }

        if (host != null && !host.isEmpty() && port > 0) {
            Endpoint e = new Endpoint(host, port, deviceId);
            log("CarPlay 端点：" + e);
            setState(State.READY);
            listener.onCarPlayEndpoint(e);
        } else {
            log("0x4301 未携带可解析端点（host=" + host + " port=" + port
                    + "），AirPlay 端点改用 mDNS `_airplay._tcp` 发现");
        }
        // 真机顺序：0x4301 之后回 0x4E0E（传输标识 = 真实蓝牙 MAC）。
        // 车机会校验这个标识；也用它和 mDNS 的 _carplay-ctrl TXT id 对上。
        engine.sendCsm(Iap2Ids.DEVICE_TRANSPORT_IDENTIFIER, Iap2Csm.paramString(0, deviceMac));
        log("发送 DeviceTransportIdentifier (0x4E0E) = " + deviceMac);
    }

    private static String hex(byte[] b, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(b.length, max); i++) {
            sb.append(String.format("%02X ", b[i]));
        }
        if (b.length > max) sb.append("…");
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- 内部

    private void setState(State s) {
        if (state == s) return;
        // 关闭是终态：链路已断（RST/蓝牙掉线）之后，别再被后续消息推回 READY。
        if (state == State.CLOSED && s != State.CLOSED) return;
        state = s;
        listener.onState(s);
    }

    /** 证书有效期用：epoch ms → yyyy-MM-dd HH:mm（UTC）。 */
    private static String fmtTime(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms)) + "Z";
    }

    private void log(String msg) {
        listener.onLog(msg);
    }

    /** 是否像 IPv4 地址（形如 1.2.3.4）。 */
    private static boolean isIpv4(String s) {
        if (s == null || s.isEmpty() || s.contains(":")) return false;
        String[] parts = s.split("\\.");
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty()) return false;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c < '0' || c > '9') return false;
            }
            try {
                int v = Integer.parseInt(p);
                if (v < 0 || v > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static String describeIds(List<Iap2Csm.Param> params) {
        StringBuilder sb = new StringBuilder();
        for (Iap2Csm.Param p : params) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p.paramId).append('(').append(p.value.length).append("B)");
        }
        return sb.toString();
    }

    /** 把车机的 want-list（u16[] 消息 ID）打成可读字符串。 */
    private static String describeMessageIds(Iap2Csm.Param p) {
        StringBuilder sb = new StringBuilder();
        byte[] v = p.value;
        for (int i = 0; i + 1 < v.length; i += 2) {
            int id = ((v[i] & 0xFF) << 8) | (v[i + 1] & 0xFF);
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("0x%04X", id));
        }
        return sb.toString();
    }

    /** 把 MFi 挑战档位标记为被拒（对端拆链时调用），下次直接跳过。 */
    public void rejectCurrentChallengeLevel() {
        int i = challengeIndex;
        if (i < 0 || i >= CHALLENGE_LENGTHS.length) return;   // 还没发过挑战，没什么可记
        REJECTED_CHALLENGE_LEVELS.add(i);
        log("标记 MFi 挑战档位 " + CHALLENGE_LENGTHS[i] + "B 被拒（进程级记忆），后续会话跳过");
    }

    /** 暴露：每个档位的超时，供驱动线程做回退。 */
    public int currentChallengeTimeoutMs() {
        if (challengeIndex < 0 || challengeIndex >= CHALLENGE_TIMEOUT_MS.length) return 0;
        return CHALLENGE_TIMEOUT_MS[challengeIndex];
    }
}
