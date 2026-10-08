package com.leshao.v3

import android.content.Context
import android.content.SharedPreferences
import android.os.IBinder
import android.os.Process
import de.robv.android.xposed.XposedBridge

object InstanceManager {

    private const val PREF_NAME = "xposed_wechat_iso"
    private const val PER_USER_RANGE = 100000
    private const val KEY_ENABLED = "enabled"

    private const val TAG = "InstanceManager"

    @Volatile
    private var sUserId = -1

    @Volatile
    private var sDataDir = ""

    @Volatile
    private var sPrimary = false

    @Volatile
    private var sPrefs: SharedPreferences? = null

    @Volatile
    private var sInited = false

    @JvmStatic
    fun init(ctx: Context?) {
        if (ctx == null) {
            LogWriter.log(TAG, "init skipped: ctx null")
            return
        }
        if (sInited) return
        sInited = true
        try {
            sUserId = Process.myUid() / PER_USER_RANGE
            sPrimary = sUserId == 0
            sDataDir = ctx.applicationInfo.dataDir
            sPrefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            LogWriter.log(
                TAG,
                "init DONE: userId=$sUserId primary=$sPrimary cloneApp=${isCloneApp()} enabled=${isEnabled()} dataDir=$sDataDir prefs=$PREF_NAME"
            )
        } catch (t: Throwable) {
            LogWriter.log(TAG, "init err: $t")
            ensureUid()
        }
    }

    private fun ensureUid() {
        if (sUserId == -1) {
            sUserId = Process.myUid() / PER_USER_RANGE
            sPrimary = sUserId == 0
        }
    }

    @JvmStatic
    fun userId(): Int {
        ensureUid()
        return sUserId
    }

    @JvmStatic
    fun isPrimary(): Boolean {
        ensureUid()
        return sPrimary
    }

    @JvmStatic
    fun dataDir(): String {
        return sDataDir
    }

    @JvmStatic
    fun isEnabled(): Boolean {
        val prefs = sPrefs ?: return true
        return try {
            prefs.getBoolean(KEY_ENABLED, true)
        } catch (t: Throwable) {
            true
        }
    }

    @JvmStatic
    fun setEnabled(on: Boolean) {
        val prefs = sPrefs
        if (prefs == null) {
            LogWriter.log(TAG, "setEnabled skipped: prefs null")
            return
        }
        try {
            prefs.edit().putBoolean(KEY_ENABLED, on).apply()
            LogWriter.log(TAG, "setEnabled: userId=${userId()} -> $on")
        } catch (t: Throwable) {
            LogWriter.log(TAG, "setEnabled err: $t")
        }
    }

    @JvmStatic
    fun feature(key: String?): Boolean {
        if (key == null) return false
        val prefs = sPrefs ?: return false
        return try {
            prefs.getBoolean(key, false)
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic
    fun setFeature(key: String?, on: Boolean) {
        if (key == null) return
        val prefs = sPrefs ?: return
        try {
            prefs.edit().putBoolean(key, on).apply()
            LogWriter.log(TAG, "setFeature: userId=${userId()} $key -> $on")
        } catch (t: Throwable) {
            LogWriter.log(TAG, "setFeature err: $t")
        }
    }

    @JvmStatic
    fun label(): String {
        return if (isPrimary()) "主微信(user0)" else "系统分身(user${userId()})"
    }

    @JvmStatic
    fun isCloneApp(): Boolean {
        val myUserId = Process.myUid() / 100000
        if (myUserId == 0) {
            return false
        }
        try {
            val group = getProfileGroupIds(myUserId)
            if (group != null) {
                for (id in group) {
                    if (id == 0) {
                        xlog("isCloneApp: userId=$myUserId 与机主用户同 Profile Group -> 系统克隆分身, 拦截")
                        return true
                    }
                }
                xlog("isCloneApp: userId=$myUserId 独立 Profile Group(组内无机主用户) -> 非克隆分身, 放行(由 LSPosed 控制)")
                return false
            }

            val at = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null)
            if (at != null) {
                val sysCtx = Class.forName("android.app.ActivityThread")
                    .getMethod("getSystemContext").invoke(at) as? Context
                if (sysCtx != null) {
                    try {
                        val r = sysCtx.javaClass.getMethod("isCloneApp").invoke(sysCtx)
                        if (r is Boolean) {
                            xlog("isCloneApp: Context.isCloneApp()=$r")
                            return r
                        }
                    } catch (ignored: Throwable) {}
                    try {
                        val um = sysCtx.getSystemService(Context.USER_SERVICE)
                        val r = um.javaClass.getMethod("isCloneProfile").invoke(um)
                        if (r is Boolean) {
                            xlog("isCloneApp: UserManager.isCloneProfile()=$r")
                            return r
                        }
                    } catch (ignored: Throwable) {}
                    try {
                        val um = sysCtx.getSystemService(Context.USER_SERVICE)
                        val profiles = um.javaClass.getMethod("getUserProfiles").invoke(um) as? List<*>
                        if (profiles != null && profiles.contains(Process.myUserHandle())) {
                            xlog("isCloneApp: userId=$myUserId 出现在机主用户 Profile Group 中 -> 系统克隆分身, 拦截")
                            return true
                        }
                    } catch (ignored: Throwable) {}
                }
            }
            xlog("isCloneApp: 系统判定 API 全部不可用, userId=$myUserId 放行(由 LSPosed 作用域控制)")
        } catch (t: Throwable) {
            xlog("isCloneApp err(放行): $t")
        }
        return false
    }

    private fun getProfileGroupIds(myUserId: Int): IntArray? {
        return try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "user") as? IBinder
                ?: return null
            val um = Class.forName("android.os.IUserManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            um.javaClass
                .getMethod("getProfileIds", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(um, myUserId, false) as? IntArray
        } catch (t: Throwable) {
            xlog("getProfileGroupIds err: $t")
            null
        }
    }

    private fun xlog(msg: String) {
        try {
            XposedBridge.log("[LeShaoV3/InstanceManager] $msg")
        } catch (ignored: Throwable) {}
    }
}