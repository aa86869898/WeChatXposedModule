package com.leshao.v3.hook;

import android.content.Context;
import android.content.SharedPreferences;

import com.leshao.v3.ContextManager;
import com.leshao.v3.UnifiedPrefs;

public class GroupConfigManager {

    private static final String PREFS_NAME = "wxgroup_config";
    private static SharedPreferences sPrefs;

    public static void init(Context ctx) {
        if (ctx != null) {
            sPrefs = UnifiedPrefs.get(ctx, PREFS_NAME);
        }
    }

    public static SharedPreferences getPrefs() {
        if (sPrefs == null) sPrefs = ContextManager.getPrefs();
        return sPrefs;
    }

    public static boolean isAutoGroupEnabled() { return getBool("auto_group_enabled", false); }
    public static boolean isSyncHookEnabled() { return getBool("sync_hook_enabled", true); }
    public static boolean isAutoBackupEnabled() { return getBool("auto_backup_enabled", false); }
    public static int getBackupIntervalHours() { return getInt("backup_interval_hours", 24); }
    public static String getLastBackupPath() { return getString("last_backup_path", ""); }
    public static long getLastBackupTime() { return getLong("last_backup_time", 0); }
    public static long getLastSyncTime() { return getLong("last_sync_time", 0); }

    public static void setAutoGroupEnabled(boolean v) { putBool("auto_group_enabled", v); }
    public static void setSyncHookEnabled(boolean v) { putBool("sync_hook_enabled", v); }
    public static void setAutoBackupEnabled(boolean v) { putBool("auto_backup_enabled", v); }
    public static void setBackupIntervalHours(int v) { putInt("backup_interval_hours", v); }
    public static void setLastBackupPath(String v) { putString("last_backup_path", v); }
    public static void setLastBackupTime(long v) { putLong("last_backup_time", v); }
    public static void setLastSyncTime(long v) { putLong("last_sync_time", v); }
    public static String getAutoRulesRaw() { return getString("auto_rules", ""); }
    public static void setAutoRulesRaw(String v) { putString("auto_rules", v); }

    private static boolean getBool(String k, boolean d) { return getPrefs() != null ? getPrefs().getBoolean(k, d) : d; }
    private static int getInt(String k, int d) { return getPrefs() != null ? getPrefs().getInt(k, d) : d; }
    private static long getLong(String k, long d) { return getPrefs() != null ? getPrefs().getLong(k, d) : d; }
    private static String getString(String k, String d) { return getPrefs() != null ? getPrefs().getString(k, d) : d; }
    private static void putBool(String k, boolean v) { if (getPrefs() != null) getPrefs().edit().putBoolean(k, v).apply(); }
    private static void putInt(String k, int v) { if (getPrefs() != null) getPrefs().edit().putInt(k, v).apply(); }
    private static void putLong(String k, long v) { if (getPrefs() != null) getPrefs().edit().putLong(k, v).apply(); }
    private static void putString(String k, String v) { if (getPrefs() != null) getPrefs().edit().putString(k, v).apply(); }
}
