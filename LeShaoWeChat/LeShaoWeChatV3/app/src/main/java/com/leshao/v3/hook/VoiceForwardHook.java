package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

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

        hookChatUICtxMenu(cl);
        hookMenuCreateOnBaseFragments(cl);
        hookMessageTapCapture(cl);

        sHooked = true;
        LogWriter.log(TAG, "all hooks installed");
    }

    // ===== 标准 Android ContextMenu: ChattingUI =====
    private static void hookChatUICtxMenu(ClassLoader cl) {
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

            XposedBridge.hookAllMethods(chattingUI, "onCreateContextMenu", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    try {
                        ContextMenu menu = (ContextMenu) param.args[0];
                        View v = (View) param.args[1];
                        if (hasVoiceCapture(v)) {
                            menu.add(0, MENU_ID, 0, "语音转发");
                            LogWriter.log(TAG, "+ctxMenu item added");
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "onCrtCtxMenu ERR: " + t.getMessage());
                    }
                }
            });
            LogWriter.log(TAG, "onCreateContextMenu hooked");

            XposedBridge.hookAllMethods(chattingUI, "onContextItemSelected", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    try {
                        MenuItem item = (MenuItem) param.args[0];
                        if (item.getItemId() == MENU_ID || "语音转发".equals(item.getTitle())) {
                            executeForward();
                            param.setResult(true);
                        }
                    } catch (Throwable t) {}
                }
            });
            LogWriter.log(TAG, "onContextItemSelected hooked");

        } catch (Throwable t) {
            LogWriter.log(TAG, "ctxMenu ERR: " + t.getMessage());
        }
    }

    // ===== 微信 MM 菜单: 多类尝试 =====
    private static void hookMenuCreateOnBaseFragments(ClassLoader cl) {
        String[] candidateClasses = {
            "com.tencent.mm.ui.chatting.ChattingUI",
            "com.tencent.mm.ui.MMFragment",
            "com.tencent.mm.ui.MMFragmentActivity",
            "com.tencent.mm.ui.MMActivity",
        };

        for (String clsName : candidateClasses) {
            try {
                Class<?> cls = cl.loadClass(clsName);
                XposedBridge.hookAllMethods(cls, "onCreateMMMenu", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!sEnabled) return;
                        Object mmMenu = param.args[0];
                        if (mmMenu == null) return;
                        try {
                            Method addMethod = findAddMethod(mmMenu.getClass());
                            if (addMethod != null) {
                                addMethod.invoke(mmMenu, MENU_ID, "语音转发");
                                LogWriter.log(TAG, "+MMMenu(" + clsName + ")");
                            }
                        } catch (Throwable inner) {
                            LogWriter.log(TAG, "addMM ERR(" + clsName + "): "
                                + inner.getClass().getSimpleName());
                        }
                    }
                });
                LogWriter.log(TAG, "onCreateMMMenu hooked on " + clsName);
            } catch (Throwable t) {
                LogWriter.log(TAG, "onCreateMMMenu MISS on " + clsName);
            }
        }
    }

    // ===== 消息捕获: RecyclerView / ListView 长按拦截 =====
    private static void hookMessageTapCapture(ClassLoader cl) {
        try {
            Class<?> fragmentCls = cl.loadClass("com.tencent.mm.ui.chatting.BaseChattingUIFragment");
            XposedHelpers.findAndHookMethod(fragmentCls, "onCreateView",
                android.view.LayoutInflater.class,
                ViewGroup.class,
                android.os.Bundle.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        View root = (View) param.getResult();
                        if (root == null) return;
                        installLongClickCapture(root);
                    }
                });
            LogWriter.log(TAG, "msg capture hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "msg capture ERR: " + t.getMessage());
        }
    }

    private static void installLongClickCapture(View root) {
        List<View> listViews = new ArrayList<>();
        List<View> recyclerViews = new ArrayList<>();
        collectListAndRecycler(root, listViews, recyclerViews);

        for (View lv : listViews) {
            if (lv instanceof android.widget.ListView) {
                android.widget.ListView l = (android.widget.ListView) lv;
                l.setOnItemLongClickListener((parent, v, position, id) -> {
                    tryCaptureFromView(v);
                    return false;
                });
                LogWriter.log(TAG, "ListView capture ok");
            }
        }
        for (View rvView : recyclerViews) {
            if (rvView instanceof androidx.recyclerview.widget.RecyclerView) {
                final androidx.recyclerview.widget.RecyclerView rv =
                    (androidx.recyclerview.widget.RecyclerView) rvView;
                rv.addOnItemTouchListener(
                    new androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener() {
                        @Override public boolean onInterceptTouchEvent(
                                androidx.recyclerview.widget.RecyclerView r,
                                android.view.MotionEvent e) {
                            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                                View child = r.findChildViewUnder(e.getX(), e.getY());
                                if (child != null) tryCaptureFromView(child);
                            }
                            return false;
                        }
                    });
                LogWriter.log(TAG, "RecyclerView capture ok");
            }
        }
    }

    private static void collectListAndRecycler(View v, List<View> lvs, List<View> rvs) {
        if (v instanceof android.widget.ListView) { lvs.add(v); return; }
        if (v instanceof androidx.recyclerview.widget.RecyclerView) { rvs.add(v); return; }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectListAndRecycler(vg.getChildAt(i), lvs, rvs);
            }
        }
    }

    // ===== 消息捕获辅助 =====
    private static boolean hasVoiceCapture(View v) {
        tryCaptureFromView(v);
        return sPendingMsg != null;
    }

    private static void tryCaptureFromView(View v) {
        sPendingMsg = null;
        sPendingTalker = null;
        if (v == null) return;
        try {
            Object tag = v.getTag();
            if (tag == null) return;

            try {
                Object msgObj = XposedHelpers.callMethod(tag, "a", new Class[]{boolean.class}, false);
                if (msgObj != null) tag = msgObj;
            } catch (Throwable ignored) {}

            long msgId = extractMsgId(tag);
            if (msgId > 0) {
                sPendingMsg = tag;
                sPendingTalker = extractTalker(tag);
                if (sPendingTalker == null) sPendingTalker = "";
            }
        } catch (Throwable ignored) {}
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
            Class<?> forwardUI = null;
            for (String name : new String[]{
                "com.tencent.mm.ui.transmit.SelectConversationUI",
                "com.tencent.mm.ui.transmit.MsgRetransmitUI",
            }) { try { forwardUI = cl.loadClass(name); break; } catch (Throwable ignored) {} }

            if (forwardUI == null) { showToast("微信版本不兼容"); return; }

            Intent intent = new Intent(ctx, forwardUI);
            intent.putExtra("Retr_Msg_content", talker);
            intent.putExtra("Retr_Msg_Type", 1);
            intent.putExtra("Retr_Msg_Id", msgId);
            intent.putExtra("Retr_Msg_Img_Type", 0);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            showToast("已打开转发界面");

        } catch (Throwable t) {
            LogWriter.log(TAG, "exec ERR: " + t.getMessage());
            showToast("转发失败: " + t.getMessage());
        }
    }

    // ===== 工具方法 =====
    private static Method findAddMethod(Class<?> menuClass) {
        for (Method m : menuClass.getDeclaredMethods()) {
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length >= 2
                && (pts[0] == int.class || pts[0] == Integer.class)
                && (pts[1] == String.class || pts[1] == CharSequence.class)) {
                m.setAccessible(true); return m;
            }
        }
        for (Method m : menuClass.getMethods()) {
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length >= 2
                && (pts[0] == int.class || pts[0] == Integer.class)
                && (pts[1] == String.class || pts[1] == CharSequence.class)) {
                m.setAccessible(true); return m;
            }
        }
        return null;
    }

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
        return "";
    }

    private static void showToast(String text) {
        try {
            Context ctx = sChatAct != null ? sChatAct : ContextManager.getAppContext();
            if (ctx != null) Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }
}
