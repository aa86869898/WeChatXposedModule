package com.leshao.v3;

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

    /** v1131: SimpleDateFormat 非线程安全, 每条 new 开销大 -> 每线程复用。 */
    private static final ThreadLocal<SimpleDateFormat> SDF =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US));

    /** v1147: 写线程与 logSync 均逐行原子追加, 持锁仅用于串行化单进程内写盘。 */
    private static final Object sLock = new Object();

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

            log("LogWriter", "=== STARTUP === logFile=" + logFile.getAbsolutePath());
            XposedBridge.log("LeShaoV3: [LogWriter] init DONE, ready=true");
        } catch (Throwable t) {
            XposedBridge.log("LeShaoV3: [LogWriter] init CRASH: " + t.getClass().getName() + ": " + t.getMessage());
        }
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
                            if (line != null) writeLocked(line);
                        } catch (Throwable ex) {
                            XposedBridge.log("LeShaoV3: [LogWriter] write IO err: "
                                + ex.getClass().getName() + ": " + ex.getMessage());
                        }
                    }
                }
            } finally {
                synchronized (sLock) { /* 无长期持有的 writer, 无需关闭 */ }
            }
        }, "leshao-log-writer");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 写前判长度: 超限先 rename, 再 append 单行。
     * v1147: 每次写独立打开 FileWriter append 一行(单次 O_APPEND write 原子),
     * 避免多进程各自持 BufferedWriter 攒批导致的行交错/截断。
     */
    private static void writeLocked(String line) throws Exception {
        if (logFile == null) return;
        rotateIfNeededLocked();
        FileWriter fw = new FileWriter(logFile, true);
        try {
            fw.write(line);
            fw.write('\n');
        } finally {
            try { fw.flush(); } catch (Throwable ignored) {}
            try { fw.close(); } catch (Throwable ignored) {}
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
