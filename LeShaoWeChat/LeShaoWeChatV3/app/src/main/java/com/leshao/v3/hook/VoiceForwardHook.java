package com.leshao.v3.hook;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 语音消息转发 — 按 WeKit 注入流程文档 (修正版) 实现
 * ======================================================
 *
 * WeKit 核心流程 (in9.B() case 21):
 *   1. DexKit 动态定位 methodCreateMenu (menu_obj, view)
 *   2. View.getTag() → 消息对象
 *   3. 反射查找 addMenuItem(int, CharSequence, Drawable)
 *   4. 调用 addMenuItem(id, "转发 [K]", icon)
 *
 * 本实现 (无 DexKit 情况下的等价方案):
 *   P1: View.performLongClick() 拦截 → 捕获被长按的 View 的 tag (消息)
 *   P2: PopupWindow.showAsDropDown 拦截 → 注入 "语音转发" 到菜单内容视图
 *   P3: Dialog.show() 拦截 → 同 P2, 处理 Dialog 型菜单
 *   P4: ContextMenu 标准回调 → 兜底方案
 */
public class VoiceForwardHook {

    private static final String TAG = "VoiceFwd";
    private static final int MENU_ID = 777001;
    private static volatile boolean sHooked = false;
    private static volatile boolean sEnabled = true;
    private static volatile Activity sChatAct;
    private static volatile Object sPendingMsg;
    private static volatile String sPendingTalker;
    private static volatile long sLastCaptureTime = 0;
    private static final long CAPTURE_TTL_MS = 3000;

    public static void setEnabled(boolean v) {
        sEnabled = v;
        LogWriter.log(TAG, "setEnabled=" + v);
    }

    public static void hook() {
        if (sHooked) return;
        ClassLoader cl = ContextManager.getClassLoader();
        if (cl == null) { LogWriter.log(TAG, "cl not ready"); return; }

        hookChatActivity(cl);
        hookLongClickCapture();
        hookPopupWindowMenu();
        hookDialogMenu();
        hookContextMenu(cl);

        sHooked = true;
        LogWriter.log(TAG, "hooks installed (performLongClick + PopupWindow + Dialog + ContextMenu)");
    }

