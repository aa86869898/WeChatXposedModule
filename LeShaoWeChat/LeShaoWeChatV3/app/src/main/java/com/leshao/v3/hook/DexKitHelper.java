package com.leshao.v3.hook;

import android.app.Application;

import com.leshao.v3.LogWriter;
import com.tencent.mmkv.MMKV;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.DexKitCacheBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
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
    private static final String MMKV_RESULTS_ID = "dexkit_scan_v3";
    private static final String KEY_VERSION_CODE = "version_code";
    private static final String KEY_MODULE_VERSION = "module_version"; // 模块版本, 更新模块强制重扫
    private static final String KEY_P06_CLASS = "p06_class";
    private static final String KEY_DB_OPENER_CLASS = "db_opener_class";
    private static final String KEY_DB_OPEN_METHOD = "db_open_method";
    private static final String KEY_DB_OPEN_PARAMS = "db_open_params";
    private static final String KEY_IMEI_CLASS = "imei_class";
    private static final String KEY_IMEI_METHOD = "imei_method";
    private static final String KEY_CSO_LOADER = "cso_loader";
    private static final String KEY_CSO_LOADER_METHOD = "cso_loader_method";
    private static final String KEY_J1_CALLER_CLASS = "j1_caller_class";
    private static final String KEY_J1_CALLER_METHOD = "j1_caller_method";
    private static final String KEY_J1_SERVICE = "j1_service";
    private static final String KEY_CONTACT_STORAGE = "contact_storage";
    private static final String KEY_CHAT_OPEN_CLASS = "chat_open_class";
    private static final String KEY_CHAT_OPEN_METHOD = "chat_open_method";
    private static final String KEY_CONV_SCROLL_CLASS = "conv_scroll_class";
    private static final String KEY_CONV_SCROLL_METHOD = "conv_scroll_method";
    private static final String KEY_CONV_LONGPRESS_CLASS = "conv_longpress_class";
    private static final String KEY_CONV_LONGPRESS_METHOD = "conv_longpress_method";
    private static final String KEY_CONV_MENU_CLASS = "conv_menu_class";
    private static final String KEY_CONV_MENU_METHOD = "conv_menu_method";
    private static final String KEY_VOICE_API = "voice_api";
    private static final String KEY_E9_CLASS = "e9_class";
    private static final String KEY_A21_CLASS = "a21_class";
    private static final String KEY_AVATAR_HELPER = "avatar_helper";
    private static final String KEY_LABEL_STORAGE = "label_storage";
    private static final String KEY_CONV_LIST_ADAPTER = "conv_list_adapter";
    private static final String KEY_MENU_G4_IMPLS = "menu_g4_impls";
    private static final String KEY_CONV_LP_IMPLS = "conv_lp_impls";

    private static final AtomicBoolean sLibraryLoaded = new AtomicBoolean(false);
    private static volatile boolean sScanComplete = false;
    private static volatile boolean sShouldShowScanDialog = false;
    private static final java.util.List<Runnable> sPostScanCallbacks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile ScanProgressCallback sProgressCallback;

    public interface ScanProgressCallback {
        void onProgress(int percent, String status, String detail);
        void onComplete();
    }

    public static void setProgressCallback(ScanProgressCallback callback) {
        sProgressCallback = callback;
    }

    private static void reportProgress(int percent, String status, String detail) {
        if (sProgressCallback != null) {
            try { sProgressCallback.onProgress(percent, status, detail); } catch (Throwable ignored) {}
        }
    }

    private static void reportComplete() {
        if (sProgressCallback != null) {
            try { sProgressCallback.onComplete(); } catch (Throwable ignored) {}
        }
    }

    public static boolean isScanComplete() { synchronized (DexKitHelper.class) { return sScanComplete; } }

    public static void addPostScanCallback(Runnable callback) {
        synchronized (DexKitHelper.class) {
            if (sScanComplete) {
                callback.run();
            } else {
                sPostScanCallbacks.add(callback);
            }
        }
    }

    private static void runPostScanCallbacks() {
        synchronized (DexKitHelper.class) {
            for (Runnable cb : sPostScanCallbacks) {
                try { cb.run(); } catch (Throwable e) { LogWriter.log(TAG, "postScanCallback error: " + e.getMessage()); }
            }
            sPostScanCallbacks.clear();
        }
    }

    private static volatile String sP06ClassName;
    private static volatile String sDbOpenerClass;
    private static volatile String sDbOpenMethodName;
    private static volatile String[] sDbOpenMethodParamTypes;
    private static volatile String sImeiClassName;
    private static volatile String sImeiMethodName;
    private static volatile String sCsoLoaderClass;
    private static volatile String sCsoLoaderMethod;
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
    private static volatile String sJ1ServiceClass;
    private static volatile String sVoiceApiClass;
    private static volatile String sE9ClassName;
    private static volatile String sA21ClassName;
    private static volatile String sAvatarHelperClass;
    private static volatile String sLabelStorageClass;
    private static volatile String sConvListListAdapterClass;
    private static volatile List<String> sConvLongPressImpls = new java.util.ArrayList<>();
    private static volatile List<String> sMenuG4Impls = new java.util.ArrayList<>();

    private static volatile int sVersionCode = 0;
    private static volatile int sModuleVersion = 0;

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

    private static DexKitCacheBridge.RecyclableBridge createBridge(ClassLoader cl) {
        try {
            return DexKitCacheBridge.create(
                "wechat_" + (sVersionCode > 0 ? sVersionCode : ""),
                cl != null ? cl : DexKitHelper.class.getClassLoader());
        } catch (Throwable e) {
            LogWriter.log(TAG, "createBridge err: " + e.getMessage());
            return null;
        }
    }

    /**
     * 通用字符串搜索：在类名/方法体/字段名中查找包含 keyword 的类。
     * 返回候选类名列表，按匹配度排序。
     */
    public static List<String> findClassesByString(ClassLoader cl, final String keyword) {
        final List<String> results = new java.util.ArrayList<>();
        if (keyword == null || keyword.isEmpty()) return results;
        try {
            DexKitCacheBridge.RecyclableBridge bridge = createBridge(cl);
            if (bridge == null) return results;
            try {
                bridge.withBridge(new DexKitCacheBridge.RecyclableBridge.BridgeFunction() {
                    @Override
                    public void apply(DexKitBridge b) {
                        try {
                            MethodMatcher mMatcher = MethodMatcher.create().usingStrings(keyword);
                            List<MethodData> methods = b.findMethod(FindMethod.create().matcher(mMatcher));
                            for (MethodData m : methods) {
                                String cn = m.getClassName();
                                if (cn != null && !results.contains(cn)) results.add(cn);
                            }
                        } catch (Throwable ignored) {}
                        try {
                            ClassMatcher cMatcher = ClassMatcher.create().addFieldForType(keyword);
                            List<ClassData> classes = b.findClass(FindClass.create().matcher(cMatcher));
                            for (ClassData c : classes) {
                                String cn = c.getName();
                                if (cn != null && !results.contains(cn)) results.add(cn);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            } finally {
                try { bridge.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findClassesByString err: " + e.getMessage());
        }
        LogWriter.log(TAG, "findClassesByString(" + keyword + "): " + results.size() + " candidates");
        return results;
    }

    /**
     * 通用字符串搜索：在指定类中查找包含 keyword 的方法。
     * 如果 className 为 null，则在全包搜索。
     */
    public static List<String> findMethodsByString(ClassLoader cl, final String className, final String keyword) {
        final List<String> results = new java.util.ArrayList<>();
        if (keyword == null || keyword.isEmpty()) return results;
        try {
            DexKitCacheBridge.RecyclableBridge bridge = createBridge(cl);
            if (bridge == null) return results;
            try {
                bridge.withBridge(new DexKitCacheBridge.RecyclableBridge.BridgeFunction() {
                    @Override
                    public void apply(DexKitBridge b) {
                        try {
                            MethodMatcher mMatcher = MethodMatcher.create().usingStrings(keyword);
                            if (className != null) {
                                mMatcher.declaredClass(className);
                            }
                            List<MethodData> methods = b.findMethod(FindMethod.create().matcher(mMatcher));
                            for (MethodData m : methods) {
                                String sig = m.getClassName() + "." + m.getName() +
                                    "(" + String.join(",", m.getParamTypeNames()) + ")";
                                if (!results.contains(sig)) results.add(sig);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            } finally {
                try { bridge.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findMethodsByString err: " + e.getMessage());
        }
        LogWriter.log(TAG, "findMethodsByString(" + className + "," + keyword + "): " + results.size());
        return results;
    }

    /**
     * 通用方法查找：按类名+方法名+参数类型精确查找。
     * 返回第一个匹配的 MethodData，用于后续反射调用。
     */
    public static MethodData findMethod(ClassLoader cl, final String className, final String methodName, final String... paramTypeNames) {
        if (className == null || methodName == null) return null;
        try {
            DexKitCacheBridge.RecyclableBridge bridge = createBridge(cl);
            if (bridge == null) return null;
            try {
                final MethodData[] result = new MethodData[1];
                bridge.withBridge(new DexKitCacheBridge.RecyclableBridge.BridgeFunction() {
                    @Override
                    public void apply(DexKitBridge b) {
                        try {
                            MethodMatcher mMatcher = MethodMatcher.create().name(methodName);
                            if (className != null) {
                                mMatcher.declaredClass(className);
                            }
                            List<MethodData> methods = b.findMethod(FindMethod.create().matcher(mMatcher));
                            if (!methods.isEmpty()) {
                                if (paramTypeNames != null && paramTypeNames.length > 0) {
                                    for (MethodData m : methods) {
                                        List<String> pts = m.getParamTypeNames();
                                        if (pts.size() == paramTypeNames.length) {
                                            boolean match = true;
                                            for (int i = 0; i < paramTypeNames.length; i++) {
                                                if (!paramTypeNames[i].isEmpty() && !pts.get(i).equals(paramTypeNames[i])) {
                                                    match = false; break;
                                                }
                                            }
                                            if (match) { result[0] = m; return; }
                                        }
                                    }
                                }
                                result[0] = methods.get(0);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                return result[0];
            } finally {
                try { bridge.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findMethod err: " + e.getMessage());
        }
        return null;
    }

    /**
     * 通用类查找：按类名精确查找（忽略包名）。
     */
    public static ClassData findClassByName(ClassLoader cl, final String simpleName) {
        if (simpleName == null) return null;
        try {
            DexKitCacheBridge.RecyclableBridge bridge = createBridge(cl);
            if (bridge == null) return null;
            try {
                final ClassData[] result = new ClassData[1];
                bridge.withBridge(new DexKitCacheBridge.RecyclableBridge.BridgeFunction() {
                    @Override
                    public void apply(DexKitBridge b) {
                        try {
                            ClassMatcher cMatcher = ClassMatcher.create();
                            List<ClassData> classes = b.findClass(FindClass.create().matcher(cMatcher));
                            for (ClassData c : classes) {
                                String cn = c.getName();
                                if (cn != null && (cn.equals(simpleName) || cn.endsWith("." + simpleName))) {
                                    result[0] = c;
                                    return;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                return result[0];
            } finally {
                try { bridge.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findClassByName err: " + e.getMessage());
        }
        return null;
    }

    public interface KernelReadyCallback {
        void onKernelReady(DexKitBridge bridge);
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

    private static boolean loadResultsFromMMKV(Application app) {
        try {
            MMKV kv = MMKV.mmkvWithID(MMKV_RESULTS_ID, MMKV.MULTI_PROCESS_MODE);
            int cachedVersion = kv.decodeInt(KEY_VERSION_CODE, 0);
            int cachedModuleVersion = kv.decodeInt(KEY_MODULE_VERSION, 0);
            if (cachedVersion != sVersionCode || cachedModuleVersion != sModuleVersion) {
                LogWriter.log(TAG, "loadResultsFromMMKV: version mismatch (cachedVer=" + cachedVersion + " currentVer=" + sVersionCode
                    + " cachedMod=" + cachedModuleVersion + " currentMod=" + sModuleVersion + "), clearing cache");
                kv.clearAll();
                return false;
            }

            sP06ClassName = kv.decodeString(KEY_P06_CLASS, null);
            sDbOpenerClass = kv.decodeString(KEY_DB_OPENER_CLASS, null);
            sDbOpenMethodName = kv.decodeString(KEY_DB_OPEN_METHOD, null);
            String dbParams = kv.decodeString(KEY_DB_OPEN_PARAMS, null);
            if (dbParams != null && !dbParams.isEmpty()) {
                sDbOpenMethodParamTypes = dbParams.split("\\|");
            }
            sImeiClassName = kv.decodeString(KEY_IMEI_CLASS, null);
            sImeiMethodName = kv.decodeString(KEY_IMEI_METHOD, null);
            sCsoLoaderClass = kv.decodeString(KEY_CSO_LOADER, null);
            sCsoLoaderMethod = kv.decodeString(KEY_CSO_LOADER_METHOD, null);
            sJ1CallerClass = kv.decodeString(KEY_J1_CALLER_CLASS, null);
            sJ1CallerMethod = kv.decodeString(KEY_J1_CALLER_METHOD, null);
            sJ1ServiceClass = kv.decodeString(KEY_J1_SERVICE, null);
            sContactStorageClass = kv.decodeString(KEY_CONTACT_STORAGE, null);
            sChatOpenClass = kv.decodeString(KEY_CHAT_OPEN_CLASS, null);
            sChatOpenMethod = kv.decodeString(KEY_CHAT_OPEN_METHOD, null);
            sConvScrollClass = kv.decodeString(KEY_CONV_SCROLL_CLASS, null);
            sConvScrollMethod = kv.decodeString(KEY_CONV_SCROLL_METHOD, null);
            sConvLongPressClass = kv.decodeString(KEY_CONV_LONGPRESS_CLASS, null);
            sConvLongPressMethod = kv.decodeString(KEY_CONV_LONGPRESS_METHOD, null);
            sConvMenuClass = kv.decodeString(KEY_CONV_MENU_CLASS, null);
            sConvMenuMethod = kv.decodeString(KEY_CONV_MENU_METHOD, null);
            sVoiceApiClass = kv.decodeString(KEY_VOICE_API, null);
            sE9ClassName = kv.decodeString(KEY_E9_CLASS, null);
            sA21ClassName = kv.decodeString(KEY_A21_CLASS, null);
            sAvatarHelperClass = kv.decodeString(KEY_AVATAR_HELPER, null);
            sLabelStorageClass = kv.decodeString(KEY_LABEL_STORAGE, null);
            sConvListListAdapterClass = kv.decodeString(KEY_CONV_LIST_ADAPTER, null);

            String lpImpls = kv.decodeString(KEY_CONV_LP_IMPLS, null);
            if (lpImpls != null && !lpImpls.isEmpty()) {
                sConvLongPressImpls = new java.util.ArrayList<>();
                for (String s : lpImpls.split("\\|")) if (!s.isEmpty()) sConvLongPressImpls.add(s);
            }
            String menuImpls = kv.decodeString(KEY_MENU_G4_IMPLS, null);
            if (menuImpls != null && !menuImpls.isEmpty()) {
                sMenuG4Impls = new java.util.ArrayList<>();
                for (String s : menuImpls.split("\\|")) if (!s.isEmpty()) sMenuG4Impls.add(s);
            }

            // 仅核心必选字段纳入完整性判定; convScroll/convLongPress/convMenu/a21/label/
            // convAdapter/j1Caller 在当前版本可能扫描不到(可选),缺失时不再判缓存不完整,
            // 否则每次启动都会清缓存重扫(见 leshao_v3_log: incomplete cached results)。
            boolean hasResults = (sP06ClassName != null && sDbOpenerClass != null && sDbOpenMethodName != null
                && sImeiClassName != null && sImeiMethodName != null && sCsoLoaderClass != null
                && sJ1ServiceClass != null && sContactStorageClass != null
                && sChatOpenClass != null && sChatOpenMethod != null
                && sVoiceApiClass != null && sE9ClassName != null && sAvatarHelperClass != null);

            if (!hasResults) {
                LogWriter.log(TAG, "loadResultsFromMMKV: incomplete cached results, clearing cache");
                kv.clearAll();
                return false;
            }

            LogWriter.log(TAG, "loadResultsFromMMKV: loaded cached results for version " + cachedVersion);

            // Initialize DexKit bridge so post-scan callbacks can use findClassesByString
            DexKitCacheBridge.RecyclableBridge bridge = null;
            try {
                loadDexKitLibrary(app);
                initDexKitCache(app);
                bridge = DexKitCacheBridge.create(
                    "wechat_" + (sVersionCode > 0 ? sVersionCode : ""), app.getClassLoader());
                if (bridge != null) {
                    LogWriter.log(TAG, "loadResultsFromMMKV: DexKit bridge initialized");
                }
            } catch (Throwable e) {
                LogWriter.log(TAG, "loadResultsFromMMKV: bridge init err: " + e.getMessage());
            }

            runPostScanCallbacks();

            // Close bridge after all callbacks have finished
            if (bridge != null) {
                try { bridge.close(); } catch (Throwable ignored) {}
            }

            // Mark scan complete AFTER callbacks have run and bridge is ready
            sScanComplete = true;
            return true;
        } catch (Throwable e) {
            LogWriter.log(TAG, "loadResultsFromMMKV error: " + e.getMessage());
            return false;
        }
    }

    private static void saveResultsToMMKV() {
        try {
            MMKV kv = MMKV.mmkvWithID(MMKV_RESULTS_ID, MMKV.MULTI_PROCESS_MODE);
            kv.clearAll();
            kv.encode(KEY_VERSION_CODE, sVersionCode);
            kv.encode(KEY_MODULE_VERSION, sModuleVersion);

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
            if (sCsoLoaderMethod != null) kv.encode(KEY_CSO_LOADER_METHOD, sCsoLoaderMethod);
            if (sJ1CallerClass != null) kv.encode(KEY_J1_CALLER_CLASS, sJ1CallerClass);
            if (sJ1CallerMethod != null) kv.encode(KEY_J1_CALLER_METHOD, sJ1CallerMethod);
            if (sJ1ServiceClass != null) kv.encode(KEY_J1_SERVICE, sJ1ServiceClass);
            if (sContactStorageClass != null) kv.encode(KEY_CONTACT_STORAGE, sContactStorageClass);
            if (sChatOpenClass != null) kv.encode(KEY_CHAT_OPEN_CLASS, sChatOpenClass);
            if (sChatOpenMethod != null) kv.encode(KEY_CHAT_OPEN_METHOD, sChatOpenMethod);
            if (sConvScrollClass != null) kv.encode(KEY_CONV_SCROLL_CLASS, sConvScrollClass);
            if (sConvScrollMethod != null) kv.encode(KEY_CONV_SCROLL_METHOD, sConvScrollMethod);
            if (sConvLongPressClass != null) kv.encode(KEY_CONV_LONGPRESS_CLASS, sConvLongPressClass);
            if (sConvLongPressMethod != null) kv.encode(KEY_CONV_LONGPRESS_METHOD, sConvLongPressMethod);
            if (sConvMenuClass != null) kv.encode(KEY_CONV_MENU_CLASS, sConvMenuClass);
            if (sConvMenuMethod != null) kv.encode(KEY_CONV_MENU_METHOD, sConvMenuMethod);
            if (sVoiceApiClass != null) kv.encode(KEY_VOICE_API, sVoiceApiClass);
            if (sE9ClassName != null) kv.encode(KEY_E9_CLASS, sE9ClassName);
            if (sA21ClassName != null) kv.encode(KEY_A21_CLASS, sA21ClassName);
            if (sAvatarHelperClass != null) kv.encode(KEY_AVATAR_HELPER, sAvatarHelperClass);
            if (sLabelStorageClass != null) kv.encode(KEY_LABEL_STORAGE, sLabelStorageClass);
            if (sConvListListAdapterClass != null) kv.encode(KEY_CONV_LIST_ADAPTER, sConvListListAdapterClass);

            if (!sConvLongPressImpls.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < sConvLongPressImpls.size(); i++) {
                    if (i > 0) sb.append("|");
                    sb.append(sConvLongPressImpls.get(i));
                }
                kv.encode(KEY_CONV_LP_IMPLS, sb.toString());
            }
            if (!sMenuG4Impls.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < sMenuG4Impls.size(); i++) {
                    if (i > 0) sb.append("|");
                    sb.append(sMenuG4Impls.get(i));
                }
                kv.encode(KEY_MENU_G4_IMPLS, sb.toString());
            }
            LogWriter.log(TAG, "saveResultsToMMKV: done for version " + sVersionCode);
        } catch (Throwable e) {
            LogWriter.log(TAG, "saveResultsToMMKV error: " + e.getMessage());
        }
    }

    private static void scanWechatTargets(final DexKitCacheBridge.RecyclableBridge cacheBridge) {
        LogWriter.log(TAG, "scanWechatTargets: using DexKitCacheBridge instance");

        final String[] scanSteps = {
            "J1 服务定位器", "P06 核心类", "数据库接口", "设备标识 (IMEI)", "CsoLoader",
            "通讯录存储", "语音 API", "e9/a21 类", "头像服务", "标签存储",
            "会话列表适配器", "聊天窗口入口",
            "长按事件", "列表滚动", "菜单注入", "菜单实现类"
        };
        final int totalSteps = scanSteps.length;

        cacheBridge.withBridge(new DexKitCacheBridge.RecyclableBridge.BridgeFunction() {
            @Override
            public void apply(DexKitBridge b) {
                reportProgress(0, "开始扫描 DexKit...", "共 " + totalSteps + " 项");
                findJ1Service(b);
                reportProgress(9, "扫描: " + scanSteps[0], "查找静态 s(Class) 方法");
                findP06Class(b);
                reportProgress(18, "扫描: " + scanSteps[1], "查找 P06 核心类");
                findDbOpenerMethods(b);
                reportProgress(27, "扫描: " + scanSteps[2], "查找数据库打开接口");
                findImeiClass(b);
                reportProgress(36, "扫描: " + scanSteps[3], "查找设备标识类");
                findCsoLoader(b);
                reportProgress(45, "扫描: " + scanSteps[4], "查找 CsoLoader");
                findContactStorageAlt(b);
                reportProgress(54, "扫描: " + scanSteps[5], "查找通讯录存储类");
                findVoiceApi(b);
                reportProgress(58, "扫描: " + scanSteps[6], "查找语音 API 类");
                findE9AndA21(b);
                reportProgress(62, "扫描: " + scanSteps[7], "查找 e9/a21 类");
                findAvatarHelper(b);
                reportProgress(66, "扫描: " + scanSteps[8], "查找头像服务类");
                findLabelStorage(b);
                reportProgress(70, "扫描: " + scanSteps[9], "查找标签存储类");
                findConvListAdapter(b);
                reportProgress(74, "扫描: " + scanSteps[10], "查找会话列表适配器");
                findChatOpenEntry(b);
                reportProgress(78, "扫描: " + scanSteps[11], "查找聊天窗口入口");
                findConvLongPressEntry(b);
                reportProgress(82, "扫描: " + scanSteps[12], "查找长按事件入口");
                findConvScrollEntry(b);
                reportProgress(86, "扫描: " + scanSteps[13], "查找列表滚动入口");
                findConvMenuEntry(b);
                reportProgress(90, "扫描: " + scanSteps[14], "查找菜单注入入口");
                findMenuG4Impls(b);
                 reportProgress(95, "扫描: " + scanSteps[15], "查找菜单实现类");
                findV955Targets(b);
                reportProgress(97, "扫描: v955 3180 适配目标", "撤回监听/标签提供者/拼音/服务定位/媒体路径");
             }
        });

        sScanComplete = true;
        LogWriter.log(TAG, "scan complete: p06=" + sP06ClassName
            + " j1=" + sJ1ServiceClass
            + " dbOpener=" + sDbOpenerClass + "." + sDbOpenMethodName
            + " imei=" + sImeiClassName + "." + sImeiMethodName
            + " cso=" + sCsoLoaderClass
            + " contactStorage=" + sContactStorageClass
            + " voiceApi=" + sVoiceApiClass
            + " e9=" + sE9ClassName
            + " a21=" + sA21ClassName
            + " avatar=" + sAvatarHelperClass
            + " label=" + sLabelStorageClass
            + " convAdapter=" + sConvListListAdapterClass
            + " chatOpen=" + sChatOpenClass + "." + sChatOpenMethod
            + " convScroll=" + sConvScrollClass + "." + sConvScrollMethod
            + " convLongPress=" + sConvLongPressClass + "." + sConvLongPressMethod
            + " convMenu=" + sConvMenuClass + "." + sConvMenuMethod);

        saveResultsToMMKV();

        reportProgress(100, "扫描完成", "所有功能已就绪");
        reportComplete();

        runPostScanCallbacks();
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
                sCsoLoaderMethod = methods.get(0).getName();
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
                    sCsoLoaderMethod = m.getName();
                    LogWriter.log(TAG, "findCsoLoader (name match): " + clsName + "." + m.getName());
                    return;
                }
            }

            if (!methods2.isEmpty()) {
                sCsoLoaderClass = methods2.get(0).getClassName();
                sCsoLoaderMethod = methods2.get(0).getName();
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

    public static String getJ1ServiceClass() { return sJ1ServiceClass; }
    public static String getVoiceApiClass() { return sVoiceApiClass; }
    public static String getE9ClassName() { return sE9ClassName; }
    public static String getA21ClassName() { return sA21ClassName; }
    public static String getAvatarHelperClass() { return sAvatarHelperClass; }
    public static String getLabelStorageClass() { return sLabelStorageClass; }
    public static String getConvListListAdapterClass() { return sConvListListAdapterClass; }

    public static void setVersionCode(int versionCode) {
        sVersionCode = versionCode;
    }

    /** 设置模块构建版本。模块更新后该值变化, 与微信版本一起作为缓存失效条件 */
    public static void setModuleVersion(int moduleVersion) {
        sModuleVersion = moduleVersion;
    }

    public static String getP06ClassName() { return sP06ClassName; }
    public static String getDbOpenerClass() { return sDbOpenerClass; }
    public static String getDbOpenMethodName() { return sDbOpenMethodName; }
    public static String[] getDbOpenMethodParamTypes() { return sDbOpenMethodParamTypes; }
    public static String getImeiClassName() { return sImeiClassName; }
    public static String getImeiMethodName() { return sImeiMethodName; }
    public static String getCsoLoaderClass() { return sCsoLoaderClass; }
    public static String getCsoLoaderMethod() { return sCsoLoaderMethod; }
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

    private static void findVoiceApi(DexKitBridge bridge) {
        try {
            // 3180: VoiceLogic v61.d1.h(String talker, String prefix) → String (注册 voiceinfo 拿 baseName)
            // 特征: 静态方法, 参数 (String,String), 返回 String, 方法体含 "amr_" 或 "voice2"
            String[] keywords = {"amr_", "voice2", "voicemsg"};
            for (String kw : keywords) {
                try {
                    MethodMatcher mMatcher = MethodMatcher.create()
                        .usingStrings(kw)
                        .paramCount(2)
                        .paramTypes("java.lang.String", "java.lang.String")
                        .returnType("java.lang.String");
                    List<MethodData> methods = bridge.findMethod(FindMethod.create().matcher(mMatcher));
                    if (!methods.isEmpty()) {
                        sVoiceApiClass = methods.get(0).getClassName();
                        LogWriter.log(TAG, "findVoiceApi (" + kw + "): " + sVoiceApiClass);
                        return;
                    }
                } catch (Throwable ignored) {}
            }
            // Legacy: any method with voice2 string
            MethodMatcher m2 = MethodMatcher.create().usingStrings("voice2");
            List<MethodData> m2s = bridge.findMethod(FindMethod.create().matcher(m2));
            for (MethodData m : m2s) {
                String cn = m.getClassName();
                if (cn != null && !cn.equals(sVoiceApiClass)) {
                    sVoiceApiClass = cn;
                    LogWriter.log(TAG, "findVoiceApi (fallback): " + cn);
                    return;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findVoiceApi error: " + e.getMessage());
        }
    }

    private static void findE9AndA21(DexKitBridge bridge) {
        try {
            // e9 class: 8.0.78(3180) 真实类名 com.tencent.mm.storage.e9 (已由 f9.Bb 参数实机验证)。
            // 曾因 d1(String) fallback 误中 com.tencent.maas.instamovie.MJPublisherSessionMetrics(美颜SDK)。
            sE9ClassName = "com.tencent.mm.storage.e9";
            LogWriter.log(TAG, "findE9 (fixed): " + sE9ClassName);
        } catch (Throwable e) {
            LogWriter.log(TAG, "findE9 error: " + e.getMessage());
        }
        try {
            // a21 class: contains o(i) method
            MethodMatcher m2 = MethodMatcher.create()
                .usingStrings("voicemsg")
                .paramCount(1);
            List<MethodData> a21Methods = bridge.findMethod(FindMethod.create().matcher(m2));
            for (MethodData m : a21Methods) {
                String cn = m.getClassName();
                if (cn != null && cn.contains("a21")) {
                    sA21ClassName = cn;
                    LogWriter.log(TAG, "findA21: " + cn);
                    break;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findA21 error: " + e.getMessage());
        }
    }

    private static void findAvatarHelper(DexKitBridge bridge) {
        try {
            // AvatarHelper: class with method returning Bitmap and taking String param
            MethodMatcher m1 = MethodMatcher.create()
                .returnType("android.graphics.Bitmap")
                .paramCount(1)
                .paramTypes("java.lang.String");
            List<MethodData> methods = bridge.findMethod(FindMethod.create().matcher(m1));
            for (MethodData m : methods) {
                String cn = m.getClassName();
                if (cn != null && (cn.contains("avatar") || cn.contains("Avatar") || cn.contains("mp0"))) {
                    sAvatarHelperClass = cn;
                    LogWriter.log(TAG, "findAvatarHelper: " + cn);
                    return;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findAvatarHelper error: " + e.getMessage());
        }
    }

    private static void findLabelStorage(DexKitBridge bridge) {
        try {
            // Label storage: class with static hj() method returning g4
            MethodMatcher m1 = MethodMatcher.create()
                .name("hj")
                .modifiers(java.lang.reflect.Modifier.STATIC);
            List<MethodData> methods = bridge.findMethod(FindMethod.create().matcher(m1));
            for (MethodData m : methods) {
                String cn = m.getClassName();
                if (cn != null && cn.contains("x93")) {
                    sLabelStorageClass = cn;
                    LogWriter.log(TAG, "findLabelStorage: " + cn + ".hj()");
                    return;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findLabelStorage error: " + e.getMessage());
        }
    }

    private static void findConvListAdapter(DexKitBridge bridge) {
        try {
            // Conversation list adapter: implements ListAdapter and has getView
            ClassMatcher cm = ClassMatcher.create()
                .addInterface("android.widget.ListAdapter");
            List<ClassData> classes = bridge.findClass(FindClass.create().matcher(cm));
            for (ClassData c : classes) {
                String cn = c.getName();
                if (cn != null && cn.contains("conversation") && cn.contains("Adapter")) {
                    sConvListListAdapterClass = cn;
                    LogWriter.log(TAG, "findConvListAdapter: " + cn);
                    return;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findConvListAdapter error: " + e.getMessage());
        }
    }

    private static void findJ1Service(DexKitBridge bridge) {
        try {
            // Strategy 1: search for methods with s(Class) OR v(Class) signature
            // v955: 3180 实证服务定位方法为 v(Class)(gp0.j1.v), 旧版 s(Class); 双签名兼容
            List<MethodData> methods = null;
            for (String mn : new String[]{"v", "s"}) {
                MethodMatcher mMatcher = MethodMatcher.create()
                    .name(mn)
                    .paramCount(1);
                methods = bridge.findMethod(
                    FindMethod.create().matcher(mMatcher)
                );
                LogWriter.log(TAG, "findJ1Service: " + methods.size() + " " + mn + "(*) methods found");
                for (MethodData m : methods) {
                    String clsName = m.getClassName();
                    if (clsName == null) continue;
                    List<String> pts = m.getParamTypeNames();
                    if (pts.size() == 1 && "java.lang.Class".equals(pts.get(0))) {
                        if (clsName.contains(".j1") || clsName.contains("$j1") || clsName.contains("j1")) {
                            sJ1ServiceClass = clsName;
                            LogWriter.log(TAG, "findJ1Service: " + clsName + "." + mn + "(Class)");
                            return;
                        }
                    }
                }
            }
            // Strategy 2: search for "Kernel not initialized" string (p06 has this)
            MethodMatcher m2 = MethodMatcher.create()
                .usingStrings("Kernel not initialized");
            List<MethodData> m2s = bridge.findMethod(FindMethod.create().matcher(m2));
            for (MethodData m : m2s) {
                String clsName = m.getClassName();
                if (clsName == null) continue;
                if (clsName.contains(".j1") || clsName.contains("$j1") || clsName.contains("j1")) {
                    sJ1ServiceClass = clsName;
                    LogWriter.log(TAG, "findJ1Service (Kernel): " + clsName);
                    return;
                }
            }
            // Strategy 3: any s(Class)/v(Class) method regardless of class name
            for (MethodData m : methods) {
                String clsName = m.getClassName();
                if (clsName == null) continue;
                List<String> pts = m.getParamTypeNames();
                if (pts.size() == 1 && "java.lang.Class".equals(pts.get(0))) {
                    sJ1ServiceClass = clsName;
                    LogWriter.log(TAG, "findJ1Service (any s(Class)): " + clsName);
                    return;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findJ1Service error: " + e.getMessage());
        }
    }

    private static void findJ1Caller(DexKitBridge bridge) {
        try {
            // 8.0.78: j1 服务定位器混淆为 gp0.j1，不再用 hm0.j1
            // v955: 3180 定位方法为 v(Class)(旧版 s), 双签名兼容
            String[] j1Candidates = {"gp0.j1.j", "gp0.j1", "hm0.j1", "fp0.j1.j", "fp0.j1"};
            for (String j1Class : j1Candidates) {
                for (String mn : new String[]{"v", "s"}) {
                    try {
                        MethodMatcher callerMatcher = MethodMatcher.create()
                            .addInvoke(MethodMatcher.create()
                                .declaredClass(j1Class)
                                .name(mn)
                                .paramTypes("java.lang.Class"));
                        List<MethodData> methods = bridge.findMethod(
                            FindMethod.create().matcher(callerMatcher)
                        );
                        if (!methods.isEmpty()) {
                            MethodData first = methods.get(0);
                            sJ1CallerClass = first.getClassName();
                            sJ1CallerMethod = first.getName();
                            LogWriter.log(TAG, "findJ1Caller: " + sJ1CallerClass + "." + sJ1CallerMethod + " via " + j1Class + "." + mn);
                            return;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            LogWriter.log(TAG, "findJ1Caller: NOT found");
        } catch (Throwable e) {
            LogWriter.log(TAG, "findJ1Caller error: " + e.getMessage());
        }
    }

    private static void findContactStorageAlt(DexKitBridge bridge) {
        try {
            // 8.0.78: contact storage 类已混淆为 e32.a 等短名，不再包含 "storage"
            // 搜索所有 ij() 返回 long 的类，取第一个（通常只有一个）
            MethodMatcher mMatcher = MethodMatcher.create()
                .name("ij")
                .returnType("long");
            List<MethodData> methods = bridge.findMethod(
                FindMethod.create().matcher(mMatcher)
            );
            LogWriter.log(TAG, "findContactStorageAlt: " + methods.size() + " ij() returning long");
            for (MethodData m : methods) {
                String clsName = m.getClassName();
                if (clsName == null) continue;
                // 8.0.78: contact storage 类名可能是 e32.a 等短名
                sContactStorageClass = clsName;
                LogWriter.log(TAG, "findContactStorageAlt: " + clsName + ".ij()");
                return;
            }
            // 兜底：搜索所有 ij() 方法
            MethodMatcher objMatcher = MethodMatcher.create()
                .name("ij");
            List<MethodData> objMethods = bridge.findMethod(
                FindMethod.create().matcher(objMatcher)
            );
            for (MethodData m : objMethods) {
                String clsName = m.getClassName();
                if (clsName != null) {
                    sContactStorageClass = clsName;
                    LogWriter.log(TAG, "findContactStorageAlt (any ij): " + clsName + ".ij()");
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
     *  微信 3180(8.0.78) 起长按菜单文案("置顶聊天"/"标为未读"/"删除该聊天"/"不显示该聊天")
     *  已从 dex 常量迁入 R.string 资源(gqi/gqe/gqc/gq6/bl9)，dex 中已无线索，
     *  旧 usingStrings 组合 5 个串中 3 个消失，AND 语义下必然 0 命中。
     *  3180 新架构(逆向实证)：
     *   - com.tencent.mm.ui.conversation.s3 "ConversationLongClickListener"
     *     同时实现 AdapterView$OnItemLongClickListener + View$OnCreateContextMenuListener，
     *     onItemLongClick 内 new eu5.s0(...).g(view,pos,id,this,...) 弹 MMListPopupWindow；
     *   - 菜单项经 s3.onCreateContextMenu(ContextMenu,View,ContextMenuInfo) 的
     *     contextMenu.add(groupId,itemId,order,"置顶"/"取消置顶") 加入("置顶"系硬编码残留)；
     *   - s0.g() 回调 onCreateContextMenuListener.onCreateContextMenu(自建 ContextMenu 实现, ...)，
     *     条目存入 f314615d(ArrayList&lt;MenuItem&gt;) 后经 kj5.f5(继承 PopupWindow).showAtLocation 展示。
     *  因此改用日志 TAG 特征字符串(双特征组合，符合 特征字符串>方法签名>父类接口 优先级)。 */
    private static void findConvMenuEntry(DexKitBridge bridge) {
        try {
            // 特征1(首选·特征字符串): ConversationLongClickListener 日志 TAG, 3180 dex 命中 1 处(s3.onItemLongClick 内 Log.i)
            MethodMatcher mMatcher = MethodMatcher.create()
                .usingStrings("MicroMsg.ConversationLongClickListener");
            List<MethodData> methods = bridge.findMethod(
                FindMethod.create().matcher(mMatcher)
            );
            LogWriter.log(TAG, "findConvMenuEntry: " + methods.size()
                + " methods use 'MicroMsg.ConversationLongClickListener'");
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

            // 特征2(父类/接口特征): 同时实现长按监听 + 建菜单监听的类, 3180 实证为 s3
            ClassMatcher cm = ClassMatcher.create()
                .addInterface("android.widget.AdapterView$OnItemLongClickListener")
                .addInterface("android.view.View$OnCreateContextMenuListener");
            List<ClassData> impls = bridge.findClass(FindClass.create().matcher(cm));
            LogWriter.log(TAG, "findConvMenuEntry: " + impls.size()
                + " classes impl OnItemLongClickListener+OnCreateContextMenuListener");
            for (ClassData c : impls) {
                String cn = c.getName();
                LogWriter.log(TAG, "  menuImpl: " + cn);
                if (cn != null && cn.contains("conversation")) {
                    sConvMenuClass = cn;
                    sConvMenuMethod = "onCreateContextMenu";
                    LogWriter.log(TAG, "findConvMenuEntry: selected(impl) " + cn + ".onCreateContextMenu");
                    return;
                }
            }
            if (!impls.isEmpty()) {
                sConvMenuClass = impls.get(0).getName();
                sConvMenuMethod = "onCreateContextMenu";
                LogWriter.log(TAG, "findConvMenuEntry: selected(impl any) " + sConvMenuClass);
                return;
            }

            // 特征3(兜底·特征字符串): MMPopupMenu 日志 TAG, 3180 dex 命中 2 处(eu5.s0 内)
            MethodMatcher popupMatcher = MethodMatcher.create()
                .usingStrings("MicroMsg.MMPopupMenu");
            List<MethodData> popupMethods = bridge.findMethod(
                FindMethod.create().matcher(popupMatcher)
            );
            LogWriter.log(TAG, "findConvMenuEntry: " + popupMethods.size()
                + " methods use 'MicroMsg.MMPopupMenu'");
            for (MethodData m : popupMethods) {
                LogWriter.log(TAG, "  popupImpl: " + m.getClassName() + "." + m.getName());
            }
            if (!popupMethods.isEmpty()) {
                sConvMenuClass = popupMethods.get(0).getClassName();
                sConvMenuMethod = popupMethods.get(0).getName();
                LogWriter.log(TAG, "findConvMenuEntry: selected(popup) "
                    + sConvMenuClass + "." + sConvMenuMethod);
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findConvMenuEntry error: " + e.getMessage());
        }
    }

    // ==================== v955: 3180 适配目标检索（严格遵守 特征字符串 > 方法签名 > 父类/接口） ====================

    private static volatile java.util.List<String> sRevokeListenerClasses = new java.util.ArrayList<>();
    private static volatile String sLabelStorageProviderClass;
    private static volatile String sLabelStorageProviderMethod = "bj";
    private static volatile String sPinyinUtilClass;
    private static volatile String sServiceLocatorClass;      // ph5.n0 等价物(ServiceManager)
    private static volatile String sMediaPathServiceClass;    // Nj(...) 媒体路径服务
    private static volatile String sMediaPathMethod = "Nj";
    private static volatile String sClipboardJsApiClass;      // setClipboardData jsapi
    private static volatile java.util.List<String> sChatMoreSelectClasses = new java.util.ArrayList<>();

    public static java.util.List<String> getRevokeListenerClasses() { return sRevokeListenerClasses; }
    public static String getLabelStorageProviderClass() { return sLabelStorageProviderClass; }
    public static String getLabelStorageProviderMethod() { return sLabelStorageProviderMethod; }
    public static String getPinyinUtilClass() { return sPinyinUtilClass; }
    public static String getServiceLocatorClass() { return sServiceLocatorClass; }
    public static String getMediaPathServiceClass() { return sMediaPathServiceClass; }
    public static String getMediaPathMethod() { return sMediaPathMethod; }
    public static String getClipboardJsApiClass() { return sClipboardJsApiClass; }
    public static java.util.List<String> getChatMoreSelectClasses() { return sChatMoreSelectClasses; }

    /** v955 3180 适配目标统一检索入口 */
    private static void findV955Targets(DexKitBridge bridge) {
        findRevokeListeners(bridge);
        findLabelStorageProvider(bridge);
        findPinyinUtil(bridge);
        findServiceLocator(bridge);
        findMediaPathService(bridge);
        findClipboardJsApi(bridge);
        findChatMoreSelect(bridge);
    }

    /** 撤回事件监听类: 特征字符串(日志TAG, 首选) */
    private static void findRevokeListeners(DexKitBridge bridge) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        // v961: 特征串收紧 —— v960 的裸 "revoke"/"revokeMsg" 误伤 300+ 类
        // (含 FinderRedDotNotifyReportStruct 等无关类, 其 callback 被 setResult(null)
        // 会破坏微信功能)。仅保留精确日志TAG + 回调签名 + 实证类名候选。
        for (String tag : new String[]{
                "MicroMsg.RevokeReceiveMessageListener",
                "MicroMsg.RevokeMsgListener"}) {
            try {
                List<ClassData> classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().usingStrings(tag)));
                for (ClassData c : classes) {
                    String cn = c.getName();
                    if (cn == null) continue;
                    out.add(cn);
                }
                LogWriter.log(TAG, "findRevokeListeners(" + tag + "): " + classes.size() + " cands");
            } catch (Throwable e) {
                LogWriter.log(TAG, "findRevokeListeners(" + tag + ") err: " + e.getMessage());
            }
        }
        // 路径2: 类名候选直查(v955/v956 实证的 3180 链路)
        if (out.isEmpty()) {
            for (String cn : new String[]{
                    "com.tencent.mm.ui.chatting.RevokeMsgListener",
                    "com.tencent.mm.ui.chatting.RevokeReceiveMessageListener",
                    "com.tencent.mm.chatroom.plugin.listener.n0"}) {
                try {
                    List<ClassData> classes = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create().className(cn)));
                    for (ClassData c : classes) {
                        if (c.getName() != null) out.add(c.getName());
                    }
                    LogWriter.log(TAG, "findRevokeListeners(byName " + cn + "): " + classes.size() + " cands");
                } catch (Throwable e) {
                    LogWriter.log(TAG, "findRevokeListeners(byName " + cn + ") err: " + e.getMessage());
                }
            }
        }
        // 路径3: callback 方法签名检索 —— 参数含撤回事件(autogen.fm.ks), 精确类型匹配
        if (out.isEmpty()) {
            try {
                List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().name("callback")));
                for (MethodData m : methods) {
                    List<String> pts = m.getParamTypeNames();
                    if (pts == null) continue;
                    boolean hit = false;
                    for (String pt : pts) {
                        if (pt != null && pt.contains("fm.ks")) {
                            hit = true;
                            break;
                        }
                    }
                    if (hit) {
                        String cn = m.getClassName();
                        if (cn != null) out.add(cn);
                    }
                }
                LogWriter.log(TAG, "findRevokeListeners(byCallback fm.ks): " + out.size() + " cands");
            } catch (Throwable e) {
                LogWriter.log(TAG, "findRevokeListeners(byCallback) err: " + e.getMessage());
            }
        }
        // v961: 兜底仍为空时, 用类名含 Revoke 的宽匹配(比裸字符串安全得多)
        if (out.isEmpty()) {
            try {
                List<ClassData> classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().className("Revoke", StringMatchType.Contains, false)));
                for (ClassData c : classes) {
                    String cn = c.getName();
                    if (cn == null) continue;
                    String simple = cn.substring(cn.lastIndexOf('.') + 1);
                    if (simple.toLowerCase().contains("revoke")) out.add(cn);
                }
                LogWriter.log(TAG, "findRevokeListeners(byClassName Revoke): " + out.size() + " cands");
            } catch (Throwable e) {
                LogWriter.log(TAG, "findRevokeListeners(byClassName) err: " + e.getMessage());
            }
        }
        sRevokeListenerClasses = new java.util.ArrayList<>(out);
        LogWriter.log(TAG, "findRevokeListeners: " + sRevokeListenerClasses);
    }

    /** 标签存储提供者: 方法签名(静态 + 返回 com.tencent.mm.storage.g4) */
    private static void findLabelStorageProvider(DexKitBridge bridge) {
        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .modifiers(java.lang.reflect.Modifier.STATIC)
                            .returnType("com.tencent.mm.storage.g4")));
            for (MethodData m : methods) {
                if (m.getParamTypeNames().isEmpty()) {
                    sLabelStorageProviderClass = m.getClassName();
                    sLabelStorageProviderMethod = m.getName();
                    LogWriter.log(TAG, "findLabelStorageProvider: " + sLabelStorageProviderClass
                            + "." + sLabelStorageProviderMethod + "()");
                    return;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findLabelStorageProvider err: " + e.getMessage());
        }
    }

    /** 拼音工具类: 方法签名(静态 a(String)→String 且 静态 b(String)→String 同类) */
    private static void findPinyinUtil(DexKitBridge bridge) {
        try {
            List<MethodData> aMethods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .modifiers(java.lang.reflect.Modifier.STATIC)
                            .name("a")
                            .paramCount(1)
                            .paramTypes("java.lang.String")
                            .returnType("java.lang.String")));
            for (MethodData am : aMethods) {
                String cn = am.getClassName();
                if (cn == null) continue;
                List<MethodData> bMethods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .modifiers(java.lang.reflect.Modifier.STATIC)
                                .name("b")
                                .paramCount(1)
                                .paramTypes("java.lang.String")
                                .returnType("java.lang.String")
                                .declaredClass(cn)));
                if (!bMethods.isEmpty()) {
                    sPinyinUtilClass = cn;
                    LogWriter.log(TAG, "findPinyinUtil: " + cn);
                    return;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findPinyinUtil err: " + e.getMessage());
        }
    }

    /** 服务定位器(ph5.n0 等价物): 特征字符串 "MicroMsg.ServiceManager" + 静态 c(Class) 签名 */
    private static void findServiceLocator(DexKitBridge bridge) {
        try {
            List<ClassData> classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().usingStrings("MicroMsg.ServiceManager")));
            for (ClassData c : classes) {
                String cn = c.getName();
                if (cn == null) continue;
                List<MethodData> cms = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .modifiers(java.lang.reflect.Modifier.STATIC)
                                .name("c")
                                .paramCount(1)
                                .paramTypes("java.lang.Class")
                                .declaredClass(cn)));
                if (!cms.isEmpty()) {
                    sServiceLocatorClass = cn;
                    LogWriter.log(TAG, "findServiceLocator: " + cn + ".c(Class)");
                    return;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findServiceLocator err: " + e.getMessage());
        }
    }

    /** 媒体路径服务: 方法签名 Nj(4参)→String */
    private static void findMediaPathService(DexKitBridge bridge) {
        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .name("Nj")
                            .paramCount(4)
                            .returnType("java.lang.String")));
            for (MethodData m : methods) {
                sMediaPathServiceClass = m.getClassName();
                sMediaPathMethod = m.getName();
                LogWriter.log(TAG, "findMediaPathService: " + sMediaPathServiceClass + "." + sMediaPathMethod);
                return;
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findMediaPathService err: " + e.getMessage());
        }
    }

    /** 小程序剪贴板 jsapi: 特征字符串 "setClipboardData"(jsapi 名稳定) */
    private static void findClipboardJsApi(DexKitBridge bridge) {
        try {
            List<ClassData> classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().usingStrings("setClipboardData")));
            for (ClassData c : classes) {
                String cn = c.getName();
                if (cn != null && cn.contains("jsapi")) {
                    sClipboardJsApiClass = cn;
                    LogWriter.log(TAG, "findClipboardJsApi: " + cn);
                    return;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findClipboardJsApi err: " + e.getMessage());
        }
    }

    /** 聊天多选 UI(ChatMoreSelectUI 等价物): 方法签名(onCreateOptionsMenu + onOptionsItemSelected 同类) */
    private static void findChatMoreSelect(DexKitBridge bridge) {
        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .searchPackages("com.tencent.mm.ui.chatting")
                    .matcher(MethodMatcher.create()
                            .name("onCreateOptionsMenu")
                            .paramCount(1)));
            java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
            for (MethodData m : methods) {
                String cn = m.getClassName();
                if (cn == null) continue;
                List<MethodData> oim = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .name("onOptionsItemSelected")
                                .paramCount(1)
                                .declaredClass(cn)));
                if (!oim.isEmpty() && (cn.contains("Select") || cn.contains("More"))) {
                    out.add(cn);
                }
            }
            sChatMoreSelectClasses = new java.util.ArrayList<>(out);
            LogWriter.log(TAG, "findChatMoreSelect: " + sChatMoreSelectClasses);
        } catch (Throwable e) {
            LogWriter.log(TAG, "findChatMoreSelect err: " + e.getMessage());
        }
    }

    /** 定位 kc5.g4(Menu 接口) 的实现类：微信自定义菜单用混淆接口 kc5.g4，
     *  直接 hook 接口无效（LSPosed 挂不上），必须 hook 具体实现类。 */
    private static void findMenuG4Impls(DexKitBridge bridge) {
        try {
            // 8.0.78: kc5.g4 doesn't exist, search for Menu implementations with string "Menu" in class name
            ClassMatcher cm = ClassMatcher.create()
                .addInterface("android.view.Menu");
            List<ClassData> impls = bridge.findClass(FindClass.create().matcher(cm));
            LogWriter.log(TAG, "findMenuG4Impls: " + impls.size() + " classes implement android.view.Menu");
            List<String> newList = new java.util.ArrayList<>();
            for (ClassData c : impls) {
                String cn = c.getName();
                if (cn != null && (cn.contains("menu") || cn.contains("Menu") || cn.contains("kc5"))) {
                    LogWriter.log(TAG, "  menuImpl: " + cn);
                    newList.add(cn);
                }
            }
            // Also search by string "Menu" in class name
            try {
                ClassMatcher cm2 = ClassMatcher.create()
                    .className(".*[Mm]enu.*");
                List<ClassData> impls2 = bridge.findClass(FindClass.create().matcher(cm2));
                for (ClassData c : impls2) {
                    String cn = c.getName();
                    if (cn != null && !newList.contains(cn) && !cn.startsWith("android.") && !cn.startsWith("java.")) {
                        LogWriter.log(TAG, "  menuImpl2: " + cn);
                        newList.add(cn);
                    }
                }
            } catch (Throwable ignored) {}
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

                    // Initialize MMKV first (needed for cache check)
                    try {
                        MMKV.initialize(app);
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "MMKV.initialize err: " + e.getMessage());
                    }

                    // Try loading from MMKV cache first — if hit, no scan needed
                    try {
                        MMKV kv = MMKV.mmkvWithID(MMKV_RESULTS_ID, MMKV.MULTI_PROCESS_MODE);
                        int cachedVersion = kv.decodeInt(KEY_VERSION_CODE, 0);
                        String cachedP06 = kv.decodeString(KEY_P06_CLASS, null);
                        if (cachedVersion == sVersionCode && cachedP06 != null) {
                            LogWriter.log(TAG, "hookApplication: MMKV cache hit for version " + cachedVersion);
                            boolean ok = loadResultsFromMMKV(app);
                            if (ok) {
                                return;
                            }
                            LogWriter.log(TAG, "hookApplication: cache incomplete, falling back to scan");
                        } else {
                            LogWriter.log(TAG, "hookApplication: MMKV cache miss (cached=" + cachedVersion + " current=" + sVersionCode + ")");
                        }
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "hookApplication MMKV check err: " + e.getMessage());
                    }

                    // Cache miss — show scan dialog on next Activity creation
                    sShouldShowScanDialog = true;

                    sExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            DexKitCacheBridge.RecyclableBridge cacheBridge = null;
                            try {
                                loadDexKitLibrary(app);
                                if (!sLibraryLoaded.get()) {
                                    LogWriter.log(TAG, "DexKit library not loaded, skipping");
                                    sShouldShowScanDialog = false;
                                    com.leshao.v3.ui.DexKitScanDialog.dismiss();
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
                                // Do NOT dismiss dialog here — user closes manually
                            }
                        }
                    });
                }
            });

            // Hook Activity.onCreate to show dialog at the right time
            try {
                XposedBridge.hookAllMethods(android.app.Activity.class, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!sShouldShowScanDialog) return;
                        if (!"com.tencent.mm".equals(((android.app.Activity) param.thisObject).getPackageName())) return;
                        // Only show on LauncherUI, not on splash activities
                        if (!"com.tencent.mm.ui.LauncherUI".equals(param.thisObject.getClass().getName())) return;
                        sShouldShowScanDialog = false;
                        LogWriter.log(TAG, "DexKitScanDialog shown on LauncherUI");
                        com.leshao.v3.ui.DexKitScanDialog.show((android.content.Context) param.thisObject);
                    }
                });
            } catch (Throwable e) {
                LogWriter.log(TAG, "hookActivity onCreate err: " + e.getMessage());
            }

            LogWriter.log(TAG, "ContextWrapper.attachBaseContext hook installed");
        } catch (Throwable e) {
            LogWriter.log(TAG, "hookApplication error: " + e.getMessage());
        }
    }

    public static void waitKernelInit(final ClassLoader cl, final KernelReadyCallback callback) {
        try {
            // Use DexKit-discovered j1 class first, fall back to candidates
            Class<?> j1 = null;
            String dexKitJ1 = sJ1ServiceClass;
            if (dexKitJ1 != null && !dexKitJ1.isEmpty()) {
                try { j1 = XposedHelpers.findClass(dexKitJ1, cl); } catch (Throwable ignored) {}
            }
            if (j1 == null) {
                String[] j1Candidates = {"gp0.j1.j", "hm0.j1", "gp0.j1", "fp0.j1.j"};
                for (String name : j1Candidates) {
                    try { j1 = XposedHelpers.findClass(name, cl); break; } catch (Throwable ignored) {}
                }
            }
            if (j1 == null) {
                LogWriter.log(TAG, "waitKernelInit: no j1 class found");
                return;
            }
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
                    LogWriter.log(TAG, "waitKernelInit: hook installed on " + j1.getName() + "." + m.getName());
                    return;
                }
            }
            LogWriter.log(TAG, "waitKernelInit: no single-param s() method found");
        } catch (Throwable e) {
            LogWriter.log(TAG, "waitKernelInit error: " + e.getMessage());
        }
    }
}