package com.leshao.v3.hook;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class RedPacketHook {

    private static final String TAG = "RedPacketHook";
    private static volatile boolean sEnabled = false;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook() {
        if (!ContextManager.isReady()) {
            LogWriter.log(TAG, "hook ABORTED: ContextManager not ready");
            return;
        }

        ClassLoader cl = ContextManager.getClassLoader();

        // 尝试 hook 红包相关类
        for (String className : new String[]{
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyReceiveUI",
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI",
        }) {
            try {
                Class<?> c = cl.loadClass(className);
                XposedHelpers.findAndHookMethod(c, "onCreate", android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!sEnabled) return;
                            try {
                                // 自动点击"开"按钮
                                Object activity = param.thisObject;
                                int openId = ((android.app.Activity) activity).getResources()
                                    .getIdentifier("lucky_money_open", "id", "com.tencent.mm");
                                if (openId != 0) {
                                    android.view.View openBtn = ((android.app.Activity) activity).findViewById(openId);
                                    if (openBtn != null) {
                                        openBtn.postDelayed(() -> openBtn.performClick(), 500);
                                        LogWriter.log(TAG, "auto open red packet");
                                    }
                                }
                            } catch (Throwable t) {
                                LogWriter.log(TAG, "red packet hook error: " + t.getMessage());
                            }
                        }
                    });
                LogWriter.log(TAG, "red packet hook registered: " + className);
            } catch (Throwable ignored) {}
        }
    }
}
