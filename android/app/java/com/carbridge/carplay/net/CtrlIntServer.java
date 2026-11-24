package com.carbridge.carplay.net;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import com.carbridge.carplay.util.Diag;

/**
 * 控制通道（ctrl-int）服务端 —— 车机会**反连**我们。
 */
public final class CtrlIntServer {

    /** 真 iPhone 用的控制端口。 */
    public static final int DEFAULT_PORT = 7100;

    public interface Listener {
        void onLog(String line);
    }

    private final Listener listener;

    /**
     * 车机最后一次邀请我们的时刻（{@code GET /ctrl-int/1/connect}）。
     *
     * <p>为什么要记：车机的邀请是**有时效**的（mcarplay 现场观察约 20 秒）。
     * 我们如果因为等端口/等屏亮拖到几十秒后才去开 AirPlay 会话，车机那头
     * 可能已经放弃这一轮，表现就是「会话建起来了但车机不消费/黑屏」，
     * 而日志里看不出是「邀请过期」还是「车机不兼容」。把年龄打出来就能区分。
     */
    private static volatile long lastInviteAtMs;
    private ServerSocket server;
    private volatile boolean running;
    private Thread acceptThread;

    /** 距离车机上次邀请已经过去多久（毫秒）；从没邀请过返回 -1。 */
    public static long lastInviteAgeMs() {
        long t = lastInviteAtMs;
        // 墙钟可能被 NTP/用户改时间往回拨，负数会被界面显示成奇怪的倒计时
        return t == 0 ? -1 : Math.max(0L, System.currentTimeMillis() - t);
    }

    public CtrlIntServer(Listener listener) {
        this.listener = listener;
    }

    /** 启动监听。返回实际绑定的端口（失败返回 -1）。 */
    public int start(int port) {
        if (running) return port;
        try {
            server = new ServerSocket(port, 4, InetAddress.getByName("0.0.0.0"));
        } catch (Throwable t) {
            listener.onLog("ctrl-int 服务启动失败（端口 " + port + "）：" + t);
            return -1;
        }
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "carbridge-ctrl-int");
        acceptThread.setDaemon(true);
        acceptThread.start();
        listener.onLog("ctrl-int 服务已监听 " + server.getLocalPort()
                + "（车机会反连 GET /ctrl-int/1/connect）");
        return server.getLocalPort();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Throwable tDiag) {
            Diag.w("CtrlIntServer.stop", tDiag);
        }
        server = null;
    }

    private void acceptLoop() {
        while (running) {
            final Socket sock;
            try {
                sock = server.accept();
            } catch (Throwable t) {
                if (running) listener.onLog("ctrl-int accept 失败: " + t);
                continue;
            }
            // 每条连接一条线程。
            // 车机的探测是「每会话 7 次 × 1.5s」，而 handle() 会读到 SO_TIMEOUT(3s) 才返回：
            // 串行处理时，一条卡住的探测就把后面的探测全堵在 accept 队列里，
            // 表现是「车机发过邀请，但我们这边像没收到」。
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        handle(sock);
                    } finally {
                        try {
                            sock.close();
                        } catch (Throwable tDiag) {
                            Diag.w("CtrlIntServer.run", tDiag);
                        }
                    }
                }
            }, "ctrl-int-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(3000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String request = in.readLine();
            String line;
            while ((line = in.readLine()) != null && line.length() > 0) {
                // 读完请求头
            }
            if (request == null) return;
            listener.onLog("ctrl-int 收到: " + request);
            if (request != null && request.contains("/ctrl-int/1/connect")) {
                lastInviteAtMs = System.currentTimeMillis();
            }
            OutputStream out = sock.getOutputStream();
            // 真 iPhone 回的是「裸 200」——只写状态行 + 空行，不写任何头
            //（连 Content-Length 都不写；orangecar 参考：GET /ctrl-int/1/connect → bare 200 OK）。
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable t) {
            listener.onLog("ctrl-int 处理失败: " + t);
        }
    }
}
