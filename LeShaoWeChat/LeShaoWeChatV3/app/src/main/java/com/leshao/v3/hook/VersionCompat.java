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
    /** v955(问题13): 上次搜索完成时间, 未命中时允许冷却后重试, 避免永久判定"无 Tinker"。 */
    private static volatile long sTinkerSearchDoneAt = 0L;
    private static final long TINKER_RETRY_MS = 10000L;

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
            sTinkerSearchDoneAt = System.currentTimeMillis();
            return latestReal;
        }

        // Return cached result if available
        // v955(问题13): 缓存 CL 动态校验, 失效则丢弃并重新搜索
        if (sCachedTinkerClassLoader != null) {
            if (isValidWechatCl(sCachedTinkerClassLoader)) return sCachedTinkerClassLoader;
            LogWriter.log(TAG, "findTinkerClassLoader: cached CL invalid, re-searching");
            sCachedTinkerClassLoader = null;
            sTinkerSearchDone = false;
        }

        // v1025: 优先使用从微信运行时对象反查的真实 ClassLoader
        ClassLoader real = sWechatRealClassLoader;
        if (real != null && isValidWechatCl(real)) {
            sCachedTinkerClassLoader = real;
            sTinkerSearchDone = true;
            sTinkerSearchDoneAt = System.currentTimeMillis();
            LogWriter.log(TAG, "findTinkerClassLoader: using wechat real CL="
                + real.getClass().getSimpleName());
            return real;
        }

        // v955(问题13): 未命中不再永久锁定, 冷却后可重新搜索
        if (sTinkerSearchDone
                && System.currentTimeMillis() - sTinkerSearchDoneAt < TINKER_RETRY_MS) {
            return null;
        }

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
                    sTinkerSearchDoneAt = System.currentTimeMillis();
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
                        sTinkerSearchDoneAt = System.currentTimeMillis();
                        return mainCL;
                    }
                    mainCL = mainCL.getParent();
                }
            }
        } catch (Throwable ignored) {}

        sTinkerSearchDone = true;
        sTinkerSearchDoneAt = System.currentTimeMillis();
        return null;
    }

    /** v955(问题13): 校验 ClassLoader 是否可用(能加载微信核心类), 用于缓存动态失效。 */
    private static boolean isValidWechatCl(ClassLoader c) {
        if (c == null) return false;
        try {
            c.loadClass("com.tencent.mm.R");
            return true;
        } catch (Throwable ignored) {}
        try {
            c.loadClass("com.tencent.mm.storage.j4");
            return true;
        } catch (Throwable ignored) {}
        return false;
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
        // v955(问题5): 关闭裸开时不做任何 CsoLoader 反射初始化, 避免触碰微信内核
        if (!ENABLE_RAW_DB_OPEN) return;
        if (sCsoLoaderReady) return;
        try {
            ClassLoader tkCL = findTinkerClassLoader(cl);
            if (tkCL == null) tkCL = cl;

            // v955: 仅使用 DexKit 精确命中的类, 不再回退硬编码 "com.tencent.cso.CsoLoader"
            String csoLoaderClass = DexKitHelper.getCsoLoaderClass();
            if (csoLoaderClass == null || csoLoaderClass.isEmpty()) {
                LogWriter.log(TAG, "tryInitCsoLoader: CsoLoader class unknown (DexKit), skip");
                return;
            }
            Class<?> cls;
            try {
                cls = XposedHelpers.findClass(csoLoaderClass, tkCL);
            } catch (Throwable e) {
                LogWriter.log(TAG, "tryInitCsoLoader: class not found " + csoLoaderClass
                        + " via " + tkCL.getClass().getSimpleName() + " err=" + errDetail(e));
                return;
            }

            // v955(问题5): 只调用 DexKit 精确入口; 禁止遍历所有方法/构造器/Unsafe 盲调
            String entry = DexKitHelper.getCsoLoaderMethod();
            if (entry == null || entry.isEmpty()) {
                LogWriter.log(TAG, "tryInitCsoLoader: no DexKit entry method for " + cls.getName() + ", skip");
                return;
            }
            if (invokeCsoEntry(cls, entry)) {
                sCsoLoaderReady = true;
                LogWriter.log(TAG, "tryInitCsoLoader: OK via DexKit " + cls.getName() + "." + entry);
                return;
            }
            for (String named : new String[]{"nativeInitialize", "preloadAllInternal", "init", "initialize"}) {
                if (named.equals(entry)) continue;
                if (invokeCsoEntry(cls, named)) {
                    sCsoLoaderReady = true;
                    LogWriter.log(TAG, "tryInitCsoLoader: OK via named " + cls.getName() + "." + named);
                    return;
                }
            }
            LogWriter.log(TAG, "tryInitCsoLoader: entry '" + entry + "' not invokable for " + cls.getName());
        } catch (Throwable e) {
            LogWriter.log(TAG, "tryInitCsoLoader: outer err=" + errDetail(e));
        }
    }

    /**
     * v955(问题5): 仅以「静态无参」或「实例无参」方式调用单个入口方法, 成功返回 true。
     * 实例化只用无参构造器或 Unsafe.allocateInstance, 不做带参构造/全量方法盲调。
     */
    private static boolean invokeCsoEntry(Class<?> cls, String name) {
        try {
            java.lang.reflect.Method m = cls.getDeclaredMethod(name);
            if (isJunkMethod(m) || m.getParameterTypes().length != 0) return false;
            m.setAccessible(true);
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                m.invoke(null);
                return true;
            }
            Object instance = newCsoInstance(cls);
            if (instance == null) return false;
            m.invoke(instance);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** v955(问题5): 无参构造优先, 失败再 Unsafe 分配; 均失败返回 null。 */
    private static Object newCsoInstance(Class<?> cls) {
        try {
            java.lang.reflect.Constructor<?> c = cls.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            return Class.forName("sun.misc.Unsafe").getMethod("allocateInstance", Class.class).invoke(unsafe, cls);
        } catch (Throwable ignored) {
            return null;
        }
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
        // v1134: 混淆改名导致哈希类找不到时, 直接从磁盘取真实账号目录名 (含 EnMicroMsg.db 的目录, 取最新)。
        // 这比 md5("mm"+uin) 可靠 —— 后者几乎永远不是真实目录, 会让调用方定位到不存在的路径。
        String diskHash = findAccountDirByDisk();
        if (diskHash != null) {
            LogWriter.log(TAG, "getDbHash from disk: " + diskHash);
            return diskHash;
        }
        String fallback = md5("mm" + uin);
        LogWriter.log(TAG, "getDbHash fallback: " + fallback);
        return fallback;
    }

    /** v1134: 当前进程用户目录下, 含 EnMicroMsg.db 的账号目录名 (取最新修改)。 */
    private static String findAccountDirByDisk() {
        try {
            int currentUser = android.os.Process.myUid() / 100000;
            java.io.File md = new java.io.File(
                "/data/user/" + currentUser + "/com.tencent.mm/MicroMsg");
            java.io.File[] dirs = md.listFiles();
            if (dirs == null) return null;
            java.io.File best = null;
            long bestM = -1L;
            for (java.io.File d : dirs) {
                if (!d.isDirectory()) continue;
                java.io.File db = new java.io.File(d, "EnMicroMsg.db");
                if (db.exists() && db.lastModified() > bestM) {
                    bestM = db.lastModified();
                    best = d;
                }
            }
            return best != null ? best.getName() : null;
        } catch (Throwable ignored) {
            return null;
        }
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

    // ==================== AntiRecall ====================

    public static Class<?> findAntiRecallClass(ClassLoader cl) {
        return findClassMulti(cl, "af5.a", "af6.a", "af4.a", "af7.a",
            "ag5.a", "ae5.a", "af5.b");
    }

    public static Class<?> findAntiRecallProtoClass(ClassLoader cl) {
        return findClassMulti(cl, "e01.u", "e02.u", "e00.u", "e03.u",
            "e01.t", "e02.t", "e01.v", "e00.t");
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
