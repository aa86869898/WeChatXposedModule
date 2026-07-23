package com.leshao.v3.hook;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public class FriendRequestHook {

    private static final String TAG = "FriendRequestHook";
    private static volatile boolean sEnabled = false;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    /**
     * Hook 好友申请自动通过
     */
    public static void hook() {
        if (!ContextManager.isReady()) {
            LogWriter.log(TAG, "hook ABORTED: ContextManager not ready");
            return;
        }

        ClassLoader cl = ContextManager.getClassLoader();

        for (String className : new String[]{
            "com.tencent.mm.plugin.subapp.ui.friend.FMessageConversationUI",
            "com.tencent.mm.plugin.profile.ui.SayHiWithSnsPermissionUI",
            "com.tencent.mm.plugin.profile.ui.ContactInfoUI",
        }) {
            try {
                Class<?> c = cl.loadClass(className);
                XposedHelpers.findAndHookMethod(c, "onCreate", android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!sEnabled) return;
                                try {
                                    android.app.Activity activity = (android.app.Activity) param.thisObject;
                                    autoAccept(activity);
                                } catch (Throwable t) {
                                    LogWriter.log(TAG, "autoAccept error: " + t.getMessage());
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                LogWriter.log(TAG, "friend request hook registered: " + className);
            } catch (Throwable ignored) {}
        }
    }

    private static void autoAccept(android.app.Activity activity) {
        // 尝试查找"通过验证"或"接受"按钮
        String[] btnTexts = {"接受", "通过验证", "添加到通讯录"};
        android.view.View root = activity.getWindow().getDecorView().getRootView();
        findAndClick(root, btnTexts);
    }

    private static void findAndClick(android.view.View view, String[] texts) {
        if (view instanceof android.widget.Button || view instanceof android.widget.TextView) {
            String text = null;
            try { text = (String) view.getClass().getMethod("getText").invoke(view); }
            catch (Throwable ignored) {}
            if (text != null) {
                final String capturedText = text;
                for (String t : texts) {
                    if (capturedText.contains(t)) {
                        view.postDelayed(() -> {
                            view.performClick();
                            LogWriter.log(TAG, "auto accept friend clicked: " + capturedText);
                        }, 1000);
                        return;
                    }
                }
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                findAndClick(vg.getChildAt(i), texts);
            }
        }
    }
}
