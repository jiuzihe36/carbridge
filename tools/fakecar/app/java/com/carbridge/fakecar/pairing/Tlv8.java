package com.carbridge.fakecar.pairing;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * TLV8 编解码（HomeKit / AirPlay 配对的消息格式）。
 */
public final class Tlv8 {

    // 配对常用类型。
    // ⚠️ **以 DiPlay/xcertplay 的映射为准**（真机抓包 + 源码确认），与标准 HomeKit 不同：
    // 标准 HomeKit STATE=0x01，而这里 STATE=0x06、0x01 是 IDENTIFIER。
    // 我们发 STATE=0x01 时对端读 tlv[0x06] 得 null → 回 {STATE=0, ERROR=2}（实测）。
    public static final int METHOD = 0x00;
    public static final int IDENTIFIER = 0x01;
    public static final int SALT = 0x02;        // DiPlay TYPE_SALT（pair-setup M2）
    public static final int PUBLIC_KEY = 0x03;
    public static final int PROOF = 0x04;
    public static final int ENCRYPTED_DATA = 0x05;
    public static final int STATE = 0x06;
    public static final int ERROR = 0x07;
    public static final int SEQUENCE = 0x08;
    public static final int SIGNATURE = 0x0A;
    public static final int PERMISSIONS = 0x0B;
    public static final int FRAGMENT_DATA = 0x0C;
    public static final int FRAGMENT_LAST = 0x0D;
    public static final int AUTHENTICATION = 0x14;

    /** DiPlay 用的错误码：2 = ERROR_AUTHENTICATION。 */
    public static final int ERR_AUTHENTICATION = 2;

    private Tlv8() {
    }

    /** 一个 TLV。 */
    public static final class Tlv {
        public final int type;
        public final byte[] value;

        public Tlv(int type, byte[] value) {
            this.type = type & 0xFF;
            this.value = value == null ? new byte[0] : value;
        }

        /** 值按无符号整数解释（1..8 字节）。 */
        public long asUInt() {
            long v = 0;
            for (byte b : value) v = (v << 8) | (b & 0xFFL);
            return v;
        }

        @Override
        public String toString() {
            return "Tlv{0x" + Integer.toHexString(type) + ", " + value.length + "B}";
        }
    }

    /** 编码一个 TLV（value 超过 255 字节会自动分片）。 */
    public static byte[] encode(int type, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (value == null || value.length == 0) {
            out.write(type & 0xFF);
            out.write(0);
            return out.toByteArray();
        }
        int off = 0;
        while (off < value.length) {
            int n = Math.min(255, value.length - off);
            out.write(type & 0xFF);
            out.write(n);
            out.write(value, off, n);
            off += n;
        }
        return out.toByteArray();
    }

    /** 编码整数值（1..8 字节大端）。 */
    public static byte[] encodeUInt(int type, long value) {
        int len = 8;
        while (len > 1 && (value >>> (8 * (len - 1))) == 0) len--;
        byte[] v = new byte[len];
        for (int i = len - 1; i >= 0; i--) v[i] = (byte) (value >>> (8 * (len - 1 - i)));
        return encode(type, v);
    }

    /** 拼接多个 TLV。 */
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

    /** 解析成 TLV 列表（同 type 的分片**不合并**，需要时用 {@link #valueOf} 取合并值）。 */
    public static List<Tlv> decode(byte[] data) {
        List<Tlv> out = new ArrayList<Tlv>();
        if (data == null) return out;
        int off = 0;
        while (off + 2 <= data.length) {
            int type = data[off] & 0xFF;
            int len = data[off + 1] & 0xFF;
            off += 2;
            if (off + len > data.length) break;   // 截断，丢弃残余
            byte[] v = new byte[len];
            System.arraycopy(data, off, v, 0, len);
            off += len;
            out.add(new Tlv(type, v));
        }
        return out;
    }

    /** 取某 type 的值（同 type 的多个分片按顺序拼接）；没有返回 null。 */
    public static byte[] valueOf(byte[] data, int type) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        boolean found = false;
        for (Tlv t : decode(data)) {
            if (t.type == (type & 0xFF)) {
                found = true;
                b.write(t.value, 0, t.value.length);
            }
        }
        return found ? b.toByteArray() : null;
    }

    /** 取某 type 的整数值；没有返回 def。 */
    public static long uintOf(byte[] data, int type, long def) {
        byte[] v = valueOf(data, type);
        if (v == null || v.length == 0) return def;
        long r = 0;
        for (byte b : v) r = (r << 8) | (b & 0xFFL);
        return r;
    }
}
