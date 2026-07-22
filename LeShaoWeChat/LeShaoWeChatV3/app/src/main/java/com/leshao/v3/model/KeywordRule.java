package com.leshao.v3.model;

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
