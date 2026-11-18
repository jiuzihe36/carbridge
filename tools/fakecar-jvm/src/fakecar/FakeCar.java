package fakecar;

import com.carbridge.carplay.iap2.Iap2Csm;
import com.carbridge.carplay.iap2.Iap2LinkPacket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 假车机：在配件侧复刻真车机的 iAP2 行为，用来在 Mac 上跑我们自己的发送端代码。
 *
 * <p>它只做「车机做了什么」，不判断对错；判错交给 {@link Harness} 的断言。
 *
 * <p>链路层假设：管道按「一次 send = 一个完整包」投递（发送端 {@code sendRaw} 就是这么写的），
 * 所以这里不需要做 RFCOMM 的流式重组。
 */
public final class FakeCar {

    /** 把字节发回手机。 */
    public interface Sink {
        void send(byte[] bytes);
    }

    /** 车机画像：决定它声明什么、发什么。 */
    public static final class Profile {
        /** 0x1D01 里「车机能收哪些手机消息」。 */
        public int[] declaresReceive = {0x4E0D, 0x4E0A, 0x4E09, 0x4E0B, 0x4E0C,
                0x4300, 0x5702, 0x4C00, 0xEA00, 0xEA03};
        /** 0x1D01 里「车机会发哪些消息」。 */
        public int[] declaresSend = {0x4301, 0x5703, 0x1D00, 0x1D01, 0xAA01, 0xAA03};
        /** 车机热点是否开着（不开就不回 0x5703，等价于真车「只弹一句请打开热点」）。 */
        public boolean hotspotOn = true;
        /** 0xAA03 签名长度（真车 RSA-1024=128；故意填短用于测「形状不符」）。 */
        public int mfiSignatureLen = 128;
        /** 是否把同一份 0xAA03 再发一次（测重放检测）。 */
        public boolean replayAuthResponse = false;
        /** 收到 0x4300 后是否回 0x4301。 */
        public boolean answerCarPlayAvailability = true;
        /** 车机型号（0x1D01 param1）。 */
        public String model = "HeadUnit";
        /** 车机拒绝我们的识别信息（回 0x1D03 而不是 0x1D01）。 */
        public boolean rejectIdentification = false;
        /** 收到这条消息后立刻发 RST 拆链。 */
        public int rstAfterMessage = 0;
        /** 0x5703 之后立刻发 0x4301（早于我们发 0x4300）。 */
        public boolean startSessionBeforeAvailability = false;

        public Profile() {
        }

        public static Profile chery() {
            return new Profile();
        }

        /** 奥迪 MIB3 3315 形态：清单只有 5 项，0x4E0D/0x4E0B/0x4E0E 都不在里面。 */
        public static Profile audiSparseList() {
            Profile p = new Profile();
            p.declaresReceive = new int[]{0x4E0C, 0x4E09, 0x4300, 0xEA00};
            p.declaresSend = new int[]{0x4301, 0xEA03};
            return p;
        }

        /** BMW iD9 / 上汽 AH4EM 形态：只收 0x4300、只发 0x4301，完全不参与 0x57xx。 */
        public static Profile newGenNoWifi() {
            Profile p = new Profile();
            p.declaresReceive = new int[]{0x4300};
            p.declaresSend = new int[]{0x4301};
            p.hotspotOn = true;
            return p;
        }
    }

    private final Sink sink;
    private final Profile profile;
    private final StringBuilder traceLog = new StringBuilder();

    /** 收到的 CSM：按到达顺序记录。 */
    public final List<Integer> receivedIds = new ArrayList<Integer>();
    /** 每条消息的最后一份 body（按 id）。 */
    public final Map<Integer, byte[]> receivedBodies = new LinkedHashMap<Integer, byte[]>();

    private final List<byte[]> pendingCsm = new ArrayList<byte[]>();
    private final Iap2Csm.Parser parser = new Iap2Csm.Parser();
    private int txSeq = 0;
    private int rxExpected = 0;
    private int lastSentSeq = 0;
    private boolean sentMarker = false;

    public FakeCar(Sink sink, Profile profile) {
        this.sink = sink;
        this.profile = profile;
    }

    public String trace() {
        return traceLog.toString();
    }

    public int countOf(int messageId) {
        int n = 0;
        for (int id : receivedIds) if (id == messageId) n++;
        return n;
    }

