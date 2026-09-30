package com.leshao.v3.hook;

import android.content.SharedPreferences;
import com.leshao.v3.ContextManager;

public class HookConfig {

    private static SharedPreferences getPrefs() {
        return ContextManager.getPrefs();
    }

    public static boolean isEnabled(String key) {
        SharedPreferences sp = getPrefs();
        if (sp == null) return getDefault(key);
        return sp.getBoolean(key, getDefault(key));
    }

    public static int getInt(String key, int def) {
        SharedPreferences sp = getPrefs();
        if (sp == null) return def;
        return sp.getInt(key, def);
    }

    public static void setInt(String key, int val) {
        SharedPreferences sp = getPrefs();
        if (sp != null) sp.edit().putInt(key, val).apply();
    }

    public static long getLong(String key, long def) {
        SharedPreferences sp = getPrefs();
        if (sp == null) return def;
        return sp.getLong(key, def);
    }

    public static String getString(String key, String def) {
        SharedPreferences sp = getPrefs();
        if (sp == null) return def;
        return sp.getString(key, def);
    }

    private static boolean getDefault(String key) {
        if (key.equals("sns_ad_block")) return true;
        if (key.equals("sns_forward")) return true;
        if (key.equals("sns_simulate_like")) return false;
        if (key.equals("sns_time_edit")) return false;
        if (key.equals("sns_video_quality")) return true;
        if (key.equals("sns_long_video")) return true;
        if (key.equals("anti_detection")) return true;
        if (key.equals("voice_forward")) return true;
        return true;
    }
}
