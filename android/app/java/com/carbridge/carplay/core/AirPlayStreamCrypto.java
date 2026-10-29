package com.carbridge.carplay.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AirPlay <b>配对会话</b>的流加密：ChaCha20-Poly1305。
 */
public final class AirPlayStreamCrypto {

    public static final int TAG_LEN = 16;
    public static final int NONCE_LEN = 12;
    public static final int KEY_LEN = 32;

    private final byte[] key;
    private long counter;   // 逐包递增

    public AirPlayStreamCrypto(byte[] key) {
        if (key == null || key.length != KEY_LEN) {
            throw new IllegalArgumentException("key 必须 32 字节");
        }
        this.key = key.clone();
    }

    /**
     * 从 pair-verify 得到的共享密钥派生数据流密钥。
     *
     * @param shared     pair-verify 的共享秘密
     * @param streamConnId SETUP 里的 streamConnectionID
     */
    public static byte[] derive(byte[] shared, long streamConnId) throws GeneralSecurityException {
        return hkdfSha512(shared,
                // 对端（DiPlay/xcertplay）按**无符号十进制**拼 salt
                ("DataStream-Salt" + Long.toUnsignedString(streamConnId)).getBytes(StandardCharsets.US_ASCII),
                "DataStream-Output-Encryption-Key".getBytes(StandardCharsets.US_ASCII),
                KEY_LEN);
    }

    /** HKDF-SHA512（RFC 5869）。 */
    public static byte[] hkdfSha512(byte[] ikm, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(salt.length == 0 ? new byte[64] : salt, "HmacSHA512"));
        byte[] prk = mac.doFinal(ikm);
        byte[] okm = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        byte counter = 1;
        while (pos < length) {
            mac.init(new SecretKeySpec(prk, "HmacSHA512"));
            mac.update(t);
            mac.update(info);
            mac.update(new byte[]{counter});
            t = mac.doFinal();
            int n = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, okm, pos, n);
            pos += n;
            counter++;
        }
        return okm;
    }

    // ================================================================== 加密

    /**
     * 加密一段视频载荷。AAD 是完整 128 字节帧头。
     *
     * @return 密文 || 16B tag（长度 = 明文 + 16）
     */
    public byte[] encryptVideo(byte[] frameHeader128, byte[] plaintext)
            throws GeneralSecurityException {
        byte[] nonce = nextNonce();
        Cipher c = newChaCha(nonce);
        c.updateAAD(frameHeader128);
        return c.doFinal(plaintext);
    }

    /**
     * 加密一段音频载荷。AAD 是 BE64 (timestamp << 32 | ssrc)。
     *
     * @return 密文 || 16B tag || 8B 小端 nonce
     */
    public byte[] encryptAudio(int rtpTimestamp, int ssrc, byte[] plaintext)
            throws GeneralSecurityException {
        byte[] nonce = nextNonce();
        byte[] aad = new byte[8];
        long v = ((long) rtpTimestamp << 32) | (ssrc & 0xFFFFFFFFL);
        for (int i = 0; i < 8; i++) aad[i] = (byte) (v >>> (8 * (7 - i)));
        Cipher c = newChaCha(nonce);
        c.updateAAD(aad);
        byte[] sealed = c.doFinal(plaintext);
        // 尾部追加 8 字节小端 nonce（接收端据此解密）
        byte[] out = new byte[sealed.length + 8];
        System.arraycopy(sealed, 0, out, 0, sealed.length);
        for (int i = 0; i < 8; i++) out[sealed.length + i] = (byte) (readLongLE(nonce, 4) >>> (8 * i));
        return out;
    }

    private Cipher newChaCha(byte[] nonce) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        // SunJCE 的 ChaCha20-Poly1305 要 IvParameterSpec（12 字节 nonce），计数器固定从 1 起
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"),
                new IvParameterSpec(nonce));
        return c;
    }

    private byte[] nextNonce() {
        byte[] n = new byte[NONCE_LEN];
        // ⚠️ 必须从 0 起（对端 frameCounter 是 AtomicLong(0)）：原来 ++counter 从 1 起，
        // 第一帧就和对端错位 → BAD_DECRYPT，整条会话被对端拆掉。
        long c = counter++;
        for (int i = 0; i < 8; i++) n[4 + i] = (byte) (c >>> (8 * i));
        return n;
    }

    private static long readLongLE(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
    }

    // ================================================================== 解密（测试用）

    /** 解密一段视频载荷（AAD 同样是 128 字节帧头）。 */
    public byte[] decryptVideo(byte[] frameHeader128, byte[] sealed)
            throws GeneralSecurityException {
        byte[] nonce = nextNonce();
        Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"),
                new IvParameterSpec(nonce));
        c.updateAAD(frameHeader128);
        return c.doFinal(sealed);
    }
}
