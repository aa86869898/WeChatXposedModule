package com.leshao.v3;

import com.leshao.v3.db.ContactRepository;
import com.leshao.v3.db.DatabaseProvider;
import com.leshao.v3.dispatch.MessageDispatcher;
import com.leshao.v3.hook.AntiRecallHook;
import com.leshao.v3.hook.FriendRequestHook;
import com.leshao.v3.hook.HookManager;
import com.leshao.v3.hook.MessageHook;
import com.leshao.v3.hook.RedPacketHook;
import com.leshao.v3.hook.SettingsEntryHook;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.service.SchedulerService;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "LeShaoV3";
    private static final String WX_PKG = "com.tencent.mm";

    public MainHook() {}

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XposedBridge.log("LeShaoV3: >>> handleLoadPackage START pkg=" + lpparam.packageName + " proc=" + lpparam.processName);

        if (!WX_PKG.equals(lpparam.packageName)) return;

        try {
            LogWriter.init();
        } catch (Throwable t) {
            XposedBridge.log("LeShaoV3: LogWriter init FAILED: " + t.getMessage());
        }

        XposedBridge.log("LeShaoV3: WeChat detected, process=" + lpparam.processName);
        LogWriter.log(TAG, ">>> handleLoadPackage process=" + lpparam.processName);

        int wxVersion = 0;
        String wxVersionName = "";
        try { wxVersion = XposedHelpers.getIntField(lpparam.appInfo, "versionCode"); }
        catch (Throwable t) { LogWriter.log(TAG, "wxVersion err: " + t.getMessage()); }
        try { wxVersionName = (String) XposedHelpers.getObjectField(lpparam.appInfo, "versionName"); }
        catch (Throwable t) { LogWriter.log(TAG, "wxVersionName err: " + t.getMessage()); }

        LogWriter.log(TAG, "WeChat: " + wxVersionName + " (" + wxVersion + ")");

        try {
            ContextManager.init(lpparam.classLoader, lpparam.appInfo.sourceDir);
            ContextManager.hookAttachBaseContext(lpparam);

            // 设置页入口 Hook：必须尽早注册（Activity 级钩子），匹配 V2.1 注册时机
            SettingsEntryHook.hook(lpparam.classLoader);
            XposedBridge.log("LeShaoV3: SettingsEntryHook registered");

            // attachBaseContext 完成后按优先级注册全部 Hook
            ContextManager.setOnReadyCallback(() -> {
                XposedBridge.log("LeShaoV3: Context ready callback fired");
            LogWriter.log(TAG, "Context ready, initializing all components...");

            // P1: 数据库密钥捕获（异步执行，不影响启动）
            DatabaseProvider.init();
            ContactRepository.init();

            // 注册安全类 Hook（先注册，通过开关控制）
            HookManager.register(AntiRecallHook::hook);
            HookManager.register(RedPacketHook::hook);
            HookManager.register(FriendRequestHook::hook);

            // 执行所有已注册 Hook
            HookManager.activateAll();

            // 消息拦截 Hook
            MessageHook.setCallback(msg -> {
                ModuleConfig cfg = ModuleConfig.load(ContextManager.getPrefs());
                MessageDispatcher.dispatch(msg, cfg);
            });
            MessageHook.hook();

            // 定时任务
            SchedulerService.start(ModuleConfig.load(ContextManager.getPrefs()));

            LogWriter.log(TAG, "All components initialized");
        });
        } catch (Throwable t) {
            XposedBridge.log("LeShaoV3: FATAL during init: " + t.getMessage());
            t.printStackTrace();
        }
    }
}
