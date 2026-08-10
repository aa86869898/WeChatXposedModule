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

    private static String logRoot() {
        Context ctx = ContextManager.getAppContext();
        String base = ctx != null ? ctx.getFilesDir().getAbsolutePath()
            : "/data/data/com.tencent.mm/files";
        return base + "/leshao_v3_logs";
    }
    private static String triggerFile() { return logRoot() + "/trigger_backup"; }
    private static String restoreTrigger() { return logRoot() + "/trigger_restore"; }
    private static String statusFile() { return logRoot() + "/last_backup_status.json"; }
    private static String backupDir() { return logRoot() + "/backup/"; }

    private static String lastBackupDate = "";
    private static ClassLoader sCL;
    private static long sUin = -1;
    private static Handler sMainHandler;

    /** 备份进度回调。onProgress/onDone 均在后台线程触发,UI 侧需自行切主线程。 */
    public interface ProgressListener {
        void onProgress(int percent);
        void onDone(boolean ok, String backupDir, long totalSize);
    }

    private static volatile ProgressListener sProgressListener;

    /** 设置手动备份进度监听,备份完成后自动清除。 */
    public static void setProgressListener(ProgressListener l) {
        sProgressListener = l;
    }

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
            if (launcherUI == null) {
                launcherUI = VersionCompat.findClassMulti(cl,
                    "com.tencent.mm.ui.MMLauncherUI",
                    "com.tencent.mm.ui.LauncherUIActivity",
                    "com.tencent.mm.ui.MainTabUI");
            }
            if (launcherUI == null) return;

            XposedBridge.hookAllMethods(launcherUI, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sMainHandler.postDelayed(() -> {
                        // 备份涉及大文件复制，放子线程避免阻塞主线程
                        new Thread(() -> {
                            try { checkAndPerformBackup(); } catch (Throwable ignored) {}
                        }, "leshao-backup").start();
                    }, 8000);
                }
            });

            XposedBridge.hookAllMethods(launcherUI, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    new Thread(() -> {
                        try { checkAndPerformBackup(); } catch (Throwable ignored) {}
                    }, "leshao-backup-exit").start();
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookAppExit err: " + t.getClass().getSimpleName());
        }
    }

    private static void checkAndPerformBackup() {
        checkRestoreTrigger();
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

    private static void checkRestoreTrigger() {
        try {
            File trigger = new File(restoreTrigger());
            if (!trigger.exists()) return;
            StringBuilder content = new StringBuilder();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(trigger));
            try {
            String line;
            while ((line = br.readLine()) != null) content.append(line);
            } finally { br.close(); }
            trigger.delete();

            String path = content.toString().trim();
            if (path.isEmpty()) return;
            File src = new File(path);
            if (!src.exists() || !src.isFile()) {
                LogWriter.log(TAG, "restore src not found: " + path);
                final String err = "恢复失败: 源文件不存在";
                sMainHandler.post(() -> {
                    try {
                        Context ctx = ContextManager.getAppContext();
                        if (ctx != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {}
                });
                return;
            }

            boolean ok = performRestore(src);
            final String msg = ok ? "聊天记录恢复完成,请重新打开微信" : "恢复失败,请检查文件";
            sMainHandler.post(() -> {
                try {
                    Context ctx = ContextManager.getAppContext();
                    if (ctx != null) Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {}
            });
            LogWriter.log(TAG, "restore " + (ok ? "ok" : "fail") + " from " + src.getName());
        } catch (Throwable t) {
            LogWriter.log(TAG, "restore trigger err: " + t.getMessage());
        }
    }

    private static boolean checkManualTrigger() {
        try {
            File trigger = new File(triggerFile());
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
        return performBackup(null);
    }

    private static boolean performBackup(final ProgressListener listener) {
        try {
            File dbDir = findDbDirectory();
            if (dbDir == null || !dbDir.exists()) {
                LogWriter.log(TAG, "DB directory not found");
                return false;
            }

            File backupDir = new File(backupDir());
            if (!backupDir.exists() && !backupDir.mkdirs()) {
                LogWriter.log(TAG, "backup err: cannot create dir " + backupDir.getAbsolutePath());
                return false;
            }
            String timeStr = sdf.format(new Date());

            String[] dbs = {"EnMicroMsg.db", "EnMicroMsg.db-wal", "EnMicroMsg.db-shm"};
            long total = 0;
            for (String dbName : dbs) {
                File src = new File(dbDir, dbName);
                if (src.exists()) total += src.length();
            }

            int count = 0;
            long copied = 0;
            for (String dbName : dbs) {
                File src = new File(dbDir, dbName);
                if (!src.exists()) continue;

                File dest = new File(backupDir, dbName.replace(".db", "_") + timeStr
                    + dbName.substring(dbName.lastIndexOf('.')));
                FileInputStream fis = null;
                FileOutputStream fos = null;
                try {
                fis = new FileInputStream(src);
                fos = new FileOutputStream(dest);
                byte[] buf = new byte[16384];
                int read;
                while ((read = fis.read(buf)) > 0) {
                    fos.write(buf, 0, read);
                    copied += read;
                    if (listener != null && total > 0) {
                        listener.onProgress((int) (copied * 100 / total));
                    }
                }
                fos.flush();
                } finally {
                try { if (fos != null) fos.close(); } catch (Exception ignored) {}
                try { if (fis != null) fis.close(); } catch (Exception ignored) {}
                }
                count++;
            }

            LogWriter.log(TAG, "backup done " + count + " files " + formatSize(copied));
            cleanupOldBackups(backupDir);
            if (listener != null) listener.onDone(count > 0, backupDir.getAbsolutePath(), copied);
            return count > 0;
        } catch (Throwable t) {
            LogWriter.log(TAG, "backup err: " + t.getMessage());
            if (listener != null) listener.onDone(false, null, 0);
            return false;
        }
    }

    public static void triggerManualBackup() {
        triggerManualBackup(null);
    }

    public static void triggerManualBackup(final ProgressListener listener) {
        try {
            if (listener != null) sProgressListener = listener;
            new Thread(() -> {
                boolean ok = performBackup(sProgressListener);
                ProgressListener doneListener = sProgressListener;
                sProgressListener = null;
                final String msg = ok ? "聊天记录备份完成" : "备份失败,请检查存储权限";
                if (sMainHandler != null && doneListener == null) {
                    sMainHandler.post(() -> {
                        try {
                            Context ctx = ContextManager.getAppContext();
                            if (ctx != null) Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
                        } catch (Throwable ignored) {}
                    });
                }
                if (ok) {
                    lastBackupDate = dateSdf.format(new Date());
                    writeStatus("ok", formatSize(getBackupTotalSize()));
                } else {
                    writeStatus("fail", "DB not found");
                }
            }).start();
        } catch (Throwable t) {
            LogWriter.log(TAG, "triggerManualBackup err: " + t.getMessage());
        }
    }

    public static void triggerRestore(String backupPath) {
        try {
            new Thread(() -> {
                File src = new File(backupPath);
                if (!src.exists() || !src.isFile()) {
                    final String err = "恢复失败: 源文件不存在";
                    sMainHandler.post(() -> {
                        try {
                            Context ctx = ContextManager.getAppContext();
                            if (ctx != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show();
                        } catch (Throwable ignored) {}
                    });
                    return;
                }
                boolean ok = performRestore(src);
                final String msg = ok ? "聊天记录恢复完成,请重新打开微信" : "恢复失败,请检查文件";
                sMainHandler.post(() -> {
                    try {
                        Context ctx = ContextManager.getAppContext();
                        if (ctx != null) Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {}
                });
                LogWriter.log(TAG, "restore " + (ok ? "ok" : "fail") + " from " + src.getName());
            }).start();
        } catch (Throwable t) {
            LogWriter.log(TAG, "triggerRestore err: " + t.getMessage());
        }
    }

    private static boolean performRestore(File srcFile) {
        try {
            File dbDir = findDbDirectory();
            if (dbDir == null || !dbDir.exists()) {
                LogWriter.log(TAG, "restore: db dir not found");
                return false;
            }

            String srcName = srcFile.getName();
            String destName = "EnMicroMsg.db";
            if (srcName.endsWith(".db-wal")) destName = "EnMicroMsg.db-wal";
            else if (srcName.endsWith(".db-shm")) destName = "EnMicroMsg.db-shm";

            File dest = new File(dbDir, destName);
            FileInputStream fis = null;
            FileOutputStream fos = null;
            try {
            fis = new FileInputStream(srcFile);
            fos = new FileOutputStream(dest);
            byte[] buf = new byte[16384];
            int read;
            while ((read = fis.read(buf)) > 0) fos.write(buf, 0, read);
            fos.flush();
            } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}
            }
            LogWriter.log(TAG, "restored " + srcName + " -> " + dest.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "restore err: " + t.getMessage());
            return false;
        }
    }

    private static long getBackupTotalSize() {
        File dir = new File(backupDir());
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
            File dir = new File(statusFile()).getParentFile();
            if (dir != null) dir.mkdirs();
            FileWriter fw = new FileWriter(new File(statusFile()));
            try {
            fw.write("{\"status\":\"" + status + "\",\"detail\":\"" + detail
                + "\",\"time\":" + System.currentTimeMillis() + "}");
            } finally { fw.close(); }
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
