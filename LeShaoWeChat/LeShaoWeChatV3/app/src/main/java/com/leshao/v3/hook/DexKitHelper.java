package com.leshao.v3.hook;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import androidx.recyclerview.widget.ItemTouchHelper;
import com.leshao.v3.ContextManager;
import com.leshao.v3.IconLoader;
import com.leshao.v3.LogWriter;
import com.leshao.v3.PathUtil;
import com.leshao.v3.ui.DexKitScanDialog;
import com.tencent.mmkv.MMKV;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.DexKitCacheBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;

/* loaded from: classes4.dex */
public class DexKitHelper {
    private static final String BASELINE_ASSET = "dexkit_baseline.json";
    private static final String BASELINE_FILE = "dexkit_baseline.json";
    private static final int CURRENT_MODULE_VERSION = 30222;
    private static final String KEY_A21_CLASS = "a21_class";
    private static final String KEY_A21_METHOD = "a21_method";
    private static final String KEY_ACTION_BAR_CLASS = "action_bar_custom_area";
    private static final String KEY_AVATAR_HELPER = "avatar_helper";
    private static final String KEY_CHAT_MORE_SELECT = "chat_more_select";
    private static final String KEY_CHAT_OPEN_CLASS = "chat_open_class";
    private static final String KEY_CHAT_OPEN_METHOD = "chat_open_method";
    private static final String KEY_CLIPBOARD_JSAPI = "clipboard_jsapi";
    private static final String KEY_CONTACT_STORAGE = "contact_storage";
    private static final String KEY_CONV_LIST_ADAPTER = "conv_list_adapter";
    private static final String KEY_CONV_LONGPRESS_CLASS = "conv_longpress_class";
    private static final String KEY_CONV_LONGPRESS_METHOD = "conv_longpress_method";
    private static final String KEY_CONV_LP_IMPLS = "conv_lp_impls";
    private static final String KEY_CONV_MENU_CLASS = "conv_menu_class";
    private static final String KEY_CONV_MENU_METHOD = "conv_menu_method";
    private static final String KEY_CONV_SCROLL_CLASS = "conv_scroll_class";
    private static final String KEY_CONV_SCROLL_METHOD = "conv_scroll_method";
    private static final String KEY_CSO_LOADER = "cso_loader";
    private static final String KEY_CSO_LOADER_METHOD = "cso_loader_method";
    private static final String KEY_CTINKER_INSTALLER = "ctinker_installer";
    private static final String KEY_DB_OPENER_CLASS = "db_opener_class";
    private static final String KEY_DB_OPEN_METHOD = "db_open_method";
    private static final String KEY_DB_OPEN_PARAMS = "db_open_params";
    private static final String KEY_E9_CLASS = "e9_class";
    private static final String KEY_IMEI_CLASS = "imei_class";
    private static final String KEY_IMEI_METHOD = "imei_method";
    private static final String KEY_J1_SERVICE = "j1_service";
    private static final String KEY_LABEL_PROVIDER_CLASS = "label_provider_class";
    private static final String KEY_LABEL_PROVIDER_METHOD = "label_provider_method";
    private static final String KEY_LABEL_STORAGE = "label_storage";
    private static final String KEY_MEDIA_PATH_METHOD = "media_path_method";
    private static final String KEY_MEDIA_PATH_SERVICE = "media_path_service";
    private static final String KEY_MENU_G4_IMPLS = "menu_g4_impls";
    private static final String KEY_MODULE_VERSION = "module_version";
    private static final String KEY_NETSCENE_UPDATE_INFO = "netscene_update_info";
    private static final String KEY_P06_CLASS = "p06_class";
    private static final String KEY_PINYIN_UTIL = "pinyin_util";
    private static final String KEY_REVOKE_LISTENERS = "revoke_listeners";
    private static final String KEY_SERVICE_LOCATOR = "service_locator";
    private static final String KEY_UPDATER_MANAGER = "updater_manager";
    private static final String KEY_VERSION_CODE = "version_code";
    private static final String KEY_VOICE_API = "voice_api";
    private static final String KEY_X9_CLASS = "x9_class";
    private static final String MMKV_RESULTS_ID = "dexkit_scan_v3";
    private static final String FIND_CACHE_MMKV = "dexkit_find_cache";
    private static final String TAG = "DexKit";
    private static volatile String sA21ClassName;
    private static volatile String sA21MethodName;
    private static volatile String sActionBarCustomAreaClass;
    private static volatile String sAvatarHelperClass;
    private static volatile String sChatOpenClass;
    private static volatile String sChatOpenMethod;
    private static volatile String sClipboardJsApiClass;
    private static volatile String sContactStorageClass;
    private static volatile String sConvListListAdapterClass;
    private static volatile String sConvLongPressClass;
    private static volatile String sConvLongPressMethod;
    private static volatile String sConvMenuClass;
    private static volatile String sConvMenuMethod;
    private static volatile String sConvScrollClass;
    private static volatile String sConvScrollMethod;
    private static volatile String sCsoLoaderClass;
    private static volatile String sCsoLoaderMethod;
    private static volatile String sCtinkerInstaller;
    private static volatile String sDbOpenMethodName;
    private static volatile String[] sDbOpenMethodParamTypes;
    private static volatile String sDbOpenerClass;
    private static volatile String sE9ClassName;
    private static volatile String sImeiClassName;
    private static volatile String sImeiMethodName;
    private static volatile String sJ1ServiceClass;
    private static volatile String sLabelStorageClass;
    private static volatile String sLabelStorageProviderClass;
    private static volatile String sMediaPathServiceClass;
    private static volatile String sNetSceneUpdateInfo;
    private static volatile String sP06ClassName;
    private static volatile String sPinyinUtilClass;
    private static volatile ScanProgressCallback sProgressCallback;
    private static volatile String sServiceLocatorClass;
    private static volatile String sUpdaterManager;
    private static volatile String sVoiceApiClass;
    private static volatile String sX9Class;
    private static final AtomicBoolean sLibraryLoaded = new AtomicBoolean(false);
    private static volatile boolean sScanComplete = false;
    private static volatile boolean sShouldShowScanDialog = false;
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static final List<Runnable> sPostScanCallbacks = new CopyOnWriteArrayList();
    private static volatile boolean sReloading = false;
    private static final Object sScanLock = new Object();
    private static final Object sBridgeLock = new Object();
    private static final AtomicBoolean sCloneRescanAttempted = new AtomicBoolean(false);
    private static final AtomicBoolean sFullScanScheduled = new AtomicBoolean(false);
    private static volatile List<String> sConvLongPressImpls = new ArrayList();
    private static volatile List<String> sMenuG4Impls = new ArrayList();
    private static volatile int sVersionCode = 0;
    private static volatile int sModuleVersion = 0;
    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() { // from class: com.leshao.v3.hook.DexKitHelper.3
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        @Override // java.util.concurrent.ThreadFactory
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "DexKitScan-" + this.threadNumber.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(1);
            return t;
        }
    });
    private static volatile boolean sCacheInitialized = false;
    private static volatile Context sAppContextForCache = null;
    private static final AtomicBoolean sActionBarRescanAttempted = new AtomicBoolean(false);
    private static final AtomicBoolean sRuntimeRescanAttempted = new AtomicBoolean(false);
    private static volatile List<String> sRevokeListenerClasses = new ArrayList();
    private static volatile String sLabelStorageProviderMethod = "bj";
    private static volatile String sMediaPathMethod = "Nj";
    private static volatile List<String> sChatMoreSelectClasses = new ArrayList();

    /* loaded from: classes4.dex */
    public interface BridgeAction<T> {
        T run(DexKitBridge dexKitBridge);
    }

    /* loaded from: classes4.dex */
    public interface KernelReadyCallback {
        void onKernelReady(DexKitBridge dexKitBridge);
    }

    /* loaded from: classes4.dex */
    public interface ScanProgressCallback {
        void onComplete();

        void onProgress(int i, String str, String str2);
    }

    public static void setProgressCallback(ScanProgressCallback callback) {
        sProgressCallback = callback;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void reportProgress(int percent, String status, String detail) {
        if (sProgressCallback != null) {
            try {
                sProgressCallback.onProgress(percent, status, detail);
            } catch (Throwable th) {
            }
        }
    }

    private static void reportComplete() {
        if (sProgressCallback != null) {
            try {
                sProgressCallback.onComplete();
            } catch (Throwable th) {
            }
        }
    }

    /** v3.0.153: 扫描完成时若存在未定位到的目标, 记录摘要供弹窗提示(非空即说明部分功能不可用)。 */
    private static volatile String sMissingSummary = null;

    /** v3.0.169: 最近一次全量扫描是否健康(核心关键目标全部命中)。
     *  不健康视为"扫描过早/CL 未就绪", 不写缓存版本号, 下次启动补扫。 */
    private static volatile boolean sLastScanHealthy = false;

    public static String getMissingSummary() {
        return sMissingSummary;
    }

/** v3.0.173: 核心扫描结果是否健康。仅检查必须依赖 DexKit 扫描值、无运行时兜底的核心锚点。
     *  dbOpener(数据库)由 DatabaseProvider 独立 hook openDatabase 兜底；imei/csoLoader/
     *  labelStorage/avatarHelper/chatOpen/convAdapter 等同样有运行时兜底或无需扫描值即能工作。
     *  全部剔除，避免健康判定被"本就无法定位的项"卡死导致缓存永不持久化，
     *  子进程(小程序/红包)永远读旧缓存。 */
    private static boolean isCoreScanHealthy() {
        return sP06ClassName != null
                && sContactStorageClass != null
                && sVoiceApiClass != null
                && sE9ClassName != null
                && sJ1ServiceClass != null;
    }

    /** 汇总真正失效的核心目标(缺失即功能不可用、无运行时兜底); 全部命中返回 null。
     *  v3.0.169: 去除头像/标签存储/会话/菜单等有运行时兜底或无需扫描值即能工作的假误报项。 */
    private static String buildMissingSummary() {
        List<String> miss = new ArrayList<>();
        if (sJ1ServiceClass == null) miss.add("J1 服务定位器");
        if (sP06ClassName == null) miss.add("P06 核心类");
        if (sDbOpenerClass == null || sDbOpenMethodName == null) miss.add("数据库打开接口");
        if (sContactStorageClass == null) miss.add("通讯录存储");
        if (sVoiceApiClass == null) miss.add("语音 API");
        if (sE9ClassName == null) miss.add("e9 类");
        if (miss.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("以下 ").append(miss.size()).append(" 项未能在当前微信版本中定位，相关功能可能不可用：\n");
        for (int i = 0; i < miss.size(); i++) {
            sb.append("• ").append(miss.get(i));
            if (i != miss.size() - 1) sb.append('\n');
        }
        return sb.toString();
    }

    public static boolean isScanComplete() {
        boolean z;
        synchronized (DexKitHelper.class) {
            z = sScanComplete;
        }
        return z;
    }

    public static boolean isReloading() {
        return sReloading;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void markScanCompleteAndDrain() {
        synchronized (DexKitHelper.class) {
            sScanComplete = true;
        }
        runPostScanCallbacks();
    }

    public static void forceReload() {
        if (sReloading) {
            LogWriter.log(TAG, "forceReload: 已有重新加载任务进行中");
            return;
        }
        sReloading = true;
        sExecutor.execute(new Runnable() {
            @Override
            public void run() {
                DexKitCacheBridge.RecyclableBridge bridge = null;
                try {
                    synchronized (sScanLock) {
                        android.content.Context ctx = com.leshao.v3.ContextManager.getAppContext();
                        if (!(ctx instanceof Application)) {
                            LogWriter.log(TAG, "forceReload: app context 不可用");
                            return;
                        }
                        Application app = (Application) ctx;
                        synchronized (DexKitHelper.class) {
                            sScanComplete = false;
                        }
                        loadDexKitLibrary(app);
                        if (!sLibraryLoaded.get()) {
                            LogWriter.log(TAG, "forceReload: DexKit 库加载失败");
                            return;
                        }
                        initDexKitCache(app);
                        // v3.0.176: createBridge 内部已优先用 APK path 数据源, 无需再等 Tinker CL
                        bridge = createBridge(app.getClassLoader());
                        if (bridge == null) {
                            LogWriter.log(TAG, "forceReload: bridge 创建失败");
                            return;
                        }
                        LogWriter.log(TAG, "forceReload: 开始重新扫描 DexKit");
                        scanWechatTargets(bridge);
                        LogWriter.log(TAG, "forceReload: 重新扫描完成, complete=" + sScanComplete);
                    }
                } catch (Throwable t) {
                    LogWriter.log(TAG, "forceReload err: " + t.getMessage());
                } finally {
                    if (bridge != null) try { bridge.close(); } catch (Throwable ignored) {}
                    // 失败时也要放行回调, 避免永久排队
                    if (!sScanComplete) markScanCompleteAndDrain();
                    sReloading = false;
                }
            }
        });
    }



    public static void addPostScanCallback(Runnable callback) {
        boolean runNow = false;
        synchronized (DexKitHelper.class) {
            if (sScanComplete) {
                runNow = true;
            } else {
                sPostScanCallbacks.add(callback);
            }
        }
        if (runNow) {
            if (isMainThread()) {
                sExecutor.execute(callback);
            } else {
                callback.run();
            }
        }
    }

    private static void runPostScanCallbacks() {
        if (isMainThread()) {
            sExecutor.execute(new Runnable() { // from class: com.leshao.v3.hook.DexKitHelper$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    DexKitHelper.drainPostScanCallbacks();
                }
            });
        } else {
            drainPostScanCallbacks();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void drainPostScanCallbacks() {
        List<Runnable> pending;
        while (true) {
            synchronized (DexKitHelper.class) {
                List<Runnable> list = sPostScanCallbacks;
                if (list.isEmpty()) {
                    return;
                }
                pending = new ArrayList<>(list);
                list.clear();
            }
            for (Runnable cb : pending) {
                try {
                    cb.run();
                } catch (Throwable e) {
                    LogWriter.log(TAG, "postScanCallback error: " + e.getMessage());
                }
            }
        }
    }

    private static boolean isMainThread() {
        try {
            return Looper.myLooper() == Looper.getMainLooper();
        } catch (Throwable th) {
            return false;
        }
    }

    public static void waitForFullScanIfScheduled() {
        if (isMainThread()) {
            return;
        }
        // v3.0.136: 最多等待 30s，避免首次全量扫描异常卡住时所有 find* 线程无限阻塞
        long deadline = System.currentTimeMillis() + 30000L;
        while (sFullScanScheduled.get() && !isScanComplete()) {
            if (System.currentTimeMillis() > deadline) {
                LogWriter.log(TAG, "waitForFullScan: timeout 30s, proceed without scan result");
                return;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void deferCloneCacheRead(final Application app) {
        sMainHandler.postDelayed(new Runnable() { // from class: com.leshao.v3.hook.DexKitHelper.2
            @Override // java.lang.Runnable
            public void run() {
                try {
                    if (!DexKitHelper.loadResultsFromMMKV(app, false)) {
                        LogWriter.log(DexKitHelper.TAG, "clone process: cache still unavailable after defer (read-only)");
                    } else {
                        LogWriter.log(DexKitHelper.TAG, "clone process: cache available after defer");
                    }
                } catch (Throwable th) {
                }
            }
        }, 30000L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /** v3.0.176(官方 path 数据源): 获取微信 APK 文件路径(base.apk), 用于 DexKitCacheBridge.create(appTag, path)。
     *  DexKit 是纯 C++ 解析 DEX 文件字节, 与 ClassLoader 就绪时序无关; 直接扫落盘 APK 可彻底摆脱
     *  "等 Tinker CL 就绪" 的枷锁, 启动早期即可稳定扫描。混淆类名/字符串特征在 APK 文件内即可匹配。 */
    private static String getWechatApkPath() {
        try {
            Context ctx = sAppContextForCache;
            if (ctx == null) {
                try {
                    ctx = ContextManager.getAppContext();
                } catch (Throwable th) {
                }
            }
            if (ctx != null) {
                android.content.pm.ApplicationInfo ai = ctx.getApplicationInfo();
                if (ai != null && ai.sourceDir != null && new java.io.File(ai.sourceDir).isFile()) {
                    return ai.sourceDir;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static DexKitCacheBridge.RecyclableBridge createBridge(ClassLoader cl) {
        try {
            if (!sCacheInitialized) {
                Context ctx = sAppContextForCache;
                if (ctx == null) {
                    try {
                        ctx = ContextManager.getAppContext();
                    } catch (Throwable th) {
                    }
                }
                // v1147: app context 注入晚于部分后台线程(如 ls-msgmenu-resolve)首轮运行,
                // 短轮询等待其就绪, 避免首轮 createBridge 失败后重试多次。
                for (int wait = 0; ctx == null && wait < 15; wait++) {
                    try { Thread.sleep(100L); } catch (InterruptedException ie) { break; }
                    try {
                        ctx = ContextManager.getAppContext();
                    } catch (Throwable th) {
                    }
                }
                if (ctx instanceof Application) {
                    loadDexKitLibrary((Application) ctx);
                    initDexKitCache(ctx);
                } else if (ctx != null) {
                    initDexKitCache(ctx);
                }
                if (!sCacheInitialized) {
                    LogWriter.log(TAG, "createBridge: cache 自举失败(无 app context), 放弃");
                    return null;
                }
            }
            // v3.0.176(官方 path 数据源): 优先直接用微信 APK 路径建桥, 不依赖 ClassLoader 就绪时序。
            // path 数据源扫的是落盘的 base.apk DEX 文件, 微信混淆类名/字符串特征均在文件内, 无需等待
            // Tinker 热补丁 CL。仅当 APK 路径不可得时才回退 classLoader 数据源。
            String apkPath = getWechatApkPath();
            if (apkPath != null) {
                LogWriter.log(TAG, "createBridge: using apk path data source=" + apkPath);
                return DexKitCacheBridge.create("wechat_" + (sVersionCode > 0 ? Integer.valueOf(sVersionCode) : ""), apkPath);
            }
            LogWriter.log(TAG, "createBridge: apk path unavailable, fallback classLoader data source");
            return DexKitCacheBridge.create("wechat_" + (sVersionCode > 0 ? Integer.valueOf(sVersionCode) : ""), cl != null ? cl : DexKitHelper.class.getClassLoader());
        } catch (Throwable e) {
            LogWriter.log(TAG, "createBridge err: " + e.getMessage());
            return null;
        }
    }

    public static <T> T withWechatBridge(ClassLoader classLoader, final BridgeAction<T> bridgeAction) {
        T t = null;
        if (bridgeAction == null) {
            return null;
        }
        waitForFullScanIfScheduled();
        synchronized (sBridgeLock) {
            DexKitCacheBridge.RecyclableBridge createBridge = createBridge(classLoader);
            if (createBridge == null) {
                return null;
            }
            final Object[] objArr = new Object[1];
            try {
                createBridge.withBridge(new DexKitCacheBridge.RecyclableBridge.BridgeFunction() { // from class: com.leshao.v3.hook.DexKitHelper.4
                    @Override // org.luckypray.dexkit.DexKitCacheBridge.RecyclableBridge.BridgeFunction
                    public void apply(DexKitBridge b) {
                        objArr[0] = bridgeAction.run(b);
                    }
                });
            } catch (Throwable th) {
                LogWriter.log(TAG, "withWechatBridge err: " + th.getMessage());
            }
            if (objArr[0] != null) {
                t = (T) objArr[0];
            }
            return t;
        }
    }

    private static String findCacheKey(String type, String className, String keyword) {
        // v3.0.166: 缓存 key 混入模块版本 —— 模块升级后旧缓存(含过时混淆签名如旧 MMMenu 类)
        // 立即失效重新扫描，避免 MessageMenuHook 等因旧签名比对失败而 anchor not found。
        return type + "|" + CURRENT_MODULE_VERSION + "|" + (className == null ? "" : className) + "|" + keyword;
    }

    private static List<String> readFindCache(String key) {
        try {
            MMKV kv = MMKV.mmkvWithID(FIND_CACHE_MMKV, 2);
            String verKey = "v_" + sVersionCode;
            String json = kv.decodeString(verKey, null);
            if (json == null || json.isEmpty()) {
                return null;
            }
            JSONObject obj = new JSONObject(json);
            JSONArray arr = obj.optJSONArray(key);
            if (arr == null) {
                return null;
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                out.add(arr.getString(i));
            }
            return out;
        } catch (Throwable th) {
            return null;
        }
    }

    private static void writeFindCache(String key, List<String> results) {
        try {
            if (results == null || results.isEmpty()) {
                return;
            }
            MMKV kv = MMKV.mmkvWithID(FIND_CACHE_MMKV, 2);
            String verKey = "v_" + sVersionCode;
            String json = kv.decodeString(verKey, null);
            JSONObject obj = (json == null || json.isEmpty()) ? new JSONObject() : new JSONObject(json);
            JSONArray arr = new JSONArray();
            for (String s : results) {
                arr.put(s);
            }
            obj.put(key, arr);
            kv.encode(verKey, obj.toString());
            kv.sync();
        } catch (Throwable th) {
        }
    }

    public static List<String> findClassesByString(ClassLoader cl, final String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return new ArrayList<>();
        }
        String cacheKey = findCacheKey("C", null, keyword);
        List<String> cached = readFindCache(cacheKey);
        if (cached != null) {
            LogWriter.log(TAG, "findClassesByString(" + keyword + "): cache hit " + cached.size() + " candidates");
            return new ArrayList<>(cached);
        }
        // v3.0.136: 主线程缓存未命中时不再现场扫描（DexKit 扫描可能在启动/主页卡死主线程）
        if (isMainThread()) {
            LogWriter.log(TAG, "findClassesByString(" + keyword + "): cache MISS on main thread, return empty (avoid block)");
            return new ArrayList<>();
        }
        DexKitCacheBridge.RecyclableBridge bridge = null;
        final List<String> results = new ArrayList<>();
        waitForFullScanIfScheduled();
        synchronized (sBridgeLock) {
            try {
                bridge = createBridge(cl);
            } catch (Throwable e) {
                LogWriter.log(TAG, "findClassesByString err: " + e.getMessage());
            }
            if (bridge == null) {
                return results;
            }
            try {
                bridge.withBridge(new DexKitCacheBridge.RecyclableBridge.BridgeFunction() { // from class: com.leshao.v3.hook.DexKitHelper.5
                    @Override // org.luckypray.dexkit.DexKitCacheBridge.RecyclableBridge.BridgeFunction
                    public void apply(DexKitBridge b) {
                        try {
                            MethodMatcher mMatcher = MethodMatcher.create().usingStrings(keyword);
                            List<MethodData> methods = b.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(mMatcher));
                            for (MethodData m : methods) {
                                String cn = m.getClassName();
                                if (cn != null && !results.contains(cn)) {
                                    results.add(cn);
                                }
                            }
                        } catch (Throwable th) {
                        }
                        try {
                            ClassMatcher cMatcher = ClassMatcher.create().addFieldForType(keyword);
                            List<ClassData> classes = b.findClass(FindClass.create().excludePackages("com.leshao").matcher(cMatcher));
                            for (ClassData c : classes) {
                                String cn2 = c.getName();
                                if (cn2 != null && !results.contains(cn2)) {
                                    results.add(cn2);
                                }
                            }
                        } catch (Throwable th2) {
                        }
                    }
                });
                LogWriter.log(TAG, "findClassesByString(" + keyword + "): " + results.size() + " candidates");
                writeFindCache(cacheKey, results);
                return results;
            } finally {
                try {
                    bridge.close();
                } catch (Throwable th) {
                }
            }
        }
    }

    public static List<String> findMethodsByString(ClassLoader cl, final String className, final String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return new ArrayList<>();
        }
        String cacheKey = findCacheKey("M", className, keyword);
        List<String> cached = readFindCache(cacheKey);
        if (cached != null) {
            LogWriter.log(TAG, "findMethodsByString(" + className + "," + keyword + "): cache hit " + cached.size());
            return new ArrayList<>(cached);
        }
        // v3.0.136: 主线程缓存未命中时不再现场扫描（避免卡死启动/主页）
        if (isMainThread()) {
            LogWriter.log(TAG, "findMethodsByString(" + className + "," + keyword + "): cache MISS on main thread, return empty (avoid block)");
            return new ArrayList<>();
        }
        DexKitCacheBridge.RecyclableBridge bridge = null;
        final List<String> results = new ArrayList<>();
        waitForFullScanIfScheduled();
        synchronized (sBridgeLock) {
            try {
                bridge = createBridge(cl);
            } catch (Throwable e) {
                LogWriter.log(TAG, "findMethodsByString err: " + e.getMessage());
            }
            if (bridge == null) {
                return results;
            }
            try {
                bridge.withBridge(new DexKitCacheBridge.RecyclableBridge.BridgeFunction() { // from class: com.leshao.v3.hook.DexKitHelper.6
                    @Override // org.luckypray.dexkit.DexKitCacheBridge.RecyclableBridge.BridgeFunction
                    public void apply(DexKitBridge b) {
                        try {
                            MethodMatcher mMatcher = MethodMatcher.create().usingStrings(keyword);
                            String str = className;
                            if (str != null) {
                                mMatcher.declaredClass(str);
                            }
                            List<MethodData> methods = b.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(mMatcher));
                            for (MethodData m : methods) {
                                String sig = m.getClassName() + "." + m.getName() + "(" + String.join(",", m.getParamTypeNames()) + ")";
                                if (!results.contains(sig)) {
                                    results.add(sig);
                                }
                            }
                        } catch (Throwable th) {
                        }
                    }
                });
                LogWriter.log(TAG, "findMethodsByString(" + className + "," + keyword + "): " + results.size());
                writeFindCache(cacheKey, results);
                return results;
            } finally {
                try {
                    bridge.close();
                } catch (Throwable th) {
                }
            }
        }
    }

    /** 方法粒度字符串定位（DexKit_StringFinder_Method.md §8.2 推荐）：找「方法体引用该字符串」的方法，
     *  返回其声明类类名（去重）。比 findClassesByString（含 addFieldForType 类字段噪声）更精准，
     *  用于定位具体的 Hook 锚点类（如 CombineEntranceService=com.tencent.mm.feature.combine 的 a10/a）。 */
    public static List<String> findMethodDeclClassByString(ClassLoader cl, final String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return new ArrayList<>();
        }
        String cacheKey = findCacheKey("MD", null, keyword);   // MD=Method DeclClass by string
        List<String> cached = readFindCache(cacheKey);
        if (cached != null) {
            LogWriter.log(TAG, "findMethodDeclClassByString(" + keyword + "): cache hit " + cached.size() + " classes");
            return new ArrayList<>(cached);
        }
        if (isMainThread()) {
            LogWriter.log(TAG, "findMethodDeclClassByString(" + keyword + "): cache MISS on main thread, return empty (avoid block)");
            return new ArrayList<>();
        }
        DexKitCacheBridge.RecyclableBridge bridge = null;
        final List<String> results = new ArrayList<>();
        waitForFullScanIfScheduled();
        synchronized (sBridgeLock) {
            try {
                bridge = createBridge(cl);
            } catch (Throwable e) {
                LogWriter.log(TAG, "findMethodDeclClassByString err: " + e.getMessage());
            }
            if (bridge == null) {
                return results;
            }
            try {
                bridge.withBridge(new DexKitCacheBridge.RecyclableBridge.BridgeFunction() {
                    @Override
                    public void apply(DexKitBridge b) {
                        try {
                            MethodMatcher mMatcher = MethodMatcher.create().usingStrings(keyword);
                            List<MethodData> methods = b.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(mMatcher));
                            for (MethodData m : methods) {
                                String cn = m.getClassName();
                                if (cn != null && !results.contains(cn)) {
                                    results.add(cn);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                LogWriter.log(TAG, "findMethodDeclClassByString(" + keyword + "): " + results.size() + " classes");
                writeFindCache(cacheKey, results);
                return results;
            } finally {
                try {
                    bridge.close();
                } catch (Throwable th) {
                }
            }
        }
    }

    public static MethodData findMethod(ClassLoader cl, final String className, final String methodName, final String... paramTypeNames) {
        MethodData methodData = null;
        if (className == null || methodName == null) {
            return null;
        }
        // v3.0.136: 主线程不现场扫描（避免卡死启动/主页）
        if (isMainThread()) {
            LogWriter.log(TAG, "findMethod(" + className + "." + methodName + "): main thread, return null (avoid block)");
            return null;
        }
        waitForFullScanIfScheduled();
        synchronized (sBridgeLock) {
            try {
                DexKitCacheBridge.RecyclableBridge bridge = createBridge(cl);
                if (bridge == null) {
                    return null;
                }
                try {
                    final MethodData[] result = new MethodData[1];
                    bridge.withBridge(new DexKitCacheBridge.RecyclableBridge.BridgeFunction() { // from class: com.leshao.v3.hook.DexKitHelper.7
                        @Override // org.luckypray.dexkit.DexKitCacheBridge.RecyclableBridge.BridgeFunction
                        public void apply(DexKitBridge b) {
                            try {
                                MethodMatcher mMatcher = MethodMatcher.create().name(methodName);
                                String str = className;
                                if (str != null) {
                                    mMatcher.declaredClass(str);
                                }
                                List<MethodData> methods = b.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(mMatcher));
                                if (!methods.isEmpty()) {
                                    String[] strArr = paramTypeNames;
                                    if (strArr != null && strArr.length > 0) {
                                        for (MethodData m : methods) {
                                            List<String> pts = m.getParamTypeNames();
                                            if (pts.size() == paramTypeNames.length) {
                                                boolean match = true;
                                                int i = 0;
                                                while (true) {
                                                    String[] strArr2 = paramTypeNames;
                                                    if (i >= strArr2.length) {
                                                        break;
                                                    }
                                                    if (strArr2[i].isEmpty() || pts.get(i).equals(paramTypeNames[i])) {
                                                        i++;
                                                    } else {
                                                        match = false;
                                                        break;
                                                    }
                                                }
                                                if (match) {
                                                    result[0] = m;
                                                    return;
                                                }
                                            }
                                        }
                                    }
                                    result[0] = methods.get(0);
                                }
                            } catch (Throwable th) {
                            }
                        }
                    });
                    methodData = result[0];
                    return methodData;
                } finally {
                    try {
                        bridge.close();
                    } catch (Throwable th) {
                    }
                }
            } catch (Throwable e) {
                LogWriter.log(TAG, "findMethod err: " + e.getMessage());
                return methodData;
            }
        }
    }

    public static ClassData findClassByName(ClassLoader cl, final String simpleName) {
        ClassData classData = null;
        if (simpleName == null) {
            return null;
        }
        waitForFullScanIfScheduled();
        synchronized (sBridgeLock) {
            try {
                DexKitCacheBridge.RecyclableBridge bridge = createBridge(cl);
                if (bridge == null) {
                    return null;
                }
                try {
                    final ClassData[] result = new ClassData[1];
                    bridge.withBridge(new DexKitCacheBridge.RecyclableBridge.BridgeFunction() { // from class: com.leshao.v3.hook.DexKitHelper.8
                        @Override // org.luckypray.dexkit.DexKitCacheBridge.RecyclableBridge.BridgeFunction
                        public void apply(DexKitBridge b) {
                            try {
                                ClassMatcher cMatcher = ClassMatcher.create();
                                List<ClassData> classes = b.findClass(FindClass.create().excludePackages("com.leshao").matcher(cMatcher));
                                for (ClassData c : classes) {
                                    String cn = c.getName();
                                    if (cn != null && (cn.equals(simpleName) || cn.endsWith("." + simpleName))) {
                                        result[0] = c;
                                        return;
                                    }
                                }
                            } catch (Throwable th) {
                            }
                        }
                    });
                    classData = result[0];
                    return classData;
                } finally {
                    try {
                        bridge.close();
                    } catch (Throwable th) {
                    }
                }
            } catch (Throwable e) {
                LogWriter.log(TAG, "findClassByName err: " + e.getMessage());
                return classData;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void loadDexKitLibrary(Application app) {
        synchronized (DexKitHelper.class) {
            AtomicBoolean atomicBoolean = sLibraryLoaded;
            if (atomicBoolean.get()) {
                return;
            }
            try {
                System.loadLibrary("dexkit");
                atomicBoolean.set(true);
                LogWriter.log(TAG, "libdexkit loaded via System.loadLibrary");
            } catch (Throwable e) {
                LogWriter.log(TAG, "loadLibrary failed: " + e.getMessage());
                try {
                    ApplicationInfo moduleInfo = app.getPackageManager().getApplicationInfo("com.leshao.v3", 0);
                    String nativeLibDir = moduleInfo.nativeLibraryDir;
                    if (nativeLibDir != null) {
                        File soFile = new File(nativeLibDir, "libdexkit.so");
                        if (soFile.exists()) {
                            System.load(soFile.getAbsolutePath());
                            sLibraryLoaded.set(true);
                            LogWriter.log(TAG, "libdexkit loaded from " + soFile.getAbsolutePath());
                            return;
                        }
                    }
                } catch (Throwable e2) {
                    LogWriter.log(TAG, "nativeLibraryDir load failed: " + e2.getMessage());
                }
                try {
                    File tmpDir = new File(app.getFilesDir(), "dexkit_native");
                    tmpDir.mkdirs();
                    File tmpSo = new File(tmpDir, "libdexkit.so");
                    if (!tmpSo.exists()) {
                        InputStream in = app.getClass().getClassLoader().getResourceAsStream("lib/arm64-v8a/libdexkit.so");
                        if (in == null) {
                            in = app.getClass().getClassLoader().getResourceAsStream("lib/armeabi-v7a/libdexkit.so");
                        }
                        if (in != null) {
                            FileOutputStream out = new FileOutputStream(tmpSo);
                            byte[] buf = new byte[8192];
                            while (true) {
                                int n = in.read(buf);
                                if (n == -1) {
                                    break;
                                } else {
                                    out.write(buf, 0, n);
                                }
                            }
                            out.close();
                            in.close();
                        }
                    }
                    if (tmpSo.exists()) {
                        System.load(tmpSo.getAbsolutePath());
                        sLibraryLoaded.set(true);
                        LogWriter.log(TAG, "libdexkit loaded from extracted " + tmpSo.getAbsolutePath());
                    }
                } catch (Throwable e3) {
                    LogWriter.log(TAG, "extract load failed: " + e3.getMessage());
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void initDexKitCache(Context appContext) {
        synchronized (DexKitHelper.class) {
            if (appContext != null) {
                sAppContextForCache = appContext.getApplicationContext() != null ? appContext.getApplicationContext() : appContext;
            }
            if (sCacheInitialized) {
                return;
            }
            try {
                MMKV.initialize(appContext);
                DexKitCacheBridge.setIdleTimeoutMillis(5000L);
                DexKitCacheBridge.setCachePolicy(new DexKitCacheBridge.CachePolicy(true, DexKitCacheBridge.CacheFailurePolicy.NONE));
                DexKitCacheBridge.init(new MmkvCacheStorage());
                sCacheInitialized = true;
                LogWriter.log(TAG, "DexKit cache initialized (MMKV)");
            } catch (Throwable e) {
                LogWriter.log(TAG, "DexKit cache init failed: " + e.getMessage());
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean loadResultsFromMMKV(Application app) {
        return loadResultsFromMMKV(app, true);
    }

    /**
     * 加载 DexKit 扫描结果缓存。
     * allowClear=true（主进程）：版本不匹配或结果不完整时清空缓存并返回 false，随后触发全量扫描。
     * allowClear=false（clone 进程）：只读，绝不 clearAll —— 否则会清掉主进程刚写入的扫描结果，
     * 导致每个新启动的进程都读到空缓存、反复触发全量扫描。
     */
    public static boolean loadResultsFromMMKV(Application app, boolean allowClear) {
        try {
            MMKV kv = MMKV.mmkvWithID(MMKV_RESULTS_ID, 2);
            int cachedVersion = kv.decodeInt(KEY_VERSION_CODE, 0);
            if (cachedVersion != sVersionCode) {
                LogWriter.log(TAG, "loadResultsFromMMKV: version mismatch (cachedVer=" + cachedVersion + " currentVer=" + sVersionCode
                        + (allowClear ? "), clearing cache" : "), read-only keep cache"));
                if (allowClear) {
                    kv.clearAll();
                }
                return false;
            }
            int cachedModule = kv.decodeInt(KEY_MODULE_VERSION, 0);
            if (cachedModule != CURRENT_MODULE_VERSION) {
                LogWriter.log(TAG, "loadResultsFromMMKV: module version mismatch (cachedModule=" + cachedModule + " currentModule=" + CURRENT_MODULE_VERSION
                        + (allowClear ? "), clearing cache" : "), read-only keep cache"));
                if (allowClear) {
                    kv.clearAll();
                    kv.sync();
                }
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
            sA21MethodName = kv.decodeString(KEY_A21_METHOD, null);
            sAvatarHelperClass = kv.decodeString(KEY_AVATAR_HELPER, null);
            sLabelStorageClass = kv.decodeString(KEY_LABEL_STORAGE, null);
            sConvListListAdapterClass = kv.decodeString(KEY_CONV_LIST_ADAPTER, null);
            sActionBarCustomAreaClass = kv.decodeString(KEY_ACTION_BAR_CLASS, null);
            sLabelStorageProviderClass = kv.decodeString(KEY_LABEL_PROVIDER_CLASS, null);
            String lpMethod = kv.decodeString(KEY_LABEL_PROVIDER_METHOD, null);
            if (lpMethod != null && !lpMethod.isEmpty()) {
                sLabelStorageProviderMethod = lpMethod;
            }
            sPinyinUtilClass = kv.decodeString(KEY_PINYIN_UTIL, null);
            sServiceLocatorClass = kv.decodeString(KEY_SERVICE_LOCATOR, null);
            sMediaPathServiceClass = kv.decodeString(KEY_MEDIA_PATH_SERVICE, null);
            String mpMethod = kv.decodeString(KEY_MEDIA_PATH_METHOD, null);
            if (mpMethod != null && !mpMethod.isEmpty()) {
                sMediaPathMethod = mpMethod;
            }
            sClipboardJsApiClass = kv.decodeString(KEY_CLIPBOARD_JSAPI, null);
            sX9Class = kv.decodeString(KEY_X9_CLASS, null);
            sNetSceneUpdateInfo = kv.decodeString(KEY_NETSCENE_UPDATE_INFO, null);
            sUpdaterManager = kv.decodeString(KEY_UPDATER_MANAGER, null);
            sCtinkerInstaller = kv.decodeString(KEY_CTINKER_INSTALLER, null);
            String revoke = kv.decodeString(KEY_REVOKE_LISTENERS, null);
            if (revoke != null && !revoke.isEmpty()) {
                sRevokeListenerClasses = new ArrayList();
                for (String s : revoke.split("\\|")) {
                    if (!s.isEmpty()) {
                        sRevokeListenerClasses.add(s);
                    }
                }
            }
            String chatMore = kv.decodeString(KEY_CHAT_MORE_SELECT, null);
            if (chatMore != null && !chatMore.isEmpty()) {
                sChatMoreSelectClasses = new ArrayList();
                for (String s2 : chatMore.split("\\|")) {
                    if (!s2.isEmpty()) {
                        sChatMoreSelectClasses.add(s2);
                    }
                }
            }
            String lpImpls = kv.decodeString(KEY_CONV_LP_IMPLS, null);
            if (lpImpls != null && !lpImpls.isEmpty()) {
                sConvLongPressImpls = new ArrayList();
                for (String s3 : lpImpls.split("\\|")) {
                    if (!s3.isEmpty()) {
                        sConvLongPressImpls.add(s3);
                    }
                }
            }
            String menuImpls = kv.decodeString(KEY_MENU_G4_IMPLS, null);
            if (menuImpls != null && !menuImpls.isEmpty()) {
                sMenuG4Impls = new ArrayList();
                for (String s4 : menuImpls.split("\\|")) {
                    if (!s4.isEmpty()) {
                        sMenuG4Impls.add(s4);
                    }
                }
            }
            boolean hasV955Critical = (sLabelStorageProviderClass == null || sLabelStorageProviderMethod == null || sLabelStorageProviderMethod.isEmpty() || sServiceLocatorClass == null) ? false : true;
            boolean hasCoreResults = (sP06ClassName == null || sDbOpenerClass == null || sDbOpenMethodName == null || sImeiClassName == null || sImeiMethodName == null || sCsoLoaderClass == null || sJ1ServiceClass == null || sContactStorageClass == null || sChatOpenClass == null || sChatOpenMethod == null || sVoiceApiClass == null || sE9ClassName == null || sAvatarHelperClass == null) ? false : true;
            if (!hasCoreResults) {
                LogWriter.log(TAG, "loadResultsFromMMKV: incomplete cached results"
                        + (allowClear ? ", clearing cache" : ", partial results still usable"));
                if (allowClear) {
                    kv.clearAll();
                    kv.sync();
                    return false;
                }
                // 只读场景（cache-only）：字段已尽力填充，标记完成并执行 postScan 回调，
                // 缺失项由各自的运行时兜底（如 DatabaseProvider 捕获 DB 实例）补齐。
                synchronized (DexKitHelper.class) {
                    sScanComplete = true;
                }
                runPostScanCallbacks();
                return true;
            }
            LogWriter.log(TAG, "loadResultsFromMMKV: loaded cached results for version " + cachedVersion + " v955Critical=" + hasV955Critical);
            synchronized (DexKitHelper.class) {
                sScanComplete = true;
            }
            runPostScanCallbacks();
            // v3.0.136: 缓存完整即用，不再补扫 —— 补扫(rescanActionBarIfMissing/rescanMissingV955Keys)
            // 会每次启动重建 DexKit bridge 扫描，造成"每次都扫"、启动卡死。
            // 微信更新或首次安装时 startFullScan 已一次性写入全部目标（含 v955/actionBar/runtime）。
            LogWriter.log(TAG, "loadResultsFromMMKV: cache-only mode, v955Critical="
                    + hasV955Critical + ", skip rescan");
            return true;
        } catch (Throwable e) {
            LogWriter.log(TAG, "loadResultsFromMMKV error: " + e.getMessage());
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void saveResultsToMMKV() {
        try {
            MMKV kv = MMKV.mmkvWithID(MMKV_RESULTS_ID, 2);
            kv.clearAll();
            kv.encode(KEY_VERSION_CODE, sVersionCode);
            // v3.0.152: 所有写入 KEY_MODULE_VERSION 的位置统一用 CURRENT_MODULE_VERSION，
            // 避免 saveResultsToMMKV(sModuleVersion) 与 persistScanVersion(CURRENT_MODULE_VERSION)
            // 写入不一致，导致同一次扫描的缓存被判定 module mismatch 而反复清除、反复扫描。
            kv.encode(KEY_MODULE_VERSION, CURRENT_MODULE_VERSION);
            if (sP06ClassName != null) {
                kv.encode(KEY_P06_CLASS, sP06ClassName);
            }
            if (sDbOpenerClass != null) {
                kv.encode(KEY_DB_OPENER_CLASS, sDbOpenerClass);
            }
            if (sDbOpenMethodName != null) {
                kv.encode(KEY_DB_OPEN_METHOD, sDbOpenMethodName);
            }
            if (sDbOpenMethodParamTypes != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < sDbOpenMethodParamTypes.length; i++) {
                    if (i > 0) {
                        sb.append("|");
                    }
                    sb.append(sDbOpenMethodParamTypes[i]);
                }
                kv.encode(KEY_DB_OPEN_PARAMS, sb.toString());
            }
            if (sImeiClassName != null) {
                kv.encode(KEY_IMEI_CLASS, sImeiClassName);
            }
            if (sImeiMethodName != null) {
                kv.encode(KEY_IMEI_METHOD, sImeiMethodName);
            }
            if (sCsoLoaderClass != null) {
                kv.encode(KEY_CSO_LOADER, sCsoLoaderClass);
            }
            if (sCsoLoaderMethod != null) {
                kv.encode(KEY_CSO_LOADER_METHOD, sCsoLoaderMethod);
            }
            if (sJ1ServiceClass != null) {
                kv.encode(KEY_J1_SERVICE, sJ1ServiceClass);
            }
            if (sContactStorageClass != null) {
                kv.encode(KEY_CONTACT_STORAGE, sContactStorageClass);
            }
            if (sChatOpenClass != null) {
                kv.encode(KEY_CHAT_OPEN_CLASS, sChatOpenClass);
            }
            if (sChatOpenMethod != null) {
                kv.encode(KEY_CHAT_OPEN_METHOD, sChatOpenMethod);
            }
            if (sConvScrollClass != null) {
                kv.encode(KEY_CONV_SCROLL_CLASS, sConvScrollClass);
            }
            if (sConvScrollMethod != null) {
                kv.encode(KEY_CONV_SCROLL_METHOD, sConvScrollMethod);
            }
            if (sConvLongPressClass != null) {
                kv.encode(KEY_CONV_LONGPRESS_CLASS, sConvLongPressClass);
            }
            if (sConvLongPressMethod != null) {
                kv.encode(KEY_CONV_LONGPRESS_METHOD, sConvLongPressMethod);
            }
            if (sConvMenuClass != null) {
                kv.encode(KEY_CONV_MENU_CLASS, sConvMenuClass);
            }
            if (sConvMenuMethod != null) {
                kv.encode(KEY_CONV_MENU_METHOD, sConvMenuMethod);
            }
            if (sVoiceApiClass != null) {
                kv.encode(KEY_VOICE_API, sVoiceApiClass);
            }
            if (sE9ClassName != null) {
                kv.encode(KEY_E9_CLASS, sE9ClassName);
            }
            if (sA21ClassName != null) {
                kv.encode(KEY_A21_CLASS, sA21ClassName);
            }
            if (sA21MethodName != null) {
                kv.encode(KEY_A21_METHOD, sA21MethodName);
            }
            if (sAvatarHelperClass != null) {
                kv.encode(KEY_AVATAR_HELPER, sAvatarHelperClass);
            }
            if (sLabelStorageClass != null) {
                kv.encode(KEY_LABEL_STORAGE, sLabelStorageClass);
            }
            if (sConvListListAdapterClass != null) {
                kv.encode(KEY_CONV_LIST_ADAPTER, sConvListListAdapterClass);
            }
            if (sActionBarCustomAreaClass != null) {
                kv.encode(KEY_ACTION_BAR_CLASS, sActionBarCustomAreaClass);
            }
            if (!sConvLongPressImpls.isEmpty()) {
                StringBuilder sb2 = new StringBuilder();
                for (int i2 = 0; i2 < sConvLongPressImpls.size(); i2++) {
                    if (i2 > 0) {
                        sb2.append("|");
                    }
                    sb2.append(sConvLongPressImpls.get(i2));
                }
                kv.encode(KEY_CONV_LP_IMPLS, sb2.toString());
            }
            if (!sMenuG4Impls.isEmpty()) {
                StringBuilder sb3 = new StringBuilder();
                for (int i3 = 0; i3 < sMenuG4Impls.size(); i3++) {
                    if (i3 > 0) {
                        sb3.append("|");
                    }
                    sb3.append(sMenuG4Impls.get(i3));
                }
                kv.encode(KEY_MENU_G4_IMPLS, sb3.toString());
            }
            if (sLabelStorageProviderClass != null) {
                kv.encode(KEY_LABEL_PROVIDER_CLASS, sLabelStorageProviderClass);
            }
            if (sLabelStorageProviderMethod != null) {
                kv.encode(KEY_LABEL_PROVIDER_METHOD, sLabelStorageProviderMethod);
            }
            if (sPinyinUtilClass != null) {
                kv.encode(KEY_PINYIN_UTIL, sPinyinUtilClass);
            }
            if (sServiceLocatorClass != null) {
                kv.encode(KEY_SERVICE_LOCATOR, sServiceLocatorClass);
            }
            if (sMediaPathServiceClass != null) {
                kv.encode(KEY_MEDIA_PATH_SERVICE, sMediaPathServiceClass);
            }
            if (sMediaPathMethod != null) {
                kv.encode(KEY_MEDIA_PATH_METHOD, sMediaPathMethod);
            }
            if (sClipboardJsApiClass != null) {
                kv.encode(KEY_CLIPBOARD_JSAPI, sClipboardJsApiClass);
            }
            if (sX9Class != null) {
                kv.encode(KEY_X9_CLASS, sX9Class);
            }
            if (sNetSceneUpdateInfo != null) {
                kv.encode(KEY_NETSCENE_UPDATE_INFO, sNetSceneUpdateInfo);
            }
            if (sUpdaterManager != null) {
                kv.encode(KEY_UPDATER_MANAGER, sUpdaterManager);
            }
            if (sCtinkerInstaller != null) {
                kv.encode(KEY_CTINKER_INSTALLER, sCtinkerInstaller);
            }
            if (!sRevokeListenerClasses.isEmpty()) {
                kv.encode(KEY_REVOKE_LISTENERS, joinList(sRevokeListenerClasses));
            }
            if (!sChatMoreSelectClasses.isEmpty()) {
                kv.encode(KEY_CHAT_MORE_SELECT, joinList(sChatMoreSelectClasses));
            }
            kv.sync();
            LogWriter.log(TAG, "saveResultsToMMKV: done for version " + sVersionCode);
        } catch (Throwable e) {
            LogWriter.log(TAG, "saveResultsToMMKV error: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void persistScanVersion() {
        try {
            MMKV kv = MMKV.mmkvWithID(MMKV_RESULTS_ID, 2);
            kv.encode(KEY_VERSION_CODE, sVersionCode);
            kv.encode(KEY_MODULE_VERSION, CURRENT_MODULE_VERSION);
            kv.sync();
            LogWriter.log(TAG, "persistScanVersion: wx=" + sVersionCode + " module=" + CURRENT_MODULE_VERSION);
        } catch (Throwable e) {
            LogWriter.log(TAG, "persistScanVersion err: " + e.getMessage());
        }
    }

    private static String joinList(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append("|");
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static boolean hasCompleteLocalCache() {
        try {
            MMKV kv = MMKV.mmkvWithID(MMKV_RESULTS_ID, 2);
            if (kv.decodeInt(KEY_VERSION_CODE, 0) != sVersionCode || kv.decodeInt(KEY_MODULE_VERSION, 0) != CURRENT_MODULE_VERSION || kv.decodeString(KEY_P06_CLASS, null) == null || kv.decodeString(KEY_DB_OPENER_CLASS, null) == null || kv.decodeString(KEY_CONTACT_STORAGE, null) == null || kv.decodeString(KEY_J1_SERVICE, null) == null || kv.decodeString(KEY_LABEL_PROVIDER_CLASS, null) == null || kv.decodeString(KEY_LABEL_PROVIDER_METHOD, null) == null) {
                return false;
            }
            if (kv.decodeString(KEY_SERVICE_LOCATOR, null) == null) {
                return false;
            }
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    private static JSONObject readModuleBaselineJson() {
        InputStream in = null;
        try {
            String moduleApk = IconLoader.moduleApkPath();
            if (moduleApk != null && !moduleApk.isEmpty()) {
                AssetManager am = (AssetManager) AssetManager.class.getDeclaredConstructor(new Class[0]).newInstance(new Object[0]);
                Method addPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
                addPath.setAccessible(true);
                Object cookie = addPath.invoke(am, moduleApk);
                if ((cookie instanceof Integer) && ((Integer) cookie).intValue() == 0) {
                    LogWriter.log(TAG, "readModuleBaseline: addAssetPath failed: " + moduleApk);
                    return null;
                }
                try {
                    InputStream in2 = am.open("dexkit_baseline.json");
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    while (true) {
                        int n = in2.read(buf);
                        if (n == -1) {
                            break;
                        }
                        bos.write(buf, 0, n);
                    }
                    String json = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                    if (json.trim().isEmpty()) {
                        if (in2 != null) {
                            try {
                                in2.close();
                            } catch (Throwable th) {
                            }
                        }
                        return null;
                    }
                    JSONObject jSONObject = new JSONObject(json);
                    if (in2 != null) {
                        try {
                            in2.close();
                        } catch (Throwable th2) {
                        }
                    }
                    return jSONObject;
                } catch (Throwable th3) {
                    if (0 != 0) {
                        try {
                            in.close();
                        } catch (Throwable th4) {
                        }
                    }
                    return null;
                }
            }
            LogWriter.log(TAG, "readModuleBaseline: moduleApkPath null");
            if (0 != 0) {
                try {
                    in.close();
                } catch (Throwable th5) {
                }
            }
            return null;
        } catch (Throwable e) {
            try {
                LogWriter.log(TAG, "readModuleBaseline err: " + e.getMessage());
                if (0 != 0) {
                    try {
                        in.close();
                    } catch (Throwable th6) {
                    }
                }
                return null;
            } finally {
                if (0 != 0) {
                    try {
                        in.close();
                    } catch (Throwable th7) {
                    }
                }
            }
        }
    }

    private static void applyBaselineJsonToFields(JSONObject o) {
        if (o == null) {
            return;
        }
        sP06ClassName = o.optString(KEY_P06_CLASS, null);
        sDbOpenerClass = o.optString(KEY_DB_OPENER_CLASS, null);
        sDbOpenMethodName = o.optString(KEY_DB_OPEN_METHOD, null);
        String dbParams = o.optString(KEY_DB_OPEN_PARAMS, null);
        if (dbParams != null && !dbParams.isEmpty()) {
            sDbOpenMethodParamTypes = dbParams.split("\\|");
        }
        sImeiClassName = o.optString(KEY_IMEI_CLASS, null);
        sImeiMethodName = o.optString(KEY_IMEI_METHOD, null);
        sCsoLoaderClass = o.optString(KEY_CSO_LOADER, null);
        sCsoLoaderMethod = o.optString(KEY_CSO_LOADER_METHOD, null);
        sJ1ServiceClass = o.optString(KEY_J1_SERVICE, null);
        sContactStorageClass = o.optString(KEY_CONTACT_STORAGE, null);
        sChatOpenClass = o.optString(KEY_CHAT_OPEN_CLASS, null);
        sChatOpenMethod = o.optString(KEY_CHAT_OPEN_METHOD, null);
        sConvScrollClass = o.optString(KEY_CONV_SCROLL_CLASS, null);
        sConvScrollMethod = o.optString(KEY_CONV_SCROLL_METHOD, null);
        sConvLongPressClass = o.optString(KEY_CONV_LONGPRESS_CLASS, null);
        sConvLongPressMethod = o.optString(KEY_CONV_LONGPRESS_METHOD, null);
        sConvMenuClass = o.optString(KEY_CONV_MENU_CLASS, null);
        sConvMenuMethod = o.optString(KEY_CONV_MENU_METHOD, null);
        sVoiceApiClass = o.optString(KEY_VOICE_API, null);
        sE9ClassName = o.optString(KEY_E9_CLASS, null);
        sA21ClassName = o.optString(KEY_A21_CLASS, null);
        sA21MethodName = o.optString(KEY_A21_METHOD, null);
        sAvatarHelperClass = o.optString(KEY_AVATAR_HELPER, null);
        sLabelStorageClass = o.optString(KEY_LABEL_STORAGE, null);
        sConvListListAdapterClass = o.optString(KEY_CONV_LIST_ADAPTER, null);
        sActionBarCustomAreaClass = o.optString(KEY_ACTION_BAR_CLASS, null);
        sLabelStorageProviderClass = o.optString(KEY_LABEL_PROVIDER_CLASS, null);
        String lpMethod = o.optString(KEY_LABEL_PROVIDER_METHOD, null);
        if (lpMethod != null && !lpMethod.isEmpty()) {
            sLabelStorageProviderMethod = lpMethod;
        }
        sPinyinUtilClass = o.optString(KEY_PINYIN_UTIL, null);
        sServiceLocatorClass = o.optString(KEY_SERVICE_LOCATOR, null);
        sMediaPathServiceClass = o.optString(KEY_MEDIA_PATH_SERVICE, null);
        String mpMethod = o.optString(KEY_MEDIA_PATH_METHOD, null);
        if (mpMethod != null && !mpMethod.isEmpty()) {
            sMediaPathMethod = mpMethod;
        }
        sClipboardJsApiClass = o.optString(KEY_CLIPBOARD_JSAPI, null);
        sX9Class = o.optString(KEY_X9_CLASS, null);
        sNetSceneUpdateInfo = o.optString(KEY_NETSCENE_UPDATE_INFO, null);
        sUpdaterManager = o.optString(KEY_UPDATER_MANAGER, null);
        sCtinkerInstaller = o.optString(KEY_CTINKER_INSTALLER, null);
        String lpImpls = o.optString(KEY_CONV_LP_IMPLS, null);
        if (lpImpls != null && !lpImpls.isEmpty()) {
            sConvLongPressImpls = new ArrayList();
            for (String s : lpImpls.split("\\|")) {
                if (!s.isEmpty()) {
                    sConvLongPressImpls.add(s);
                }
            }
        }
        String menuImpls = o.optString(KEY_MENU_G4_IMPLS, null);
        if (menuImpls != null && !menuImpls.isEmpty()) {
            sMenuG4Impls = new ArrayList();
            for (String s2 : menuImpls.split("\\|")) {
                if (!s2.isEmpty()) {
                    sMenuG4Impls.add(s2);
                }
            }
        }
        String revoke = o.optString(KEY_REVOKE_LISTENERS, null);
        if (revoke != null && !revoke.isEmpty()) {
            sRevokeListenerClasses = new ArrayList();
            for (String s3 : revoke.split("\\|")) {
                if (!s3.isEmpty()) {
                    sRevokeListenerClasses.add(s3);
                }
            }
        }
        String chatMore = o.optString(KEY_CHAT_MORE_SELECT, null);
        if (chatMore != null && !chatMore.isEmpty()) {
            sChatMoreSelectClasses = new ArrayList();
            for (String s4 : chatMore.split("\\|")) {
                if (!s4.isEmpty()) {
                    sChatMoreSelectClasses.add(s4);
                }
            }
        }
    }

    public static boolean tryLoadBaselineFromModule(Application app) {
        try {
            JSONObject o = readModuleBaselineJson();
            if (o == null) {
                return false;
            }
            int ver = o.optInt(KEY_VERSION_CODE, 0);
            if (ver != sVersionCode) {
                LogWriter.log(TAG, "baseline: version mismatch (module=" + ver + " current=" + sVersionCode + "), skip");
                return false;
            }
            if (o.has(KEY_P06_CLASS) && o.has(KEY_DB_OPENER_CLASS) && o.has(KEY_CONTACT_STORAGE) && o.has(KEY_J1_SERVICE)) {
                if (o.has(KEY_LABEL_PROVIDER_CLASS) && o.has(KEY_LABEL_PROVIDER_METHOD) && o.has(KEY_SERVICE_LOCATOR)) {
                    applyBaselineJsonToFields(o);
                    LogWriter.log(TAG, "baseline: applied from module APK (version " + ver + ", keys=" + o.length() + ")");
                    DexKitCacheBridge.RecyclableBridge bridge = null;
                    if (app != null) {
                        try {
                            loadDexKitLibrary(app);
                            initDexKitCache(app);
                            bridge = createBridge(app.getClassLoader());
                        } catch (Throwable e) {
                            LogWriter.log(TAG, "baseline: bridge init err: " + e.getMessage());
                        }
                    }
                    markScanCompleteAndDrain();
                    // v3.0.136: baseline 命中后不再补扫 actionBar（避免每次启动扫描）
                    if (bridge != null) {
                        try {
                            bridge.close();
                            return true;
                        } catch (Throwable th) {
                            return true;
                        }
                    }
                    return true;
                }
                LogWriter.log(TAG, "baseline: missing v955 critical keys, skip");
                return false;
            }
            LogWriter.log(TAG, "baseline: incomplete, skip");
            return false;
        } catch (Throwable e2) {
            LogWriter.log(TAG, "tryLoadBaselineFromModule err: " + e2.getMessage());
            return false;
        }
    }

    public static void exportBaselineToFile(Application app, boolean force) {
        String v;
        if (app == null) {
            return;
        }
        try {
            MMKV kv = MMKV.mmkvWithID(MMKV_RESULTS_ID, 2);
            int ver = kv.decodeInt(KEY_VERSION_CODE, 0);
            if (ver != sVersionCode) {
                return;
            }
            String str = null;
            String p06 = kv.decodeString(KEY_P06_CLASS, null);
            String contact = kv.decodeString(KEY_CONTACT_STORAGE, null);
            String labelProvider = kv.decodeString(KEY_LABEL_PROVIDER_CLASS, null);
            String serviceLocator = kv.decodeString(KEY_SERVICE_LOCATOR, null);
            if (p06 != null && contact != null && labelProvider != null && serviceLocator != null) {
                File out = new File(PathUtil.getLeshaoRootDir(app), "dexkit_baseline.json");
                if (out.exists() && !force) {
                    return;
                }
                JSONObject o = new JSONObject();
                o.put(KEY_VERSION_CODE, kv.decodeInt(KEY_VERSION_CODE, 0));
                o.put(KEY_MODULE_VERSION, kv.decodeInt(KEY_MODULE_VERSION, 0));
                String[] allKeys = kv.allKeys();
                int length = allKeys.length;
                int i = 0;
                while (i < length) {
                    String k = allKeys[i];
                    if (!KEY_VERSION_CODE.equals(k) && !KEY_MODULE_VERSION.equals(k) && (v = kv.decodeString(k, str)) != null && !v.isEmpty()) {
                        o.put(k, v);
                    }
                    i++;
                    str = null;
                }
                FileOutputStream fos = new FileOutputStream(out, false);
                try {
                    fos.write(o.toString().getBytes(StandardCharsets.UTF_8));
                    try {
                        fos.close();
                    } catch (Throwable th) {
                    }
                    LogWriter.log(TAG, "exportBaseline: " + out.getAbsolutePath() + " keys=" + o.length());
                    return;
                } finally {
                }
            }
            LogWriter.log(TAG, "exportBaseline: cache incomplete (missing v955 critical), skip");
        } catch (Throwable e) {
            LogWriter.log(TAG, "exportBaseline err: " + e.getMessage());
        }
    }

    private static void rescanActionBarIfMissing(final Application app) {
        if (sActionBarCustomAreaClass != null) return;
        if (!sActionBarRescanAttempted.compareAndSet(false, true)) return;
        if (app == null) return;
        sExecutor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (sScanLock) {
                    DexKitCacheBridge.RecyclableBridge bridge = null;
                    try {
                        loadDexKitLibrary(app);
                        if (!sLibraryLoaded.get()) return;
                        initDexKitCache(app);
                        synchronized (sBridgeLock) {
                            bridge = createBridge(app.getClassLoader());
                            if (bridge == null) return;
                            bridge.withBridge(new DexKitCacheBridge.RecyclableBridge.BridgeFunction() {
                                @Override
                                public void apply(DexKitBridge b) {
                                    findActionBarCustomArea(b);
                                }
                            });
                        }
                        if (sActionBarCustomAreaClass != null) {
                            saveResultsToMMKV();
                            LogWriter.log(TAG, "rescanActionBar: saved " + sActionBarCustomAreaClass);
                        } else {
                            LogWriter.log(TAG, "rescanActionBar: still not found");
                        }
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "rescanActionBar err: " + e.getMessage());
                    } finally {
                        if (bridge != null) {
                            synchronized (sBridgeLock) {
                                try { bridge.close(); } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            }
        });
    }


    /* JADX INFO: Access modifiers changed from: private */
    private static void rescanRuntimeHookTargetsIfMissing(final Application app) {
        if (sX9Class != null && sNetSceneUpdateInfo != null
                && sUpdaterManager != null && sCtinkerInstaller != null) return;
        if (app == null) return;
        if (!sRuntimeRescanAttempted.compareAndSet(false, true)) return;
        sExecutor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (sScanLock) {
                    DexKitCacheBridge.RecyclableBridge bridge = null;
                    try {
                        loadDexKitLibrary(app);
                        if (!sLibraryLoaded.get()) return;
                        initDexKitCache(app);
                        synchronized (sBridgeLock) {
                            bridge = createBridge(app.getClassLoader());
                            if (bridge == null) return;
                            final DexKitCacheBridge.RecyclableBridge fb = bridge;
                            fb.withBridge(new DexKitCacheBridge.RecyclableBridge.BridgeFunction() {
                                @Override
                                public void apply(DexKitBridge b) {
                                    findRuntimeHookTargets(b);
                                }
                            });
                        }
                        saveResultsToMMKV();
                        LogWriter.log(TAG, "rescanRuntimeHookTargets: x9=" + sX9Class
                                + " netScene=" + sNetSceneUpdateInfo
                                + " updater=" + sUpdaterManager
                                + " ctinker=" + sCtinkerInstaller);
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "rescanRuntimeHookTargets err: " + e.getMessage());
                    } finally {
                        if (bridge != null) {
                            synchronized (sBridgeLock) {
                                try { bridge.close(); } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            }
        });
    }



    private static void rescanMissingV955Keys(final Application app) {
        if (sLabelStorageProviderClass != null && sServiceLocatorClass != null) return;
        if (!sCloneRescanAttempted.compareAndSet(false, true)) {
            markScanCompleteAndDrain();
            return;
        }
        if (app == null) {
            markScanCompleteAndDrain();
            return;
        }
        sExecutor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (sScanLock) {
                    DexKitCacheBridge.RecyclableBridge bridge = null;
                    try {
                        loadDexKitLibrary(app);
                        if (!sLibraryLoaded.get()) { markScanCompleteAndDrain(); return; }
                        initDexKitCache(app);
                        // 与 find* 搜索互斥: dexkit native 跨实例并发不安全(见 sBridgeLock 注释)
                        synchronized (sBridgeLock) {
                            bridge = createBridge(app.getClassLoader());
                            if (bridge == null) return;
                            bridge.withBridge(new DexKitCacheBridge.RecyclableBridge.BridgeFunction() {
                                @Override
                                public void apply(DexKitBridge b) {
                                    findV955Targets(b);
                                }
                            });
                        }
                        if (sLabelStorageProviderClass != null) {
                            saveResultsToMMKV();
                            LogWriter.log(TAG, "rescanV955: saved labelProvider=" + sLabelStorageProviderClass
                                    + " serviceLocator=" + sServiceLocatorClass);
                        } else {
                            LogWriter.log(TAG, "rescanV955: label provider still not found");
                        }
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "rescanV955 err: " + e.getMessage());
                    } finally {
                        if (bridge != null) {
                            synchronized (sBridgeLock) {
                                try { bridge.close(); } catch (Throwable ignored) {}
                            }
                        }
                        // 补扫失败也必须放行回调, 避免永久排队
                        markScanCompleteAndDrain();
                    }
                }
            }
        });
    }



    /* JADX INFO: Access modifiers changed from: private */
    private static void scanWechatTargets(final DexKitCacheBridge.RecyclableBridge cacheBridge) {
        LogWriter.log(TAG, "scanWechatTargets: using DexKitCacheBridge instance");

        final String[] scanSteps = {
            "J1 服务定位器", "P06 核心类", "数据库接口", "设备标识 (IMEI)", "CsoLoader",
            "通讯录存储", "语音 API", "e9/a21 类", "头像服务", "标签存储",
            "会话列表适配器", "聊天窗口入口",
            "长按事件", "列表滚动", "菜单注入", "菜单实现类"
        };
        final int totalSteps = scanSteps.length;

        // 问题3/16: 扫描全程持锁, 与补扫/缓存写入串行
        synchronized (sScanLock) {
        // 与各 hook 线程的 find* 搜索互斥: dexkit native 跨实例并发不安全(见 sBridgeLock 注释)
        synchronized (sBridgeLock) {
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
                findActionBarCustomArea(b);
                reportProgress(98, "扫描: 标题栏 helper", "定位 ActionBarCustomArea(三横菜单标题栏注入)");
                findRuntimeHookTargets(b);
                reportProgress(99, "扫描: 运行期定位类", "x9 分发/更新阻断类名持久化");
             }
        });

        synchronized (DexKitHelper.class) { sScanComplete = true; }
        // v3.0.169: 扫描过早(真实 CL 未就绪)时核心目标会整批为 null。
        // 此时只保留内存结果、不覆盖旧缓存、不弹报错，下次启动补扫。
        sLastScanHealthy = isCoreScanHealthy();
        LogWriter.log(TAG, "scan complete(healthy=" + sLastScanHealthy + "): p06=" + sP06ClassName
            + " j1=" + sJ1ServiceClass
            + " dbOpener=" + sDbOpenerClass + "." + sDbOpenMethodName
            + " imei=" + sImeiClassName + "." + sImeiMethodName
            + " cso=" + sCsoLoaderClass
            + " contactStorage=" + sContactStorageClass
            + " voiceApi=" + sVoiceApiClass
            + " e9=" + sE9ClassName
            + " a21=" + (sA21ClassName == null ? "disabled" : sA21ClassName)
            + " avatar=" + sAvatarHelperClass
            + " label=" + sLabelStorageClass
            + " convAdapter=" + sConvListListAdapterClass
            + " chatOpen=" + sChatOpenClass + "." + sChatOpenMethod
            + " convScroll=" + sConvScrollClass + "." + sConvScrollMethod
            + " convLongPress=" + sConvLongPressClass + "." + sConvLongPressMethod
            + " convMenu=" + sConvMenuClass + "." + sConvMenuMethod);

        if (sLastScanHealthy) {
            saveResultsToMMKV();

            // v1101: 扫描成功且缓存完整时, 自动导出大厅基线文件(已存在则跳过), 供回填 assets 打包
            try {
                android.content.Context ctx = sAppContextForCache;
                if (ctx instanceof Application) {
                    exportBaselineToFile((Application) ctx, false);
                }
            } catch (Throwable ignored) {}

            sMissingSummary = buildMissingSummary();
            if (sMissingSummary != null) {
                LogWriter.log(TAG, "SCAN MISSING: " + sMissingSummary.replace('\n', ' '));
            }
            reportProgress(100, "扫描完成", sMissingSummary == null ? "所有功能已就绪" : "部分功能未适配");
        } else {
            // v3.0.169: 不健康扫描不落盘、不弹"未适配"误报(整批假 null 不是真实缺失),
            // 内存结果仍可临时使用; 缓存保持旧值, 版本号不更新, 下次启动触发补扫。
            sMissingSummary = null;
            LogWriter.log(TAG, "scanWechatTargets: UNHEALTHY scan (core targets null, likely premature), "
                + "keep old cache, suppress popup, will rescan next launch");
            reportProgress(100, "扫描完成", "核心目标未完全定位，稍后自动补扫");
        }
        reportComplete();
        } // synchronized(sBridgeLock)
        } // synchronized(sScanLock)
        markScanCompleteAndDrain();
    }



    /* JADX INFO: Access modifiers changed from: private */
    public static void findP06Class(DexKitBridge bridge) {
        try {
            // v3.0.171（核心数据审计）：3180 反编译证实 "Kernel not initialized by MMApplication!"
            // 全 dex 只在 gp0.j1.j() 中；gp0.j1.b() 转调 j() 并断言 "mCoreAccount not initialized!"。
            // kw5.y0 无 b 方法；sw5.y0 的 b() 是媒体列表装配，均无内核串。P06 即 gp0.j1（内核/账号单例）。
            String kernelStr = "Kernel not initialized";
            MethodMatcher mMatcher = MethodMatcher.create().usingStrings(kernelStr);
            List<MethodData> methods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(mMatcher));
            LogWriter.log(TAG, "findP06Class: " + methods.size() + " methods use '" + kernelStr + "'");
            for (MethodData m : methods) {
                LogWriter.log(TAG, "  candidate: " + m.getClassName() + "." + m.getName() + " params=" + m.getParamTypeNames());
            }
            // 首选：内核守卫类 gp0.j1（其 j() 抛 "Kernel not initialized"，b() 转调 j()）
            for (MethodData m : methods) {
                String clsName = m.getClassName();
                if ("gp0.j1".equals(clsName)) {
                    sP06ClassName = clsName;
                    LogWriter.log(TAG, "findP06Class (kernel singleton): " + clsName + "." + m.getName()
                            + " -> gp0.j1.b() 转调 j()");
                    return;
                }
            }
            // 兜底：保留原 b 方法优先 / 首个非 hm0.j1 候选
            for (MethodData m2 : methods) {
                String clsName = m2.getClassName();
                if (!"hm0.j1".equals(clsName) && "b".equals(m2.getName())) {
                    sP06ClassName = clsName;
                    LogWriter.log(TAG, "findP06Class (b+String): " + clsName + "." + m2.getName());
                    return;
                }
            }
            for (MethodData m3 : methods) {
                String clsName2 = m3.getClassName();
                if (!"hm0.j1".equals(clsName2)) {
                    sP06ClassName = clsName2;
                    LogWriter.log(TAG, "findP06Class (first non-hm0.j1): " + clsName2 + "." + m3.getName());
                    return;
                }
            }
            LogWriter.log(TAG, "findP06Class: NOT found (string-based)");
        } catch (Throwable e) {
            LogWriter.log(TAG, "findP06Class error: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void findDbOpenerMethods(DexKitBridge bridge) {
        MethodMatcher mMatcher3;
        boolean z;
        try {
            MethodMatcher mMatcher = MethodMatcher.create().usingStrings("EnMicroMsg").modifiers(8).paramCount(4).paramTypes("java.lang.String", "java.lang.String", "int", "boolean");
            List<MethodData> methods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(mMatcher));
            LogWriter.log(TAG, "findDbOpener: " + methods.size() + " static methods match 'EnMicroMsg' + 4 params (String,String,int,boolean)");
            if (!methods.isEmpty()) {
                MethodData m = methods.get(0);
                sDbOpenerClass = m.getClassName();
                sDbOpenMethodName = m.getName();
                sDbOpenMethodParamTypes = (String[]) m.getParamTypeNames().toArray(new String[0]);
                LogWriter.log(TAG, "findDbOpener: " + sDbOpenerClass + "." + sDbOpenMethodName + " params=" + sDbOpenMethodParamTypes.length);
                return;
            }
            MethodMatcher mMatcher32 = MethodMatcher.create().usingStrings("MicroMsg").modifiers(8).paramCount(4).paramTypes("java.lang.String", "java.lang.String", "int", "boolean");
            List<MethodData> methods3 = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(mMatcher32));
            LogWriter.log(TAG, "findDbOpener: " + methods3.size() + " static methods match 'MicroMsg' + 4 params");
            if (!methods3.isEmpty()) {
                MethodData m2 = methods3.get(0);
                sDbOpenerClass = m2.getClassName();
                sDbOpenMethodName = m2.getName();
                sDbOpenMethodParamTypes = (String[]) m2.getParamTypeNames().toArray(new String[0]);
                LogWriter.log(TAG, "findDbOpener: " + sDbOpenerClass + "." + sDbOpenMethodName);
                return;
            }
            MethodMatcher mMatcher2 = MethodMatcher.create().usingStrings("EnMicroMsg").modifiers(8).paramCount(2);
            List<MethodData> methods2 = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(mMatcher2));
            LogWriter.log(TAG, "findDbOpener: " + methods2.size() + " static methods match 'EnMicroMsg' + 2 params");
            for (MethodData m3 : methods2) {
                List<String> pts = m3.getParamTypeNames();
                if (pts.size() >= 2) {
                    mMatcher3 = mMatcher32;
                    if ("java.lang.String".equals(pts.get(0))) {
                        z = true;
                        if ("java.lang.String".equals(pts.get(1))) {
                            sDbOpenerClass = m3.getClassName();
                            sDbOpenMethodName = m3.getName();
                            sDbOpenMethodParamTypes = (String[]) pts.toArray(new String[0]);
                            LogWriter.log(TAG, "findDbOpener: " + sDbOpenerClass + "." + sDbOpenMethodName + " params=" + pts.size());
                            return;
                        }
                    } else {
                        z = true;
                    }
                } else {
                    mMatcher3 = mMatcher32;
                    z = true;
                }
                mMatcher32 = mMatcher3;
            }
            LogWriter.log(TAG, "findDbOpener: NOT found");
        } catch (Throwable e) {
            LogWriter.log(TAG, "findDbOpener error: " + e.getMessage());
        }
    }

    private static boolean isExcludedCandidate(String clsName) {
        return clsName == null || clsName.isEmpty() || clsName.startsWith("android.") || clsName.startsWith("androidx.") || clsName.startsWith("java.") || clsName.startsWith("javax.") || clsName.startsWith("kotlin.") || clsName.startsWith("kotlinx.") || clsName.startsWith("org.") || clsName.startsWith("com.android.") || clsName.startsWith("dalvik.") || clsName.startsWith("libcore.") || clsName.startsWith("com.google.") || clsName.startsWith("com.leshao.");
    }

    private static int pickCandidateIndex(List<MethodData> methods) {
        if (methods == null || methods.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < methods.size(); i++) {
            MethodData m = methods.get(i);
            if (m != null && !isExcludedCandidate(m.getClassName())) {
                return i;
            }
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void findCsoLoader(DexKitBridge bridge) {
        try {
            MethodMatcher mMatcher = MethodMatcher.create().usingStrings("CsoLoader").modifiers(8).paramCount(0);
            List<MethodData> methods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(mMatcher));
            LogWriter.log(TAG, "findCsoLoader: " + methods.size() + " static 0-param methods use 'CsoLoader'");
            for (MethodData m : methods) {
                LogWriter.log(TAG, "  candidate: " + m.getClassName() + "." + m.getName() + " params=" + m.getParamTypeNames());
            }
            int idx = pickCandidateIndex(methods);
            if (idx >= 0) {
                MethodData m2 = methods.get(idx);
                sCsoLoaderClass = m2.getClassName();
                sCsoLoaderMethod = m2.getName();
                LogWriter.log(TAG, "findCsoLoader: " + sCsoLoaderClass + "." + m2.getName());
                return;
            }
            MethodMatcher mMatcher2 = MethodMatcher.create().usingStrings("CsoLoader").paramCount(0);
            List<MethodData> methods2 = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(mMatcher2));
            LogWriter.log(TAG, "findCsoLoader: " + methods2.size() + " 0-param methods use 'CsoLoader'");
            for (MethodData m3 : methods2) {
                String clsName = m3.getClassName();
                if (clsName != null && clsName.contains("CsoLoader") && !isExcludedCandidate(clsName)) {
                    sCsoLoaderClass = clsName;
                    sCsoLoaderMethod = m3.getName();
                    LogWriter.log(TAG, "findCsoLoader (name match): " + clsName + "." + m3.getName());
                    return;
                }
            }
            int idx2 = pickCandidateIndex(methods2);
            if (idx2 >= 0) {
                MethodData m4 = methods2.get(idx2);
                sCsoLoaderClass = m4.getClassName();
                sCsoLoaderMethod = m4.getName();
                LogWriter.log(TAG, "findCsoLoader (first): " + sCsoLoaderClass + "." + m4.getName());
                return;
            }
            LogWriter.log(TAG, "findCsoLoader: NOT found");
        } catch (Throwable e) {
            LogWriter.log(TAG, "findCsoLoader error: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void findImeiClass(DexKitBridge bridge) {
        try {
            MethodMatcher mMatcher = MethodMatcher.create().usingStrings("android.permission.READ_PHONE_STATE").returnType("java.lang.String");
            List<MethodData> methods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(mMatcher));
            LogWriter.log(TAG, "findImeiClass: " + methods.size() + " methods use 'READ_PHONE_STATE' return String");
            int idx = pickCandidateIndex(methods);
            if (idx >= 0) {
                MethodData m = methods.get(idx);
                sImeiClassName = m.getClassName();
                sImeiMethodName = m.getName();
                LogWriter.log(TAG, "findImeiClass: " + sImeiClassName + "." + sImeiMethodName);
                return;
            }
            MethodMatcher mMatcher2 = MethodMatcher.create().usingStrings("getDeviceId").returnType("java.lang.String");
            List<MethodData> methods2 = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(mMatcher2));
            LogWriter.log(TAG, "findImeiClass: " + methods2.size() + " methods use 'getDeviceId' return String");
            int idx2 = pickCandidateIndex(methods2);
            if (idx2 >= 0) {
                MethodData m2 = methods2.get(idx2);
                sImeiClassName = m2.getClassName();
                sImeiMethodName = m2.getName();
                LogWriter.log(TAG, "findImeiClass (getDeviceId): " + sImeiClassName + "." + sImeiMethodName);
                return;
            }
            MethodMatcher mMatcher3 = MethodMatcher.create().paramTypes("boolean").returnType("java.lang.String");
            List<MethodData> methods3 = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").searchPackages("wo", "wn", "wp", "vo", "vn", "vp", "xo", "xn", "xp").matcher(mMatcher3));
            for (MethodData m3 : methods3) {
                String clsName = m3.getClassName();
                if (clsName != null && clsName.endsWith("w0")) {
                    sImeiClassName = clsName;
                    sImeiMethodName = m3.getName();
                    LogWriter.log(TAG, "findImeiClass (package): " + clsName + "." + m3.getName());
                    return;
                }
            }
            LogWriter.log(TAG, "findImeiClass: NOT found");
        } catch (Throwable e) {
            LogWriter.log(TAG, "findImeiClass error: " + e.getMessage());
        }
    }

    public static String getJ1ServiceClass() {
        return sJ1ServiceClass;
    }

    public static String getVoiceApiClass() {
        return sVoiceApiClass;
    }

    public static String getE9ClassName() {
        return sE9ClassName;
    }

    public static String getA21ClassName() {
        return sA21ClassName;
    }

    public static String getA21MethodName() {
        return sA21MethodName;
    }

    public static String getAvatarHelperClass() {
        return sAvatarHelperClass;
    }

    public static String getLabelStorageClass() {
        return sLabelStorageClass;
    }

    public static String getConvListListAdapterClass() {
        return sConvListListAdapterClass;
    }

    public static void setVersionCode(int versionCode) {
        sVersionCode = versionCode;
    }

    public static void setModuleVersion(int moduleVersion) {
        sModuleVersion = moduleVersion;
    }

    public static String getP06ClassName() {
        return sP06ClassName;
    }

    public static String getDbOpenerClass() {
        return sDbOpenerClass;
    }

    public static String getDbOpenMethodName() {
        return sDbOpenMethodName;
    }

    public static String[] getDbOpenMethodParamTypes() {
        return sDbOpenMethodParamTypes;
    }

    public static String getImeiClassName() {
        return sImeiClassName;
    }

    public static String getImeiMethodName() {
        return sImeiMethodName;
    }

    public static String getCsoLoaderClass() {
        return sCsoLoaderClass;
    }

    public static String getCsoLoaderMethod() {
        return sCsoLoaderMethod;
    }

    public static String getContactStorageClass() {
        return sContactStorageClass;
    }

    public static String getChatOpenClass() {
        return sChatOpenClass;
    }

    public static String getChatOpenMethod() {
        return sChatOpenMethod;
    }

    public static String getConvScrollClass() {
        return sConvScrollClass;
    }

    public static String getConvScrollMethod() {
        return sConvScrollMethod;
    }

    public static String getConvLongPressClass() {
        return sConvLongPressClass;
    }

    public static String getConvLongPressMethod() {
        return sConvLongPressMethod;
    }

    public static String getConvMenuClass() {
        return sConvMenuClass;
    }

    public static String getConvMenuMethod() {
        return sConvMenuMethod;
    }

    public static String ngetX9Class() {
        return sX9Class;
    }

    public static String ngetNetSceneUpdateInfo() {
        return sNetSceneUpdateInfo;
    }

    public static String ngetUpdaterManager() {
        return sUpdaterManager;
    }

    public static String ngetCtinkerInstaller() {
        return sCtinkerInstaller;
    }

    public static List<String> getConvLongPressImpls() {
        List<String> copy = new ArrayList<>();
        for (String s : sConvLongPressImpls) {
            copy.add(s);
        }
        return copy;
    }

    public static List<String> getMenuG4Impls() {
        List<String> copy = new ArrayList<>();
        for (String s : sMenuG4Impls) {
            copy.add(s);
        }
        return copy;
    }

    public static String getActionBarCustomAreaClass() {
        return sActionBarCustomAreaClass;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:119:0x02f5  */
    /* JADX WARN: Removed duplicated region for block: B:127:0x02ff  */
    /* JADX WARN: Removed duplicated region for block: B:158:0x024f  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private static void findActionBarCustomArea(DexKitBridge bridge) {
        try {
            final java.util.LinkedHashSet<String> cands = new java.util.LinkedHashSet<>();
            // A) 类级字符串锚点(文档首选)
            try {
                for (ClassData c : bridge.findClass(FindClass.create().excludePackages("com.leshao").matcher(
                        ClassMatcher.create().usingStrings("MicroMsg.ActionBarCustomArea")))) {
                    if (c.getName() != null) cands.add(c.getName());
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "findActionBarCustomArea [A] err: " + t.getMessage());
            }
            // A2) 方法级字符串锚点(更宽)
            try {
                for (MethodData m : bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(
                        MethodMatcher.create().usingStrings("MicroMsg.ActionBarCustomArea")))) {
                    if (m.getClassName() != null) cands.add(m.getClassName());
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "findActionBarCustomArea [A2] err: " + t.getMessage());
            }
            // B) 结构锚点: com.tencent.mm.ui 下所有含 <init>(View) 的类(标题栏 helper 在此包, 限制范围避免遍历过多类)
            try {
                for (MethodData m : bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(
                        MethodMatcher.create().name("<init>").paramCount(1)
                                .paramTypes("android.view.View")))) {
                    String cn = m.getClassName();
                    if (cn != null && cn.startsWith("com.tencent.mm.ui")) cands.add(cn);
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "findActionBarCustomArea [B] err: " + t.getMessage());
            }
            LogWriter.log(TAG, "findActionBarCustomArea: " + cands.size() + " candidates (A/A2/B)");

            String primary = null; int primaryScore = -1;
            String fallback = null; int fallbackScore = -1;
            for (String cn : cands) {
                ClassData cd = null;
                try { cd = bridge.getClassData(cn); } catch (Throwable ignored) {}
                if (cd == null) continue;

                boolean hasViewCtor = false, hasG = false, hasC = false;
                int viewFields = 0; boolean hasWe = false;
                try {
                    for (MethodData m : cd.getMethods()) {
                        if (("<init>".equals(m.getName()) || m.isConstructor())
                                && m.getParamCount() == 1) {
                            java.util.List<String> pt = m.getParamTypeNames();
                            if (pt != null && pt.size() == 1
                                    && "android.view.View".equals(pt.get(0))) hasViewCtor = true;
                        }
                        java.util.List<String> pt = m.getParamTypeNames();
                        if (pt != null && pt.size() == 1) {
                            if ("g".equals(m.getName())
                                    && "java.lang.CharSequence".equals(pt.get(0))) hasG = true;
                            if ("c".equals(m.getName())
                                    && "android.view.View$OnClickListener".equals(pt.get(0))) hasC = true;
                        }
                    }
                    for (FieldData f : cd.getFields()) {
                        String tn = f.getTypeName();
                        if (tn == null) continue;
                        if ("android.view.View".equals(tn) || "android.view.ViewGroup".equals(tn)
                                || "android.widget.TextView".equals(tn)
                                || "android.widget.ImageView".equals(tn)
                                || tn.endsWith("WeImageView")) viewFields++;
                        if (tn.endsWith("WeImageView")) hasWe = true;
                    }
                } catch (Throwable ignored) {}

                boolean semanticOk = hasViewCtor && hasG && hasC;
                boolean structureOk = hasViewCtor && hasWe && viewFields >= 10
                        && cn != null && cn.startsWith("com.tencent.mm.ui")
                        && !cn.startsWith("com.tencent.mm.plugin");
                LogWriter.log(TAG, "  cand " + cn + " viewCtor=" + hasViewCtor + " g=" + hasG
                        + " c=" + hasC + " we=" + hasWe + " viewFields=" + viewFields
                        + " semantic=" + semanticOk + " structure=" + structureOk);

                if (semanticOk) {
                    int score = viewFields + (hasWe ? 5 : 0);
                    if (score > primaryScore) { primaryScore = score; primary = cn; }
                } else if (structureOk) {
                    int score = viewFields + (hasWe ? 5 : 0);
                    if (score > fallbackScore) { fallbackScore = score; fallback = cn; }
                }
            }

            if (primary != null) {
                sActionBarCustomAreaClass = primary;
                LogWriter.log(TAG, "findActionBarCustomArea: PRIMARY " + primary
                        + " (score=" + primaryScore + ")");
            } else if (fallback != null) {
                sActionBarCustomAreaClass = fallback;
                LogWriter.log(TAG, "findActionBarCustomArea: FALLBACK " + fallback
                        + " (score=" + fallbackScore + ")");
            } else {
                LogWriter.log(TAG, "findActionBarCustomArea: NOT found");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "findActionBarCustomArea err: " + t.getMessage());
        }
    }



    /* JADX INFO: Access modifiers changed from: private */
    public static void findVoiceApi(DexKitBridge bridge) {
        // v3.0.171（核心数据审计）：3180 反编译证实字符串 "voicemsg" 不在 e9 中，
        // 真正持有者是 VoiceContent 相关类：dl.f0 / ks1.h / sp3.i / t44.x5 / v61.f1 / x95.a / x95.b。
        // 因此用 "voicemsg" 定位语音 API 类比 amr_/voice2 更精确，优先使用。
        List<MethodData> methods = new ArrayList<>();
        try {
            String[] keywords = {"voicemsg", "voice2", "amr_"};
            for (String kw : keywords) {
                try {
                    MethodMatcher mMatcher = MethodMatcher.create().usingStrings(kw).paramCount(2).paramTypes("java.lang.String", "java.lang.String").returnType("java.lang.String");
                    methods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(mMatcher));
                } catch (Throwable th) {
                }
                if (methods.isEmpty()) {
                    continue;
                } else {
                    sVoiceApiClass = methods.get(0).getClassName();
                    LogWriter.log(TAG, "findVoiceApi (" + kw + "): " + sVoiceApiClass);
                    return;
                }
            }
            MethodMatcher m2 = MethodMatcher.create().usingStrings("voice2");
            List<MethodData> m2s = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(m2));
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

    /* JADX INFO: Access modifiers changed from: private */
    public static void findE9AndA21(DexKitBridge bridge) {
        try {
            // v3.0.171（核心数据审计）：e9 = com.tencent.mm.storage.e9 = MicroMsg.MsgInfo（子包全路径，正确）。
            // 其 "d1" 实为 D1(J)V（msgId 合理性断言，1500000001>j && -10<j），并非 TTS 内容转换；
            // e9 内无字符串 "voicemsg"，语音判断在 b1() 用 MicroMsg.VoiceContent 字符串。
            sE9ClassName = "com.tencent.mm.storage.e9";
            LogWriter.log(TAG, "findE9 (fixed): " + sE9ClassName);
        } catch (Throwable e) {
            LogWriter.log(TAG, "findE9 error: " + e.getMessage());
        }
        // v3.0.171：a21 为影视/时间线剪辑扁平包（a21.q = androidx.recyclerview.widget.p2 holder，
        // 38 字段均为 MJID/时间线视图，与 MsgInfo/TTS 无关）。不再用 "voicemsg" 查找 a21，
        // 保留 sA21ClassName=null，hookA21Oi 已停用。
        LogWriter.log(TAG, "findA21: DISABLED (a21 = mj_publisher movie/timeline package, unrelated to TTS)");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void findAvatarHelper(DexKitBridge bridge) {
        try {
            MethodMatcher m1 = MethodMatcher.create().returnType("android.graphics.Bitmap").paramCount(1).paramTypes("java.lang.String");
            List<MethodData> methods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(m1));
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

    /* JADX INFO: Access modifiers changed from: private */
    public static void findLabelStorage(DexKitBridge bridge) {
        try {
            MethodMatcher m1 = MethodMatcher.create().name("hj").modifiers(8);
            List<MethodData> methods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(m1));
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

    /* JADX INFO: Access modifiers changed from: private */
    public static void findConvListAdapter(DexKitBridge bridge) {
        try {
            ClassMatcher cm = ClassMatcher.create().addInterface("android.widget.ListAdapter");
            List<ClassData> classes = bridge.findClass(FindClass.create().excludePackages("com.leshao").matcher(cm));
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

    /* JADX INFO: Access modifiers changed from: private */
    public static void findJ1Service(DexKitBridge bridge) {
        Iterator<MethodData> it;
        List<MethodData> methods = null;
        int i = 2;
        try {
            int i2 = 1;
            String[] strArr = {"v", "s"};
            int i3 = 0;
            while (i3 < i) {
                String mn = strArr[i3];
                MethodMatcher mMatcher = MethodMatcher.create().name(mn).paramCount(i2);
                methods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(mMatcher));
                LogWriter.log(TAG, "findJ1Service: " + methods.size() + " " + mn + "(*) methods found");
                Iterator<MethodData> it2 = methods.iterator();
                while (it2.hasNext()) {
                    MethodData m = it2.next();
                    String clsName = m.getClassName();
                    if (clsName != null) {
                        List<String> pts = m.getParamTypeNames();
                        List<MethodData> methods2 = methods;
                        if (pts.size() == 1) {
                            it = it2;
                            if ("java.lang.Class".equals(pts.get(0)) && (clsName.contains(".j1") || clsName.contains("$j1") || clsName.contains("j1"))) {
                                sJ1ServiceClass = clsName;
                                LogWriter.log(TAG, "findJ1Service: " + clsName + "." + mn + "(Class)");
                                return;
                            }
                        } else {
                            it = it2;
                        }
                        it2 = it;
                        methods = methods2;
                    }
                }
                i3++;
                i = 2;
                i2 = 1;
            }
            // v3.0.154: 修复「Kernel」兜底死代码 —— 旧实现无条件选第一个使用
            // "Kernel not initialized" 的类(实测误选 w5.y0，该类并无 v/s(Class))，
            // 必须要求候选类自身含 v(Class) 或 s(Class) 方法(真正的服务定位器特征)才采用。
            MethodMatcher m2 = MethodMatcher.create().usingStrings("Kernel not initialized");
            List<MethodData> m2s = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(m2));
            for (MethodData md : m2s) {
                String clsName2 = md.getClassName();
                if (clsName2 == null || isExcludedCandidate(clsName2)) continue;
                String mn2 = md.getName();
                List<String> pts2 = md.getParamTypeNames();
                boolean isLocator = ("v".equals(mn2) || "s".equals(mn2))
                        && pts2.size() == 1 && "java.lang.Class".equals(pts2.get(0));
                boolean nameLooksJ1 = clsName2.contains(".j1") || clsName2.contains("$j1") || clsName2.contains("j1");
                if (isLocator || nameLooksJ1) {
                    sJ1ServiceClass = clsName2;
                    LogWriter.log(TAG, "findJ1Service (Kernel " + mn2 + "): " + clsName2 + " params=" + pts2);
                    return;
                }
            }
            LogWriter.log(TAG, "findJ1Service (Kernel): no v/s(Class) candidate");
            for (MethodData m3 : methods) {
                String clsName3 = m3.getClassName();
                if (!isExcludedCandidate(clsName3)) {
                    List<String> pts2 = m3.getParamTypeNames();
                    if (pts2.size() == 1 && "java.lang.Class".equals(pts2.get(0))) {
                        sJ1ServiceClass = clsName3;
                        LogWriter.log(TAG, "findJ1Service (any s(Class)): " + clsName3);
                        return;
                    }
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findJ1Service error: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void findContactStorageAlt(DexKitBridge bridge) {
        try {
            MethodMatcher mMatcher = MethodMatcher.create().name("ij").returnType("long");
            List<MethodData> methods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(mMatcher));
            LogWriter.log(TAG, "findContactStorageAlt: " + methods.size() + " ij() returning long");
            int idx = pickCandidateIndex(methods);
            if (idx >= 0) {
                MethodData m = methods.get(idx);
                sContactStorageClass = m.getClassName();
                LogWriter.log(TAG, "findContactStorageAlt: " + sContactStorageClass + ".ij()");
                return;
            }
            MethodMatcher objMatcher = MethodMatcher.create().name("ij");
            List<MethodData> objMethods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(objMatcher));
            int idx2 = pickCandidateIndex(objMethods);
            if (idx2 >= 0) {
                MethodData m2 = objMethods.get(idx2);
                sContactStorageClass = m2.getClassName();
                LogWriter.log(TAG, "findContactStorageAlt (any ij): " + sContactStorageClass + ".ij()");
                return;
            }
            LogWriter.log(TAG, "findContactStorageAlt: NOT found");
        } catch (Throwable e) {
            LogWriter.log(TAG, "findContactStorageAlt error: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void findChatOpenEntry(DexKitBridge bridge) {
        try {
            MethodMatcher mMatcher = MethodMatcher.create().usingStrings("Chat_User");
            List<MethodData> methods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").searchPackages("com.tencent.mm.ui.chatting").matcher(mMatcher));
            LogWriter.log(TAG, "findChatOpenEntry: " + methods.size() + " methods in chatting pkg use 'Chat_User'");
            int limit = Math.min(methods.size(), 30);
            for (int i = 0; i < limit; i++) {
                MethodData m = methods.get(i);
                LogWriter.log(TAG, "  chatOpen[" + i + "]: " + m.getClassName() + "." + m.getName() + " params=" + m.getParamTypeNames() + " return=" + m.getReturnTypeName());
            }
            int i2 = methods.size();
            if (i2 > limit) {
                LogWriter.log(TAG, "  ... " + (methods.size() - limit) + " more");
            }
            if (!methods.isEmpty()) {
                MethodData selected = null;
                for (MethodData m2 : methods) {
                    String cn = m2.getClassName();
                    String mn = m2.getName();
                    if (cn != null && cn.contains("ChattingUIFragment") && !cn.contains("$$") && m2.getParamTypeNames().isEmpty() && "void".equals(m2.getReturnTypeName()) && (mn.equals("M0") || mn.equals("t0") || mn.equals("u0") || mn.equals("onResume") || mn.equals("onCreateView"))) {
                        selected = m2;
                        LogWriter.log(TAG, "findChatOpenEntry: prefer " + cn + "." + mn);
                        break;
                    }
                }
                if (selected == null) {
                    Iterator<MethodData> it = methods.iterator();
                    while (true) {
                        if (!it.hasNext()) {
                            break;
                        }
                        MethodData m3 = it.next();
                        String cn2 = m3.getClassName();
                        String mn2 = m3.getName();
                        if (cn2 != null && cn2.contains("ChattingUI") && !cn2.contains("$$") && m3.getParamTypeNames().isEmpty()) {
                            selected = m3;
                            LogWriter.log(TAG, "findChatOpenEntry: prefer2 " + cn2 + "." + mn2);
                            break;
                        }
                    }
                }
                if (selected == null) {
                    selected = methods.get(0);
                }
                sChatOpenClass = selected.getClassName();
                sChatOpenMethod = selected.getName();
                LogWriter.log(TAG, "findChatOpenEntry: selected " + sChatOpenClass + "." + sChatOpenMethod);
            }
            ClassMatcher cm = ClassMatcher.create().addFieldForType("com.tencent.mm.ui.widget.MMEditText");
            List<ClassData> holders = bridge.findClass(FindClass.create().excludePackages("com.leshao").matcher(cm));
            LogWriter.log(TAG, "findChatOpenEntry: " + holders.size() + " classes hold MMEditText field (all pkg)");
            int hLimit = Math.min(holders.size(), 10);
            for (int i3 = 0; i3 < hLimit; i3++) {
                LogWriter.log(TAG, "  holder[" + i3 + "]: " + holders.get(i3).getName());
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findChatOpenEntry error: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void findConvLongPressEntry(DexKitBridge bridge) {
        int MAX_LP_IMPLS;
        try {
            MethodMatcher callerMatcher = MethodMatcher.create().addInvoke(MethodMatcher.create().declaredClass("android.widget.AbsListView").name("setOnItemLongClickListener"));
            List<MethodData> callers = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(callerMatcher));
            LogWriter.log(TAG, "findConvLongPressEntry: " + callers.size() + " methods invoke setOnItemLongClickListener (all pkg)");
            int cLimit = Math.min(callers.size(), 20);
            for (int i = 0; i < cLimit; i++) {
                MethodData m = callers.get(i);
                LogWriter.log(TAG, "  lpCaller[" + i + "]: " + m.getClassName() + "." + m.getName() + " params=" + m.getParamTypeNames());
            }
            int i2 = callers.size();
            if (i2 > cLimit) {
                LogWriter.log(TAG, "  ... " + (callers.size() - cLimit) + " more");
            }
            int cSel = pickCandidateIndex(callers);
            if (cSel >= 0) {
                MethodData m2 = callers.get(cSel);
                sConvLongPressClass = m2.getClassName();
                sConvLongPressMethod = m2.getName();
                LogWriter.log(TAG, "findConvLongPressEntry: selected " + sConvLongPressClass + "." + sConvLongPressMethod);
            }
            ClassMatcher icm = ClassMatcher.create().addInterface("android.widget.AdapterView$OnItemLongClickListener");
            List<ClassData> impls = bridge.findClass(FindClass.create().excludePackages("com.leshao").matcher(icm));
            LogWriter.log(TAG, "findConvLongPressEntry: " + impls.size() + " classes implement OnItemLongClickListener (all pkg)");
            int MAX_LP_IMPLS2 = ItemTouchHelper.Callback.DEFAULT_DRAG_ANIMATION_DURATION;
            int logLimit = Math.min(impls.size(), 25);
            int iLimit = Math.min(impls.size(), ItemTouchHelper.Callback.DEFAULT_DRAG_ANIMATION_DURATION);
            List<String> newImpls = new ArrayList<>();
            int i3 = 0;
            while (i3 < iLimit) {
                String cn = impls.get(i3).getName();
                if (i3 < logLimit) {
                    MAX_LP_IMPLS = MAX_LP_IMPLS2;
                    LogWriter.log(TAG, "  lpImpl[" + i3 + "]: " + cn);
                } else {
                    MAX_LP_IMPLS = MAX_LP_IMPLS2;
                }
                newImpls.add(cn);
                i3++;
                MAX_LP_IMPLS2 = MAX_LP_IMPLS;
            }
            int MAX_LP_IMPLS3 = impls.size();
            if (MAX_LP_IMPLS3 > iLimit) {
                LogWriter.log(TAG, "findConvLongPressEntry: impls truncated " + impls.size() + " -> " + iLimit);
            }
            List<String> convOnly = new ArrayList<>();
            for (String cn2 : newImpls) {
                if (cn2.contains("fh5") || cn2.contains("conversation") || cn2.contains("Conversation")) {
                    convOnly.add(cn2);
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

    /* JADX INFO: Access modifiers changed from: private */
    public static void findConvScrollEntry(DexKitBridge bridge) {
        try {
            MethodMatcher scrollMatcher = MethodMatcher.create().addInvoke(MethodMatcher.create().declaredClass("android.widget.AbsListView").name("setSelection")).addInvoke(MethodMatcher.create().declaredClass("android.widget.ListView").name("smoothScrollToPosition")).addInvoke(MethodMatcher.create().declaredClass("android.widget.ListView").name("scrollTo")).addInvoke(MethodMatcher.create().declaredClass("android.widget.AbsListView").name("smoothScrollToPositionFromTop")).addInvoke(MethodMatcher.create().declaredClass("android.widget.AbsListView").name("setSelectionFromTop"));
            List<MethodData> callers = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(scrollMatcher));
            LogWriter.log(TAG, "findConvScrollEntry: " + callers.size() + " methods invoke setSelection/smoothScrollToPosition/scrollTo (all pkg)");
            int cLimit = Math.min(callers.size(), 25);
            for (int i = 0; i < cLimit; i++) {
                MethodData m = callers.get(i);
                LogWriter.log(TAG, "  scroll[" + i + "]: " + m.getClassName() + "." + m.getName() + " params=" + m.getParamTypeNames() + " return=" + m.getReturnTypeName());
            }
            int i2 = callers.size();
            if (i2 > cLimit) {
                LogWriter.log(TAG, "  ... " + (callers.size() - cLimit) + " more");
            }
            if (!callers.isEmpty()) {
                MethodData m2 = callers.get(0);
                sConvScrollClass = m2.getClassName();
                sConvScrollMethod = m2.getName();
                LogWriter.log(TAG, "findConvScrollEntry: selected " + sConvScrollClass + "." + sConvScrollMethod);
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findConvScrollEntry error: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void findConvMenuEntry(DexKitBridge bridge) {
        try {
            MethodMatcher mMatcher = MethodMatcher.create().usingStrings("MicroMsg.ConversationLongClickListener");
            List<MethodData> methods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(mMatcher));
            LogWriter.log(TAG, "findConvMenuEntry: " + methods.size() + " methods use 'MicroMsg.ConversationLongClickListener'");
            int limit = Math.min(methods.size(), 20);
            for (int i = 0; i < limit; i++) {
                MethodData m = methods.get(i);
                LogWriter.log(TAG, "  menu[" + i + "]: " + m.getClassName() + "." + m.getName() + " params=" + m.getParamTypeNames() + " return=" + m.getReturnTypeName());
            }
            int i2 = methods.size();
            if (i2 > limit) {
                LogWriter.log(TAG, "  ... " + (methods.size() - limit) + " more");
            }
            if (!methods.isEmpty()) {
                for (MethodData m2 : methods) {
                    String cn = m2.getClassName();
                    if (cn != null && cn.contains("conversation")) {
                        sConvMenuClass = cn;
                        sConvMenuMethod = m2.getName();
                        LogWriter.log(TAG, "findConvMenuEntry: selected " + cn + "." + m2.getName());
                        return;
                    }
                }
                MethodData m3 = methods.get(0);
                sConvMenuClass = m3.getClassName();
                sConvMenuMethod = m3.getName();
                LogWriter.log(TAG, "findConvMenuEntry: selected(any) " + sConvMenuClass + "." + sConvMenuMethod);
            }
            ClassMatcher cm = ClassMatcher.create().addInterface("android.widget.AdapterView$OnItemLongClickListener").addInterface("android.view.View$OnCreateContextMenuListener");
            List<ClassData> impls = bridge.findClass(FindClass.create().excludePackages("com.leshao").matcher(cm));
            LogWriter.log(TAG, "findConvMenuEntry: " + impls.size() + " classes impl OnItemLongClickListener+OnCreateContextMenuListener");
            for (ClassData c : impls) {
                String cn2 = c.getName();
                LogWriter.log(TAG, "  menuImpl: " + cn2);
                if (cn2 != null && cn2.contains("conversation")) {
                    sConvMenuClass = cn2;
                    sConvMenuMethod = "onCreateContextMenu";
                    LogWriter.log(TAG, "findConvMenuEntry: selected(impl) " + cn2 + ".onCreateContextMenu");
                    return;
                }
            }
            if (!impls.isEmpty()) {
                sConvMenuClass = impls.get(0).getName();
                sConvMenuMethod = "onCreateContextMenu";
                LogWriter.log(TAG, "findConvMenuEntry: selected(impl any) " + sConvMenuClass);
                return;
            }
            MethodMatcher popupMatcher = MethodMatcher.create().usingStrings("MicroMsg.MMPopupMenu");
            List<MethodData> popupMethods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(popupMatcher));
            LogWriter.log(TAG, "findConvMenuEntry: " + popupMethods.size() + " methods use 'MicroMsg.MMPopupMenu'");
            for (MethodData m4 : popupMethods) {
                LogWriter.log(TAG, "  popupImpl: " + m4.getClassName() + "." + m4.getName());
            }
            if (!popupMethods.isEmpty()) {
                sConvMenuClass = popupMethods.get(0).getClassName();
                sConvMenuMethod = popupMethods.get(0).getName();
                LogWriter.log(TAG, "findConvMenuEntry: selected(popup) " + sConvMenuClass + "." + sConvMenuMethod);
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findConvMenuEntry error: " + e.getMessage());
        }
    }

    public static List<String> getRevokeListenerClasses() {
        return sRevokeListenerClasses;
    }

    public static String getLabelStorageProviderClass() {
        return sLabelStorageProviderClass;
    }

    public static String getLabelStorageProviderMethod() {
        return sLabelStorageProviderMethod;
    }

    /**
     * v3.0.166：实时检索标签存储提供者（静态 + 无参 + 返回 com.tencent.mm.storage.g4）。
     * 缓存加载（cache-only）模式可能缺 label_provider 结果，ChatGroupHook 重试失败
     * （label=false，旧类名候选 x93.* 在 3180 不存在）时调用本方法现场扫描兜底。
     * 命中后同步回写缓存字段，供后续逻辑复用。
     */
    public static boolean findLabelStorageProviderLive(ClassLoader cl) {
        if (sLabelStorageProviderClass != null && sLabelStorageProviderMethod != null) return true;
        if (isMainThread()) return false;
        final String[] found = new String[2];
        withWechatBridge(cl, new BridgeAction<Object>() {
            @Override
            public Object run(DexKitBridge b) {
                try {
                    List<MethodData> methods = b.findMethod(FindMethod.create().excludePackages("com.leshao")
                            .matcher(MethodMatcher.create().modifiers(8)
                                    .returnType("com.tencent.mm.storage.g4")));
                    for (MethodData m : methods) {
                        if (m.getParamTypeNames().isEmpty()) {
                            found[0] = m.getClassName();
                            found[1] = m.getName();
                            break;
                        }
                    }
                } catch (Throwable ignored) {}
                return null;
            }
        });
        if (found[0] != null) {
            sLabelStorageProviderClass = found[0];
            sLabelStorageProviderMethod = found[1];
            LogWriter.log(TAG, "findLabelStorageProviderLive: "
                    + found[0] + "." + found[1] + "()");
            return true;
        }
        LogWriter.log(TAG, "findLabelStorageProviderLive: not found");
        return false;
    }

    public static String getPinyinUtilClass() {
        return sPinyinUtilClass;
    }

    public static String getServiceLocatorClass() {
        return sServiceLocatorClass;
    }

    public static String getMediaPathServiceClass() {
        return sMediaPathServiceClass;
    }

    public static String getMediaPathMethod() {
        return sMediaPathMethod;
    }

    public static String getClipboardJsApiClass() {
        return sClipboardJsApiClass;
    }

    public static List<String> getChatMoreSelectClasses() {
        return sChatMoreSelectClasses;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void findV955Targets(DexKitBridge bridge) {
        findRevokeListeners(bridge);
        findLabelStorageProvider(bridge);
        findPinyinUtil(bridge);
        findServiceLocator(bridge);
        findMediaPathService(bridge);
        findClipboardJsApi(bridge);
        findChatMoreSelect(bridge);
    }

    private static void findRevokeListeners(DexKitBridge bridge) {
        String cn;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String[] strArr = {"MicroMsg.RevokeReceiveMessageListener", "MicroMsg.RevokeMsgListener"};
        for (int i = 0; i < 2; i++) {
            String tag = strArr[i];
            try {
                List<ClassData> classes = bridge.findClass(FindClass.create().excludePackages("com.leshao").matcher(ClassMatcher.create().usingStrings(tag)));
                Iterator<ClassData> it = classes.iterator();
                while (it.hasNext()) {
                    String cn2 = it.next().getName();
                    if (cn2 != null) {
                        out.add(cn2);
                    }
                }
                LogWriter.log(TAG, "findRevokeListeners(" + tag + "): " + classes.size() + " cands");
            } catch (Throwable e) {
                LogWriter.log(TAG, "findRevokeListeners(" + tag + ") err: " + e.getMessage());
            }
        }
        if (out.isEmpty()) {
            String[] strArr2 = {"com.tencent.mm.ui.chatting.RevokeMsgListener", "com.tencent.mm.ui.chatting.RevokeReceiveMessageListener", "com.tencent.mm.chatroom.plugin.listener.n0"};
            for (int i2 = 0; i2 < 3; i2++) {
                String cn3 = strArr2[i2];
                try {
                    List<ClassData> classes2 = bridge.findClass(FindClass.create().excludePackages("com.leshao").matcher(ClassMatcher.create().className(cn3)));
                    for (ClassData c : classes2) {
                        if (c.getName() != null) {
                            out.add(c.getName());
                        }
                    }
                    LogWriter.log(TAG, "findRevokeListeners(byName " + cn3 + "): " + classes2.size() + " cands");
                } catch (Throwable e2) {
                    LogWriter.log(TAG, "findRevokeListeners(byName " + cn3 + ") err: " + e2.getMessage());
                }
            }
        }
        if (out.isEmpty()) {
            try {
                List<MethodData> methods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(MethodMatcher.create().name("callback")));
                for (MethodData m : methods) {
                    List<String> pts = m.getParamTypeNames();
                    if (pts != null) {
                        boolean hit = false;
                        Iterator<String> it2 = pts.iterator();
                        while (true) {
                            if (!it2.hasNext()) {
                                break;
                            }
                            String pt = it2.next();
                            if (pt != null && pt.contains("fm.ks")) {
                                hit = true;
                                break;
                            }
                        }
                        if (hit && (cn = m.getClassName()) != null) {
                            out.add(cn);
                        }
                    }
                }
                LogWriter.log(TAG, "findRevokeListeners(byCallback fm.ks): " + out.size() + " cands");
            } catch (Throwable e3) {
                LogWriter.log(TAG, "findRevokeListeners(byCallback) err: " + e3.getMessage());
            }
        }
        if (out.isEmpty()) {
            try {
                Iterator<ClassData> it3 = bridge.findClass(FindClass.create().excludePackages("com.leshao").matcher(ClassMatcher.create().className("Revoke", StringMatchType.Contains, false))).iterator();
                while (it3.hasNext()) {
                    String cn4 = it3.next().getName();
                    if (cn4 != null) {
                        String simple = cn4.substring(cn4.lastIndexOf(46) + 1);
                        if (simple.toLowerCase().contains("revoke")) {
                            out.add(cn4);
                        }
                    }
                }
                LogWriter.log(TAG, "findRevokeListeners(byClassName Revoke): " + out.size() + " cands");
            } catch (Throwable e4) {
                LogWriter.log(TAG, "findRevokeListeners(byClassName) err: " + e4.getMessage());
            }
        }
        sRevokeListenerClasses = new ArrayList(out);
        LogWriter.log(TAG, "findRevokeListeners: " + sRevokeListenerClasses);
    }

    private static void findLabelStorageProvider(DexKitBridge bridge) {
        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(MethodMatcher.create().modifiers(8).returnType("com.tencent.mm.storage.g4")));
            for (MethodData m : methods) {
                if (m.getParamTypeNames().isEmpty()) {
                    sLabelStorageProviderClass = m.getClassName();
                    sLabelStorageProviderMethod = m.getName();
                    LogWriter.log(TAG, "findLabelStorageProvider: " + sLabelStorageProviderClass + "." + sLabelStorageProviderMethod + "()");
                    return;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findLabelStorageProvider err: " + e.getMessage());
        }
    }

    private static void findPinyinUtil(DexKitBridge bridge) {
        try {
            List<MethodData> aMethods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(MethodMatcher.create().modifiers(8).name("a").paramCount(1).paramTypes("java.lang.String").returnType("java.lang.String")));
            for (MethodData am : aMethods) {
                String cn = am.getClassName();
                if (cn != null) {
                    List<MethodData> bMethods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(MethodMatcher.create().modifiers(8).name("b").paramCount(1).paramTypes("java.lang.String").returnType("java.lang.String").declaredClass(cn)));
                    if (!bMethods.isEmpty()) {
                        sPinyinUtilClass = cn;
                        LogWriter.log(TAG, "findPinyinUtil: " + cn);
                        return;
                    }
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findPinyinUtil err: " + e.getMessage());
        }
    }

    private static void findServiceLocator(DexKitBridge bridge) {
        try {
            List<ClassData> classes = bridge.findClass(FindClass.create().excludePackages("com.leshao").matcher(ClassMatcher.create().usingStrings("MicroMsg.ServiceManager")));
            for (ClassData c : classes) {
                String cn = c.getName();
                if (cn != null) {
                    List<MethodData> cms = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(MethodMatcher.create().modifiers(8).name("c").paramCount(1).paramTypes("java.lang.Class").declaredClass(cn)));
                    if (!cms.isEmpty()) {
                        sServiceLocatorClass = cn;
                        LogWriter.log(TAG, "findServiceLocator: " + cn + ".c(Class)");
                        return;
                    }
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findServiceLocator err: " + e.getMessage());
        }
    }

    private static void findMediaPathService(DexKitBridge bridge) {
        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(MethodMatcher.create().name("Nj").paramCount(4).returnType("java.lang.String")));
            Iterator<MethodData> it = methods.iterator();
            if (it.hasNext()) {
                MethodData m = it.next();
                sMediaPathServiceClass = m.getClassName();
                sMediaPathMethod = m.getName();
                LogWriter.log(TAG, "findMediaPathService: " + sMediaPathServiceClass + "." + sMediaPathMethod);
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findMediaPathService err: " + e.getMessage());
        }
    }

    private static void findClipboardJsApi(DexKitBridge bridge) {
        try {
            List<ClassData> classes = bridge.findClass(FindClass.create().excludePackages("com.leshao").matcher(ClassMatcher.create().usingStrings("setClipboardData")));
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

    private static void findChatMoreSelect(DexKitBridge bridge) {
        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").searchPackages("com.tencent.mm.ui.chatting").matcher(MethodMatcher.create().name("onCreateOptionsMenu").paramCount(1)));
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (MethodData m : methods) {
                String cn = m.getClassName();
                if (cn != null) {
                    List<MethodData> oim = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(MethodMatcher.create().name("onOptionsItemSelected").paramCount(1).declaredClass(cn)));
                    if (!oim.isEmpty() && (cn.contains("Select") || cn.contains("More"))) {
                        out.add(cn);
                    }
                }
            }
            sChatMoreSelectClasses = new ArrayList(out);
            LogWriter.log(TAG, "findChatMoreSelect: " + sChatMoreSelectClasses);
        } catch (Throwable e) {
            LogWriter.log(TAG, "findChatMoreSelect err: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void findMenuG4Impls(DexKitBridge bridge) {
        try {
            ClassMatcher cm = ClassMatcher.create().addInterface("android.view.Menu");
            List<ClassData> impls = bridge.findClass(FindClass.create().excludePackages("com.leshao").matcher(cm));
            LogWriter.log(TAG, "findMenuG4Impls: " + impls.size() + " classes implement android.view.Menu");
            List<String> newList = new ArrayList<>();
            for (ClassData c : impls) {
                String cn = c.getName();
                if (cn != null && (cn.contains("menu") || cn.contains("Menu") || cn.contains("kc5"))) {
                    LogWriter.log(TAG, "  menuImpl: " + cn);
                    newList.add(cn);
                }
            }
            try {
                ClassMatcher cm2 = ClassMatcher.create().className(".*[Mm]enu.*");
                List<ClassData> impls2 = bridge.findClass(FindClass.create().excludePackages("com.leshao").matcher(cm2));
                for (ClassData c2 : impls2) {
                    String cn2 = c2.getName();
                    if (cn2 != null && !newList.contains(cn2) && !cn2.startsWith("android.") && !cn2.startsWith("java.")) {
                        LogWriter.log(TAG, "  menuImpl2: " + cn2);
                        newList.add(cn2);
                    }
                }
            } catch (Throwable th) {
            }
            sMenuG4Impls = newList;
        } catch (Throwable e) {
            LogWriter.log(TAG, "findMenuG4Impls error: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void findRuntimeHookTargets(DexKitBridge bridge) {
        try {
            if (sX9Class == null) {
                sX9Class = findX9DispatchClass(bridge);
                LogWriter.log(TAG, "findRuntimeHookTargets: x9=" + sX9Class);
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findRuntimeHookTargets x9 err: " + e.getMessage());
        }
        try {
            if (sNetSceneUpdateInfo == null) {
                sNetSceneUpdateInfo = firstClassByDexKitString(bridge, "MicroMsg.NetSceneGetUpdateInfo");
                LogWriter.log(TAG, "findRuntimeHookTargets: NetSceneGetUpdateInfo=" + sNetSceneUpdateInfo);
            }
        } catch (Throwable e2) {
            LogWriter.log(TAG, "findRuntimeHookTargets NetSceneGetUpdateInfo err: " + e2.getMessage());
        }
        try {
            if (sUpdaterManager == null) {
                sUpdaterManager = firstClassByDexKitString(bridge, "MicroMsg.UpdaterManager");
                LogWriter.log(TAG, "findRuntimeHookTargets: UpdaterManager=" + sUpdaterManager);
            }
        } catch (Throwable e3) {
            LogWriter.log(TAG, "findRuntimeHookTargets UpdaterManager err: " + e3.getMessage());
        }
        try {
            if (sCtinkerInstaller == null) {
                sCtinkerInstaller = firstClassByDexKitString(bridge, "MicroMsg.Tinker.CTinkerInstaller");
                LogWriter.log(TAG, "findRuntimeHookTargets: CTinkerInstaller=" + sCtinkerInstaller);
            }
        } catch (Throwable e4) {
            LogWriter.log(TAG, "findRuntimeHookTargets CTinkerInstaller err: " + e4.getMessage());
        }
    }

    private static String firstClassByDexKitString(DexKitBridge bridge, String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> cands = new LinkedHashSet<>();
        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(MethodMatcher.create().usingStrings(keyword)));
            for (MethodData m : methods) {
                String cn = m.getClassName();
                if (cn != null && !isExcludedCandidate(cn)) {
                    cands.add(cn);
                }
            }
        } catch (Throwable th) {
        }
        try {
            List<ClassData> classes = bridge.findClass(FindClass.create().excludePackages("com.leshao").matcher(ClassMatcher.create().addFieldForType(keyword)));
            for (ClassData c : classes) {
                String cn2 = c.getName();
                if (cn2 != null && !isExcludedCandidate(cn2)) {
                    cands.add(cn2);
                }
            }
        } catch (Throwable th2) {
        }
        Iterator<String> it = cands.iterator();
        if (it.hasNext()) {
            return it.next();
        }
        return null;
    }

    private static String findX9DispatchClass(DexKitBridge bridge) {
        String pt0;
        LinkedHashSet<String> cands = new LinkedHashSet<>();
        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create().excludePackages("com.leshao").matcher(MethodMatcher.create().usingStrings("IEvent")));
            for (MethodData m : methods) {
                String cn = m.getClassName();
                if (cn != null && !isExcludedCandidate(cn)) {
                    cands.add(cn);
                }
            }
        } catch (Throwable th) {
        }
        Iterator<String> it = cands.iterator();
        while (it.hasNext()) {
            String cn2 = it.next();
            try {
                ClassData cd = bridge.getClassData(cn2);
                if (cd != null) {
                    Iterator it2 = cd.getMethods().iterator();
                    while (it2.hasNext()) {
                        MethodData m2 = (MethodData) it2.next();
                        List<String> pts = m2.getParamTypeNames();
                        if (pts != null && !pts.isEmpty() && (pt0 = pts.get(0)) != null && (pt0.equals("com.tencent.mm.storage.e9") || pt0.endsWith(".e9"))) {
                            return cn2;
                        }
                    }
                }
            } catch (Throwable th2) {
            }
        }
        return null;
    }

    /**
     * v3.0.176: 扫描数据源已切换到官方 path 方式(createBridge 直接扫 APK 文件字节),
     * 不再依赖 ClassLoader 就绪时序。原 v3.0.169 resolveScanClassLoader(等待 Tinker CL
     * 的 10s 轮询)已删除——DexKit 解析 DEX 文件而非反射类加载, base.apk 内即可匹配全部锚点。
     */
    /* JADX INFO: Access modifiers changed from: private */
    public static void startFullScan(final Application app, final boolean showDialog) {
        try {
            if (!sFullScanScheduled.compareAndSet(false, true)) {
                LogWriter.log(TAG, "startFullScan: full scan already scheduled, skip");
                return;
            }
            if (showDialog) {
                sShouldShowScanDialog = true;
            }
            final Runnable scanTask = new Runnable() { // from class: com.leshao.v3.hook.DexKitHelper.13
                @Override // java.lang.Runnable
                public void run() {
                    DexKitCacheBridge.RecyclableBridge cacheBridge = null;
                    try {
                        try {
                            DexKitHelper.loadDexKitLibrary(app);
                            if (!DexKitHelper.sLibraryLoaded.get()) {
                                LogWriter.log(DexKitHelper.TAG, "DexKit library not loaded, skipping");
                                if (showDialog) {
                                    DexKitHelper.sShouldShowScanDialog = false;
                                    // 仅临时关闭本次弹窗，不清除 sDismissed 永久标记，
                                    // 下次版本变化触发的扫描仍可正常弹窗。
                                    DexKitScanDialog.hideDialog();
                                }
                                DexKitHelper.markScanCompleteAndDrain();
                                if (0 != 0) {
                                    try {
                                        cacheBridge.close();
                                        return;
                                    } catch (Throwable th) {
                                        return;
                                    }
                                }
                                return;
                            }
                            DexKitHelper.initDexKitCache(app);
                            String appTag = "wechat_" + (DexKitHelper.sVersionCode > 0 ? Integer.valueOf(DexKitHelper.sVersionCode) : "");
                            // v3.0.176: 补扫循环。官方推荐 DexKitCacheBridge.create(appTag, path) 直接扫
                            // 落盘 APK 文件, 不依赖 ClassLoader 就绪时序, 启动早期即可稳定扫描。
                            // 仅在极端情况(APK 路径不可得)才回退 classLoader。仍保留 unhealthy 延迟重扫兜底。
                            int deferredScanCount = 0;
                            while (true) {
                                // v3.0.176: 用官方 path 数据源建桥(扫描 DEX 文件字节), 不再等待 Tinker CL。
                                // 若将来需覆盖补丁专属类, 可再叠 classLoader 兜底; 当前核心锚点均在 base.apk 内。
                                DexKitCacheBridge.RecyclableBridge cacheBridge2 = DexKitHelper.createBridge(app.getClassLoader());
                                LogWriter.log(DexKitHelper.TAG, "startFullScan: appTag=" + appTag + " showDialog=" + showDialog + " bridge=" + (cacheBridge2 != null ? "ok" : "null") + " deferCount=" + deferredScanCount);
                                if (cacheBridge2 == null) {
                                    LogWriter.log(DexKitHelper.TAG, "startFullScan: bridge null, drain callbacks");
                                    DexKitHelper.markScanCompleteAndDrain();
                                    break;
                                }
                                DexKitHelper.scanWechatTargets(cacheBridge2);
                                cacheBridge2.close();
                                // v3.0.169: 关键目标为空(视为扫描过早/不完整)时不写版本号,
                                // 避免污染缓存导致下次 SCAN SKIP 复用坏结果
                                if (DexKitHelper.sLastScanHealthy) {
                                    DexKitHelper.persistScanVersion();
                                    break;
                                }
                                LogWriter.log(DexKitHelper.TAG, "startFullScan: scan unhealthy, keep old cache (defer rescan " + (deferredScanCount + 1) + "/3)");
                                if (deferredScanCount >= 3) {
                                    LogWriter.log(DexKitHelper.TAG, "startFullScan: deferred rescans exhausted, give up this launch (next launch will rescan)");
                                    break;
                                }
                                long delayMillis = deferredScanCount == 0 ? 5000L : (deferredScanCount == 1 ? 10000L : 20000L);
                                deferredScanCount++;
                                LogWriter.log(DexKitHelper.TAG, "startFullScan: defer rescan #" + deferredScanCount + " in " + delayMillis + "ms");
                                try {
                                    Thread.sleep(delayMillis);
                                } catch (InterruptedException ie) {
                                    LogWriter.log(DexKitHelper.TAG, "startFullScan: deferred rescan interrupted");
                                    break;
                                }
                            }
                        } catch (Throwable e) {
                            try {
                                LogWriter.log(DexKitHelper.TAG, "DexKit scan thread error: " + e.getMessage());
                                DexKitHelper.markScanCompleteAndDrain();
                                if (0 != 0) {
                                    cacheBridge.close();
                                }
                            } catch (Throwable th2) {
                                if (0 != 0) {
                                    try {
                                        cacheBridge.close();
                                    } catch (Throwable th3) {
                                    }
                                }
                                throw th2;
                            }
                        }
                    } catch (Throwable th4) {
                    }
                }
            };
            sMainHandler.postDelayed(new Runnable() { // from class: com.leshao.v3.hook.DexKitHelper.14
                @Override // java.lang.Runnable
                public void run() {
                    try {
                        DexKitHelper.sExecutor.execute(scanTask);
                    } catch (Throwable th) {
                    }
                }
            }, 0L);
            // v3.0.136: 扫描总超时 180s，防止 dex 解析异常时 sFullScanScheduled 永远为 true、
            // 导致其他线程在 waitForFullScanIfScheduled 中无限等待（启动卡死）。
            sMainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!DexKitHelper.isScanComplete()) {
                        LogWriter.log(DexKitHelper.TAG, "startFullScan: FORCE timeout 180s, mark complete");
                        DexKitHelper.markScanCompleteAndDrain();
                    }
                }
            }, 180000L);
        } catch (Throwable e) {
            LogWriter.log(TAG, "startFullScan err: " + e.getMessage());
            markScanCompleteAndDrain();
        }
    }

    public static void hookApplication(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Method attachMethod = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            XposedBridge.hookMethod(attachMethod, new XC_MethodHook() { // from class: com.leshao.v3.hook.DexKitHelper.15
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    if (param.thisObject instanceof Application) {
                        Application app = (Application) param.thisObject;
                        if ("com.tencent.mm".equals(app.getPackageName())) {
                            String processName = lpparam.processName;
                            boolean isMainProcess = "com.tencent.mm".equals(processName);
                            if (!isMainProcess) {
                                LogWriter.log(DexKitHelper.TAG, "clone process " + processName + ": reading MMKV cache only (read-only, never clears cache)");
                                try {
                                    MMKV.initialize(app);
                                } catch (Throwable e) {
                                    LogWriter.log(DexKitHelper.TAG, "clone process MMKV.initialize err: " + e.getMessage());
                                }
                                try {
                                    if (!DexKitHelper.loadResultsFromMMKV(app, false)) {
                                        // 只读：版本不匹配/缓存不完整时绝不 clearAll（否则清掉主进程扫描结果，
                                        // 导致反复全量扫描）。模块刚更新时主进程正在扫描，defer 后重读即可。
                                        LogWriter.log(DexKitHelper.TAG, "clone process: no usable cache, defer read (main process scanning)");
                                        DexKitHelper.deferCloneCacheRead(app);
                                        return;
                                    }
                                    return;
                                } catch (Throwable e2) {
                                    LogWriter.log(DexKitHelper.TAG, "clone process MMKV read failed: " + e2.getMessage());
                                    return;
                                }
                            }
                            try {
                                MMKV.initialize(app);
                            } catch (Throwable e3) {
                                LogWriter.log(DexKitHelper.TAG, "MMKV.initialize err: " + e3.getMessage());
                            }
                            try {
                                MMKV kv = MMKV.mmkvWithID(DexKitHelper.MMKV_RESULTS_ID, 2);
                                int cachedVersion = kv.decodeInt(DexKitHelper.KEY_VERSION_CODE, 0);
                                int cachedModule = kv.decodeInt(DexKitHelper.KEY_MODULE_VERSION, 0);
                                boolean versionMatch = (cachedVersion == DexKitHelper.sVersionCode)
                                        && (cachedModule == DexKitHelper.CURRENT_MODULE_VERSION);
                                // v3.0.152: 仅当 ①首次安装无缓存 或 ②微信版本变化(微信更新) 或 ③模块版本变化(模块更新)
                                // 时全量扫描一次；版本匹配时只加载缓存（无论完整与否），绝不扫描、绝不 rescan、
                                // 绝不 fallback scan —— 避免每次启动（含 appbrand/push 等 clone 进程）都触发
                                // DexKit 扫描，造成"使用微信过程中反复加载 dexkit"。
                                if (versionMatch) {
                                    LogWriter.log(DexKitHelper.TAG, "hookApplication: SCAN SKIP (wx=" + cachedVersion
                                            + " module=" + DexKitHelper.CURRENT_MODULE_VERSION + "), cache-only");
                                    DexKitHelper.loadResultsFromMMKV(app, false);
                                    return;
                                }
                                LogWriter.log(DexKitHelper.TAG, "hookApplication: SCAN NEEDED (cachedWx=" + cachedVersion
                                        + " cachedModule=" + cachedModule
                                        + " currentWx=" + DexKitHelper.sVersionCode
                                        + " currentModule=" + DexKitHelper.CURRENT_MODULE_VERSION + ")");
                                // v3.0.176: 微信未变(仅模块升级)时静默补齐扫描, 不弹"重新适配"窗;
                                // 微信更新或首次安装才全量扫描并弹窗。DexKitCacheBridge 按 key 命中
                                // 已有缓存, 模块升级只补查缺失锚点, 补齐后由 startFullScan 持久化版本号。
                                boolean wxUnchanged = (cachedVersion == DexKitHelper.sVersionCode);
                                DexKitHelper.sShouldShowScanDialog = !wxUnchanged;
                                DexKitHelper.startFullScan(app, !wxUnchanged);
                                return;
                            } catch (Throwable e4) {
                                LogWriter.log(DexKitHelper.TAG, "hookApplication MMKV check err: " + e4.getMessage());
                            }
                            DexKitHelper.sShouldShowScanDialog = true;
                            DexKitHelper.startFullScan(app, true);
                        }
                    }
                }
            });
            try {
                XposedBridge.hookAllMethods(Instrumentation.class, "callActivityOnCreate", new XC_MethodHook() { // from class: com.leshao.v3.hook.DexKitHelper.16
                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                        if (DexKitHelper.sShouldShowScanDialog) {
                            Object obj = param.args[0];
                            if (obj instanceof Activity) {
                                Activity activity = (Activity) obj;
                                // v3.0.123: 不再只等 LauncherUI —— 微信启动先出现 WeChatSplashActivity，
                                // 若扫描已开始而 LauncherUI 迟迟未创建（首启/升级后全量扫描耗时），
                                // 用户会看到长时间黑屏且无任何进度反馈。这里放宽到启动期第一个
                                // 微信 Activity（Splash / LauncherUI）即显示进度弹窗，保证有可见反馈。
                                String actName = activity.getClass().getName();
                                boolean isStartupActivity = "com.tencent.mm".equals(activity.getPackageName())
                                        && ("com.tencent.mm.ui.LauncherUI".equals(actName)
                                            || "com.tencent.mm.app.WeChatSplashActivity".equals(actName));
                                if (isStartupActivity) {
                                    DexKitHelper.sShouldShowScanDialog = false;
                                    LogWriter.log(DexKitHelper.TAG, "DexKitScanDialog shown on " + actName);
                                    DexKitScanDialog.show(activity);
                                    if (!DexKitHelper.sFullScanScheduled.get() || DexKitHelper.isScanComplete()) {
                                        DexKitHelper.sMainHandler.postDelayed(new Runnable() { // from class: com.leshao.v3.hook.DexKitHelper$16$$ExternalSyntheticLambda0
                                            @Override // java.lang.Runnable
                                            public final void run() {
                                                DexKitScanDialog.onScanComplete();
                                            }
                                        }, 1200L);
                                    }
                                }
                            }
                        }
                    }
                });
                LogWriter.log(TAG, "Instrumentation.callActivityOnCreate hook installed");
            } catch (Throwable e) {
                LogWriter.log(TAG, "hookActivity onCreate err: " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
            LogWriter.log(TAG, "ContextWrapper.attachBaseContext hook installed");
        } catch (Throwable e2) {
            LogWriter.log(TAG, "hookApplication error: " + e2.getMessage());
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
                                        // v3.0.176: 统一走 createBridge(优先官方 path 数据源), 与主扫描同一 appTag 池化实例
                                        cacheBridge = DexKitHelper.createBridge(cl);
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



    /* renamed from: com.leshao.v3.hook.DexKitHelper$17, reason: invalid class name */
    /* loaded from: classes4.dex */
    class AnonymousClass17 extends XC_MethodHook {
        private volatile boolean sCalled = false;
        final /* synthetic */ KernelReadyCallback val$callback;
        final /* synthetic */ ClassLoader val$cl;

        AnonymousClass17(ClassLoader classLoader, KernelReadyCallback kernelReadyCallback) {
            this.val$cl = classLoader;
            this.val$callback = kernelReadyCallback;
        }

        protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
            if (!this.sCalled && param.getThrowable() == null) {
                Object result = param.getResult();
                if (result == null) {
                    return;
                }
                this.sCalled = true;
                LogWriter.log(DexKitHelper.TAG, "waitKernelInit: kernel is ready");
                DexKitHelper.sExecutor.execute(new Runnable() { // from class: com.leshao.v3.hook.DexKitHelper.17.1
                    @Override // java.lang.Runnable
                    public void run() {
                        DexKitCacheBridge.RecyclableBridge cacheBridge = null;
                        try {
                            try {
                                // v3.0.176: 统一走 createBridge(优先官方 path 数据源), 与主扫描同一 appTag 池化实例
                                cacheBridge = DexKitHelper.createBridge(AnonymousClass17.this.val$cl);
                                if (cacheBridge != null) {
                                    cacheBridge.withBridge(new DexKitCacheBridge.RecyclableBridge.BridgeFunction() { // from class: com.leshao.v3.hook.DexKitHelper.17.1.1
                                        @Override // org.luckypray.dexkit.DexKitCacheBridge.RecyclableBridge.BridgeFunction
                                        public void apply(DexKitBridge b) {
                                            AnonymousClass17.this.val$callback.onKernelReady(b);
                                        }
                                    });
                                }
                                if (cacheBridge != null) {
                                    cacheBridge.close();
                                }
                            } catch (Throwable e) {
                                try {
                                    LogWriter.log(DexKitHelper.TAG, "waitKernelInit bridge error: " + e.getMessage());
                                    if (cacheBridge != null) {
                                        cacheBridge.close();
                                    }
                                } catch (Throwable th) {
                                    if (cacheBridge != null) {
                                        try {
                                            cacheBridge.close();
                                        } catch (Throwable th2) {
                                        }
                                    }
                                    throw th;
                                }
                            }
                        } catch (Throwable th3) {
                        }
                    }
                });
            }
        }
    }
}
