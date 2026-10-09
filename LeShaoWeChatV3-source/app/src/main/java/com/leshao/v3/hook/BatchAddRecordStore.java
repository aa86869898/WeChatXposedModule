package com.leshao.v3.hook;

import android.content.Context;
import android.content.SharedPreferences;

import com.leshao.v3.ContextManager;
import com.leshao.v3.UnifiedPrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量加好友记录持久化存储。
 * 记录写入微信进程自己的 SharedPreferences（leshao_batch_add_records），
 * 与微信账号数据目录绑定，重启微信不会丢失。
 */
public final class BatchAddRecordStore {

    private static final String PREFS = "leshao_batch_add_records";
    private static final String KEY = "records_json";
    private static final int MAX = 500;

    public static class Record {
        public long time;
        public String username;
        public String displayName;
        public boolean success;
        public String reason;
    }

    private BatchAddRecordStore() {}

    private static SharedPreferences sp() {
        Context ctx = ContextManager.getAppContext();
        return ctx != null ? UnifiedPrefs.get(ctx, PREFS) : null;
    }

    public static synchronized void add(String username, String displayName,
                                        boolean success, String reason) {
        SharedPreferences p = sp();
        if (p == null) return;
        List<Record> list = getAll();
        Record r = new Record();
        r.time = System.currentTimeMillis();
        r.username = username;
        r.displayName = displayName;
        r.success = success;
        r.reason = reason;
        list.add(0, r);
        while (list.size() > MAX) list.remove(list.size() - 1);
        JSONArray arr = new JSONArray();
        for (Record x : list) arr.put(toJson(x));
        p.edit().putString(KEY, arr.toString()).apply();
    }

    public static synchronized List<Record> getAll() {
        List<Record> list = new ArrayList<>();
        SharedPreferences p = sp();
        if (p == null) return list;
        String s = p.getString(KEY, "");
        if (s == null || s.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Record r = new Record();
                r.time = o.optLong("time", 0);
                r.username = o.optString("username", "");
                r.displayName = o.optString("displayName", "");
                r.success = o.optBoolean("success", false);
                r.reason = o.optString("reason", "");
                list.add(r);
            }
        } catch (Throwable ignored) {}
        return list;
    }

    public static synchronized void clear() {
        SharedPreferences p = sp();
        if (p != null) p.edit().remove(KEY).apply();
    }

    private static JSONObject toJson(Record r) {
        JSONObject o = new JSONObject();
        try {
            o.put("time", r.time);
            o.put("username", r.username);
            o.put("displayName", r.displayName);
            o.put("success", r.success);
            o.put("reason", r.reason);
        } catch (Throwable ignored) {}
        return o;
    }
}
