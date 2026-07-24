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
    private static volatile boolean sMenuInjected = false;
    private static volatile boolean sEnabled = true;
    private static volatile Activity sChatAct;
    private static volatile View sPendingView;
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
                    sMenuInjected = false;
                    sPendingMsg = null;
                    sPendingView = null;
                }
            });
            XposedBridge.hookAllMethods(cui, "onPause", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (sChatAct == param.thisObject) sChatAct = null;
                }
            });
        } catch (Throwable ignored) {}
    }

    // ===== WeKit 方案: DexFile 全量扫描 chatting.* 所有方法 =====
    private static void hookChatFragmentForAdapter(final ClassLoader cl) {
        // 策略 A: 枚举 com.tencent.mm.ui.chatting.* 全部类, hook 所有方法
        hookAllChattingClasses(cl);

        // 策略 B: View.createContextMenu (兜底)
        try {
            XposedBridge.hookAllMethods(View.class, "createContextMenu", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    onMenuCreateMethod("View.createContextMenu", param);
                }
            });
        } catch (Throwable ignored) {}

        // 策略 C: Activity.onContextItemSelected (菜单点击兜底)
        try {
            XposedBridge.hookAllMethods(Activity.class, "onContextItemSelected", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof MenuItem) {
                        MenuItem item = (MenuItem) param.args[0];
                        if (item.getItemId() == MENU_ID) {
                            executeForward();
                            param.setResult(true);
                        }
                    }
                }
            });
        } catch (Throwable ignored) {}

        LogWriter.log(TAG, "chatting scan + View/Activity hooks installed");
    }

    private static void hookAllChattingClasses(ClassLoader cl) {
        try {
            String apkPath = ContextManager.getApkPath();
            if (apkPath == null) { LogWriter.log(TAG, "APK path null"); return; }

            dalvik.system.DexFile dex = new dalvik.system.DexFile(apkPath);
            java.util.Enumeration<String> entries = dex.entries();
            int clsCount = 0, hookedCount = 0;

            while (entries.hasMoreElements()) {
                String className = entries.nextElement();
                if (!className.startsWith("com.tencent.mm.ui.chatting.")) continue;

                try {
                    Class<?> cls = cl.loadClass(className);
                    int n = hookAllNonStaticMethods(cls);
                    if (n > 0) {
                        clsCount++;
                        hookedCount += n;
                    }
                } catch (Throwable ignored) {}
            }
            dex.close();
            LogWriter.log(TAG, "chatting scan: " + clsCount + " classes, " + hookedCount + " methods hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "DEX scan error: " + t.getMessage());
        }
    }

    private static int hookAllNonStaticMethods(Class<?> cls) {
        int count = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            final String clsName = cls.getSimpleName();
            final String mName = m.getName();
            count++;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    int n = sCallCount.incrementAndGet();
                    if (n <= 20) {
                        StringBuilder sb = new StringBuilder();
                        sb.append(clsName).append(".").append(mName).append("(");
                        for (int i = 0; i < Math.min(param.args.length, 4); i++) {
                            if (i > 0) sb.append(",");
                            Object a = param.args[i];
                            sb.append(a == null ? "null" : a.getClass().getSimpleName());
                            if (a instanceof View) {
                                Object tag = ((View) a).getTag();
                                if (tag != null) sb.append(":T=").append(tag.getClass().getSimpleName());
                            }
                        }
                        sb.append(")");
                        LogWriter.log(TAG, sb.toString());
                    }
                    onMenuCreateMethod(clsName + "." + mName, param);
                }
            });
        }
        return count;
    }

    /**
     * WeKit case 21 逻辑: 检测菜单创建方法并注入"转发[K]"
     * 特征: args 中有 View (消息项) + menu 对象
     */
    private static void onMenuCreateMethod(String source, XC_MethodHook.MethodHookParam param) {
        if (sMenuInjected) return;

        // 提取 View — 微信把消息存在 View.getTag() 中
        View itemView = null;
        Object menuObj = null;

        for (int i = 0; i < param.args.length; i++) {
            Object arg = param.args[i];
            if (arg instanceof View && arg.getClass().getName().startsWith("android")) {
                itemView = (View) arg;
            }
            if (arg != null && i == 0 && param.args.length >= 2) {
                String name = arg.getClass().getName().toLowerCase();
                if (name.contains("menu") || name.contains("context")) {
                    menuObj = arg;
                }
            }
        }

        if (itemView == null || menuObj == null) return;

        LogWriter.log(TAG, "=== FOUND [" + source + "] ===");
        LogWriter.log(TAG, "  menu: " + menuObj.getClass().getName());
        LogWriter.log(TAG, "  view: " + itemView.getClass().getSimpleName());

        // WeKit: View.getTag() → 消息对象
        Object tag = itemView.getTag();
        LogWriter.log(TAG, "  tag: " + (tag == null ? "null" : tag.getClass().getName()));

        sMenuInjected = true;
        sPendingView = itemView;
        injectForwardMenuItem(menuObj, itemView);
    }

    private static void injectForwardMenuItem(Object menuObj, View itemView) {
        try {
            // WeKit 文档: addMenuItem(int, CharSequence, Drawable)
            Object tag = itemView.getTag();
            LogWriter.log(TAG, "View.tag: " + (tag == null ? "null" : tag.getClass().getName()));

            // 尝试多种菜单添加方式
            boolean ok = false;

            // 方式1: addMenuItem(int, CharSequence) 
            try {
                Method addItem = menuObj.getClass().getMethod("addMenuItem", int.class, CharSequence.class);
                addItem.invoke(menuObj, MENU_ID, "转发[K]");
                LogWriter.log(TAG, "added via addMenuItem(int,CharSequence)");
                ok = true;
            } catch (Throwable ignored) {}

            // 方式2: addMenuItem(int, CharSequence, Drawable)
            if (!ok) {
                try {
                    Method addItem = menuObj.getClass().getMethod("addMenuItem", int.class, CharSequence.class, Drawable.class);
                    addItem.invoke(menuObj, MENU_ID, "转发[K]", null);
                    LogWriter.log(TAG, "added via addMenuItem(int,CharSequence,Drawable)");
                    ok = true;
                } catch (Throwable ignored) {}
            }

            // 方式3: add(int, int, int, CharSequence) — ContextMenu 标准方法
            if (!ok) {
                try {
                    Method add = menuObj.getClass().getMethod("add", int.class, int.class, int.class, CharSequence.class);
                    add.invoke(menuObj, 0, MENU_ID, 0, "转发[K]");
                    LogWriter.log(TAG, "added via add(int,int,int,CharSequence)");
                    ok = true;
                } catch (Throwable ignored) {}
            }

            // 方式4: add(int, int, int, int) 
            if (!ok) {
                try {
                    Method add = menuObj.getClass().getMethod("add", int.class, int.class, int.class, int.class);
                    add.invoke(menuObj, 0, MENU_ID, 0, 0);
                    LogWriter.log(TAG, "added via add(int,int,int,int)");
                    ok = true;
                } catch (Throwable ignored) {}
            }

            if (!ok) {
                LogWriter.log(TAG, "ALL addMenuItem methods failed on " + menuObj.getClass().getName());
                StringBuilder sb = new StringBuilder("methods: ");
                for (Method m : menuObj.getClass().getMethods()) {
                    if (m.getName().toLowerCase().contains("add")) {
                        sb.append(m.getName()).append("(");
                        Class<?>[] pts = m.getParameterTypes();
                        for (int i = 0; i < pts.length; i++) {
                            if (i > 0) sb.append(",");
                            sb.append(pts[i].getSimpleName());
                        }
                        sb.append(") ");
                    }
                }
                LogWriter.log(TAG, sb.toString());
            }

            // 待后续: 注册 menu item click handler
            // 需要 hook ContextMenu/Activity.onContextItemSelected 等

        } catch (Throwable t) {
            LogWriter.log(TAG, "injectMenu error: " + t.getMessage());
        }
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
