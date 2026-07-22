package com.leshao.v3.db;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import dalvik.system.DexFile;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class DatabaseProvider {

    private static final String TAG = "DatabaseProvider";
    public interface OnDbReadyListener {
        void onDbReady(Object db, byte[] password);
    }

    private static volatile Object sDatabase;
    private static volatile byte[] sPassword;
    private static volatile OnDbReadyListener sDbReadyListener;
    private static final AtomicBoolean sHooked = new AtomicBoolean(false);

    public static Object getDatabase() { return sDatabase; }
    public static byte[] getPassword() { return sPassword; }
    public static boolean isReady() { return sDatabase != null; }

    public static void setOnDbReadyListener(OnDbReadyListener listener) {
        sDbReadyListener = listener;
    }

    /**
     * 在 attachBaseContext 完成后调用，启动 WCDB Hook 捕获密钥和数据库实例。
     * 所有耗时操作在子线程执行。
     */
    public static void init() {
        new Thread(() -> {
            if (!ContextManager.waitForReady(60000)) {
                LogWriter.log(TAG, "init ABORTED: attachBaseContext not ready in 60s");
                return;
            }
            LogWriter.log(TAG, "init START, apk=" + ContextManager.getApkPath());
            tryHookWcdb();
        }, "leshao-db-init").start();
    }

    /**
     * 策略A: 直接 try ClassLoader.loadClass 加载 WCDB SQLiteDatabase
     * 策略B: DexFile 枚举 + Xposed 通用 hook
     */
    private static void tryHookWcdb() {
        ClassLoader cl = ContextManager.getClassLoader();
        String apkPath = ContextManager.getApkPath();

        Class<?> wcdbClass = null;

        // 策略A: ClassLoader 直接加载
        for (String cn : new String[]{
            "com.tencent.wcdb.database.SQLiteDatabase",
            "com.tencent.wcdb.database.ExSQLiteDatabase",
        }) {
            try {
                wcdbClass = cl.loadClass(cn);
                LogWriter.log(TAG, "Strategy A OK: loaded " + cn);
                break;
            } catch (Throwable ignored) {}
        }

        // 策略B: DexFile 枚举
        if (wcdbClass == null && apkPath != null) {
            try {
                DexFile df = new DexFile(apkPath);
                java.util.Enumeration<String> entries = df.entries();
                while (entries.hasMoreElements()) {
                    String cn = entries.nextElement();
                    if (cn.contains("wcdb") && cn.endsWith("SQLiteDatabase")) {
                        try {
                            wcdbClass = df.loadClass(cn, cl);
                            LogWriter.log(TAG, "Strategy B OK: loaded " + cn);
                            break;
                        } catch (Throwable ignored) {}
                    }
                }
                df.close();
            } catch (Throwable e) {
                LogWriter.log(TAG, "Strategy B FAILED: " + e.getMessage());
            }
        }

        if (wcdbClass == null) {
            LogWriter.log(TAG, "ALL strategies FAILED: cannot find WCDB SQLiteDatabase");
            return;
        }

        hookOpenDatabase(wcdbClass);
        hookRawQuery(wcdbClass);
    }

    /**
     * Hook SQLiteDatabase.openDatabase 多参数重载，捕获密钥和数据库实例
     */
    private static void hookOpenDatabase(Class<?> wcdbClass) {
        if (sHooked.getAndSet(true)) return;

        // 尝试多种 openDatabase 重载签名
        // (String, byte[], SQLiteCipherSpec, CursorFactory, int, DatabaseErrorHandler, int)
        // (String, byte[], SQLiteCipherSpec, CursorFactory, int, DatabaseErrorHandler)
        // (String, byte[], SQLiteCipherSpec, CursorFactory, int)
        // (String, byte[], CursorFactory, int)
        for (Method m : wcdbClass.getDeclaredMethods()) {
            if (!m.getName().equals("openDatabase")) continue;
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length < 2) continue;
            if (params[0] != String.class) continue;
            if (params[1] != byte[].class) continue;

            LogWriter.log(TAG, "hookOpenDatabase: found " + m.getName() + "(" + params.length + " params)");
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result == null) return;

                    String path = (String) param.args[0];
                    byte[] pwd = (byte[]) param.args[1];

                    if (path != null && path.contains("EnMicroMsg")) {
                        if (sPassword == null) {
                            sPassword = pwd;
                            LogWriter.log(TAG, "KEY captured: path=" + path + " keyLen=" + (pwd != null ? pwd.length : 0));
                        }
                        if (sDatabase == null) {
                            sDatabase = result;
                            LogWriter.log(TAG, "DB captured: path=" + path);
                            notifyDbReady();
                        }
                    }
                }
            });
            return; // Hook 第一个匹配的重载
        }

        LogWriter.log(TAG, "hookOpenDatabase FAILED: no matching openDatabase(String, byte[], ...) found");
    }

    /**
     * Hook SQLiteDatabase.rawQuery 实例方法，捕获 DB 实例。
     * openDatabase 在 attachBaseContext 之前就已完成，rawQuery 作为兜底。
     */
    private static void hookRawQuery(Class<?> wcdbClass) {
        for (Method m : wcdbClass.getDeclaredMethods()) {
            if (!m.getName().equals("rawQuery")) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length < 2) continue;
            if (params[0] != String.class) continue;

            LogWriter.log(TAG, "hookRawQuery: found " + m.getName() + "(" + params.length + " params)");
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sDatabase == null && param.thisObject != null) {
                        try {
                            String path = (String) XposedHelpers.callMethod(param.thisObject, "getPath");
                            if (path != null && path.contains("EnMicroMsg")) {
                                sDatabase = param.thisObject;
                                LogWriter.log(TAG, "DB captured via rawQuery (EnMicroMsg: " + path + ")");
                                notifyDbReady();
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            });
            return;
        }
        LogWriter.log(TAG, "hookRawQuery FAILED: no matching rawQuery(String, ...) found");
    }

    private static void notifyDbReady() {
        OnDbReadyListener listener = sDbReadyListener;
        if (listener != null) {
            try {
                listener.onDbReady(sDatabase, sPassword);
            } catch (Throwable t) {
                LogWriter.log(TAG, "notifyDbReady ERROR: " + t.getMessage());
            }
        }
    }
}
