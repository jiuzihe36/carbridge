package com.carbridge.carplay.pairing;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * SRP-6a <b>客户端</b>（AirPlay pair-setup 变体），
 * 公式逐项对齐 DiPlay `Srp6a.kt`：
 */
public final class SRP6a {

    /** HomeKit pair-setup 的用户名（固定字面量）。 */
    public static final String USERNAME = "Pair-Setup";

    static final BigInteger N = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
                    + "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
                    + "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
                    + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05"
                    + "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB"
                    + "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
                    + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183"
                    + "995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A"
                    + "85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7AB"
                    + "F5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D8"
                    + "7602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208"
                    + "E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16);
    static final BigInteger G = BigInteger.valueOf(5);
    static final int N_BYTES = 384;
    private static final byte[] COLON = {':'};

    private final SecureRandom random = new SecureRandom();
    private final String password;

    private BigInteger bigA;
    private BigInteger bigB;
    private BigInteger bigAPriv;
    private byte[] saltBytes;
    private byte[] sessionKey;     // K = H(minimal(S))，未 pad

    /** @param password 固定配对码（见 {@link #defaultCode()}）。 */
    public SRP6a(String password) {
        this.password = password == null ? "" : password;
    }

        /**
     * DiPlay 的固定配对码。
     */
    public static String defaultCode() {
        byte[] enc = {0x3F, 0x35, 0x3F, 0x35};
        char[] out = new char[enc.length];
        for (int i = 0; i < enc.length; i++) {
            out[i] = (char) (enc[i] ^ 0x0C);
        }
        return new String(out);
    }

    /** DiPlay 的固定码（见 {@link #defaultCode()}）。 */
    public static SRP6a pairSetup() {
        return new SRP6a(defaultCode());
    }

    // ================================================================== 客户端步骤

    /** 接收服务端公钥 B 与 salt（必须在 {@link #publicA()} 之前调用）。 */
    public void processServerParams(BigInteger b, byte[] salt) {
        this.bigB = b;
        this.saltBytes = salt;
    }

    /** 服务端公钥 B 的 pad 形式（384 字节），用于 M1。 */
    public byte[] serverPublicKey() {
        return pad(bigB);
    }

    /** 服务端下发的 salt（16 字节，不 pad）。 */
    public byte[] salt() {
        return saltBytes;
    }

    /** 生成 A = g^a 并算出会话密钥 K。 */
    public byte[] publicA() {
        if (bigB == null || saltBytes == null) throw new IllegalStateException("先调 processServerParams");
        bigAPriv = new BigInteger(N.bitLength() - 1, random);
        bigA = G.modPow(bigAPriv, N);
        return pad(bigA);
    }

    /** 我方公钥 A 的 pad 形式（384 字节）——验服务端证明要用。 */
    public byte[] clientPublicKey() {
        if (bigA == null) throw new IllegalStateException("先调 publicA()");
        return pad(bigA);
    }

    /** 会话密钥 K = H(minimal(S))。 */
    public byte[] sessionKey() {
        computeSessionKey();
        return sessionKey;
    }

    /**
     * 客户端证明 M1：
     * {@code H( H(N) XOR H(g) ‖ H("Pair-Setup") ‖ salt ‖ pad(A) ‖ pad(B) ‖ K )}
     */
    public byte[] clientProof() {
        computeSessionKey();
        byte[] hashN = hash(minimal(N));
        byte[] hashG = hash(minimal(G));
        byte[] xor = new byte[hashN.length];
        for (int i = 0; i < hashN.length; i++) xor[i] = (byte) (hashN[i] ^ hashG[i]);
        return hash(xor, hash(USERNAME.getBytes(StandardCharsets.UTF_8)), saltBytes,
                pad(bigA), pad(bigB), sessionKey);
    }

    // ================================================================== 内部

