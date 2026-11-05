package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

import org.luckypray.dexkit.result.ClassData;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 乐少·去广告（开关名：去你妈的广告）。
 *
 * <p>严格按《微信去广告_完整方案_十轮审查合并终版.md》（第十轮最终版）的广告位清单与 hook 点实现，
 * 覆盖三大场景：小程序（AppBrand，含 MBAD 内部广告）/ 朋友圈（SNS Timeline）/ 视频号（Finder，含直播）。</p>
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
     * 子进程无需朋友圈/视频号 Hook，只挂 {@link #hookAppBrand}（开屏）+ {@link #hookMbAd}
     * （MBAD 内部广告全家 + 激励视频秒过，含广告落地页），保持最小开销。</p>
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
                    hookAppBrand(cl);          // 1. 小程序开屏
                    hookMbAd(cl);              // 2. 小程序内部广告（MBAD 全家 + 激励视频秒过）
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
        // 1.1 主级: K2(d7) 共 1 参 -> 恒 false
        //     8.0.79 字符串 MicroMsg.AppBrandAdUtils[AppBrandSplashAd] 仍存在，
        //     宿主已漂移（jsapi.auth.q0 / page.l0 / f7），用方法级字符串锚点直接定位。
        //     v3.0.524: 仅 hook 返回 boolean 的方法（真正的广告闸门）。page.l0.onClick
        //     等 void 点击处理器不再拦截，避免小程序页面卡死（setResult(false) 塞 void 会
        //     吞掉页面点击，导致「一直加载中」）。
        //     v3.0.525: f7.L2（AppBrandSplashAd 字符串同时命中的另一个 1 参 boolean 方法）
        //     被实机日志证实会在小程序启动瞬间被调用，且拦截后小程序一直加载中 ——
        //     排除 f7 类，只保留 vc1.j.a（确认的 checkCanShowAd 闸门），f7.L2 改 DIAG 观察。
        hookMethodsByString(cl, "MicroMsg.AppBrandAdUtils[AppBrandSplashAd]", 1, false, "appbrand.K2", true, new String[]{"f7"});

        // 1.2 次级: AppBrandAdABTests b()/a() -> false
        //     8.0.79 该 TAG 仍存在，宿主漂移到 com.tencent.mm.plugin.appbrand.ad.jsapi.n
        //     v3.0.524: 仅 hook boolean 方法。vc1.d.c()/d() 返回 int，硬塞 false 会
        //     ClassCastException 被微信内部吞掉 -> 小程序加载打不开。
        //     v3.0.525: 同样排除 f7 类（只保留 vc1.d.a/b 布尔开关）。
        hookMethodsByString(cl, "MicroMsg.AppBrandAdABTests[AppBrandSplashAd]", -1, false, "appbrand.AB", true, new String[]{"f7"});

        // 1.3 次级: isAdContact —— 8.0.79 中宿主为 vc1.j.a(AppBrandInitConfigWC)（AppBrandAdUtils TAG），
        //     已由上面 appbrand.K2（MicroMsg.AppBrandAdUtils[AppBrandSplashAd] 1 参 boolean）覆盖，不再重复 hook。

        // 1.4 次级: sendShouldShowAdIfNeed（原 lc1.b0.l(I)）-> no-op
        //     8.0.79 宿主为 jsapi.u（AppBrand.JsApiShowSplashAd / showSplashAd 日志串）
        hookMethodsByString(cl, "MicroMsg.AppBrand.JsApiShowSplashAd[AppBrandSplashAd]", -1, null, "appbrand.sendShouldShowAdIfNeed");

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

        // v3.0.144：MBAD 全家（b0.T1 / cm1.* / seekTo）按十轮终版只在 :appbrand0~4 子进程安装
        //（见 hookAppBrandOnly），主进程不再安装，避免主进程误拦 MagicCard 等通用组件。
    }

    // ============================================================
    // 1.8 小程序内部广告（MBAD / MagicBrush Ad）——《微信去广告_完整方案_十轮审查合并终版.md》
    // ============================================================

    /**
     * v3.0.144：MBAD 小程序内部广告拦截（十轮审查终版）。
     *
     * <p>依据《微信去广告_完整方案_十轮审查合并终版.md》第十轮最终代码 + 附录 A 修正全集 + 第九轮错误纠正：</p>
     * <ul>
     *   <li>★★★ P0 总闸：{@code service.b0.T1()} 返回 null → 微信原生 "mMBADInstaller is null" 失败分支。
     *       （这是唯一安全的 P0；🔴 {@code x3.k()} 是服务初始化，拦了小程序打不开，已移除。）</li>
     *   <li>★★★ 备选：{@code cm1.r.invoke()}（installer 协程）、{@code cm1.u.d()}（preload）。</li>
     *   <li>★★★ 宿主→小程序消息正门：{@code cm1.m.N3(...)}。</li>
     *   <li>★★☆ 封面广告：{@code cm1.j.a(Object,Object,"xi3.k")}（onRequestInsertCoverView 插入入口，参数是 Object 不是 View）
     *       + {@code cm1.k.invoke()}（bindFrameSetView）。</li>
     *   <li>🔴 封面不拦：{@code cm1.j.e()}（onRequestRemoveCoverView = 关掉封面，拦了封面关不掉）。</li>
     *   <li>★★☆ 激励视频：{@code cm1.i.d(View)/c(View)} + bridge 重载 {@code a(Object)/b(Object)}。</li>
     *   <li>★★☆ 广告事件总线：{@code jsapi.advertise.p.A(...)}。</li>
     *   <li>★★☆ 广告跳转族：advertise.o/s、channels.b0/f0/w、j7、y7、profile.h 的 A/E/I/C 入口。</li>
     *   <li>★★☆ 数据层：{@code bk.j0.mj/lj}（innerPullAds + handleAdOpen 点击跳转正门，v3.0.146）、{@code dk.b.call}（CGI）。</li>
     *   <li>★★☆ 视频号跳转执行器：{@code cc4.s4}（FinderAdJumpHelper 六方法 a/b/d/e/g/h，v3.0.146）。</li>
     *   <li>★☆☆ 上报：{@code bk3.a.t}、{@code pu0.m.cj/dj}；关系追踪 {@code nc1.f.f/a}。</li>
     *   <li>★☆☆ 广告落地页 6 个 Activity 一起 finish。</li>
     * </ul>
     *
     * <p>🔴 已按十轮终版移除的危险/误伤 hook：{@code x3.k()}（禁区）、{@code hj3.v1.ji/R0/D1}（通用渲染器，
     * 拦了 MagicBrush 全黑屏）、{@code tl3.g} 18 方法（通用视频回调，现改为 seekTo 中只挂 e()）、
     * {@code cm1.m.n} / {@code dk.f.invoke} / {@code bk3.a.f} / {@code ck3.p.t} /
     * {@code ck3.v.t} / {@code bu0.a.c}（最终代码未采纳，删除以严格对齐十轮终版）。
     * v3.0.146 起 {@code bk.j0.lj}（handleAdOpen）经第十一部分诊断确认为点击跳转正门，重新纳入。</p>
     *
     * <p>所有方法统一在 before 中 {@code setResult(null)}（等价 {@code XC_MethodReplacement.returnConstant(null)}），
     * 使 MBAD 框架"不起来 / 广告不存在 / 上报不出去"。短名混淆类（cm1.*、bk.*、b0 等）由
     * {@link #loadClass} 的 DexKit 短名兜底解析；方法名匹配任意参数签名（tryHookSoft 策略），签名漂移静默跳过。</p>
     *
     * <p>进程分级：本方法只在 {@code :appbrand0~4} 子进程安装（{@link #hookAppBrandOnly}），主进程不装，
     * 避免误伤主进程通用组件（十轮终版 isMain 分支不含 MBAD）。</p>
     */
    private static void hookMbAd(ClassLoader cl) {
        LogWriter.log(TAG, "mbad: install MBAD in-app ad blocker (v3.0.144, 十轮终版)");

        // ★★★ P0 总闸：MBAD 拿不到 installer → 微信原生失败分支（最安全）
        // v3.0.526: 实机日志 + baksmali 确认 8.0.79 中 MBAD installer 仍在
        // com.tencent.mm.plugin.appbrand.service.b0.T1()（返回 Lom1/u，懒加载：
        // 内部 if (Z==null) Y1(false) 再 return Z）。之前硬编码 type.service.b0
        // 是误判（类不存在，总闸一直没装上 → 网格/弹窗/格子广告全漏）。
        hookByNameParamCount(cl, "com.tencent.mm.plugin.appbrand.service.b0",
                "T1", 0, null, "mbad.b0.T1");

        // ★★★ 备选：installer 协程 / 预加载
// ★★★ 备选：installer 协程 / 预加载（8.0.79 类已漂移，改用 [MBAd] 字符串锚点定位）
        // v3.0.525: 实机日志证实 mbad.cm1r.installerCoroutine（b0.Y1）在小程序启动瞬间
        // (18:57:28.598) 被调用并被 setResult(null) 拦截 → 小程序一直加载中。
        // 这两个是"备选"拦截点（P0 总闸 b0.T1 因类漂移未装上），收益低风险高，
        // 改为 DIAG 只观察不拦截。开屏广告显示与否由 K2/AB/sendShouldShowAdIfNeed 布尔闸门控制。
        hookMethodsDiagByString(cl, "[MBAd] create new installer, appId:%s, service:%s", "mbad.cm1r.installerCoroutine");
        hookMethodsDiagByString(cl, "[MBAd] preloadMBAdIfEnable", "mbad.cm1u.preload");

        // ★★★ 宿主 → 小程序消息正门（8.0.79 用 notifyAdEvent show 字符串定位）
        hookMethodsByString(cl, "[MBAd] notifyAdEvent show->mark adType=%s", -1, null, "mbad.cm1m.bizCreated");

        // ★★☆ 封面广告（《十轮终版》第七轮错误 1 修正）：
        //      只拦 cm1.j.a(Object,Object,`xi3.k`)（onRequestInsertCoverView，插不进来就不显示）。
        //      🔴 绝不可拦 e()（onRequestRemoveCoverView = 关掉封面）——拦了让封面广告关不掉。
        //      f() 只 setLayoutParams，d() 返回 show/hide 回调持有者，也不拦。
        // ★★☆ 封面广告（8.0.79 用 [MBAd] B4 bizClient 导航字符串定位插入入口）
        //      🔴 绝不可拦 e()/移除封面方法——拦了让封面广告关不掉。
        hookMethodsByString(cl, "[MBAd] B4: bizClient navigator", -1, null, "mbad.cm1j.cover");
        hookMethodsByString(cl, "[MBAd] keepAlive setPauseType", -1, null, "mbad.cm1k.bindFrameSetView");

        // ★★☆ 激励视频 show/hide（含 bridge 重载 a/b）
        // v3.0.148：改走《激励视频_直接跳过拿奖励_深度分析.md》方案 D —— 完全放行，让广告真播，
        // 由 seekTo(短播) + a0.onReceiveResult 注入 isEnded 拿奖励（见 hookSeekTo / hookRewardAdResult）。
        // 之前此处 setResult(null) 会令广告不播、onReceiveResult 不达，发奖率骤降，故 v3.0.148 起移除。
        // （sAdShowing 标记仍由 hookSeekTo 内 cm1.i.d 的独立钩子维护。）

        // ★★☆ 广告事件总线（JsApiNotifyAdEvent）—— v3.0.526: 实机日志确认仍在 appbrand.jsapi.advertise.p
        //      （字符串锚点 mbad.cm1m.bizCreated 已命中 com.tencent.mm.plugin.appbrand.jsapi.advertise.p.A/3）
        hookMethodsReturnNull(cl, "com.tencent.mm.plugin.appbrand.jsapi.advertise.p",
                java.util.Collections.singleton("A"), "mbad.jsapi.notifyAdEvent");

        // ★★☆ 广告跳转族（MBAD JSAPI 白名单，v3.0.526: 确认未迁 type 包，统一软 hook A/E/I/C）
        String[] jsApi = {
                "com.tencent.mm.plugin.appbrand.jsapi.advertise.o",
                "com.tencent.mm.plugin.appbrand.jsapi.advertise.s",
                "com.tencent.mm.plugin.appbrand.jsapi.channels.f0",
                "com.tencent.mm.plugin.appbrand.jsapi.channels.w",
                "com.tencent.mm.plugin.appbrand.jsapi.j7",
                "com.tencent.mm.plugin.appbrand.jsapi.y7",
                "com.tencent.mm.plugin.appbrand.jsapi.profile.h",
        };
        java.util.Set<String> apiMethods = new java.util.HashSet<>(
                java.util.Arrays.asList("A", "E", "I", "C"));
        for (String c : jsApi) {
            hookMethodsReturnNull(cl, c, apiMethods, "mbad.jsapi.jump");
        }

        // ★★☆ 数据层（innerPullAds / CGI）
        // v3.0.146：补漏第十一部分——bk.j0.lj() = handleAdOpen（广告点击跳转执行者），
        // 原只钩了 mj()（innerPullAds）漏了这个正门。日志锚点：
        // "handleAdOpen try open, pkg:" / "open install page, canvasId:"
        hookMethodsReturnNull(cl, "bk.j0",
                new java.util.HashSet<>(java.util.Arrays.asList("mj", "lj")), "mbad.data");
        hookMethodsReturnNull(cl, "dk.b",
                java.util.Collections.singleton("call"), "mbad.cgi.call");

        // ★☆☆ 上报（让广告主收不到数据）
        hookMethodsReturnNull(cl, "bk3.a",
                java.util.Collections.singleton("t"), "mbad.bk3a.fullLinkReport");

        // ★☆☆ 上报（8.0.79 用 JsApiNotifyAdEvent 字符串锚点，存在于 classes29/54/69/75）
        hookMethodsByString(cl, "MicroMsg.JsApiNotifyAdEvent[MBAd]", -1, null, "mbad.innerReport");
        // ★☆☆ 广告关系追踪（防"杀宿主"误判）（8.0.79 用 sendEventToMBBiz 字符串锚点）
        hookMethodsByString(cl, "[MBAd] sendEventToMBBiz mMBADInstaller is null", -1, null, "mbad.adTracker");

        // ★☆☆ 广告落地页（点中也不落地）
        hookAdLandingPages(cl);

        // 激励视频秒过（可选，双保险）
        hookSeekTo(cl);

        // v3.0.148：激励视频拿奖励 —— 方案 D-2（isEnded 注入）
        hookRewardAdResult(cl);
    }

    // ============================================================
    // 1.9 激励视频真跳过（seekTo 秒过）——《第六部分》+ 第十轮最终代码
    // ============================================================

    /** 广告展示中标记（60 秒超时防多开串号）。 */
    private static volatile boolean sAdShowing = false;
    private static volatile long sAdShowAt = 0L;
    /** 已 seek 过的播放器实例（弱引用防泄漏）。 */
    private static final java.util.Set<Object> sSeeked =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Object, Boolean>());

    /**
     * v3.0.521：激励视频秒过（8.0.79 字符串锚点版）。
     * v3.0.148：改方案 D-1（短播）—— seekTo(duration-3000) 而非 seekTo(duration)，
     * 留 3 秒让 play1s / ValidExpose 上报，配合 hookRewardAdResult 的 isEnded 注入拿奖励。
     *
     * <p>8.0.79 旧短名 tl3.g / po3.o / cm1.i 已全部漂移（jadx 实锤：tl3.g 变成依赖注入枚举、
     * po3.o 变成 Iterator、cm1.i 变成 Runnable），旧签名找不到任何方法。本版改用 MBAD 唯一
     * 字符串锚点定位真实类：
     * <ul>
     *   <li>门控：{@code "[MBAd] reward video show -> markAdAction appId=%s"} /
     *       {@code "[MBAd] reward video hide -> clearAdAction appId=%s"}（唯一命中 om1.i，
     *       MagicAdMiniProgramInstaller 的 onRequestShow/onRequestHide）</li>
     *   <li>进度回调：{@code "onVideoPlayProgressMs"}（唯一命中 pm3.g.h(lp3.o, long, long)，
     *       TPPlayer 监听器）</li>
     * </ul>
     * 完全绕过混淆短名，DexKit 缓存命中后秒装。</p>
     */
    private static void hookSeekTo(ClassLoader cl) {
        // 1) 广告门控：reward video show/hide 维护 sAdShowing（60 秒超时防多开串号）
        hookSeekGate(cl, "[MBAd] reward video show -> markAdAction appId=%s", true);
        hookSeekGate(cl, "[MBAd] reward video hide -> clearAdAction appId=%s", false);

        // 2) 播放器进度回调：onVideoPlayProgressMs 唯一命中 pm3.g.h(lp3.o, long, long)
        try {
            List<String> sigs = DexKitHelper.findMethodsByString(cl, null, "onVideoPlayProgressMs");
            if (sigs == null || sigs.isEmpty()) {
                LogWriter.log(TAG, "[WARN] seekTo: no method strings 'onVideoPlayProgressMs' (cache miss?)");
                return;
            }
            int hooked = 0;
            for (String sig : sigs) {
                String[] parsed = parseSig(sig);
                if (parsed == null) continue;
                Class<?> c = loadClass(cl, parsed[0]);
                if (c == null) continue;
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(parsed[1])) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    if (m.getParameterCount() != 3) continue;
                    if (pts[1] != long.class || pts[2] != long.class) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!sEnabled || !sAdShowing) return;
                            if (System.currentTimeMillis() - sAdShowAt > 60_000L) return;
                            try {
                                Object mp = param.args[0];
                                if (mp == null || sSeeked.contains(mp)) return;
                                long duration = (long) XposedHelpers.callMethod(mp, "getDurationMs");
                                long position = (long) param.args[1];
                                if (duration <= 3000 || position < 500) return;
                                XposedHelpers.callMethod(mp, "seekTo", duration - 3000);
                                sSeeked.add(mp);
                                markTriggered("seekTo.rewardAd");
                                LogWriter.log(TAG, "seekTo: reward ad seekTo(" + (duration - 3000) + ") <- " + position);
                            } catch (Throwable ignored) {}
                        }
                    });
                    hooked++;
                }
            }
            if (hooked > 0) {
                LogWriter.log(TAG, "seekTo: installed progress hook (" + hooked + "): " + sigs);
            } else {
                LogWriter.log(TAG, "[WARN] seekTo: no 3-param method for 'onVideoPlayProgressMs': " + sigs);
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "[WARN] seekTo err: " + t);
        }
    }

    /** 用 MBAD reward video show/hide 字符串锚点定位门控方法并维护 sAdShowing。 */
    private static void hookSeekGate(final ClassLoader cl, final String anchor, final boolean show) {
        try {
            List<String> sigs = DexKitHelper.findMethodsByString(cl, null, anchor);
            if (sigs == null || sigs.isEmpty()) {
                LogWriter.log(TAG, "[WARN] seekTo gate: no method strings '" + anchor + "'");
                return;
            }
            int hooked = 0;
            for (String sig : sigs) {
                String[] parsed = parseSig(sig);
                if (parsed == null) continue;
                Class<?> c = loadClass(cl, parsed[0]);
                if (c == null) continue;
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(parsed[1])) continue;
                    if (m.getParameterCount() != 1) continue; // View 参数
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!sEnabled) return;
                            sAdShowing = show;
                            if (show) sAdShowAt = System.currentTimeMillis();
                        }
                    });
                    hooked++;
                }
            }
            if (hooked > 0) {
                LogWriter.log(TAG, "seekTo gate '" + anchor + "' -> " + (show ? "SHOW" : "HIDE") + " (" + hooked + ")");
            } else {
                LogWriter.log(TAG, "[WARN] seekTo gate: no hookable method for '" + anchor + "'");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "[WARN] seekTo gate err: " + t);
        }
    }

    // ============================================================
    // 1.10 激励视频拿奖励（isEnded 注入）——《激励视频_直接跳过拿奖励_深度分析.md》方案 D-2
    // ============================================================

    /**
     * v3.0.521：激励视频「看完」事实注入（8.0.79 版）。
     *
     * <p>原理（《激励视频_直接跳过拿奖励_深度分析.md》第七部分）：微信不负责发奖，发奖由小程序业务代码
     * 依据 {@code onClose({isEnded})} 决定。seekTo 只能过"播放器完成"门，过不了"基础库 JS 的 isEnded 事件"门。
     * 本钩子在 {@code com.tencent.mm.plugin.type.jsapi.channels.a0.onReceiveResult(ProcessResult)} 到达时，
     * 把 errCode 字段 {@code d} 置 0，并把 protobuf 反馈 {@code e}（nd5.da3）注入
     * {@code rewardedDuration=99999} + {@code feedbackInfo=[{"isEnded":true}]}，让小程序
     * {@code onClose} 收到 {@code {isEnded:true}} 直接发奖。</p>
     *
     * <p>8.0.79：旧类 com.tencent.mm.plugin.appbrand.jsapi.channels.a0 已删除，新实现为
     * com.tencent.mm.plugin.type.jsapi.channels.a0（implements type.ipc.u），结果类型
     * OpenChannelsRewardedVideoAdResult 字段：d=errCode、e=nd5.da3（protobuf：
     * field0=rewardedDuration int、field1=feedbackInfo String/JSON）。</p>
     *
     * <p>关键：不 setResult(null)，放行原流程把结果吐给小程序；与 {@link #hookSeekTo}（短播）联动构成方案 D。</p>
     */
    private static void hookRewardAdResult(ClassLoader cl) {
        try {
            Class<?> a0 = loadClass(cl, "com.tencent.mm.plugin.type.jsapi.channels.a0");
            Class<?> resultType = loadClass(cl,
                    "com.tencent.mm.plugin.type.ipc.AppBrandProxyUIProcessTask$ProcessResult");
            if (a0 == null || resultType == null) {
                LogWriter.log(TAG, "[WARN] rewardAd: a0/resultType null");
                return;
            }
            Method m = null;
            try { m = findMethodInHierarchy(a0, "onReceiveResult", resultType); } catch (Throwable ignored) {}
            if (m == null) {
                LogWriter.log(TAG, "[WARN] rewardAd: a0.onReceiveResult(null)");
                return;
            }
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    try {
                        Object result = param.args[0];
                        if (result == null) return;
                        // errCode -> 0（字段 d）
                        XposedHelpers.setIntField(result, "d", 0);
                        // protobuf 反馈 e（nd5.da3）：field0=rewardedDuration，field1=feedbackInfo(JSON 串)
                        Object fb = XposedHelpers.getObjectField(result, "e");
                        if (fb != null) {
                            XposedHelpers.callMethod(fb, "set", 0, 99999);
                            XposedHelpers.callMethod(fb, "set", 1, "[{\"isEnded\":true}]");
                        }
                        markTriggered("rewardAd.isEnded");
                        LogWriter.log(TAG, "rewardAd: isEnded injected (d=0, rewardedDuration=99999)");
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "rewardAd: installed");
        } catch (Throwable t) {
            LogWriter.log(TAG, "[WARN] rewardAd err: " + t);
        }
    }

