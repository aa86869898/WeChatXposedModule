package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.MotionEvent;
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
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 语音消息转发 — 完整实现
 * ========================
 *
 * 按 WeKit 注入流程文档实现:
 *   P1: 标准 android.view.ContextMenu (onCreateContextMenu / onContextItemSelected)
 *   P2: 微信 MM 菜单 (onCreateMMMenu) — 多类穷举
 *   P3: PopupWindow 拦截 (showAsDropDown / showAtLocation) — 兜底方案
 *   P4: 消息捕获 (RecyclerView/ListView 长按 + View.setTag 拦截)
 */
public class VoiceForwardHook {

    private static final String TAG = "VoiceFwd";
    private static final int MENU_ID = 777001;
    private static volatile boolean sHooked = false;
    private static volatile boolean sEnabled = true;
    private static volatile Activity sChatAct;
    private static volatile Object sPendingMsg;
    private static volatile String sPendingTalker;
    private static volatile View sLastLongClickView;

    public static void setEnabled(boolean v) {
        sEnabled = v;
        LogWriter.log(TAG, "setEnabled=" + v);
    }

    public static void hook() {
        if (sHooked) return;
        ClassLoader cl = ContextManager.getClassLoader();
        if (cl == null) { LogWriter.log(TAG, "cl not ready"); return; }

        hookChatUI(cl);
        hookMMMenuMulti(cl);
        hookPopupWindow(cl);
        hookMsgCapture(cl);

        sHooked = true;
        LogWriter.log(TAG, "all P1-P4 hooks installed");
    }

