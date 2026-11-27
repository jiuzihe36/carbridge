package com.carbridge.carplay.pairing;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Ed25519（RFC 8032）——AirPlay <b>pair-verify</b> 用的签名算法。
 */
public final class Ed25519 {

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    /**
     * 曲线阶 L（Ed25519 官方值，十六进制写死避免转写错误）：
     * {@code 1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ed}
     */
    public static final BigInteger L = new BigInteger(
            "1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ed", 16);
    private static final BigInteger D = BigInteger.valueOf(-121665).mod(P)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    /** 基点 B：y = 4/5，x 由曲线方程解出。 */
    private static final Point B = Point.base();

    private Ed25519() {
    }

    // ================================================================== API

    /**
     * 验签。
     *
     * @param signature 64 字节：R(32) || s(32)
     * @param publicKey 32 字节压缩公钥
     * @param message   被签的消息
     * @return true 表示签名有效
     */
    public static boolean verify(byte[] signature, byte[] publicKey, byte[] message) {
        try {
            if (signature == null || signature.length != 64) return false;
            if (publicKey == null || publicKey.length != 32) return false;
            Point r = Point.decode(java.util.Arrays.copyOfRange(signature, 0, 32));
            Point a = Point.decode(publicKey);
            if (r == null || a == null) return false;
            // ⚠️ 小阶点拒绝：cofactorless 方程下 identity 公钥可伪造任意签名
            //    （A=identity、s=1、R=[1]·B 就通过验签方程，已实证）。
            //    libsodium 思路：[8]·P == O 即小阶，覆盖全部 8 个点，不靠黑名单。
            if (isSmallOrder(r) || isSmallOrder(a)) return false;
            BigInteger s = le255(signature, 32);
            if (s.compareTo(L) >= 0) return false;      // s 必须 < L

            byte[] encR = java.util.Arrays.copyOfRange(signature, 0, 32);
            BigInteger h = challenge(encR, publicKey, message);
            Point sb = B.scalarMult(s);
            Point ha = a.scalarMult(h);
            Point rhs = r.add(ha);
            return sb.equals(rhs);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 小阶点判定：[8]·P == O（覆盖全部 8 个小阶点，含 identity）。 */
    private static boolean isSmallOrder(Point p) {
        return p.scalarMult(BigInteger.valueOf(8))
                .equals(new Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO));
    }

    /** 生成密钥对。 */
    public static KeyPair generate() {
        SecureRandom rnd = new SecureRandom();
        byte[] seed = new byte[32];
        rnd.nextBytes(seed);
        return keyPairFromSeed(seed);
    }

    /** 由 32 字节种子导出密钥对。 */
    public static KeyPair keyPairFromSeed(byte[] seed) {
        if (seed == null || seed.length != 32) throw new IllegalArgumentException("seed 必须 32 字节");
        BigInteger a = clamp(sha512(seed), 0, 32);
        Point pub = B.scalarMult(a);
        return new KeyPair(seed, pub.encode());
    }

    /** 用私钥签名（RFC 8032 的确定性签名）。 */
    public static byte[] sign(byte[] seed, byte[] message) {
        if (seed == null || seed.length != 32) throw new IllegalArgumentException("seed 必须 32 字节");
        byte[] h = sha512(seed);
        BigInteger a = clamp(h, 0, 32);
        byte[] prefix = java.util.Arrays.copyOfRange(h, 32, 64);
        byte[] pub = B.scalarMult(a).encode();

        // ⚠️ RFC 8032：r = SHA-512(prefix ‖ M) 按**完整 64 字节小端**再 mod L
        byte[] rHash = sha512(concat(prefix, message));
        BigInteger r = le512(rHash).mod(L);
        byte[] bigR = B.scalarMult(r).encode();

        BigInteger hh = challenge(bigR, pub, message);
        BigInteger s = r.add(hh.multiply(a)).mod(L);

        byte[] sig = new byte[64];
        System.arraycopy(bigR, 0, sig, 0, 32);
        le255To(s, sig, 32);
        return sig;
    }

    /** 公钥 + 私钥（种子）。 */
    public static final class KeyPair {
        public final byte[] seed;
        public final byte[] publicKey;

        KeyPair(byte[] seed, byte[] publicKey) {
            this.seed = seed;
            this.publicKey = publicKey;
        }
    }

    // ================================================================== 内部

    /**
     * h = SHA-512(R || A || M) mod L。
     *
     * ⚠️ RFC 8032 把摘要按**小端**整数解读（不是大端），且要用**全部 64 字节**。
     * 曾错写成 {@code new BigInteger(1, digest)}（大端），自签自验一致但
     * BouncyCastle/真车一律判无效 —— 实测 pair-setup M5 被 {STATE=6, ERROR=2} 拒。
     */
    private static BigInteger challenge(byte[] encR, byte[] encA, byte[] message) {
        MessageDigest md = sha512Digest();
        md.update(encR);
        md.update(encA);
        md.update(message);
        return le512(md.digest()).mod(L);
    }

    /** 任意长度字节按**小端**整数解读（RFC 8032 对 SHA-512 输出的解读方式）。 */
    private static BigInteger le512(byte[] b) {
        BigInteger v = BigInteger.ZERO;
        for (int i = b.length - 1; i >= 0; i--) {
            v = v.shiftLeft(8).or(BigInteger.valueOf(b[i] & 0xFFL));
        }
        return v;
    }

    /** clamp：按 RFC 8032 清零/置位后取 32 字节小端整数。 */
    private static BigInteger clamp(byte[] h, int off, int len) {
        byte[] c = java.util.Arrays.copyOfRange(h, off, off + len);
        c[0] = (byte) (c[0] & 248);   // RFC 钳位：清零低 3 位
        c[31] = (byte) (c[31] & 127); // RFC 钳位：清最高位
        c[31] = (byte) (c[31] | 64);  // RFC 钳位：置第二高位
        return le255(c, 0);
    }

    private static BigInteger le255(byte[] b, int off) {
        BigInteger v = BigInteger.ZERO;
        for (int i = 31; i >= 0; i--) {
            v = v.shiftLeft(8).or(BigInteger.valueOf(b[off + i] & 0xFFL));
        }
        return v;
    }

    private static void le255To(BigInteger v, byte[] out, int off) {
        for (int i = 0; i < 32; i++) {
            out[off + i] = v.shiftRight(8 * i).byteValue();
        }
    }

    private static byte[] sha512(byte[] data) {
        return sha512Digest().digest(data);
    }

    private static MessageDigest sha512Digest() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    /** 扩展齐次坐标点 (X:Y:Z:T)，x = X/Z, y = Y/Z, T = xy。 */
    static final class Point {
        final BigInteger x;
        final BigInteger y;
        final BigInteger z;
        final BigInteger t;

        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.t = t;
        }

        static Point base() {
            // y = 4/5 mod P；x 由 -x^2 + y^2 = 1 + d x^2 y^2 解出（取偶数 x）
            BigInteger y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
            BigInteger y2 = y.multiply(y).mod(P);
            BigInteger u = y2.subtract(BigInteger.ONE).mod(P);          // y^2 - 1
            BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);  // d*y^2 + 1
            BigInteger x2 = u.multiply(v.modInverse(P)).mod(P);
            BigInteger x = modSqrt(x2);
            if (x.testBit(0)) x = P.subtract(x);   // 取偶数
            return new Point(x, y, BigInteger.ONE, x.multiply(y).mod(P));
        }

