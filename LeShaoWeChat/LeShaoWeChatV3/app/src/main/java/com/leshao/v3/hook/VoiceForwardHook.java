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

    private static volatile boolean sForwarding = false;

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

    // ===== WeKit 方案: 精准扫描 viewitems + component + Menu.add 拦截 =====
    private static void hookChatFragmentForAdapter(final ClassLoader cl) {
        // 策略 A: 扫描 WeKit 文档明确的 2 个包 (含静态方法 + 内部类)
        String[] pkgs = {
            "com.tencent.mm.ui.chatting.viewitems",
            "com.tencent.mm.ui.chatting.component",
        };
        hookAllClassesInPackages(cl, pkgs);

        // 策略 B: hook android.view.Menu.add (拦截所有菜单项添加)
        try {
            Class<?> menuIf = android.view.Menu.class;
            for (Method m : menuIf.getDeclaredMethods()) {
                if (m.getName().equals("add")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            int n = sCallCount.incrementAndGet();
                            if (n > 20) return;
                            LogWriter.log(TAG, "Menu.add id=" + param.args[1] + " title=" + param.args[3] + " @" + param.thisObject.getClass().getSimpleName());
                        }
                    });
                }
            }
            LogWriter.log(TAG, "Menu.add hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "Menu.add fail: " + t.getMessage());
        }

        // 策略 C: Activity.onContextItemSelected (click handler)
        try {
            XposedBridge.hookAllMethods(Activity.class, "onContextItemSelected", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof MenuItem) {
                        MenuItem item = (MenuItem) param.args[0];
                        LogWriter.log(TAG, "Click: id=" + item.getItemId() + " menu=" + param.thisObject.getClass().getSimpleName());
                        if (item.getItemId() == MENU_ID) {
                            executeForward();
                            param.setResult(true);
                        }
                    }
                }
            });
        } catch (Throwable ignored) {}

        LogWriter.log(TAG, "viewitems+component+Menu.add hooks installed");
    }

    private static void hookAllClassesInPackages(ClassLoader cl, String[] pkgs) {
        try {
            String apkPath = ContextManager.getApkPath();
            if (apkPath == null) { LogWriter.log(TAG, "APK path null"); return; }

            dalvik.system.DexFile dex = new dalvik.system.DexFile(apkPath);
            java.util.Enumeration<String> entries = dex.entries();
            int clsCount = 0, hookedCount = 0;

            while (entries.hasMoreElements()) {
                String className = entries.nextElement();
                boolean match = false;
                for (String pkg : pkgs) {
                    if (className.startsWith(pkg + ".") || className.equals(pkg + ".a") || className.equals(pkg)) {
                        match = true; break;
                    }
                }
                if (!match) continue;

                try {
                    Class<?> cls = cl.loadClass(className);
                    // 含内部类: 枚举 declared classes
                    int n = hookAllMethodsOnClass(cls, cls.getSimpleName());
                    clsCount++;
                    hookedCount += n;
                    for (Class<?> inner : cls.getDeclaredClasses()) {
                        int ni = hookAllMethodsOnClass(inner, cls.getSimpleName() + "$" + inner.getSimpleName());
                        hookedCount += ni;
                    }
                } catch (Throwable ignored) {}
            }
            dex.close();
            LogWriter.log(TAG, "scan " + java.util.Arrays.toString(pkgs) + ": " + clsCount + " classes, " + hookedCount + " methods");
        } catch (Throwable t) {
            LogWriter.log(TAG, "scan error: " + t.getMessage());
        }
    }

    private static int hookAllMethodsOnClass(Class<?> cls, String label) {
        int count = 0;
        for (Method m : cls.getDeclaredMethods()) {
            // 性能优化: 只 hook 参数签名为 (View,XXX) 或 (XXX,View) 的方法
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length < 2) continue;
            boolean hasView = false;
            for (Class<?> pt : pts) {
                if (View.class.isAssignableFrom(pt)) { hasView = true; break; }
            }
            if (!hasView) continue;

            final String mName = m.getName();
            count++;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    int n = sCallCount.incrementAndGet();
                    if (n <= 30) {
                        StringBuilder sb = new StringBuilder();
                        sb.append(label).append(".").append(mName).append("(");
                        boolean hasView = false;
                        for (int i = 0; i < Math.min(param.args.length, 4); i++) {
                            if (i > 0) sb.append(",");
                            Object a = param.args[i];
                            if (a instanceof View) {
                                hasView = true;
                                Object tag = ((View) a).getTag();
                                sb.append("V:").append(a.getClass().getSimpleName());
                                sb.append(tag != null ? ":T=" + tag.getClass().getSimpleName() : "");
                            } else if (a instanceof MenuItem) {
                                sb.append("MI:").append(((MenuItem) a).getItemId());
                            } else {
                                sb.append(a == null ? "null" : a.getClass().getSimpleName());
                            }
                        }
                        sb.append(")");
                        if (hasView) sb.insert(0, "★");
                        LogWriter.log(TAG, sb.toString());
                    }

                    // 策略1: 检测 MenuItem click (case 22)
                    for (Object arg : param.args) {
                        if (arg instanceof MenuItem && ((MenuItem) arg).getItemId() == MENU_ID) {
                            LogWriter.log(TAG, ">>> our menu item clicked! <<<");
                            executeForward();
                            try { param.setResult(true); } catch (Throwable ignored) {}
                            return;
                        }
                    }

                    // 策略2: 检测菜单创建 (case 21) — 有 View arg 就可能
                    if (sMenuInjected) return;
                    View itemView = null;
                    for (Object arg : param.args) {
                        if (arg instanceof View) { itemView = (View) arg; break; }
                    }
                    if (itemView == null) return;

                    // 有 View 了，找菜单对象
                    Object menuObj = null;
                    for (Object arg : param.args) {
                        if (arg == itemView) continue;
                        if (arg == null) continue;
                        // 尝试调用 addMenuItem
                        try {
                            arg.getClass().getMethod("addMenuItem", int.class, CharSequence.class);
                            menuObj = arg;
                            break;
                        } catch (Throwable ignored) {}
                        // 尝试调用 add
                        try {
                            arg.getClass().getMethod("add", int.class, int.class, int.class, CharSequence.class);
                            menuObj = arg;
                            break;
                        } catch (Throwable ignored) {}
                    }

                    if (menuObj == null) return;

                    LogWriter.log(TAG, "=== FOUND [" + label + "." + mName + "] ===");
                    LogWriter.log(TAG, "  menu: " + menuObj.getClass().getName());
                    LogWriter.log(TAG, "  view: " + itemView.getClass().getName());
                    Object tag = itemView.getTag();
                    LogWriter.log(TAG, "  tag: " + (tag == null ? "null" : tag.getClass().getName()));

                    sMenuInjected = true;
                    sPendingView = itemView;
                    injectForwardMenuItem(menuObj, itemView);
                }
            });
        }
        return count;
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
                addItem.invoke(menuObj, MENU_ID, "语音转发");
                LogWriter.log(TAG, "added via addMenuItem(int,CharSequence)");
                ok = true;
            } catch (Throwable ignored) {}

            // 方式2: addMenuItem(int, CharSequence, Drawable)
            if (!ok) {
                try {
                    Method addItem = menuObj.getClass().getMethod("addMenuItem", int.class, CharSequence.class, Drawable.class);
                    addItem.invoke(menuObj, MENU_ID, "语音转发", null);
                    LogWriter.log(TAG, "added via addMenuItem(int,CharSequence,Drawable)");
                    ok = true;
                } catch (Throwable ignored) {}
            }

            // 方式3: add(int, int, int, CharSequence) — ContextMenu 标准方法
            if (!ok) {
                try {
                    Method add = menuObj.getClass().getMethod("add", int.class, int.class, int.class, CharSequence.class);
                    add.invoke(menuObj, 0, MENU_ID, 0, "语音转发");
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
        if (!sEnabled || sForwarding) return;
        sForwarding = true;
        try {
            View view = sPendingView;
            if (view == null) { showToast("请先长按一条语音消息"); return; }

            Object tag = view.getTag();
            if (tag == null) { showToast("无法获取消息信息"); return; }

            LogWriter.log(TAG, "forward: tag=" + tag.getClass().getName());

            // 提取消息 ID
            long msgId = extractMsgId(tag);
            LogWriter.log(TAG, "forward: msgId=" + msgId + " (from " + tag.getClass().getSimpleName() + ")");

            if (msgId <= 0) {
                StringBuilder sb = new StringBuilder("tag fields: ");
                for (Field f : tag.getClass().getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(tag);
                        sb.append(f.getName()).append("=");
                        if (v instanceof Number || v instanceof String || v instanceof Boolean) {
                            sb.append(v);
                        } else if (v != null) {
                            sb.append(v.getClass().getSimpleName());
                        } else {
                            sb.append("null");
                        }
                        sb.append(" ");
                    } catch (Throwable ignored) {}
                }
                LogWriter.log(TAG, sb.toString());
                showToast("消息ID提取失败, 查看日志");
                return;
            }

            String talker = extractTalker(tag);
            LogWriter.log(TAG, "forward: talker=" + talker);

            Context ctx = sChatAct != null ? sChatAct : ContextManager.getAppContext();
            if (ctx == null) { showToast("context unavailable"); return; }

            Class<?> fwdUI = null;
            for (String n : new String[]{
                "com.tencent.mm.ui.transmit.SelectConversationUI",
                "com.tencent.mm.ui.transmit.MsgRetransmitUI",
            }) { try { fwdUI = ctx.getClassLoader().loadClass(n); break; } catch (Throwable ignored) {} }

            if (fwdUI == null) { showToast("微信版本不兼容"); return; }

            Intent intent = new Intent(ctx, fwdUI);
            intent.putExtra("Retr_Msg_content", talker != null ? talker : "");
            intent.putExtra("Retr_Msg_Type", 34); // voice
            intent.putExtra("Retr_Msg_Id", msgId);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            LogWriter.log(TAG, "forward: SelectConversationUI launched");
            showToast("请选择接收人");
        } catch (Throwable t) {
            LogWriter.log(TAG, "forward error: " + t.getMessage());
            showToast("转发失败: " + t.getMessage());
        } finally {
            sForwarding = false;
        }
    }

    // ===== 工具 =====
    private static long extractMsgId(Object msg) {
        if (msg == null) return 0;
        // WeChat 8.0.76 common field names
        try { return XposedHelpers.getLongField(msg, "field_msgId"); } catch (Throwable ignored) {}
        try { return XposedHelpers.getLongField(msg, "msgId"); } catch (Throwable ignored) {}
        try { return XposedHelpers.getLongField(msg, "field_msgSvrId"); } catch (Throwable ignored) {}
        try { return XposedHelpers.getLongField(msg, "msgSvrId"); } catch (Throwable ignored) {}
        try { return XposedHelpers.getLongField(msg, "D"); } catch (Throwable ignored) {}
        try { return XposedHelpers.getLongField(msg, "d"); } catch (Throwable ignored) {}
        try { return (long) XposedHelpers.callMethod(msg, "getMsgId"); } catch (Throwable ignored) {}
        try { return (long) XposedHelpers.callMethod(msg, "getMsgSvrId"); } catch (Throwable ignored) {}
        for (Field f : msg.getClass().getDeclaredFields()) {
            if (f.getType() == long.class) {
                String fn = f.getName().toLowerCase();
                if (fn.contains("msgid") || fn.contains("svrid") || fn.contains("msg_id")) {
                    try { f.setAccessible(true); return f.getLong(msg); } catch (Throwable ignored) {}
                }
            }
        }
        return 0;
    }

    private static String extractTalker(Object msg) {
        if (msg == null) return "";
        try { return (String) XposedHelpers.getObjectField(msg, "field_talker"); } catch (Throwable ignored) {}
        try { return (String) XposedHelpers.getObjectField(msg, "talker"); } catch (Throwable ignored) {}
        try { return (String) XposedHelpers.callMethod(msg, "N0"); } catch (Throwable ignored) {}
        try { return (String) XposedHelpers.callMethod(msg, "getTalker"); } catch (Throwable ignored) {}
        try { return (String) XposedHelpers.callMethod(msg, "a"); } catch (Throwable ignored) {}
        for (Field f : msg.getClass().getDeclaredFields()) {
            if (f.getType() == String.class) {
                String fn = f.getName().toLowerCase();
                if (fn.contains("talker") || fn.contains("username") || fn.contains("fromuser")) {
                    try { f.setAccessible(true); return (String) f.get(msg); } catch (Throwable ignored) {}
                }
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
