package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class AntiDetectionHook {

    private static final String TAG = "AntiDetect";

    public static void hook() {
        try {
            hookStackTrace();
            hookDexFile();
            LogWriter.log(TAG, "all hooks installed");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook err: " + t.getMessage());
        }
    }

    private static void hookStackTrace() {
        try {
            XposedHelpers.findAndHookMethod(Throwable.class, "getStackTrace", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        StackTraceElement[] original = (StackTraceElement[]) param.getResult();
                        if (original == null || original.length == 0) return;

                        int dirty = 0;
                        for (int i = 0; i < original.length && i < 5; i++) {
                            StackTraceElement e = original[i];
                            if (e == null || e.getClassName() == null) continue;
                            if (e.getClassName().startsWith("de.robv.android.xposed")) dirty++;
                        }
                        if (dirty == 0) return;

                        int count = 0;
                        for (StackTraceElement e : original) {
                            if (e == null || e.getClassName() == null) continue;
                            if (!e.getClassName().startsWith("de.robv.android.xposed")) count++;
                        }

                        StackTraceElement[] clean = new StackTraceElement[count];
                        int j = 0;
                        for (StackTraceElement e : original) {
                            if (e == null || e.getClassName() == null) continue;
                            if (!e.getClassName().startsWith("de.robv.android.xposed")) {
                                clean[j++] = e;
                            }
                        }
                        param.setResult(clean);
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "stacktrace hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "stacktrace err: " + t.getMessage());
        }
    }

    private static void hookDexFile() {
        try {
            Class<?> dexFile = XposedHelpers.findClass("dalvik.system.DexFile",
                ClassLoader.getSystemClassLoader());
            XposedHelpers.findAndHookMethod(dexFile, "defineClassNative",
                ClassLoader.class, String.class, String.class, int.class, Object.class,
                Class.class, Object.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            StackTraceElement[] trace = Thread.currentThread().getStackTrace();
                            for (StackTraceElement e : trace) {
                                if (e != null && e.getClassName() != null
                                    && e.getClassName().startsWith("de.robv.android.xposed")) {
                                    param.setResult(Class.class);
                                    return;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            LogWriter.log(TAG, "dexfile hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "dexfile err: " + t.getMessage());
        }
    }
}
