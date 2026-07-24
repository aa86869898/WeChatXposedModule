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
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 语音消息转发 — WeChat 8.0.76 发现模式 v4
 * =======================================
 *
 * 目标: 发现微信长按消息时的菜单创建方法 (methodCreateMenu 等效)
 * 策略: hook ChattingUIFragment/ChattingUI 的 View 参数方法
 *       任意触发立即日志，包含方法名和参数计数
 */
public class VoiceForwardHook {

    private static final String TAG = "VF";
    private static final int MENU_ID = 777001;
    private static volatile boolean sHooked = false;
    private static volatile boolean sEnabled = true;
    private static volatile Activity sChatAct;
    private static volatile Object sPendingMsg;
    private static volatile String sPendingTalker;
    private static final AtomicInteger sCallCount = new AtomicInteger(0);
    private static final int MAX_LOG = 30;

    public static void setEnabled(boolean v) {
        sEnabled = v;
        LogWriter.log(TAG, "setEnabled=" + v);
    }

    public static void hook() {
        if (sHooked) return;
        ClassLoader cl = ContextManager.getClassLoader();
        if (cl == null) { LogWriter.log(TAG, "cl not ready"); return; }

        hookChatActivity(cl);
        hookChattingFragMethods(cl);
        installClickHandlers(cl);

        sHooked = true;
        LogWriter.log(TAG, "ready — long-press a msg to see VF:CALL logs");
    }

    // ===== 聊天页 Activity =====
    private static void hookChatActivity(ClassLoader cl) {
        try {
            Class<?> cui = cl.loadClass("com.tencent.mm.ui.chatting.ChattingUI");
            XposedBridge.hookAllMethods(cui, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    sChatAct = (Activity) param.thisObject;
                    sCallCount.set(0);
                }
            });
            XposedBridge.hookAllMethods(cui, "onPause", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (sChatAct == param.thisObject) sChatAct = null;
                }
            });
        } catch (Throwable ignored) {}
    }

    // ===== 核心: Hook ChattingUIFragment/BF 的 View 参数方法 ====
    private static void hookChattingFragMethods(ClassLoader cl) {
        String[] targets = {
            "com.tencent.mm.ui.chatting.ChattingUIFragment",
            "com.tencent.mm.ui.chatting.BaseChattingUIFragment",
            "com.tencent.mm.ui.chatting.ChattingUI",
        };

        for (String clsName : targets) {
            try {
                Class<?> cls = cl.loadClass(clsName);
                int cnt = hookMethodsWithViewParam(cls, clsName);
                LogWriter.log(TAG, "hooked " + cnt + " view-methods on " + cls.getSimpleName());
            } catch (ClassNotFoundException e) {
                LogWriter.log(TAG, clsName + " NOT FOUND");
            }
        }
    }

    private static int hookMethodsWithViewParam(Class<?> cls, String clsName) {
        int count = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;

            Class<?>[] pts = m.getParameterTypes();
            boolean hasView = false;
            boolean hasMenuLike = false;
            for (Class<?> pt : pts) {
                if (View.class.isAssignableFrom(pt)) hasView = true;
                if (pt == Object.class) hasView = true;
                if (pt.getName().contains("Menu")) hasMenuLike = true;
            }

            if (!hasView && !hasMenuLike && pts.length < 2) continue;

            final String mName = m.getName();
            final int pc = pts.length;
            final boolean hv = hasView;

            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!sEnabled) return;
                        int n = sCallCount.incrementAndGet();
                        if (n <= MAX_LOG) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("VF:CALL[").append(n).append("] ");
                            sb.append(cls.getSimpleName()).append(".").append(mName);
                            sb.append("(").append(pc).append(" args)");
                            if (hv) {
                                for (int i = 0; i < param.args.length && i < pc; i++) {
                                    if (param.args[i] instanceof View) {
                                        View v = (View) param.args[i];
                                        Object t = v.getTag();
                                        sb.append(" arg[").append(i).append("]=View");
                                        if (t != null) {
                                            sb.append("+tag=").append(t.getClass().getSimpleName());
                                            tryCapture(t);
                                        } else {
                                            sb.append("+tag=null");
                                        }
                                    }
                                }
                            }
                            LogWriter.log(TAG, sb.toString());

                            if (sPendingMsg != null) {
                                boolean ok = tryAddMenuItem(param.thisObject);
                                LogWriter.log(TAG, "VF:INJECT " + (ok ? "OK" : "FAIL") + " into " + cls.getSimpleName() + "." + mName);
                            }
                        }
                    }
                });
                count++;
            } catch (Throwable ignored) {}
        }
        return count;
    }

    // ===== addMenuItem 注入 ====
    private static boolean tryAddMenuItem(Object menuObj) {
        try {
            for (Method m : menuObj.getClass().getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                m.setAccessible(true);

                if (pts.length == 3 && (pts[0] == int.class || pts[0] == Integer.class)
                    && (pts[1] == String.class || pts[1] == CharSequence.class)
                    && (pts[2] == Drawable.class || pts[2] == Object.class)) {
                    try { m.invoke(menuObj, MENU_ID, "语音转发", null); return true; }
                    catch (Throwable ignored) {}
                }
                if (pts.length == 2 && (pts[0] == int.class || pts[0] == Integer.class)
                    && (pts[1] == String.class || pts[1] == CharSequence.class)) {
                    try { m.invoke(menuObj, MENU_ID, "语音转发"); return true; }
                    catch (Throwable ignored) {}
                }
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

    // ===== Click handler: case 22 等效 ====
    private static void installClickHandlers(ClassLoader cl) {
        String[] targets = {
            "com.tencent.mm.ui.chatting.ChattingUI",
            "com.tencent.mm.ui.chatting.ChattingUIFragment",
            "com.tencent.mm.ui.chatting.BaseChattingUIFragment",
            "com.tencent.mm.ui.MMActivity",
        };
        for (String clsName : targets) {
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
                                LogWriter.log(TAG, "VF:CLICK on " + cls.getSimpleName());
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    // Note: click handlers install happens inside hook() — see MainHook
    // For now, we rely on the fact that onContextItemSelected will fire
    // if our addMenuItem was called with our MENU_ID on a ContextMenu

    // ===== 消息捕获 ====
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
            }
        } catch (Throwable ignored) {}
    }

    // ===== 转发执行 ====
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
