package com.leshao.v3;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

import de.robv.android.xposed.XposedBridge;

public class LogWriter {

    private static final String LOG_FILE = "leshao_v3_log.txt";
    private static final long MAX_SIZE = 512 * 1024;
    private static final int FLUSH_INTERVAL_MS = 2000;
    private static volatile boolean ready = false;
    private static volatile File logFile;
    // 有界队列：写盘失败时丢弃新日志而非无限堆积内存
    private static final LinkedBlockingQueue<String> sQueue = new LinkedBlockingQueue<>(2000);
    private static volatile boolean sWriterRunning = false;

    private static final long HEARTBEAT_INTERVAL_MS = 20 * 1000;

    public static void init() {
        if (ready) return;
        try {
            File rootDir = PathUtil.getLeshaoRootDir();
            XposedBridge.log("LeShaoV3: [LogWriter] dir=" + rootDir.getAbsolutePath()
                + " exists=" + rootDir.exists()
                + " isDir=" + rootDir.isDirectory()
                + " readable=" + rootDir.canRead()
                + " writable=" + rootDir.canWrite()
                + " free=" + rootDir.getFreeSpace());

            logFile = new File(rootDir, LOG_FILE);
            XposedBridge.log("LeShaoV3: [LogWriter] logFile=" + logFile.getAbsolutePath());

            ready = true;
            startWriterThread();
            startHeartbeat();

            log("LogWriter", "=== STARTUP === logFile=" + logFile.getAbsolutePath());
            XposedBridge.log("LeShaoV3: [LogWriter] init DONE, ready=true");
        } catch (Throwable t) {
            XposedBridge.log("LeShaoV3: [LogWriter] init CRASH: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** 心跳日志: 每 20s 写一行, 用于肉眼区分「进程冻结/日志断流」与「正常无消息」。 */
    private static void startHeartbeat() {
        Thread t = new Thread(() -> {
            int cnt = 0;
            while (sWriterRunning) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                try {
                    long free = logFile == null ? -1 : logFile.getFreeSpace();
                    cnt++;
                    logSync("LogWriter", "心跳 alive cnt=" + cnt
                        + " queue=" + sQueue.size()
                        + " free=" + (free < 0 ? "na" : (free / 1024) + "KB"));
                } catch (Throwable ignored) {
                }
            }
        }, "leshao-heartbeat");
        t.setDaemon(true);
        t.start();
    }

    private static void startWriterThread() {
        if (sWriterRunning) return;
        sWriterRunning = true;
        new Thread(() -> {
            BufferedWriter bw = null;
            try {
                while (sWriterRunning) {
                    try {
                        String line = sQueue.poll();
                        if (line == null) {
                            if (bw != null) { bw.flush(); }
                            Thread.sleep(FLUSH_INTERVAL_MS);
                            continue;
                        }
                        if (bw == null && logFile != null) bw = openWriter(logFile);
                        if (bw != null) { bw.write(line); bw.newLine(); }
                    } catch (Throwable t) {
                        XposedBridge.log("LeShaoV3: [LogWriter] write IO err: " + t.getClass().getName() + ": " + t.getMessage());
                        try { if (bw != null) bw.close(); } catch (Throwable ignored) {}
                        bw = null;
                    }
                }
            } finally {
                try { if (bw != null) bw.close(); } catch (Throwable ignored) {}
            }
        }, "leshao-log-writer").start();
    }

    private static BufferedWriter openWriter(File f) {
        try {
            if (!f.getParentFile().exists()) {
                boolean ok = f.getParentFile().mkdirs();
                XposedBridge.log("LeShaoV3: [LogWriter] openWriter mkdirs "
                    + f.getParentFile().getAbsolutePath() + " ok=" + ok);
            }
            if (f.exists() && f.length() > MAX_SIZE) {
                File bak = new File(f.getParent(), f.getName() + ".bak");
                bak.delete();
                f.renameTo(bak);
            }
            return new BufferedWriter(new FileWriter(f, true), 8192);
        } catch (Throwable t) {
            XposedBridge.log("LeShaoV3: [LogWriter] openWriter FAILED: " + f.getAbsolutePath()
                + " " + t.getClass().getName() + ": " + t.getMessage());
            return null;
        }
    }

    public static void log(String tag, String msg) {
        String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String thread = Thread.currentThread().getName();
        String line = ts + " [" + thread + "] " + tag + ": " + msg;
        try { XposedBridge.log("LeShaoV3: " + tag + ": " + msg); } catch (Throwable ignored) {}
        writeLine(line);
    }

    private static void writeLine(String line) {
        if (!ready || logFile == null) return;
        sQueue.offer(line);
    }

    /** 崩溃等场景同步直写落盘，绕过异步队列，保证进程被杀前日志已持久化 */
    public static void logSync(String tag, String msg) {
        try {
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            String thread = Thread.currentThread().getName();
            String line = ts + " [" + thread + "] " + tag + ": " + msg;
            try { XposedBridge.log("LeShaoV3: " + tag + ": " + msg); } catch (Throwable ignored) {}
            if (logFile == null) return;
            java.io.FileWriter fw = null;
            try {
                if (logFile.exists() && logFile.length() > MAX_SIZE) {
                    File bak = new File(logFile.getParent(), logFile.getName() + ".bak");
                    bak.delete();
                    logFile.renameTo(bak);
                }
                fw = new java.io.FileWriter(logFile, true);
                fw.write(line);
                fw.write('\n');
            } finally {
                if (fw != null) { try { fw.flush(); } catch (Throwable ignored) {} try { fw.close(); } catch (Throwable ignored) {} }
            }
        } catch (Throwable ignored) {}
    }
}
