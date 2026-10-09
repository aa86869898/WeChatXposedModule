package com.leshao.v3.hook

import com.leshao.v3.ContextManager
import com.leshao.v3.LogWriter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.ArrayList
import java.util.LinkedHashSet

/**
 * 新增功能（文档《WeChat_MsgForge / RedPacket / LeftTop / ChatFooter》）共用的 Hook 基建。
 *
 * 本环境 R8 会改写 XposedHelpers 的 varargs findAndHookMethod/findAndHookConstructor，
 * 因此统一走 findClass + getDeclaredConstructor/Method + XposedBridge.hookMethod。
 */
object HookUtil {

    /**
     * 候选 ClassLoader（去重，顺序即优先级）：
     * Tinker 真实 CL → ContextManager Tinker CL → 传入 cl → AppContext CL。
     * 微信经热修复运行时真实类由 Tinker DelegateLastClassLoader 加载，必须逐份解析。
     */
    @JvmStatic
    fun candidateLoaders(cl: ClassLoader?): Set<ClassLoader> {
        val loaders = LinkedHashSet<ClassLoader>()
        try {
            val tk = VersionCompat.findTinkerClassLoader(cl)
            if (tk != null) loaders.add(tk)
        } catch (ignored: Throwable) {}
        try {
            val cm = ContextManager.getTinkerClassLoader()
            if (cm != null) loaders.add(cm)
        } catch (ignored: Throwable) {}
        if (cl != null) loaders.add(cl)
        try {
            val app = ContextManager.getClassLoader()
            if (app != null) loaders.add(app)
        } catch (ignored: Throwable) {}
        return loaders
    }

    /** 在所有候选 CL 上解析同名类，返回不同的 Class 对象（同一类不同 CL 视为不同）。 */
    @JvmStatic
    fun loadClasses(cl: ClassLoader?, clsName: String?): Set<Class<*>> {
        val out = LinkedHashSet<Class<*>>()
        if (clsName == null) return out
        for (loader in candidateLoaders(cl)) {
            try {
                out.add(loader.loadClass(clsName))
            } catch (ignored: Throwable) {}
        }
        return out
    }

    @JvmStatic
    fun loaderName(l: ClassLoader?): String {
        return if (l == null) "null" else l.javaClass.simpleName
    }

    /**
     * 按字符串锚点定位首个候选类名（沿用 AntiRecallHook / WeChatUpdateBlocker 的通用做法）。
     * 每个锚点单独搜索，命中即返回。
     */
    @JvmStatic
    fun firstClassByStrings(cl: ClassLoader?, tag: String, vararg anchors: String): String? {
        for (kw in anchors) {
            try {
                val cands = DexKitHelper.findClassesByString(cl, kw)
                if (cands != null && cands.isNotEmpty()) {
                    LogWriter.log(tag, "class hit: " + cands[0] + " via '" + kw + "'")
                    return cands[0]
                }
            } catch (ignored: Throwable) {}
        }
        return null
    }

    /** 汇总多个锚点的全部候选类名（去重，保序）。 */
    @JvmStatic
    fun classCandidates(cl: ClassLoader?, vararg anchors: String): List<String> {
        val out = LinkedHashSet<String>()
        for (kw in anchors) {
            try {
                val cands = DexKitHelper.findClassesByString(cl, kw)
                if (cands != null) out.addAll(cands)
            } catch (ignored: Throwable) {}
        }
        return ArrayList(out)
    }

    fun interface CtorFilter {
        fun accept(params: Array<Class<*>>): Boolean
    }

    /**
     * 在一个 Class 上 hook 所有满足 filter 的构造器。
     *
     * @return 命中的构造器数量
     */
    @JvmStatic
    fun hookCtors(c: Class<*>?, filter: CtorFilter?, hook: XC_MethodHook?): Int {
        if (c == null) return 0
        var n = 0
        for (ctor in c.declaredConstructors) {
            try {
                if (filter != null && !filter.accept(ctor.parameterTypes)) continue
                ctor.isAccessible = true
                XposedBridge.hookMethod(ctor, hook)
                n++
            } catch (ignored: Throwable) {}
        }
        return n
    }

    @JvmStatic
    fun isInt(t: Class<*>): Boolean {
        return t == Int::class.javaPrimitiveType || t == Integer::class.java
    }

    @JvmStatic
    fun isLong(t: Class<*>): Boolean {
        return t == Long::class.javaPrimitiveType || t == Long::class.java
    }
}