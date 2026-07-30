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

    private static final String LOG_DIR = "/data/data/com.tencent.mm/files/leshao_v3";
    private static final String LOG_FILE = "music_log.txt";
    private static final long MAX_SIZE = 256 * 1024;
    private static boolean sReady;
    private static File sLogFile;

    public static synchronized void init(Context ctx) {
        if (sReady) return;
        try {
            File dir = new File(LOG_DIR);
            dir.mkdirs();
            sLogFile = new File(dir, LOG_FILE);
            sReady = true;
            Log.i("MusicLog", "init OK, path=" + sLogFile.getAbsolutePath());
            writeRaw("MusicLog init OK, path=" + sLogFile.getAbsolutePath());
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
        if (!sReady || sLogFile == null) return;
        writeToFile(level, tag, msg);
    }

    private static void writeRaw(String msg) {
        if (sLogFile == null) return;
        try {
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            PrintWriter pw = new PrintWriter(new FileWriter(sLogFile, true));
            pw.println(ts + " [main] " + msg);
            pw.close();
        } catch (Throwable ignored) {}
    }

    private static void writeToFile(String level, String tag, String msg) {
        try {
            if (sLogFile.exists() && sLogFile.length() > MAX_SIZE) {
                File bak = new File(sLogFile.getParent(), LOG_FILE + ".bak");
                bak.delete();
                sLogFile.renameTo(bak);
            }
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            String thread = Thread.currentThread().getName();
            String line = ts + " [" + thread + "] " + level + "/" + tag + ": " + msg;
            PrintWriter pw = new PrintWriter(new FileWriter(sLogFile, true));
            pw.println(line);
            pw.close();
        } catch (Throwable ignored) {}
    }

    public static String logPath() {
        if (sLogFile != null) return sLogFile.getAbsolutePath();
        return LOG_DIR + "/" + LOG_FILE;
    }
}
