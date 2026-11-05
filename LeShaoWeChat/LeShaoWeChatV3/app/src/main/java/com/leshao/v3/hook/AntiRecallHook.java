package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 消息防撤回 —— 严格实现文档《WeChat_AntiRevoke_Reverse.md》。
 *
 * <p>原理：微信撤回 = 服务端指令 + 客户端本地把原消息改写成系统提示。
 * 只要在「改写」入口 {@code b41.t.c}（doRevokeMsg）直接 return，原消息 type/content
 * 不变，UI 自然继续渲染原消息，即防撤回。提示语保留服务端下发的 replacemsg，模块零文案。</p>
 *
 * <p>Hook 点（文档 10.2 / 13.1，全部走 DexKit 字符串锚点动态定位，不写死混淆名）：</p>
 * <ul>
 *   <li>H1 接收方撤回总入口 doRevokeMsg —— 必选，单聊+群聊主路径</li>
 *   <li>H3 商务号/企业微信撤回 qy_revoke_msg —— 默认开</li>
 *   <li>H2 群聊 getcrmsg 历史路径 —— 默认关（会整体跳过 seq 维护）</li>
 *   <li>H4 自己撤回也失效 d1.J —— 默认关</li>
 * </ul>
 *
 * <p>开关与配置入口均在「联系人和群聊」菜单内（见 ContactGroupPageView）。</p>
 */
public class AntiRecallHook {

    private static final String TAG = "AntiRecallHook";

    // ── 配置键（对应文档 11.4 的 5 个开关）──
    /** BLOCK_RECV_REVOKE：接收方撤回总开关。 */
    public static final String K_MASTER = "anti_revoke";
    /** BLOCK_BIZ_REVOKE：商务号撤回。 */
    public static final String K_BIZ = "anti_revoke_biz";
    /** BLOCK_SELF_REVOKE：自己撤回也失效。 */
    public static final String K_SELF = "anti_revoke_self";
    /** BLOCK_CHATROOM_PATH：群聊 getcrmsg 历史路径。 */
    public static final String K_CHATROOM_HIST = "anti_revoke_chatroom_hist";
    /** SHOW_REVOKE_HINT：拦截后在会话里插入一条原生提示行（保留原消息不变）。 */
    public static final String K_HINT = "anti_revoke_hint";
    /** HINT_TEXT：撤回提示自定义文字模板，{name} 会被替换为发送者昵称。 */
    public static final String K_HINT_TEXT = "anti_revoke_hint_text";

    // ── DexKit 锚点（文档 13.1，均为日志/XML 常量，跨版本相对稳定）──
    private static final String A_H1_DOREVOKE = "doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s";
    private static final String A_H1_DOREVOKE2 =
            "doRevokeMsg revokeFlag=%d msgId=%s talker=%s type=%d revokeMsgSvrId=%d";
    private static final String A_H3_CLIMSGID = ".sysmsg.revoke_climsgid";
    private static final String A_H3_BIZ_TAG = "MicroMsg.BizChatSysCmdMsgConsumerHandleRevokeMsg";
    private static final String A_H2_GROUP = "summerbadcr updateConv chatRoomId";
    private static final String A_H4_CGI = "/cgi-bin/micromsg-bin/revokemsg";

    private static volatile boolean sEnabled = false;
    private static volatile boolean sBiz = false;
    private static volatile boolean sSelf = false;
    private static volatile boolean sChatroomHist = false;
    private static volatile boolean sHint = false;
    private static volatile boolean sHooked = false;
    private static volatile ClassLoader sCl;
    private static volatile Object sMsgStorage;
    /** 提示行插入专用后台线程，避免阻塞微信撤回处理线程。 */
    private static final java.util.concurrent.ExecutorService sExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private AntiRecallHook() {}

    public static boolean isEnabled() { return sEnabled; }

    /** 从 prefs 读取四个开关（缺省值遵循文档 11.4）。 */
    public static void updateConfig(SharedPreferences prefs) {
        if (prefs == null) return;
        sEnabled = prefs.getBoolean(K_MASTER, false);
        sBiz = prefs.getBoolean(K_BIZ, false);
        sSelf = prefs.getBoolean(K_SELF, false);
        sChatroomHist = prefs.getBoolean(K_CHATROOM_HIST, false);
        sHint = prefs.getBoolean(K_HINT, false);
        // v3.0.549: 「自己撤回也失效」存在严重缺陷（拦截 onGYNetEnd 会卡转圈/产生假消息），
        // 强制关闭并迁移旧配置（一次性），自己撤回恢复正常微信流程。
        boolean selfPref = prefs.getBoolean(K_SELF, false);
        if (selfPref) {
            prefs.edit().putBoolean(K_SELF, false).apply();
            LogWriter.log(TAG, "已迁移关闭「自己撤回也失效」（该功能存在缺陷，避免卡转圈/假消息）");
        }
        sSelf = false;
    }

