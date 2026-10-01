package com.leshao.v3.hook;

import android.content.SharedPreferences;

import com.leshao.v3.ChatFooterLongPressMenu;
import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
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
    private static final String K_TEXT = "ls_msgforge_text";

    /** 兼容旧配置残留的模式名，仅保留纯文本替换。 */
    public static final String MODE_SYSTEM = "system";

    private static final String DEF_TEXT = "【安全提示】检测到当前会话存在风险，请谨慎操作。";

    private static volatile boolean sEnabled = false;
    private static volatile String sText = DEF_TEXT;

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
        sText = sp.getString(K_TEXT, DEF_TEXT);
        LogWriter.log(TAG, "config enabled=" + sEnabled);
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

    public static String getText() {
        try {
            return ContextManager.getPrefs().getString(K_TEXT, DEF_TEXT);
        } catch (Throwable t) {
            return sText;
        }
    }

    public static void setText(String v) { put(K_TEXT, v); sText = v; }

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
            int totalHooked = 0;
            for (String clsName : cands) {
                int total = 0;
                for (Class<?> c : HookUtil.loadClasses(cl, clsName)) {
                    total += HookUtil.hookCtors(c, null, new XC_MethodHook() {
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
                    totalHooked += total;
                } else {
                    LogWriter.log(TAG, "候选 " + clsName + " 无匹配构造器，尝试下一个");
                }
            }
            if (totalHooked == 0) {
                LogWriter.log(TAG, "NetSceneSendMsg 构造器未匹配 candidates=" + cands);
                return;
            }
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

    /**
     * 在所有 NetSceneSendMsg 候选类构造器中扫描 (String content, int type==1) 参数对，
     * 不依赖固定参数位置（不同微信版本构造器布局可能不同）。
     */
    private static void disguiseCtor(XC_MethodHook.MethodHookParam param) {
        if (!sEnabled) return;
        Object[] args = param.args;
        if (args == null || args.length < 2) return;
        // 查找 content 位置：int type==1 的前一个参数是 String 内容
        int contentIdx = -1;
        int typeIdx = -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Number
                    && ((Number) args[i]).intValue() == 1
                    && i > 0 && args[i - 1] instanceof String) {
                typeIdx = i;
                contentIdx = i - 1;
                break;
            }
        }
        if (contentIdx < 0) return;
        String original = (String) args[contentIdx];
        if (original == null || original.isEmpty() || isForged(original)) return;
        String content = apply(original);
        if (content == null) return;
        args[contentIdx] = content;
        args[typeIdx] = targetType();
        if (typeIdx + 1 < args.length && args[typeIdx + 1] instanceof Number) {
            args[typeIdx + 1] = targetFlag();
        }
        LogWriter.log(TAG, "disguise ctor -> type=" + args[typeIdx]
                + " cls=" + param.method.getDeclaringClass().getName()
                + " args=" + args.length);
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

    // v3.0.82：微信不支持客户端伪造发送系统消息/名片/链接卡片（type=42 名片需服务器校验真实
    // username，type=10000 无客户端发送流程，type=49 会显示原始 XML）。因此消息伪装
    // 只保留「纯文本替换」：type 恒为 1，仅把 content 替换为自定义伪装文字，保证发送成功。
    private static int targetType() {
        return 1;
    }

    private static int targetFlag() {
        return 0;
    }

    /** 生成伪造 payload：普通文本替换为伪装文案；群聊 @ 消息保留「@昵称 」前缀，只替换正文。 */
    public static String apply(String original) {
        if (isAtMessage(original)) return forgeAtMessage(original);
        return systemContent(original);
    }

    /** 群聊 @ 消息：content 形如「@昵称 正文」，@ 关系由 msgsource 字段携带，
     *  content 里必须隐藏 @ 昵称，只保留伪装文案（否则 @ 出来等于没伪装）。
     *  msgsource 的 atusernames 不被修改，@ 提醒仍有效，但显示内容不含 @ 前缀。 */
    private static String forgeAtMessage(String content) {
        if (content == null) return content;
        String forged = (sText == null || sText.isEmpty()) ? content : sText;
        LogWriter.log(TAG, "at msg forged hiddenAt body=" + trunc(forged, 20));
        return forged;
    }

    public static String systemContent(String original) {
        return (sText == null || sText.isEmpty()) ? original : sText;
    }

    /** 供 UI 预览当前伪装配置的文案。 */
    public static String preview() {
        updateConfig();
        return systemContent(DEF_TEXT);
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
        // 四条链路同时安装，互不干扰：
        //  - qs5.v5(SendMsgMgr) 发送方法改写（8.0.78 实测 UI 发送最终收敛于此，主生效点）；
        //  - oh0.c 命中时在发送任务内就地改写 content/type（对走新框架的发送生效）；
        //  - om.SendTextComponent 命中时仅记录并放行（诊断，不再预发送/拦截）；
        //  - f9 消息入库层改写（最可靠兜底：微信 UI 发送必然 insert MMMsg 到 message 表）。
        installSendMgrPatch(fcl);
        installLogicPatch(fcl);
        installSendComponentFallback(fcl);
        installStoragePatch(fcl);
    }

    private static ClassLoader resolveLoader(ClassLoader cl) {
        try {
            ClassLoader tk = VersionCompat.findTinkerClassLoader(cl);
            if (tk != null && !tk.getClass().getName().contains("Leshao") && tk != cl) return tk;
        } catch (Throwable ignored) {}
        return cl;
    }

    /** 主生效点：qs5.v5（MicroMsg.SendMsgMgr）发送方法 oj/nj/mj/pj(toUser,content,type,flag)。 */
    private static void installSendMgrPatch(ClassLoader cl) {
        try {
            List<String> names = new java.util.ArrayList<>();
            try {
                names.addAll(DexKitHelper.findClassesByString(cl, "MicroMsg.SendMsgMgr"));
            } catch (Throwable ignored) {}
            if (!names.contains("qs5.v5")) names.add("qs5.v5");
            if (!names.contains("kl5.s5")) names.add("kl5.s5");
            int installed = 0;
            for (String cn : names) {
                for (Class<?> c : HookUtil.loadClasses(cl, cn)) {
                    for (Method m : c.getDeclaredMethods()) {
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length < 4 || pts[0] != String.class || pts[1] != String.class) continue;
                        if (!HookUtil.isInt(pts[2]) || !HookUtil.isInt(pts[3])) continue;
                        String mn = m.getName();
                        if (!"oj".equals(mn) && !"nj".equals(mn)
                                && !"mj".equals(mn) && !"pj".equals(mn)) continue;
                        try {
                            m.setAccessible(true);
                            XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam p) {
                                    try {
                                        patchSendMgrArgs(p);
                                    } catch (Throwable e) {
                                        LogWriter.log(TAG, "sendmgr patch err: " + e);
                                    }
                                }
                            });
                            installed++;
                            LogWriter.log(TAG, "SendMsgMgr hooked cls=" + c.getName()
                                    + " loader=" + HookUtil.loaderName(c.getClassLoader())
                                    + " m=" + mn + "(" + Arrays.toString(pts) + ")");
                        } catch (Throwable ignored) {}
                    }
                }
            }
            LogWriter.log(TAG, "SendMsgMgr 发送方法补丁 hooks=" + installed + " cands=" + names.size());
        } catch (Throwable t) {
            LogWriter.log(TAG, "SendMsgMgr 补丁 FAIL: " + t.getMessage());
        }
    }

    private static void patchSendMgrArgs(XC_MethodHook.MethodHookParam p) {
        if (!sEnabled) return;
        Object[] a = p.args;
        if (a == null || a.length < 4) return;
        if (!(a[0] instanceof String) || !(a[1] instanceof String)) return;
        int type = a[2] instanceof Number ? ((Number) a[2]).intValue() : 0;
        if (type != 1) return;
        String original = (String) a[1];
        if (original == null || original.isEmpty() || isForged(original)) return;
        String content = apply(original);
        if (content == null) return;
        a[1] = content;
        a[2] = targetType();
        if (a.length >= 4 && a[3] instanceof Number) a[3] = targetFlag();
        // 群聊 @ 消息：content 已隐藏 @ 昵称，必须同步清空 atusernames/msgsource 参数，
        // 否则微信按 @ 消息校验（content 无 @ 昵称）会拒绝发送。
        if (isAtMessage(original)) {
            clearAtArgs(a);
            LogWriter.log(TAG, "sendmgr patch clearedAtArgs");
        }
        LogWriter.log(TAG, "sendmgr patch -> type=" + a[2]
                + " talker=" + a[0] + " text=" + trunc(original, 20));
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
                        LogWriter.log(TAG, "SendTextLogic hooked cls=" + c.getName()
                                + " m=" + m.getName() + "(" + java.util.Arrays.toString(pts) + ")");
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
        if (isAtMessage(original)) {
            clearAtArgs(a);
            LogWriter.log(TAG, "logic patch clearedAtArgs");
        }
        LogWriter.log(TAG, "logic patch -> type=" + a[2]
                + " origLen=" + (original == null ? 0 : original.length()));
    }

    /** 清空发送参数中索引 4 起的 @ 相关字符串（msgsource XML / atusernames / atuserlist）。
     *  索引 0-3 分别是 talker/content/type/flag，绝不能动。 */
    private static void clearAtArgs(Object[] a) {
        if (a == null) return;
        for (int i = 4; i < a.length; i++) {
            if (a[i] instanceof String) {
                String s = (String) a[i];
                if (s.contains("<msgsource>") || s.contains("atusernames")
                        || s.contains("atuserlist") || s.contains("@chatroom")) {
                    a[i] = "";
                }
            }
        }
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
        // 不再“预发送 + 拦截原发送”（会导致消息上屏后状态无人更新而一直转圈）。
        // 直接放行原发送：真实发送会流经 SendTextLogic 链路补丁（installLogicPatch），
        // 由 patchLogicArgs 就地改写 content/type，微信原生完成上屏与状态流转。
        LogWriter.log(TAG, "ui intercept " + mname + " 放行, 由逻辑链路改写 type=" + targetType()
                + " talker=" + talker + " text=" + trunc(original, 30));
    }

    private static boolean isForged(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.startsWith("<msg") || t.startsWith("<?xml") || t.contains("<appmsg");
    }

    /** 微信群聊 @ 消息识别：content 形如「@昵称 文字」且 @ 前只有不可见控制符/空白。
     *  @ 消息结构特殊（含 @ 关系），若替换 content 会破坏发送协议导致发不出去，必须放行。 */
    private static boolean isAtMessage(String s) {
        if (s == null || s.isEmpty()) return false;
        if (s.contains("atuserlist") || s.contains("<msgsource>")) return true;
        int idx = s.indexOf('@');
        if (idx < 0) return false;
        String before = s.substring(0, idx);
        for (int i = 0; i < before.length(); i++) {
            char c = before.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isISOControl(c)
                    && c != '\u200B' && c != '\u200C' && c != '\u200D' && c != '\uFEFF') {
                return false;
            }
        }
        return true;
    }

    private static String trunc(String s, int m) {
        return s == null ? "" : s.length() > m ? s.substring(0, m) + "..." : s;
    }

    // ---------------- 消息入库层改写（最可靠兜底） ----------------
    //
    // 微信 UI 发送文本必然调用 com.tencent.mm.storage.f9 的 insert 方法（Bb/Db/Hb/yb），
    // 参数 p0=com.tencent.mm.storage.e9（MMMsg）。在此处将出站 type==1 消息的
    // field_type / field_content 就地改写为伪造类型与 XML，微信原生完成上屏与状态流转。
    private static void installStoragePatch(ClassLoader cl) {
        try {
            Class<?> f9 = VersionCompat.findMsgStorageClass(cl);
            if (f9 == null) {
                try {
                    f9 = XposedHelpers.findClass("com.tencent.mm.storage.f9", cl);
                } catch (Throwable ignored) {}
            }
            if (f9 == null) {
                LogWriter.log(TAG, "storage patch FAIL: f9 not found");
                return;
            }
            int installed = 0;
            for (Method m : f9.getDeclaredMethods()) {
                String mn = m.getName();
                if (!"Bb".equals(mn) && !"Db".equals(mn)
                        && !"Hb".equals(mn) && !"yb".equals(mn)) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 1) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                patchStorageArgs(p);
                            } catch (Throwable e) {
                                LogWriter.log(TAG, "storage patch err: " + e);
                            }
                        }
                    });
                    installed++;
                    LogWriter.log(TAG, "storage patch hooked " + f9.getName()
                            + "." + mn + "(" + Arrays.toString(pts) + ")");
                } catch (Throwable ignored) {}
            }
            LogWriter.log(TAG, "storage patch hooks=" + installed);
        } catch (Throwable t) {
            LogWriter.log(TAG, "installStoragePatch FAIL: " + t.getMessage());
        }
    }

    private static void patchStorageArgs(XC_MethodHook.MethodHookParam p) {
        if (!sEnabled) return;
        if (p.args == null || p.args.length < 1 || p.args[0] == null) return;
        Object msg = p.args[0];
        // e9 的 isSend/type/content 字段可能声明在父类（MessageHook 用 z0()/getType() 方法调用），
        // 因此先尝试方法调用，失败再沿继承链反射字段，避免 getDeclaredField 找不到而静默失败。
        int isSend;
        try {
            isSend = ((Number) XposedHelpers.callMethod(msg, "z0")).intValue();
        } catch (Throwable t) {
            try {
                isSend = readIntFieldUp(msg, "field_isSend");
            } catch (Throwable t2) {
                return;
            }
        }
        if (isSend != 1) return;
        int type;
        try {
            type = ((Number) XposedHelpers.callMethod(msg, "getType")).intValue();
        } catch (Throwable t) {
            try {
                type = readIntFieldUp(msg, "field_type");
            } catch (Throwable t2) {
                return;
            }
        }
        if (type != 1) return;
        String original = null;
        try {
            Object v = XposedHelpers.callMethod(msg, "getContent");
            if (v != null) original = v.toString();
        } catch (Throwable ignored) {}
        if (original == null || original.isEmpty()) {
            try {
                original = readStrFieldUp(msg, "field_content");
            } catch (Throwable ignored) {}
        }
        if (original == null || original.isEmpty() || isForged(original)) return;
        String content = apply(original);
        if (content == null) return;
        // 群聊 @ 消息：content 隐藏 @ 昵称后，必须同步清空消息对象上的 msgsource，
        // 否则微信按 @ 消息校验（content 无 @ 昵称）会拒绝发送。
        if (isAtMessage(original)) {
            clearMsgSource(msg);
            LogWriter.log(TAG, "storage patch clearedMsgSource");
        }
        // 写入 type：优先 setType 方法，失败沿继承链写字段
        boolean typeWritten = false;
        try {
            XposedHelpers.callMethod(msg, "setType", targetType());
            typeWritten = true;
        } catch (Throwable ignored) {}
        if (!typeWritten) {
            try {
                writeIntFieldUp(msg, "field_type", targetType());
            } catch (Throwable ignored) {}
        }
        // 写入 content：优先 setContent，失败写字段
        boolean contentWritten = false;
        try {
            XposedHelpers.callMethod(msg, "setContent", content);
            contentWritten = true;
        } catch (Throwable ignored) {}
        if (!contentWritten) {
            try {
                writeStrFieldUp(msg, "field_content", content);
            } catch (Throwable ignored) {}
        }
        // type 恒为 1（纯文本替换），无需额外状态处理。
        long msgId = 0;
        try {
            msgId = ((Number) XposedHelpers.callMethod(msg, "getMsgId")).longValue();
        } catch (Throwable ignored) {}
        LogWriter.log(TAG, "storage patch -> type=" + targetType()
                + " isSend=1 msgId=" + msgId + " text=" + trunc(original, 20));
    }

