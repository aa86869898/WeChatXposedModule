package com.leshao.v3.service;

import android.content.Context;
import android.content.SharedPreferences;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;

/**
 * 模块黑名单与管理员管理
 * 管理员可查看和编辑模块黑名单
 */
public class ActivationManager {

    private static final String TAG = "ActivationManager";

    public static final String[] ADMIN_WXIDS = {
        "wxid_9ohhf82mrlgc22",
        "wxid_qf3ok46p08v922"
    };

    public static boolean isAdmin(String wxid) {
        if (wxid == null) return false;
        for (String a : ADMIN_WXIDS) {
            if (wxid.equals(a)) return true;
        }
        return false;
    }

    private static final String PREF_KEY_BLACKLIST = "ls_module_blacklist";

    public static java.util.Set<String> getBlacklist() {
        java.util.Set<String> set = new java.util.HashSet<>();
        SharedPreferences prefs = ContextManager.getPrefs();
        if (prefs == null) return set;
        String raw = prefs.getString(PREF_KEY_BLACKLIST, "");
        if (raw == null || raw.isEmpty()) return set;
        for (String s : raw.split("\\|")) {
            if (s != null && !s.trim().isEmpty()) set.add(s.trim());
        }
        return set;
    }

    public static boolean isBlacklisted(String wxid) {
        if (wxid == null || wxid.isEmpty()) return false;
        return getBlacklist().contains(wxid);
    }

    public static boolean isCurrentUserBlocked() {
        String wxid = ModuleConfig.getCurrentWxid();
        return wxid != null && !wxid.isEmpty() && !isAdmin(wxid) && isBlacklisted(wxid);
    }

    public static void addBlacklist(String wxid) {
        if (wxid == null || wxid.trim().isEmpty()) return;
        java.util.Set<String> set = getBlacklist();
        if (set.contains(wxid)) return;
        set.add(wxid);
        saveBlacklist(set);
        LogWriter.log(TAG, "blacklist add: " + wxid);
    }

    public static void removeBlacklist(String wxid) {
        if (wxid == null) return;
        java.util.Set<String> set = getBlacklist();
        if (!set.remove(wxid)) return;
        saveBlacklist(set);
        LogWriter.log(TAG, "blacklist remove: " + wxid);
    }

    private static void saveBlacklist(java.util.Set<String> set) {
        SharedPreferences prefs = ContextManager.getPrefs();
        if (prefs == null) return;
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) sb.append('|');
            sb.append(s);
        }
        prefs.edit().putString(PREF_KEY_BLACKLIST, sb.toString()).apply();
    }
}