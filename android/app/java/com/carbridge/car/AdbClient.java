package com.carbridge.car;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Locale;

import javax.crypto.Cipher;

import android.util.Base64;

/**
 * 最小 ADB 客户端：只做 CNXN / AUTH / shell，够在车机上跑 setprop 与重启 CarPlay。
 *
 * <p>为什么手机里要自带一个 adb：车机侧那条「后门」只有 adb 能写，而用户不方便把
 * 电脑带到车上。车机的 adbd 之前已经信任过 Mac 的密钥，所以把同一把私钥打进包里，
 * 连上去不需要在车机屏幕上点「允许调试」。
 *
 * <p>协议要点（踩过的坑都在这儿）：
 * <ul>
 *   <li>包头 24 字节、小端；magic = command ^ 0xffffffff</li>
 *   <li>新 adbd 的 checksum 恒为 0，老 adbd 才填真实和 → 两种都得接受</li>
 *   <li>AUTH 的 token 本身就是 20 字节摘要，签名要签 SHA1 DigestInfo + token，
 *       <b>不能</b>再对 token 做一次 SHA1</li>
 * </ul>
 */
final class AdbClient {

    interface Log {
        void log(String line);
    }

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_WRTE = 0x45545257;
    private static final int A_CLSE = 0x45534c43;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;
    private static final int MAX_DATA = 4096;

    private final Log log;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private int nextLocalId = 1;

    AdbClient(Log log) {
        this.log = log;
    }

    /** 连上并完成 AUTH。失败抛异常，调用方负责收尾。 */
    void connect(String host, int port, String privateKeyPem, String publicKey,
                 int timeoutMs) throws Exception {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setSoTimeout(20000);
        socket.setTcpNoDelay(true);
        in = socket.getInputStream();
        out = socket.getOutputStream();

        byte[] hostPayload = "host::\u0000".getBytes(StandardCharsets.UTF_8);
        send(A_CNXN, 0x01000001, MAX_DATA, hostPayload);

        int authCount = 0;
        boolean pubkeySent = false;
        long authDeadline = 0;
        while (true) {
            Packet p;
            try {
                p = readPacket();
            } catch (SocketTimeoutException e) {
                if (pubkeySent) {
                    throw new IOException("等待车机屏幕点「允许调试」超时", e);
                }
                throw e;
            }
            if (p.command == A_CNXN) {
                String banner = new String(p.data, StandardCharsets.UTF_8);
                log.log("ADB 已连接：" + banner.replace('\u0000', ' ').trim());
                return;
            }
            if (p.command == A_AUTH && p.arg0 == AUTH_TOKEN) {
                authCount++;
                if (pubkeySent && authDeadline > 0
                        && System.currentTimeMillis() > authDeadline) {
                    throw new IOException("等待车机屏幕点「允许调试」超时");
                }
                // 第一次先拿已授权的密钥签名；车机不认才把公钥发过去，
                // 让它在屏幕上弹「允许 USB 调试」（只该发生在第一次）。
                if (authCount == 2 && !pubkeySent && publicKey != null
                        && publicKey.trim().length() > 0) {
                    String pk = publicKey.trim();
                    if (!pk.endsWith("\u0000")) pk += "\u0000";
                    log.log("车机不认识这把密钥 → 发送公钥，等车机屏幕点「允许调试」…");
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, pk.getBytes(StandardCharsets.UTF_8));
                    pubkeySent = true;
                    authDeadline = System.currentTimeMillis() + 70000L;
                    socket.setSoTimeout(70000);
                    continue;
                }
                byte[] signature = signToken(privateKeyPem, p.data);
                send(A_AUTH, AUTH_SIGNATURE, 0, signature);
                continue;
            }
            throw new IOException("ADB 握手收到未知包：" + hex(p.command));
        }
    }

    /** 执行一条 shell 命令，返回合并后的 stdout/stderr。 */
    String shell(String command) throws Exception {
        int localId = nextLocalId++;
        byte[] payload = ("shell:" + command + "\u0000").getBytes(StandardCharsets.UTF_8);
        send(A_OPEN, localId, 0, payload);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int remoteId = -1;
        long deadline = System.currentTimeMillis() + 60000L;
        while (System.currentTimeMillis() < deadline) {
            Packet p;
            try {
                p = readPacket();
            } catch (SocketTimeoutException e) {
                throw new IOException("等待车机 shell 输出超时", e);
            }
            if (p.command == A_OKAY) {
                if (remoteId < 0) remoteId = p.arg1;
                continue;
            }
            if (p.command == A_WRTE) {
                if (remoteId < 0) remoteId = p.arg1;
                output.write(p.data);
                send(A_OKAY, localId, remoteId == -1 ? p.arg1 : remoteId, new byte[0]);
                continue;
            }
            if (p.command == A_CLSE) {
                try {
                    send(A_CLSE, localId, p.arg1, new byte[0]);
                } catch (Throwable ignored) {
                }
                return output.toString("UTF-8");
            }
            if (p.command == A_AUTH) {
                throw new IOException("shell 中途收到 AUTH，连接状态异常");
            }
        }
        throw new IOException("等待车机 shell 结束超时");
    }

    void close() {
        try {
            if (socket != null) socket.close();
        } catch (Throwable ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int checksum = 0;
        for (byte b : data) checksum += (b & 0xff);
        writeIntLE(command);
        writeIntLE(arg0);
        writeIntLE(arg1);
        writeIntLE(data.length);
        writeIntLE(checksum);
        writeIntLE(command ^ 0xffffffff);
        out.write(data);
        out.flush();
    }

    private Packet readPacket() throws IOException {
        byte[] header = readFully(24);
        int command = readIntLE(header, 0);
        int arg0 = readIntLE(header, 4);
        int arg1 = readIntLE(header, 8);
        int length = readIntLE(header, 12);
        int checksum = readIntLE(header, 16);
        int magic = readIntLE(header, 20);
        if ((command ^ 0xffffffff) != magic) {
            throw new IOException("ADB 包头 magic 不匹配");
        }
        if (length < 0 || length > 1024 * 1024) {
            throw new IOException("ADB 包长度异常：" + length);
        }
        byte[] data = readFully(length);
        int actual = 0;
        for (byte b : data) actual += (b & 0xff);
        if (checksum != 0 && actual != checksum) {
            throw new IOException("ADB 包校验和错误");
        }
        return new Packet(command, arg0, arg1, data);
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException("ADB 连接被车机关闭");
            off += r;
        }
        return buf;
    }

    private void writeIntLE(int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 24) & 0xff);
    }

    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xff)
                | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 3] & 0xff) << 24);
    }

    private static byte[] signToken(String pem, byte[] token) throws Exception {
        PrivateKey key = parsePrivateKey(pem);
        // SHA1 DigestInfo 前缀（15 字节）+ 20 字节 token
        byte[] prefix = new byte[] {
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
                0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        };
        byte[] digestInfo = new byte[prefix.length + token.length];
        System.arraycopy(prefix, 0, digestInfo, 0, prefix.length);
        System.arraycopy(token, 0, digestInfo, prefix.length, token.length);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(digestInfo);
    }

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.decode(base64, Base64.DEFAULT);
        return KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String hex(int v) {
        return String.format(Locale.US, "0x%08x", v);
    }

    private static final class Packet {
        final int command;
        final int arg0;
        final int arg1;
        final byte[] data;

        Packet(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }
}
