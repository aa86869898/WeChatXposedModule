package com.leshao.v3.hook;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupWindow;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 语音消息转发 — WeChat 8.0.76 专用
 * ==============================
 *
 * 按 WeKit 注入流程文档 (FINAL v3) 实现:
 *   case 21 等效: hook 微信 methodCreateMenu(menu_obj, view)
 *                 → View.getTag() → 消息
 *                 → addMenuItem(int, CharSequence, Drawable) 注入"语音转发"
 *   case 22 等效: hook 微信 click handler
 *                 → MenuItem.getItemId() == 777001 → executeForward()
 *
 * 核心策略: 全量 hook MMPopupMenu 族类 → 发现 + 注入
 */
public class VoiceForwardHook {

    private static final String TAG = "VoiceFwd";
    private static final int MENU_ID = 777001;
    private static volatile boolean sHooked = false;
    private static volatile boolean sEnabled = true;
    private static volatile Activity sChatAct;
    private static volatile Object sPendingMsg;
    private static volatile String sPendingTalker;

    public static void setEnabled(boolean v) {
        sEnabled = v;
        LogWriter.log(TAG, "setEnabled=" + v);
    }

    public static void hook() {
        if (sHooked) return;
        ClassLoader cl = ContextManager.getClassLoader();
        if (cl == null) { LogWriter.log(TAG, "cl not ready"); return; }

        hookChatActivity(cl);
        hookMMPopupMenuDiscovery(cl);
        hookClickHandlers(cl);
        hookFallbackPopupWindow();
        hookFallbackDialog();
        hookFallbackContextMenu(cl);

        sHooked = true;
        LogWriter.log(TAG, "hooks installed");
    }

