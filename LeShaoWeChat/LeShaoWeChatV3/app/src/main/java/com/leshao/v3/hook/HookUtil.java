package com.leshao.v3.hook;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Constructor;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 新增功能（文档《WeChat_MsgForge / RedPacket / LeftTop / ChatFooter》）共用的 Hook 基建。
 *
 * <p>本环境 R8 会改写 XposedHelpers 的 varargs findAndHookMethod/findAndHookConstructor，
 * 因此统一走 findClass + getDeclaredConstructor/Method + XposedBridge.hookMethod。</p>
 */
public final class HookUtil {

    private HookUtil() {}

    /**
     * 候选 ClassLoader（去重，顺序即优先级）：
     * Tinker 真实 CL → ContextManager Tinker CL → 传入 cl → AppContext CL。
     * 微信经热修复运行时真实类由 Tinker DelegateLastClassLoader 加载，必须逐份解析。
     */
    public static Set<ClassLoader> candidateLoaders(ClassLoader cl) {
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        try {
            ClassLoader tk = VersionCompat.findTinkerClassLoader(cl);
            if (tk != null) loaders.add(tk);
        } catch (Throwable ignored) {}
        try {
            ClassLoader cm = ContextManager.getTinkerClassLoader();
            if (cm != null) loaders.add(cm);
        } catch (Throwable ignored) {}
        if (cl != null) loaders.add(cl);
        try {
            ClassLoader app = ContextManager.getClassLoader();
            if (app != null) loaders.add(app);
        } catch (Throwable ignored) {}
        return loaders;
    }

    /** 在所有候选 CL 上解析同名类，返回不同的 Class 对象（同一类不同 CL 视为不同）。 */
    public static Set<Class<?>> loadClasses(ClassLoader cl, String clsName) {
        Set<Class<?>> out = new LinkedHashSet<>();
        if (clsName == null) return out;
        for (ClassLoader loader : candidateLoaders(cl)) {
            try {
                out.add(loader.loadClass(clsName));
            } catch (Throwable ignored) {}
        }
        return out;
    }

    public static String loaderName(ClassLoader l) {
        return l == null ? "null" : l.getClass().getSimpleName();
    }

    /**
     * 按字符串锚点定位首个候选类名（沿用 AntiRecallHook / WeChatUpdateBlocker 的通用做法）。
     * 每个锚点单独搜索，命中即返回。
     */
    public static String firstClassByStrings(ClassLoader cl, String tag, String... anchors) {
        for (String kw : anchors) {
            try {
                List<String> cands = DexKitHelper.findClassesByString(cl, kw);
                if (cands != null && !cands.isEmpty()) {
                    LogWriter.log(tag, "class hit: " + cands.get(0) + " via '" + kw + "'");
                    return cands.get(0);
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 汇总多个锚点的全部候选类名（去重，保序）。 */
    public static List<String> classCandidates(ClassLoader cl, String... anchors) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String kw : anchors) {
            try {
                List<String> cands = DexKitHelper.findClassesByString(cl, kw);
                if (cands != null) out.addAll(cands);
            } catch (Throwable ignored) {}
        }
        return new java.util.ArrayList<>(out);
    }

    public interface CtorFilter {
        boolean accept(Class<?>[] params);
    }

    /**
     * 在一个 Class 上 hook 所有满足 filter 的构造器。
     *
     * @return 命中的构造器数量
     */
    public static int hookCtors(Class<?> c, CtorFilter filter, XC_MethodHook hook) {
        if (c == null) return 0;
        int n = 0;
        for (Constructor<?> ctor : c.getDeclaredConstructors()) {
            try {
                if (filter != null && !filter.accept(ctor.getParameterTypes())) continue;
                ctor.setAccessible(true);
                XposedBridge.hookMethod(ctor, hook);
                n++;
            } catch (Throwable ignored) {}
        }
        return n;
    }

    public static boolean isInt(Class<?> t) { return t == int.class || t == Integer.class; }
    public static boolean isLong(Class<?> t) { return t == long.class || t == Long.class; }
}
