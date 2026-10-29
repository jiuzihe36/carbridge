package com.carbridge.carplay.pairing;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * X25519（RFC 7748）——AirPlay <b>pair-verify</b> 的密钥协商。
 */
public final class X25519 {

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);
    private static final BigInteger NINE = BigInteger.valueOf(9);
    private static final BigInteger TWO = BigInteger.valueOf(2);

    private static final SecureRandom RANDOM = new SecureRandom();

    private X25519() {
    }

    /** 生成 32 字节临时私钥。 */
    public static byte[] generatePrivateKey() {
        byte[] k = new byte[32];
        RANDOM.nextBytes(k);
        return clamp(k);
    }

    /** 由私钥算公钥：X25519(k, 9)。 */
    public static byte[] publicKey(byte[] privateKey) {
        return scalarMult(clamp(privateKey), NINE);
    }

    /** 共享秘密：X25519(k, peerPublic)。 */
    public static byte[] sharedSecret(byte[] privateKey, byte[] peerPublic) {
        return scalarMult(clamp(privateKey), leToBig(peerPublic));
    }

    /** clamp：RFC 7748 §5。 */
    public static byte[] clamp(byte[] k) {
        byte[] c = k.clone();
        c[0] &= 248;
        c[31] &= 127;
        c[31] |= 64;
        return c;
    }

    /**
     * Montgomery ladder（RFC 7748 §5）。
     *
     * @param scalar 已 clamp 的 32 字节私钥
     * @param u      对端 u 坐标
     */
    static byte[] scalarMult(byte[] scalar, BigInteger u) {
        BigInteger x1 = u;
        BigInteger x2 = BigInteger.ONE;
        BigInteger z2 = BigInteger.ZERO;
        BigInteger x3 = u;
        BigInteger z3 = BigInteger.ONE;
        int swap = 0;

        for (int t = 254; t >= 0; t--) {
            int kt = (scalar[t >>> 3] >>> (t & 7)) & 1;
            swap ^= kt;
            if (swap == 1) {
                BigInteger tmp = x2; x2 = x3; x3 = tmp;
                tmp = z2; z2 = z3; z3 = tmp;
            }
            swap = kt;

            BigInteger a = x2.add(z2).mod(P);
            BigInteger aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P);
            BigInteger bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P);
            BigInteger d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P);
            BigInteger cb = c.multiply(b).mod(P);
            BigInteger t3 = da.add(cb).mod(P);
            x3 = t3.multiply(t3).mod(P);
            z3 = x1.multiply(da.subtract(cb).mod(P).multiply(da.subtract(cb).mod(P)).mod(P)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e).mod(P)).mod(P)).mod(P);
        }
        if (swap == 1) {
            BigInteger tmp = x2; x2 = x3; x3 = tmp;
            tmp = z2; z2 = z3; z3 = tmp;
        }
        BigInteger invZ = z2.modInverse(P);
        return bigToLe(x2.multiply(invZ).mod(P));
    }

    /** 大端字节串 → BigInteger（小端输入）。 */
    static BigInteger leToBig(byte[] b) {
        BigInteger v = BigInteger.ZERO;
        for (int i = b.length - 1; i >= 0; i--) {
            v = v.shiftLeft(8).or(BigInteger.valueOf(b[i] & 0xFFL));
        }
        return v;
    }

    /** BigInteger → 32 字节小端。 */
    static byte[] bigToLe(BigInteger v) {
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            out[i] = v.shiftRight(8 * i).byteValue();
        }
        return out;
    }

    /** 便于测试：直接算 X25519(scalar, u)，scalar/u 都是小端字节。 */
    public static byte[] x25519(byte[] scalar, byte[] u) {
        return scalarMult(clamp(scalar), leToBig(u));
    }
}
