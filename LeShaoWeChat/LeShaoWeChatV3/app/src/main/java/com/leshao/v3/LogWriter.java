package com.leshao.v3;

import android.os.Environment;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

public class LogWriter {

    private static final String LOG_DIR = "/data/data/com.tencent.mm/files/leshao_v3";
    private static final String LOG_DIR_EXT = "/sdcard/leshao_v3_logs";
    private static final String LOG_FILE = "leshao_v3_log.txt";
    private static final long MAX_SIZE = 512 * 1024;
    private static boolean ready = false;
    private static File logFile;

    public static void init() {
        if (ready) return;
        try {
            File dir = new File(LOG_DIR);
            dir.mkdirs();
            logFile = new File(dir, LOG_FILE);
            // 同时写入外部存储方便无 root 查看
            try { new File(LOG_DIR_EXT).mkdirs(); } catch (Throwable ignored) {}
            ready = true;
            log("LogWriter", "init OK, path=" + logFile.getAbsolutePath() + " ext=" + LOG_DIR_EXT);
        } catch (Throwable t) {
            XposedBridge.log("LeShaoV3: LogWriter init FAILED: " + t.getMessage());
        }
    }

    public static void log(String tag, String msg) {
        String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String thread = Thread.currentThread().getName();
        String line = ts + " [" + thread + "] " + tag + ": " + msg;
        XposedBridge.log("LeShaoV3: " + tag + ": " + msg);
        writeLine(line);
    }

    private static void writeLine(String line) {
        if (!ready || logFile == null) return;
        try {
            if (logFile.exists() && logFile.length() > MAX_SIZE) {
                File bak = new File(logFile.getParent(), LOG_FILE + ".bak");
                bak.delete();
                logFile.renameTo(bak);
            }
            PrintWriter pw = new PrintWriter(new FileWriter(logFile, true));
            pw.println(line);
            pw.close();
        } catch (Throwable ignored) {}
        // 同时写入外部存储
        try {
            File extFile = new File(LOG_DIR_EXT, LOG_FILE);
            if (extFile.exists() && extFile.length() > MAX_SIZE) {
                File bak = new File(LOG_DIR_EXT, LOG_FILE + ".bak");
                bak.delete();
                extFile.renameTo(bak);
            }
            PrintWriter pw2 = new PrintWriter(new FileWriter(extFile, true));
            pw2.println(line);
            pw2.close();
        } catch (Throwable ignored) {}
    }
}
