package com.leshao.ai.api.openai;

import com.leshao.ai.api.model.ChatCompletionRequest;
import com.leshao.ai.api.model.ChatCompletionResponse;
import com.leshao.ai.api.model.ChatMessage;
import com.leshao.ai.api.model.ProviderType;

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
 * OpenAI compatible 客户端。
 * <p>
 * 支持三种端点协议：
 * <ul>
 *   <li>{@code /v1/chat/completions} — OpenAI Chat 格式（默认/可回退）</li>
 *   <li>{@code /v1/responses}        — OpenAI Responses 格式</li>
 * </ul>
 * 内部仅使用标准库 {@link HttpURLConnection} 与 Android 自带 {@link org.json.JSONObject}，
 * 不依赖 OkHttp/Retrofit，保证可在 LSPosed 宿主进程内直接运行。
 */
public class OpenAIClient {

    /** 协议类型（决定使用哪个端点与响应解析方式） */
    private final ProviderType type;
    /** API 基础地址，例如 https://api.openai.com 或自定义网关地址 */
    private final String baseUrl;
    /** API 密钥（Authorization: Bearer xxx） */
    private final String apiKey;
    /** 模型名，例如 gpt-4o、gpt-4o-mini */
    private final String model;
    /** 采样温度，可选 */
    private Double temperature;
    /** 最大输出 token 数，可选 */
    private Integer maxTokens;
    /** 网络连接超时（毫秒） */
    private int connectTimeout = 30_000;
    /** 读取超时（毫秒） */
    private int readTimeout = 120_000;

    /**
     * 构造函数。
     *
     * @param type    协议类型（OPENAI_CHAT 或 OPENAI_RESPONSES）
     * @param baseUrl API 基础地址
     * @param apiKey  API 密钥
     * @param model   模型名
     */
    public OpenAIClient(ProviderType type, String baseUrl, String apiKey, String model) {
        // 若 type 为空或不是 OpenAI 相关，则回退到 OPENAI_CHAT，保证可用
        ProviderType resolved = (type == null) ? ProviderType.OPENAI_CHAT : type;
        if (resolved != ProviderType.OPENAI_CHAT && resolved != ProviderType.OPENAI_RESPONSES) {
            resolved = ProviderType.OPENAI_CHAT;
        }
        this.type = resolved;
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
     * @param messages 历史消息（可选）；若包含 system，可自动作为首条注入
     * @param system   system 指令，为 null 或空时不注入
     * @return 模型返回的文本
     * @throws Exception 网络错误、认证失败或解析失败时抛出
     */
    public String chat(List<ChatMessage> messages, String system) throws Exception {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel(model);
        request.setMessages(messages);
        request.setSystem(system);
        request.setTemperature(temperature);
        request.setMaxTokens(maxTokens);

        String endpoint = "/v1/chat/completions";
        boolean responsesMode = (type == ProviderType.OPENAI_RESPONSES);
        if (responsesMode) {
            endpoint = "/v1/responses";
        }

        JSONObject body = request.toJson();
        // Responses 格式需要把 messages 映射为 input 字段
        if (responsesMode) {
            org.json.JSONArray input = body.optJSONArray("messages");
            body.remove("messages");
            body.put("input", input);
        }

        String raw = postJson(com.leshao.ai.api.ApiUrl.join(baseUrl, endpoint), body);
        JSONObject json = new JSONObject(raw);

        String text;
        if (responsesMode) {
            text = ChatCompletionResponse.extractTextFromResponses(json);
        } else {
            text = ChatCompletionResponse.extractTextFromChat(json);
        }
        // 若按协议解析为空，尝试回退到 Chat 格式解析，提升容错性
        if (text == null || text.isEmpty()) {
            text = ChatCompletionResponse.extractTextFromChat(json);
        }
        return text;
    }

    /**
     * 向指定 URL 发送 JSON POST 请求并读取响应体。
     *
     * @param urlStr  完整请求 URL
     * @param json    请求体
     * @return 服务端返回的响应体字符串
     * @throws Exception 网络或 HTTP 错误时抛出（中文说明）
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
            String key = com.leshao.ai.api.ApiUrl.normalizeKey(apiKey);
            if (!key.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + key);
            }

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
                throw new Exception("请求失败，HTTP 状态码：" + code + "，响应：" + errBody);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 读取输入流中的全部文本（UTF-8）。
     */
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

    /**
     * 去掉 baseUrl 末尾的斜杠，避免拼接端点时出现双斜杠。
     */
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
