package com.leshao.v3

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.widget.TextView
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader

object IconLoader {

    @JvmField
    val MODULE_PKG = "com.leshao.v3"

    @JvmField
    val IC_VOICE_FORWARD = 0

    @JvmField
    val IC_VOICE_LIST = 1

    @JvmField
    val IC_SCHEDULE_SEND = 2

    @JvmField
    val IC_LESHAO_GROUP = 3

    @JvmField
    val IC_LESHAO_ICON = 4

    private var sModuleApkPath: String? = null
    private var sModuleRes: Resources? = null

    @Keep
    private val KEEP_ICONS = intArrayOf(
        R.drawable.ic_voice_forward,
        R.drawable.ic_voice_list,
        R.drawable.ic_schedule_send,
        R.drawable.ic_leshao_group,
        R.drawable.ic_leshao_icon
    )

    @JvmStatic
    @Synchronized
    fun moduleApkPath(): String? {
        sModuleApkPath?.let { return it }

        try {
            val reader = BufferedReader(FileReader("/proc/self/maps"))
            var matchedCount = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.contains(MODULE_PKG)) {
                    matchedCount++
                    val idx = line.indexOf('/')
                    if (idx > 0) {
                        val path = line.substring(idx).trim()
                        LogWriter.log("IconLoader", "maps[$matchedCount]=$path")
                        if (path.endsWith(".apk")) {
                            sModuleApkPath = path
                            break
                        }
                    }
                }
            }
            reader.close()
            LogWriter.log("IconLoader", "maps total matching lines=$matchedCount")
        } catch (t: Throwable) {
            LogWriter.log("IconLoader", "maps fail: $t")
        }

        if (sModuleApkPath == null) {
            try {
                val ctx = ContextManager.getAppContext()
                if (ctx != null) {
                    val ai: ApplicationInfo? = ctx.packageManager.getApplicationInfo(MODULE_PKG, 0)
                    if (ai != null && ai.sourceDir != null) {
                        sModuleApkPath = ai.sourceDir
                        LogWriter.log("IconLoader", "pmApi found: $sModuleApkPath")
                    }
                }
            } catch (e: PackageManager.NameNotFoundException) {
                LogWriter.log("IconLoader", "pmApi NameNotFound: $MODULE_PKG")
            } catch (t: Throwable) {
                LogWriter.log("IconLoader", "pmApi fail: $t")
            }
        }

        if (sModuleApkPath == null) {
            try {
                val p = Runtime.getRuntime().exec("pm path $MODULE_PKG")
                val reader = BufferedReader(InputStreamReader(p.inputStream))
                val line = reader.readLine()
                reader.close()
                val errReader = BufferedReader(InputStreamReader(p.errorStream))
                val errLine = errReader.readLine()
                errReader.close()
                p.waitFor()
                val exitCode = p.exitValue()
                LogWriter.log("IconLoader", "pmPath exit=$exitCode stdout=$line stderr=$errLine")
                if (line != null && line.startsWith("package:")) {
                    sModuleApkPath = line.substring("package:".length).trim()
                }
            } catch (t: Throwable) {
                LogWriter.log("IconLoader", "pmPath exec fail: $t")
            }
        }

        if (sModuleApkPath == null) {
            try {
                scanDir(File("/data/app"))
            } catch (t: Throwable) {
                LogWriter.log("IconLoader", "scan fail: $t")
            }
        }

        LogWriter.log("IconLoader", "moduleApkPath=$sModuleApkPath")
        return sModuleApkPath
    }

    private fun scanDir(dir: File) {
        if (sModuleApkPath != null) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                if (f.name.contains(MODULE_PKG)) {
                    val baseApk = File(f, "base.apk")
                    if (baseApk.exists()) {
                        sModuleApkPath = baseApk.absolutePath
                        return
                    }
                }
                scanDir(f)
                if (sModuleApkPath != null) return
            }
        }
    }

    @JvmStatic
    @Synchronized
    fun moduleResources(hostCtx: Context?): Resources? {
        sModuleRes?.let { return it }
        try {
            val apk = moduleApkPath()
            if (apk == null) return null
            val ctor = AssetManager::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            val am = ctor.newInstance()
            val addPath = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
            addPath.isAccessible = true
            val cookie = addPath.invoke(am, apk) as Int
            if (cookie == 0) {
                LogWriter.log("IconLoader", "addAssetPath returned 0: $apk")
                return null
            }
            val dm = hostCtx?.resources?.displayMetrics ?: DisplayMetrics()
            val cfg = hostCtx?.resources?.configuration ?: Configuration()
            sModuleRes = Resources(am, dm, cfg)
            return sModuleRes
        } catch (t: Throwable) {
            LogWriter.log("IconLoader", "moduleResources fail: $t")
            return null
        }
    }

    @JvmStatic
    fun load(hostCtx: Context?, iconId: Int, sizeDp: Int): Drawable? {
        val res = moduleResources(hostCtx)
        if (res != null) {
            try {
                val resId = getResId(res, iconId)
                if (resId != 0) {
                    val d = res.getDrawable(resId)
                    if (d != null) {
                        val density = hostCtx?.resources?.displayMetrics?.density
                            ?: res.displayMetrics.density
                        val px = (sizeDp * density + 0.5f).toInt()
                        d.setBounds(0, 0, px, px)
                        return d
                    }
                }
            } catch (t: Throwable) {
                LogWriter.log("IconLoader", "res load fail id=$iconId : $t")
            }
        }
        val d = IconData.getDrawable(iconId)
        if (d != null) {
            val density = hostCtx?.resources?.displayMetrics?.density ?: 3.0f
            val px = (sizeDp * density + 0.5f).toInt()
            d.setBounds(0, 0, px, px)
            LogWriter.log("IconLoader", "loaded from IconData fallback id=$iconId")
            return d
        }
        LogWriter.log("IconLoader", "load FAILED id=$iconId")
        return null
    }

    @JvmStatic
    fun setCompoundLeft(tv: TextView?, d: Drawable?) {
        if (tv == null || d == null) return
        try {
            tv.setCompoundDrawables(d, null, null, null)
            val spaceW = tv.paint.measureText(" ")
            tv.setCompoundDrawablePadding((spaceW + 0.5f).toInt())
        } catch (ignored: Throwable) {}
    }

    private fun getResId(res: Resources, iconId: Int): Int {
        return try {
            when (iconId) {
                IC_VOICE_FORWARD -> res.getIdentifier("ic_voice_forward", "drawable", MODULE_PKG)
                IC_VOICE_LIST -> res.getIdentifier("ic_voice_list", "drawable", MODULE_PKG)
                IC_SCHEDULE_SEND -> res.getIdentifier("ic_schedule_send", "drawable", MODULE_PKG)
                IC_LESHAO_GROUP -> res.getIdentifier("ic_leshao_group", "drawable", MODULE_PKG)
                IC_LESHAO_ICON -> res.getIdentifier("ic_leshao_icon", "drawable", MODULE_PKG)
                else -> 0
            }
        } catch (t: Throwable) {
            LogWriter.log("IconLoader", "getResId fail: $t")
            0
        }
    }
}