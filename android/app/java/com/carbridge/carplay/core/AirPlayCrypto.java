package com.carbridge.carplay.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * CarPlay 视频流的「旧式 et=1」加密：AES-128-CTR。
 */
public final class AirPlayCrypto implements AutoCloseable {

    private static final byte[] SALT_KEY = "AirPlayStreamKey".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SALT_IV = "AirPlayStreamIV".getBytes(StandardCharsets.US_ASCII);

    private final Cipher cipher;

    public AirPlayCrypto(long streamConnectionId) throws GeneralSecurityException {
        this(derive(SALT_KEY, streamConnectionId), derive(SALT_IV, streamConnectionId));
    }

    /** 直接指定 key/iv（测试与配对会话用）。 */
    public AirPlayCrypto(byte[] key, byte[] iv) throws GeneralSecurityException {
        cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
    }

    /** 加/解密一段视频载荷（CTR 对称）；连续调用形成连续密钥流。 */
    public byte[] process(byte[] data) {
        return cipher.update(data);
    }

    @Override
    public void close() {
        // javax.crypto.Cipher 没有 reset()；置空由 GC 处理。
    }

    // ---------------------------------------------------------------- 派生

    public static byte[] deriveKey(long id) throws GeneralSecurityException {
        return derive(SALT_KEY, id);
    }

    public static byte[] deriveIv(long id) throws GeneralSecurityException {
        return derive(SALT_IV, id);
    }

    private static byte[] derive(byte[] salt, long id) throws GeneralSecurityException {
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        md.update(salt);
        md.update(Long.toUnsignedString(id).getBytes(StandardCharsets.US_ASCII));
        md.update(new byte[16]);
        return Arrays.copyOf(md.digest(), 16);
    }
}
