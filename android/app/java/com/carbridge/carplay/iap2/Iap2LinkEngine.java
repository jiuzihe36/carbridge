package com.carbridge.carplay.iap2;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * iAP2 链路层状态机（发送端 / iPhone 角色）。
 */
public final class Iap2LinkEngine {

    /** 底层字节通道（蓝牙 RFCOMM 或 WiFi TCP）。 */
    public interface Transport {
        void send(byte[] bytes);
    }

    public interface Listener {
        void onState(State state);

        /** 收到一条完整的 CSM 消息（控制会话）。 */
        void onMessage(Iap2Csm.Message message);

        void onLog(String msg);
    }

    public enum State { IDLE, DETECTING, NEGOTIATING, NORMAL, CLOSED }

    public enum Role {
        /** 主动发 SYN（无线模式下的手机）。 */
        INITIATOR,
        /** 等对端 SYN 再回 SYN|ACK。 */
        RESPONDER
    }

    private static final int DEFAULT_CONTROL_SESSION = 1;

    private final Transport transport;
    private final Listener listener;
    private final Role role;

    private final ByteArrayOutputStream stream = new ByteArrayOutputStream();
    private final Iap2Csm.Parser csmParser = new Iap2Csm.Parser();

    private State state = State.IDLE;
    private int txSeq = 99;          // 实测初始序号 99，u8 回绕
    private int lastSentSeq = 98;    // ACK-only 要带 seq-1
    private int rxExpected = -1;     // 下一个期望的入向序号，-1 表示未定
    private int controlSessionId = DEFAULT_CONTROL_SESSION;

    private Iap2LinkPacket.LinkConfig peerConfig;
    private boolean peerDetected;
    private boolean sentSyn;

    public Iap2LinkEngine(Transport transport, Listener listener, Role role) {
        this.transport = transport;
        this.listener = listener;
        this.role = role;
    }

    public State state() {
        return state;
    }

    /** 采纳的控制会话 id（来自对端 SYN 的会话表；没收到则为默认 10）。 */
    public int controlSessionId() {
        return controlSessionId;
    }

    public Iap2LinkPacket.LinkConfig peerConfig() {
        return peerConfig;
    }

    // ================================================================== 生命周期

    public void start() {
        if (state != State.IDLE) return;
        setState(State.DETECTING);
        sendRaw(Iap2LinkPacket.DETECT);
        log("发送 detect marker");
    }

    public void close() {
        setState(State.CLOSED);
    }

    // ================================================================== 入向

    /** 喂入底层收到的字节。 */
    public synchronized void onBytes(byte[] data) {
        stream.write(data, 0, data.length);
        byte[] buf = stream.toByteArray();
        int off = 0;
        while (true) {
            if (buf.length - off < 2) break;
            int b0 = buf[off] & 0xFF;
            int b1 = buf[off + 1] & 0xFF;
            if (b0 == 0xFF && b1 == 0x55) {
                if (buf.length - off < Iap2LinkPacket.DETECT.length) break;
                byte[] marker = new byte[Iap2LinkPacket.DETECT.length];
                System.arraycopy(buf, off, marker, 0, marker.length);
                off += marker.length;
                onDetect(marker);
            } else if (b0 == 0xFF && b1 == 0x5A) {
                if (buf.length - off < Iap2LinkPacket.HEADER_SIZE) break;
                int total = Iap2LinkPacket.getShortBE(buf, off + 2);
                if (total < Iap2LinkPacket.HEADER_SIZE || total > Iap2LinkPacket.MAX_PAYLOAD) {
                    off++; // 非法长度，跳过一个字节重新同步
                    continue;
                }
                if (buf.length - off < total) break;
                byte[] frame = new byte[total];
                System.arraycopy(buf, off, frame, 0, total);
                off += total;
                try {
                    onPacket(Iap2LinkPacket.decode(frame));
                } catch (RuntimeException e) {
                    log("丢弃坏帧：" + e.getMessage());
                }
            } else {
                off++; // 重新同步
            }
        }
        stream.reset();
        if (off < buf.length) stream.write(buf, off, buf.length - off);
    }