/** 清空 e9 消息对象上的 msgsource（@ 关系载体），把 @ 消息降级为普通文本消息。 */
    private static void clearMsgSource(Object msg) {
        String[] names = {"msgSource", "msgsource", "field_msgSource", "field_msgsource"};
        for (String n : names) {
            try {
                java.lang.reflect.Field f = findFieldUp(msg, n);
                f.set(msg, "");
                LogWriter.log(TAG, "msgSource cleared field=" + n);
                return;
            } catch (Throwable ignored) {}
        }
        try {
            XposedHelpers.callMethod(msg, "setMsgSource", "");
            LogWriter.log(TAG, "msgSource cleared via setMsgSource");
            return;
        } catch (Throwable ignored) {}
        // 沿继承链找 String 字段值含 msgsource/atusernames 的兜底
        for (Class<?> c = msg.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(msg);
                    if (v instanceof String && (((String) v).contains("<msgsource>")
                            || ((String) v).contains("atusernames"))) {
                        f.set(msg, "");
                        LogWriter.log(TAG, "msgSource cleared field=" + f.getName());
                        return;
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    /** 沿继承链查找字段（e9 的字段可能在父类声明）。 */
    private static java.lang.reflect.Field findFieldUp(Object o, String name) throws NoSuchFieldException {
        Class<?> c = o.getClass();
        while (c != null && c != Object.class) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static int readIntFieldUp(Object o, String name) throws Throwable {
        java.lang.reflect.Field f = findFieldUp(o, name);
        return ((Number) f.get(o)).intValue();
    }

    private static String readStrFieldUp(Object o, String name) throws Throwable {
        java.lang.reflect.Field f = findFieldUp(o, name);
        Object v = f.get(o);
        return v == null ? null : v.toString();
    }

    private static void writeIntFieldUp(Object o, String name, int value) throws Throwable {
        java.lang.reflect.Field f = findFieldUp(o, name);
        f.set(o, value);
    }

    private static void writeStrFieldUp(Object o, String name, String value) throws Throwable {
        java.lang.reflect.Field f = findFieldUp(o, name);
        f.set(o, value);
    }
}
