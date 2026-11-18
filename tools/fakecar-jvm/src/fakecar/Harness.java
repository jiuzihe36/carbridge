package fakecar;

import com.carbridge.carplay.iap2.Iap2Csm;
import com.carbridge.carplay.iap2.Iap2LinkEngine;
import com.carbridge.carplay.iap2.Iap2SenderSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 假车机测试台：把**产品代码里的发送端**（{@link Iap2SenderSession}）和
 * {@link FakeCar} 用两条无界队列对接起来，跑若干车机画像，逐条断言。
 *
 * <p>它验证的是「我们发给车机的字节」和「我们对车机行为的反应」，不需要蓝牙、
 * 不需要第二台设备，在 Mac 上 {@code ./build.sh && ./run.sh} 就能跑。
 */
public final class Harness {

    // ------------------------------------------------------------------ 管道

    /** 一次 send = 一个完整包，两边各有一个消费线程，避免同步回调互相重入。 */
    static final class Link {
        final BlockingQueue<byte[]> toCar = new LinkedBlockingQueue<byte[]>();
        final BlockingQueue<byte[]> toPhone = new LinkedBlockingQueue<byte[]>();
        volatile boolean running = true;
    }

    /** 一个场景跑完的结果。 */
    static final class Result {
        final List<String> pass = new ArrayList<String>();
        final List<String> fail = new ArrayList<String>();

        void check(boolean ok, String what) {
            (ok ? pass : fail).add(what);
        }
    }

    static int failures = 0;
    static final java.util.concurrent.atomic.AtomicReference<java.util.List<String>> logsRef =
            new java.util.concurrent.atomic.AtomicReference<java.util.List<String>>(
                    new java.util.ArrayList<String>());
    static final boolean DEBUG = System.getenv("FAKECAR_DEBUG") != null;

