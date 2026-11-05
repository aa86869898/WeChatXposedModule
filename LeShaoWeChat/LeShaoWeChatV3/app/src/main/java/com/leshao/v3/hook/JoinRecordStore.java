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
 * 自动扫码进群记录存储（v3.0.516 新增）。
 *
 * <p>在微信进程自己的 SharedPreferences 中持久化进群记录（JSON 数组），
 * 与微信账号数据目录绑定，重启微信不丢失。记录列表页见
 * {@code ui.JoinRecordPageView}。</p>
 */
public final class JoinRecordStore {

    private static final String PREFS = "leshao_join_records";
    private static final String KEY = "records_json";
    private static final int MAX = 200;

    public static class Record {
        public long time;
        public String room;
        public String code;   // v3.0.543: 关联的群二维码 URL（用于判断是否已加入）
    }

    private JoinRecordStore() {}

    private static SharedPreferences sp() {
        Context ctx = ContextManager.getAppContext();
        return ctx != null ? UnifiedPrefs.get(ctx, PREFS) : null;
    }

    public static synchronized void add(String room) {
        add(room, null);
    }

    public static synchronized void add(String room, String code) {
        if (room == null || room.isEmpty()) return;
        SharedPreferences p = sp();
        if (p == null) return;
        List<Record> list = getAll();
        // 同一群短时间(60s)内只记一条，避免重复
        long now = System.currentTimeMillis();
        for (Record r : list) {
            if (room.equals(r.room) && now - r.time < 60_000L) return;
        }
        // 清理超过 TTL 的旧记录（防止永久残留导致同一群码被跳过）
        list.removeIf(x -> now - x.time > TTL);
        Record r = new Record();
        r.time = now;
        r.room = room;
        r.code = code;
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
                r.room = o.optString("room", "");
                r.code = o.has("code") ? o.optString("code", null) : null;
                list.add(r);
            }
        } catch (Throwable ignored) {}
        return list;
    }

    /** v3.0.543: 按群二维码 URL 查找最近一次进群的群 username。 */
    private static final long TTL = 7L * 24 * 3600 * 1000; // 进群记录 7 天有效期

    public static synchronized String getRoomByCode(String code) {
        if (code == null || code.isEmpty()) return null;
        long now = System.currentTimeMillis();
        for (Record r : getAll()) {
            if (code.equals(r.code)) {
                // 超过 7 天的记录视为过期（可能早已被踢/退群），允许重新扫码进群
                if (now - r.time > TTL) return null;
                return r.room;
            }
        }
        return null;
    }

    /** v3.0.546: 按群 username 删除该群的全部进群记录（退群/被移出群聊时实时清理）。
     *  返回被删除记录关联的群二维码 URL 列表，供调用方同步清理内存去重。 */
    public static synchronized List<String> removeRoom(String room) {
        List<String> removedCodes = new ArrayList<>();
        if (room == null || room.isEmpty()) return removedCodes;
        SharedPreferences p = sp();
        if (p == null) return removedCodes;
        List<Record> list = getAll();
        List<Record> keep = new ArrayList<>();
        for (Record r : list) {
            if (room.equals(r.room)) {
                if (r.code != null) removedCodes.add(r.code);
            } else {
                keep.add(r);
            }
        }
        if (keep.size() == list.size()) return removedCodes;  // 没有该群记录，无需写盘
        JSONArray arr = new JSONArray();
        for (Record x : keep) arr.put(toJson(x));
        p.edit().putString(KEY, arr.toString()).apply();
        return removedCodes;
    }

    public static synchronized void clear() {
        SharedPreferences p = sp();
        if (p != null) p.edit().remove(KEY).apply();
    }

    public static int count() {
        return getAll().size();
    }

    private static JSONObject toJson(Record r) {
        JSONObject o = new JSONObject();
        try {
            o.put("time", r.time);
            o.put("room", r.room);
            if (r.code != null) o.put("code", r.code);
        } catch (Throwable ignored) {}
        return o;
    }
}