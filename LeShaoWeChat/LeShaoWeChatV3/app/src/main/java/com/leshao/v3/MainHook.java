package com.leshao.v3;

import android.app.Activity;
import android.os.Bundle;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Process;

import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.AntiRecallHook;
import com.leshao.v3.hook.AutoForwardHook;
import com.leshao.v3.hook.AntiDetectionHook;
import com.leshao.v3.hook.AutoMethodDetector;
import com.leshao.v3.hook.AutoRemark;
import com.leshao.v3.hook.BatchAddFriend;
import com.leshao.v3.hook.BatchInviteGroupsHook;
import com.leshao.v3.hook.BatchMessage;
import com.leshao.v3.hook.CallFeatures;
import com.leshao.v3.hook.ChatBackup;
import com.leshao.v3.hook.ChatFooterEnhance;
import com.leshao.v3.hook.ChatGroupHook;
import com.leshao.v3.hook.ChatGroupUiInjector;
import com.leshao.v3.hook.ChatVoiceSwitchHook;
import com.leshao.v3.hook.ChatUICustom;
import com.leshao.v3.hook.ContactChangeLog;
import com.leshao.v3.hook.ConvPrivacy;
import com.leshao.v3.hook.DeleteDetect;
import com.leshao.v3.hook.DexKitHelper;
import com.leshao.v3.hook.FakeAddSource;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import com.leshao.v3.hook.FriendRequestHook;
import com.leshao.v3.hook.GroupMemberResolver;
import com.leshao.v3.hook.GroupMemberTools;
import com.leshao.v3.wm.WmEntry;
import com.leshao.v3.wm.hook.WmChatHook;
import com.leshao.v3.hook.HideContactFields;
import com.leshao.v3.hook.HookManager;
import com.leshao.v3.hook.LoginMonitor;
import com.leshao.v3.hook.MessageHook;
import com.leshao.v3.hook.MsgExport;
import com.leshao.v3.hook.WanQunGroupHook;
import com.leshao.v3.hook.NotifyCustom;
import com.leshao.v3.hook.PrivacyFeatures;
import com.leshao.v3.hook.RedPacketAlert;
import com.leshao.v3.hook.RedPacketHook;
import com.leshao.v3.hook.SearchEnhance;
import com.leshao.v3.hook.TtsVoiceSender;
import com.leshao.v3.hook.ShakeCustom;
import com.leshao.v3.hook.SignatureDump;
import com.leshao.v3.hook.SnsFeatures;
import com.leshao.v3.hook.StickyEnhance;
import com.leshao.v3.hook.TabCustom;
import com.leshao.v3.hook.TypingIndicator;
import com.leshao.v3.hook.UnreadBadge;
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

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "LeShaoV3";
    private static final String WX_PKG = "com.tencent.mm";
    private static volatile boolean sMainInitialized = false;
    private static final List<String> sModuleResults = new ArrayList<>();
    private static int sModuleTotal = 0;
    private static int sModuleOk = 0;
    private static int sModuleFail = 0;
    private static long sStartTime = 0;

    public MainHook() {}

