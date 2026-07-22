package com.leshao.wechat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public class DeepSeekManager {
    private static final String API = "https://api.deepseek.com/v1/chat/completions";
    private static final Map<String, List<JSONObject>> history = new HashMap<>();

    public static String chat(String wxid, String msg) {
        if (ModuleSettings.deepseekApiKey.isEmpty()) return null;
        return call(wxid, "你是一个友好的AI助手", msg);
    }

    public static String translate(String text) {
        if (ModuleSettings.deepseekApiKey.isEmpty()) return null;
        return call(null, "你是一个翻译助手，把用户输入翻译成中文", text);
    }

    public static String summarize(String text) {
        if (ModuleSettings.deepseekApiKey.isEmpty()) return null;
        return call(null, "你是一个摘要助手，用一句话总结用户输入", text);
    }

    private static String call(String historyKey, String systemPrompt, String userMsg) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", ModuleSettings.deepseekModel);
            body.put("temperature", 0.7);
            body.put("max_tokens", 1024);

            JSONArray msgs = new JSONArray();
            JSONObject sys = new JSONObject(); sys.put("role", "system"); sys.put("content", systemPrompt); msgs.put(sys);

            if (historyKey != null) {
                synchronized (history) {
                    List<JSONObject> h = history.get(historyKey);
                    if (h != null) for (JSONObject m : h) msgs.put(m);
                }
            }

            JSONObject user = new JSONObject(); user.put("role", "user"); user.put("content", userMsg); msgs.put(user);
            body.put("messages", msgs);

            String resp = Utils.httpPost(API, body.toString(), "application/json");
            if (resp == null) return null;

            JSONObject j = new JSONObject(resp);
            JSONArray choices = j.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                String reply = choices.getJSONObject(0).optJSONObject("message").optString("content", "");
                if (historyKey != null && !reply.isEmpty()) {
                    synchronized (history) {
                        List<JSONObject> h = history.get(historyKey);
                        if (h == null) { h = new ArrayList<>(); history.put(historyKey, h); }
                        h.add(user);
                        JSONObject a = new JSONObject(); a.put("role", "assistant"); a.put("content", reply); h.add(a);
                        if (h.size() > 20) { h.remove(0); h.remove(0); }
                    }
                }
                return reply.trim();
            }
        } catch (Exception e) { Utils.log("DeepSeek err: " + e.getMessage()); }
        return null;
    }

    public static void clearHistory(String key) { synchronized (history) { history.remove(key); } }
    public static void clearAllHistory() { synchronized (history) { history.clear(); } }
}
