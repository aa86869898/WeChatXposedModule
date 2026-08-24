package com.leshao.wechat;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public class KeywordReplyManager {
    private static final Map<String, String> replyMap = new HashMap<>();

    public static void init() {
        replyMap.clear();
        String saved = ModuleSettings.getStr("ls_keyword_reply_map", "");
        if (saved == null || saved.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(saved);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item != null) replyMap.put(item.optString("kw"), item.optString("reply"));
            }
        } catch (Exception e) {}
    }

    public static String check(String content) {
        if (content == null || !ModuleSettings.keywordReplyEnabled || replyMap.isEmpty()) return null;
        for (Map.Entry<String, String> e : replyMap.entrySet()) {
            if (content.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    public static void addRule(String kw, String reply) {
        replyMap.put(kw, reply);
        saveAll();
    }

    public static void removeRule(String kw) {
        replyMap.remove(kw);
        saveAll();
    }

    public static Map<String, String> getAllRules() { return new HashMap<>(replyMap); }

    private static void saveAll() {
        try {
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, String> e : replyMap.entrySet()) {
                JSONObject item = new JSONObject(); item.put("kw", e.getKey()); item.put("reply", e.getValue()); arr.put(item);
            }
            ModuleSettings.putStr("ls_keyword_reply_map", arr.toString());
        } catch (Exception e) {}
    }
}
