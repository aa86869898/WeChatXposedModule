package com.leshao.v3.model;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class ContactChangeRecord {
    public long time;
    public String wxid;
    public String nickname;
    public String remark;
    public String oldNickname;
    public String newNickname;
    public String oldRemark;
    public String newRemark;
    public String oldSignature;
    public String newSignature;
    public boolean nicknameChanged;
    public boolean remarkChanged;
    public boolean signatureChanged;
    public boolean avatarChanged;
    public int oldAvatarHash;
    public int newAvatarHash;
    public String oldAvatarPath;
    public String newAvatarPath;

    public ContactChangeRecord() {}

    public boolean hasAnyChange() {
        return nicknameChanged || remarkChanged || signatureChanged || avatarChanged;
    }

    public String displayName() {
        if (remark != null && !remark.isEmpty()) return remark;
        if (nickname != null && !nickname.isEmpty()) return nickname;
        return wxid;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("t", time);
            o.put("w", wxid != null ? wxid : "");
            o.put("n", nickname != null ? nickname : "");
            o.put("r", remark != null ? remark : "");
            o.put("nc", nicknameChanged);
            o.put("rc", remarkChanged);
            o.put("sc", signatureChanged);
            o.put("ac", avatarChanged);
            if (nicknameChanged) {
                o.put("on", oldNickname != null ? oldNickname : "");
                o.put("nn", newNickname != null ? newNickname : "");
            }
            if (remarkChanged) {
                o.put("or", oldRemark != null ? oldRemark : "");
                o.put("nr", newRemark != null ? newRemark : "");
            }
            if (signatureChanged) {
                o.put("os", oldSignature != null ? oldSignature : "");
                o.put("ns", newSignature != null ? newSignature : "");
            }
            if (avatarChanged) {
                o.put("ah", oldAvatarHash);
                o.put("na", newAvatarHash);
                o.put("op", oldAvatarPath != null ? oldAvatarPath : "");
                o.put("np", newAvatarPath != null ? newAvatarPath : "");
            }
        } catch (Throwable ignored) {}
        return o;
    }

    public static ContactChangeRecord fromJson(JSONObject o) {
        ContactChangeRecord r = new ContactChangeRecord();
        r.time = o.optLong("t");
        r.wxid = o.optString("w");
        r.nickname = o.optString("n");
        r.remark = o.optString("r");
        r.nicknameChanged = o.optBoolean("nc");
        r.remarkChanged = o.optBoolean("rc");
        r.signatureChanged = o.optBoolean("sc");
        r.avatarChanged = o.optBoolean("ac");
        if (r.nicknameChanged) {
            r.oldNickname = o.optString("on");
            r.newNickname = o.optString("nn");
        }
        if (r.remarkChanged) {
            r.oldRemark = o.optString("or");
            r.newRemark = o.optString("nr");
        }
        if (r.signatureChanged) {
            r.oldSignature = o.optString("os");
            r.newSignature = o.optString("ns");
        }
        if (r.avatarChanged) {
            r.oldAvatarHash = o.optInt("ah");
            r.newAvatarHash = o.optInt("na");
            r.oldAvatarPath = o.optString("op", null);
            r.newAvatarPath = o.optString("np", null);
        }
        return r;
    }

    public static List<ContactChangeRecord> parseArray(String json) {
        List<ContactChangeRecord> list = new ArrayList<>();
        if (json == null || json.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                ContactChangeRecord r = fromJson(arr.getJSONObject(i));
                if (r.hasAnyChange()) list.add(r);
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
