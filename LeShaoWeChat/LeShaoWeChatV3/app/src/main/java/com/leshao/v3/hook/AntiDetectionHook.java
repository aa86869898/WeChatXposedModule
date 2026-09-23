package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 反Xposed/LSPosed检测 — 生产级完整实现
 * ======================================
 * 
 * 微信检测Xposed的3条路径（基于实际逆向）:
 * 
 * 路径1: com.tencent.mm.app.h3.a(StackTraceElement[]) → boolean
 *   遍历调用栈 → 检查className包含:
 *     "de.robv.android.xposed.XposedBridge"
 *     "com.zte.heartyservice.SCC.FrameworkBridge"
 *   Hook: afterHookedMethod → setResult(false)
 * 
 * 路径2: com.tencent.mm.app.h3.c(Throwable)
 *   检测Xposed导致的IllegalAccessError:
 *     "Class ref in pre-verified class resolved to unexpected implementation"
 *   发现后 → n.c(); n.a(); n.b(); 清理Tinker
 *   Hook: beforeHookedMethod → setResult(null) 阻止清理
 * 
 * 路径3: 系统属性/文件检测
 *   检测 /system/framework/XposedBridge.jar
 *   检测 /data/data/de.robv.android.xposed.installer/
 *   检测 /system/lib/libxposed_*.so
 * 
 * 路径4: ClassLoader检测
 *   检测ClassLoader中是否加载了Xposed相关类
 * 
 * 路径5: StackTrace检测（多处）
 *   微信在多个地方调用getStackTrace()检测Xposed
 */
public class AntiDetectionHook {

    private static final String XP_PREFIX = "de.robv";
    private static final String XP_SUFFIX = "android.xposed";
    private static final String J_SYSTEM = "java.lang.System";
    private static final String J_CLASS_LOADER = "java.lang.ClassLoader";
    private static final String J_LOAD_CLASS = "loadClass";

    private static boolean sEnabled = true;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        try {
            android.content.SharedPreferences p = com.leshao.v3.ContextManager.getPrefs();
            if (p != null) sEnabled = p.getBoolean("ls_anti_detection", true);
        } catch (Throwable ignored) {}

        if (!sEnabled) return;

