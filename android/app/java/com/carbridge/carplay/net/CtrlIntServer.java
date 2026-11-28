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

    /** 启动监听。返回实际绑定的端口（失败返回 -1）。
     *
     *  <p>端口回退（OpenCarLink/DiPlay 做法）：默认端口被占时试 7101..7110，
     *  再不行用临时端口。**绝不**在没绑上的情况下回报默认端口 —— 车机反连
     *  必失败，而日志只会写「服务起不来」。
     */
    public int start(int port) {
        // ⚠️ 重入必须返回**实际绑定**的端口，不能回入参：改动态端口时两者不同，
        //    mDNS 会把错端口广播给车机，反连必失败。
        if (running) return server != null ? server.getLocalPort() : port;
        int[] candidates = new int[12];
        candidates[0] = port;
        for (int i = 1; i <= 10; i++) candidates[i] = port + i;
        candidates[11] = 0;   // 0 = 系统临时端口
        Throwable lastErr = null;
        for (int p : candidates) {
            try {
                server = new ServerSocket(p, 4, InetAddress.getByName("0.0.0.0"));
                if (p != port) {
                    listener.onLog("ctrl-int 默认端口 " + port + " 不可用，回退到 "
                            + server.getLocalPort());
                }
                break;
            } catch (Throwable t) {
                lastErr = t;
            }
        }
        if (server == null) {
            listener.onLog("ctrl-int 服务启动失败（" + port + ".." + (port + 10)
                    + " 及临时端口全不可用）：" + lastErr);
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
        int acceptFails = 0;
        while (running) {
            final Socket sock;
            try {
                sock = server.accept();
                acceptFails = 0;
            } catch (Throwable t) {
                if (!running) break;
                // ⚠️ 持久性失败（如 EMFILE fd 耗尽）不能无条件 continue：
                //    立即重试 = 100% CPU + 每圈一条日志刷屏。失败计数退避。
                acceptFails++;
                listener.onLog("ctrl-int accept 失败（第 " + acceptFails + " 次）: " + t);
                try {
                    Thread.sleep(Math.min(200L * acceptFails, 3000L));
                } catch (InterruptedException ie) {
                    return;
                }
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
            String request = readLineLimited(in);
            String line;
            while ((line = readLineLimited(in)) != null && line.length() > 0) {
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

    /** 单行上限：真实的 ctrl-int 请求行不到 200 字节，超过它一定是垃圾/未按行结束。 */
    private static final int MAX_LINE = 8 * 1024;

    /**
     * 读一行，超过 {@link #MAX_LINE} 直接抛异常。
     *
     * <p>{@link BufferedReader#readLine()} 没有长度上限：对端只发字节不发换行时，
     * 缓冲会一直涨到 OOM。这条通道是车机连进来的普通 TCP，必须自己设闸。
     */
    private static String readLineLimited(BufferedReader in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder(64);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > MAX_LINE) {
                throw new java.io.IOException("请求行超过 " + MAX_LINE + " 字节（对端未按行结束）");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