    /**
     * 主开关（兼容旧 API）。开启后若已拿到 ClassLoader 则尽力即时安装 hook；
     * 关闭仅置位、不卸载（重启微信后生效）。
     */
    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
        SharedPreferences prefs = ContextManager.getPrefs();
        if (prefs != null) prefs.edit().putBoolean(K_MASTER, enabled).apply();
        LogWriter.log(TAG, "setEnabled=" + enabled);
        if (enabled) {
            ClassLoader cl = sCl != null ? sCl : ContextManager.getClassLoader();
            if (cl != null) tryInstall(cl);
        }
    }

    /** hook 入口：应在 DexKit 可用、onReady 阶段调用，由 MainHook 注册。 */
    public static void hook(ClassLoader cl) {
        sCl = cl;
        updateConfig(ContextManager.getPrefs());
        if (!sEnabled) {
            LogWriter.log(TAG, "hook: master disabled, skip");
            return;
        }
        tryInstall(cl);
    }

    private static synchronized void tryInstall(ClassLoader cl) {
        if (cl == null) return;
        if (sHooked) return;
        sHooked = true;
        LogWriter.log(TAG, "install... enabled=" + sEnabled + " biz=" + sBiz
                + " self=" + sSelf + " chatroomHist=" + sChatroomHist + " hint=" + sHint);

        // H1/H3/H2/H4 均需同步 DexKit 全量搜索(数秒), 放到后台线程执行,
        // 避免阻塞 HookManager.activateAll 线程导致后续任务饿死。
        sExecutor.execute(() -> {
            // H1 接收方撤回总入口（单聊 + 群聊主路径）★必选
            hookReceivRevoke(cl);
            // H3 商务号/企业微信撤回
            if (sBiz) hookBizRevoke(cl);
            // H2 群聊 getcrmsg 历史路径（默认关）
            if (sChatroomHist) hookChatroomHistory(cl);
            // H4 自己撤回也失效（默认关）
            if (sSelf) hookSelfRevoke(cl);

            LogWriter.log(TAG, "install done");
        });
    }

    // ==================== H1：接收方撤回总入口 ====================

    private static void hookReceivRevoke(ClassLoader cl) {
        hookByMethodString(cl,
                new String[]{A_H1_DOREVOKE, A_H1_DOREVOKE2},
                6, null, "H1", true, new BlockAction() {
                    @Override public void onBlocked(XC_MethodHook.MethodHookParam param, ClassLoader loader) {
                        // H1 采用「放行微信生成原生系统提示」方案：
                        // before 深拷贝原消息（保存防撤回副本），不拦截，让微信正常生成系统提示并刷新 UI。
                        if (!sHint) return;
                        try {
                            beforeRevokeCopy(param, loader);
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "[H1] copy err: " + t);
                        }
                    }
                    @Override public void afterHooked(XC_MethodHook.MethodHookParam param, ClassLoader loader) {
                        // after：插入原消息副本（防撤回）+ 修改微信生成的系统提示文字为自定义文案
                        if (!sHint) return;
                        try {
                            afterRevokeRestore(param, loader);
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "[H1] restore err: " + t);
                        }
                    }
                });
    }

    /** 命中拦截后的附加动作（如插入提示行）；在 setResult 之前调用。
     *  v3.0.555: H1 改为「放行微信生成原生提示 + after 恢复原消息副本」，afterHooked 在方法返回后调用。 */
    private interface BlockAction {
        void onBlocked(XC_MethodHook.MethodHookParam param, ClassLoader cl);
        default void afterHooked(XC_MethodHook.MethodHookParam param, ClassLoader cl) {}
    }

    // ==================== H3：商务号撤回 ====================

    private static void hookBizRevoke(ClassLoader cl) {
        if (hookByMethodString(cl, new String[]{A_H3_CLIMSGID}, 3, null, "H3", false)) return;
        // 退路：按 TAG 字符串定位到类，再 hook 其 a(String, Map, p0)
        String cls = firstClassByStrings(cl, A_H3_BIZ_TAG);
        if (cls != null) hookExact(cl, cls, "a", 3, null, "H3", false);
    }

    // ==================== H2：群聊 getcrmsg 历史路径 ====================

    private static void hookChatroomHistory(ClassLoader cl) {
        hookByMethodString(cl, new String[]{A_H2_GROUP}, 0, null, "H2", false);
    }

    // ==================== H4：自己撤回也失效 ====================

    private static void hookSelfRevoke(ClassLoader cl) {
        String cls = firstClassByStrings(cl, A_H4_CGI);
        if (cls == null) {
            LogWriter.log(TAG, "[WARN] H4: class not resolved by '" + A_H4_CGI + "'");
            return;
        }
        // v3.0.548: 不再拦截 d1.J（只拦内容改写，微信后续 k1(0) 翻转 isSend 会产生"对方假消息"），
        // 改为整体拦截 onGYNetEnd 成功分支（errType==0 && errCode==0），使 J/k1(0)/v3/Qc 全部不执行，
        // 本地消息保持"自己发出的原消息"（isSend=1、原内容、原 type），不会出现对方视角的假消息。
        // onGYNetEnd 是微信网络框架的标准回调名（接口方法不混淆，跨版本稳定），参数个数不写死，回调内判断成功分支。
        hookExact(cl, cls, "onGYNetEnd", -1, null, "H4", false);
    }

    // ==================== 通用定位与安装 ====================

    /**
     * 用字符串锚点（DexKit）定位方法：遍历锚点，取首个参数个数匹配的方法签名并安装。
     * 解析形如 {@code com.a.b.c(String,long,com.x.p0,...)} 的签名，得到类名与方法名。
     */
    private static boolean hookByMethodString(ClassLoader cl, String[] anchors, int paramCount,
                                              Object blockResult, String tag, boolean logArgs) {
        return hookByMethodString(cl, anchors, paramCount, blockResult, tag, logArgs, null);
    }

    private static boolean hookByMethodString(ClassLoader cl, String[] anchors, int paramCount,
                                              Object blockResult, String tag, boolean logArgs,
                                              BlockAction action) {
        for (String anchor : anchors) {
            try {
                List<String> sigs = DexKitHelper.findMethodsByString(cl, null, anchor);
                if (sigs == null || sigs.isEmpty()) continue;
                for (String sig : sigs) {
                    int p = sig.indexOf('(');
                    if (p <= 0) continue;
                    String decl = sig.substring(0, p);
                    int dot = decl.lastIndexOf('.');
                    if (dot <= 0) continue;
                    String clsName = decl.substring(0, dot);
                    String mName = decl.substring(dot + 1);
                    String params = sig.substring(p + 1, sig.length() - 1);
                    int pc = params.isEmpty() ? 0 : params.split(",").length;
                    if (paramCount >= 0 && pc != paramCount) continue;
                    if (hookExact(cl, clsName, mName, paramCount, blockResult, tag, logArgs, action)) {
                        LogWriter.log(TAG, "[" + tag + "] resolved via '" + anchor + "'");
                        return true;
                    }
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "[" + tag + "] anchor err: " + t);
            }
        }
        LogWriter.log(TAG, "[WARN] " + tag + ": anchor not resolved");
        return false;
    }

    /**
     * 在指定类中按「方法名 + 参数个数」精确定位并 hook，入口 setResult 拦截。
     *
     * <p>关键：微信经 Tinker 热修复运行时，同名类会被加载出多份平行副本
     * （base.apk 的 PathClassLoader 副本 vs patch-7eb9a009 的 DelegateLastClassLoader 副本），
     * 运行时只调用其中一份。若只挂 base 副本则「装上但零捕获」。因此这里对
     * 所有可解析到的 ClassLoader 逐一份获取并 hook 同名类，确保命中真实运行类。</p>
     */
    private static boolean hookExact(ClassLoader cl, String clsName, String methodName, int paramCount,
                                     Object blockResult, String tag, boolean logArgs) {
        return hookExact(cl, clsName, methodName, paramCount, blockResult, tag, logArgs, null);
    }

    private static boolean hookExact(ClassLoader cl, String clsName, String methodName, int paramCount,
                                     Object blockResult, String tag, boolean logArgs, BlockAction action) {
        boolean ok = false;
        Set<Class<?>> seen = new HashSet<>();
        for (ClassLoader loader : candidateLoaders(cl)) {
            Class<?> c;
            try {
                c = loader.loadClass(clsName);
            } catch (Throwable ignored) {
                continue;
            }
            if (c == null || !seen.add(c)) continue;
            ok |= installOn(c, methodName, paramCount, blockResult, tag, logArgs, loader, action);
        }
        if (!ok) {
            LogWriter.log(TAG, "[WARN] " + tag + ": " + clsName + "." + methodName
                    + "(" + paramCount + ") not found in any loader");
        }
        return ok;
    }

    /** 在单个 Class 上安装 hook（可能命中多个同名重载）。 */
    private static boolean installOn(Class<?> c, String methodName, int paramCount,
                                     Object blockResult, String tag, boolean logArgs,
                                     ClassLoader loader, BlockAction action) {
        boolean ok = false;
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (paramCount >= 0 && m.getParameterCount() != paramCount) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (logArgs && param.args != null && param.args.length >= 4) {
                                LogWriter.log(TAG, "[" + tag + "] BLOCKED talker=" + param.args[0]
                                        + " svrId=" + param.args[1]
                                        + " replacemsg=" + param.args[3]);
                            } else {
                                LogWriter.log(TAG, "[" + tag + "] BLOCKED");
                            }
                        } catch (Throwable ignored) {}
                        // ★ 自己撤回不拦截：微信对自己撤回下发 "你撤回了一条消息"，
                        // 若拦截会导致自己撤回失败并在聊天里出现「撤回失败」假消息。
                        if ("H1".equals(tag) && param.args != null && param.args.length > 3
                                && (param.args[3] instanceof String)
                                && String.valueOf(param.args[3]).startsWith("你撤回")) {
                            LogWriter.log(TAG, "[" + tag + "] 自己撤回，跳过拦截");
                            return;
                        }
                        // H4（自己撤回网络回调）：仅在成功分支（errType==0 && errCode==0）整体拦截，
                        // 防止微信继续 k1(0) 翻转 isSend 产生"对方假消息"；失败分支放行。
                        if ("H4".equals(tag)) {
                            if (param.args == null || param.args.length < 2
                                    || !(param.args[0] instanceof Integer)
                                    || !(param.args[1] instanceof Integer)
                                    || ((Integer) param.args[0]) != 0
                                    || ((Integer) param.args[1]) != 0) {
                                return;
                            }
                        } else {
                            // 参数校验仅对 H1（6 参）生效：只要求 replacemsg 存在（args[3] 是 String）；
                            // args[0] 可能是 xmlSrvMsgId(long)/revokeFlag(int)，不能要求它是 String，否则防撤回主路径失效。
                            // H3（3 参）/H2（0 参）参数结构不同，不做该校验。
                            if (("H1".equals(tag))
                                    && (param.args == null || param.args.length <= 3
                                        || !(param.args[3] instanceof String))) {
                                return;
                            }
                        }
                        // v3.0.555: H1 采用「放行微信生成原生提示」：不 setResult，让微信完成系统提示生成与 UI 刷新；
                        // before 中仅深拷贝原消息（防撤回副本），after 中恢复原消息 + 修改提示文字。
                        if ("H1".equals(tag) && action != null) {
                            try {
                                action.onBlocked(param, loader);
                            } catch (Throwable ignored) {}
                            return;
                        }
                        // 其他路径（H3/H2/H4）：先拦截，保证任何后续附加动作都不会影响防撤回本身
                        param.setResult(blockResult);
                        // 再异步执行附加动作（插入提示行）。同步入库会占用撤回处理线程、
                        // 可能与微信持有的消息存储锁相互等待，导致 before 钩子迟迟不返回。
                        if (action != null) {
                            final BlockAction act = action;
                            final XC_MethodHook.MethodHookParam p = param;
                            final ClassLoader ld = loader;
                            sExecutor.execute(() -> {
                                try {
                                    // 让撤回复写流程先收尾，避免与其争抢消息存储锁
                                    Thread.sleep(250L);
                                    act.onBlocked(p, ld);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                } catch (Throwable t) {
                                    LogWriter.log(TAG, "block action err: " + t);
                                }
                            });
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (action != null) {
                            try {
                                action.afterHooked(param, loader);
                            } catch (Throwable ignored) {}
                        }
                    }
                });
                ok = true;
                LogWriter.log(TAG, "[" + tag + "] hooked " + c.getName() + "."
                        + m.getName() + "(" + m.getParameterCount() + ") via " + loaderName(loader));
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "[" + tag + "] hook err: " + t);
        }
        return ok;
    }

    /**
     * 候选 ClassLoader（去重，顺序即优先级）：
     * 1) VersionCompat 反查到的微信真实 CL / Tinker DelegateLastClassLoader（运行时实际生效者）
     * 2) ContextManager 缓存的 Tinker CL
     * 3) 传入的 LPPosed cl（base.apk 平行副本）
     * 4) 微信 AppContext 的 CL
     */
    private static Set<ClassLoader> candidateLoaders(ClassLoader cl) {
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        try {
            ClassLoader tk = VersionCompat.findTinkerClassLoader(cl);
            if (tk != null) loaders.add(tk);
        } catch (Throwable ignored) {}
        try {
            ClassLoader cm = ContextManager.getTinkerClassLoader();
            if (cm != null) loaders.add(cm);
        } catch (Throwable ignored) {}
        if (cl != null) loaders.add(cl);
        try {
            ClassLoader app = ContextManager.getClassLoader();
            if (app != null) loaders.add(app);
        } catch (Throwable ignored) {}
        return loaders;
    }

    private static String loaderName(ClassLoader l) {
        return l == null ? "null" : l.getClass().getSimpleName();
    }

    /** 按字符串锚点定位首个候选类名（沿用 WeChatUpdateBlocker 的通用做法）。 */
    private static String firstClassByStrings(ClassLoader cl, String... anchors) {
        for (String kw : anchors) {
            try {
                List<String> cands = DexKitHelper.findClassesByString(cl, kw);
                if (cands != null && !cands.isEmpty()) {
                    LogWriter.log(TAG, "class hit: " + cands.get(0) + " via '" + kw + "'");
                    return cands.get(0);
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ==================== H1 放行方案：深拷贝 + 恢复原消息 + 修改提示文字 ====================

    /** before/after 之间传递 [副本, 是否自己撤回] 的线程局部上下文（before 与 after 同线程）。 */
    private static final ThreadLocal<Object[]> sHookCtx = new ThreadLocal<>();

    /** 统一取真实运行 CL（Tinker 优先）。 */
    private static ClassLoader tinkerCl(ClassLoader cl) {
        try {
            ClassLoader tk = VersionCompat.findTinkerClassLoader(cl);
            if (tk != null) return tk;
        } catch (Throwable ignored) {}
        return cl;
    }

    /** before：深拷贝原消息（防撤回副本），自我撤回放行。 */
    private static void beforeRevokeCopy(XC_MethodHook.MethodHookParam param, ClassLoader cl) {
        if (param.args == null || param.args.length < 4) return;
        String talker = param.args[0] instanceof String ? (String) param.args[0] : null;
        Object svrIdObj = param.args[1];
        long svrId = svrIdObj instanceof Number ? ((Number) svrIdObj).longValue() : 0L;
        String replacemsg = param.args[3] instanceof String ? (String) param.args[3] : null;
        // 自己撤回：完全放行（微信正常流程），after 不处理
        if (replacemsg != null && replacemsg.startsWith("你撤回")) {
            sHookCtx.set(new Object[]{null, Boolean.TRUE});
            return;
        }
        try {
            ClassLoader realCl = tinkerCl(cl);
            Object f9 = getMsgStorage(realCl);
            if (f9 == null) return;
            // v3.0.579: 8.0.79 实证 f9 无 O3()；按 svrId 取消息 = k4(String talker, long svrId)
            Object original = getBySvrId(f9, talker, svrId);
            if (original == null) return;
            Object copy = deepCopyMsg(original);
            sHookCtx.set(new Object[]{copy, Boolean.FALSE});
        } catch (Throwable t) {
            LogWriter.log(TAG, "[H1] beforeRevokeCopy err: " + t);
            sHookCtx.set(new Object[]{null, Boolean.FALSE});
        }
    }

    /** 深拷贝 e9 消息：反射复制所有字段（含父类），清空 msgId 让微信分配新 id。 */
    private static Object deepCopyMsg(Object src) {
        try {
            Object dst = XposedHelpers.newInstance(src.getClass());
            for (Class<?> cur = src.getClass(); cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                for (Field f : cur.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        f.set(dst, f.get(src));
                    } catch (Throwable ignored) {}
                }
            }
            try { XposedHelpers.callMethod(dst, "setMsgId", 0L); } catch (Throwable ignored) {}
            try { XposedHelpers.setObjectField(dst, "field_msgId", 0L); } catch (Throwable ignored) {}
            return dst;
        } catch (Throwable t) {
            LogWriter.log(TAG, "[H1] deepCopyMsg err: " + t);
            return null;
        }
    }

    /** after：插入原消息副本（防撤回）+ 修改微信生成的系统提示文字为自定义文案。 */
    private static void afterRevokeRestore(XC_MethodHook.MethodHookParam param, ClassLoader cl) {
        if (param.args == null || param.args.length < 4) return;
        Object[] ctx = sHookCtx.get();
        sHookCtx.remove();
        if (ctx == null || Boolean.TRUE.equals(ctx[1])) return;
        String talker = param.args[0] instanceof String ? (String) param.args[0] : null;
        Object svrIdObj = param.args[1];
        long svrId = svrIdObj instanceof Number ? ((Number) svrIdObj).longValue() : 0L;
        String replacemsg = param.args[3] instanceof String ? (String) param.args[3] : null;
        if (talker == null || replacemsg == null) return;
        try {
            ClassLoader realCl = tinkerCl(cl);
            Object f9 = getMsgStorage(realCl);
            if (f9 == null) return;

            // 1) 插入原消息副本（防撤回：原消息恢复显示）
            Object copy = ctx[0];
            if (copy != null) {
                try {
                    Object newIdObj = XposedHelpers.callMethod(f9, "Nb", copy, false);
                    long newId = newIdObj instanceof Number ? ((Number) newIdObj).longValue() : 0L;
                    if (newId > 0) {
                        // v3.0.579: 8.0.79 实证无 Qc()；UPDATE 落库+通知 = ud(long,e9,boolean)
                        updateAndNotify(f9, newId, copy);
                    }
                } catch (Throwable t) {
                    LogWriter.log(TAG, "[H1] restore insert copy err: " + t);
                }
            }

            // 2) 修改微信生成的系统提示文字（微信已把原消息改写成 type=268445456/285222674 提示）
            try {
                Object cur = getBySvrId(f9, talker, svrId);
                if (cur != null) {
                    String custom = buildFailHint(replacemsg);
                    // v3.0.579: 8.0.79 实证 setContent = Z0(String)（b1 全版本不存在）
                    try { XposedHelpers.callMethod(cur, "Z0", custom); } catch (Throwable ignored) {}
                    try { XposedHelpers.setObjectField(cur, "field_content", custom); } catch (Throwable ignored) {}
                    long mid = 0L;
                    try { mid = ((Long) XposedHelpers.callMethod(cur, "getMsgId")).longValue(); } catch (Throwable ignored) {}
                    if (mid > 0) updateAndNotify(f9, mid, cur);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            LogWriter.log(TAG, "[H1] afterRevokeRestore err: " + t);
        }
    }

    // ==================== 原生提示行插入 ====================

    /**
     * 生成撤回提示文案：由服务端 replacemsg 提取发送者昵称，
     * 用自定义模板（K_HINT_TEXT）替换 {@code {name}}；未配置时使用默认文案
     * {@code "{name}撤回了一条消息（已防撤回保留）"}。
     */
    private static String buildFailHint(String replacemsg) {
        SharedPreferences prefs = ContextManager.getPrefs();
        String custom = prefs == null ? "" : prefs.getString(K_HINT_TEXT, "");
        String s = replacemsg == null ? "" : replacemsg.trim();
        String name = "";
        int i = s.lastIndexOf("撤回");
        if (i > 0) {
            String n = s.substring(0, i).trim();
            if (!n.isEmpty()) name = n;
        }
        String tmpl;
        if (custom != null && !custom.trim().isEmpty()) {
            tmpl = custom.trim();
        } else {
            tmpl = "{name}撤回了一条消息（已防撤回保留）";
        }
        return tmpl.replace("{name}", name.isEmpty() ? "对方" : name);
    }

    /**
     * 拦截撤回后，在会话里插入一条系统提示行（type=10000），文案为
     * {@code "<发送者>消息撤回失败"}，同时保留被撤回的原消息不变 —— 即「失败提示 × 防撤回」并存。
     */
    private static void insertRevokeHint(ClassLoader cl, String talker, String replacemsg) {
        if (talker == null || talker.isEmpty() || replacemsg == null || replacemsg.isEmpty()) return;
        final String text = buildFailHint(replacemsg);
        if (text.isEmpty()) return;
        try {
            // 统一用真实运行 CL（Tinker 优先），与 WeChatMessenger.sendViaMsgInfo 保持一致
            ClassLoader realCl = cl;
            try {
                ClassLoader tk = VersionCompat.findTinkerClassLoader(cl);
                if (tk != null) realCl = tk;
            } catch (Throwable ignored) {}
            Object f9 = getMsgStorage(realCl);
            if (f9 == null) {
                LogWriter.log(TAG, "[H1] hint: MsgInfoStorage 不可用");
                return;
            }
            Class<?> e9 = VersionCompat.findMsgInfoStorageClass(realCl);
            if (e9 == null) {
                LogWriter.log(TAG, "[H1] hint: e9 类未定位");
                return;
            }
            // 构造 e9：优先带参构造（talker 作为构造参数），失败回退无参构造 + 字段设置。
            // 8.0.79 中 b1/k1/e1/t1/r1/r3 setter 已改名（NoSuchMethodError），
            // 改用 field_* 字段直接设置（field_talker/field_content/field_isSend/field_createTime/field_status 实证存在）。
            Object msg;
            try {
                msg = XposedHelpers.newInstance(e9, talker);
            } catch (Throwable t1) {
                msg = XposedHelpers.newInstance(e9);
                try { XposedHelpers.setObjectField(msg, "field_talker", talker); } catch (Throwable ignored) {}
            }
            try { XposedHelpers.callMethod(msg, "setType", 10000); } catch (Throwable ignored) {}
            try { XposedHelpers.setObjectField(msg, "field_type", 10000); } catch (Throwable ignored) {}
            try { XposedHelpers.setObjectField(msg, "field_content", text); } catch (Throwable ignored) {}
            try { XposedHelpers.setIntField(msg, "field_isSend", 0); } catch (Throwable ignored) {}
            try { XposedHelpers.setObjectField(msg, "field_createTime", System.currentTimeMillis()); } catch (Throwable ignored) {}
            try { XposedHelpers.setIntField(msg, "field_status", 1); } catch (Throwable ignored) {}
            try { XposedHelpers.setObjectField(msg, "field_msgSource", ""); } catch (Throwable ignored) {}
            try { XposedHelpers.setObjectField(msg, "field_msgSeq", 0L); } catch (Throwable ignored) {}

            // 构造后 read-back 验证关键字段（type/talker/isSend/content），任一失败则放弃入库，
            // 避免产生 type=0 / talker 为空 / content 为空的脏消息（日志曾出现 b1 setter 失败导致文字为空）。
            try {
                Object typeVal = XposedHelpers.callMethod(msg, "getType");
                Object talkerVal = XposedHelpers.callMethod(msg, "N0");
                Object isSendVal = XposedHelpers.callMethod(msg, "z0");
                // v3.0.579: 8.0.79 实证 getContent = F0()（S1 全版本不存在）
                Object contentVal = null;
                try { contentVal = XposedHelpers.callMethod(msg, "F0"); } catch (Throwable ignored) {}
                if (contentVal == null) {
                    try { contentVal = XposedHelpers.getObjectField(msg, "field_content"); } catch (Throwable ignored) {}
                }
                if (!(typeVal instanceof Integer) || ((Integer) typeVal) != 10000
                        || !(talkerVal instanceof String) || !talker.equals(talkerVal)
                        || !(isSendVal instanceof Integer) || ((Integer) isSendVal) != 0
                        || !(contentVal instanceof String) || !text.equals(contentVal)) {
                    LogWriter.log(TAG, "[H1] hint: read-back 验证失败 type=" + typeVal
                            + " talker=" + talkerVal + " isSend=" + isSendVal
                            + " content=" + contentVal + "，放弃插入提示行");
                    return;
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "[H1] hint: read-back 异常，放弃插入提示行: " + t);
                return;
            }

            // 1) 入库（false = INSERT），返回新 msgId
            // v3.0.469: 旧 Bb(e9,boolean) 已改名 Nb(e9,boolean)->long（失败串 "MsgInfo processAddMsg insert db error"）
            long id = 0L;
            try {
                Object ret = XposedHelpers.callMethod(f9, "Nb", msg, false);
                if (ret instanceof Number) id = ((Number) ret).longValue();
            } catch (Throwable t) {
                LogWriter.log(TAG, "[H1] hint Nb err: " + t);
            }
            if (id > 0) {
                // 2) 通知 UI 刷新：新版 td(long,e9) = ud(long,e9,true) = 落库 + doNotify()
                try {
                    XposedHelpers.callMethod(f9, "td", id, msg);
                } catch (Throwable t) {
                    LogWriter.log(TAG, "[H1] hint td notify err: " + t);
                }
            }
            LogWriter.log(TAG, "[H1] hint inserted talker=" + talker + " id=" + id
                    + " text=" + text);
        } catch (Throwable t) {
            LogWriter.log(TAG, "[H1] hint fatal: " + t);
        }
    }

    private static void callSafe(Object target, String method, Object... args) {
        try {
            XposedHelpers.callMethod(target, method, args);
        } catch (Throwable t) {
            LogWriter.log(TAG, "[H1] hint setter " + method + " err: " + t);
        }
    }

    /** 获取 f9 MsgInfoStorage 实例：StorageHub 优先，失败回退 e01.d9.b().u()。 */
    private static Object getMsgStorage(ClassLoader cl) {
        if (sMsgStorage != null) return sMsgStorage;
        try {
            com.leshao.ai.hook.wechat.StorageHub hub = com.leshao.ai.hook.wechat.StorageHub.get();
            if (hub != null) {
                Object s = hub.msgInfoStorage();
                if (s != null) {
                    sMsgStorage = s;
                    return s;
                }
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> shortCls = VersionCompat.findMsgStorageShortClass(cl);
            if (shortCls != null) {
                Object service = XposedHelpers.callStaticMethod(shortCls, "b");
                if (service != null) {
                    Object s = XposedHelpers.callMethod(service, "u");
                    if (s != null) {
                        sMsgStorage = s;
                        return s;
                    }
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "getMsgStorage err: " + t);
        }
        return null;
    }

    /**
     * 按 msgSvrId 取消息（8.0.79 实证：f9.k4(String talker, long svrId) → e9）。
     * 旧实现用的 f9.O3(talker, svrId) 在 8.0.79 已不存在。
     */
    private static Object getBySvrId(Object f9, String talker, long svrId) {
        if (f9 == null || talker == null || svrId == 0L) return null;
        try {
            Object m = XposedHelpers.callMethod(f9, "k4", talker, svrId);
            if (m != null) return m;
        } catch (Throwable ignored) {}
        // 兜底：k4 不存在时按 msgId 查找（svrId 有时被上层当作 msgId 传入）
        try {
            Object m = XposedHelpers.callMethod(f9, "q8", String.valueOf(svrId));
            if (m != null) return m;
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * UPDATE 落库 + 通知 UI（8.0.79 实证：f9.ud(long msgId, e9 msg, boolean notify) → int）。
     * 旧实现用的 f9.Qc(msgId, e9, true) 在 8.0.79 已不存在。
     */
    private static void updateAndNotify(Object f9, long msgId, Object msg) {
        if (f9 == null || msgId <= 0L || msg == null) return;
        try {
            XposedHelpers.callMethod(f9, "ud", msgId, msg, true);
            return;
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.callMethod(f9, "td", msgId, msg);
        } catch (Throwable ignored) {}
    }

    // ==================== 配置弹窗（入口在「联系人和群聊」菜单） ====================

    public static void showConfigDialog(Context ctx) {
        if (ctx == null) return;
        SharedPreferences prefs = ContextManager.getPrefs();
        if (prefs == null) {
            Toast.makeText(ctx, "配置不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(com.leshao.v3.ui.CandyUi.dialogBg(ctx));
        root.setPadding((int) (12 * d), (int) (12 * d), (int) (12 * d), (int) (12 * d));
        com.leshao.v3.ui.InsetsUtil.clipRounded(root);

        ScrollView sv = new ScrollView(ctx);
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding((int) (4 * d), 0, (int) (4 * d), 0);

        content.addView(new com.leshao.v3.ui.widgets.SectionHeader(ctx, "消息防撤回",
                "拦截服务端撤回改写，原消息继续显示（保留微信原生提示）"));

        LinearLayout card = com.leshao.v3.ui.widgets.M3Page.card(ctx);
        card.addView(switchItem(ctx, "\uD83D\uDEE1", "接收方撤回（别人撤回我）",
                "总开关：单聊 + 群聊主路径，必选", prefs.getBoolean(K_MASTER, false), K_MASTER, prefs));
        card.addView(switchItem(ctx, "\uD83C\uDFE2", "商务号/企业微信撤回",
                "qy_revoke_msg，默认开", prefs.getBoolean(K_BIZ, false), K_BIZ, prefs));
        card.addView(switchItem(ctx, "\uD83D\uDCAC", "保留撤回失败提示",
                "拦截后插入系统提示行（原消息仍在）",
                prefs.getBoolean(K_HINT, false), K_HINT, prefs));
        final EditText hintText = new EditText(ctx);
        hintText.setText(prefs.getString(K_HINT_TEXT, ""));
        hintText.setHint("默认：{name}撤回了一条消息（已防撤回保留）");
        hintText.setHintTextColor(com.leshao.v3.ui.AppColors.onSurfaceVariant());
        hintText.setTextSize(13);
        hintText.setTextColor(com.leshao.v3.ui.AppColors.onSurface());
        hintText.setSingleLine(false);
        hintText.setMinLines(1);
        hintText.setMaxLines(2);
        hintText.setBackground(com.leshao.v3.ui.CandyUi.inputBg(ctx));
        hintText.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins((int) (10 * d), 0, (int) (10 * d), (int) (10 * d));
        card.addView(hintText, hintLp);
        TextView hintTip = new TextView(ctx);
        hintTip.setText("撤回提示自定义文字，{name} 自动替换为发送者昵称，留空使用默认文案");
        hintTip.setTextSize(11);
        hintTip.setTextColor(com.leshao.v3.ui.AppColors.onSurfaceVariant());
        hintTip.setPadding((int) (10 * d), 0, (int) (10 * d), (int) (10 * d));
        card.addView(hintTip);
        card.addView(switchItem(ctx, "\u21A9", "自己撤回也失效",
                "该功能存在缺陷已停用（避免卡转圈/假消息）", false, K_SELF, prefs));
        card.addView(switchItem(ctx, "\uD83D\uDDC2", "群聊历史防撤回",
                "getcrmsg 历史路径，可能影响群 seq，默认关",
                prefs.getBoolean(K_CHATROOM_HIST, false), K_CHATROOM_HIST, prefs));
        content.addView(card);

        TextView note = new TextView(ctx);
        note.setText("说明：配置在微信启动时安装 Hook，修改后请重启微信以完全生效。");
        note.setTextSize(12);
        note.setTextColor(com.leshao.v3.ui.AppColors.onSurfaceVariant());
        note.setPadding((int) (6 * d), (int) (10 * d), (int) (6 * d), (int) (4 * d));
        content.addView(note);

        sv.addView(content);

        int sheetW = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.9f);
        int maxContentH = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.72f);
        int innerW = Math.max(1, sheetW - (int) (24 * d) - (int) (8 * d));
        content.measure(
                View.MeasureSpec.makeMeasureSpec(innerW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(maxContentH, View.MeasureSpec.AT_MOST));
        int contentH = Math.min(content.getMeasuredHeight(), maxContentH);
        sv.setLayoutParams(new LinearLayout.LayoutParams(-1, contentH));
        root.addView(sv);

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        btnRow.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (4 * d));
        com.leshao.v3.ui.widgets.ModernButton btnCancel =
                new com.leshao.v3.ui.widgets.ModernButton(ctx, "取消",
                        com.leshao.v3.ui.widgets.ModernButton.STYLE_GHOST);
        com.leshao.v3.ui.widgets.ModernButton btnSave =
                new com.leshao.v3.ui.widgets.ModernButton(ctx, "保存",
                        com.leshao.v3.ui.widgets.ModernButton.STYLE_PRIMARY);
        btnRow.addView(btnCancel);
        View spacer = new View(ctx);
        spacer.setLayoutParams(new LinearLayout.LayoutParams((int) (12 * d), 1));
        btnRow.addView(spacer);
        btnRow.addView(btnSave);
        root.addView(btnRow);

        int theme = com.leshao.v3.ui.AppColors.isDarkMode()
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(ctx, theme)
                .setView(com.leshao.v3.ui.InsetsUtil.window(null, root, 0.9f, -1f))
                .setCancelable(true)
                .create();
        btnCancel.onClick(() -> dlg.dismiss());
        btnSave.onClick(() -> {
            String txt = hintText.getText() == null ? "" : hintText.getText().toString().trim();
            prefs.edit().putString(K_HINT_TEXT, txt).apply();
            updateConfig(prefs);
            Toast.makeText(ctx, "已保存，重启微信后完全生效", Toast.LENGTH_SHORT).show();
            dlg.dismiss();
        });
        com.leshao.v3.ui.InsetsUtil.centerAutoHeight(dlg, 0.9f);
        dlg.show();
        com.leshao.v3.ui.WindowLayer.track(dlg.getWindow());
    }

    /** 配置弹窗内的单行开关（即时写入 prefs）。 */
    private static View switchItem(Context ctx, String icon, String title, String desc,
                                   boolean checked, String key, SharedPreferences prefs) {
        Switch sw = com.leshao.v3.ui.CandyUi.newSwitch(ctx);
        sw.setChecked(checked);
        // 「自己撤回也失效」已停用（存在缺陷），只展示不可操作
        if (K_SELF.equals(key)) {
            sw.setEnabled(false);
        } else {
            sw.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(key, on).apply());
        }
        return com.leshao.v3.ui.widgets.M3Page.tailRow(ctx, icon, title, desc, sw);
    }
}
