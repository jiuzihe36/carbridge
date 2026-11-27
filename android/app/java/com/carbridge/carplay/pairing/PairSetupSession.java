package com.carbridge.carplay.pairing;

import com.carbridge.carplay.core.AirPlayStreamCrypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * AirPlay <b>pair-setup</b> 会话（M1–
 * M6）——对齐 DiPlay `PairSetup.kt`。
 */
public final class PairSetupSession {

    public interface Listener {
        void onLog(String msg);

        void onTlvOut(byte[] tlv);
    }

    private static final byte[] SALT = "Pair-Setup-Encrypt-Salt".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] INFO = "Pair-Setup-Encrypt-Info".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SIGN_SALT =
            "Pair-Setup-Controller-Sign-Salt".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SIGN_INFO =
            "Pair-Setup-Controller-Sign-Info".getBytes(StandardCharsets.US_ASCII);

    private static final String MSG05 = "PS-Msg05";

    private final Listener listener;
    private final Ed25519.KeyPair identity;
    private final String password;
    /** 公钥 hex —— 与 pair-verify 用同一个，否则对端配对库查不到。 */
    private final String pairingId;

    private SRP6a srp;
    private byte[] sessionKey;

    private State state = State.IDLE;

    public enum State {IDLE, SENT_M1, SENT_M3, SENT_M5, PAIRED, FAILED}

    public PairSetupSession(Listener listener, Ed25519.KeyPair identity, String password) {
        this.listener = listener;
        this.identity = identity;
        // 空/未给 → 用 DiPlay 的固定配对码（其 PairSetup.kt 的 SETUP_CODE）。
        // 取值走 SRP6a.defaultCode()，不在字面量里留明文。
        this.password = (password == null || password.isEmpty()) ? SRP6a.defaultCode() : password;
        this.pairingId = hex(identity.publicKey);
    }

    public State state() {
        return state;
    }

    public String pairingId() {
        return pairingId;
    }

    // ================================================================== M1

    public void start() {
        try {
            state = State.SENT_M1;
            log("→ pair-setup M1（SRP-6a，口令 " + mask(password) + "）");
            listener.onTlvOut(Tlv8.encodeUInt(Tlv8.STATE, 1));
        } catch (Throwable t) {
            log("M1 失败: " + t);
            state = State.FAILED;
        }
    }

    // ================================================================== 消息处理

    public void onTlv(byte[] tlv) {
        if (tlv == null || tlv.length == 0) {
            log("收到空的 pair-setup 响应");
            state = State.FAILED;
            return;
        }
        try {
            long st = Tlv8.uintOf(tlv, Tlv8.STATE, -1);
            Long err = nullableUInt(tlv, Tlv8.ERROR);
            if (err != null) {
                log("对端返回 pair-setup 错误：state=" + st + " error=" + err);
                state = State.FAILED;
                return;
            }
            switch (Long.valueOf(st).intValue()) {
                case 2:
                    handleM2(tlv);
                    break;
                case 4:
                    handleM4(tlv);
                    break;
                case 6:
                    handleM6(tlv);
                    break;
                default:
                    log("未知 pair-setup state=" + st);
                    state = State.FAILED;
            }
        } catch (Throwable t) {
            log("pair-setup 处理失败: " + t);
            state = State.FAILED;
        }
    }

    private void handleM2(byte[] tlv) throws GeneralSecurityException {
        byte[] b = Tlv8.valueOf(tlv, Tlv8.PUBLIC_KEY);
        byte[] salt = Tlv8.valueOf(tlv, Tlv8.SALT);
        if (b == null || salt == null) {
            log("M2 缺 PUBLIC_KEY/SALT");
            state = State.FAILED;
            return;
        }
        srp = new SRP6a(password);
        srp.processServerParams(new BigInteger(1, b), salt);
        byte[] A = srp.publicA();
        byte[] m1Proof = srp.clientProof();
        sessionKey = srp.sessionKey();
        log("M2 收到 SRP B(" + b.length + "B) salt(" + salt.length + "B) → 计算 A + M1");
        state = State.SENT_M3;
        listener.onTlvOut(Tlv8.concat(
                Tlv8.encodeUInt(Tlv8.STATE, 3),
                Tlv8.encode(Tlv8.PUBLIC_KEY, A),
                Tlv8.encode(Tlv8.PROOF, m1Proof)));
        log("→ pair-setup M3（A + 客户端证明）");
    }

    private void handleM4(byte[] tlv) throws GeneralSecurityException {
        byte[] serverProof = Tlv8.valueOf(tlv, Tlv8.PROOF);
        if (serverProof == null) {
            log("M4 缺 PROOF");
            state = State.FAILED;
            return;
        }
        // 服务端证明 = H(pad(A) ‖ 客户端证明 ‖ K)，验它确保对端也持有一致的配对码
        byte[] expect = sha512(
                srp.clientPublicKey(),      // pad 到 384 字节的 A
                srp.clientProof(),
                sessionKey);
        if (!java.security.MessageDigest.isEqual(expect, serverProof)) {
            log("M4 服务端证明不匹配（配对码错误？）");
            state = State.FAILED;
            return;
        }
        log("M4 服务端证明通过 → 发身份");

        byte[] encKey = AirPlayStreamCrypto.hkdfSha512(sessionKey, SALT, INFO, 32);
        byte[] signKey = AirPlayStreamCrypto.hkdfSha512(sessionKey, SIGN_SALT, SIGN_INFO, 32);
        byte[] idBytes = pairingId.getBytes(StandardCharsets.UTF_8);
        byte[] signData = Tlv8.concat(signKey, idBytes, identity.publicKey);
        byte[] sig = Ed25519.sign(identity.seed, signData);

        byte[] sub = Tlv8.concat(
                Tlv8.encode(Tlv8.IDENTIFIER, idBytes),
                Tlv8.encode(Tlv8.PUBLIC_KEY, identity.publicKey),
                Tlv8.encode(Tlv8.SIGNATURE, sig));
        byte[] sealed = PairVerifySession.chachaSeal(encKey, MSG05, sub);

        state = State.SENT_M5;
        listener.onTlvOut(Tlv8.concat(
                Tlv8.encodeUInt(Tlv8.STATE, 5),
                Tlv8.encode(Tlv8.ENCRYPTED_DATA, sealed)));
        log("→ pair-setup M5（加密身份 id=" + pairingId.substring(0, 8) + "…）");
    }

    private void handleM6(byte[] tlv) throws GeneralSecurityException {
        byte[] sealed = Tlv8.valueOf(tlv, Tlv8.ENCRYPTED_DATA);
        if (sealed == null || sessionKey == null) {
            log("M6 缺 ENCRYPTED_DATA");
            state = State.FAILED;
            return;
        }
        byte[] encKey = AirPlayStreamCrypto.hkdfSha512(sessionKey, SALT, INFO, 32);
        byte[] sub;
        try {
            sub = PairVerifySession.chachaOpen(encKey, "PS-Msg06", sealed);
        } catch (GeneralSecurityException e) {
            log("M6 解密/tag 校验失败（密文被篡改或密钥不符）: " + e);
            state = State.FAILED;
            return;
        }
        byte[] theirId = Tlv8.valueOf(sub, Tlv8.IDENTIFIER);
        byte[] theirPk = Tlv8.valueOf(sub, Tlv8.PUBLIC_KEY);
        byte[] theirSig = Tlv8.valueOf(sub, Tlv8.SIGNATURE);
        if (theirId == null || theirPk == null || theirSig == null) {
            log("M6 解开但缺字段");
            state = State.FAILED;
            return;
        }
        // 对端签名：signKey ‖ id ‖ ltpk（Pair-Setup-Accessory-Sign-*）
        byte[] aSignKey = AirPlayStreamCrypto.hkdfSha512(sessionKey,
                "Pair-Setup-Accessory-Sign-Salt".getBytes(StandardCharsets.US_ASCII),
                "Pair-Setup-Accessory-Sign-Info".getBytes(StandardCharsets.US_ASCII), 32);
        byte[] aData = Tlv8.concat(aSignKey, theirId, theirPk);
        if (!Ed25519.verify(theirSig, theirPk, aData)) {
            log("M6 对端身份验签失败");
            state = State.FAILED;
            return;
        }
        log("★ pair-setup 完成，已注册 id=" + pairingId.substring(0, 8)
                + "…  对端 id=" + new String(theirId, StandardCharsets.UTF_8));
        state = State.PAIRED;
    }

    // ================================================================== 工具

    private static Long nullableUInt(byte[] tlv, int type) {
        byte[] v = Tlv8.valueOf(tlv, type);
        if (v == null || v.length == 0) return null;
        long r = 0;
        for (byte b : v) r = (r << 8) | (b & 0xFFL);
        return r;
    }

    private static byte[] sha512(byte[]... parts) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-512");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 日志里别明文打配对码（定长掩码：4 位 PIN 露首末就只剩 100 种可能）。 */
    private static String mask(String pw) {
        if (pw == null || pw.isEmpty()) return "(空)";
        return "****";
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(java.util.Locale.US, "%02x", x));
        return sb.toString();
    }

    private void log(String m) {
        listener.onLog(m);
    }
}
