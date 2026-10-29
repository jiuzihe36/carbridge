package com.carbridge.carplay.net;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

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
    private ServerSocket server;
    private volatile boolean running;
    private Thread acceptThread;

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
        } catch (Throwable ignored) {
        }
        server = null;
    }

    private void acceptLoop() {
        while (running) {
            Socket sock = null;
            try {
                sock = server.accept();
                handle(sock);
            } catch (Throwable t) {
                if (running) listener.onLog("ctrl-int accept 失败: " + t);
            } finally {
                if (sock != null) {
                    try {
                        sock.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
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
