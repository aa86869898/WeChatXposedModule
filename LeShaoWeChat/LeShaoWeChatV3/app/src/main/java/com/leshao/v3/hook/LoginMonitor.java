package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import android.os.Build;
import java.io.File;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * [功能32] 登录设备监控
 * 功能:
 *   1. 每次登录记录设备指纹(型号/厂商/Android版本/IMEI hash)
 *   2. 检测新设备 → 弹出Toast告警
 *   3. 日志: /sdcard/LeShaoV3Logs/login_history.log
 *   4. 记录登录时长
 */
public class LoginMonitor {

    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    private static final SimpleDateFormat sdf = new SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private static File logFile;
    private static long loginTimestamp = 0;

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.loginMonitorEnabled) return;

        logFile = new File("/sdcard/LeShaoV3Logs/login_history.log");
        logFile.getParentFile().mkdirs();

        hookLauncherCreate(cl);
        hookLauncherDestroy(cl);
    }

    private static void hookLauncherCreate(ClassLoader cl) {
        try {
            Class<?> launcherUI = XposedHelpers.findClass(
                    "com.tencent.mm.ui.LauncherUI", cl);

            XposedBridge.hookAllMethods(launcherUI, "onCreate",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    loginTimestamp = System.currentTimeMillis();
                    String fingerprint = collectDeviceFingerprint();
                    String lastFingerprint = readLastFingerprint();

                    if (lastFingerprint != null && !lastFingerprint.isEmpty()
                            && !lastFingerprint.equals(fingerprint)) {
                        String alert = "检测到新设备登录!\n"
                                + "上次: " + lastFingerprint + "\n"
                                + "本次: " + fingerprint;
                        Logger.w("[LoginMonitor] " + alert);
                        showAlert(alert);
                    }

                    saveFingerprint(fingerprint);
                    writeLog("LOGIN", fingerprint);
                }
            });
            Logger.i("[LoginMonitor] LauncherUI.onCreate Hook完成");
        } catch (Throwable t) {
            Logger.w("[LoginMonitor] Hook失败: " + t.getMessage());
        }
    }

    private static void hookLauncherDestroy(ClassLoader cl) {
        try {
            Class<?> launcherUI = XposedHelpers.findClass(
                    "com.tencent.mm.ui.LauncherUI", cl);

            XposedBridge.hookAllMethods(launcherUI, "onDestroy",
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    long duration = (System.currentTimeMillis() - loginTimestamp) / 1000;
                    writeLog("LOGOUT", "在线时长: " + formatDuration(duration));
                }
            });
        } catch (Throwable t) {}
    }

    private static String collectDeviceFingerprint() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(Build.MANUFACTURER).append("|");
            sb.append(Build.MODEL).append("|");
            sb.append("Android").append(Build.VERSION.RELEASE).append("|");
            sb.append("SDK").append(Build.VERSION.SDK_INT);

            try {
                Object instance = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("com.tencent.mm.ui.LauncherUI",
                                ContextManager.getClassLoader()), "getInstance");
                if (instance != null) {
                    android.content.Context ctx = (android.content.Context) instance;
                    String pkg = ctx.getPackageName();
                    String ver = ctx.getPackageManager().getPackageInfo(pkg, 0).versionName;
                    sb.append("|WX").append(ver);
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            sb.append("unknown");
        }
        return sb.toString();
    }

    private static String readLastFingerprint() {
        try {
            File fpFile = new File("/sdcard/LeShaoV3Logs/device_fingerprint.txt");
            if (!fpFile.exists()) return null;
            BufferedReader br = new BufferedReader(new FileReader(fpFile));
            String line;
            try {
            line = br.readLine();
            } finally { br.close(); }
            return line;
        } catch (Throwable t) { return null; }
    }

    private static void saveFingerprint(String fingerprint) {
        try {
            File fpFile = new File("/sdcard/LeShaoV3Logs/device_fingerprint.txt");
            fpFile.getParentFile().mkdirs();
            FileWriter fw = new FileWriter(fpFile);
            try {
            fw.write(fingerprint);
            } finally { fw.close(); }
        } catch (Throwable ignored) {}
    }

    private static void writeLog(String event, String detail) {
        try {
            logFile.getParentFile().mkdirs();
            FileWriter fw = new FileWriter(logFile, true);
            try {
            fw.write(sdf.format(new Date()) + " | " + event + " | " + detail + "\n");
            } finally { fw.close(); }
        } catch (Throwable ignored) {}
    }

    private static String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分" + (seconds % 60) + "秒";
        return (seconds / 3600) + "小时" + ((seconds % 3600) / 60) + "分";
    }

    private static void showAlert(final String msg) {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(new Runnable() {
            public void run() {
                try {
                    Object instance = XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("com.tencent.mm.ui.LauncherUI",
                                    ContextManager.getClassLoader()), "getInstance");
                    if (instance != null) {
                        android.widget.Toast.makeText((android.content.Context) instance,
                                msg, android.widget.Toast.LENGTH_LONG).show();
                    }
                } catch (Throwable ignored) {}
            }
        }, 2000);
    }
}