/** v3.0.138：按方法名集合批量 hook 并返回默认值（任意参数签名，签名漂移静默跳过）。
     *  v3.0.141：不能再一律 setResult(null) —— 若目标方法返回原始类型（boolean/int 等），
     *  调用方自动拆箱会抛 NPE，被微信内部捕获后表现为「小程序加载打不开」。
     *  改为按返回类型给安全默认值：boolean→false、数值→0、void→不拦截、引用→null。
     *  v3.0.142：跳过抽象方法（Xposed 禁止 hook 抽象方法，直接 hook 会抛
     *  IllegalArgumentException 并中断剩余方法的 hook）。
     *  v3.0.142b：void 方法同样必须 setResult(null) 阻止方法体执行——
     *  之前 block==null 时跳过，导致 void 广告方法照常跑（如 setPageView 类副作用）。 */
    private static void hookMethodsReturnNull(final ClassLoader cl, final String clsName,
                                              final java.util.Set<String> names, final String tag) {
        Class<?> c = loadClass(cl, clsName);
        if (c == null) {
            LogWriter.log(TAG, "[WARN] " + tag + ": class null (" + clsName + ")");
            return;
        }
        int hooked = 0;
        int abstractSkipped = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!names.contains(m.getName())) continue;
            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                abstractSkipped++;
                continue;
            }
            m.setAccessible(true);
            final Object block = defaultValueFor(m);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    param.setResult(block);
                    markTriggered(tag);
                }
            });
            hooked++;
        }
        if (abstractSkipped > 0) {
            LogWriter.log(TAG, tag + ": skip abstract methods x" + abstractSkipped + " in " + clsName);
        }
        LogWriter.log(TAG, "hooked " + clsName + " methods(" + names + ") as " + tag + " x" + hooked);
    }

    /** 按方法返回类型生成安全默认值；void 返回 null（对 void 方法 setResult(null)
     *  同样会阻止方法体执行，是合法的 Xposed 用法）。 */
    private static boolean hasMethod(Class<?> cls, String name, int paramCount) {
        if (cls == null) return false;
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name)
                    && (paramCount < 0 || m.getParameterCount() == paramCount)) return true;
        }
        return false;
    }

    private static Object defaultValueFor(Method m) {
        Class<?> rt = m.getReturnType();
        if (rt == boolean.class) return Boolean.FALSE;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == short.class) return (short) 0;
        if (rt == byte.class) return (byte) 0;
        if (rt == float.class) return 0f;
        if (rt == double.class) return 0d;
        if (rt == char.class) return '\0';
        return null;
    }

    // ============================================================
    // 2. 朋友圈（SNS Timeline）
    // ============================================================

    private static void hookSnsTimeline(ClassLoader cl) {
        // 2.1 ★主★ AdSnsInfoStorageLogic.insert(List,List,boolean,int) -> 空实现（广告永不入库）
        //     DexKit 锚点: "insert, forbid ad" + "MicroMsg.AdSnsInfoStorageLogic"
        //     v3.0.469: 字符串候选会误选内部类 w（LinkedHashMap lambda）。权威类名
        //     com.tencent.mm.plugin.sns.model.x 存在且含 i/4 方法，优先直接使用。
        String storageLogic = "com.tencent.mm.plugin.sns.model.x";
        Class<?> xCls = loadClass(cl, storageLogic);
        if (xCls == null || !hasMethod(xCls, "i", 4)) {
            storageLogic = resolveClassByStrings(cl, new String[]{
                    "MicroMsg.AdSnsInfoStorageLogic"
            }, "com.tencent.mm.plugin.sns.model.x");
        } else {
            LogWriter.log(TAG, "class via authoritative: " + storageLogic);
        }
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

        // 2.4 ★辅★ 新版时间线: ImproveSnsInfo.isAd() -> false（权威类名 dl4.p，含 isAd()）
        String improve = "dl4.p";
        Class<?> improveCls = loadClass(cl, improve);
        if (improveCls == null || !hasMethod(improveCls, "isAd", 0)) {
            improve = resolveClassByStrings(cl, new String[]{
                    "ImproveSnsInfo"
            }, "dl4.p");
        }
        hookReturnConst(cl, improve, "isAd", false, "sns.ImproveSnsInfo.isAd");

        // 2.5 ★兜底★ ConsecutiveAdDataImproveHelper.c(List) 清空返回
        String consecutive = resolveClassByStrings(cl, new String[]{
                "ConsecutiveAdDataImproveHelper"
        }, "cc4.o4");
        hookSnsConsecutiveAd(cl, consecutive);

        // 2.6 ★第十一部分★ FinderAdJumpHelper 广告跳转执行器 6 方法 -> null
        //     日志锚点："FinderAdJumpHelper" / "is_from_ad" / "doJumpFinderFeedsDetailUI"
        //     8.0.78 短名 cc4.s4；8.0.79 短名 wc4.s4（com.tencent.mm.plugin.sns.ad.helper.FinderAdJumpHelper）。
        String finderJump = resolveClassByStrings(cl, new String[]{
                "FinderAdJumpHelper", "doJumpFinderFeedsDetailUI"
        }, WeChatVersion.isNew() ? "wc4.s4" : "cc4.s4");
        if (finderJump != null) {
            hookMethodsReturnNull(cl, finderJump,
                    new java.util.HashSet<>(java.util.Arrays.asList("a", "b", "d", "e", "g", "h")),
                    "finderAdJump");
        }
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
                            markTriggered("sns.handleNormalResp");
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
                            markTriggered("sns.consecutiveAd");
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
                    markTriggered("finder.insertAll");
                } catch (Throwable ignored) {}
            }
        }, "finder.insertAll");

        // 3.2 ★主★ insert(lu2/u5,int,boolean) before: adFlag != 0 -> return -1
        hookMethodByParamCountSafe(cl, loader, "insert", 3, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length < 1) return;
                    if (isFinderAd(param.args[0], cl)) {
                        param.setResult(-1);
                        markTriggered("finder.insert");
                    }
                } catch (Throwable ignored) {}
            }
        }, "finder.insert");

        // 3.3 直播广告（第八/九轮新增：n0.b / ds2.j.u / ds2.w.invoke / h3.b）
        hookFinderLiveAds(cl);

        // 3.4 视频号评论区广告（《视频号评论区广告_完整拦截方案.md》）
        //     评论区走独立 DataBuffer（FinderCommentLoader=model.z），与信息流 BaseFinderFeedLoader 不通，
        //     需单独 hook 数据层(b 批量/a 单条) + 渲染层(getAdvertisement_info/getPromotion_info 返 null)。
        hookFinderComments(cl);
    }

    /**
     * v3.0.144：视频号直播广告（《十轮审查合并终版》第八/九轮新增）。
     *
     * <ul>
     *   <li>{@code n0.b(protobuf f)}：FinderLiveAdVideoPlugin 轮询回调（再审 1 复核通过）。</li>
     *   <li>{@code ds2.j.u()}：全屏 loadVideo（再审 4 复核通过）。</li>
     *   <li>{@code ds2.w.invoke()}：小窗 loadLivingVideoInMiniMode（再审 4 复核通过）。</li>
     *   <li>{@code finder.live.widget.h3.b(FinderLiveGuideFollowAdInfo)}：直播关注引导广告（第九轮新增）。</li>
     *   <li>🔴 不碰 {@code finder.live.plugin.jf0}（FinderLiveTXLivePlayerPlugin，直播播放器本体，拦了直播看不了）。</li>
     * </ul>
     */
    private static void hookFinderLiveAds(ClassLoader cl) {
        // n0.b(com.tencent.mm.protobuf.f) -> null（直播广告视频轮询回调）
        try {
            Class<?> n0 = loadClass(cl, "com.tencent.mm.plugin.finder.live.plugin.n0");
            if (n0 != null) {
                for (Method m : n0.getDeclaredMethods()) {
                    if (!m.getName().equals("b")) continue;
                    if (m.getParameterCount() != 1) continue;
                    Class<?> p0 = m.getParameterTypes()[0];
                    if (p0.getName().equals("com.tencent.mm.protobuf.f")
                            || p0.getName().endsWith(".f")) {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                if (sEnabled) {
                                    param.setResult(null);
                                    markTriggered("finder.liveAdVideo");
                                }
                            }
                        });
                        LogWriter.log(TAG, "hooked " + n0.getName() + ".b(protobuf) as finder.liveAdVideo");
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // ds2.j.u() -> null（全屏 loadVideo）
        hookMethodsReturnNull(cl, "ds2.j",
                java.util.Collections.singleton("u"), "finder.liveLoadVideo");
        // ds2.w.invoke() -> null（小窗 loadLivingVideoInMiniMode）
        hookMethodsReturnNull(cl, "ds2.w",
                java.util.Collections.singleton("invoke"), "finder.liveMiniVideo");
        // v3.0.146：ds2.x.invoke() -> null（onVideoSizeChange，直播广告横屏全屏）。
        // 日志锚点："onVideoSizeChange [" / "isLandscapeVideo validVideoSize:"
        hookMethodsReturnNull(cl, "ds2.x",
                java.util.Collections.singleton("invoke"), "finder.liveVideoSize");

        // finder.liveGuideAd —— v3.0.558 反编译确认：8.0.79 中 h3.b(b12) 是直播间列表/榜单刷新方法
        // （getList -> notifyDataSetChanged），原 FinderLiveGuideFollowAdInfo 引导广告方法已不存在。
        // 强行拦截会损坏直播间列表，故移除该 hook。
        try {
            Class<?> h3 = loadClass(cl, "com.tencent.mm.plugin.finder.live.widget.h3");
            if (h3 != null) {
                boolean hooked = false;
                for (Method m : h3.getDeclaredMethods()) {
                    if (!m.getName().equals("b")) continue;
                    if (m.getParameterCount() != 1) continue;
                    Class<?> p0 = m.getParameterTypes()[0];
                    String n = p0.getName();
                    String base = n.substring(n.lastIndexOf('.') + 1);
                    if (base.contains("Guide") || base.contains("FollowAd") || base.contains("LiveAd")) {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                if (sEnabled) {
                                    param.setResult(null);
                                    markTriggered("finder.liveGuideAd");
                                }
                            }
                        });
                        hooked = true;
                        LogWriter.log(TAG, "hooked " + h3.getName() + ".b(" + base + ") as finder.liveGuideAd");
                        break;
                    }
                }
                if (!hooked) {
                    StringBuilder sig = new StringBuilder();
                    for (Method m : h3.getDeclaredMethods()) {
                        if (m.getName().equals("b") || m.getParameterCount() == 1) {
                            StringBuilder ps = new StringBuilder("(");
                            for (Class<?> t : m.getParameterTypes()) {
                                if (ps.length() > 1) ps.append(',');
                                String tn = t.getName();
                                ps.append(tn.substring(tn.lastIndexOf('.') + 1));
                            }
                            ps.append(')');
                            sig.append('b').append(ps).append(' ');
                        }
                    }
                    LogWriter.log(TAG, "MISS finder.liveGuideAd: h3.b 无可匹配重载, 现有候选: " + sig);
                }
            } else {
                LogWriter.log(TAG, "MISS finder.liveGuideAd: class com.tencent.mm.plugin.finder.live.widget.h3 未找到");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "MISS finder.liveGuideAd: " + t.getClass().getName() + ": " + t.getMessage());
        }
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
                "com.tencent.mm.plugin.sns.ad.landingpage.SnsAdNativeLandingPagesMMUI",
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

    /**
     * v3.0.167：视频号评论区广告拦截（《视频号评论区广告_完整拦截方案.md》）。
     *
     * <p>评论区链路与信息流完全独立：{@code com.tencent.mm.plugin.finder.feed.model.z}
     * （FinderCommentLoader）是评论 DataBuffer，{@code b(List,boolean,boolean)} 批量插入、
     * {@code a(...)} 单条插入。广告评论判定：{@code item.d -> tn0.p0() -> FinderCommentInfo}
     * 的 {@code getAdvertisement_info / getPromotion_info} 任一非空即广告。</p>
     *
     * <ul>
     *   <li>数据层根治：hook {@code b} 批量前迭代剔除广告评论。</li>
     *   <li>单条兜底：hook {@code a} 广告列入参时返回 -1 拒绝。</li>
     *   <li>v3.0.170 修复：移除渲染层 getter 恒返 null 兜底。该兜底把
     *       {@code FinderCommentInfo.getAdvertisement_info / getPromotion_info} 全局置 null，
     *       与 {@link #isAdComment} 用同一 getter 判广告自相矛盾，导致数据层 b/a 判定永远 false。
     *       按文档“要彻底去掉就用 3.2（数据层删除）”回归数据层根治。</li>
     * </ul>
     */
    private static void hookFinderComments(final ClassLoader cl) {
        hookMethodByParamCountSafe(cl, "com.tencent.mm.plugin.finder.feed.model.z", "b", 3,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!sEnabled || param.args.length < 1) return;
                            if (!(param.args[0] instanceof List)) return;
                            List<?> list = (List<?>) param.args[0];
                            if (list == null || list.isEmpty()) return;
                            int before = list.size();
                            Iterator<?> it = list.iterator();
                            while (it.hasNext()) {
                                if (isAdComment(it.next(), cl)) it.remove();
                            }
                            if (list.size() != before) {
                                LogWriter.log(TAG, "FinderCommentLoader.b removed "
                                        + (before - list.size()) + " ad comments");
                                markTriggered("finder.comment.b");
                            }
                        } catch (Throwable ignored) {}
                    }
                }, "finder.comment.b");

        hookMethodByParamCountSafe(cl, "com.tencent.mm.plugin.finder.feed.model.z", "a", 1,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!sEnabled || param.args.length < 1) return;
                            if (isAdComment(param.args[0], cl)) {
                                param.setResult(-1);
                                markTriggered("finder.comment.a");
                            }
                        } catch (Throwable ignored) {}
                    }
                }, "finder.comment.a");

        // v3.0.170：移除渲染层 getter 返 null 兜底，避免与 isAdComment 检测互斥（见类注释）。
    }

    /** 判定一条评论是否为广告/推广评论（文档 §3.1）。
     *  item 一般是 lu2.b1（评论包装），其 d 字段是 storage.tn0；tn0.p0() 取 FinderCommentInfo。 */
    private static boolean isAdComment(Object item, ClassLoader cl) {
        try {
            if (item == null) return false;
            Object tn0 = XposedHelpers.getObjectField(item, "d");
            if (tn0 == null) return false;
            Object info = XposedHelpers.callMethod(tn0, "p0");
            if (info == null) return false;
            if (XposedHelpers.callMethod(info, "getAdvertisement_info") != null) return true;
            if (XposedHelpers.callMethod(info, "getPromotion_info") != null) return true;
            return false;
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
        // v3.0.461：跨版本字符串锚点优先。R8 每版都会改混淆短名（lc1.d / jk4.p / cc4.o4），
        // 但类内的 Log TAG / 协议串是稳定常量。先按字符串定位，短名只作最后兜底。
        for (String kw : anchors) {
            if (kw == null || kw.isEmpty()) continue;
            try {
                List<String> cands = DexKitHelper.findClassesByString(cl, kw);
                if (cands != null && !cands.isEmpty()) {
                    String hit = pickPreferred(cands);
                    LogWriter.log(TAG, "class hit: " + hit + " via '" + kw + "'");
                    return hit;
                }
            } catch (Throwable ignored) {}
        }
        // 字符串未命中：再尝试短名（仅当前版本有效）
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
        Class<?> c = WxResolver.resolve(cl, name);
        if (c != null) return c;
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

    /** 定位指定名字、零参/任意参的单个方法并 hook（方法名+参数个数匹配）。
     *  v3.0.141：blockResult 传 null 时按返回类型适配默认值，避免原始类型拆箱 NPE
     *  （如 lc1.b0.l 返回 boolean、sns.insert 返回 int 等）。
     *  v3.0.142b：void 方法同样 setResult(null) 阻止执行。 */
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
            final Object block = blockResult != null ? blockResult : defaultValueFor(m);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    param.setResult(block);
                    markTriggered(tag);
                }
            });
            hooked++;
        }
        if (hooked == 0) LogWriter.log(TAG, "[WARN] " + tag + ": no method " + methodName
                + "/" + paramCount + " in " + clsName);
        else LogWriter.log(TAG, "hooked " + clsName + "." + methodName + " as " + tag + " (" + hooked + ")");
    }

    /** 定位指定名字的方法（无参）并固定返回值。 */
    /**
     * 跨版本稳定：按方法内字符串锚点定位方法并固定返回值（替代按短名类+方法名）。
     * R8 混淆会改类名/方法名，但方法内的日志 TAG/协议串是稳定常量。
     * 用 findMethodsByString(cl, null, anchor) 直接拿到「包含该字符串的方法」，
     * 从签名解析出类名+方法名后 hook，完全绕过短名漂移。
     */
    private static void hookMethodsByString(final ClassLoader cl, final String anchor,
                                           final int paramCount, final Object result, final String tag) {
        hookMethodsByString(cl, anchor, paramCount, result, tag, false);
    }

    /** 字符串锚点通用 hook。booleanReturnOnly=true 时只 hook 返回 boolean 的方法
     * （用于开屏广告布尔闸门，避免 setResult 硬塞给 void/int 方法导致小程序卡死）。 */
    private static void hookMethodsByString(final ClassLoader cl, final String anchor,
                                           final int paramCount, final Object result, final String tag,
                                           final boolean booleanReturnOnly) {
        hookMethodsByString(cl, anchor, paramCount, result, tag, booleanReturnOnly, null);
    }

    /** v3.0.525: 支持排除类（如 f7——AppBrandSplashAd 字符串同时命中的非广告闸门方法）。
     *  被排除的方法改为安装 DIAG 钩子（只记录调用栈，不拦截），用于确认其真实身份。 */
    private static void hookMethodsByString(final ClassLoader cl, final String anchor,
                                           final int paramCount, final Object result, final String tag,
                                           final boolean booleanReturnOnly, final String[] excludeClasses) {
        try {
            List<String> sigs = DexKitHelper.findMethodsByString(cl, null, anchor);
            if (sigs == null || sigs.isEmpty()) {
                LogWriter.log(TAG, "[WARN] " + tag + ": no method strings '" + anchor + "' (cache miss?)");
                return;
            }
            int hooked = 0;
            LinkedHashSet<String> hookedDesc = new LinkedHashSet<>();
            for (String sig : sigs) {
                String[] parsed = parseSig(sig);
                if (parsed == null) continue;
                String clsName = parsed[0];
                String methodName = parsed[1];
                if (clsName == null || methodName == null) continue;
                // 排除类（子串匹配，如 "f7" 命中 com.tencent.mm.plugin.appbrand.f7）
                if (excludeClasses != null) {
                    boolean excluded = false;
                    for (String ex : excludeClasses) {
                        if (clsName.contains(ex)) { excluded = true; break; }
                    }
                    if (excluded) {
                        hookDiag(cl, clsName, methodName, paramCount, tag + ".excluded");
                        continue;
                    }
                }
                Class<?> c = loadClass(cl, clsName);
                if (c == null) continue;
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(methodName)) continue;
                    if (paramCount >= 0 && m.getParameterCount() != paramCount) continue;
                    if (booleanReturnOnly && m.getReturnType() != boolean.class) continue;
                    m.setAccessible(true);
                    // v3.0.524: 类型安全。result 类型与方法返回类型不匹配时（如 int 方法塞
                    // Boolean false、Object 方法塞 boolean），改用 defaultValueFor 生成
                    // 安全默认值，避免 ClassCastException 被微信内部吞掉导致小程序打不开。
                    final Object block = typeCompatible(result, m.getReturnType())
                            ? result : defaultValueFor(m);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!sEnabled) return;
                            p.setResult(block);
                            markTriggered(tag);
                        }
                    });
                    hooked++;
                    hookedDesc.add(clsName + "." + methodName + "/" + m.getParameterCount());
                }
            }
            if (hooked > 0) {
                LogWriter.log(TAG, "hooked by-string '" + anchor + "' as " + tag + " (" + hooked + "): " + hookedDesc);
            } else {
                LogWriter.log(TAG, "[WARN] " + tag + ": string '" + anchor + "' no hookable method (param=" + paramCount + ")");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "[WARN] " + tag + " err: " + t);
        }
    }

    /** v3.0.525: 只记录不拦截的诊断钩子。用于确认字符串锚点命中的非目标方法真实身份，
     *  避免盲目 setResult 误伤小程序加载流程。每个方法只打印前 3 次调用栈。 */
    private static void hookDiag(final ClassLoader cl, final String clsName, final String methodName,
                                 final int paramCount, final String tag) {
        try {
            Class<?> c = loadClass(cl, clsName);
            if (c == null) return;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (paramCount >= 0 && m.getParameterCount() != paramCount) continue;
                m.setAccessible(true);
                final String desc = clsName + "." + methodName + "(" + m.getParameterCount()
                        + ") ret=" + m.getReturnType().getName();
                final java.util.concurrent.atomic.AtomicInteger cnt = new java.util.concurrent.atomic.AtomicInteger();
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (cnt.incrementAndGet() > 3) return;
                        StringBuilder sb = new StringBuilder();
                        StackTraceElement[] st = Thread.currentThread().getStackTrace();
                        int n = Math.min(st.length, 10);
                        for (int i = 3; i < n; i++) {
                            sb.append("\n    at ").append(st[i]);
                        }
                        LogWriter.log(TAG, "DIAG " + tag + " called: " + desc + " stack:" + sb);
                    }
                });
                LogWriter.log(TAG, "DIAG hooked " + desc + " (no block)");
                break;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "DIAG hook err " + clsName + "." + methodName + ": " + t);
        }
    }

    /** v3.0.525: 按字符串锚点安装诊断钩子（不拦截）。用于 mbad installer/preload 等
     *  被确认会阻塞小程序加载的"备选拦截点"——先放行并观察其调用上下文。 */
    private static void hookMethodsDiagByString(final ClassLoader cl, final String anchor, final String tag) {
        try {
            List<String> sigs = DexKitHelper.findMethodsByString(cl, null, anchor);
            if (sigs == null || sigs.isEmpty()) {
                LogWriter.log(TAG, "[WARN] " + tag + ": diag no method strings '" + anchor + "'");
                return;
            }
            for (String sig : sigs) {
                String[] parsed = parseSig(sig);
                if (parsed == null) continue;
                hookDiag(cl, parsed[0], parsed[1], -1, tag);
            }
        } catch (Throwable ignored) {}
    }

    /** 检查 result 能否安全 setResult 到返回类型 rt 上（void/原始类型需精确匹配）。 */
    private static boolean typeCompatible(Object result, Class<?> rt) {
        if (result == null) return false;
        if (rt == Void.TYPE) return false;
        if (rt.isPrimitive()) {
            if (rt == boolean.class) return result instanceof Boolean;
            if (rt == byte.class) return result instanceof Byte;
            if (rt == short.class) return result instanceof Short;
            if (rt == int.class) return result instanceof Integer;
            if (rt == long.class) return result instanceof Long;
            if (rt == float.class) return result instanceof Float;
            if (rt == double.class) return result instanceof Double;
            if (rt == char.class) return result instanceof Character;
            return false;
        }
        return rt.isInstance(result);
    }

    private static String[] parseSig(String sig) {
        if (sig == null) return null;
        int lp = sig.indexOf('(');
        if (lp <= 0) return null;
        String head = sig.substring(0, lp);
        int dot = head.lastIndexOf('.');
        if (dot <= 0 || dot >= head.length() - 1) return null;
        return new String[]{ head.substring(0, dot), head.substring(dot + 1) };
    }

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
                    markTriggered(tag);
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
     * <p>与 {@link #hookActivityFinishOnCreateQuiet} 的区别：目标类不存在时打印 [WARN]，用于落地页等确定性目标。
     * v3.0.142b：用 findMethodExact 替代 getDeclaredMethod（子类继承父类 onCreate 时后者找不到）。</p>
     */
    private static void hookActivityFinishOnCreate(final ClassLoader cl, final String clsName,
                                                   final String tag) {
        Class<?> c = loadClass(cl, clsName);
        if (c == null) { LogWriter.log(TAG, "[WARN] " + tag + ": class null (" + clsName + ")"); return; }
        try {
            Method onCreate = findMethodInHierarchy(c, "onCreate", android.os.Bundle.class);
            onCreate.setAccessible(true);
            XposedBridge.hookMethod(onCreate, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    try { XposedHelpers.callMethod(param.thisObject, "finish"); markTriggered(tag); } catch (Throwable ignored) {}
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
     * 用于 {@code AppBrandAdUI} 的 5 个进程槽位（slot0/1..4）批量挂载——多数设备只启用部分槽位。
     * v3.0.142b：用 findMethodExact 替代 getDeclaredMethod——AppBrandAdUI1..4 的 onCreate
     * 声明在父类中，getDeclaredMethod 找不到（日志 [WARN] onCreate err NoSuchMethodException）。</p>
     */
    private static void hookActivityFinishOnCreateQuiet(final ClassLoader cl, final String clsName,
                                                        final String tag) {
        Class<?> c = loadClass(cl, clsName);
        if (c == null) return;   // 该槽位类不存在（设备未启用），静默跳过
        try {
            Method onCreate = findMethodInHierarchy(c, "onCreate", android.os.Bundle.class);
            onCreate.setAccessible(true);
            XposedBridge.hookMethod(onCreate, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    try { XposedHelpers.callMethod(param.thisObject, "finish"); markTriggered(tag); } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "hooked " + clsName + ".onCreate -> finish as " + tag);
        } catch (Throwable t) {
            LogWriter.log(TAG, "[WARN] " + tag + ": onCreate err " + t);
        }
    }

    /** 沿类继承链查找声明方法。getDeclaredMethod 不查找父类，而 Activity 子类（如
     *  AppBrandAdUI1..4）的 onCreate 常声明在父类中；getMethod 又只查 public 且会被
     *  ProGuard 混淆影响。沿父类链逐个 getDeclaredMethod 最稳妥。 */
    private static Method findMethodInHierarchy(Class<?> c, String name, Class<?>... pts)
            throws NoSuchMethodException {
        for (Class<?> cur = c; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            try {
                Method m = cur.getDeclaredMethod(name, pts);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(name);
    }

    private static String simpleName(String full) {
        int i = full.lastIndexOf('.');
        return i >= 0 ? full.substring(i + 1) : full;
    }

    // ============================================================
    // 运行期触发日志（供用户实测定位「钩错地方」）
    // ============================================================

    /** 已打印过触发日志的 hook 点（去重，防止高频广告路径刷屏）。 */
    private static final java.util.Set<String> sTriggeredLog =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /** 仅在首次触发时打印「TRIGGERED: tag」，让日志能区分「钩子没装上」与「装上了但广告路径根本不调用」。 */
    private static void markTriggered(String tag) {
        if (sTriggeredLog.add(tag)) {
            LogWriter.log(TAG, "TRIGGERED: " + tag);
        }
    }
}