package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Enumeration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
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
        hookForwardTracing(cl);

        sHooked = true;
        LogWriter.log(TAG, "ready — do a native forward to trace API");
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

            // 从 view 获取 Activity
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
            final Activity fwdAct = act;
            final Object fwdMsg = (sPendingMsg != null) ? sPendingMsg : msg;
            com.leshao.v3.ui.ContactPickerDialog.show(act, "", 
                com.leshao.v3.ui.ContactPickerDialog.MODE_GROUP,
                new com.leshao.v3.ui.ContactPickerDialog.OnContactsSelected() {
                    @Override
                    public void onSelected(Set<String> wxids, String display) {
                        LogWriter.log(TAG, "forward: selected=" + wxids + " display=" + display);
                        int done = 0;
                        for (String wxid : wxids) {
                            if (doForwardVoice(fwdAct, fwdMsg, wxid)) done++;
                        }
                        showToast("已转发到 " + done + " 个目标");
                    }
                });
        } catch (Throwable t) {
            LogWriter.log(TAG, "forward error: " + t.getMessage());
            showToast("转发失败: " + t.getMessage());
        } finally {
            sForwarding = false;
            sMenuInjected = false;
        }
    }

    // ===== 实际转发语音 (tl.p0 SceneVoice Recorder 方案) =====
    private static boolean doForwardVoice(Activity act, Object msgObj, String targetWxid) {
        try {
            Object e9 = getE9(msgObj);
            if (e9 == null) { LogWriter.log(TAG, "doForward: cannot get e9"); return false; }

            ClassLoader cl = ContextManager.getClassLoader();
            long msgId = extractMsgId(e9);
            String xml = null;
            try { xml = (String) XposedHelpers.callMethod(e9, "I0"); } catch (Throwable ignored) {}

            LogWriter.log(TAG, "doForward: msgId=" + msgId + " → " + targetWxid);

            // 1. 找语音文件
            String voiceFile = findVoiceFile(e9);
            if (voiceFile == null) {
                LogWriter.log(TAG, "doForward: cannot find any voice file");
                return false;
            }
            LogWriter.log(TAG, "doForward: voiceFile=" + voiceFile);

            // 2. 解析时长
            int duration = parseVoiceDuration(xml);
            LogWriter.log(TAG, "doForward: duration=" + duration + "ms");

            // 3. 使用 tl.p0 SceneVoice Recorder 发送
            boolean sent = sendViaSceneVoice(act, cl, targetWxid, voiceFile, duration, e9);
            LogWriter.log(TAG, "doForward: sent=" + sent);
            return sent;
        } catch (Throwable t) {
            LogWriter.log(TAG, "doForward error: " + t.getMessage());
            return false;
        }
    }

    private static Object getE9(Object msgObj) {
        if (msgObj == null) return null;
        if (msgObj.getClass().getName().contains("storage")) return msgObj;
        try { return XposedHelpers.callMethod(msgObj, "c"); } catch (Throwable ignored) {}
        for (String mn : new String[]{"getMsgInfo","getMsg","a","b","d"}) {
            try {
                Object r = XposedHelpers.callMethod(msgObj, mn);
                if (r != null && r.getClass().getName().contains("storage")) return r;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String findVoiceFile(Object e9) {
        // y0() → clientmsgid → MD5 → 2级子目录 → voice2/XX/YY/msg_{cid}.amr
        ClassLoader cl = ContextManager.getClassLoader();

        String cid = null;
        try { cid = (String) XposedHelpers.callMethod(e9, "y0"); } catch (Throwable ignored) {}
        if (cid == null || cid.isEmpty()) {
            try {
                String xml = (String) XposedHelpers.callMethod(e9, "I0");
                if (xml != null) cid = extractXmlAttr(xml, "clientmsgid");
            } catch (Throwable ignored) {}
        }
        if (cid == null || cid.isEmpty()) {
            LogWriter.log(TAG, "voice: no clientmsgid");
            return null;
        }
        LogWriter.log(TAG, "voice: cid=" + cid);

        // 优先用 MD5 路径直接定位
        String uinHash = getUinHash(cl);
        if (uinHash != null) {
            String[] roots = {
                "/data/data/com.tencent.mm/MicroMsg/" + uinHash + "/voice2",
                "/data/user/0/com.tencent.mm/MicroMsg/" + uinHash + "/voice2",
            };
            String md5 = md5(cid);
            String path = md5.substring(0, 2) + "/" + md5.substring(2, 4) + "/msg_" + cid + ".amr";
            for (String v2 : roots) {
                java.io.File f = new java.io.File(v2, path);
                if (f.exists()) { LogWriter.log(TAG, "voice: " + f.getAbsolutePath() + " EXISTS"); return f.getAbsolutePath(); }
            }
            LogWriter.log(TAG, "voice: md5 path not found, falling back to search");
        } else {
            LogWriter.log(TAG, "voice: no uinHash, searching recursively");
        }

        // fallback: 递归搜索 voice2 下精准匹配 msg_{cid}.amr
        return searchVoice2Dir(cid);
    }

    private static String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    private static String extractXmlAttr(String xml, String attr) {
        for (String q : new String[]{"\"", "'"}) {
            String pattern = attr + "=" + q;
            int idx = xml.indexOf(pattern);
            if (idx >= 0) {
                idx += pattern.length();
                int end = xml.indexOf(q, idx);
                if (end > idx) return xml.substring(idx, end);
            }
        }
        return null;
    }

    private static String getUinHash(ClassLoader cl) {
        try {
            for (String clsName : new String[]{
                "com.tencent.mm.kernel.h",
                "com.tencent.mm.kernel.g",
                "com.tencent.mm.sdk.platformtools.x"
            }) {
                try {
                    Object acc = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass(clsName, cl), "c");
                    if (acc != null) {
                        long uin = 0;
                        try { uin = XposedHelpers.getIntField(acc, "e"); } catch (Throwable ignored) {}
                        if (uin <= 0) try { uin = XposedHelpers.getLongField(acc, "e"); } catch (Throwable ignored) {}
                        if (uin <= 0) try { uin = XposedHelpers.getIntField(acc, "field_uin"); } catch (Throwable ignored) {}
                        if (uin <= 0) try {
                            Object val = XposedHelpers.callMethod(acc, "getUin");
                            if (val instanceof Integer) uin = ((Integer) val).longValue();
                            else if (val instanceof Long) uin = (Long) val;
                        } catch (Throwable ignored) {}
                        if (uin > 0) {
                            String uinStr = String.valueOf(uin);
                            return md5(uinStr);
                        }
                    }
                } catch (Throwable ignored) {}
            }

            try {
                android.content.Context ctx = ContextManager.getAppContext();
                if (ctx != null) {
                    android.content.SharedPreferences sp = ctx.getSharedPreferences("system_config_prefs", 0);
                    long uin = 0;
                    try { uin = sp.getInt("default_uin", 0); } catch (Throwable ignored) {}
                    if (uin <= 0) try { uin = sp.getLong("default_uin", 0); } catch (Throwable ignored) {}
                    if (uin > 0) {
                        LogWriter.log(TAG, "uinHash from prefs: uin=" + uin);
                        return md5(String.valueOf(uin));
                    }
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "uinHash prefs error: " + t.getMessage());
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "getUinHash error: " + t.getMessage());
        }
        return null;
    }

    private static String searchVoice2Dir(String cid) {
        String targetName = "msg_" + cid + ".amr";
        String[] roots = {
            "/data/data/com.tencent.mm/MicroMsg",
            "/data/user/0/com.tencent.mm/MicroMsg",
        };
        for (String root : roots) {
            java.io.File md = new java.io.File(root);
            if (!md.exists()) continue;
            for (java.io.File userDir : md.listFiles()) {
                if (!userDir.isDirectory()) continue;
                java.io.File v2 = new java.io.File(userDir, "voice2");
                if (!v2.isDirectory()) { v2 = new java.io.File(userDir, "voice"); continue; }
                if (!v2.isDirectory()) continue;
                String found = searchFileRecursive(v2, targetName, 4);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String searchFileRecursive(java.io.File dir, String targetName, int depth) {
        if (depth <= 0 || dir == null) return null;
        java.io.File[] files = dir.listFiles();
        if (files == null) return null;
        for (java.io.File f : files) {
            if (f.isDirectory()) {
                String found = searchFileRecursive(f, targetName, depth - 1);
                if (found != null) return found;
            } else if (f.isFile() && f.getName().equals(targetName) && f.length() > 500) {
                LogWriter.log(TAG, "voice2: found " + f.getAbsolutePath() + " size=" + f.length());
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private static int parseVoiceDuration(String xml) {
        if (xml == null) return 5000; // default 5s
        try {
            for (String attr : new String[]{"voicelength", "length"}) {
                int idx = xml.indexOf(attr + "=\"");
                if (idx >= 0) {
                    idx += attr.length() + 2;
                    int end = xml.indexOf("\"", idx);
                    if (end > idx) return Integer.parseInt(xml.substring(idx, end));
                }
            }
        } catch (Throwable ignored) {}
        return 5000;
    }

    private static boolean sendViaSceneVoice(Activity act, ClassLoader cl, String targetWxid, String voiceFile, int duration, Object origE9) {
        try {
            LogWriter.log(TAG, "SceneVoice: origTalker=" + extractTalker(origE9) + " origMsgId=" + extractMsgId(origE9));

            Class<?> e9Class = XposedHelpers.findClass("com.tencent.mm.storage.e9", cl);

            // 获取原始语音 XML, 作为模板
            String origXml = null;
            try { origXml = (String) XposedHelpers.callMethod(origE9, "I0"); } catch (Throwable ignored) {}
            if (origXml == null) try { origXml = (String) XposedHelpers.getObjectField(origE9, "field_content"); } catch (Throwable ignored) {}
            LogWriter.log(TAG, "SceneVoice: origXml=" + (origXml != null ? origXml.substring(0, Math.min(80, origXml.length())) : "null"));

            // 创建新的语音消息
            Object newMsg = XposedHelpers.newInstance(e9Class, targetWxid);
            XposedHelpers.callMethod(newMsg, "A1", 34);   // setType=语音(34)
            XposedHelpers.callMethod(newMsg, "e1", System.currentTimeMillis()); // setCreateTime

            // 设 talker
            try { XposedHelpers.setObjectField(newMsg, "field_talker", targetWxid); } catch (Throwable ignored) {}
            try { XposedHelpers.callMethod(newMsg, "y1", targetWxid); } catch (Throwable ignored) {}

            // 设语音 XML content — 用原始 XML 但替换必要字段
            if (origXml != null) {
                XposedHelpers.callMethod(newMsg, "X0", origXml);
            }

            // 设语音文件路径
            try { XposedHelpers.callMethod(newMsg, "j1", voiceFile); } catch (Throwable ignored) {}
            try { XposedHelpers.setObjectField(newMsg, "field_imgPath", voiceFile); } catch (Throwable ignored) {}

            // f9.H9(msg) 插入 DB — 内部调 uh3.k0.b() 自动分配合法 msgId
            Object storage = getMsgStorage(cl);
            if (storage == null) {
                LogWriter.log(TAG, "SceneVoice: msgStorage is null");
                return false;
            }
            long assignedMsgId = (Long) XposedHelpers.callMethod(storage, "H9", newMsg);
            LogWriter.log(TAG, "SceneVoice: f9.H9() done, assignedMsgId=" + assignedMsgId);

            // b31.w 上传语音文件
            trySendViaB31(cl, targetWxid, voiceFile, duration);

            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "SceneVoice error: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return trySendViaB31(cl, targetWxid, voiceFile, duration);
        }
    }

    private static Object sMsgStorage;

    private static Object getMsgStorage(ClassLoader cl) {
        if (sMsgStorage != null) return sMsgStorage;
        try {
            Class<?> e01d9 = XposedHelpers.findClass("e01.d9", cl);
            Object service = XposedHelpers.callStaticMethod(e01d9, "b");
            if (service != null) {
                sMsgStorage = XposedHelpers.callMethod(service, "u");
                LogWriter.log(TAG, "SceneVoice: msgStorage obtained via e01.d9.b().u()");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "SceneVoice: msgStorage error: " + t.getMessage());
        }
        return sMsgStorage;
    }

    private static boolean trySendViaB31(ClassLoader cl, String targetWxid, String voiceFile, int duration) {
        try {
            Class<?> wClass = XposedHelpers.findClass("b31.w", cl);
            Object sender;
            
            try {
                // 先试无参构造
                sender = XposedHelpers.newInstance(wClass);
            } catch (Throwable e1) {
                try {
                    // 试 (int,int,com.tencent.mm.modelbase.b)  — b31 的内部类型
                    Class<?> bClass = XposedHelpers.findClass("com.tencent.mm.modelbase.b", cl);
                    sender = XposedHelpers.newInstance(wClass,
                        new Class[]{int.class, int.class, bClass}, 0, 0, null);
                } catch (Throwable e2) {
                    LogWriter.log(TAG, "b31.w: all constructors failed: " + e2.getMessage());
                    return false;
                }
            }
            LogWriter.log(TAG, "b31.w: instance created");

            // 设目标 + 文件 + 时长
            try { XposedHelpers.setObjectField(sender, "e", voiceFile); } catch (Throwable ignored) {}
            try { XposedHelpers.setIntField(sender, "m", duration); } catch (Throwable ignored) {}
            try { XposedHelpers.setObjectField(sender, "d", targetWxid); } catch (Throwable ignored) {}
            try { XposedHelpers.callMethod(sender, "init", 0, 0, null); } catch (Throwable ignored) {}

            // start 上传
            try {
                XposedHelpers.callMethod(sender, "start",
                    new Class[]{String.class}, voiceFile);
            } catch (Throwable e) {
                try {
                    XposedHelpers.callMethod(sender, "start", voiceFile);
                } catch (Throwable e2) {
                    LogWriter.log(TAG, "b31.w start() failed: " + e2.getMessage());
                    return false;
                }
            }
            LogWriter.log(TAG, "b31.w: start() called → sent");
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "b31.w error: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return false;
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

    // ===== Send API 发现: 找 tl.p0/x0 + b31.w/j =====
    private static void hookForwardTracing(ClassLoader cl) {
        findSceneVoiceRecorder(cl);
    }

    private static void findSceneVoiceRecorder(ClassLoader cl) {
        // 扫描 DEX 中所有 simpleName 为 p0, o0, x0, y0 的类, 找 SceneVoice Recorder
        try {
            String apkPath = ContextManager.getApkPath();
            if (apkPath == null) return;
            dalvik.system.DexFile dex = new dalvik.system.DexFile(apkPath);
            Enumeration<String> entries = dex.entries();

            while (entries.hasMoreElements()) {
                String cn = entries.nextElement();
                String simple = cn.substring(cn.lastIndexOf('.') + 1);
                // 目标类简名
                if (!simple.equals("p0") && !simple.equals("o0") && !simple.equals("x0") && !simple.equals("y0")
                    && !simple.equals("w") && !simple.equals("j") && !simple.equals("l")) continue;
                try {
                    Class<?> cls = cl.loadClass(cn);
                    // 检查是否有 g(String,e9) 方法 (scene voice recorder 特征)
                    boolean hasG = false, hasStop = false, hasJ = false;
                    String gSig = "", stopSig = "";
                    for (Method m : cls.getDeclaredMethods()) {
                        if (m.getName().equals("g") && m.getParameterTypes().length >= 2) {
                            hasG = true;
                            gSig = sig(m);
                        }
                        if (m.getName().equals("stop") && m.getReturnType() == boolean.class && m.getParameterTypes().length == 0) {
                            hasStop = true;
                            stopSig = sig(m);
                        }
                        if (m.getName().equals("j") && m.getReturnType() != void.class && m.getParameterTypes().length == 0) {
                            hasJ = true;
                        }
                    }
                    if (hasG && hasStop) {
                        LogWriter.log(TAG, "◆FOUND SceneVoice p0: " + cn);
                        for (Method m : cls.getDeclaredMethods()) {
                            LogWriter.log(TAG, "  method: " + sig(m));
                        }
                    }
                    // 检查 b31.w 特征: 有 start()+stop()+init()方法
                    boolean hasStart = false, hasInit = false;
                    for (Method m : cls.getDeclaredMethods()) {
                        if (m.getName().equals("start")) hasStart = true;
                        if (m.getName().equals("init")) hasInit = true;
                    }
                    if (hasStart && hasStop && hasInit && simple.equals("w")) {
                        LogWriter.log(TAG, "◆FOUND b31.w: " + cn);
                        for (Method m : cls.getDeclaredMethods()) {
                            LogWriter.log(TAG, "  method: " + sig(m));
                        }
                    }
                    // 检查 b31.j/l 特征: 有 doScene()+onGYNetEnd()
                    boolean hasDoScene = false, hasOnGY = false;
                    for (Method m : cls.getDeclaredMethods()) {
                        if (m.getName().equals("doScene")) hasDoScene = true;
                        if (m.getName().equals("onGYNetEnd")) hasOnGY = true;
                    }
                    if (hasDoScene && hasOnGY && (simple.equals("j") || simple.equals("l"))) {
                        LogWriter.log(TAG, "◆FOUND b31." + simple + ": " + cn);
                        for (Method m : cls.getDeclaredMethods()) {
                            LogWriter.log(TAG, "  method: " + sig(m));
                        }
                    }
                    // 检查 x0 特征: 有 t() 或 g() 静态方法
                    if (simple.equals("x0")) {
                        boolean hasT = false, hasGStatic = false;
                        for (Method m : cls.getDeclaredMethods()) {
                            if (m.getName().equals("t")) hasT = true;
                            if (m.getName().equals("g") && Modifier.isStatic(m.getModifiers())) hasGStatic = true;
                        }
                        if (hasT || hasGStatic) {
                            LogWriter.log(TAG, "◆FOUND x0 utils: " + cn);
                            for (Method m : cls.getDeclaredMethods()) {
                                LogWriter.log(TAG, "  method: " + sig(m));
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            dex.close();
        } catch (Throwable t) {
            LogWriter.log(TAG, "◆find error: " + t.getMessage());
        }
    }

    private static String sig(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append("(");
        Class<?>[] pts = m.getParameterTypes();
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(pts[i].getSimpleName());
        }
        sb.append(")→").append(m.getReturnType().getSimpleName());
        return sb.toString();
    }
}
