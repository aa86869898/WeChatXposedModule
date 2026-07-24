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
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/**
 * [功能17] 未读角标自定义
 * MainTabUI.b(int index, int count) — 设置角标
 * MainTabUI.l(int index) — 清除角标
 * 样式:
 *   0 = 微信默认(数字)
 *   1 = 仅红点
 *   2 = 精确数字(不截断)
 *   3 = 99+模式(超过99显示"99+")
 *   4 = 自定义颜色
 */
public class UnreadBadge {

    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    private static int badgeStyle = 0;
    private static int maxDisplay = 99;
    private static int badgeColor = 0xFFFA5151;

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.unreadBadgeEnabled) return;

        hookSetBadge(cl);
        hookClearBadge(cl);
    }

    private static void hookSetBadge(ClassLoader cl) {
        try {
            Class<?> mainTab = XposedHelpers.findClass(
                    "com.tencent.mm.ui.MainTabUI", cl);

            for (java.lang.reflect.Method m : mainTab.getDeclaredMethods()) {
                if (m.getName().equals("b")
                        && m.getParameterTypes().length == 2
                        && m.getParameterTypes()[0] == int.class
                        && m.getParameterTypes()[1] == int.class) {

                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int tabIndex = (Integer) param.args[0];
                            int count = (Integer) param.args[1];

                            if (count <= 0) return;

                            switch (badgeStyle) {
                                case 1:
                                    param.args[1] = 1;
                                    break;
                                case 2:
                                    break;
                                case 3:
                                    if (count > maxDisplay) {
                                        param.args[1] = maxDisplay;
                                    }
                                    break;
                                case 4:
                                    applyBadgeColor(param.thisObject, tabIndex);
                                    break;
                                default:
                                    break;
                            }

                            Logger.i("[Badge] Tab[" + tabIndex + "] count="
                                    + count + " → " + param.args[1]
                                    + " (style=" + badgeStyle + ")");
                        }
                    });
                }
            }
            Logger.i("[Badge] MainTabUI.b() Hook完成");
        } catch (Throwable t) {
            Logger.w("[Badge] Hook失败: " + t.getMessage());
        }
    }

    private static void hookClearBadge(ClassLoader cl) {
        try {
            Class<?> mainTab = XposedHelpers.findClass(
                    "com.tencent.mm.ui.MainTabUI", cl);

            for (java.lang.reflect.Method m : mainTab.getDeclaredMethods()) {
                if (m.getName().equals("l")
                        && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == int.class) {

                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Logger.i("[Badge] 清除Tab[" + param.args[0] + "]角标");
                        }
                    });
                }
            }
        } catch (Throwable t) {}
    }

    private static void applyBadgeColor(Object mainTab, int tabIndex) {
        try {
            ViewGroup vg = (ViewGroup) mainTab;
            View tabView = vg.getChildAt(tabIndex);
            if (tabView != null) {
                findAndColorBadge(tabView);
            }
        } catch (Throwable ignored) {}
    }

    private static void findAndColorBadge(View view) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            String text = tv.getText().toString();
            if (text.matches("\\d+") || text.contains("+")) {
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(badgeColor);
                tv.setBackground(bg);
                tv.setTextColor(Color.WHITE);
                Logger.i("[Badge] 角标颜色已修改");
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                findAndColorBadge(vg.getChildAt(i));
            }
        }
    }
}
