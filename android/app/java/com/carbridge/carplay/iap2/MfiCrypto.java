package com.carbridge.carplay.iap2;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * MFi 配件认证的密码学：X.509 证书解析 + RSA-1024 验签。
 */
public final class MfiCrypto {

    /** RSA 公钥。 */
    public static final class RsaKey {
        public final BigInteger modulus;   // n
        public final BigInteger exponent;  // e（通常 65537）
        public RsaKey(BigInteger n, BigInteger e) {
            this.modulus = n;
            this.exponent = e;
        }

        @Override
        public String toString() {
            return "RSA-" + modulus.bitLength() + "/e=" + exponent;
        }
    }

    private MfiCrypto() {
    }

    // ================================================================== 证书解析

    /**
     * 从 MFi 证书（PKCS#7 SignedData / 裸 X.509 都兼容）里提取 RSA 公钥。
     *
     * <p>实现策略（鲁棒，不依赖精确结构位置）：递归遍历整个 DER，找出所有
     * 「SEQUENCE 内含两个 INTEGER（第一个 128B=1024bit 模数、第二个小指数）」的
     * RSAPublicKey 结构，返回模数最大的那一个（最可能是真正的公钥）。
     *
     * @return 公钥，或 null（解析不出 RSA 公钥，可能不是 RSA 证书）
     */
    public static RsaKey parseCertificate(byte[] der) {
        if (der == null || der.length < 16) return null;
        List<RsaKey> found = new ArrayList<RsaKey>();
        try {
            collectRsaKeys(der, 0, der.length, found, 0);
        } catch (Throwable ignored) {
        }
        RsaKey best = null;
        for (RsaKey k : found) {
            if (best == null || k.modulus.bitLength() > best.modulus.bitLength()) best = k;
        }
        return best;
    }

    /** 递归遍历 DER，收集所有 RSAPublicKey（SEQUENCE{INTEGER n, INTEGER e}）。 */
    private static void collectRsaKeys(byte[] der, int off, int end, List<RsaKey> out, int depth) {
        if (depth > 16) return;
        while (off + 2 <= end) {
            int tag = der[off] & 0xFF;
            Len len = readLength(der, off + 1);
            if (len == null || len.valueEnd > end) return;
            if ((tag & 0x20) != 0) {   // constructed
                if (tag == 0x30) {
                    RsaKey k = tryParseRsaKey(der, off, len.valueEnd);
                    if (k != null) out.add(k);
                }
                collectRsaKeys(der, len.valueStart, len.valueEnd, out, depth + 1);
            } else if (tag == 0x03 && len.valueStart < len.valueEnd) {
                // BIT STRING（subjectPublicKey）：跳过 1 字节 unused-bits 计数后是 RSAPublicKey DER
                RsaKey k = tryParseRsaKey(der, len.valueStart + 1, len.valueEnd);
                if (k != null) out.add(k);
            }
            off = len.valueEnd;
        }
    }

    /** 尝试把 [off,end) 解析成 RSAPublicKey SEQUENCE{INTEGER n, INTEGER e}。 */
    private static RsaKey tryParseRsaKey(byte[] der, int off, int end) {
        try {
            int p = off;
            if ((der[p++] & 0xFF) != 0x30) return null;
            Len outer = readLength(der, p);
            if (outer == null || outer.valueEnd != end) return null;
            p = outer.valueStart;
            // 第一个 INTEGER = n（1024-bit 模数）
            if ((der[p++] & 0xFF) != 0x02) return null;
            Len nl = readLength(der, p);
            if (nl == null) return null;
            int nLen = nl.valueEnd - nl.valueStart;
            if (nLen < 120 || nLen > 132) return null;   // 128/129 字节
            BigInteger n = new BigInteger(1, der, nl.valueStart, nLen);
            p = nl.valueEnd;
            // 第二个 INTEGER = e（小指数）
            if ((der[p++] & 0xFF) != 0x02) return null;
            Len el = readLength(der, p);
            if (el == null) return null;
            int eLen = el.valueEnd - el.valueStart;
            if (eLen < 1 || eLen > 8) return null;
            BigInteger e = new BigInteger(1, der, el.valueStart, eLen);
            if (el.valueEnd != end) return null;   // 必须恰好两个 INTEGER
            return new RsaKey(n, e);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** ASN.1 长度字段。 */
    private static final class Len {
        final int valueStart;
        final int valueEnd;
        Len(int s, int e) {
            this.valueStart = s;
            this.valueEnd = e;
        }
    }

    /** 读 off 处的长度字段，返回 value 的起止偏移；越界返回 null。 */
    private static Len readLength(byte[] der, int off) {
        if (off >= der.length) return null;
        int b = der[off] & 0xFF;
        int p = off + 1;
        int len;
        if (b < 0x80) {
            len = b;
        } else {
            int n = b & 0x7F;
            if (p + n > der.length) return null;
            len = 0;
            for (int i = 0; i < n; i++) len = (len << 8) | (der[p++] & 0xFF);
        }
        if (p + len > der.length) return null;
        return new Len(p, p + len);
    }

    // ================================================================== RSA 验签

    /**
     * 验证 0xAA03 的签名（PKCS#1 v1.5 + SHA-1）。
     *
     * @param challenge 20 字节挑战
     * @param signature 128 字节 RSA 签名
     * @param key       从证书解析出的公钥
     */
    public static boolean verifySignature(byte[] challenge, byte[] signature, RsaKey key) {
        if (challenge == null || signature == null || key == null) return false;
        try {
            BigInteger m = new BigInteger(1, signature).modPow(key.exponent, key.modulus);
            int k = (key.modulus.bitLength() + 7) / 8;
            byte[] em = new byte[k];
            byte[] raw = m.toByteArray();
            int src = Math.max(0, raw.length - k);
            int dst = k - Math.min(k, raw.length);
            System.arraycopy(raw, src, em, dst, Math.min(k, raw.length));
            // 找 DigestInfo：00 01 FF..FF 00 <DigestInfo>
            if (em[0] != 0x00 || em[1] != 0x01) return false;
            int p = 2;
            while (p < em.length && em[p] == (byte) 0xFF) p++;
            if (p >= em.length || em[p] != 0x00) return false;
            int diStart = p + 1;
            // DigestInfo 末尾 20 字节是 SHA-1(challenge)
            if (em.length - diStart < 20 + 15) return false;
            byte[] digest = new byte[20];
            System.arraycopy(em, em.length - 20, digest, 0, 20);
            byte[] expected = MessageDigest.getInstance("SHA-1").digest(challenge);
            return MessageDigest.isEqual(digest, expected);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
