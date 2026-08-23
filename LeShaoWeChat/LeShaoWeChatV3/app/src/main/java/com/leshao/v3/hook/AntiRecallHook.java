package com.leshao.v3.hook;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.service.StatsCollector;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class AntiRecallHook {

    private static final String TAG = "AntiRecallHook";
    private static volatile boolean sEnabled = true;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook() {
        if (!ContextManager.isReady()) {
            LogWriter.log(TAG, "hook ABORTED: ContextManager not ready");
            return;
        }

        // 从配置初始化开关：默认关闭时仅安装 hook 外壳，回调内按 sEnabled 判断，
        // 设置页切换后立即生效，避免关闭后重启仍强制生效
        try {
            ModuleConfig cfg = ModuleConfig.load(ContextManager.getPrefs());
            sEnabled = cfg != null && cfg.antiRecall;
            LogWriter.log(TAG, "antiRecall enabled=" + sEnabled);
        } catch (Throwable ignored) {}

        ClassLoader cl = ContextManager.getClassLoader();

        hookXmlRevoke(cl);
        hookProtoRevoke(cl);
        hookRecallRecorder(cl);
        hookKotlinRevoke(cl);
        hookF9Ta(cl);
        LogWriter.log(TAG, "anti-recall hooks installed (5 paths)");
    }

    // ===== 路径1: XML 撤回阻断 — af5.a.run() =====

    private static void hookXmlRevoke(ClassLoader cl) {
        // 多版本类名尝试（按最常见排序）
        for (String clsName : new String[]{
            "af5.a",      // 实测 8.0.76 有效
            "af6.a",      // 备选
            "af4.a",      // 备选 8.0.49
            "af6.c",      // 备选
            "af5.c",      // 备选
        }) {
            try {
                Class<?> c = XposedHelpers.findClass(clsName, cl);
                XposedBridge.hookAllMethods(c, "run", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (!sEnabled) return;
                            param.setResult(null);
                            LogWriter.log(TAG, "[XML] 阻止撤回成功: " + clsName);
                        } catch (Throwable ignored) {}
                    }
                });
                LogWriter.log(TAG, "[XML] Hooked: " + clsName + ".run()");
                return;
            } catch (XposedHelpers.ClassNotFoundError ignored) {
            } catch (Throwable t) { LogWriter.log(TAG, "[XML] " + clsName + " err: " + t.getMessage()); }
        }
        LogWriter.log(TAG, "[XML] 未找到 af*.run() 类，请更新类名");
    }

    // ===== 路径2: Protobuf 撤回阻断 — e01.u.f() =====

    private static void hookProtoRevoke(ClassLoader cl) {
        for (String clsName : new String[]{
            "e01.u",      // 实测 8.0.76 有效
            "e02.u",      // 备选
            "e00.u",      // 备选
            "e01.t",      // 备选
            "e02.t",      // 备选
        }) {
            try {
                Class<?> c = XposedHelpers.findClass(clsName, cl);
                XposedBridge.hookAllMethods(c, "f", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (!sEnabled) return;
                            param.setResult(null);
                            LogWriter.log(TAG, "[Proto] 阻止撤回成功: " + clsName);
                        } catch (Throwable ignored) {}
                    }
                });
                LogWriter.log(TAG, "[Proto] Hooked: " + clsName + ".f()");
                return;
            } catch (XposedHelpers.ClassNotFoundError ignored) {
            } catch (Throwable t) { LogWriter.log(TAG, "[Proto] " + clsName + " err: " + t.getMessage()); }
        }
        LogWriter.log(TAG, "[Proto] 未找到 e*.f() 类，请更新类名");
    }

    // ===== 路径3: 撤回记录 + 系统提示插入 =====

    private static void hookRecallRecorder(ClassLoader cl) {
        for (String className : new String[]{
            "com.tencent.mm.modelmulti.p",
            "com.tencent.mm.modelmulti.q",
        }) {
            try {
                Class<?> c = cl.loadClass(className);
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (m.getParameterTypes().length >= 2) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    try {
                                        if (!sEnabled) return;
                                        try {
                                            Object msgObj = findRecallMsg(param.args);
                                            if (msgObj != null) {
                                                String talker = getField(msgObj, "field_talker", "getTalker");
                                                String content = getField(msgObj, "field_content", "getContent");
                                                if (content != null) {
                                                    StatsCollector.recordRecall(talker, content);
                                                    LogWriter.log(TAG, "recall recorded: " + talker + " -> " + (content.length() > 20 ? content.substring(0, 20) + "..." : content));
                                                }
                                                // 尝试插入系统提示 "xxx撤回了一条消息"
                                                tryInsertSystemTip(msgObj, talker);
                                            }
                                        } catch (Throwable ignored) {}
                                    } catch (Throwable ignored) {}
                                }
                            });
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 尝试在聊天中插入 "某某某撤回了一条消息" 的系统提示
     */
    private static void tryInsertSystemTip(Object recallMsg, String talkerWxid) {
        try {
            // 尝试拿到原消息的 talker（聊天对象）
            String convTalker = getField(recallMsg, "field_talker", "talker", "getTalker");
            if (convTalker == null) return;

            // 取撤回者显示名（联系人数据源已清空，暂用 wxid；待重写）
            String displayName = talkerWxid;

            String tipText = displayName + " 撤回了一条消息";

            // 调用 WeChat 内部 API 插入系统消息:
            // 方法1: com.tencent.mm.model.br.a(String convTalker, long time, cc msg)
            // 方法2: com.tencent.mm.modelmulti.p.c(String convTalker, cc msg, boolean)
            ClassLoader cl = ContextManager.getClassLoader();
            try {
                // 尝试通过 modelmulti 插入
                Class<?> mmCls = cl.loadClass("com.tencent.mm.modelmulti.p");
                if (mmCls != null) {
                    // 找 insert 方法: 通常签名 (String, cc, boolean) 或 (String, cc)
                    for (java.lang.reflect.Method mm : mmCls.getDeclaredMethods()) {
                        Class<?>[] pts = mm.getParameterTypes();
                        if (pts.length >= 2 && pts[0] == String.class) {
                            // 创建一个简单的系统消息
                            Class<?> msgCls = null;
                            try { msgCls = cl.loadClass("com.tencent.mm.storage.cc"); }
                            catch (Throwable e2) {
                                try { msgCls = cl.loadClass("com.tencent.mm.storage.bv"); }
                                catch (Throwable e3) {}
                            }
                            if (msgCls != null) {
                                Object sysMsg = msgCls.newInstance();
                                // setContent(tipText)
                                trySetField(sysMsg, "field_content", tipText);
                                trySetField(sysMsg, "field_type", 10000);
                                trySetField(sysMsg, "field_isSend", 0);
                                trySetField(sysMsg, "field_createTime", System.currentTimeMillis());
                                trySetField(sysMsg, "field_talker", convTalker);

                                mm.invoke(null, convTalker, sysMsg, false);
                                LogWriter.log(TAG, "system tip inserted: " + tipText);
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                LogWriter.log(TAG, "insert system tip failed: " + e.getMessage());
            }
        } catch (Throwable ignored) {}
    }

    private static void trySetField(Object obj, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Throwable ignored) {}
    }

    private static Object findRecallMsg(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg == null) continue;
            String cn = arg.getClass().getName();
            if (cn.contains("Recall") || cn.contains("Revoke")) return arg;
        }
        return null;
    }

    private static String getField(Object obj, String... names) {
        for (String n : names) {
            try { Object v = obj.getClass().getDeclaredField(n).get(obj); return v != null ? v.toString() : null; }
            catch (Throwable ignored) {}
            try { Object v = obj.getClass().getMethod(n).invoke(obj); return v != null ? v.toString() : null; }
            catch (Throwable ignored) {}
        }
        return null;
    }

    // ===== 路径5: 保险 — storage.f9.Ta 拦截撤回标记消息重新入库 =====
    // 反编译确认: 撤回时会构造类型为 285222674(0x11002712, 保留原文) 或
    // 268445456(0x10002710, 清空内容) 的提示消息, 经 f9.Ta(long, e9, boolean) 重新入库。
    // 即使 e01.u.f 阻断漏网, 此处也可阻止撤回提示消息显示。
    private static void hookF9Ta(ClassLoader cl) {
        try {
            Class<?> f9 = VersionCompat.findMsgStorageClass(cl);
            if (f9 == null) return;
            XposedBridge.hookAllMethods(f9, "Ta", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        if (!sEnabled) return;
                        Object msg = param.args.length > 1 ? param.args[1] : null;
                        if (msg == null) return;
                        int type = getMsgType(msg);
                        if (type == 285222674 || type == 268445456) {
                            // 撤回提示消息: 改写为普通文本「XXX 撤回了一条消息 [已拦截]」后放行入库,
                            // 既保留可见的系统提示, 又标记拦截结果
                            String tip = null;
                            try {
                                java.lang.reflect.Field f = msg.getClass().getDeclaredField("field_content");
                                f.setAccessible(true);
                                Object v = f.get(msg);
                                if (v != null) tip = v.toString();
                            } catch (Throwable ignored) {}
                            try { trySetField(msg, "field_type", 1); } catch (Throwable ignored) {}
                            try { trySetField(msg, "field_content",
                                    (tip == null ? "对方撤回了一条消息" : tip) + " [已拦截]"); } catch (Throwable ignored) {}
                            try {
                                String talker = getField(msg, "field_talker", "talker", "getTalker");
                                StatsCollector.recordRecall(talker, tip);
                            } catch (Throwable ignored) {}
                            LogWriter.log(TAG, "[Ta] 撤回提示改写为拦截提示: " + tip);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "[Ta] Hooked: f9.Ta()");
        } catch (Throwable t) {
            LogWriter.log(TAG, "[Ta] f9.Ta hook err: " + t.getMessage());
        }
    }

    private static int getMsgType(Object msg) {
        try {
            java.lang.reflect.Field f = msg.getClass().getDeclaredField("field_type");
            f.setAccessible(true);
            Object v = f.get(msg);
            if (v instanceof Integer) return (Integer) v;
            if (v instanceof Long) return (int) (long) (Long) v;
        } catch (Throwable ignored) {}
        try {
            Object v = msg.getClass().getMethod("getType").invoke(msg);
            if (v instanceof Integer) return (Integer) v;
            if (v instanceof Long) return (int) (long) (Long) v;
        } catch (Throwable ignored) {}
        return 0;
    }

    // ===== 路径4: Kotlin协程撤回阻断 — bd0.s.invokeSuspend() ⭐新增 =====

    private static void hookKotlinRevoke(ClassLoader cl) {
        try {
            Class<?> bd0s = VersionCompat.findBd0SClass(cl);
            if (bd0s == null) return;
            XposedBridge.hookAllMethods(bd0s, "invokeSuspend", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!sEnabled) return;
                    param.setResult(null);
                    LogWriter.log(TAG, "[协程] 阻止撤回成功: bd0.s.invokeSuspend()");
                }
            });
            LogWriter.log(TAG, "[协程] Hooked: bd0.s.invokeSuspend()");
        } catch (XposedHelpers.ClassNotFoundError ignored) {
            LogWriter.log(TAG, "[协程] bd0.s 未找到 (可能版本不支持)");
        } catch (Throwable t) {
            LogWriter.log(TAG, "[协程] bd0.s err: " + t.getMessage());
        }
    }
}
