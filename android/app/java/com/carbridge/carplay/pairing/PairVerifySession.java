package com.carbridge.carplay.pairing;

import com.carbridge.carplay.core.AirPlayStreamCrypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * AirPlay <b>pair-verify</b> 会话（M1–M
 * 4）——**对齐 DiPlay/xcertplay 的实现**。
 */
public final class PairVerifySession {

    public interface Listener {
        void onLog(String msg);

        /** 把一条 TLV8 消息发给对端。 */
        void onTlvOut(byte[] tlv);
    }

    private static final byte[] SALT =
            "Pair-Verify-Encrypt-Salt".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] INFO =
            "Pair-Verify-Encrypt-Info".getBytes(StandardCharsets.US_ASCII);

    private static final String MSG02 = "PV-Msg02";
    private static final String MSG03 = "PV-Msg03";

    private final Listener listener;
    private final Ed25519.KeyPair identity;
    /**
     * 我方配对 id。**必须与 pair-setup M5 里的 IDENTIFIER 逐字节一致**，否则对端
     * `pairings.get(id)` 查不到 → 回 {STATE=4, ERROR=2}（真机实测）。
     * 用公钥 hex（ASCII），UTF-8 往返无损。
     */
    private final byte[] myId;

    private byte[] ephPriv;
    private byte[] ephPub;
    private byte[] peerEphPub;
    private byte[] sharedSecret;
    private byte[] verifyKey;

    /** 对端长期公钥（来自 mDNS TXT `pk` 或 iAP2 0x4301 参数 4）；拿不到则跳过验签。 */
    private byte[] peerLongTermKey;

    private State state = State.IDLE;

    public enum State {IDLE, SENT_M1, WAIT_M4, VERIFIED, FAILED}

    public PairVerifySession(Listener listener, Ed25519.KeyPair identity) {
        this(listener, identity, null);
    }

    public PairVerifySession(Listener listener, Ed25519.KeyPair identity, byte[] peerLongTermKey) {
        this.listener = listener;
        this.identity = identity;
        this.peerLongTermKey = peerLongTermKey;
        this.myId = bytesToHex(identity.publicKey).getBytes(StandardCharsets.US_ASCII);
    }

    public State state() {
        return state;
    }

    public byte[] sharedSecret() {
        return sharedSecret;
    }

    /** 我方配对 id（公钥 hex 的 ASCII 形式，与 pair-setup 一致）。 */
    public String pairingId() {
        return new String(myId, StandardCharsets.US_ASCII);
    }

    public ControlCipher writeCipher() throws GeneralSecurityException {
        return sharedSecret == null ? null : new ControlCipher(sharedSecret, true, true);
    }

    public ControlCipher readCipher() throws GeneralSecurityException {
        return sharedSecret == null ? null : new ControlCipher(sharedSecret, false, false);
    }

    // ================================================================== M1

    public void start() {
        try {
            ephPriv = X25519.generatePrivateKey();
            ephPub = X25519.publicKey(ephPriv);
            byte[] m1 = Tlv8.concat(
                    Tlv8.encodeUInt(Tlv8.STATE, 1),
                    Tlv8.encode(Tlv8.PUBLIC_KEY, ephPub));
            state = State.SENT_M1;
            log("→ pair-verify M1（临时公钥 32B，id=" + pairingId().substring(0, 8) + "…）");
            listener.onTlvOut(m1);
        } catch (Throwable t) {
            log("M1 失败: " + t);
            state = State.FAILED;
        }
    }

    // ================================================================== M2 / M4

    public void onTlv(byte[] tlv) {
        if (tlv == null || tlv.length == 0) {
            log("收到空的 pair-verify 响应");
            state = State.FAILED;
            return;
        }
        try {
            long st = Tlv8.uintOf(tlv, Tlv8.STATE, -1);
            Long err = nullableUInt(tlv, Tlv8.ERROR);
            if (err != null) {
                log("对端返回配对错误：state=" + st + " error=" + err);
                state = State.FAILED;
                return;
            }
            if (st == 2) {
                handleM2(tlv);
            } else if (st == 4) {
                handleM4();
            } else {
                log("未知 pair-verify state=" + st);
                state = State.FAILED;
            }
        } catch (Throwable t) {
            log("pair-verify 处理失败: " + t);
            state = State.FAILED;
        }
    }

    private void handleM2(byte[] tlv) throws GeneralSecurityException {
        byte[] pub = Tlv8.valueOf(tlv, Tlv8.PUBLIC_KEY);
        byte[] sealed = Tlv8.valueOf(tlv, Tlv8.ENCRYPTED_DATA);
        if (pub == null || pub.length != 32) {
            log("M2 缺 PUBLIC_KEY");
            state = State.FAILED;
            return;
        }
        if (sealed == null) {
            log("M2 缺 ENCRYPTED_DATA");
            state = State.FAILED;
            return;
        }
        peerEphPub = pub;
        sharedSecret = X25519.sharedSecret(ephPriv, peerEphPub);
        verifyKey = AirPlayStreamCrypto.hkdfSha512(sharedSecret, SALT, INFO, 32);

        // ENCRYPTED_DATA 里装的是 {IDENTIFIER, SIGNATURE}，先解
        byte[] sub = chacha(verifyKey, MSG02, sealed);
        byte[] peerId = Tlv8.valueOf(sub, Tlv8.IDENTIFIER);
        byte[] sig = Tlv8.valueOf(sub, Tlv8.SIGNATURE);
        log("M2 解开：对端 id=" + (peerId == null ? "?" : new String(peerId, StandardCharsets.UTF_8))
                + " sig=" + (sig == null ? 0 : sig.length) + "B");

        // 验签：对端临时公钥 ‖ 对端 id ‖ 我方临时公钥
        byte[] signed = Tlv8.concat(peerEphPub, peerId == null ? new byte[0] : peerId, ephPub);
        if (peerLongTermKey != null && sig != null) {
            if (!Ed25519.verify(sig, peerLongTermKey, signed)) {
                log("M2 验签失败");
                state = State.FAILED;
                return;
            }
            log("M2 验签通过");
        } else if (sig != null) {
            log("⚠ 无对端长期公钥，跳过 M2 验签（需 mDNS `pk` 或 iAP2 0x4301 参数 4）");
        }

        // M3：把 {IDENTIFIER, SIGNATURE} 加密后发出
        byte[] sub3 = Tlv8.concat(
                Tlv8.encode(Tlv8.IDENTIFIER, myId),
                Tlv8.encode(Tlv8.SIGNATURE, Ed25519.sign(identity.seed,
                        Tlv8.concat(ephPub, myId, peerEphPub))));
        byte[] sealed3 = chacha(verifyKey, MSG03, sub3);
        byte[] m3 = Tlv8.concat(
                Tlv8.encodeUInt(Tlv8.STATE, 3),
                Tlv8.encode(Tlv8.ENCRYPTED_DATA, sealed3));
        state = State.WAIT_M4;
        log("→ pair-verify M3");
        listener.onTlvOut(m3);
    }

    private void handleM4() {
        log("收到 M4，配对验证成功");
        state = State.VERIFIED;
    }

    private static Long nullableUInt(byte[] tlv, int type) {
        byte[] v = Tlv8.valueOf(tlv, type);
        if (v == null || v.length == 0) return null;
        long r = 0;
        for (byte b : v) r = (r << 8) | (b & 0xFFL);
        return r;
    }

    // ================================================================== ChaCha20

        /**
     * pair-verify 的数据用 **ChaCha20-Poly1305**（AEAD，输出含 16 字节 tag），
     * 对齐 DiPlay `AirPlayCrypto.chachaSeal/chachaOpen`。
     */
    public static byte[] chacha(byte[] key, String label, byte[] data)
            throws GeneralSecurityException {
        byte[] nonce = nonceLabel(label);
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(key, "ChaCha20"),
                new javax.crypto.spec.IvParameterSpec(nonce));
        return c.doFinal(data);
    }

    /** nonce = 12 字节，标签 ASCII 放偏移 4（与 DiPlay nonceLabel 一致）。 */
    public static byte[] nonceLabel(String label) {
        byte[] lbl = label.getBytes(StandardCharsets.US_ASCII);
        byte[] nonce = new byte[12];
        int n = Math.min(lbl.length, 8);
        System.arraycopy(lbl, 0, nonce, 4, n);
        return nonce;
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(java.util.Locale.US, "%02x", x));
        return sb.toString();
    }

    private void log(String m) {
        listener.onLog(m);
    }
}
