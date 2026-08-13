package com.leshao.v3.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AiClient {
    public interface Callback { void onResult(String text); void onError(String msg); }
    public interface ModelsCallback { void onModels(List<String> models); void onError(String msg); }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);

    public static class ChatMessage {
        public final String role;
        public final String content;
        public ChatMessage(String role, String content) { this.role = role; this.content = content; }
    }

    public static void chatAsync(String system, List<ChatMessage> msgs, Callback cb) {
        POOL.execute(() -> {
            try { cb.onResult(chatSync(system, msgs)); }
            catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    public static String chatSync(String system, List<ChatMessage> msgs) throws Exception {
        String url = apiUrl(AiConfig.activeBaseUrl(), "/chat/completions");
        JSONArray arr = new JSONArray();
        if (system != null && !system.isEmpty()) {
            arr.put(new JSONObject().put("role", "system").put("content", system));
        }
        for (ChatMessage m : msgs) {
            arr.put(new JSONObject().put("role", m.role).put("content", m.content));
        }

        JSONObject body = new JSONObject();
        body.put("model", AiConfig.activeModel());
        body.put("messages", arr);
        body.put("temperature", AiConfig.temperature());

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + AiConfig.activeKey());

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        conn.disconnect();

        if (code >= 400) throw new RuntimeException("HTTP " + code + ": " + sb);

        JSONObject resp = new JSONObject(sb.toString());
        return resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
    }

    public static void listModelsAsync(String base, String key, ModelsCallback cb) {
        POOL.execute(() -> {
            try {
                String url = apiUrl(base, "/models");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(30_000);
                conn.setRequestProperty("Authorization", "Bearer " + key);

                int code = conn.getResponseCode();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                        code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                conn.disconnect();

                if (code >= 400) throw new RuntimeException("HTTP " + code + ": " + sb);

                JSONObject resp = new JSONObject(sb.toString());
                JSONArray data = resp.getJSONArray("data");
                List<String> models = new ArrayList<>();
                for (int i = 0; i < data.length(); i++) {
                    String id = data.getJSONObject(i).optString("id");
                    if (id != null && !id.isEmpty()) models.add(id);
                }
                cb.onModels(models);
            } catch (Exception e) {
                cb.onError(e.getMessage());
            }
        });
    }

    private static String apiUrl(String base, String path) {
        String b = base == null ? "" : base.trim().replaceAll("/+$", "");
        return b.endsWith("/v1") ? b + path : b + "/v1" + path;
    }
}
