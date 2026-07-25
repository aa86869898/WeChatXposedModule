package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ChatBackup {
    private static final String TAG = "ChatBackup";
    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
    private static final SimpleDateFormat dateSdf = new SimpleDateFormat("yyyyMMdd");
    private static final int RETENTION_DAYS = 7;
    private static final String TRIGGER_FILE = "/sdcard/leshao_v3_logs/trigger_backup";
    private static final String STATUS_FILE = "/sdcard/leshao_v3_logs/last_backup_status.json";

    private static String lastBackupDate = "";
    private static ClassLoader sCL;
    private static long sUin = -1;
    private static Handler sMainHandler;

    public static void hook(ClassLoader cl) {
        sCL = cl;
        sMainHandler = new Handler(Looper.getMainLooper());
        if (!sEnabled) return;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.chatBackupEnabled) return;

        hookAppExit(cl);
        scheduleDailyBackup();
        LogWriter.log(TAG, "hook OK");
    }

    private static void hookAppExit(ClassLoader cl) {
        try {
            Class<?> launcherUI = VersionCompat.findLauncherUIClass(cl);
            if (launcherUI == null) return;

            XposedBridge.hookAllMethods(launcherUI, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sMainHandler.postDelayed(() -> {
                        checkAndPerformBackup();
                    }, 8000);
                }
            });

            XposedBridge.hookAllMethods(launcherUI, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    checkAndPerformBackup();
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookAppExit err: " + t.getClass().getSimpleName());
        }
    }

    private static void checkAndPerformBackup() {
        boolean manual = checkManualTrigger();
        String today = dateSdf.format(new Date());
        if (!today.equals(lastBackupDate) || manual) {
            boolean ok = performBackup();
            if (ok) {
                lastBackupDate = today;
                writeStatus("ok", formatSize(getBackupTotalSize()));
            } else {
                writeStatus("fail", "DB not found");
            }
            if (manual) {
                final String msg = ok ? "聊天记录备份完成" : "备份失败,请检查存储权限";
                sMainHandler.post(() -> {
                    try {
                        Context ctx = ContextManager.getAppContext();
                        if (ctx != null) Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {}
                });
            }
        }
    }

    private static boolean checkManualTrigger() {
        try {
            File trigger = new File(TRIGGER_FILE);
            if (trigger.exists()) {
                boolean deleted = trigger.delete();
                LogWriter.log(TAG, "manual trigger detected, deleted=" + deleted);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void scheduleDailyBackup() {
        try {
            Context ctx = ContextManager.getAppContext();
            if (ctx == null) return;

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    checkAndPerformBackup();
                }
            };

            try {
                ctx.registerReceiver(receiver,
                    new IntentFilter("com.leshao.v3.BACKUP_ALARM"),
                    Context.RECEIVER_NOT_EXPORTED);
            } catch (Throwable ignored) {}

            AlarmManager alarmMgr = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent("com.leshao.v3.BACKUP_ALARM");
            intent.setPackage(ctx.getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(ctx, 9999, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 2);
            calendar.set(java.util.Calendar.MINUTE, 0);
            calendar.set(java.util.Calendar.SECOND, 0);
            if (calendar.before(java.util.Calendar.getInstance()))
                calendar.add(java.util.Calendar.DAY_OF_MONTH, 1);

            alarmMgr.setRepeating(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(),
                    AlarmManager.INTERVAL_DAY, pending);
            LogWriter.log(TAG, "daily backup scheduled at 02:00");
        } catch (Throwable t) {
            LogWriter.log(TAG, "schedule err: " + t.getClass().getSimpleName());
        }
    }

    private static boolean performBackup() {
        try {
            File dbDir = findDbDirectory();
            if (dbDir == null || !dbDir.exists()) {
                LogWriter.log(TAG, "DB directory not found");
                return false;
            }

            File backupDir = new File("/sdcard/leshao_v3_logs/backup/");
            backupDir.mkdirs();
            String timeStr = sdf.format(new Date());

            String[] dbs = {"EnMicroMsg.db", "EnMicroMsg.db-wal", "EnMicroMsg.db-shm"};
            int count = 0;
            long totalSize = 0;

            for (String dbName : dbs) {
                File src = new File(dbDir, dbName);
                if (!src.exists()) continue;

                File dest = new File(backupDir, dbName.replace(".db", "_") + timeStr
                    + dbName.substring(dbName.lastIndexOf('.')));
                FileInputStream fis = new FileInputStream(src);
                FileOutputStream fos = new FileOutputStream(dest);
                byte[] buf = new byte[16384];
                int read;
                while ((read = fis.read(buf)) > 0) { fos.write(buf, 0, read); totalSize += read; }
                fos.flush(); fos.close(); fis.close();
                count++;
            }

            LogWriter.log(TAG, "backup done " + count + " files " + formatSize(totalSize));
            cleanupOldBackups(backupDir);
            return count > 0;
        } catch (Throwable t) {
            LogWriter.log(TAG, "backup err: " + t.getMessage());
            return false;
        }
    }

    public static void triggerManualBackup() {
        try {
            File triggerDir = new File(TRIGGER_FILE).getParentFile();
            if (triggerDir != null) triggerDir.mkdirs();
            new FileWriter(TRIGGER_FILE).close();
            LogWriter.log(TAG, "manual trigger written");
        } catch (Throwable t) {
            LogWriter.log(TAG, "trigger write err: " + t.getMessage());
        }
    }

    private static long getBackupTotalSize() {
        File dir = new File("/sdcard/leshao_v3_logs/backup/");
        if (!dir.exists()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        long total = 0;
        for (File f : files) {
            if (f.isFile() && f.lastModified() > System.currentTimeMillis() - 3600000) {
                total += f.length();
            }
        }
        return total;
    }

    private static void writeStatus(String status, String detail) {
        try {
            File dir = new File(STATUS_FILE).getParentFile();
            if (dir != null) dir.mkdirs();
            FileWriter fw = new FileWriter(new File(STATUS_FILE));
            fw.write("{\"status\":\"" + status + "\",\"detail\":\"" + detail
                + "\",\"time\":" + System.currentTimeMillis() + "}");
            fw.close();
        } catch (Throwable ignored) {}
    }

    private static File findDbDirectory() {
        try {
            Context ctx = ContextManager.getAppContext();
            if (ctx == null) return null;

            long uin = getUin(ctx);
            if (uin <= 0) return null;

            String hash = getDbHash((int) uin);

            String[] paths = {
                "/data/user/0/com.tencent.mm/MicroMsg/" + hash + "/",
                "/data/data/com.tencent.mm/MicroMsg/" + hash + "/"
            };

            for (String path : paths) {
                File dir = new File(path);
                File db = new File(dir, "EnMicroMsg.db");
                if (db.exists()) {
                    LogWriter.log(TAG, "found DB at " + path);
                    return dir;
                }
            }

            String base = getBaseDir(ctx);
            if (base != null) {
                File dir = new File(base, "MicroMsg/" + hash + "/");
                File db = new File(dir, "EnMicroMsg.db");
                if (db.exists()) return dir;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "findDb err: " + t.getClass().getSimpleName());
        }
        return null;
    }

    private static long getUin(Context ctx) {
        if (sUin > 0) return sUin;
        try {
            SharedPreferences sp = ctx.getSharedPreferences("system_config_prefs", 0);
            Object uv = sp.getAll().get("default_uin");
            if (uv != null) {
                sUin = Long.parseLong(uv.toString());
                return sUin;
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static String getBaseDir(Context ctx) {
        return VersionCompat.getBaseDir(sCL, ctx);
    }

    private static String getDbHash(int uin) {
        return VersionCompat.getDbHash(sCL, uin);
    }

    private static void cleanupOldBackups(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - (RETENTION_DAYS * 86400000L);
        int deleted = 0;
        for (File f : files) {
            if (f.lastModified() < cutoff && f.delete()) deleted++;
        }
        if (deleted > 0) LogWriter.log(TAG, "cleaned " + deleted + " old backups");
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }
}