    // ===== P1: 标准 ContextMenu =====
    private static void hookChatUI(ClassLoader cl) {
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
                        tryCapture(v);
                        if (sPendingMsg != null && menu != null) {
                            menu.add(0, MENU_ID, 0, "语音转发");
                            LogWriter.log(TAG, "P1: +ctxMenu");
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "P1 err: " + t.getMessage());
                    }
                }
            });
            LogWriter.log(TAG, "P1: onCreateContextMenu ok");

            XposedBridge.hookAllMethods(chattingUI, "onContextItemSelected", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    try {
                        MenuItem item = (MenuItem) param.args[0];
                        if (item.getItemId() == MENU_ID || "语音转发".equals(item.getTitle())) {
                            executeForward();
                            param.setResult(true);
                            LogWriter.log(TAG, "P1: ctxItem click");
                        }
                    } catch (Throwable t) {}
                }
            });
            LogWriter.log(TAG, "P1: onContextItemSelected ok");

        } catch (Throwable t) {
            LogWriter.log(TAG, "P1 fail: " + t.getMessage());
        }
    }

    // ===== P2: 微信 MM 菜单 — 穷举所有候选类 =====
    private static void hookMMMenuMulti(ClassLoader cl) {
        String[] candidates = {
            // 聊天界面
            "com.tencent.mm.ui.chatting.ChattingUI",
            "com.tencent.mm.ui.chatting.ChattingUIFragment",
            "com.tencent.mm.ui.chatting.BaseChattingUIFragment",
            // MM 基类
            "com.tencent.mm.ui.MMFragment",
            "com.tencent.mm.ui.MMFragmentActivity",
            "com.tencent.mm.ui.MMActivity",
            // LauncherUI
            "com.tencent.mm.ui.LauncherUI",
            // 通用菜单工具类
            "com.tencent.mm.ui.tools.MMTextInput",
            "com.tencent.mm.ui.tools.MMPopupMenu",
            "com.tencent.mm.ui.tools.ActionBarSearchView",
            "com.tencent.mm.ui.base.MMAlert",
            "com.tencent.mm.ui.base.MMPreference",
        };

        for (final String clsName : candidates) {
            try {
                Class<?> cls = cl.loadClass(clsName);
                XposedBridge.hookAllMethods(cls, "onCreateMMMenu", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!sEnabled) return;
                        Object mmMenu = param.args[0];
                        if (mmMenu == null) return;
                        try {
                            boolean added = tryAddToMMMenu(mmMenu, clsName);
                            if (added) LogWriter.log(TAG, "P2: +MMMenu on " + clsName);
                            else LogWriter.log(TAG, "P2: MMMenu found on " + clsName + " but add failed");
                        } catch (Throwable inner) {
                            LogWriter.log(TAG, "P2: add err on " + clsName + ": " + inner.getClass().getSimpleName());
                        }
                    }
                });
                LogWriter.log(TAG, "P2: hooked onCreateMMMenu on " + clsName);
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                LogWriter.log(TAG, "P2: err hooking " + clsName + ": " + t.getClass().getSimpleName());
            }
        }
    }

    private static boolean tryAddToMMMenu(Object mmMenu, String clsName) {
        Class<?> menuClass = mmMenu.getClass();
        for (Method m : menuClass.getDeclaredMethods()) {
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length >= 2
                && (pts[0] == int.class || pts[0] == Integer.class)
                && (pts[1] == String.class || pts[1] == CharSequence.class
                    || pts[1] == Object.class)) {
                try { m.setAccessible(true); m.invoke(mmMenu, MENU_ID, "语音转发"); return true; }
                catch (Throwable ignored) {}
            }
            if (pts.length == 4 && (pts[0] == int.class || pts[0] == Integer.class)
                && pts[1] == int.class && pts[2] == int.class
                && (pts[3] == String.class || pts[3] == CharSequence.class)) {
                try { m.setAccessible(true); m.invoke(mmMenu, 0, MENU_ID, 0, "语音转发"); return true; }
                catch (Throwable ignored) {}
            }
        }
        for (Method m : menuClass.getMethods()) {
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length >= 2 && pts[1] == CharSequence.class) {
                try { m.setAccessible(true); m.invoke(mmMenu, MENU_ID, "语音转发"); return true; }
                catch (Throwable ignored) {}
            }
        }
        return false;
    }

    // ===== P3: PopupWindow 拦截 — 兜底 =====
    private static void hookPopupWindow(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(PopupWindow.class, "showAsDropDown", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    PopupWindow pw = (PopupWindow) param.thisObject;
                    View anchor = (View) param.args[0];
                    tryCapture(anchor);
                    if (sPendingMsg != null) {
                        injectIntoPopupWindow(pw, anchor);
                    }
                }
            });
            LogWriter.log(TAG, "P3: showAsDropDown ok");

            XposedBridge.hookAllMethods(PopupWindow.class, "showAtLocation", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    PopupWindow pw = (PopupWindow) param.thisObject;
                    if (sChatAct == null) return;
                    View decor = sChatAct.getWindow().getDecorView();
                    tryCapture(decor);
                    if (sPendingMsg != null) {
                        injectIntoPopupWindow(pw, null);
                    }
                }
            });
            LogWriter.log(TAG, "P3: showAtLocation ok");

        } catch (Throwable t) {
            LogWriter.log(TAG, "P3 fail: " + t.getMessage());
        }
    }

    private static void injectIntoPopupWindow(PopupWindow pw, View anchor) {
        try {
            View content = pw.getContentView();
            if (content == null) return;

            // 遍历查找可以添加菜单项的 LinearLayout
            LinearLayout target = findAddableLayout(content);
            if (target == null) {
                // 如果是 ListView (旧版微信菜单), 无法简单注入
                if (content instanceof android.widget.ListView) return;
                return;
            }

            // 检查是否已经添加过
            for (int i = 0; i < target.getChildCount(); i++) {
                View child = target.getChildAt(i);
                if (child instanceof TextView && "语音转发".equals(((TextView) child).getText())) {
                    return;
                }
            }

            Context ctx = sChatAct != null ? sChatAct : target.getContext();
            TextView tv = new TextView(ctx);
            tv.setText("语音转发");
            tv.setTextSize(16);
            tv.setPadding(48, 32, 48, 32);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setClickable(true);
            tv.setFocusable(true);
            tv.setTextColor(0xFF333333);
            tv.setOnClickListener(v -> {
                pw.dismiss();
                executeForward();
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            target.addView(tv, lp);
            LogWriter.log(TAG, "P3: injected into PopupWindow");

        } catch (Throwable t) {
            LogWriter.log(TAG, "P3 inject err: " + t.getClass().getSimpleName());
        }
    }

    private static LinearLayout findAddableLayout(View v) {
        if (v instanceof LinearLayout) {
            LinearLayout ll = (LinearLayout) v;
            // 检查是否是菜单容器 (有多个子项或方向为垂直)
            if (ll.getOrientation() == LinearLayout.VERTICAL && ll.getChildCount() > 0) {
                return ll;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                LinearLayout found = findAddableLayout(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    // ===== P4: 消息捕获 =====
    private static void hookMsgCapture(ClassLoader cl) {
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
                        installCaptureOnAllViews(root);
                    }
                });
            LogWriter.log(TAG, "P4: BaseChattingUIFragment.onCreateView ok");
        } catch (Throwable t) {
            LogWriter.log(TAG, "P4 fail: " + t.getMessage());
        }
    }

    private static void installCaptureOnAllViews(View root) {
        List<View> allViews = new ArrayList<>();
        collectAll(root, allViews);
        LogWriter.log(TAG, "P4: scanning " + allViews.size() + " views");

        for (View v : allViews) {
            // 在微信聊天列表中，消息 item 通常有 tag
            if (v.getTag() != null) {
                final View fv = v;
                v.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View view) {
                        sLastLongClickView = fv;
                        tryCapture(fv);
                        return false; // 不拦截, 让微信继续处理
                    }
                });
            }

            // 也尝试 ListView item long click
            if (v instanceof android.widget.ListView) {
                ((android.widget.ListView) v).setOnItemLongClickListener(
                    (parent, itemView, position, id) -> {
                        sLastLongClickView = itemView;
                        tryCapture(itemView);
                        return false;
                    });
            }
        }
    }

    private static void collectAll(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectAll(vg.getChildAt(i), out);
            }
        }
    }

    // ===== 消息捕获辅助 =====
    private static void tryCapture(View v) {
        sPendingMsg = null;
        sPendingTalker = null;
        if (v == null) return;
        try {
            Object tag = v.getTag();
            if (tag == null && sLastLongClickView != null) {
                tag = sLastLongClickView.getTag();
            }
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
                LogWriter.log(TAG, "captured msgId=" + msgId + " talker=" + sPendingTalker);
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
        return "";
    }

    private static void showToast(String text) {
        try {
            Context ctx = sChatAct != null ? sChatAct : ContextManager.getAppContext();
            if (ctx != null) Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }
}