    private void onDetect(byte[] marker) {
        if (!Arrays.equals(marker, Iap2LinkPacket.DETECT)) {
            log("收到未知 marker: " + hex(marker));
        }
        if (!peerDetected) {
            peerDetected = true;
            log("收到对端 detect marker");
        }
        if (role == Role.INITIATOR && !sentSyn) {
            sendSyn();
        }
    }

    private synchronized void onPacket(Iap2LinkPacket p) {
        // 链路已经关闭就别再喂消息了：否则车机发过 RST 之后，积压/在途的包还会继续推进
        // 会话状态机，最后把 CLOSED 覆盖回 READY —— 上层以为还连着，实际链路已死。
        if (state == State.CLOSED) return;
        boolean syn = p.hasControl(Iap2LinkPacket.CONTROL_SYN);
        boolean ack = p.hasControl(Iap2LinkPacket.CONTROL_ACK);

        if (p.hasControl(Iap2LinkPacket.CONTROL_RST)) {
            log("收到 RST，链路关闭");
            setState(State.CLOSED);
            return;
        }

        if (syn && !ack) {
            // 对端发起 SYN
            peerConfig = Iap2LinkPacket.LinkConfig.decode(p.payload);
            adoptSessions(peerConfig);
            rxExpected = (p.seq + 1) & 0xFF;
            log("收到 SYN " + peerConfig.sessions + "（控制会话 id=" + controlSessionId + "）");
            if (role == Role.INITIATOR && sentSyn) {
                // 双方同时发起：回 ACK 收敛
                sendAckOnly(p.seq);
            } else {
                sendSynAck(p.seq);
            }
            if (state != State.NORMAL) setState(State.NEGOTIATING);
            return;
        }

        if (syn && ack) {
            peerConfig = Iap2LinkPacket.LinkConfig.decode(p.payload);
            adoptSessions(peerConfig);
            rxExpected = (p.seq + 1) & 0xFF;
            log("收到 SYN|ACK，采纳会话表，控制会话 id=" + controlSessionId);
            if (!sentSyn) {
                // 我们是被动侧，也要回 SYN|ACK 才能收敛
                sendSynAck(p.seq);
            } else {
                sendAckOnly(p.seq);
            }
            setState(State.NORMAL);
            return;
        }

        if (ack && p.payload.length == 0) {
            if (state == State.NEGOTIATING) {
                log("收到 ACK，链路进入 NORMAL");
                setState(State.NORMAL);
            }
            return;
        }

        // 数据帧
        if (rxExpected >= 0 && p.seq != rxExpected) {
            log("乱序包 seq=" + p.seq + " 期望 " + rxExpected + "，丢弃并重 ACK");
            sendAckOnly((rxExpected - 1) & 0xFF);
            return;
        }
        rxExpected = (p.seq + 1) & 0xFF;
        deliver(p.payload);
        sendAckOnly(p.seq);
    }

    private void deliver(byte[] payload) {
        for (Iap2Csm.Message m : csmParser.feed(payload)) {
            log("收到 CSM " + Iap2Ids.name(m.messageId) + " (0x"
                    + Integer.toHexString(m.messageId) + ", " + m.body.length + "B)");
            listener.onMessage(m);
        }
    }

    private void adoptSessions(Iap2LinkPacket.LinkConfig cfg) {
        controlSessionId = cfg.controlSessionId(DEFAULT_CONTROL_SESSION);
    }

    // ================================================================== 出向

    /** 发送一条 CSM 消息（自动按对端最大包长分片）。 */
    public synchronized void sendCsm(int messageId, byte[] body) {
        byte[] frame = Iap2Csm.frame(messageId, body);
        int maxLen = peerConfig != null ? Math.max(64, peerConfig.maxReceivePacketLength) : 0x1000;
        int off = 0;
        while (off < frame.length) {
            int n = Math.min(maxLen, frame.length - off);
            byte[] chunk = new byte[n];
            System.arraycopy(frame, off, chunk, 0, n);
            sendData(chunk);
            off += n;
        }
    }

