package com.carbridge.fakecar;

import com.carbridge.fakecar.iap2.Iap2Csm;
import com.carbridge.fakecar.iap2.Iap2LinkPacket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * 便携假车机的 <b>iAP2 配件端会话</b>（1:1 复刻 s
 * trict_fakecar.py 的 Accessory 类）。
 */
public final class Iap2AccessorySession {

    /** 日志出口。 */
    public interface Log {
        void onLog(String line);
    }

    /** 关键阶段回调（用于驱动 UI 状态）。 */
    public interface Phase {
        void onPhase(String name);
    }

    /** 假车机配置（0x5703 / 0x4301 / ctrl-int 邀请都用它）。 */
    public static final class Config {
        public String carIp = "192.168.1.1";      // 0x4301 里报给手机的 AirPlay 端点 IP
        public int apPort = 7010;                 // AirPlay 控制端口
        public String deviceId = "00:00:00:00:00:00"; // 0x4301 设备 ID
        public String ssid = "@NRadio-4B02-5G";   // 0x5703 SSID
        public String wifiPass = "sim0001234";    // 0x5703 密码
        public int wifiSecurity = 2;              // 0x5703 安全类型（2=WPA2）
        public int wifiChannel = 6;               // 0x5703 信道
        public String phoneIp = "192.168.1.2";    // ctrl-int 邀请要反连的手机 IP
        public int ctrlIntPort = 7100;            // 手机 CtrlIntServer 端口

        // —— 以下五项决定「假车机像不像你那台车」，都可以用 carprofile.json 覆盖 ——
        public String model  = "HeadUnit";        // 0x1D01 param1（车机型号，真车例：Desay SV）
        public String serial = "0000000000000";   // 0x1D01 param3
        public String maker  = "VENDOR";          // 0x1D01 param2
        public String fw     = "1.1.0";           // 0x1D01 param4
        /** 车机能收哪些手机消息（0x1D01 param7）。真车抓包里照抄。 */
        public int[] accRecv = {0x1D00, 0x1D01, 0x1D02, 0xAA00, 0xAA02, 0xAA05, 0x5702, 0x4300,
                0x4E0C, 0x4E09, 0x4E0A, 0x4E0B, 0x4E0D, 0x4E0E, 0xFFFA, 0x4155, 0x4E04};
        /** 车机会发哪些消息（0x1D01 param6）。 */
        /** MFi 挑战长度（真车方向：车机发挑战、手机回签名）。Chery 记录是 32，本车未实测。 */
        public int mfiChallengeLen = 32;
        public int[] accSend = {0x1D00, 0x1D01, 0xAA01, 0xAA03, 0x5703, 0x4301, 0xAE00, 0x5000,
                0x4C00, 0x4C03, 0x4E03, 0x4154, 0x6800};

        /** 用 carprofile.json 覆盖（字段缺哪个就用默认，不要求全填）。 */
        public void applyJson(org.json.JSONObject o) {
            if (o == null) return;
            model  = o.optString("model",  model);
            serial = o.optString("serial", serial);
            maker  = o.optString("maker",  maker);
            fw     = o.optString("fw",     fw);
            mfiChallengeLen = o.optInt("mfiChallengeLen", mfiChallengeLen);
            deviceId = o.optString("deviceId", deviceId);
            carIp    = o.optString("carIp",    carIp);
            apPort   = o.optInt("apPort",   apPort);
            ssid     = o.optString("ssid",     ssid);
            wifiPass = o.optString("wifiPass", wifiPass);
            org.json.JSONArray recv = o.optJSONArray("accRecv");
            if (recv != null && recv.length() > 0) {
                int[] a = new int[recv.length()];
                for (int i = 0; i < a.length; i++) a[i] = recv.optInt(i);
                accRecv = a;
            }
            org.json.JSONArray send = o.optJSONArray("accSend");
            if (send != null && send.length() > 0) {
                int[] a = new int[send.length()];
                for (int i = 0; i < a.length; i++) a[i] = send.optInt(i);
                accSend = a;
            }
        }
    }

