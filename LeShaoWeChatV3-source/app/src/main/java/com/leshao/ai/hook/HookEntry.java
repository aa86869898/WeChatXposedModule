package com.leshao.ai.hook;

import com.leshao.ai.hook.wechat.WeChatHook;
import com.leshao.ai.util.DexKitBridgeHolder;

import java.io.File;
import java.io.IOException;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口：LSPosed/Xposed 加载入口。
 * <p>
 * 作用域限定为微信 {@code com.tencent.mm}，仅在该进程内初始化 dexkit 桥
 * 并挂载微信 hook。设置界面 Activity 走 Android 正常组件生命周期（本类不干预）。
 */
public class HookEntry implements IXposedHookLoadPackage {

    public static final String WECHAT_PACKAGE = "com.tencent.mm";

    /** 微信进程内可用。 */
    public static volatile ClassLoader appClassLoader;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }

        // 本模块的所有 hook 只针对微信进程；其余包名直接忽略，避免污染其他应用
        if (!lpparam.packageName.equals(WECHAT_PACKAGE)) {
            return;
        }
        appClassLoader = lpparam.classLoader;

        // 初始化运行时 dexkit 桥（扫描微信进程内的 dex，用于跨版本类/方法定位）
        DexKitBridgeHolder.init(lpparam.classLoader);

        // 挂载微信 hook 核心
        try {
            WeChatHook.install(lpparam);
        } catch (Throwable t) {
            de.robv.android.xposed.XposedBridge.log("[LeshaoAI] 微信 Hook 挂载失败: " + t);
        }
    }
}
