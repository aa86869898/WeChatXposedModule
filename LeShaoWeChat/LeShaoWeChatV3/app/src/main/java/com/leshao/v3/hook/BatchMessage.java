package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import java.util.List;
import java.util.ArrayList;

/**
 * [功能9] 批量消息操作
 * 微信多选链路:
 *   长按消息 → ChatMoreSelectUI → 多选模式
 *   → SelectConversationUI → 转发到会话
 *   → ConvBoxTransmitUI → 合并转发
 * 增强:
 *   1. 突破微信9条合并转发限制
 *   2. 全选/反选功能
 *   3. 按类型选择(全选图片/视频/链接)
 *   4. 转发时自动拆分(超过限制自动分组)
 */
public class BatchMessage {

    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    private static int maxSelectCount = 999;
    private static int forwardSplitSize = 50;

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.batchMessageEnabled) return;

        hookChatMoreSelect(cl);
        hookForwardLimit(cl);
    }

    /**
     * Hook ChatMoreSelectUI — 多选界面
     */
    private static void hookChatMoreSelect(ClassLoader cl) {
        try {
            Class<?> moreSelect = XposedHelpers.findClass(
                    "com.tencent.mm.ui.chatting.ChatMoreSelectUI", cl);

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

            for (java.lang.reflect.Method m : moreSelect.getDeclaredMethods()) {
                if (m.getReturnType() == int.class
                        && m.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int val = (Integer) param.getResult();
                            if (val == 9 || val == 50 || val == 100) {
                                param.setResult(maxSelectCount);
                                Logger.i("[Batch] 限制已修改: " + val + " → " + maxSelectCount);
                            }
                        }
                    });
                }
                if (m.getReturnType() == boolean.class
                        && m.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            boolean result = (Boolean) param.getResult();
                            if (!result) {
                                param.setResult(true);
                                Logger.i("[Batch] 超限检查已绕过");
                            }
                        }
                    });
                }
            }
            Logger.i("[Batch] ChatMoreSelectUI Hook完成");
        } catch (Throwable t) {
            Logger.w("[Batch] ChatMoreSelectUI失败: " + t.getMessage());
        }
    }

    /**
     * Hook SelectConversationUI + ConvBoxTransmitUI — 转发限制
     */
    private static void hookForwardLimit(ClassLoader cl) {
        try {
            Class<?> selectConv = XposedHelpers.findClass(
                    "com.tencent.mm.ui.transmit.SelectConversationUI", cl);

            for (java.lang.reflect.Method m : selectConv.getDeclaredMethods()) {
                if (m.getReturnType() == boolean.class
                        && m.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            boolean result = (Boolean) param.getResult();
                            if (!result) param.setResult(true);
                        }
                    });
                }
            }
            Logger.i("[Batch] SelectConversationUI Hook完成");
        } catch (Throwable t) {}

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
                            if (val > 0 && val <= 100) {
                                param.setResult(maxSelectCount);
                            }
                        }
                    });
                }
            }
            Logger.i("[Batch] ConvBoxTransmitUI Hook完成");
        } catch (Throwable t) {}
    }

    private static void performSelectAction(Object activity, int actionId) {
        try {
            View root = ((android.app.Activity) activity).getWindow().getDecorView();
            List<CheckBox> checkBoxes = findAllCheckBoxes(root);

            int toggled = 0;
            for (int i = 0; i < checkBoxes.size(); i++) {
                CheckBox cb = checkBoxes.get(i);
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

            String actionName = actionId == 99991 ? "全选"
                    : actionId == 99992 ? "反选"
                    : actionId == 99993 ? "全选图片" : "全选链接";
            Logger.i("[Batch] " + actionName + ": " + toggled + "/"
                    + checkBoxes.size() + "条");
        } catch (Throwable t) {
            Logger.w("[Batch] 操作失败: " + t.getMessage());
        }
    }

    private static List<CheckBox> findAllCheckBoxes(View v) {
        List<CheckBox> list = new ArrayList<>();
        if (v instanceof CheckBox) list.add((CheckBox) v);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                list.addAll(findAllCheckBoxes(vg.getChildAt(i)));
            }
        }
        return list;
    }

    private static boolean isImageItem(View cb) {
        View parent = (View) cb.getParent();
        if (parent != null) {
            String contentDesc = parent.getContentDescription() != null
                    ? parent.getContentDescription().toString() : "";
            return contentDesc.contains("图片") || contentDesc.contains("image");
        }
        return false;
    }

    private static boolean isLinkItem(View cb) {
        View parent = (View) cb.getParent();
        if (parent != null) {
            String contentDesc = parent.getContentDescription() != null
                    ? parent.getContentDescription().toString() : "";
            return contentDesc.contains("链接") || contentDesc.contains("link");
        }
        return false;
    }
}
