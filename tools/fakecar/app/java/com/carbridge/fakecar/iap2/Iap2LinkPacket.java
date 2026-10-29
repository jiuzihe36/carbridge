package com.carbridge.fakecar.iap2;

import java.util.ArrayList;
import java.util.List;

/**
 * iAP2 链路层数据帧。
 */
public final class Iap2LinkPacket {

    public static final int CONTROL_SYN = 0x80;
    public static final int CONTROL_ACK = 0x40;
    public static final int CONTROL_EAK = 0x20;
    public static final int CONTROL_RST = 0x10;
    public static final int CONTROL_SLP = 0x08;

    public static final int HEADER_SIZE = 9;
    public static final int MAX_PAYLOAD = 0xFFFF;

    /** 链路层握手前的探测 marker：双方互发。 */
    public static final byte[] DETECT = {(byte) 0xFF, 0x55, 0x02, 0x00, (byte) 0xEE, 0x10};

    public int control;
    public int seq;
    public int ack;
    public int sessionId;
    public byte[] payload;

    public Iap2LinkPacket(int control, int seq, int ack, int sessionId, byte[] payload) {
        this.control = control & 0xFF;
        this.seq = seq & 0xFF;
        this.ack = ack & 0xFF;
        this.sessionId = sessionId & 0xFF;
        this.payload = payload == null ? new byte[0] : payload;
    }

    public boolean hasControl(int bit) {
        return (control & bit) != 0;
    }

    public int totalLength() {
        return HEADER_SIZE + payload.length + (payload.length > 0 ? 1 : 0);
    }

    public byte[] encode() {
        byte[] out = new byte[totalLength()];
        out[0] = (byte) 0xFF;
        out[1] = 0x5A;
        putShortBE(out, 2, totalLength());
        out[4] = (byte) control;
        out[5] = (byte) seq;
        out[6] = (byte) ack;
        out[7] = (byte) sessionId;
        out[8] = (byte) checksum(out, 0, 8);
        if (payload.length > 0) {
            System.arraycopy(payload, 0, out, HEADER_SIZE, payload.length);
            out[out.length - 1] = (byte) checksum(payload, 0, payload.length);
        }
        return out;
    }

    /** 从完整帧字节解码；校验失败抛异常。 */
    public static Iap2LinkPacket decode(byte[] frame) {
        if (frame.length < HEADER_SIZE) throw new IllegalArgumentException("帧太短 " + frame.length);
        if ((frame[0] & 0xFF) != 0xFF || (frame[1] & 0xFF) != 0x5A) {
            throw new IllegalArgumentException("不是 iAP2 数据帧");
        }
        int total = getShortBE(frame, 2);
        if (total != frame.length) {
            throw new IllegalArgumentException("长度不符 total=" + total + " 实际=" + frame.length);
        }
        // 校验不变式：覆盖区字节和 + 校验字节 ≡ 0 (mod 256)，所以求和必须**含校验字节**。
        // 头校验字节在 index 8，即覆盖 0..8（9 字节）。
        if (checksum(frame, 0, HEADER_SIZE) != 0) throw new IllegalArgumentException("头校验失败");

        int payloadLen = total - HEADER_SIZE - (total > HEADER_SIZE ? 1 : 0);
        byte[] payload = new byte[Math.max(0, payloadLen)];
        if (payload.length > 0) {
            System.arraycopy(frame, HEADER_SIZE, payload, 0, payload.length);
            if (checksum(frame, HEADER_SIZE, payload.length + 1) != 0) {
                throw new IllegalArgumentException("载荷校验失败");
            }
        }
        return new Iap2LinkPacket(frame[4] & 0xFF, frame[5] & 0xFF, frame[6] & 0xFF, frame[7] & 0xFF, payload);
    }

    // ---------------------------------------------------------------- 校验和

    /** 计算覆盖区的校验和，使「覆盖区字节和 + 校验和」mod 256 == 0。 */
    public static int checksum(byte[] b, int off, int len) {
        int sum = 0;
        for (int i = 0; i < len; i++) sum += b[off + i] & 0xFF;
        return ((~sum) + 1) & 0xFF;
    }

    public static void putShortBE(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    public static int getShortBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    // ---------------------------------------------------------------- SYN 载荷

    /** SYN 载荷里的一个会话描述符（3 字节：id / type / version）。 */
    public static final class SessionDescriptor {
        /** 会话类型（Apple 权威 spec）：0=Control、1=External Accessory、2=File Transfer。 */
        public static final int TYPE_CONTROL = 0;
        public static final int TYPE_EXTERNAL_ACCESSORY = 1;
        public static final int TYPE_FILE_TRANSFER = 2;

        public final int id;
        public final int type;
        public final int version;

        public SessionDescriptor(int id, int type, int version) {
            this.id = id;
            this.type = type;
            this.version = version;
        }

        @Override
        public String toString() {
            return "Session{id=" + id + ", type=" + type + ", v=" + version + "}";
        }
    }

    /** 解析后的 SYN 协商参数。 */
    public static final class LinkConfig {
        public int linkVersion = 1;
        public int maxOutstandingPackets = 5;
        public int maxReceivePacketLength = 0x1000;
        public int retransmitTimeoutMs = 500;
        public int cumulativeAckTimeoutMs = 176;
        public int maxRetransmissions = 30;
        public int maxCumulativeAcks = 10;
        public final List<SessionDescriptor> sessions = new ArrayList<SessionDescriptor>();

        /** 控制会话 id（type 0），没找到返回 fallback。 */
        public int controlSessionId(int fallback) {
            for (SessionDescriptor s : sessions) {
                if (s.type == SessionDescriptor.TYPE_CONTROL) return s.id;
            }
            return fallback;
        }

        public byte[] encode() {
            // 前 10 字节为标量参数，随后每个会话 3 字节
            byte[] out = new byte[10 + sessions.size() * 3];
            out[0] = (byte) linkVersion;
            out[1] = (byte) maxOutstandingPackets;
            putShortBE(out, 2, maxReceivePacketLength);
            putShortBE(out, 4, retransmitTimeoutMs);
            putShortBE(out, 6, cumulativeAckTimeoutMs);
            out[8] = (byte) maxRetransmissions;
            out[9] = (byte) maxCumulativeAcks;
            int o = 10;
            for (SessionDescriptor s : sessions) {
                out[o++] = (byte) s.id;
                out[o++] = (byte) s.type;
                out[o++] = (byte) s.version;
            }
            return out;
        }

        public static LinkConfig decode(byte[] payload) {
            LinkConfig c = new LinkConfig();
            if (payload.length < 10) return c;
            c.linkVersion = payload[0] & 0xFF;
            c.maxOutstandingPackets = payload[1] & 0xFF;
            c.maxReceivePacketLength = getShortBE(payload, 2);
            c.retransmitTimeoutMs = getShortBE(payload, 4);
            c.cumulativeAckTimeoutMs = getShortBE(payload, 6);
            c.maxRetransmissions = payload[8] & 0xFF;
            c.maxCumulativeAcks = payload[9] & 0xFF;
            for (int o = 10; o + 2 < payload.length; o += 3) {
                c.sessions.add(new SessionDescriptor(payload[o] & 0xFF, payload[o + 1] & 0xFF, payload[o + 2] & 0xFF));
            }
            return c;
        }
    }
}
