package com.carbridge.fakecar;

import com.carbridge.fakecar.core.AirPlayStreamCrypto;
import com.carbridge.fakecar.core.Bplist;
import com.carbridge.fakecar.pairing.ControlCipher;
import com.carbridge.fakecar.pairing.Ed25519;
import com.carbridge.fakecar.pairing.Tlv8;
import com.carbridge.fakecar.pairing.X25519;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 便携假车机的 <b>AirPlay 接收端</b>（1:1 复刻 strict_fakecar.py 的 AirPlayServer）。
 *
 * <p>车机角色在 WiFi 上扮演 AirPlay <b>接收端</b>：目标手机的 carbridge 会主动连过来，
 * 走 pair-verify(M1~M4) → 控制通道 ChaCha20 → ANNOUNCE/SETUP//info → 屏幕流(110)解密 H.264
 * → 音频流(100)解密。同时开好视频 TCP / 音频 UDP / 事件 TCP / keepalive UDP 四个通道。
 */
public final class AirPlayServer {

    public interface Log {
        void onLog(String line);
    }

    public interface Phase {
        void onPhase(String name);   // "airplay", "screen", "audio"
    }

    public interface VideoFrame {
        /** 收到 avcC 配置（含 SPS/PPS），解码器可据此初始化。 */
        void onConfig(byte[] avcC);

        /** 收到一帧已解密的 AVCC 格式 H.264（4 字节长度 + NAL）。 */
        void onFrame(int frameNum, byte[] avcc);
    }

    // ------------------------------------------------------------------ 常量
    private static final int HEADER_SIZE = 128;
    private static final int CTRL_TAG = 16;

    // HID 描述符（与真车 /info 一致：触摸 + 电话键 + 消费键）
    private static final String TOUCH_UUID = "eso_touchScreen";
    private static final String KEY_UUID = "eso_playback";
    private static final String TEL_UUID = "eso_telephony";

    private static final byte[] TOUCH_DESCRIPTOR = new byte[]{
            0x05, 0x01, 0x09, 0x01, (byte) 0xA1, 0x01,
            0x05, 0x0D, 0x09, 0x04, (byte) 0xA1, 0x02,
            0x09, 0x42, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, (byte) 0x95, 0x01, (byte) 0x81, 0x02,
            0x75, 0x07, (byte) 0x95, 0x01, (byte) 0x81, 0x01,
            0x09, 0x30, 0x15, 0x00, 0x26, (byte) 0xFF, 0x7F, 0x00, 0x00,
            0x75, 0x10, (byte) 0x95, 0x01, (byte) 0x81, 0x02,
            0x09, 0x31, 0x15, 0x00, 0x26, (byte) 0xFF, 0x7F, 0x00, 0x00,
            0x75, 0x10, (byte) 0x95, 0x01, (byte) 0x81, 0x02,
            (byte) 0xC0, (byte) 0xC0};

    private static final byte[] KEY_DESCRIPTOR = new byte[]{
            0x05, 0x0C, 0x09, 0x01, (byte) 0xA1, 0x01,
            0x09, 0x00, 0x09, (byte) 0xB0, 0x09, (byte) 0xB1, 0x09, (byte) 0xB5, 0x09, (byte) 0xB6,
            0x15, 0x00, 0x25, 0x04, 0x75, 0x08, (byte) 0x95, 0x01, (byte) 0x81, 0x00,
            (byte) 0xC0};

    private static final byte[] TEL_DESCRIPTOR = new byte[]{
            0x05, 0x0B, 0x09, 0x07, (byte) 0xA1, 0x01,
            0x09, 0x00, 0x09, 0x20, 0x09, 0x21, 0x09, 0x26,
            0x15, 0x00, 0x25, 0x03, 0x75, 0x08, (byte) 0x95, 0x01, (byte) 0x81, 0x00,
            (byte) 0xC0};

    private final Log log;
    private final Phase phase;
    private final VideoFrame videoFrame;
    private final int apPort;

    private volatile boolean running;
    private ServerSocket controlSrv;
    private ServerSocket videoSrv;
    private DatagramSocket audioSock;
    private ServerSocket eventSrv;
    private DatagramSocket keepaliveSock;

    private int videoPort;
    private int audioPort;
    private int eventPort;
    private int keepalivePort;

