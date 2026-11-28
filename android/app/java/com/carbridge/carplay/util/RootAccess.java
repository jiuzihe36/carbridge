package com.carbridge.carplay.util;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/**
 * root 可用性探测（SukiSU / KernelSU / Magisk 通用）。
 *
 * <p>为什么需要单独一层：应用在 Android 10+ 读不到 {@code /proc/net/tcp}
 * （SELinux），拿 root 才读得到 —— 有了它，「等 Car+ 六个端口齐」代替「盲等 8 秒」，
 * 冷启动首帧能快一截。但 root 授权是**一次性弹框**：应用第一次 exec su 时
 * SukiSU 才会弹「是否授权」，用户不点就一直挂着 → 必须主动触发一次并等够时间，
 * 不能按「1500ms 没输出就永久放弃」处理。
 *
 * <p>状态机：
 * <ul>
 *   <li>{@link #UNKNOWN} 还没试过 / 上次是弹框超时（允许稍后重试）；</li>
 *   <li>{@link #AVAILABLE} su 成功；</li>
 *   <li>{@link #DENIED} su 明确被拒（输出 Permission denied）—— 60 秒内不重试，
 *       避免每次轮询都 fork 一次 su；60 秒后允许再试（用户可能在授权框里点了允许）。</li>
 * </ul>
 */
public final class RootAccess {

    public static final int UNKNOWN = 0;
    public static final int AVAILABLE = 1;
    public static final int DENIED = -1;

    /** 弹框等待上限：SukiSU 的授权框要人点，给 4 秒（纯可选项，不值得为它多等）。 */
    private static final long PROMPT_TIMEOUT_MS = 4000L;
    /** 被拒后的冷却期：冷却期内不 fork su（轮询很密）。 */
    private static final long DENIED_COOLDOWN_MS = 60_000L;

    private static volatile int state = UNKNOWN;
    private static volatile String detail = "";
    private static volatile long lastAttemptAt;
    private static volatile boolean promptShownOnce;

    private RootAccess() {
    }

    public static int state() {
        return state;
    }

    public static String detail() {
        return detail;
    }

    /** 候选 su 路径：先查存在性；查不到也不能放弃 —— 直接按候选逐个 exec。 */
    private static final String[] SU_PATHS = {
            "/system/bin/su", "/debug_ramdisk/su", "/data/adb/ksu/bin/su",
            "/sbin/su", "/system/xbin/su", "/data/adb/magisk/su",
            "/system_ext/bin/su", "/product/bin/su"};

    /** 找一个能用的 su 绝对路径（应用进程 PATH 不一定含 /system/bin）。 */
    public static String suBinary() {
        for (String cand : SU_PATHS) {
            try {
                if (new File(cand).exists()) return cand;
            } catch (Throwable tDiag) {
                Diag.w("RootAccess.suBinary", tDiag);
            }
        }
        return "su";
    }

    /**
     * 启动一条 su 命令。**不依赖 File.exists()**：真机实测 App 进程 stat
     * /system/bin/su 会返回不存在（SELinux/挂载命名空间），但直接 exec 该路径是可以的 ——
     * 所以按候选表逐个尝试，ENOENT 才换下一个。
     */
    private static Process start(String cmd) throws java.io.IOException {
        java.io.IOException last = null;
        String[] cands = new String[SU_PATHS.length + 1];
        System.arraycopy(SU_PATHS, 0, cands, 0, SU_PATHS.length);
        cands[SU_PATHS.length] = "su";
        for (String cand : cands) {
            try {
                return Runtime.getRuntime().exec(new String[]{cand, "-c", cmd});
            } catch (java.io.IOException ioe) {
                last = ioe;
                if (!String.valueOf(ioe.getMessage()).contains("No such file")) {
                    throw ioe;   // 不是「找不到」，没必要换路径（多为权限/被拒）
                }
            }
        }
        if (last != null) throw last;
        throw new java.io.IOException("没有可用的 su");
    }

    /** 现在能不能尝试一次 su（被拒后的冷却检查）。 */
    public static synchronized boolean mayAttempt() {
        if (state == AVAILABLE) return true;
        long now = System.currentTimeMillis();
        if (state == DENIED && now - lastAttemptAt < DENIED_COOLDOWN_MS) return false;
        return true;
    }

    /**
     * 执行一次 {@code su -c id}：成功 → AVAILABLE；被拒 → DENIED；超时（多半是
     * 授权框没人点）→ 保持 UNKNOWN，下次再试。返回最新状态。
     */
    public static synchronized int probe(Context ctx) {
        if (state == AVAILABLE) return state;
        long now = System.currentTimeMillis();
        if (state == DENIED && now - lastAttemptAt < DENIED_COOLDOWN_MS) return state;
        lastAttemptAt = now;
        Process p = null;
        try {
            p = start("id");
            final Process fp = p;
            final ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    InputStream in = fp.getInputStream();
                    byte[] buf = new byte[512];
                    int n;
                    try {
                        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    } catch (Throwable tDiag) {
                        Diag.w("RootAccess.probe", tDiag);
                    }
                }
            }, "root-probe");
            reader.setDaemon(true);
            reader.start();
            boolean done = p.waitFor(PROMPT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                try { p.destroy(); } catch (Throwable tDiag) { Diag.w("RootAccess.probe.kill", tDiag); }
                // 授权框没人点：不算「被拒」，允许下次再试
                detail = "授权框未响应（" + (PROMPT_TIMEOUT_MS / 1000) + "s）";
                if (!promptShownOnce && ctx != null) {
                    promptShownOnce = true;
                }
                return state;
            }
            reader.join(500);
            String out = bos.toString("UTF-8");
            if (out != null && out.contains("uid=0")) {
                state = AVAILABLE;
                detail = "su 可用";
                return state;
            }
            if (out != null && out.toLowerCase(java.util.Locale.US).contains("denied")) {
                state = DENIED;
                detail = "su 被拒（请在 SukiSU 里给本应用授权）";
                return state;
            }
            state = DENIED;
            detail = "su 无输出（" + (out == null ? "" : out.trim()) + "）";
            return state;
        } catch (Throwable t) {
            state = DENIED;
            detail = "su 执行失败：" + t;
            return state;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable tDiag) { Diag.w("RootAccess.probe#2", tDiag); }
        }
    }

    /** 跑一条 root 命令，返回 stdout（失败/超时返回 null）。调用前应 probe 过。 */
    public static String exec(String cmd, long timeoutMs) {
        Process p = null;
        try {
            p = start(cmd);
            final Process fp = p;
            final ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    InputStream in = fp.getInputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    try {
                        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    } catch (Throwable tDiag) {
                        Diag.w("RootAccess.exec", tDiag);
                    }
                }
            }, "root-exec");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                try { p.destroy(); } catch (Throwable tDiag) { Diag.w("RootAccess.exec.kill", tDiag); }
                return null;
            }
            reader.join(500);
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable tDiag) { Diag.w("RootAccess.exec#2", tDiag); }
        }
    }
}
