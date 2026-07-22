package com.leshao.v3.service;

import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.model.WeChatMessage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import de.robv.android.xposed.XposedHelpers;

public class AIService {

    private static final String TAG = "AIService";

    public static void process(WeChatMessage msg, ModuleConfig cfg) {
        if (msg == null || cfg == null) return;
        if (!cfg.deepseekEnabled) return;
        if (cfg.deepseekApiKey == null || cfg.deepseekApiKey.isEmpty()) return;
        if (msg.type != WeChatMessage.TYPE_TEXT) return;

        String content = msg.content;
        if (content == null || content.isEmpty()) return;

        // 检查是否 @机器人 或包含指令前缀
        if (!shouldProcess(content)) return;

        new Thread(() -> {
            try {
                String reply = callDeepSeek(cfg.deepseekApiKey, content);
                if (reply != null && !reply.isEmpty()) {
                    sendReply(msg.talker, reply);
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "AI process error: " + t.getMessage());
            }
        }, "leshao-ai").start();
    }

    private static boolean shouldProcess(String content) {
        return content.contains("@机器人") || content.startsWith("AI") || content.startsWith("ai");
    }

    private static String callDeepSeek(String apiKey, String prompt) throws Exception {
        URL url = new URL("https://api.deepseek.com/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

        String body = "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\""
            + escapeJson(prompt) + "\"}],\"max_tokens\":500}";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes("UTF-8"));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            LogWriter.log(TAG, "DeepSeek API error: " + code);
            return null;
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }

        String resp = sb.toString();
        int idx = resp.indexOf("\"content\":\"");
        if (idx < 0) return null;
        idx += 11;
        int end = resp.indexOf("\"", idx);
        if (end < 0) return resp.substring(idx);
        return resp.substring(idx, end).replace("\\n", "\n").replace("\\\"", "\"");
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static void sendReply(String talker, String text) {
        try {
            ClassLoader cl = com.leshao.v3.ContextManager.getClassLoader();
            Class<?> msgClass = cl.loadClass("com.tencent.mm.modelmulti.n");
            Object msg = XposedHelpers.newInstance(msgClass, talker, text, 1);
            XposedHelpers.callStaticMethod(msgClass, "b", msg);
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendReply FAILED: " + t.getMessage());
        }
    }
}