    public static void main(String[] args) throws Exception {
        System.out.println("carbridge 假车机测试台（iAP2 控制链路）");
        System.out.println("=========================================");

        scenario("标准车机（清单完整）", FakeCar.Profile.chery(), new Scenario() {
            @Override
            public void run(Iap2SenderSession session, FakeCar car, Result r) throws Exception {
                waitFor(car, 0x4301, 3000);
                r.check(car.countOf(0x4E0D) == 1, "发出 0x4E0D 无线会话标识");
                r.check(car.countOf(0x4E0A) == 1, "发出 0x4E0A 语言");
                r.check(car.countOf(0x4E09) == 1, "发出 0x4E09 设备名");
                r.check(car.countOf(0x4E0B) == 1, "发出 0x4E0B 时间");
                r.check(car.countOf(0x4E0C) == 1, "发出 0x4E0C 设备 UUID");
                r.check(car.countOf(0x4300) == 1, "0x4300 只发一次");
                String mac = macIn4300(car);
                r.check("AA:BB:CC:DD:EE:01".equals(mac), "0x4300 携带真实蓝牙 MAC（实际 " + mac + "）");
                r.check(car.countOf(0x5702) == 1, "发出 0x5702 请求热点凭据");
            }
        });

        scenario("奥迪形态：可收清单只有 5 项", FakeCar.Profile.audiSparseList(), new Scenario() {
            @Override
            public void run(Iap2SenderSession session, FakeCar car, Result r) throws Exception {
                waitFor(car, 0x4300, 3000);
                r.check(car.countOf(0x4E0D) == 1, "清单没声明 0x4E0D 也照样发");
                r.check(car.countOf(0x4E0B) == 1, "清单没声明 0x4E0B 也照样发");
                r.check(car.countOf(0x4E09) == 1, "0x4E09 设备名照发");
                r.check(car.countOf(0x4300) == 1, "0x4300 照发一次");
            }
        });

        scenario("车机热点没开：不回 0x5703", hotspotOff(), new Scenario() {
            @Override
            public void run(Iap2SenderSession session, FakeCar car, Result r) throws Exception {
                waitFor(car, 0x5702, 2000);
                Thread.sleep(300);
                session.onWifiConfigTimeout();      // 上层定时器到点
                boolean seen = waitFor(car, 0x4300, 1500);
                r.check(seen, "等不到 0x5703 时改发 0x4300（超时兜底）");
                r.check(car.countOf(0x4300) == 1, "兜底路径 0x4300 只发一次");
            }
        });

        scenario("新势力车机：只收 0x4300 / 只发 0x4301", FakeCar.Profile.newGenNoWifi(), new Scenario() {
            @Override
            public void run(Iap2SenderSession session, FakeCar car, Result r) throws Exception {
                boolean seen = waitFor(car, 0x4300, 2500);
                r.check(seen, "不等 0x5703 就发出 0x4300（提前发）");
                r.check(car.countOf(0x5702) == 0 || car.countOf(0x4300) > 0,
                        "0x4300 已发出（0x5702 可有可无）");
            }
        });

        scenario("重复触发：0x5703 晚到 + 超时兜底", FakeCar.Profile.chery(), new Scenario() {
            @Override
            public void run(Iap2SenderSession session, FakeCar car, Result r) throws Exception {
                waitFor(car, 0x5702, 2000);
                session.onWifiConfigTimeout();       // 先超时一次
                Thread.sleep(200);
                session.onWifiConfigTimeout();       // 再来一次
                Thread.sleep(400);
                r.check(car.countOf(0x4300) == 1, "0x4300 仍然只发一次（闸门生效）");
            }
        });


        scenario("车机拒绝识别（回 0x1D03）", rejectProfile(), new Scenario() {
            @Override
            public void run(Iap2SenderSession session, FakeCar car, Result r) throws Exception {
                boolean got = waitFor(car, 0x1D00, 2000);
                r.check(got, "发出 0x1D00");
                Thread.sleep(600);
                r.check(session.state() == Iap2SenderSession.State.ERROR,
                        "被拒绝后进入 ERROR（当前 " + session.state() + "）");
            }
        });

        scenario("车机中途 RST 拆链", rstProfile(), new Scenario() {
            @Override
            public void run(Iap2SenderSession session, FakeCar car, Result r) throws Exception {
                Thread.sleep(600);
                r.check(session.state() == Iap2SenderSession.State.CLOSED,
                        "收到 RST 后进入 CLOSED（当前 " + session.state() + "）");
            }
        });

        scenario("0x4301 早于 0x4300 到达", earlyStartProfile(), new Scenario() {
            @Override
            public void run(Iap2SenderSession session, FakeCar car, Result r) throws Exception {
                waitFor(car, 0x5702, 2000);
                Thread.sleep(500);
                synchronized (session) { }
                r.check(session.state() != Iap2SenderSession.State.ERROR,
                        "提前到的 0x4301 不会让会话进 ERROR（当前 " + session.state() + "）");
            }
        });


        scenario("签名形状不符（5B）", shortSigProfile(), new Scenario() {
            @Override
            public void run(Iap2SenderSession session, FakeCar car, Result r) throws Exception {
                boolean seen = waitFor(car, 0x4300, 3000);
                r.check(seen, "宽松模式下仍然继续到 0x4300（形状不对不阻断）");
                r.check(logContains(logsRef.get(), "签名形状不符"), "日志里记了「签名形状不符」");
            }
        });

        scenario("车机重放同一份签名", replayProfile(), new Scenario() {
            @Override
            public void run(Iap2SenderSession session, FakeCar car, Result r) throws Exception {
                boolean seen = waitFor(car, 0x4300, 3000);
                r.check(seen, "宽松模式下仍然继续（重放只记日志）");
                r.check(logContains(logsRef.get(), "重放"), "日志里记了「重放」");
            }
        });

        System.out.println();
        if (failures == 0) {
            System.out.println("全部通过 ✔");
        } else {
            System.out.println(failures + " 项失败 �’");
        }
        System.exit(failures == 0 ? 0 : 1);
    }


    static FakeCar.Profile shortSigProfile() {
        FakeCar.Profile p = FakeCar.Profile.chery();
        p.mfiSignatureLen = 5;
        return p;
    }

    static FakeCar.Profile replayProfile() {
        FakeCar.Profile p = FakeCar.Profile.chery();
        p.replayAuthResponse = true;
        return p;
    }

    static boolean logContains(java.util.List<String> logs, String kw) {
        for (String l : logs) if (l != null && l.contains(kw)) return true;
        return false;
    }

    static FakeCar.Profile rejectProfile() {
        FakeCar.Profile p = FakeCar.Profile.chery();
        p.rejectIdentification = true;
        return p;
    }

    static FakeCar.Profile rstProfile() {
        FakeCar.Profile p = FakeCar.Profile.chery();
        p.rstAfterMessage = 0x5702;
        return p;
    }

    static FakeCar.Profile earlyStartProfile() {
        FakeCar.Profile p = FakeCar.Profile.chery();
        p.startSessionBeforeAvailability = true;
        return p;
    }

    /** 车机热点关着（不回 0x5703），但其它照常。 */
    static FakeCar.Profile hotspotOff() {
        FakeCar.Profile p = FakeCar.Profile.chery();
        p.hotspotOn = false;
        return p;
    }

    // ------------------------------------------------------------------ 场景框架

    interface Scenario {
        void run(Iap2SenderSession session, FakeCar car, Result r) throws Exception;
    }

