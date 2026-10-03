package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;

import org.luckypray.dexkit.result.ClassData;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 乐少·去广告（开关名：去你妈的广告）。
 *
 * <p>严格按《微信去广告_完整方案_三轮审查合并终版.md》的广告位清单与 hook 点实现，
 * 覆盖三大场景：小程序（AppBrand）/ 朋友圈（SNS Timeline）/ 视频号（Finder）。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>日志统一走 {@link LogWriter}（log/logSync）。</li>
 *   <li>类名解析优先 DexKit（{@link DexKitHelper#findClassesByString} /
 *       {@link DexKitHelper#findMethodsByString}）字符串锚点，短名/具名做兜底。</li>
 *   <li>本构建环境 {@code XposedHelpers.findAndHookMethod} 的 varargs 可能被改写不可用，
 *       统一走 {@code clazz.getDeclaredMethod(...) + XposedBridge.hookMethod(...)}。</li>
 *   <li>全程 try/catch，任何异常都不影响微信启动；开关关闭时直接跳过。</li>
 * </ul>
 *
 * <p>开关：{@link HookConfig#isEnabled(String)}，key = {@link #PREF_ENABLED}（默认 true）。</p>
 */
public class AdBlockerHook {

    private static final String TAG = "AdBlocker";

    /** UI 开关 key（HookConfig.getDefault 已将该 key 默认置为 true）。 */
    public static final String PREF_ENABLED = "sns_ad_block";

    private static volatile boolean sEnabled = false;
    private static volatile boolean sHooked = false;

    /** DexKit 全量搜索可能耗时数秒，hook 安装放后台线程，避免阻塞 HookManager 串行激活循环。 */
    private static final java.util.concurrent.ExecutorService sExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "leshao-adblocker");
                t.setDaemon(true);
                return t;
            });

    private AdBlockerHook() {}

    public static boolean isEnabled() { return sEnabled; }

    /** 供 UI 读写开关（写入 HookConfig 使用的 SharedPreferences key）。 */
    public static void setEnabled(boolean v) {
        sEnabled = v;
        try {
            android.content.SharedPreferences sp = com.leshao.v3.ContextManager.getPrefs();
            if (sp != null) sp.edit().putBoolean(PREF_ENABLED, v).apply();
        } catch (Throwable ignored) {}
    }

    /**
     * hook 入口：常规注册方式为 HookManager.register("AdBlockerHook", () -> AdBlockerHook.hook(cl))。
     * 内部只做开关判定与后台调度，真正的 DexKit 定位/安装异步执行。
     */
    public static void hook(final ClassLoader cl) {
        try {
            boolean enabled = HookConfig.isEnabled(PREF_ENABLED);
            sEnabled = enabled;
            if (!enabled) {
                LogWriter.log(TAG, "hook: disabled (key=" + PREF_ENABLED + ")");
                return;
            }
            if (sHooked) return;
            sHooked = true;

            LogWriter.log(TAG, "hook: install start...");
            sExecutor.execute(() -> {
                try {
                    hookAppBrand(cl);          // 1. 小程序
                    hookSnsTimeline(cl);       // 2. 朋友圈
                    hookFinder(cl);            // 3. 视频号
                    hookAdLandingPages(cl);    // 4. 广告落地页兜底
                    LogWriter.log(TAG, "hook: install done");
                } catch (Throwable t) {
                    LogWriter.log(TAG, "hook install err: " + t);
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook err: " + t);
        }
    }

    /**
     * v3.0.127: 仅在 {@code :appbrand0~4} 子进程调用 —— 只安装小程序（AppBrand）去广告 Hook。
     *
     * <p>小程序开屏广告全链路（{@code d7.K2} / {@code lc1.*} / {@code AppBrandAdUI}）都跑在
     * {@code :appbrand0~4} 子进程，主进程安装的这些 Hook 在子进程永不触发（见《去广告最终文档.md》）。
     * 子进程无需朋友圈/视频号/落地页 Hook，也不做 UI 注入，故只挂 {@link #hookAppBrand}，保持最小开销。</p>
     *
     * <p>与 {@link #hook(ClassLoader)} 共用 {@link #sEnabled}/{@link #sHooked} 门控，单进程内幂等。</p>
     */
    public static void hookAppBrandOnly(final ClassLoader cl) {
        try {
            boolean enabled = HookConfig.isEnabled(PREF_ENABLED);
            sEnabled = enabled;
            if (!enabled) {
                LogWriter.log(TAG, "appBrandOnly: disabled (key=" + PREF_ENABLED + ")");
                return;
            }
            if (sHooked) return;
            sHooked = true;

            LogWriter.log(TAG, "appBrandOnly: install start...");
            sExecutor.execute(() -> {
                try {
                    hookAppBrand(cl);          // 仅小程序
                    LogWriter.log(TAG, "appBrandOnly: install done");
                } catch (Throwable t) {
                    LogWriter.log(TAG, "appBrandOnly install err: " + t);
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "appBrandOnly err: " + t);
        }
    }

    // ============================================================
    // 1. 小程序（AppBrand）
    // ============================================================

    private static void hookAppBrand(ClassLoader cl) {
        // 1.1 ★主★ 开屏广告总闸 d7.K2(d7) -> false
        //     DexKit 锚点: "checkCanShowAd, show ad (splash ad debug mode open)"
        //     + "MicroMsg.AppBrandAdUtils[AppBrandSplashAd]"
        String d7 = resolveClassByStrings(cl, new String[]{
                "MicroMsg.AppBrandAdUtils[AppBrandSplashAd]"
        }, "com.tencent.mm.plugin.appbrand.d7");
        // K2(d7) 共 1 参 -> 恒 false
        hookByNameParamCount(cl, d7, "K2", 1, false, "appbrand.K2");

        // 1.2 次级: lc1.d.b()/a() (AppBrandAdABTests) -> false
        //     DexKit 锚点: "MicroMsg.AppBrandAdABTests[AppBrandSplashAd]"
        String abTests = resolveClassByStrings(cl, new String[]{
                "MicroMsg.AppBrandAdABTests[AppBrandSplashAd]"
        }, "lc1.d");
        hookReturnConst(cl, abTests, "b", false, "appbrand.AB.canShowAppBrandAd");
        hookReturnConst(cl, abTests, "a", false, "appbrand.AB.canAllShowAppBrandAd");

        // 1.3 次级: lc1.j.a(AppBrandInitConfigWC) -> false (isAdContact，比 1.2 更彻底)
        //     注意: lc1.j 与 d7 共用 Log TAG, 用锚点会误命中 d7, 故此处直接用混淆短名。
        hookByNameParamCount(cl, "lc1.j", "a", 1, false, "appbrand.isAdContact");

        // 1.4 次级: lc1.b0.l(I) -> no-op (不再向小程序派发 shouldShowSplashAd)
        String splashLogic = resolveClassByStrings(cl, new String[]{
                "MicroMsg.AppBrandSplashAdLogic[AppBrandSplashAd]"
        }, "lc1.b0");
        hookByNameParamCount(cl, splashLogic, "l", 1, null, "appbrand.sendShouldShowAdIfNeed");

        // 1.5 兜底: 开屏广告宿主 Activity 一起来就 finish
        //     微信按进程槽位拆分 Activity：AppBrandAdUI(=slot0) / AppBrandAdUI1..4。
        //     各槽位与 :appbrand0..4 一一对应，全部挂 finish 才能覆盖"第 2+ 个小程序"。
        //     (见《去广告最终文档.md》附带发现 1；不存在的槽位类静默跳过，不刷日志。)
        for (int slot = 0; slot <= 4; slot++) {
            final String adUi = "com.tencent.mm.plugin.appbrand.ad.ui.AppBrandAdUI"
                    + (slot == 0 ? "" : String.valueOf(slot));
            hookActivityFinishOnCreateQuiet(cl, adUi, "appbrand.AdUI" + (slot == 0 ? "" : slot));
        }

        // 1.6 兜底: "..."菜单广告 footer 不显示 (setPageView(pageView) 共 1 参)
        hookByNameParamCount(cl, "com.tencent.mm.plugin.appbrand.ad.ui.AppBrandMenuFooter",
                "setPageView", 1, null, "appbrand.MenuFooter");

        // 1.7 次级: JsApiShowSplashAd.A(jsapi/l, JSONObject, int) -> no-op
        //     小程序侧请求 show 开屏时直接吞掉（按参数个数 3 定位，规避签名漂移）。
        hookMethodByParamCountSafe(cl, "com.tencent.mm.plugin.appbrand.ad.jsapi.u",
                "A", 3, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(null);
                    }
                }, "appbrand.JsApiShowSplashAd");

        // v3.0.127: 移除小程序内广告 JSAPI（createRewardedVideoAd 等 4 个 DexKit 方法字符串扫描）。
        // 实测微信 3180 四处扫描全部 0 命中（该版本不走通用广告工厂），且空结果不进 MMKV 缓存，
        // 会在 5 个 :appbrand 子进程各重跑一次全量 DexKit 扫描，代价高、收益为零，故删除。
    }

    // ============================================================
    // 2. 朋友圈（SNS Timeline）
    // ============================================================

    private static void hookSnsTimeline(ClassLoader cl) {
        // 2.1 ★主★ AdSnsInfoStorageLogic.insert(List,List,boolean,int) -> 空实现（广告永不入库）
        //     DexKit 锚点: "insert, forbid ad" + "MicroMsg.AdSnsInfoStorageLogic"
        String storageLogic = resolveClassByStrings(cl, new String[]{
                "MicroMsg.AdSnsInfoStorageLogic"
        }, "com.tencent.mm.plugin.sns.model.x");
        // insert(List,List,boolean,int) 共 4 参 -> 空实现
        hookByNameParamCount(cl, storageLogic, "i", 4, null, "sns.insert");

        // 2.2 ★辅★ NetSceneSnsTimeLine.handleNormalResp 只清 o(广告)+r(推荐→广告)，绝不清 f
        //     DexKit 锚点: "handleNormalResp" + "com.tencent.mm.plugin.sns.model.NetSceneSnsTimeLine"
        String timeLine = resolveClassByStrings(cl, new String[]{
                "com.tencent.mm.plugin.sns.model.NetSceneSnsTimeLine",
                "handleNormalResp"
        }, "com.tencent.mm.plugin.sns.model.l3");
        hookSnsHandleNormalResp(cl, timeLine);

        // 2.3 ★辅★ UI 层: SnsInfo.isAd() -> false
        hookReturnConst(cl, "com.tencent.mm.plugin.sns.storage.SnsInfo", "isAd", false, "sns.SnsInfo.isAd");

        // 2.4 ★辅★ 新版时间线: ImproveSnsInfo.isAd() -> false（混淆名 jk4.p 兜底）
        String improve = resolveClassByStrings(cl, new String[]{
                "ImproveSnsInfo"
        }, "jk4.p");
        hookReturnConst(cl, improve, "isAd", false, "sns.ImproveSnsInfo.isAd");

        // 2.5 ★兜底★ ConsecutiveAdDataImproveHelper.c(List) 清空返回
        String consecutive = resolveClassByStrings(cl, new String[]{
                "ConsecutiveAdDataImproveHelper"
        }, "cc4.o4");
        hookSnsConsecutiveAd(cl, consecutive);
    }

    /** l3.J(II,String,pc5/ni6) after: 仅清空响应对象里 o(广告)/r(推荐) 两个 LinkedList 字段。 */
    private static void hookSnsHandleNormalResp(final ClassLoader cl, final String clsName) {
        if (clsName == null) { LogWriter.log(TAG, "[WARN] sns.handleNormalResp: class null"); return; }
        try {
            Class<?> c = loadClass(cl, clsName);
            if (c == null) { LogWriter.log(TAG, "[WARN] sns.handleNormalResp: class not loadable " + clsName); return; }
            int hooked = 0;
            for (Method m : c.getDeclaredMethods()) {
                // 方法总长 1384 行；按签名 III(I I String <resp>) 定位，params=4 且前两个 int、第三个 String
                if (m.getParameterCount() != 4) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts[0] != int.class || pts[1] != int.class || pts[2] != String.class) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!sEnabled) return;
                        try {
                            Object resp = param.args[3];
                            if (resp == null) return;
                            clearFieldIfList(resp, "o");
                            clearFieldIfList(resp, "r");
                        } catch (Throwable ignored) {}
                    }
                });
                hooked++;
            }
            LogWriter.log(TAG, "hooked " + clsName + ".handleNormalResp methods(" + hooked + ")");
        } catch (Throwable t) {
            LogWriter.log(TAG, "[WARN] sns.handleNormalResp err: " + t);
        }
    }

    /** cc4.o4.c(List) after: 清空返回列表（连续广告治理兜底）。 */
    private static void hookSnsConsecutiveAd(final ClassLoader cl, final String clsName) {
        if (clsName == null) { LogWriter.log(TAG, "[WARN] sns.consecutiveAd: class null"); return; }
        try {
            Class<?> c = loadClass(cl, clsName);
            if (c == null) { LogWriter.log(TAG, "[WARN] sns.consecutiveAd: class not loadable " + clsName); return; }
            int hooked = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (!List.class.isAssignableFrom(m.getParameterTypes()[0])) continue;
                if (m.getReturnType() != List.class) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!sEnabled) return;
                        try {
                            Object r = param.getResult();
                            if (r instanceof List) ((List<?>) r).clear();
                        } catch (Throwable ignored) {}
                    }
                });
                hooked++;
            }
            LogWriter.log(TAG, "hooked " + clsName + ".consecutiveAd methods(" + hooked + ")");
        } catch (Throwable t) {
            LogWriter.log(TAG, "[WARN] sns.consecutiveAd err: " + t);
        }
    }

    // ============================================================
    // 3. 视频号（Finder）
    // ============================================================

    private static void hookFinder(final ClassLoader cl) {
        final String loader = "com.tencent.mm.plugin.finder.feed.model.BaseFinderFeedLoader";

        // 3.1 ★主★ insertAll(List,int) before: 剔除 adFlag != 0
        hookMethodByParamCountSafe(cl, loader, "insertAll", 2, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length < 1 || !(param.args[0] instanceof List)) return;
                    List<?> list = (List<?>) param.args[0];
                    if (list == null) return;
                    Iterator<?> it = list.iterator();
                    while (it.hasNext()) {
                        if (isFinderAd(it.next(), cl)) it.remove();
                    }
                } catch (Throwable ignored) {}
            }
        }, "finder.insertAll");

        // 3.2 ★主★ insert(lu2/u5,int,boolean) before: adFlag != 0 -> return -1
        hookMethodByParamCountSafe(cl, loader, "insert", 3, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length < 1) return;
                    if (isFinderAd(param.args[0], cl)) param.setResult(-1);
                } catch (Throwable ignored) {}
            }
        }, "finder.insert");
    }

    // ============================================================
    // 4. 广告落地页兜底（点了广告也不落地）
    // ============================================================

    private static void hookAdLandingPages(ClassLoader cl) {
        String[] pages = {
                "com.tencent.mm.plugin.sns.ui.SnsAdNativeLandingPagesUI",
                "com.tencent.mm.plugin.sns.ui.SnsAdNativeLandingPagesPreviewUI",
                "com.tencent.mm.plugin.sns.ui.SnsAdProxyUI",
                "com.tencent.mm.plugin.sns.ui.SnsAdStreamVideoPlayUI",
                "com.tencent.mm.plugin.sns.ad.animproxy.SnsAdAnimProxyUI",
        };
        for (String p : pages) {
            hookActivityFinishOnCreate(cl, p, "landing:" + simpleName(p));
        }
    }

    // ============================================================
    // 判定与工具
    // ============================================================

    /**
     * 视频号广告判定：BaseFinderFeed.z() -> FinderItem.getFeedObject() -> FinderObject.getAdFlag() != 0。
     * 比微信自带 sh2.b1.D 更宽（覆盖 campaign adFlag==4 的情况）。
     */
    private static boolean isFinderAd(Object feed, ClassLoader cl) {
        try {
            if (feed == null) return false;
            Class<?> baseFeed = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.finder.model.BaseFinderFeed", cl);
            if (baseFeed == null || !baseFeed.isInstance(feed)) return false;
            Object item = XposedHelpers.callMethod(feed, "z");
            if (item == null) return false;
            Object obj = XposedHelpers.callMethod(item, "getFeedObject");
            if (obj == null) return false;
            Object flag = XposedHelpers.callMethod(obj, "getAdFlag");
            return (flag instanceof Integer) && ((Integer) flag) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 只清指定名字的 List 字段；字段不存在/异常都静默忽略（pc5/ni6 的 o/r）。 */
    private static void clearFieldIfList(Object owner, String fieldName) {
        try {
            java.lang.reflect.Field f = null;
            for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try { f = c.getDeclaredField(fieldName); break; } catch (Throwable ignored) {}
            }
            if (f == null) return;
            f.setAccessible(true);
            Object v = f.get(owner);
            if (v instanceof List) ((List<?>) v).clear();
        } catch (Throwable ignored) {}
    }

    /**
     * 通过 DexKit 字符串锚点在候选类名中定位真实类。
     * <p>优先级：具名 fallback（以 com.tencent.mm. 开头且可加载）> DexKit 锚点首个命中 > 短名 fallback。
     * 若 fallback 是具名类，直接采用（最稳，避免锚点误命中同 TAG 的其它类）；</p>
     */
    private static String resolveClassByStrings(ClassLoader cl, String[] anchors, String fallback) {
        // 短混淆名（如 lc1.d）是本 APK 的唯一真相：文档的“原始类名”并不存在，
        // 且锚点字符串（Log TAG）会误命中同 TAG 的其它类。故优先用短名解析。
        if (fallback != null) {
            Class<?> fb = loadClass(cl, fallback);
            if (fb != null) {
                LogWriter.log(TAG, "class via short-name: " + fallback + " -> " + fb.getName());
                return fb.getName();
            }
        }
        if (fallback != null && fallback.startsWith("com.tencent.mm.") && loadClass(cl, fallback) != null) {
            return fallback;
        }
        for (String kw : anchors) {
            try {
                List<String> cands = DexKitHelper.findClassesByString(cl, kw);
                if (cands != null && !cands.isEmpty()) {
                    String hit = pickPreferred(cands);
                    LogWriter.log(TAG, "class hit: " + hit + " via '" + kw + "'");
                    return hit;
                }
            } catch (Throwable ignored) {}
        }
        LogWriter.log(TAG, "class miss(anchors), fallback=" + fallback);
        return fallback;
    }

    /** 候选类去重后优先选择可加载的 com.tencent.mm.* 类。 */
    private static String pickPreferred(List<String> cands) {
        String first = null;
        for (String c : cands) {
            if (c == null || c.isEmpty()) continue;
            if (first == null) first = c;
            if (c.startsWith("com.tencent.mm.") && !c.contains("$")) return c;
        }
        return first;
    }

   /** 按类型加载类，失败返回 null；短名（混淆类）回退 DexKit 按简名解析，并遍历候选 CL。 */
    private static Class<?> loadClass(ClassLoader cl, String name) {
        if (name == null || name.isEmpty()) return null;
        for (ClassLoader loader : HookUtil.candidateLoaders(cl)) {
            try { return loader.loadClass(name); } catch (Throwable ignored) {}
        }
        // 短名（如 lc1.d）无法 loadClass，改用 DexKit 按简名定位真实全限定名。
        if (!name.contains(".")) {
            try {
                ClassData cd = DexKitHelper.findClassByName(cl, name);
                if (cd != null && cd.getName() != null) {
                    for (ClassLoader loader : HookUtil.candidateLoaders(cl)) {
                        try { return loader.loadClass(cd.getName()); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 定位指定名字、零参/任意参的单个方法并 hook（方法名+参数个数匹配）。 */
    private static void hookByNameParamCount(final ClassLoader cl, final String clsName,
                                             final String methodName, final int paramCount,
                                             final Object blockResult, final String tag) {
        Class<?> c = loadClass(cl, clsName);
        if (c == null) { LogWriter.log(TAG, "[WARN] " + tag + ": class null (" + clsName + ")"); return; }
        int hooked = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) continue;
            if (paramCount >= 0 && m.getParameterCount() != paramCount) continue;
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    param.setResult(blockResult);
                }
            });
            hooked++;
        }
        if (hooked == 0) LogWriter.log(TAG, "[WARN] " + tag + ": no method " + methodName
                + "/" + paramCount + " in " + clsName);
        else LogWriter.log(TAG, "hooked " + clsName + "." + methodName + " as " + tag + " (" + hooked + ")");
    }

    /** 定位指定名字的方法（无参）并固定返回值。 */
    private static void hookReturnConst(final ClassLoader cl, final String clsName,
                                        final String methodName, final Object result, final String tag) {
        Class<?> c = loadClass(cl, clsName);
        if (c == null) { LogWriter.log(TAG, "[WARN] " + tag + ": class null (" + clsName + ")"); return; }
        int hooked = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) continue;
            if (m.getParameterCount() != 0) continue;
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    param.setResult(result);
                }
            });
            hooked++;
        }
        if (hooked == 0) LogWriter.log(TAG, "[WARN] " + tag + ": no zero-arg method " + methodName
                + " in " + clsName);
        else LogWriter.log(TAG, "hooked " + clsName + "." + methodName + " as " + tag + " (" + hooked + ")");
    }

    /** 通用 hook：按方法名 + 参数个数匹配（用于混淆签名漂移时按数量兜底）。 */
    private static void hookMethodByParamCountSafe(final ClassLoader cl, final String clsName,
                                                   final String methodName, final int paramCount,
                                                   final XC_MethodHook callback, final String tag) {
        Class<?> c = loadClass(cl, clsName);
        if (c == null) { LogWriter.log(TAG, "[WARN] " + tag + ": class null (" + clsName + ")"); return; }
        int hooked = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) continue;
            if (m.getParameterCount() != paramCount) continue;
            m.setAccessible(true);
            XposedBridge.hookMethod(m, callback);
            hooked++;
        }
        if (hooked == 0) LogWriter.log(TAG, "[WARN] " + tag + ": no method " + methodName
                + "/" + paramCount + " in " + clsName);
        else LogWriter.log(TAG, "hooked " + clsName + "." + methodName + " as " + tag + " (" + hooked + ")");
    }

    /**
     * Activity.onCreate(Bundle) after -> finish()。宿主广告页兜底。
     * <p>与 {@link #hookActivityFinishOnCreateQuiet} 的区别：目标类不存在时打印 [WARN]，用于落地页等确定性目标。</p>
     */
    private static void hookActivityFinishOnCreate(final ClassLoader cl, final String clsName,
                                                   final String tag) {
        Class<?> c = loadClass(cl, clsName);
        if (c == null) { LogWriter.log(TAG, "[WARN] " + tag + ": class null (" + clsName + ")"); return; }
        try {
            Method onCreate = c.getDeclaredMethod("onCreate", android.os.Bundle.class);
            onCreate.setAccessible(true);
            XposedBridge.hookMethod(onCreate, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    try { XposedHelpers.callMethod(param.thisObject, "finish"); } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "hooked " + clsName + ".onCreate -> finish as " + tag);
        } catch (Throwable t) {
            LogWriter.log(TAG, "[WARN] " + tag + ": onCreate err " + t);
        }
    }

    /**
     * Activity.onCreate(Bundle) after -> finish()。宿主广告页兜底。
     * <p>与 {@link #hookActivityFinishOnCreate} 的区别：目标类不存在时静默跳过（不刷 [WARN]），
     * 用于 {@code AppBrandAdUI} 的 5 个进程槽位（slot0/1..4）批量挂载——多数设备只启用部分槽位。</p>
     */
    private static void hookActivityFinishOnCreateQuiet(final ClassLoader cl, final String clsName,
                                                        final String tag) {
        Class<?> c = loadClass(cl, clsName);
        if (c == null) return;   // 该槽位类不存在（设备未启用），静默跳过
        try {
            Method onCreate = c.getDeclaredMethod("onCreate", android.os.Bundle.class);
            onCreate.setAccessible(true);
            XposedBridge.hookMethod(onCreate, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    try { XposedHelpers.callMethod(param.thisObject, "finish"); } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "hooked " + clsName + ".onCreate -> finish as " + tag);
        } catch (Throwable t) {
            LogWriter.log(TAG, "[WARN] " + tag + ": onCreate err " + t);
        }
    }

    private static String simpleName(String full) {
        int i = full.lastIndexOf('.');
        return i >= 0 ? full.substring(i + 1) : full;
    }
}