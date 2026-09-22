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
        String base = normalizeBase(baseUrl);
        if (base == null) {
            throw new IllegalArgumentException("接口地址为空");
        }
        String endpoint = base + "/models";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Accept", "application/json");
            boolean anthropic = isAnthropic(providerType);
            if (anthropic) {
                conn.setRequestProperty("x-api-key", apiKey == null ? "" : apiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + (apiKey == null ? "" : apiKey));
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(in);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + ": " + brief(body));
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

    /** 规范化 base: 补齐 /v1(若未包含), 去掉尾部斜杠。 */
    private static String normalizeBase(String baseUrl) {
        if (baseUrl == null) return null;
        String b = baseUrl.trim();
        if (b.isEmpty()) return null;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (!b.contains("/v1") && !b.endsWith("/v1")) {
            b = b + "/v1";
        }
        return b;
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
