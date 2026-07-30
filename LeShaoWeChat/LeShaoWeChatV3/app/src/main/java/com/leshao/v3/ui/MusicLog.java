package com.leshao.v3.ui;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MusicLog {

    private static String sPath;

    public static void init() {
        try {
            File d = new File("/data/data/com.tencent.mm/files/leshao_v3");
            if (!d.exists()) d.mkdirs();
            sPath = d.getAbsolutePath() + "/music_log.txt";
            raw("MusicLog init OK");
        } catch (Throwable t) {
            Log.e("MusicLog", "init err", t);
        }
    }

    public static void i(String tag, String msg) { w("I", tag, msg); }
    public static void e(String tag, String msg) { w("E", tag, msg); }
    public static void e(String tag, String msg, Throwable t) {
        String st = (t != null) ? Log.getStackTraceString(t) : "";
        w("E", tag, msg + "\n" + st);
    }

    private static synchronized void w(String lv, String tag, String msg) {
        Log.println("E".equals(lv) ? Log.ERROR : Log.INFO, "M:" + tag, msg);
        if (sPath == null) return;
        try {
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            PrintWriter pw = new PrintWriter(new FileWriter(sPath, true));
            pw.println(ts + " " + lv + "/" + tag + ": " + msg);
            pw.close();
        } catch (Throwable ignored) {}
    }

    private static synchronized void raw(String msg) {
        if (sPath == null) return;
        try {
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            PrintWriter pw = new PrintWriter(new FileWriter(sPath, true));
            pw.println(ts + " " + msg);
            pw.close();
        } catch (Throwable ignored) {}
    }

    public static String path() { return sPath; }
}
