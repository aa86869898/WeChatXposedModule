package com.leshao.v3;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class ContextManager {

    private static final String TAG = "ContextManager";
    private static volatile ClassLoader sClassLoader;
    private static volatile ClassLoader sTinkerClassLoader;
    private static volatile String sApkPath;
    private static volatile String sModuleApkPath;
    private static volatile boolean sReady = false;
    private static volatile Context sAppContext;
    private static Runnable sOnReadyCallback;
    private static volatile boolean sCallbackFired = false;

    public static ClassLoader getTinkerClassLoader() {
        return sTinkerClassLoader;
    }

    public static void setOnReadyCallback(Runnable callback) {
        sOnReadyCallback = callback;
    }

    public static void init(ClassLoader cl, String apkPath) {
        LogWriter.log(TAG, "=== init V2 START ===");
        sClassLoader = cl;
        sApkPath = apkPath;
        LogWriter.log(TAG, "init: apk=" + apkPath);
    }

    public static void hookAttachBaseContext(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Method attachMethod = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            XposedBridge.hookMethod(attachMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // 仅接受 Application 级别的 attach，避免 Activity/Service 覆盖
                    // sAppContext 并被静态引用导致 Activity 泄漏
                    if (!(param.thisObject instanceof Application)) return;
                    sAppContext = (Context) param.thisObject;
                    if (!sReady) {
                        sReady = true;
                        LogWriter.log(TAG, "attachBaseContext DONE, ready=true");
                    }
                    if (sOnReadyCallback != null && !sCallbackFired) {
                        sCallbackFired = true;
                        try {
                            sOnReadyCallback.run();
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "onReady callback FAILED: " + t.getMessage());
                        }
                    }
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookAttachBaseContext FAILED: " + t.getMessage());
        }
    }

    public static boolean isReady() { return sReady; }

    public static ClassLoader getClassLoader() { return sClassLoader; }

    public static String getApkPath() { return sApkPath; }

    /** 设置模块自身 APK 路径（LSPosed 运行时会向 LoadPackageParam 注入 modulePath 字段） */
    public static void setModuleApkPath(String moduleApkPath) {
        sModuleApkPath = moduleApkPath;
        if (moduleApkPath != null) LogWriter.log(TAG, "moduleApkPath=" + moduleApkPath);
    }

    public static String getModuleApkPath() { return sModuleApkPath; }

    public static Context getAppContext() { return sAppContext; }

    public static SharedPreferences getPrefs() {
        if (sAppContext == null) return null;
        return UnifiedPrefs.get(sAppContext, "leshao_v3_prefs");
    }

    public static boolean waitForReady(long timeoutMs) {
        long start = System.currentTimeMillis();
        while (!sReady && (System.currentTimeMillis() - start) < timeoutMs) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        return sReady;
    }

    public static String getVersionName() {
        return "3.6.6-v166";
    }
}
