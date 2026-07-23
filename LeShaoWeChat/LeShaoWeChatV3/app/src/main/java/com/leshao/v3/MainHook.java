package com.leshao.v3;

import android.os.Handler;
import android.os.Looper;

import com.leshao.v3.db.ContactRepository;
import com.leshao.v3.db.DatabaseProvider;
import com.leshao.v3.hook.AntiRecallHook;
import com.leshao.v3.hook.AntiDetectionHook;
import com.leshao.v3.hook.AutoCollectHook;
import com.leshao.v3.hook.FriendRequestHook;
import com.leshao.v3.hook.HookManager;
import com.leshao.v3.hook.MessageHook;
import com.leshao.v3.hook.RedPacketHook;
import com.leshao.v3.hook.SettingsEntryHook;
import com.leshao.v3.hook.ThemeHook;
import com.leshao.v3.hook.SettingsInjectProvider;
import com.leshao.v3.hook.VoiceForwardHook;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.service.SchedulerService;
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
        XposedBridge.log("LeShaoV3: >>> handleLoadPackage START pkg=" + lpparam.packageName + " proc=" + lpparam.processName);

        if (!WX_PKG.equals(lpparam.packageName)) return;

        boolean isMain = WX_PKG.equals(lpparam.processName);

        try {
            LogWriter.init();
        } catch (Throwable t) {
            XposedBridge.log("LeShaoV3: LogWriter init FAILED: " + t.getMessage());
        }

        XposedBridge.log("LeShaoV3: WeChat detected, process=" + lpparam.processName);
        LogWriter.log(TAG, ">>> handleLoadPackage process=" + lpparam.processName);

        int wxVersion = 0;
        try { wxVersion = XposedHelpers.getIntField(lpparam.appInfo, "versionCode"); }
        catch (Throwable t) { LogWriter.log(TAG, "wxVersion err: " + t.getMessage()); }

        if (!isMain) {
            LogWriter.log(TAG, "skip sub-process, only init DB hook");
            DatabaseProvider.initEarly(lpparam.classLoader);
            return;
        }

        if (sMainInitialized) {
            LogWriter.log(TAG, "MAIN already initialized, skip duplicate handleLoadPackage");
            return;
        }
        sMainInitialized = true;

        final int wxVerCode = wxVersion;
        LogWriter.log(TAG, "WeChat versionCode=" + wxVerCode);

        try {
            ContextManager.init(lpparam.classLoader, lpparam.appInfo.sourceDir);
            ContextManager.hookAttachBaseContext(lpparam);

            ContactRepository.init();

            DatabaseProvider.initEarly(lpparam.classLoader);

            SettingsEntryHook.hook(lpparam.classLoader);
            XposedBridge.log("LeShaoV3: SettingsEntryHook registered");

            MessageHook.hook(lpparam.classLoader);
            XposedBridge.log("LeShaoV3: MessageHook registered");

            ContextManager.setOnReadyCallback(new Runnable() {
                @Override
                public void run() {
                    XposedBridge.log("LeShaoV3: Context ready callback fired");
                    LogWriter.log(TAG, "Context ready, initializing all components...");

                    try {
                        DatabaseProvider.init();

                        android.content.Context ctx = ContextManager.getAppContext();
                        if (ctx instanceof android.app.Application) {
                            SettingsInjectProvider.injectIntoWeChat((android.app.Application) ctx);
                        } else {
                            LogWriter.log(TAG, "WARN: getAppContext not Application: " +
                                (ctx != null ? ctx.getClass().getName() : "null"));
                        }

                        if (ctx != null) {
                            try {
                                android.content.pm.PackageInfo pi = ctx.getPackageManager()
                                    .getPackageInfo(WX_PKG, 0);
                                LogWriter.log(TAG, "WeChat: " + pi.versionName + " (" + wxVerCode + ")");
                            } catch (Throwable t) {
                                LogWriter.log(TAG, "wxVersionName read err: " + t.getMessage());
                            }
                        }

                        TTSBroadcaster.init(ctx);

                        HookManager.register(AntiDetectionHook::hook);
                        HookManager.register(AntiRecallHook::hook);
                        HookManager.register(RedPacketHook::hook);
                        HookManager.register(AutoCollectHook::hook);
                        HookManager.register(FriendRequestHook::hook);
                        HookManager.register(ThemeHook::hook);
                        HookManager.register(VoiceForwardHook::hook);

                        HookManager.activateAll();

                        SchedulerService.start(ModuleConfig.load(ContextManager.getPrefs()));

                        LogWriter.log(TAG, "All components initialized");
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "onReady init FAILED: " + t.getMessage());
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("LeShaoV3: FATAL during init: " + t.getMessage());
            t.printStackTrace();
            LogWriter.log(TAG, "FATAL during init: " + t.getMessage());
        }
    }
}
