package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * [功能31] 底部Tab自定义
 * 微信底部4个Tab:
 *   0 = 微信(会话列表)
 *   1 = 通讯录
 *   2 = 发现
 *   3 = 我
 */
public class TabCustom {

    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static int[] hiddenTabs = new int[0];
    public static String[] tabLabels = {"微信", "联系人", "发现", "我"};
    public static boolean customLabels = false;

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.tabCustomEnabled) return;

        hookMainTabInit(cl);
        hookMainTabNotify(cl);
    }

    private static void hookMainTabInit(ClassLoader cl) {
        try {
            Class<?> mainTab = XposedHelpers.findClass(
                    "com.tencent.mm.ui.MainTabUI", cl);

            XposedBridge.hookAllMethods(mainTab, "d", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    applyTabModifications(param.thisObject);
                }
            });
            Logger.i("[Tab] MainTabUI.d() Hook完成");
        } catch (Throwable t) {
            Logger.w("[Tab] MainTabUI失败: " + t.getMessage());
        }
    }

    private static void hookMainTabNotify(ClassLoader cl) {
        try {
            Class<?> mainTab = XposedHelpers.findClass(
                    "com.tencent.mm.ui.MainTabUI", cl);
            XposedBridge.hookAllMethods(mainTab, "n", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    applyTabModifications(param.thisObject);
                }
            });
        } catch (Throwable t) {}
    }

    private static void applyTabModifications(Object mainTab) {
        try {
            if (hiddenTabs != null && hiddenTabs.length > 0) {
                for (int idx : hiddenTabs) {
                    try {
                        XposedHelpers.callMethod(mainTab, "c", idx);
                        Logger.i("[Tab] c(" + idx + ") 调用成功");
                    } catch (Throwable ignored) {}
                }
            }

            Class<?> clazz = mainTab.getClass();
            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                f.setAccessible(true);
                try {
                    Object value = f.get(mainTab);
                    if (value instanceof ViewGroup) {
                        ViewGroup vg = (ViewGroup) value;
                        for (int i = 0; i < vg.getChildCount(); i++) {
                            View child = vg.getChildAt(i);

                            Object tag = child.getTag();
                            int tabIndex = -1;
                            if (tag instanceof Integer) {
                                tabIndex = (Integer) tag;
                            } else {
                                tabIndex = i;
                            }

                            if (shouldHide(tabIndex)) {
                                child.setVisibility(View.GONE);
                                Logger.i("[Tab] 已隐藏Tab: " + tabIndex);
                            }

                            if (customLabels && tabIndex >= 0 && tabIndex < tabLabels.length) {
                                modifyTabLabel(child, tabLabels[tabIndex]);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Logger.w("[Tab] 修改失败: " + t.getMessage());
        }
    }

    private static boolean shouldHide(int index) {
        for (int h : hiddenTabs) {
            if (h == index) return true;
        }
        return false;
    }

    private static void modifyTabLabel(View view, String newLabel) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            String current = tv.getText().toString();
            if (current.equals("微信") || current.equals("通讯录")
                    || current.equals("发现") || current.equals("我")
                    || current.equals("WeChat") || current.equals("Contacts")
                    || current.equals("Discover") || current.equals("Me")) {
                tv.setText(newLabel);
                Logger.i("[Tab] 标签已修改: " + current + " → " + newLabel);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                modifyTabLabel(vg.getChildAt(i), newLabel);
            }
        }
    }
}
