package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ChatBackup {

    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
    private static final int RETENTION_DAYS = 7;
    private static boolean hasBackedUpToday = false;
    private static String lastBackupDate = "";

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.chatBackupEnabled) return;

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
                        hasBackedUpToday = true;
                    }
                }
            });

            XposedBridge.hookAllMethods(launcherUI, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String today = new SimpleDateFormat("yyyyMMdd").format(new Date());
                    if (!today.equals(lastBackupDate) && !hasBackedUpToday) {
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(new Runnable() {
                            public void run() {
                                performBackup();
                                lastBackupDate = today;
                                hasBackedUpToday = true;
                            }
                        }, 5000);
                    }
                }
            });
        } catch (Throwable t) {}
    }

    private static void scheduleDailyBackup() {
        try {
            Context ctx = com.leshao.v3.ContextManager.getAppContext();
            if (ctx == null) return;

            AlarmManager alarmMgr = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent("com.leshao.v3.BACKUP");
            PendingIntent pending = PendingIntent.getBroadcast(ctx, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 2);
            calendar.set(java.util.Calendar.MINUTE, 0);
            calendar.set(java.util.Calendar.SECOND, 0);
            if (calendar.before(java.util.Calendar.getInstance())) {
                calendar.add(java.util.Calendar.DAY_OF_MONTH, 1);
            }

            alarmMgr.setRepeating(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(),
                    AlarmManager.INTERVAL_DAY, pending);

            Logger.i("[Backup] 每日备份已调度 (凌晨2:00)");
        } catch (Throwable t) {
            Logger.w("[Backup] 调度失败: " + t.getMessage());
        }
    }

    private static void performBackup() {
        try {
            File dbFile = findDatabaseFile();
            if (dbFile == null || !dbFile.exists()) {
                Logger.w("[Backup] 数据库文件不存在");
                return;
            }

            File backupDir = new File("/sdcard/LeShaoV3Logs/backup/");
            backupDir.mkdirs();

            String name = "EnMicroMsg_" + sdf.format(new Date()) + ".db";
            File backupFile = new File(backupDir, name);

            FileInputStream fis = new FileInputStream(dbFile);
            FileOutputStream fos = new FileOutputStream(backupFile);
            byte[] buf = new byte[16384];
            int read;
            long total = 0;
            while ((read = fis.read(buf)) > 0) {
                fos.write(buf, 0, read);
                total += read;
            }
            fos.flush();
            fos.close();
            fis.close();

            Logger.i("[Backup] 备份完成: " + backupFile.getName()
                    + " (" + formatSize(total) + ")");

            cleanupOldBackups(backupDir);
        } catch (Throwable t) {
            Logger.w("[Backup] 备份失败: " + t.getMessage());
        }
    }

    private static File findDatabaseFile() {
        try {
            Context ctx = com.leshao.v3.ContextManager.getAppContext();
            if (ctx == null) return null;

            android.content.SharedPreferences sp = ctx.getSharedPreferences(
                    "system_config_prefs", 0);
            long uin = sp.getLong("default_uin", 0);
            if (uin == 0) return null;

            String hash = md5(String.valueOf(uin));
            String dbPath = "/data/data/com.tencent.mm/MicroMsg/" + hash + "/EnMicroMsg.db";
            File f = new File(dbPath);
            return f.exists() ? f : null;
        } catch (Throwable t) {}
        return null;
    }

    private static void cleanupOldBackups(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - (RETENTION_DAYS * 86400000L);
        int deleted = 0;
        for (File f : files) {
            if (f.lastModified() < cutoff) {
                if (f.delete()) deleted++;
            }
        }
        if (deleted > 0) {
            Logger.i("[Backup] 清理了 " + deleted + " 个旧备份");
        }
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
