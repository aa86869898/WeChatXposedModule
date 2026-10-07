package com.leshao.v3

import android.content.Context
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.lang.reflect.Method

object PathUtil {

    @JvmStatic
    fun getLeshaoRootDir(wxContext: Context): File {
        val userId = getMyUserId()
        val baseDir = if (userId <= 0) {
            // v1131: userId 反射失败(-1)或主用户(0)时, 用进程自身 Context 的 filesDir
            // (Context 路径本身已按 user 隔离, 避免拼出 /data/user/-1 非法路径)
            wxContext.filesDir
        } else {
            wxContext.cacheDir
        }
        val rootDir = File(baseDir, "leshao_v3")
        ensureDir(rootDir)
        return rootDir
    }

    @JvmStatic
    fun getLeshaoRootDir(): File {
        val userId = getMyUserId()
        val baseDir = if (userId <= 0) {
            // v1131: -1 表示反射失败, 无 Context 时退化到 user0 路径(调用方 LogWriter 会在 -1 时跳过写入)
            File("/data/data/com.tencent.mm/files")
        } else {
            File("/data/user/$userId/com.tencent.mm/cache")
        }
        val rootDir = File(baseDir, "leshao_v3")
        ensureDir(rootDir)
        return rootDir
    }

    private fun ensureDir(dir: File) {
        try {
            if (!dir.exists()) {
                dir.mkdirs()
                XposedBridge.log("LeShaoV3: PathUtil mkdirs OK: " + dir.absolutePath)
            }
        } catch (e: Exception) {
            XposedBridge.log("LeShaoV3: PathUtil mkdirs FAIL: " + e.message)
        }
    }

    @JvmStatic
    fun getLogDirectory(wxContext: Context): File {
        val dir = File(getLeshaoRootDir(wxContext), "log")
        try {
            if (!dir.exists()) dir.mkdirs()
        } catch (_: Exception) {
        }
        return dir
    }

    @JvmStatic
    fun getMyUserId(): Int {
        return try {
            val cls = Class.forName("android.os.UserHandle")
            val m = cls.getMethod("myUserId")
            m.invoke(null) as Int
        } catch (e: Exception) {
            // v1131: 反射失败返回 -1(此前返回 0 会误导调用方写入 user0 目录)
            -1
        }
    }
}