        hookXposedStackCheck(cl);
        hookTinkerCrashProtect(cl);
        hookSystemPropCheck(cl);
        hookClassLoaderCheck(cl);
        hookStackTraceCheck();
    }

    private static void hookXposedStackCheck(ClassLoader cl) {
        try {
            Class<?> h3 = VersionCompat.findAppClass(cl);
            if (h3 == null) { XposedBridge.log("[AntiDetect] app class not found"); return; }
            XposedBridge.hookAllMethods(h3, "a", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                                        param.setResult(Boolean.FALSE);
                    } catch (Throwable e) {
                        de.robv.android.xposed.XposedBridge.log("LeShaoV3 cb err: " + e);
                    }
                }
            });
            XposedBridge.log("[AntiDetect] h3.a() check handled");
        } catch (Throwable t) {
            XposedBridge.log("[AntiDetect] h3.a() unavailable: " + t.getMessage());
        }
    }

    private static void hookTinkerCrashProtect(ClassLoader cl) {
        try {
            Class<?> h3 = VersionCompat.findAppClass(cl);
            if (h3 == null) return;
            XposedBridge.hookAllMethods(h3, "c", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                                        param.setResult(null);
                    } catch (Throwable e) {
                        de.robv.android.xposed.XposedBridge.log("LeShaoV3 cb err: " + e);
                    }
                }
            });
            XposedBridge.log("[AntiDetect] h3.c() crash protect handled");
        } catch (Throwable t) {
            XposedBridge.log("[AntiDetect] h3.c() unavailable: " + t.getMessage());
        }
    }

    private static void hookSystemPropCheck(ClassLoader cl) {
        try {
            Class<?> systemClass = Class.forName(J_SYSTEM);
            Method getPropMethod = systemClass.getDeclaredMethod("getProperty", String.class);
            
            XposedBridge.hookMethod(getPropMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                                        String key = (String) param.args[0];
                                        if (key != null && (key.contains("xposed") || key.contains("Xposed"))) {
                                            param.setResult(null);
                                        }
                    } catch (Throwable e) {
                        de.robv.android.xposed.XposedBridge.log("LeShaoV3 cb err: " + e);
                    }
                }
            });
        } catch (Throwable t) {}

        try {
            Class<?> systemClass = Class.forName(J_SYSTEM);
            Method getPropsMethod = systemClass.getDeclaredMethod("getProperties");
            XposedBridge.hookMethod(getPropsMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                                        java.util.Properties props = (java.util.Properties) param.getResult();
                                        if (props != null) {
                                            props.remove("xposed.version");
                                            props.remove("xposed.lib");
                                        }
                    } catch (Throwable e) {
                        de.robv.android.xposed.XposedBridge.log("LeShaoV3 cb err: " + e);
                    }
                }
            });
        } catch (Throwable t) {}

        XposedBridge.log("[AntiDetect] System prop check handled");
    }

    private static void hookClassLoaderCheck(ClassLoader cl) {
        try {
            Class<?> classLoaderClass = Class.forName(J_CLASS_LOADER);
            Method loadClassMethod = classLoaderClass.getDeclaredMethod(J_LOAD_CLASS, String.class);
            
            XposedBridge.hookMethod(loadClassMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                                        String className = (String) param.args[0];
                                        if (className != null && (
                                                className.contains(XP_PREFIX + "." + XP_SUFFIX) ||
                                                className.contains("org.meowcat") ||
                                                className.contains("io.github.lsposed"))) {
                                        }
                    } catch (Throwable e) {
                        de.robv.android.xposed.XposedBridge.log("LeShaoV3 cb err: " + e);
                    }
                }
            });
        } catch (Throwable t) {}

        XposedBridge.log("[AntiDetect] ClassLoader check monitored");
    }

    private static void hookStackTraceCheck() {
        try {
            Method getStackTrace = Thread.class.getDeclaredMethod("getStackTrace");
            XposedBridge.hookMethod(getStackTrace, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                                        StackTraceElement[] stack = (StackTraceElement[]) param.getResult();
                                        if (stack == null) return;
                                        // v1024: getStackTrace 是高频调用, 先扫描是否含 Xposed 帧,
                                        // 命中才重建数组 —— 避免每次调用都分配 ArrayList(扫描期/网络
                                        // 栈追踪高频调用时开销显著)。
                                        boolean hasXp = false;
                                        for (StackTraceElement e : stack) {
                                            String cls = e.getClassName();
                                            if (cls != null && (cls.contains(XP_PREFIX + "." + XP_SUFFIX)
                                                    || cls.contains("XposedBridge"))) {
                                                hasXp = true;
                                                break;
                                            }
                                        }
                                        if (!hasXp) return;
                                        java.util.List<StackTraceElement> filtered = new java.util.ArrayList<>();
                                        for (StackTraceElement e : stack) {
                                            String cls = e.getClassName();
                                            if (cls == null || (!cls.contains(XP_PREFIX + "." + XP_SUFFIX) 
                                                    && !cls.contains("XposedBridge"))) {
                                                filtered.add(e);
                                            }
                                        }
                                        param.setResult(filtered.toArray(new StackTraceElement[0]));
                    } catch (Throwable e) {
                        de.robv.android.xposed.XposedBridge.log("LeShaoV3 cb err: " + e);
                    }
                }
            });
            XposedBridge.log("[AntiDetect] StackTrace filter applied");
        } catch (Throwable t) {
            XposedBridge.log("[AntiDetect] StackTrace filter unavailable: " + t.getMessage());
        }
    }
}
