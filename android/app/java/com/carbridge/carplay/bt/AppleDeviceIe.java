package com.carbridge.carplay.bt;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 「Apple Device IE」—— 车机塞进<strong>自己 EIR 的厂商自定义数据</strong> （EIR manufacturer sp
 * ecific data，类型 0xFF）里的那段二进制， 里面装的是<strong>车机热点的 BSSID / SSID / 密码</strong>。
 */
public final class AppleDeviceIe {

    /** TLV 类型：BSSID（字符串）。 */
    private static final int TLV_BSSID = 0x01;
    /** TLV 类型：密码（字符串）。 */
    private static final int TLV_PASSWORD = 0x02;
    /** TLV 类型：SSID（字符串）。 */
    private static final int TLV_SSID = 0x03;
    /** TLV 类型：车机 wlan1 MAC（6 字节二进制）。 */
    private static final int TLV_MAC = 0x07;

    private static final Pattern MAC_TEXT = Pattern.compile(
            "([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}");

    /** 热点 BSSID（形如 "00:11:22:33:44:55"），可能为 null。 */
    public final String bssid;
    /** 热点 SSID，可能为 null。 */
    public final String ssid;
    /** 热点密码，可能为 null。 */
    public final String password;
    /** 车机 wlan1 的 MAC（"00:11:22:33:44:55"），可能为 null。 */
    public final String mac;

    public AppleDeviceIe(String bssid, String ssid, String password, String mac) {
        this.bssid = bssid;
        this.ssid = ssid;
        this.password = password;
        this.mac = mac;
    }

    /** 有没有拿到能用的热点信息。 */
    public boolean hasHotspot() {
        return ssid != null && !ssid.isEmpty();
    }

    @Override
    public String toString() {
        return "AppleDeviceIe{ssid=" + ssid + ", bssid=" + bssid
                + ", pwd=" + (password == null ? "null" : password.length() + "B")
                + ", mac=" + mac + "}";
    }

    // ================================================================== 解析

    /**
     * 解析 EIR 厂商自定义数据。
     *
     * @param data 原始字节（可能是完整 EIR，也可能直接是 IE 载荷）；null/空 → 返回 null
     */
    public static AppleDeviceIe parse(byte[] data) {
        if (data == null || data.length < 4) return null;
        // 完整 EIR 的话，第一个字段是 [len][type][payload…]，先把 0xFF 厂商段抠出来
        byte[] payload = extractManufacturerPayload(data);
        if (payload != null) {
            AppleDeviceIe ie = parsePayload(payload);
            if (ie != null) return ie;
        }
        return parsePayload(data);
    }

