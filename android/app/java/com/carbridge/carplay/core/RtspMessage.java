package com.carbridge.carplay.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AirPlay/CarPlay 用的 RTSP 报文编解码。
 */
public final class RtspMessage {

    /** 与 SETUP 里的 sourceVersion 保持一致（同一个发送端不能自报两个版本）。 */
    public static String userAgent() {
        return AirPlaySetup.USER_AGENT;
    }

    public static final String CONTENT_TYPE_BPLIST = "application/x-apple-binary-plist";

    /**
     * 单行头部上限（64KB）。
     *
     * <p>控制通道是从车机连过来的普通 TCP；对端一旦不发换行（固件 bug、半包卡死、
     * 或根本不是 RTSP），{@code readLine} 的缓冲会只增不减，几百 KB 垃圾就能把进程顶到 OOM。
     * 真实的 RTSP 头行最长也就几百字节，64KB 已经很宽松。
     */
    public static final int MAX_LINE = 64 * 1024;

    /** body 上限（8MB）。/info 的 plist 只有几 KB，超过这个量级一定是长度字段坏了。 */
    public static final int MAX_BODY = 8 * 1024 * 1024;

    private RtspMessage() {
    }

    /** 构造请求；body 为 null 时不带 Content-* 头。 */
    public static byte[] request(String method, String path, int cseq, byte[] body) {
        return request(method, path, cseq, body, CONTENT_TYPE_BPLIST, null);
    }

    /**
     * 构造带自定义头的请求。
     *
     * @param contentType Content-Type；null 表示不带
     * @param extraHeaders 附加头（如 {@code X-Apple-HKP: 2}），每项形如 "名: 值"
     */
    public static byte[] request(String method, String path, int cseq, byte[] body,
                                 String contentType, String[] extraHeaders) {
        StringBuilder h = new StringBuilder();
        h.append(method).append(' ').append(path).append(" RTSP/1.0\r\n");
        h.append("CSeq: ").append(cseq).append("\r\n");
        h.append("User-Agent: ").append(userAgent()).append("\r\n");
        // 真 iPhone 每条 RTSP 都带这个头；少数车机用它区分「iPhone 客户端」和第三方实现
        h.append("X-Apple-Client-Name: iPhone\r\n");
        if (extraHeaders != null) {
            for (String e : extraHeaders) {
                if (e != null && !e.isEmpty()) h.append(e).append("\r\n");
            }
        }
        if (body != null) {
            if (contentType != null) {
                h.append("Content-Type: ").append(contentType).append("\r\n");
            }
            h.append("Content-Length: ").append(body.length).append("\r\n");
        }
        h.append("\r\n");
        byte[] head = h.toString().getBytes(StandardCharsets.US_ASCII);
        if (body == null) return head;
        byte[] out = new byte[head.length + body.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(body, 0, out, head.length, body.length);
        return out;
    }

    /** 构造对我们的请求的 200 回复。 */
    public static byte[] ok(int cseq) {
        return ("RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    /** 解析后的响应。 */
    public static final class Response {
        public final int status;
        public final int cseq;
        public final Map<String, String> headers = new LinkedHashMap<String, String>();
        public byte[] body = new byte[0];
        public String statusLine = "";

        Response(int status, int cseq) {
            this.status = status;
            this.cseq = cseq;
        }

        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        public String header(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
            }
            return null;
        }

        /** 若 body 是 bplist 则解析，否则返回 null。 */
        public Object bodyAsPlist() {
            if (body == null || body.length < 8) return null;
            try {
                return Bplist.read(body);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** 阻塞读一条响应。 */
    public static Response readResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null) return null;
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) throw new IOException("非法状态行: " + statusLine);
        int status;
        try {
            status = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            // 不能让它以 NumberFormatException 逃出去：调用点只 catch IOException，
            // 会把「对端发了垃圾」当成未捕获异常。
            throw new IOException("非法状态码: " + statusLine);
        }
        int cseq = -1;
        Map<String, String> headers = new LinkedHashMap<String, String>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                String k = line.substring(0, idx).trim();
                String v = line.substring(idx + 1).trim();
                headers.put(k, v);
                if (k.equalsIgnoreCase("CSeq")) {
                    try {
                        cseq = Integer.parseInt(v);
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
        }
        Response r = new Response(status, cseq);
        r.statusLine = statusLine;
        r.headers.putAll(headers);
        String cl = r.header("Content-Length");
        if (cl != null) {
            int len;
            try {
                len = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                throw new IOException("非法 Content-Length: " + cl);
            }
            if (len < 0 || len > MAX_BODY) {
                throw new IOException("Content-Length 越界: " + len + "（上限 " + MAX_BODY + "）");
            }
            r.body = readN(in, len);
        }
        return r;
    }

    /** 读一行（去掉 CRLF）；超过 {@link #MAX_LINE} 直接报错，不让缓冲无限增长。 */
    public static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(64);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                byte[] arr = b.toByteArray();
                int n = arr.length;
                if (n > 0 && arr[n - 1] == '\r') n--;
                return new String(arr, 0, n, StandardCharsets.US_ASCII);
            }
            b.write(c);
            if (b.size() > MAX_LINE) {
                throw new IOException("RTSP 头行超过 " + MAX_LINE + " 字节（对端未按行结束或数据损坏）");
            }
        }
        return b.size() == 0 ? null : new String(b.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static byte[] readN(InputStream in, int len) throws IOException {
        byte[] out = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(out, off, len - off);
            if (n < 0) throw new IOException("body 截断 " + off + "/" + len);
            off += n;
        }
        return out;
    }
}
