package com.leshao.v3;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

public class ShadowLabelStore {

    private static final String PREFS_NAME = "leshao_shadow_labels";
    private static final String KEY_LABELS = "labels_json";
    private static final String KEY_LABEL_ORDER = "label_order";
    private static volatile SharedPreferences sPrefs;

    public static class ShadowLabel {
        public int labelId;
        public String labelName;
        public long createTime;

        public ShadowLabel() {}
        public ShadowLabel(int id, String name, long time) {
            this.labelId = id; this.labelName = name; this.createTime = time;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try { o.put("id", labelId); o.put("name", labelName); o.put("time", createTime); } catch (Throwable ignored) {}
            return o;
        }

        public static ShadowLabel fromJson(JSONObject o) {
            ShadowLabel l = new ShadowLabel();
            l.labelId = o.optInt("id", -1);
            l.labelName = o.optString("name", "");
            l.createTime = o.optLong("time", 0);
            return l;
        }
    }

    public static void init(Context ctx) {
        if (sPrefs == null && ctx != null) {
            sPrefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    public static List<ShadowLabel> getAll() {
        List<ShadowLabel> list = new ArrayList<>();
        try {
            if (sPrefs == null) return list;
            String json = sPrefs.getString(KEY_LABELS, "");
            if (json.isEmpty()) return list;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(ShadowLabel.fromJson(arr.getJSONObject(i)));
            }
        } catch (Throwable e) { LogWriter.log("ShadowStore", "getAll: " + e.getMessage()); }
        return list;
    }

    public static void add(int labelId, String name) {
        try {
            if (sPrefs == null) return;
            List<ShadowLabel> list = getAll();
            for (ShadowLabel l : list) { if (l.labelName.equals(name)) return; }
            list.add(new ShadowLabel(labelId, name, System.currentTimeMillis()));
            save(list);
            LogWriter.log("ShadowStore", "add: id=" + labelId + " name=" + name);
        } catch (Throwable e) { LogWriter.log("ShadowStore", "add error: " + e.getMessage()); }
    }

    public static void rename(int labelId, String newName) {
        try {
            if (sPrefs == null) return;
            List<ShadowLabel> list = getAll();
            for (ShadowLabel l : list) {
                if (l.labelId == labelId) { l.labelName = newName; save(list); return; }
            }
        } catch (Throwable e) { LogWriter.log("ShadowStore", "rename error: " + e.getMessage()); }
    }

    public static void remove(int labelId) {
        try {
            if (sPrefs == null) return;
            List<ShadowLabel> list = getAll();
            for (Iterator<ShadowLabel> it = list.iterator(); it.hasNext(); ) {
                if (it.next().labelId == labelId) { it.remove(); break; }
            }
            save(list);
            LogWriter.log("ShadowStore", "remove: id=" + labelId);
        } catch (Throwable e) { LogWriter.log("ShadowStore", "remove error: " + e.getMessage()); }
    }

    public static void updateId(int oldId, int newId, String name) {
        try {
            if (sPrefs == null) return;
            List<ShadowLabel> list = getAll();
            for (ShadowLabel l : list) {
                if (l.labelId == oldId && l.labelName.equals(name)) {
                    l.labelId = newId;
                    save(list);
                    LogWriter.log("ShadowStore", "updateId: " + oldId + " -> " + newId);
                    return;
                }
            }
        } catch (Throwable e) { LogWriter.log("ShadowStore", "updateId error: " + e.getMessage()); }
    }

    public static List<Integer> getLabelOrder() {
        List<Integer> r = new ArrayList<>();
        try {
            if (sPrefs == null) return r;
            String json = sPrefs.getString(KEY_LABEL_ORDER, "");
            if (json.isEmpty()) return r;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) r.add(arr.getInt(i));
        } catch (Throwable e) { LogWriter.log("ShadowStore", "getOrder: " + e.getMessage()); }
        return r;
    }

    public static void saveLabelOrder(List<Integer> order) {
        try {
            if (sPrefs == null) return;
            JSONArray arr = new JSONArray();
            for (int id : order) arr.put(id);
            sPrefs.edit().putString(KEY_LABEL_ORDER, arr.toString()).commit();
        } catch (Throwable e) { LogWriter.log("ShadowStore", "saveOrder: " + e.getMessage()); }
    }

    public static void save(List<ShadowLabel> list) {
        try {
            if (sPrefs == null) return;
            JSONArray arr = new JSONArray();
            for (ShadowLabel l : list) arr.put(l.toJson());
            sPrefs.edit().putString(KEY_LABELS, arr.toString()).commit();
        } catch (Throwable e) { LogWriter.log("ShadowStore", "save error: " + e.getMessage()); }
    }
}
