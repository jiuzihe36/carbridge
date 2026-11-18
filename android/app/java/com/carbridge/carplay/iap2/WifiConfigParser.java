package com.carbridge.carplay.iap2;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 0x5703（AccessoryWiFiConfiguration）的自适应解析。
 *
 * <p>为什么单独抽出来：不同车机这套参数的**字段顺序不一样**（标准版 0/1/2/3、
 * DiPlay 版 1/2/3/4、安全字段前置的变体……），解析错了的后果是「连到错误的 WiFi」
 * 或「密码错一直连不上」，而日志里只看到一句「收到 0x5703」。这类代码必须可测。
 *
 * <p>策略与真 iPhone 一致：不写死一种布局，**逐个探测 + 合理性校验**择优——
 * SSID 必须非空、信道必须落在 2.4G(1..14) 或 5G(36..165)、安全类型 0..4。
 */
public final class WifiConfigParser {

    /** 解析结果。 */
    public static final class Cfg {
        public final String ssid;
        public final String password;
        public final int channel;
        /** 0=None 1=WEP 2=WPA/WPA2 3=WPA3 过渡 4=WPA3 */
        public final int security;

        public Cfg(String ssid, String password, int channel, int security) {
            this.ssid = ssid;
            this.password = password;
            this.channel = channel;
            this.security = security;
        }

        public boolean usable() {
            return ssid != null && !ssid.isEmpty();
        }

        @Override
        public String toString() {
            return "ssid=" + ssid + ", ch=" + channel + ", sec=" + security
                    + ", pwd=" + (password == null || password.isEmpty() ? "(空)" : "(已给)");
        }
    }

    /** 依次尝试的字段顺序：{ssid, password, channel, security}。 */
    private static final int[][] LAYOUTS = {
            {0, 1, 2, 3},   // 标准
            {1, 2, 3, 4},   // DiPlay
            {0, 1, 3, 2},   // 标准变体（安全在前）
            {1, 2, 4, 3},   // 车机/DiPlay 变体（安全在前）
    };

    private WifiConfigParser() {
    }

    public static Cfg parse(List<Iap2Csm.Param> params) {
        if (params == null || params.isEmpty()) return new Cfg(null, null, 0, 0);
        Map<Integer, Iap2Csm.Param> byId = new HashMap<Integer, Iap2Csm.Param>();
        for (Iap2Csm.Param p : params) byId.put(p.paramId, p);

        // 打分择优，而不是「第一个看起来合法的就用」。
        //
        // ⚠️ 踩过的坑：标准布局 {0,1,2,3} 的消息里没有 id=4，
        //    DiPlay 布局 {1,2,3,4} 拿它去解也能得到一个「信道合法」的结果
        //    —— 于是把密码当成 SSID 用了，连的还是对的网，密码却是错的。
        //    所以「四个字段都在」比「信道碰巧合法」重要得多。
        Cfg best = null;
        int bestScore = -1;
        for (int[] L : LAYOUTS) {
            Cfg c = tryLayout(byId, L[0], L[1], L[2], L[3]);
            if (c == null || !c.usable()) continue;
            int score = 0;
            boolean complete = byId.containsKey(L[0]) && byId.containsKey(L[1])
                    && byId.containsKey(L[2]) && byId.containsKey(L[3]);
            if (complete) score += 8;                       // 四个字段齐全，权重最高
            if (isValidChannel(c.channel)) score += 4;
            if (c.security >= 0 && c.security <= 4) score += 2;
            if (looksLikeSsid(c.ssid)) score += 1;
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best != null ? best : new Cfg(null, null, 0, 0);
    }

    /** SSID 的粗略合理性：1..32 字节、可打印（中文 SSID 是多字节 UTF-8，也算可打印）。 */
    private static boolean looksLikeSsid(String ssid) {
        if (ssid == null || ssid.isEmpty() || ssid.length() > 32) return false;
        for (int i = 0; i < ssid.length(); i++) {
            char ch = ssid.charAt(i);
            if (ch < 0x20 || ch == 0x7F) return false;
        }
        return true;
    }

    private static Cfg tryLayout(Map<Integer, Iap2Csm.Param> m,
                                 int ssidId, int pwdId, int chId, int secId) {
        Iap2Csm.Param ssidP = m.get(ssidId);
        if (ssidP == null) return null;
        Iap2Csm.Param pwdP = m.get(pwdId);
        Iap2Csm.Param chP = m.get(chId);
        Iap2Csm.Param secP = m.get(secId);
        return new Cfg(
                ssidP.asString(),
                pwdP == null ? null : pwdP.asString(),
                chP == null ? 0 : (int) chP.asUInt(),
                secP == null ? 0 : (int) secP.asUInt());
    }

    /** WiFi 信道：2.4G 是 1..14，5G 是 36..165。 */
    public static boolean isValidChannel(int ch) {
        return (ch >= 1 && ch <= 14) || (ch >= 36 && ch <= 165);
    }
}
