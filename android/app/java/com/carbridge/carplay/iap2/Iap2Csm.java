package com.carbridge.carplay.iap2;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * iAP2 控制会话（CSM）分帧与 TLV 参数。
 */
public final class Iap2Csm {

    public static final int FRAME_MAGIC = 0x4040;
    public static final int FRAME_HEADER_SIZE = 6;

    private Iap2Csm() {
    }

    // ================================================================== 帧

    public static byte[] frame(int messageId, byte[] body) {
        byte[] b = body == null ? new byte[0] : body;
        byte[] out = new byte[FRAME_HEADER_SIZE + b.length];
        out[0] = 0x40;
        out[1] = 0x40;
        Iap2LinkPacket.putShortBE(out, 2, out.length);
        Iap2LinkPacket.putShortBE(out, 4, messageId);
        System.arraycopy(b, 0, out, FRAME_HEADER_SIZE, b.length);
        return out;
    }

    /** 一条完整的 CSM 消息。 */
    public static final class Message {
        public final int messageId;
        public final byte[] body;

        public Message(int messageId, byte[] body) {
            this.messageId = messageId;
            this.body = body;
        }

        public List<Param> params() {
            return parseParams(body);
        }

        @Override
        public String toString() {
            return "CsmMsg{id=0x" + Integer.toHexString(messageId) + ", body=" + body.length + "B}";
        }
    }

    public static Message parse(byte[] frame) {
        if (frame.length < FRAME_HEADER_SIZE || (frame[0] & 0xFF) != 0x40 || (frame[1] & 0xFF) != 0x40) {
            throw new IllegalArgumentException("不是 CSM 帧");
        }
        int total = Iap2LinkPacket.getShortBE(frame, 2);
        if (total != frame.length) {
            throw new IllegalArgumentException("CSM 长度不符 " + total + " vs " + frame.length);
        }
        int id = Iap2LinkPacket.getShortBE(frame, 4);
        byte[] body = new byte[frame.length - FRAME_HEADER_SIZE];
        System.arraycopy(frame, FRAME_HEADER_SIZE, body, 0, body.length);
        return new Message(id, body);
    }

    /** 增量解析器：喂入链路层载荷字节流，吐出完整 CSM 消息。 */
    public static final class Parser {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        public List<Message> feed(byte[] data) {
            buffer.write(data, 0, data.length);
            byte[] all = buffer.toByteArray();
            List<Message> out = new ArrayList<Message>();
            int off = 0;
            while (all.length - off >= FRAME_HEADER_SIZE) {
                if ((all[off] & 0xFF) != 0x40 || (all[off + 1] & 0xFF) != 0x40) {
                    off++; // 丢到下一个可能的帧头
                    continue;
                }
                int total = Iap2LinkPacket.getShortBE(all, off + 2);
                if (total < FRAME_HEADER_SIZE || off + total > all.length) break;
                byte[] one = new byte[total];
                System.arraycopy(all, off, one, 0, total);
                out.add(parse(one));
                off += total;
            }
            buffer.reset();
            if (off < all.length) buffer.write(all, off, all.length - off);
            return out;
        }
    }

    // ================================================================== 参数 TLV

    /** 一个参数。 */
    public static final class Param {
        public final int paramId;
        public final byte[] value;

        public Param(int paramId, byte[] value) {
            this.paramId = paramId;
            this.value = value;
        }

        /** 值按大端无符号整数解释。 */
        public long asUInt() {
            long v = 0;
            for (byte b : value) v = (v << 8) | (b & 0xFFL);
            return v;
        }

        /** 值按 NUL 结尾 UTF-8 字符串解释。 */
        public String asString() {
            int n = value.length;
            if (n > 0 && value[n - 1] == 0) n--;
            return new String(value, 0, n, java.nio.charset.StandardCharsets.UTF_8);
        }

        public List<Param> asNested() {
            return parseParams(value);
        }

        @Override
        public String toString() {
            return "Param{id=0x" + Integer.toHexString(paramId) + ", len=" + value.length + "}";
        }
    }

    public static byte[] param(int paramId, byte[] value) {
        byte[] v = value == null ? new byte[0] : value;
        byte[] out = new byte[4 + v.length];
        Iap2LinkPacket.putShortBE(out, 0, out.length);
        Iap2LinkPacket.putShortBE(out, 2, paramId);
        System.arraycopy(v, 0, out, 4, v.length);
        return out;
    }

    public static byte[] paramString(int paramId, String s) {
        byte[] raw = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] nul = new byte[raw.length + 1]; // NUL 结尾，空串就是单个 0x00
        System.arraycopy(raw, 0, nul, 0, raw.length);
        return param(paramId, nul);
    }

    public static byte[] paramUInt(int paramId, long value, int sizeBytes) {
        byte[] v = new byte[sizeBytes];
        for (int i = 0; i < sizeBytes; i++) {
            v[sizeBytes - 1 - i] = (byte) ((value >>> (8 * i)) & 0xFF);
        }
        return param(paramId, v);
    }

    /** 1 字节无符号值参数。 */
    public static byte[] u8(int paramId, int value) {
        return param(paramId, new byte[]{(byte) (value & 0xFF)});
    }

    /**
     * 参数组：value 里再套一层 TLV（外层 param 的值是若干子参数）。
     *
     * <p>CarDeck 的 0x4300 就是这种形状：
     * {@code group(1, u8(0,1), string(1, mac))} —— 外层 id=1，值里是「标志」和「蓝牙 MAC」。
     */
    public static byte[] group(int paramId, byte[]... children) {
        return param(paramId, concat(children));
    }

    public static List<Param> parseParams(byte[] body) {
        List<Param> out = new ArrayList<Param>();
        int off = 0;
        while (off + 4 <= body.length) {
            int len = Iap2LinkPacket.getShortBE(body, off);
            if (len < 4 || off + len > body.length) break;
            int id = Iap2LinkPacket.getShortBE(body, off + 2);
            byte[] value = new byte[len - 4];
            System.arraycopy(body, off + 4, value, 0, value.length);
            out.add(new Param(id, value));
            off += len;
        }
        return out;
    }

    public static Param find(List<Param> params, int paramId) {
        for (Param p : params) {
            if (p.paramId == paramId) return p;
        }
        return null;
    }

    public static byte[] concat(byte[]... parts) {
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
