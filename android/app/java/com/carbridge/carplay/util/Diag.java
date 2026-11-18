package com.carbridge.carplay.util;

/**
 * 异常记录器：把「以前被空 catch 吞掉」的异常收敛成可观测的日志。
 *
 * <p>规则：
 * <ul>
 *   <li>同一个 tag 第一次出现时打完整栈（logcat），之后只累计次数；</li>
 *   <li>不抛异常、不做 IO，所以可以安全地放在任何 catch 里；</li>
 *   <li>次数可以通过 {@link #count(String)} 查到，便于在诊断包里报告「本轮吞了多少次」。</li>
 * </ul>
 */
public final class Diag {

    private static final java.util.Set<String> SEEN =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static final java.util.Map<String, Integer> COUNTS =
            new java.util.concurrent.ConcurrentHashMap<String, Integer>();

    private Diag() {
    }

    /** 记一次被吞的异常（同 tag 只打首个栈）。 */
    public static void w(String tag, Throwable t) {
        Integer old = COUNTS.get(tag);
        COUNTS.put(tag, old == null ? 1 : old + 1);
        String key = tag + "|" + (t == null ? "null"
                : t.getClass().getName() + ":" + t.getMessage());
        if (SEEN.add(key)) {
            try {
                android.util.Log.w("carbridge", "[" + tag + "] " + t, t);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 某个 tag 被吞了多少次（诊断包用）。 */
    public static int count(String tag) {
        Integer v = COUNTS.get(tag);
        return v == null ? 0 : v;
    }

    /** 全部计数，格式 {@code tag=次数}。 */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : COUNTS.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.length() == 0 ? "(无)" : sb.toString();
    }
}
