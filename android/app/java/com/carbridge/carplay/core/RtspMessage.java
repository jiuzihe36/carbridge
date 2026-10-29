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

    public static final String USER_AGENT = "AirPlay/566.25.21";
    public static final String CONTENT_TYPE_BPLIST = "application/x-apple-binary-plist";

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
        h.append("User-Agent: ").append(USER_AGENT).append("\r\n");
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
        int status = Integer.parseInt(parts[1].trim());
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
            int len = Integer.parseInt(cl.trim());
            r.body = readN(in, len);
        }
        return r;
    }

    /** 读一行（去掉 CRLF）。 */
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
