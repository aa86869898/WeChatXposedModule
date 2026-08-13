package com.leshao.v3.ai;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AiLog {
    private static final String TAG = "LeShaoAI";
    private static final String LOG_DIR = "/data/data/com.leshao.v3/files";
    private static final String LOG_FILE = "leshao_ai_log.txt";

    public static void i(String msg) { log("I", msg); }
    public static void d(String msg) { log("D", msg); }
    public static void w(String msg) { log("W", msg); }
    public static void e(String msg) { log("E", msg); }
    public static void e(String msg, Throwable t) {
        log("E", msg + " -> " + android.util.Log.getStackTraceString(t));
    }

    private static synchronized void log(String level, String msg) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " [" + Thread.currentThread().getName() + "] [" + level + "] " + msg;
        android.util.Log.e(TAG, line);
        try {
            File dir = new File(LOG_DIR);
            if (dir.exists() || dir.mkdirs()) {
                FileWriter fw = new FileWriter(new File(dir, LOG_FILE), true);
                fw.write(line + "\n");
                fw.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
