package com.leshao.v3.db;

import com.leshao.v3.ContactRepository;
import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import dalvik.system.DexFile;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * v1025: WCDB 逃密方案 —— 不再独立初始化 CsoLoader / 自己打开 EnMicroMsg.db，
 * 而是 hook 微信自身进程的 WCDB openDatabase，捕获微信已打开的 DB 实例与密钥。
 *
 * v1024 教训(日志证实): hook 已安装(7 open + 2 rawQuery overloads)但从未触发, 且模块侧
 * CsoLoader 报 "No implementation found"(native lib 已加载但 JNI 未注册到该类对象) ——
 * 说明微信实际使用的 wcdb/cso 类从另一个 ClassLoader 加载(同名类不同 Class 对象,
 * Xposed hook 不跨 Class 对象生效)。v1025 新增 probeAndRehook(): 从微信运行时对象
 * (Activity/e9 等已验证会触发的 hook)反查真实 ClassLoader, 用它重新 loadClass 并 hook。
 */
public class DatabaseProvider {

    private static final String TAG = "DatabaseProvider";

    public interface OnDbReadyListener {
        void onDbReady(Object db, byte[] password);
    }

    private static volatile Object sDatabase;
    private static volatile byte[] sPassword;
    private static volatile OnDbReadyListener sDbReadyListener;
    /** 已完成 hook 的 WCDB Class 对象集合(按 Class 去重, 允许对多 ClassLoader 版本重复 hook) */
    private static final Set<Class<?>> sHookedClasses = new HashSet<>();
    private static final AtomicBoolean sProbeDone = new AtomicBoolean(false);
    private static volatile String sRealClassLoaderDesc = null;
    /** 微信真实 ClassLoader */
    private static volatile ClassLoader sRealClassLoader;
    /** 已 probe 过的 ClassLoader(identity 去重, 无锁读) */
    private static final Set<ClassLoader> sProbedCLs =
        Collections.newSetFromMap(new ConcurrentHashMap<ClassLoader, Boolean>());

    public static Object getDatabase() { return sDatabase; }
    public static byte[] getPassword() { return sPassword; }
    public static boolean isReady() { return sDatabase != null; }
    /** v1025: 微信真实 ClassLoader(反查自微信运行时对象), 供联系人/语音等模块优先使用 */
    public static ClassLoader getRealClassLoader() { return sRealClassLoader; }

    public static void setOnDbReadyListener(OnDbReadyListener listener) {
        sDbReadyListener = listener;
        if (sDatabase != null) {
            try { listener.onDbReady(sDatabase, sPassword); } catch (Throwable ignored) {}
        }
    }

    /**
     * 在 handleLoadPackage 中立即调用(attachBaseContext 之前)，
     * 使用 lpparam.classLoader 提前 Hook WCDB openDatabase，捕获微信自开 DB。
     */
    public static void initEarly(ClassLoader classLoader) {
        LogWriter.log(TAG, "initEarly: START via handleLoadPackage classLoader");
        Class<?> wcdbClass = loadWcdbClass(classLoader, null);
        if (wcdbClass != null && sHookedClasses.add(wcdbClass)) {
            installHooks(wcdbClass);
        }
        // 兜底: 枚举进程内所有 BaseDexClassLoader, 覆盖平行 CL 上的 wcdb
        hookClassLoaderDiscovery(classLoader);
        // initEarly 无法确定微信真实 CL, 标记尚未 probe; probeAndRehook 由微信对象回调触发
    }

    /**
     * 在 attachBaseContext 完成后调用，作为兜底(initEarly 类加载失败时)。
     */
    public static void init() {
        new Thread(() -> {
            if (!ContextManager.waitForReady(60000)) {
                LogWriter.log(TAG, "init ABORTED: attachBaseContext not ready in 60s");
                return;
            }
            LogWriter.log(TAG, "init START, apk=" + ContextManager.getApkPath());
            Class<?> wcdbClass = loadWcdbClass(
                ContextManager.getClassLoader(), ContextManager.getApkPath());
            if (wcdbClass != null && sHookedClasses.add(wcdbClass)) {
                installHooks(wcdbClass);
            }
        }, "leshao-db-init").start();
    }

