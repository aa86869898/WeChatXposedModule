package com.leshao.v3;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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

    /** v1131: SimpleDateFormat 非线程安全, 每条 new 开销大 -> 每线程复用。 */
    private static final ThreadLocal<SimpleDateFormat> SDF =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US));

    /** v1131: 轮转/写入由唯一写线程持锁完成; logSync 仅追加, 不再 rename/delete。 */
    private static final Object sLock = new Object();
    private static BufferedWriter sWriter;

    public static void init() {
        if (ready) return;
        try {
            // v1131: userId 反射失败(-1)时不落文件, 避免写入错误的 user 目录
            if (PathUtil.getMyUserId() < 0) {
                XposedBridge.log("LeShaoV3: [LogWriter] userId 未知(-1), 跳过文件日志");
                return;
            }
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
        Thread t = new Thread(() -> {
            try {
                while (sWriterRunning) {
                    String line;
                    try {
                        line = sQueue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        break;
                    }
                    synchronized (sLock) {
                        try {
                            if (line == null) {
                                flushLocked();
                                continue;
                            }
                            writeLocked(line);
                        } catch (Throwable ex) {
                            XposedBridge.log("LeShaoV3: [LogWriter] write IO err: "
                                + ex.getClass().getName() + ": " + ex.getMessage());
                            closeLocked();
                        }
                    }
                }
            } finally {
                synchronized (sLock) { closeLocked(); }
            }
        }, "leshao-log-writer");
        t.setDaemon(true);
        t.start();
    }

    /** 写前判长度: 超限先关闭当前 writer 再 rename, 由唯一写线程完成, 避免删除正在写的 inode。 */
    private static void writeLocked(String line) throws Exception {
        if (logFile == null) return;
        if (sWriter != null && logFile.exists() && logFile.length() > MAX_SIZE) {
            closeLocked();
        }
        if (sWriter == null) {
            rotateIfNeededLocked();
            sWriter = openWriter(logFile);
        }
        if (sWriter != null) {
            sWriter.write(line);
            sWriter.newLine();
        }
    }

    private static void rotateIfNeededLocked() {
        try {
            if (logFile == null || !logFile.exists() || logFile.length() <= MAX_SIZE) return;
            File bak = new File(logFile.getParent(), logFile.getName() + ".bak");
            if (bak.exists()) bak.delete();
            if (logFile.renameTo(bak)) {
                XposedBridge.log("LeShaoV3: [LogWriter] rotated -> " + bak.getName());
            }
        } catch (Throwable ignored) {}
    }

    private static BufferedWriter openWriter(File f) {
        try {
            if (f.getParentFile() != null && !f.getParentFile().exists()) {
                boolean ok = f.getParentFile().mkdirs();
                XposedBridge.log("LeShaoV3: [LogWriter] openWriter mkdirs "
                    + f.getParentFile().getAbsolutePath() + " ok=" + ok);
            }
            return new BufferedWriter(new FileWriter(f, true), 8192);
        } catch (Throwable t) {
            XposedBridge.log("LeShaoV3: [LogWriter] openWriter FAILED: " + f.getAbsolutePath()
                + " " + t.getClass().getName() + ": " + t.getMessage());
            return null;
        }
    }

    private static void flushLocked() {
        if (sWriter != null) { try { sWriter.flush(); } catch (Throwable ignored) {} }
    }

    private static void closeLocked() {
        if (sWriter != null) {
            try { sWriter.flush(); } catch (Throwable ignored) {}
            try { sWriter.close(); } catch (Throwable ignored) {}
            sWriter = null;
        }
    }

    // v1088: 默认不再把每条日志同步镜像到 logcat(XposedBridge.log 会同步写 logd,
    // 高频日志是主线程卡顿来源之一)。文件日志仍异步写入, 诊断不受影响。
    private static final boolean MIRROR_TO_LOGCAT = false;

    public static void log(String tag, String msg) {
        String ts = SDF.get().format(new Date());
        String thread = Thread.currentThread().getName();
        String line = ts + " [" + thread + "] " + tag + ": " + msg;
        if (MIRROR_TO_LOGCAT) {
            try { XposedBridge.log("LeShaoV3: " + tag + ": " + msg); } catch (Throwable ignored) {}
        }
        writeLine(line);
    }

    private static void writeLine(String line) {
        if (!ready || logFile == null) return;
        sQueue.offer(line);
    }

    /**
     * 崩溃等场景同步直写落盘, 绕过异步队列, 保证进程被杀前日志已持久化。
     * v1131: 只做追加, 不再 rename/delete(轮转统一交给写线程), 避免删除正在写的 inode。
     */
    public static void logSync(String tag, String msg) {
        try {
            String ts = SDF.get().format(new Date());
            String thread = Thread.currentThread().getName();
            String line = ts + " [" + thread + "] " + tag + ": " + msg;
            try { XposedBridge.log("LeShaoV3: " + tag + ": " + msg); } catch (Throwable ignored) {}
            synchronized (sLock) {
                if (logFile == null) return;
                FileWriter fw = null;
                try {
                    fw = new FileWriter(logFile, true);
                    fw.write(line);
                    fw.write('\n');
                } finally {
                    if (fw != null) {
                        try { fw.flush(); } catch (Throwable ignored) {}
                        try { fw.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
}
