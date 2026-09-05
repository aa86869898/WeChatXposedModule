package com.leshao.v3.hook;

import android.app.Application;

import com.leshao.v3.LogWriter;
import com.tencent.mmkv.MMKV;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.DexKitCacheBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class DexKitHelper {

    private static final String TAG = "DexKit";
    private static final String MMKV_RESULTS_ID = "dexkit_scan";
    private static final String KEY_P06_CLASS = "p06_class";
    private static final String KEY_DB_OPENER_CLASS = "db_opener_class";
    private static final String KEY_DB_OPEN_METHOD = "db_open_method";
    private static final String KEY_DB_OPEN_PARAMS = "db_open_params";
    private static final String KEY_IMEI_CLASS = "imei_class";
    private static final String KEY_IMEI_METHOD = "imei_method";
    private static final String KEY_CSO_LOADER = "cso_loader";
    private static final String KEY_J1_CALLER_CLASS = "j1_caller_class";
    private static final String KEY_J1_CALLER_METHOD = "j1_caller_method";
    private static final String KEY_CONTACT_STORAGE = "contact_storage";
    private static final String KEY_CHAT_OPEN_CLASS = "chat_open_class";
    private static final String KEY_CHAT_OPEN_METHOD = "chat_open_method";
    private static final String KEY_CONV_SCROLL_CLASS = "conv_scroll_class";
    private static final String KEY_CONV_SCROLL_METHOD = "conv_scroll_method";
    private static final String KEY_CONV_LONGPRESS_CLASS = "conv_longpress_class";
    private static final String KEY_CONV_LONGPRESS_METHOD = "conv_longpress_method";
    private static final String KEY_CONV_MENU_CLASS = "conv_menu_class";
    private static final String KEY_CONV_MENU_METHOD = "conv_menu_method";

    private static final AtomicBoolean sLibraryLoaded = new AtomicBoolean(false);
    private static volatile boolean sScanComplete = false;
    private static volatile Runnable sPostScanCallback;

    private static volatile String sP06ClassName;
    private static volatile String sDbOpenerClass;
    private static volatile String sDbOpenMethodName;
    private static volatile String[] sDbOpenMethodParamTypes;
    private static volatile String sImeiClassName;
    private static volatile String sImeiMethodName;
    private static volatile String sCsoLoaderClass;
    private static volatile String sJ1CallerClass;
    private static volatile String sJ1CallerMethod;
    private static volatile String sContactStorageClass;
    private static volatile String sChatOpenClass;
    private static volatile String sChatOpenMethod;
    private static volatile String sConvScrollClass;
    private static volatile String sConvScrollMethod;
    private static volatile String sConvLongPressClass;
    private static volatile String sConvLongPressMethod;
    private static volatile String sConvMenuClass;
    private static volatile String sConvMenuMethod;
    private static volatile List<String> sConvLongPressImpls = new java.util.ArrayList<>();
    private static volatile List<String> sMenuG4Impls = new java.util.ArrayList<>();

    private static volatile int sVersionCode = 0;

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "DexKitScan-" + threadNumber.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }
    });

    public static boolean isScanComplete() { return sScanComplete; }

    public static void setPostScanCallback(Runnable callback) {
        if (sScanComplete) {
            callback.run();
        } else {
            sPostScanCallback = callback;
        }
    }

    public static void setVersionCode(int versionCode) {
        sVersionCode = versionCode;
    }

    public static String getP06ClassName() { return sP06ClassName; }
    public static String getDbOpenerClass() { return sDbOpenerClass; }
    public static String getDbOpenMethodName() { return sDbOpenMethodName; }
    public static String[] getDbOpenMethodParamTypes() { return sDbOpenMethodParamTypes; }
    public static String getImeiClassName() { return sImeiClassName; }
    public static String getImeiMethodName() { return sImeiMethodName; }
    public static String getCsoLoaderClass() { return sCsoLoaderClass; }
    public static String getJ1CallerClass() { return sJ1CallerClass; }
    public static String getJ1CallerMethod() { return sJ1CallerMethod; }
    public static String getContactStorageClass() { return sContactStorageClass; }
    public static String getChatOpenClass() { return sChatOpenClass; }
    public static String getChatOpenMethod() { return sChatOpenMethod; }
    public static String getConvScrollClass() { return sConvScrollClass; }
    public static String getConvScrollMethod() { return sConvScrollMethod; }
    public static String getConvLongPressClass() { return sConvLongPressClass; }
    public static String getConvLongPressMethod() { return sConvLongPressMethod; }
    public static String getConvMenuClass() { return sConvMenuClass; }
    public static String getConvMenuMethod() { return sConvMenuMethod; }

    /** 长按监听实现类列表（DexKit 全包反查 OnItemLongClickListener 实现），供 Bug3 hook 使用 */
    public static List<String> getConvLongPressImpls() {
        List<String> copy = new java.util.ArrayList<>();
        for (String s : sConvLongPressImpls) copy.add(s);
        return copy;
    }

    /** kc5.g4(Menu 接口) 的实现类列表，供菜单注入定位使用 */
    public static List<String> getMenuG4Impls() {
        List<String> copy = new java.util.ArrayList<>();
        for (String s : sMenuG4Impls) copy.add(s);
        return copy;
    }

    private static synchronized void loadDexKitLibrary(Application app) {
        if (sLibraryLoaded.get()) return;
        try {
            System.loadLibrary("dexkit");
            sLibraryLoaded.set(true);
            LogWriter.log(TAG, "libdexkit loaded via System.loadLibrary");
            return;
        } catch (Throwable e) {
            LogWriter.log(TAG, "loadLibrary failed: " + e.getMessage());
        }

        try {
            android.content.pm.ApplicationInfo moduleInfo = app.getPackageManager()
                .getApplicationInfo("com.leshao.v3", 0);
            String nativeLibDir = moduleInfo.nativeLibraryDir;
            if (nativeLibDir != null) {
                java.io.File soFile = new java.io.File(nativeLibDir, "libdexkit.so");
                if (soFile.exists()) {
                    System.load(soFile.getAbsolutePath());
                    sLibraryLoaded.set(true);
                    LogWriter.log(TAG, "libdexkit loaded from " + soFile.getAbsolutePath());
                    return;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "nativeLibraryDir load failed: " + e.getMessage());
        }

        try {
            java.io.File tmpDir = new java.io.File(app.getFilesDir(), "dexkit_native");
            tmpDir.mkdirs();
            java.io.File tmpSo = new java.io.File(tmpDir, "libdexkit.so");
            if (!tmpSo.exists()) {
                java.io.InputStream in = app.getClass().getClassLoader()
                    .getResourceAsStream("lib/arm64-v8a/libdexkit.so");
                if (in == null) {
                    in = app.getClass().getClassLoader()
                        .getResourceAsStream("lib/armeabi-v7a/libdexkit.so");
                }
                if (in != null) {
                    java.io.FileOutputStream out = new java.io.FileOutputStream(tmpSo);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    out.close();
                    in.close();
                }
            }
            if (tmpSo.exists()) {
                System.load(tmpSo.getAbsolutePath());
                sLibraryLoaded.set(true);
                LogWriter.log(TAG, "libdexkit loaded from extracted " + tmpSo.getAbsolutePath());
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "extract load failed: " + e.getMessage());
        }
    }

    private static volatile boolean sCacheInitialized = false;

    private static synchronized void initDexKitCache(android.content.Context appContext) {
        if (sCacheInitialized) return;
        try {
            MMKV.initialize(appContext);
            DexKitCacheBridge.setIdleTimeoutMillis(5000L);
            DexKitCacheBridge.setCachePolicy(
                new DexKitCacheBridge.CachePolicy(true, DexKitCacheBridge.CacheFailurePolicy.NONE)
            );
            DexKitCacheBridge.init(new MmkvCacheStorage());
            sCacheInitialized = true;
            LogWriter.log(TAG, "DexKit cache initialized (MMKV)");
        } catch (Throwable e) {
            LogWriter.log(TAG, "DexKit cache init failed: " + e.getMessage());
        }
    }

    private static void loadResultsFromMMKV(Application app) {
        try {
            MMKV kv = MMKV.mmkvWithID(MMKV_RESULTS_ID, MMKV.MULTI_PROCESS_MODE);
            String p06 = kv.decodeString(KEY_P06_CLASS, null);
            String dbOpener = kv.decodeString(KEY_DB_OPENER_CLASS, null);
            String dbMethod = kv.decodeString(KEY_DB_OPEN_METHOD, null);
            String dbParams = kv.decodeString(KEY_DB_OPEN_PARAMS, null);
            String imeiClass = kv.decodeString(KEY_IMEI_CLASS, null);
            String imeiMethod = kv.decodeString(KEY_IMEI_METHOD, null);
            String csoLoader = kv.decodeString(KEY_CSO_LOADER, null);
            String j1CallerClass = kv.decodeString(KEY_J1_CALLER_CLASS, null);
            String j1CallerMethod = kv.decodeString(KEY_J1_CALLER_METHOD, null);
            String contactStorage = kv.decodeString(KEY_CONTACT_STORAGE, null);
            String chatOpenClass = kv.decodeString(KEY_CHAT_OPEN_CLASS, null);
            String chatOpenMethod = kv.decodeString(KEY_CHAT_OPEN_METHOD, null);
            String convScrollClass = kv.decodeString(KEY_CONV_SCROLL_CLASS, null);
            String convScrollMethod = kv.decodeString(KEY_CONV_SCROLL_METHOD, null);
            String convLpClass = kv.decodeString(KEY_CONV_LONGPRESS_CLASS, null);
            String convLpMethod = kv.decodeString(KEY_CONV_LONGPRESS_METHOD, null);
            String convMenuClass = kv.decodeString(KEY_CONV_MENU_CLASS, null);
            String convMenuMethod = kv.decodeString(KEY_CONV_MENU_METHOD, null);

            if (p06 == null && dbOpener == null && imeiClass == null && csoLoader == null
                && j1CallerClass == null && contactStorage == null
                && chatOpenClass == null && convScrollClass == null && convLpClass == null
                && convMenuClass == null) {
                LogWriter.log(TAG, "loadResultsFromMMKV: no cached results");
                return;
            }

            sP06ClassName = p06;
            sDbOpenerClass = dbOpener;
            sDbOpenMethodName = dbMethod;
            if (dbParams != null && !dbParams.isEmpty()) {
                sDbOpenMethodParamTypes = dbParams.split("\\|");
            }
            sImeiClassName = imeiClass;
            sImeiMethodName = imeiMethod;
            sCsoLoaderClass = csoLoader;
            sJ1CallerClass = j1CallerClass;
            sJ1CallerMethod = j1CallerMethod;
            sContactStorageClass = contactStorage;
            sChatOpenClass = chatOpenClass;
            sChatOpenMethod = chatOpenMethod;
            sConvScrollClass = convScrollClass;
            sConvScrollMethod = convScrollMethod;
            sConvLongPressClass = convLpClass;
            sConvLongPressMethod = convLpMethod;
            sConvMenuClass = convMenuClass;
            sConvMenuMethod = convMenuMethod;

            sScanComplete = true;
            LogWriter.log(TAG, "loadResultsFromMMKV: loaded cached results");

            if (sPostScanCallback != null) {
                try {
                    sPostScanCallback.run();
                } catch (Throwable e) {
                    LogWriter.log(TAG, "postScanCallback error: " + e.getMessage());
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "loadResultsFromMMKV error: " + e.getMessage());
        }
    }

    private static void saveResultsToMMKV() {
        try {
            MMKV kv = MMKV.mmkvWithID(MMKV_RESULTS_ID, MMKV.MULTI_PROCESS_MODE);
            if (sP06ClassName != null) kv.encode(KEY_P06_CLASS, sP06ClassName);
            if (sDbOpenerClass != null) kv.encode(KEY_DB_OPENER_CLASS, sDbOpenerClass);
            if (sDbOpenMethodName != null) kv.encode(KEY_DB_OPEN_METHOD, sDbOpenMethodName);
            if (sDbOpenMethodParamTypes != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < sDbOpenMethodParamTypes.length; i++) {
                    if (i > 0) sb.append("|");
                    sb.append(sDbOpenMethodParamTypes[i]);
                }
                kv.encode(KEY_DB_OPEN_PARAMS, sb.toString());
            }
            if (sImeiClassName != null) kv.encode(KEY_IMEI_CLASS, sImeiClassName);
            if (sImeiMethodName != null) kv.encode(KEY_IMEI_METHOD, sImeiMethodName);
            if (sCsoLoaderClass != null) kv.encode(KEY_CSO_LOADER, sCsoLoaderClass);
            if (sJ1CallerClass != null) kv.encode(KEY_J1_CALLER_CLASS, sJ1CallerClass);
            if (sJ1CallerMethod != null) kv.encode(KEY_J1_CALLER_METHOD, sJ1CallerMethod);
            if (sContactStorageClass != null) kv.encode(KEY_CONTACT_STORAGE, sContactStorageClass);
            if (sChatOpenClass != null) kv.encode(KEY_CHAT_OPEN_CLASS, sChatOpenClass);
            if (sChatOpenMethod != null) kv.encode(KEY_CHAT_OPEN_METHOD, sChatOpenMethod);
            if (sConvScrollClass != null) kv.encode(KEY_CONV_SCROLL_CLASS, sConvScrollClass);
            if (sConvScrollMethod != null) kv.encode(KEY_CONV_SCROLL_METHOD, sConvScrollMethod);
            if (sConvLongPressClass != null) kv.encode(KEY_CONV_LONGPRESS_CLASS, sConvLongPressClass);
            if (sConvLongPressMethod != null) kv.encode(KEY_CONV_LONGPRESS_METHOD, sConvLongPressMethod);
            if (sConvMenuClass != null) kv.encode(KEY_CONV_MENU_CLASS, sConvMenuClass);
            if (sConvMenuMethod != null) kv.encode(KEY_CONV_MENU_METHOD, sConvMenuMethod);
            LogWriter.log(TAG, "saveResultsToMMKV: done");
        } catch (Throwable e) {
            LogWriter.log(TAG, "saveResultsToMMKV error: " + e.getMessage());
        }
    }

    private static void scanWechatTargets(final DexKitCacheBridge.RecyclableBridge cacheBridge) {
        LogWriter.log(TAG, "scanWechatTargets: using DexKitCacheBridge instance");

        cacheBridge.withBridge(new DexKitCacheBridge.RecyclableBridge.BridgeFunction() {
            @Override
            public void apply(DexKitBridge b) {
                findP06Class(b);
                findDbOpenerMethods(b);
                findImeiClass(b);
                findCsoLoader(b);
                findContactStorageAlt(b);
                findJ1Methods(b);
                findRealP06Class(b);
                findChatOpenEntry(b);
                findConvLongPressEntry(b);
                findConvScrollEntry(b);
                findConvMenuEntry(b);
                findMenuG4Impls(b);
            }
        });

        sScanComplete = true;
        LogWriter.log(TAG, "scan complete: p06=" + sP06ClassName
            + " dbOpener=" + sDbOpenerClass + "." + sDbOpenMethodName
            + " imei=" + sImeiClassName + "." + sImeiMethodName
            + " cso=" + sCsoLoaderClass
            + " j1Caller=" + sJ1CallerClass + "." + sJ1CallerMethod
            + " contactStorage=" + sContactStorageClass
            + " chatOpen=" + sChatOpenClass + "." + sChatOpenMethod
            + " convScroll=" + sConvScrollClass + "." + sConvScrollMethod
            + " convLongPress=" + sConvLongPressClass + "." + sConvLongPressMethod
            + " convMenu=" + sConvMenuClass + "." + sConvMenuMethod);

        saveResultsToMMKV();

        if (sPostScanCallback != null) {
            try {
                sPostScanCallback.run();
            } catch (Throwable e) {
                LogWriter.log(TAG, "postScanCallback error: " + e.getMessage());
            }
        }
    }

    private static void findP06Class(DexKitBridge bridge) {
        try {
            MethodMatcher mMatcher = MethodMatcher.create()
                .usingStrings("Kernel not initialized");
            List<MethodData> methods = bridge.findMethod(
                FindMethod.create().matcher(mMatcher)
            );

            LogWriter.log(TAG, "findP06Class: " + methods.size() + " methods use 'Kernel not initialized'");
            for (MethodData m : methods) {
                LogWriter.log(TAG, "  candidate: " + m.getClassName() + "." + m.getName()
                    + " params=" + m.getParamTypeNames());
            }

            for (MethodData m : methods) {
                String clsName = m.getClassName();
                if ("hm0.j1".equals(clsName)) continue;
                if ("b".equals(m.getName())) {
                    sP06ClassName = clsName;
                    LogWriter.log(TAG, "findP06Class (b+String): " + clsName + "." + m.getName());
                    return;
                }
            }

            for (MethodData m : methods) {
                String clsName = m.getClassName();
                if ("hm0.j1".equals(clsName)) continue;
                sP06ClassName = clsName;
                LogWriter.log(TAG, "findP06Class (first non-hm0.j1): " + clsName + "." + m.getName());
                return;
            }

            LogWriter.log(TAG, "findP06Class: NOT found (string-based)");
        } catch (Throwable e) {
            LogWriter.log(TAG, "findP06Class error: " + e.getMessage());
        }
    }

    private static void findDbOpenerMethods(DexKitBridge bridge) {
        try {
            MethodMatcher mMatcher = MethodMatcher.create()
                .usingStrings("EnMicroMsg")
                .modifiers(java.lang.reflect.Modifier.STATIC)
                .paramCount(4)
                .paramTypes("java.lang.String", "java.lang.String", "int", "boolean");
            List<MethodData> methods = bridge.findMethod(
                FindMethod.create().matcher(mMatcher)
            );
            LogWriter.log(TAG, "findDbOpener: " + methods.size() + " static methods match 'EnMicroMsg' + 4 params (String,String,int,boolean)");

            if (!methods.isEmpty()) {
                MethodData m = methods.get(0);
                sDbOpenerClass = m.getClassName();
                sDbOpenMethodName = m.getName();
                sDbOpenMethodParamTypes = m.getParamTypeNames().toArray(new String[0]);
                LogWriter.log(TAG, "findDbOpener: " + sDbOpenerClass + "." + sDbOpenMethodName + " params=" + sDbOpenMethodParamTypes.length);
                return;
            }

            MethodMatcher mMatcher3 = MethodMatcher.create()
                .usingStrings("MicroMsg")
                .modifiers(java.lang.reflect.Modifier.STATIC)
                .paramCount(4)
                .paramTypes("java.lang.String", "java.lang.String", "int", "boolean");
            List<MethodData> methods3 = bridge.findMethod(
                FindMethod.create().matcher(mMatcher3)
            );
            LogWriter.log(TAG, "findDbOpener: " + methods3.size() + " static methods match 'MicroMsg' + 4 params");
            if (!methods3.isEmpty()) {
                MethodData m = methods3.get(0);
                sDbOpenerClass = m.getClassName();
                sDbOpenMethodName = m.getName();
                sDbOpenMethodParamTypes = m.getParamTypeNames().toArray(new String[0]);
                LogWriter.log(TAG, "findDbOpener: " + sDbOpenerClass + "." + sDbOpenMethodName);
                return;
            }

            MethodMatcher mMatcher2 = MethodMatcher.create()
                .usingStrings("EnMicroMsg")
                .modifiers(java.lang.reflect.Modifier.STATIC)
                .paramCount(2);
            List<MethodData> methods2 = bridge.findMethod(
                FindMethod.create().matcher(mMatcher2)
            );
            LogWriter.log(TAG, "findDbOpener: " + methods2.size() + " static methods match 'EnMicroMsg' + 2 params");
            for (MethodData m : methods2) {
                List<String> pts = m.getParamTypeNames();
                if (pts.size() >= 2 && "java.lang.String".equals(pts.get(0)) && "java.lang.String".equals(pts.get(1))) {
                    sDbOpenerClass = m.getClassName();
                    sDbOpenMethodName = m.getName();
                    sDbOpenMethodParamTypes = pts.toArray(new String[0]);
                    LogWriter.log(TAG, "findDbOpener: " + sDbOpenerClass + "." + sDbOpenMethodName + " params=" + pts.size());
                    return;
                }
            }

            LogWriter.log(TAG, "findDbOpener: NOT found");
        } catch (Throwable e) {
            LogWriter.log(TAG, "findDbOpener error: " + e.getMessage());
        }
    }

    private static void findCsoLoader(DexKitBridge bridge) {
        try {
            MethodMatcher mMatcher = MethodMatcher.create()
                .usingStrings("CsoLoader")
                .modifiers(java.lang.reflect.Modifier.STATIC)
                .paramCount(0);
            List<MethodData> methods = bridge.findMethod(
                FindMethod.create().matcher(mMatcher)
            );
            LogWriter.log(TAG, "findCsoLoader: " + methods.size() + " static 0-param methods use 'CsoLoader'");
            for (MethodData m : methods) {
                LogWriter.log(TAG, "  candidate: " + m.getClassName() + "." + m.getName()
                    + " params=" + m.getParamTypeNames());
            }

            if (!methods.isEmpty()) {
                sCsoLoaderClass = methods.get(0).getClassName();
                LogWriter.log(TAG, "findCsoLoader: " + sCsoLoaderClass + "." + methods.get(0).getName());
                return;
            }

            MethodMatcher mMatcher2 = MethodMatcher.create()
                .usingStrings("CsoLoader")
                .paramCount(0);
            List<MethodData> methods2 = bridge.findMethod(
                FindMethod.create().matcher(mMatcher2)
            );
            LogWriter.log(TAG, "findCsoLoader: " + methods2.size() + " 0-param methods use 'CsoLoader'");
            for (MethodData m : methods2) {
                String clsName = m.getClassName();
                if (clsName != null && clsName.contains("CsoLoader")) {
                    sCsoLoaderClass = clsName;
                    LogWriter.log(TAG, "findCsoLoader (name match): " + clsName + "." + m.getName());
                    return;
                }
            }

            if (!methods2.isEmpty()) {
                sCsoLoaderClass = methods2.get(0).getClassName();
                LogWriter.log(TAG, "findCsoLoader (first): " + sCsoLoaderClass + "." + methods2.get(0).getName());
                return;
            }

            LogWriter.log(TAG, "findCsoLoader: NOT found");
        } catch (Throwable e) {
            LogWriter.log(TAG, "findCsoLoader error: " + e.getMessage());
        }
    }

    private static void findImeiClass(DexKitBridge bridge) {
        try {
            MethodMatcher mMatcher = MethodMatcher.create()
                .usingStrings("android.permission.READ_PHONE_STATE")
                .returnType("java.lang.String");
            List<MethodData> methods = bridge.findMethod(
                FindMethod.create().matcher(mMatcher)
            );
            LogWriter.log(TAG, "findImeiClass: " + methods.size() + " methods use 'READ_PHONE_STATE' return String");
            for (MethodData m : methods) {
                sImeiClassName = m.getClassName();
                sImeiMethodName = m.getName();
                LogWriter.log(TAG, "findImeiClass: " + sImeiClassName + "." + sImeiMethodName);
                return;
            }

            MethodMatcher mMatcher2 = MethodMatcher.create()
                .usingStrings("getDeviceId")
                .returnType("java.lang.String");
            List<MethodData> methods2 = bridge.findMethod(
                FindMethod.create().matcher(mMatcher2)
            );
            LogWriter.log(TAG, "findImeiClass: " + methods2.size() + " methods use 'getDeviceId' return String");
            for (MethodData m : methods2) {
                sImeiClassName = m.getClassName();
                sImeiMethodName = m.getName();
                LogWriter.log(TAG, "findImeiClass (getDeviceId): " + sImeiClassName + "." + sImeiMethodName);
                return;
            }

            MethodMatcher mMatcher3 = MethodMatcher.create()
                .paramTypes("boolean")
                .returnType("java.lang.String");
            List<MethodData> methods3 = bridge.findMethod(
                FindMethod.create().searchPackages(
                    "wo", "wn", "wp", "vo", "vn", "vp", "xo", "xn", "xp"
                ).matcher(mMatcher3)
            );
            for (MethodData m : methods3) {
                String clsName = m.getClassName();
                if (clsName != null && clsName.endsWith("w0")) {
                    sImeiClassName = clsName;
                    sImeiMethodName = m.getName();
                    LogWriter.log(TAG, "findImeiClass (package): " + clsName + "." + m.getName());
                    return;
                }
            }

            LogWriter.log(TAG, "findImeiClass: NOT found");
        } catch (Throwable e) {
            LogWriter.log(TAG, "findImeiClass error: " + e.getMessage());
        }
    }

    private static void findJ1Caller(DexKitBridge bridge) {
        try {
            MethodMatcher callerMatcher = MethodMatcher.create()
                .addInvoke(MethodMatcher.create()
                    .declaredClass("hm0.j1")
                    .name("s")
                    .paramTypes("java.lang.Class"));
            List<MethodData> methods = bridge.findMethod(
                FindMethod.create().matcher(callerMatcher)
            );

            LogWriter.log(TAG, "findJ1Caller: " + methods.size() + " methods invoke hm0.j1.s(Class)");
            int limit = Math.min(methods.size(), 10);
            for (int i = 0; i < limit; i++) {
                MethodData m = methods.get(i);
                LogWriter.log(TAG, "  j1 caller[" + i + "]: " + m.getClassName() + "." + m.getName()
                    + " params=" + m.getParamTypeNames() + " return=" + m.getReturnTypeName());
            }
            if (methods.size() > 10) {
                LogWriter.log(TAG, "  ... " + (methods.size() - 10) + " more j1 callers omitted");
            }

            if (!methods.isEmpty()) {
                MethodData first = methods.get(0);
                sJ1CallerClass = first.getClassName();
                sJ1CallerMethod = first.getName();
                LogWriter.log(TAG, "findJ1Caller: " + sJ1CallerClass + "." + sJ1CallerMethod);
            } else {
                LogWriter.log(TAG, "findJ1Caller: NOT found");
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findJ1Caller error: " + e.getMessage());
        }
    }

    private static void findContactStorageAlt(DexKitBridge bridge) {
        try {
            MethodMatcher mMatcher = MethodMatcher.create()
                .name("ij")
                .returnType("long");
            List<MethodData> methods = bridge.findMethod(
                FindMethod.create().matcher(mMatcher)
            );
            LogWriter.log(TAG, "findContactStorageAlt: " + methods.size() + " ij() returning long");
            int ctLimit = Math.min(methods.size(), 10);
            for (int i = 0; i < ctLimit; i++) {
                MethodData m = methods.get(i);
                String clsName = m.getClassName();
                LogWriter.log(TAG, "  ij candidate: " + clsName + "." + m.getName()
                    + " return=" + m.getReturnTypeName());
                if (clsName != null && clsName.contains("storage")) {
                    sContactStorageClass = clsName;
                    LogWriter.log(TAG, "findContactStorageAlt: " + clsName + ".ij()");
                    return;
                }
            }
            if (methods.size() > 10) {
                LogWriter.log(TAG, "  ... " + (methods.size() - 10) + " more ij() results omitted");
            }

            MethodMatcher objMatcher = MethodMatcher.create()
                .name("ij");
            List<MethodData> objMethods = bridge.findMethod(
                FindMethod.create().matcher(objMatcher)
            );
            LogWriter.log(TAG, "findContactStorageAlt: " + objMethods.size() + " total ij()");
            for (MethodData m : objMethods) {
                String clsName = m.getClassName();
                if (clsName != null && clsName.contains("storage")) {
                    sContactStorageClass = clsName;
                    LogWriter.log(TAG, "findContactStorageAlt (storage): " + clsName + ".ij()");
                    return;
                }
            }

            LogWriter.log(TAG, "findContactStorageAlt: NOT found");
        } catch (Throwable e) {
            LogWriter.log(TAG, "findContactStorageAlt error: " + e.getMessage());
        }
    }

    private static void findJ1Methods(DexKitBridge bridge) {
        try {
            MethodMatcher mMatcher = MethodMatcher.create()
                .declaredClass("hm0.j1");
            List<MethodData> methods = bridge.findMethod(
                FindMethod.create().matcher(mMatcher)
            );
            LogWriter.log(TAG, "findJ1Methods: " + methods.size() + " methods in hm0.j1");
            int limit = Math.min(methods.size(), 20);
            for (int i = 0; i < limit; i++) {
                MethodData m = methods.get(i);
                LogWriter.log(TAG, "  hm0.j1." + m.getName() + " params=" + m.getParamTypeNames()
                    + " return=" + m.getReturnTypeName());
            }
            if (methods.size() > 20) {
                LogWriter.log(TAG, "  ... " + (methods.size() - 20) + " more hm0.j1 methods");
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findJ1Methods error: " + e.getMessage());
        }
    }

    private static void findRealP06Class(DexKitBridge bridge) {
        try {
            ClassMatcher matcher = ClassMatcher.create();
            List<ClassData> classes = bridge.findClass(
                FindClass.create().matcher(matcher)
            );
            for (ClassData c : classes) {
                String name = c.getName();
                if (name != null && (name.equals("p06") || name.endsWith(".p06"))) {
                    sP06ClassName = name;
                    LogWriter.log(TAG, "findRealP06Class: " + name);
                    return;
                }
            }
            LogWriter.log(TAG, "findRealP06Class: class named 'p06' NOT found in any dex");
        } catch (Throwable e) {
            LogWriter.log(TAG, "findRealP06Class error: " + e.getMessage());
        }
    }

    /** 规则1(bug1 悬浮球只显示一次)：定位聊天窗口打开的生命周期入口。
     *  8.0.49 下 ChattingUI.onResume / ChattingUIFragment.M0 / BaseChattingUIFragment.onResume 均不触发，
     *  需要找到真正被调用的打开方法。使用 Intent extra "Chat_User" 字符串反查。 */
    private static void findChatOpenEntry(DexKitBridge bridge) {
        try {
            MethodMatcher mMatcher = MethodMatcher.create()
                .usingStrings("Chat_User");
            List<MethodData> methods = bridge.findMethod(
                FindMethod.create().searchPackages("com.tencent.mm.ui.chatting")
                    .matcher(mMatcher)
            );
            LogWriter.log(TAG, "findChatOpenEntry: " + methods.size()
                + " methods in chatting pkg use 'Chat_User'");
            int limit = Math.min(methods.size(), 30);
            for (int i = 0; i < limit; i++) {
                MethodData m = methods.get(i);
                LogWriter.log(TAG, "  chatOpen[" + i + "]: " + m.getClassName() + "." + m.getName()
                    + " params=" + m.getParamTypeNames() + " return=" + m.getReturnTypeName());
            }
            if (methods.size() > limit) {
                LogWriter.log(TAG, "  ... " + (methods.size() - limit) + " more");
            }
            if (!methods.isEmpty()) {
                // 优先级过滤：ChattingUIFragment 的 0 参 void 方法（M0/t0/u0）最可能是 fragment 内打开入口
                MethodData selected = null;
                for (MethodData m : methods) {
                    String cn = m.getClassName();
                    String mn = m.getName();
                    if (cn != null && cn.contains("ChattingUIFragment")
                        && !cn.contains("$$")
                        && m.getParamTypeNames().isEmpty()
                        && "void".equals(m.getReturnTypeName())
                        && (mn.equals("M0") || mn.equals("t0") || mn.equals("u0")
                            || mn.equals("onResume") || mn.equals("onCreateView"))) {
                        selected = m;
                        LogWriter.log(TAG, "findChatOpenEntry: prefer " + cn + "." + mn);
                        break;
                    }
                }
                if (selected == null) {
                    for (MethodData m : methods) {
                        String cn = m.getClassName();
                        String mn = m.getName();
                        if (cn != null && cn.contains("ChattingUI") && !cn.contains("$$")
                            && m.getParamTypeNames().isEmpty()) {
                            selected = m;
                            LogWriter.log(TAG, "findChatOpenEntry: prefer2 " + cn + "." + mn);
                            break;
                        }
                    }
                }
                if (selected == null) selected = methods.get(0);
                sChatOpenClass = selected.getClassName();
                sChatOpenMethod = selected.getName();
                LogWriter.log(TAG, "findChatOpenEntry: selected " + sChatOpenClass + "." + sChatOpenMethod);
            }

            // 兜底：查找类名包含 MMEditText 的聊天输入框持有类
            ClassMatcher cm = ClassMatcher.create()
                .addFieldForType("com.tencent.mm.ui.widget.MMEditText");
            List<ClassData> holders = bridge.findClass(
                FindClass.create().matcher(cm)
            );
            LogWriter.log(TAG, "findChatOpenEntry: " + holders.size()
                + " classes hold MMEditText field (all pkg)");
            int hLimit = Math.min(holders.size(), 10);
            for (int i = 0; i < hLimit; i++) {
                LogWriter.log(TAG, "  holder[" + i + "]: " + holders.get(i).getName());
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findChatOpenEntry error: " + e.getMessage());
        }
    }

    /** 规则2(bug3 长按菜单无注入项)：定位会话列表长按回调/菜单创建入口。
     *  8.0.49 不使用 AbsListView.setOnItemLongClickListener(包装器从不触发)，
     *  而是由 ConversationListView 直接持有 OnItemLongClickListener 实现类。
     *  DexKit 已发现 7 个实现类(f4/i/kb/o3/p9/q0/r3)，这里扩大到全包反查，
     *  并直接 hook 各实现类的 onItemLongClick。 */
    private static void findConvLongPressEntry(DexKitBridge bridge) {
        try {
            MethodMatcher callerMatcher = MethodMatcher.create()
                .addInvoke(MethodMatcher.create()
                    .declaredClass("android.widget.AbsListView")
                    .name("setOnItemLongClickListener"));
            List<MethodData> callers = bridge.findMethod(
                FindMethod.create().matcher(callerMatcher)
            );
            LogWriter.log(TAG, "findConvLongPressEntry: " + callers.size()
                + " methods invoke setOnItemLongClickListener (all pkg)");
            int cLimit = Math.min(callers.size(), 20);
            for (int i = 0; i < cLimit; i++) {
                MethodData m = callers.get(i);
                LogWriter.log(TAG, "  lpCaller[" + i + "]: " + m.getClassName() + "." + m.getName()
                    + " params=" + m.getParamTypeNames());
            }
            if (callers.size() > cLimit) {
                LogWriter.log(TAG, "  ... " + (callers.size() - cLimit) + " more");
            }
            if (!callers.isEmpty()) {
                MethodData m = callers.get(0);
                sConvLongPressClass = m.getClassName();
                sConvLongPressMethod = m.getName();
                LogWriter.log(TAG, "findConvLongPressEntry: selected "
                    + sConvLongPressClass + "." + sConvLongPressMethod);
            }

            // 查找实现 OnItemLongClickListener 的类(全包)
            ClassMatcher icm = ClassMatcher.create()
                .addInterface("android.widget.AdapterView$OnItemLongClickListener");
            List<ClassData> impls = bridge.findClass(
                FindClass.create().matcher(icm)
            );
            LogWriter.log(TAG, "findConvLongPressEntry: " + impls.size()
                + " classes implement OnItemLongClickListener (all pkg)");
            int iLimit = Math.min(impls.size(), 25);
            List<String> newImpls = new java.util.ArrayList<>();
            for (int i = 0; i < iLimit; i++) {
                String cn = impls.get(i).getName();
                LogWriter.log(TAG, "  lpImpl[" + i + "]: " + cn);
                newImpls.add(cn);
            }
            // 全量收集（不截断），供 Bug3 hook 使用
            for (int i = iLimit; i < impls.size(); i++) {
                newImpls.add(impls.get(i).getName());
            }
            // 优先保留会话相关实现类，减少门控 hook 安装面
            java.util.List<String> convOnly = new java.util.ArrayList<>();
            for (String cn : newImpls) {
                if (cn.contains("fh5") || cn.contains("conversation") || cn.contains("Conversation")) {
                    convOnly.add(cn);
                }
            }
            LogWriter.log(TAG, "findConvLongPressEntry: conv-scoped impls=" + convOnly);
            if (!convOnly.isEmpty()) {
                sConvLongPressImpls = convOnly;
            } else {
                sConvLongPressImpls = newImpls;
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findConvLongPressEntry error: " + e.getMessage());
        }
    }

    /** 规则3(bug2 tab 切换跳回顶部)：定位会话列表滚动/置顶方法。
     *  用户期望切标签后保持原滚动位置，因此需要找到微信内部的滚动入口，
     *  同时 hook 后改为按用户名锚定恢复。全包反查调用者。 */
    private static void findConvScrollEntry(DexKitBridge bridge) {
        try {
            MethodMatcher scrollMatcher = MethodMatcher.create()
                .addInvoke(MethodMatcher.create()
                    .declaredClass("android.widget.AbsListView")
                    .name("setSelection"))
                .addInvoke(MethodMatcher.create()
                    .declaredClass("android.widget.ListView")
                    .name("smoothScrollToPosition"))
                .addInvoke(MethodMatcher.create()
                    .declaredClass("android.widget.ListView")
                    .name("scrollTo"))
                .addInvoke(MethodMatcher.create()
                    .declaredClass("android.widget.AbsListView")
                    .name("smoothScrollToPositionFromTop"))
                .addInvoke(MethodMatcher.create()
                    .declaredClass("android.widget.AbsListView")
                    .name("setSelectionFromTop"));
            List<MethodData> callers = bridge.findMethod(
                FindMethod.create().matcher(scrollMatcher)
            );
            LogWriter.log(TAG, "findConvScrollEntry: " + callers.size()
                + " methods invoke setSelection/smoothScrollToPosition/scrollTo (all pkg)");
            int cLimit = Math.min(callers.size(), 25);
            for (int i = 0; i < cLimit; i++) {
                MethodData m = callers.get(i);
                LogWriter.log(TAG, "  scroll[" + i + "]: " + m.getClassName() + "." + m.getName()
                    + " params=" + m.getParamTypeNames() + " return=" + m.getReturnTypeName());
            }
            if (callers.size() > cLimit) {
                LogWriter.log(TAG, "  ... " + (callers.size() - cLimit) + " more");
            }
            if (!callers.isEmpty()) {
                MethodData m = callers.get(0);
                sConvScrollClass = m.getClassName();
                sConvScrollMethod = m.getName();
                LogWriter.log(TAG, "findConvScrollEntry: selected "
                    + sConvScrollClass + "." + sConvScrollMethod);
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findConvScrollEntry error: " + e.getMessage());
        }
    }

    /** 规则4(bug3)：定位会话列表长按菜单创建入口。
     *  微信 8.0.49 长按弹出的是自定义菜单/对话框，不在标准 ContextMenu 链路，
     *  用常见菜单文案字符串反查创建方法。 */
    private static void findConvMenuEntry(DexKitBridge bridge) {
        try {
            MethodMatcher mMatcher = MethodMatcher.create()
                .usingStrings("置顶聊天", "标为未读", "不显示", "删除该聊天", "cancel")
                .paramCount(1);
            List<MethodData> methods = bridge.findMethod(
                FindMethod.create().matcher(mMatcher)
            );
            LogWriter.log(TAG, "findConvMenuEntry: " + methods.size()
                + " methods use menu strings");
            int limit = Math.min(methods.size(), 20);
            for (int i = 0; i < limit; i++) {
                MethodData m = methods.get(i);
                LogWriter.log(TAG, "  menu[" + i + "]: " + m.getClassName() + "." + m.getName()
                    + " params=" + m.getParamTypeNames() + " return=" + m.getReturnTypeName());
            }
            if (methods.size() > limit) {
                LogWriter.log(TAG, "  ... " + (methods.size() - limit) + " more");
            }
            if (!methods.isEmpty()) {
                for (MethodData m : methods) {
                    String cn = m.getClassName();
                    if (cn != null && cn.contains("conversation")) {
                        sConvMenuClass = cn;
                        sConvMenuMethod = m.getName();
                        LogWriter.log(TAG, "findConvMenuEntry: selected " + cn + "." + m.getName());
                        return;
                    }
                }
                MethodData m = methods.get(0);
                sConvMenuClass = m.getClassName();
                sConvMenuMethod = m.getName();
                LogWriter.log(TAG, "findConvMenuEntry: selected(any) " + sConvMenuClass + "." + sConvMenuMethod);
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findConvMenuEntry error: " + e.getMessage());
        }
    }

    /** 定位 kc5.g4(Menu 接口) 的实现类：微信自定义菜单用混淆接口 kc5.g4，
     *  直接 hook 接口无效（LSPosed 挂不上），必须 hook 具体实现类。 */
    private static void findMenuG4Impls(DexKitBridge bridge) {
        try {
            ClassMatcher cm = ClassMatcher.create()
                .addInterface("kc5.g4");
            List<ClassData> impls = bridge.findClass(
                FindClass.create().matcher(cm)
            );
            LogWriter.log(TAG, "findMenuG4Impls: " + impls.size()
                + " classes implement kc5.g4");
            List<String> newList = new java.util.ArrayList<>();
            int limit = Math.min(impls.size(), 25);
            for (int i = 0; i < limit; i++) {
                String cn = impls.get(i).getName();
                LogWriter.log(TAG, "  menuG4Impl[" + i + "]: " + cn);
                newList.add(cn);
            }
            for (int i = limit; i < impls.size(); i++) {
                newList.add(impls.get(i).getName());
            }
            sMenuG4Impls = newList;
        } catch (Throwable e) {
            LogWriter.log(TAG, "findMenuG4Impls error: " + e.getMessage());
        }
    }

    public static void hookApplication(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            java.lang.reflect.Method attachMethod = android.content.ContextWrapper.class
                .getDeclaredMethod("attachBaseContext", android.content.Context.class);
            XposedBridge.hookMethod(attachMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Application)) return;
                    final Application app = (Application) param.thisObject;
                    if (!"com.tencent.mm".equals(app.getPackageName())) return;

                    final String processName = lpparam.processName;
                    final boolean isMainProcess = "com.tencent.mm".equals(processName);

                    if (!isMainProcess) {
                        LogWriter.log(TAG, "clone process " + processName + ": reading MMKV cache only");
                        try {
                            loadResultsFromMMKV(app);
                        } catch (Throwable e) {
                            LogWriter.log(TAG, "clone process MMKV read failed: " + e.getMessage());
                        }
                        return;
                    }

                    sExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            DexKitCacheBridge.RecyclableBridge cacheBridge = null;
                            try {
                                loadDexKitLibrary(app);
                                if (!sLibraryLoaded.get()) {
                                    LogWriter.log(TAG, "DexKit library not loaded, skipping");
                                    return;
                                }

                                initDexKitCache(app);

                                final String appTag = "wechat_" + (sVersionCode > 0 ? sVersionCode : "");
                                LogWriter.log(TAG, "main process: appTag=" + appTag);

                                cacheBridge = DexKitCacheBridge.create(
                                    appTag, app.getClassLoader());
                                LogWriter.log(TAG, "DexKitCacheBridge.create done");

                                if (cacheBridge != null) {
                                    scanWechatTargets(cacheBridge);
                                }
                            } catch (Throwable e) {
                                LogWriter.log(TAG, "DexKit scan thread error: " + e.getMessage());
                            } finally {
                                if (cacheBridge != null) {
                                    try {
                                        cacheBridge.close();
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    });
                }
            });

            LogWriter.log(TAG, "ContextWrapper.attachBaseContext hook installed");
        } catch (Throwable e) {
            LogWriter.log(TAG, "hookApplication error: " + e.getMessage());
        }
    }

    public interface KernelReadyCallback {
        void onKernelReady(DexKitBridge bridge);
    }

    public static void waitKernelInit(final ClassLoader cl, final KernelReadyCallback callback) {
        try {
            Class<?> j1 = XposedHelpers.findClass("hm0.j1", cl);
            for (java.lang.reflect.Method m : j1.getDeclaredMethods()) {
                if ("s".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        private volatile boolean sCalled = false;

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (sCalled) return;
                            if (param.getThrowable() != null) return;
                            Object result = param.getResult();
                            if (result == null) return;
                            sCalled = true;

                            LogWriter.log(TAG, "waitKernelInit: kernel is ready");

                            sExecutor.execute(new Runnable() {
                                @Override
                                public void run() {
                                    DexKitCacheBridge.RecyclableBridge cacheBridge = null;
                                    try {
                                        final String appTag = "wechat_"
                                            + (sVersionCode > 0 ? sVersionCode : "");
                                        cacheBridge = DexKitCacheBridge.create(
                                            appTag, cl);
                                        if (cacheBridge != null) {
                                            cacheBridge.withBridge(
                                                new DexKitCacheBridge.RecyclableBridge.BridgeFunction() {
                                                    @Override
                                                    public void apply(DexKitBridge b) {
                                                        callback.onKernelReady(b);
                                                    }
                                                });
                                        }
                                    } catch (Throwable e) {
                                        LogWriter.log(TAG, "waitKernelInit bridge error: "
                                            + e.getMessage());
                                    } finally {
                                        if (cacheBridge != null) {
                                            try { cacheBridge.close(); }
                                            catch (Throwable ignored) {}
                                        }
                                    }
                                }
                            });
                        }
                    });
                    LogWriter.log(TAG, "waitKernelInit: hook installed on hm0.j1."
                        + m.getName());
                    return;
                }
            }
            LogWriter.log(TAG, "waitKernelInit: no single-param s() method found");
        } catch (Throwable e) {
            LogWriter.log(TAG, "waitKernelInit error: " + e.getMessage());
        }
    }
}