        Point add(Point q) {
            BigInteger a = y.subtract(x).mod(P).multiply(q.y.subtract(q.x).mod(P)).mod(P);
            BigInteger b = y.add(x).mod(P).multiply(q.y.add(q.x).mod(P)).mod(P);
            BigInteger c = t.multiply(BigInteger.valueOf(2)).mod(P).multiply(D).mod(P)
                    .multiply(q.t).mod(P);
            BigInteger d = z.multiply(BigInteger.valueOf(2)).mod(P).multiply(q.z).mod(P);
            BigInteger e = b.subtract(a).mod(P);
            BigInteger f = d.subtract(c).mod(P);
            BigInteger g = d.add(c).mod(P);
            BigInteger h = b.add(a).mod(P);
            return new Point(
                    e.multiply(f).mod(P),
                    g.multiply(h).mod(P),
                    f.multiply(g).mod(P),
                    e.multiply(h).mod(P));
        }

        Point doublePoint() {
            BigInteger a = x.multiply(x).mod(P);
            BigInteger b = y.multiply(y).mod(P);
            BigInteger c = z.multiply(z).mod(P).multiply(BigInteger.valueOf(2)).mod(P);
            BigInteger h2 = a.add(b).mod(P);
            BigInteger xpy = x.add(y).mod(P);
            BigInteger e = h2.subtract(xpy.multiply(xpy).mod(P)).mod(P);
            BigInteger g = a.subtract(b).mod(P);
            BigInteger f = c.add(g).mod(P);
            return new Point(
                    e.multiply(f).mod(P),
                    g.multiply(h2).mod(P),
                    f.multiply(g).mod(P),
                    e.multiply(h2).mod(P));
        }