    // pair-verify 状态
    private volatile byte[] shared;
    private volatile byte[] videoKey;
    private volatile byte[] audioKey;
    private volatile long videoConnId;
    private volatile long audioConnId;

    // 统计 / 阶段
    private volatile int videoFrames;
    private volatile int audioPackets;
    private volatile boolean sawAnnounce;
    private volatile boolean sawConfig;

    // 事件通道（触摸/硬键）
    private volatile Socket eventConn;
    private volatile ControlCipher eventEnc;
    /** 由 audioLoop 线程写、stop 线程释放 → volatile 保证可见性。 */
    private volatile android.media.AudioTrack audioTrack;
    private int writeErr;
    private int playErr;
    private int eventCounter;
    private int eventCseq = 1;

    /** /info 里自报的身份，默认用真车（Desay SV / 320.17.7）。 */
    public volatile String infoDeviceId = "88:BD:78:7A:56:09";
    public volatile String infoModel    = "Desay SV";
    public volatile String infoMaker    = "Desay SV";
    public volatile String infoName     = "CarPlay";
    public volatile String infoSrcvers  = "320.17.7";

    public AirPlayServer(Log log, Phase phase, VideoFrame videoFrame, int apPort) {
        this.log = log;
        this.phase = phase;
        this.videoFrame = videoFrame;
        this.apPort = apPort;
    }

    public int videoPort() {
        return videoPort;
    }

    public int audioPort() {
        return audioPort;
    }

    public int eventPort() {
        return eventPort;
    }

    public int keepalivePort() {
        return keepalivePort;
    }

    public int videoFrames() {
        return videoFrames;
    }

    public int audioPackets() {
        return audioPackets;
    }

    public boolean sawScreen() {
        return videoFrames > 0;
    }

    // ================================================================== 启动

    public synchronized void start() throws IOException {
        if (running) return;
        running = true;

        videoSrv = new ServerSocket(0);
        videoPort = videoSrv.getLocalPort();

        audioSock = new DatagramSocket(0);
        audioPort = audioSock.getLocalPort();

        eventSrv = new ServerSocket(0);
        eventPort = eventSrv.getLocalPort();

        keepaliveSock = new DatagramSocket(0);
        keepalivePort = keepaliveSock.getLocalPort();

        spawn("fakecar-video", new Runnable() {
            @Override
            public void run() {
                acceptVideoLoop();
            }
        });
        spawn("fakecar-audio", new Runnable() {
            @Override
            public void run() {
                audioLoop();
            }
        });
        spawn("fakecar-event", new Runnable() {
            @Override
            public void run() {
                acceptEventLoop();
            }
        });
        spawn("fakecar-keepalive", new Runnable() {
            @Override
            public void run() {
                keepaliveLoop();
            }
        });

        controlSrv = new ServerSocket(apPort);
        emit("[ap] AirPlay server 0.0.0.0:" + apPort + " listening");
        spawn("fakecar-control", new Runnable() {
            @Override
            public void run() {
                controlLoop();
            }
        });
    }

    public synchronized void stop() {
        running = false;
        closeQuietly(controlSrv);
        closeQuietly(videoSrv);
        closeQuietly(audioSock);
        closeQuietly(eventSrv);
        closeQuietly(keepaliveSock);
        stopAudioTrack();
        eventExecutor.shutdownNow();
    }

