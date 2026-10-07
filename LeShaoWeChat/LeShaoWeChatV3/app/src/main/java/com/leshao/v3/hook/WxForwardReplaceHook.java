package com.leshao.v3.hook;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.ContactPickerDialog;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 微信原生转发按钮替换 —— 聊天窗口长按消息「转发」改用模块联系人选择器。
 *
 * <p>严格按《wechat_forward_replace.md》v7 最终定稿：
 * <ul>
 *   <li>§12/§13 语音专项：语音转发绕过 {@code manager.t.a}（{@code bq.Q} case142 直连
 *       {@code MsgRetransmitUI}），因此统一走框架层 {@code startActivity}/{code startActivityForResult}
 *       按组件名 + 来源过滤（唯一能同时覆盖 t.a 路径与语音等直连路径的 Hook）；并 Hook 语音菜单
 *       构建 {@code bq.S} 注入「转发」项（itemId=142）。</li>
 *   <li>§19 v12 方案B（语音确认版）：①菜单注入保留（o0.a 里 c(tag.d(),142,0,微信title,icon)）；
 *       ②点击 142 让 {@code bq.Q} 原生拉起 {@code MsgRetransmitUI}→微信原生 {@code SelectConversationUI}
 *       （拦截器对已标记语音 msgId 放行，不用模块选择器）；③hook {@code MsgRetransmitUI.onActivityResult}
 *       读到 {@code Select_Conv_User} 后调 {@code VoiceForwardHook.forwardVoiceToTarget} 用模块内核
 *       发送语音，并 {@code setResult(null)} 阻止微信原生 g7/t7（防双发）。</li>
 *   <li>§11.3/§14.3 Design A：入口 {@code manager.t.a} hook before 记消息（不替换，让原生
 *       {@code MsgTransmitUI} 出现）；拦截 {@code ...transmit.SelectConversationUI}（caller 开头
 *       {@code MsgRetransmitUI} + req==0 + 带 {@code Retr_Msg_Type}）后取消原生选择器、弹
 *       模块 {@link ContactPickerDialog}。</li>
 *   <li>§11.2/§14.4 回灌：选择器确认后主线程调用 {@code onActivityResult(0, RESULT_OK, ret)}，
 *       ret 带 {@code Select_Conv_User}=逗号分隔 wxid，微信原生 {@code g7()}/{@code t7()} 全类型发送。</li>
 *   <li>§14.1/§14.4 路径D：{@code MsgTransmitUI} 无实例/已销毁时，用原转发 Intent（Retr_Msg_Id/talker/
 *       content/Type/scene_from=17）+ {@code Select_Conv_User} 重建直接发送（微信原生直发任意类型）；失败再退
 *       {@link #fallbackSelfSend} 模块自发送。</li>
 * </ul>
 */
public final class WxForwardReplaceHook {

    public static final String TAG = "WxFwdReplace";
    public static final String K_ENABLED = "ls_wx_forward_replace";

    private static final String CLS_T = "com.tencent.mm.ui.chatting.manager.t";
    private static final String CLS_MSG_RETRANSMIT = "com.tencent.mm.ui.transmit.MsgRetransmitUI";
    private static final String CLS_SELECT_CONV = "com.tencent.mm.ui.transmit.SelectConversationUI";
    private static final String CLS_VOICE_VIEW = "com.tencent.mm.ui.chatting.viewitems.bq";

    // 文本族转发菜单 itemId=142 / 资源 id（§13.1）：2131774067="转发" title, 2131822160=icon
    private static final int MENU_ID_FORWARD = 142;
    private static final int RES_TITLE_FORWARD = 2131774067;
    private static final int RES_ICON_FORWARD = 2131822160;
    private static final int MSG_TYPE_VOICE = 34;   // 语音消息类型（§15.3）
    private static final String CLS_MENU = "kj5.i4";
    private static final String CLS_MVVM = "com.tencent.mm.ui.mvvm.MvvmContactListUI";
    // 3180 组合选人服务：a10/a（CombineEntranceService）。cj(Activity,void??) 确认选择时把
    // 最终选定列表交给 CombineEntranceService；反编译实证来源 207-2076。
    private static final String CLS_COMBINE = "com.tencent.mm.feature.combine.CombineEntranceService";
    private static final String ANCHOR_PROVIDER = "OnCreateContextMMMenu";

    private static volatile boolean sEnabled = false;
    private static volatile boolean sInstalled = false;

    // 语音「转发」按钮注入后记录 msgId -> 时间戳（§19 方案B：微信原生选人，发送走模块）
    private static final long VOICE_MARK_TTL = 10L * 60L * 1000L;   // 10 分钟有效期
    private static final java.util.Map<Long, Long> sVoiceMsgIds =
            new java.util.concurrent.ConcurrentHashMap<>();
    // 语音消息对象缓存：msgId -> e9（方案B onActivityResult 拦截后用模块内核发送）
    private static final java.util.Map<Long, Object> sVoiceMsgs =
            new java.util.concurrent.ConcurrentHashMap<>();
    // §19.2(2) 语音转发 pending：bq.Q(142) 点击时置位，兜底「语音 Intent 可能无 Retr_Msg_Id」的情况
    private static final long VOICE_PENDING_TTL = 60_000L;   // 60 秒窗口
    private static volatile boolean sVoicePending = false;
    private static volatile long sVoicePendingUntil = 0L;
    private static volatile Object sVoicePendingMsg = null;
    // 修复转发.md v4 + SendChain §5A：MvvmContactListUI.finish() 里抓 state.p 选中目标，B 回填 Select_Conv_User
    private static volatile android.app.Activity sLastMvvm;
    private static volatile java.util.List<String> sPickedTargets;

    // Design A 状态
    private static volatile Object sPendingMsg;                 // manager.t.a 传入的 e9
    private static volatile WeakReference<Activity> sFromRef = new WeakReference<>(null);
    private static volatile int sReqCode = 0;
    private static volatile Intent sOrigRetr;                   // 备份 MsgRetransmitUI 原启动 Intent（路径D 用）
    private static volatile WeakReference<Context> sCtxRef = new WeakReference<>(null);
    private static volatile boolean sPickerBusy = false;        // 防多 hook 点重复弹选择器

    private WxForwardReplaceHook() {}

    // ---------------- 配置 ----------------

    public static boolean isEnabled() {
        SharedPreferences sp = safePrefs();
        return sp != null && sp.getBoolean(K_ENABLED, false);
    }

    public static void setEnabled(boolean on) {
        try {
            ContextManager.getPrefs().edit().putBoolean(K_ENABLED, on).apply();
        } catch (Throwable ignored) {}
        sEnabled = on;
        LogWriter.log(TAG, "setEnabled=" + on);
    }

    private static SharedPreferences safePrefs() {
        try {
            return ContextManager.getPrefs();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- Hook 安装 ----------------

public static void hook(ClassLoader cl) {
        try {
            sEnabled = isEnabled();
        } catch (Throwable ignored) {}
        if (sInstalled) return;
        // 系统/框架类 hook（Activity/ContextWrapper）loader 无关，立即装一次
        try {
            installSelectIntercept();     // §14.3 Activity 层拦 SelectConversationUI（系统类）
            installCwIntercept();         // §14.3 保险：ContextWrapper.startActivityForResult(Intent,int)
        } catch (Throwable ignored) {}
        // v187: 微信真实类由运行时 App 类加载器(或 Tinker loader)加载。入口 cl 是 LSP 初始平行副本，
        // 用它 findClass 挂上的 hook 跑到非运行类上零捕获(§19.2 实测 bq.Q/MsgRetransmitUI 全不触发)。
        // 改为后台反复取真实 loader(优先 application.getClassLoader(), 兜底 findTinker)再装微信类 hook。
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                for (int attempt = 0; attempt < 10 && !sInstalled; attempt++) {
                    ClassLoader useCL = pickRealClassLoader(cl, attempt);
                    if (useCL == null) {
                        try { Thread.sleep(1000L); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    try {
                        installWxClassHooks(useCL);
                        sInstalled = true;
                        LogWriter.log(TAG, "hooks installed (loader=" + useCL.getClass().getName() + ")");
                        return;
                    } catch (Throwable t2) {
                        LogWriter.log(TAG, "wx install attempt " + attempt + " err: " + t2.getMessage());
                    }
                    try { Thread.sleep(1500L); } catch (InterruptedException ignored) {}
                }
                if (!sInstalled) LogWriter.log(TAG, "wx hooks resume failed (feature off for wechat classes)");
            }
        }, "ls-wxfwd-resolve");
        t.setDaemon(true);
        t.start();
    }

    /** 微信类真实加载器：优先运行时 AppContext，其次 Tinker，最后兜底入参 cl。 */
    private static ClassLoader pickRealClassLoader(ClassLoader base, int attempt) {
        try {
            android.content.Context app = com.leshao.v3.ContextManager.getAppContext();
            if (app != null) {
                ClassLoader alc = app.getClassLoader();
                if (alc != null) {
                    try { alc.loadClass("com.tencent.mm.R"); return alc; } catch (Throwable ignored) {}
                    return alc;
                }
            }
        } catch (Throwable ignored) {}
        try {
            ClassLoader tk = VersionCompat.findTinkerClassLoader(base);
            if (tk != null) {
                try { tk.loadClass("com.tencent.mm.storage.e9"); return tk; } catch (Throwable ignored) {}
                return tk;
            }
        } catch (Throwable ignored) {}
        if (attempt >= 2 && base != null) return base;
        return null;
    }

    /** 微信类 hook 装全套；任一失败抛出（外层重试）。 */
    private static void installWxClassHooks(ClassLoader cl) throws Throwable {
        installCoreEntry(cl);            // §11.3 #2 manager.t.a 入口（不替换）
        installVoiceMenuInject(cl);      // §13.2/14.2 语音菜单注入「转发」项
        installFallback(cl);             // §6/12.4 框架层兜底
        installVoicePendingInBqQ(cl);    // §19.2(2) bq.Q(142) 语音转发 pending 跟踪
        installFwdFix(cl);               // 修复转发.md v4：hookRetransmit(① 基类层+子类) + hookPickerFinish(② finish 缓存)
    }

    /** §11.3/§14.2 入口：hook manager.t.a 记待转发消息（不替换，Design A 需原生 MsgRetransmitUI 出现）。 */
    private static void installCoreEntry(ClassLoader cl) {
        Class<?> clsT = findManagerT(cl);
        if (clsT == null) {
            LogWriter.log(TAG, "entry: manager.t not found (fallback-only mode)");
            return;
        }
        Method target = null;
        for (Method m : clsT.getDeclaredMethods()) {
            if (!m.getName().equals("a")) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != 3) continue;
            if (pts[1] != Context.class) continue;
            if (pts[2] != Runnable.class) continue;
            if (!pts[0].getName().contains("e9") && !pts[0].getName().contains("Msg")) continue;
            target = m;
            break;
        }
        if (target == null) {
            LogWriter.log(TAG, "entry: a(e9,Context,Runnable) not found in " + clsT.getName());
            return;
        }
        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!sEnabled) return;
                try {
                    Object msg = param.args[0];
                    if (msg == null) return;
                    sPendingMsg = msg;
                    Object ctx = param.args[1];
                    if (ctx instanceof Context) sCtxRef = new WeakReference<>((Context) ctx);
                    long msgId = 0;
                    try { msgId = (Long) XposedHelpers.callMethod(msg, "getMsgId"); } catch (Throwable ignored) {}
                    LogWriter.log(TAG, "ENTRY manager.t.a type=" + msgTypeOf(msg) + " msgId=" + msgId);
                } catch (Throwable t) {
                    LogWriter.log(TAG, "entry cb err: " + t.getMessage());
                }
            }
        });
        LogWriter.log(TAG, "entry: hooked " + clsT.getName() + ".a(" + ptsDump(target) + ")");
    }

    /** §14.3 Design A 拦截：Activity.startActivityForResult（2参/3参由 hookAllMethods 全收）。 */
    private static void installSelectIntercept() {
        XposedBridge.hookAllMethods(Activity.class, "startActivityForResult", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!sEnabled) return;
                try {
                    if (param.args.length < 2) return;
                    Object o = param.args[0];
                    if (!(o instanceof Intent)) return;
                    Intent in = (Intent) o;
                    ComponentName cn = in.getComponent();
                    if (cn == null || !CLS_SELECT_CONV.equals(cn.getClassName())) return;
                    Activity from = (Activity) param.thisObject;
                    if (from == null) return;
                    String fromCls = from.getClass().getName();
                    if (!fromCls.startsWith("com.tencent.mm.ui.transmit.MsgRetransmitUI")) {
                        LogWriter.log(TAG, "intercept skip (caller=" + fromCls + ")");
                        return;
                    }
                    int req = ((Number) param.args[1]).intValue();
                    if (req != 0) {
                        LogWriter.log(TAG, "intercept skip (req=" + req + ")");
                        return;
                    }
                    // §16.5：语音走微信原生转发（模块只负责注入按钮，不接管选择器）
                    if (isVoiceNativeForward(from)) {
                        LogWriter.log(TAG, "intercept skip (voice native forward msgId="
                                + from.getIntent().getLongExtra("Retr_Msg_Id", -1L) + ")");
                        return;
                    }
                    // §14.3 来源过滤（文档骨架）：caller==MsgRetransmitUI + req==0 即接管。
                    // Retr_* 在 MsgRetransmitUI 启动 intent(from.getIntent()) 里，而非本 SelectConversationUI intent。
                    takeOver(from, req, from.getIntent());
                } catch (Throwable t) {
                    LogWriter.log(TAG, "intercept cb err: " + t.getMessage());
                }
            }
        });
        LogWriter.log(TAG, "intercept: Activity.startActivityForResult hooked");
    }

    /** §14.3 保险：ContextWrapper.startActivityForResult（MMBaseActivity 2参会委托到它）。
     *  v3.0.211：ContextWrapper 有 (Intent,int) 与 (Intent,int,Bundle) 两个签名，单签名
     *  findAndHookMethod 在签名不匹配时会抛误导性的 "No static method findAndHookMethod(...)V"
     *  （日志 cw intercept install err）。改为两个签名逐一尝试，哪个存在 hook 哪个。 */
    private static void installCwIntercept() {
        try {
            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    try {
                        Intent in = (Intent) param.args[0];
                        if (in == null || in.getComponent() == null) return;
                        if (!CLS_SELECT_CONV.equals(in.getComponent().getClassName())) return;
                        Activity from = resolveActivity(param.thisObject);
                        if (from == null) return;
                        String fromCls = from.getClass().getName();
                        boolean isMrui = fromCls.startsWith("com.tencent.mm.ui.transmit.MsgRetransmitUI");
                        if (!isMrui) return;
                        int req = ((Number) param.args[1]).intValue();
                        // §16.5：语音走微信原生转发（模块只负责注入按钮，不接管选择器）
                        if (isVoiceNativeForward(from)) {
                            LogWriter.log(TAG, "cw intercept skip (voice native forward msgId="
                                    + from.getIntent().getLongExtra("Retr_Msg_Id", -1L) + ")");
                            return;
                        }
                        takeOver(from, req, from.getIntent());
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "cw intercept err: " + t.getMessage());
                    }
                }
            };
            boolean any = false;
            java.util.Set<de.robv.android.xposed.XC_MethodHook.Unhook> un = XposedBridge.hookAllMethods(
                    ContextWrapper.class, "startActivityForResult", h);
            if (un != null && !un.isEmpty()) {
                LogWriter.log(TAG, "cw intercept hooked " + un.size() + " overloads");
                any = true;
            }
            if (!any) LogWriter.log(TAG, "cw intercept none (ContextWrapper startActivityForResult miss)");
        } catch (Throwable t) {
            LogWriter.log(TAG, "cw intercept install err: " + t.getMessage());
        }
    }

    /** §15/§16 语音菜单注入：hook 提供者 a(kj5.i4, View, ContextMenuInfo)（3180 实测 viewitems.o0.a）。 */
    private static void installVoiceMenuInject(ClassLoader cl) {
        Class<?> provider = findMenuProvider(cl);
        if (provider == null) {
            LogWriter.log(TAG, "voice menu: provider not found, skip");
            return;
        }
        Class<?> menuCls;
        Class<?> infoCls;
        try {
            menuCls = cl.loadClass("kj5.i4");
        } catch (Throwable t) {
            LogWriter.log(TAG, "voice menu: load kj5.i4 fail: " + t.getMessage());
            return;
        }
        try {
            infoCls = cl.loadClass("android.view.ContextMenu$ContextMenuInfo");
        } catch (Throwable ignored) {
            infoCls = android.view.ContextMenu.class;
        }
        final Class<?> finfo = infoCls;
        Method target = null;
        for (Method mm : provider.getDeclaredMethods()) {
            if (!mm.getName().equals("a")) continue;
            Class<?>[] pts = mm.getParameterTypes();
            if (pts.length != 3) continue;
            if (!menuCls.isAssignableFrom(pts[0])) continue;
            if (pts[1] != View.class) continue;
            target = mm;
            break;
        }
        if (target == null) {
            LogWriter.log(TAG, "voice menu: provider.a(kj5.i4,View,ContextMenuInfo) not found in " + provider.getName());
            return;
        }
        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!sEnabled) return;
                try {
                    Object menu = p.args[0];
                    if (menu == null) return;
                    if (XposedHelpers.callMethod(menu, "findItem", 108) != null) return;
                    if (XposedHelpers.callMethod(menu, "findItem", MENU_ID_FORWARD) != null) return;
                    View v = (View) p.args[1];
                    if (v == null) return;
                    Object tag = v.getTag();
                    if (tag == null) return;
                    Object msgObj = callSafe(tag, "c");
                    if (msgObj == null) return;
                    int type = intOf(XposedHelpers.callMethod(msgObj, "getType"));
                    if (type != MSG_TYPE_VOICE) return;      // 只补语音
                    int group = groupOf(tag);
                    // ★ 必须 c(group, 142, 0, String title, iconRes)：同时给 Title + Icon（§16 铁律）
                    XposedHelpers.callMethod(menu, "c", group, MENU_ID_FORWARD, 0,
                            titleOf(v, RES_TITLE_FORWARD), RES_ICON_FORWARD);
                    // —— 自检日志（§15.3）——
                    int n = (Integer) XposedHelpers.callMethod(menu, "size");
                    Object it = XposedHelpers.callMethod(menu, "getItem", n - 1);
                    LogWriter.log(TAG, "INJECT size=" + n + " title="
                            + XposedHelpers.callMethod(it, "getTitle") + " icon="
                            + XposedHelpers.callMethod(it, "getIcon"));
                    // —— 记录语音 msgId：§19 方案B 点击 142 后微信原生选人，选完由模块发送 ——
                    long voiceMsgId = msgIdOf(msgObj);
                    if (voiceMsgId > 0) {
                        sVoiceMsgIds.put(voiceMsgId, System.currentTimeMillis());
                        sVoiceMsgs.put(voiceMsgId, msgObj);
                        LogWriter.log(TAG, "VOICE_MARK msgId=" + voiceMsgId
                                + " (native picker + module send via plan-B)");
                    }
                } catch (Throwable t) {
                    LogWriter.log(TAG, "voice menu inject err: " + t.getMessage());
                }
            }
        });
        LogWriter.log(TAG, "voice menu: hooked provider " + provider.getName() + ".a(kj5/i4,View,ContextMenuInfo)");
    }


    /** §6/§12.4 兜底：框架层拦 SelectConversationUI 启动 + 来源过滤（MsgRetransmitUI 放行让 Design A 走通）。 */
    private static void installFallback(ClassLoader cl) {
        String[] clsNames = {
                "android.app.Activity",
                "android.content.ContextWrapper",
                "android.content.ContextImpl"
        };
        String[] methodNames = {"startActivity", "startActivityForResult"};
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!sEnabled) return;
                try {
                    for (Object a : param.args) {
                        if (!(a instanceof Intent)) continue;
                        Intent in = (Intent) a;
                        ComponentName c = in.getComponent();
                        String cn = (c == null) ? null : c.getClassName();
                        if (cn == null) continue;
                        if (CLS_MSG_RETRANSMIT.equals(cn)) {
                            // 放行 MsgTransmitUI：Design A 需要它先出现，再由 SelectConversationUI 拦截接管/回灌
                            continue;
                        }
                        if (!CLS_SELECT_CONV.equals(cn)) continue;
                        if (sFromRef.get() != null || sPickerBusy) continue;   // 主拦截/选择器已在处理
                        Object thiz = param.thisObject;
                        Activity from = resolveActivity(thiz);
                        String fromCls = from != null ? from.getClass().getName() : "null";
                        // §14.3 来源过滤：caller==MsgRetransmitUI 即接管（语音等同样命中）
                        if (from != null && fromCls.startsWith("com.tencent.mm.ui.transmit.MsgRetransmitUI")) {
                            // §16.5：语音走微信原生转发（模块只负责注入按钮，不接管选择器）
                            if (isVoiceNativeForward(from)) {
                                LogWriter.log(TAG, "fallback skip (voice native forward msgId="
                                        + from.getIntent().getLongExtra("Retr_Msg_Id", -1L) + ")");
                                return;
                            }
                            LogWriter.log(TAG, "FALLBACK HIT " + cn + " this=" + fromCls);
                            param.setResult(null);   // 阻止原生选择器
                            takeOver(from, reqOf(param), from.getIntent());
                        }
                        return;
                    }
                } catch (Throwable t) {
                    LogWriter.log(TAG, "fallback cb err: " + t.getMessage());
                }
            }
        };
        int hooked = 0;
        for (String cn : clsNames) {
            Class<?> c;
            try {
                c = XposedHelpers.findClass(cn, cl);
            } catch (Throwable ignored) { continue; }
            for (String mn : methodNames) {
                try {
                    XposedBridge.hookAllMethods(c, mn, hook);
                    hooked++;
                } catch (Throwable ignored) {}
            }
        }
        LogWriter.log(TAG, "fallback: hooked " + hooked + " methods");
    }

    /**
     * §19.2(2) 语音转发 pending：hook {@code bq.Q}，点击 142（转发）时记录语音 e9。
     * 兜底「语音 Intent 可能无 Retr_Msg_Id」——SelectConversationUI 放行与模块发送都靠此状态。
     */
    private static void installVoicePendingInBqQ(ClassLoader cl) {
        Method target = null;
        Class<?> bq;
        try {
            bq = XposedHelpers.findClass(CLS_VOICE_VIEW, cl);
        } catch (Throwable t) {
            LogWriter.log(TAG, "plan-B: bq not found: " + t.getMessage());
            return;
        }
        for (Method m : bq.getDeclaredMethods()) {
            if (!m.getName().equals("Q")) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length >= 1 && MenuItem.class.isAssignableFrom(pts[0])) { target = m; break; }
        }
        if (target == null) {
            LogWriter.log(TAG, "plan-B: bq.Q(MenuItem,...) not found in " + bq.getName());
            return;
        }
        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!sEnabled) return;
                try {
                    MenuItem mi = (MenuItem) p.args[0];
                    if (mi == null || mi.getItemId() != MENU_ID_FORWARD) return;
                    Object e9 = findE9InQArgs(p.args);
                    sVoicePendingMsg = e9;
                    sVoicePending = e9 != null;
                    sVoicePendingUntil = System.currentTimeMillis() + VOICE_PENDING_TTL;
                    if (e9 != null) {
                        long msgId = msgIdOf(e9);
                        if (msgId > 0) {
                            sVoiceMsgIds.put(msgId, System.currentTimeMillis());
                            sVoiceMsgs.put(msgId, e9);
                        }
                        LogWriter.log(TAG, "BQ_Q_142 pending msgId=" + msgId);
                    } else {
                        LogWriter.log(TAG, "BQ_Q_142 pending (no e9 in args)");
                    }
                } catch (Throwable ignored) {}
            }
        });
        LogWriter.log(TAG, "plan-B: hooked bq.Q(MenuItem,...) pending tracker");
    }

    /** 从 bq.Q 参数中提取 e9 消息对象：storage 对象直接返回，否则尝试 call("c")。 */
    private static Object findE9InQArgs(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a == null) continue;
            String cn = a.getClass().getName();
            if (cn.contains("storage") || cn.equals("com.tencent.mm.storage.e9")) return a;
        }
        for (Object a : args) {
            if (a == null) continue;
            try {
                Object inner = XposedHelpers.callMethod(a, "c");
                if (inner != null) {
                    String n = inner.getClass().getName();
                    if (n.contains("storage")) return inner;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 转发修复（WeChat_3180_SendChain_MsgToCgi522.md §5.3/§5.4/§5A）：用已验证原语 cl.loadClass + getDeclaredMethod + XposedBridge.hookMethod。
     *  注入 = 回填 p.args[2] 塞 Select_Conv_User（不写私有字段 h），让微信原生自己 split/写 h/走完发送链。
     *  装机第一行日志无守卫，打 target/declClass/declCL 直接诊断。 */
    private static void installFwdFix(ClassLoader cl) {
        hookRetransmit(cl);   // ① 钩 MsgRetransmitUI.onActivityResult 自身
        hookPicker(cl);       // ② 钩 MvvmContactListUI.finish 抓 state.p 目标
    }

    /* ---------- ① MsgRetransmitUI.onActivityResult（自身声明层） ---------- */
    private static void hookRetransmit(ClassLoader cl) {
        try {
            Class<?> c = cl.loadClass("com.tencent.mm.ui.transmit.MsgRetransmitUI");
            Method m = c.getDeclaredMethod("onActivityResult",
                    int.class, int.class, Intent.class);
            // 装机即诊断：确认钩的是 MsgRetransmitUI 自身那一层
            LogWriter.log(TAG, "[FwdFix] target=" + m
                    + " declClass=" + m.getDeclaringClass().getName()
                    + " declCL=" + m.getDeclaringClass().getClassLoader());
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    // ← 第一行，无守卫
                    LogWriter.log(TAG, "[FwdFix][B] ENTER this=" + p.thisObject.getClass().getName()
                            + " req=" + p.args[0] + " rc=" + p.args[1] + " data=" + p.args[2]);
                    try {
                        if (p.thisObject == null) return;
                        int rc = intOf(p.args[1]);
                        if (rc != Activity.RESULT_OK) return;         // 只在“确定”时补
                        Intent data = (Intent) p.args[2];
                        String existing = data != null ? data.getStringExtra("Select_Conv_User") : null;
                        LogWriter.log(TAG, "[FwdFix][B] existing Select_Conv_User=" + existing);
                        // v3.0.198：优先信任原生 data（MvvmContactListUI 选择器权威结果）。
                        // 真机 17:35:52 铁证：用户只选一人，原生 data=wxid_9ohhf82mrlgc22 正确，
                        // 而 readSelection 误抓出 [本群, 个人] → 强制覆盖反而把本群也注入 → 发回本群。
                        java.util.List<String> picked = parseCsvUsers(existing);
                        if (picked == null || picked.isEmpty()) {
                            picked = sPickedTargets;                    // data 为空才用 readSelection 兜底
                        }
                        if (picked == null || picked.isEmpty()) {
                            LogWriter.log(TAG, "[FwdFix][B] 无目标, 放弃");
                            return;
                        }
                        // 真机 16:26:41 备注：极端情况下 data 可能带官方账号排除列表污染，此处解析后
                        // 再过滤 facebookapp/fmessage/gh_ 官方号，保证不误发。过滤逻辑见 filterOfficialAccounts。
                        picked = filterOfficialAccounts(picked);
                        if (picked.isEmpty()) {
                            LogWriter.log(TAG, "[FwdFix][B] 目标全被过滤, 放弃");
                            return;
                        }
                        Intent faked = data != null ? data : new Intent();
                        faked.putExtra("Select_Conv_User", TextUtils.join(",", picked));
                        p.args[2] = faked;                            // 原生继续：自己写 h、自己发送
                        LogWriter.log(TAG, "[FwdFix][B] injected=" + picked + " (was: " + existing + ")");

                        // ★ 主动驱动模块发送：真机 16:38:39 铁证 —— data 正确、注入成功，但微信原生
                        // 语音转发链根本不触发（MsgRetransmitUI 收到后直接 finish 回 LauncherUI，
                        // x51.b0.kj / v51.r0.doScene 零回调）。3180 原生语音转发 UI 链路已坏，
                        // 必须绕开 UI，用模块 TtsVoiceSender 直发兜底。
                        // v3.0.197：优先 bq.Q/launch 的 pending；再兜底 sVoiceMsgs 最新（onVoiceMenuBuilt 标记）。
                        final Object pendMsgF = (sVoicePendingMsg != null) ? sVoicePendingMsg : pendingVoiceE9();
                        if (pendMsgF == null) {
                            LogWriter.log(TAG, "[FwdFix][B] no pending voice msg, skip activeSend");
                        } else {
                            for (final String one : picked) {
                                Thread active = new Thread(() -> {
                                    try {
                                        boolean sent = VoiceForwardHook.forwardVoiceToTarget(one, pendMsgF);
                                        LogWriter.log(TAG, "[FwdFix][B] activeSend=" + sent
                                                + " target=" + one);
                                    } catch (Throwable th) {
                                        LogWriter.log(TAG, "[FwdFix][B] activeSend err " + th);
                                    }
                                }, "ls-wxfwd-active");
                                active.setDaemon(true);
                                active.start();
                            }
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "[FwdFix][B] err " + t);
                    }
                }
            });
        } catch (Throwable t) { LogWriter.log(TAG, "[FwdFix] installRetransmit err " + t); }
    }

    /* ---------- ② MvvmContactListUI.finish()：确定/关闭一刻读 state.p（mrv5/n0.p LinkedList 选中列表） ---------- */
    private static void hookPicker(ClassLoader cl) {
        try {
            Class<?> c = cl.loadClass("com.tencent.mm.ui.mvvm.MvvmContactListUI");
            Method m = c.getDeclaredMethod("finish");
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    try {
                        sLastMvvm = (Activity) p.thisObject;
                        java.util.List<String> picked = readSelection((Activity) p.thisObject);
                        if (picked == null || picked.isEmpty()) {
                            LogWriter.log(TAG, "[FwdFix][A] finish 无picked, dump state:");
                            dumpState((Activity) p.thisObject);
                            return;
                        }
                        sPickedTargets = picked;
                        LogWriter.log(TAG, "[FwdFix][A] finish picked=" + picked);
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "[FwdFix][A] err " + t);
                    }
                }
            });
            LogWriter.log(TAG, "[FwdFix] installed " + c.getName() + ".finish (pick)");
        } catch (Throwable t) { LogWriter.log(TAG, "[FwdFix] installPicker err " + t); }
    }

    /* ---------- 工具：getStateCenter→getState→选中目标（真机 16:26:41 实测：n0.p 空、n0.q=官方账号排除列表、真选中在 n0.F HashMap key） ---------- */
    @SuppressWarnings("unchecked")
    private static java.util.List<String> readSelection(Activity act) {
        try {
            Object center = act.getClass().getMethod("getStateCenter").invoke(act);
            if (center == null) return null;
            Object st = center.getClass().getMethod("getState").invoke(center);   // mr5/n0
            if (st == null) return null;
            // ① 公开字段 p（LinkedList<String> 理论选中列表）
            try {
                java.lang.reflect.Field fp = st.getClass().getDeclaredField("p");
                fp.setAccessible(true);
                Object pv = fp.get(st);
                if (pv instanceof java.util.List && !((java.util.List<?>) pv).isEmpty()) {
                    java.util.List<String> out = new java.util.ArrayList<>();
                    for (Object o : (java.util.List<?>) pv) out.add(String.valueOf(o));
                    return out;
                }
            } catch (Throwable ignored) {}
            // ② HashMap 字段优先（真机实测选中目标在 n0.F，key=wxid，value=SelectContactReportInfo）
            //    —— 必须排在 Collection 之前，否则 n0.q 官方账号「排除」列表会污染
            for (java.lang.reflect.Field f : st.getClass().getDeclaredFields()) {
                if (!java.util.Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(st);
                if (v instanceof java.util.Map && !((java.util.Map<?, ?>) v).isEmpty()) {
                    java.util.List<String> out = new java.util.ArrayList<>();
                    for (Object k : ((java.util.Map<?, ?>) v).keySet()) out.add(String.valueOf(k));
                    return out;
                }
            }
            // ③ 非空 Collection 字段（兜底，但跳过官方账号排除列表特征）
            for (java.lang.reflect.Field f : st.getClass().getDeclaredFields()) {
                if (!java.util.Collection.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(st);
                if (v instanceof java.util.Collection && !((java.util.Collection<?>) v).isEmpty()) {
                    java.util.List<String> out = new java.util.ArrayList<>();
                    for (Object o : (java.util.Collection<?>) v) out.add(String.valueOf(o));
                    if (out.contains("facebookapp") || out.contains("weixin") || out.contains("qqmail")) {
                        continue;   // 官方账号排除列表, 跳过
                    }
                    return out;
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "[FwdFix] readSelection err " + t);
        }
        return null;
    }

    /* ---------- 工具：state 全字段 dump（一次定位真字段名） ---------- */
    private static void dumpState(Activity act) {
        try {
            Object st = null;
            if (act != null) {
                Object center = act.getClass().getMethod("getStateCenter").invoke(act);
                if (center != null) st = center.getClass().getMethod("getState").invoke(center);
            }
            if (st == null) { LogWriter.log(TAG, "[FwdFix][STATE] null"); return; }
            for (java.lang.reflect.Field f : st.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v;
                try { v = f.get(st); } catch (Throwable t) { v = "<err>"; }
                LogWriter.log(TAG, "[FwdFix][STATE] " + st.getClass().getSimpleName() + "." + f.getName()
                        + " : " + (v == null ? "null" : v.getClass().getSimpleName()) + " = " + v);
            }
        } catch (Throwable t) { LogWriter.log(TAG, "[FwdFix][STATE] dump err " + t); }
    }

    /** VoiceForwardHook 语音菜单注入成功后调用：把该语音 e9 标记为方案B待转发（供 [FwdFix][B] activeSend 兜底）。
     *  v3.0.196 铁证：日志无 BQ_Q_142 / 无 launchNativeVoiceForward，但 onVoiceMenuBuilt 注入菜单一定触发且能拿到 e9，
     *  因此必须在此处标记 pending，否则 [FwdFix][B] 永远 no pending voice msg。 */
    public static void markVoiceForwardPending(Object e9) {
        if (e9 == null) return;
        long msgId = msgIdOf(e9);
        if (msgId > 0) {
            sVoiceMsgIds.put(msgId, System.currentTimeMillis());
            sVoiceMsgs.put(msgId, e9);
        }
        sVoicePendingMsg = e9;
        sVoicePending = true;
        sVoicePendingUntil = System.currentTimeMillis() + VOICE_PENDING_TTL;
        LogWriter.log(TAG, "VOICE_MARK(menu)> msgId=" + msgId
                + " type=" + msgTypeOf(e9) + " pendingSet");
    }

    /** 当前待转发语音 e9：优先 bq.Q(142) pending，其次缓存 sVoiceMsgs 中最新的一条。 */
    private static Object pendingVoiceE9() {
        long now = System.currentTimeMillis();
        if (sVoicePending && now < sVoicePendingUntil && sVoicePendingMsg != null) {
            return sVoicePendingMsg;
        }
        Object best = null;
        long bestAt = Long.MIN_VALUE;
        for (java.util.Map.Entry<Long, Object> e : sVoiceMsgs.entrySet()) {
            Long ts = sVoiceMsgIds.get(e.getKey());
            if (ts == null) continue;
            if (ts > bestAt) {
                bestAt = ts;
                best = e.getValue();
            }
        }
        return best;
    }

    /** 消费 pending（发送已接管后清状态，防双发）。 */
    private static void consumeVoicePending() {
        sVoicePending = false;
        sVoicePendingMsg = null;
    }

/**
     * §19 方案B：让语音「转发」点击后走微信原生选人（供 VoiceForwardHook 复用）。
     * 设置方案B pending（SelectConversationUI 放行 + onActivityResult 模块发送），
     * 并按 §12.1 语音 {@code bq.Q} case142 同款 Intent 拉起 {@code MsgRetransmitUI}。
     */
    public static boolean launchNativeVoiceForward(Context ctx, Object e9) {
        try {
            if (ctx == null || e9 == null) return false;
            long msgId = msgIdOf(e9);
            if (msgId > 0) {
                sVoiceMsgIds.put(msgId, System.currentTimeMillis());
                sVoiceMsgs.put(msgId, e9);
            }
            sVoicePendingMsg = e9;
            sVoicePending = true;
            sVoicePendingUntil = System.currentTimeMillis() + VOICE_PENDING_TTL;
            Intent it = new Intent();
            it.setClassName(ctx.getPackageName(), CLS_MSG_RETRANSMIT);
            it.putExtra("Retr_Msg_Type", voiceRetrType(e9));   // e9.V2() ? 6 : 4（§12.1）
            String content = voiceContentOf(e9);
            if (content != null) it.putExtra("Retr_Msg_content", content);
            it.putExtra("scene_from", 17);
            if (msgId > 0) it.putExtra("Retr_Msg_Id", msgId);  // 辅助 msgId 匹配放行
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            LogWriter.log(TAG, "launchNativeVoiceForward msgId=" + msgId);
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "launchNativeVoiceForward err: " + t.getMessage());
            return false;
        }
    }

    /** 语音转发类型：e9.V2() 真 → 6，假 → 4（§12.1）。 */
    private static int voiceRetrType(Object e9) {
        try {
            Object v = XposedHelpers.callMethod(e9, "V2");
            if (v instanceof Boolean) return ((Boolean) v) ? 6 : 4;
        } catch (Throwable ignored) {}
        return 6;
    }

    /** 语音内容/描述：优先 e9.I0()（XML），退回 j()（field_content）。 */
    private static String voiceContentOf(Object e9) {
        try {
            Object v = XposedHelpers.callMethod(e9, "I0");
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {}
        try {
            Object v = XposedHelpers.callMethod(e9, "j");
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {}
        return null;
    }

    // ---------------- 接管 + 选择器 + 回灌 ----------------

    /** 统一接管：记住 from(WeakRef) + req + 原启动 Intent，取消原生 SelectConversationUI，弹选择器。 */
    private static void takeOver(Activity from, int req, Intent origRetr) {
        if (sPickerBusy) return;
        if (from == null || from.isFinishing()) return;
        if (origRetr != null) sOrigRetr = origRetr;
        if (from.getApplicationContext() != null) {
            sCtxRef = new WeakReference<>(from.getApplicationContext());
        }
        sFromRef = new WeakReference<>(from);
        sReqCode = req;
        sPickerBusy = true;
        LogWriter.log(TAG, "INTERCEPT SelectConversationUI req=" + req
                + " from=" + from.getClass().getName());
        final Activity act = from;
        try {
            ContactPickerDialog.show(act, "", ContactPickerDialog.MODE_GROUP,
                    (wxids, display) -> onPicked(act, wxids),
                    () -> {
                        sPickerBusy = false;
                        sPendingMsg = null;
                        sFromRef = new WeakReference<>(null);
                        sOrigRetr = null;
                        LogWriter.log(TAG, "picker canceled");
                    });
        } catch (Throwable t) {
            sPickerBusy = false;
            LogWriter.log(TAG, "launchPicker err: " + t.getMessage());
        }
    }

    /** §11.2/§14.4 核心：主线程回灌；MsgTransmitUI 无实例/已销毁走 §14.1 路径D 直接发送。 */
    private static void onPicked(Activity from, Set<String> wxids) {
        try {
            if (wxids == null || wxids.isEmpty()) {
                LogWriter.log(TAG, "empty selection, skip");
                sPickerBusy = false;
                return;
            }
            final Activity ms = sFromRef.get();
            final String csv = TextUtils.join(",", wxids);
            final Intent ret = new Intent();
            ret.putExtra("Select_Conv_User", csv);
            final Intent orig = sOrigRetr;
            final Context ctx = sCtxRef != null ? sCtxRef.get() : null;
            // 清状态（onPicked 完成后重新可用）
            sPendingMsg = null;
            sFromRef = new WeakReference<>(null);
            sOrigRetr = null;
            sPickerBusy = false;

            if (ms != null && !ms.isDestroyed()) {
                final Activity f = ms;
                LogWriter.log(TAG, "REINJECT onActivityResult(0,RESULT_OK) users=" + csv);
                f.runOnUiThread(() -> {
                    try {
                        XposedHelpers.callMethod(f, "onActivityResult", sReqCode, Activity.RESULT_OK, ret);
                        LogWriter.log(TAG, "REINJECT ok");
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "REINJECT err: " + t.getMessage());
                        reinjectPathD(orig, ctx, csv, wxids);
                    }
                });
                return;
            }

            // 无 MsgTransmitUI 实例或已销毁：§14.1 路径D 重建直发
            LogWriter.log(TAG, "onPicked: MsgRetransmitUI not alive, try Path-D direct send");
            reinjectPathD(orig, ctx, csv, wxids);
        } catch (Throwable t) {
            sPickerBusy = false;
            LogWriter.log(TAG, "onPicked err: " + t.getMessage());
        }
    }

    /** §14.1/§14.4 路径D：用原转发 Intent + Select_Conv_User 重建 MsgRetransmitUI，微信原生直发任意类型。 */
    private static void reinjectPathD(Intent orig, Context ctx, String csv, Set<String> wxids) {
        try {
            if (orig == null || ctx == null) {
                LogWriter.log(TAG, "Path-D skip (no orig/ctx)");
                fallbackSelfSend(wxids);
                return;
            }
            Intent it = new Intent(orig);
            try {
                it.setClass(ctx, Class.forName(CLS_MSG_RETRANSMIT, true, ctx.getClassLoader()));
            } catch (Throwable ignored) {
                it.setClassName(ctx.getPackageName(), CLS_MSG_RETRANSMIT);
            }
            it.putExtra("Select_Conv_User", csv);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            LogWriter.log(TAG, "PATH-D: relaunch MsgRetransmitUI users=" + csv);
        } catch (Throwable t) {
            LogWriter.log(TAG, "Path-D err: " + t.getMessage());
            fallbackSelfSend(wxids);
        }
    }

    /** 兜底发送：路径D 失败时用模块内核发已支持类型。 */
    private static void fallbackSelfSend(Set<String> wxids) {
        try {
            Object msg = sPendingMsg;
            sPendingMsg = null;
            if (msg == null) {
                LogWriter.log(TAG, "fallbackSelfSend: no pending msg");
                return;
            }
            int done = AutoForwardHook.forwardE9ToTargets(msg, wxids);
            LogWriter.log(TAG, "fallbackSelfSend done=" + done + "/" + wxids.size());
        } catch (Throwable t) {
            LogWriter.log(TAG, "fallbackSelfSend err: " + t.getMessage());
        }
    }

    // ---------------- 工具 ----------------

    private static int reqOf(XC_MethodHook.MethodHookParam param) {
        try {
            if (param.args.length >= 2 && param.args[1] instanceof Number) {
                return ((Number) param.args[1]).intValue();
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static Class<?> findManagerT(ClassLoader cl) {
        try {
            return XposedHelpers.findClass(CLS_T, cl);
        } catch (Throwable ignored) {}
        // DexKit 字符串锚点兜底（§10.3）：日志名 ShareDialogHelper
        try {
            List<String> cands = DexKitHelper.findClassesByString(cl, "ShareDialogHelper");
            if (cands != null) {
                for (String name : cands) {
                    try {
                        Class<?> c = XposedHelpers.findClass(name, cl);
                        if (hasTargetMethod(c)) return c;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean hasTargetMethod(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals("a")) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length == 3 && pts[1] == Context.class && pts[2] == Runnable.class) return true;
        }
        return false;
    }

    private static Activity resolveActivity(Object ctxOrThiz) {
        if (ctxOrThiz instanceof Activity) return (Activity) ctxOrThiz;
        Context ctx = (ctxOrThiz instanceof Context) ? (Context) ctxOrThiz : null;
        if (ctx == null && ctxOrThiz != null) {
            for (String mn : new String[]{"getActivity", "getContext"}) {
                try {
                    Object r = XposedHelpers.callMethod(ctxOrThiz, mn);
                    if (r instanceof Activity) return (Activity) r;
                    if (r instanceof Context) { ctx = (Context) r; break; }
                } catch (Throwable ignored) {}
            }
        }
        while (ctx instanceof Context) {
            if (ctx instanceof Activity) return (Activity) ctx;
            if (!(ctx instanceof ContextWrapper)) break;
            Context base = ((ContextWrapper) ctx).getBaseContext();
            if (base == null || base == ctx) break;
            ctx = base;
        }
        return null;
    }

    /** §19 方案B：按 msgId 取语音 e9 消息对象（菜单注入时缓存）。 */
    private static Object voiceMsgOf(long msgId) {
        if (msgId <= 0) return null;
        return sVoiceMsgs.get(msgId);
    }

    private static int msgTypeOf(Object e9) {
        try {
            Object t = XposedHelpers.callMethod(e9, "getType");
            if (t instanceof Integer) return (Integer) t;
        } catch (Throwable ignored) {}
        return -1;
    }

    private static long msgIdOf(Object e9) {
        try {
            Object v = XposedHelpers.callMethod(e9, "getMsgId");
            if (v instanceof Long) return (Long) v;
            if (v instanceof Number) return ((Number) v).longValue();
        } catch (Throwable ignored) {}
        return 0L;
    }

    /** §19 方案B：语音走微信原生选人。命中语音（msgId 标记 或 bq.Q pending）则 Design A 拦截器放行。 */
    private static boolean isVoiceNativeForward(Activity from) {
        try {
            if (from == null) return false;
            // 1) 主要路径：Retr_Msg_Id 命中语音标记
            Intent it = from.getIntent();
            if (it != null) {
                long msgId = it.getLongExtra("Retr_Msg_Id", -1L);
                if (msgId > 0) {
                    Long ts = sVoiceMsgIds.get(msgId);
                    if (ts != null) {
                        long age = System.currentTimeMillis() - ts;
                        if (age >= 0 && age <= VOICE_MARK_TTL) return true;
                        sVoiceMsgIds.remove(msgId);   // 过期条目清理
                        sVoiceMsgs.remove(msgId);
                    }
                }
            }
            // 2) 兜底：bq.Q(142) 语音转发 pending（语音 Intent 可能无 Retr_Msg_Id）
            long now = System.currentTimeMillis();
            if (sVoicePending) {
                if (now < sVoicePendingUntil) return true;
                sVoicePending = false;
                sVoicePendingMsg = null;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 解析团队选择器 data 的 Select_Conv_User（逗号分隔），空→null。 */
    private static java.util.List<String> parseCsvUsers(String csv) {
        if (csv == null || csv.trim().isEmpty()) return null;
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s == null ? "" : s.trim();
            if (t.isEmpty()) continue;
            if (out.contains(t)) continue;
            out.add(t);
        }
        return out.isEmpty() ? null : out;
    }

    /** 过滤官方账号/排除列表污染（facebookapp/fmessage/gh_ 公众号开头等）。
     *  wxid_ 个人与 @chatroom/@im 群均为合法目标，一律保留。 */
    private static java.util.List<String> filterOfficialAccounts(java.util.List<String> in) {
        if (in == null) return new java.util.ArrayList<>();
        java.util.List<String> out = new java.util.ArrayList<>(in.size());
        for (String s : in) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty()) continue;
            if (t.equals("facebookapp") || t.equals("fmessage") || t.equals("qqmail")
                    || t.equals("tmessage") || t.equals("qmessage") || t.equals("qzone")
                    || t.equals("weibo") || t.equals("floatbottle") || t.equals("gh_verifymessage")
                    || t.startsWith("gh_")) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    private static String ptsDump(Method m) {
        Class<?>[] pts = m.getParameterTypes();
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(pts[i].getSimpleName());
        }
        return sb.append(")").toString();
    }

    /** §15.4 提供者定位：先试逻辑类路径，再用 DexKit 字符串锚点。 */
    private static Class<?> findMenuProvider(ClassLoader cl) {
        // 3180 实测提供者 = viewitems.o0（日志已证实）；先试常用候选
        String[] cands = {
                "com.tencent.mm.ui.chatting.viewitems.o0",
                "com.tencent.mm.ui.chatting.viewitems.p",
                "com.tencent.mm.ui.chatting.viewitems.b0"
        };
        for (String c : cands) {
            try {
                Class<?> k = XposedHelpers.findClass(c, cl);
                if (hasMenuProviderA(k)) return k;
            } catch (Throwable ignored) {}
        }
        // DexKit 字符串锚点（§15.4）：含 OnCreateContextMMMenu
        try {
            List<String> hit = DexKitHelper.findClassesByString(cl, ANCHOR_PROVIDER);
            if (hit != null) {
                for (String name : hit) {
                    try {
                        Class<?> k = XposedHelpers.findClass(name, cl);
                        if (hasMenuProviderA(k)) return k;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 判断某类是否有 a(kj5.i4, View, ContextMenuInfo)。 */
    private static boolean hasMenuProviderA(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals("a")) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != 3) continue;
            if (pts[1] != View.class) continue;
            String p0 = pts[0].getName();
            String p2 = pts[2].getName();
            if (p0.contains("kj5") && (p2.contains("ContextMenu") || p2.contains("kj5"))) return true;
        }
        return false;
    }

    private static Object callSafe(Object o, String m, Object... args) {
        try {
            return XposedHelpers.callMethod(o, m, args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intOf(Object v) {
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Number) return ((Number) v).intValue();
        return -1;
    }

    private static int groupOf(Object tag) {
        try {
            Object d = XposedHelpers.callMethod(tag, "d");
            if (d instanceof Integer) return (Integer) d;
        } catch (Throwable ignored) {}
        return 0;
    }

    private static CharSequence titleOf(View v, int resId) {
        try {
            return v.getContext().getString(resId);
        } catch (Throwable t) {
            return "转发";
        }
    }
}