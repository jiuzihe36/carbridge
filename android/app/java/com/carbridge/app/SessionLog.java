package com.carbridge.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import com.carbridge.carplay.util.Diag;

/**
 * 会话日志：内存环形缓冲 + 落盘文件。
 *
 * <p>从 MainActivity 抽出来的原因：原来这套逻辑和界面/连接/诊断混在一个 2500 行的类里，
 * 任何一处改日志行为都要动那个巨类。现在它只关心「收一行、留得住、别撑爆」。
 *
 * <p>三条硬约束（都是踩过的坑）：
 * <ul>
 *   <li><b>内存封顶</b>：长时间跑不能一直涨，超过 {@link #MEM_CHARS} 丢掉最旧一半；</li>
 *   <li><b>文件封顶</b>：超过 {@link #FILE_MAX} 砍掉最旧的一半（保留最近的现场）；</li>
 *   <li><b>绝不反噬</b>：写盘失败只记一次 logcat，不抛异常、不递归调用自己。</li>
 * </ul>
 */
public final class SessionLog {

    /** UI 一次渲染多少行（只渲染尾部，避免每行都刷新整段文本）。 */
    public static final int UI_TAIL = 250;

    private static final int MEM_CHARS = 120 * 1024;
    private static final long FILE_MAX = 2L * 1024 * 1024;
    private static final String FILE_NAME = "carbridge-session.log";

    private final List<String> lines = new ArrayList<String>();
    private int chars;
    private final File file;

    private FileOutputStream out;
    private boolean openTried;
    private boolean writeErrorNoted;

    public SessionLog(Context ctx) {
        File dir = null;
        try {
            dir = ctx.getExternalFilesDir(null);
        } catch (Throwable tDiag) {
            Diag.w("SessionLog.SessionLog", tDiag);
        }
        this.file = new File(dir == null ? ctx.getFilesDir() : dir, FILE_NAME);
    }

    /** 日志文件路径（诊断包导出、界面提示都用它）。 */
    public File file() {
        return file;
    }

    /** 追加一行（调用方已经带好时间戳）。 */
    public void append(String entry) {
        if (entry == null) return;
        writeFile(entry);
        synchronized (lines) {
            lines.add(entry);
            chars += entry.length() + 1;
            if (chars > MEM_CHARS) {
                int drop = lines.size() / 2;
                for (int i = 0; i < drop; i++) {
                    chars -= lines.remove(0).length() + 1;
                }
            }
        }
    }

    /** 尾部 n 行（UI 用）。 */
    public String tail(int n) {
        synchronized (lines) {
            int from = Math.max(0, lines.size() - n);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
            return sb.toString();
        }
    }

    /** 全量（长按复制用）。 */
    public String all() {
        return tail(Integer.MAX_VALUE);
    }

    /** 搜索结果：匹配行拼接文本 + 统计（UI 用，不落盘）。 */
    public static final class SearchResult {
        public final String text;
        public final int matches;
        public final int scanned;

        SearchResult(String text, int matches, int scanned) {
            this.text = text;
            this.matches = matches;
            this.scanned = scanned;
        }
    }

    /**
     * 关键字搜索（大小写不敏感，扫描整个内存缓冲）。逻辑在 {@link LogSearch}（可单测）。
     *
     * @param maxMatches 最多返回多少条匹配行（防止一次渲染几十万字符）
     */
    public SearchResult search(String query, int maxMatches) {
        LogSearch.Result r;
        synchronized (lines) {
            r = LogSearch.search(lines, query, maxMatches);
        }
        return new SearchResult(r.text, r.matches, r.scanned);
    }

    /** 把内存缓冲整体补写进文件（文件缺失/被清空时用，保证导出/分享必有内容）。 */
    public void dumpToFile() {
        String all = all();
        if (all.isEmpty()) return;
        synchronized (this) {
            try {
                if (out == null) {
                    File parent = file.getParentFile();
                    if (parent != null && !parent.isDirectory()) parent.mkdirs();
                    out = new FileOutputStream(file, true);
                }
                out.write(all.getBytes("UTF-8"));
                out.flush();
            } catch (Throwable t) {
                com.carbridge.carplay.util.Diag.w("SessionLog.dump", t);
            }
        }
    }

    /** 当前缓存行数（诊断用）。 */
    public int size() {
        synchronized (lines) {
            return lines.size();
        }
    }

    // ------------------------------------------------------------------ 落盘

    private void writeFile(String entry) {
        synchronized (this) {
            try {
                if (out == null && !openTried) {
                    openTried = true;
                    if (file.isFile() && file.length() > FILE_MAX) truncateHead();
                    File parent = file.getParentFile();
                    if (parent != null && !parent.isDirectory()) parent.mkdirs();
                    out = new FileOutputStream(file, true);
                }
                if (out != null) {
                    out.write((entry + "\n").getBytes("UTF-8"));
                    out.flush();
                }
            } catch (Throwable t) {
                // 日志管线绝不能反过来弄挂业务线程；但也**不能完全静默**：
                // 文件写不动 = 现场导出的是残缺日志，必须留一条痕迹（只留一次，避免刷屏）。
                if (!writeErrorNoted) {
                    writeErrorNoted = true;
                    try {
                        android.util.Log.w("carbridge", "日志文件写入失败，后续仍会写内存缓冲: " + t);
                    } catch (Throwable tDiag) {
                        Diag.w("SessionLog.writeFile", tDiag);
                    }
                }
            }
        }
    }

    /** 砍掉文件最旧的一半，保留最近的现场。 */
    private void truncateHead() {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long len = raf.length();
            long keep = len / 2;
            byte[] tail = new byte[(int) keep];
            raf.seek(len - keep);
            raf.readFully(tail);
            raf.setLength(0);
            raf.seek(0);
            raf.write("…（日志超过上限，已丢弃较早的一半）…\n".getBytes("UTF-8"));
            raf.write(tail);
        } catch (Throwable t) {
            com.carbridge.carplay.util.Diag.w("SessionLog.truncate", t);
        }
    }

    /** 关闭文件句柄（Activity 销毁时调用）。 */
    public void close() {
        synchronized (this) {
            try {
                if (out != null) out.close();
            } catch (Throwable tDiag) {
                Diag.w("SessionLog.close", tDiag);
            }
            out = null;
        }
    }
}
