package com.leshao.ai.api.anthropic;

import com.leshao.ai.api.model.ChatMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Anthropic Claude 客户端。
 * <p>
 * 使用标准库 {@link HttpURLConnection} 调用 Anthropic Messages API：
 * <ul>
 *   <li>端点：{@code /v1/messages}</li>
 *   <li>鉴权：header {@code x-api-key}</li>
 *   <li>版本：header {@code anthropic-version: 2023-06-01}</li>
 * </ul>
 * 消息格式：role 为 user/assistant，content 为含 text 的对象数组；system 作为独立参数。
 */
public class AnthropicClient {

    /** 默认 API 基础地址 */
    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    /** Anthropic API 版本号 */
    public static final String API_VERSION = "2023-06-01";

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private Double temperature;
    private Integer maxTokens;
    private int connectTimeout = 30_000;
    private int readTimeout = 120_000;

    /**
     * 使用默认地址构造客户端。
     *
     * @param apiKey Anthropic 密钥（x-api-key）
     * @param model  模型名，例如 claude-3-5-sonnet-20241022
     */
    public AnthropicClient(String apiKey, String model) {
        this(DEFAULT_BASE_URL, apiKey, model);
    }

    /**
     * 使用自定义地址构造客户端。
     *
     * @param baseUrl API 基础地址
     * @param apiKey  Anthropic 密钥
     * @param model   模型名
     */
    public AnthropicClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    /**
     * 同步发起对话请求并返回纯文本回答。
     *
     * @param messages 历史消息（role 需为 user / assistant）
     * @param system   system 内容，作为一个独立顶层参数传入；为 null/空时不传
     * @return 模型返回的文本（content[0].text）
     * @throws Exception 网络错误、HTTP 错误或解析失败时抛出（中文说明）
     */
    public String chat(List<ChatMessage> messages, String system) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        } else {
            // Anthropic API 要求必传 max_tokens，给定一个合理默认值
            body.put("max_tokens", 1024);
        }
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (system != null && !system.isEmpty()) {
            body.put("system", system);
        }

        JSONArray arr = new JSONArray();
        if (messages != null) {
            for (ChatMessage m : messages) {
                JSONObject msg = new JSONObject();
                msg.put("role", m.getRole());
                JSONArray contentArr = new JSONArray();
                JSONObject contentItem = new JSONObject();
                contentItem.put("type", "text");
                contentItem.put("text", m.getContent() == null ? "" : m.getContent());
                contentArr.put(contentItem);
                msg.put("content", contentArr);
                arr.put(msg);
            }
        }
        body.put("messages", arr);

        String raw = postJson(baseUrl + "/v1/messages", body);
        JSONObject json = new JSONObject(raw);

        // 解析 content[0].text
        JSONArray content = json.optJSONArray("content");
        if (content != null && content.length() > 0) {
            JSONObject first = content.optJSONObject(0);
            if (first != null) {
                String text = first.optString("text", "");
                return text;
            }
        }
        return "";
    }

    /**
     * 向指定 URL 发送 JSON POST 请求并读取响应体。
     */
    private String postJson(String urlStr, JSONObject json) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setDoOutput(true);
            conn.setUseCaches(false);

            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", API_VERSION);

            byte[] bodyBytes = json.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return readStream(conn.getInputStream());
            } else {
                String errBody = readStream(conn.getErrorStream());
                throw new Exception("Anthropic 请求失败，HTTP 状态码：" + code + "，响应：" + errBody);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String readStream(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    private String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