public static final String MODULE_BUILD = "v960";

    /** 模块构建版本号(整数)。随 MODULE_BUILD 同步递增, 用于 DexKit 扫描缓存失效 */

    public static final int MODULE_VERSION_CODE = 960;

    private static volatile Thread.UncaughtExceptionHandler sPrevCrashHandler = null;
    private static volatile boolean sCrashHandlerInstalled = false;

    private static void installCrashHandler() {
        rearmCrashHandler();
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
    public static synchronized void rearmCrashHandler() {
        try {
            Thread.UncaughtExceptionHandler cur = Thread.getDefaultUncaughtExceptionHandler();
            if (cur == (Thread.UncaughtExceptionHandler) sCrashLogger) return;
            sPrevCrashHandler = cur;
            Thread.setDefaultUncaughtExceptionHandler(sCrashLogger);
            if (!sCrashHandlerInstalled) {
                sCrashHandlerInstalled = true;
                LogWriter.log(TAG, "崩溃链已装 (prev=" + (cur == null ? "null" : cur.getClass().getName()) + ")");
            }
        } catch (Throwable ignored) {}
    }

    private static final Thread.UncaughtExceptionHandler sCrashLogger = (thread, throwable) -> {
        try {
            if (throwable == null) return;
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            throwable.printStackTrace(pw);
            pw.flush();
            LogWriter.log("CRASH", "thread=" + thread.getName() + " msg=" + throwable.getMessage());
            LogWriter.logSync("CRASH", sw.toString());
        } catch (Throwable ignored) {}
        Thread.UncaughtExceptionHandler prev = sPrevCrashHandler;
        if (prev != null) {
            try { prev.uncaughtException(thread, throwable); } catch (Throwable ignored) {}
        } else {
            try { android.os.Process.killProcess(android.os.Process.myPid()); } catch (Throwable ignored) {}
            try { System.exit(10); } catch (Throwable ignored) {}
        }
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!WX_PKG.equals(lpparam.packageName)) return;

        LogWriter.init();

        boolean isMain = WX_PKG.equals(lpparam.processName);
        if (!isMain) return;

        if (sMainInitialized) return;
        sMainInitialized = true;
        sStartTime = System.currentTimeMillis();

        int wxVersion = 0;
        try { wxVersion = XposedHelpers.getIntField(lpparam.appInfo, "versionCode"); }
        catch (Throwable t) {}

        int userId = Process.myUid() / 100000;
        String instanceLabel = userId == 0 ? "主微信" : ("分身微信(user" + userId + ")");
        LogWriter.log(TAG, "=== LeShaoV3 模块加载开始 ===");
        LogWriter.log(TAG, "模块构建版本: " + MODULE_BUILD);
        LogWriter.log(TAG, "WeChat versionCode=" + wxVersion + " uid=" + Process.myUid() + " userId=" + userId);
        LogWriter.log(TAG, "当前实例: " + instanceLabel + ", process=" + lpparam.processName);
        installCrashHandler();

        final int wxVerCode = wxVersion;
        final ClassLoader cl = lpparam.classLoader;

        installGlobalActivityHook(cl);

        try {
            ContextManager.init(cl, lpparam.appInfo.sourceDir);
            captureModuleApkPath(lpparam);
            ContextManager.hookAttachBaseContext(lpparam);

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
            safeRun("MessageHook", () -> MessageHook.hook(cl));
            safeRun("TtsVoiceSender", () -> TtsVoiceSender.hook(cl));
            safeRun("CornerMenu", () -> CornerMenu.hook(cl));
            safeRun("ChatRoomMuteHelper", () -> ChatRoomMuteHelper.hook(cl));
            safeRun("ChatFooterLongPressMenu", () -> ChatFooterLongPressMenu.hook(cl));
            safeRun("WmChatHook.p06Bypass", () -> WmChatHook.hookP06BypassEarly(cl));
            safeRun("ChatGroupUiInjector", () -> ChatGroupUiInjector.hook(cl));
            ContextManager.setOnReadyCallback(new Runnable() {
                @Override
                public void run() {
                    try {
                        Context ctx = ContextManager.getAppContext();
                        LogWriter.log(TAG, "--- ContextManager.onReady 回调开始 ---");
                        rearmCrashHandler();

                        safeRun("SignatureDump", () -> SignatureDump.dump(cl));
                        safeRun("TTSBroadcaster", () -> TTSBroadcaster.init(ctx));
                        safeRun("VoiceAutoPlay", () -> VoiceAutoPlay.hook(cl));
                        safeRun("ModuleConfig.initWxid", () -> ModuleConfig.initWxid(ctx));
                        safeRun("VoiceHistoryDbHelper", () -> VoiceHistoryDbHelper.getInstance(ctx)
                                .deleteExpired(System.currentTimeMillis() - 30L * 86400000L));

                        safeRun("AntiDetectionHook", () -> AntiDetectionHook.hook(cl));
                        safeRun("AntiRecallHook", () -> HookManager.register("AntiRecallHook", AntiRecallHook::hook));
                        safeRun("RedPacketHook", () -> HookManager.register("RedPacketHook", RedPacketHook::hook));
                        safeRun("RedPacketAlert", () -> HookManager.register("RedPacketAlert", () -> RedPacketAlert.hook(cl)));
                        safeRun("ChatGroupHook", () -> HookManager.register("ChatGroupHook", () -> ChatGroupHook.hook(cl)));
                        safeRun("FriendRequestHook", () -> FriendRequestHook.hook(cl));

                        safeRun("VoiceForwardHook", () -> HookManager.register("VoiceForwardHook", VoiceForwardHook::hook));
                        safeRun("AutoForwardHook", () -> HookManager.register("AutoForwardHook", () -> AutoForwardHook.hook(cl)));
                        safeRun("WeChatUpdateBlocker", () -> HookManager.register("WeChatUpdateBlocker", () -> WeChatUpdateBlocker.hook(cl)));
                        safeRun("TypingIndicator", () -> HookManager.register("TypingIndicator", () -> TypingIndicator.hook(cl)));
                        safeRun("ChatFooterEnhance", () -> HookManager.register("ChatFooterEnhance", () -> ChatFooterEnhance.hook(cl)));
                        safeRun("ChatVoiceSwitchHook", () -> ChatVoiceSwitchHook.init(cl));
                        safeRun("ChatUICustom", () -> HookManager.register("ChatUICustom", () -> ChatUICustom.hook(cl)));
                        safeRun("BatchMessage", () -> HookManager.register("BatchMessage", () -> BatchMessage.hook(cl)));
                        safeRun("AutoRemark", () -> HookManager.register("AutoRemark", () -> AutoRemark.hook(cl)));
                        safeRun("SearchEnhance", () -> HookManager.register("SearchEnhance", () -> SearchEnhance.hook(cl)));
                        safeRun("NotifyCustom", () -> HookManager.register("NotifyCustom", () -> NotifyCustom.hook(cl)));
                        safeRun("UnreadBadge", () -> HookManager.register("UnreadBadge", () -> UnreadBadge.hook(cl)));
                        safeRun("TabCustom", () -> HookManager.register("TabCustom", () -> TabCustom.hook(cl)));
                        safeRun("ShakeCustom", () -> HookManager.register("ShakeCustom", () -> ShakeCustom.hook(cl)));
                        safeRun("StickyEnhance", () -> HookManager.register("StickyEnhance", () -> StickyEnhance.hook(cl)));
                        safeRun("DeleteDetect", () -> HookManager.register("DeleteDetect", () -> DeleteDetect.hook(cl)));
                        safeRun("CallFeatures", () -> HookManager.register("CallFeatures", () -> CallFeatures.hook(cl)));

                        safeRun("SnsFeatures", () -> HookManager.register("SnsFeatures", () -> SnsFeatures.hook(cl)));

                        safeRun("PrivacyFeatures", () -> HookManager.register("PrivacyFeatures", () -> PrivacyFeatures.hook(cl)));
                        safeRun("LoginMonitor", () -> HookManager.register("LoginMonitor", () -> LoginMonitor.hook(cl)));
                        safeRun("HideContactFields", () -> HookManager.register("HideContactFields", () -> HideContactFields.hook(cl)));
                        safeRun("ConvPrivacy", () -> HookManager.register("ConvPrivacy", () -> ConvPrivacy.hook(cl)));

                        safeRun("ContactChangeLog", () -> HookManager.register("ContactChangeLog", () -> ContactChangeLog.hook(cl)));
                        safeRun("MsgExport", () -> HookManager.register("MsgExport", () -> MsgExport.hook(cl)));
                        safeRun("BatchInviteGroups", () -> HookManager.register("BatchInviteGroups", () -> BatchInviteGroupsHook.hook(cl)));
                        safeRun("ChatBackup", () -> HookManager.register("ChatBackup", () -> ChatBackup.hook(cl)));
                        safeRun("WmEntry", () -> WmEntry.injectAll(cl));

                        safeRun("WanQunGroupHook", () -> {
                            WanQunGroupHook.init(cl);
                            WanQunGroupHook.hookReceive(cl);
                        });

                        safeRun("FakeAddSource", () -> FakeAddSource.hook(cl));
                        safeRun("BatchAddFriend", () -> BatchAddFriend.hook(cl));

                        safeRun("GroupMemberTools", () -> {
                            try {
                                GroupMemberTools.init(cl);
                                GroupMemberTools.hook(cl);
                            } catch (Throwable t) {
                                LogWriter.log(TAG, "[MainHook] GroupMemberTools FAIL: " + t.getMessage());
                            }
                        });

                        safeRun("LeshaoAI", () -> {
                            com.leshao.ai.hook.HookEntry.appClassLoader = cl;
                            com.leshao.ai.util.DexKitBridgeHolder.init(cl);
                            com.leshao.ai.hook.wechat.WeChatHook.install(lpparam);
                            LogWriter.log(TAG, "[MainHook] LeshaoAI 模块已加载");
                        });

                        safeRun("AutoMethodDetector", () -> {
                            DexKitHelper.waitKernelInit(cl, new DexKitHelper.KernelReadyCallback() {
                                @Override
                                public void onKernelReady(DexKitBridge bridge) {
                                    MethodMatcher matcher = MethodMatcher.create()
                                        .paramCount(1)
                                        .paramTypes("java.lang.String")
                                        .returnType("void");

                                    XC_MethodHook realHook = new XC_MethodHook() {
                                        @Override
                                        protected void afterHookedMethod(MethodHookParam param) {
                                            LogWriter.log("AutoDetect",
                                                "Business method called: "
                                                + param.method.getDeclaringClass().getName());
                                        }
                                    };

                                    AutoMethodDetector.detect(bridge, cl, matcher,
                                        realHook, "example", 30000L);
                                }
                            });
                        });
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "[MainHook] FATAL in onReadyCallback: " + t.getClass().getSimpleName()
                            + " " + t.getMessage());
                    } finally {
                        try { HookManager.activateAll(); }
                        catch (Throwable t) { LogWriter.log(TAG, "[MainHook] activateAll FAIL: " + t.getMessage()); }
                        printModuleSummary();
                    }
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "LeShaoV3: FATAL during init: " + t.getMessage());
        }
    }

    private static void installGlobalActivityHook(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String cls = param.thisObject.getClass().getName();
                        if (cls.startsWith("com.tencent.mm.")) {
                            LogWriter.log("ActivityLife", "onCreate: " + cls);
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
                            LogWriter.log("ActivityLife", "onResume: " + cls);
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
                            LogWriter.log("ActivityLife", "onPause: " + cls);
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
                            LogWriter.log("ActivityLife", "onDestroy: " + cls);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "全局 Activity 生命周期 Hook 已安装");
        } catch (Throwable t) {
            LogWriter.log(TAG, "全局 Activity 生命周期 Hook 失败: " + t.getMessage());
        }
    }

    private static void printModuleSummary() {
        long elapsed = System.currentTimeMillis() - sStartTime;
        LogWriter.log(TAG, "=== 模块加载汇总 ===");
        LogWriter.log(TAG, "总模块数: " + sModuleTotal + "  成功: " + sModuleOk + "  失败: " + sModuleFail);
        LogWriter.log(TAG, "加载耗时: " + elapsed + "ms");
        for (String r : sModuleResults) {
            LogWriter.log(TAG, r);
        }
        LogWriter.log(TAG, "=== 模块加载完成 ===");
    }

    private static void safeRun(String name, Runnable task) {
        sModuleTotal++;
        long started = System.currentTimeMillis();
        LogWriter.log(TAG, "[Module] START " + name);
        try {
            task.run();
            sModuleOk++;
            sModuleResults.add("  [OK] " + name);
            LogWriter.log(TAG, "[Module] OK " + name + " elapsed="
                    + (System.currentTimeMillis() - started) + "ms");
        } catch (Throwable t) {
            sModuleFail++;
            String err = t.getClass().getSimpleName() + ": " + t.getMessage();
            sModuleResults.add("  [FAIL] " + name + " - " + err);
            LogWriter.log(TAG, "[Module] FAIL " + name + " elapsed="
                    + (System.currentTimeMillis() - started) + "ms: " + err);
        }
    }
}
