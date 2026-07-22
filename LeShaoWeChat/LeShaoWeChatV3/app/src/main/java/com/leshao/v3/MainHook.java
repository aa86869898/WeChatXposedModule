package com.leshao.v3;

import com.leshao.v3.db.ContactRepository;
import com.leshao.v3.db.DatabaseProvider;
import com.leshao.v3.dispatch.MessageDispatcher;
import com.leshao.v3.hook.AntiRecallHook;
import com.leshao.v3.hook.AntiDetectionHook;
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

        try {
            String wxVersionName = "";
            try {
                Object atObj = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                    "currentActivityThread");
                Object appObj = XposedHelpers.callMethod(atObj, "getApplication");
                if (appObj != null) {
                    Object ctxField = null;
                    try {
                        ctxField = XposedHelpers.getStaticObjectField(
                            lpparam.classLoader.loadClass("com.tencent.mm.sdk.platformtools.MMApplicationContext"),
                            "mContext");
                    } catch (Throwable ignored) {}
                    Object ctx = ctxField != null ? ctxField : appObj;
                    if (ctx != null) {
                        Object pm = XposedHelpers.callMethod(ctx, "getPackageManager");
                        Object pi = XposedHelpers.callMethod(pm, "getPackageInfo", WX_PKG, 0);
                        wxVersionName = (String) XposedHelpers.getObjectField(pi, "versionName");
                    }
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "wxVersionName err: " + t.getMessage());
            }

            LogWriter.log(TAG, "WeChat: " + wxVersionName + " (" + wxVersion + ")");
        } catch (Throwable t) {
            LogWriter.log(TAG, "version check err: " + t.getMessage());
        }

        try {
            ContextManager.init(lpparam.classLoader, lpparam.appInfo.sourceDir);
            ContextManager.hookAttachBaseContext(lpparam);

            ContactRepository.init();

            DatabaseProvider.initEarly(lpparam.classLoader);

            SettingsEntryHook.hook(lpparam.classLoader);
            XposedBridge.log("LeShaoV3: SettingsEntryHook registered");

            ContextManager.setOnReadyCallback(new Runnable() {
                @Override
                public void run() {
                    XposedBridge.log("LeShaoV3: Context ready callback fired");
                    LogWriter.log(TAG, "Context ready, initializing all components...");

                    DatabaseProvider.init();

                    android.content.Context ctx = ContextManager.getAppContext();
                    if (ctx instanceof android.app.Application) {
                        SettingsInjectProvider.injectIntoWeChat((android.app.Application) ctx);
                    } else {
                        LogWriter.log(TAG, "WARN: getAppContext not Application: " +
                            (ctx != null ? ctx.getClass().getName() : "null"));
                    }

                    HookManager.register(AntiDetectionHook::hook);
                    HookManager.register(AntiRecallHook::hook);
                    HookManager.register(RedPacketHook::hook);
                    HookManager.register(FriendRequestHook::hook);
                    HookManager.register(ThemeHook::hook);
                    HookManager.register(VoiceForwardHook::hook);

                    HookManager.activateAll();

                    MessageHook.setCallback(new MessageHook.MessageCallback() {
                        @Override
                        public void onMessage(com.leshao.v3.model.WeChatMessage msg) {
                            ModuleConfig cfg = ModuleConfig.load(ContextManager.getPrefs());
                            MessageDispatcher.dispatch(msg, cfg);
                        }
                    });
                    MessageHook.hook();

                    SchedulerService.start(ModuleConfig.load(ContextManager.getPrefs()));

                    LogWriter.log(TAG, "All components initialized");
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("LeShaoV3: FATAL during init: " + t.getMessage());
            t.printStackTrace();
        }
    }
}