    /** 手机发来的原始字节。 */
    public void onBytes(byte[] data) {
        if (data == null || data.length == 0) return;
        if (data.length == Iap2LinkPacket.DETECT.length && startsWithDetect(data)) {
            traceLog.append("  car← DETECT marker\n");
            // 链路层协议：双方互发 marker。只收到不回，发送端会一直等（→ 永远不发 SYN）。
            if (!sentMarker) {
                sentMarker = true;
                traceLog.append("  car→ DETECT marker\n");
                sink.send(Iap2LinkPacket.DETECT);
            }
            return;
        }
        Iap2LinkPacket p;
        try {
            p = Iap2LinkPacket.decode(data);
        } catch (Throwable t) {
            traceLog.append("  car← 无法解码的包（").append(data.length).append("B）：")
                    .append(t).append('\n');
            return;
        }
        boolean syn = p.hasControl(Iap2LinkPacket.CONTROL_SYN);
        boolean ack = p.hasControl(Iap2LinkPacket.CONTROL_ACK);

        if (syn && !ack) {
            traceLog.append("  car← SYN  seq=").append(p.seq).append('\n');
            rxExpected = (p.seq + 1) & 0xFF;   // 对端 SYN 占一个序号
            sendSynAck(p.seq);
            return;
        }
        traceLog.append("  car← 包 ctrl=0x").append(Integer.toHexString(p.control))
                .append(" seq=").append(p.seq).append(" ack=").append(p.ack)
                .append(" payload=").append(p.payload.length)
                .append(" hex=").append(hex(p.payload)).append('\n');
        if (ack && p.payload.length == 0) {
            return;   // 纯 ACK，忽略
        }
        if (p.payload.length > 0) {
            if (rxExpected >= 0 && p.seq != rxExpected) {
                // 乱序：重 ACK 期望值，丢弃（和真车机一致）
                traceLog.append("  car← 丢包 seq=").append(p.seq)
                        .append(" 期望=").append(rxExpected).append('\n');
                sendAckOnly((rxExpected - 1) & 0xFF);
                return;
            }
            rxExpected = (p.seq + 1) & 0xFF;
            sendAckOnly(p.seq);
            for (Iap2Csm.Message m : parser.feed(p.payload)) {
                onCsm(m);
            }
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(b.length, 24); i++) sb.append(String.format("%02x ", b[i]));
        return sb.toString().trim();
    }

