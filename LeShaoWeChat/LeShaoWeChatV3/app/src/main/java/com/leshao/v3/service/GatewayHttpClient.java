package com.leshao.v3.service;

import com.leshao.v3.LogWriter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地网关 HTTP 客户端（Socket 直连，绕过 Android 明文 HTTP 限制）。
 * <p>用于访问 {@code http://127.0.0.1:8787/v1} 这类本地明文网关：
 * Android 9+ 默认禁止 cleartext 流量，HttpURLConnection 会被
 * {@code CLEARTEXT communication not permitted} 拦截，Socket 直连不受影响。</p>
 */
public final class GatewayHttpClient {

    public static final class Response {
        public int code;
        public byte[] body;

        public String bodyString() {
            return body == null ? "" : new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static final String TAG = "GatewayHttp";
    private static final Pattern STATUS_PATTERN = Pattern.compile("HTTP/1\\.[01] (\\d{3})");

    private GatewayHttpClient() {
    }

    public static Response get(String urlStr, String bearerKey) {
        return request("GET", urlStr, bearerKey, null);
    }

    public static Response postJson(String urlStr, String bearerKey, String jsonBody) {
        return request("POST", urlStr, bearerKey, jsonBody);
    }

    public static Response request(String method, String urlStr, String bearerKey, String jsonBody) {
        Response resp = new Response();
        Socket socket = null;
        try {
            URL url = new URL(urlStr);
            String host = url.getHost();
            int port = url.getPort();
            if (port <= 0) {
                port = url.getDefaultPort() > 0 ? url.getDefaultPort() : 80;
            }
            String path = url.getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            if (url.getQuery() != null) {
                path += "?" + url.getQuery();
            }

            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 15000);
            socket.setSoTimeout(30000);

            byte[] bodyBytes = (jsonBody != null) ? jsonBody.getBytes("UTF-8") : null;
            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append(':').append(port).append("\r\n");
            req.append("Accept: */*\r\n");
            if (bearerKey != null && !bearerKey.isEmpty()) {
                req.append("Authorization: Bearer ").append(bearerKey).append("\r\n");
            }
            if (bodyBytes != null) {
                req.append("Content-Type: application/json\r\n");
                req.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            }
            req.append("Connection: close\r\n\r\n");

            OutputStream os = socket.getOutputStream();
            os.write(req.toString().getBytes("UTF-8"));
            if (bodyBytes != null) {
                os.write(bodyBytes);
            }
            os.flush();

            InputStream is = socket.getInputStream();
            ByteArrayOutputStream full = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                full.write(buf, 0, n);
            }
            byte[] raw = full.toByteArray();
            if (raw.length == 0) {
                LogWriter.log(TAG, "empty response: " + method + " " + urlStr);
                return resp;
            }

            String head = new String(raw, "ISO-8859-1");
            int headerEnd = head.indexOf("\r\n\r\n");
            if (headerEnd < 0) {
                LogWriter.log(TAG, "no header separator: " + method + " " + urlStr);
                return resp;
            }
            Matcher m = STATUS_PATTERN.matcher(head.substring(0, headerEnd));
            if (m.find()) {
                resp.code = Integer.parseInt(m.group(1));
            }
            resp.body = Arrays.copyOfRange(raw, headerEnd + 4, raw.length);
            return resp;
        } catch (Throwable t) {
            LogWriter.log(TAG, "request err " + method + " " + urlStr + ": "
                    + t.getClass().getSimpleName() + " " + t.getMessage());
            return resp;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}