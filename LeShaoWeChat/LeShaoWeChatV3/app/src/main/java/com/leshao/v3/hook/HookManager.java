package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.ArrayList;

public class HookManager {

    private static final String TAG = "HookManager";

    private static final Map<String, XC_MethodHook.Unhook> trackedHooks = new ConcurrentHashMap<>();
    private static final List<NamedTask> pendingTasks = new CopyOnWriteArrayList<>();
    private static final List<String> hookLog = new CopyOnWriteArrayList<>();
    private static final AtomicInteger successCount = new AtomicInteger(0);
    private static final AtomicInteger failCount = new AtomicInteger(0);
    private static boolean activated = false;

    private static final class NamedTask {
        final String name;
        final Runnable task;

        NamedTask(String name, Runnable task) {
            this.name = name;
            this.task = task;
        }
    }

    public static void register(String name, Runnable task) {
        NamedTask namedTask = new NamedTask(name, task);
        LogWriter.log(TAG, "REGISTERED " + name + " activated=" + activated);
        if (activated) { runTask(namedTask, 1, 1); }
        else { pendingTasks.add(namedTask); }
    }

    public static void register(Runnable task) {
        register(task.getClass().getName(), task);
    }

    public static int pendingCount() { return pendingTasks.size(); }

    public static void activateAll() {
        activated = true;
        int total = pendingTasks.size();
        LogWriter.log(TAG, "activateAll: " + total + " pending tasks (async)");
        List<NamedTask> tasks = new ArrayList<>(pendingTasks);
        pendingTasks.clear();
        new Thread(() -> {
            int idx = 0;
            int ok = 0;
            int fail = 0;
            for (NamedTask task : tasks) {
                if (runTask(task, idx + 1, total)) ok++;
                else fail++;
                idx++;
            }
            LogWriter.log(TAG, "activateAll DONE: " + ok + " OK, " + fail + " FAIL");
        }, "leshao-hook-activate").start();
    }

    private static boolean runTask(NamedTask namedTask, int index, int total) {
        long started = System.currentTimeMillis();
        LogWriter.log(TAG, "[" + index + "/" + total + "] START " + namedTask.name);
        try {
            namedTask.task.run();
            LogWriter.log(TAG, "[" + index + "/" + total + "] OK " + namedTask.name
                    + " elapsed=" + (System.currentTimeMillis() - started) + "ms");
            return true;
        } catch (Throwable ex) {
            LogWriter.log(TAG, "[" + index + "/" + total + "] FAIL " + namedTask.name + ": "
                    + ex.getClass().getSimpleName() + " " + ex.getMessage());
            return false;
        }
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
            successCount.incrementAndGet();
            log("✅ " + key + " → " + clazz.getSimpleName() + "." + methodName);
            return true;
        } catch (Throwable t) {
            failCount.incrementAndGet();
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
