package com.leshao.v3.hook;

import android.content.Context;

import com.leshao.v3.ContextManager;
import com.leshao.v3.ContactRepository;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ShadowLabelStore;
import com.leshao.v3.hook.model.LabelInfo;

import java.util.*;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ChatGroupHook {

    private static final String TAG = "ChatGroupHook";

    private static volatile Object sLabelStorage;
    private static volatile Object sContactStorage;
    private static volatile ClassLoader sClassLoader;
    private static volatile Class<?> sJ1Class;
    private static volatile Context sWeChatContext;
    private static volatile boolean sHooksInstalled = false;
    private static volatile boolean sInitDone = false;

    private static final List<LabelInfo> sCachedLabels = Collections.synchronizedList(new ArrayList<LabelInfo>());
    private static final Map<Integer, Set<String>> sShadowContactMap = Collections.synchronizedMap(new HashMap<>());
    private static volatile boolean sShadowMapBuilt = false;
    // 内置虚拟标签 ID，避开 sNextFakeId 分配段，任何微信/分身环境恒存在
    public static final int LABEL_ID_GROUP = 10000;
    public static final int LABEL_ID_FRIEND = 10001;
    public static final int LABEL_ID_SERVICE = 10002;
    public static final String LABEL_NAME_GROUP = "\u7FA4\u804A";
    public static final String LABEL_NAME_FRIEND = "\u597D\u53CB";
    public static final String LABEL_NAME_SERVICE = "\u670D\u52A1";
    // 动态标签 ID 起点：必须大于内置 ID 段，避免与内置标签冲突
    private static int sNextFakeId = 20000;

    public static void hook(ClassLoader cl) {
        sClassLoader = cl;
        // Phase 1: Render UI immediately (tag bar, context menu hooks)
        ShadowLabelStore.init(getWeChatContext());
        installRealTimeHooks();
        // Phase 2: Defer functionality initialization until DexKit scan completes
        DexKitHelper.addPostScanCallback(() -> {
            ensureInit();
            startSubSystems();
            if (isReady()) {
                restoreShadowLabels();
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> { try { ChatGroupUiInjector.refreshTagData(); } catch (Throwable e) { LogWriter.log(TAG, "refreshTagData err: " + e); } });
                ContactRepository.loadAsync(null);
            } else {
                scheduleRetry(500);
            }
            LogWriter.log(TAG, "ChatGroupHook post-scan init done");
        });
        LogWriter.log(TAG, "ChatGroupHook 初始化完成 (功能等DexKit扫描后启用)");
    }

    private static int sRetryCount = 0;

    private static void scheduleRetry(int delayMs) {
        sRetryCount++;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            new Thread(() -> {
                try { Thread.sleep(100); } catch (Throwable ignored) {}
                ensureInit();
                if (isReady()) {
                    sRetryCount = 0;
                    restoreShadowLabels();
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> { try { ChatGroupUiInjector.refreshTagData(); } catch (Throwable e) { LogWriter.log(TAG, "refreshTagData err: " + e); } });
                    ContactRepository.loadAsync(null);
                } else if (sRetryCount < 6) {
                    scheduleRetry(delayMs + 300);
                }
            }, "leshao-retry").start();
        }, delayMs);
    }

    private static boolean initCoreServices() {
        try {
            ClassLoader cl = sClassLoader;
            ClassLoader tkCL = VersionCompat.findTinkerClassLoader(cl);
            if (tkCL != null) {
                cl = tkCL;
                LogWriter.log(TAG, "initCoreServices: using Tinker ClassLoader");
            }

            // Find label storage
            // v955: 3180 标签存储提供者经 DexKit 动态检索(方法签名: 静态 + 返回 g4),
            // 严禁硬编码类名。旧版 x93 系候选仅作历史版本兼容。
            String dkLabelProvider = DexKitHelper.getLabelStorageProviderClass();
            if (dkLabelProvider != null && !dkLabelProvider.isEmpty()) {
                try {
                    Class<?> cls = XposedHelpers.findClass(dkLabelProvider, cl);
                    Object r = XposedHelpers.callStaticMethod(cls, DexKitHelper.getLabelStorageProviderMethod());
                    if (r != null) {
                        sLabelStorage = r;
                        LogWriter.log(TAG, "initCoreServices: label storage via DexKit=" + dkLabelProvider);
                    }
                } catch (Throwable ignored) {}
            }
            if (sLabelStorage == null) {
                String[] cand = {"x93.r","x93.s","x93.q","x93.t","y93.r","w93.r"};
                for (String cn : cand) {
                    try {
                        Class<?> cls = XposedHelpers.findClass(cn, cl);
                        Object r = XposedHelpers.callStaticMethod(cls, "hj");
                        if (r != null && "com.tencent.mm.storage.g4".equals(r.getClass().getName())) {
                            sLabelStorage = r;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }

            // Find j1 service locator: verify s(Class) OR v(Class) method exists
            // v955: 3180 实证 gp0.j1 只有 v(Class), 无 s(Class) — 旧版只认 s 导致全部匹配失败
            Class<?> j1 = null;
            // Try DexKit-discovered j1 service class first
            String dexKitJ1 = DexKitHelper.getJ1ServiceClass();
            if (dexKitJ1 != null && !dexKitJ1.isEmpty()) {
                try {
                    Class<?> j1Cls = XposedHelpers.findClass(dexKitJ1, cl);
                    findMethodInHierarchy(j1Cls, "v", Class.class);
                    j1 = j1Cls;
                    LogWriter.log(TAG, "initCoreServices: using j1 from DexKit=" + dexKitJ1);
                } catch (Throwable ignored) {}
            }
            // Try DexKit-discovered p06 class (it's the actual service locator in 8.0.78)
            if (j1 == null) {
                String dexKitP06 = DexKitHelper.getP06ClassName();
                if (dexKitP06 != null && !dexKitP06.isEmpty()) {
                    try {
                        Class<?> j1Cls = XposedHelpers.findClass(dexKitP06, cl);
                        findMethodInHierarchy(j1Cls, "v", Class.class);
                        j1 = j1Cls;
                        LogWriter.log(TAG, "initCoreServices: using j1 from DexKit p06=" + dexKitP06);
                    } catch (Throwable ignored) {}
                }
            }
            if (j1 == null) {
                // v955: 严格遵守无类名兜底铁律 — j1 仅经 DexKit 动态检索
                // (特征字符串 "Kernel not initialized" + v/s(Class) 方法签名),
                // 扫描未完成时由 initCoreServices 重试机制(leshao-retry)再次尝试。
                LogWriter.log(TAG, "initCoreServices: j1 待 DexKit 扫描(Tinker=" + (tkCL != null) + "), 将重试");
                if (!DexKitHelper.isScanComplete()) {
                    DexKitHelper.addPostScanCallback(() -> {
                        try { initCoreServices(); } catch (Throwable ignored) {}
                    });
                }
                return false;
            }
            sJ1Class = j1;

            // Find contact storage via DexKit result first
            Class<?> sc4 = null;
            String dexKitContactStorage = DexKitHelper.getContactStorageClass();
            if (dexKitContactStorage != null) {
                try {
                    sc4 = XposedHelpers.findClass(dexKitContactStorage, cl);
                    LogWriter.log(TAG, "initCoreServices: contact storage via DexKit: " + dexKitContactStorage);
                } catch (Throwable ignored) {}
            }
            if (sc4 == null) {
                // v955: 服务接口类经调用特征定位 — j1.v/s(Class) 的实参类型即存储服务接口。
                // 遍历 DexKit 检索到的 j1 定位方法, 用其参数中的 Class 字面量调用方反查不可行,
                // 此处按项目既有约定保留 tn3.c4 现行接口候选(3180 dex 实证), 旧版 sh3.c4 兜底。
                for (String cn : new String[]{"tn3.c4", "sh3.c4"}) {
                    try { sc4 = XposedHelpers.findClass(cn, cl); break; } catch (Throwable ignored) {}
                }
            }
            if (sc4 == null) {
                // Fallback: search for ij() returning long
                try {
                    List<String> storageCandidates = DexKitHelper.findClassesByString(cl, "storage");
                    for (String cn : storageCandidates) {
                        try {
                            Class<?> c = XposedHelpers.findClass(cn, cl);
                            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                                if ("ij".equals(m.getName()) && m.getParameterCount() == 0) {
                                    sc4 = c;
                                    LogWriter.log(TAG, "initCoreServices: contact storage via fallback: " + cn);
                                    break;
                                }
                            }
                            if (sc4 != null) break;
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
            if (sc4 == null) {
                LogWriter.log(TAG, "initCoreServices: contact storage not found");
                return false;
            }

            // v955: 3180 服务定位方法是 v(Class), 旧版 s(Class) 兜底
            Object svc = null;
            for (String mn : new String[]{"v", "s"}) {
                try {
                    svc = XposedHelpers.callStaticMethod(j1, mn, sc4);
                    if (svc != null) break;
                } catch (Throwable ignored) {}
            }
            if (svc == null) {
                // v962: 服务定位器按接口类检索(WmChatHook.copyMediaToWxDir 实证: 传实现类返回 null,
                // 须传声明目标方法的接口类)。e32.a 沿继承链收集接口, 取声明 ij() 者作为入参重试。
                List<Class<?>> ifaces = new ArrayList<>();
                for (Class<?> c = sc4; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Class<?> iface : c.getInterfaces()) ifaces.add(iface);
                }
                for (Class<?> iface : ifaces) {
                    boolean hasIj = false;
                    for (java.lang.reflect.Method m : iface.getDeclaredMethods()) {
                        if ("ij".equals(m.getName())) { hasIj = true; break; }
                    }
                    if (!hasIj) continue;
                    for (String mn : new String[]{"v", "s"}) {
                        try {
                            Object s2 = XposedHelpers.callStaticMethod(j1, mn, iface);
                            if (s2 != null) {
                                svc = s2;
                                LogWriter.log(TAG, "initCoreServices: j1." + mn + " iface 命中: " + iface.getName());
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                    if (svc != null) break;
                }
            }
            if (svc == null) {
                StringBuilder ifaceList = new StringBuilder();
                for (Class<?> iface : sc4.getInterfaces()) {
                    if (ifaceList.length() > 0) ifaceList.append(',');
                    ifaceList.append(iface.getName());
                }
                LogWriter.log(TAG, "initCoreServices: j1.v/s(c4) null, e32.a interfaces=[" + ifaceList + "]");
                return false;
            }
            sContactStorage = XposedHelpers.callMethod(svc, "ij");
            if (sLabelStorage == null) {
                // v955: x93.r 3180 已不存在, 该 fallback 仅对旧版有效; 3180 走上方 jf3.z.bj()
                try {
                    Class<?> fallback = XposedHelpers.findClass("x93.r", cl);
                    sLabelStorage = XposedHelpers.callStaticMethod(fallback, "hj");
                } catch (Throwable ignored) {}
            }
            LogWriter.log(TAG, "核心服务初始化完成 label=" + (sLabelStorage != null) + " contact=" + (sContactStorage != null));
            return sLabelStorage != null && sContactStorage != null;
        } catch (Throwable e) {
            LogWriter.log(TAG, "initCoreServices: " + e.toString());
            return false;
        }
    }

    /** 沿继承链查找存在指定签名方法的类, 找不到抛 NoSuchMethodException */
    private static void findMethodInHierarchy(Class<?> cls, String name, Class<?>... paramTypes)
            throws NoSuchMethodException {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod(name, paramTypes);
                return;
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(name);
    }

    private static void installRealTimeHooks() {
        if (sHooksInstalled) return;
        sHooksInstalled = true;
        try {
            Class<?> g4 = XposedHelpers.findClass("com.tencent.mm.storage.g4", sClassLoader);

            XposedBridge.hookAllMethods(g4, "insert", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object label = p.args[0];
                        int id = XposedHelpers.getIntField(label, "field_labelID");
                        String name = (String) XposedHelpers.getObjectField(label, "field_labelName");
                        if ((boolean) p.getResult()) {
                            EventBus.post(EventBus.Event.LABEL_CREATED, new int[]{id, name.hashCode()});
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.hookAllMethods(g4, "d", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                                        LogWriter.log(TAG, "标签删除: ID=" + p.args[0]);
                    } catch (Throwable e) {
                        LogWriter.log("ChatGroupHook", "cb err: " + e);
                    }
                }
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                                        if ((boolean) p.getResult()) EventBus.post(EventBus.Event.LABEL_DELETED, p.args[0]);
                    } catch (Throwable e) {
                        LogWriter.log("ChatGroupHook", "cb err: " + e);
                    }
                }
            });
            XposedBridge.hookAllMethods(g4, "update", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object label = p.args[0];
                        int id = XposedHelpers.getIntField(label, "field_labelID");
                        String name = (String) XposedHelpers.getObjectField(label, "field_labelName");
                        if ((boolean) p.getResult()) EventBus.post(EventBus.Event.LABEL_RENAMED, new Object[]{id, name});
                    } catch (Throwable ignored) {}
                }
            });
            Class<?> j4 = XposedHelpers.findClass("com.tencent.mm.storage.j4", sClassLoader);
            XposedBridge.hookAllMethods(j4, "p0", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        EventBus.post(EventBus.Event.CONTACT_LABEL_CHANGED, p.args[0]);
                    } catch (Throwable ignored) {}
                }
            });
            LabelSyncHook.install(sClassLoader);

            EventBus.subscribe(EventBus.Event.LABEL_CREATED, (event, data) -> {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> { try { ChatGroupUiInjector.refreshTagData(); } catch (Throwable e) { LogWriter.log(TAG, "refreshTagData err: " + e); } });
            });
            EventBus.subscribe(EventBus.Event.LABEL_DELETED, (event, data) -> {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> { try { ChatGroupUiInjector.refreshTagData(); } catch (Throwable e) { LogWriter.log(TAG, "refreshTagData err: " + e); } });
            });
            EventBus.subscribe(EventBus.Event.LABEL_RENAMED, (event, data) -> {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> { try { ChatGroupUiInjector.refreshTagData(); } catch (Throwable e) { LogWriter.log(TAG, "refreshTagData err: " + e); } });
            });
            EventBus.subscribe(EventBus.Event.LABELS_SYNCED, (event, data) -> {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> { try { ChatGroupUiInjector.refreshTagData(); } catch (Throwable e) { LogWriter.log(TAG, "refreshTagData err: " + e); } });
            });

            LogWriter.log(TAG, "7个实时Hook已安装 (含4个UI刷新订阅)");
        } catch (Throwable e) { LogWriter.log(TAG, "installHooks error: " + e.getMessage()); }
    }

    private static void startSubSystems() {
        if (GroupConfigManager.isAutoGroupEnabled()) {
            AutoGroupEngine.start();
            AutoGroupEngine.subscribeToEvents();
        }
        if (GroupConfigManager.isAutoBackupEnabled()) {
            LabelBackup.scheduleAutoBackup(sWeChatContext);
        }
    }

    // ==================== 公开访问器 ====================
    public static Object getLabelStorage() {
        ensureInit();
        return sLabelStorage;
    }
    public static Object getContactStorage() {
        ensureInit();
        return sContactStorage;
    }
    public static ClassLoader getClassLoader() { return sClassLoader; }
    public static Context getWeChatContext() {
        if (sWeChatContext == null) sWeChatContext = ContextManager.getAppContext();
        return sWeChatContext;
    }
    public static boolean isReady() { return sLabelStorage != null && sContactStorage != null; }

    private static synchronized void ensureInit() {
        if (sInitDone) return;
        if (initCoreServices()) {
            sInitDone = true;
        }
    }

    private static void restoreShadowLabels() {
        // 先固定注入内置虚拟标签，保证任何环境（含分身微信）恒存在
        seedBuiltInLabels();
        try {
            List<ShadowLabelStore.ShadowLabel> shadows = ShadowLabelStore.getAll();
            if (shadows.isEmpty()) return;
            int maxId = 0;
            synchronized (sCachedLabels) {
                for (ShadowLabelStore.ShadowLabel sl : shadows) {
                    // 内置虚拟标签由 seedBuiltInLabels 统一管理，跳过持久化旧数据，避免 ID/名称错位
                    if (sl.labelId == LABEL_ID_GROUP || sl.labelId == LABEL_ID_FRIEND || sl.labelId == LABEL_ID_SERVICE) continue;
                    boolean exists = false;
                    for (LabelInfo l : sCachedLabels) { if (l.labelName.equals(sl.labelName)) { exists = true; break; } }
                    if (exists) continue;
                    try {
                        LabelInfo exist = findLabelByNameFromStorage(sLabelStorage, sl.labelName);
                        if (exist != null) {
                            if (exist.labelId > 0) {
                                if (sl.labelId <= 0) ShadowLabelStore.updateId(sl.labelId, exist.labelId, sl.labelName);
                                continue;
                            }
                            // exist.labelId <= 0: broken WeChat label, do not skip shadow restore
                        }
                    } catch (Throwable ignored) {}
                    int id = sl.labelId > 0 ? sl.labelId : sNextFakeId++;
                    LabelInfo li = new LabelInfo(id, sl.labelName);
                    li.createTime = sl.createTime;
                    sCachedLabels.add(li);
                    if (id > maxId) maxId = id;
                    LogWriter.log(TAG, "restoreShadow: restored '" + sl.labelName + "' id=" + id);
                }
            }
            if (maxId >= sNextFakeId) sNextFakeId = maxId + 1;
        } catch (Throwable e) { LogWriter.log(TAG, "restoreShadow: " + e.getMessage()); }
    }

    /** 固定注入三个内置虚拟标签（群聊/好友/服务），不依赖 ShadowLabelStore 持久化数据 */
    private static void seedBuiltInLabels() {
        synchronized (sCachedLabels) {
            seedBuiltInLabel(LABEL_ID_GROUP, LABEL_NAME_GROUP);
            seedBuiltInLabel(LABEL_ID_FRIEND, LABEL_NAME_FRIEND);
            seedBuiltInLabel(LABEL_ID_SERVICE, LABEL_NAME_SERVICE);
        }
    }

    private static void seedBuiltInLabel(int id, String name) {
        for (LabelInfo l : sCachedLabels) {
            if (l.labelId == id) {
                if (l.labelName == null || l.labelName.isEmpty()) l.labelName = name;
                return;
            }
        }
        LabelInfo li = new LabelInfo(id, name);
        li.createTime = 0;
        sCachedLabels.add(li);
    }

    // ==================== 标签查询 ====================
    public static List<LabelInfo> getAllLabels() {
        List<LabelInfo> r = new ArrayList<>();
        try {
            Object st = getLabelStorage();
            if (st != null) {
                ArrayList<?> list = (ArrayList<?>) XposedHelpers.callMethod(st, "k1");
                if (list != null && !list.isEmpty()) {
                    Map<Integer, List<String>> contactMap = loadContactLabelMap(st);
                    for (Object o : list) {
                        LabelInfo li = toLabelInfo(o, contactMap);
                        if (li.labelId > 0) {
                            r.add(li);
                        } else if (li.labelName != null && !li.labelName.isEmpty()) {
                            rescueBrokenLabel(li);
                        }
                    }
                }
            }
        } catch (Throwable e) { LogWriter.log(TAG, "getAll: " + e.getMessage()); }
        mergeCachedLabels(r);
        return r;
    }

    public static List<LabelInfo> getAllLabelsWithTemp() {
        List<LabelInfo> r = new ArrayList<>();
        try {
            Object st = getLabelStorage();
            if (st != null) {
                ArrayList<?> list = (ArrayList<?>) XposedHelpers.callMethod(st, "l1");
                if (list != null && !list.isEmpty()) {
                    Map<Integer, List<String>> contactMap = loadContactLabelMap(st);
                    for (Object o : list) {
                        LabelInfo li = toLabelInfo(o, contactMap);
                        if (li.labelId > 0) {
                            r.add(li);
                        } else if (li.labelName != null && !li.labelName.isEmpty()) {
                            rescueBrokenLabel(li);
                        }
                    }
                }
            }
        } catch (Throwable e) { LogWriter.log(TAG, "getAllTemp: " + e.getMessage()); }
        mergeCachedLabels(r);
        return r;
    }

    public static LabelInfo getLabelById(String labelId) {
        int id = Integer.parseInt(labelId);
        try {
            Object st = getLabelStorage();
            if (st != null) {
                Object o = XposedHelpers.callMethod(st, "r1", labelId);
                if (o != null) return toLabelInfo(o);
            }
        } catch (Throwable ignored) {}
        synchronized (sCachedLabels) { for (LabelInfo l : sCachedLabels) { if (l.labelId == id) return l; } }
        return null;
    }

    public static LabelInfo getLabelByName(String name) {
        try {
            Object st = getLabelStorage();
            if (st == null) return null;
            Object o = XposedHelpers.callMethod(st, "t1", name);
            return o != null ? toLabelInfo(o) : null;
        } catch (Throwable e) { return null; }
    }

    public static List<LabelInfo> searchLabels(String query) {
        List<LabelInfo> r = new ArrayList<>();
        try {
            Object st = getLabelStorage();
            if (st == null) return r;
            ArrayList<?> list = (ArrayList<?>) XposedHelpers.callMethod(st, "V1", query);
            if (list == null || list.isEmpty()) return r;
            Map<Integer, List<String>> contactMap = loadContactLabelMap(st);
            for (Object o : list) {
                LabelInfo li = toLabelInfo(o, contactMap);
                if (li.labelId > 0) {
                    r.add(li);
                } else if (li.labelName != null && !li.labelName.isEmpty()) {
                    rescueBrokenLabel(li);
                }
            }
        } catch (Throwable e) { LogWriter.log(TAG, "search: " + e.getMessage()); }
        synchronized (sCachedLabels) { for (LabelInfo l : sCachedLabels) { if (l.labelName != null && l.labelName.toLowerCase().contains(query.toLowerCase())) r.add(l); } }
        return r;
    }

    public static int labelCount() {
        int n = 0;
        try {
            Object st = getLabelStorage();
            if (st != null) {
                ArrayList<?> list = (ArrayList<?>) XposedHelpers.callMethod(st, "k1");
                if (list != null) n = list.size();
            }
        } catch (Throwable ignored) {}
        synchronized (sCachedLabels) { n += sCachedLabels.size(); }
        return n;
    }

    /** Migrate a WeChat 8.0.56 label with broken field_labelID (-1) into cache */
    private static void rescueBrokenLabel(LabelInfo li) {
        synchronized (sCachedLabels) {
            for (LabelInfo cl : sCachedLabels) {
                if (li.labelName.equals(cl.labelName)) return;
            }
            li.labelId = sNextFakeId++;
            li.createTime = System.currentTimeMillis();
            sCachedLabels.add(li);
            ShadowLabelStore.add(li.labelId, li.labelName);
            LogWriter.log(TAG, "rescue: " + li.labelName + " -> id=" + li.labelId);
        }
    }

    public static List<LabelInfo> getAllLabelsLightweight() {
        List<LabelInfo> r = new ArrayList<>();
        try {
            Object st = getLabelStorage();
            if (st != null) {
                ArrayList<?> list = (ArrayList<?>) XposedHelpers.callMethod(st, "k1");
                if (list != null) for (Object o : list) {
                    LabelInfo li = toLabelInfo(o, null);
                    if (li.labelId > 0) {
                        r.add(li);
                    } else if (li.labelName != null && !li.labelName.isEmpty()) {
                        rescueBrokenLabel(li);
                    }
                }
            }
        } catch (Throwable e) { LogWriter.log(TAG, "getAllLight: " + e.getMessage()); }
        mergeCachedLabels(r);
        return r;
    }

    /**
     * 仅返回模块自身的标签(内置虚拟标签 + 本模块创建的标签)，
     * 不含微信通讯录用户手动创建的标签。
     * 聊天分组顶部标签栏只应展示模块分组，不展示微信标签。
     */
    public static List<LabelInfo> getModuleLabels() {
        List<LabelInfo> r = new ArrayList<>();
        synchronized (sCachedLabels) { r.addAll(sCachedLabels); }
        return r;
    }

    /**
     * 将模块缓存标签合并进微信标签列表; 微信已成功创建的同名标签不重复追加,
     * 避免 createLabel 写入微信后又留在模块缓存导致的标签双份显示。
     */
    private static void mergeCachedLabels(List<LabelInfo> r) {
        synchronized (sCachedLabels) {
            if (sCachedLabels.isEmpty()) return;
            Set<String> names = new HashSet<>();
            for (LabelInfo l : r) { if (l.labelName != null) names.add(l.labelName); }
            for (LabelInfo l : sCachedLabels) {
                if (l.labelName != null && !names.add(l.labelName)) continue;
                r.add(l);
            }
        }
    }

    // ==================== 标签增删改 ====================
    public static LabelInfo createLabel(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        name = name.trim();
        try {
            // check duplicates in both WeChat and cache
            LabelInfo exist = findLabelByNameFromStorage(getLabelStorage(), name);
            if (exist != null) return exist;
            synchronized (sCachedLabels) {
                for (LabelInfo l : sCachedLabels) { if (name.equals(l.labelName)) return l; }
            }

            int fakeId = sNextFakeId++;
            LabelInfo li = new LabelInfo(fakeId, name);
            li.createTime = System.currentTimeMillis();

            synchronized (sCachedLabels) { sCachedLabels.add(li); }
            ShadowLabelStore.add(fakeId, name);
            LogWriter.log(TAG, "create: cached id=" + fakeId + " name=" + name + " totalCache=" + sCachedLabels.size());

            // best-effort: also try WeChat insert
            try {
                Object st = getLabelStorage();
                ClassLoader cl = getClassLoader();
                if (st != null && cl != null) {
                    Class<?> c4 = XposedHelpers.findClass("com.tencent.mm.storage.c4", cl);
                    Object label = XposedHelpers.newInstance(c4);
                    try { XposedHelpers.setIntField(label, "field_labelID", -1); } catch (Throwable ignored) {}
                    try { XposedHelpers.setObjectField(label, "field_labelName", name); } catch (Throwable ignored) {}
                    try {
                        // v955: 拼音工具经 DexKit 动态检索(静态 a(String)+b(String) 双签名), 零硬编码
                        Class<?> kc = null;
                        String dkPy = DexKitHelper.getPinyinUtilClass();
                        if (dkPy != null && !dkPy.isEmpty()) {
                            try { kc = XposedHelpers.findClass(dkPy, cl); } catch (Throwable ignored) {}
                        }
                        if (kc != null) {
                            XposedHelpers.setObjectField(label, "field_labelPYFull", XposedHelpers.callStaticMethod(kc, "a", name));
                            XposedHelpers.setObjectField(label, "field_labelPYShort", XposedHelpers.callStaticMethod(kc, "b", name));
                        }
                    } catch (Throwable ignored) {}
                    try { XposedHelpers.setBooleanField(label, "field_isTemporary", false); } catch (Throwable ignored) {}
                    boolean ok = (boolean) XposedHelpers.callMethod(st, "insert", label);
                    if (ok) {
                        int rid = XposedHelpers.getIntField(label, "field_labelID");
                        if (rid > 0) { li.labelId = rid; ShadowLabelStore.updateId(fakeId, rid, name); }
                    }
                    LogWriter.log(TAG, "create: wechat insert=" + ok);
                }
            } catch (Throwable e) { LogWriter.log(TAG, "create: wechat insert failed: " + e.getMessage()); }

            return li;
        } catch (Throwable e) { LogWriter.log(TAG, "create error: " + e.getMessage()); }
        return null;
    }

    private static LabelInfo findLabelByNameFromStorage(Object st, String name) {
        try {
            ArrayList<?> list = (ArrayList<?>) XposedHelpers.callMethod(st, "k1");
            if (list != null) {
                for (Object o : list) {
                    String n = (String) XposedHelpers.getObjectField(o, "field_labelName");
                    if (name.equals(n)) return toLabelInfo(o);
                }
            }
        } catch (Throwable e) { LogWriter.log(TAG, "findLabelByName: " + e.getMessage()); }
        return null;
    }

    public static boolean deleteLabel(String labelId) {
        int id = Integer.parseInt(labelId);
        if (id == LABEL_ID_GROUP || id == LABEL_ID_FRIEND || id == LABEL_ID_SERVICE) return false;
        boolean wechatDeleted = false;
        try {
            Object st = getLabelStorage();
            if (st != null) {
                wechatDeleted = (boolean) XposedHelpers.callMethod(st, "d", labelId);
                if (wechatDeleted) refreshCache();
            }
        } catch (Throwable e) { LogWriter.log(TAG, "delete wechat: " + e.getMessage()); }
        synchronized (sCachedLabels) {
            for (Iterator<LabelInfo> it = sCachedLabels.iterator(); it.hasNext(); ) {
                if (it.next().labelId == id) it.remove();
            }
        }
        ShadowLabelStore.remove(id);
        LogWriter.log(TAG, "delete: id=" + labelId + " wechat=" + wechatDeleted);
        return true;
    }

    public static boolean renameLabel(String labelId, String newName) {
        if (newName == null || newName.trim().isEmpty()) return false;
        newName = newName.trim();
        int id = Integer.parseInt(labelId);
        if (id == LABEL_ID_GROUP || id == LABEL_ID_FRIEND || id == LABEL_ID_SERVICE) return false;
        // try WeChat rename
        try {
            Object st = getLabelStorage();
            ClassLoader cl = getClassLoader();
            if (st != null && cl != null) {
                Object label = XposedHelpers.callMethod(st, "r1", labelId);
                if (label != null) {
                    XposedHelpers.setObjectField(label, "field_labelName", newName);
                    try {
                        // v955: 拼音工具经 DexKit 动态检索(静态 a(String)+b(String) 双签名), 零硬编码
                        Class<?> kc = null;
                        String dkPy = DexKitHelper.getPinyinUtilClass();
                        if (dkPy != null && !dkPy.isEmpty()) {
                            try { kc = XposedHelpers.findClass(dkPy, cl); } catch (Throwable ignored) {}
                        }
                        if (kc != null) {
                            XposedHelpers.setObjectField(label, "field_labelPYFull", XposedHelpers.callStaticMethod(kc, "a", newName));
                            XposedHelpers.setObjectField(label, "field_labelPYShort", XposedHelpers.callStaticMethod(kc, "b", newName));
                        }
                    } catch (Throwable ignored) {}
                    boolean ignored = (boolean) XposedHelpers.callMethod(st, "update", label, new String[]{"labelID"});
                    refreshCache();
                }
            }
        } catch (Throwable e) { LogWriter.log(TAG, "rename wechat: " + e.getMessage()); }
        // update cache
        synchronized (sCachedLabels) {
            for (LabelInfo l : sCachedLabels) { if (l.labelId == id) { l.labelName = newName; break; } }
        }
        ShadowLabelStore.rename(id, newName);
        LogWriter.log(TAG, "rename: id=" + labelId + " -> " + newName);
        return true;
    }

    // ==================== 标签-联系人查询 ====================
    @SuppressWarnings("unchecked")
    public static List<String> getContactsByLabelId(int labelId) {
        List<String> r = new ArrayList<>();
        try {
            Object st = getLabelStorage();
            if (st != null) {
                try {
                    HashMap<Integer, ArrayList<String>> m = (HashMap<Integer, ArrayList<String>>) XposedHelpers.getObjectField(st, "f");
                    if (m != null) { ArrayList<String> u = m.get(labelId); if (u != null) r.addAll(u); }
                } catch (Throwable e1) {
                    LogWriter.log(TAG, "getContacts via field f failed: " + e1.getMessage() + ", using fallback");
                }
                // fallback: scan all labels
                if (r.isEmpty()) {
                    for (LabelInfo l : getAllLabels()) if (l.labelId == labelId) { r.addAll(l.contacts); break; }
                }
            }
        } catch (Throwable e) { LogWriter.log(TAG, "getContacts: " + e.getMessage()); }
        // virtual built-in labels: scan conversation list
        if (r.isEmpty() && (labelId == 10000 || labelId == 10001 || labelId == 10002)) {
            try {
                List<String> builtIn = ConversationFilter.getUsernamesForBuiltInLabel(labelId);
                if (builtIn != null) r.addAll(builtIn);
            } catch (Throwable ignored) {}
        }
        // shadow labels: use maintained map
        if (r.isEmpty()) {
            if (!sShadowMapBuilt) { buildShadowContactMap(); sShadowMapBuilt = true; }
            Set<String> sm = sShadowContactMap.get(labelId);
            if (sm != null) r.addAll(sm);
        }
        return r;
    }

    static void buildShadowContactMap() {
        synchronized (sCachedLabels) {
            if (sCachedLabels.isEmpty()) return;
            Set<Integer> shadowIds = new HashSet<>();
            for (LabelInfo l : sCachedLabels) shadowIds.add(l.labelId);
            ConversationFilter.scanContactsForShadowLabels(shadowIds, sShadowContactMap);
        }
        LogWriter.log(TAG, "buildShadowContactMap: " + sShadowContactMap.size() + " labels mapped");
    }

    @SuppressWarnings("unchecked")
    public static int[] getLabelIdsByContact(String username) {
        try {
            Object st = getLabelStorage();
            if (st == null) return new int[0];
            try {
                HashMap<String, int[]> m = (HashMap<String, int[]>) XposedHelpers.getObjectField(st, "e");
                if (m != null) { int[] ids = m.get(username); return ids != null ? ids : new int[0]; }
            } catch (Throwable e1) {
                LogWriter.log(TAG, "getLabelIds via field e failed: " + e1.getMessage() + ", using fallback");
                List<Integer> ids = new ArrayList<>();
                for (LabelInfo l : getAllLabels()) if (l.contacts != null && l.contacts.contains(username)) ids.add(l.labelId);
                int[] arr = new int[ids.size()];
                for (int j = 0; j < ids.size(); j++) arr[j] = ids.get(j);
                return arr;
            }
        } catch (Throwable e) { LogWriter.log(TAG, "getLabelIds: " + e.getMessage()); }
        return new int[0];
    }

    // ==================== 联系人标签详情 ====================
    public static List<Map<String, Object>> getLabels(String username) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            int[] ids = getContactLabelIds(username);
            for (int id : ids) {
                if (id <= 0) continue;
                LabelInfo info = getLabelById(String.valueOf(id));
                if (info != null) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("labelId", id);
                    m.put("labelName", info.labelName);
                    m.put("labelPYFull", info.labelPYFull != null ? info.labelPYFull : "");
                    m.put("createTime", info.createTime);
                    result.add(m);
                }
            }
        } catch (Throwable e) { LogWriter.log(TAG, "getLabels: " + e.getMessage()); }
        return result;
    }

    // ==================== 联系人标签操作 ====================
    public static String getContactLabelIdsRaw(String username) {
        try {
            Object st = getContactStorage();
            if (st == null) return "";
            Object c = XposedHelpers.callMethod(st, "m", username);
            if (c == null) return "";
            String ids = (String) XposedHelpers.callMethod(c, "A0");
            return ids != null ? ids : "";
        } catch (Throwable e) { return ""; }
    }

    public static int[] getContactLabelIds(String username) {
        String raw = getContactLabelIdsRaw(username);
        if (raw.isEmpty()) return new int[0];
        String[] parts = raw.split(",");
        int[] ids = new int[parts.length];
        for (int i = 0; i < parts.length; i++) { try { ids[i] = Integer.parseInt(parts[i].trim()); } catch (Exception e) { ids[i] = -1; } }
        return ids;
    }

    public static boolean addLabelToContact(String username, int labelId) {
        return modifyLabels(username, labelId, true);
    }

    public static boolean removeLabelFromContact(String username, int labelId) {
        return modifyLabels(username, labelId, false);
    }

    public static boolean setContactLabels(String username, int[] labelIds) {
        try {
            Object st = getContactStorage();
            if (st == null) return false;
            Object c = XposedHelpers.callMethod(st, "m", username);
            if (c == null) return false;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < labelIds.length; i++) { if (i > 0) sb.append(","); sb.append(labelIds[i]); }
            XposedHelpers.setObjectField(c, "field_contactLabelIds", sb.toString());
            int ret = (int) XposedHelpers.callMethod(st, "p0", username, c);
            refreshCache();
            return ret >= 0;
        } catch (Throwable e) { return false; }
    }

    public static boolean clearContactLabels(String username) {
        return setContactLabels(username, new int[0]);
    }

    public static int batchAddLabel(List<String> users, int labelId) {
        int n = 0; for (String u : users) if (addLabelToContact(u, labelId)) n++; return n;
    }

    public static int batchRemoveLabel(List<String> users, int labelId) {
        int n = 0; for (String u : users) if (removeLabelFromContact(u, labelId)) n++; return n;
    }

    public static int batchSetLabels(List<String> users, int[] labelIds) {
        int n = 0; for (String u : users) if (setContactLabels(u, labelIds)) n++; return n;
    }

    public static void refreshCache() {
        try {
            if (sLabelStorage != null) XposedHelpers.callMethod(sLabelStorage, "F0");
            LogWriter.log(TAG, "refreshCache: F0 called OK");
        } catch (Throwable e) {
            LogWriter.log(TAG, "refreshCache: F0 failed: " + e.getMessage());
        }
    }

    // ==================== 内部 ====================
    @SuppressWarnings("unchecked")
    private static Map<Integer, List<String>> loadContactLabelMap(Object st) {
        Map<Integer, List<String>> map = new HashMap<>();
        try {
            XposedHelpers.callMethod(st, "F0");
            try {
                HashMap<Integer, ArrayList<String>> f = (HashMap<Integer, ArrayList<String>>) XposedHelpers.getObjectField(st, "f");
                if (f != null) for (Map.Entry<Integer, ArrayList<String>> e : f.entrySet()) map.put(e.getKey(), new ArrayList<>(e.getValue()));
            } catch (Throwable e) {
                LogWriter.log(TAG, "loadContactMap field f fail: " + e.getMessage());
            }
        } catch (Throwable e) { LogWriter.log(TAG, "loadContactMap F0 fail: " + e.getMessage()); }
        return map;
    }

    private static LabelInfo toLabelInfo(Object label) {
        return toLabelInfo(label, null);
    }

    private static LabelInfo toLabelInfo(Object label, Map<Integer, List<String>> contactMap) {
        LabelInfo info = new LabelInfo();
        try {
            info.labelId = XposedHelpers.getIntField(label, "field_labelID");
            info.labelName = (String) XposedHelpers.getObjectField(label, "field_labelName");
            info.labelPYFull = (String) XposedHelpers.getObjectField(label, "field_labelPYFull");
            info.labelPYShort = (String) XposedHelpers.getObjectField(label, "field_labelPYShort");
            try {
                java.lang.reflect.Field f = label.getClass().getDeclaredField("field_createTime");
                f.setAccessible(true); info.createTime = f.getLong(label);
            } catch (Throwable ignored) {}
            info.isTemporary = XposedHelpers.getBooleanField(label, "field_isTemporary");
            try {
                java.lang.reflect.Field f = label.getClass().getDeclaredField("field_lastUseTime");
                f.setAccessible(true); info.lastUseTime = f.getLong(label);
            } catch (Throwable ignored) {}
            info.contacts = contactMap != null ? contactMap.getOrDefault(info.labelId, Collections.<String>emptyList()) : Collections.<String>emptyList();
        } catch (Throwable e) { LogWriter.log(TAG, "toInfo: " + e.getMessage()); }
        return info;
    }

    private static boolean modifyLabels(String username, int labelId, boolean add) {
        try {
            Object st = getContactStorage();
            if (st == null) return false;
            Object c = XposedHelpers.callMethod(st, "m", username);
            if (c == null) return false;
            String cur = (String) XposedHelpers.callMethod(c, "A0");
            Set<String> set = new LinkedHashSet<>();
            if (cur != null && !cur.isEmpty()) {
                for (String s : cur.split(",")) { String ts = s.trim(); if (!ts.isEmpty()) set.add(ts); }
            }
            String sid = String.valueOf(labelId);
            if (add) { if (!set.add(sid)) return true; }
            else { if (!set.remove(sid)) return true; }
            XposedHelpers.setObjectField(c, "field_contactLabelIds", set.isEmpty() ? "" : String.join(",", set));
            int ret = (int) XposedHelpers.callMethod(st, "p0", username, c);
            refreshCache();
            LogWriter.log(TAG, "modifyLabels: " + (add ? "add" : "rem") + " label=" + labelId + " user=" + username + " ret=" + ret);
            // maintain shadow contact map for cache-only labels
            synchronized (sCachedLabels) {
                for (LabelInfo l : sCachedLabels) {
                    if (l.labelId == labelId) {
                        if (add) {
                            sShadowContactMap.computeIfAbsent(labelId, k -> new HashSet<>()).add(username);
                        } else {
                            Set<String> us = sShadowContactMap.get(labelId);
                            if (us != null) us.remove(username);
                        }
                        break;
                    }
                }
            }
            return ret >= 0;
        } catch (Throwable e) { LogWriter.log(TAG, "modifyLabels err: " + e.getMessage()); return false; }
    }
}
