package com.leshao.v3.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 WebSocket 客户端（RFC6455，无第三方依赖）。
 * <p>仅支持单连接文本/二进制消息、ping/pong、close；客户端帧带掩码。
 * 用于豆包 voicegenie 免登录 TTS（wss://audio.doubao.com）。</p>
 */
public final class WebSocketClient {

    public interface Listener {
        void onMessage(byte[] payload, boolean binary);

        void onClose(int code, String reason);

        void onError(Throwable t);
    }

    private final SSLSocket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Listener listener;
    private volatile boolean closed;

    public WebSocketClient(String host, int port, String path,
                           String origin, String userAgent, Listener listener) throws IOException {
        this(host, port, path, origin, userAgent, null, listener);
    }

    public WebSocketClient(String host, int port, String path,
                           String origin, String userAgent,
                           java.util.Map<String, String> extraHeaders,
                           Listener listener) throws IOException {
        this.listener = listener;
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket s = (SSLSocket) factory.createSocket();
        // 对齐 Python 网关 open_timeout=15：连接 + TLS 握手 + HTTP 101 均设 15s 超时，
        // 避免服务端不响应时线程长期阻塞导致试听卡死
        s.connect(new java.net.InetSocketAddress(host, port), 15000);
        s.setSoTimeout(15000);
        s.startHandshake();
        this.socket = s;
        this.in = s.getInputStream();
        this.out = s.getOutputStream();

        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String secKey = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP);

        StringBuilder req = new StringBuilder();
        req.append("GET ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host).append(':').append(port).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(secKey).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        if (origin != null) {
            req.append("Origin: ").append(origin).append("\r\n");
        }
        if (userAgent != null) {
            req.append("User-Agent: ").append(userAgent).append("\r\n");
        }
        req.append("Sec-Fetch-Dest: empty\r\n");
        req.append("Sec-Fetch-Mode: websocket\r\n");
        req.append("Sec-Fetch-Site: same-site\r\n");
        if (extraHeaders != null) {
            for (java.util.Map.Entry<String, String> e : extraHeaders.entrySet()) {
                req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        req.append("\r\n");
        out.write(req.toString().getBytes("UTF-8"));
        out.flush();

        String header = readHttpHeader(in);
        if (!header.contains(" 101 ") && !header.startsWith("HTTP/1.1 101")) {
            throw new IOException("websocket handshake failed: " + header);
        }
        // 握手完成，恢复阻塞读取（readLoop 依赖长连接）
        s.setSoTimeout(0);

        Thread reader = new Thread(this::readLoop, "leshao-ws-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private static String readHttpHeader(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int prev = -1;
        int prev2 = -1;
        int prev3 = -1;
        int b;
        while ((b = in.read()) >= 0) {
            bos.write(b);
            if (prev3 == '\r' && prev2 == '\n' && prev == '\r' && b == '\n') {
                break;
            }
            prev3 = prev2;
            prev2 = prev;
            prev = b;
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    public void sendText(String text) throws IOException {
        sendFrame(0x1, text.getBytes("UTF-8"));
    }

    public void sendBinary(byte[] data) throws IOException {
        sendFrame(0x2, data);
    }

    private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
        if (closed) {
            throw new IOException("websocket closed");
        }
        int len = payload.length;
        out.write(0x80 | opcode); // FIN + opcode
        byte[] maskKey = new byte[4];
        new SecureRandom().nextBytes(maskKey);
        if (len < 126) {
            out.write(0x80 | len);
        } else if (len < 65536) {
            out.write(0x80 | 126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x80 | 127);
            long l = len;
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((l >> (i * 8)) & 0xFF));
            }
        }
        out.write(maskKey);
        for (int i = 0; i < len; i++) {
            out.write(payload[i] ^ maskKey[i % 4]);
        }
        out.flush();
    }

    private void readLoop() {
        // 分片重组：服务端大帧可能以 opcode=0x2 + 延续帧(0x0) 分片下发，
        // 若不重组会丢失音频数据，表现为「连接成功但收不到音频」
        java.io.ByteArrayOutputStream frag = null;
        int fragOpcode = -1;
        try {
            while (!closed) {
                int b0 = in.read();
                if (b0 < 0) {
                    break;
                }
                int b1 = in.read();
                if (b1 < 0) {
                    break;
                }
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = read16();
                } else if (len == 127) {
                    len = read64();
                }
                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    readFully(mask);
                }
                byte[] payload = new byte[(int) len];
                readFully(payload);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i % 4];
                    }
                }
                if (opcode == 0x8) { // close
                    closed = true;
                    int code = payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 1000;
                    String reason = payload.length > 2 ? new String(payload, 2, payload.length - 2, "UTF-8") : "";
                    listener.onClose(code, reason);
                    break;
                } else if (opcode == 0x9) { // ping -> pong
                    try {
                        sendFrame(0xA, payload);
                    } catch (Throwable ignored) {
                    }
                } else if (opcode == 0x1 || opcode == 0x2) {
                    if (fin) {
                        // 单帧完整消息
                        listener.onMessage(payload, opcode == 0x2);
                    } else {
                        // 分片开始
                        frag = new java.io.ByteArrayOutputStream();
                        fragOpcode = opcode;
                        frag.write(payload, 0, payload.length);
                    }
                } else if (opcode == 0x0) { // continuation
                    if (frag != null) {
                        frag.write(payload, 0, payload.length);
                        if (fin) {
                            listener.onMessage(frag.toByteArray(), fragOpcode == 0x2);
                            frag = null;
                            fragOpcode = -1;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            if (!closed) {
                listener.onError(t);
            }
        } finally {
            close();
        }
    }

    private int read16() throws IOException {
        return ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
    }

    private long read64() throws IOException {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (in.read() & 0xFF);
        }
        return v;
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IOException("eof");
            }
            off += n;
        }
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            socket.close();
        } catch (Throwable ignored) {
        }
    }
}