    static void scenario(String name, final FakeCar.Profile profile, final Scenario body) throws Exception {
        System.out.println();
        System.out.println("── " + name);
        final Link link = new Link();
        final FakeCar car = new FakeCar(new FakeCar.Sink() {
            @Override
            public void send(byte[] bytes) {
                link.toPhone.offer(bytes);
            }
        }, profile);

        final AtomicReference<Iap2SenderSession> sessionRef = new AtomicReference<Iap2SenderSession>();
        final List<String> logs = new ArrayList<String>();
        logsRef.set(logs);
        Iap2SenderSession.Listener listener = new Iap2SenderSession.Listener() {
            @Override public void onState(Iap2SenderSession.State s) { }
            @Override public void onLog(String msg) { synchronized (logs) { logs.add(msg); } }
            @Override public void onWifiConfig(Iap2SenderSession.WifiConfig c) { synchronized (logs) { logs.add("WIFI " + c); } }
            @Override public void onCarPlayEndpoint(Iap2SenderSession.Endpoint e) { synchronized (logs) { logs.add("ENDPOINT " + e); } }
        };
        Iap2LinkEngine.Listener linkListener = new Iap2LinkEngine.Listener() {
            @Override public void onState(Iap2LinkEngine.State s) { }
            @Override public void onMessage(Iap2Csm.Message m) { }
            @Override public void onLog(String m) { }
        };
        Iap2SenderSession session = new Iap2SenderSession(listener, new Iap2LinkEngine.Transport() {
            @Override
            public void send(byte[] bytes) {
                link.toCar.offer(bytes);
            }
        }, linkListener);
        session.setDeviceIdentity("iPhone", "AA:BB:CC:DD:EE:01");
        sessionRef.set(session);

        final AtomicBoolean running = new AtomicBoolean(true);
        Thread phone = new Thread(new Runnable() {
            @Override public void run() {
                while (running.get()) {
                    try {
                        byte[] b = link.toPhone.poll(200, TimeUnit.MILLISECONDS);
                        if (b != null) {
                            if (DEBUG) System.out.println("     phone 收到 " + b.length + "B");
                            sessionRef.get().onBytes(b);
                        }
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable t) {
                        System.out.println("     !! phone 线程异常: " + t);
                        t.printStackTrace(System.out);
                        return;
                    }
                }
            }
        }, "phone");
        Thread unit = new Thread(new Runnable() {
            @Override public void run() {
                while (running.get()) {
                    try {
                        byte[] b = link.toCar.poll(200, TimeUnit.MILLISECONDS);
                        if (b != null) {
                            if (DEBUG) System.out.println("     car 收到 " + b.length + "B");
                            car.onBytes(b);
                        }
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable t) {
                        System.out.println("     !! car 线程异常: " + t);
                        t.printStackTrace(System.out);
                        return;
                    }
                }
            }
        }, "car");
        phone.setDaemon(true);
        unit.setDaemon(true);
        phone.start();
        unit.start();

        session.start();

        Result r = new Result();
        try {
            body.run(session, car, r);
        } catch (Throwable t) {
            r.fail.add("场景抛异常：" + t);
        }
        running.set(false);
        try { session.close(); } catch (Throwable ignored) { }

        for (String s : r.pass) System.out.println("  ✓ " + s);
        for (String s : r.fail) System.out.println("  ✗ " + s);
        failures += r.fail.size();
        if (r.fail.size() > 0) {
            System.out.println("  -- 车机侧看到的消息序列：" + ids(car));
            System.out.println("  -- 链路跟踪：\n" + car.trace());
            synchronized (logs) {
                for (String l : logs) System.out.println("     log: " + l);
            }
        }
    }

    /** 等到车机收到某条消息（或超时）。 */
    static boolean waitFor(FakeCar car, int id, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (car.countOf(id) > 0) return true;
            Thread.sleep(20);
        }
        return false;
    }

    static String ids(FakeCar car) {
        StringBuilder sb = new StringBuilder();
        for (int id : car.receivedIds) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("0x").append(Integer.toHexString(id));
        }
        return sb.length() == 0 ? "(空)" : sb.toString();
    }

    /** 从 0x4300 的 body 里取出蓝牙 MAC（group(1) → param(1) 字符串）。 */
    static String macIn4300(FakeCar car) {
        byte[] body = car.receivedBodies.get(0x4300);
        if (body == null) return "(未收到)";
        List<Iap2Csm.Param> params = Iap2Csm.parseParams(body);
        if (params.isEmpty()) return "(空 body)";
        for (Iap2Csm.Param sp : params.get(0).asNested()) {
            if (sp.paramId == 1) return sp.asString();
        }
        return "(没找到 MAC 参数)";
    }

    private Harness() {
    }
}
