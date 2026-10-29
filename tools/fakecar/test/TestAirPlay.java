import com.carbridge.fakecar.AirPlayServer;
import com.carbridge.fakecar.core.AirPlayStreamCrypto;
import com.carbridge.fakecar.core.Bplist;
import com.carbridge.fakecar.pairing.Ed25519;
import com.carbridge.fakecar.pairing.Tlv8;
import com.carbridge.fakecar.pairing.X25519;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 桌面冒烟测试：验证 AirPlayServer 的 pair-verify(M1~M4) + 加密控制通道。
 * 客户端扮演「手机」，连本地 AirPlayServer，跑一遍握手 + ANNOUNCE/SETUP/GET /info。
 */
public class TestAirPlay {

    static int pass = 0, fail = 0;

    static void check(boolean cond, String name) {
        if (cond) {
            pass++;
            System.out.println("  ✓ " + name);
        } else {
            fail++;
            System.out.println("  ✗ " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        // 1) Ed25519 自签自验 + X25519 共享一致
        Ed25519.KeyPair kp = Ed25519.generate();
        byte[] msg = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] sig = Ed25519.sign(kp.seed, msg);
        check(Ed25519.verify(sig, kp.publicKey, msg), "Ed25519 自签自验");
        check(!Ed25519.verify(sig, kp.publicKey, "hello2".getBytes(StandardCharsets.UTF_8)), "Ed25519 拒错误消息");

        byte[] aPriv = X25519.generatePrivateKey();
        byte[] aPub = X25519.publicKey(aPriv);
        byte[] bPriv = X25519.generatePrivateKey();
        byte[] bPub = X25519.publicKey(bPriv);
        check(java.util.Arrays.equals(X25519.sharedSecret(aPriv, bPub), X25519.sharedSecret(bPriv, aPub)),
                "X25519 共享秘密一致");

        // 2) 启动 AirPlayServer
        int port = 7031;
        AirPlayServer srv = new AirPlayServer(new AirPlayServer.Log() {
            @Override
            public void onLog(String line) {
                System.out.println("  [srv] " + line);
            }
        }, new AirPlayServer.Phase() {
            @Override
            public void onPhase(String name) {
                System.out.println("  [srv-phase] " + name);
            }
        }, null, port);
        srv.start();
        Thread.sleep(300);

        Socket sock = new Socket("127.0.0.1", port);
        sock.setSoTimeout(4000);
        InputStream in = sock.getInputStream();
        OutputStream out = sock.getOutputStream();

        // —— M1 ——
        byte[] clientPriv = X25519.generatePrivateKey();
        byte[] clientPub = X25519.publicKey(clientPriv);
        byte[] m1 = Tlv8.concat(Tlv8.encodeUInt(Tlv8.STATE, 1), Tlv8.encode(Tlv8.PUBLIC_KEY, clientPub));
        out.write(rtspReq("POST", "/pair-verify", 1, m1));
        out.flush();
        Rtsp m2Resp = readRtsp(in);
        check(m2Resp.statusLine.contains("200") && m2Resp.body.length > 0, "M2 响应 200");
        Map<Integer, byte[]> m2 = parseTlv(m2Resp.body);
        byte[] serverPub = m2.get(Tlv8.PUBLIC_KEY);
        byte[] encrypted = m2.get(Tlv8.ENCRYPTED_DATA);
        check(serverPub != null && serverPub.length == 32, "M2 有公钥(32B)");
        check(m2.get(Tlv8.STATE) != null && m2.get(Tlv8.STATE)[0] == 2, "M2 state=2");

        // 解密 M2 ENCRYPTED_DATA 并验签
        byte[] shared = X25519.sharedSecret(clientPriv, serverPub);
        byte[] pvKey = AirPlayStreamCrypto.hkdfSha512(shared,
                "Pair-Verify-Encrypt-Salt".getBytes(StandardCharsets.US_ASCII),
                "Pair-Verify-Encrypt-Info".getBytes(StandardCharsets.US_ASCII), 32);
        byte[] sub = chachaOpen(pvKey, nonceLabel("PV-Msg02"), encrypted, new byte[0]);
        Map<Integer, byte[]> subTlv = parseTlv(sub);
        byte[] carId = subTlv.get(Tlv8.IDENTIFIER);
        byte[] carSig = subTlv.get(Tlv8.SIGNATURE);
        check(carId != null && carSig != null, "M2 ENCRYPTED_DATA 含 id+sig");
        byte[] signed = concat(serverPub, carId, clientPub);
        check(Ed25519.verify(carSig, hexDecode(carId), signed), "M2 签名验签通过");

        // —— M3 ——
        byte[] m3 = Tlv8.encodeUInt(Tlv8.STATE, 3);
        out.write(rtspReq("POST", "/pair-verify", 2, m3));
        out.flush();
        Rtsp m4Resp = readRtsp(in);
        Map<Integer, byte[]> m4 = parseTlv(m4Resp.body);
        check(m4.get(Tlv8.STATE) != null && m4.get(Tlv8.STATE)[0] == 4, "M4 state=4");

        // —— 加密控制通道 ——
        byte[] writeKey = AirPlayStreamCrypto.hkdfSha512(shared,
                "Control-Salt".getBytes(StandardCharsets.US_ASCII),
                "Control-Write-Encryption-Key".getBytes(StandardCharsets.US_ASCII), 32);
        byte[] readKey = AirPlayStreamCrypto.hkdfSha512(shared,
                "Control-Salt".getBytes(StandardCharsets.US_ASCII),
                "Control-Read-Encryption-Key".getBytes(StandardCharsets.US_ASCII), 32);

        // ANNOUNCE
        byte[] announce = "ANNOUNCE rtsp://x/1 RTSP/1.0\r\nCSeq: 3\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        out.write(sealCtrl(writeKey, 0, announce));
        out.flush();
        Rtsp announceResp = readCtrlRtsp(in, readKey, 0);
        check(announceResp != null && announceResp.statusLine.contains("200"), "加密 ANNOUNCE → 200");

        // SETUP /setup
        byte[] setup = "SETUP /setup RTSP/1.0\r\nCSeq: 4\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        out.write(sealCtrl(writeKey, 1, setup));
        out.flush();
        Rtsp setupResp = readCtrlRtsp(in, readKey, 1);
        check(setupResp != null && setupResp.statusLine.contains("200"), "加密 SETUP /setup → 200");
        Object setupObj = Bplist.read(setupResp.body);
        check(setupObj instanceof Map, "SETUP /setup 返回 bplist 字典");

        // GET /info
        byte[] info = "GET /info RTSP/1.0\r\nCSeq: 5\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        out.write(sealCtrl(writeKey, 2, info));
        out.flush();
        Rtsp infoResp = readCtrlRtsp(in, readKey, 2);
        check(infoResp != null && infoResp.statusLine.contains("200"), "加密 GET /info → 200");
        Object infoObj = Bplist.read(infoResp.body);
        check(infoObj instanceof Map, "GET /info 返回 bplist 字典");
        if (infoObj instanceof Map) {
            Map<?, ?> im = (Map<?, ?>) infoObj;
            check(im.containsKey("displays") && im.containsKey("hidDevices") && im.containsKey("audioFormats"),
                    "/info 含 displays/hidDevices/audioFormats");
        }

        sock.close();
        srv.stop();

        System.out.println("\n结果: " + pass + " 通过, " + fail + " 失败");
        if (fail > 0) System.exit(1);
        System.out.println("✅ AirPlay pair-verify + 加密控制通道 全通过");
    }

    // ================================================================== 工具

    static byte[] rtspReq(String method, String path, int cseq, byte[] body) {
        String head = method + " " + path + " RTSP/1.0\r\nCSeq: " + cseq
                + "\r\nContent-Type: application/octet-stream\r\n"
                + "Content-Length: " + (body == null ? 0 : body.length) + "\r\n\r\n";
        byte[] h = head.getBytes(StandardCharsets.ISO_8859_1);
        if (body == null || body.length == 0) return h;
        return concat(h, body);
    }

    static final class Rtsp {
        String statusLine;
        byte[] body;
    }

    static Rtsp readRtsp(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] one = new byte[1];
        byte[] b;
        while (true) {
            int n = in.read(one);
            if (n < 0) throw new RuntimeException("EOF");
            buf.write(one[0]);
            b = buf.toByteArray();
            if (b.length >= 4 && b[b.length - 4] == '\r' && b[b.length - 3] == '\n'
                    && b[b.length - 2] == '\r' && b[b.length - 1] == '\n') break;
        }
        String head = new String(b, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n");
        Rtsp r = new Rtsp();
        r.statusLine = lines[0];
        int cl = 0;
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0 && lines[i].substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                cl = Integer.parseInt(lines[i].substring(c + 1).trim());
            }
        }
        r.body = readN(in, cl);
        return r;
    }

    static Rtsp readCtrlRtsp(InputStream in, byte[] readKey, long counter) throws Exception {
        byte[] lenHdr = readN(in, 2);
        int len = (lenHdr[0] & 0xFF) | ((lenHdr[1] & 0xFF) << 8);
        byte[] frame = readN(in, len + 16);
        byte[] plain = chachaOpen(readKey, nonce64(counter), frame, lenHdr);
        // 解析 RTSP 明文
        String all = new String(plain, StandardCharsets.ISO_8859_1);
        int idx = all.indexOf("\r\n\r\n");
        String head = all.substring(0, idx);
        String[] lines = head.split("\r\n");
        Rtsp r = new Rtsp();
        r.statusLine = lines[0];
        int cl = 0;
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0 && lines[i].substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                cl = Integer.parseInt(lines[i].substring(c + 1).trim());
            }
        }
        r.body = new byte[cl];
        System.arraycopy(plain, idx + 4, r.body, 0, cl);
        return r;
    }

    static byte[] sealCtrl(byte[] key, long counter, byte[] plain) throws Exception {
        byte[] lenHdr = new byte[]{(byte) (plain.length & 0xFF), (byte) (plain.length >>> 8)};
        byte[] sealed = chachaSeal(key, nonce64(counter), plain, lenHdr);
        return concat(lenHdr, sealed);
    }

    static byte[] chachaSeal(byte[] key, byte[] nonce, byte[] plain, byte[] aad) throws Exception {
        Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        c.updateAAD(aad);
        return c.doFinal(plain);
    }

    static byte[] chachaOpen(byte[] key, byte[] nonce, byte[] sealed, byte[] aad) throws Exception {
        Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        c.updateAAD(aad);
        return c.doFinal(sealed);
    }

    static byte[] nonce64(long counter) {
        byte[] nonce = new byte[12];
        for (int i = 0; i < 8; i++) nonce[4 + i] = (byte) (counter >>> (8 * i));
        return nonce;
    }

    static byte[] nonceLabel(String label) {
        byte[] lbl = label.getBytes(StandardCharsets.US_ASCII);
        byte[] nonce = new byte[12];
        for (int i = 0; i < lbl.length && i < 8; i++) nonce[4 + i] = lbl[i];
        return nonce;
    }

    static Map<Integer, byte[]> parseTlv(byte[] data) {
        Map<Integer, byte[]> out = new java.util.LinkedHashMap<>();
        for (Tlv8.Tlv t : Tlv8.decode(data)) out.put(t.type, t.value);
        return out;
    }

    static byte[] readN(InputStream in, int n) throws Exception {
        byte[] out = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(out, off, n - off);
            if (r < 0) throw new RuntimeException("EOF");
            off += r;
        }
        return out;
    }

    static byte[] concat(byte[]... parts) {
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

    static byte[] hexDecode(byte[] hex) {
        byte[] out = new byte[hex.length / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex[i * 2], 16);
            int lo = Character.digit(hex[i * 2 + 1], 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