    private synchronized void computeSessionKey() {
        if (sessionKey != null) return;
        BigInteger a = bigAPriv;
        BigInteger u = hashInt(pad(bigA), pad(bigB));
        BigInteger kVal = hashInt(minimal(N), pad(G));
        BigInteger x = hashInt(saltBytes,
                hash(concat(USERNAME.getBytes(StandardCharsets.UTF_8), COLON,
                        password.getBytes(StandardCharsets.UTF_8))));
        BigInteger gux = G.modPow(x, N);
        BigInteger base = bigB.subtract(kVal.multiply(gux)).mod(N);
        BigInteger exponent = a.add(u.multiply(x));
        BigInteger s = base.modPow(exponent, N);
        sessionKey = hash(minimal(s));
    }

    /** 摘要按大端整数解释（用于 u / k / x 这类数值）。 */
    private static BigInteger hashInt(byte[]... parts) {
        return new BigInteger(1, hash(parts));
    }

    /** 去前导 0（对端 toBytes）；0 → [0]。 */
    static byte[] minimal(BigInteger v) {
        if (v.signum() == 0) return new byte[]{0};
        byte[] enc = v.toByteArray();
        if (enc.length > 1 && enc[0] == 0) return Arrays.copyOfRange(enc, 1, enc.length);
        return enc;
    }

    /** 左补零到 384 字节（对端 pad）。 */
    static byte[] pad(BigInteger v) {
        byte[] b = minimal(v);
        if (b.length >= N_BYTES) return b;
        byte[] out = new byte[N_BYTES];
        System.arraycopy(b, 0, out, N_BYTES - b.length, b.length);
        return out;
    }

    private static byte[] hash(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            for (byte[] p : parts) md.update(p);
            return md.digest();
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

    // ------------------------------------------------------------------ 自检辅助

    public static String modulusHex() {
        return N.toString(16);
    }

    public static int modulusBits() {
        return N.bitLength();
    }

    public static int generator() {
        return G.intValue();
    }

    /** 服务端侧实现（自检用）：与 DiPlay `Srp6a.start` + `verify` 逐字对应。 */
    public static final class Server {
        private final SecureRandom random = new SecureRandom();
        private final String username;
        private final String password;
        public final byte[] salt;
        private final BigInteger verifier;
        private final BigInteger privateExponent;
        public final BigInteger publicB;

        public Server(String username, String password) {
            this.username = username;
            this.password = password;
            this.salt = new byte[16];
            random.nextBytes(salt);
            BigInteger kVal = hashInt(minimal(N), pad(G));
            BigInteger x = hashInt(salt, hash(concat(username.getBytes(StandardCharsets.UTF_8),
                    COLON, password.getBytes(StandardCharsets.UTF_8))));
            BigInteger verifier = G.modPow(x, N);
            BigInteger b = new BigInteger(32 * 8, random);
            this.verifier = verifier;
            this.privateExponent = b;
            this.publicB = kVal.multiply(verifier).add(G.modPow(b, N)).mod(N);
        }

        public byte[] serverPublicKey() {
            return pad(publicB);
        }

        /** 校验客户端的 A 与 M1，返回 K ‖ serverProof。 */
        public byte[][] verify(byte[] publicKeyA, byte[] clientProof) {
            BigInteger a = new BigInteger(1, publicKeyA);
            if (a.mod(N).signum() == 0) return null;
            BigInteger u = hashInt(pad(a), pad(publicB));
            BigInteger s = a.multiply(verifier.modPow(u, N)).mod(N).modPow(privateExponent, N);
            byte[] k = hash(minimal(s));

            byte[] hashN = hash(minimal(N));
            byte[] hashG = hash(minimal(G));
            byte[] xor = new byte[hashN.length];
            for (int i = 0; i < hashN.length; i++) xor[i] = (byte) (hashN[i] ^ hashG[i]);
            byte[] expected = hash(xor, hash(username.getBytes(StandardCharsets.UTF_8)), salt,
                    pad(a), pad(publicB), k);
            if (!Arrays.equals(expected, clientProof)) return null;
            byte[] serverProof = hash(pad(a), clientProof, k);
            return new byte[][]{k, serverProof};
        }
    }
}