    /**
     * v1025 核心: 从微信运行时对象反查真实 ClassLoader 并重新 hook。
     * 幂等 —— 每个 ClassLoader(identity)只 probe 一次。
     * 在微信 Activity onCreate/onResume 回调中调用(已 probe 过时纳秒级返回)。
     */
    public static void probeAndRehook(Object wechatInstance) {
        if (wechatInstance == null || sDatabase != null) return;
        ClassLoader realCl = wechatInstance.getClass().getClassLoader();
        if (realCl == null || !sProbedCLs.add(realCl)) return;

        synchronized (DatabaseProvider.class) {
            LogWriter.log(TAG, "probeAndRehook: wechat instance CL=" + realCl);
            sRealClassLoaderDesc = String.valueOf(realCl);
            sRealClassLoader = realCl;

            // 遍历 CL 及 parent 链, 逐个尝试加载 + hook(未 probe 过的 CL 才试)
            ClassLoader cl = realCl;
            int depth = 0;
            while (cl != null && depth < 5) {
                try {
                    Class<?> wcdbClass = loadWcdbClass(cl, null);
                    if (wcdbClass != null && sHookedClasses.add(wcdbClass)) {
                        LogWriter.log(TAG, "probeAndRehook: rehook via CL[" + depth + "]="
                            + cl.getClass().getSimpleName());
                        hookOpenDatabase(wcdbClass);
                        hookRawQuery(wcdbClass);
                        hookConstructors(wcdbClass);
                    }
                } catch (Throwable t) {
                    LogWriter.log(TAG, "probeAndRehook CL[" + depth + "] error: " + t);
                }
                cl = cl.getParent();
                depth++;
            }

            if (!sHookedClasses.isEmpty()) {
                sProbeDone.set(true);
                LogWriter.log(TAG, "probeAndRehook: DONE hookedClasses="
                    + sHookedClasses.size());
            }

            // 保存真实 CL, 供 VersionCompat.openDatabaseWcdb/tryInitCsoLoader 优先使用
            try {
                com.leshao.v3.hook.VersionCompat.setWechatRealClassLoader(realCl);
            } catch (Throwable ignored) {}

            // CsoLoader 反查: 真实 CL 上的 CsoLoader 可能已由微信初始化(JNI 已注册)
            try {
                com.leshao.v3.hook.VersionCompat.tryInitCsoLoaderReal(realCl);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * v1025 兜底: hook BaseDexClassLoader.findClass, 枚举进程内所有出现过的 ClassLoader,
     * 每个新 CL 都尝试加载并 hook WCDB —— 覆盖微信把内核 dex 放在平行 CL 的情况。
     */
    public static void hookClassLoaderDiscovery(ClassLoader cl) {
        try {
            Class<?> baseDex = Class.forName("dalvik.system.BaseDexClassLoader");
            for (Method m : baseDex.getDeclaredMethods()) {
                if (!m.getName().equals("findClass")) continue;
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // 已捕获 DB 后直接返回, 保持热路径零开销
                        if (sDatabase != null) return;
                        // thisObject 本身就是 ClassLoader(BaseDexClassLoader 子类)
                        ClassLoader owner = (ClassLoader) param.thisObject;
                        if (owner == null) return;
                        if (!sProbedCLs.add(owner)) return;
                        LogWriter.log(TAG, "CL discovered: " + owner);
                        probeClassLoader(owner);
                    }
                });
                LogWriter.log(TAG, "hookClassLoaderDiscovery: findClass hooked");
                return;
            }
            LogWriter.log(TAG, "hookClassLoaderDiscovery: findClass not found");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookClassLoaderDiscovery failed: " + t);
        }
    }

