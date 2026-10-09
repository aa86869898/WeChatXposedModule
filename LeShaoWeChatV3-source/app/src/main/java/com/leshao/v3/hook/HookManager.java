package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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
    // v1131: activated 由多线程读写, 用 AtomicBoolean 保证可见性
    private static final AtomicBoolean activated = new AtomicBoolean(false);
    /** 单任务超时保护: 个别任务(DexKit 全量搜索等)卡死时, 记录 FAIL 并继续下一个, 避免整个激活循环被饿死。 */
    private static final long TASK_TIMEOUT_SECONDS = 10L;
    /** 每个任务独立提交到守护线程池, 便于按任务粒度超时控制; 超时后任务线程仍可能运行(daemon), 但不阻塞激活循环。 */
    private static final java.util.concurrent.ExecutorService sTaskExecutor =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "leshao-hook-task");
                t.setDaemon(true);
                return t;
            });

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
        LogWriter.log(TAG, "REGISTERED " + name + " activated=" + activated.get());
        if (activated.get()) { runTask(namedTask, 1, 1); }
        else { pendingTasks.add(namedTask); }
    }

    public static void register(Runnable task) {
        register(task.getClass().getName(), task);
    }

    public static int pendingCount() { return pendingTasks.size(); }

    public static void activateAll() {
        activated.set(true);
        int total = pendingTasks.size();
        LogWriter.log(TAG, "activateAll: " + total + " pending tasks (async)");
        List<NamedTask> tasks = new ArrayList<>(pendingTasks);
        pendingTasks.clear();
        Thread t = new Thread(() -> {
            int idx = 0;
            int ok = 0;
            int fail = 0;
            for (NamedTask task : tasks) {
                if (runTask(task, idx + 1, total)) {
                    ok++;
                    successCount.incrementAndGet();
                } else {
                    fail++;
                    failCount.incrementAndGet();
                }
                idx++;
            }
            LogWriter.log(TAG, "activateAll DONE: " + ok + " OK, " + fail + " FAIL");
        }, "leshao-hook-activate");
        // v1131: 守护线程, 避免常驻线程阻止进程退出
        t.setDaemon(true);
        t.start();
    }

    private static boolean runTask(NamedTask namedTask, int index, int total) {
        long started = System.currentTimeMillis();
        LogWriter.log(TAG, "[" + index + "/" + total + "] START " + namedTask.name);
        try {
            java.util.concurrent.Future<?> f = sTaskExecutor.submit(namedTask.task);
            try {
                f.get(TASK_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                // 尝试中断任务线程; DexKit native 搜索可能不响应, 但任务线程是 daemon, 不会拖垮进程。
                f.cancel(true);
                LogWriter.log(TAG, "[" + index + "/" + total + "] TIMEOUT " + namedTask.name
                        + " after " + TASK_TIMEOUT_SECONDS + "s, continuing");
                return false;
            } catch (Throwable ex) {
                LogWriter.log(TAG, "[" + index + "/" + total + "] FAIL " + namedTask.name + ": "
                        + ex.getClass().getSimpleName() + " " + ex.getMessage());
                return false;
            }
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
                // v1131: 修正不安全强转 —— hookAllMethods 直接返回 Set<Unhook>
                java.util.Set<XC_MethodHook.Unhook> unhooks =
                        XposedBridge.hookAllMethods(clazz, methodName, callback);
                XC_MethodHook.Unhook first = (unhooks == null || unhooks.isEmpty())
                        ? null : unhooks.iterator().next();
                trackedHooks.put(key, first);
            } else {
                method = clazz.getDeclaredMethod(methodName, (Class<?>[]) paramTypes);
                method.setAccessible(true);
                XC_MethodHook.Unhook unhook = XposedBridge.hookMethod(method, callback);
                trackedHooks.put(key, unhook);
            }
            successCount.incrementAndGet();
            log("OK " + key + " -> " + clazz.getSimpleName() + "." + methodName);
            return true;
        } catch (Throwable t) {
            failCount.incrementAndGet();
            log("FAIL " + key + " -> " + t.getMessage());
            return false;
        }
    }

    public static void unregister(String key) {
        XC_MethodHook.Unhook unhook = trackedHooks.remove(key);
        if (unhook != null) {
            unhook.unhook();
            log("已注销: " + key);
        }
    }

    public static void unregisterAll() {
        for (Map.Entry<String, XC_MethodHook.Unhook> e : trackedHooks.entrySet()) {
            try { e.getValue().unhook(); } catch (Throwable ignored) {}
        }
        trackedHooks.clear();
        log("全部Hook已注销");
    }

    public static String getStats() {
        return "Hook统计: 成功=" + successCount.get() + " 失败=" + failCount.get()
                + " 活跃=" + trackedHooks.size();
    }

    private static void log(String msg) {
        hookLog.add(msg);
        XposedBridge.log("[HookManager] " + msg);
    }
}
