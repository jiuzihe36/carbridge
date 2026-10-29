package com.carbridge.fakecar.pairing;

import com.carbridge.fakecar.core.AirPlayStreamCrypto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AirPlay **配对后**的控制通道加密 —— 对齐 Di
 * Play `ControlCipher.kt` 的实际格式。
 */
public final class ControlCipher {

    private static final byte[] SALT = "Control-Salt".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EVENTS_SALT = "Events-Salt".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_SIZE = 2;
    private static final int TAG_SIZE = 16;
    private static final int MAX_PAYLOAD = 0x4000;

    private final SecretKeySpec key;
    private final boolean encrypting;
    private long counter;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    /**
     * @param sharedSecret pair-verify 的共享秘密
     * @param useWriteKey  true=用 Write 密钥（controller→accessory 方向）
     * @param encrypting   true=加密发出，false=解密收到
     */
    public ControlCipher(byte[] sharedSecret, boolean useWriteKey, boolean encrypting)
            throws GeneralSecurityException {
        this(deriveKey(sharedSecret, SALT, "Control-", useWriteKey), encrypting);
    }

        /**
     * 事件通道（车机→手机发触摸/命令那条）：帧格式与 nonce 和控制通道完全一致，
     * 但 salt 与密钥标签不同 —— 对齐 DiPlay {@code acceptEvent}： {@code HKDF-SHA512(shared,"Events-Salt","Events-Write/Read-Encryption-Key")}。
     */
    public static ControlCipher events(byte[] sharedSecret, boolean useWriteKey, boolean encrypting)
            throws GeneralSecurityException {
        return new ControlCipher(
                deriveKey(sharedSecret, EVENTS_SALT, "Events-", useWriteKey), encrypting);
    }

    private static SecretKeySpec deriveKey(byte[] sharedSecret, byte[] salt, String prefix,
            boolean useWriteKey) throws GeneralSecurityException {
        byte[] k = AirPlayStreamCrypto.hkdfSha512(sharedSecret, salt,
                (prefix + (useWriteKey ? "Write" : "Read") + "-Encryption-Key")
                        .getBytes(StandardCharsets.US_ASCII), 32);
        return new SecretKeySpec(k, "ChaCha20");
    }

    private ControlCipher(SecretKeySpec key, boolean encrypting) {
        this.key = key;
        this.encrypting = encrypting;
    }

    // ================================================================== 加密

    public synchronized byte[] encrypt(byte[] plaintext) throws GeneralSecurityException {
        if (!encrypting) throw new IllegalStateException("这是接收方向的 cipher");
        if (plaintext == null || plaintext.length == 0) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int off = 0;
        do {
            int n = Math.min(MAX_PAYLOAD, plaintext.length - off);
            byte[] header = new byte[]{(byte) n, (byte) (n >>> 8)};   // 2 字节小端
            byte[] chunk = new byte[n];
            System.arraycopy(plaintext, off, chunk, 0, n);
            byte[] sealed = seal(chunk, header);
            out.write(header, 0, header.length);
            out.write(sealed, 0, sealed.length);
            counter++;
            off += n;
        } while (off < plaintext.length);
        return out.toByteArray();
    }

    /** 解密（可带缓冲，处理 TCP 半帧）。返回已解出的明文，可能为空。 */
    public synchronized byte[] decrypt(byte[] framed) throws GeneralSecurityException {
        if (encrypting) throw new IllegalStateException("这是发送方向的 cipher");
        if (framed == null || framed.length == 0) return new byte[0];
        pending.write(framed, 0, framed.length);
        byte[] buf = pending.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int off = 0;
        while (buf.length - off >= HEADER_SIZE) {
            int len = (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8);
            int frameEnd = off + HEADER_SIZE + len + TAG_SIZE;
            if (buf.length < frameEnd) break;          // 半帧，留待下次
            byte[] aad = new byte[HEADER_SIZE];
            System.arraycopy(buf, off, aad, 0, HEADER_SIZE);
            byte[] sealed = new byte[len + TAG_SIZE];
            System.arraycopy(buf, off + HEADER_SIZE, sealed, 0, sealed.length);
            byte[] plain = open(sealed, aad);
            out.write(plain, 0, plain.length);
            counter++;
            off = frameEnd;
        }
        pending.reset();
        if (off < buf.length) pending.write(buf, off, buf.length - off);
        return out.toByteArray();
    }

    private byte[] seal(byte[] plain, byte[] aad) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(nonce64(counter)));
        c.updateAAD(aad);
        return c.doFinal(plain);
    }

    private byte[] open(byte[] sealed, byte[] aad) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(nonce64(counter)));
        c.updateAAD(aad);
        return c.doFinal(sealed);
    }

    /** nonce = 4 字节 0 ‖ 8 字节**小端**计数器（与 DiPlay nonce64 一致）。 */
    public static byte[] nonce64(long counter) {
        byte[] nonce = new byte[12];
        for (int i = 0; i < 8; i++) nonce[4 + i] = (byte) (counter >>> (8 * i));
        return nonce;
    }
}