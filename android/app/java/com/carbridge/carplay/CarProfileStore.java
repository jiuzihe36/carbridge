package com.carbridge.carplay;

import android.content.Context;
import android.content.SharedPreferences;

import com.carbridge.carplay.util.Diag;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 多车机配置存储：一台车机（按它的蓝牙 MAC 区分）一条配置。
 *
 * <p>为什么要按 MAC 分开存：同一个手机要轮流接好几台车机（自己的车、借的车、台架上的车机），
 * 而这几个值是「每台车机各不相同」的——
 * <ul>
 *   <li>{@code srcvers}：广播版本。有车机只认 950.7.1，有老车机只认 320.17.1；
 *       一台车调好了，换一台可能就连不上探测；</li>
 *   <li>{@code autoFit} + {@code targetW/H/Fps}：车机画布大小与刷新率不一样，
 *       上次在 A 车采纳的 1920×720 拿到 B 车的小屏上会被拉伸；</li>
 *   <li>{@code lastModel}：车机 /info 自报的型号，只是给人看的，方便在列表里认出是哪台；</li>
 *   <li>{@code lastSeenAt}：最后连接时间，用来回答「上次接的是哪台」，默认选中它。</li>
 * </ul>
 * 所以「全局一份配置」会把上一台车的调参带到下一台车上，必须按车机分开。
 *
 * <p>为什么用 SharedPreferences + 一个 JSON 字符串，而不用 Room / 单独文件：
 * <ul>
 *   <li>数据量是「个位数台车 × 8 个字段」，为它引入 Room（注解处理器 + 建库 + 迁移）
 *       成本远大于收益，而且本项目的无 Gradle 构建链里加注解处理器很别扭；</li>
 *   <li>每次连接只读一条记录，SharedPreferences 整文件读进内存的开销可以忽略；</li>
 *   <li>字段以后一定会加（这类调参项一直在长），存 JSON 时加字段 = 多一个键，
 *       老记录直接 {@code opt} 出默认值就兼容了，不用写数据库迁移；</li>
 *   <li>不用「一堆平铺的 key」（{@code mac_XX_srcvers}）是因为那样一台车就散成 8 个键，
 *       删一台车要删 8 次、读一台车要拼 8 个 key；一条 JSON 天然是「一个整体」，
 *       删/读/列都只碰一个键。</li>
 * </ul>
 *
 * <p>键的形态是 {@code mac:AA:BB:CC:DD:EE:FF}，值是该台车的 JSON。{@link #list} 靠
 * 「键前缀 + getAll()」枚举全部车机，不再另外维护一份 MAC 索引——索引和真实记录会不同步
 * （删了记录忘了删索引），而前缀枚举永远和存储一致。
 *
 * <p>本类只做存取，不参与连接流程；调用方自己决定何时读、何时写。
 * 所有异常都收敛到 {@link Diag#w}，存储坏掉时降级成「没有记录」，不让界面崩。
 */
public final class CarProfileStore {

    /** SharedPreferences 文件名（固定叫 car_profiles，方便用 adb 直接看）。 */
    private static final String PREFS = "car_profiles";

    /** 每条记录的键前缀；后面拼规范化后的 MAC。 */
    private static final String KEY_PREFIX = "mac:";

    /**
     * 记录格式版本。现在只有 1，但先写进去：以后字段语义变了（比如 targetFps 改成
     * 区间字符串），读到 {@code v} 小的记录就知道要走兼容分支，而不是靠猜。
     */
    private static final int SCHEMA = 1;

    private CarProfileStore() {
    }

    // ---------------------------------------------------------------- 数据

    /**
     * 一台车机的配置。字段全 public、直接读写——它就是个数据袋，
     * 加 getter/setter 只会让调用方多敲字。
     */
    public static final class CarProfile {

        /** 车机蓝牙 MAC（规范化后的 AA:BB:CC:DD:EE:FF），同时是存储键。 */
        public String mac;

        /** 广播版本（mDNS TXT 的 srcvers），如 950.7.1。 */
        public String srcvers;

        /** 画质是否自动适配车机画布。默认开：新车的画布未知，跟随车机最不容易出错。 */
        public boolean autoFit = true;

        /** 上次采纳（或车机上报）的画布宽，0 表示还不知道。 */
        public int targetW;

        /** 上次采纳的画布高。 */
        public int targetH;

        /** 上次采纳的帧率，0 表示还不知道。 */
        public int targetFps;

        /** 车机 /info 自报的 model，仅用于展示/日志，可能为 null。 */
        public String lastModel;

        /** 最后连接时间（System.currentTimeMillis），0 表示「存过但没连过」。 */
        public long lastSeenAt;

        /** 空配置（调用方 new 完直接填字段）。 */
        public CarProfile() {
        }

        /** 画布是否已经采到过值。 */
        public boolean hasCanvas() {
            return targetW > 0 && targetH > 0;
        }

        /** 一行摘要，日志/诊断页用。 */
        @Override
        public String toString() {
            return (mac == null ? "?" : mac)
                    + "｜srcvers=" + (srcvers == null ? "默认" : srcvers)
                    + "｜autoFit=" + (autoFit ? "开" : "关")
                    + "｜画布=" + (hasCanvas() ? targetW + "×" + targetH : "未知")
                    + "@" + (targetFps > 0 ? targetFps + "fps" : "帧率未知")
                    + "｜model=" + (lastModel == null ? "未报" : lastModel)
                    + "｜最后连接=" + lastSeenAt;
        }
    }

    // ---------------------------------------------------------------- 写

    /**
     * 保存一台车机的配置（整条覆盖）。
     *
     * <p>整条覆盖而不是逐字段合并：调用方手里拿到的就是「这台车现在的完整配置」，
     * 合并会让「把 autoFit 改回 true」这种操作因为老值残留而失效。
     *
     * <p>{@code lastSeenAt <= 0} 时补成当前时间：否则刚存下的记录在
     * {@link #mostRecent} 里永远排最后，「刚接过的车」反而选不中。
     */
    public static void save(Context ctx, String mac, CarProfile p) {
        if (ctx == null || p == null) return;
        String key = keyOf(mac);
        if (key == null) return;
        try {
            p.mac = canonical(mac);
            if (p.lastSeenAt <= 0) p.lastSeenAt = System.currentTimeMillis();

            JSONObject o = new JSONObject();
            o.put("v", SCHEMA);
            // MAC 在值里再存一份：只用 adb 导出 prefs 看内容时，人一眼能对上是哪台车。
            o.put("mac", p.mac);
            o.put("srcvers", p.srcvers == null ? JSONObject.NULL : p.srcvers);
            o.put("autoFit", p.autoFit);
            o.put("targetW", p.targetW);
            o.put("targetH", p.targetH);
            o.put("targetFps", p.targetFps);
            o.put("lastModel", p.lastModel == null ? JSONObject.NULL : p.lastModel);
            o.put("lastSeenAt", p.lastSeenAt);

            // apply() 而不是 commit()：保存发生在连接流程里，不能为了落盘阻塞主线程；
            // 进程当场被杀的概率极低，丢了下次连接会重新写一遍。
            prefs(ctx).edit().putString(key, o.toString()).apply();
        } catch (Throwable t) {
            Diag.w("CarProfileStore", t);
        }
    }

    /** 删掉一台车机的记录（换车/重置用）。没有记录时什么也不做。 */
    public static void remove(Context ctx, String mac) {
        if (ctx == null) return;
        String key = keyOf(mac);
        if (key == null) return;
        try {
            prefs(ctx).edit().remove(key).apply();
        } catch (Throwable t) {
            Diag.w("CarProfileStore", t);
        }
    }

    // ---------------------------------------------------------------- 读

    /**
     * 读一台车机的配置；没有记录（或记录坏了）返回 {@code null}。
     *
     * <p>返回 null 而不是「默认配置」，是为了让调用方能区分「第一次见这台车」和
     * 「这台车调过、值就是默认」——前者适合跟随车机上报，后者不该被覆盖。
     */
    public static CarProfile load(Context ctx, String mac) {
        if (ctx == null) return null;
        String key = keyOf(mac);
        if (key == null) return null;
        try {
            String json = prefs(ctx).getString(key, null);
            if (json == null) return null;
            CarProfile p = parse(json);
            if (p != null) p.mac = key.substring(KEY_PREFIX.length());
            return p;
        } catch (Throwable t) {
            Diag.w("CarProfileStore", t);
            return null;
        }
    }

    /**
     * 全部车机配置，按最后连接时间从新到旧。
     *
     * <p>按前缀枚举 getAll()：SharedPreferences 会把整个文件读进内存，这里再筛一遍
     * 是纯内存操作，台数是个位数，不需要额外索引。
     */
    public static List<CarProfile> list(Context ctx) {
        List<CarProfile> out = new ArrayList<CarProfile>();
        if (ctx == null) return out;
        try {
            Map<String, ?> all = prefs(ctx).getAll();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String key = e.getKey();
                if (key == null || !key.startsWith(KEY_PREFIX)) continue;
                Object v = e.getValue();
                // 只认字符串：这个文件名只有本类用，但万一被别的版本塞了别的类型，
                // 跳过它而不是让 ClassCastException 把整张列表带崩。
                if (!(v instanceof String)) continue;
                CarProfile p = parse((String) v);
                if (p == null) continue;
                p.mac = key.substring(KEY_PREFIX.length());
                out.add(p);
            }
        } catch (Throwable t) {
            Diag.w("CarProfileStore", t);
            return out;
        }
        sortBySeen(out);
        return out;
    }

    /**
     * 最后连接的那台车机；一台都没有时返回 {@code null}。
     *
     * <p>用途：连接前先猜「大概还是上次那台」，把它的 srcvers / 画布先摆上，
     * 车机真连上来再用 /info 上报值纠正。
     */
    public static CarProfile mostRecent(Context ctx) {
        List<CarProfile> all = list(ctx);
        return all.isEmpty() ? null : all.get(0);
    }

    // ---------------------------------------------------------------- 内部

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 键 = 前缀 + 规范化 MAC；MAC 为空/无效时返回 null（不该拿空串当归一类）。 */
    private static String keyOf(String mac) {
        String c = canonical(mac);
        return c == null ? null : KEY_PREFIX + c;
    }

    /**
     * 规范化 MAC：去空白、大写、统一成冒号分隔。
     *
     * <p>为什么不直接用 {@code BtIdentity.normalize}：那个函数是给「本机蓝牙地址」做严格校验的，
     * 会否掉全 0/全 F、组播位、本地管理位——这些在「本机地址」上是填错，但在「对端车机地址」
     * 上我们没有立场判定非法（车机 MAC 由系统/用户给出）。这里只做格式统一，让
     * {@code aa-bb-cc-dd-ee-ff}、{@code AABBCCDDEEFF} 和 {@code AA:BB:...} 落到同一个键上，
     * 不至于同一台车存出三条记录；实在认不出的形态就原样（trim + 大写）当键，
     * 宁可键难看，也不要因为格式不在预期内就丢掉记录。
     */
    private static String canonical(String mac) {
        if (mac == null) return null;
        String raw = mac.trim();
        if (raw.length() == 0) return null;
        String hex = raw.replaceAll("[:\\-\\s.]", "");
        if (hex.matches("[0-9A-Fa-f]{12}")) {
            String up = hex.toUpperCase();
            StringBuilder sb = new StringBuilder(17);
            for (int i = 0; i < 12; i += 2) {
                if (i > 0) sb.append(':');
                sb.append(up, i, i + 2);
            }
            return sb.toString();
        }
        return raw.toUpperCase();
    }

    /**
     * JSON → CarProfile；坏记录返回 null。
     *
     * <p>每个字段都用 {@code opt*} 带默认值读：老版本写下的记录缺字段时，
     * 缺的那个就走默认（autoFit 默认开、画布 0=未知），而不是整条丢掉。
     */
    private static CarProfile parse(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            CarProfile p = new CarProfile();
            p.srcvers = str(o, "srcvers");
            p.autoFit = o.optBoolean("autoFit", true);
            p.targetW = o.optInt("targetW", 0);
            p.targetH = o.optInt("targetH", 0);
            p.targetFps = o.optInt("targetFps", 0);
            p.lastModel = str(o, "lastModel");
            p.lastSeenAt = o.optLong("lastSeenAt", 0L);
            return p;
        } catch (Throwable t) {
            // 一条记录坏了只影响这一台车；留着它也没用，下次 save 会覆盖，
            // 但这里不做删除——读操作不该有写副作用。
            Diag.w("CarProfileStore", t);
            return null;
        }
    }

    /** 取字符串字段：显式处理 JSONObject.NULL，避免拿到字符串 "null"。 */
    private static String str(JSONObject o, String key) {
        if (o.isNull(key)) return null;
        String v = o.optString(key, null);
        return v == null || v.length() == 0 ? null : v;
    }

    /** 按最后连接时间倒序（同时间保持原有顺序，稳定）。匿名类写法，不用 lambda。 */
    private static void sortBySeen(List<CarProfile> items) {
        Collections.sort(items, new Comparator<CarProfile>() {
            @Override
            public int compare(CarProfile a, CarProfile b) {
                long x = a == null ? 0L : a.lastSeenAt;
                long y = b == null ? 0L : b.lastSeenAt;
                if (x == y) return 0;
                return x > y ? -1 : 1;   // 大的在前
            }
        });
    }
}
