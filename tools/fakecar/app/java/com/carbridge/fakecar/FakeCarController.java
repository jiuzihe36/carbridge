package com.carbridge.fakecar;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

/**
 * 便携假车机 <b>总控</b>：AirPlay 接收端 + 蓝牙连手机 + iAP2 配件会话 串成一条流水线。
 *
 * <p>流程：启动 AirPlayServer（拿到端口）→ 蓝牙 SDP 查手机（认 2d8d2466）→ 连 CADE 通道
 * → 起 iAP2 会话 → 会话自动走 0x5703/0x4300/0x4301 → 手机连 AirPlay → 屏幕/音频流解密。
 */
public final class FakeCarController {

    public interface Log {
        void onLog(String line);
    }

    public interface Phase {
        void onPhase(String name);
    }

    private final Context context;
    private final Log log;
    private final Phase phase;

    private final Iap2AccessorySession.Config sessionCfg = new Iap2AccessorySession.Config();
    private AirPlayServer airPlay;
    private Iap2AccessorySession session;
    private BluetoothSocket btSocket;
    private volatile boolean started;
    private volatile BluetoothDevice target;
    private volatile AirPlayServer.VideoFrame videoFrame;
    private android.os.PowerManager.WakeLock wakeLock;
    private volatile boolean reconnecting;

    public FakeCarController(Context context, Log log, Phase phase) {
        this.context = context.getApplicationContext();
        this.log = log;
        this.phase = phase;
        // 默认配置
        sessionCfg.apPort = 7010;
        sessionCfg.carIp = detectLanIp();
        sessionCfg.deviceId = detectBtMac();
        sessionCfg.phoneIp = "";
    }

    public void setVideoFrame(AirPlayServer.VideoFrame videoFrame) {
        this.videoFrame = videoFrame;
    }