    // ===== 聊天页 Activity =====
    private static void hookChatActivity(ClassLoader cl) {
        try {
            Class<?> chattingUI = cl.loadClass("com.tencent.mm.ui.chatting.ChattingUI");
            XposedBridge.hookAllMethods(chattingUI, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { sChatAct = (Activity) param.thisObject; }
            });
            XposedBridge.hookAllMethods(chattingUI, "onPause", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { if (sChatAct == param.thisObject) sChatAct = null; }
            });
        } catch (Throwable ignored) {}
    }

    // ===== 核心: Hook WeChat 内部菜单类 ====
    private static void hookMMPopupMenuDiscovery(ClassLoader cl) {
        String[] mmMenuClasses = {
            "com.tencent.mm.ui.tools.MMPopupMenu",
            "com.tencent.mm.ui.base.MMPopupMenu",
            "com.tencent.mm.ui.widget.MMPopupMenu",
            "com.tencent.mm.ui.tools.MMContextMenu",
            "com.tencent.mm.ui.base.MMContextMenu",
            "com.tencent.mm.ui.base.MMMenu",
            "com.tencent.mm.ui.tools.MMMenu",
            "com.tencent.mm.ui.chatting.component.ChattingContextMenu",
        };

        for (String clsName : mmMenuClasses) {
            try {
                Class<?> cls = cl.loadClass(clsName);
                hookAllMMMenuMethods(cls, clsName);
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                LogWriter.log(TAG, "err loading " + clsName + ": " + t.getClass().getSimpleName());
            }
        }
    }

    private static void hookAllMMMenuMethods(Class<?> cls, String clsName) {
        int count = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            final String mName = m.getName();
            final Class<?>[] paramTypes = m.getParameterTypes();
            final int paramCount = paramTypes.length;

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    onMMMenuMethodCalled(param.thisObject, param.args, clsName, mName, paramCount, false);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    onMMMenuMethodCalled(param.thisObject, param.args, clsName, mName, paramCount, true);
                }
            });
            count++;
        }
        if (count > 0) {
            LogWriter.log(TAG, "DISC: hooked " + count + " methods on " + clsName);
        }
    }

    private static void onMMMenuMethodCalled(Object self, Object[] args, String clsName,
                                              String mName, int paramCount, boolean isAfter) {
        if (!sEnabled) return;

        for (Object arg : args) {
            if (arg instanceof View) {
                View v = (View) arg;
                if (v.getTag() != null) {
                    tryCapture(v.getTag());
                }
            }
        }

        if (sPendingMsg != null) {
            boolean ok = injectMenuItems(self, clsName, mName);
            if (ok) {
                LogWriter.log(TAG, "INJECTED into " + clsName + "." + mName + "(" + paramCount + " args)" + " after=" + isAfter);
            }
        }
    }

    // ===== addMenuItem 注入 — 按 FINAL 文档签名 ====
    private static boolean injectMenuItems(Object menuObj, String clsName, String methodName) {
        try {
            Class<?> menuClass = menuObj.getClass();

            for (Method m : menuClass.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                m.setAccessible(true);

                // ★ 文档签名: addMenuItem(int, CharSequence, Drawable)
                if (pts.length == 3 && (pts[0] == int.class || pts[0] == Integer.class)
                    && (pts[1] == String.class || pts[1] == CharSequence.class)
                    && (pts[2] == Drawable.class || pts[2] == Object.class)) {
                    try { m.invoke(menuObj, MENU_ID, "语音转发", null); return true; }
                    catch (Throwable ignored) {}
                }
                // addMenuItem(int, CharSequence) — 2 参数简化版
                if (pts.length == 2 && (pts[0] == int.class || pts[0] == Integer.class)
                    && (pts[1] == String.class || pts[1] == CharSequence.class)) {
                    try { m.invoke(menuObj, MENU_ID, "语音转发"); return true; }
                    catch (Throwable ignored) {}
                }
                // addMenuItem(int, int, int, CharSequence) — ContextMenu 标准
                if (pts.length == 4 && (pts[0] == int.class || pts[0] == Integer.class)
                    && (pts[1] == int.class || pts[1] == Integer.class)
                    && (pts[2] == int.class || pts[2] == Integer.class)
                    && (pts[3] == String.class || pts[3] == CharSequence.class)) {
                    try { m.invoke(menuObj, 0, MENU_ID, 0, "语音转发"); return true; }
                    catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ===== Click Handler: case 22 等效 ====
    private static void hookClickHandlers(ClassLoader cl) {
        String[] uiClasses = {
            "com.tencent.mm.ui.chatting.ChattingUI",
            "com.tencent.mm.ui.chatting.ChattingUIFragment",
            "com.tencent.mm.ui.chatting.BaseChattingUIFragment",
            "com.tencent.mm.ui.MMActivity",
        };

        for (String clsName : uiClasses) {
            try {
                Class<?> cls = cl.loadClass(clsName);

                XposedBridge.hookAllMethods(cls, "onContextItemSelected", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!sEnabled) return;
                        try {
                            MenuItem item = (MenuItem) param.args[0];
                            if (item.getItemId() == MENU_ID) {
                                executeForward();
                                param.setResult(true);
                                LogWriter.log(TAG, "CLICK: onContextItemSelected on " + clsName);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                LogWriter.log(TAG, "CLICK: hook onContextItemSelected on " + clsName);

            } catch (Throwable ignored) {}
        }
    }

    // ===== 备用: PopupWindow ====
    private static void hookFallbackPopupWindow() {
        try {
            XposedBridge.hookAllMethods(PopupWindow.class, "showAsDropDown", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    View anchor = (View) param.args[0];
                    Object tag = anchor.getTag();
                    if (tag != null) tryCapture(tag);
                    if (sPendingMsg != null) {
                        LogWriter.log(TAG, "FB_PW: showAsDropDown anchor=" + anchor.getClass().getSimpleName());
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    // ===== 备用: Dialog ====
    private static void hookFallbackDialog() {
        try {
            XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (sPendingMsg != null) {
                        LogWriter.log(TAG, "FB_DLG: show " + param.thisObject.getClass().getSimpleName());
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    // ===== 备用: ContextMenu ====
    private static void hookFallbackContextMenu(ClassLoader cl) {
        try {
            Class<?> chattingUI = cl.loadClass("com.tencent.mm.ui.chatting.ChattingUI");
            XposedBridge.hookAllMethods(chattingUI, "onCreateContextMenu", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    ContextMenu menu = (ContextMenu) param.args[0];
                    View v = (View) param.args[1];
                    Object tag = v.getTag();
                    tryCapture(tag);
                    if (sPendingMsg != null && menu != null) {
                        menu.add(0, MENU_ID, 0, "语音转发");
                        LogWriter.log(TAG, "FB_CTX: +ctxMenu");
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    // ===== 消息捕获 =====
    private static void tryCapture(Object tag) {
        if (tag == null) return;
        try {
            Object msgObj = tag;
            try {
                Object inner = XposedHelpers.callMethod(tag, "a", new Class[]{boolean.class}, false);
                if (inner != null) msgObj = inner;
            } catch (Throwable ignored) {}

            long msgId = extractMsgId(msgObj);
            if (msgId > 0) {
                sPendingMsg = msgObj;
                sPendingTalker = extractTalker(msgObj);
                if (sPendingTalker == null) sPendingTalker = "";
                LogWriter.log(TAG, "captured msgId=" + msgId + " talker=" + sPendingTalker + " tagType=" + tag.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "capture err: " + t.getMessage());
        }
    }

    // ===== 转发执行 (case 22 等效 onClick) ====
    private static void executeForward() {
        if (!sEnabled) return;
        try {
            Object msg = sPendingMsg;
            String talker = sPendingTalker;
            sPendingMsg = null;
            sPendingTalker = null;

            if (msg == null) { showToast("请先选中一条语音消息"); return; }

            long msgId = extractMsgId(msg);
            if (msgId <= 0) { showToast("无法获取消息信息"); return; }

            String extTalker = extractTalker(msg);
            if (talker == null || talker.isEmpty()) talker = extTalker;
            if (talker == null) talker = "";

            Context ctx = sChatAct != null ? sChatAct : ContextManager.getAppContext();
            if (ctx == null) { showToast("context unavailable"); return; }

            ClassLoader cl = ctx.getClassLoader();
            Class<?> fwdUI = null;
            for (String n : new String[]{
                "com.tencent.mm.ui.transmit.SelectConversationUI",
                "com.tencent.mm.ui.transmit.MsgRetransmitUI",
            }) { try { fwdUI = cl.loadClass(n); break; } catch (Throwable ignored) {} }

            if (fwdUI == null) { showToast("微信版本不兼容"); return; }

            Intent intent = new Intent(ctx, fwdUI);
            intent.putExtra("Retr_Msg_content", talker);
            intent.putExtra("Retr_Msg_Type", 1);
            intent.putExtra("Retr_Msg_Id", msgId);
            intent.putExtra("Retr_Msg_Img_Type", 0);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            showToast("已打开转发界面");

        } catch (Throwable t) {
            LogWriter.log(TAG, "exec err: " + t.getMessage());
            showToast("转发失败: " + t.getMessage());
        }
    }

    // ===== 工具 =====
    private static long extractMsgId(Object msg) {
        try { return (long) XposedHelpers.callMethod(msg, "getMsgId"); } catch (Throwable ignored) {}
        try { return XposedHelpers.getLongField(msg, "field_msgId"); } catch (Throwable ignored) {}
        try { return XposedHelpers.getLongField(msg, "msgId"); } catch (Throwable ignored) {}
        for (Field f : msg.getClass().getDeclaredFields()) {
            if (f.getType() == long.class && f.getName().toLowerCase().contains("msgid")) {
                try { f.setAccessible(true); return f.getLong(msg); } catch (Throwable ignored) {}
            }
        }
        for (Field f : msg.getClass().getFields()) {
            if (f.getType() == long.class && f.getName().toLowerCase().contains("msgid")) {
                try { return f.getLong(msg); } catch (Throwable ignored) {}
            }
        }
        return 0;
    }

    private static String extractTalker(Object msg) {
        try { return (String) XposedHelpers.callMethod(msg, "N0"); } catch (Throwable ignored) {}
        try { return (String) XposedHelpers.getObjectField(msg, "field_talker"); } catch (Throwable ignored) {}
        try { return (String) XposedHelpers.getObjectField(msg, "talker"); } catch (Throwable ignored) {}
        for (Field f : msg.getClass().getDeclaredFields()) {
            if (f.getType() == String.class && f.getName().toLowerCase().contains("talker")) {
                try { f.setAccessible(true); return (String) f.get(msg); } catch (Throwable ignored) {}
            }
        }
        return "";
    }

    private static void showToast(String text) {
        try {
            Context ctx = sChatAct != null ? sChatAct : ContextManager.getAppContext();
            if (ctx != null) Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }
}
