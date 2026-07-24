package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

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
 * 语音消息转发 — WeChat 8.0.76 Adapter 发现模式 v5
 * ==============================================
 *
 * 已排除: ChattingUIFragment/ChattingUI (无长按方法)
 * 新策略: 动态发现 RecyclerView Adapter → hook 其方法
 */
public class VoiceForwardHook {

    private static final String TAG = "VF";
    private static final int MENU_ID = 777001;
    private static volatile boolean sHooked = false;
    private static volatile boolean sAdapterHooked = false;
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
        hookChatFragmentForAdapter(cl);
        installClickHandlers(cl);

        sHooked = true;
        LogWriter.log(TAG, "ready — enter chat to discover Adapter");
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

    // ===== 动态发现 RecyclerView Adapter ====
    private static void hookChatFragmentForAdapter(final ClassLoader cl) {
        try {
            Class<?> cf = cl.loadClass("com.tencent.mm.ui.chatting.ChattingUIFragment");

            XposedBridge.hookAllMethods(cf, "onCreateView", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (sAdapterHooked) return;
                    sCallCount.set(0);

                    View root = (View) param.getResult();
                    LogWriter.log(TAG, "onCreateView returned: " + (root == null ? "null" : root.getClass().getSimpleName()));

                    if (root == null) return;

                    int childCount = root instanceof ViewGroup ? ((ViewGroup) root).getChildCount() : -1;
                    RecyclerView rv = findRecyclerView(root);
                    if (rv == null) {
                        LogWriter.log(TAG, "no RecyclerView: root=" + root.getClass().getSimpleName() + " children=" + childCount);
                        return;
                    }

                    RecyclerView.Adapter<?> adapter = rv.getAdapter();
                    if (adapter == null) {
                        LogWriter.log(TAG, "RecyclerView found but Adapter null");
                        return;
                    }

                    String adapterCls = adapter.getClass().getName();
                    LogWriter.log(TAG, "found Adapter: " + adapterCls);

                    try {
                        Class<?> adCls = cl.loadClass(adapterCls);
                        int cnt = hookAllNonStaticMethods(adCls);
                        LogWriter.log(TAG, "hooked " + cnt + " methods on Adapter " + adCls.getSimpleName());
                        sAdapterHooked = true;
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "failed to hook Adapter: " + t.getMessage());
                    }
                }
            });
            LogWriter.log(TAG, "hookAllMethods onCreateView on ChattingUIFragment installed");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook onCreateView failed: " + t.getMessage());
        }
    }

    private static RecyclerView findRecyclerView(View v) {
        if (v instanceof RecyclerView) return (RecyclerView) v;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                RecyclerView rv = findRecyclerView(vg.getChildAt(i));
                if (rv != null) return rv;
            }
        }
        return null;
    }

    private static int hookAllNonStaticMethods(Class<?> cls) {
        int count = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            try {
                XposedBridge.hookMethod(m, createCallback(cls.getSimpleName(), m.getName(), m.getParameterTypes().length));
                count++;
            } catch (Throwable ignored) {}
        }
        for (Class<?> sup = cls.getSuperclass(); sup != null && sup != Object.class; sup = sup.getSuperclass()) {
            for (Method m : sup.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                try {
                    XposedBridge.hookMethod(m, createCallback(sup.getSimpleName(), m.getName(), m.getParameterTypes().length));
                    count++;
                } catch (Throwable ignored) {}
            }
        }
        return count;
    }

    private static XC_MethodHook createCallback(final String clsName, final String mName, final int pc) {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!sEnabled) return;
                int n = sCallCount.incrementAndGet();
                if (n > MAX_LOG) return;

                StringBuilder sb = new StringBuilder("VF:CALL[").append(n).append("] ");
                sb.append(clsName).append(".").append(mName).append("(").append(pc).append(")");

                boolean foundView = false;
                for (int i = 0; i < param.args.length; i++) {
                    Object a = param.args[i];
                    if (a instanceof View) {
                        View v = (View) a;
                        Object t = v.getTag();
                        sb.append(" a[").append(i).append("]=View+tag=").append(t != null ? t.getClass().getSimpleName() : "null");
                        foundView = true;
                        if (t != null) tryCapture(t);
                    }
                }
                if (!foundView) {
                    for (int i = 0; i < Math.min(param.args.length, 4); i++) {
                        Object a = param.args[i];
                        sb.append(" a[").append(i).append("]=").append(a != null ? a.getClass().getSimpleName() : "null");
                    }
                }

                LogWriter.log(TAG, sb.toString());

                if (sPendingMsg != null && param.args.length >= 1) {
                    for (Object a : param.args) {
                        if (a != null && !(a instanceof View) && !(a instanceof Number) && !(a instanceof Boolean)) {
                            boolean ok = tryAddMenuItem(a);
                            if (ok) {
                                LogWriter.log(TAG, "VF:INJECT OK into " + clsName + "." + mName);
                                break;
                            }
                        }
                    }
                }
            }
        };
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
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ===== Click handler ====
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
                                LogWriter.log(TAG, "VF:CLICK " + cls.getSimpleName());
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

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
