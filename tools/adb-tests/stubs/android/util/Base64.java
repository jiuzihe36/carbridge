package android.util;

/** JVM 替身：android.util.Base64（只覆盖 AdbClient 用到的 decode）。 */
public final class Base64 {
    public static final int DEFAULT = 0;

    public static byte[] decode(String s, int flags) {
        return java.util.Base64.getMimeDecoder().decode(s);
    }

    private Base64() {
    }
}
