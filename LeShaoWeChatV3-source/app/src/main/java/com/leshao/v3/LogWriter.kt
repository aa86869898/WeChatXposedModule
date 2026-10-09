package com.leshao.v3

import de.robv.android.xposed.XposedBridge
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object LogWriter {

    private const val LOG_FILE = "leshao_v3_log.txt"
    private const val MAX_SIZE = 512 * 1024
    private const val FLUSH_INTERVAL_MS = 2000L

    @Volatile
    private var ready = false

    @Volatile
    private var logFile: File? = null

    // 有界队列：写盘失败时丢弃新日志而非无限堆积内存
    private val sQueue = LinkedBlockingQueue<String>(2000)

    @Volatile
    private var sWriterRunning = false

    /** v1131: SimpleDateFormat 非线程安全, 每条 new 开销大 -> 每线程复用。 */
    private val SDF = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /** v1147: 写线程与 logSync 均逐行原子追加, 持锁仅用于串行化单进程内写盘。 */
    private val sLock = Any()

    @JvmStatic
    fun init() {
        if (ready) return
        try {
            // v1131: userId 反射失败(-1)时不落文件, 避免写入错误的 user 目录
            if (PathUtil.getMyUserId() < 0) {
                XposedBridge.log("LeShaoV3: [LogWriter] userId 未知(-1), 跳过文件日志")
                return
            }
            val rootDir = PathUtil.getLeshaoRootDir()
            XposedBridge.log("LeShaoV3: [LogWriter] dir=" + rootDir.absolutePath
                + " exists=" + rootDir.exists()
                + " isDir=" + rootDir.isDirectory
                + " readable=" + rootDir.canRead()
                + " writable=" + rootDir.canWrite()
                + " free=" + rootDir.freeSpace)

            logFile = File(rootDir, LOG_FILE)
            XposedBridge.log("LeShaoV3: [LogWriter] logFile=" + logFile!!.absolutePath)

            ready = true
            startWriterThread()

            log("LogWriter", "=== STARTUP === logFile=" + logFile!!.absolutePath)
            XposedBridge.log("LeShaoV3: [LogWriter] init DONE, ready=true")
        } catch (t: Throwable) {
            XposedBridge.log("LeShaoV3: [LogWriter] init CRASH: " + t.javaClass.name + ": " + t.message)
        }
    }

    private fun startWriterThread() {
        if (sWriterRunning) return
        sWriterRunning = true
        val t = Thread({
            try {
                while (sWriterRunning) {
                    val line: String?
                    try {
                        line = sQueue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        break
                    }
                    synchronized(sLock) {
                        try {
                            if (line != null) writeLocked(line)
                        } catch (ex: Throwable) {
                            XposedBridge.log("LeShaoV3: [LogWriter] write IO err: "
                                + ex.javaClass.name + ": " + ex.message)
                        }
                    }
                }
            } finally {
                synchronized(sLock) { /* 无长期持有的 writer, 无需关闭 */ }
            }
        }, "leshao-log-writer")
        t.isDaemon = true
        t.start()
    }

    /**
     * 写前判长度: 超限先 rename, 再 append 单行。
     * v1147: 每次写独立打开 FileWriter append 一行(单次 O_APPEND write 原子),
     * 避免多进程各自持 BufferedWriter 攒批导致的行交错/截断。
     */
    private fun writeLocked(line: String) {
        val lf = logFile ?: return
        rotateIfNeededLocked()
        var fw: FileWriter? = null
        try {
            fw = FileWriter(lf, true)
            fw.write(line)
            fw.write('\n'.code)
        } finally {
            try { fw?.flush() } catch (ignored: Throwable) {}
            try { fw?.close() } catch (ignored: Throwable) {}
        }
    }

    private fun rotateIfNeededLocked() {
        try {
            val lf = logFile ?: return
            if (!lf.exists() || lf.length() <= MAX_SIZE) return
            val bak = File(lf.parent, lf.name + ".bak")
            if (bak.exists()) bak.delete()
            if (lf.renameTo(bak)) {
                XposedBridge.log("LeShaoV3: [LogWriter] rotated -> " + bak.name)
            }
        } catch (ignored: Throwable) {}
    }

    // v1088: 默认不再把每条日志同步镜像到 logcat(XposedBridge.log 会同步写 logd,
    // 高频日志是主线程卡顿来源之一)。文件日志仍异步写入, 诊断不受影响。
    private const val MIRROR_TO_LOGCAT = false

    @JvmStatic
    fun log(tag: String, msg: String) {
        val ts = SDF.get().format(Date())
        val thread = Thread.currentThread().name
        val line = ts + " [" + thread + "] " + tag + ": " + msg
        if (MIRROR_TO_LOGCAT) {
            try { XposedBridge.log("LeShaoV3: " + tag + ": " + msg) } catch (ignored: Throwable) {}
        }
        writeLine(line)
    }

    private fun writeLine(line: String) {
        if (!ready || logFile == null) return
        sQueue.offer(line)
    }

    /**
     * 崩溃等场景同步直写落盘, 绕过异步队列, 保证进程被杀前日志已持久化。
     * v1131: 只做追加, 不再 rename/delete(轮转统一交给写线程), 避免删除正在写的 inode。
     */
    @JvmStatic
    fun logSync(tag: String, msg: String) {
        try {
            val ts = SDF.get().format(Date())
            val thread = Thread.currentThread().name
            val line = ts + " [" + thread + "] " + tag + ": " + msg
            try { XposedBridge.log("LeShaoV3: " + tag + ": " + msg) } catch (ignored: Throwable) {}
            synchronized(sLock) {
                val lf = logFile ?: return
                var fw: FileWriter? = null
                try {
                    fw = FileWriter(lf, true)
                    fw.write(line)
                    fw.write('\n'.code)
                } finally {
                    if (fw != null) {
                        try { fw.flush() } catch (ignored: Throwable) {}
                        try { fw.close() } catch (ignored: Throwable) {}
                    }
                }
            }
        } catch (ignored: Throwable) {}
    }
}