        Point scalarMult(BigInteger k) {
            Point result = new Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);
            Point addend = this;
            for (int i = 0; i < k.bitLength(); i++) {
                if (k.testBit(i)) result = result.add(addend);
                addend = addend.doublePoint();
            }
            return result;
        }

        /** 32 字节压缩编码：y 小端 + x 的最高位（符号位）。 */
        byte[] encode() {
            BigInteger invZ = z.modInverse(P);
            BigInteger yy = y.multiply(invZ).mod(P);
            BigInteger xx = x.multiply(invZ).mod(P);
            byte[] out = new byte[32];
            le255To(yy, out, 0);
            if (xx.testBit(0)) out[31] |= (byte) 0x80;
            return out;
        }

        /** 解压 32 字节编码；非法返回 null。 */
        static Point decode(byte[] enc) {
            if (enc == null || enc.length != 32) return null;
            byte[] copy = enc.clone();
            boolean sign = (copy[31] & 0x80) != 0;
            copy[31] &= 0x7F;
            BigInteger yy = le255(copy, 0);
            if (yy.compareTo(P) >= 0) return null;
            // x^2 = (y^2 - 1) / (d*y^2 + 1)
            BigInteger y2 = yy.multiply(yy).mod(P);
            BigInteger num = y2.subtract(BigInteger.ONE).mod(P);
            BigInteger den = D.multiply(y2).add(BigInteger.ONE).mod(P);
            BigInteger xx = num.multiply(den.modInverse(P)).mod(P);
            BigInteger x = modSqrt(xx);
            if (x == null) return null;
            if (x.testBit(0) != sign) x = P.subtract(x);
            return new Point(x, yy, BigInteger.ONE, x.multiply(yy).mod(P));
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Point)) return false;
            Point q = (Point) o;
            // 比较射影坐标：X1*Z2 == X2*Z1 且 Y1*Z2 == Y2*Z1
            return x.multiply(q.z).mod(P).equals(q.x.multiply(z).mod(P))
                    && y.multiply(q.z).mod(P).equals(q.y.multiply(z).mod(P));
        }

        @Override
        public int hashCode() {
            return x.hashCode() ^ y.hashCode();
        }

        /**
         * 模平方根（p ≡ 5 mod 8）：先试 a^((p+3)/8)。
         *
         * ⚠️ 该指数可能给出的是 **sqrt(−a)** 而非 sqrt(a)（实测 4 就会得 −4），
         * 因此要再乘 sqrt(−1) 纠正；两者都不是则 a 不是二次剩余。
         */
        static BigInteger modSqrt(BigInteger a) {
            BigInteger e = P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8));
            BigInteger r = a.modPow(e, P);
            BigInteger target = a.mod(P);
            if (r.multiply(r).mod(P).equals(target)) return r;
            BigInteger sqrtNegOne = BigInteger.valueOf(2)
                    .modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P);
            BigInteger r2 = r.multiply(sqrtNegOne).mod(P);
            if (r2.multiply(r2).mod(P).equals(target)) return r2;
            return null;
        }
    }
}
