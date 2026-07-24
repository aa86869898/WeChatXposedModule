package com.leshao.v3;

/**
 * 统一日志工具 (底层委托 LogWriter)
 */
public class Logger {
    public static boolean debug = true;

    public static void i(String msg) {
        if (!debug) return;
        LogWriter.log("LeShaoV3", msg);
    }

    public static void e(String msg) {
        LogWriter.log("LeShaoV3", "[E] " + msg);
    }

    public static void e(String msg, Throwable t) {
        LogWriter.log("LeShaoV3", "[E] " + msg + " -> " + t.getMessage());
    }

    public static void w(String msg) {
        LogWriter.log("LeShaoV3", "[W] " + msg);
    }
}
