package com.leshao.v3.hook;

import android.os.Handler;
import android.os.Looper;

import com.leshao.v3.LogWriter;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public class AutoMethodDetector {

    private static final String TAG = "AutoMethodDetector";

    private static volatile boolean sFeatureEnabled = true;

    public static boolean isFeatureEnabled() {
        return sFeatureEnabled;
    }

    public static void disableFeature(String tag) {
        sFeatureEnabled = false;
        LogWriter.log(TAG, "[" + tag + "] Feature disabled");
    }

    public static void detect(
            DexKitBridge bridge,
            ClassLoader classLoader,
            MethodMatcher matcher,
            XC_MethodHook realHook,
            String tag,
            long timeoutMs) {

        if (!sFeatureEnabled) {
            LogWriter.log(TAG, "[" + tag + "] Feature disabled, skipping");
            return;
        }

        try {
            List<MethodData> candidates = bridge.findMethod(
                FindMethod.create().matcher(matcher)
            );
            LogWriter.log(TAG, "[" + tag + "] Found " + candidates.size() + " candidates");

            if (candidates.isEmpty()) {
                LogWriter.log(TAG, "[" + tag + "] No candidates, disabling feature");
                sFeatureEnabled = false;
                return;
            }

            List<Method> runtimeMethods = new ArrayList<>();
            for (MethodData md : candidates) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (m != null) {
                        runtimeMethods.add(m);
                        LogWriter.log(TAG, "[" + tag + "] candidate: " + md.getClassName()
                            + "." + md.getName() + " params=" + md.getParamTypeNames());
                    }
                } catch (Throwable e) {
                    LogWriter.log(TAG, "[" + tag + "] getMethodInstance failed for "
                        + md.getClassName() + "." + md.getName() + ": " + e.getMessage());
                }
            }

            LogWriter.log(TAG, "[" + tag + "] " + runtimeMethods.size() + " runtime methods");

            if (runtimeMethods.isEmpty()) {
                LogWriter.log(TAG, "[" + tag + "] No runtime methods, disabling feature");
                sFeatureEnabled = false;
                return;
            }

            final List<XC_MethodHook.Unhook> tempUnhooks = new ArrayList<>();
            final AtomicBoolean found = new AtomicBoolean(false);
            final Handler handler = new Handler(Looper.getMainLooper());

            final Runnable timeoutTask = new Runnable() {
                @Override
                public void run() {
                    if (found.get()) return;
                    LogWriter.log(TAG, "[" + tag + "] Timeout, cleaning up temp hooks");
                    for (XC_MethodHook.Unhook u : tempUnhooks) {
                        try { if (u != null) u.unhook(); } catch (Throwable ignored) {}
                    }
                    tempUnhooks.clear();
                    sFeatureEnabled = false;
                    LogWriter.log(TAG, "[" + tag + "] Feature disabled after timeout");
                }
            };

            XC_MethodHook tempHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (found.get()) return;
                    if (param.getThrowable() != null) return;
                    found.set(true);
                    handler.removeCallbacks(timeoutTask);

                    LogWriter.log(TAG, "[" + tag + "] Detected target: "
                        + param.method.getDeclaringClass().getName()
                        + "." + param.method.getName());

                    for (XC_MethodHook.Unhook u : tempUnhooks) {
                        if (u != null) {
                            try { u.unhook(); } catch (Throwable ignored) {}
                        }
                    }
                    tempUnhooks.clear();

                    XposedBridge.hookMethod(param.method, realHook);
                    LogWriter.log(TAG, "[" + tag + "] Real hook installed on "
                        + param.method.getDeclaringClass().getName()
                        + "." + param.method.getName());
                }
            };

            for (Method m : runtimeMethods) {
                XC_MethodHook.Unhook unhook = XposedBridge.hookMethod(m, tempHook);
                tempUnhooks.add(unhook);
            }

            LogWriter.log(TAG, "[" + tag + "] Installed " + tempUnhooks.size()
                + " temp hooks, timeout=" + timeoutMs + "ms");
            handler.postDelayed(timeoutTask, timeoutMs);

        } catch (Throwable e) {
            LogWriter.log(TAG, "[" + tag + "] detect error: " + e.getMessage());
            sFeatureEnabled = false;
        }
    }
}