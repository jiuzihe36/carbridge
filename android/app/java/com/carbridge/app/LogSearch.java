package com.carbridge.app;

import java.util.List;
import java.util.Locale;

/**
 * 日志关键字搜索的**纯逻辑**（无 Android 依赖，可 JVM 单测）。
 *
 * <p>{@link SessionLog#search} 只是把内存缓冲交给它。
 */
public final class LogSearch {

    /** 搜索结果：匹配行拼接文本 + 统计。 */
    public static final class Result {
        public final String text;
        public final int matches;
        public final int scanned;

        Result(String text, int matches, int scanned) {
            this.text = text;
            this.matches = matches;
            this.scanned = scanned;
        }
    }

    private LogSearch() {
    }

    /**
     * 大小写不敏感的子串搜索。
     *
     * @param maxMatches 最多返回多少条匹配行（防止一次渲染几十万字符）
     */
    public static Result search(List<String> lines, String query, int maxMatches) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
        if (q.isEmpty() || lines == null) return new Result("", 0, 0);
        StringBuilder sb = new StringBuilder();
        int matches = 0;
        int scanned = 0;
        for (String line : lines) {
            if (line == null) continue;
            scanned++;
            if (line.toLowerCase(Locale.US).contains(q)) {
                if (matches < maxMatches) sb.append(line).append('\n');
                matches++;
            }
        }
        return new Result(sb.toString(), matches, scanned);
    }
}
