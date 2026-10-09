package com.leshao.v3

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

object ContextManager {

    private const val TAG = "ContextManager"

    @Volatile
    private var sClassLoader: ClassLoader? = null

    @Volatile
    private var sTinkerClassLoader: ClassLoader? = null

    @Volatile
    private var sApkPath: String? = null

    @Volatile
    private var sModuleApkPath: String? = null

    @Volatile
    private var sReady = false

    @Volatile
    private var sAppContext: Context? = null

    private var sOnReadyCallback: Runnable? = null

    @Volatile
    private var sCallbackFired = false

    @JvmStatic
    fun getTinkerClassLoader(): ClassLoader? {
        return sTinkerClassLoader
    }

    @JvmStatic
    fun setOnReadyCallback(callback: Runnable?) {
        sOnReadyCallback = callback
    }

    @JvmStatic
    fun init(cl: ClassLoader?, apkPath: String?) {
        LogWriter.log(TAG, "=== init V2 START ===")
        sClassLoader = cl
        sApkPath = apkPath
        LogWriter.log(TAG, "init: apk=" + apkPath)
    }

    @JvmStatic
    fun hookAttachBaseContext(lpp: XC_LoadPackage.LoadPackageParam?) {
        try {
            val attachMethod: Method =
                android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            XposedBridge.hookMethod(attachMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // 仅接受 Application 级别的 attach，避免 Activity/Service 覆盖
                    // sAppContext 并被静态引用导致 Activity 泄漏
                    if (param.thisObject !is Application) return
                    sAppContext = param.thisObject as Context
                    // v1025: 最早探针 —— Application 的 CL 即微信真实 CL, 须在微信打开
                    // EnMicroMsg.db 之前 rehook WCDB(否则 open 事件捕获不到)
                    try {
                        com.leshao.v3.db.DatabaseProvider.probeAndRehook(param.thisObject)
                    } catch (t: Throwable) {
                        LogWriter.log(TAG, "DatabaseProvider.probeAndRehook FAILED: " + t.message)
                    }
                    // v962: 主/分身实例隔离管理器初始化(须早于一切业务 Hook, 见《微信模块隔离.md》)
                    try {
                        InstanceManager.init(sAppContext)
                    } catch (t: Throwable) {
                        LogWriter.log(TAG, "InstanceManager.init FAILED: " + t.message)
                    }
                    if (!sReady) {
                        sReady = true
                        LogWriter.log(TAG, "attachBaseContext DONE, ready=true")
                    }
                    if (sOnReadyCallback != null && !sCallbackFired) {
                        sCallbackFired = true
                        try {
                            sOnReadyCallback!!.run()
                        } catch (t: Throwable) {
                            LogWriter.log(TAG, "onReady callback FAILED: " + t.message)
                        }
                    }
                }
            })
        } catch (t: Throwable) {
            LogWriter.log(TAG, "hookAttachBaseContext FAILED: " + t.message)
        }
    }

    @JvmStatic
    fun isReady(): Boolean {
        return sReady
    }

    @JvmStatic
    fun getClassLoader(): ClassLoader? {
        return sClassLoader
    }

    @JvmStatic
    fun getApkPath(): String? {
        return sApkPath
    }

    /**
     * 记录模块自身 APK 路径（LSPosed 运行时会向 LoadPackageParam 注入 modulePath 字段）。
     *
     * 当前仅由 [MainHook.captureModuleApkPath] 写入并落日志, 供排查用; 模块 APK 路径的
     * 实际消费方是 [IconLoader.moduleApkPath]（DexKit 基线导出等）, 不读取本字段。
     * 保留本 setter/getter 以兼容既有调用点, 不代表已闭环的配置链路。
     */
    @JvmStatic
    fun setModuleApkPath(moduleApkPath: String?) {
        sModuleApkPath = moduleApkPath
        if (moduleApkPath != null) LogWriter.log(TAG, "moduleApkPath=" + moduleApkPath)
    }

    /** 返回最近一次 setModuleApkPath 记录的值; 当前全项目无读取方, 仅为排查/预留。 */
    @JvmStatic
    fun getModuleApkPath(): String? {
        return sModuleApkPath
    }

    @JvmStatic
    fun getAppContext(): Context? {
        return sAppContext
    }

    @JvmStatic
    fun getPrefs(): SharedPreferences? {
        val ctx = sAppContext ?: return null
        return UnifiedPrefs.get(ctx, "leshao_v3_prefs")
    }

    @JvmStatic
    fun waitForReady(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (!sReady && (System.currentTimeMillis() - start) < timeoutMs) {
            try {
                Thread.sleep(100)
            } catch (ignored: InterruptedException) {
            }
        }
        return sReady
    }

    @JvmStatic
    fun getVersionName(): String {
        return "3.6.6-v166"
    }
}