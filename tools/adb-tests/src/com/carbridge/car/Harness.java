package com.carbridge.car;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * JVM 直连 fake-adbd（--stray 模式）验证 AdbClient 的流隔离。
 *
 * <p>断言：每条 shell 命令都必须拿到**自己的**输出 —— fake-adbd 在每条应答前
 * 注入上一条流的迟到 WRTE+CLSE，旧实现（不看流 id）必然错位。
 */
public final class Harness {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 15575;

        // 现场生成一对测试密钥（fake-adbd 不验签，但 parsePrivateKey 要合法 PKCS8）。
        // parsePrivateKey 会剥掉头尾与空白，所以这里直接给纯 base64 的 PKCS8。
        KeyPairGenerator kg = KeyPairGenerator.getInstance("RSA");
        kg.initialize(2048);
        KeyPair kp = kg.generateKeyPair();
        String pem = Base64.getMimeEncoder().encodeToString(kp.getPrivate().getEncoded());

        AdbClient c = new AdbClient(new AdbClient.Log() {
            @Override public void log(String line) { System.out.println("  [adb] " + line); }
        });
        c.connect("127.0.0.1", port, pem, "fake adbkey ", 3000);

        int pass = 0, fail = 0;
        // 按 CarSideSetup 的命令序列跑：每条都必须拿到自己的输出（有串台包时旧实现必错位）
        String[][] cmds = {
            {"getprop ro.product.model", "FakeCar-CTL"},
            {"getprop ro.build.display.id", "FAKE_17.0.1/demo"},
            {"getprop ro.build.type", "OK"},                        // fake-adbd 对未知命令回 OK
            {"getprop persist.sys.wirelesscarplay.address", ""},    // 空输出最易被上一条挤占
            {"getprop ro.product.model", "FakeCar-CTL"},
        };
        for (String[] tc : cmds) {
            String out = c.shell(tc[0]).trim();
            boolean ok = out.equals(tc[1]);
            System.out.printf("%s `%s` -> [%s]%n", ok ? "✓" : "✗ 错位!", tc[0], out);
            if (ok) pass++; else fail++;
        }
        c.close();
        System.out.println("RESULT: pass=" + pass + " fail=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    private Harness() {
    }
}
