package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import android.text.InputFilter;
import android.widget.EditText;

/**
 * [功能7] 聊天输入框增强
 * ChatFooter (com.tencent.mm.pluginsdk.ui.chat.ChatFooter):
 *   getEditingLength() → String    字数检查
 *   F(e9,a35.g) → boolean          核心发送
 * 增强:
 *   1. 突破5000字限制
 *   2. 输入框高度自适应
 *   3. 发送按钮监听长文本
 */
public class ChatFooterEnhance {

    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static int maxTextLength = 50000;
    public static int maxLines = 10;

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.chatFooterEnhanceEnabled) return;

        hookTextLimit(cl);
        hookInputResize(cl);
        hookSendMonitor(cl);
    }

    /**
     * 解除字数限制
     * ChatFooter.getEditingLength() 返回编辑区文本长度
     * 微信内部通过此值判断是否超过5000字限制
     * Hook返回"0" → 绕过检查
     */
    private static void hookTextLimit(ClassLoader cl) {
        try {
            Class<?> chatFooter = XposedHelpers.findClass(
                    "com.tencent.mm.pluginsdk.ui.chat.ChatFooter", cl);

            XposedBridge.hookAllMethods(chatFooter, "getEditingLength",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult("0");
                }
            });
            Logger.i("[Footer] 字数限制已解除 (返回'0')");

            XposedBridge.hookAllMethods(chatFooter, "onFinishInflate",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    android.view.View root = (android.view.View) param.thisObject;
                    removeTextLimit(root);
                }
            });

        } catch (Throwable t) {
            Logger.w("[Footer] 字数限制Hook失败: " + t.getMessage());
        }
    }

    private static void removeTextLimit(android.view.View view) {
        if (view instanceof EditText) {
            EditText et = (EditText) view;
            InputFilter[] filters = et.getFilters();
            if (filters != null) {
                java.util.List<InputFilter> newFilters = new java.util.ArrayList<>();
                for (InputFilter f : filters) {
                    if (!(f instanceof InputFilter.LengthFilter)) {
                        newFilters.add(f);
                    }
                }
                if (newFilters.size() != filters.length) {
                    et.setFilters(new InputFilter[]{
                        new InputFilter.LengthFilter(maxTextLength)
                    });
                    Logger.i("[Footer] EditText限制已修改: max=" + maxTextLength);
                }
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                removeTextLimit(vg.getChildAt(i));
            }
        }
    }

    private static void hookInputResize(ClassLoader cl) {
        try {
            Class<?> chatFooter = XposedHelpers.findClass(
                    "com.tencent.mm.pluginsdk.ui.chat.ChatFooter", cl);

            XposedBridge.hookAllMethods(chatFooter, "onSizeChanged",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    android.view.View root = (android.view.View) param.thisObject;
                    setEditTextMaxLines(root);
                }
            });
        } catch (Throwable t) {}
    }

    private static void setEditTextMaxLines(android.view.View view) {
        if (view instanceof EditText) {
            EditText et = (EditText) view;
            if (et.getMaxLines() > 0 && et.getMaxLines() < maxLines) {
                et.setMaxLines(maxLines);
                Logger.i("[Footer] 最大行数: → " + maxLines);
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                setEditTextMaxLines(vg.getChildAt(i));
            }
        }
    }

    private static void hookSendMonitor(ClassLoader cl) {
        try {
            Class<?> chatFooter = XposedHelpers.findClass(
                    "com.tencent.mm.pluginsdk.ui.chat.ChatFooter", cl);

            XposedBridge.hookAllMethods(chatFooter, "F", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object msgInfo = param.args[0];
                        String content = (String) XposedHelpers.getObjectField(
                                msgInfo, "field_content");
                        int len = content != null ? content.length() : 0;
                        if (len > 5000) {
                            Logger.i("[Footer] 发送长文本: " + len + "字");
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {}
    }
}
