package com.leshao.v3.hook;

import android.os.Handler;
import android.os.Looper;


import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.KeywordRule;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.service.TTSBroadcaster;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class MessageHook {

    private static final String TAG = "MessageHook";
    private static int sCount = 0;
    private static Handler sMainHandler;
    private static ClassLoader sClassLoader;
    // 插入序去重集合: LinkedHashMap 保证迭代顺序=插入顺序, removeEldestEntry 淘汰最旧,
    // 修复此前 ConcurrentHashMap 无序却按迭代顺序淘汰的问题。
    private static final Set<Long> sSeenMsgIds =
            Collections.newSetFromMap(new LinkedHashMap<Long, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                    return size() > 400;
                }
            });
    // 关键词回复/DB 入库等反射发送放后台, 避免阻塞主线程回调。
    private static final ExecutorService sBgExecutor = Executors.newSingleThreadExecutor();
    // v3.0.577: 已挂过 hook 的类(避免 base/real 双 CL 重复补挂导致回调翻倍)。
    private static final Set<Class<?>> sHookedF9Classes =
            Collections.synchronizedSet(new java.util.LinkedHashSet<Class<?>>());
    private static final Set<Class<?>> sHookedX9Classes =
            Collections.synchronizedSet(new java.util.LinkedHashSet<Class<?>>());
    // 调试开关: IEvent.e(SendMsgSuccessEvent) 仅打印日志, 默认关闭以减少无谓 hook。
    private static final boolean DEBUG_I_EVENT_BUS = false;
    private static final ThreadLocal<Boolean> sConsumedTtsOriginal = new ThreadLocal<>();

    public static void hook(ClassLoader cl) {
        sClassLoader = cl;
        sMainHandler = new Handler(Looper.getMainLooper());

        LogWriter.log(TAG, "=== v56 IEvent.e hook ===");
        android.util.Log.e(TAG, "=== v56 IEvent.e hook ===");

        // Defer message hook discovery until DexKit scan completes
        com.leshao.v3.hook.DexKitHelper.addPostScanCallback(() -> {
            // v3.0.577: 立即用当前 CL 挂一遍(非 Tinker 环境即为真实 CL), 同时在后台等待
            // Tinker 真实 CL(DelegateLastClassLoader) 就绪后再补挂一遍。此前只挂当前 CL,
            // 而启动早期 findTinkerClassLoader 常因 10s 冷却返回 null → 挂到 base.apk
            // 平行副本类上, 运行时消息经真实 CL 的 f9/au.g 分发 → hook 零捕获(TTS 不播报)。
            try { hookMsgStorageInsert(cl); } catch (Throwable e) { LogWriter.log(TAG, "hookMsgStorageInsert(base) err: " + e); }
            try { hookX9Dispatch(cl); } catch (Throwable e) { LogWriter.log(TAG, "hookX9Dispatch(base) err: " + e); }
            scheduleRealClRehook(cl);
            if (DEBUG_I_EVENT_BUS) hookIEventBus(cl);
            LogWriter.log(TAG, "MessageHook post-scan init done");
        });
        hookSensitiveBlock(cl);
    }

    /**
     * v3.0.577: 后台等待 Tinker 真实 CL 就绪后, 用真实 CL 再挂一遍 f9 入库 + x9 分发 hook。
     * 微信 Tinker 热修复下真实类由 DelegateLastClassLoader 加载, 与 base.apk 的 PathClassLoader
     * 是两套平行 Class, 只挂后者运行时零捕获。findTinkerClassLoader 有 10s 冷却, 故循环 ~40s。
     */
    private static void scheduleRealClRehook(final ClassLoader baseCl) {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                ClassLoader real = null;
                try {
                    real = com.leshao.v3.hook.VersionCompat.findTinkerClassLoader(baseCl);
                } catch (Throwable ignored) {
                }
                if (real == null) {
                    try {
                        real = com.leshao.v3.ContextManager.getTinkerClassLoader();
                    } catch (Throwable ignored) {
                    }
                }
                if (real != null && real != baseCl
                        && !real.getClass().getName().contains("Leshao")) {
                    LogWriter.log(TAG, "scheduleRealClRehook: 真实 CL 就绪(try=" + i + ") "
                            + real.getClass().getSimpleName() + ", 补挂 f9/x9");
                    try { hookMsgStorageInsert(real); } catch (Throwable e) { LogWriter.log(TAG, "hookMsgStorageInsert(real) err: " + e); }
                    try { hookX9Dispatch(real); } catch (Throwable e) { LogWriter.log(TAG, "hookX9Dispatch(real) err: " + e); }
                    return;
                }
                try { Thread.sleep(800L); } catch (InterruptedException ignored) { return; }
            }
            LogWriter.log(TAG, "scheduleRealClRehook: 未取得独立真实 CL(非 Tinker 环境?), 保持 base CL hook");
        }, "ls-msghook-realcl");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 8.0.78(3180) 播报主入口: f9.Bb(e9, boolean) = MsgInfoStorage.insertMsgInfo(MsgInfo, boolean)。
     * 这是文档验证过的最稳入库入口(方案A推荐)。
     * <p>
     * v960: 回调时机由 before 改为 <b>after</b> —— before 阶段 e9 实体的
     * field_content 尚未填充(文字/位置/红包 content 全为空, 实测导致文字不播报/
     * 位置未知/红包取不到 nativeUrl), after 阶段入库完成 content 必然已设置。
     */
    private static void hookMsgStorageInsert(ClassLoader cl) {
        try {
            cl = resolveRealCl(cl);
            Class<?> f9 = VersionCompat.findMsgStorageClass(cl);
            if (f9 == null) {
                return;
            }
            // v3.0.577: 同一个 f9 Class 只挂一次, 避免 base/real 重复补挂导致回调翻倍。
            if (!sHookedF9Classes.add(f9)) {
                LogWriter.log(TAG, "hookMsgStorageInsert: 已挂过 " + f9.getName()
                        + " (cl=" + cl.getClass().getSimpleName() + "), 跳过");
                return;
            }
            int hooked = 0;
            // v3.0.467: 8.0.79 中 f9.Bb 已改名（插入入口为 Nb(e9,boolean)→long，Mb(e9) 是入库包装）。
            // 1) 硬编码候选方法名（跨版本小漂移兜底）
            String[] insertNames = {"Nb", "Bb", "Mb", "insertMsgInfo", "insert"};
            java.util.Set<String> hookedNames = new java.util.LinkedHashSet<>();
            for (java.lang.reflect.Method m : f9.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                boolean nameOk = false;
                for (String nm : insertNames) {
                    if (m.getName().equals(nm)) { nameOk = true; break; }
                }
                if (!nameOk) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 1 || pts[0] == null) continue;
                String p0 = pts[0].getName();
                if (!p0.endsWith(".e9") && !p0.contains("MsgInfo")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (p.args.length < 1 || p.args[0] == null) return;
                            onInsertMsgInfo(p.args[0]);
                        } catch (Throwable e) {
                            LogWriter.log("MessageHook", "insertMsgInfo cb err: " + e);
                        }
                    }
                });
                LogWriter.log(TAG, "hooked f9." + m.getName() + "(" + pts.length + " args) p0=" + p0);
                hookedNames.add(m.getName());
                hooked++;
            }
            // 2) DexKit 字符串锚点兜底：方法体含 "MsgInfo processAddMsg insert db error" 的 f9 方法
            if (hooked == 0) {
                try {
                    java.util.List<String> sigs = com.leshao.v3.hook.DexKitHelper.findMethodsByString(cl, null,
                            "MsgInfo processAddMsg insert db error");
                    for (String sig : sigs) {
                        if (sig == null || sig.indexOf('(') <= 0) continue;
                        String head = sig.substring(0, sig.indexOf('('));
                        int dot = head.lastIndexOf('.');
                        if (dot <= 0) continue;
                        String cn = head.substring(0, dot);
                        String mn = head.substring(dot + 1);
                        if (!cn.endsWith("storage.f9")) continue;
                        for (java.lang.reflect.Method m : f9.getDeclaredMethods()) {
                            if (!m.getName().equals(mn)) continue;
                            Class<?>[] pts = m.getParameterTypes();
                            if (pts.length < 1 || pts[0] == null) continue;
                            String p0 = pts[0].getName();
                            if (!p0.endsWith(".e9") && !p0.contains("MsgInfo")) continue;
                            XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override protected void afterHookedMethod(MethodHookParam p) {
                                    try {
                                        if (p.args.length < 1 || p.args[0] == null) return;
                                        onInsertMsgInfo(p.args[0]);
                                    } catch (Throwable e) {
                                        LogWriter.log("MessageHook", "insertMsgInfo cb err: " + e);
                                    }
                                }
                            });
                            hooked++;
                            LogWriter.log(TAG, "hooked f9." + mn + " via string anchor");
                            break;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            LogWriter.log(TAG, "insertMsgInfo hooks installed: " + hooked + " names=" + hookedNames);
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookMsgStorageInsert FAIL: " + t.getMessage());
        }
    }

    // v1042: 微信经 Tinker 热修复时真实类由 DelegateLastClassLoader 加载, lpparam.classLoader
    // 只是 base.apk 平行副本。切换到真实 CL 才能 hook 到运行时实际调用的 f9/e9 类。
    private static ClassLoader resolveRealCl(ClassLoader cl) {
        try {
            ClassLoader tk = VersionCompat.findTinkerClassLoader(cl);
            if (tk != null && !tk.getClass().getName().contains("Leshao") && tk != cl) {
                LogWriter.log(TAG, "使用 Tinker 真实 CL " + tk.getClass().getSimpleName());
                return tk;
            }
        } catch (Throwable ignored) {
        }
        return cl;
    }

    /** f9.Bb 入口: 播报逻辑复用 onX9Message, 靠 msgId/svrId 去重避免与 x9 分发重复 */
    static void onInsertMsgInfo(Object e9) {
        try {
            onX9Message(e9, null, true);
        } catch (Throwable t) {
            LogWriter.log(TAG, "onInsertMsgInfo err: " + t.getMessage());
        }
    }

    /**
     * 敏感词过滤: 在消息入库层 (f9.Ra) 拦截含敏感词的接收消息, 使其不显示在聊天列表。
     * 与 processKeywordAndSensitive 的「不播报」形成双保险: 这里直接阻断消息入库。
     */
    private static void hookSensitiveBlock(ClassLoader cl) {
        try {
            cl = resolveRealCl(cl);
            Class<?> f9 = VersionCompat.findMsgStorageClass(cl);
            if (f9 == null) {
                LogWriter.log(TAG, "sensitive block: f9 storage class not found");
                return;
            }
            // 遍历 f9 所有 Ra 重载，为每个方法单独记录返回类型（避免多重重载类型不一致导致拆箱 NPE），并过滤抽象方法
            java.util.List<java.lang.reflect.Method> raMethods = new java.util.ArrayList<>();
            final java.util.Map<java.lang.reflect.Method, Integer> raRetTypes = new java.util.HashMap<>();
            try {
                for (java.lang.reflect.Method m : f9.getDeclaredMethods()) {
                    if (!m.getName().equals("Ra")) continue;
                    if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                    Class<?> rt = m.getReturnType();
                    int t = rt == boolean.class ? 1 : (rt == int.class ? 2 : (rt == long.class ? 3 : 0));
                    raMethods.add(m);
                    raRetTypes.put(m, t);
                }
            } catch (Throwable ignored) {}
            for (final java.lang.reflect.Method raM : raMethods) {
                final int rtType = raRetTypes.get(raM);
                XposedBridge.hookMethod(raM, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length < 2 || param.args[1] == null) return;
                            ModuleConfig cfg = ModuleConfig.load(ContextManager.getPrefs());
                            if (cfg == null || !cfg.sensitiveFilterEnabled
                                    || cfg.sensitiveWords == null || cfg.sensitiveWords.isEmpty()) return;

                            Object msg = param.args[1];
                            // 只过滤接收消息
                            int isSend = -1;
                            try { isSend = (Integer) XposedHelpers.callMethod(msg, "z0"); }
                            catch (Throwable ignored) {}
                            try {
                                java.lang.reflect.Field f = msg.getClass().getDeclaredField("field_isSend");
                                f.setAccessible(true);
                                Object v = f.get(msg);
                                if (v instanceof Integer) isSend = (Integer) v;
                            } catch (Throwable ignored) {}
                            if (isSend == 1) return;

                            String content = readMsgContent(msg);
                            if (content == null || content.isEmpty()) return;
                            for (String w : cfg.sensitiveWords) {
                                if (w != null && !w.isEmpty() && content.contains(w)) {
                                    LogWriter.log(TAG, "[Sensitive] 拦截敏感词消息入库: " + w
                                            + " msg=" + trunc(content, 40));
                                    // 按当前方法返回类型给安全默认值，避免原始类型拆箱 NPE
                                    if (rtType == 1) param.setResult(false);
                                    else if (rtType == 2) param.setResult(0);
                                    else if (rtType == 3) param.setResult(0L);
                                    else param.setResult(null);
                                    return;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }
            LogWriter.log(TAG, "sensitive block hooks installed (f9.Ra)");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookSensitiveBlock FAIL: " + t.getMessage());
        }
    }

    private static String readMsgContent(Object msg) {
        try {
            java.lang.reflect.Field f = msg.getClass().getDeclaredField("field_content");
            f.setAccessible(true);
            Object v = f.get(msg);
            if (v != null) return v.toString();
        } catch (Throwable ignored) {}
        try {
            Object v = XposedHelpers.callMethod(msg, "getContent");
            if (v != null) return v.toString();
        } catch (Throwable ignored) {}
        try {
            Object v = XposedHelpers.callMethod(msg, "I0");
            if (v != null) return v.toString();
        } catch (Throwable ignored) {}
        // v960 兜底1: 遍历字段(含父类)找名字含 content 的 String 字段(防混淆改名)
        try {
            String v = readContentByFieldScan(msg);
            if (v != null) return v;
        } catch (Throwable ignored) {}
        // v960 兜底2: 混淆方法名 j()(MessageHook DB 重读路径同款)
        try {
            Object v = XposedHelpers.callMethod(msg, "j");
            if (v != null) return v.toString();
        } catch (Throwable ignored) {}
        // v960 兜底3: 首个 XML/文本形态 String 字段(排除 talker 类字段)
        try {
            String v = readContentByHeuristic(msg);
            if (v != null) return v;
        } catch (Throwable ignored) {}
        return null;
    }

    /** v960: 遍历字段(含父类)找名字含 content 的 String 字段。 */
    private static String readContentByFieldScan(Object msg) {
        Class<?> c = msg.getClass();
        while (c != null && !c.equals(Object.class)) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType() == String.class) {
                    String n = f.getName().toLowerCase();
                    if (n.contains("content") || n.equals("msg") || n.equals("text")) {
                        try {
                            f.setAccessible(true);
                            Object v = f.get(msg);
                            if (v != null && !((String) v).isEmpty()) return (String) v;
                        } catch (Throwable ignored) {}
                    }
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** v960: 启发式取 content —— 首个 XML 头或合理长度纯文本 String 字段。 */
    private static String readContentByHeuristic(Object msg) {
        Class<?> c = msg.getClass();
        while (c != null && !c.equals(Object.class)) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                String n = f.getName().toLowerCase();
                if (n.contains("talker") || n.contains("username") || n.contains("wxid")
                        || n.contains("nick") || n.contains("remark") || n.contains("imgsource")
                        || n.contains("msgsource") || n.contains("pushcontent")) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(msg);
                    if (v == null) continue;
                    String s = (String) v;
                    if (s.isEmpty()) continue;
                    if (s.startsWith("<") || (s.length() <= 4096 && !s.contains("="))) return s;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** v960 诊断: dump 消息类全部字段名与值摘要(仅 content 读取失败时调用, 每类只 dump 一次)。 */
    private static final java.util.Set<String> sDumpedClasses =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    static void dumpMsgFields(Object msg) {
        if (msg == null) return;
        String cn = msg.getClass().getName();
        if (!sDumpedClasses.add(cn)) return;
        try {
            StringBuilder sb = new StringBuilder("fields of " + cn + ": ");
            Class<?> c = msg.getClass();
            while (c != null && !c.equals(Object.class)) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(msg);
                        String vs = v == null ? "null" : v.toString();
                        if (vs.length() > 60) vs = vs.substring(0, 60) + "...";
                        sb.append(f.getName()).append("(").append(f.getType().getSimpleName())
                          .append(")=").append(vs).append(" | ");
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
            LogWriter.log("MessageHook", "[DUMP] " + sb);
        } catch (Throwable ignored) {}
    }

    // ====== x9 分发 (接收消息: TTS + 语音播放) ======

    private static void hookX9Dispatch(ClassLoader cl) {
        try {
            // v3.0.577: x9 分发类同样必须用真实 CL(Tinker) 加载, 否则挂在 base.apk 平行副本
            // 的 au.g 上, 运行时经真实 CL 分发 → 零捕获 → TTS 播报/语音自动播放全部失效。
            cl = resolveRealCl(cl);
            Class<?> x9Cls = null;
            // 1) 优先使用全量扫描时持久化的缓存类名, 避免普通重启触发 DexKit 搜索(加载 so)
            String cachedX9 = DexKitHelper.ngetX9Class();
            if (cachedX9 != null && !cachedX9.isEmpty()) {
                try {
                    Class<?> c = cl.loadClass(cachedX9);
                    // 检查是否有接收 e9 类型参数的方法
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length >= 1) {
                            String pt0 = pts[0].getName();
                            if (pt0.equals("com.tencent.mm.storage.e9") || pt0.endsWith(".e9")) {
                                x9Cls = c;
                                LogWriter.log(TAG, "x9 class found via cached X9: " + cachedX9);
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            // 2) 缓存缺失(首次扫描/微信更新后)才用 DexKit 字符串搜索动态发现 x9 类（消息分发类）
            if (x9Cls == null) {
                List<String> candidates = DexKitHelper.findClassesByString(cl, "IEvent");
                for (String cn : candidates) {
                    try {
                        Class<?> c = cl.loadClass(cn);
                        // 检查是否有接收 e9 类型参数的方法
                        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                            Class<?>[] pts = m.getParameterTypes();
                            if (pts.length >= 1) {
                                String pt0 = pts[0].getName();
                                if (pt0.equals("com.tencent.mm.storage.e9") || pt0.endsWith(".e9")) {
                                    x9Cls = c;
                                    LogWriter.log(TAG, "x9 class found via DexKit IEvent: " + cn);
                                    break;
                                }
                            }
                        }
                        if (x9Cls != null) break;
                    } catch (Throwable ignored) {}
                }
            }
            // 3) 兜底：搜索包含 "EventBus" 字符串的类
            if (x9Cls == null) {
                List<String> busCandidates = DexKitHelper.findClassesByString(cl, "EventBus");
                for (String cn : busCandidates) {
                    try {
                        Class<?> c = cl.loadClass(cn);
                        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                            Class<?>[] pts = m.getParameterTypes();
                            if (pts.length >= 1) {
                                String pt0 = pts[0].getName();
                                if (pt0.equals("com.tencent.mm.storage.e9") || pt0.endsWith(".e9")) {
                                    x9Cls = c;
                                    LogWriter.log(TAG, "x9 class found via DexKit EventBus: " + cn);
                                    break;
                                }
                            }
                        }
                        if (x9Cls != null) break;
                    } catch (Throwable ignored) {}
                }
            }
            // 4) 兜底：搜索所有包含 "MsgInfo" 字符串的类（8.0.78 消息存储类特征）
            if (x9Cls == null) {
                List<String> msgCandidates = DexKitHelper.findClassesByString(cl, "MsgInfo");
                for (String cn : msgCandidates) {
                    try {
                        Class<?> c = cl.loadClass(cn);
                        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                            Class<?>[] pts = m.getParameterTypes();
                            if (pts.length >= 1) {
                                String pt0 = pts[0].getName();
                                if (pt0.endsWith(".e9") || pt0.contains("MsgInfo")) {
                                    x9Cls = c;
                                    LogWriter.log(TAG, "x9 class found via DexKit MsgInfo: " + cn);
                                    break;
                                }
                            }
                        }
                        if (x9Cls != null) break;
                    } catch (Throwable ignored) {}
                }
            }
            // 5) 兜底：搜索 "handleMsg" 或 "dispatch" 方法名
            if (x9Cls == null) {
                List<String> dispatchCandidates = DexKitHelper.findMethodsByString(cl, null, "dispatch");
                for (String sig : dispatchCandidates) {
                    try {
                        String cn = sig.substring(0, sig.indexOf('.'));
                        Class<?> c = cl.loadClass(cn);
                        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                            Class<?>[] pts = m.getParameterTypes();
                            if (pts.length >= 1) {
                                String pt0 = pts[0].getName();
                                if (pt0.endsWith(".e9") || pt0.contains("MsgInfo")) {
                                    x9Cls = c;
                                    LogWriter.log(TAG, "x9 class found via dispatch method: " + cn);
                                    break;
                                }
                            }
                        }
                        if (x9Cls != null) break;
                    } catch (Throwable ignored) {}
                }
            }
            // 6) 兜底：搜索 any class with method taking e9/MsgInfo param
            if (x9Cls == null) {
                List<String> e9Candidates = DexKitHelper.findClassesByString(cl, "storage");
                for (String cn : e9Candidates) {
                    try {
                        Class<?> c = cl.loadClass(cn);
                        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                            Class<?>[] pts = m.getParameterTypes();
                            if (pts.length >= 1) {
                                String pt0 = pts[0].getName();
                                if (pt0.endsWith(".e9") || pt0.contains("MsgInfo")) {
                                    x9Cls = c;
                                    LogWriter.log(TAG, "x9 class found via storage search: " + cn);
                                    break;
                                }
                            }
                        }
                        if (x9Cls != null) break;
                    } catch (Throwable ignored) {}
                }
            }
            if (x9Cls == null) {
                LogWriter.log(TAG, "x9 class ALL strategies failed");
                return;
            }
            Class<?> e9Cls = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Cls == null) {
                LogWriter.log(TAG, "e9 class not found");
                return;
            }
            // v3.0.577: 同一个 x9 Class 只挂一次(base/real 双 CL 去重)。
            if (!sHookedX9Classes.add(x9Cls)) {
                LogWriter.log(TAG, "hookX9Dispatch: 已挂过 " + x9Cls.getName()
                        + " (cl=" + cl.getClass().getSimpleName() + "), 跳过");
                return;
            }

            int hooked = 0;
            for (java.lang.reflect.Method m : x9Cls.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length >= 1 && pts[0] == e9Cls) {
                    final int paramCount = pts.length;
                    final Class<?> returnType = m.getReturnType();
                    XposedBridge.hookMethod(m,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                try {
                                                                sConsumedTtsOriginal.set(Boolean.FALSE);
                                                                if (TtsVoiceSender.consumeBlockedOriginal(p.args[0])) {
                                                                    LogWriter.log(TAG, "consume blocked #tts original x9." + m.getName()
                                                                            + " return=" + returnType.getName());
                                                                    sConsumedTtsOriginal.set(Boolean.TRUE);
                                                                    p.setResult(defaultReturnValue(returnType));
                                                                    return;
                                                                }
                                                                if (TtsVoiceSender.shouldConsumeTtsFailureMessage(p.args[0])) {
                                                                    LogWriter.log(TAG, "consume #tts failure residue x9." + m.getName()
                                                                            + " return=" + returnType.getName());
                                                                    sConsumedTtsOriginal.set(Boolean.TRUE);
                                                                    p.setResult(defaultReturnValue(returnType));
                                                                    return;
                                                                }
                                                                if (TtsVoiceSender.handleOutgoingX9(p.args[0])) {
                                                                    LogWriter.log(TAG, "consume outgoing #tts/mode x9." + m.getName()
                                                                            + " return=" + returnType.getName());
                                                                    sConsumedTtsOriginal.set(Boolean.TRUE);
                                                                    p.setResult(defaultReturnValue(returnType));
                                                                }
                                } catch (Throwable e) {
                                    LogWriter.log("MessageHook", "cb err: " + e);
                                }
                            }

                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                try {
                                                                if (Boolean.TRUE.equals(sConsumedTtsOriginal.get())) {
                                                                    sConsumedTtsOriginal.remove();
                                                                    return;
                                                                }
                                                                sConsumedTtsOriginal.remove();
                                                                onX9Message(p.args[0], paramCount >= 2 ? p.args[1] : null, false);
                                } catch (Throwable e) {
                                    LogWriter.log("MessageHook", "cb err: " + e);
                                }
                            }
                        });
                    LogWriter.log(TAG, "hooked x9." + m.getName() + "(" + pts.length + ")");
                    hooked++;
                }
            }
            LogWriter.log(TAG, "x9 hooks installed: " + hooked + " methods");

        } catch (Throwable t) {
            LogWriter.log(TAG, "hookX9 FAIL: " + t.getMessage());
        }
    }

    static void onX9Message(Object e9, Object p0, boolean fromInsert) {
        try {
            int rawType = (int) XposedHelpers.callMethod(e9, "getType");
            int type = mapType(rawType);
            String talker = (String) XposedHelpers.callMethod(e9, "N0");
            String content = readMsgContent(e9);

            // v960: x9 分发阶段 content 可能尚未就绪(入库前), 非语音消息直接跳过,
            // 交给 f9.Bb 入库后(after)处理; 语音消息 content 本就为空, 两条路径都放行
            if (!fromInsert && (content == null || content.isEmpty())
                    && rawType != 34 && rawType != 228) {
                return;
            }

            // v960: 非语音消息 content 为空 = 读取异常, dump 字段辅助定位(语音 content 本就为空)
            if (content == null || content.isEmpty()) {
                if (rawType != 34 && rawType != 228) {
                    dumpMsgFields(e9);
                }
            }

            if (content != null && (content.startsWith("<msgsource")
                || content.startsWith("<pushcontent")))
                return;

            int isSend = -1;
            try { isSend = (Integer) XposedHelpers.callMethod(e9, "z0"); } catch (Throwable ignored) {}
            long msgId = 0;
            // 8.0.78: getMsgId() 为验证有效入口; H0 为旧版兼容
            try { msgId = (Long) XposedHelpers.callMethod(e9, "getMsgId"); } catch (Throwable ignored) {}
            if (msgId == 0) try { msgId = (Long) XposedHelpers.callMethod(e9, "H0"); } catch (Throwable ignored) {}
            // msgId 未分配时用 msgSvrId 兜底去重（v3.0.579: 8.0.79 实证 H0()=getMsgSvrId, F0()=getContent）
            long svrId = 0;
            try { svrId = (Long) XposedHelpers.callMethod(e9, "H0"); } catch (Throwable ignored) {}
            if (msgId == 0 && svrId != 0) msgId = -svrId;

            sCount++;
            boolean isVoice = (rawType == 34 || rawType == 228);
            boolean isTts = content != null && content.startsWith("#tts");
            // v961: 全类型打印消息内容(旧实现仅语音/#tts 打印, 文字等消息内容看不到)
            // v492: type=49 邀请卡片(含"邀请你加入群聊")打印完整 content, 供复刻官方卡片
            boolean isInviteCard = (rawType == 49 || type == 49)
                && content != null && content.contains("邀请你加入群聊");
            if (isInviteCard) {
                LogWriter.log(TAG, "### INVITE_CARD_FULL #" + sCount
                    + " isSend=" + isSend + " msgId=" + msgId
                    + " talker=" + talker + " voice=" + isVoice + " tts=" + isTts);
                LogWriter.log(TAG, "### INVITE_CARD_XML >>>" + content + "<<<");
            }
            LogWriter.log(TAG, "#" + sCount
                + " type=" + rawType + "->" + type
                + " isSend=" + isSend + " msgId=" + msgId
                + " talker=" + trunc(talker, 20)
                + " voice=" + isVoice + " tts=" + isTts
                + " content=" + trunc(content, 200));

            // 一键拉群风控回执：服务端返回「账号安全原因…无法加入群聊」时，
            // 立即暂停进行中的批量邀请队列，避免继续撞墙加重风控/封禁。
            if (isSend != 1 && content != null
                    && (content.contains("由于账号安全原因") || content.contains("无法加入当前群聊"))) {
                BatchInviteManager.notifyRiskControl(content);
            }

            // 去重: 同一个 msgId 只处理一次(容量淘汰由 LinkedHashMap.removeEldestEntry 按插入序完成)
            synchronized (sSeenMsgIds) {
                if (msgId != 0 && !sSeenMsgIds.add(msgId)) {
                    android.util.Log.e(TAG, "!!! SKIP duplicate msgId=" + msgId);
                    return;
                }
            }


            // v3.0.581: 接收侧严格门禁 —— 只有 isSend 明确等于 0 才按「收到的消息」处理。
            // 旧实现用 isSend != 1，读不到 isSend（-1，z0 抛异常）时会被当作接收消息，
            // 于是用户自己发出的点歌指令在 isSend 读取失败的瞬间触发点歌（「自己发的指令也点歌」）。
            // 现在 isSend 读不到一律不处理（fail-closed）。
            if (isSend == 0) {
                // v3.0.583: 首次落库回调 content 还是 msgsource XML 占位，正文在第二次回调。
                // 跳过占位，避免拿 XML 去匹配点歌/关键词/播报。
                if (SelfMsgGuard.isMsgsourceXml(content)) {
                    LogWriter.log(TAG, "skip: content 尚未回填(msgsource) talker=" + trunc(talker, 20));
                    return;
                }
                // v3.0.583: 分身/多设备同步回来的自发消息 isSend 也是 0，且没有本地账本。
                // 发送者等于自己的 wxid 时直接判定归属自己，避免再次触发点歌/关键词回复/播报。
                if (SelfMsgGuard.isSelfSender(e9)) {
                    LogWriter.log(TAG, "skip: 自身发送(同步副本) talker=" + trunc(talker, 20)
                            + " content=" + trunc(content, 30));
                    return;
                }
                // v3.0.580: 模块自发消息护栏 —— 点歌提示/AI 回复/红包播报等由模块自己插入的
                // 消息若以接收形态回到这里，会触发关键词自动回复与语音播报（还会再发消息），
                // 统一在此拦掉，避免自我循环与连锁群发。
                if (SelfMsgGuard.isSelf(talker, type, content)) {
                    LogWriter.log(TAG, "skip: 模块自发消息 talker=" + trunc(talker, 20)
                            + " content=" + trunc(content, 30));
                    return;
                }
                final int fType = type;
                final String fTalker = talker;
                final String fContent = content;
                final Object fMsg = e9;
                sMainHandler.post(() -> {
                    try {
                        boolean blocked = processKeywordAndSensitive(fType, fTalker, fContent, fMsg);
                        if (!blocked) {
                            TTSBroadcaster.handleMessageRaw(fType, fTalker, fContent);
                        }
                    } catch (Throwable e) {
                        LogWriter.log("TTS", "err: " + e.getMessage());
                    }
                });

                if (rawType == 34 || rawType == 228) {
                    final long voiceMsgId = msgId;
                    final String vTalker = talker;
                    if (!VoiceAutoPlay.shouldAutoPlay(vTalker)) {
                        android.util.Log.e(TAG, ">>> VOICE skipped (whitelist) talker=" + vTalker + " msgId=" + voiceMsgId);
                        LogWriter.log("VoiceAutoPlay", "skip whitelist talker=" + vTalker + " msgId=" + voiceMsgId);
                        return;
                    }
                    android.util.Log.e(TAG, ">>> VOICE msgId=" + voiceMsgId + " talker=" + vTalker + " isSend=" + isSend);
                    LogWriter.log("VoiceAutoPlay", "rawType=" + rawType + " msgId=" + voiceMsgId + " talker=" + vTalker);
                    sMainHandler.post(() -> {
                        try {
                            VoiceAutoPlay.onVoiceMsg(e9, voiceMsgId, p0);
                        } catch (Throwable e) {
                            LogWriter.log("VoiceAutoPlay", "msgHook err: " + e.getMessage());
                        }
                    });
                }
            } else if (isSend == 1) {
                // 发出消息: 优先由 before 拦截(不发送/不入库), 此处为 x9 后置兜底
                try { TtsVoiceSender.handleOutgoingX9(e9); } catch (Throwable ignored) {}
            } else {
                // v3.0.581: isSend 读取失败(-1) → 归属不明，直接丢弃，绝不当成接收消息触发点歌/AI/转发
                LogWriter.log(TAG, "skip: isSend 读取失败，按未分类消息丢弃 talker=" + trunc(talker, 20)
                        + " type=" + rawType + " content=" + trunc(content, 30));
            }

        } catch (Throwable t) {
            LogWriter.log(TAG, "err: " + t);
        }
    }

    // ====== 关键词回复 + 敏感词过滤 (接收消息) ======

    private static boolean processKeywordAndSensitive(int type, String talker, String content, Object quotedMsg) {
        if (content == null || content.isEmpty()) return false;
        if (content.startsWith("#tts")) return false;
        // 系统提示消息（type=10000，如防撤回插入的「xxx撤回消息失败」）不参与关键词回复/敏感词过滤，
        // 避免提示行被当成真实聊天消息触发自动回复。
        if (type == 10000) return false;

        // 点歌指令(支持别名, 仅白名单会话生效): 命中后由 DianGeService 接管并后台完成。
        // 必须放在 ModuleConfig 判空之前 —— 点歌只依赖 OnlineMusicPrefs, 与 ModuleConfig 无关;
        // 否则 cfg 未就绪时会先 return false, 消息被 AI 侧过滤后彻底静默吞掉(v1085 群点歌偶发不触发)。
        boolean dianGeHandled = false;
        try {
            dianGeHandled = com.leshao.v3.music.DianGeService.maybeHandle(sClassLoader, talker, content, quotedMsg);
            if (dianGeHandled) {
                LogWriter.log(TAG, "[DianGe] 已接管点歌 talker=" + trunc(talker, 20));
            } else if (com.leshao.v3.music.DianGeService.looksLikeCommand(talker, content)) {
                LogWriter.log(TAG, "[DianGe] 指令未执行(未启用/非白名单/冷却) talker=" + trunc(talker, 20));
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "[DianGe] err: " + e.getMessage());
        }

        ModuleConfig cfg = ModuleConfig.load(ContextManager.getPrefs());
        if (cfg == null) {
            LogWriter.log(TAG, "[Config] ModuleConfig 未就绪, 跳过敏感词/关键词回复");
            return false;
        }

        boolean blocked = false;
        if (cfg.sensitiveFilterEnabled && !cfg.sensitiveWords.isEmpty()) {
            for (String w : cfg.sensitiveWords) {
                if (w != null && !w.isEmpty() && content.contains(w)) {
                    LogWriter.log(TAG, "[Sensitive] 命中敏感词=" + w + " talker=" + trunc(talker, 20));
                    blocked = true;
                    break;
                }
            }
        }

        // 点歌已接管时不再走关键词回复, 避免同一消息二次回复
        if (dianGeHandled) return blocked;

        if (cfg.keywordReplyEnabled && !cfg.keywordRules.isEmpty()) {            for (KeywordRule r : cfg.keywordRules) {
                if (r == null || r.keyword == null || r.reply == null) continue;
                boolean match = r.fuzzyMatch
                        ? content.contains(r.keyword)
                        : content.equals(r.keyword);
                if (match) {
                    LogWriter.log(TAG, "[KwReply] 命中=" + r.keyword + " -> " + r.reply);
                    // 反射发送+DB 入库较重, 切后台线程执行, 避免阻塞主线程回调
                    final String replyTalker = talker;
                    final String replyText = r.reply;
                    try {
                        sBgExecutor.execute(() -> {
                            try {
                                GroupFeatures.sendTextMessage(sClassLoader, replyTalker, replyText);
                            } catch (Throwable e) {
                                LogWriter.log(TAG, "[KwReply] send err: " + e.getMessage());
                            }
                        });
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "[KwReply] schedule err: " + e.getMessage());
                    }
                    break;
                }
            }
        }

        return blocked;
    }

    // ====== IEvent.e() 事件总线 (拦截自己发出的 #tts) ======

    private static void hookIEventBus(ClassLoader cl) {
        try {
            Class<?> iEventClz = WxResolver.resolve(cl, "com.tencent.mm.sdk.event.IEvent");
            Class<?> sendOkClz = WxResolver.resolve(cl, "com.tencent.mm.autogen.events.SendMsgSuccessEvent");

            java.lang.reflect.Method eMethod = iEventClz.getDeclaredMethod("e");
            XposedBridge.hookMethod(eMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!sendOkClz.isInstance(param.thisObject)) return;

                        Object data = XposedHelpers.getObjectField(param.thisObject, "g");
                        if (data == null) return;
                        Object e9 = XposedHelpers.getObjectField(data, "a");
                        if (e9 == null) return;

                        String content = (String) XposedHelpers.callMethod(e9, "j");
                        String talker = (String) XposedHelpers.callMethod(e9, "N0");
                        long msgId = (Long) XposedHelpers.callMethod(e9, "getMsgId");
                        int type = (Integer) XposedHelpers.callMethod(e9, "getType");

                        LogWriter.log(TAG, "IEvent.e: type=" + type + " talker=" + talker
                            + " msgId=" + msgId + " content=" + trunc(content, 30));
                        android.util.Log.e(TAG, ">>> IEvent.e SendMsgSuccess: type=" + type
                            + " talker=" + talker + " msgId=" + msgId
                            + " content=[" + (content == null ? "null" : content.substring(0, Math.min(content.length(), 60))) + "]");

                    } catch (Throwable t) {
                        android.util.Log.e(TAG, ">>> IEvent.e hook err: " + t.getMessage());
                    }
                }
            });
            LogWriter.log(TAG, "IEvent.e() hooked OK");
            android.util.Log.e(TAG, ">>> IEvent.e() hooked OK");

        } catch (Throwable t) {
            LogWriter.log(TAG, "hookIEventBus FAIL: " + t.getMessage());
            android.util.Log.e(TAG, ">>> IEvent.e() FAIL: " + t.getMessage());
        }
    }

    // ====== 工具方法 ======

    static String trunc(String s, int m) {
        return s == null ? "" : s.length() > m ? s.substring(0, m) + "..." : s;
    }

    static int mapType(int t) {
        if (t >= 268435456 || t == 74 || t == 83 || t == 84 || t == 87
            || t == 95 || t == 102 || t == 103 || t == 131 || t == 132
            || t == 1048625 || t == 16777265) return 49;
        return t;
    }

    private static Object defaultReturnValue(Class<?> returnType) {
        if (returnType == Void.TYPE) return null;
        if (returnType == Boolean.TYPE) return false;
        if (returnType == Byte.TYPE) return (byte) 0;
        if (returnType == Short.TYPE) return (short) 0;
        if (returnType == Integer.TYPE) return 0;
        if (returnType == Long.TYPE) return 0L;
        if (returnType == Float.TYPE) return 0f;
        if (returnType == Double.TYPE) return 0d;
        if (returnType == Character.TYPE) return (char) 0;
        return null;
    }
}
