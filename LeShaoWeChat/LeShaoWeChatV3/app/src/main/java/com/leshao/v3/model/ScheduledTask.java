package com.leshao.v3.model;

import java.util.ArrayList;
import java.util.List;

public class ScheduledTask {

    public String id;
    public String targetWxid;
    public String content;
    public int hour;
    public int minute;
    public int repeatDays; // bitmask: 1=Sun, 2=Mon, 4=Tue, 8=Wed, 16=Thu, 32=Fri, 64=Sat
    public boolean enabled;

    public ScheduledTask() {
        this.id = String.valueOf(System.currentTimeMillis());
        this.repeatDays = 0;
    }

    public ScheduledTask(String id, String targetWxid, String content, int hour, int minute,
                         int repeatDays, boolean enabled) {
        this.id = id != null ? id : String.valueOf(System.currentTimeMillis());
        this.targetWxid = targetWxid != null ? targetWxid : "";
        this.content = content != null ? content : "";
        this.hour = hour;
        this.minute = minute;
        this.repeatDays = repeatDays;
        this.enabled = enabled;
    }

    public static List<ScheduledTask> fromJson(String json) {
        List<ScheduledTask> tasks = new ArrayList<>();
        if (json == null || json.isEmpty() || "[]".equals(json)) return tasks;
        try {
            String inner = json.substring(1, json.length() - 1);
            if (inner.isEmpty()) return tasks;
            String[] items = inner.split("\\},\\{");
            for (String item : items) {
                item = item.replace("{", "").replace("}", "");
                ScheduledTask t = new ScheduledTask();
                t.id = extract(item, "\"id\":\"", "\"");
                t.targetWxid = extract(item, "\"targetWxid\":\"", "\"");
                t.content = extract(item, "\"content\":\"", "\"");
                String h = extract(item, "\"hour\":", ",");
                if (h == null) h = extract(item, "\"hour\":", "}");
                t.hour = h != null ? Integer.parseInt(h) : 0;
                String m = extract(item, "\"minute\":", ",");
                if (m == null) m = extract(item, "\"minute\":", "}");
                t.minute = m != null ? Integer.parseInt(m) : 0;
                String rd = extract(item, "\"repeatDays\":", ",");
                if (rd == null) rd = extract(item, "\"repeatDays\":", "}");
                t.repeatDays = rd != null ? Integer.parseInt(rd) : 0;
                String en = extract(item, "\"enabled\":", "}");
                t.enabled = "true".equals(en);
                if (t.id != null) tasks.add(t);
            }
        } catch (Throwable ignored) {}
        return tasks;
    }

    public static String toJson(List<ScheduledTask> tasks) {
        if (tasks == null || tasks.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tasks.size(); i++) {
            ScheduledTask t = tasks.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"id\":\"").append(escape(t.id))
              .append("\",\"targetWxid\":\"").append(escape(t.targetWxid))
              .append("\",\"content\":\"").append(escape(t.content))
              .append("\",\"hour\":").append(t.hour)
              .append(",\"minute\":").append(t.minute)
              .append(",\"repeatDays\":").append(t.repeatDays)
              .append(",\"enabled\":").append(t.enabled)
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
