package com.leshao.v3.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class KeywordRule {

    public String keyword;
    public String reply;
    public boolean fuzzyMatch;

    public KeywordRule() {}

    public KeywordRule(String keyword, String reply, boolean fuzzyMatch) {
        this.keyword = keyword;
        this.reply = reply;
        this.fuzzyMatch = fuzzyMatch;
    }

    public static List<KeywordRule> fromJson(String json) {
        List<KeywordRule> rules = new ArrayList<>();
        if (json == null || json.isEmpty() || "[]".equals(json)) return rules;
        try {
            String inner = json.substring(1, json.length() - 1);
            if (inner.isEmpty()) return rules;
            String[] items = inner.split("\\},\\{");
            for (String item : items) {
                item = item.replace("{", "").replace("}", "");
                String kw = extract(item, "\"keyword\":\"", "\"");
                String rp = extract(item, "\"reply\":\"", "\"");
                String fm = extract(item, "\"fuzzyMatch\":", ",");
                if (fm == null) fm = extract(item, "\"fuzzyMatch\":", "}");
                if (kw != null && rp != null) {
                    rules.add(new KeywordRule(kw, rp, "true".equals(fm)));
                }
            }
        } catch (Throwable ignored) {}
        return rules;
    }

    public static String toJson(List<KeywordRule> rules) {
        if (rules == null || rules.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rules.size(); i++) {
            KeywordRule r = rules.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"keyword\":\"")
              .append(escape(r.keyword))
              .append("\",\"reply\":\"")
              .append(escape(r.reply))
              .append("\",\"fuzzyMatch\":")
              .append(r.fuzzyMatch)
              .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    public static KeywordRule fromObj(JSONObject o) {
        if (o == null) return null;
        String kw = o.optString("keyword", null);
        String rp = o.optString("reply", null);
        if (kw == null || kw.trim().isEmpty()) return null;
        return new KeywordRule(kw.trim(), rp == null ? "" : rp, o.optBoolean("fuzzyMatch", false));
    }

    public static JSONObject toObj(KeywordRule r) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("keyword", r.keyword == null ? "" : r.keyword);
        o.put("reply", r.reply == null ? "" : r.reply);
        o.put("fuzzyMatch", r.fuzzyMatch);
        return o;
    }

    public static List<KeywordRule> listFromJson(JSONArray arr) {
        List<KeywordRule> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            KeywordRule r = fromObj(arr.optJSONObject(i));
            if (r != null) out.add(r);
        }
        return out;
    }

    public static JSONArray listToJson(List<KeywordRule> rules) throws JSONException {
        JSONArray arr = new JSONArray();
        if (rules == null) return arr;
        for (KeywordRule r : rules) {
            if (r == null || r.keyword == null || r.keyword.trim().isEmpty()) continue;
            arr.put(toObj(r));
        }
        return arr;
    }

    /** v1085: 正文是否命中该规则(默认包含匹配, fuzzyMatch 时忽略大小写)。 */
    public boolean matches(String body) {
        if (body == null || keyword == null || keyword.trim().isEmpty()) return false;
        String kw = keyword.trim();
        if (fuzzyMatch) {
            return body.toLowerCase(java.util.Locale.ROOT)
                    .contains(kw.toLowerCase(java.util.Locale.ROOT));
        }
        return body.contains(kw);
    }

    private static String extract(String src, String prefix, String suffix) {
        int s = src.indexOf(prefix);
        if (s < 0) return null;
        s += prefix.length();
        int e = src.indexOf(suffix, s);
        if (e < 0) return src.substring(s);
        return src.substring(s, e);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
