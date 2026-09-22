package com.leshao.ai.api;

import com.leshao.ai.api.model.ProviderType;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 拉取服务商可用模型列表(/v1/models)。
 *
 * <p>兼容 OpenAI 与 Anthropic 两套鉴权头; 仅用标准库 {@link HttpURLConnection},
 * 可在 LSPosed 宿主进程内直接运行。</p>
 */
public final class ModelCatalogClient {

    private ModelCatalogClient() {
    }

    /**
     * 同步拉取模型 id 列表(需在后台线程调用)。
     *
     * @throws Exception 网络/鉴权/解析失败
     */
    public static List<String> fetch(String providerType, String baseUrl, String apiKey) throws Exception {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.isEmpty()) {
            throw new IllegalArgumentException("接口地址为空");
        }
        String key = ApiUrl.normalizeKey(apiKey);
        boolean anthropic = isAnthropic(providerType);
        // 先按 OpenAI/DeepSeek 约定尝试 /v1/models, 失败再回退到 /models
        Exception last = null;
        for (String path : new String[]{"/v1/models", "/models"}) {
            String endpoint = ApiUrl.join(base, path);
            try {
                return request(endpoint, key, anthropic);
            } catch (HttpError e) {
                last = e;
                // 仅当端点不存在(404)时才回退到下一个路径; 鉴权/其他错误直接抛出
                if (e.code != 404) throw e;
            }
        }
        throw last != null ? last : new IllegalStateException("获取模型失败");
    }

    /** 带 HTTP 状态码的异常, 便于决定是否回退路径。 */
    private static final class HttpError extends Exception {
        final int code;

        HttpError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static List<String> request(String endpoint, String key, boolean anthropic) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Accept", "application/json");
            if (anthropic) {
                conn.setRequestProperty("x-api-key", key);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + key);
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(in);
            if (code < 200 || code >= 300) {
                throw new HttpError(code, "HTTP " + code + " (" + endpoint + "): " + brief(body));
            }
            return parseModelIds(body);
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean isAnthropic(String providerType) {
        if (providerType == null) return false;
        String t = providerType.toLowerCase();
        return t.contains("anthropic") || t.contains("claude");
    }

    private static List<String> parseModelIds(String body) throws Exception {
        List<String> out = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) return out;
        JSONObject obj = new JSONObject(body);
        JSONArray arr = obj.optJSONArray("data");
        if (arr == null) arr = obj.optJSONArray("models");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            Object it = arr.opt(i);
            String id = null;
            if (it instanceof String) {
                id = (String) it;
            } else if (it instanceof JSONObject) {
                JSONObject o = (JSONObject) it;
                id = o.optString("id", null);
                if (id == null) id = o.optString("name", null);
            }
            if (id != null && !id.trim().isEmpty()) out.add(id.trim());
        }
        Collections.sort(out);
        return out;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String brief(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** 供 ProviderType 复用(避免未使用告警)。 */
    public static List<String> fetch(ProviderType type, String baseUrl, String apiKey) throws Exception {
        return fetch(type == null ? null : type.name(), baseUrl, apiKey);
    }
}
