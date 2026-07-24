package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.hook.HookConfig;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * [功能31] TabCustom — 完整修复版
 * ===============================
 * 
 * ⚠️ 修复: MainTabUI 不是 ViewGroup 子类
 *   → 去掉强转, 改用反射调用 c() + 遍历内部字段找真正的 ViewGroup 容器
 * 
 * MainTabUI 方法: a(int)切换 b(int,int)角标 c(int)控制Tab d()初始化
 *                 i()->int 当前Tab l(int)清除角标 n()通知变更
 */
public class TabCustom {
    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static int[] hiddenTabs = new int[0];
    public static String[] tabLabels = {"微信", "联系人", "发现", "我"};
    public static boolean customLabels = false;

    public static void hook(ClassLoader cl) {
        XposedBridge.log("[TabCustom] hook() ENTER sEnabled=" + sEnabled);
        if (!sEnabled) return;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        XposedBridge.log("[TabCustom] config.tabCustomEnabled=" + config.tabCustomEnabled);
        if (config == null || !config.tabCustomEnabled) return;

        String hidden = HookConfig.getString("tab_visible", "");
        if (hidden != null && !hidden.isEmpty()) {
            String[] parts = hidden.split(",");
            hiddenTabs = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try { hiddenTabs[i] = Integer.parseInt(parts[i].trim()); }
                catch (Throwable ignored) {}
            }
        }

        String labels = HookConfig.getString("tab_labels", "");
        if (labels != null && !labels.isEmpty()) {
            String[] parts = labels.split(",");
            if (parts.length >= 4) {
                tabLabels = parts;
                customLabels = true;
            }
        }

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
            XposedBridge.log("[Tab] MainTabUI.d() Hook完成");
        } catch (Throwable t) {
            XposedBridge.log("[Tab] MainTabUI失败: " + t.getMessage());
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

    /**
     * ⚠️ 修复: 不再强转 (ViewGroup) mainTab
     * 
     * 方式1: 调用 c(int) 方法控制Tab
     * 方式2: 反射遍历 MainTabUI 内部字段找真正的 ViewGroup 容器
     */
    private static void applyTabModifications(Object mainTab) {
        try {
            // === 方式1: 调用 c(int) ===
            if (hiddenTabs.length > 0) {
                for (int idx : hiddenTabs) {
                    try {
                        XposedHelpers.callMethod(mainTab, "c", idx);
                        XposedBridge.log("[Tab] c(" + idx + ") 调用成功");
                    } catch (Throwable ignored) {}
                }
            }

            // === 方式2: 反射找内部 ViewGroup ===
            Class<?> clazz = mainTab.getClass();
            boolean foundViewGroup = false;
            
            while (clazz != null && clazz != Object.class && !foundViewGroup) {
                for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                    f.setAccessible(true);
                    try {
                        Object value = f.get(mainTab);
                        if (value instanceof ViewGroup) {
                            ViewGroup vg = (ViewGroup) value;
                            // 找到真正的Tab容器 → 操作子View
                            for (int i = 0; i < vg.getChildCount(); i++) {
                                View child = vg.getChildAt(i);
                                if (shouldHide(i)) {
                                    child.setVisibility(View.GONE);
                                    XposedBridge.log("[Tab] 已隐藏Tab[" + i + "]");
                                }
                                if (customLabels && i < tabLabels.length) {
                                    modifyTabLabel(child, tabLabels[i]);
                                }
                            }
                            foundViewGroup = true;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
                clazz = clazz.getSuperclass();
            }

            if (foundViewGroup) {
                XposedBridge.log("[Tab] 通过反射找到内部ViewGroup ✓");
            } else {
                XposedBridge.log("[Tab] 仅通过c()方法控制 (未找到内部ViewGroup)");
            }
        } catch (Throwable t) {
            XposedBridge.log("[Tab] 修改失败: " + t.getMessage());
        }
    }

    private static boolean shouldHide(int index) {
        for (int h : hiddenTabs) if (h == index) return true;
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
                XposedBridge.log("[Tab] 标签: " + current + " → " + newLabel);
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