    private void spawn(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    // ================================================================== 控制通道

    private void controlLoop() {
        while (running) {
            try {
                final Socket c = controlSrv.accept();
                spawn("fakecar-ctrl-" + c.getInetAddress().getHostAddress(), new Runnable() {
                    @Override
                    public void run() {
                        handleControl(c);
                    }
                });
            } catch (IOException e) {
                if (running) emit("[ap] control accept error: " + e);
            }
        }
    }

    private void handleControl(Socket c) {
        emit("[ap] control conn " + c.getInetAddress().getHostAddress() + ":" + c.getPort());
        try {
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();

            // —— 明文阶段：pair-verify ——
            for (int step = 0; step < 2; step++) {
                RtspMessage req = parseRtspPlain(in);
                if (req == null) {
                    emit("[ap] pair-verify 连接提前关闭");
                    return;
                }
                emit("[ap] <- " + req.method + " " + req.path + " (body=" + req.body.length + "B)");
                if (!"/pair-verify".equals(req.path) || !"POST".equals(req.method)) {
                    emit("[ap] 期望 /pair-verify，收到 " + req.path);
                    return;
                }
                Map<Integer, byte[]> tlv = parseTlv(req.body);
                byte[] state = tlv.get(Tlv8.STATE);
                if (step == 0 && state != null && state.length == 1 && state[0] == 0x01) {
                    replyM2(out, tlv);
                } else if (step == 1 && state != null && state.length == 1 && state[0] == 0x03) {
                    replyM4(out);
                    break;
                } else {
                    emit("[ap] 未知 pair-verify state=" + (state == null ? "null" : state.length + "B"));
                    return;
                }
            }

            // —— 加密阶段 ——
            // 车机收手机：手机用 Control-Write 加密 → 车机用 Write 密钥解；
            // 车机回手机：车机用 Control-Read 加密 → 手机用 Read 密钥解。
            // 帧格式：2 字节小端密文长度 ‖ 密文 ‖ 16 字节 tag；nonce = 4 字节 0 ‖ 8 字节小端计数器。
            final byte[] writeKey = controlKey(shared, true);
            final byte[] readKey = controlKey(shared, false);
            long wc = 0, rc = 0;
            emit("[ap] 控制通道已转 ChaCha20-Poly1305 加密");

            while (running) {
                byte[] lenHdr;
                byte[] frame;
                try {
                    lenHdr = readN(in, 2);
                    int len = (lenHdr[0] & 0xFF) | ((lenHdr[1] & 0xFF) << 8);
                    frame = readN(in, len + CTRL_TAG);
                } catch (EOFException e) {
                    break;
                } catch (IOException e) {
                    break;
                }
                byte[] plain = chachaOpen(writeKey, nonce64(wc), frame, lenHdr);
                wc++;

                RtspMessage parsed = parseRtspPlaintext(plain);
                if (parsed == null) continue;
                emit("[ap] <- " + parsed.method + " " + parsed.path + " (body=" + parsed.body.length + "B)");
                byte[] respBody = dispatch(parsed.method, parsed.path, parsed.headers, parsed.body);
                String cseq = parsed.headers.get("CSeq");
                if (cseq == null) cseq = "1";
                byte[] resp = rtspOk(cseq, respBody);
                byte[] lenHdr2 = lenLe(resp.length);
                byte[] sealed = chachaSeal(readKey, nonce64(rc), resp, lenHdr2);
                rc++;
                // 帧 = 2 字节小端长度头 ‖ 密文 ‖ 16 字节 tag（长度头同时是 AAD）
                out.write(lenHdr2);
                out.write(sealed);
                out.flush();
                emit("[ap] -> 200 " + parsed.method + " " + parsed.path + " (resp=" + resp.length + "B)");
            }
        } catch (Exception e) {
            emit("[ap] control conn error: " + e);
        } finally {
            closeQuietly(c);
        }
        emit("[ap] control conn closed");
    }

    // ------------------------------------------------------------- pair-verify

    private void replyM2(OutputStream out, Map<Integer, byte[]> tlv) throws Exception {
        byte[] phoneEph = tlv.get(Tlv8.PUBLIC_KEY);
        if (phoneEph == null || phoneEph.length != 32) {
            emit("[ap] M1 缺公钥");
            return;
        }
        byte[] ephPriv = X25519.generatePrivateKey();
        byte[] ephPub = X25519.publicKey(ephPriv);
        shared = X25519.sharedSecret(ephPriv, phoneEph);

        Ed25519.KeyPair idKey = Ed25519.generate();
        String carId = hex(idKey.publicKey);
        byte[] carIdBytes = carId.getBytes(StandardCharsets.US_ASCII);
        byte[] signed = concat(ephPub, carIdBytes, phoneEph);
        byte[] sig = Ed25519.sign(idKey.seed, signed);

        byte[] pvKey = AirPlayStreamCrypto.hkdfSha512(shared,
                "Pair-Verify-Encrypt-Salt".getBytes(StandardCharsets.US_ASCII),
                "Pair-Verify-Encrypt-Info".getBytes(StandardCharsets.US_ASCII), 32);
        byte[] sub = concat(Tlv8.encode(Tlv8.IDENTIFIER, carIdBytes),
                Tlv8.encode(Tlv8.SIGNATURE, sig));
        byte[] sealed = chachaSeal(pvKey, nonceLabel("PV-Msg02"), sub, new byte[0]);

        byte[] m2 = concat(Tlv8.encodeUInt(Tlv8.STATE, 2),
                Tlv8.encode(Tlv8.PUBLIC_KEY, ephPub),
                Tlv8.encode(Tlv8.ENCRYPTED_DATA, sealed));
        out.write(rtspOk("1", m2));
        out.flush();
        emit("[ap] -> pair-verify M2（id=" + carId.substring(0, 8) + "…）");
    }

    private void replyM4(OutputStream out) throws IOException {
        byte[] m4 = Tlv8.encodeUInt(Tlv8.STATE, 4);
        out.write(rtspOk("2", m4));
        out.flush();
        emit("[ap] -> pair-verify M4（配对成功）");
    }

    // ------------------------------------------------------------- 分发

    private byte[] dispatch(String method, String path, Map<String, String> headers, byte[] body) {
        if ("ANNOUNCE".equals(method)) {
            sawAnnounce = true;
            firePhase("airplay");
            emit("[ap] ✓ ANNOUNCE（path=" + path + "）");
            return null;
        }
        if ("SETUP".equals(method) && "/setup".equals(path)) {
            return bplist(map(
                    "eventPort", (long) eventPort,
                    "keepAlivePort", (long) keepalivePort));
        }
        if ("GET".equals(method) && "/info".equals(path)) {
            return infoBody();
        }
        if ("SETUP".equals(method) && "/".equals(path)) {
            return streamSetup(body);
        }
        if ("RECORD".equals(method)) return null;
        if ("POST".equals(method) && "/command".equals(path)) return null;
        if ("POST".equals(method) && "/feedback".equals(path)) return null;
        if ("SETUP".equals(method) || "GET".equals(method) || "POST".equals(method)) return null;
        emit("[ap] 未处理 " + method + " " + path);
        return null;
    }

    @SuppressWarnings("unchecked")
    private byte[] infoBody() {
        Map<String, Object> display = map(
                "widthPixels", 1920L,
                "heightPixels", 720L,
                "uuid", "00000000-0000-0000-0000-000000000001",
                "maxFPS", 30L);
        Map<String, Object> audioFmt = map(
                "type", 100L,
                "audioType", "media",
                "audioOutputFormats", 0x800L,
                "audioInputFormats", 0L);
        Map<String, Object> touch = map(
                "uuid", TOUCH_UUID, "name", "eso_touchScreen", "hidDescriptor", TOUCH_DESCRIPTOR);
        Map<String, Object> tel = map(
                "uuid", TEL_UUID, "name", "eso_telephony", "hidDescriptor", TEL_DESCRIPTOR);
        Map<String, Object> key = map(
                "uuid", KEY_UUID, "name", "eso_playback", "hidDescriptor", KEY_DESCRIPTOR);
        List<Object> displays = java.util.Arrays.asList((Object) display);
        List<Object> audioFormats = java.util.Arrays.asList((Object) audioFmt);
        List<Object> hids = java.util.Arrays.asList((Object) touch, tel, key);
        Map<String, Object> root = map(
                "deviceid", infoDeviceId,
                "model", infoModel,
                "manufacturer", infoMaker,
                "name", infoName,
                "srcvers", infoSrcvers,
                "displays", displays,
                "audioFormats", audioFormats,
                "hidDevices", hids);
        return bplist(root);
    }

    @SuppressWarnings("unchecked")
    private byte[] streamSetup(byte[] body) {
        Object obj = Bplist.read(body);
        if (!(obj instanceof Map)) return bplist(map("streams", java.util.Arrays.asList()));
        Map<String, Object> root = (Map<String, Object>) obj;
        Object streamsObj = root.get("streams");
        java.util.List<Map<String, Object>> outStreams = new java.util.ArrayList<>();
        if (streamsObj instanceof List) {
            for (Object s : (List<Object>) streamsObj) {
                if (!(s instanceof Map)) continue;
                Map<String, Object> st = (Map<String, Object>) s;
                long type = num(st.get("type"));
                long connId = num(st.get("streamConnectionID"));
                if (type == 110) {
                    videoConnId = connId;
                    try {
                        videoKey = AirPlayStreamCrypto.derive(shared, connId);
                    } catch (GeneralSecurityException e) {
                        emit("[ap] 视频密钥派生失败: " + e);
                    }
                    outStreams.add(map("type", 110L, "dataPort", (long) videoPort));
                    emit("[ap] ✓ 屏幕流 SETUP type=110 connId=" + connId + " dataPort=" + videoPort);
                } else if (type == 100 || type == 102) {
                    audioConnId = connId;
                    try {
                        audioKey = AirPlayStreamCrypto.derive(shared, connId);
                    } catch (GeneralSecurityException e) {
                        emit("[ap] 音频密钥派生失败: " + e);
                    }
                    outStreams.add(map("type", type, "dataPort", (long) audioPort));
                    emit("[ap] ✓ 音频流 SETUP type=" + type + " connId=" + connId + " dataPort=" + audioPort + "(UDP)");
                }
            }
        }
        return bplist(map("streams", outStreams));
    }

    // ------------------------------------------------------------- 数据通道

    private void acceptVideoLoop() {
        while (running) {
            try {
                final Socket c = videoSrv.accept();
                spawn("fakecar-vid-" + c.getInetAddress().getHostAddress(), new Runnable() {
                    @Override
                    public void run() {
                        videoLoop(c);
                    }
                });
            } catch (IOException e) {
                if (running) emit("[ap] video accept error: " + e);
            }
        }
    }

    private void videoLoop(Socket c) {
        emit("[ap] video data conn " + c.getInetAddress().getHostAddress() + ":" + c.getPort());
        long counter = 0;
        try {
            InputStream in = c.getInputStream();
            while (running) {
                byte[] hdr = readN(in, HEADER_SIZE);
                if (hdr == null) break;
                long bodySize = le32(hdr, 0);
                int opcode = hdr[4] & 0xFF;
                if (bodySize == 0) continue;
                byte[] body = readN(in, (int) bodySize);
                if (body == null) break;
                if (opcode == 1) {           // CONFIG 明文（avcC）
                    sawConfig = true;
                    emit("[ap] VideoConfig avcC=" + body.length + "B");
                    if (videoFrame != null) videoFrame.onConfig(body);
                    continue;
                }
                if (opcode == 2 || opcode == 5) {   // keepalive 明文
                    continue;
                }
                if (opcode == 0) {          // FRAME 加密
                    if (videoKey == null) continue;
                    try {
                        byte[] plain = chachaOpen(videoKey, nonce64(counter), body, hdr);
                        counter++;
                        videoFrames++;
                        onVideoFrame(plain);
                    } catch (Exception e) {
                        emit("[ap] 视频解密失败: " + e);
                    }
                } else {
                    emit("[ap] 未知 opcode " + opcode);
                }
            }
        } catch (Exception e) {
            emit("[ap] video stream error: " + e);
        } finally {
            closeQuietly(c);
        }
    }

    private void onVideoFrame(byte[] avcc) {
        List<byte[]> nals = splitAvcc(avcc);
        StringBuilder names = new StringBuilder();
        for (byte[] b : nals) {
            if (b.length == 0) continue;
            int t = b[0] & 0x1F;
            names.append(t == 1 ? "slice" : t == 5 ? "IDR" : t == 7 ? "SPS" : t == 8 ? "PPS" : String.valueOf(t))
                    .append(",");
        }
        if (videoFrames <= 3) {
            StringBuilder hex = new StringBuilder();
            for (byte[] b : nals) {
                if (b.length > 0) hex.append(String.format("%02x(%d) ", b[0], b[0] & 0x1F));
            }
            emit("[ap] frame#" + videoFrames + " NAL头: " + hex + " 共" + nals.size() + "个NAL");
        }
        if (videoFrames == 1 || videoFrames % 60 == 0) {
            emit("[ap] video frame #" + videoFrames + " " + avcc.length + "B (nal=" + names + ")");
        }
        if (videoFrames == 1) firePhase("screen");
        if (videoFrame != null) videoFrame.onFrame(videoFrames, avcc);
    }

    private void audioLoop() {
        byte[] buf = new byte[4096];
        while (running) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                audioSock.receive(p);
            } catch (IOException e) {
                if (running) emit("[ap] audio recv error: " + e);
                continue;
            }
            byte[] data = new byte[p.getLength()];
            System.arraycopy(p.getData(), p.getOffset(), data, 0, p.getLength());
            audioPackets++;
            if (audioPackets == 1) {
                firePhase("audio");
                emit("[ap] ✓ 音频流首包 " + data.length + "B");
            }
            if (audioPackets % 500 == 0) emit("[ap] 音频已收 " + audioPackets + " 包");
            if (audioKey != null && data.length > 12 + 16 + 8) {
                try {
                    byte[] hdr = java.util.Arrays.copyOfRange(data, 0, 12);
                    byte[] aad = java.util.Arrays.copyOfRange(data, 4, 12);
                    byte[] sealed = java.util.Arrays.copyOfRange(data, 12, data.length - 8);
                    byte[] n8 = java.util.Arrays.copyOfRange(data, data.length - 8, data.length);
                    byte[] nonce = new byte[12];
                    System.arraycopy(n8, 0, nonce, 4, 8);
                    byte[] plain = chachaOpen(audioKey, nonce, sealed, aad);
                    if (audioPackets <= 2) {
                        emit("[ap] 音频解密 OK " + plain.length + "B");
                    }
                    playPcm(plain);
                } catch (Exception e) {
                    // 音频解密失败不致命，静默
                }
            }
        }
    }

    /**
     * 播放解密出的 PCM（假车机的「扬声器」）。
     *
     * <p>CarPlay 无线音频：44100Hz / 双声道 / 16bit **大端**（与手机侧 RTP 打包一致，
     * 352 帧/包 × 4B = 1408B，125 包/秒 ≈ 44100 帧/秒）。Android AudioTrack 只吃小端，
     * 所以逐样本高低字节交换后再写。
     */
    private void playPcm(byte[] pcm) {
        if (pcm == null || pcm.length < 2) return;
        try {
            if (audioTrack == null) {
                int minBuf = android.media.AudioTrack.getMinBufferSize(44100,
                        android.media.AudioFormat.CHANNEL_OUT_STEREO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT);
                audioTrack = new android.media.AudioTrack(
                        android.media.AudioManager.STREAM_MUSIC, 44100,
                        android.media.AudioFormat.CHANNEL_OUT_STEREO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(minBuf * 4, 1408 * 20),
                        android.media.AudioTrack.MODE_STREAM);
                audioTrack.play();
                emit("[ap] 扬声器已开启（44100Hz 立体声，minBuf=" + minBuf + "）");
            }
            // 大端 → 小端
            for (int i = 0; i + 1 < pcm.length; i += 2) {
                byte t = pcm[i];
                pcm[i] = pcm[i + 1];
                pcm[i + 1] = t;
            }
            int w = audioTrack.write(pcm, 0, pcm.length);
            if (w < 0 && writeErr++ < 3) emit("[ap] 音频写入失败: " + w);
        } catch (Throwable t) {
            if (playErr++ < 3) emit("[ap] 扬声器异常: " + t);
        }
    }

    private void stopAudioTrack() {
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception ignored) {
            }
            audioTrack = null;
        }
    }

    private void acceptEventLoop() {
        while (running) {
            try {
                final Socket c = eventSrv.accept();
                emit("[ap] event conn " + c.getInetAddress().getHostAddress() + "（触摸/硬键通道）");
                eventConn = c;
                try {
                    eventEnc = ControlCipher.events(shared, true, true);
                } catch (GeneralSecurityException e) {
                    eventEnc = null;
                }
                spawn("fakecar-event-hold", new Runnable() {
                    @Override
                    public void run() {
                        eventHold(c);
                    }
                });
            } catch (IOException e) {
                if (running) emit("[ap] event accept error: " + e);
            }
        }
    }

    private void eventHold(Socket c) {
        byte[] buf = new byte[4096];
        try {
            while (c.getInputStream().read(buf) >= 0) {
                // 只读不处理（手机→车机的事件通道内容我们不需要）
            }
        } catch (IOException ignored) {
        } finally {
            if (eventConn == c) {
                eventConn = null;
                eventEnc = null;
            }
            closeQuietly(c);
        }
    }

    private void keepaliveLoop() {
        byte[] buf = new byte[128];
        while (running) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                keepaliveSock.receive(p);
            } catch (IOException e) {
                if (running) emit("[ap] keepalive recv error: " + e);
            }
        }
    }

    // ================================================================== 触摸 / 硬键

    public void sendTouch(float normX, float normY, boolean down) {
        int lx = Math.max(0, Math.min(32767, (int) (normX * 32767)));
        int ly = Math.max(0, Math.min(32767, (int) (normY * 32767)));
        byte[] report = new byte[]{(byte) (down ? 1 : 0), (byte) (lx & 0xFF), (byte) (lx >> 8),
                (byte) (ly & 0xFF), (byte) (ly >> 8)};
        sendEvent(bplist(map("type", "hidSendReport", "uuid", TOUCH_UUID, "hidReport", report)));
        emit("[ap] 触摸 -> (" + normX + "," + normY + ") down=" + down);
    }

    public void sendHardKey(int index) {
        sendEvent(bplist(map("type", "hidSendReport", "uuid", KEY_UUID,
                "hidReport", new byte[]{(byte) (index & 0xFF)})));
        emit("[ap] 硬键 index=" + index);
    }

    private final java.util.concurrent.ExecutorService eventExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "fakecar-event-tx");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    private synchronized void sendEvent(byte[] bodyBplist) {
        final Socket c = eventConn;
        final ControlCipher enc = eventEnc;
        if (c == null || enc == null || shared == null) return;
        final int cseq = eventCseq++;
        final byte[] msg = rtspRequest("POST", "/command", cseq, bodyBplist);
        // ⚠️ 触摸来自 UI 线程，socket 写必须后台执行（NetworkOnMainThreadException）
        try {
            eventExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] sealed = enc.encrypt(msg);
                        c.getOutputStream().write(sealed);
                        c.getOutputStream().flush();
                    } catch (Exception e) {
                        emit("[ap] 事件发送失败: " + e);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // stop() 后 executor 已 shutdown；丢弃本次事件，别把触摸线程带崩
        }
    }

    // ================================================================== 工具

    private void emit(String line) {
        if (log != null) log.onLog(line);
    }

    private void firePhase(String name) {
        if (phase != null) phase.onPhase(name);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static byte[] bplist(Object o) {
        return Bplist.write(o);
    }

    private static long num(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof Boolean) return ((Boolean) o) ? 1 : 0;
        return 0;
    }

    // ------------------------------------------------------------- ChaCha

    private static byte[] chachaSeal(byte[] key, byte[] nonce, byte[] plain, byte[] aad)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        c.updateAAD(aad);
        return c.doFinal(plain);
    }

    private static byte[] chachaOpen(byte[] key, byte[] nonce, byte[] sealed, byte[] aad)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        c.updateAAD(aad);
        return c.doFinal(sealed);
    }

    /** 控制通道密钥：Write（手机→车机）/ Read（车机→手机）。 */
    private static byte[] controlKey(byte[] shared, boolean write) throws GeneralSecurityException {
        return AirPlayStreamCrypto.hkdfSha512(shared,
                "Control-Salt".getBytes(StandardCharsets.US_ASCII),
                (write ? "Control-Write-Encryption-Key" : "Control-Read-Encryption-Key")
                        .getBytes(StandardCharsets.US_ASCII), 32);
    }

    private static byte[] lenLe(int n) {
        return new byte[]{(byte) (n & 0xFF), (byte) (n >>> 8)};
    }

    private static byte[] nonce64(long counter) {
        byte[] nonce = new byte[12];
        for (int i = 0; i < 8; i++) nonce[4 + i] = (byte) (counter >>> (8 * i));
        return nonce;
    }

    private static byte[] nonceLabel(String label) {
        byte[] lbl = label.getBytes(StandardCharsets.US_ASCII);
        byte[] nonce = new byte[12];
        for (int i = 0; i < lbl.length && i < 8; i++) nonce[4 + i] = lbl[i];
        return nonce;
    }

    // ------------------------------------------------------------- TLV8 / RTSP

    private static Map<Integer, byte[]> parseTlv(byte[] data) {
        Map<Integer, byte[]> out = new LinkedHashMap<>();
        for (Tlv8.Tlv t : Tlv8.decode(data)) out.put(t.type, t.value);
        return out;
    }

    private static byte[] rtspOk(String cseq, byte[] body) {
        if (body == null) {
            return ("RTSP/1.0 200 OK\r\nCSeq: " + cseq
                    + "\r\nServer: AirTunes/320.17.7"
                    + "\r\nX-Apple-HKP: 0"
                    + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        }
        return concat(
                ("RTSP/1.0 200 OK\r\nCSeq: " + cseq
                        + "\r\nContent-Type: application/x-apple-binary-plist\r\n"
                        + "Content-Length: " + body.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1),
                body);
    }

    private static byte[] rtspRequest(String method, String path, int cseq, byte[] body) {
        if (body == null) {
            return (method + " " + path + " RTSP/1.0\r\nCSeq: " + cseq + "\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1);
        }
        return concat(
                (method + " " + path + " RTSP/1.0\r\nCSeq: " + cseq
                        + "\r\nContent-Type: application/x-apple-binary-plist\r\n"
                        + "Content-Length: " + body.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1),
                body);
    }

    // ------------------------------------------------------------- RTSP 解析

    private static final class RtspMessage {
        final String method;
        final String path;
        final Map<String, String> headers;
        final byte[] body;

        RtspMessage(String method, String path, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }
    }

    private static RtspMessage parseRtspPlain(InputStream in) throws IOException {
        ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
        byte[] one = new byte[1];
        int state = 0; // 0=读头部直到 \r\n\r\n
        while (true) {
            int n = in.read(one);
            if (n < 0) return null;
            headBuf.write(one[0]);
            byte[] b = headBuf.toByteArray();
            int len = b.length;
            if (len >= 4 && b[len - 4] == '\r' && b[len - 3] == '\n'
                    && b[len - 2] == '\r' && b[len - 1] == '\n') {
                break;
            }
        }
        String head = new String(headBuf.toByteArray(), StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n");
        String[] reqLine = lines[0].split(" ");
        String method = reqLine[0];
        String path = reqLine.length > 1 ? reqLine[1] : "/";
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int idx = lines[i].indexOf(':');
            if (idx > 0) headers.put(lines[i].substring(0, idx).trim(),
                    lines[i].substring(idx + 1).trim());
        }
        int cl = 0;
        try {
            cl = Integer.parseInt(headers.containsKey("Content-Length")
                    ? headers.get("Content-Length") : "0");
        } catch (NumberFormatException ignored) {
        }
        byte[] body = readN(in, cl);
        if (body == null) body = new byte[0];
        return new RtspMessage(method, path, headers, body);
    }

    private static RtspMessage parseRtspPlaintext(byte[] plain) {
        int idx = indexOf(plain, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        if (idx < 0) return null;
        String head = new String(plain, 0, idx, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n");
        String[] reqLine = lines[0].split(" ");
        if (reqLine.length < 2) return null;
        String method = reqLine[0];
        String path = reqLine[1];
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) headers.put(lines[i].substring(0, c).trim(), lines[i].substring(c + 1).trim());
        }
        int cl = 0;
        try {
            cl = Integer.parseInt(headers.containsKey("Content-Length")
                    ? headers.get("Content-Length") : "0");
        } catch (NumberFormatException ignored) {
        }
        int bodyStart = idx + 4;
        int bodyEnd = Math.min(plain.length, bodyStart + cl);
        byte[] body = new byte[Math.max(0, bodyEnd - bodyStart)];
        System.arraycopy(plain, bodyStart, body, 0, body.length);
        return new RtspMessage(method, path, headers, body);
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] out = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(out, off, n - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
        return out;
    }

    // ------------------------------------------------------------- 二进制工具

    private static long le32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private static List<byte[]> splitAvcc(byte[] data) {
        java.util.List<byte[]> nals = new java.util.ArrayList<>();
        int off = 0;
        while (off + 4 <= data.length) {
            int n = (int) ((data[off] & 0xFFL) << 24 | (data[off + 1] & 0xFFL) << 16
                    | (data[off + 2] & 0xFFL) << 8 | (data[off + 3] & 0xFFL));
            off += 4;
            if (n <= 0 || off + n > data.length) break;
            byte[] nal = new byte[n];
            System.arraycopy(data, off, nal, 0, n);
            off += n;
            nals.add(nal);
        }
        return nals;
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

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
