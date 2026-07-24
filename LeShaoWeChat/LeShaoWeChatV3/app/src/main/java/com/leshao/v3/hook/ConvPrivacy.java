package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import java.util.Set;
import java.util.HashSet;

/**
 * [功能15] 会话消息预览隐私
 * 隐私级别:
 *   0 = 关闭(显示原文)
 *   1 = 全局隐藏(所有消息显示"[新消息]")
 *   2 = 按联系人(指定联系人才隐藏)
 *   3 = 仅隐藏内容(显示"xxx发来一条消息")
 */
public class ConvPrivacy {

    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    private static int privacyLevel = 1;
    private static Set<String> privacyList = new HashSet<>();
    private static boolean hideNotificationContent = true;
    private static boolean hideConvListContent = true;

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.convPrivacyEnabled) return;

        loadPrivacyList();

        hookNotificationPrivacy(cl);
        hookConvListPrivacy(cl);
    }

    private static void loadPrivacyList() {
        String saved = "";
        if (saved != null && !saved.isEmpty()) {
            for (String s : saved.split(",")) {
                if (!s.trim().isEmpty()) privacyList.add(s.trim());
            }
        }
    }

    /**
     * Hook notification.m0.a() — 修改通知内容
     * 624行大方法，11个参数
     */
    private static void hookNotificationPrivacy(ClassLoader cl) {
        try {
            Class<?> m0 = XposedHelpers.findClass(
                    "com.tencent.mm.booter.notification.m0", cl);

            for (java.lang.reflect.Method m : m0.getDeclaredMethods()) {
                if (m.getName().equals("a")
                        && m.getParameterTypes().length >= 9
                        && m.getParameterTypes()[0].getName().contains("e9")) {

                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (privacyLevel == 0) return;

                            String talker = "";
                            try {
                                if (param.args.length >= 7) {
                                    talker = String.valueOf(param.args[6]);
                                }
                            } catch (Throwable ignored) {}

                            boolean shouldHide = false;
                            switch (privacyLevel) {
                                case 1:
                                    shouldHide = true;
                                    break;
                                case 2:
                                    shouldHide = privacyList.contains(talker);
                                    break;
                                case 3:
                                    shouldHide = true;
                                    break;
                            }

                            if (shouldHide) {
                                if (hideNotificationContent) {
                                    if (param.args.length >= 5
                                            && param.args[4] instanceof String) {
                                        String original = (String) param.args[4];
                                        if (original != null && !original.isEmpty()
                                                && !original.equals("[新消息]")) {
                                            if (privacyLevel == 3) {
                                                param.args[4] = talker + "发来一条消息";
                                            } else {
                                                param.args[4] = "[新消息]";
                                            }
                                        }
                                    }
                                    if (param.args.length >= 8
                                            && param.args[7] instanceof String) {
                                        String original = (String) param.args[7];
                                        if (original != null && !original.isEmpty()
                                                && !original.equals("[新消息]")) {
                                            param.args[7] = "[新消息]";
                                        }
                                    }
                                }
                            }
                        }
                    });

                    Logger.i("[ConvPrivacy] notification.m0.a() Hook完成 "
                            + "(level=" + privacyLevel + ")");
                    break;
                }
            }
        } catch (Throwable t) {
            Logger.w("[ConvPrivacy] 通知Hook失败: " + t.getMessage());
        }
    }

    private static void hookConvListPrivacy(ClassLoader cl) {
        if (!hideConvListContent) return;
        try {
            Class<?> convList = XposedHelpers.findClass(
                    "com.tencent.mm.ui.conversation.adapter.MvvmConvList", cl);

            XposedBridge.hookAllMethods(convList, "c", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (privacyLevel == 1) {
                        String digest = (String) param.getResult();
                        if (digest != null && !digest.isEmpty()
                                && !digest.equals("[新消息]")) {
                            param.setResult("[新消息]");
                        }
                    }
                }
            });
            Logger.i("[ConvPrivacy] MvvmConvList Hook完成");
        } catch (Throwable t) {
            Logger.w("[ConvPrivacy] 会话列表Hook失败: " + t.getMessage());
        }
    }
}
