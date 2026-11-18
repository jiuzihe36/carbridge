package com.carbridge.carplay.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MAC 地址的纯文本处理（规范化 / 校验 / 从自由文本里抠 / EUI-64 反推）。
 *
 * <p>为什么要单独一个类：这套规则以前分在两处（{@code BtIdentity.normalize} 与
 * {@code WifiMacResolver.parse}），**两边的校验强度还不一样**——
 * 蓝牙身份必须拒绝「本地管理位」（占位地址 02:00:… 就长这样，填进去车机永远不邀请），
 * 而 Wi-Fi 侧反而要接受它（热点接口地址本来就可能是本地管理、随机化的）。
 * 这种「同名不同规则」最容易在改动时踩错，所以收口到这里并写死单测。
 */
public final class MacText {

    /** 系统在任何设备上都返回的占位地址。 */
    public static final String PLACEHOLDER = "02:00:00:00:00:00";

    private static final Pattern HEX12 = Pattern.compile("[0-9A-Fa-f]{12}");
    private static final Pattern MAC_IN_TEXT =
            Pattern.compile("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b");

    private MacText() {
    }

    /**
     * 严格校验（用于蓝牙身份）：拒绝占位、全 0、全 F、组播位、**本地管理位**。
     *
     * @return 大写冒号形式；不合法返回 null
     */
    public static String canonicalStrict(String input) {
        String out = canonical(input);
        if (out == null) return null;
        int first = Integer.parseInt(out.substring(0, 2), 16);
        if ((first & 0x02) != 0) return null;   // 本地管理位：手填填错最容易踩这个
        return out;
    }

    /**
     * 宽松校验（用于热点 Wi-Fi 地址）：只拒绝占位、全 0、全 F、组播位。
     *
     * <p>允许本地管理位是有意的：热点接口地址经常是随机化的，拒了就拿不到。
     */
    public static String canonical(String input) {
        if (input == null) return null;
        String hex = input.trim().replaceAll("[:\\-\\s.]", "");
        if (!HEX12.matcher(hex).matches()) return null;
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(hex.substring(i, i + 2).toUpperCase(Locale.US));
        }
        String out = sb.toString();
        if ("00:00:00:00:00:00".equals(out) || "FF:FF:FF:FF:FF:FF".equals(out)) return null;
        if (PLACEHOLDER.equals(out)) return null;
        int first = Integer.parseInt(out.substring(0, 2), 16);
        if ((first & 0x01) != 0) return null;   // 组播位
        return out;
    }

    /** 从小写/大写混排的 6 字节数组得到规范形式（宽松校验）。 */
    public static String fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != 6) return null;
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format(Locale.US, "%02X", bytes[i] & 0xFF));
        }
        return canonical(sb.toString());
    }

    /** 文本里第一个合法 MAC（宽松校验）。 */
    public static String firstIn(String text) {
        if (text == null) return null;
        Matcher m = MAC_IN_TEXT.matcher(text);
        while (m.find()) {
            String mac = canonical(m.group());
            if (mac != null) return mac;
        }
        return null;
    }

    /**
     * fe80::xxxx:xxff:fexx:xxxx → MAC（翻转 U/L 位）。
     *
     * <p>随机接口标识（没有 ff:fe 片段）返回 null —— 那种地址里没有 MAC 信息。
     */
    public static String fromEui64(InetAddress address) {
        if (!(address instanceof Inet6Address)) return null;
        byte[] b = address.getAddress();
        if ((b[0] & 0xFF) != 0xFE || (b[1] & 0xC0) != 0x80) return null;
        if ((b[11] & 0xFF) != 0xFF || (b[12] & 0xFF) != 0xFE) return null;
        return fromBytes(new byte[]{
                (byte) (b[8] ^ 0x02), b[9], b[10], b[13], b[14], b[15]
        });
    }
}
