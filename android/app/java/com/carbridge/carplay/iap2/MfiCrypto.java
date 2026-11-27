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
        } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("MfiCrypto", t);
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
        } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("MfiCrypto", t);
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
            if (n > 3 || p + n > der.length) return null;   // DER 长度最多 3 字节（<16MB）；大值视为畸形
            len = 0;
            for (int i = 0; i < n; i++) len = (len << 8) | (der[p++] & 0xFF);
            if (len < 0) return null;                        // int 溢出（0x84 7F FF FF 会变负数）
        }
        if (p + len > der.length) return null;
        return new Len(p, p + len);
    }

    // ================================================================== EC（P-256）

    /** 从证书里取 EC 公钥（SubjectPublicKeyInfo）。很多车机（含 Apple MFi 近年）用的是 ECC。 */
    public static java.security.PublicKey parseEcPublicKey(byte[] der) {
        if (der == null || der.length < 32) return null;
        // id-ecPublicKey OID: 1.2.840.10045.2.1 → 06 07 2A 86 48 CE 3D 02 01
        byte[] oid = {0x06, 0x07, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x02, 0x01};
        for (int i = 0; i + oid.length < der.length; i++) {
            if (der[i] != oid[0]) continue;
            boolean hit = true;
            for (int k = 0; k < oid.length; k++) {
                if (der[i + k] != oid[k]) { hit = false; break; }
            }
            if (!hit) continue;
            // SPKI 从包住这个 OID 的 SEQUENCE(0x30) 开始：向前找最近的 0x30，且其长度覆盖 OID
            for (int back = i - 1; back >= 0 && back > i - 8; back--) {
                if ((der[back] & 0xFF) != 0x30) continue;
                Len len = readLength(der, back + 1);
                if (len == null || len.valueEnd <= i || len.valueEnd > der.length) continue;
                try {
                    byte[] spki = new byte[len.valueEnd - back];
                    System.arraycopy(der, back, spki, 0, spki.length);
                    return java.security.KeyFactory.getInstance("EC")
                            .generatePublic(new java.security.spec.X509EncodedKeySpec(spki));
                } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("MfiCrypto", t);
                    // 继续向前找
                }
            }
        }
        return null;
    }

    /** ECDSA 验签（MFi ECC 身份用 SHA-256；老一点的用 SHA-1，两个都试）。
     *  ⚠️ 签名形式两种都收：DER SEQUENCE（Java/OpenSSL 惯例）与 64B raw r||s
     *  （WebCrypto/BoringSSL 惯例）。以前只认 DER，raw 签名的车机在严格模式下会误拒。 */
    public static boolean verifyEcSignature(byte[] challenge, byte[] signature, java.security.PublicKey key) {
        if (challenge == null || signature == null || key == null) return false;
        byte[][] forms;
        if (signature.length == 64) {
            byte[] der = ecRawToDer(signature);
            forms = der != null ? new byte[][]{der, signature} : new byte[][]{signature};
        } else {
            forms = new byte[][]{signature};
        }
        for (String alg : new String[]{"SHA256withECDSA", "SHA1withECDSA"}) {
            for (byte[] form : forms) {
                try {
                    java.security.Signature v = java.security.Signature.getInstance(alg);
                    v.initVerify(key);
                    v.update(challenge);
                    if (v.verify(form)) return true;
                } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("MfiCrypto", t);
                }
            }
        }
        return false;
    }

    /** 64B raw r||s（各 32B 大端）→ DER SEQUENCE{r,s}。失败返回 null。 */
    private static byte[] ecRawToDer(byte[] raw) {
        try {
            byte[] r = derInteger(raw, 0, 32);
            byte[] s = derInteger(raw, 32, 32);
            int len = r.length + s.length;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(len + 4);
            out.write(0x30);
            if (len < 0x80) {
                out.write(len);
            } else {
                out.write(0x81);
                out.write(len);
            }
            out.write(r, 0, r.length);
            out.write(s, 0, s.length);
            return out.toByteArray();
        } catch (Throwable t) {
            com.carbridge.carplay.util.Diag.w("MfiCrypto", t);
            return null;
        }
    }

    /** 把大端无符号整数（span）编成 DER INTEGER：去前导零、最高位为 1 时补 0x00。 */
    private static byte[] derInteger(byte[] src, int off, int len) {
        int start = off;
        int end = off + len;
        while (start < end - 1 && src[start] == 0) start++;
        boolean pad = (src[start] & 0x80) != 0;
        int body = end - start + (pad ? 1 : 0);
        byte[] out = new byte[2 + body];
        out[0] = 0x02;
        out[1] = (byte) body;
        int p = 2;
        if (pad) out[p++] = 0x00;
        System.arraycopy(src, start, out, p, end - start);
        return out;
    }

    /**
     * 取证书有效期（notBefore, notAfter，epoch ms）。找不到返回 null。
     * 不追求完整 ASN.1：直接扫前两个 UTCTime(0x17) / GeneralizedTime(0x18)，
     * 顺序就是 notBefore、notAfter —— 对「证书是否过期/是不是占位证书」够用。
     */
    public static long[] certValidity(byte[] der) {
        if (der == null) return null;
        try {
            long[] out = new long[2];
            int got = 0;
            for (int i = 0; i + 2 < der.length && got < 2; i++) {
                int tag = der[i] & 0xFF;
                if (tag != 0x17 && tag != 0x18) continue;
                Len len = readLength(der, i + 1);
                if (len == null || len.valueStart < 0 || len.valueEnd > der.length
                        || len.valueEnd < len.valueStart) continue;
                String s = new String(der, len.valueStart, len.valueEnd - len.valueStart,
                        java.nio.charset.StandardCharsets.US_ASCII);
                long ms = parseAsn1Time(s, tag == 0x18);
                if (ms > 0) out[got++] = ms;
                i = len.valueEnd - 1;
            }
            return got == 2 ? out : null;
        } catch (Throwable t) {
            // ⚠️ 这个入口以前是全类唯一没有 try/catch 的：畸形 DER 一旦抛
            //    StringIndexOutOfBounds 就被链路层当「坏帧」吞掉 —— 0xAA01 整条丢失、
            //    挑战不发、静默卡在 AUTHENTICATING（与「0xAA00 无超时」同症状）。
            com.carbridge.carplay.util.Diag.w("MfiCrypto.certValidity", t);
            return null;
        }
    }

    private static long parseAsn1Time(String s, boolean generalized) {
        try {
            // 支持「无秒」形式：UTCTime yyMMddHHmmZ(11)/GeneralizedTime yyyyMMddHHmmZ(13)
            if (s.endsWith("Z")) {
                if (!generalized && s.length() == 11) s = s.substring(0, 10) + "00Z";
                if (generalized && s.length() == 13) s = s.substring(0, 12) + "00Z";
            }
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                    generalized ? "yyyyMMddHHmmss'Z'" : "yyMMddHHmmss'Z'", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            if (!generalized && s.length() == 13) {
                // UTCTime 的两位年份：<50 → 20xx，≥50 → 19xx（RFC 5280）
                int yy = Integer.parseInt(s.substring(0, 2));
                s = (yy < 50 ? "20" : "19") + s;
                f = new java.text.SimpleDateFormat("yyyyMMddHHmmss'Z'", java.util.Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            }
            return f.parse(s).getTime();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 签名形状校验：MFi 里 RSA 签名是固定长度（模长字节），ECDSA 是 DER SEQUENCE。
     * 形状不对说明「对面根本没按 MFi 签」，严格模式该直接拒。
     */
    public static boolean signatureShapeOk(byte[] sig, RsaKey rsa, java.security.PublicKey ec) {
        if (sig == null || sig.length == 0) return false;
        if (rsa != null) return sig.length == (rsa.modulus.bitLength() + 7) / 8;
        if (ec != null) {
            if (sig.length == 64) return true;                       // raw r||s
            // DER SEQUENCE：以前只看首字节 0x30 + 长度 8..80，`30 00 00 00 00 00 00 00`
            // 也会判 true。现在要求长度字段与总长自洽。
            if ((sig[0] & 0xFF) != 0x30) return false;
            int l = sig[1] & 0xFF;
            return l >= 6 && l <= 78 && l + 2 == sig.length;
        }
        return false;
    }

    /** 证书里有没有 Apple 的 OID 弧（1.2.840.113635 = 2A 86 48 86 F7 63）→ 是否 Apple 签发体系。 */
    public static boolean looksAppleIssued(byte[] der) {
        if (der == null) return false;
        byte[] arc = {0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x63};
        for (int i = 0; i + arc.length <= der.length; i++) {
            boolean hit = true;
            for (int k = 0; k < arc.length; k++) {
                if (der[i + k] != arc[k]) { hit = false; break; }
            }
            if (hit) return true;
        }
        return false;
    }

    // ================================================================== RSA 验签

    /** DigestInfo 前缀（含算法 OID 与 NULL 参数）：MFi 老车机用 SHA-1，新一些的用 SHA-256。 */
    private static final byte[] DI_SHA1 = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E, 0x03, 0x02, 0x1A, 0x05, 0x00};
    private static final byte[] DI_SHA256 = {
            0x30, 0x31, 0x30, 0x0D, 0x06, 0x09, 0x60, (byte) 0x86, 0x48, 0x01, 0x65,
            0x03, 0x04, 0x02, 0x01, 0x05, 0x00};
    /** 省略 NULL 参数的变体（部分实现这么签）：SHA-1 与 SHA-256。 */
    private static final byte[] DI_SHA1_NONULL = {
            0x30, 0x1F, 0x30, 0x07, 0x06, 0x05, 0x2B, 0x0E, 0x03, 0x02, 0x1A};
    private static final byte[] DI_SHA256_NONULL = {
            0x30, 0x2F, 0x30, 0x0B, 0x06, 0x09, 0x60, (byte) 0x86, 0x48, 0x01, 0x65,
            0x03, 0x04, 0x02, 0x01};

    /**
     * 验证 0xAA03 的签名（PKCS#1 v1.5）。
     *
     * <p>摘要算法按 DigestInfo 前缀自动识别：SHA-1（MFi 传统）与 SHA-256（较新）。
     * 以前写死 SHA-1，严格模式下 SHA-256 签名会被误拒。
     *
     * @param challenge 20/32 字节挑战
     * @param signature 模长字节的 RSA 签名
     * @param key       从证书解析出的公钥
     */
    public static boolean verifySignature(byte[] challenge, byte[] signature, RsaKey key) {
        if (challenge == null || signature == null || key == null) return false;
        // ⚠️ 公钥参数最低校验：e=1 的「证书」曾能通过 —— 此时签名就是 EM 本身，
        //    零私钥运算即可伪造任意签名（实测）。RSA 要求 e 为 ≥3 的奇数。
        if (key.exponent.compareTo(java.math.BigInteger.valueOf(3)) < 0
                || !key.exponent.testBit(0)) {
            return false;
        }
        try {
            BigInteger m = new BigInteger(1, signature).modPow(key.exponent, key.modulus);
            int k = (key.modulus.bitLength() + 7) / 8;
            if (k < 64) return false;   // 512-bit 以下的 RSA 身份不接受
            byte[] em = new byte[k];
            byte[] raw = m.toByteArray();
            int src = Math.max(0, raw.length - k);
            int dst = k - Math.min(k, raw.length);
            System.arraycopy(raw, src, em, dst, Math.min(k, raw.length));
            // 找 DigestInfo：00 01 FF..FF 00 <DigestInfo>
            if (em[0] != 0x00 || em[1] != 0x01) return false;
            int p = 2;
            while (p < em.length && em[p] == (byte) 0xFF) p++;
            if (p - 2 < 8) return false;   // PKCS#1 要求至少 8 字节 FF 填充
            if (p >= em.length || em[p] != 0x00) return false;
            int diStart = p + 1;
            String hash;
            int digestLen;
            if (startsWith(em, diStart, DI_SHA256) || startsWith(em, diStart, DI_SHA256_NONULL)) {
                hash = "SHA-256";
                digestLen = 32;
            } else if (startsWith(em, diStart, DI_SHA1) || startsWith(em, diStart, DI_SHA1_NONULL)) {
                hash = "SHA-1";
                digestLen = 20;
            } else {
                // 只认标准 DigestInfo；未知 OID = 不是合法 PKCS#1 v1.5(SHA-1/256)
                return false;
            }
            if (em.length - diStart < digestLen + 10) return false;
            byte[] digest = new byte[digestLen];
            System.arraycopy(em, em.length - digestLen, digest, 0, digestLen);
            byte[] expected = MessageDigest.getInstance(hash).digest(challenge);
            return MessageDigest.isEqual(digest, expected);
        } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("MfiCrypto", t);
            return false;
        }
    }

    private static boolean startsWith(byte[] d, int off, byte[] prefix) {
        if (d == null || off < 0 || off + prefix.length > d.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (d[off + i] != prefix[i]) return false;
        }
        return true;
    }
}
