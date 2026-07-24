package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.hook.HookConfig;

import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import java.util.List;
import java.util.ArrayList;

/**
 * [功能9] BatchMessage — 完整修复版
 * ==================================
 * 
 * ⚠️ 修复: ChatMoreSelectUI 类名中的 "." 被错误替换为 "$"
 *   XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChatMoreSelectUI", cl)
 *   如果该版本微信混淆了此类, 用 findClassOrNull 安全处理
 */
public class BatchMessage {
    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static int maxSelectCount = 999;
    public static int forwardSplitSize = 50;

    public static void hook(ClassLoader cl) {
        if (!sEnabled || !ModuleConfig.load(ContextManager.getPrefs()).batchMessageEnabled) return;
        maxSelectCount = HookConfig.getInt("batch_max_select", 999);
        forwardSplitSize = HookConfig.getInt("forward_split_size", 50);

        hookChatMoreSelect(cl);
        hookForwardLimit(cl);
    }

    /**
     * ⚠️ 修复: 使用正确类名 + 安全查找
     */
    private static void hookChatMoreSelect(ClassLoader cl) {
        // ⚠️ 修复: 使用正确的点分隔类名, 并安全查找
        Class<?> moreSelect = null;
        try {
            moreSelect = XposedHelpers.findClass(
                    "com.tencent.mm.ui.chatting.ChatMoreSelectUI", cl);
        } catch (XposedHelpers.ClassNotFoundError e) {
            XposedBridge.log("[Batch] ChatMoreSelectUI 在当前版本被混淆, 跳过");
            return;
        }

        if (moreSelect == null) return;

        try {
            // Hook onCreateOptionsMenu — 添加全选/反选
            XposedBridge.hookAllMethods(moreSelect, "onCreateOptionsMenu",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Menu menu = (Menu) param.args[0];
                    if (menu != null) {
                        menu.add(0, 99991, 0, "全选");
                        menu.add(0, 99992, 0, "反选");
                        menu.add(0, 99993, 0, "全选图片");
                        menu.add(0, 99994, 0, "全选链接");
                    }
                }
            });

            // Hook onOptionsItemSelected — 处理点击
            XposedBridge.hookAllMethods(moreSelect, "onOptionsItemSelected",
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    MenuItem item = (MenuItem) param.args[0];
                    int id = item.getItemId();
                    if (id >= 99991 && id <= 99994) {
                        performSelectAction(param.thisObject, id);
                        param.setResult(true);
                    }
                }
            });

            // Hook 选中数量限制
            for (java.lang.reflect.Method m : moreSelect.getDeclaredMethods()) {
                if (m.getReturnType() == int.class
                        && m.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int val = (Integer) param.getResult();
                            if (val == 9 || val == 50 || val == 100) {
                                param.setResult(maxSelectCount);
                                XposedBridge.log("[Batch] 限制: " + val + " → " + maxSelectCount);
                            }
                        }
                    });
                }
                if (m.getReturnType() == boolean.class
                        && m.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(Boolean) param.getResult()) param.setResult(true);
                        }
                    });
                }
            }
            XposedBridge.log("[Batch] ChatMoreSelectUI Hook完成");
        } catch (Throwable t) {
            XposedBridge.log("[Batch] ChatMoreSelectUI异常: " + t.getMessage());
        }
    }

    private static void hookForwardLimit(ClassLoader cl) {
        // SelectConversationUI
        try {
            Class<?> selectConv = XposedHelpers.findClass(
                    "com.tencent.mm.ui.transmit.SelectConversationUI", cl);
            for (java.lang.reflect.Method m : selectConv.getDeclaredMethods()) {
                if (m.getReturnType() == boolean.class
                        && m.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(Boolean) param.getResult()) param.setResult(true);
                        }
                    });
                }
            }
            XposedBridge.log("[Batch] SelectConversationUI Hook完成");
        } catch (Throwable t) {}

        // ConvBoxTransmitUI
        try {
            Class<?> boxTransmit = XposedHelpers.findClass(
                    "com.tencent.mm.ui.transmit.ConvBoxTransmitUI", cl);
            for (java.lang.reflect.Method m : boxTransmit.getDeclaredMethods()) {
                if (m.getReturnType() == int.class
                        && m.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int val = (Integer) param.getResult();
                            if (val > 0 && val <= 100) param.setResult(maxSelectCount);
                        }
                    });
                }
            }
            XposedBridge.log("[Batch] ConvBoxTransmitUI Hook完成");
        } catch (Throwable t) {}
    }

    private static void performSelectAction(Object activity, int actionId) {
        try {
            View root = ((android.app.Activity) activity).getWindow().getDecorView();
            List<CheckBox> checkBoxes = findAllCheckBoxes(root);
            int toggled = 0;
            for (CheckBox cb : checkBoxes) {
                boolean shouldCheck = false;
                switch (actionId) {
                    case 99991: shouldCheck = true; break;
                    case 99992: shouldCheck = !cb.isChecked(); break;
                    case 99993: shouldCheck = isImageItem(cb); break;
                    case 99994: shouldCheck = isLinkItem(cb); break;
                }
                if (shouldCheck && !cb.isChecked()) {
                    cb.setChecked(true);
                    cb.performClick();
                    toggled++;
                } else if (!shouldCheck && cb.isChecked() && actionId == 99992) {
                    cb.setChecked(false);
                    toggled++;
                }
            }
            XposedBridge.log("[Batch] " + (actionId == 99991 ? "全选" :
                    actionId == 99992 ? "反选" : actionId == 99993 ? "全选图片" : "全选链接")
                    + ": " + toggled + "/" + checkBoxes.size());
        } catch (Throwable t) {
            XposedBridge.log("[Batch] 操作失败: " + t.getMessage());
        }
    }

    private static List<CheckBox> findAllCheckBoxes(View v) {
        List<CheckBox> list = new ArrayList<>();
        if (v instanceof CheckBox) list.add((CheckBox) v);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++)
                list.addAll(findAllCheckBoxes(vg.getChildAt(i)));
        }
        return list;
    }

    private static boolean isImageItem(View cb) {
        View parent = (View) cb.getParent();
        if (parent == null) return false;
        String desc = parent.getContentDescription() != null
                ? parent.getContentDescription().toString() : "";
        return desc.contains("图片") || desc.contains("image");
    }

    private static boolean isLinkItem(View cb) {
        View parent = (View) cb.getParent();
        if (parent == null) return false;
        String desc = parent.getContentDescription() != null
                ? parent.getContentDescription().toString() : "";
        return desc.contains("链接") || desc.contains("link");
    }
}
