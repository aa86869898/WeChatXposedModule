package com.leshao.v3;

import android.os.Environment;
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
    private static boolean ready = false;
    private static File logFile;
    private static File extLogFile;
    private static final LinkedBlockingQueue<String> sQueue = new LinkedBlockingQueue<>();
    private static volatile boolean sWriterRunning = false;

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

            try {
                File extDir = new File(Environment.getExternalStorageDirectory(), "leshao_v3_logs");
                if (!extDir.exists()) extDir.mkdirs();
                extLogFile = new File(extDir, LOG_FILE);
                XposedBridge.log("LeShaoV3: [LogWriter] extLogFile=" + extLogFile.getAbsolutePath()
                    + " exists=" + extLogFile.getParentFile().exists());
            } catch (Throwable t) {
                XposedBridge.log("LeShaoV3: [LogWriter] extLogFile FAILED: " + t.getClass().getName() + ": " + t.getMessage());
            }

            ready = true;
            startWriterThread();

            log("LogWriter", "=== STARTUP === logFile=" + logFile.getAbsolutePath());
            XposedBridge.log("LeShaoV3: [LogWriter] init DONE, ready=true");
        } catch (Throwable t) {
            XposedBridge.log("LeShaoV3: [LogWriter] init CRASH: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private static void startWriterThread() {
        if (sWriterRunning) return;
        sWriterRunning = true;
        new Thread(() -> {
            BufferedWriter bw = null, bw2 = null;
            try {
                while (sWriterRunning) {
                    try {
                        String line = sQueue.poll();
                        if (line == null) {
                            if (bw != null) { bw.flush(); }
                            if (bw2 != null) { bw2.flush(); }
                            Thread.sleep(FLUSH_INTERVAL_MS);
                            continue;
                        }
                        if (bw == null && logFile != null) bw = openWriter(logFile);
                        if (bw2 == null && extLogFile != null) bw2 = openWriter(extLogFile);
                        if (bw != null) { bw.write(line); bw.newLine(); }
                        if (bw2 != null) { bw2.write(line); bw2.newLine(); }
                    } catch (Throwable t) {
                        XposedBridge.log("LeShaoV3: [LogWriter] write IO err: " + t.getClass().getName() + ": " + t.getMessage());
                        try { if (bw != null) bw.close(); } catch (Throwable ignored) {}
                        try { if (bw2 != null) bw2.close(); } catch (Throwable ignored) {}
                        bw = null; bw2 = null;
                    }
                }
            } finally {
                try { if (bw != null) bw.close(); } catch (Throwable ignored) {}
                try { if (bw2 != null) bw2.close(); } catch (Throwable ignored) {}
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
        writeLineSync(line);
    }

    private static synchronized void writeLineSync(String line) {
        if (!ready || logFile == null) return;
        try {
            BufferedWriter bw = openWriter(logFile);
            bw.write(line);
            bw.newLine();
            bw.flush();
            bw.close();
            if (extLogFile != null) {
                BufferedWriter bw2 = openWriter(extLogFile);
                bw2.write(line);
                bw2.newLine();
                bw2.flush();
                bw2.close();
            }
        } catch (Throwable t) {
            XposedBridge.log("LeShaoV3: [LogWriter] sync write err: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private static void writeLine(String line) {
        if (!ready || logFile == null) return;
        sQueue.offer(line);
    }
}
