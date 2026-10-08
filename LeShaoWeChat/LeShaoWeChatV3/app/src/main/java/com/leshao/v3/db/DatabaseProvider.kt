package com.leshao.v3.db

import com.leshao.v3.ContactRepository
import com.leshao.v3.ContextManager
import com.leshao.v3.LogWriter

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

import dalvik.system.DexFile
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * v1025: WCDB 逃密方案 —— 不再独立初始化 CsoLoader / 自己打开 EnMicroMsg.db，
 * 而是 hook 微信自身进程的 WCDB openDatabase，捕获微信已打开的 DB 实例与密钥。
 *
 * v1024 教训(日志证实): hook 已安装(7 open + 2 rawQuery overloads)但从未触发, 且模块侧
 * CsoLoader 报 "No implementation found"(native lib 已加载但 JNI 未注册到该类对象) ——
 * 说明微信实际使用的 wcdb/cso 类从另一个 ClassLoader 加载(同名类不同 Class 对象,
 * Xposed hook 不跨 Class 对象生效)。v1025 新增 probeAndRehook(): 从微信运行时对象
 * (Activity/e9 等已验证会触发的 hook)反查真实 ClassLoader, 用它重新 loadClass 并 hook。
 */
class DatabaseProvider {

    fun interface OnDbReadyListener {
        fun onDbReady(db: Any?, password: ByteArray?)
    }

