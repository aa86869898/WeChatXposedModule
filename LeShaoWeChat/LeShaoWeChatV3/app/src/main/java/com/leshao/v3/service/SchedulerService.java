package com.leshao.v3.service;

import android.os.PowerManager;
import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.model.ScheduledTask;

import de.robv.android.xposed.XposedHelpers;

import java.util.Calendar;
import java.util.List;

public class SchedulerService {

    private static final String TAG = "SchedulerService";
    private static volatile boolean sRunning = false;
    private static Thread sThread;

    public static void start(ModuleConfig cfg) {
        if (sRunning) return;
        sRunning = true;
        sThread = new Thread(new Runnable() {
            @Override
            public void run() {
                LogWriter.log(TAG, "scheduler started");
                PowerManager.WakeLock wl = null;
                try {
                    PowerManager pm = (PowerManager) ContextManager.getAppContext()
                        .getSystemService(android.content.Context.POWER_SERVICE);
                    wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "leshao:scheduler");
                } catch (Throwable ignored) {}

                while (sRunning) {
                    try {
                        checkAndExecute(ContextManager.getPrefs() != null
                            ? ModuleConfig.load(ContextManager.getPrefs()) : null);
                        Thread.sleep(30000);
                    } catch (InterruptedException e) {
                        break;
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "scheduler error: " + t.getMessage());
                    }
                }

                if (wl != null && wl.isHeld()) wl.release();
            }
        }, "leshao-scheduler");
        sThread.start();
    }

    public static void stop() {
        sRunning = false;
        if (sThread != null) sThread.interrupt();
    }

    private static void checkAndExecute(ModuleConfig cfg) {
        if (cfg == null || !cfg.masterSwitch) return;
        List<ScheduledTask> tasks = cfg.scheduledTasks;
        if (tasks == null || tasks.isEmpty()) return;

        Calendar now = Calendar.getInstance();
        int currentMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        for (ScheduledTask task : tasks) {
            if (!task.enabled) continue;
            int taskMin = task.hour * 60 + task.minute;
            if (taskMin != currentMin) continue;

            int dow = now.get(Calendar.DAY_OF_WEEK);
            int dayBit = 1 << (dow - 1);
            if (task.repeatDays != 0 && (task.repeatDays & dayBit) == 0) continue;

            execute(task);
        }
    }

    private static void execute(ScheduledTask task) {
        try {
            ClassLoader cl = ContextManager.getClassLoader();
            Class<?> msgClass = cl.loadClass("com.tencent.mm.modelmulti.n");
            Object msg = XposedHelpers.newInstance(msgClass, task.targetWxid, task.content, 1);
            XposedHelpers.callStaticMethod(msgClass, "b", msg);
            LogWriter.log(TAG, "scheduled send: target=" + task.targetWxid + " content=" + task.content);
        } catch (Throwable t) {
            LogWriter.log(TAG, "scheduled send FAILED: " + t.getMessage());
        }
    }
}
