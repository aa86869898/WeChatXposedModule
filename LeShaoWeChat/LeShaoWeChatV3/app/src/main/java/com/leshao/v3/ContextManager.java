package com.leshao.v3;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class ContextManager {

    private static final String TAG = "ContextManager";
    private static ClassLoader sClassLoader;
    private static String sApkPath;
    private static boolean sReady = false;
    private static Context sAppContext;
    private static Runnable sOnReadyCallback;

    public static void setOnReadyCallback(Runnable callback) {
        sOnReadyCallback = callback;
    }

    public static void init(ClassLoader cl, String apkPath) {
        sClassLoader = cl;
        sApkPath = apkPath;
        LogWriter.log(TAG, "init: apk=" + apkPath);
    }

    public static void hookAttachBaseContext(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            XposedHelpers.findAndHookMethod(
                android.content.ContextWrapper.class,
                "attachBaseContext",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        sAppContext = (Context) param.thisObject;
                        sReady = true;
                        LogWriter.log(TAG, "attachBaseContext DONE, ready=true");
                        if (sOnReadyCallback != null) {
                            try { sOnReadyCallback.run(); } catch (Throwable t) {
                                LogWriter.log(TAG, "onReady callback FAILED: " + t.getMessage());
                            }
                        }
                    }
                }
            );
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookAttachBaseContext FAILED: " + t.getMessage());
        }
    }

    public static boolean isReady() { return sReady; }

    public static ClassLoader getClassLoader() { return sClassLoader; }

    public static String getApkPath() { return sApkPath; }

    public static Context getAppContext() { return sAppContext; }

    public static SharedPreferences getPrefs() {
        if (sAppContext == null) return null;
        return sAppContext.getSharedPreferences("leshao_v3_prefs", Context.MODE_PRIVATE);
    }

    public static boolean waitForReady(long timeoutMs) {
        long start = System.currentTimeMillis();
        while (!sReady && (System.currentTimeMillis() - start) < timeoutMs) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        return sReady;
    }
}
