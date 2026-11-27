package com.carbridge.carplay.util;

/** JVM 替身：Diag（App 里走 android.util.Log）。 */
public final class Diag {
    public static void w(String tag, Throwable t) {
        System.out.println("  [Diag." + tag + "] " + t);
    }

    public static void count(String tag) {
        System.out.println("  [Diag.count] " + tag);
    }

    private Diag() {
    }
}