    /**
     * 读外部配置：{@code <外部私有目录>/carprofile.json}。
     * 里面写什么，假车机就按什么自报 —— 把真车抓到的 model/serial/want-list 填进去就是 1:1。
     */
    public void loadCarProfile(java.io.File dir) {
        java.io.File f = new java.io.File(dir, "carprofile.json");
        if (!f.isFile()) return;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            org.json.JSONObject o = new org.json.JSONObject(new String(buf, 0, n, "UTF-8"));
            sessionCfg.applyJson(o);
            log.onLog("已加载 carprofile.json：" + f.getAbsolutePath());
        } catch (Throwable t) {
            log.onLog("carprofile.json 解析失败：" + t);
        }
    }

    public Iap2AccessorySession.Config config() {
        return sessionCfg;
    }

    public BluetoothDevice target() {
        return target;
    }

    public void setTarget(BluetoothDevice device) {
        this.target = device;
    }

    public AirPlayServer airPlay() {
        return airPlay;
    }

    public boolean isStarted() {
        return started;
    }

    /** 找已配对设备里「支持 CarPlay」的那台（SDP 有 2d8d2466）。 */
    public BluetoothDevice findCarPlayDevice() {
        BtCarConnector conn = new BtCarConnector(context);
        List<BluetoothDevice> bonded = conn.bondedDevices();
        emit("已配对设备 " + bonded.size() + " 台：");
        for (BluetoothDevice d : bonded) emit("  · " + conn.describe(d));
        for (BluetoothDevice d : bonded) {
            emit("查 " + conn.describe(d) + " 的 SDP…");
            if (conn.isCarPlayDevice(d, 5000, new BtCarConnector.Log() {
                @Override
                public void onLog(String line) {
                    emit(line);
                }
            })) {
                emit("✓ 认出 CarPlay 设备：" + conn.describe(d) + "（2d8d2466 命中）");
                emit("  UUID 明细：" + conn.describeUuids(d, 1000, null));
                return d;
            } else {
                emit("  ✗ 未在 SDP 里看到 2d8d2466 → 车机 Settings 认不出这台 iPhone");
                emit("  UUID 明细：" + conn.describeUuids(d, 1000, null));
            }
        }
        emit("✗ 没有已配对设备广播 2d8d2466（先和目标手机配对，且目标手机 carbridge 要开着）");
        return null;
    }

    /** 启动整条链路。 */
    public synchronized void start(final BluetoothDevice device) {
        if (started) return;
        started = true;
        this.target = device;

        // 0) 锁屏保活：前台服务 + partial wake lock，让 CPU 不睡、进程不被冻结，
        //    蓝牙 RFCOMM 读循环不断（否则锁屏后对端判定超时 → CarPlay 链路断）
        KeepAliveService.start(context);
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    context.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "fakecar:carplay");
            wakeLock.acquire();
            emit("✓ 已启动前台保活（锁屏后 CarPlay 链路不中断）");
        } catch (Throwable t) {
            emit("唤醒锁获取失败: " + t);
        }

        // 1) 刷新本机 IP（0x4301 端点用）
        sessionCfg.carIp = detectLanIp();
        emit("本机(LAN) IP: " + sessionCfg.carIp + "，AirPlay 端口: " + sessionCfg.apPort);

        // 1) 先起 AirPlay 接收端
        try {
            airPlay = new AirPlayServer(new AirPlayServer.Log() {
                @Override
                public void onLog(String line) {
                    emit(line);
                }
            }, new AirPlayServer.Phase() {
                @Override
                public void onPhase(String name) {
                    emitPhase(name);
                }
            }, videoFrame, sessionCfg.apPort);
            // /info 的身份也跟车机档案走（1:1：deviceid=真车蓝牙MAC、model/maker/srcvers）
            airPlay.infoDeviceId = sessionCfg.deviceId;
            airPlay.infoModel    = sessionCfg.model;
            airPlay.infoMaker    = sessionCfg.maker;
            airPlay.infoSrcvers  = sessionCfg.fw;
            airPlay.start();
            emit("✓ AirPlay 接收端就绪（控制:" + sessionCfg.apPort
                    + " 视频:" + airPlay.videoPort() + " 音频UDP:" + airPlay.audioPort()
                    + " 事件:" + airPlay.eventPort() + "）");
        } catch (Exception e) {
            emit("✗ AirPlay 启动失败: " + e);
            started = false;
            return;
        }

        // 2) 蓝牙连手机
        final BtCarConnector conn = new BtCarConnector(context);
        emit("—— 识别门：查 " + (device.getName() == null ? device.getAddress() : device.getName())
                + " 的 SDP（认 2d8d2466）——");
        boolean recognized = conn.isCarPlayDevice(device, 8000, new BtCarConnector.Log() {
            @Override
            public void onLog(String line) {
                emit(line);
            }
        });
        emit(recognized ? "✓ 认出 CarPlay 设备（2d8d2466 命中）"
                : "✗ 未在 SDP 里看到 2d8d2466（可能 SDP 未同步，仍继续尝试连接）");
        emit("  UUID 明细：" + conn.describeUuids(device, 1000, null));

        // 3) 连 CADE
        emit("—— 连接 iAP2 ——");
        try {
            btSocket = conn.connectCade(device, new BtCarConnector.Log() {
                @Override
                public void onLog(String line) {
                    emit(line);
                }
            });
        } catch (Throwable e) {
            emit("✗ 蓝牙连接异常: " + e);
            btSocket = null;
        }
        if (btSocket == null) {
            emit("✗ 无法连接手机 RFCOMM，链路终止");
            started = false;
            return;
        }

        // 4) 起 iAP2 配件会话
        try {
            InputStream in = btSocket.getInputStream();
            OutputStream out = btSocket.getOutputStream();
            session = new Iap2AccessorySession(in, out, sessionCfg,
                    new Iap2AccessorySession.Log() {
                        @Override
                        public void onLog(String line) {
                            emit(line);
                        }
                    },
                    new Iap2AccessorySession.Phase() {
                        @Override
                        public void onPhase(String name) {
                            emitPhase(name);
                        }
                    });
            session.setOnClosed(new Runnable() {
                @Override
                public void run() {
                    onIap2Closed();
                }
            });
            session.start();
        } catch (Exception e) {
            emit("✗ iAP2 会话启动失败: " + e);
            started = false;
        }
    }

    public synchronized void stop() {
        started = false;
        if (session != null) session.stop();
        if (btSocket != null) {
            try {
                btSocket.close();
            } catch (Exception ignored) {
            }
            btSocket = null;
        }
        if (airPlay != null) airPlay.stop();
        airPlay = null;
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Throwable ignored) {
            }
            wakeLock = null;
        }
        KeepAliveService.stop(context);
        emit("已停止");
    }

    /**
     * iAP2 会话被对端断开（非本端 stop）→ 自动重连。
     *
     * <p>AirPlay 接收端保持运行（端口不变），重连后手机收到新的 0x4301 会重新连
     * AirPlay（重新 pair-verify + SETUP + 推流），整条链路自动恢复。
     */
    private void onIap2Closed() {
        if (!started) return;
        if (reconnecting) return;
        reconnecting = true;
        emit("⚠ iAP2 会话断开，自动重连…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(1000);   // 稍等，别撞上对端还没释放的旧连接
                    if (!started) return;
                    BluetoothDevice dev = target;
                    if (dev == null) return;
                    // 关旧 socket
                    BluetoothSocket old = btSocket;
                    btSocket = null;
                    if (old != null) {
                        try {
                            old.close();
                        } catch (Throwable ignored) {
                        }
                    }
                    // 重连 CADE
                    BtCarConnector conn = new BtCarConnector(context);
                    BluetoothSocket s = conn.connectCade(dev, new BtCarConnector.Log() {
                        @Override
                        public void onLog(String line) {
                            emit(line);
                        }
                    });
                    if (s == null) {
                        emit("✗ iAP2 重连失败（下次断开会再试）");
                        return;
                    }
                    btSocket = s;
                    final BluetoothSocket fs = s;
                    session = new Iap2AccessorySession(fs.getInputStream(), fs.getOutputStream(),
                            sessionCfg,
                            new Iap2AccessorySession.Log() {
                                @Override
                                public void onLog(String line) {
                                    emit(line);
                                }
                            },
                            new Iap2AccessorySession.Phase() {
                                @Override
                                public void onPhase(String name) {
                                    emitPhase(name);
                                }
                            });
                    session.setOnClosed(new Runnable() {
                        @Override
                        public void run() {
                            onIap2Closed();
                        }
                    });
                    session.start();
                    emit("✓ iAP2 重连成功");
                } catch (Throwable t) {
                    emit("✗ iAP2 重连异常: " + t);
                } finally {
                    reconnecting = false;
                }
            }
        }, "fakecar-reconnect").start();
    }

    private void emit(String line) {
        if (log != null) log.onLog(line);
    }

    private void emitPhase(String name) {
        if (phase != null) phase.onPhase(name);
    }

    /** 触摸回传（0..1 归一化坐标，逻辑上限 32767）。 */
    public void sendTouch(float normX, float normY, boolean down) {
        AirPlayServer ap = airPlay;
        if (ap != null) ap.sendTouch(normX, normY, down);
    }

    /** 硬键回传（0=释放,1=播放,2=暂停,3=下一曲,4=上一曲）。 */
    public void sendHardKey(int index) {
        AirPlayServer ap = airPlay;
        if (ap != null) ap.sendHardKey(index);
    }

    // ================================================================== 网络工具

    public static String detectLanIp() {
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    public static String detectBtMac() {
        try {
            android.bluetooth.BluetoothAdapter a = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (a != null && a.getAddress() != null) return a.getAddress();
        } catch (Throwable ignored) {
        }
        return "00:00:00:00:00:00";
    }

    // UUID 常量（供 UI 显示）
    public static final UUID MARKER_UUID = BtCarConnector.MARKER_UUID;
    public static final UUID CADE_UUID = BtCarConnector.CADE_UUID;
}
