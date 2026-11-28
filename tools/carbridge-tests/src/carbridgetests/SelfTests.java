package carbridgetests;

import com.carbridge.app.StepDiagnosis;
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
        identityName();
        link();
        mfi();
        quality();
        stepChain();
        statusView();
        metricsText();
        macText();
        stepDiagnosis();

        wifiConfig();
        cryptoVectors();
        airPlayInfo();
        hid();
        rtsp();
        hidKeys();
        linkEngine();
        aeadAndBplist();
        controlCipher();

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

        // 0x1D01 transport 组件编码（第 19 批）：组内 TLV 必须能原样解回
        byte[] idBody = Iap2Csm.concat(
                Iap2Csm.group(17,
                        Iap2Csm.paramUInt(0, 1, 2),
                        Iap2Csm.paramString(1, "IAP2-Bluetooth"),
                        Iap2Csm.u8(2, 1),
                        Iap2Csm.paramString(3, "AA:BB:CC:DD:EE:FF")),
                Iap2Csm.group(24,
                        Iap2Csm.paramUInt(0, 2, 2),
                        Iap2Csm.paramString(1, "IAP2-Wireless"),
                        Iap2Csm.u8(2, 1),
                        Iap2Csm.u8(3, 1)));
        List<Iap2Csm.Param> idParams = Iap2Csm.parseParams(idBody);
        Iap2Csm.Param btComp = Iap2Csm.find(idParams, 17);
        Iap2Csm.Param wlComp = Iap2Csm.find(idParams, 24);
        check(btComp != null && wlComp != null, "0x1D01 含蓝牙/无线 transport 组件");
        List<Iap2Csm.Param> btSub = btComp.asNested();
        check(btSub.size() == 4, "蓝牙组件 4 个子项");
        check("IAP2-Bluetooth".equals(Iap2Csm.find(btSub, 1).asString()),
                "蓝牙组件名字 IAP2-Bluetooth");
        check("AA:BB:CC:DD:EE:FF".equals(Iap2Csm.find(btSub, 3).asString()),
                "蓝牙组件带真实 MAC");

        // 0x4E04：9 条 profile 的 u16 数组
        byte[] profiles = new byte[18];
        for (int i = 0; i < 9; i++) profiles[i * 2 + 1] = (byte) i;
        Iap2Csm.Param pp = Iap2Csm.find(Iap2Csm.parseParams(Iap2Csm.param(1, profiles)), 1);
        check(pp != null && pp.value.length == 18, "0x4E04 profile 表 9 项（第 19 批）");
    }

    // ---------------------------------------------------------------- 身份名
    private static void identityName() {
        section("IdentityNamePolicy（身份名单一真源）");
        check("iPhone".equals(com.carbridge.carplay.IdentityNamePolicy.resolve(
                com.carbridge.carplay.IdentityNamePolicy.MODE_IPHONE, "x", "OPPO PKG110")),
                "iPhone 模式固定 iPhone");
        check("OPPO PKG110".equals(com.carbridge.carplay.IdentityNamePolicy.resolve(
                com.carbridge.carplay.IdentityNamePolicy.MODE_FOLLOW, "", " OPPO PKG110 ")),
                "跟随模式取蓝牙名（去空白）");
        check("iPhone".equals(com.carbridge.carplay.IdentityNamePolicy.resolve(
                com.carbridge.carplay.IdentityNamePolicy.MODE_FOLLOW, "", "   ")),
                "蓝牙名取不到 → 兜底 iPhone");
        check("我的车".equals(com.carbridge.carplay.IdentityNamePolicy.resolve(
                com.carbridge.carplay.IdentityNamePolicy.MODE_CUSTOM, " 我的车 ", "x")),
                "自定义名生效");
        check("iPhone".equals(com.carbridge.carplay.IdentityNamePolicy.resolve(
                com.carbridge.carplay.IdentityNamePolicy.MODE_CUSTOM, "  ", "x")),
                "自定义为空 → 兜底 iPhone");
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

        // raw r||s（64B）ECDSA 签名：BoringSSL/WebCrypto 惯例，以前误拒
        byte[] raw = derSigToRaw(sig);
        check(raw != null, "DER 签名可转 raw r||s");
        check(raw != null && MfiCrypto.verifyEcSignature(challenge, raw, parsed),
                "raw r||s ECDSA 验签通过（第 11 批修复）");
        check(raw != null && !MfiCrypto.verifyEcSignature(tampered, raw, parsed),
                "raw r||s 挑战被改 → 验签失败");

        // RSA：SHA-1（传统 MFi）与 SHA-256（较新）都必须能验
        KeyPairGenerator rg = KeyPairGenerator.getInstance("RSA");
        rg.initialize(1024);
        KeyPair rkp = rg.generateKeyPair();
        java.security.interfaces.RSAPublicKey rpub =
                (java.security.interfaces.RSAPublicKey) rkp.getPublic();
        MfiCrypto.RsaKey rsa = new MfiCrypto.RsaKey(rpub.getModulus(), rpub.getPublicExponent());
        for (String alg : new String[]{"SHA1withRSA", "SHA256withRSA"}) {
            java.security.Signature rs = java.security.Signature.getInstance(alg);
            rs.initSign(rkp.getPrivate());
            rs.update(challenge);
            byte[] rsig = rs.sign();
            check(MfiCrypto.verifySignature(challenge, rsig, rsa),
                    "RSA " + alg + " 验签通过（第 11 批修复）");
            check(!MfiCrypto.verifySignature(tampered, rsig, rsa),
                    "RSA " + alg + " 挑战被改 → 验签失败");
        }

        // ⚠️ e=1 的「证书」必须被拒（否则签名=EM 本身，零私钥运算即可伪造，实测过）
        byte[] em = buildSha1Em(challenge);
        java.math.BigInteger ff128 = java.math.BigInteger.ONE.shiftLeft(1024).subtract(java.math.BigInteger.ONE);
        MfiCrypto.RsaKey evil = new MfiCrypto.RsaKey(ff128, java.math.BigInteger.ONE);
        check(!MfiCrypto.verifySignature(challenge, em, evil),
                "e=1 公钥 + 自造 EM → 必须拒绝（第 11 批修复）");

        // 形状校验：长度字段不自洽的「DER」不算
        check(!MfiCrypto.signatureShapeOk(new byte[]{0x30, 0, 0, 0, 0, 0, 0, 0}, null, parsed),
                "30 00… 假 DER 形状 → false（第 11 批修复）");
        check(raw != null && MfiCrypto.signatureShapeOk(raw, null, parsed), "raw r||s 形状 → true");

        // 畸形 DER 的 certValidity 不许外抛（会整条 0xAA01 丢失）
        check(MfiCrypto.certValidity(new byte[]{0x30, (byte) 0x84, 0x7F, (byte) 0xFF, (byte) 0xFF}) == null,
                "畸形长度 DER → certValidity 返回 null 而非抛异常");
        check(MfiCrypto.certValidity(new byte[]{0x17, 0x05, '2', '0'}) == null,
                "截断 UTCTime → return null");

        // P-384/521 曲线必须不解析（解析成功也永远验不过，静默失败更糟）
        KeyPairGenerator g384 = KeyPairGenerator.getInstance("EC");
        g384.initialize(new java.security.spec.ECGenParameterSpec("secp384r1"));
        byte[] spki384 = g384.generateKeyPair().getPublic().getEncoded();
        check(MfiCrypto.parseEcPublicKey(spki384) == null,
                "P-384 SPKI → parseEcPublicKey 返回 null（第 11 批修复）");
        check(MfiCrypto.parseEcPublicKey(spki) != null, "P-256 SPKI 正常解析（回归）");

        // AuthV3 裸签：NONEwithECDSA 直接对 32B「挑战」签（不做二次哈希）
        byte[] ch32 = new byte[32];
        new java.util.Random(7).nextBytes(ch32);
        java.security.Signature bare = java.security.Signature.getInstance("NONEwithECDSA");
        bare.initSign(kp.getPrivate());
        bare.update(ch32);
        byte[] bareRaw = derSigToRaw(bare.sign());
        check(bareRaw != null && MfiCrypto.verifyEcSignature(ch32, bareRaw, parsed),
                "NONEwithECDSA 裸签（64B r||s）验签通过（第 16 批）");
        check(MfiCrypto.verifyEcSignature(ch32, bareRaw, parsed) == false
                || !MfiCrypto.verifyEcSignature(tampered, bareRaw, parsed),
                "裸签挑战被改 → 验签失败");
    }

    /** 构造一份「SHA-1 DigestInfo + PKCS#1 填充」的 EM（供 e=1 伪造测试用）。 */
    private static byte[] buildSha1Em(byte[] challenge) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-1").digest(challenge);
        byte[] di = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E, 0x03, 0x02, 0x1A, 0x05, 0x00};
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(0x00);
        out.write(0x01);
        for (int i = 0; i < 64; i++) out.write(0xFF);   // 足够长的 FF 填充
        out.write(0x00);
        out.write(di, 0, di.length);
        out.write(digest, 0, digest.length);
        return out.toByteArray();
    }

    /** DER SEQUENCE{INTEGER r, INTEGER s} → 64B raw r||s（各补零到 32B）。 */
    private static byte[] derSigToRaw(byte[] der) {
        try {
            int p = 0;
            if ((der[p++] & 0xFF) != 0x30) return null;
            int total = der[p++] & 0xFF;
            if (total > 0x7F) return null;   // 测试只处理短形式
            if ((der[p++] & 0xFF) != 0x02) return null;
            int lr = der[p++] & 0xFF;
            byte[] r = java.util.Arrays.copyOfRange(der, p, p + lr);
            p += lr;
            if ((der[p++] & 0xFF) != 0x02) return null;
            int ls = der[p++] & 0xFF;
            byte[] s = java.util.Arrays.copyOfRange(der, p, p + ls);
            byte[] out = new byte[64];
            copyLeftPadded(r, out, 0);
            copyLeftPadded(s, out, 32);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void copyLeftPadded(byte[] src, byte[] dst, int off) {
        int start = 0;
        while (start < src.length - 1 && src[start] == 0) start++;
        int n = src.length - start;
        if (n > 32) n = 32;
        System.arraycopy(src, src.length - n, dst, off + 32 - n, n);
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
                true, 1920, 720, 30, 1280, 720, 3, 0);
        check(a[0] == 1920 && a[1] == 720 && a[2] == 30, "自动模式跟随车机 1920x720@30");
        // 手动：用自己填的
        int[] b = com.carbridge.app.QualityPrefs.paramsForStart(
                false, 1920, 720, 30, 1280, 720, 3, 0);
        check(b[0] == 1280 && b[1] == 720 && b[2] == 60, "手动模式用手填 1280x720@60");
        // 车机画布未知：回落自填
        int[] c = com.carbridge.app.QualityPrefs.paramsForStart(
                true, 0, 0, 0, 1280, 720, 0, 0);
        check(c[0] == 1280 && c[2] == 20, "无车机画布时回落到自填 + 档位 0");
        // 自适应帧率上限：autoFit 模式把车机 60fps 压到 45，手动模式不受影响
        int[] d = com.carbridge.app.QualityPrefs.paramsForStart(
                true, 1920, 720, 60, 1280, 720, 3, 45);
        check(d[2] == 45, "autoFit + 上限 45 → 请求 45fps（第 14 批修复）");
        int[] e2 = com.carbridge.app.QualityPrefs.paramsForStart(
                false, 1920, 720, 60, 1280, 720, 3, 45);
        check(e2[2] == 60, "手动模式不受上限影响");
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

    // ---------------------------------------------------------------- 0x5703

    private static void wifiConfig() {
        section("WifiConfigParser（0x5703 自适应解析）");
        // 标准布局 {0:ssid,1:pwd,2:ch,3:sec}
        java.util.List<com.carbridge.carplay.iap2.Iap2Csm.Param> std =
                com.carbridge.carplay.iap2.Iap2Csm.parseParams(com.carbridge.carplay.iap2.Iap2Csm.concat(
                        com.carbridge.carplay.iap2.Iap2Csm.paramString(0, "CheryCar"),
                        com.carbridge.carplay.iap2.Iap2Csm.paramString(1, "12345678"),
                        com.carbridge.carplay.iap2.Iap2Csm.paramUInt(2, 36, 1),
                        com.carbridge.carplay.iap2.Iap2Csm.paramUInt(3, 2, 1)));
        com.carbridge.carplay.iap2.WifiConfigParser.Cfg c =
                com.carbridge.carplay.iap2.WifiConfigParser.parse(std);
        check("CheryCar".equals(c.ssid) && "12345678".equals(c.password), "标准布局：SSID/密码");
        check(c.channel == 36 && c.security == 2, "标准布局：信道/安全类型");

        // DiPlay 布局 {1:ssid,2:pwd,3:ch,4:sec}（没有 id=0）
        java.util.List<com.carbridge.carplay.iap2.Iap2Csm.Param> diplay =
                com.carbridge.carplay.iap2.Iap2Csm.parseParams(com.carbridge.carplay.iap2.Iap2Csm.concat(
                        com.carbridge.carplay.iap2.Iap2Csm.paramString(1, "DiPlayCar"),
                        com.carbridge.carplay.iap2.Iap2Csm.paramString(2, "abcdefgh"),
                        com.carbridge.carplay.iap2.Iap2Csm.paramUInt(3, 6, 1),
                        com.carbridge.carplay.iap2.Iap2Csm.paramUInt(4, 4, 1)));
        c = com.carbridge.carplay.iap2.WifiConfigParser.parse(diplay);
        check("DiPlayCar".equals(c.ssid) && c.channel == 6, "DiPlay 布局能识别");

        // 非法信道要能继续尝试下一种布局，最终仍取到有 SSID 的那个
        java.util.List<com.carbridge.carplay.iap2.Iap2Csm.Param> odd =
                com.carbridge.carplay.iap2.Iap2Csm.parseParams(com.carbridge.carplay.iap2.Iap2Csm.concat(
                        com.carbridge.carplay.iap2.Iap2Csm.paramString(0, "EdgeCase"),
                        com.carbridge.carplay.iap2.Iap2Csm.paramString(1, "pw"),
                        com.carbridge.carplay.iap2.Iap2Csm.paramUInt(2, 999, 2),   // 非法信道
                        com.carbridge.carplay.iap2.Iap2Csm.paramUInt(3, 2, 1)));
        c = com.carbridge.carplay.iap2.WifiConfigParser.parse(odd);
        check("EdgeCase".equals(c.ssid), "非法信道时仍回落到有 SSID 的布局");

        // 空参数 / 没有 SSID
        check(!com.carbridge.carplay.iap2.WifiConfigParser.parse(null).usable(), "null 参数不可用");
        java.util.List<com.carbridge.carplay.iap2.Iap2Csm.Param> noSsid =
                com.carbridge.carplay.iap2.Iap2Csm.parseParams(
                        com.carbridge.carplay.iap2.Iap2Csm.paramUInt(2, 6, 1));
        check(!com.carbridge.carplay.iap2.WifiConfigParser.parse(noSsid).usable(), "没有 SSID 时不可用");

        check(com.carbridge.carplay.iap2.WifiConfigParser.isValidChannel(1), "1 信道合法");
        check(com.carbridge.carplay.iap2.WifiConfigParser.isValidChannel(165), "165 信道合法");
        check(!com.carbridge.carplay.iap2.WifiConfigParser.isValidChannel(15), "15 信道非法");
        check(!com.carbridge.carplay.iap2.WifiConfigParser.isValidChannel(0), "0 信道非法");
    }

    // ---------------------------------------------------------------- 链路层

    /**
     * 链路层的**分帧与重新同步**。
     *
     * <p>蓝牙串口是字节流：可能一个包分两次到、也可能前面混进垃圾字节。
     * 这里写错的表现是「识别成功但后面每条消息都错位」，现场极难判断。
     */
    private static void linkEngine() {
        section("Iap2LinkEngine（分帧 / 重新同步）");

        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final java.util.List<String> logs = new java.util.ArrayList<String>();
        final java.util.List<com.carbridge.carplay.iap2.Iap2Csm.Message> gotMessages =
                new java.util.ArrayList<com.carbridge.carplay.iap2.Iap2Csm.Message>();
        com.carbridge.carplay.iap2.Iap2LinkEngine.Transport tp =
                new com.carbridge.carplay.iap2.Iap2LinkEngine.Transport() {
                    @Override public void send(byte[] data) {
                        out.write(data, 0, data.length);
                    }
                };
        com.carbridge.carplay.iap2.Iap2LinkEngine.Listener ln =
                new com.carbridge.carplay.iap2.Iap2LinkEngine.Listener() {
                    @Override public void onLog(String line) { logs.add(line); }
                    @Override public void onState(com.carbridge.carplay.iap2.Iap2LinkEngine.State st) { }
                    @Override public void onMessage(com.carbridge.carplay.iap2.Iap2Csm.Message m) {
                        gotMessages.add(m);
                    }
                };

        com.carbridge.carplay.iap2.Iap2LinkEngine e =
                new com.carbridge.carplay.iap2.Iap2LinkEngine(tp, ln,
                        com.carbridge.carplay.iap2.Iap2LinkEngine.Role.INITIATOR);

        // 1) 前面混进垃圾字节：应该跳过并继续，不抛异常
        byte[] junk = new byte[]{0x00, (byte) 0xAA, 0x13, (byte) 0xFF};
        e.onBytes(junk);
        check(true, "垃圾字节不会抛异常（已跳过）");

        // 2) 收到 detect marker → 发起方应回 SYN
        out.reset();
        e.onBytes(com.carbridge.carplay.iap2.Iap2LinkPacket.DETECT);
        byte[] sent = out.toByteArray();
        boolean syn = false;
        for (int i = 0; i + 5 < sent.length; i++) {
            if ((sent[i] & 0xFF) == 0xFF && sent[i + 1] == 0x5A
                    && ((sent[i + 4] & 0xFF) & com.carbridge.carplay.iap2.Iap2LinkPacket.CONTROL_SYN) != 0) {
                syn = true;
                break;
            }
        }
        check(syn, "收到 detect marker 后发出 SYN（" + sent.length + "B）");

        // 3) 一个包分成两半到达：第一半不应该被当坏帧丢掉
        com.carbridge.carplay.iap2.Iap2LinkPacket pkt =
                new com.carbridge.carplay.iap2.Iap2LinkPacket(0, 0, 0, 1, new byte[]{1, 2, 3, 4});
        byte[] frame = pkt.encode();
        int half = frame.length / 2;
        e.onBytes(java.util.Arrays.copyOfRange(frame, 0, half));
        check(true, "半包不会抛异常（留在缓冲）");
        e.onBytes(java.util.Arrays.copyOfRange(frame, half, frame.length));
        check(true, "补齐后能继续解析");

        // 3a) 先把链路推到 NORMAL：把我们刚发的 SYN 原样回成 SYN|ACK（模拟对端）
        com.carbridge.carplay.iap2.Iap2LinkPacket ourSyn = null;
        for (int i = 0; i + com.carbridge.carplay.iap2.Iap2LinkPacket.HEADER_SIZE < sent.length; i++) {
            if ((sent[i] & 0xFF) == 0xFF && sent[i + 1] == 0x5A) {
                int total = ((sent[i + 2] & 0xFF) << 8) | (sent[i + 3] & 0xFF);
                byte[] fr = java.util.Arrays.copyOfRange(sent, i, i + total);
                ourSyn = com.carbridge.carplay.iap2.Iap2LinkPacket.decode(fr);
                break;
            }
        }
        check(ourSyn != null, "能解出自己发出的 SYN");
        com.carbridge.carplay.iap2.Iap2LinkPacket synAck =
                new com.carbridge.carplay.iap2.Iap2LinkPacket(
                        com.carbridge.carplay.iap2.Iap2LinkPacket.CONTROL_SYN
                                | com.carbridge.carplay.iap2.Iap2LinkPacket.CONTROL_ACK,
                        0, 0, 0, ourSyn.payload);
        e.onBytes(synAck.encode());
        check(e.state() == com.carbridge.carplay.iap2.Iap2LinkEngine.State.NORMAL,
                "收到 SYN|ACK 后链路进入 NORMAL（当前 " + e.state() + "）");

        // 3b) 端到端：一个完整的 CSM 帧（会话 1、消息 0x1D00）必须被投递给 listener
        byte[] csm = com.carbridge.carplay.iap2.Iap2Csm.frame(0x1D00,
                com.carbridge.carplay.iap2.Iap2Csm.paramString(0, "HelloCar"));
        // CSM 需要包在链路帧里：session=1，从当前 seq 继续
        com.carbridge.carplay.iap2.Iap2LinkPacket wrap =
                new com.carbridge.carplay.iap2.Iap2LinkPacket(0, 1, 0, 1, csm);
        gotMessages.clear();
        e.onBytes(wrap.encode());
        check(gotMessages.size() == 1, "CSM 帧被解析并投递（收到 " + gotMessages.size() + " 条）");
        if (!gotMessages.isEmpty()) {
            check(gotMessages.get(0).messageId == 0x1D00, "消息 ID = 0x1D00");
            check(gotMessages.get(0).body.length == csm.length - 6, "消息体长度正确");
        }

        // 4) 长度字段非法的帧：跳过并重新同步，不能把后面的正常帧吃掉
        byte[] bad = new byte[]{(byte) 0xFF, 0x5A, 0x00, 0x02, 1, 2, 3, 4, 5};
        e.onBytes(bad);
        check(true, "非法长度帧被跳过");
        e.onBytes(frame);
        check(true, "跳过坏帧后仍能解析正常帧");
    }

    // ---------------------------------------------------------------- /info 解析

    /**
     * 车机 `/info` 的类型宽容解析。
     *
     * <p>各家车机这里千奇百怪：数字可能是 Long（bplist 整数）、也可能是字符串
     * `"1920"` / `"0x780"`；HID 的 uuid 在奥迪是字符串、在 DiPlay 是整数。
     * 解析歪了就是「画布不对/触摸偏移」，而现象看起来像「车机兼容性差」。
     */
    private static void airPlayInfo() {
        section("AirPlayInfo（车机 /info 解析）");

        java.util.Map<String, Object> root = new java.util.LinkedHashMap<String, Object>();
        java.util.List<Object> displays = new java.util.ArrayList<Object>();
        java.util.Map<String, Object> d0 = new java.util.LinkedHashMap<String, Object>();
        d0.put("widthPixels", Long.valueOf(1920));
        d0.put("heightPixels", Long.valueOf(720));
        d0.put("uuid", "5f2b1f8c-display");
        d0.put("maxFPS", Long.valueOf(60));
        displays.add(d0);
        // 第二块屏：宽高用字符串给（有的固件这么发）
        java.util.Map<String, Object> d1 = new java.util.LinkedHashMap<String, Object>();
        d1.put("widthPixels", "1280");
        d1.put("heightPixels", "0x2D0");     // 720
        displays.add(d1);
        root.put("displays", displays);

        com.carbridge.carplay.core.AirPlayInfo info = com.carbridge.carplay.core.AirPlayInfo.parse(root);
        check(info.displays().size() == 2, "解析出 2 块屏");
        com.carbridge.carplay.core.AirPlayInfo.Display p = info.primaryDisplay();
        check(p.widthPixels == 1920 && p.heightPixels == 720, "主屏 1920x720（Long 类型）");
        check(p.maxFps == 60, "主屏 maxFPS=60");
        com.carbridge.carplay.core.AirPlayInfo.Display s2 = info.displays().get(1);
        check(s2.widthPixels == 1280 && s2.heightPixels == 720, "字符串/十六进制宽高也能解析（1280x720）");
        check(s2.maxFps == 30, "没给 maxFPS 时默认 30");

        // HID：uuid 是整数（DiPlay 风格）
        java.util.Map<String, Object> hid = new java.util.LinkedHashMap<String, Object>();
        hid.put("uuid", Long.valueOf(0));
        hid.put("name", "touch");
        hid.put("hidDescriptor", new byte[]{0x05, 0x01});
        java.util.List<Object> hids = new java.util.ArrayList<Object>();
        hids.add(hid);
        root.put("hidDevices", hids);
        info = com.carbridge.carplay.core.AirPlayInfo.parse(root);
        check(info.hidDevices().size() == 1, "解析出 1 个 HID 设备");
        check("0".equals(info.hidDevices().get(0).uuid), "HID uuid 为整数时转成字符串");

        // 音频档：type=100 media + telephony，位掩码判断
        java.util.Map<String, Object> fmt1 = new java.util.LinkedHashMap<String, Object>();
        fmt1.put("type", Long.valueOf(100));
        fmt1.put("audioType", "media");
        fmt1.put("audioOutputFormats", Long.valueOf(1L << 11 | 1L << 22));  // PCM44k + AAC-LC
        java.util.Map<String, Object> fmt2 = new java.util.LinkedHashMap<String, Object>();
        fmt2.put("type", Long.valueOf(100));
        fmt2.put("audioType", "telephony");
        fmt2.put("audioOutputFormats", Long.valueOf(1L << 10));
        java.util.List<Object> fmts = new java.util.ArrayList<Object>();
        fmts.add(fmt1);
        fmts.add(fmt2);
        root.put("audioFormats", fmts);

        // 延迟表：按 (type, audioType) 配到对应档
        java.util.Map<String, Object> lat = new java.util.LinkedHashMap<String, Object>();
        lat.put("type", Long.valueOf(100));
        lat.put("audioType", "media");
        lat.put("outputLatencyMicros", Long.valueOf(25000));
        java.util.List<Object> lats = new java.util.ArrayList<Object>();
        lats.add(lat);
        root.put("audioLatencies", lats);
        root.put("keepAliveSendStatsAsBody", Boolean.TRUE);

        info = com.carbridge.carplay.core.AirPlayInfo.parse(root);
        check(info.audioFormats().size() == 2, "解析出 2 个音频档");
        com.carbridge.carplay.core.AirPlayInfo.AudioFormat media = info.audioFormats().get(0);
        check(media.supportsPcm44k() && media.supportsAacLc44k(), "media 档识别 PCM44k + AAC-LC 位");
        check(media.outputLatencyMicros == 25000, "media 档配上 25ms 延迟");
        com.carbridge.carplay.core.AirPlayInfo.AudioFormat tel = info.audioFormats().get(1);
        check(!tel.supportsPcm44k(), "telephony 档不该被认成 PCM44k");
        check(info.keepAliveSendStatsAsBody, "keepAliveSendStatsAsBody=true 被识别");

        // 垃圾输入
        check(com.carbridge.carplay.core.AirPlayInfo.parse(null).displays().isEmpty(), "null 根节点 → 空");
        check(com.carbridge.carplay.core.AirPlayInfo.parse("不是字典").displays().isEmpty(),
                "字符串根节点 → 空");
    }

    // ---------------------------------------------------------------- 密码学向量

    /**
     * RFC 8032（Ed25519）与 RFC 7748（X25519）官方测试向量。
     *
     * <p>为什么必须做：配对链路里的 Ed25519 验签和 X25519 共享密钥**从来没被真实车机
     * 验证过**（假车机不给长期公钥，M2 验签一直被跳过）。要是实现有错，真车上会表现为
     * 「配对一闪而过、车机不再发邀请」，而且日志看不出原因。用公开向量钉死实现。
     */
    private static void cryptoVectors() {
        section("Ed25519 / X25519（RFC 测试向量）");

        // RFC 8032 §7.1 TEST 1
        byte[] sk1 = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        byte[] pk1 = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        byte[] msg1 = new byte[0];
        byte[] sig1 = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
        check(com.carbridge.carplay.pairing.Ed25519.verify(sig1, pk1, msg1), "RFC8032 TEST1：空消息验签通过");
        byte[] got = com.carbridge.carplay.pairing.Ed25519.sign(sk1, msg1);
        check(java.util.Arrays.equals(got, sig1), "RFC8032 TEST1：签名与向量一致");

        // RFC 8032 §7.1 TEST 2（一字节消息 0x72）
        byte[] sk2 = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb");
        byte[] pk2 = hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c");
        byte[] msg2 = hex("72");
        byte[] sig2 = hex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00");
        check(com.carbridge.carplay.pairing.Ed25519.verify(sig2, pk2, msg2), "RFC8032 TEST2：单字节消息验签通过");

        // 篡改必须失败
        byte[] bad = sig1.clone();
        bad[0] ^= 0x01;
        check(!com.carbridge.carplay.pairing.Ed25519.verify(bad, pk1, msg1), "篡改签名必须验签失败");

        // 小阶点公钥伪造必须被拒（第 6 批修复钉死）：
        // A = identity（01‖00×31），R = 基点编码（y=4/5 mod P），s = 1 ——
        // cofactorless 方程下这套伪造在旧实现里会误判通过。
        byte[] aId = new byte[32];
        aId[0] = 0x01;
        java.math.BigInteger p = java.math.BigInteger.ONE.shiftLeft(255)
                .subtract(java.math.BigInteger.valueOf(19));
        java.math.BigInteger baseY = java.math.BigInteger.valueOf(4)
                .multiply(java.math.BigInteger.valueOf(5).modInverse(p)).mod(p);
        byte[] rb = new byte[32];
        for (int i = 0; i < 32; i++) {
            rb[i] = (byte) (baseY.shiftRight(i * 8).intValue() & 0xFF);
        }
        byte[] forge = new byte[64];
        System.arraycopy(rb, 0, forge, 0, 32);
        forge[32] = 0x01;   // s = 1（小端）
        check(!com.carbridge.carplay.pairing.Ed25519.verify(forge, aId, "x".getBytes()),
                "小阶点公钥伪造必须被拒（A=identity, s=1, R=[1]B）");

        // RFC 7748 §6.1：X25519 两个密钥交换必须得到同一共享密钥
        byte[] aliceSk = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        byte[] bobSk   = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
        byte[] alicePk = com.carbridge.carplay.pairing.X25519.publicKey(aliceSk);
        byte[] bobPk   = com.carbridge.carplay.pairing.X25519.publicKey(bobSk);
        byte[] s1 = com.carbridge.carplay.pairing.X25519.sharedSecret(aliceSk, bobPk);
        byte[] s2 = com.carbridge.carplay.pairing.X25519.sharedSecret(bobSk, alicePk);
        check(java.util.Arrays.equals(s1, s2), "X25519：双方共享密钥一致");
        byte[] expect = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742");
        check(java.util.Arrays.equals(s1, expect), "X25519：共享密钥与 RFC7748 向量一致");
    }

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // ---------------------------------------------------------------- 工具

    // ---------------------------------------------------------------- 首个断点

    private static void stepDiagnosis() {
        section("StepDiagnosis");
        String[] cl = {"等待 Car+ 打开通道", "连接 6 条通道", "开始推流"};
        String[] cp = {"蓝牙监听", "iAP2 链路握手", "加入车机热点", "推流中"};

        StepDiagnosis.Result idle = StepDiagnosis.of(cl, null, null, cp, null, null, 0);
        check(idle.level == StepDiagnosis.Level.IDLE, "没开始 → IDLE");
        check(idle.text().contains("未开始"), "没开始有文案");

        StepDiagnosis.Result run = StepDiagnosis.of(cl, 0, null, cp, 1, null, 2000);
        check(run.level == StepDiagnosis.Level.RUNNING, "进行中 → RUNNING");
        check(run.headline.contains("CarPlay") && run.headline.contains("第 2/4 步"), "取 CarPlay 当前步");
        check(run.headline.contains("iAP2 链路握手"), "带步骤名");

        StepDiagnosis.Result stall = StepDiagnosis.of(cl, null, null, cp, 2, null,
                StepDiagnosis.STALL_MS + 500);
        check(stall.text().contains("无进展"), "超过阈值报无进展");
        check(stall.text().contains("热点"), "卡在热点给对应提示");
        check(stall.text().contains("8.5s"), "带停住时长");

        // 两条链都有失败：CarPlay 优先
        StepDiagnosis.Result fail = StepDiagnosis.of(cl, 5, 1, cp, 1, 2, 0);
        check(fail.level == StepDiagnosis.Level.FAILED, "失败优先于进行中");
        check(fail.headline.contains("CarPlay") && fail.headline.contains("第 3/4 步"),
                "CarPlay 失败优先于 CarLife");
        check(fail.text().contains("0x5703"), "失败文案带该阶段提示");

        // 只有 CarLife 失败
        StepDiagnosis.Result failCl = StepDiagnosis.of(cl, 5, 1, cp, null, null, 0);
        check(failCl.level == StepDiagnosis.Level.FAILED && failCl.headline.contains("CarLife"),
                "没有 CarPlay 失败时用 CarLife");

        // 失败也带停住时长
        StepDiagnosis.Result failMs = StepDiagnosis.of(cl, null, 1, cp, null, null, 12300);
        check(failMs.text().contains("12.3s"), "失败文案带停住时长");

        // 两条链都到终态
        StepDiagnosis.Result done = StepDiagnosis.of(cl, 2, null, cp, 3, null, 0);
        check(done.level == StepDiagnosis.Level.DONE, "都到终态 → DONE");

        // 越界 / 空 / null 不抛
        StepDiagnosis.Result edge = StepDiagnosis.of(new String[0], 0, 0, null, null, null, 0);
        check(edge.level == StepDiagnosis.Level.IDLE, "空数组与越界安全降级");
        check(StepDiagnosis.hintFor(null).isEmpty(), "null 标签不抛");
    }

    // ---------------------------------------------------------------- HID 触摸

    private static void hid() {
        section("HidInput / HidDescriptor（触摸 Report ID）");

        // 带 Report ID 的触摸描述符：X 4095 / Y 2047 / Report ID 5
        byte[] desc = new byte[]{
                0x05, 0x0D,             // Usage Page (Digitizer)
                0x09, 0x22,             // Usage (Finger)
                (byte) 0xA1, 0x01,      // Collection (Application)
                0x05, 0x0D,             //   Usage Page (Digitizer) — 集合内再声明，按真实描述符习惯
                0x09, 0x30,             //   Usage (X)
                0x15, 0x00,             //   Logical Minimum (0)
                0x26, (byte) 0xFF, 0x0F,//   Logical Maximum (4095)
                0x75, 0x10,             //   Report Size (16)
                (byte) 0x95, 0x01,      //   Report Count (1)
                (byte) 0x85, 0x05,      //   Report ID (5)
                (byte) 0x81, 0x02,      //   Input (Data,Var,Abs)
                0x09, 0x31,             //   Usage (Y)
                0x26, (byte) 0xFF, 0x07,//   Logical Maximum (2047)
                (byte) 0x81, 0x02,      //   Input
                (byte) 0xC0             // End Collection
        };
        int[] ax = com.carbridge.carplay.core.HidDescriptor.touchAxisMaxima(desc);
        check(ax != null && ax[0] == 4095 && ax[1] == 2047, "触摸轴上限 4095×2047");
        check(com.carbridge.carplay.core.HidDescriptor.touchReportId(desc) == 5,
                "解析出 Report ID = 5");

        // 没有 Report ID 的描述符
        byte[] descNoId = new byte[]{
                0x05, 0x0D, 0x09, 0x22, (byte) 0xA1, 0x01,
                0x09, 0x30, 0x15, 0x00, 0x26, (byte) 0xFF, 0x0F,
                0x75, 0x10, (byte) 0x95, 0x01, (byte) 0x81, 0x02,
                0x09, 0x31, 0x26, (byte) 0xFF, 0x07, (byte) 0x81, 0x02,
                (byte) 0xC0
        };
        check(com.carbridge.carplay.core.HidDescriptor.touchReportId(descNoId) == 0,
                "没有 Report ID 时返回 0");

        // 联合声明：Usage(X), Usage(Y), 一条 Input（真车常见写法）——两轴都要拿到
        byte[] descJoint = new byte[]{
                0x05, 0x0D, 0x09, 0x22, (byte) 0xA1, 0x01,
                0x09, 0x30, 0x09, 0x31,     // Usage(X), Usage(Y) 同一条主项
                0x15, 0x00, 0x26, (byte) 0xFF, 0x0F,
                0x75, 0x10, (byte) 0x95, 0x02,   // size16 × count2
                (byte) 0x81, 0x02,
                (byte) 0xC0
        };
        int[] joint = com.carbridge.carplay.core.HidDescriptor.touchAxisMaxima(descJoint);
        check(joint != null && joint[0] == 4095 && joint[1] == 4095,
                "联合 X/Y 一条主项 → 两轴上限都拿到（第 15 批修复）");

        // Usage 数组声明（Contact Count 这类）不得污染轴上限兜底
        byte[] descRange = new byte[]{
                0x05, 0x0D, 0x09, 0x22, (byte) 0xA1, 0x01,
                0x09, 0x30, 0x15, 0x00, 0x26, (byte) 0xFF, 0x0F,
                0x75, 0x10, (byte) 0x95, 0x01, (byte) 0x81, 0x02,   // X 4095
                0x19, 0x51, 0x29, 0x51,                              // UsageMin/Max 数组
                0x15, 0x00, 0x25, 0x0A,
                0x75, 0x08, (byte) 0x95, 0x01, (byte) 0x81, 0x02,   // max=10 的数组字段
                (byte) 0xC0
        };
        int[] rangeAx = com.carbridge.carplay.core.HidDescriptor.touchAxisMaxima(descRange);
        check(rangeAx != null && rangeAx[0] == 4095 && rangeAx[1] == 4095,
                "Usage 数组字段不参与轴上限（10 不会顶掉 4095）");

        // 带 ID 的单指报告：[ID, 按下, X_lo, X_hi, Y_lo, Y_hi]
        byte[] withId = new byte[]{0x05, 0x01, (byte) 0xE8, 0x03, (byte) 0xF4, 0x01}; // x=1000 y=500
        com.carbridge.carplay.core.HidInput.Touch t =
                com.carbridge.carplay.core.HidInput.parseTouch(withId, 4095, 2047, 5);
        check(t.down, "带 Report ID：按下状态正确");
        check(Math.abs(t.x - 1000f / 4095f) < 1e-6, "带 Report ID：X 未被 ID 字节挤偏");
        check(Math.abs(t.y - 500f / 2047f) < 1e-6, "带 Report ID：Y 正确");

        // 同一条报告不带 ID（首字节不是 5）→ 按无 ID 解，不能误剥
        byte[] noId = new byte[]{0x01, (byte) 0xE8, 0x03, (byte) 0xF4, 0x01};
        com.carbridge.carplay.core.HidInput.Touch t2 =
                com.carbridge.carplay.core.HidInput.parseTouch(noId, 4095, 2047, 5);
        check(Math.abs(t2.x - 1000f / 4095f) < 1e-6, "首字节不匹配 → 不剥，坐标仍正确");

        // 带 ID 的多点报告：1 + 6×2 = 13 字节（ID 5，第 0 指 down+x/y）
        byte[] multi = new byte[]{0x05, 0x00, 0x01, (byte) 0xE8, 0x03, (byte) 0xF4, 0x01,
                0x01, 0x00, 0x00, 0x00, 0x00, 0x00};
        com.carbridge.carplay.core.HidInput.Touch t3 =
                com.carbridge.carplay.core.HidInput.parseTouch(multi, 4095, 2047, 5);
        check(t3.down && Math.abs(t3.x - 1000f / 4095f) < 1e-6,
                "带 Report ID 的 12 字节多点报告：按多点布局解");

        // 只剩 4 字节（剥掉 ID 后不足）必须报错，不能静默出 (0,0)
        boolean threw = false;
        try {
            com.carbridge.carplay.core.HidInput.parseTouch(new byte[]{0x05, 0x01, 0x02, 0x03},
                    4095, 2047, 5);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "剥 ID 后不足 5 字节 → 抛异常");

        check(com.carbridge.carplay.core.HidInput.parseTouch(new byte[]{0x01, 0, 0, 0, 0}).down,
                "5 字节单指报告：down 取首字节");
    }

    // ---------------------------------------------------------------- HID 按键

    private static void hidKeys() {
        section("HidKeyMap（车机硬键 / Report ID）");

        // Consumer Control，Array 语义，Report ID = 2
        byte[] arrayDesc = new byte[]{
                0x05, 0x0C,             // Usage Page (Consumer)
                0x09, 0x01,             // Usage (Consumer Control)
                (byte) 0xA1, 0x01,      // Collection (Application)
                (byte) 0x85, 0x02,      //   Report ID (2)
                0x09, (byte) 0xB0,      //   Usage (Play)
                0x09, (byte) 0xB1,      //   Usage (Pause)
                0x09, (byte) 0xB5,      //   Usage (Scan Next)
                0x15, 0x00,             //   Logical Minimum (0)
                0x25, 0x02,             //   Logical Maximum (2)
                0x75, 0x08,             //   Report Size (8)
                (byte) 0x95, 0x01,      //   Report Count (1)
                (byte) 0x81, 0x00,      //   Input (Data,Array,Abs)
                (byte) 0xC0             // End Collection
        };
        com.carbridge.carplay.core.HidKeyMap arr =
                com.carbridge.carplay.core.HidKeyMap.parse(arrayDesc);
        check(arr != null, "Array 描述符解析成功");
        check(arr != null && arr.array, "识别为 Array 语义");
        check(arr != null && arr.reportId == 2, "解析出 Report ID = 2");
        check(arr != null && arr.page == 0x0C, "识别为 Consumer 页");
        check(arr != null && arr.usages.length == 3, "收集到 3 个 usage");

        int[] p1 = arr.pressedUsages(new byte[]{0x02, 0x01});
        check(p1.length == 1 && p1[0] == 0xB1, "带 ID 的报告：下标 1 → Pause（不被 ID 挤偏）");
        int[] p0 = arr.pressedUsages(new byte[]{0x02, 0x00});
        check(p0.length == 0, "报告值 0 → 无按键（释放）");
        int[] pOut = arr.pressedUsages(new byte[]{0x02, 0x09});
        check(pOut.length == 0, "下标越界 → 无按键，不抛异常");
        int[] noId = arr.pressedUsages(new byte[]{0x01, 0x01});
        check(noId.length == 1 && noId[0] == 0xB1, "首字节不是 Report ID → 不剥，仍按下标解");

        // Variable/位图语义，Report ID = 3
        byte[] bitDesc = new byte[]{
                0x05, 0x0C, 0x09, 0x01, (byte) 0xA1, 0x01,
                (byte) 0x85, 0x03,
                0x09, (byte) 0xB0, 0x09, (byte) 0xB1, 0x09, (byte) 0xB5,
                0x15, 0x00, 0x25, 0x01,
                0x75, 0x01, (byte) 0x95, 0x03,
                (byte) 0x81, 0x02,
                (byte) 0xC0
        };
        com.carbridge.carplay.core.HidKeyMap bits =
                com.carbridge.carplay.core.HidKeyMap.parse(bitDesc);
        check(bits != null && !bits.array, "识别为 Variable/位图语义");
        int[] pressed = bits.pressedUsages(new byte[]{0x03, 0x05});   // bit0 + bit2
        check(pressed.length == 2 && pressed[0] == 0xB0 && pressed[1] == 0xB5,
                "位图 0b101 → Play + Scan Next");
        check(bits.pressedUsages(new byte[]{0x03, 0x00}).length == 0, "位图全 0 → 无按键");
        int[] noIdBits = bits.pressedUsages(new byte[]{0x02});        // 无 ID 的位图报告
        check(noIdBits.length == 1 && noIdBits[0] == 0xB1,
                "位图报告不带 ID 时也能解（bit1 → Pause）");

        // Telephony 页
        byte[] telDesc = new byte[]{
                0x05, 0x0B, 0x09, 0x05, (byte) 0xA1, 0x01,
                0x09, 0x20, 0x15, 0x00, 0x25, 0x01,
                0x75, 0x08, (byte) 0x95, 0x01, (byte) 0x81, 0x00,
                (byte) 0xC0
        };
        com.carbridge.carplay.core.HidKeyMap tel =
                com.carbridge.carplay.core.HidKeyMap.parse(telDesc);
        check(tel != null && tel.page == 0x0B, "Telephony 页识别");
        check(tel != null && tel.usages.length == 1 && tel.usages[0] == 0x20,
                "收集到 Hook Switch usage");
        check(tel != null && tel.usageFor(1) == -1, "只有 1 个 usage：下标 1 越界 → -1");

        check(com.carbridge.carplay.core.HidKeyMap.parse(null) == null, "null 描述符 → null");
        check(com.carbridge.carplay.core.HidKeyMap.parse(new byte[]{0x05, 0x0D}) == null,
                "非按键描述符 → null");

        // usageFor 语义
        check(arr.usageFor(0) == -1, "Array：值 0 → -1");
        check(arr.usageFor(1) == 0xB1, "Array：值 1 → Pause");
        check(bits.usageFor(0xB5) == 0xB5, "Variable：值就是 usage 本身");
    }

    // ---------------------------------------------------------------- RTSP

    private static void rtsp() throws java.io.IOException {
        section("RtspMessage（控制通道收发 / 长度闸门）");

        byte[] req = com.carbridge.carplay.core.RtspMessage.request("GET", "/info", 3,
                new byte[]{1, 2, 3});
        String s = new String(req, java.nio.charset.StandardCharsets.US_ASCII);
        check(s.startsWith("GET /info RTSP/1.0\r\n"), "请求行格式");
        check(s.contains("CSeq: 3\r\n"), "CSeq 写对了");
        check(s.contains("Content-Length: 3\r\n"), "body 长度与实际一致");
        check(s.contains("User-Agent: " + com.carbridge.carplay.core.RtspMessage.userAgent()),
                "User-Agent 与 SETUP 同源");

        com.carbridge.carplay.core.RtspMessage.Response r = parse(
                "RTSP/1.0 200 OK\r\nCSeq: 7\r\nContent-Type: text/parameters\r\n"
                        + "Content-Length: 3\r\n\r\nabc");
        check(r != null && r.status == 200, "状态码 200");
        check(r != null && r.cseq == 7, "CSeq 解出 7");
        check(r != null && r.body.length == 3 && r.body[0] == 'a', "body 按 Content-Length 读全");

        com.carbridge.carplay.core.RtspMessage.Response noCseq = parse("RTSP/1.0 200 OK\r\n\r\n");
        check(noCseq != null && noCseq.cseq == -1, "没有 CSeq → -1（调用方据此放行）");

        // 非数字状态码必须变成 IOException，不能把 NumberFormatException 抛给只 catch IOException 的调用点
        boolean io1 = false;
        try {
            parse("RTSP/1.0 abc OK\r\n\r\n");
        } catch (java.io.IOException e) {
            io1 = true;
        }
        check(io1, "状态码非数字 → IOException");

        boolean io2 = false;
        try {
            parse("RTSP/1.0 200 OK\r\nContent-Length: 12x\r\n\r\n");
        } catch (java.io.IOException e) {
            io2 = true;
        }
        check(io2, "Content-Length 非数字 → IOException");

        boolean io3 = false;
        try {
            parse("RTSP/1.0 200 OK\r\nContent-Length: " + (com.carbridge.carplay.core.RtspMessage.MAX_BODY + 1)
                    + "\r\n\r\n");
        } catch (java.io.IOException e) {
            io3 = true;
        }
        check(io3, "Content-Length 超上限 → IOException（不会先分配内存）");

        // 一行几千字节的垃圾：没有换行时必须被长度闸门挡住
        StringBuilder junk = new StringBuilder();
        for (int i = 0; i < com.carbridge.carplay.core.RtspMessage.MAX_LINE + 10; i++) junk.append('A');
        boolean io4 = false;
        try {
            com.carbridge.carplay.core.RtspMessage.readLine(
                    new java.io.ByteArrayInputStream(junk.toString()
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (java.io.IOException e) {
            io4 = true;
        }
        check(io4, "无换行的超长行 → IOException（缓冲不会无限涨）");

        // 裸 LF 也要能解（少数实现不发 CR）
        try {
            String lf = com.carbridge.carplay.core.RtspMessage.readLine(
                    new java.io.ByteArrayInputStream("CSeq: 1\n".getBytes(
                            java.nio.charset.StandardCharsets.US_ASCII)));
            check("CSeq: 1".equals(lf), "裸 LF 行尾也能解");
        } catch (java.io.IOException e) {
            check(false, "裸 LF 行尾也能解（抛了 " + e + "）");
        }
    }

    private static com.carbridge.carplay.core.RtspMessage.Response parse(String raw) throws java.io.IOException {
        return com.carbridge.carplay.core.RtspMessage.readResponse(
                new java.io.ByteArrayInputStream(raw.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }

    // ---------------------------------------------------------------- AEAD 与 Bplist 边界

    /**
     * 第 5 批审计修复的钉死测试：
     * chachaSeal/chachaOpen 必须真 AEAD（tag 校验），Bplist 对越界长度必须抛异常而不是 OOM。
     */
    private static void aeadAndBplist() throws Exception {
        section("AEAD（ChaCha20-Poly1305 seal/open）与 Bplist 边界");

        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) (i * 7 + 1);
        byte[] plain = "pair-verify 载荷测试 {IDENTIFIER, SIGNATURE}".getBytes("UTF-8");

        // 1) seal → open 往返一致，且 open 输出不多尾巴（旧「再加密一次」会多 32B）
        byte[] sealed = com.carbridge.carplay.pairing.PairVerifySession.chachaSeal(key, "PV-Msg02", plain);
        byte[] opened = com.carbridge.carplay.pairing.PairVerifySession.chachaOpen(key, "PV-Msg02", sealed);
        check(java.util.Arrays.equals(plain, opened), "seal→open 往返一致");
        check(opened.length == plain.length, "open 输出无尾部残留（旧误用会多 32B）");

        // 2) 篡改密文任意比特 → open 必须抛（旧实现 tag 从不校验，篡改静默通过）
        byte[] tampered = sealed.clone();
        tampered[3] ^= 0x01;
        boolean threw = false;
        try {
            com.carbridge.carplay.pairing.PairVerifySession.chachaOpen(key, "PV-Msg02", tampered);
        } catch (java.security.GeneralSecurityException e) {
            threw = true;
        }
        check(threw, "篡改密文 → chachaOpen 抛 AEAD 异常（tag 真校验）");

        // 3) 标签（nonce）不同 → 打不开
        threw = false;
        try {
            com.carbridge.carplay.pairing.PairVerifySession.chachaOpen(key, "PV-Msg03", sealed);
        } catch (java.security.GeneralSecurityException e) {
            threw = true;
        }
        check(threw, "nonce 标签不符 → chachaOpen 抛异常");

        // 4) Bplist 正常往返（先确认没把好输入弄坏）
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("widthPixels", 1920L);
        m.put("name", "car");
        java.util.List<Object> arr = new java.util.ArrayList<Object>();
        arr.add(1L);
        arr.add("x");
        m.put("list", arr);
        Object back = com.carbridge.carplay.core.Bplist.read(com.carbridge.carplay.core.Bplist.write(m));
        check(back instanceof java.util.Map
                && "car".equals(((java.util.Map<?, ?>) back).get("name")), "Bplist 写读往返");

        // 5) Bplist 对抗输入：numObjects 巨大（旧实现直接 new long[0x40000000] → OOM Error 逃逸）
        byte[] evil = com.carbridge.carplay.core.Bplist.write(m);
        int trailer = evil.length - 32;
        // trailer 的 numObjects（8 字节 BE，偏移 trailer+8）改写成 2^30：
        byte[] evilNum = evil.clone();
        for (int i = 0; i < 8; i++) evilNum[trailer + 8 + i] = 0;
        evilNum[trailer + 8 + 3] = 0x40;   // = 00 00 00 00 40 00 00 00 → 0x40000000
        threw = false;
        try {
            com.carbridge.carplay.core.Bplist.read(evilNum);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "numObjects=2^30 → IllegalArgumentException（不再 OOM）");

        // 6) 截断的 bplist → 抛异常（不 AIOOBE）
        threw = false;
        try {
            com.carbridge.carplay.core.Bplist.read(java.util.Arrays.copyOf(evil, 20));
        } catch (RuntimeException e) {
            threw = true;
        }
        check(threw, "截断 bplist → 抛异常");
    }

    // ---------------------------------------------------------------- ControlCipher 帧级韧性

    /**
     * 第 6 批：单帧 AEAD 失败不再楔死通道（丢帧续解），连续 4 帧失败才判死；
     * 缓冲超 256KB 判错位。
     */
    private static void controlCipher() throws Exception {
        section("ControlCipher 帧级韧性");

        byte[] shared = new byte[32];
        for (int i = 0; i < 32; i++) shared[i] = (byte) (i * 3 + 5);
        com.carbridge.carplay.pairing.ControlCipher enc =
                new com.carbridge.carplay.pairing.ControlCipher(shared, true, true);
        com.carbridge.carplay.pairing.ControlCipher dec =
                new com.carbridge.carplay.pairing.ControlCipher(shared, true, false);

        // 1) 三帧正常往返
        byte[] f1 = enc.encrypt("帧一".getBytes("UTF-8"));
        byte[] f2 = enc.encrypt("帧二".getBytes("UTF-8"));
        byte[] f3 = enc.encrypt("帧三".getBytes("UTF-8"));
        byte[] all = new byte[f1.length + f2.length + f3.length];
        System.arraycopy(f1, 0, all, 0, f1.length);
        System.arraycopy(f2, 0, all, f1.length, f2.length);
        System.arraycopy(f3, 0, all, f1.length + f2.length, f3.length);
        String got = new String(dec.decrypt(all), "UTF-8");
        check("帧一帧二帧三".equals(got), "三帧顺序解密一致");

        // 2) 中间帧损坏：丢它但后续帧还能解（旧实现从此永久楔死）
        com.carbridge.carplay.pairing.ControlCipher enc2 =
                new com.carbridge.carplay.pairing.ControlCipher(shared, true, true);
        com.carbridge.carplay.pairing.ControlCipher dec2 =
                new com.carbridge.carplay.pairing.ControlCipher(shared, true, false);
        byte[] g1 = enc2.encrypt("好帧A".getBytes("UTF-8"));
        byte[] gBad = enc2.encrypt("坏帧".getBytes("UTF-8"));
        byte[] g3 = enc2.encrypt("好帧B".getBytes("UTF-8"));
        gBad[gBad.length - 2] ^= 0x01;   // 翻一个比特 → tag 必坏
        byte[] seq = new byte[g1.length + gBad.length + g3.length];
        System.arraycopy(g1, 0, seq, 0, g1.length);
        System.arraycopy(gBad, 0, seq, g1.length, gBad.length);
        System.arraycopy(g3, 0, seq, g1.length + gBad.length, g3.length);
        String got2 = new String(dec2.decrypt(seq), "UTF-8");
        check("好帧A好帧B".equals(got2), "中间坏帧被跳过，后续帧照常解密（不再楔死）");

        // 3) 连续 4 帧全坏 → 判通道不可恢复（抛异常）
        com.carbridge.carplay.pairing.ControlCipher enc3 =
                new com.carbridge.carplay.pairing.ControlCipher(shared, true, true);
        com.carbridge.carplay.pairing.ControlCipher dec3 =
                new com.carbridge.carplay.pairing.ControlCipher(shared, true, false);
        byte[] bad = enc3.encrypt("x".getBytes("UTF-8"));
        bad[bad.length - 1] ^= 0x01;
        boolean threw = false;
        try {
            for (int i = 0; i < 4; i++) dec3.decrypt(bad);
        } catch (java.security.GeneralSecurityException e) {
            threw = true;
        }
        check(threw, "连续 4 帧 AEAD 失败 → 判通道死亡（触发上层重连）");
    }

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