    // ===== 聊天页 Activity 追踪 =====
    private static void hookChatActivity(ClassLoader cl) {
        try {
            Class<?> chattingUI = cl.loadClass("com.tencent.mm.ui.chatting.ChattingUI");
            XposedBridge.hookAllMethods(chattingUI, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    sChatAct = (Activity) param.thisObject;
                }
            });
            XposedBridge.hookAllMethods(chattingUI, "onPause", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (sChatAct == param.thisObject) sChatAct = null;
                }
            });
            LogWriter.log(TAG, "chatActivity tracking ok");
        } catch (Throwable t) {
            LogWriter.log(TAG, "chatActivity err: " + t.getMessage());
        }
    }

    // ===== P1: View.performLongClick 拦截 — 捕获消息 ===
    private static void hookLongClickCapture() {
        try {
            XposedBridge.hookAllMethods(View.class, "performLongClick", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    View v = (View) param.thisObject;
                    Object tag = v.getTag();
                    if (tag == null) return;
                    tryCapture(v, tag);
                }
            });
            LogWriter.log(TAG, "P1: performLongClick hook ok");
        } catch (Throwable t) {
            LogWriter.log(TAG, "P1 err: " + t.getMessage());
        }
    }

    // ===== P2: PopupWindow 拦截 — 注入菜单项 ===
    private static void hookPopupWindowMenu() {
        try {
            XposedBridge.hookAllMethods(PopupWindow.class, "showAsDropDown", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    if (isCaptureStale()) return;
                    if (sPendingMsg == null) return;
                    PopupWindow pw = (PopupWindow) param.thisObject;
                    injectIntoMenuView(pw.getContentView(), pw);
                }
            });
            LogWriter.log(TAG, "P2: showAsDropDown hook ok");

            XposedBridge.hookAllMethods(PopupWindow.class, "showAtLocation", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    if (isCaptureStale()) return;
                    if (sPendingMsg == null) return;
                    PopupWindow pw = (PopupWindow) param.thisObject;
                    injectIntoMenuView(pw.getContentView(), pw);
                }
            });
            LogWriter.log(TAG, "P2: showAtLocation hook ok");
        } catch (Throwable t) {
            LogWriter.log(TAG, "P2 err: " + t.getMessage());
        }
    }

    // ===== P3: Dialog 拦截 — 处理 Dialog 型菜单 ===
    private static void hookDialogMenu() {
        try {
            XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    if (isCaptureStale()) return;
                    if (sPendingMsg == null) return;
                    Dialog d = (Dialog) param.thisObject;
                    View decor = d.getWindow() != null ? d.getWindow().getDecorView() : null;
                    if (decor != null) {
                        injectIntoMenuView(decor, null);
                    }
                }
            });
            LogWriter.log(TAG, "P3: Dialog.show hook ok");
        } catch (Throwable t) {
            LogWriter.log(TAG, "P3 err: " + t.getMessage());
        }
    }

    // ===== P4: 标准 ContextMenu 兜底 ===
    private static void hookContextMenu(ClassLoader cl) {
        try {
            Class<?> chattingUI = cl.loadClass("com.tencent.mm.ui.chatting.ChattingUI");
            XposedBridge.hookAllMethods(chattingUI, "onCreateContextMenu", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    try {
                        ContextMenu menu = (ContextMenu) param.args[0];
                        View v = (View) param.args[1];
                        tryCapture(v, v.getTag());
                        if (sPendingMsg != null && menu != null) {
                            menu.add(0, MENU_ID, 0, "语音转发");
                            LogWriter.log(TAG, "P4: +ctxMenu");
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "P4 err: " + t.getMessage());
                    }
                }
            });
            LogWriter.log(TAG, "P4: onCreateContextMenu ok");

            XposedBridge.hookAllMethods(chattingUI, "onContextItemSelected", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    try {
                        MenuItem item = (MenuItem) param.args[0];
                        if (item.getItemId() == MENU_ID || "语音转发".equals(item.getTitle())) {
                            executeForward();
                            param.setResult(true);
                            LogWriter.log(TAG, "P4: ctxItem click");
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "P4: onContextItemSelected ok");
        } catch (Throwable t) {
            LogWriter.log(TAG, "P4 fail: " + t.getMessage());
        }
    }

    // ===== 菜单项注入 =====
    private static void injectIntoMenuView(View root, PopupWindow pw) {
        if (root == null) return;
        try {
            LinearLayout target = findMenuContainer(root);
            if (target == null) return;

            if (alreadyInjected(target)) return;

            Context ctx = sChatAct != null ? sChatAct : target.getContext();
            TextView tv = new TextView(ctx);
            tv.setText("语音转发");
            tv.setTextSize(16);
            tv.setPadding(48, 32, 48, 32);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setClickable(true);
            tv.setFocusable(true);
            tv.setTextColor(0xFF333333);
            tv.setBackgroundResource(android.R.drawable.list_selector_background);
            tv.setOnClickListener(v -> {
                if (pw != null) pw.dismiss();
                executeForward();
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            target.addView(tv, lp);
            LogWriter.log(TAG, "menu item injected");
        } catch (Throwable t) {
            LogWriter.log(TAG, "inject err: " + t.getClass().getSimpleName());
        }
    }

    private static LinearLayout findMenuContainer(View v) {
        if (v instanceof LinearLayout) {
            LinearLayout ll = (LinearLayout) v;
            if (ll.getOrientation() == LinearLayout.VERTICAL && ll.getChildCount() > 0) {
                return ll;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                LinearLayout found = findMenuContainer(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean alreadyInjected(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof TextView && "语音转发".equals(((TextView) child).getText())) {
                return true;
            }
        }
        return false;
    }

    // ===== 消息捕获 =====
    private static void tryCapture(View v, Object tag) {
        if (v == null || tag == null) return;
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
                sLastCaptureTime = System.currentTimeMillis();
                LogWriter.log(TAG, "captured msgId=" + msgId + " talker=" + sPendingTalker);
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "capture err: " + t.getMessage());
        }
    }

    private static boolean isCaptureStale() {
        if (sPendingMsg == null) return true;
        if (System.currentTimeMillis() - sLastCaptureTime > CAPTURE_TTL_MS) {
            sPendingMsg = null;
            sPendingTalker = null;
            return true;
        }
        return false;
    }

    // ===== 转发执行 =====
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