    /** 对单个 ClassLoader 尝试加载并 hook WCDB(幂等, 按 Class 对象去重) */
    private static void probeClassLoader(ClassLoader cl) {
        synchronized (DatabaseProvider.class) {
            Class<?> wcdbClass = loadWcdbClass(cl, null);
            if (wcdbClass != null && sHookedClasses.add(wcdbClass)) {
                LogWriter.log(TAG, "probeClassLoader: hook via CL=" + cl.getClass().getSimpleName());
                hookOpenDatabase(wcdbClass);
                hookRawQuery(wcdbClass);
                hookConstructors(wcdbClass);
            }
        }
    }

    /**
     * 策略A: 直接 try ClassLoader.loadClass 加载 WCDB SQLiteDatabase
     * 策略B: DexFile 枚举 + Xposed 通用 hook
     */
    private static Class<?> loadWcdbClass(ClassLoader cl, String apkPath) {
        ClassLoader tinkerCL = com.leshao.v3.hook.VersionCompat.findTinkerClassLoader(cl);
        ClassLoader[] cls = tinkerCL != null && tinkerCL != cl
            ? new ClassLoader[]{tinkerCL, cl}
            : new ClassLoader[]{cl};
        for (ClassLoader c : cls) {
            if (c == null) continue;
            for (String cn : new String[]{
                "com.tencent.wcdb.database.SQLiteDatabase",
                "com.tencent.wcdb2.database.SQLiteDatabase",
                "com.tencent.wcdb.database.ExSQLiteDatabase",
            }) {
                try {
                    Class<?> wcdbClass = c.loadClass(cn);
                    LogWriter.log(TAG, "STRATEGY A OK: loaded " + cn + " via "
                        + c.getClass().getSimpleName());
                    return wcdbClass;
                } catch (Throwable ignored) {}
            }
        }

        // 策略B: DexFile 枚举
        if (apkPath != null) {
            try {
                DexFile df = new DexFile(apkPath);
                java.util.Enumeration<String> entries = df.entries();
                while (entries.hasMoreElements()) {
                    String cn = entries.nextElement();
                    if (cn.contains("wcdb") && cn.endsWith("SQLiteDatabase")) {
                        try {
                            Class<?> wcdbClass = df.loadClass(cn, cl);
                            LogWriter.log(TAG, "STRATEGY B OK: loaded " + cn);
                            df.close();
                            return wcdbClass;
                        } catch (Throwable ignored) {}
                    }
                }
                df.close();
            } catch (Throwable e) {
                LogWriter.log(TAG, "STRATEGY B FAILED: " + e.getMessage());
            }
        }

        LogWriter.log(TAG, "loadWcdbClass FAILED for "
            + cl.getClass().getSimpleName() + ": cannot find WCDB SQLiteDatabase");
        return null;
    }