    private static boolean startsWithDetect(byte[] d) {
        for (int i = 0; i < Iap2LinkPacket.DETECT.length; i++) {
            if (d[i] != Iap2LinkPacket.DETECT[i]) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ CSM

    private void onCsm(Iap2Csm.Message m) {
        receivedIds.add(m.messageId);
        receivedBodies.put(m.messageId, m.body);
        traceLog.append("  car← CSM 0x").append(Integer.toHexString(m.messageId))
                .append(" (").append(m.body.length).append("B)\n");
        switch (m.messageId) {
            case 0x1D00:
                if (profile.rejectIdentification) sendCsm(0x1D03, new byte[0]);
                else sendIdentification();
                break;
            case 0xAA00:
                sendCsm(0xAA01, Iap2Csm.param(0, new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));
                break;
            case 0xAA02: {
                byte[] sig = new byte[profile.mfiSignatureLen];
                for (int i = 0; i < sig.length; i++) sig[i] = (byte) (i * 7);
                sendCsm(0xAA03, Iap2Csm.param(0, sig));
                if (profile.replayAuthResponse) sendCsm(0xAA03, Iap2Csm.param(0, sig));
                break;
            }
            case 0x5702:
                if (profile.hotspotOn) {
                    sendWifiConfiguration();
                    if (profile.startSessionBeforeAvailability) sendCarPlayStartSession();
                } else {
                    traceLog.append("  car→ 热点没开，不回 0x5703\n");
                }
                break;
            case 0x4300:
                if (profile.answerCarPlayAvailability) sendCarPlayStartSession();
                break;
            default:
                break;
        }
        if (profile.rstAfterMessage != 0 && m.messageId == profile.rstAfterMessage) {
            sendRst();
        }
    }

    /** 主动拆链（真车机会在超时/用户取消时这么干）。 */
    public void sendRst() {
        traceLog.append("  car→ RST\n");
        Iap2LinkPacket p = new Iap2LinkPacket(
                Iap2LinkPacket.CONTROL_RST, txSeq, rxExpected & 0xFF, 0, new byte[0]);
        sink.send(p.encode());
    }

    private void sendIdentification() {
        byte[] body = Iap2Csm.concat(
                Iap2Csm.paramString(0, "CarPlay"),            // name
                Iap2Csm.paramString(1, profile.model),        // modelIdentifier
                Iap2Csm.paramString(3, "VENDOR"),             // manufacturer
                Iap2Csm.paramString(4, "0000000000000"),      // serialNumber
                Iap2Csm.param(6, u16List(profile.declaresSend)),
                Iap2Csm.param(7, u16List(profile.declaresReceive)),
                Iap2Csm.paramString(0x0016, "GPGGA,GPGSV"));  // 定位组件
        sendCsm(0x1D01, body);
    }

    private void sendWifiConfiguration() {
        byte[] body = Iap2Csm.concat(
                Iap2Csm.paramString(0, "CarPlay-1234"),
                Iap2Csm.paramString(1, "12345678"),
                Iap2Csm.u8(2, 6),
                Iap2Csm.u8(3, 4));
        sendCsm(0x5703, body);
    }

    /** 0x4301 用「无线组」布局：param1 里嵌 id=3 的 IP 列表，param2 端口，param3 设备 ID。 */
    private void sendCarPlayStartSession() {
        byte[] wirelessGroup = Iap2Csm.concat(
                Iap2Csm.paramString(3, "192.168.66.1"));
        byte[] body = Iap2Csm.concat(
                Iap2Csm.group(1, wirelessGroup),
                Iap2Csm.paramUInt(2, 7000, 4),
                Iap2Csm.paramString(3, "AA:BB:CC:DD:EE:FF"));
        sendCsm(0x4301, body);
    }

    private static byte[] u16List(int[] ids) {
        byte[] out = new byte[ids.length * 2];
        for (int i = 0; i < ids.length; i++) {
            out[i * 2] = (byte) ((ids[i] >> 8) & 0xFF);
            out[i * 2 + 1] = (byte) (ids[i] & 0xFF);
        }
        return out;
    }

    public void sendCsm(int messageId, byte[] body) {
        byte[] frame = Iap2Csm.frame(messageId, body);
        traceLog.append("  car→ CSM 0x").append(Integer.toHexString(messageId))
                .append(" (").append(body.length).append("B)\n");
        sendData(frame);
    }

    // ------------------------------------------------------------------ 链路

    private void sendSynAck(int ackSeq) {
        Iap2LinkPacket.LinkConfig cfg = new Iap2LinkPacket.LinkConfig();
        cfg.linkVersion = 1;
        cfg.maxOutstandingPackets = 4;
        cfg.maxReceivePacketLength = 0x3E8;
        cfg.retransmitTimeoutMs = 2000;
        cfg.cumulativeAckTimeoutMs = 200;
        cfg.maxRetransmissions = 4;
        cfg.maxCumulativeAcks = 4;
        cfg.sessions.add(new Iap2LinkPacket.SessionDescriptor(1, 0, 2));
        cfg.sessions.add(new Iap2LinkPacket.SessionDescriptor(2, 1, 1));
        Iap2LinkPacket p = new Iap2LinkPacket(
                Iap2LinkPacket.CONTROL_SYN | Iap2LinkPacket.CONTROL_ACK,
                txSeq, ackSeq & 0xFF, 0, cfg.encode());
        lastSentSeq = txSeq;
        txSeq = (txSeq + 1) & 0xFF;
        sink.send(p.encode());
        traceLog.append("  car→ SYN|ACK\n");
    }

    private void sendAckOnly(int ackSeq) {
        Iap2LinkPacket p = new Iap2LinkPacket(
                Iap2LinkPacket.CONTROL_ACK, lastSentSeq, ackSeq & 0xFF, 0, new byte[0]);
        sink.send(p.encode());
    }

    private void sendData(byte[] payload) {
        int seq = txSeq;
        lastSentSeq = seq;
        txSeq = (txSeq + 1) & 0xFF;
        Iap2LinkPacket p = new Iap2LinkPacket(
                Iap2LinkPacket.CONTROL_ACK, seq, rxExpected == 0 ? 0 : (rxExpected - 1) & 0xFF,
                0, payload);
        sink.send(p.encode());
    }
}
