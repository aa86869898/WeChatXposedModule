package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.hook.HookConfig;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * [功能39/44] ChatBackup — 完整修复版
 * ===================================
 * 
 * ⚠️ 修复: 数据库路径
 *   Android 11+ 使用 /data/user/0/ 而非 /data/data/
 *   优先尝试 /data/user/0/... 失败尝试 /data/data/...
 * 
 *   实测路径: /data/user/0/com.tencent.mm/MicroMsg/<md5>/EnMicroMsg.db
 */
public class ChatBackup {
    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
    private static final int RETENTION_DAYS = 7;
    private static String lastBackupDate = "";

    public static void hook(ClassLoader cl) {
        XposedBridge.log("[ChatBackup] hook() ENTER sEnabled=" + sEnabled);
        if (!sEnabled || !ModuleConfig.load(ContextManager.getPrefs()).chatBackupEnabled) return;
        hookAppExit(cl);
        scheduleDailyBackup();
    }

    private static void hookAppExit(ClassLoader cl) {
        try {
            Class<?> launcherUI = XposedHelpers.findClass(
                    "com.tencent.mm.ui.LauncherUI", cl);

            XposedBridge.hookAllMethods(launcherUI, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String today = new SimpleDateFormat("yyyyMMdd").format(new Date());
                    if (!today.equals(lastBackupDate)) {
                        performBackup();
                        lastBackupDate = today;
                    }
                }
            });

            XposedBridge.hookAllMethods(launcherUI, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String today = new SimpleDateFormat("yyyyMMdd").format(new Date());
                    if (!today.equals(lastBackupDate)) {
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(new Runnable() {
                            public void run() {
                                performBackup();
                                lastBackupDate = today;
                            }
                        }, 5000);
                    }
                }
            });
        } catch (Throwable t) {}
    }

    private static void scheduleDailyBackup() {
        try {
            Context ctx = ContextManager.getAppContext();
            if (ctx == null) return;

            AlarmManager alarmMgr = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent("com.wechatplus.BACKUP");
            PendingIntent pending = PendingIntent.getBroadcast(ctx, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 2);
            calendar.set(java.util.Calendar.MINUTE, 0);
            calendar.set(java.util.Calendar.SECOND, 0);
            if (calendar.before(java.util.Calendar.getInstance()))
                calendar.add(java.util.Calendar.DAY_OF_MONTH, 1);

            alarmMgr.setRepeating(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(),
                    AlarmManager.INTERVAL_DAY, pending);
            XposedBridge.log("[Backup] 每日备份已调度 (凌晨2:00)");
        } catch (Throwable t) {
            XposedBridge.log("[Backup] 调度失败: " + t.getMessage());
        }
    }

    private static void performBackup() {
        try {
            File dbFile = findDatabaseFile();
            if (dbFile == null || !dbFile.exists()) {
                XposedBridge.log("[Backup] 数据库文件不存在 (路径: " +
                        (dbFile != null ? dbFile.getAbsolutePath() : "null") + ")");
                return;
            }

            File backupDir = new File("/sdcard/WeChatPlus/backup/");
            backupDir.mkdirs();
            String name = "EnMicroMsg_" + sdf.format(new Date()) + ".db";
            File backupFile = new File(backupDir, name);

            FileInputStream fis = new FileInputStream(dbFile);
            FileOutputStream fos = new FileOutputStream(backupFile);
            byte[] buf = new byte[16384];
            int read;
            long total = 0;
            while ((read = fis.read(buf)) > 0) { fos.write(buf, 0, read); total += read; }
            fos.flush(); fos.close(); fis.close();

            XposedBridge.log("[Backup] ✅ 备份完成: " + backupFile.getName()
                    + " (" + formatSize(total) + ")");
            cleanupOldBackups(backupDir);
        } catch (Throwable t) {
            XposedBridge.log("[Backup] 备份失败: " + t.getMessage());
        }
    }

    /**
     * ⚠️ 修复: 双路径尝试
     *   /data/user/0/... (Android 11+) 优先
     *   /data/data/...   (旧 Android)  备用
     */
    private static File findDatabaseFile() {
        try {
            Context ctx = ContextManager.getAppContext();
            if (ctx == null) return null;

            long uin = ctx.getSharedPreferences("system_config_prefs", 0)
                    .getLong("default_uin", 0);
            if (uin == 0) uin = ctx.getSharedPreferences("system_config_prefs", 0)
                    .getInt("default_uin", 0);
            if (uin == 0) return null;

            String hash = md5(String.valueOf(uin));

            // 优先 /data/user/0/, 备用 /data/data/
            String[] paths = {
                "/data/user/0/com.tencent.mm/MicroMsg/" + hash + "/EnMicroMsg.db",
                "/data/data/com.tencent.mm/MicroMsg/" + hash + "/EnMicroMsg.db"
            };

            for (String path : paths) {
                File f = new File(path);
                if (f.exists()) {
                    XposedBridge.log("[Backup] 找到DB: " + path);
                    return f;
                }
            }
            XposedBridge.log("[Backup] 所有路径都不存在: " + paths[0]);
        } catch (Throwable t) {}
        return null;
    }

    private static void cleanupOldBackups(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - (RETENTION_DAYS * 86400000L);
        int deleted = 0;
        for (File f : files) {
            if (f.lastModified() < cutoff && f.delete()) deleted++;
        }
        if (deleted > 0) XposedBridge.log("[Backup] 清理了 " + deleted + " 个旧备份");
    }

    private static String md5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }
}