    /**
     * 解析手机拿到的那个 String extra。
     *
     * <p>ColorOS 的 {@code EIR_MANUFACTURER} 是 String，格式取决于原生栈：
     * 多数情况是<strong>十六进制字符串</strong>，少数是原始字节的 Latin-1 形式。
     * 两种都试：先试更像的那种（全是 hex 字符 → 当 hex 解），再试另一种。
     */
    public static AppleDeviceIe parse(String text) {
        if (text == null || text.isEmpty()) return null;
        String t = text.trim();
        boolean hexLike = (t.length() & 1) == 0 && t.length() >= 4;
        if (hexLike) {
            for (int i = 0; i < t.length() && hexLike; i++) {
                if (Character.digit(t.charAt(i), 16) < 0) hexLike = false;
            }
        }
        if (hexLike) {
            byte[] hex = decodeHex(t);
            AppleDeviceIe ie = parse(hex);
            if (ie != null) return ie;
        }
        return parse(text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    /** 十六进制字符串 → 字节；不是合法 hex 返回 null。 */
    public static byte[] decodeHex(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.length() < 4 || (t.length() & 1) != 0) return null;
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(t.charAt(i * 2), 16);
            int lo = Character.digit(t.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /**
     * 如果这段数据是完整 EIR（多字段 TLV 流），抠出类型 0xFF 的厂商段；
     * 不是完整 EIR 就返回 null（调用方按「整段就是 IE 载荷」处理）。
     *
     * <p>EIR 每个字段的格式是 {@code [len][type][data…]}，{@code len} = 后面
     * type+data 的总字节数（不含 len 自己），所以一个字段占 {@code 1+len} 字节。
     */
    private static byte[] extractManufacturerPayload(byte[] data) {
        // 完整 EIR 的第一个字节是第一个字段的总长度，必须放得下
        int firstLen = data[0] & 0xff;
        if (firstLen < 2 || firstLen + 1 > data.length) return null;
        int off = 0;
        byte[] mfr = null;
        while (off + 2 <= data.length) {
            int len = data[off] & 0xff;
            if (len < 2 || off + 1 + len > data.length) return null;   // 不是合法 EIR 流
            int type = data[off + 1] & 0xff;
            if (type == 0xFF && mfr == null) {
                int plen = len - 1;    // 去掉 type 字节
                if (plen <= 0) return null;
                mfr = Arrays.copyOfRange(data, off + 2, off + 2 + plen);
            }
            off += 1 + len;
        }
        // 必须正好走完整个缓冲，否则说明这不是完整 EIR（只是碰巧像）
        if (off != data.length || mfr == null) return null;
        return mfr;
    }

    /** 按「Apple Device IE 载荷」解析。 */
    private static AppleDeviceIe parsePayload(byte[] p) {
        if (p == null || p.length < 4) return null;
        // Apple Device IE 的第一个字节恒为 0xDD（既是 IE 标记，也可能是公司 ID 的低字节）。
        // 不满足就直接否掉 —— 否则随机数据会被误判成车机热点。
        if ((p[0] & 0xff) != 0xDD) return null;
        // 已知布局：TLV 从固定头之后开始。头部长度字段是算出来的，不同固件可能差 1~2 字节；
        // 载荷前面还可能带 2 字节公司 ID（0x00DD）→ 偏移再 +2。所以 8..13 都试一遍。
        for (int off = 8; off <= 13 && off < p.length; off++) {
            AppleDeviceIe ie = fromTlvs(parseTlvs(p, off));
            if (ie != null && (ie.hasHotspot() || ie.bssid != null)) return ie;
        }
        // 兜底：全缓冲扫描 TLV（找第一个像样的 TLV 序列）
        for (int off = 2; off + 2 <= p.length; off++) {
            int type = p[off] & 0xff;
            if (type != TLV_BSSID && type != TLV_SSID && type != TLV_PASSWORD) continue;
            AppleDeviceIe ie = fromTlvs(parseTlvs(p, off));
            if (ie != null && ie.hasHotspot()) return ie;
        }
        return null;
    }

    /** 从 [off] 开始按 TLV（[type][len][value…]）顺序解析。 */
    private static Map<Integer, byte[]> parseTlvs(byte[] d, int off) {
        Map<Integer, byte[]> out = new LinkedHashMap<>();
        int p = off;
        while (p + 2 <= d.length) {
            int type = d[p] & 0xff;
            int len = d[p + 1] & 0xff;
            if (len == 0 || p + 2 + len > d.length) break;
            out.put(type, Arrays.copyOfRange(d, p + 2, p + 2 + len));
            p += 2 + len;
        }
        return out;
    }

    /** 把 TLV 表转成对象；一个有效字段都没有就返回 null。 */
    private static AppleDeviceIe fromTlvs(Map<Integer, byte[]> tlvs) {
        if (tlvs == null || tlvs.isEmpty()) return null;
        String bssid = null;
        String ssid = null;
        String password = null;
        String mac = null;

        byte[] b = tlvs.get(TLV_BSSID);
        if (b != null) {
            String s = printable(b);
            if (s != null && MAC_TEXT.matcher(s).matches()) bssid = s;
        }
        byte[] s3 = tlvs.get(TLV_SSID);
        if (s3 != null) {
            String s = printable(s3);
            if (s != null && !s.isEmpty() && s.length() <= 32) ssid = s;
        }
        byte[] s2 = tlvs.get(TLV_PASSWORD);
        if (s2 != null) {
            String s = printable(s2);
            if (s != null) password = s;
        }
        byte[] m = tlvs.get(TLV_MAC);
        if (m != null && m.length == 6) {
            StringBuilder sb = new StringBuilder(17);
            for (int i = 0; i < 6; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format(Locale.US, "%02X", m[i] & 0xff));
            }
            mac = sb.toString();
        }
        if (bssid == null && ssid == null && password == null && mac == null) return null;
        return new AppleDeviceIe(bssid, ssid, password, mac);
    }

    /** 字节数组 → 可打印字符串；含非打印字符返回 null。 */
    private static String printable(byte[] b) {
        if (b == null || b.length == 0) return null;
        StringBuilder sb = new StringBuilder(b.length);
        for (byte x : b) {
            int c = x & 0xff;
            if (c < 0x20 || c > 0x7e) return null;
            sb.append((char) c);
        }
        return sb.toString();
    }
}
