package com.leshao.v3

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashTrace {

    private const val TRACE_FILE = "crash_trace.txt"
    /** v1131: crash_trace.txt 大小上限, 超限轮转 .bak, 防止长跑无限增长 */
    private const val MAX_SIZE = 256L * 1024
    private const val SIZE_CHECK_INTERVAL_MS = 5000L
    private val SDF = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val LOCK = Any()

    private var sFile: File? = null
    private var sFos: FileOutputStream? = null
    private var sWriter: OutputStreamWriter? = null
    private var sInited = false
    private var sLastSizeCheck = 0L

    @JvmStatic
    fun t(tag: String) {
        try {
            synchronized(LOCK) {
                if (!sInited) {
                    sInited = true
                    val rootDir = PathUtil.getLeshaoRootDir()
                    sFile = File(rootDir, TRACE_FILE)
                    sFos = FileOutputStream(sFile, true)
                    sWriter = OutputStreamWriter(sFos, StandardCharsets.UTF_8)
                    sLastSizeCheck = System.currentTimeMillis()
                }
                val w = sWriter ?: return
                // v1131: 去掉每行 getFD().sync()(Hook 主线程回调里 fsync 严重拖慢), 仅 flush;
                // 并低频校验大小, 超限则轮转。
                val now = System.currentTimeMillis()
                if (now - sLastSizeCheck > SIZE_CHECK_INTERVAL_MS) {
                    sLastSizeCheck = now
                    rotateIfNeededLocked()
                }
                w.write(SDF.format(Date()) + " " + tag + "\n")
                w.flush()
            }
        } catch (_: Throwable) {
        }
    }

    private fun rotateIfNeededLocked() {
        try {
            val fos = sFos ?: return
            val file = sFile ?: return
            if (!file.exists() || file.length() <= MAX_SIZE) return
            try { sWriter?.flush() } catch (_: Throwable) {
            }
            try { fos.close() } catch (_: Throwable) {
            }
            val bak = File(file.parent, file.name + ".bak")
            if (bak.exists()) bak.delete()
            file.renameTo(bak)
            sFos = FileOutputStream(file, true)
            sWriter = OutputStreamWriter(sFos, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
        }
    }
}