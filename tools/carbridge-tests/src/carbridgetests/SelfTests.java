package carbridgetests;

import com.carbridge.carplay.core.AdaptiveResolution;
import com.carbridge.carplay.core.DisplayGeometry;
import com.carbridge.carplay.iap2.Iap2Csm;
import com.carbridge.carplay.iap2.Iap2LinkPacket;
import com.carbridge.carplay.iap2.MfiCrypto;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.List;

/**
 * 纯逻辑单测（JVM，不需要 Android）。
 *
 * <p>只测「错了会静默出错」的那些函数：画布适配、CSM/链路层编解码、MFi 的 DER 解析与验签形状。
 * 跑法：{@code ./run.sh}（退出码非 0 即失败）。
 */
public final class SelfTests {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("carbridge 纯逻辑单测");
        System.out.println("====================");

        geometry();
        adaptive();
        csm();
        link();
        mfi();
        quality();
        stepChain();
        statusView();
        metricsText();
        macText();

        System.out.println();
        System.out.println("通过 " + passed + " / 失败 " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- 画布

    private static void geometry() {
        section("DisplayGeometry");
        // 常见车机画布必须原样通过
        int[] fit = DisplayGeometry.fit(1920, 720);
        check(fit != null && fit[0] == 1920 && fit[1] == 720, "1920x720 原样保留");
        check(DisplayGeometry.fit(0, 720) == null, "非法宽度返回 null");
        check(DisplayGeometry.fit(1920, -1) == null, "非法高度返回 null");

        // 帧率夹取：低于下限抬到下限，高于上限压到上限，非法给默认
        int min = DisplayGeometry.clampFps(1);
        check(min >= 20 && min <= 60, "1fps 被夹进合法区间（实际 " + min + "）");
        check(DisplayGeometry.clampFps(1000) <= 120, "1000fps 被压到上限（120）");
        check(DisplayGeometry.clampFps(0) == 30, "0 → 默认 30");
        check(DisplayGeometry.clampFps(-5) == 30, "负数 → 默认 30");
    }

    // ---------------------------------------------------------------- 自适应

    private static void adaptive() {
        section("AdaptiveResolution");

        // 会话不足 8 秒：编码器热身期的帧率系统性偏低，不许拿来降档
        check(AdaptiveResolution.suggest(1920, 720, 60, 1920, 720, 60, 12.0, 7) == null,
                "7 秒会话 → 不下结论（null）");
        check(AdaptiveResolution.suggest(1920, 720, 60, 1920, 720, 60, 12.0, 0) == null,
                "0 秒会话 → null");
        check(AdaptiveResolution.suggest(1920, 720, 60, 1920, 720, 60, 12.0, 8) != null,
                "满 8 秒才开始下结论");

        // 低帧率降档：35/60 ≈ 58%，低于 60% 门槛
        AdaptiveResolution.Suggestion low =
                AdaptiveResolution.suggest(1920, 720, 60, 1920, 720, 60, 35.0, 20);
        check(low != null && low.fps == 45, "60fps 实测 35 → 降一档 45");
        check(low != null && low.width == 1920 && low.height == 720, "降档不动分辨率");
        check(low != null && low.changed, "降档标记 changed");
        check(low != null && low.reason != null && low.reason.length() > 0, "降档带原因字符串");

        // 60% 边界（36/60 正好 60%）必须落在死区，不许降
        AdaptiveResolution.Suggestion edge =
                AdaptiveResolution.suggest(1920, 720, 60, 1920, 720, 60, 36.0, 20);
        check(edge != null && edge.fps == 60 && !edge.changed, "36/60 正好 60% → 死区不降");

        // 一次只降一档，逐轮收敛
        AdaptiveResolution.Suggestion d2 =
                AdaptiveResolution.suggest(1920, 720, 60, 1920, 720, 45, 20.0, 20);
        check(d2 != null && d2.fps == 30, "45fps 实测 20 → 只降到 30");
        AdaptiveResolution.Suggestion d3 =
                AdaptiveResolution.suggest(1920, 720, 60, 1920, 720, 30, 10.0, 20);
        check(d3 != null && d3.fps == 24, "30fps 实测 10 → 降到 24");

        // 24 是地板，再烂也不往下走
        AdaptiveResolution.Suggestion floor =
                AdaptiveResolution.suggest(1920, 720, 60, 1920, 720, 24, 3.0, 20);
        check(floor != null && floor.fps == 24 && !floor.changed, "24fps 已是地板 → 保持");

        // 高帧率升档：90% 以上才升，且车机得允许
        AdaptiveResolution.Suggestion up =
                AdaptiveResolution.suggest(1920, 720, 60, 1920, 720, 30, 29.0, 20);
        check(up != null && up.fps == 45, "30fps 实测 29（97%）→ 升到 45");
        AdaptiveResolution.Suggestion up2 =
                AdaptiveResolution.suggest(1920, 720, 60, 1920, 720, 45, 44.0, 20);
        check(up2 != null && up2.fps == 60, "45fps 实测 44 → 升到 60");
        AdaptiveResolution.Suggestion top =
                AdaptiveResolution.suggest(1920, 720, 60, 1920, 720, 60, 60.0, 20);
        check(top != null && top.fps == 60 && !top.changed, "60fps 已顶档 → 保持");

        // 车机只给 30fps：实测再满也不能升出车机上限
        AdaptiveResolution.Suggestion capped =
                AdaptiveResolution.suggest(1920, 720, 30, 1920, 720, 30, 30.0, 20);
        check(capped != null && capped.fps == 30 && !capped.changed, "车机上限 30 → 不升档");

        // 90% 边界：40/45≈89% 仍属死区不升；40.5/45 正好 90% 才升
        AdaptiveResolution.Suggestion upEdge =
                AdaptiveResolution.suggest(1920, 720, 60, 1920, 720, 45, 40.0, 20);
        check(upEdge != null && upEdge.fps == 45 && !upEdge.changed, "40/45≈89% → 未到 90% 不升");
        AdaptiveResolution.Suggestion upEdge2 =
                AdaptiveResolution.suggest(1920, 720, 60, 1920, 720, 45, 40.5, 20);
        check(upEdge2 != null && upEdge2.fps == 60, "40.5/45 正好 90% → 升档");

        // 非法画布必须被忽略：不采纳它，帧率判定照常
        AdaptiveResolution.Suggestion bad =
                AdaptiveResolution.suggest(0, 0, 0, 1920, 720, 60, 60.0, 20);
        check(bad != null && bad.width == 1920 && bad.height == 720, "0x0 画布被忽略，尺寸不变");
        check(bad != null && bad.fps == 60 && !bad.changed, "非法画布不影响帧率决策");
        check(AdaptiveResolution.suggest(-100, 720, 60, 5, 0, 60, 60.0, 20) == null,
                "画布与当前都非法 → null");

        // 车机画布变化：采纳画布（含 fps），并要求重连
        AdaptiveResolution.Suggestion canvas =
                AdaptiveResolution.suggest(1280, 480, 60, 1920, 720, 30, 30.0, 20);
        check(canvas != null && canvas.width == 1280 && canvas.height == 480,
                "车机画布 1280x480 → 采纳画布尺寸");
        check(canvas != null && canvas.fps == 60 && canvas.changed, "采纳画布同时采纳画布 fps");

        // 超长边画布走 fit：长边压到 1920 以内、fps 夹到 60 以内
        AdaptiveResolution.Suggestion big =
                AdaptiveResolution.suggest(2560, 1080, 70, 1920, 720, 60, 60.0, 20);
        check(big != null && Math.max(big.width, big.height) <= DisplayGeometry.MAX_LONG_EDGE,
                "2560 宽画布长边被压到 1920 内");
        check(big != null && big.fps == 70, "70fps 画布允许通过（上限已放开到 120）");

        // 没有有效采样就不猜
        AdaptiveResolution.Suggestion none =
                AdaptiveResolution.suggest(1920, 720, 60, 1920, 720, 60, 0.0, 30);
        check(none != null && none.fps == 60 && !none.changed, "实测 0 → 无采样保持");

        // 阶梯函数本身
        check(AdaptiveResolution.downTier(60) == 45 && AdaptiveResolution.downTier(45) == 30
                        && AdaptiveResolution.downTier(30) == 24,
                "downTier 60→45→30→24");
        check(AdaptiveResolution.downTier(24) == 24, "downTier 地板不变");
        check(AdaptiveResolution.upTier(24) == 30 && AdaptiveResolution.upTier(30) == 45
                        && AdaptiveResolution.upTier(45) == 60,
                "upTier 24→30→45→60");
        check(AdaptiveResolution.upTier(60) == 60, "upTier 顶档不变");
    }

    // ---------------------------------------------------------------- CSM

    private static void csm() {
        section("Iap2Csm");
        byte[] body = Iap2Csm.concat(
                Iap2Csm.paramString(0, "iPhone"),
                Iap2Csm.u8(1, 1));
        byte[] frame = Iap2Csm.frame(0x4E09, body);
        check(frame[0] == 0x40 && frame[1] == 0x40, "帧头 40 40");
        check(((frame[2] & 0xFF) << 8 | (frame[3] & 0xFF)) == frame.length, "长度字段 = 实际长度");

        Iap2Csm.Message m = Iap2Csm.parse(frame);
        check(m.messageId == 0x4E09, "消息 ID 还原");
        List<Iap2Csm.Param> ps = m.params();
        check(ps.size() == 2, "两个参数");
        check("iPhone".equals(ps.get(0).asString()), "字符串参数还原");
        check(ps.get(1).asUInt() == 1, "u8 参数还原");

        // 垃圾前缀：逐字节跳过，仍能找到后面的真帧
        byte[] junk = new byte[]{0x11, 0x22, 0x33};
        byte[] withJunk = new byte[junk.length + frame.length];
        System.arraycopy(junk, 0, withJunk, 0, junk.length);
        System.arraycopy(frame, 0, withJunk, junk.length, frame.length);
        Iap2Csm.Parser pj = new Iap2Csm.Parser();
        check(pj.feed(withJunk).size() == 1, "前置垃圾字节不影响后续帧");

        // 畸形长度：声明比实际长 → 不吐消息、也不炸
        Iap2Csm.Parser pm = new Iap2Csm.Parser();
        byte[] bad = frame.clone();
        bad[2] = (byte) 0xFF;
        bad[3] = (byte) 0xFF;
        check(pm.feed(bad).isEmpty(), "长度字段夸大时不吐消息");

        // 缓冲上限：持续灌「假帧头」不会无限增长
        Iap2Csm.Parser pc = new Iap2Csm.Parser();
        // 长度字段 = 5（比帧头还短）→ 解析器永远 break 不消费，缓冲只能一直涨
        byte[] flood = new byte[8192];
        for (int i = 0; i + 3 < flood.length; i += 4) {
            flood[i] = 0x40; flood[i + 1] = 0x40; flood[i + 2] = 0x00; flood[i + 3] = 0x05;
        }
        for (int i = 0; i < 64; i++) pc.feed(flood);   // 512KB 垃圾
        check(pc.droppedBytes() > 0, "超限后开始丢弃并计数（dropped=" + pc.droppedBytes() + "）");

        // 增量解析：拆成两段喂进去也要吐出一条完整消息
        Iap2Csm.Parser p = new Iap2Csm.Parser();
        byte[] half1 = java.util.Arrays.copyOfRange(frame, 0, 4);
        byte[] half2 = java.util.Arrays.copyOfRange(frame, 4, frame.length);
        check(p.feed(half1).isEmpty(), "半包不吐消息");
        check(p.feed(half2).size() == 1, "补全后吐出一条");
    }

    // ---------------------------------------------------------------- 链路层

    private static void link() {
        section("Iap2LinkPacket");
        Iap2LinkPacket p = new Iap2LinkPacket(Iap2LinkPacket.CONTROL_ACK, 7, 6, 1, new byte[]{1, 2, 3});
        byte[] raw = p.encode();
        check((raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0x5A, "链路头 FF5A");
        Iap2LinkPacket back = Iap2LinkPacket.decode(raw);
        check(back.seq == 7 && back.ack == 6, "seq/ack 还原");
        check(java.util.Arrays.equals(back.payload, new byte[]{1, 2, 3}), "载荷还原");

        // 校验和：整包按字节求和应为 0
        int sum = 0;
        for (byte b : raw) sum += b;
        check((sum & 0xFF) == 0, "校验和使整包 ≡ 0");

        Iap2LinkPacket.LinkConfig cfg = new Iap2LinkPacket.LinkConfig();
        cfg.maxReceivePacketLength = 0x3E8;
        cfg.sessions.add(new Iap2LinkPacket.SessionDescriptor(1, 0, 2));
        byte[] enc = cfg.encode();
        Iap2LinkPacket.LinkConfig dec = Iap2LinkPacket.LinkConfig.decode(enc);
        check(dec != null && dec.maxReceivePacketLength == 0x3E8, "LinkConfig 往返一致");
        check(dec.sessions.size() == 1 && dec.sessions.get(0).id == 1, "会话表往返一致");
    }

    // ---------------------------------------------------------------- MFi

    private static void mfi() throws Exception {
        section("MfiCrypto / 认证材料");

        // 用真实生成的 EC P-256 密钥造一份 SubjectPublicKeyInfo，测解析 + 验签
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        byte[] spki = kp.getPublic().getEncoded();

        java.security.PublicKey parsed = MfiCrypto.parseEcPublicKey(spki);
        check(parsed != null, "EC 公钥解析成功");
        check(MfiCrypto.parseCertificate(spki) == null, "RSA 解析器不误报 EC 证书");

        byte[] challenge = new byte[32];
        for (int i = 0; i < challenge.length; i++) challenge[i] = (byte) (i * 3);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(kp.getPrivate());
        s.update(challenge);
        byte[] sig = s.sign();

        check(MfiCrypto.verifyEcSignature(challenge, sig, parsed), "ECDSA 验签通过");
        byte[] tampered = challenge.clone();
        tampered[0] ^= 0x7F;
        check(!MfiCrypto.verifyEcSignature(tampered, sig, parsed), "挑战被改动 → 验签失败");

        // 形状校验：ECDSA DER 应合法；5 字节垃圾应被拒
        check(MfiCrypto.signatureShapeOk(sig, null, parsed), "ECDSA DER 形状合法");
        check(!MfiCrypto.signatureShapeOk(new byte[]{1, 2, 3, 4, 5}, null, parsed), "5B 垃圾被拒");

        // Apple OID 探测
        byte[] appleOid = {0x06, 0x0A, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x63, 0x64, 0x06, 0x02, 0x01};
        check(MfiCrypto.looksAppleIssued(appleOid), "检出 Apple OID 弧");
        check(!MfiCrypto.looksAppleIssued(new byte[]{1, 2, 3}), "普通字节不算 Apple 证书");
    }

    // ---------------------------------------------------------------- 画质

    private static void quality() {
        section("QualityPrefs");
        check(com.carbridge.app.QualityPrefs.clampDim(100) == 320, "100 被抬到下限 320");
        check(com.carbridge.app.QualityPrefs.clampDim(9999) == 3840, "9999 被压到上限 3840");
        check(com.carbridge.app.QualityPrefs.clampDim(1920) == 1920, "1920 原样");

        check(com.carbridge.app.QualityPrefs.parseDim("1920", 720) == 1920, "解析 1920");
        check(com.carbridge.app.QualityPrefs.parseDim("abc", 720) == 720, "非法回落默认");
        check(com.carbridge.app.QualityPrefs.parseDim("", 720) == 720, "空串回落默认");
        check(com.carbridge.app.QualityPrefs.parseDim(" 1280 ", 720) == 1280, "去空格后解析");

        check(com.carbridge.app.QualityPrefs.clampIndex(-1, 4) == 0, "负下标夹到 0");
        check(com.carbridge.app.QualityPrefs.clampIndex(9, 4) == 3, "越界夹到末尾");
        check(com.carbridge.app.QualityPrefs.clampIndex(1, 0) == 0, "空档位数组不崩");

        // 自动适配：跟随车机画布
        int[] a = com.carbridge.app.QualityPrefs.paramsForStart(
                true, 1920, 720, 30, 1280, 720, 3);
        check(a[0] == 1920 && a[1] == 720 && a[2] == 30, "自动模式跟随车机 1920x720@30");
        // 手动：用自己填的
        int[] b = com.carbridge.app.QualityPrefs.paramsForStart(
                false, 1920, 720, 30, 1280, 720, 3);
        check(b[0] == 1280 && b[1] == 720 && b[2] == 60, "手动模式用手填 1280x720@60");
        // 车机画布未知：回落自填
        int[] c = com.carbridge.app.QualityPrefs.paramsForStart(
                true, 0, 0, 0, 1280, 720, 0);
        check(c[0] == 1280 && c[2] == 20, "无车机画布时回落到自填 + 档位 0");
    }

    // ---------------------------------------------------------------- 步骤链

    private static void stepChain() {
        section("StepChain（进度链状态判定）");
        // 正常推进：0/1 完成，2 进行中，3 之后待开始
        check(com.carbridge.app.StepChain.stateOf(0, 2, null, 5)
                == com.carbridge.app.StepChain.State.DONE, "进行中之前的步 = 完成");
        check(com.carbridge.app.StepChain.stateOf(2, 2, null, 5)
                == com.carbridge.app.StepChain.State.ACTIVE, "当前步 = 进行中");
        check(com.carbridge.app.StepChain.stateOf(3, 2, null, 5)
                == com.carbridge.app.StepChain.State.PENDING, "之后的步 = 待开始");

        // 第 2 步失败：第 0/1 步必须仍是完成（曾经的 bug：全部退回待开始）
        check(com.carbridge.app.StepChain.stateOf(0, 2, 2, 5)
                == com.carbridge.app.StepChain.State.DONE, "失败步之前仍算完成");
        check(com.carbridge.app.StepChain.stateOf(2, 2, 2, 5)
                == com.carbridge.app.StepChain.State.FAILED, "失败步 = 失败");
        check(com.carbridge.app.StepChain.stateOf(3, 2, 2, 5)
                == com.carbridge.app.StepChain.State.PENDING, "失败步之后 = 待开始");

        // 到达终态：最后一步也要打勾（曾经的 bug：永远转圈）
        check(com.carbridge.app.StepChain.stateOf(5, 5, null, 5)
                == com.carbridge.app.StepChain.State.DONE, "终态步 = 完成（不再转圈）");
        check(com.carbridge.app.StepChain.stateOf(0, 9, null, 5)
                == com.carbridge.app.StepChain.State.DONE, "下标越界的当前步也全完成");

        // 还没开始
        check(com.carbridge.app.StepChain.stateOf(0, null, null, 5)
                == com.carbridge.app.StepChain.State.PENDING, "未开始 = 待开始");

        // 进度计数
        check(com.carbridge.app.StepChain.doneCount(6, 2, null) == 2, "进行中时完成 2 步");
        check(com.carbridge.app.StepChain.doneCount(6, 2, 2) == 2, "失败时前面 2 步仍算完成");
        check(com.carbridge.app.StepChain.doneCount(6, 5, null) == 6, "到达终态 = 全部完成");
        check(com.carbridge.app.StepChain.doneCount(6, null, null) == 0, "未开始 = 0");
    }

    // ---------------------------------------------------------------- 状态→显示规则

    private static void statusView() {
        section("StatusView（状态→显示规则）");
        com.carbridge.app.StatusView.Kind idle = com.carbridge.app.StatusView.Kind.IDLE;
        com.carbridge.app.StatusView.Kind stream = com.carbridge.app.StatusView.Kind.STREAMING;
        com.carbridge.app.StatusView.Kind err = com.carbridge.app.StatusView.Kind.ERROR;
        com.carbridge.app.StatusView.Kind closed = com.carbridge.app.StatusView.Kind.CLOSED;

        check(!idle.busy && stream.busy, "只有工作态才计时");
        check(stream.tone == com.carbridge.app.StatusView.Tone.OK, "推流 = 绿色");
        check(err.tone == com.carbridge.app.StatusView.Tone.ERR, "失败 = 红色");
        check(err.manualHint, "失败要显示人工处理提示");
        check(closed.resetSteps, "断开必须清进度（否则停在 n/15）");
        check(closed.releaseButton, "断开要把按钮切回连接");
        check(!closed.busy, "断开要停表");

        // 遍历所有状态：IDLE 必须是唯一「什么都不做」的兜底
        for (com.carbridge.app.StatusView.Kind k : com.carbridge.app.StatusView.Kind.values()) {
            boolean anyAction = k.busy || k.resetSteps || k.manualHint || k.releaseButton;
            if (k == idle) {
                check(!anyAction, "IDLE 不触发任何副作用");
            }
        }
    }

    // ---------------------------------------------------------------- 指标格式

    private static void metricsText() {
        section("MetricsText（指标格式化）");
        check(com.carbridge.app.MetricsText.fps(12.4).equals("12 fps"), "帧率四舍五入到整数");
        check(com.carbridge.app.MetricsText.fps(0) == null, "0 帧率返回 null（界面显示占位符）");
        check(com.carbridge.app.MetricsText.resolution(1920, 720).equals("1920×720"), "分辨率文本");
        check(com.carbridge.app.MetricsText.resolution(0, 720) == null, "非法分辨率返回 null");

        // 码率：每帧 250KB、30fps → 250*1024*8*30/1e6 ≈ 61.4 Mbps
        float m = com.carbridge.app.MetricsText.mbps(1000L * 256000, 1000, 30);
        check(Math.abs(m - 61.44f) < 0.5f, "码率估算 ≈61.4Mbps（实际 " + m + "）");
        check(com.carbridge.app.MetricsText.mbpsText(0, 0, 0) == null, "无数据码率返回 null");

        String d = com.carbridge.app.MetricsText.detail(120, 3L * 1048576, 1200);
        check(d.contains("帧数 120") && d.contains("3.0 MB") && d.contains("首帧 1.20s"), "详情行格式");
        String d2 = com.carbridge.app.MetricsText.detail(10, 1048576, 0);
        check(!d2.contains("首帧"), "首帧未到时详情行不显示首帧");
        check(com.carbridge.app.MetricsText.detail(0, 0, 0) == null, "零字节时不显示详情行");
    }

    // ---------------------------------------------------------------- MAC 文本

    private static void macText() {
        section("MacText（MAC 规范化与校验）");
        com.carbridge.carplay.util.MacText m = null;

        // 规范化：各种分隔符都要能吃
        check("A4:50:46:1C:2D:EE".equals(com.carbridge.carplay.util.MacText.canonicalStrict("a4:50:46:1c:2d:ee")), "小写转大写规范形式");
        check("A4:50:46:1C:2D:EE".equals(com.carbridge.carplay.util.MacText.canonicalStrict("a4-50-46-1c-2d-ee")), "连字符分隔");
        check("A4:50:46:1C:2D:EE".equals(com.carbridge.carplay.util.MacText.canonicalStrict("a450461c2dee")), "12 位连续十六进制");
        check("A4:50:46:1C:2D:EE".equals(com.carbridge.carplay.util.MacText.canonicalStrict(" a4 50 46 1c 2d ee ")), "空格分隔 + 两端空白");

        // 严格校验该拒的
        check(com.carbridge.carplay.util.MacText.canonicalStrict("02:00:00:00:00:00") == null, "拒占位地址");
        check(com.carbridge.carplay.util.MacText.canonicalStrict("00:00:00:00:00:00") == null, "拒全 0");
        check(com.carbridge.carplay.util.MacText.canonicalStrict("FF:FF:FF:FF:FF:FF") == null, "拒全 F");
        check(com.carbridge.carplay.util.MacText.canonicalStrict("01:50:46:1C:2D:EE") == null, "拒组播位");
        check(com.carbridge.carplay.util.MacText.canonicalStrict("06:50:46:1C:2D:EE") == null, "严格模式拒本地管理位");
        check(com.carbridge.carplay.util.MacText.canonicalStrict("A4:50:46:1C:2D") == null, "拒位数不足");
        check(com.carbridge.carplay.util.MacText.canonicalStrict("ZZ:50:46:1C:2D:EE") == null, "拒非十六进制");
        check(com.carbridge.carplay.util.MacText.canonicalStrict(null) == null, "null 安全");

        // 宽松模式必须接受本地管理位（热点接口地址就是这种）
        check("06:50:46:1C:2D:EE".equals(com.carbridge.carplay.util.MacText.canonical("06:50:46:1C:2D:EE")), "宽松模式接受本地管理位");
        check(com.carbridge.carplay.util.MacText.canonical("02:00:00:00:00:00") == null, "宽松模式仍拒占位");

        // 从自由文本里抠（ifconfig/sysfs/proc 的输出）
        check("A4:50:46:1C:2D:EE".equals(com.carbridge.carplay.util.MacText.firstIn(
                "wlan0: flags=4163<UP>  ether a4:50:46:1c:2d:ee  txqueuelen 3000")), "从 ifconfig 文本抠出 MAC");
        check(com.carbridge.carplay.util.MacText.firstIn("no mac here") == null, "无 MAC 返回 null");

        // 字节数组
        check("A4:50:46:1C:2D:EE".equals(com.carbridge.carplay.util.MacText.fromBytes(
                new byte[]{(byte)0xA4,0x50,0x46,0x1C,0x2D,(byte)0xEE})), "字节数组规范化");
        check(com.carbridge.carplay.util.MacText.fromBytes(new byte[]{0,0,0,0,0,0}) == null, "全 0 字节数组被拒");
    }

    // ---------------------------------------------------------------- 工具

    private static void section(String name) {
        System.out.println();
        System.out.println("── " + name);
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + what);
        } else {
            failed++;
            System.out.println("  ✗ " + what);
        }
    }

    private SelfTests() {
    }
}
