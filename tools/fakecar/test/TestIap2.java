import com.carbridge.fakecar.Iap2AccessorySession;
import com.carbridge.fakecar.iap2.Iap2Csm;
import com.carbridge.fakecar.iap2.Iap2LinkPacket;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 桌面冒烟测试：验证 Iap2AccessorySession 的链路层握手 + CSM 分发。
 * 用内存流喂入手机侧（发射端）的帧，检查假车机（配件端）的应答。
 */
public class TestIap2 {

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
        // 手机侧（发射端）要发的帧
        ByteArrayOutputStream phoneTx = new ByteArrayOutputStream();

        // 1) SYN：control=SYN(0x80), seq=5, ack=0, sess=0, payload=手机自己的链路配置
        Iap2LinkPacket.LinkConfig phoneCfg = new Iap2LinkPacket.LinkConfig();
        phoneCfg.sessions.add(new Iap2LinkPacket.SessionDescriptor(10, 0, 2));
        phoneCfg.sessions.add(new Iap2LinkPacket.SessionDescriptor(11, 2, 1));
        phoneTx.write(new Iap2LinkPacket(Iap2LinkPacket.CONTROL_SYN, 5, 0, 0, phoneCfg.encode()).encode());

        // 2) 数据帧：CSM 0x1D00（StartIdentification，空体）
        phoneTx.write(dataFrame(1, 0x1D00, new byte[0]));

        // 3) 数据帧：CSM 0xAA00（ReqAuthCert，空体）
        phoneTx.write(dataFrame(2, 0xAA00, new byte[0]));

        // 4) 数据帧：CSM 0x5702（ReqAccWifiCfg，空体）
        phoneTx.write(dataFrame(3, 0x5702, new byte[0]));

        // 5) 数据帧：CSM 0xAA05（AuthSucceeded）
        phoneTx.write(dataFrame(4, 0xAA05, new byte[0]));

        // 6) 数据帧：CSM 0x4E0D（WirelessCarPlayUpdate 问候）
        phoneTx.write(dataFrame(5, 0x4E0D, new byte[0]));

        // 假车机（配件端）
        ByteArrayOutputStream carTx = new ByteArrayOutputStream();
        final List<String> phases = new ArrayList<>();
        Iap2AccessorySession.Config cfg = new Iap2AccessorySession.Config();
        cfg.carIp = "192.168.1.99";
        cfg.apPort = 7010;
        cfg.deviceId = "AA:BB:CC:DD:EE:FF";
        cfg.phoneIp = "127.0.0.1";
        cfg.ctrlIntPort = 9;   // 不会被邀请连上，0x4300 不测

        Iap2AccessorySession session = new Iap2AccessorySession(
                new ByteArrayInputStream(phoneTx.toByteArray()),
                carTx,
                cfg,
                new Iap2AccessorySession.Log() {
                    @Override
                    public void onLog(String line) {
                        System.out.println("  [car] " + line);
                    }
                },
                new Iap2AccessorySession.Phase() {
                    @Override
                    public void onPhase(String name) {
                        phases.add(name);
                    }
                });
        session.start();

        // 等读线程把 ByteArrayInputStream 读完（EOF 自然退出）
        Thread.sleep(500);
        session.stop();

        byte[] out = carTx.toByteArray();
        System.out.println("  [out] 假车机共发出 " + out.length + " 字节");

        // 解析输出帧
        List<Iap2LinkPacket> frames = parseFrames(out);
        System.out.println("  [out] " + frames.size() + " 个链路帧");

        // 第一帧应是 DETECT
        boolean hasDetect = false;
        // DETECT 不是 FF 5A 帧，单独检查原始字节
        if (out.length >= 6 && (out[0] & 0xFF) == 0xFF && (out[1] & 0xFF) == 0x55) {
            hasDetect = true;
        }
        check(hasDetect, "首发 DETECT");

        // 找 SYN|ACK 帧
        boolean synAck = false;
        for (Iap2LinkPacket f : frames) {
            if (f.hasControl(Iap2LinkPacket.CONTROL_SYN) && f.hasControl(Iap2LinkPacket.CONTROL_ACK)) {
                synAck = true;
                Iap2LinkPacket.LinkConfig cfg2 = Iap2LinkPacket.LinkConfig.decode(f.payload);
                check(cfg2.controlSessionId(0) == 10, "SYN|ACK 控制会话 id=10");
            }
        }
        check(synAck, "回 SYN|ACK");

        // 收集 CSM 消息
        List<Integer> csmIds = new ArrayList<>();
        for (Iap2LinkPacket f : frames) {
            if (!f.hasControl(Iap2LinkPacket.CONTROL_SYN)) {
                // 数据帧，解析 CSM
                for (Iap2Csm.Message m : new Iap2Csm.Parser().feed(f.payload)) {
                    csmIds.add(m.messageId);
                }
            }
        }
        System.out.println("  [out] CSM 消息: " + csmIds);
        check(csmIds.contains(0x1D01), "回 0x1D01 配件识别");
        check(csmIds.contains(0x1D00), "回 0x1D00 要求手机自报");
        check(csmIds.contains(0xAA01), "回 0xAA01 证书");
        check(csmIds.contains(0x5703), "回 0x5703 WiFi 配置");
        check(phases.contains("mfi"), "MFi 阶段触发");
        check(phases.contains("greeting"), "问候阶段触发");

        // 校验 0x5703 布局：param1=SSID, param2=密码, param3=安全, param4=信道
        for (Iap2LinkPacket f : frames) {
            for (Iap2Csm.Message m : new Iap2Csm.Parser().feed(f.payload)) {
                if (m.messageId == 0x5703) {
                    List<Iap2Csm.Param> ps = m.params();
                    boolean s1 = false, s2 = false, s3 = false, s4 = false;
                    for (Iap2Csm.Param p : ps) {
                        if (p.paramId == 1) s1 = p.asString().equals("@NRadio-4B02-5G");
                        if (p.paramId == 2) s2 = p.asString().equals("sim0001234");
                        if (p.paramId == 3) s3 = p.asUInt() == 2;
                        if (p.paramId == 4) s4 = p.asUInt() == 6;
                    }
                    check(s1 && s2 && s3 && s4, "0x5703 布局正确(SSID/密码/安全/信道)");
                }
            }
        }

        System.out.println("\n结果: " + pass + " 通过, " + fail + " 失败");
        if (fail > 0) System.exit(1);
        System.out.println("✅ iAP2 配件会话 握手 + CSM 分发 全通过");
    }

    static byte[] dataFrame(int seq, int csmId, byte[] csmBody) {
        byte[] csm = Iap2Csm.frame(csmId, csmBody);
        return new Iap2LinkPacket(0x00, seq, 0, 10, csm).encode();
    }

    static List<Iap2LinkPacket> parseFrames(byte[] b) {
        List<Iap2LinkPacket> out = new ArrayList<>();
        int off = 0;
        // 跳过开头的 DETECT(6B)
        if (b.length >= 6 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0x55) off = 6;
        while (off + 9 <= b.length) {
            if ((b[off] & 0xFF) == 0xFF && (b[off + 1] & 0xFF) == 0x5A) {
                int total = Iap2LinkPacket.getShortBE(b, off + 2);
                if (off + total > b.length) break;
                byte[] frame = new byte[total];
                System.arraycopy(b, off, frame, 0, total);
                try {
                    out.add(Iap2LinkPacket.decode(frame));
                } catch (IllegalArgumentException e) {
                    System.out.println("  !! 帧解析失败: " + e);
                }
                off += total;
            } else {
                off++;
            }
        }
        return out;
    }
}
