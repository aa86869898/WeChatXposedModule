package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;
import java.lang.reflect.Method;
import de.robv.android.xposed.XposedHelpers;

public class VersionCompat {

    private static final String TAG = "VersionCompat";

    /**
     * 尝试多个候选类名, 返回第一个找到的
     */
    public static Class<?> findClassMulti(ClassLoader cl, String... names) {
        for (String name : names) {
            try { return XposedHelpers.findClass(name, cl); }
            catch (Throwable ignored) {}
        }
        return null;
    }

    // ==================== Storage ====================
    // y3 → y3/y4 联系人对象

    public static Class<?> findContactClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.storage.y3", "com.tencent.mm.storage.y4",
            "com.tencent.mm.storage.a3", "com.tencent.mm.storage.z2");
    }

    public static String getContactUsername(Object contact) {
        String result;
        for (String m : new String[]{"d1", "d0", "getUsername", "c1", "getWxid"}) {
            try { result = (String) XposedHelpers.callMethod(contact, m); return result; } catch (Throwable ignored) {}
        }
        return "";
    }

    public static String getContactNickname(Object contact) {
        for (String m : new String[]{"M0", "M1", "L0", "getNickname", "N0"}) {
            try { return (String) XposedHelpers.callMethod(contact, m); } catch (Throwable ignored) {}
        }
        return "";
    }

    public static String getContactRemark(Object contact) {
        for (String m : new String[]{"w0", "w1", "v0", "getRemark", "u0"}) {
            try { return (String) XposedHelpers.callMethod(contact, m); } catch (Throwable ignored) {}
        }
        return "";
    }

    public static int getContactAvatar(Object contact) {
        for (String m : new String[]{"R0", "R1", "Q0", "getShowHead"}) {
            try {
                Object r = XposedHelpers.callMethod(contact, m);
                if (r instanceof Integer) return (Integer) r;
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    // ==================== ModContact (np4) ====================

    public static Class<?> findModContactClass(ClassLoader cl) {
        return findClassMulti(cl, "a65.np4", "a59.np4", "a67.np4",
            "a65.mp4", "a65.nq4", "a65.op4");
    }

    public static Class<?> findEw5Class(ClassLoader cl) {
        return findClassMulti(cl, "a65.ew5", "a59.ew5", "a67.ew5",
            "a65.ew6", "a65.dw5", "a65.fw5");
    }

    public static Class<?> findVClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.plugin.messenger.foundation.v",
            "com.tencent.mm.plugin.messenger.foundation.t",
            "com.tencent.mm.plugin.messenger.foundation.u");
    }

    public static String ew5ToString(Object ew5Obj, ClassLoader cl) {
        try {
            Class<?> j1Class = findClassMulti(cl, "a65.j1", "a59.j1", "a67.j1",
                "a65.i1", "a65.k1", "a65.h1");
            if (j1Class != null) {
                Method m = j1Class.getDeclaredMethod("g", Object.class);
                m.setAccessible(true);
                return (String) m.invoke(null, ew5Obj);
            }
        } catch (Throwable ignored) {}
        try {
            Object result = XposedHelpers.callMethod(ew5Obj, "toString");
            return result != null ? result.toString() : "";
        } catch (Throwable ignored2) {}
        return "";
    }

    // ==================== Database ====================

    /**
     * 安全开关: 彻底关闭对微信 EnMicroMsg.db 的独立裸开(openDatabase/openDatabaseWcdb/openEnMicroDb)
     * 及硬编码 opener 类猜测, 杜绝因错误密码/错误标志反复触碰导致微信检测到数据损坏。
     * 数据访问完全交由 DatabaseProvider (运行时捕获微信自身句柄) 或 StorageHub (微信存储 API)。
     */
    public static final boolean ENABLE_RAW_DB_OPEN = false;

    public static Class<?> findDbOpenerClass(ClassLoader cl) {
        if (!ENABLE_RAW_DB_OPEN) {
            LogWriter.log(TAG, "findDbOpenerClass: RAW_DB_OPEN disabled for safety");
            return null;
        }
        // Priority 1: use DexKit scan result
        if (DexKitHelper.isScanComplete()) {
            String clsName = DexKitHelper.getDbOpenerClass();
            if (clsName != null) {
                try {
                    Class<?> result = XposedHelpers.findClass(clsName, cl);
                    LogWriter.log(TAG, "findDbOpenerClass (DexKit): " + result.getName());
                    return result;
                } catch (Throwable e) {
                    LogWriter.log(TAG, "findDbOpenerClass DexKit failed: " + e.getMessage());
                }
            }
        }
        // 硬编码候选类已关闭(防止混淆类名漂移产生冲突)
        LogWriter.log(TAG, "findDbOpenerClass: hardcoded candidates disabled");
        return null;
    }

    public static Object openDatabase(Class<?> dbOpenerClass, String path, String password) {
        if (!ENABLE_RAW_DB_OPEN) {
            LogWriter.log(TAG, "openDatabase: disabled for safety (path=" + path + ")");
            return null;
        }
        Throwable lastErr = null;

        // Priority 1: use DexKit method signature on ka5.f
        boolean scanComplete = DexKitHelper.isScanComplete();
        String p1Method = DexKitHelper.getDbOpenMethodName();
        String[] p1Params = DexKitHelper.getDbOpenMethodParamTypes();
        LogWriter.log(TAG, "openDatabase: scanComplete=" + scanComplete + " method=" + p1Method
                + " params=" + (p1Params != null ? java.util.Arrays.toString(p1Params) : "null"));
        if (scanComplete && p1Method != null && p1Params != null) {
            try {
                Class<?>[] paramClasses = new Class<?>[p1Params.length];
                for (int i = 0; i < p1Params.length; i++) {
                    paramClasses[i] = mapBasicType(p1Params[i]);
                }
                Object[] args = new Object[p1Params.length];
                args[0] = path;
                args[1] = password;
                for (int i = 2; i < p1Params.length; i++) {
                    if ("int".equals(p1Params[i]) || "java.lang.Integer".equals(p1Params[i])) {
                        args[i] = 0;
                    } else if ("boolean".equals(p1Params[i]) || "java.lang.Boolean".equals(p1Params[i])) {
                        args[i] = false;
                    } else {
                        args[i] = null;
                    }
                }
                try {
                    tryInitCsoLoaderScheduled(dbOpenerClass.getClassLoader());
                    Object db1 = dbOpenerClass.getDeclaredMethod(p1Method, paramClasses)
                        .invoke(null, args);
                    if (db1 == null) {
                        LogWriter.log(TAG, "openDatabase P1 invoke returned null: " + dbOpenerClass.getName() + "." + p1Method);
                    }
                    return db1;
                } catch (Throwable e) {
                    lastErr = e;
                    LogWriter.log(TAG, "openDatabase P1 failed: "
                            + (e.getCause() != null ? e.getCause().getClass().getSimpleName() : e.getClass().getSimpleName())
                            + " " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage())
                            + " cause=" + (e.getCause() != null ? e.getCause().toString() : "none"));
                }
            } catch (Throwable e) {
                LogWriter.log(TAG, "openDatabase DexKit setup failed: " + e.getMessage());
            }
        }

        // Priority 2: try known signatures on ka5.f
        tryInitCsoLoaderScheduled(dbOpenerClass.getClassLoader());
        for (int flags : new int[]{0, 1}) {
            for (boolean b : new boolean[]{true, false}) {
                try {
                    return dbOpenerClass.getDeclaredMethod("s", String.class, String.class, int.class, boolean.class)
                        .invoke(null, path, password, flags, b);
                } catch (Throwable e) {
                    lastErr = e;
                    LogWriter.log(TAG, "openDatabase P2 failed(s/" + flags + "/" + b + "): " + errDetail(e));
                }
            }
            try {
                return dbOpenerClass.getDeclaredMethod("r", String.class, String.class, int.class)
                    .invoke(null, path, password, flags);
            } catch (Throwable e) {
                lastErr = e;
                LogWriter.log(TAG, "openDatabase P2 failed(r/" + flags + "): " + errDetail(e));
            }
        }

        // Priority 3: use com.tencent.wcdb.database.SQLiteDatabase directly (bypass ka5.f)
        // Use WeChat's classloader, not the default Class.forName
        ClassLoader wechatCL = dbOpenerClass.getClassLoader();
        try {
            Class<?> wcdbCls = wechatCL.loadClass("com.tencent.wcdb.database.SQLiteDatabase");
            byte[] keyBytes = password != null ? password.getBytes("UTF-8") : new byte[0];
            for (java.lang.reflect.Method m : wcdbCls.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (!m.getReturnType().equals(wcdbCls)) continue;
                String name = m.getName();
                if (!name.contains("open") && !name.contains("Open")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 2) continue;
                if (!pts[0].equals(String.class)) continue;
                try {
                    Object[] args = new Object[pts.length];
                    args[0] = path;
                    if (pts[1] == byte[].class) {
                        args[1] = keyBytes;
                    } else if (pts[1] == String.class) {
                        args[1] = password;
                    } else {
                        args[1] = null;
                    }
                    for (int i = 2; i < pts.length; i++) {
                        args[i] = null;
                    }
                    Object db = m.invoke(null, args);
                    if (db != null) {
                        LogWriter.log(TAG, "openDatabase: WCDB." + name + " OK");
                        return db;
                    }
                } catch (Throwable e) {
                    lastErr = e;
                    LogWriter.log(TAG, "openDatabase P3 failed(WCDB." + m.getName() + "): " + errDetail(e));
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "openDatabase WCDB class load failed: " + errDetail(e));
        }

        boolean isNoClassDef = lastErr instanceof NoClassDefFoundError;
        LogWriter.log(TAG, "openDatabase all failed for " + path + ": lastErr="
                + (lastErr != null ? lastErr.getClass().getSimpleName() + " " + lastErr.getMessage() : "unknown")
                + " isNoClassDef=" + isNoClassDef);
        if (!isNoClassDef) {
            LogWriter.log(TAG, "openDatabase FAILED for " + path + ": " + (lastErr != null ? lastErr.getClass().getSimpleName() + " " + lastErr.getMessage() : "unknown"));
        }
        return null;
    }

    public static Object openDatabaseWcdb(ClassLoader cl, String path, String password) {
        if (!ENABLE_RAW_DB_OPEN) {
            LogWriter.log(TAG, "openDatabaseWcdb: disabled for safety (path=" + path + ")");
            return null;
        }
        ClassLoader wcdbCL = findTinkerClassLoader(cl);
        if (wcdbCL == null) wcdbCL = cl;
        LogWriter.log(TAG, "openDatabaseWcdb: using CL=" + wcdbCL.getClass().getSimpleName()
            + " (original=" + cl.getClass().getSimpleName() + ")");

        String dbOpenerClassName = DexKitHelper.getDbOpenerClass();
        if (dbOpenerClassName == null) dbOpenerClassName = "ka5.f";

        try {
            Class<?> dbOpenerClass = wcdbCL.loadClass(dbOpenerClassName);

            for (java.lang.reflect.Method mm : dbOpenerClass.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(mm.getModifiers())) continue;
                Class<?>[] pt = mm.getParameterTypes();
                if (pt.length < 2) continue;
                if (pt[0] != String.class) continue;
                String mname = mm.getName();
                if (!mname.equals("s") && !mname.equals("r") && !mname.equals("t") && !mname.equals("u") && !mname.equals("v") && !mname.equals("w")) continue;
                mm.setAccessible(true);

                Object[] args = new Object[pt.length];
                args[0] = path;
                for (int i = 1; i < pt.length; i++) {
                    if (pt[i] == String.class) args[i] = password;
                    else if (pt[i] == int.class) args[i] = 0;
                    else if (pt[i] == boolean.class) args[i] = false;
                    else if (pt[i] == byte[].class) args[i] = password != null ? password.getBytes("UTF-8") : new byte[0];
                    else args[i] = null;
                }
                try {
                    Object db = mm.invoke(null, args);
                    if (db != null) {
                        LogWriter.log(TAG, "openDatabaseWcdb: SUCCESS via " + dbOpenerClassName + "." + mname);
                        return db;
                    }
                } catch (Throwable e) {
                    // expected on non-Tinker threads, fallback to WCDB direct
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "openDatabaseWcdb: " + dbOpenerClassName + " class load failed: " + errDetail(e));
        }

        try {
            Class<?> wcdbCls = wcdbCL.loadClass("com.tencent.wcdb.database.SQLiteDatabase");

            byte[] keyBytes = password != null ? password.getBytes("UTF-8") : new byte[0];
            java.lang.reflect.Method[] methods = wcdbCls.getDeclaredMethods();
            for (java.lang.reflect.Method m : methods) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (!m.getReturnType().equals(wcdbCls)) continue;
                String name = m.getName();
                if (!name.contains("open")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 2) continue;
                if (pts[0] != String.class) continue;

                Object[] args = new Object[pts.length];
                args[0] = path;
                if (pts[1] == byte[].class) {
                    args[1] = keyBytes;
                } else if (pts[1] == String.class) {
                    args[1] = password;
                } else {
                    args[1] = null;
                }
                for (int i = 2; i < pts.length; i++) {
                    if (pts[i] == int.class) args[i] = 0;
                    else if (pts[i] == boolean.class) args[i] = false;
                    else args[i] = null;
                }
                try {
                    Object db = m.invoke(null, args);
                    if (db != null) {
                        LogWriter.log(TAG, "openDatabaseWcdb: SUCCESS via WCDB." + name);
                        return db;
                    }
                } catch (Throwable e) {
                    // expected on non-Tinker threads
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "openDatabaseWcdb: SQLiteDatabase class load failed: " + errDetail(e));
        }
        return null;
    }

    private static volatile ClassLoader sCachedTinkerClassLoader = null;
    private static volatile boolean sTinkerSearchDone = false;

    public static ClassLoader findTinkerClassLoader(ClassLoader cl) {
        // v1042: 微信经 Tinker 热修复时, 真实存储类(f9/e9 等)由 DelegateLastClassLoader 加载,
        // 该 CL 比 base.apk 平行副本(PathClassLoader)更"真实"。此前 sCachedTinkerClassLoader
        // 一旦缓存就永不刷新, 而早期 probe 通常先拿到 PathClassLoader 并缓存, 导致后续所有
        // hook 都挂在平行副本类上(运行时调用不经过它) → hook 装上但零捕获。
        // 修复: 若 sWechatRealClassLoader 已更新为更真实的 CL(DelegateLastClassLoader/Tinker),
        // 优先使用并刷新缓存。
        ClassLoader latestReal = sWechatRealClassLoader;
        if (latestReal != null && isTinkerRuntimeLoader(latestReal)) {
            if (sCachedTinkerClassLoader != latestReal) {
                sCachedTinkerClassLoader = latestReal;
                LogWriter.log(TAG, "findTinkerClassLoader: refresh to real CL="
                    + latestReal.getClass().getSimpleName());
            }
            sTinkerSearchDone = true;
            return latestReal;
        }

        // Return cached result if available
        if (sCachedTinkerClassLoader != null) return sCachedTinkerClassLoader;

        // v1025: 优先使用从微信运行时对象反查的真实 ClassLoader
        ClassLoader real = sWechatRealClassLoader;
        if (real != null) {
            sCachedTinkerClassLoader = real;
            sTinkerSearchDone = true;
            LogWriter.log(TAG, "findTinkerClassLoader: using wechat real CL="
                + real.getClass().getSimpleName());
            return real;
        }

        if (sTinkerSearchDone) return null;

        // 优先使用 ContextManager 缓存的 Tinker ClassLoader
        ClassLoader cached = com.leshao.v3.ContextManager.getTinkerClassLoader();
        if (cached != null) {
            sCachedTinkerClassLoader = cached;
            sTinkerSearchDone = true;
            return cached;
        }

        // Try multiple classloader sources
        ClassLoader[] candidates = new ClassLoader[] {
            cl,
            Thread.currentThread().getContextClassLoader(),
            com.leshao.v3.ContextManager.getAppContext() != null
                ? com.leshao.v3.ContextManager.getAppContext().getClassLoader() : null
        };

        for (ClassLoader start : candidates) {
            if (start == null) continue;
            ClassLoader current = start;
            while (current != null) {
                String name = current.getClass().getName();
                // Tinker uses DelegateLastClassLoader or its subclass
                if (name.contains("DelegateLastClassLoader")
                    || name.contains("TinkerClassLoader")
                    || name.contains("Tinker")) {
                    LogWriter.log(TAG, "findTinkerClassLoader: found " + name);
                    sCachedTinkerClassLoader = current;
                    sTinkerSearchDone = true;
                    return current;
                }
                current = current.getParent();
            }
        }

        // Fallback: try main thread's context classloader
        try {
            java.lang.reflect.Field threadField = android.os.Looper.class.getDeclaredField("sThreadLocal");
            threadField.setAccessible(true);
            Object threadLocal = threadField.get(null);
            java.lang.reflect.Method getMethod = threadLocal.getClass().getMethod("get");
            Thread mainThread = (Thread) getMethod.invoke(threadLocal);
            if (mainThread != null) {
                ClassLoader mainCL = mainThread.getContextClassLoader();
                while (mainCL != null) {
                    String name = mainCL.getClass().getName();
                    if (name.contains("DelegateLastClassLoader")
                        || name.contains("TinkerClassLoader")
                        || name.contains("Tinker")) {
                        LogWriter.log(TAG, "findTinkerClassLoader: found via main thread=" + name);
                        sCachedTinkerClassLoader = mainCL;
                        sTinkerSearchDone = true;
                        return mainCL;
                    }
                    mainCL = mainCL.getParent();
                }
            }
        } catch (Throwable ignored) {}

        sTinkerSearchDone = true;
        return null;
    }

    private static String findNativeLibDir() {
        try {
            android.content.Context ctx = com.leshao.v3.ContextManager.getAppContext();
            if (ctx != null) {
                android.content.pm.ApplicationInfo ai = ctx.getPackageManager()
                    .getApplicationInfo("com.tencent.mm", 0);
                if (ai != null && ai.nativeLibraryDir != null) {
                    return ai.nativeLibraryDir;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Class<?> mapBasicType(String typeName) {
        switch (typeName) {
            case "int": return int.class;
            case "boolean": return boolean.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            case "byte": return byte.class;
            case "short": return short.class;
            case "char": return char.class;
            default:
                try { return Class.forName(typeName); }
                catch (Throwable e) { return Object.class; }
        }
    }

    private static boolean sCsoLoaderTried = false;
    private static volatile boolean sCsoLoaderReady = false;
    private static long sCsoLoaderLastTryAt = 0L;
    private static final long CSO_LOADER_RETRY_MS = 3000L;

    public static boolean isCsoLoaderReady() { return sCsoLoaderReady; }

    /**
     * v1022: 允许重复尝试初始化 CsoLoader。
     * v1021 曾用一次性标志 sCsoLoaderTried，第一次尝试时微信内核未就绪，
     * 之后 openDatabase 每次都直接跳过初始化，导致 "Missing initialization" 永久失败。
     * 现在每次 openDatabase 前都会重试（带 3s 节流），微信内核就绪后即可初始化成功。
     */
    private static boolean tryInitCsoLoaderScheduled(ClassLoader cl) {
        long now = System.currentTimeMillis();
        if (sCsoLoaderReady) return true;
        if (now - sCsoLoaderLastTryAt < CSO_LOADER_RETRY_MS) return sCsoLoaderReady;
        sCsoLoaderLastTryAt = now;
        tryInitCsoLoader(cl);
        return sCsoLoaderReady;
    }

    private static String errDetail(Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getSimpleName());
        if (e.getMessage() != null) sb.append(": ").append(e.getMessage());
        Throwable c = e.getCause();
        while (c != null) {
            sb.append(" << ").append(c.getClass().getSimpleName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage());
            c = c.getCause();
        }
        return sb.toString();
    }

    /** v1023: 过滤 Object 声明的方法(如 getClass/hashCode/toString), 避免被当作 CsoLoader 初始化入口 */
    private static boolean isJunkMethod(java.lang.reflect.Method m) {
        try {
            Class<?> decl = m.getDeclaringClass();
            if (decl == java.lang.Object.class) return true;
        } catch (Throwable ignored) {}
        String n = m.getName();
        return "getClass".equals(n) || "hashCode".equals(n) || "toString".equals(n)
                || "equals".equals(n) || "notify".equals(n) || "notifyAll".equals(n)
                || "wait".equals(n) || "clone".equals(n) || "finalize".equals(n);
    }

    /** v1025: 从微信运行时对象反查出的真实 ClassLoader, 供 WCDB/CsoLoader 相关路径优先使用 */
    private static volatile ClassLoader sWechatRealClassLoader = null;

    /** v1042: 判定该 CL 是否为微信 Tinker 热修复的真实运行时加载器。
     *  真实存储类在此类加载器下独立加载, base.apk 平行副本(PathClassLoader)不可用。 */
    private static boolean isTinkerRuntimeLoader(ClassLoader cl) {
        if (cl == null) return false;
        String name = cl.getClass().getName();
        return name.contains("DelegateLastClassLoader")
            || name.contains("TinkerClassLoader")
            || name.contains("Tinker");
    }

    public static void setWechatRealClassLoader(ClassLoader cl) {
        if (cl == null) return;
        sWechatRealClassLoader = cl;
        // v1042: 探测到 Tinker 真实运行时 CL 时同步刷新缓存,
        // 避免早期 PathClassLoader 平行副本被永久缓存导致 hook 挂错类。
        if (isTinkerRuntimeLoader(cl)) {
            if (sCachedTinkerClassLoader != cl) {
                LogWriter.log(TAG, "setWechatRealClassLoader: refresh cache to "
                    + cl.getClass().getSimpleName());
            }
            sCachedTinkerClassLoader = cl;
            sTinkerSearchDone = true;
        }
    }

    /** v1025: CsoLoader 反查入口(5s 限频), 由 DatabaseProvider.probeAndRehook 在真实 CL 上调用 */
    private static volatile long sRealCsoLastTryAt = 0L;

    public static void tryInitCsoLoaderReal(ClassLoader cl) {
        if (sCsoLoaderReady) return;
        long now = System.currentTimeMillis();
        if (now - sRealCsoLastTryAt < 5000L) return;
        sRealCsoLastTryAt = now;
        tryInitCsoLoader(cl);
    }

    private static void tryInitCsoLoader(ClassLoader cl) {
        if (sCsoLoaderReady) return;
        StringBuilder diag = new StringBuilder();
        try {
            ClassLoader tkCL = findTinkerClassLoader(cl);
            if (tkCL == null) tkCL = cl;

            String csoLoaderClass = DexKitHelper.getCsoLoaderClass();
            if (csoLoaderClass == null) {
                csoLoaderClass = "com.tencent.cso.CsoLoader";
            }
            Class<?> cls;
            try {
                cls = XposedHelpers.findClass(csoLoaderClass, tkCL);
            } catch (Throwable e) {
                LogWriter.log(TAG, "tryInitCsoLoader: class not found " + csoLoaderClass
                        + " via " + tkCL.getClass().getSimpleName() + " err=" + errDetail(e));
                return;
            }

            android.content.Context ctx = com.leshao.v3.ContextManager.getAppContext();
            String pkgName = ctx != null ? ctx.getPackageName() : "com.tencent.mm";

            // 记录候选方法，供诊断
            int tried = 0;

            for (java.lang.reflect.Method m : cls.getMethods()) {
                if (isJunkMethod(m)) continue;
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterTypes().length == 0) {
                    tried++;
                    try {
                        m.invoke(null);
                        sCsoLoaderReady = true;
                        LogWriter.log(TAG, "tryInitCsoLoader: OK via " + cls.getName() + "." + m.getName() + "()");
                        return;
                    } catch (Throwable t) {
                        diag.append("\n  zero-arg ").append(m.getName()).append("() -> ").append(errDetail(t));
                    }
                }
            }

            for (java.lang.reflect.Method m : cls.getMethods()) {
                if (isJunkMethod(m)) continue;
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                int pc = m.getParameterTypes().length;
                if (pc == 0) continue;
                tried++;
                Object[] args = buildArgs(m.getParameterTypes(), ctx, cl, pkgName);
                try {
                    m.invoke(null, args);
                    sCsoLoaderReady = true;
                    LogWriter.log(TAG, "tryInitCsoLoader: OK via " + cls.getName() + "." + m.getName()
                            + "(" + m.getParameterTypes().length + ")");
                    return;
                } catch (Throwable t) {
                    diag.append("\n  static ").append(m.getName()).append("(").append(pc).append(") -> ").append(errDetail(t));
                }
            }

            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (isJunkMethod(m)) continue;
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                m.setAccessible(true);
                tried++;
                Object[] args = buildArgs(m.getParameterTypes(), ctx, cl, pkgName);
                try {
                    m.invoke(null, args);
                    sCsoLoaderReady = true;
                    LogWriter.log(TAG, "tryInitCsoLoader: OK via declared " + cls.getName() + "." + m.getName()
                            + "(" + m.getParameterTypes().length + ")");
                    return;
                } catch (Throwable t) {
                    diag.append("\n  declared ").append(m.getName()).append("(").append(m.getParameterTypes().length).append(") -> ").append(errDetail(t));
                }
            }

            Object instance = null;
            java.lang.reflect.Constructor<?>[] ctors = cls.getDeclaredConstructors();
            for (java.lang.reflect.Constructor<?> ctor : ctors) {
                ctor.setAccessible(true);
                Object[] ctorArgs = buildArgs(ctor.getParameterTypes(), ctx, cl, pkgName);
                try {
                    instance = ctor.newInstance(ctorArgs);
                    break;
                } catch (Throwable t) {
                    diag.append("\n  ctor -> ").append(errDetail(t));
                }
            }
            if (instance == null) {
                try {
                    java.lang.reflect.Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
                    f.setAccessible(true);
                    Object unsafe = f.get(null);
                    instance = Class.forName("sun.misc.Unsafe").getMethod("allocateInstance", Class.class).invoke(unsafe, cls);
                } catch (Throwable t) {
                    diag.append("\n  unsafe -> ").append(errDetail(t));
                }
            }

            if (instance != null) {
                // v1023: 优先尝试 DexKit 找到的 CsoLoader 初始化入口(如 com.tencent.cso.CsoLoader.c)
                String dexMethod = DexKitHelper.getCsoLoaderMethod();
                if (dexMethod != null && !dexMethod.isEmpty()) {
                    tried++;
                    try {
                        java.lang.reflect.Method dm = cls.getDeclaredMethod(dexMethod);
                        dm.setAccessible(true);
                        if (dm.getParameterTypes().length == 0 && !isJunkMethod(dm)) {
                            dm.invoke(instance);
                            sCsoLoaderReady = true;
                            LogWriter.log(TAG, "tryInitCsoLoader: OK via dex-method " + cls.getName() + "." + dexMethod + "()");
                            return;
                        }
                    } catch (Throwable t) {
                        diag.append("\n  dex-method ").append(dexMethod).append(" -> ").append(errDetail(t));
                    }
                }

                for (java.lang.reflect.Method m : cls.getMethods()) {
                    if (isJunkMethod(m)) continue;
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    boolean hasCsoLoaderParam = false;
                    for (Class<?> pt : m.getParameterTypes()) {
                        if (pt == cls) { hasCsoLoaderParam = true; break; }
                    }
                    if (!hasCsoLoaderParam) continue;
                    tried++;
                    Object[] args = buildArgs(m.getParameterTypes(), ctx, cl, pkgName);
                    for (int i = 0; i < args.length; i++) {
                        if (m.getParameterTypes()[i] == cls) args[i] = instance;
                    }
                    try {
                        m.invoke(null, args);
                        sCsoLoaderReady = true;
                        LogWriter.log(TAG, "tryInitCsoLoader: OK via static(csoLoader) " + cls.getName() + "." + m.getName());
                        return;
                    } catch (Throwable t) {
                        diag.append("\n  static(cso) ").append(m.getName()).append(" -> ").append(errDetail(t));
                    }
                }

                for (java.lang.reflect.Method m : cls.getMethods()) {
                    if (isJunkMethod(m)) continue;
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterTypes().length == 0) {
                        tried++;
                        try {
                            m.invoke(instance);
                            sCsoLoaderReady = true;
                            LogWriter.log(TAG, "tryInitCsoLoader: OK via instance " + cls.getName() + "." + m.getName() + "()");
                            return;
                        } catch (Throwable t) {
                            diag.append("\n  instance ").append(m.getName()).append("() -> ").append(errDetail(t));
                        }
                    }
                }
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    if (isJunkMethod(m)) continue;
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    if ("nativeInitialize".equals(m.getName())
                        || "preloadAllInternal".equals(m.getName())
                        || "init".equals(m.getName())
                        || "initialize".equals(m.getName())) {
                        m.setAccessible(true);
                        tried++;
                        Object[] args = buildArgs(m.getParameterTypes(), ctx, cl, pkgName);
                        try {
                            m.invoke(instance, args);
                            sCsoLoaderReady = true;
                            LogWriter.log(TAG, "tryInitCsoLoader: OK via named " + cls.getName() + "." + m.getName() + "()");
                            return;
                        } catch (Throwable t) {
                            diag.append("\n  named ").append(m.getName()).append(" -> ").append(errDetail(t));
                        }
                    }
                }
            }
            LogWriter.log(TAG, "tryInitCsoLoader: all " + tried + " attempts failed for " + cls.getName()
                    + " ready=" + sCsoLoaderReady + " details:" + diag);
        } catch (Throwable e) {
            LogWriter.log(TAG, "tryInitCsoLoader: outer err=" + errDetail(e));
        }
    }

    private static Object[] buildArgs(Class<?>[] paramTypes,
            android.content.Context ctx, ClassLoader cl, String pkgName) {
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> pt = paramTypes[i];
            if (android.content.Context.class.isAssignableFrom(pt)) {
                args[i] = ctx;
            } else if (pt == java.lang.ClassLoader.class || pt == ClassLoader.class) {
                args[i] = cl;
            } else if (pt == String.class) {
                args[i] = pkgName;
            } else if (pt == boolean.class) {
                args[i] = false;
            } else if (pt == int.class) {
                args[i] = 0;
            } else if (pt == long.class) {
                args[i] = 0L;
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    public static Class<?> findBaseDirClass(ClassLoader cl) {
        return findClassMulti(cl, "mp0.b", "mo0.b", "mq0.b",
            "mp0.a", "mp0.c", "np0.b");
    }

    public static String getBaseDir(ClassLoader cl, android.content.Context ctx) {
        ClassLoader tkCL = findTinkerClassLoader(cl);
        ClassLoader useCL = tkCL != null ? tkCL : cl;
        Class<?> baseClass = findBaseDirClass(useCL);
        if (baseClass == null) baseClass = findBaseDirClass(cl);
        if (baseClass != null) {
            for (String m : new String[]{"X", "Y", "W", "getDataDir", "a"}) {
                try {
                    Object r = XposedHelpers.callStaticMethod(baseClass, m);
                    if (r instanceof String && !((String) r).isEmpty()) {
                        LogWriter.log(TAG, "getBaseDir: " + r + " (via " + baseClass.getName() + "." + m + ")");
                        return (String) r;
                    }
                } catch (Throwable ignored) {}
            }
        }
        String fallback = ctx.getFilesDir().getParentFile().getAbsolutePath() + "/";
        LogWriter.log(TAG, "getBaseDir fallback: " + fallback);
        return fallback;
    }

    public static Class<?> findDbHashClass(ClassLoader cl) {
        return findClassMulti(cl, "hm0.b0", "hm0.a0", "hm0.c0",
            "gm0.b0", "im0.b0", "hl0.b0");
    }

    public static String getDbHash(ClassLoader cl, int uin) {
        ClassLoader tkCL = findTinkerClassLoader(cl);
        ClassLoader useCL = tkCL != null ? tkCL : cl;
        Class<?> hashClass = findDbHashClass(useCL);
        if (hashClass == null) hashClass = findDbHashClass(cl);
        if (hashClass != null) {
            for (String m : new String[]{"e", "f", "d", "a"}) {
                try {
                    Object r = hashClass.getDeclaredMethod(m, int.class).invoke(null, uin);
                    if (r instanceof String && !((String) r).isEmpty()) {
                        LogWriter.log(TAG, "getDbHash: " + r + " (via " + hashClass.getName() + "." + m + ")");
                        return (String) r;
                    }
                } catch (Throwable ignored) {}
            }
        }
        String fallback = md5("mm" + uin);
        LogWriter.log(TAG, "getDbHash fallback: " + fallback);
        return fallback;
    }

    /**
     * v1016/v1018: 打开微信 EnMicroMsg.db。
     * <p>
     * v1018 改为「枚举 MicroMsg 下真实存在的账号目录 + 逐个密码候选爆破」：
     * 不再依赖能解析设备号（Android 10+ 常读不到 IMEI），目录按最后修改时间倒序，
     * 每个目录依次尝试 {@code 目录名前7位} / {@code md5(imei+uin)[:7]} / {@code md5("mm"+uin)[:7]} 等候选密码，
     * 命中即返回。规避 v1016 固定密码算错导致 DB 全部打不开的问题。
     */
    public static Object openEnMicroDb(ClassLoader cl, String baseDir, long uin) {
        if (!ENABLE_RAW_DB_OPEN) {
            LogWriter.log(TAG, "openEnMicroDb: disabled for safety (uin=" + uin + ")");
            return null;
        }
        if (baseDir == null || uin <= 0) {
            LogWriter.log(TAG, "openEnMicroDb: baseDir/uin 无效 baseDir=" + baseDir + " uin=" + uin);
            return null;
        }
        if (!baseDir.endsWith("/")) baseDir += "/";
        Class<?> dbCls = findDbOpenerClass(cl);
        if (dbCls == null) {
            LogWriter.log(TAG, "openEnMicroDb: dbCls null");
            return null;
        }

        // 1) 共用密码候选（按可能性排序）
        java.util.LinkedHashSet<String> sharedPwds = new java.util.LinkedHashSet<>();
        java.util.List<String> imeis = imeiCandidates(cl);
        if (imeis.isEmpty()) imeis = java.util.Collections.singletonList("1234567890ABCDEF");
        for (String imei : imeis) {
            String full = md5(imei + uin);
            if (full.length() >= 7) sharedPwds.add(full.substring(0, 7));
        }
        String legacy = md5("mm" + uin);
        if (legacy.length() >= 7) sharedPwds.add(legacy.substring(0, 7));
        String mm2 = md5(uin + "mm");
        if (mm2.length() >= 7) sharedPwds.add(mm2.substring(0, 7));
        String uinOnly = md5(String.valueOf(uin));
        if (uinOnly.length() >= 7) sharedPwds.add(uinOnly.substring(0, 7));
        sharedPwds.add("1234567890ABCDEF".substring(0, 7));

        // 2) 枚举 MicroMsg 下真实存在的账号目录（有 EnMicroMsg.db 的）
        java.util.LinkedHashMap<String, String> paths = new java.util.LinkedHashMap<>();
        java.io.File[] dirs = new java.io.File(baseDir + "MicroMsg").listFiles();
        if (dirs != null) {
            java.util.Arrays.sort(dirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (java.io.File dir : dirs) {
                if (dir == null || !dir.isDirectory()) continue;
                String name = dir.getName();
                if (name.length() < 7) continue;
                java.io.File dbf = new java.io.File(dir, "EnMicroMsg.db");
                if (!dbf.exists()) continue;
                paths.put(dbf.getAbsolutePath(), name.substring(0, 7));
            }
        }

        if (paths.isEmpty()) {
            LogWriter.log(TAG, "openEnMicroDb: MicroMsg 下未发现 EnMicroMsg.db");
            return null;
        }

        for (java.util.Map.Entry<String, String> e : paths.entrySet()) {
            String dbPath = e.getKey();
            // 每目录密码候选: 目录名前7位优先, 再叠加共用候选
            java.util.LinkedHashSet<String> pwds = new java.util.LinkedHashSet<>();
            pwds.add(e.getValue());
            pwds.addAll(sharedPwds);
            for (String password : pwds) {
                Object db = openDatabase(dbCls, dbPath, password);
                if (db == null) db = openDatabaseWcdb(cl, dbPath, password);
                if (db != null) {
                    LogWriter.log(TAG, "openEnMicroDb: OK " + dbPath);
                    return db;
                }
            }
            LogWriter.log(TAG, "openEnMicroDb: open failed(密码均不匹配) " + dbPath);
        }
        LogWriter.log(TAG, "openEnMicroDb: FAILED all candidates (" + paths.size() + " dirs)");
        return null;
    }

    public static Class<?> findImeiClass(ClassLoader cl) {
        return findClassMulti(cl, "wo.w0", "wn.w0", "wp.w0",
            "wo.v0", "wo.x0", "vo.w0");
    }

    public static String getImei(ClassLoader cl) {
        java.util.List<String> cands = imeiCandidates(cl);
        if (!cands.isEmpty()) return cands.get(0);
        return "1234567890ABCDEF";
    }

    /**
     * v1016: 设备号候选列表(按优先级去重)。
     * 微信内部设备号 → 系统 IMEI(模块运行在微信进程内, 微信已持有 READ_PHONE_STATE) → 旧版兜底值。
     */
    public static java.util.List<String> imeiCandidates(ClassLoader cl) {
        java.util.List<String> out = new java.util.ArrayList<>();
        ClassLoader tkCL = findTinkerClassLoader(cl);
        ClassLoader useCL = tkCL != null ? tkCL : cl;

        if (DexKitHelper.isScanComplete()) {
            String imeiClass = DexKitHelper.getImeiClassName();
            String imeiMethod = DexKitHelper.getImeiMethodName();
            if (imeiClass != null && imeiMethod != null) {
                try {
                    Class<?> cls = XposedHelpers.findClass(imeiClass, useCL);
                    String r = invokeImeiMethod(cls, imeiMethod);
                    if (looksLikeDeviceId(r)) {
                        LogWriter.log(TAG, "getImei (DexKit): " + imeiClass + "." + imeiMethod
                                + " via " + useCL.getClass().getSimpleName());
                        addImei(out, r);
                    }
                } catch (Throwable e) {
                    // DexKit path fails silently, fallback to wo.w0.g always succeeds
                }
            }
        }
        Class<?> imeiClass = findImeiClass(useCL);
        if (imeiClass == null) imeiClass = findImeiClass(cl);
        if (imeiClass != null) {
            for (String m : new String[]{"g", "f", "h", "e"}) {
                String r = invokeImeiMethod(imeiClass, m);
                if (looksLikeDeviceId(r)) {
                    LogWriter.log(TAG, "getImei: found via " + imeiClass.getName() + "." + m);
                    addImei(out, r);
                    break;
                }
            }
        }
        // v1016: 兜底直读系统 IMEI —— 模块运行在微信进程内, 微信已持有 READ_PHONE_STATE
        String sysImei = systemImei();
        if (sysImei != null) {
            LogWriter.log(TAG, "getImei: via TelephonyManager");
            addImei(out, sysImei);
        }
        if (out.isEmpty()) {
            LogWriter.log(TAG, "getImei fallback: 1234567890ABCDEF");
        }
        addImei(out, "1234567890ABCDEF");
        return out;
    }

    private static void addImei(java.util.List<String> out, String v) {
        if (v == null || v.isEmpty()) return;
        if (!out.contains(v)) out.add(v);
    }

    /** 设备号形态校验: IMEI(13-16位数字) 或 32位hex 或 30-40位hex/连字符，且不含空白与控制符。 */
    private static boolean looksLikeDeviceId(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() < 13 || t.length() > 40) return false;
        if (t.matches("[0-9]{13,16}")) return true;
        if (t.matches("[0-9a-fA-F]{32}")) return true;
        if (t.matches("[0-9a-fA-F-]{30,40}")) return true;
        return false;
    }

    /** 按方法签名宽容调用设备号方法: (boolean)/()/(Context)/(ClassLoader) 逐一尝试。 */
    private static String invokeImeiMethod(Class<?> cls, String method) {
        if (cls == null || method == null) return null;
        android.content.Context ctx = com.leshao.v3.ContextManager.getAppContext();
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals(method)) continue;
            int pc = m.getParameterCount();
            if (m.getReturnType() != String.class) continue;
            try {
                Object r = null;
                if (pc == 0) {
                    r = m.invoke(null);
                } else if (pc == 1 && m.getParameterTypes()[0] == boolean.class) {
                    r = m.invoke(null, Boolean.TRUE);
                } else if (pc == 1 && m.getParameterTypes()[0] == android.content.Context.class && ctx != null) {
                    r = m.invoke(null, ctx);
                } else if (pc == 1 && m.getParameterTypes()[0] == ClassLoader.class) {
                    r = m.invoke(null, cls.getClassLoader());
                } else {
                    continue;
                }
                if (r instanceof String) {
                    String s = (String) r;
                    if (!s.isEmpty()) return s;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 直读系统 IMEI（运行于微信进程内时可用）。 */
    private static String systemImei() {
        try {
            android.content.Context ctx = com.leshao.v3.ContextManager.getAppContext();
            if (ctx == null) return null;
            android.telephony.TelephonyManager tm =
                    (android.telephony.TelephonyManager) ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE);
            if (tm == null) return null;
            String id = null;
            try { id = tm.getDeviceId(); } catch (Throwable ignored) {}
            if (isEmpty(id) && android.os.Build.VERSION.SDK_INT >= 26) {
                try { id = tm.getImei(0); } catch (Throwable ignored) {}
            }
            if (isEmpty(id) && android.os.Build.VERSION.SDK_INT >= 26) {
                try { id = tm.getMeid(0); } catch (Throwable ignored) {}
            }
            return isEmpty(id) ? null : id;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }


    // ==================== Storage helpers ====================

    public static Class<?> findContactInfoUIClass(ClassLoader cl) {
        return findClassMulti(cl,
            "com.tencent.mm.plugin.profile.ui.ContactInfoUI",
            "com.tencent.mm.plugin.profile.ui.v2.ContactInfoUI",
            "com.tencent.mm.plugin.profile.ui.ContactWidgetUI");
    }

    public static Class<?> findLauncherUIClass(ClassLoader cl) {
        return findClassMulti(cl,
            "com.tencent.mm.ui.LauncherUI",
            "com.tencent.mm.ui.LauncherUIProxy");
    }

    public static Class<?> findChattingUIClass(ClassLoader cl) {
        return findClassMulti(cl,
            "com.tencent.mm.ui.chatting.ChattingUIFragment",
            "com.tencent.mm.ui.chatting.v2.ChattingUIFragment");
    }

    public static Class<?> findContactStorageClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.storage.j4",
            "com.tencent.mm.storage.k4", "com.tencent.mm.storage.i4",
            "com.tencent.mm.storage.h4");
    }

    // ==================== AntiRecall ====================

    public static Class<?> findAntiRecallClass(ClassLoader cl) {
        return findClassMulti(cl, "af5.a", "af6.a", "af4.a", "af7.a",
            "ag5.a", "ae5.a", "af5.b");
    }

    public static Class<?> findAntiRecallProtoClass(ClassLoader cl) {
        return findClassMulti(cl, "e01.u", "e02.u", "e00.u", "e03.u",
            "e01.t", "e02.t", "e01.v", "e00.t");
    }

    // 群成员同步逻辑类: e01.v1.t(String room, ArrayList<String> members, String roomOwner)
    // = syncAddChatroomMember, 入群欢迎的首选触发点(反编译确认)
    public static Class<?> findChatroomMembersLogicClass(ClassLoader cl) {
        return findClassMulti(cl, "e01.v1", "e02.v1", "e01.v2", "e00.v1",
            "e01.w1", "e02.w1");
    }

    // ==================== VOIP 自动接听(反编译确认) ====================

    // 底层接听方法: h2.a(boolean onlyAudio, boolean isVideo)
    public static Class<?> findVoipAcceptClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.plugin.voip.model.h2",
            "com.tencent.mm.plugin.voip.ui.h2",
            "com.tencent.mm.plugin.voip.v2.model.h2");
    }

    // 接听入口: d0.h()(视频) / d0.j()->d0.g0()(语音) / d0.D(int callType)(模拟)
    public static Class<?> findVoipEntryClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.plugin.voip.model.d0",
            "com.tencent.mm.plugin.voip.ui.d0",
            "com.tencent.mm.plugin.voip.v2.model.d0",
            "com.tencent.mm.plugin.voip.model.f0");
    }

    // ==================== Message/Notification ====================

    public static Class<?> findMsgStorageClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.storage.f9",
            "com.tencent.mm.storage.g9", "com.tencent.mm.storage.e9",
            "com.tencent.mm.storage.f8");
    }

    public static Class<?> findNotifyClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.plugin.notification.c.c",
            "com.tencent.mm.plugin.notification.c.d",
            "com.tencent.mm.plugin.notification.c.b");
    }

    // ==================== MainSettings ====================

    public static Class<?> findMainSettingsClass(ClassLoader cl) {
        return findClassMulti(cl,
            "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI",
            "com.tencent.mm.plugin.setting.ui.setting.MainSettingsUI",
            "com.tencent.mm.plugin.setting.ui.MainSettingsUI",
            "com.tencent.mm.plugin.setting.ui.setting.SettingsUI",
            "com.tencent.mm.plugin.setting.ui.setting.SettingUI",
            "com.tencent.mm.ui.setting.SettingsUI",
            "com.tencent.mm.plugin.setting.ui.setting_new.SettingsUI",
            "com.tencent.mm.ui.tools.preference.MMPreference",
            "com.tencent.mm.plugin.setting.ui.setting.SelfQRcodeUI");
    }

    // ==================== Storage/DB (short names) ====================

    public static Class<?> findMsgStorageShortClass(ClassLoader cl) {
        return findClassMulti(cl, "e01.d9", "e01.e9", "e01.d8", "e02.d9", "e02.e9",
            "d9", "e9", "c9", "d8", "e8");
    }

    public static Class<?> findMsgInfoStorageClass(ClassLoader cl) {
        // 8.0.78(3180) 真实消息类名 = com.tencent.mm.storage.e9 (已由 f9.Bb p0 实机验证)。
        // DexKit fallback 曾误中 com.tencent.maas.instamovie.MJPublisherSessionMetrics(美颜SDK),
        // 因此固定类名优先, DexKit 类名仅作候选兜底。
        Class<?> fixed = findClassMulti(cl, "com.tencent.mm.storage.e9",
            "com.tencent.mm.storage.d9", "com.tencent.mm.storage.f9",
            "com.tencent.mm.storage.e8");
        if (fixed != null) return fixed;

        // 仅当固定类名全部缺失时才尝试 DexKit 发现结果 (且必须带 storage 包路径)
        String dexKitE9 = com.leshao.v3.hook.DexKitHelper.getE9ClassName();
        if (dexKitE9 != null && dexKitE9.contains(".mm.storage.")) {
            try {
                return XposedHelpers.findClass(dexKitE9, cl);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 查找消息分发类 (x9) */
    public static Class<?> findMsgDispatchClass(ClassLoader cl) {
        for (String pkg : new String[]{"e01", "e02", "e00", "e03"}) {
            for (String suffix : new String[]{
                    "x9", "x8", "y9", "w9", "z9", "x10", "x11",
                    "a9", "b9", "c9", "v9", "u9", "t9"}) {
                try { return findClassMulti(cl, pkg + "." + suffix); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    public static Class<?> findAdapterClass(ClassLoader cl) {
        return findClassMulti(cl, "nw1.t2", "nw1.s2", "nw1.u2",
            "nv1.t2", "nx1.t2");
    }

    public static Class<?> findClassSafe(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            try {
                return XposedHelpers.findClass(name, cl);
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    // ==================== Voice/Media ====================

    public static Class<?> findVoiceMsgClass(ClassLoader cl) {
        return findClassMulti(cl, "e01.d9", "e02.d9", "e00.d9", "e03.d9",
            "e01.e9", "e01.d8", "v61.q1", "v61.q0", "v61.q2");
    }

    public static Class<?> findVoiceStreamClass(ClassLoader cl) {
        return findClassMulti(cl, "v61.b1", "v61.b2", "v61.b0", "v61.b3",
            "b31.w", "b32.w", "b30.w", "b33.w", "b31.v", "b31.x");
    }

    public static Class<?> findVoicePlayerClass(ClassLoader cl) {
        return findClassMulti(cl, "pv.p0", "pv.p1", "pv.p2", "pv.o0", "pv.o1",
            "wb0.c", "wb0.b",
            "y21.p0", "y22.p0", "y20.p0", "y23.p0", "y21.o0", "y21.q0");
    }

    public static Class<?> findPlayThreadClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.sdk.platformtools.h1",
            "com.tencent.mm.sdk.platformtools.h2",
            "com.tencent.mm.sdk.platformtools.g1");
    }

    /** 查找语音发送逻辑类 (v61.d1 VoiceLogic)，提供 h(注册)/u(发送)/s(直接发) 静态方法 */
    public static Class<?> findVoiceLogicClass(ClassLoader cl) {
        String dexKit = com.leshao.v3.hook.DexKitHelper.getVoiceApiClass();
        if (dexKit != null && !dexKit.isEmpty()) {
            try { return XposedHelpers.findClass(dexKit, cl); } catch (Throwable ignored) {}
        }
        return findClassMulti(cl, "v61.d1", "v61.d2", "v61.d0", "v61.d3",
            "v61.e1", "v61.e0", "v61.f1", "v61.d4");
    }

    /** 查找语音路径服务类 (pv.p0 / wb0.b)，提供 ej/getAmrFullPath 静态或实例方法 */
    public static Class<?> findVoicePathServiceClass(ClassLoader cl) {
        return findClassMulti(cl, "pv.p0", "pv.p1", "pv.o0", "pv.o1",
            "wb0.b", "wb0.c", "wb0.a");
    }

    // ==================== AntiRecall/Events ====================

    public static Class<?> findBd0SClass(ClassLoader cl) {
        return findClassMulti(cl, "bd0.s", "be0.s", "bc0.s", "bd1.s",
            "bd0.t", "bd0.r");
    }

    // ==================== UI/Theme ====================

    public static Class<?> findGaClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.ui.ga",
            "com.tencent.mm.ui.gb", "com.tencent.mm.ui.fa",
            "com.tencent.mm.ui.ha");
    }

    public static Class<?> findAppClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.app.h3",
            "com.tencent.mm.app.g3", "com.tencent.mm.app.i3",
            "com.tencent.mm.app.h4");
    }

    // ==================== Group/SNS ====================

    public static Class<?> findStickySorterClass(ClassLoader cl) {
        return findClassMulti(cl, "tm2.v8", "tm2.u8", "tm2.w8",
            "tl2.v8", "tn2.v8");
    }

    public static Class<?> findContextMenuClass(ClassLoader cl) {
        return findClassMulti(cl, "ly3.k3", "ly3.j3", "ly3.l3",
            "lx3.k3", "ly3.k4");
    }

    // ==================== Misc ====================

    public static Class<?> findModelBaseClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.modelbase.b",
            "com.tencent.mm.modelbase.c",
            "com.tencent.mm.modelbase.a");
    }

    public static Class<?> findKkKClass(ClassLoader cl) {
        return findClassMulti(cl, "kk.k", "kl.k", "kj.k",
            "kk.l", "kk.j");
    }

    // ==================== Contact get signature ====================

    public static String getContactSignature(Object contact) {
        for (String extraMethod : new String[]{"z0", "y0", "A0", "z1"}) {
            try {
                Object extra = XposedHelpers.callMethod(contact, extraMethod);
                if (extra != null) {
                    for (String sigMethod : new String[]{"getSignature", "signature", "a0", "A0"}) {
                        try {
                            Object result = XposedHelpers.callMethod(extra, sigMethod);
                            if (result instanceof String) return (String) result;
                        } catch (Throwable ignored) {}
                    }
                    break;
                }
            } catch (Throwable ignored) {}
        }
        return "";
    }

    // ==================== Utility ====================

    private static String md5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }
}
