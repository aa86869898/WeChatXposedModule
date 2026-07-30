package com.leshao.v3.ui;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MusicLog {

    private static final String LOG_FILE = "music_log.txt";
    private static final long MAX_SIZE = 256 * 1024;
    private static boolean sReady;
    private static File sLogFile;
    private static String sExtDir;

    public static synchronized void init() {
        init((Context) null);
    }

    public static synchronized void init(Context ctx) {
        if (sReady) return;
        try {
            File bestFile = null;

            // 1. 首选: app内部文件目录(免权限,永远可用)
            if (ctx != null) {
                File internalDir = ctx.getFilesDir();
                if (internalDir != null && internalDir.exists()) {
                    bestFile = new File(internalDir, LOG_FILE);
                    i("MusicLog", "using internal: " + bestFile.getAbsolutePath());
                }
            }
            // 2. 兜底: 硬编码 WeChat 内部路径
            if (bestFile == null) {
                File fallbackDir = new File("/data/data/com.tencent.mm/files");
                fallbackDir.mkdirs();
                bestFile = new File(fallbackDir, LOG_FILE);
                i("MusicLog", "using fallback: " + bestFile.getAbsolutePath());
            }

            sLogFile = bestFile;
            sReady = true;

            // 同时尝试外部存储根目录(有权限时可用)
            try {
                File extDir = new File("/sdcard");
                File extFile = new File(extDir, LOG_FILE);
                if (extFile.exists() || extDir.canWrite()) {
                    sExtDir = "/sdcard";
                }
            } catch (Throwable ignored) {}

            write("I", "MusicLog", "init OK, primary=" + (sLogFile != null ? sLogFile.getAbsolutePath() : "null") + " ext=" + sExtDir);
        } catch (Throwable t) {
            Log.e("MusicLog", "init FAILED", t);
        }
    }

    public static void i(String tag, String msg) {
        write("I", tag, msg);
    }

    public static void e(String tag, String msg) {
        write("E", tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        String stack = t != null ? Log.getStackTraceString(t) : "";
        write("E", tag, msg + (stack.isEmpty() ? "" : "\n" + stack));
    }

    private static synchronized void write(String level, String tag, String msg) {
        Log.println(level.equals("E") ? Log.ERROR : Log.INFO, "Music:" + tag, msg);
        if (!sReady || sLogFile == null) init();
        if (!sReady || sLogFile == null) return;
        try {
            writeToFile(sLogFile, level, tag, msg);
            if (sExtDir != null) {
                writeToFile(new File(sExtDir, LOG_FILE), level, tag, msg);
            }
        } catch (Throwable ignored) {}
    }

    private static void writeToFile(File file, String level, String tag, String msg) {
        try {
            if (file.exists() && file.length() > MAX_SIZE) {
                File bak = new File(file.getParent(), LOG_FILE + ".bak");
                bak.delete();
                file.renameTo(bak);
            }
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            String thread = Thread.currentThread().getName();
            String line = ts + " [" + thread + "] " + level + "/" + tag + ": " + msg;
            PrintWriter pw = new PrintWriter(new FileWriter(file, true));
            pw.println(line);
            pw.close();
        } catch (Throwable ignored) {}
    }

    public static String logPath() {
        if (sLogFile != null) return sLogFile.getAbsolutePath();
        return "unknown";
    }
}