    // ------------------------------------------------------------------ 常量
    private static final int CTRL_SESSION = 1;
    private static final byte[] DETECT =
            {(byte) 0xFF, 0x55, 0x02, 0x00, (byte) 0xEE, 0x10};

    // CSM 消息 ID
    private static final int MSG_START_IDENT = 0x1D00;
    private static final int MSG_IDENT_INFO = 0x1D01;
    private static final int MSG_REQ_AUTH_CERT = 0xAA00;
    private static final int MSG_AUTH_CERT = 0xAA01;
    private static final int MSG_AUTH_CHALLENGE = 0xAA02;
    private static final int MSG_AUTH_RESPONSE = 0xAA03;
    /** MFi 用的 RSA-1024 密钥对：懒加载，整个进程复用（每轮重连都换会让人以为是不同设备）。 */
    private static java.security.KeyPair RSA_KEYS;

    private static synchronized java.security.KeyPair rsa() {
        if (RSA_KEYS == null) {
            try {
                java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA");
                g.initialize(1024);
                RSA_KEYS = g.generateKeyPair();
            } catch (Exception e) {
                throw new IllegalStateException("RSA 密钥生成失败", e);
            }
        }
        return RSA_KEYS;
    }
    private static final int MSG_AUTH_SUCCEEDED = 0xAA05;
    private static final int MSG_REQ_WIFI_CFG = 0x5702;
    private static final int MSG_ACC_WIFI_CFG = 0x5703;
    private static final int MSG_CARPLAY_AVAIL = 0x4300;
    private static final int MSG_CARPLAY_START = 0x4301;

    private static final String I2ID_TABLE = buildIdTable();

    private static String buildIdTable() {
        return "0x1D00=StartIdentification,0x1D01=IdentificationInformation,"
                + "0x1D02=IdentificationAccepted,0xAA00=ReqAuthCert,0xAA01=AuthCertificate,"
                + "0xAA02=Challenge,0xAA03=AuthResponse,0xAA04=AuthFailed,0xAA05=AuthSucceeded,"
                + "0x5702=ReqAccWifiCfg,0x5703=AccWifiCfg,0x4300=CarPlayAvailability,"
                + "0x4301=CarPlayStartSession,0x4E0C=DeviceUUIDUpdate,0x4E09=DeviceInfoUpdate,"
                + "0x4E0A=DeviceLanguageUpdate,0x4E0B=DeviceTimeUpdate,0x4E0D=WirelessCarPlayUpdate,"
                + "0x4E0E=DeviceTransportIdentifier,0xFFFA=StartLocationInfo";
    }

    private final InputStream in;
    private final OutputStream out;
    private final Log log;
    private final Phase phase;
    private final Config cfg;

    private volatile boolean running;

    /** 会话是否还活着（用于判断「上一轮是不是已经死了，可以重来」）。 */
    public boolean isAlive() {
        return running;
    }
    private Thread readThread;
    /** 会话结束（对端断开/读失败）时回调；用于自动重连。 */
    private volatile Runnable onClosed;

    // 链路层序号（对齐 Python Accessory）
    private int synSeq = 0;
    private int nextSeq = 1;
    private int lastSent = 0;

    // CSM 分帧（持久缓冲，处理跨链路帧拆分的消息）
    private final Iap2Csm.Parser csmParser = new Iap2Csm.Parser();

    // 状态（防重复）
    private boolean identified;
    private boolean mfiChallengeSent;
    private boolean certSent;
    private boolean sent5703;
    private boolean sent4301;

    public Iap2AccessorySession(InputStream in, OutputStream out, Config cfg, Log log, Phase phase) {
        this.in = in;
        this.out = out;
        this.cfg = cfg == null ? new Config() : cfg;
        this.log = log;
        this.phase = phase;
    }

    /** 会话结束回调（对端断开/读失败时触发，用于自动重连）。 */
    public void setOnClosed(Runnable onClosed) {
        this.onClosed = onClosed;
    }

