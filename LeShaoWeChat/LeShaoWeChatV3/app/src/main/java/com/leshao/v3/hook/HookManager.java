package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;

/**
 * HookManager — 统一Hook管理
 * ==========================
 * 
 * 功能:
 *   1. 注册/注销Hook的统一入口
 *   2. Hook状态追踪（已注册方法数、成功/失败统计）
 *   3. 条件Hook（按版本/按开关动态启用）
 *   4. Hook性能监控
 *   5. 兼容原项目 Runnable 注册模式
 */
public class HookManager {

    private static final Map<String, XC_MethodHook.Unhook> trackedHooks = new ConcurrentHashMap<>();
    private static final List<Runnable> pendingTasks = new ArrayList<>();
    private static final List<String> hookLog = new ArrayList<>();
    private static int successCount = 0;
    private static int failCount = 0;
    private static boolean activated = false;

    public static void register(Runnable task) {
        if (activated) { task.run(); }
        else { pendingTasks.add(task); }
    }

    public static int pendingCount() { return pendingTasks.size(); }

    public static void activateAll() {
        activated = true;
        XposedBridge.log("[HookManager] activateAll: " + pendingTasks.size() + " tasks");
        int idx = 0;
        for (Runnable t : pendingTasks) {
            try {
                t.run();
            } catch (Throwable ex) {
                XposedBridge.log("[HookManager] task[" + idx + "] failed: " + ex.getClass().getSimpleName()
                    + " " + ex.getMessage());
            }
            idx++;
        }
        pendingTasks.clear();
        XposedBridge.log("[HookManager] activateAll DONE, success=" + successCount + " fail=" + failCount);
    }

    /** 注册Hook并追踪 */
    public static boolean register(String key, Class<?> clazz, String methodName, XC_MethodHook callback, Object... paramTypes) {
        try {
            java.lang.reflect.Method method;
            if (paramTypes.length == 0) {
                var unhook = XposedBridge.hookAllMethods(clazz, methodName, callback);
                trackedHooks.put(key, unhook instanceof java.util.Set
                        ? ((java.util.Set<XC_MethodHook.Unhook>)unhook).iterator().next() : null);
            } else {
                method = clazz.getDeclaredMethod(methodName, (Class<?>[]) paramTypes);
                method.setAccessible(true);
                var unhook = XposedBridge.hookMethod(method, callback);
                trackedHooks.put(key, unhook);
            }
            successCount++;
            log("✅ " + key + " → " + clazz.getSimpleName() + "." + methodName);
            return true;
        } catch (Throwable t) {
            failCount++;
            log("❌ " + key + " → " + t.getMessage());
            return false;
        }
    }

    public static void unregister(String key) {
        XC_MethodHook.Unhook unhook = trackedHooks.remove(key);
        if (unhook != null) {
            unhook.unhook();
            log("🔴 已注销: " + key);
        }
    }

    public static void unregisterAll() {
        for (Map.Entry<String, XC_MethodHook.Unhook> e : trackedHooks.entrySet()) {
            try { e.getValue().unhook(); } catch (Throwable ignored) {}
        }
        trackedHooks.clear();
        log("🔴 全部Hook已注销");
    }

    public static String getStats() {
        return "Hook统计: 成功=" + successCount + " 失败=" + failCount + " 活跃=" + trackedHooks.size();
    }

    private static void log(String msg) {
        hookLog.add(msg);
        XposedBridge.log("[HookManager] " + msg);
    }
}
