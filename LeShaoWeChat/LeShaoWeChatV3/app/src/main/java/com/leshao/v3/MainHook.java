package com.leshao.v3;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Process;


import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.AntiRecallHook;
import com.leshao.v3.hook.AntiDetectionHook;
import com.leshao.v3.hook.AutoRemark;
import com.leshao.v3.hook.BatchMessage;
import com.leshao.v3.hook.CallFeatures;
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
import com.leshao.v3.hook.NotifyCustom;
import com.leshao.v3.hook.PrivacyFeatures;
import com.leshao.v3.hook.RedPacketHook;
import com.leshao.v3.hook.SearchEnhance;
import com.leshao.v3.hook.TtsVoiceSender;
import com.leshao.v3.hook.ShakeCustom;
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

            MessageHook.hook(cl);
            TtsVoiceSender.hook(cl);
            CornerMenu.hook(cl);
            ChatRoomMuteHelper.hook(cl);
            ChatFooterLongPressMenu.hook(cl);
            ChatGroupUiInjector.hook(cl);
            ContextManager.setOnReadyCallback(new Runnable() {
                @Override
                public void run() {
                    try {
                        Context ctx = ContextManager.getAppContext();

                        TTSBroadcaster.init(ctx);
                        VoiceAutoPlay.hook(cl);

                        ModuleConfig.initWxid(ctx);

                        try {
                            VoiceHistoryDbHelper.getInstance(ctx).deleteExpired(
                                System.currentTimeMillis() - 30L * 86400000L);
                        } catch (Throwable ignored) {}

                        AntiDetectionHook.hook(cl);
                        HookManager.register(AntiRecallHook::hook);
                        HookManager.register(RedPacketHook::hook);
                        HookManager.register(() -> ChatGroupHook.hook(cl));
                        FriendRequestHook.hook(cl);

                        HookManager.register(VoiceForwardHook::hook);
                        HookManager.register(() -> TypingIndicator.hook(cl));
                        HookManager.register(() -> ChatFooterEnhance.hook(cl));
                        ChatVoiceSwitchHook.init(cl);
                        HookManager.register(() -> ChatUICustom.hook(cl));
                        HookManager.register(() -> BatchMessage.hook(cl));
                        HookManager.register(() -> AutoRemark.hook(cl));
                        HookManager.register(() -> SearchEnhance.hook(cl));
                        HookManager.register(() -> NotifyCustom.hook(cl));
                        HookManager.register(() -> UnreadBadge.hook(cl));
                        HookManager.register(() -> TabCustom.hook(cl));
                        HookManager.register(() -> ShakeCustom.hook(cl));
                        HookManager.register(() -> StickyEnhance.hook(cl));
                        HookManager.register(() -> DeleteDetect.hook(cl));
                        HookManager.register(() -> CallFeatures.hook(cl));

                        HookManager.register(() -> SnsFeatures.hook(cl));

                        HookManager.register(() -> PrivacyFeatures.hook(cl));
                        HookManager.register(() -> LoginMonitor.hook(cl));
                        HookManager.register(() -> HideContactFields.hook(cl));
                        HookManager.register(() -> ConvPrivacy.hook(cl));

                        HookManager.register(() -> ContactChangeLog.hook(cl));
                        HookManager.register(() -> GroupFeatures.hook(cl));
                        HookManager.register(() -> WmEntry.injectAll(cl));

                        LogWriter.log(TAG, "[MainHook] 开始初始化 FakeAddSource");
                        FakeAddSource.hook(cl);
                        LogWriter.log(TAG, "[MainHook] FakeAddSource 初始化完成");

                        try {
                            AiConfig.init(ctx);
                            ChatHooks.install(lpparam);
                            LogWriter.log(TAG, "[MainHook] AI 聊天助手已加载 v624");
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "[MainHook] AI install FAIL: " + t.getMessage());
                        }

                        HookManager.activateAll();
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "[MainHook] FATAL in onReadyCallback: " + t.getClass().getSimpleName()
                            + " " + t.getMessage());
                    }
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "LeShaoV3: FATAL during init: " + t.getMessage());
        }
    }
}
