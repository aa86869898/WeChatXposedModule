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

        hookChattingUIMenu(cl);
        hookMessageCapture(cl);

        sHooked = true;
        LogWriter.log(TAG, "hooks installed");
    }

    // ===== 菜单注入: ChattingUI (标准 ContextMenu + MM 菜单) =====
    private static void hookChattingUIMenu(ClassLoader cl) {
        try {
            Class<?> chattingUI = cl.loadClass("com.tencent.mm.ui.chatting.ChattingUI");

            // 追踪当前 Activity
            XposedBridge.hookAllMethods(chattingUI, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sChatAct = (Activity) param.thisObject;
                }
            });
            XposedBridge.hookAllMethods(chattingUI, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (sChatAct == param.thisObject) sChatAct = null;
                }
            });

            // 路径1: 标准 Android ContextMenu
            try {
                XposedBridge.hookAllMethods(chattingUI, "onCreateContextMenu", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!sEnabled) return;
                        try {
                            ContextMenu menu = (ContextMenu) param.args[0];
                            View v = (View) param.args[1];
                            tryCaptureVoiceMsgFromView(v);
                            if (sPendingMsg != null && menu != null) {
                                menu.add(0, MENU_ID, 0, "语音转发");
                                LogWriter.log(TAG, "added 语音转发 to context menu");
                            }
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "onCreateContextMenu err: " + t.getMessage());
                        }
                    }
                });
                LogWriter.log(TAG, "hooked onCreateContextMenu");
            } catch (Throwable t) {
                LogWriter.log(TAG, "onCreateContextMenu not found: " + t.getMessage());
            }

            // 路径2: 微信定制 MM 菜单 (onCreateMMMenu)
            try {
                XposedBridge.hookAllMethods(chattingUI, "onCreateMMMenu", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!sEnabled) return;
                        try {
                            Object mmMenu = param.args[0];
                            if (mmMenu == null) return;

                            // 尝试通过反射添加菜单项
                            try {
                                Method addMethod = findAddMenuItemMethod(mmMenu.getClass());
                                if (addMethod != null) {
                                    addMethod.invoke(mmMenu, MENU_ID, "语音转发");
                                    LogWriter.log(TAG, "added 语音转发 to MM menu");
                                }
                            } catch (Throwable inner) {
                                LogWriter.log(TAG, "MM addMenu err: " + inner.getMessage());
                            }
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "onCreateMMMenu err: " + t.getMessage());
                        }
                    }
                });
                LogWriter.log(TAG, "hooked onCreateMMMenu");
            } catch (Throwable t) {
                LogWriter.log(TAG, "onCreateMMMenu not found: " + t.getMessage());
            }

            // 菜单点击: 标准 ContextMenu
            XposedBridge.hookAllMethods(chattingUI, "onContextItemSelected", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    try {
                        MenuItem item = (MenuItem) param.args[0];
                        if (item.getItemId() == MENU_ID || "语音转发".equals(item.getTitle())) {
                            executeForward();
                            param.setResult(true);
                            LogWriter.log(TAG, "context menu: voice fwd triggered");
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "context menu click err: " + t.getMessage());
                    }
                }
            });

            // 菜单点击: 微信 MM 菜单
            try {
                XposedBridge.hookAllMethods(chattingUI, "onMMMenuItemSelected", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!sEnabled) return;
                        try {
                            MenuItem item = (MenuItem) param.args[0];
                            if (item.getItemId() == MENU_ID || "语音转发".equals(item.getTitle())) {
                                executeForward();
                                param.setResult(true);
                                LogWriter.log(TAG, "MM menu: voice fwd triggered");
                            }
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "MM menu click err: " + t.getMessage());
                        }
                    }
                });
            } catch (Throwable t) {
                LogWriter.log(TAG, "onMMMenuItemSelected not found: " + t.getMessage());
            }

            LogWriter.log(TAG, "ChattingUI menu hooks done");
        } catch (Throwable t) {
            LogWriter.log(TAG, "ChattingUI hook failed: " + t.getMessage());
        }
    }

    // ===== 消息捕获: RecyclerView 长按 =====
    private static void hookMessageCapture(ClassLoader cl) {
        try {
            Class<?> fragmentCls = cl.loadClass("com.tencent.mm.ui.chatting.BaseChattingUIFragment");
            XposedHelpers.findAndHookMethod(fragmentCls, "onCreateView",
                android.view.LayoutInflater.class,
                ViewGroup.class,
                android.os.Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View root = (View) param.getResult();
                        if (root == null) return;
                        setupCaptureOnRecyclerView(root);
                    }
                });
            LogWriter.log(TAG, "BaseChattingUIFragment hook OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "msg capture hook failed: " + t.getMessage());
        }
    }

    private static void setupCaptureOnRecyclerView(View root) {
        try {
            android.widget.ListView listView = findListView(root);
            if (listView != null) {
                listView.setOnItemLongClickListener((parent, v, position, id) -> {
                    if (!sEnabled) return false;
                    tryCaptureVoiceMsgFromView(v);
                    return false;
                });
                LogWriter.log(TAG, "ListView capture installed");
                return;
            }

            androidx.recyclerview.widget.RecyclerView rv = findRecyclerView(root);
            if (rv != null) {
                rv.addOnItemTouchListener(
                    new androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener() {
                        @Override
                        public boolean onInterceptTouchEvent(androidx.recyclerview.widget.RecyclerView rv,
                                                              android.view.MotionEvent e) {
                            if (!sEnabled) return false;
                            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                                View child = rv.findChildViewUnder(e.getX(), e.getY());
                                if (child != null) {
                                    tryCaptureVoiceMsgFromView(child);
                                }
                            }
                            return false;
                        }
                    });
                LogWriter.log(TAG, "RecyclerView capture installed");
                return;
            }
            LogWriter.log(TAG, "no list/rv found in chat view");
        } catch (Throwable t) {
            LogWriter.log(TAG, "setup capture err: " + t.getMessage());
        }
    }

    private static android.widget.ListView findListView(View root) {
        if (root instanceof android.widget.ListView) return (android.widget.ListView) root;
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                android.widget.ListView lv = findListView(vg.getChildAt(i));
                if (lv != null) return lv;
            }
        }
        return null;
    }

    private static androidx.recyclerview.widget.RecyclerView findRecyclerView(View root) {
        if (root instanceof androidx.recyclerview.widget.RecyclerView)
            return (androidx.recyclerview.widget.RecyclerView) root;
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                androidx.recyclerview.widget.RecyclerView rv = findRecyclerView(vg.getChildAt(i));
                if (rv != null) return rv;
            }
        }
        return null;
    }

    private static void tryCaptureVoiceMsgFromView(View v) {
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
            String talker = extractTalker(tag);
            if (talker == null || talker.isEmpty()) talker = "";

            if (msgId > 0) {
                sPendingMsg = tag;
                sPendingTalker = talker;
                LogWriter.log(TAG, "captured msgId=" + msgId + " talker=" + talker);
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

            if (msg == null) {
                LogWriter.log(TAG, "executeForward: no msg captured");
                showToast("请先选中一条语音消息");
                return;
            }

            long msgId = extractMsgId(msg);
            if (msgId <= 0) {
                LogWriter.log(TAG, "executeForward: invalid msgId");
                showToast("无法获取消息信息");
                return;
            }

            String extTalker = extractTalker(msg);
            if (talker == null || talker.isEmpty()) talker = extTalker;
            if (talker == null || talker.isEmpty()) talker = "";

            LogWriter.log(TAG, "executeForward: msgId=" + msgId + " talker=" + talker);

            Context ctx = sChatAct;
            if (ctx == null) ctx = ContextManager.getAppContext();
            if (ctx == null) { LogWriter.log(TAG, "no context"); return; }

            ClassLoader cl = ctx.getClassLoader();
            Class<?> forwardUI = null;
            for (String name : new String[]{
                "com.tencent.mm.ui.transmit.SelectConversationUI",
                "com.tencent.mm.ui.transmit.MsgRetransmitUI",
            }) {
                try {
                    forwardUI = cl.loadClass(name);
                    break;
                } catch (Throwable ignored) {}
            }

            if (forwardUI == null) {
                LogWriter.log(TAG, "forward UI class not found");
                showToast("微信版本不兼容");
                return;
            }

            Intent intent = new Intent(ctx, forwardUI);
            intent.putExtra("Retr_Msg_content", talker);
            intent.putExtra("Retr_Msg_Type", 1);
            intent.putExtra("Retr_Msg_Id", msgId);
            intent.putExtra("Retr_Msg_Img_Type", 0);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);

            LogWriter.log(TAG, "forward started: " + forwardUI.getSimpleName());
            showToast("已打开转发界面");

        } catch (Throwable t) {
            LogWriter.log(TAG, "executeForward err: " + t.getClass().getSimpleName()
                + " " + t.getMessage());
            showToast("转发失败: " + t.getMessage());
        }
    }

    // ===== 工具方法 =====
    private static Method findAddMenuItemMethod(Class<?> menuClass) {
        for (Method m : menuClass.getDeclaredMethods()) {
            Class<?>[] paramTypes = m.getParameterTypes();
            if (paramTypes.length >= 2
                && (paramTypes[0] == int.class || paramTypes[0] == Integer.class)
                && (paramTypes[1] == String.class || paramTypes[1] == CharSequence.class)) {
                m.setAccessible(true);
                return m;
            }
        }
        for (Method m : menuClass.getMethods()) {
            Class<?>[] paramTypes = m.getParameterTypes();
            if (paramTypes.length >= 2
                && (paramTypes[0] == int.class || paramTypes[0] == Integer.class)
                && (paramTypes[1] == String.class || paramTypes[1] == CharSequence.class)) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static long extractMsgId(Object msg) {
        try { return (long) XposedHelpers.callMethod(msg, "getMsgId"); } catch (Throwable ignored) {}
        try { return XposedHelpers.getLongField(msg, "field_msgId"); } catch (Throwable ignored) {}
        try { return XposedHelpers.getLongField(msg, "msgId"); } catch (Throwable ignored) {}
        try {
            for (Field f : msg.getClass().getDeclaredFields()) {
                if (f.getType() == long.class && f.getName().toLowerCase().contains("msgid")) {
                    f.setAccessible(true);
                    return f.getLong(msg);
                }
            }
        } catch (Throwable ignored) {}
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
            Context ctx = sChatAct;
            if (ctx == null) ctx = ContextManager.getAppContext();
            if (ctx == null) return;
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }
}
