package com.leshao.v3.model;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class ContactChangeRecord {
    public long time;
    public String wxid;
    public String nickname;
    public String changeType;
    public String oldValue;
    public String newValue;

    public ContactChangeRecord() {}

    public ContactChangeRecord(long time, String wxid, String nickname, String changeType, String oldValue, String newValue) {
        this.time = time;
        this.wxid = wxid;
        this.nickname = nickname;
        this.changeType = changeType;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public String desc() {
        if (oldValue == null) oldValue = "";
        if (newValue == null) newValue = "";
        return nickname + " " + changeType + ": " + oldValue + " -> " + newValue;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("t", time);
            o.put("w", wxid != null ? wxid : "");
            o.put("n", nickname != null ? nickname : "");
            o.put("c", changeType != null ? changeType : "");
            o.put("o", oldValue != null ? oldValue : "");
            o.put("v", newValue != null ? newValue : "");
        } catch (Throwable ignored) {}
        return o;
    }

    public static ContactChangeRecord fromJson(JSONObject o) {
        ContactChangeRecord r = new ContactChangeRecord();
        r.time = o.optLong("t");
        r.wxid = o.optString("w");
        r.nickname = o.optString("n");
        r.changeType = o.optString("c");
        r.oldValue = o.optString("o");
        r.newValue = o.optString("v");
        return r;
    }

    public static List<ContactChangeRecord> parseArray(String json) {
        List<ContactChangeRecord> list = new ArrayList<>();
        if (json == null || json.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(fromJson(arr.getJSONObject(i)));
            }
        } catch (Throwable ignored) {}
        return list;
    }

    public static String toArrayJson(List<ContactChangeRecord> list) {
        JSONArray arr = new JSONArray();
        for (ContactChangeRecord r : list) {
            arr.put(r.toJson());
        }
        return arr.toString();
    }
}
