package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Method;
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

    // ── DexKit 锚点（文档 13.1，均为日志/XML 常量，跨版本相对稳定）──
    private static final String A_H1_DOREVOKE = "doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s";
    private static final String A_H1_DOREVOKE2 =
            "doRevokeMsg revokeFlag=%d msgId=%s talker=%s type=%d revokeMsgSvrId=%d";
    private static final String A_H3_CLIMSGID = ".sysmsg.revoke_climsgid";
    private static final String A_H3_BIZ_TAG = "MicroMsg.BizChatSysCmdMsgConsumerHandleRevokeMsg";
    private static final String A_H2_GROUP = "summerbadcr updateConv chatRoomId";
    private static final String A_H4_CGI = "/cgi-bin/micromsg-bin/revokemsg";

    private static volatile boolean sEnabled = true;
    private static volatile boolean sBiz = true;
    private static volatile boolean sSelf = false;
    private static volatile boolean sChatroomHist = false;
    private static volatile boolean sHint = true;
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
        sEnabled = prefs.getBoolean(K_MASTER, true);
        sBiz = prefs.getBoolean(K_BIZ, true);
        sSelf = prefs.getBoolean(K_SELF, false);
        sChatroomHist = prefs.getBoolean(K_CHATROOM_HIST, false);
        sHint = prefs.getBoolean(K_HINT, true);
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
                        if (!sHint) return;
                        try {
                            String talker = param.args != null && param.args.length > 0
                                    ? String.valueOf(param.args[0]) : null;
                            String replacemsg = param.args != null && param.args.length > 3
                                    ? (String) param.args[3] : null;
                            insertRevokeHint(loader, talker, replacemsg);
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "[H1] hint err: " + t);
                        }
                    }
                });
    }

    /** 命中拦截后的附加动作（如插入提示行）；在 setResult 之前调用。 */
    private interface BlockAction {
        void onBlocked(XC_MethodHook.MethodHookParam param, ClassLoader cl);
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
        hookExact(cl, cls, "J", 4, null, "H4", false);
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
                        // 先拦截：保证任何后续附加动作都不会影响防撤回本身
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

    // ==================== 原生提示行插入 ====================

    /**
     * 由服务端 replacemsg 生成「撤回失败」提示文案：
     * {@code "XXX撤回了一条消息"} → {@code "XXX消息撤回失败"}。
     * 发送者名字取自 replacemsg 前缀（微信已算好的显示名），模块不硬编码人名。
     */
    private static String buildFailHint(String replacemsg) {
        String s = replacemsg == null ? "" : replacemsg.trim();
        if (s.isEmpty()) return "";
        int i = s.lastIndexOf("撤回");
        if (i > 0) {
            String name = s.substring(0, i).trim();
            if (!name.isEmpty()) return name + "消息撤回失败";
        }
        return "消息撤回失败";
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
            Object f9 = getMsgStorage(cl);
            if (f9 == null) {
                LogWriter.log(TAG, "[H1] hint: MsgInfoStorage 不可用");
                return;
            }
            Class<?> e9 = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9 == null) {
                LogWriter.log(TAG, "[H1] hint: e9 类未定位");
                return;
            }
            // 构造 e9（8.0.78 3180 实证 setter）
            Object msg = XposedHelpers.newInstance(e9);
            callSafe(msg, "u1", talker);                      // field_talker
            callSafe(msg, "b1", text);                        // field_content
            callSafe(msg, "setType", 10000);                  // 通用系统提示
            callSafe(msg, "k1", 0);                           // isSend = 0
            callSafe(msg, "e1", System.currentTimeMillis());  // createTime
            callSafe(msg, "r1", "");                          // 清 msgSource
            callSafe(msg, "r3", "");

            // 1) 入库（false = INSERT），返回新 msgId
            long id = 0L;
            try {
                Object ret = XposedHelpers.callMethod(f9, "Bb", msg, false);
                if (ret instanceof Number) id = ((Number) ret).longValue();
            } catch (Throwable t) {
                LogWriter.log(TAG, "[H1] hint Bb err: " + t);
            }
            if (id > 0) {
                // 2) 通知 UI 刷新：Qc = 落库 + 通知
                try {
                    XposedHelpers.callMethod(f9, "Qc", id, msg, true);
                } catch (Throwable t) {
                    LogWriter.log(TAG, "[H1] hint Qc notify err: " + t);
                }
            } else {
                // 3) Bb 未取到 id 时，尝试直接用 Qc 插入（带通知）
                try {
                    Object r = XposedHelpers.callMethod(f9, "Qc", 0L, msg, true);
                    if (r instanceof Number) id = ((Number) r).longValue();
                } catch (Throwable t) {
                    LogWriter.log(TAG, "[H1] hint Qc insert err: " + t);
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
        } catch (Throwable ignored) {}
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
                "总开关：单聊 + 群聊主路径，必选", prefs.getBoolean(K_MASTER, true), K_MASTER, prefs));
        card.addView(switchItem(ctx, "\uD83C\uDFE2", "商务号/企业微信撤回",
                "qy_revoke_msg，默认开", prefs.getBoolean(K_BIZ, true), K_BIZ, prefs));
        card.addView(switchItem(ctx, "\uD83D\uDCAC", "保留撤回失败提示",
                "拦截后插入「对方消息撤回失败」提示行（原消息仍在）",
                prefs.getBoolean(K_HINT, true), K_HINT, prefs));
        card.addView(switchItem(ctx, "\u21A9", "自己撤回也失效",
                "本地不再改写（服务端仍会撤）", prefs.getBoolean(K_SELF, false), K_SELF, prefs));
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
        sw.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(key, on).apply());
        return com.leshao.v3.ui.widgets.M3Page.tailRow(ctx, icon, title, desc, sw);
    }
}
