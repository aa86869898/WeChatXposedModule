package com.leshao.v3;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Process;


import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.AntiRecallHook;
import com.leshao.v3.hook.AntiDetectionHook;
import com.leshao.v3.hook.AutoRemark;
import com.leshao.v3.hook.BatchAddFriend;
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
import com.leshao.v3.hook.FakeAddSource;
import com.leshao.v3.hook.FriendRequestHook;
import com.leshao.v3.hook.GroupFeatures;
import com.leshao.v3.wm.WmEntry;
import com.leshao.v3.hook.HideContactFields;
import com.leshao.v3.hook.HookManager;
import com.leshao.v3.hook.LoginMonitor;
import com.leshao.v3.hook.MessageHook;
import com.leshao.v3.hook.MsgExport;
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
import com.leshao.v3.db.VoiceHistoryDbHelper;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.service.TTSBroadcaster;
import com.leshao.v3.ai.AiConfig;
import com.leshao.v3.ai.ChatHooks;
import com.leshao.v3.ting.TingMusicModule;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "LeShaoV3";
    private static final String WX_PKG = "com.tencent.mm";
    private static volatile boolean sMainInitialized = false;

    public MainHook() {}

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!WX_PKG.equals(lpparam.packageName)) return;

        LogWriter.init();

        boolean isMain = WX_PKG.equals(lpparam.processName);
        if (!isMain) {
            LogWriter.log(TAG, "skip sub-process: " + lpparam.processName);
            return;
        }

        if (sMainInitialized) return;
        sMainInitialized = true;

        int wxVersion = 0;
        try { wxVersion = XposedHelpers.getIntField(lpparam.appInfo, "versionCode"); }
        catch (Throwable t) {}

        int userId = Process.myUid() / 100000;
        String instanceLabel = userId == 0 ? "主微信" : ("分身微信(user" + userId + ")");
        LogWriter.log(TAG, "WeChat versionCode=" + wxVersion + " uid=" + Process.myUid() + " userId=" + userId);
        LogWriter.log(TAG, "当前实例: " + instanceLabel + ", process=" + lpparam.processName);

        final int wxVerCode = wxVersion;
        final ClassLoader cl = lpparam.classLoader;

        try {
            ContextManager.init(cl, lpparam.appInfo.sourceDir);
            ContextManager.hookAttachBaseContext(lpparam);

            // 每个模块独立 try-catch: 任一模块失败不影响其他模块注册与 activateAll
            safeRun("MessageHook", () -> MessageHook.hook(cl));
            safeRun("TtsVoiceSender", () -> TtsVoiceSender.hook(cl));
            safeRun("CornerMenu", () -> CornerMenu.hook(cl));
            safeRun("ChatRoomMuteHelper", () -> ChatRoomMuteHelper.hook(cl));
            safeRun("ChatFooterLongPressMenu", () -> ChatFooterLongPressMenu.hook(cl));
            safeRun("ChatGroupUiInjector", () -> ChatGroupUiInjector.hook(cl));
            ContextManager.setOnReadyCallback(new Runnable() {
                @Override
                public void run() {
                    try {
                        Context ctx = ContextManager.getAppContext();

                        // 每个模块独立 try-catch: 任一模块失败不影响其他模块注册与 activateAll
                        safeRun("SignatureDump", () -> SignatureDump.dump(cl));
                        safeRun("TTSBroadcaster", () -> TTSBroadcaster.init(ctx));
                        safeRun("VoiceAutoPlay", () -> VoiceAutoPlay.hook(cl));
                        safeRun("ModuleConfig.initWxid", () -> ModuleConfig.initWxid(ctx));
                        safeRun("VoiceHistoryDbHelper", () -> VoiceHistoryDbHelper.getInstance(ctx)
                                .deleteExpired(System.currentTimeMillis() - 30L * 86400000L));

                        safeRun("AntiDetectionHook", () -> AntiDetectionHook.hook(cl));
                        safeRun("AntiRecallHook", () -> HookManager.register(AntiRecallHook::hook));
                        safeRun("RedPacketHook", () -> HookManager.register(RedPacketHook::hook));
                        safeRun("RedPacketAlert", () -> HookManager.register(() -> RedPacketAlert.hook(cl)));
                        safeRun("ChatGroupHook", () -> HookManager.register(() -> ChatGroupHook.hook(cl)));
                        safeRun("FriendRequestHook", () -> FriendRequestHook.hook(cl));

                        safeRun("VoiceForwardHook", () -> HookManager.register(VoiceForwardHook::hook));
                        safeRun("TypingIndicator", () -> HookManager.register(() -> TypingIndicator.hook(cl)));
                        safeRun("ChatFooterEnhance", () -> HookManager.register(() -> ChatFooterEnhance.hook(cl)));
                        safeRun("ChatVoiceSwitchHook", () -> ChatVoiceSwitchHook.init(cl));
                        safeRun("ChatUICustom", () -> HookManager.register(() -> ChatUICustom.hook(cl)));
                        safeRun("BatchMessage", () -> HookManager.register(() -> BatchMessage.hook(cl)));
                        safeRun("AutoRemark", () -> HookManager.register(() -> AutoRemark.hook(cl)));
                        safeRun("SearchEnhance", () -> HookManager.register(() -> SearchEnhance.hook(cl)));
                        safeRun("NotifyCustom", () -> HookManager.register(() -> NotifyCustom.hook(cl)));
                        safeRun("UnreadBadge", () -> HookManager.register(() -> UnreadBadge.hook(cl)));
                        safeRun("TabCustom", () -> HookManager.register(() -> TabCustom.hook(cl)));
                        safeRun("ShakeCustom", () -> HookManager.register(() -> ShakeCustom.hook(cl)));
                        safeRun("StickyEnhance", () -> HookManager.register(() -> StickyEnhance.hook(cl)));
                        safeRun("DeleteDetect", () -> HookManager.register(() -> DeleteDetect.hook(cl)));
                        safeRun("CallFeatures", () -> HookManager.register(() -> CallFeatures.hook(cl)));

                        safeRun("SnsFeatures", () -> HookManager.register(() -> SnsFeatures.hook(cl)));

                        safeRun("PrivacyFeatures", () -> HookManager.register(() -> PrivacyFeatures.hook(cl)));
                        safeRun("LoginMonitor", () -> HookManager.register(() -> LoginMonitor.hook(cl)));
                        safeRun("HideContactFields", () -> HookManager.register(() -> HideContactFields.hook(cl)));
                        safeRun("ConvPrivacy", () -> HookManager.register(() -> ConvPrivacy.hook(cl)));

                        safeRun("ContactChangeLog", () -> HookManager.register(() -> ContactChangeLog.hook(cl)));
                        safeRun("GroupFeatures", () -> HookManager.register(() -> GroupFeatures.hook(cl)));
                        safeRun("MsgExport", () -> HookManager.register(() -> MsgExport.hook(cl)));
                        safeRun("ChatBackup", () -> HookManager.register(() -> ChatBackup.hook(cl)));
                        safeRun("WmEntry", () -> WmEntry.injectAll(cl));

                        safeRun("FakeAddSource", () -> FakeAddSource.hook(cl));
                        safeRun("BatchAddFriend", () -> BatchAddFriend.hook(cl));

                        safeRun("AiConfig+ChatHooks", () -> {
                            AiConfig.init(ctx);
                            ChatHooks.install(lpparam);
                            LogWriter.log(TAG, "[MainHook] AI 聊天助手已加载 v629");
                        });

                        safeRun("TingMusicModule", () -> TingMusicModule.hook(cl));
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "[MainHook] FATAL in onReadyCallback: " + t.getClass().getSimpleName()
                            + " " + t.getMessage());
                    } finally {
                        // 无论任何模块失败, 都激活全部已注册 hook
                        try { HookManager.activateAll(); }
                        catch (Throwable t) { LogWriter.log(TAG, "[MainHook] activateAll FAIL: " + t.getMessage()); }
                    }
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "LeShaoV3: FATAL during init: " + t.getMessage());
        }
    }

    /** 独立执行模块初始化, 单个模块抛异常不影响后续模块 */
    private static void safeRun(String name, Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            LogWriter.log(TAG, "[MainHook] " + name + " FAIL: " + t.getClass().getSimpleName()
                    + " " + t.getMessage());
        }
    }
}
