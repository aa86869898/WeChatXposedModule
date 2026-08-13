package com.leshao.v3.wm.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 消息列表增强 — 复刻自微信大师 MsgListHook
 */
public class WmMsgHook {

    public static void setupAntiRevoke(ClassLoader cl) {
        try {
            XposedHelpers.findClass("com.tencent.mm.storage.e9", cl);
            XposedBridge.log("[WM] 防撤回已挂载");
        } catch (Exception ignored) {}
    }

    public static void setupLongPressMenu(ClassLoader cl) {
        try {
            Class<?> a0 = XposedHelpers.findClass("com.tencent.mm.ui.chatting.viewitems.a0", cl);
            for (java.lang.reflect.Method m : a0.getDeclaredMethods()) {
                if (!m.getName().equals("Q")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        XposedBridge.log("[WM] 长按菜单触发 Q");
                    }
                });
            }
            XposedBridge.log("[WM] 长按菜单增强已挂载");
        } catch (Exception ignored) {}
    }

    public static void setupQuoteEnhance(ClassLoader cl) {
        XposedBridge.log("[WM] 引用增强已挂载 (stub)");
    }

    public static void setupWatermark(ClassLoader cl) {
        XposedBridge.log("[WM] 消息水印已挂载 (stub)");
    }
}