    /** 开始会话：先发 DETECT，再起读循环。 */
    public void start() {
        running = true;
        tx(DETECT, "DETECT");
        readThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "fakecar-iap2");
        readThread.setDaemon(true);
        readThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (readThread != null) readThread.interrupt();
        } catch (Throwable ignored) {
        }
    }

    // ================================================================== 发送

    private synchronized void tx(byte[] bytes, String label) {
        try {
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            emit("!! 发送失败 " + label + ": " + e);
        }
        if (label != null) emit("> " + label);
    }

    private void txFrame(int control, int seq, int ack, int session, byte[] payload, String label) {
        byte[] frame = new Iap2LinkPacket(control, seq, ack, session, payload).encode();
        tx(frame, label);
    }

    /**
     * 发一条 CSM（分配 seq 并发送）。
     *
     * <p>⚠️ 读循环线程（回 0x5703 等）和 ctrl-int 线程（回 0x4301）会并发调用，
     * {@code nextSeq/lastSent} 的「分配 + 递增」必须原子，否则两个帧拿到相同 seq，
     * 手机端链路层 ACK 会乱。
     */
    private synchronized void txCsm(int mid, byte[] body, String label) {
        byte[] csm = Iap2Csm.frame(mid, body);
        txFrame(Iap2LinkPacket.CONTROL_ACK, nextSeq, 0, CTRL_SESSION, csm,
                "frame seq=" + nextSeq + " sess=" + CTRL_SESSION + " " + label);
        lastSent = nextSeq;
        nextSeq = (nextSeq + 1) & 0xFF;
    }

    // ================================================================== 读循环

    private void readLoop() {
        byte[] tmp = new byte[8192];
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        // 区分「对端断开」（running 仍为 true）与「本端 stop()」（running 已置 false）
        boolean closedByPeer = false;
        while (running) {
            int n;
            try {
                n = in.read(tmp);
            } catch (IOException e) {
                emit("读循环结束: " + e);
                closedByPeer = running;
                break;
            }
            if (n < 0) {
                emit("对端关闭 RFCOMM");
                closedByPeer = running;
                break;
            }
            buf.write(tmp, 0, n);
            buf = drain(buf);
            if (!running) break;
        }
        emit("iAP2 会话结束");
        if (closedByPeer) {
            Runnable cb = onClosed;
            if (cb != null) cb.run();
        }
    }

    /** 逐字节喂入帧解析；返回剩余未消费字节（紧凑化）。 */
    private ByteArrayOutputStream drain(ByteArrayOutputStream buf) {
        byte[] b = buf.toByteArray();
        int off = 0;
        while (off < b.length && running) {
            int b0 = b[off] & 0xFF;
            int b1 = off + 1 < b.length ? b[off + 1] & 0xFF : -1;
            // DETECT marker（对端也发）
            if (b0 == 0xFF && b1 == 0x55) {
                if (b.length - off < 6) break;   // 半截 DETECT，等下次
                if (matches(b, off, DETECT)) {
                    emit("< DETECT(peer)");
                    off += 6;
                    continue;
                }
                off++;
                continue;
            }
            // 数据帧
            if (b0 == 0xFF && b1 == 0x5A) {
                if (b.length - off < 4) break;
                int total = Iap2LinkPacket.getShortBE(b, off + 2);
                if (total < 9) {
                    off++;
                    continue;
                }
                if (b.length - off < total) break;
                byte[] frame = new byte[total];
                System.arraycopy(b, off, frame, 0, total);
                off += total;
                onFrame(frame);
                continue;
            }
            off++;
        }
        ByteArrayOutputStream rest = new ByteArrayOutputStream();
        if (off < b.length) rest.write(b, off, b.length - off);
        return rest;
    }

    private static boolean matches(byte[] b, int off, byte[] marker) {
        for (int i = 0; i < marker.length; i++) {
            if (b[off + i] != marker[i]) return false;
        }
        return true;
    }

    private void onFrame(byte[] f) {
        Iap2LinkPacket pkt;
        try {
            pkt = Iap2LinkPacket.decode(f);
        } catch (IllegalArgumentException e) {
            emit("!! 帧校验失败: " + e.getMessage());
            return;
        }
        int ctl = pkt.control;
        int seq = pkt.seq;
        int ack = pkt.ack;
        int sess = pkt.sessionId;
        byte[] payload = pkt.payload;

        StringBuilder bits = new StringBuilder();
        if ((ctl & 0x80) != 0) bits.append("SYN ");
        if ((ctl & 0x40) != 0) bits.append("ACK ");
        if ((ctl & 0x20) != 0) bits.append("EAK ");
        if ((ctl & 0x10) != 0) bits.append("RST ");
        if ((ctl & 0x08) != 0) bits.append("SLP ");
        emit("< " + (bits.length() == 0 ? "DATA" : bits.toString().trim())
                + " seq=" + seq + " ack=" + ack + " sess=" + sess + " payload=" + payload.length + "B");

        if ((ctl & 0x10) != 0) {   // RST
            emit("!! 对端发 RST，会话终止");
            stop();
            return;
        }
        boolean isSyn = (ctl & 0x80) != 0;
        boolean isAck = (ctl & 0x40) != 0;

        if (isSyn && !isAck) {
            // SYN：回 SYN|ACK + 链路协商
            Iap2LinkPacket.LinkConfig cfg2 = new Iap2LinkPacket.LinkConfig();
            cfg2.linkVersion = 1;
            cfg2.maxOutstandingPackets = 0x7F;
            cfg2.maxReceivePacketLength = 0x1000;
            cfg2.retransmitTimeoutMs = 500;
            cfg2.cumulativeAckTimeoutMs = 176;
            cfg2.maxRetransmissions = 30;
            cfg2.maxCumulativeAcks = 10;
            cfg2.sessions.add(new Iap2LinkPacket.SessionDescriptor(
                    CTRL_SESSION, Iap2LinkPacket.SessionDescriptor.TYPE_CONTROL, 2));
            cfg2.sessions.add(new Iap2LinkPacket.SessionDescriptor(
                    2, Iap2LinkPacket.SessionDescriptor.TYPE_EXTERNAL_ACCESSORY, 1));
            txFrame(Iap2LinkPacket.CONTROL_SYN | Iap2LinkPacket.CONTROL_ACK,
                    synSeq, seq, 0, cfg2.encode(), "SYN|ACK seq=" + synSeq + " ack=" + seq);
            lastSent = synSeq;
            synSeq = (synSeq + 1) & 0xFF;
            firePhase("iap2");
            return;
        }

        if (payload.length > 0) {
            // 数据帧：ACK + 累积 CSM
            txFrame(Iap2LinkPacket.CONTROL_ACK, lastSent, seq, 0, new byte[0],
                    "ACK seq=" + lastSent + " ack=" + seq);
            drainCsm(payload);
        }
    }

    // ================================================================== CSM

    private void drainCsm(byte[] payload) {
        for (Iap2Csm.Message m : csmParser.feed(payload)) {
            onCsm(m.messageId, m.params());
        }
    }

    private void onCsm(int mid, List<Iap2Csm.Param> ps) {
        String name = idName(mid);
        emit("< CSM 0x" + String.format("%04X", mid) + " " + name + " " + descParams(ps));
        switch (mid) {
            case MSG_START_IDENT:       // 手机要求配件自报身份
                sendAccessoryIdentification();
                txCsm(MSG_START_IDENT, new byte[0], "0x1D00 (要求手机自报身份)");
                break;
            case MSG_IDENT_INFO:        // 手机身份回复
                emit("  < 手机 0x1D01（身份回复）");
                break;
            case MSG_REQ_AUTH_CERT:
                if (certSent) break;
                certSent = true;
                // 发**真的** RSA-1024 公钥（SPKI DER），而不是 64 个 0x04 占位。
                // 手机侧会解析出公钥并做「签名形状/验签」检查；占位证书会让它报
                // 「签名形状不符」——测试环境里那条警告就是这么来的。
                try {
                    byte[] spki = rsa().getPublic().getEncoded();
                    txCsm(MSG_AUTH_CERT, Iap2Csm.param(0, spki),
                            "0xAA01 RSA-1024 公钥 " + spki.length + "B（可被解析）");
                } catch (Throwable t) {
                    emit("0xAA01 公钥发送失败：" + t);
                }
                // ⚠️ 真车（CHERY/Tiggo，mcarplay 艾瑞泽 8 记录）是**车机先发 32 字节挑战**，
                //    手机回 128B 签名，车机再回 0xAA05。以前这里是纯被动回应，方向反了。
                {
                    byte[] challenge = new byte[cfg.mfiChallengeLen];
                    new java.security.SecureRandom().nextBytes(challenge);
                    txCsm(MSG_AUTH_CHALLENGE, Iap2Csm.param(0, challenge),
                            "0xAA02 MFi 挑战 " + cfg.mfiChallengeLen + "B");
                    mfiChallengeSent = true;
                }
                break;
            case MSG_AUTH_RESPONSE: {
                int slen = ps.isEmpty() ? 0 : ps.get(0).value.length;
                emit("  < 手机 0xAA03 签名 " + slen + "B");
                txCsm(MSG_AUTH_SUCCEEDED, new byte[0], "0xAA05 MFi 通过");
                firePhase("mfi");
                break;
            }
            case MSG_AUTH_CHALLENGE: {
                byte[] challenge = ps.isEmpty() ? new byte[0] : ps.get(0).value;
                byte[] sig;
                try {
                    java.security.Signature sign = java.security.Signature.getInstance("SHA1withRSA");
                    sign.initSign(rsa().getPrivate());
                    sign.update(challenge);
                    sig = sign.sign();       // RSA-1024 → 128B，形状与内容都合规
                } catch (Throwable t) {
                    emit("0xAA03 签名失败：" + t);
                    sig = new byte[128];
                }
                txCsm(MSG_AUTH_RESPONSE, Iap2Csm.param(0, sig),
                        "0xAA03 真签名 " + sig.length + "B（挑战=" + challenge.length + "B）");
                break;
            }
            case MSG_AUTH_SUCCEEDED:
                firePhase("mfi");
                emit("✓ MFi 认证通过（0xAA05）");
                break;
            case MSG_REQ_WIFI_CFG:
                if (sent5703) break;
                sent5703 = true;
                sendWifiCfg();
                break;
            case MSG_CARPLAY_AVAIL:
                if (sent4301) break;
                sent4301 = true;
                firePhase("ctrl-int");
                startCarPlaySession();
                break;
            default:
                if (mid == 0x4E0D) {
                    firePhase("greeting");
                    emit("✓ 收到设备问候 0x4E0D（无线 CarPlay 支持）");
                }
                break;
        }
    }

    private void sendWifiCfg() {
        // 0x5703 布局（对齐 CarDeck/车机）：param1=SSID, param2=密码, param3=安全(u8), param4=信道(u8)
        byte[] body = Iap2Csm.concat(
                Iap2Csm.paramString(1, cfg.ssid),
                Iap2Csm.paramString(2, cfg.wifiPass),
                Iap2Csm.u8(3, cfg.wifiSecurity),
                Iap2Csm.u8(4, cfg.wifiChannel));
        txCsm(MSG_ACC_WIFI_CFG, body, "0x5703 ssid=" + cfg.ssid
                + " ch=" + cfg.wifiChannel + " sec=" + (cfg.wifiSecurity == 2 ? "WPA2" : cfg.wifiSecurity));
        firePhase("wifi");
    }

    private void startCarPlaySession() {
        // 真车流程：0x4300 后先做 ctrl-int 邀请（反连手机 7100 → GET /ctrl-int/1/connect → 200），
        // 邀请后再发 0x4301。放线程里做，别卡住 RFCOMM 读循环。
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                ctrlIntInvite();
                sendStartSession();
            }
        }, "fakecar-4301");
        t.setDaemon(true);
        t.start();
    }

    private void sendStartSession() {
        // 0x4301 布局（对齐 CarDeck/车机）：
        //   param1 = 无线参数组（子参数 id 3 = IP 地址）
        //   param2 = AirPlay 端口 (u32)
        //   param3 = 设备 ID (string)
        byte[] wireless = Iap2Csm.param(1, Iap2Csm.paramString(3, cfg.carIp));
        byte[] port = Iap2Csm.paramUInt(2, cfg.apPort, 4);
        byte[] devid = Iap2Csm.paramString(3, cfg.deviceId);
        txCsm(MSG_CARPLAY_START, Iap2Csm.concat(wireless, port, devid),
                "0x4301 endpoint " + cfg.carIp + ":" + cfg.apPort);
        firePhase("endpoint");
    }

    private void ctrlIntInvite() {
        // 反连手机 ctrl-int：GET /ctrl-int/1/connect。手机要等入网后才起 CtrlIntServer，重试几次。
        if (cfg.phoneIp == null || cfg.phoneIp.trim().isEmpty()) {
            emit("[car] 跳过 ctrl-int 邀请（未填手机 IP，不影响主链路）");
            return;
        }
        for (int attempt = 0; attempt < 8 && running; attempt++) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(cfg.phoneIp, cfg.ctrlIntPort), 2000);
                s.setSoTimeout(2000);
                String req = "GET /ctrl-int/1/connect HTTP/1.1\r\n"
                        + "AirPlay-Receiver-Device-ID: 00:00:00:00:00:00\r\n"
                        + "\r\n";
                s.getOutputStream().write(req.getBytes("ISO-8859-1"));
                s.getOutputStream().flush();
                byte[] resp = new byte[256];
                int rn = s.getInputStream().read(resp);
                String status = rn > 0
                        ? new String(resp, 0, rn, "ISO-8859-1").split("\r\n")[0] : "(无响应)";
                emit("[car] ctrl-int 邀请：" + status);
                if (status.contains("200")) {
                    firePhase("ctrl-int-ok");
                    emit("✓ ctrl-int 邀请被接受");
                    return;
                }
            } catch (Exception e) {
                if (attempt == 7) emit("[car] ✗ ctrl-int 邀请失败（手机 7100 没在听？）: " + e);
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void sendAccessoryIdentification() {
        if (identified) return;
        identified = true;
        // 自报参数全部来自 config（可用 carprofile.json 覆盖成真车那一套 → 1:1）
        int[] accSend = cfg.accSend;
        int[] accRecv = cfg.accRecv;
        byte[] body = Iap2Csm.concat(
                Iap2Csm.paramString(0, cfg.model),
                Iap2Csm.paramString(1, cfg.model),
                Iap2Csm.paramString(2, cfg.maker),
                Iap2Csm.paramString(3, cfg.serial),
                Iap2Csm.paramString(4, cfg.fw),
                Iap2Csm.param(6, idList(accSend)),
                Iap2Csm.param(7, idList(accRecv)));
        txCsm(MSG_IDENT_INFO, body, "0x1D01 配件识别（" + cfg.model + " + want-list）");
        firePhase("identified");
    }

    private static byte[] idList(int[] ids) {
        byte[] out = new byte[ids.length * 2];
        for (int i = 0; i < ids.length; i++) {
            Iap2LinkPacket.putShortBE(out, i * 2, ids[i]);
        }
        return out;
    }

    private static byte[] repeat(byte v, int n) {
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = v;
        return out;
    }

    private String descParams(List<Iap2Csm.Param> ps) {
        StringBuilder sb = new StringBuilder("{");
        for (Iap2Csm.Param p : ps) {
            if (p.value.length > 0 && p.value[p.value.length - 1] == 0
                    && isPrintable(p.value)) {
                sb.append(p.paramId).append("=").append(p.asString()).append(",");
            } else {
                sb.append(p.paramId).append("(").append(p.value.length).append("B),");
            }
        }
        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        return sb.append("}").toString();
    }

    private static boolean isPrintable(byte[] b) {
        for (byte x : b) {
            if (x != 0 && (x < 0x20 || x > 0x7E)) return false;
        }
        return true;
    }

    private String idName(int mid) {
        for (String entry : I2ID_TABLE.split(",")) {
            if (entry.startsWith("0x" + String.format("%04X", mid) + "=")) {
                return entry.substring(entry.indexOf('=') + 1);
            }
        }
        return String.format("0x%04X", mid);
    }

    private void emit(String line) {
        if (log != null) log.onLog(line);
    }

    private void firePhase(String name) {
        if (phase != null) phase.onPhase(name);
    }
}
