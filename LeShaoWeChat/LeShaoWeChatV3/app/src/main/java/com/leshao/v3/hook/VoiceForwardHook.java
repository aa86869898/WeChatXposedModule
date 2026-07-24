package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
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
import java.util.Set;
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
    private static volatile long sMenuInjectedTime = 0;
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
    private static volatile String sFullHookedClass = null;

    public static void hook() {
        if (sHooked) return;
        ClassLoader cl = ContextManager.getClassLoader();
        if (cl == null) { LogWriter.log(TAG, "cl not ready"); return; }

        hookChatActivity(cl);
        hookChatFragmentForAdapter(cl);

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

    // 对指定 simpleName 的类做全方法 hook（找 click handler）
    private static void hookAllMethodsNoFilterOnLabel(String simpleName) {
        ClassLoader cl = ContextManager.getClassLoader();
        if (cl == null) return;
        try {
            String apkPath = ContextManager.getApkPath();
            if (apkPath == null) return;
            dalvik.system.DexFile dex = new dalvik.system.DexFile(apkPath);
            java.util.Enumeration<String> entries = dex.entries();
            while (entries.hasMoreElements()) {
                String cn = entries.nextElement();
                if (!cn.endsWith("." + simpleName)) continue;
                // 只匹配 viewitems 或 component 包
                if (!cn.startsWith("com.tencent.mm.ui.chatting.viewitems.")
                    && !cn.startsWith("com.tencent.mm.ui.chatting.component.")) continue;
                try {
                    Class<?> cls = cl.loadClass(cn);
                    int n = hookAllMethodsOnClass(cls, simpleName, true);
                    LogWriter.log(TAG, "FULL-hook " + simpleName + ": " + n + " methods");
                    for (Class<?> inner : cls.getDeclaredClasses()) {
                        n += hookAllMethodsOnClass(inner, simpleName + "$" + inner.getSimpleName(), true);
                    }
                } catch (Throwable ignored) {}
                break;
            }
            dex.close();
        } catch (Throwable t) {
            LogWriter.log(TAG, "FULL-hook error: " + t.getMessage());
        }
    }

    private static int hookAllMethodsOnClass(Class<?> cls, String label) {
        return hookAllMethodsOnClass(cls, label, false);
    }

    private static int hookAllMethodsOnClass(Class<?> cls, String label, boolean noFilter) {
        int count = 0;
        for (Method m : cls.getDeclaredMethods()) {
            // 性能优化: 只 hook 参数签名为 (View,XXX) 或 (XXX,View) 或 (MenuItem,XXX) 的方法
            if (!noFilter) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 1) continue;
                boolean hasTarget = false;
                for (Class<?> pt : pts) {
                    if (View.class.isAssignableFrom(pt) || MenuItem.class.isAssignableFrom(pt)) { hasTarget = true; break; }
                }
                if (!hasTarget) continue;
            }

            final String mName = m.getName();
            count++;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    int n = sCallCount.incrementAndGet();
                    if (n <= 30) {
                        boolean hadTag = false;
                        StringBuilder sb = new StringBuilder();
                        sb.append(label).append(".").append(mName).append("(");
                        for (int i = 0; i < Math.min(param.args.length, 4); i++) {
                            if (i > 0) sb.append(",");
                            Object a = param.args[i];
                            if (a instanceof View) {
                                Object tag = ((View) a).getTag();
                                if (tag != null) hadTag = true;
                                sb.append("V:").append(a.getClass().getSimpleName());
                                sb.append(tag != null ? ":T=" + tag.getClass().getSimpleName() : "");
                            } else if (a instanceof MenuItem) {
                                sb.append("MI:").append(((MenuItem) a).getItemId());
                            } else {
                                sb.append(a == null ? "null" : a.getClass().getSimpleName());
                            }
                        }
                        sb.append(")");
                        if (hadTag) sb.insert(0, "★");
                        LogWriter.log(TAG, sb.toString());
                    }

                    // ===== MenuItem click 检测 (冷却 500ms 后生效, 防自动触发) =====
                    long elapsed = System.currentTimeMillis() - sMenuInjectedTime;
                    for (Object arg : param.args) {
                        if (arg instanceof MenuItem && ((MenuItem) arg).getItemId() == MENU_ID) {
                            LogWriter.log(TAG, ">>> MENU_ID found in " + label + "." + mName + " elapsed=" + elapsed + "ms <<<");
                            if (sMenuInjected && elapsed > 500) {
                                LogWriter.log(TAG, ">>> COOLDOWN PASSED — executeForward! <<<");
                                sMenuInjected = false;
                                executeForward();
                                try { param.setResult(true); } catch (Throwable ignored) {}
                                return;
                            }
                        }
                    }

                    // 策略1: 检测菜单创建 (case 21) — 有 View arg 就可能
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

                    // 存储消息数据: 优先 View.tag (vo), 其次 args[2] (d)
                    Object tagData = itemView.getTag();
                    Object msgData = null;
                    for (int i = 0; i < param.args.length; i++) {
                        Object a = param.args[i];
                        if (a != menuObj && a != itemView && a != null && !(a instanceof View)) {
                            msgData = a;
                            break;
                        }
                    }
                    // vo tag 有更丰富层次结构，优先存
                    sPendingMsg = (tagData != null) ? tagData : msgData;
                    sPendingView = itemView;

                    if (sPendingMsg != null) {
                        long mid = extractMsgId(sPendingMsg);
                        String tlk = extractTalker(sPendingMsg);
                        LogWriter.log(TAG, "=== FOUND [" + label + "." + mName + "] msgId=" + mid + " talker=" + tlk + " msg=" + sPendingMsg.getClass().getSimpleName() + " ===");
                        LogWriter.log(TAG, "  sPendingMsg: " + dumpObjFields(sPendingMsg));
                        // 尝试调用 getter 方法
                        for (String mn : new String[]{"getMsgInfo","getMsg","a","b","c","d","e","f","getTag","getData"}) {
                            try {
                                Object r = XposedHelpers.callMethod(sPendingMsg, mn);
                                if (r != null && r.getClass().getName().contains("storage")) {
                                    LogWriter.log(TAG, "  ★ " + mn + "() → " + r.getClass().getName());
                                    long rmid = extractMsgId(r);
                                    LogWriter.log(TAG, "  ★ " + mn + " msgId=" + rmid);
                                }
                            } catch (Throwable ignored) {}
                        }
                    } else {
                        LogWriter.log(TAG, "=== FOUND [" + label + "." + mName + "] ===");
                    }

                    // dump 所有参数
                    StringBuilder argsb = new StringBuilder("  args: ");
                    for (int i = 0; i < param.args.length; i++) {
                        Object a = param.args[i];
                        argsb.append("[").append(i).append("]=");
                        argsb.append(a == null ? "null" : a.getClass().getSimpleName());
                        if (a instanceof View) {
                            Object vt = ((View) a).getTag();
                            if (vt != null) argsb.append(":T=").append(vt.getClass().getSimpleName());
                        }
                        argsb.append(" ");
                    }
                    LogWriter.log(TAG, argsb.toString());

                    // dump parent chain tags with field values
                    View p = (View) itemView.getParent();
                    for (int pi = 0; p != null && pi < 5; pi++) {
                        Object pt = p.getTag();
                        if (pt != null) {
                            StringBuilder sb = new StringBuilder("  parent[").append(pi).append("]=")
                                .append(p.getClass().getSimpleName()).append(" tag=").append(pt.getClass().getName())
                                .append(" {");
                            for (Field f : pt.getClass().getDeclaredFields()) {
                                try {
                                    f.setAccessible(true);
                                    Object v = f.get(pt);
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
                            sb.append("}");
                            LogWriter.log(TAG, sb.toString());
                        }
                        if (p.getParent() instanceof View) p = (View) p.getParent(); else break;
                    }

                    sPendingView = itemView;
                    sMenuInjected = true;
                    sMenuInjectedTime = System.currentTimeMillis();
                    injectForwardMenuItem(menuObj, itemView);

                    // 对菜单创建类开启全方法 hook（覆盖无 View 参数的 click handler）
                    if (!label.equals(sFullHookedClass)) {
                        sFullHookedClass = label;
                        hookAllMethodsNoFilterOnLabel(label);
                    }
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



    // ===== 转发执行 — 使用自己的联系人选择器 ====
    private static void executeForward() {
        if (!sEnabled || sForwarding) return;
        sForwarding = true;
        try {
            final Object msg = sPendingMsg;
            final View view = sPendingView;
            if (msg == null) { showToast("请先长按一条语音消息"); return; }

            // 尝试从 vo/d/hq/父类提取 msgId
            long msgId = extractMsgId(msg);
            String talker = extractTalker(msg);

            // 方法2: 通过 RecyclerView ViewHolder 获取 adapter position
            if (msgId <= 0 && view != null) {
                try {
                    // 从 view 向上找 RecyclerView
                    View p = view;
                    while (p != null && !(p instanceof RecyclerView)) {
                        if (p.getParent() instanceof View) p = (View) p.getParent();
                        else break;
                    }
                    if (p instanceof RecyclerView) {
                        RecyclerView rv = (RecyclerView) p;
                        RecyclerView.ViewHolder vh = rv.findContainingViewHolder(view);
                        if (vh != null) {
                            int pos = vh.getAdapterPosition();
                            LogWriter.log(TAG, "forward: ViewHolder pos=" + pos);
                            RecyclerView.Adapter<?> adapter = rv.getAdapter();
                            if (adapter != null) {
                                LogWriter.log(TAG, "forward: adapter=" + adapter.getClass().getName());
                                // 尝试通过 adapter 获取消息
                                try {
                                    Object adapterMsg = XposedHelpers.callMethod(adapter, "getItem", pos);
                                    if (adapterMsg != null) {
                                        LogWriter.log(TAG, "forward: adapter getItem=" + adapterMsg.getClass().getName());
                                        msgId = extractMsgId(adapterMsg);
                                        if (talker.isEmpty()) talker = extractTalker(adapterMsg);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                } catch (Throwable t) {
                    LogWriter.log(TAG, "forward: adapter error: " + t.getMessage());
                }
            }

            // 方法3: 通过 msg 的 getter 方法获取消息对象
            if (msgId <= 0) {
                for (String mn : new String[]{"a","b","c","d","e","f","getMsgInfo","getMsg","getData"}) {
                    try {
                        Object inner = XposedHelpers.callMethod(msg, mn);
                        if (inner != null) {
                            long id = extractMsgId(inner);
                            if (id > 0) {
                                msgId = id;
                                if (talker.isEmpty()) talker = extractTalker(inner);
                                LogWriter.log(TAG, "forward: msgId=" + msgId + " via " + mn + "() → " + inner.getClass().getSimpleName());
                                break;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }

            LogWriter.log(TAG, "forward: msgId=" + msgId + " talker=" + talker + " msg=" + msg.getClass().getSimpleName());

            final long finalMsgId = msgId;
            // 从 view 获取 Activity，优先于 sChatAct
            Activity act = sChatAct;
            if (act == null && view != null) {
                Context ctx = view.getContext();
                if (ctx instanceof Activity) act = (Activity) ctx;
                else try { act = (Activity) XposedHelpers.callMethod(ctx, "getActivity"); } catch (Throwable ignored) {}
                if (act == null) act = (Activity) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.tencent.mm.ui.LauncherUI", ContextManager.getClassLoader()), "getInstance");
            }
            if (act == null) { showToast("无法获取Activity"); return; }
            LogWriter.log(TAG, "forward: act=" + act.getClass().getSimpleName());

            // 用我们自己的 ContactPickerDialog
            com.leshao.v3.ui.ContactPickerDialog.show(act, "", 
                com.leshao.v3.ui.ContactPickerDialog.MODE_GROUP,
                new com.leshao.v3.ui.ContactPickerDialog.OnContactsSelected() {
                    @Override
                    public void onSelected(Set<String> wxids, String display) {
                        LogWriter.log(TAG, "forward: selected=" + wxids + " display=" + display);
                        // TODO: 调用微信转发 API 实际执行转发
                        showToast("选择了 " + wxids.size() + " 个目标 (msgId=" + finalMsgId + ")");
                    }
                });
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
        long id = extractMsgIdFromClass(msg, msg.getClass());
        if (id > 0) return id;
        // 遍历父类链
        for (Class<?> sc = msg.getClass().getSuperclass(); sc != null && sc != Object.class; sc = sc.getSuperclass()) {
            id = extractMsgIdFromClass(msg, sc);
            if (id > 0) return id;
        }
        return 0;
    }

    private static long extractMsgIdFromClass(Object msg, Class<?> cls) {
        if (cls == null) return 0;
        try { return XposedHelpers.getLongField(msg, "field_msgId"); } catch (Throwable ignored) {}
        try { return XposedHelpers.getLongField(msg, "msgId"); } catch (Throwable ignored) {}
        try { return XposedHelpers.getLongField(msg, "field_msgSvrId"); } catch (Throwable ignored) {}
        try { return XposedHelpers.getLongField(msg, "msgSvrId"); } catch (Throwable ignored) {}
        try { return XposedHelpers.getLongField(msg, "D"); } catch (Throwable ignored) {}
        try { return XposedHelpers.getLongField(msg, "d"); } catch (Throwable ignored) {}
        for (Field f : cls.getDeclaredFields()) {
            if (f.getType() == long.class) {
                String fn = f.getName().toLowerCase();
                if (fn.contains("msgid") || fn.contains("svrid") || fn.contains("msg_id")) {
                    try { f.setAccessible(true); return f.getLong(msg); } catch (Throwable ignored) {}
                }
            }
        }
        // 也检查 int 类型的 msgId
        for (Field f : cls.getDeclaredFields()) {
            if (f.getType() == int.class) {
                String fn = f.getName().toLowerCase();
                if (fn.contains("msgid") || fn.contains("svrid") || fn.contains("msg_id") || fn.contains("id")) {
                    try { f.setAccessible(true); return f.getInt(msg); } catch (Throwable ignored) {}
                }
            }
        }
        return 0;
    }

    private static String extractTalker(Object msg) {
        if (msg == null) return "";
        String s = extractTalkerFromClass(msg, msg.getClass());
        if (s != null && !s.isEmpty()) return s;
        for (Class<?> sc = msg.getClass().getSuperclass(); sc != null && sc != Object.class; sc = sc.getSuperclass()) {
            s = extractTalkerFromClass(msg, sc);
            if (s != null && !s.isEmpty()) return s;
        }
        return "";
    }

    private static String extractTalkerFromClass(Object msg, Class<?> cls) {
        if (cls == null) return null;
        try { return (String) XposedHelpers.getObjectField(msg, "field_talker"); } catch (Throwable ignored) {}
        try { return (String) XposedHelpers.getObjectField(msg, "talker"); } catch (Throwable ignored) {}
        for (Field f : cls.getDeclaredFields()) {
            if (f.getType() == String.class) {
                String fn = f.getName().toLowerCase();
                if (fn.contains("talker") || fn.contains("username") || fn.contains("fromuser")) {
                    try { f.setAccessible(true); return (String) f.get(msg); } catch (Throwable ignored) {}
                }
            }
        }
        return null;
    }

    private static String dumpObjFields(Object obj) {
        if (obj == null) return "null";
        StringBuilder sb = new StringBuilder(obj.getClass().getName()).append(" extends ");
        Class<?> sc = obj.getClass().getSuperclass();
        sb.append(sc != null ? sc.getSimpleName() : "null");
        sb.append(" {");
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
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
        }
        sb.append("}");
        return sb.toString();
    }

    private static void showToast(String text) {
        try {
            Context ctx = sChatAct != null ? sChatAct : ContextManager.getAppContext();
            if (ctx != null) Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }
}
