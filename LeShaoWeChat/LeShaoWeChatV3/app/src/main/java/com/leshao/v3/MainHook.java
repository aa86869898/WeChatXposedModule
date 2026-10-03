package com.leshao.v3;

import android.app.Activity;
import android.os.Bundle;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Process;

import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.AutoForwardHook;
import com.leshao.v3.hook.AntiDetectionHook;
import com.leshao.v3.hook.ChatBubbleHook;
import com.leshao.v3.hook.FavVoiceForwardHook;
import com.leshao.v3.hook.ForwardLimitHook;
import com.leshao.v3.hook.WxForwardReplaceHook;
import com.leshao.v3.hook.ChatGroupHook;
import com.leshao.v3.hook.ChatGroupUiInjector;
import com.leshao.v3.hook.ChatVoiceSwitchHook;
import com.leshao.v3.hook.DexKitHelper;
import com.leshao.v3.wm.WmEntry;
import com.leshao.v3.wm.hook.WmChatHook;
import com.leshao.v3.hook.HookManager;
import com.leshao.v3.hook.MessageHook;
import com.leshao.v3.hook.MessageMenuHook;
import com.leshao.v3.hook.WanQunGroupHook;
import com.leshao.v3.hook.TtsVoiceSender;
import com.leshao.v3.hook.SignatureDump;
import com.leshao.v3.hook.VoiceForwardHook;
import com.leshao.v3.hook.VoiceAutoPlay;
import com.leshao.v3.hook.WeChatUpdateBlocker;
import com.leshao.v3.db.VoiceHistoryDbHelper;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.service.TTSBroadcaster;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "LeShaoV3";
    private static final String WX_PKG = "com.tencent.mm";
    private static volatile boolean sMainInitialized = false;
    private static final List<String> sModuleResults =
            java.util.Collections.synchronizedList(new ArrayList<>());
    // v1131: deferRun 在线程中自增, 用 AtomicInteger 保证可见性与原子性
    private static final AtomicInteger sModuleTotal = new AtomicInteger(0);
    private static final AtomicInteger sModuleOk = new AtomicInteger(0);
    private static final AtomicInteger sModuleFail = new AtomicInteger(0);
    private static long sStartTime = 0;
    /** onReady 回调专用后台执行器(单线程守护), 避免 DexKit 全量搜索阻塞主线程冷启动。 */
    private static final java.util.concurrent.ExecutorService sOnReadyExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "leshao-onReady");
                t.setDaemon(true);
                return t;
            });

    /** v1131: 每个 Activity 生命周期都写文件日志噪声过大, 默认关闭(仅保留功能性 probe)。 */
    private static final boolean ACTIVITY_LIFECYCLE_LOG = false;

    public MainHook() {}

    public static final String MODULE_BUILD = "v3.0.124";

    /** 模块构建版本号(整数)。随 MODULE_BUILD 同步递增, 用于 DexKit 扫描缓存失效 */

    public static final int MODULE_VERSION_CODE = 30124;

    /** v1079: 当前前台 Activity(onResume 记录/onPause 清除), 供 talker 解析等复用。 */
    private static volatile java.lang.ref.WeakReference<Activity> sResumedActivity;

    public static Activity currentActivity() {
        java.lang.ref.WeakReference<Activity> ref = sResumedActivity;
        return ref == null ? null : ref.get();
    }

    /** 模块编译时间(构建时由 gradle 注入, 缺省回退到本次进程启动时间)。 */
    public static final String MODULE_BUILD_TIME = BuildConfig.BUILD_TIME;

    /**
     * v1131: 崩溃处理器统一收口。
     *
     * <p>历史问题: 本模块与其它模块(如 TtsVoiceSender)各自 setDefaultUncaughtExceptionHandler,
     * 互相把对方 handler 记作 prev, 形成 A→B→A 环并丢失真正的系统 handler。现改为:</p>
     * <ul>
     *   <li>{@link #installCrashHandler()} 幂等: 只在链首安装一次本模块 handler;</li>
     *   <li>{@link #rearmCrashHandler()} 沿 prev 链跳过所有由本模块安装的 handler,
     *       最终委托到真正的系统 KillApplicationHandler;</li>
     *   <li>运行时 AtomicBoolean 防重入, 保证每次崩溃只记一次日志且系统 handler 仍执行。</li>
     * </ul>
     */
    private static volatile Thread.UncaughtExceptionHandler sNextCrashHandler = null;
    private static volatile Thread.UncaughtExceptionHandler sSystemCrashHandler = null;
    private static volatile boolean sCrashHandlerInstalled = false;
    private static final AtomicBoolean sCrashHandling = new AtomicBoolean(false);

    /** 所有未捕获异常的统一记录入口, 供外部模块(TtsVoiceSender 等)调用。 */
    public static void logCrash(Throwable throwable) {
        if (throwable == null) return;
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            throwable.printStackTrace(pw);
            pw.flush();
            LogWriter.log("CRASH", "msg=" + throwable.getMessage());
            LogWriter.logSync("CRASH", sw.toString());
        } catch (Throwable ignored) {}
    }

    /** 幂等安装: 已在链首则直接返回; 否则重新挂载并链到真正的系统 handler。 */
    public static synchronized void installCrashHandler() {
        try {
            Thread.UncaughtExceptionHandler cur = Thread.getDefaultUncaughtExceptionHandler();
            if (cur == sCrashLogger) {
                if (sNextCrashHandler == null) sNextCrashHandler = resolveCrashDelegate(cur);
                return;
            }
            Thread.UncaughtExceptionHandler delegate = resolveCrashDelegate(cur);
            if (isSystemCrashHandler(delegate)) sSystemCrashHandler = delegate;
            if (delegate == null) delegate = sSystemCrashHandler;
            if (delegate == sCrashLogger) return; // 禁止成环
            sNextCrashHandler = delegate;
            Thread.setDefaultUncaughtExceptionHandler(sCrashLogger);
            if (!sCrashHandlerInstalled) {
                sCrashHandlerInstalled = true;
                LogWriter.log(TAG, "崩溃链已装 (next=" + (delegate == null ? "null" : delegate.getClass().getName()) + ")");
            }
        } catch (Throwable ignored) {}
    }

    /** 微信/Bugly 可能覆盖默认 handler, onReady 后再装一次; 跳过本模块 handler, 链到系统 handler。 */
    public static synchronized void rearmCrashHandler() {
        try {
            Thread.UncaughtExceptionHandler cur = Thread.getDefaultUncaughtExceptionHandler();
            if (cur == sCrashLogger) return; // 已在链首, 幂等
            Thread.UncaughtExceptionHandler delegate = resolveCrashDelegate(cur);
            if (isSystemCrashHandler(delegate)) sSystemCrashHandler = delegate;
            if (delegate == null) delegate = sSystemCrashHandler;
            if (delegate == sCrashLogger) return; // 禁止成环
            sNextCrashHandler = delegate;
            Thread.setDefaultUncaughtExceptionHandler(sCrashLogger);
        } catch (Throwable ignored) {}
    }

    /** 沿 prev 链跳过本模块安装的 handler, 返回下一个应委托的 handler。 */
    private static Thread.UncaughtExceptionHandler resolveCrashDelegate(Thread.UncaughtExceptionHandler start) {
        java.util.Set<Thread.UncaughtExceptionHandler> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Thread.UncaughtExceptionHandler cur = start;
        while (cur != null) {
            if (!seen.add(cur)) return sSystemCrashHandler; // 检测到环, 退回系统 handler
            if (isOwnCrashHandler(cur)) { cur = sNextCrashHandler; continue; }
            return cur;
        }
        return sSystemCrashHandler;
    }

    private static boolean isOwnCrashHandler(Thread.UncaughtExceptionHandler h) {
        return h == sCrashLogger;
    }

    private static boolean isSystemCrashHandler(Thread.UncaughtExceptionHandler h) {
        if (h == null) return false;
        String n = h.getClass().getName();
        return n.contains("KillApplicationHandler") || n.contains("RuntimeInit");
    }

    /** 委托系统 handler 终止进程; 链异常时兜底 killProcess。 */
    private static void dispatchCrashToSystem(Thread thread, Throwable throwable) {
        Thread.UncaughtExceptionHandler sys = sSystemCrashHandler;
        if (sys != null && !isOwnCrashHandler(sys)) {
            try { sys.uncaughtException(thread, throwable); return; } catch (Throwable ignored) {}
        }
        try { android.os.Process.killProcess(android.os.Process.myPid()); } catch (Throwable ignored) {}
        try { System.exit(10); } catch (Throwable ignored) {}
    }

    /**
     * 捕获模块自身 APK 路径。LSPosed 运行时会向 LoadPackageParam 注入 modulePath 字段（编译期 api-82 无此字段，用反射读）。
     */
    private static void captureModuleApkPath(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Object v = XposedHelpers.getObjectField(lp, "modulePath");
            if (v != null) ContextManager.setModuleApkPath(String.valueOf(v));
        } catch (Throwable t) {
            LogWriter.log(TAG, "modulePath capture fail: " + t.getMessage());
        }
    }

    /** 微信/Bugly 可能在 Application 初始化时覆盖默认 handler，onReady 后再装一次并链到其已有 handler */
    private static final Thread.UncaughtExceptionHandler sCrashLogger = (thread, throwable) -> {
        if (!sCrashHandling.compareAndSet(false, true)) {
            // 重入: 链上出现环, 只保证系统 handler 执行, 不再重复记录
            dispatchCrashToSystem(thread, throwable);
            return;
        }
        logCrash(throwable);
        Thread.UncaughtExceptionHandler next = sNextCrashHandler;
        if (next != null && !isOwnCrashHandler(next)) {
            try { next.uncaughtException(thread, throwable); return; } catch (Throwable ignored) {}
        }
        dispatchCrashToSystem(thread, throwable);
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!WX_PKG.equals(lpparam.packageName)) return;

        final String processName = lpparam.processName;
        final boolean isMainProcess = WX_PKG.equals(processName);
        final boolean isPushProcess = processName != null && processName.endsWith(":push");

        // v1131 进程门控: :appbrand0/1、:sandbox、:toolsmp 等子进程不需要模块功能,
        // 直接 return 最稳(不写文件日志、不做 UI 注入/重扫描, 避免无谓开销与残留副作用)。
        // :push 进程需保留消息接收入库(MessageHook/WanQunGroupHook.hookReceive), 走最小初始化。
        // 主进程 com.tencent.mm 完整执行(分身 user 的 processName 同为 com.tencent.mm, 天然放行)。
        if (!isMainProcess && !isPushProcess) {
            try {
                de.robv.android.xposed.XposedBridge.log("[LeShaoV3] skip sub-process: " + processName);
            } catch (Throwable ignored) {}
            return;
        }

        LogWriter.init();

        if (sMainInitialized) return;
        sMainInitialized = true;
        sStartTime = System.currentTimeMillis();

        int wxVersion = 0;
        try { wxVersion = XposedHelpers.getIntField(lpparam.appInfo, "versionCode"); }
        catch (Throwable t) {}

        // v962: 实例身份统一走 InstanceManager(主微信/系统分身隔离, 见《微信模块隔离.md》);
        // attachBaseContext 前 userId 走 uid 兜底计算, prefs 状态以 onReady 时为准
        int userId = InstanceManager.userId();
        String instanceLabel = InstanceManager.label();
        LogWriter.log(TAG, "=== LeShaoV3 模块加载开始 ===");
        LogWriter.log(TAG, "模块构建版本: " + MODULE_BUILD);
        LogWriter.log(TAG, "WeChat versionCode=" + wxVersion + " uid=" + Process.myUid() + " userId=" + userId);
        LogWriter.log(TAG, "当前实例: " + instanceLabel + ", process=" + lpparam.processName);
        installCrashHandler();

        final int wxVerCode = wxVersion;
        final ClassLoader cl = lpparam.classLoader;

        if (isPushProcess) {
            hookPushProcess(lpparam, cl, wxVerCode);
            return;
        }

        installGlobalActivityHook(cl);
        // v1015: 全局窗口返回栈（二级返回首页 / 三级返回上一层，覆盖所有 Dialog/PopupWindow）
        safeRun("UiBackInstaller", () -> com.leshao.v3.ui.UiBackInstaller.install(cl));

        try {
            ContextManager.init(cl, lpparam.appInfo.sourceDir);
            captureModuleApkPath(lpparam);
            ContextManager.hookAttachBaseContext(lpparam);

            // v1024: WCDB 逃密 —— 必须在微信打开 EnMicroMsg.db 之前 hook WCDB openDatabase,
            // 捕获微信自开的 DB 实例与密钥(绕开 CsoLoader native 库未加载问题)。
            safeRun("DatabaseProvider.initEarly", () -> com.leshao.v3.db.DatabaseProvider.initEarly(cl));

             safeRun("DexKitHelper.setVersionCode", () -> DexKitHelper.setVersionCode(wxVerCode));
             safeRun("DexKitHelper.setModuleVersion", () -> DexKitHelper.setModuleVersion(MODULE_VERSION_CODE));
             safeRun("DexKitHelper.hookApplication", () -> DexKitHelper.hookApplication(lpparam));
              DexKitHelper.setProgressCallback(new DexKitHelper.ScanProgressCallback() {
                  @Override
                  public void onProgress(int percent, String status, String detail) {
                      com.leshao.v3.ui.DexKitScanDialog.updateProgress(percent, status, detail);
                  }
                  @Override
                  public void onComplete() {
                      // Don't auto-dismiss - let user close the dialog
                      com.leshao.v3.ui.DexKitScanDialog.onScanComplete();
                  }
              });
             safeRun("DexKitScanDialog.initSteps", () -> {
                 String[] steps = {
                     "J1 服务定位器", "P06 核心类", "数据库接口", "设备标识 (IMEI)", "CsoLoader",
                     "通讯录存储", "语音 API", "e9/a21 类", "头像服务", "标签存储",
                     "会话列表适配器", "聊天窗口入口",
                     "长按事件", "列表滚动", "菜单注入", "菜单实现类"
                 };
                 String[] details = {
                     "查找静态 s(Class) 方法", "查找 P06 核心类", "查找数据库打开接口",
                     "查找设备标识类", "查找 CsoLoader", "查找通讯录存储类",
                     "查找语音 API 类", "查找 e9/a21 类", "查找头像服务类",
                     "查找标签存储类", "查找会话列表适配器", "查找聊天窗口入口",
                     "查找长按事件入口", "查找列表滚动入口", "查找菜单注入入口", "查找菜单实现类"
                 };
                 com.leshao.v3.ui.DexKitScanDialog.initSteps(steps, details);
             });
            deferRun("MessageHook", () -> MessageHook.hook(cl));
            deferRun("TtsVoiceSender", () -> TtsVoiceSender.hook(cl));
            deferRun("MessageMenuHook", () -> MessageMenuHook.hook(cl));
            deferRun("ChatFooterLongPressMenu", () -> ChatFooterLongPressMenu.hook(cl));
            deferRun("WmChatHook.p06Bypass", () -> WmChatHook.hookP06BypassEarly(cl));
            deferRun("ChatGroupUiInjector", () -> ChatGroupUiInjector.hook(cl));
            deferRun("MsgForgeHook", () -> com.leshao.v3.hook.MsgForgeHook.hook(cl));
            deferRun("RedPacketHook", () -> com.leshao.v3.hook.RedPacketHook.hook(cl));
            ContextManager.setOnReadyCallback(new Runnable() {
                @Override
                public void run() {
                    sOnReadyExecutor.execute(() -> {
                        try {
                            // v962: 实例总开关门控 —— 关闭时本实例不加载任何功能 Hook
                            // (日志/崩溃链/悬浮球菜单等早期 Hook 仍保留, 用户可经悬浮球进入设置重新开启)
                            LogWriter.log(TAG, "实例状态: " + InstanceManager.label()
                                    + " enabled=" + InstanceManager.isEnabled()
                                    + " dataDir=" + InstanceManager.dataDir());
                            if (!InstanceManager.isEnabled()) {
                                LogWriter.log(TAG, "实例总开关已关闭(userId=" + InstanceManager.userId()
                                        + "), 跳过全部功能加载");
                                return;
                            }
                            Context ctx = ContextManager.getAppContext();
                            LogWriter.log(TAG, "--- ContextManager.onReady 回调开始 ---");
                            rearmCrashHandler();

                            // v1024: 监听 WCDB 捕获结果, DB 就绪后主动加载联系人(联系人选择器使用)
                            try {
                                com.leshao.v3.db.DatabaseProvider.setOnDbReadyListener((db, pwd) -> {
                                    LogWriter.log(TAG, "[MainHook] DB captured listener fired, loading contacts");
                                    ContactRepository.loadAsync(null);
                                });
                            } catch (Throwable t) {
                                LogWriter.log(TAG, "[MainHook] DatabaseProvider listener err: " + t.getMessage());
                            }

                            safeRun("SignatureDump", () -> SignatureDump.dump(cl));
                            safeRun("TTSBroadcaster", () -> TTSBroadcaster.init(ctx));
                            safeRun("VoiceAutoPlay", () -> VoiceAutoPlay.hook(cl));
                            safeRun("ModuleConfig.initWxid", () -> ModuleConfig.initWxid(ctx));
                            safeRun("VoiceHistoryDbHelper", () -> VoiceHistoryDbHelper.getInstance(ctx)
                                    .deleteExpired(System.currentTimeMillis() - 30L * 86400000L));

                            safeRun("AntiDetectionHook", () -> AntiDetectionHook.hook(cl));
                            // v1146: 消息防撤回（文档《WeChat_AntiRevoke_Reverse.md》H1/H3 方案）
                            safeRun("AntiRecallHook", () -> HookManager.register("AntiRecallHook",
                                    () -> com.leshao.v3.hook.AntiRecallHook.hook(cl)));
                            safeRun("ChatGroupHook", () -> HookManager.register("ChatGroupHook", () -> ChatGroupHook.hook(cl)));

                            safeRun("VoiceForwardHook", () -> HookManager.register("VoiceForwardHook", VoiceForwardHook::hook));
                            safeRun("AutoForwardHook", () -> HookManager.register("AutoForwardHook", () -> AutoForwardHook.hook(cl)));
                            safeRun("WeChatUpdateBlocker", () -> HookManager.register("WeChatUpdateBlocker", () -> WeChatUpdateBlocker.hook(cl)));
                            safeRun("ChatVoiceSwitchHook", () -> ChatVoiceSwitchHook.init(cl));

                            // 新增（严格按需求文档实现）
                            safeRun("LeftTopEntryHook", () -> com.leshao.v3.hook.LeftTopEntryHook.hook(cl));
                            safeRun("ChatFooterBarHook", () -> com.leshao.v3.hook.ChatFooterBarHook.hook(cl));

                            // 自定义气泡（文档方案A）+ 收藏语音转发（文档路线A）
                            safeRun("ChatBubbleHook", () -> HookManager.register("ChatBubbleHook",
                                    () -> ChatBubbleHook.hook(cl)));
                            safeRun("FavVoiceForwardHook", () -> HookManager.register("FavVoiceForwardHook",
                                    () -> FavVoiceForwardHook.hook(cl)));
                            // v3.0.89: 突破转发/群发多选联系人 9 人上限（文档方案A：hook Intent.getIntExtra）
                            safeRun("ForwardLimitHook", () -> HookManager.register("ForwardLimitHook",
                                    () -> ForwardLimitHook.hook(cl)));
                            // v3.0.x: 微信原生转发按钮替换为模块联系人选择器（文档《微信原生转发按钮替换》）
                            safeRun("WxForwardReplaceHook", () -> HookManager.register("WxForwardReplaceHook",
                                    () -> WxForwardReplaceHook.hook(cl)));

                            safeRun("WmEntry", () -> WmEntry.injectAll(cl));

                            safeRun("WanQunGroupHook", () -> {
                                WanQunGroupHook.init(cl);
                                WanQunGroupHook.hookReceive(cl);
                            });

                            // 右上角"+"菜单注入：一键免打扰 / 一键解除免打扰
                            safeRun("PlusMenuInjector", () -> HookManager.register("PlusMenuInjector",
                                    () -> com.leshao.v3.hook.PlusMenuInjector.hook(cl)));
                            // 去广告（更多功能 -> 去你妈的广告）
                            safeRun("AdBlockerHook", () -> HookManager.register("AdBlockerHook",
                                    () -> com.leshao.v3.hook.AdBlockerHook.hook(cl)));
                            // 一键拉群：聊天输入框上方快捷栏"拉群"按钮
                            safeRun("ChatFooterInviteHook", () -> {
                                com.leshao.v3.hook.BatchInviteManager.init(cl);
                                HookManager.register("ChatFooterInviteHook",
                                        () -> com.leshao.v3.hook.ChatFooterInviteHook.hook(cl));
                            });

                            // v998: 已移除"添加好友伪装来源"(FakeAddSource)

                            // v980: LeshaoAI 依赖 DexKit 联网解析, 原实现直接在主线程同步执行(阻塞 294ms),
                            // 其 hook 目标(聊天菜单)在后段 UI 才加载, 改为后台线程延迟安装, 既不丢 hook 时机,
                            // 又消除启动期主线程阻塞。
                            deferRun("LeshaoAI", () -> {
                                com.leshao.ai.hook.HookEntry.appClassLoader = cl;
                                com.leshao.ai.util.DexKitBridgeHolder.init(cl);
                                com.leshao.ai.hook.wechat.WeChatHook.install(lpparam);
                                LogWriter.log(TAG, "[MainHook] LeshaoAI 模块已加载");
                            });
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "[MainHook] FATAL in onReadyCallback: " + t.getClass().getSimpleName()
                                + " " + t.getMessage());
                        } finally {
                            int hookTasksRegistered = HookManager.pendingCount();
                            try { HookManager.activateAll(); }
                            catch (Throwable t) { LogWriter.log(TAG, "[MainHook] activateAll FAIL: " + t.getMessage()); }
                            printModuleSummary(hookTasksRegistered);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "LeShaoV3: FATAL during init: " + t.getMessage());
        }
    }

    /**
     * v1131: :push 进程最小初始化 —— 仅保留接收入库相关 Hook(MessageHook + 万群管理),
     * 不做 UI 注入 / Activity 生命周期日志 / DexKitScanDialog 进度回调, 降低子进程开销。
     */
    private static void hookPushProcess(XC_LoadPackage.LoadPackageParam lpparam,
                                        final ClassLoader cl, final int wxVerCode) {
        LogWriter.log(TAG, "push 进程最小初始化开始 process=" + lpparam.processName);
        try {
            ContextManager.init(cl, lpparam.appInfo.sourceDir);
            captureModuleApkPath(lpparam);
            ContextManager.hookAttachBaseContext(lpparam);
            safeRun("DexKitHelper.setVersionCode", () -> DexKitHelper.setVersionCode(wxVerCode));
            safeRun("DexKitHelper.setModuleVersion", () -> DexKitHelper.setModuleVersion(MODULE_VERSION_CODE));
            safeRun("DexKitHelper.hookApplication", () -> DexKitHelper.hookApplication(lpparam));
        } catch (Throwable t) {
            LogWriter.log(TAG, "push init err: " + t.getMessage());
        }
        ContextManager.setOnReadyCallback(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!InstanceManager.isEnabled()) {
                        LogWriter.log(TAG, "push 实例开关关闭, 跳过接收 Hook");
                        return;
                    }
                    safeRun("MessageHook(push)", () -> MessageHook.hook(cl));
                    safeRun("WanQunGroupHook(push)", () -> {
                        WanQunGroupHook.init(cl);
                        WanQunGroupHook.hookReceive(cl);
                    });
                } catch (Throwable t) {
                    LogWriter.log(TAG, "push onReady FATAL: " + t.getMessage());
                } finally {
                    int hookTasksRegistered = HookManager.pendingCount();
                    try { HookManager.activateAll(); } catch (Throwable t) {}
                    printModuleSummary(hookTasksRegistered);
                }
            }
        });
    }

    private static void installGlobalActivityHook(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String cls = param.thisObject.getClass().getName();
                        if (cls.startsWith("com.tencent.mm.")) {
                            if (ACTIVITY_LIFECYCLE_LOG) LogWriter.log("ActivityLife", "onCreate: " + cls);
                            // v1025: onCreate 早于 onResume, 尽早反查真实 ClassLoader
                            com.leshao.v3.db.DatabaseProvider.probeAndRehook(param.thisObject);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String cls = param.thisObject.getClass().getName();
                        if (cls.startsWith("com.tencent.mm.")) {
                            if (ACTIVITY_LIFECYCLE_LOG) LogWriter.log("ActivityLife", "onResume: " + cls);
                            sResumedActivity = new java.lang.ref.WeakReference<>((Activity) param.thisObject);
                            // v1140: 微信切换深色模式会重建 Activity, onResume 时实时重算模块配色
                            com.leshao.v3.ui.AppColors.refresh();
                            // v1025: 从微信 Activity 反查真实 ClassLoader 并重新 hook WCDB
                            com.leshao.v3.db.DatabaseProvider.probeAndRehook(param.thisObject);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String cls = param.thisObject.getClass().getName();
                        if (cls.startsWith("com.tencent.mm.")) {
                            if (ACTIVITY_LIFECYCLE_LOG) LogWriter.log("ActivityLife", "onPause: " + cls);
                            java.lang.ref.WeakReference<Activity> ref = sResumedActivity;
                            if (ref != null && ref.get() == param.thisObject) sResumedActivity = null;
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String cls = param.thisObject.getClass().getName();
                        if (cls.startsWith("com.tencent.mm.")) {
                            if (ACTIVITY_LIFECYCLE_LOG) LogWriter.log("ActivityLife", "onDestroy: " + cls);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "全局 Activity 生命周期 Hook 已安装");
        } catch (Throwable t) {
            LogWriter.log(TAG, "全局 Activity 生命周期 Hook 失败: " + t.getMessage());
        }
    }

    private static void printModuleSummary(int hookTasksRegistered) {
        long elapsed = System.currentTimeMillis() - sStartTime;
        LogWriter.log(TAG, "=== 模块加载汇总 ===");
        // v1131/本次修正: safeRun 只反映"调度"结果(register 仅入队, deferRun 异步);
        // HookManager 的 activateAll 在子线程异步安装, 汇总时通常尚未完成, 故只报告已入队数,
        // 真实安装结果以 HookManager 的 "activateAll DONE: X OK, Y FAIL" 日志为准, 避免虚高/虚报。
        LogWriter.log(TAG, "调度: 总数=" + sModuleTotal.get()
                + "  成功=" + sModuleOk.get() + "  失败=" + sModuleFail.get());
        LogWriter.log(TAG, "HookManager 已注册(入队)=" + hookTasksRegistered
                + "  (异步安装结果见 HookManager activateAll DONE 日志)");
        LogWriter.log(TAG, "加载耗时: " + elapsed + "ms");
        for (String r : sModuleResults) {
            LogWriter.log(TAG, r);
        }
        LogWriter.log(TAG, "=== 模块加载完成 ===");
    }

    private static void safeRun(String name, Runnable task) {
        sModuleTotal.incrementAndGet();
        long started = System.currentTimeMillis();
        LogWriter.log(TAG, "[Module] START " + name);
        try {
            task.run();
            sModuleOk.incrementAndGet();
            sModuleResults.add("  [OK] " + name);
            LogWriter.log(TAG, "[Module] OK " + name + " elapsed="
                    + (System.currentTimeMillis() - started) + "ms");
        } catch (Throwable t) {
            sModuleFail.incrementAndGet();
            String err = t.getClass().getSimpleName() + ": " + t.getMessage();
            sModuleResults.add("  [FAIL] " + name + " - " + err);
            LogWriter.log(TAG, "[Module] FAIL " + name + " elapsed="
                    + (System.currentTimeMillis() - started) + "ms: " + err);
        }
    }

    /**
     * v980: 将耗时模块移出主线程执行 —— onReady 回调运行于 Application.attachBaseContext 主线程,
     * 任何同步 DexKit 解析都会直接拖慢微信冷启动。deferRun 用独立守护线程异步安装 hook,
     * 目标类均为后段 UI(getView/view 相关), 异步安装不丢时机。
     */
    private static void deferRun(String name, Runnable task) {
        sModuleTotal.incrementAndGet();
        LogWriter.log(TAG, "[Module] DEFER " + name);
        Thread t = new Thread(() -> {
            long started = System.currentTimeMillis();
            try {
                task.run();
                sModuleOk.incrementAndGet();
                sModuleResults.add("  [OK] " + name + " (async)");
                LogWriter.log(TAG, "[Module] OK " + name + " elapsed="
                        + (System.currentTimeMillis() - started) + "ms (async)");
            } catch (Throwable e) {
                sModuleFail.incrementAndGet();
                String err = e.getClass().getSimpleName() + ": " + e.getMessage();
                sModuleResults.add("  [FAIL] " + name + " - " + err);
                LogWriter.log(TAG, "[Module] FAIL " + name + " elapsed="
                        + (System.currentTimeMillis() - started) + "ms (async): " + err);
            }
        }, "leshao-defer-" + name);
        t.setDaemon(true);
        t.start();
    }
}