    companion object {

        private const val TAG = "DatabaseProvider"

        @Volatile
        private var sDatabase: Any? = null

        @Volatile
        private var sPassword: ByteArray? = null

        @Volatile
        private var sDbReadyListener: OnDbReadyListener? = null

        /**
         * 已完成 hook 的 WCDB Class 对象集合(按 Class 去重, 允许对多 ClassLoader 版本重复 hook)。
         * v955(问题9): 改为并发集合 —— initEarly/init 在锁外 add, probeAndRehook/probeClassLoader
         * 在锁内 add, 原 HashSet 存在并发写竞态/丢更新。
         */
        private val sHookedClasses: MutableSet<Class<*>> =
            Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

        private val sProbeDone = AtomicBoolean(false)

        @Volatile
        private var sRealClassLoaderDesc: String? = null

        /** 微信真实 ClassLoader */
        @Volatile
        private var sRealClassLoader: ClassLoader? = null

        /** 已 probe 过的 ClassLoader(identity 去重, 无锁读) */
        private val sProbedCLs: MutableSet<ClassLoader> =
            Collections.newSetFromMap(ConcurrentHashMap<ClassLoader, Boolean>())

        @JvmStatic
        fun getDatabase(): Any? {
            return sDatabase
        }

        @JvmStatic
        fun getPassword(): ByteArray? {
            return sPassword
        }

        @JvmStatic
        fun isReady(): Boolean {
            return sDatabase != null
        }

        /** v1025: 微信真实 ClassLoader(反查自微信运行时对象), 供联系人/语音等模块优先使用 */
        @JvmStatic
        fun getRealClassLoader(): ClassLoader? {
            return sRealClassLoader
        }

        @JvmStatic
        fun setOnDbReadyListener(listener: OnDbReadyListener?) {
            sDbReadyListener = listener
            if (sDatabase != null) {
                try {
                    listener?.onDbReady(sDatabase, sPassword)
                } catch (ignored: Throwable) {
                }
            }
        }

        /**
         * 在 handleLoadPackage 中立即调用(attachBaseContext 之前)，
         * 使用 lpparam.classLoader 提前 Hook WCDB openDatabase，捕获微信自开 DB。
         */
        @JvmStatic
        fun initEarly(classLoader: ClassLoader) {
            LogWriter.log(TAG, "initEarly: START via handleLoadPackage classLoader")
            val wcdbClass = loadWcdbClass(classLoader, null)
            if (wcdbClass != null && sHookedClasses.add(wcdbClass)) {
                installHooks(wcdbClass)
            }
            // 兜底: 枚举进程内所有 BaseDexClassLoader, 覆盖平行 CL 上的 wcdb
            hookClassLoaderDiscovery(classLoader)
            // initEarly 无法确定微信真实 CL, 标记尚未 probe; probeAndRehook 由微信对象回调触发
        }

        /**
         * 在 attachBaseContext 完成后调用，作为兜底(initEarly 类加载失败时)。
         */
        @JvmStatic
        fun init() {
            Thread({
                if (!ContextManager.waitForReady(60000)) {
                    LogWriter.log(TAG, "init ABORTED: attachBaseContext not ready in 60s")
                } else {
                    LogWriter.log(TAG, "init START, apk=" + ContextManager.getApkPath())
                    val wcdbClass = loadWcdbClass(
                        ContextManager.getClassLoader(), ContextManager.getApkPath())
                    if (wcdbClass != null && sHookedClasses.add(wcdbClass)) {
                        installHooks(wcdbClass)
                    }
                }
            }, "leshao-db-init").start()
        }

        /**
         * v1025 核心: 从微信运行时对象反查真实 ClassLoader 并重新 hook。
         * 幂等 —— 每个 ClassLoader(identity)只 probe 一次。
         * 在微信 Activity onCreate/onResume 回调中调用(已 probe 过时纳秒级返回)。
         */
        @JvmStatic
        fun probeAndRehook(wechatInstance: Any?) {
            if (wechatInstance == null || sDatabase != null) return
            val realCl = wechatInstance.javaClass.classLoader
            if (realCl == null || !sProbedCLs.add(realCl)) return

            synchronized(DatabaseProvider::class.java) {
                LogWriter.log(TAG, "probeAndRehook: wechat instance CL=" + realCl)
                sRealClassLoaderDesc = realCl.toString()
                sRealClassLoader = realCl

                // 遍历 CL 及 parent 链, 逐个尝试加载 + hook(未 probe 过的 CL 才试)
                var cl: ClassLoader? = realCl
                var depth = 0
                while (cl != null && depth < 5) {
                    try {
                        val wcdbClass = loadWcdbClass(cl, null)
                        if (wcdbClass != null && sHookedClasses.add(wcdbClass)) {
                            LogWriter.log(TAG, "probeAndRehook: rehook via CL[" + depth + "]="
                                + cl.javaClass.simpleName)
                            hookOpenDatabase(wcdbClass)
                            hookRawQuery(wcdbClass)
                            hookConstructors(wcdbClass)
                        }
                    } catch (t: Throwable) {
                        LogWriter.log(TAG, "probeAndRehook CL[" + depth + "] error: " + t)
                    }
                    cl = cl.parent
                    depth++
                }

                if (!sHookedClasses.isEmpty()) {
                    sProbeDone.set(true)
                    LogWriter.log(TAG, "probeAndRehook: DONE hookedClasses="
                        + sHookedClasses.size)
                }

                // 保存真实 CL, 供 VersionCompat.openDatabaseWcdb/tryInitCsoLoader 优先使用
                try {
                    com.leshao.v3.hook.VersionCompat.setWechatRealClassLoader(realCl)
                } catch (ignored: Throwable) {
                }

                // CsoLoader 反查: 真实 CL 上的 CsoLoader 可能已由微信初始化(JNI 已注册)
                // v955(问题5): 裸开总开关关闭时不再触碰 CsoLoader 反射初始化
                if (com.leshao.v3.hook.VersionCompat.ENABLE_RAW_DB_OPEN) {
                    try {
                        com.leshao.v3.hook.VersionCompat.tryInitCsoLoaderReal(realCl)
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }

        /**
         * v1025 兜底: hook BaseDexClassLoader.findClass, 枚举进程内所有出现过的 ClassLoader,
         * 每个新 CL 都尝试加载并 hook WCDB —— 覆盖微信把内核 dex 放在平行 CL 的情况。
         */
        @JvmStatic
        fun hookClassLoaderDiscovery(cl: ClassLoader) {
            try {
                val baseDex = Class.forName("dalvik.system.BaseDexClassLoader")
                for (m in baseDex.declaredMethods) {
                    if (m.name != "findClass") continue
                    if (Modifier.isStatic(m.modifiers)) continue
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            // 已捕获 DB 后直接返回, 保持热路径零开销
                            if (sDatabase != null) return
                            // thisObject 本身就是 ClassLoader(BaseDexClassLoader 子类)
                            val owner = param.thisObject as ClassLoader?
                            if (owner == null) return
                            if (!sProbedCLs.add(owner)) return
                            LogWriter.log(TAG, "CL discovered: " + owner)
                            probeClassLoader(owner)
                        }
                    })
                    LogWriter.log(TAG, "hookClassLoaderDiscovery: findClass hooked")
                    return
                }
                LogWriter.log(TAG, "hookClassLoaderDiscovery: findClass not found")
            } catch (t: Throwable) {
                LogWriter.log(TAG, "hookClassLoaderDiscovery failed: " + t)
            }
        }

        /** 对单个 ClassLoader 尝试加载并 hook WCDB(幂等, 按 Class 对象去重) */
        private fun probeClassLoader(cl: ClassLoader) {
            synchronized(DatabaseProvider::class.java) {
                val wcdbClass = loadWcdbClass(cl, null)
                if (wcdbClass != null && sHookedClasses.add(wcdbClass)) {
                    LogWriter.log(TAG, "probeClassLoader: hook via CL=" + cl.javaClass.simpleName)
                    hookOpenDatabase(wcdbClass)
                    hookRawQuery(wcdbClass)
                    hookConstructors(wcdbClass)
                }
            }
        }

        /**
         * 策略A: 直接 try ClassLoader.loadClass 加载 WCDB SQLiteDatabase
         * 策略B: DexFile 枚举 + Xposed 通用 hook
         */
        private fun loadWcdbClass(cl: ClassLoader?, apkPath: String?): Class<*>? {
            val tinkerCL = com.leshao.v3.hook.VersionCompat.findTinkerClassLoader(cl)
            val cls: Array<ClassLoader?> =
                if (tinkerCL != null && tinkerCL !== cl)
                    arrayOf(tinkerCL, cl)
                else
                    arrayOf(cl)
            for (c in cls) {
                if (c == null) continue
                for (cn in arrayOf(
                    "com.tencent.wcdb.database.SQLiteDatabase",
                    "com.tencent.wcdb2.database.SQLiteDatabase",
                    "com.tencent.wcdb.database.ExSQLiteDatabase",
                )) {
                    try {
                        val wcdbClass = c.loadClass(cn)
                        LogWriter.log(TAG, "STRATEGY A OK: loaded " + cn + " via "
                            + c.javaClass.simpleName)
                        return wcdbClass
                    } catch (ignored: Throwable) {
                    }
                }
            }

            // 策略B: DexFile 枚举
            if (apkPath != null) {
                var df: DexFile? = null
                try {
                    df = DexFile(apkPath)
                    val entries: java.util.Enumeration<String> = df.entries()
                    while (entries.hasMoreElements()) {
                        val cn = entries.nextElement()
                        if (cn.contains("wcdb") && cn.endsWith("SQLiteDatabase")) {
                            try {
                                val wcdbClass = df.loadClass(cn, cl)
                                LogWriter.log(TAG, "STRATEGY B OK: loaded " + cn)
                                return wcdbClass
                            } catch (ignored: Throwable) {
                            }
                        }
                    }
                } catch (e: Throwable) {
                    LogWriter.log(TAG, "STRATEGY B FAILED: " + e.message)
                } finally {
                    // v955(问题12): 任何路径/异常下都关闭 DexFile, 避免 fd 泄漏
                    if (df != null) {
                        try {
                            df.close()
                        } catch (ignored: Throwable) {
                        }
                    }
                }
            }

            // v1134: 3180 上 WCDB 由独立 ClassLoader/dex 加载, 直接 loadClass 常失败 —— 这是预期行为,
            // 真正的 DB 捕获走 ctor / openDatabase hook(见 DB captured via ctor 日志), 不属于故障。
            LogWriter.log(TAG, "loadWcdbClass: WCDB SQLiteDatabase not directly loadable for "
                + cl!!.javaClass.simpleName + " (expected on 3180; captured via ctor/openDatabase)")
            return null
        }

        /**
         * Hook 所有匹配 (String, ...) 的静态 open* 重载，捕获 EnMicroMsg.db 实例与密钥。
         * v1025: 放宽参数限制(不限定 params[1] 类型), 防止签名变体漏网; 记录已 hook 类对象。
         */
        private fun hookOpenDatabase(wcdbClass: Class<*>) {
            var hooked = 0
            for (m in wcdbClass.declaredMethods) {
                if (!Modifier.isStatic(m.modifiers)) continue
                val name = m.name
                if (!name.contains("open")) continue
                val params = m.parameterTypes
                if (params.size < 2) continue
                if (params[0] != String::class.java) continue

                val sig = name + "(" + params.size + "p)"
                LogWriter.log(TAG, "hookOpenDatabase[" + wcdbClass.name + "]: found " + sig)
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result ?: return
                        val path = param.args[0] as String?
                        val pwdArg = param.args[1]
                        val pwd = paramsToBytes(pwdArg)
                        if (path != null && path.contains("EnMicroMsg")) {
                            if (sPassword == null) {
                                sPassword = pwd
                                LogWriter.log(TAG, "KEY captured: path=" + path
                                        + " keyType=" + (if (pwdArg == null) "null"
                                            else pwdArg.javaClass.simpleName)
                                        + " keyLen=" + (pwd?.size ?: 0))
                            }
                            if (sDatabase == null) {
                                sDatabase = result
                                LogWriter.log(TAG, "DB captured: path=" + path
                                        + " dbClass=" + result.javaClass.name)
                                notifyDbReady()
                            }
                        }
                    }
                })
                hooked++
            }
            if (hooked == 0) {
                LogWriter.log(TAG, "hookOpenDatabase[" + wcdbClass.name
                    + "]: no matching static open(String, ...) found")
            } else {
                LogWriter.log(TAG, "hookOpenDatabase[" + wcdbClass.name
                    + "]: " + hooked + " overloads hooked")
            }
        }

        /**
         * Hook SQLiteDatabase.rawQuery 实例方法，捕获 DB 实例(openDatabase 可能错过时兜底)。
         * v1025: 对每个真实 CL 版本的 SQLiteDatabase 都 hook(长驻连接, 微信启动后查询持续发生)。
         */
        private fun hookRawQuery(wcdbClass: Class<*>) {
            var hooked = 0
            for (m in wcdbClass.declaredMethods) {
                if (m.name != "rawQuery") continue
                val params = m.parameterTypes
                if (params.size < 2) continue
                if (params[0] != String::class.java) continue

                LogWriter.log(TAG, "hookRawQuery[" + wcdbClass.name + "]: found rawQuery("
                    + params.size + " params)")
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val thiz = param.thisObject
                        if (sDatabase == null && thiz != null) {
                            try {
                                val path = thiz.javaClass.getMethod("getPath").invoke(thiz) as String?
                                if (path != null && path.contains("EnMicroMsg")) {
                                    sDatabase = thiz
                                    LogWriter.log(TAG, "DB captured via rawQuery (EnMicroMsg: "
                                        + path + ")")
                                    notifyDbReady()
                                }
                            } catch (ignored: Throwable) {
                            }
                        }
                    }
                })
                hooked++
            }
            if (hooked == 0) {
                LogWriter.log(TAG, "hookRawQuery[" + wcdbClass.name
                    + "]: no matching rawQuery(String, ...) found")
            } else {
                LogWriter.log(TAG, "hookRawQuery[" + wcdbClass.name
                    + "]: " + hooked + " overloads hooked")
            }
        }

        /** 统一安装对单个 WCDB 类对象的三类 hook */
        private fun installHooks(wcdbClass: Class<*>) {
            hookOpenDatabase(wcdbClass)
            hookRawQuery(wcdbClass)
            hookConstructors(wcdbClass)
        }

        /**
         * v1025 杀手锏: hook SQLiteDatabase 全部构造函数。
         * DB 实例无论从哪个静态工厂/内部路径创建, 最终必走构造函数 ——
         * 绕过所有静态 open* 签名差异, 直接在 after 回调里从 thisObject.getPath() 捕获。
         */
        private fun hookConstructors(wcdbClass: Class<*>) {
            var hooked = 0
            for (ctor in wcdbClass.declaredConstructors) {
                ctor.isAccessible = true
                XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val thiz = param.thisObject
                        if (sDatabase != null || thiz == null) return
                        try {
                            val path = thiz.javaClass.getMethod("getPath").invoke(thiz) as String?
                            if (path != null && path.contains("EnMicroMsg")) {
                                sDatabase = thiz
                                LogWriter.log(TAG, "DB captured via ctor (EnMicroMsg: " + path + ")")
                                notifyDbReady()
                            }
                        } catch (ignored: Throwable) {
                        }
                    }
                })
                hooked++
            }
            if (hooked == 0) {
                LogWriter.log(TAG, "hookConstructors[" + wcdbClass.name + "]: no ctors")
            } else {
                LogWriter.log(TAG, "hookConstructors[" + wcdbClass.name
                    + "]: " + hooked + " ctors hooked")
            }
        }

        private fun paramsToBytes(arg: Any?): ByteArray? {
            if (arg == null) return null
            if (arg is ByteArray) return arg
            if (arg is String) {
                try {
                    return arg.toByteArray(Charsets.UTF_8)
                } catch (ignored: Throwable) {
                }
            }
            return null
        }

        private fun notifyDbReady() {
            val listener = sDbReadyListener
            if (listener != null) {
                try {
                    listener.onDbReady(sDatabase, sPassword)
                } catch (t: Throwable) {
                    LogWriter.log(TAG, "notifyDbReady ERROR: " + t.message)
                }
            }
            // 捕获 DB 后通知联系人仓库重新加载
            try {
                ContactRepository.onDatabaseCaptured()
            } catch (ignored: Throwable) {
            }
        }
    }
}