    /**
     * Hook 所有匹配 (String, ...) 的静态 open* 重载，捕获 EnMicroMsg.db 实例与密钥。
     * v1025: 放宽参数限制(不限定 params[1] 类型), 防止签名变体漏网; 记录已 hook 类对象。
     */
    private static void hookOpenDatabase(Class<?> wcdbClass) {
        int hooked = 0;
        for (Method m : wcdbClass.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            String name = m.getName();
            if (!name.contains("open")) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length < 2) continue;
            if (params[0] != String.class) continue;

            final String sig = name + "(" + params.length + "p)";
            LogWriter.log(TAG, "hookOpenDatabase[" + wcdbClass.getName() + "]: found " + sig);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result == null) return;
                    String path = (String) param.args[0];
                    Object pwdArg = param.args[1];
                    byte[] pwd = paramsToBytes(pwdArg);
                    if (path != null && path.contains("EnMicroMsg")) {
                        if (sPassword == null) {
                            sPassword = pwd;
                            LogWriter.log(TAG, "KEY captured: path=" + path
                                + " keyType=" + (pwdArg == null ? "null"
                                    : pwdArg.getClass().getSimpleName())
                                + " keyLen=" + (pwd != null ? pwd.length : 0));
                        }
                        if (sDatabase == null) {
                            sDatabase = result;
                            LogWriter.log(TAG, "DB captured: path=" + path
                                + " dbClass=" + result.getClass().getName());
                            notifyDbReady();
                        }
                    }
                }
            });
            hooked++;
        }
        if (hooked == 0) {
            LogWriter.log(TAG, "hookOpenDatabase[" + wcdbClass.getName()
                + "]: no matching static open(String, ...) found");
        } else {
            LogWriter.log(TAG, "hookOpenDatabase[" + wcdbClass.getName()
                + "]: " + hooked + " overloads hooked");
        }
    }

    /**
     * Hook SQLiteDatabase.rawQuery 实例方法，捕获 DB 实例(openDatabase 可能错过时兜底)。
     * v1025: 对每个真实 CL 版本的 SQLiteDatabase 都 hook(长驻连接, 微信启动后查询持续发生)。
     */
    private static void hookRawQuery(Class<?> wcdbClass) {
        int hooked = 0;
        for (Method m : wcdbClass.getDeclaredMethods()) {
            if (!m.getName().equals("rawQuery")) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length < 2) continue;
            if (params[0] != String.class) continue;

            LogWriter.log(TAG, "hookRawQuery[" + wcdbClass.getName() + "]: found rawQuery("
                + params.length + " params)");
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sDatabase == null && param.thisObject != null) {
                        try {
                            String path = (String) param.thisObject.getClass()
                                .getMethod("getPath").invoke(param.thisObject);
                            if (path != null && path.contains("EnMicroMsg")) {
                                sDatabase = param.thisObject;
                                LogWriter.log(TAG, "DB captured via rawQuery (EnMicroMsg: "
                                    + path + ")");
                                notifyDbReady();
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            });
            hooked++;
        }
        if (hooked == 0) {
            LogWriter.log(TAG, "hookRawQuery[" + wcdbClass.getName()
                + "]: no matching rawQuery(String, ...) found");
        } else {
            LogWriter.log(TAG, "hookRawQuery[" + wcdbClass.getName()
                + "]: " + hooked + " overloads hooked");
        }
    }

    /** 统一安装对单个 WCDB 类对象的三类 hook */
    private static void installHooks(Class<?> wcdbClass) {
        hookOpenDatabase(wcdbClass);
        hookRawQuery(wcdbClass);
        hookConstructors(wcdbClass);
    }

    /**
     * v1025 杀手锏: hook SQLiteDatabase 全部构造函数。
     * DB 实例无论从哪个静态工厂/内部路径创建, 最终必走构造函数 ——
     * 绕过所有静态 open* 签名差异, 直接在 after 回调里从 thisObject.getPath() 捕获。
     */
    private static void hookConstructors(Class<?> wcdbClass) {
        int hooked = 0;
        for (Constructor<?> ctor : wcdbClass.getDeclaredConstructors()) {
            ctor.setAccessible(true);
            XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (sDatabase != null || param.thisObject == null) return;
                    try {
                        String path = (String) param.thisObject.getClass()
                            .getMethod("getPath").invoke(param.thisObject);
                        if (path != null && path.contains("EnMicroMsg")) {
                            sDatabase = param.thisObject;
                            LogWriter.log(TAG, "DB captured via ctor (EnMicroMsg: " + path + ")");
                            notifyDbReady();
                        }
                    } catch (Throwable ignored) {}
                }
            });
            hooked++;
        }
        if (hooked == 0) {
            LogWriter.log(TAG, "hookConstructors[" + wcdbClass.getName() + "]: no ctors");
        } else {
            LogWriter.log(TAG, "hookConstructors[" + wcdbClass.getName()
                + "]: " + hooked + " ctors hooked");
        }
    }

    private static byte[] paramsToBytes(Object arg) {
        if (arg == null) return null;
        if (arg instanceof byte[]) return (byte[]) arg;
        if (arg instanceof String) {
            try { return ((String) arg).getBytes("UTF-8"); } catch (Throwable ignored) {}
        }
        return null;
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
        // 捕获 DB 后通知联系人仓库重新加载
        try {
            ContactRepository.onDatabaseCaptured();
        } catch (Throwable ignored) {}
    }
}
