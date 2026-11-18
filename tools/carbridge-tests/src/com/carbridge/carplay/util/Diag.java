package com.carbridge.carplay.util;

/**
 * 测试专用替身：产品代码里的 {@code Diag} 依赖 android.util.Log，
 * JVM 测试工程编不了 Android，所以这里提供一个同名的空实现。
 *
 * <p>只用于 {@code tools/carbridge-tests} 的编译；它不会被装进 APK
 * （APK 构建编的是 android/app/java 下的真实现）。
 */
public final class Diag {

    private Diag() {
    }

    public static void w(String tag, Throwable t) {
        // 测试里不关心被吞的异常
    }

    public static int count(String tag) {
        return 0;
    }

    public static String dump() {
        return "(test)";
    }
}
