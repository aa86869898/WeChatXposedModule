package com.leshao.v3;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;

import com.leshao.v3.db.ContactRepository;
import com.leshao.v3.db.DatabaseProvider;
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.AntiRecallHook;
import com.leshao.v3.hook.AntiDetectionHook;
import com.leshao.v3.hook.AutoCollectHook;
import com.leshao.v3.hook.AutoRemark;
import com.leshao.v3.hook.AutoReplyHook;
import com.leshao.v3.hook.BatchMessage;
import com.leshao.v3.hook.CallFeatures;
import com.leshao.v3.hook.ChatBackup;
import com.leshao.v3.hook.ChatFooterEnhance;
import com.leshao.v3.hook.ChatUICustom;
import com.leshao.v3.hook.ContactChangeLog;
import com.leshao.v3.hook.ContactExport;
import com.leshao.v3.hook.ConvPrivacy;
import com.leshao.v3.hook.DeleteDetect;
import com.leshao.v3.hook.FriendRequestHook;
import com.leshao.v3.hook.GroupFeatures;
import com.leshao.v3.hook.HideContactFields;
import com.leshao.v3.hook.HookManager;
import com.leshao.v3.hook.LoginMonitor;
import com.leshao.v3.hook.MessageHook;
import com.leshao.v3.hook.MsgExport;
import com.leshao.v3.hook.NotifyCustom;
import com.leshao.v3.hook.PrivacyFeatures;
import com.leshao.v3.hook.RedPacketAlert;
import com.leshao.v3.hook.RedPacketHook;
import com.leshao.v3.hook.ScheduledSend;
import com.leshao.v3.hook.SearchEnhance;
import com.leshao.v3.hook.SettingsEntryHook;
import com.leshao.v3.hook.SettingsInjectProvider;
import com.leshao.v3.hook.ShakeCustom;
import com.leshao.v3.hook.SnsFeatures;
import com.leshao.v3.hook.StickyEnhance;
import com.leshao.v3.hook.TabCustom;
import com.leshao.v3.hook.ThemeHook;
import com.leshao.v3.hook.TypingIndicator;
import com.leshao.v3.hook.UnreadBadge;
import com.leshao.v3.hook.VoiceForwardHook;
import com.leshao.v3.hook.VoiceAutoPlay;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.service.TTSBroadcaster;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
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

        boolean isMain = WX_PKG.equals(lpparam.processName);

        try { LogWriter.init(); } catch (Throwable t) {
            LogWriter.log(TAG, "LeShaoV3: LogWriter init FAILED: " + t.getMessage());
        }

        int wxVersion = 0;
        try { wxVersion = XposedHelpers.getIntField(lpparam.appInfo, "versionCode"); }
        catch (Throwable t) {}

        if (!isMain) {
            DatabaseProvider.initEarly(lpparam.classLoader);
            return;
        }

        if (sMainInitialized) return;
        sMainInitialized = true;

        final int wxVerCode = wxVersion;
        final ClassLoader cl = lpparam.classLoader;

        try {
            ContextManager.init(cl, lpparam.appInfo.sourceDir);
            ContextManager.hookAttachBaseContext(lpparam);
            ContactRepository.init();
            DatabaseProvider.initEarly(cl);

            SettingsEntryHook.hook(cl);
            MessageHook.hook(cl);

            ContextManager.setOnReadyCallback(new Runnable() {
                @Override
                public void run() {
                    try {
                        DatabaseProvider.init();

                        Context ctx = ContextManager.getAppContext();
                        if (ctx instanceof Application) {
                            SettingsInjectProvider.injectIntoWeChat((Application) ctx);
                        }

                        TTSBroadcaster.init(ctx);
                        VoiceAutoPlay.hook(cl);

                        // 管理员自动略过激活门控
                        ModuleConfig.initWxid(ctx);

                        // === 安全 ===================================================================
                        AntiDetectionHook.hook(cl);
                        HookManager.register(AntiRecallHook::hook);
                        HookManager.register(RedPacketHook::hook);
                        HookManager.register(AutoCollectHook::hook);
                        FriendRequestHook.hook(cl);

                        // === WeChatPlus 聊天增强层 (24项) ============================================
                        HookManager.register(() -> AutoReplyHook.hook(cl));
                        HookManager.register(() -> TypingIndicator.hook(cl));
                        HookManager.register(() -> ChatFooterEnhance.hook(cl));
                        HookManager.register(() -> ChatUICustom.hook(cl));
                        HookManager.register(() -> BatchMessage.hook(cl));
                        HookManager.register(() -> ScheduledSend.hook(cl));
                        HookManager.register(() -> AutoRemark.hook(cl));
                        HookManager.register(() -> SearchEnhance.hook(cl));
                        HookManager.register(() -> NotifyCustom.hook(cl));
                        HookManager.register(() -> UnreadBadge.hook(cl));
                        HookManager.register(() -> TabCustom.hook(cl));
                        HookManager.register(() -> ShakeCustom.hook(cl));
                        HookManager.register(() -> StickyEnhance.hook(cl));
                        HookManager.register(() -> DeleteDetect.hook(cl));
                        HookManager.register(() -> CallFeatures.hook(cl));

                        // === 朋友圈 ================================================================
                        HookManager.register(() -> SnsFeatures.hook(cl));

                        // === 隐私安全 ==============================================================
                        HookManager.register(() -> PrivacyFeatures.hook(cl));
                        HookManager.register(() -> LoginMonitor.hook(cl));
                        HookManager.register(() -> HideContactFields.hook(cl));
                        HookManager.register(() -> ConvPrivacy.hook(cl));

                        // === 联系人与群管 ==========================================================
                        HookManager.register(() -> ContactExport.hook(cl));
                        HookManager.register(() -> ContactChangeLog.hook(cl));
                        HookManager.register(() -> GroupFeatures.hook(cl));

                        // === 数据工具 ==============================================================
                        HookManager.register(() -> MsgExport.hook(cl));
                        HookManager.register(() -> ChatBackup.hook(cl));

                        // === 红包提醒 ==============================================================
                        HookManager.register(() -> RedPacketAlert.hook(cl));

                        // === 主题引擎 ==============================================================
                        HookManager.register(ThemeHook::hook);
                        HookManager.register(VoiceForwardHook::hook);

                        LogWriter.log(TAG, "[MainHook] activateAll() START, pendingTasks=" + HookManager.pendingCount());

                        HookManager.activateAll();

                        LogWriter.log(TAG, "[MainHook] activateAll() DONE");
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
