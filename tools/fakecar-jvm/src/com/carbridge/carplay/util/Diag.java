package com.carbridge.carplay.util;

/**
 * JVM 测试台用的 {@code Diag} 替身。
 *
 * <p>产品代码里的 {@code Diag} 依赖 {@code android.util.Log}，把 iap2 整包搬上 JVM 会编不过；
 * 按本仓库惯例放一个同包同名的空替身，而不是去改产品代码（改了就没有 logcat 了）。
 */
public final class Diag {
    private Diag() {
    }

    public static void w(String tag, Throwable t) {
        // 测试台不关心这类告警
    }

    public static int count(String tag) {
        return 0;
    }

    public static String dump() {
        return "";
    }
}
