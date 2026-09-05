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

    public static Class<?> findDbOpenerClass(ClassLoader cl) {
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
        Class<?> result = findClassMulti(cl, "ka5.f", "ka5.e", "ka4.f", "ka6.f", "ka5.g");
        if (result == null) {
            LogWriter.log(TAG, "findDbOpenerClass: ALL candidates NOT found");
        } else {
            LogWriter.log(TAG, "findDbOpenerClass: found " + result.getName());
        }
        return result;
    }

    public static Object openDatabase(Class<?> dbOpenerClass, String path, String password) {
        Throwable lastErr = null;

        // Priority 1: use DexKit method signature on ka5.f
        if (DexKitHelper.isScanComplete()) {
            String methodName = DexKitHelper.getDbOpenMethodName();
            String[] paramTypes = DexKitHelper.getDbOpenMethodParamTypes();
            if (methodName != null && paramTypes != null) {
                try {
                    Class<?>[] paramClasses = new Class<?>[paramTypes.length];
                    for (int i = 0; i < paramTypes.length; i++) {
                        paramClasses[i] = mapBasicType(paramTypes[i]);
                    }
                    Object[] args = new Object[paramTypes.length];
                    args[0] = path;
                    args[1] = password;
                    for (int i = 2; i < paramTypes.length; i++) {
                        if ("int".equals(paramTypes[i]) || "java.lang.Integer".equals(paramTypes[i])) {
                            args[i] = 0;
                        } else if ("boolean".equals(paramTypes[i]) || "java.lang.Boolean".equals(paramTypes[i])) {
                            args[i] = false;
                        } else {
                            args[i] = null;
                        }
                    }
                    try {
                        tryInitCsoLoader(dbOpenerClass.getClassLoader());
                        return dbOpenerClass.getDeclaredMethod(methodName, paramClasses)
                            .invoke(null, args);
                    } catch (Throwable e) {
                        lastErr = e;
                    }
                } catch (Throwable e) {
                    LogWriter.log(TAG, "openDatabase DexKit setup failed: " + e.getMessage());
                }
            }
        }

        // Priority 2: try known signatures on ka5.f
        tryInitCsoLoader(dbOpenerClass.getClassLoader());
        for (int flags : new int[]{0, 1}) {
            for (boolean b : new boolean[]{true, false}) {
                try {
                    return dbOpenerClass.getDeclaredMethod("s", String.class, String.class, int.class, boolean.class)
                        .invoke(null, path, password, flags, b);
                } catch (Throwable e) {
                    lastErr = e;
                }
            }
            try {
                return dbOpenerClass.getDeclaredMethod("r", String.class, String.class, int.class)
                    .invoke(null, path, password, flags);
            } catch (Throwable e) {
                lastErr = e;
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
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "openDatabase WCDB class load failed: " + e.getMessage());
        }

        boolean isNoClassDef = lastErr instanceof NoClassDefFoundError;
        if (!isNoClassDef) {
            LogWriter.log(TAG, "openDatabase FAILED for " + path + ": " + (lastErr != null ? lastErr.getClass().getSimpleName() + " " + lastErr.getMessage() : "unknown"));
        }
        return null;
    }

    public static Object openDatabaseWcdb(ClassLoader cl, String path, String password) {
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

    private static volatile boolean sTinkerNotFoundLogged = false;

    public static ClassLoader findTinkerClassLoader(ClassLoader cl) {
        // 优先使用 ContextManager 缓存的 Tinker ClassLoader
        ClassLoader cached = com.leshao.v3.ContextManager.getTinkerClassLoader();
        if (cached != null) {
            return cached;
        }
        // 尝试从主线程的 context ClassLoader 查找
        try {
            java.lang.reflect.Field threadField = android.os.Looper.class.getDeclaredField("sThreadLocal");
            threadField.setAccessible(true);
            Object threadLocal = threadField.get(null);
            java.lang.reflect.Method getMethod = threadLocal.getClass().getMethod("get");
            Thread mainThread = (Thread) getMethod.invoke(threadLocal);
            if (mainThread != null) {
                ClassLoader mainCL = mainThread.getContextClassLoader();
                if (mainCL != null) {
                    ClassLoader current = mainCL;
                    while (current != null) {
                        if (current.getClass().getName().contains("DelegateLastClassLoader")) {
                            LogWriter.log(TAG, "findTinkerClassLoader: found via main thread=" + current.getClass().getName());
                            return current;
                        }
                        current = current.getParent();
                    }
                }
            }
        } catch (Throwable ignored) {}
        for (ClassLoader start : new ClassLoader[] {
            cl,
            Thread.currentThread().getContextClassLoader(),
            com.leshao.v3.ContextManager.getAppContext() != null
                ? com.leshao.v3.ContextManager.getAppContext().getClassLoader() : null
        }) {
            if (start == null) continue;
            ClassLoader current = start;
            while (current != null) {
                String name = current.getClass().getName();
                if (name.contains("DelegateLastClassLoader")) {
                    LogWriter.log(TAG, "findTinkerClassLoader: found " + name);
                    return current;
                }
                current = current.getParent();
            }
        }
        if (!sTinkerNotFoundLogged) {
                    sTinkerNotFoundLogged = true;
                    // Tinker CL not available yet (DexKit scan not complete), will be found later
                }
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

    public static boolean isCsoLoaderReady() { return sCsoLoaderReady; }

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

    private static void tryInitCsoLoader(ClassLoader cl) {
        if (sCsoLoaderTried) return;
        sCsoLoaderTried = true;
        try {
            ClassLoader tkCL = findTinkerClassLoader(cl);
            if (tkCL == null) tkCL = cl;

            String csoLoaderClass = DexKitHelper.getCsoLoaderClass();
            if (csoLoaderClass == null) {
                csoLoaderClass = "com.tencent.cso.CsoLoader";
            }
            Class<?> cls = XposedHelpers.findClass(csoLoaderClass, tkCL);

            android.content.Context ctx = com.leshao.v3.ContextManager.getAppContext();
            String pkgName = ctx != null ? ctx.getPackageName() : "com.tencent.mm";

            for (java.lang.reflect.Method m : cls.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterTypes().length == 0) {
                    try {
                        m.invoke(null);
                        sCsoLoaderReady = true;
                        return;
                    } catch (Throwable ignored) {}
                }
            }

            for (java.lang.reflect.Method m : cls.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                int pc = m.getParameterTypes().length;
                if (pc == 0) continue;
                Object[] args = buildArgs(m.getParameterTypes(), ctx, cl, pkgName);
                try {
                    m.invoke(null, args);
                    sCsoLoaderReady = true;
                    return;
                } catch (Throwable ignored) {}
            }

            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterTypes().length == 0) {
                    m.setAccessible(true);
                    try {
                        m.invoke(null);
                        sCsoLoaderReady = true;
                        return;
                    } catch (Throwable ignored) {}
                    continue;
                }
                m.setAccessible(true);
                Object[] args = buildArgs(m.getParameterTypes(), ctx, cl, pkgName);
                try {
                    m.invoke(null, args);
                    sCsoLoaderReady = true;
                    return;
                } catch (Throwable ignored) {}
            }

            Object instance = null;
            java.lang.reflect.Constructor<?>[] ctors = cls.getDeclaredConstructors();
            for (java.lang.reflect.Constructor<?> ctor : ctors) {
                ctor.setAccessible(true);
                Object[] ctorArgs = buildArgs(ctor.getParameterTypes(), ctx, cl, pkgName);
                try {
                    instance = ctor.newInstance(ctorArgs);
                    break;
                } catch (Throwable ignored) {}
            }
            if (instance == null) {
                try {
                    java.lang.reflect.Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
                    f.setAccessible(true);
                    Object unsafe = f.get(null);
                    instance = Class.forName("sun.misc.Unsafe").getMethod("allocateInstance", Class.class).invoke(unsafe, cls);
                } catch (Throwable ignored) {}
            }

            if (instance != null) {
                for (java.lang.reflect.Method m : cls.getMethods()) {
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    boolean hasCsoLoaderParam = false;
                    for (Class<?> pt : m.getParameterTypes()) {
                        if (pt == cls) { hasCsoLoaderParam = true; break; }
                    }
                    if (!hasCsoLoaderParam) continue;
                    Object[] args = buildArgs(m.getParameterTypes(), ctx, cl, pkgName);
                    for (int i = 0; i < args.length; i++) {
                        if (m.getParameterTypes()[i] == cls) args[i] = instance;
                    }
                    try {
                        m.invoke(null, args);
                        sCsoLoaderReady = true;
                        return;
                    } catch (Throwable ignored) {}
                }

                for (java.lang.reflect.Method m : cls.getMethods()) {
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterTypes().length == 0) {
                        try {
                            m.invoke(instance);
                            sCsoLoaderReady = true;
                            return;
                        } catch (Throwable ignored) {}
                    }
                }
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    if ("nativeInitialize".equals(m.getName())
                        || "preloadAllInternal".equals(m.getName())
                        || "init".equals(m.getName())
                        || "initialize".equals(m.getName())) {
                        m.setAccessible(true);
                        Object[] args = buildArgs(m.getParameterTypes(), ctx, cl, pkgName);
                        try {
                            m.invoke(instance, args);
                            sCsoLoaderReady = true;
                            return;
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
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

    public static Class<?> findImeiClass(ClassLoader cl) {
        return findClassMulti(cl, "wo.w0", "wn.w0", "wp.w0",
            "wo.v0", "wo.x0", "vo.w0");
    }

    public static String getImei(ClassLoader cl) {
        ClassLoader tkCL = findTinkerClassLoader(cl);
        ClassLoader useCL = tkCL != null ? tkCL : cl;

        if (DexKitHelper.isScanComplete()) {
            String imeiClass = DexKitHelper.getImeiClassName();
            String imeiMethod = DexKitHelper.getImeiMethodName();
            if (imeiClass != null && imeiMethod != null) {
                try {
                    Class<?> cls = XposedHelpers.findClass(imeiClass, useCL);
                    String result = (String) cls.getDeclaredMethod(imeiMethod, boolean.class).invoke(null, true);
                    LogWriter.log(TAG, "getImei (DexKit): " + imeiClass + "." + imeiMethod + " via " + useCL.getClass().getSimpleName());
                    return result;
                } catch (Throwable e) {
                    // DexKit path fails silently, fallback to wo.w0.g always succeeds
                }
            }
        }
        Class<?> imeiClass = findImeiClass(useCL);
        if (imeiClass == null) imeiClass = findImeiClass(cl);
        if (imeiClass != null) {
            for (String m : new String[]{"g", "f", "h", "e"}) {
                try {
                    String result = (String) imeiClass.getDeclaredMethod(m, boolean.class).invoke(null, true);
                    LogWriter.log(TAG, "getImei: found via " + imeiClass.getName() + "." + m);
                    return result;
                }
                catch (Throwable ignored) {}
            }
        }
        LogWriter.log(TAG, "getImei fallback: 1234567890ABCDEF");
        return "1234567890ABCDEF";
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
        return findClassMulti(cl, "com.tencent.mm.storage.e9",
            "com.tencent.mm.storage.d9", "com.tencent.mm.storage.f9",
            "com.tencent.mm.storage.e8");
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
            "e01.e9", "e01.d8");
    }

    public static Class<?> findVoiceStreamClass(ClassLoader cl) {
        return findClassMulti(cl, "b31.w", "b32.w", "b30.w", "b33.w",
            "b31.v", "b31.x");
    }

    public static Class<?> findVoicePlayerClass(ClassLoader cl) {
        return findClassMulti(cl, "y21.p0", "y22.p0", "y20.p0", "y23.p0",
            "y21.o0", "y21.q0");
    }

    public static Class<?> findPlayThreadClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.sdk.platformtools.h1",
            "com.tencent.mm.sdk.platformtools.h2",
            "com.tencent.mm.sdk.platformtools.g1");
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