    private void sendData(byte[] payload) {
        int seq = txSeq;
        txSeq = (txSeq + 1) & 0xFF;
        lastSentSeq = seq;
        Iap2LinkPacket p = new Iap2LinkPacket(
                Iap2LinkPacket.CONTROL_ACK, seq, rxExpected < 0 ? 0 : (rxExpected - 1) & 0xFF,
                controlSessionId, payload);
        sendRaw(p.encode());
    }

    /** ACK-only 必须带 seq-1（否则会与下一个数据包同 seq）。 */
    private void sendAckOnly(int ackSeq) {
        Iap2LinkPacket p = new Iap2LinkPacket(
                Iap2LinkPacket.CONTROL_ACK, lastSentSeq, ackSeq & 0xFF, 0, new byte[0]);
        sendRaw(p.encode());
    }

    private void sendSyn() {
        sentSyn = true;
        Iap2LinkPacket.LinkConfig cfg = defaultConfig();
        lastSentSeq = txSeq;
        Iap2LinkPacket p = new Iap2LinkPacket(
                Iap2LinkPacket.CONTROL_SYN, txSeq, 0, 0, cfg.encode());
        txSeq = (txSeq + 1) & 0xFF;
        sendRaw(p.encode());
        log("发送 SYN，会话表 " + cfg.sessions);
        if (state == State.DETECTING) setState(State.NEGOTIATING);
    }

    /** 回 SYN|ACK：保留对端会话表，只改协商参数。 */
    private void sendSynAck(int ackSeq) {
        Iap2LinkPacket.LinkConfig cfg = defaultConfig();
        if (peerConfig != null) {
            cfg.sessions.clear();
            cfg.sessions.addAll(peerConfig.sessions);
        }
        cfg.maxOutstandingPackets = 0x7F;
        // ⚠️ maxLength 保持对端声明的值，不能放大成 0xFFFF：
        //    真 iPhone / AADisplay 都是回显对端的值（mcarplay 真车对照）。
        //    放大等于告诉车机「你尽管发大包」，一旦超过我们分片/解析的预算，
        //    表现就是「某几条消息收不到、链路像卡住」。
        if (peerConfig != null && peerConfig.maxReceivePacketLength > 0) {
            cfg.maxReceivePacketLength = peerConfig.maxReceivePacketLength;
        }
        Iap2LinkPacket p = new Iap2LinkPacket(
                Iap2LinkPacket.CONTROL_SYN | Iap2LinkPacket.CONTROL_ACK, lastSentSeq, ackSeq & 0xFF, 0, cfg.encode());
        sendRaw(p.encode());
        sentSyn = true;
        log("发送 SYN|ACK，会话表 " + cfg.sessions);
    }

    private Iap2LinkPacket.LinkConfig defaultConfig() {
        Iap2LinkPacket.LinkConfig cfg = new Iap2LinkPacket.LinkConfig();
        cfg.linkVersion = 1;
        cfg.maxOutstandingPackets = 5;
        cfg.maxReceivePacketLength = 0x1000;
        cfg.retransmitTimeoutMs = 500;
        cfg.cumulativeAckTimeoutMs = 176;
        cfg.maxRetransmissions = 30;
        cfg.maxCumulativeAcks = 10;
        // ⚠️ Apple 权威 spec：控制会话 {id=1, type=0, version=2}，EA 会话 {id=2, type=1, version=1}。
        // 早期照抄 HaToan 服务端的 10/11 是非标准的；对端若给了自己的表会被采纳覆盖。
        cfg.sessions.add(new Iap2LinkPacket.SessionDescriptor(
                1, Iap2LinkPacket.SessionDescriptor.TYPE_CONTROL, 2));
        cfg.sessions.add(new Iap2LinkPacket.SessionDescriptor(
                2, Iap2LinkPacket.SessionDescriptor.TYPE_EXTERNAL_ACCESSORY, 1));
        return cfg;
    }

    private void sendRaw(byte[] bytes) {
        try {
            transport.send(bytes);
        } catch (RuntimeException e) {
            log("发送失败：" + e.getMessage());
        }
    }

    private void setState(State s) {
        if (state == s) return;
        state = s;
        listener.onState(s);
    }

    private void log(String msg) {
        listener.onLog(msg);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x ", x));
        return sb.toString().trim();
    }
}
