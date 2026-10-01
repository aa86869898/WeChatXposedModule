package com.leshao.v3.hook;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import com.leshao.ai.hook.wechat.StorageHub;
import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 文档《WeChat_RedPacket_Background_Grab_Reverse.md》——纯后台秒抢红包。
 *
 * <p>方案 A：Hook 解析层 {@code dx0/r.v(String)}，识别 appmsg type=2001 / wcpayinfo，
 * 解析 nativeUrl 拿 sendId/channelId → 反射构造 {@code n6}（NetSceneReceiveLuckyMoney）
 * → {@code doScene(dispatcher, callback)} 后台领取。talker 通过 Hook 消息入库方法建立
 * {@code xml → talker} 映射获得。</p>
 *
 * <p>已内置风控：随机延时、仅群聊（默认）、防重、频控、自己发的不抢。</p>
 */
public final class RedPacketHook {

    public static final String TAG = "RedPacket";

    private static final String K_ENABLED = "ls_redpacket_enabled";
    private static final String K_GROUP_ONLY = "ls_redpacket_group_only";
    private static final String K_MAX_PER_MIN = "ls_redpacket_max_per_min";
    private static final String K_DELAY_MAX = "ls_redpacket_delay_max_ms";
    private static final String K_WHITELIST = "ls_redpacket_whitelist";

    private static final String ANCHOR_CGI = "/cgi-bin/mmpay-bin/receivewxhb";
    private static final String ANCHOR_CGI_OPEN = "/cgi-bin/mmpay-bin/openwxhb";
    private static final String C_LUCKY_UTIL = "com.tencent.mm.plugin.luckymoney.model.o5";
    private static final String C_CFG_LOGIC = "b41.y1";
    private static final String ANCHOR_APPMSG_HB = "wxpay://c2cbizmessagehandler/hongbao/receivehongbao";
    private static final String ANCHOR_APPMSG_BIZ = "weixin://openNativeUrl/weixinHB/startreceivebizhbrequest";
    private static final String ANCHOR_APPMSG = "MicroMsg.AppMessage";
    private static final String ANCHOR_APPMSG_XML = "originXml";
    private static final String ANCHOR_MSG_STORAGE = "CREATE TABLE IF NOT EXISTS message";
    private static final String ANCHOR_MSG_INSERT = "insert failed";
    private static final String SESSION_SUFFIX = "@chatroom";

    private static volatile boolean sEnabled = false;
    private static volatile boolean sGroupOnly = true;
    private static volatile int sMaxPerMin = 10;
    private static volatile int sDelayMax = 0;
    private static volatile String sWhitelist = "";

    // xml → {talker}
    private static final Map<String, String> sXmlTalker = Collections.synchronizedMap(
            new LinkedHashMap<String, String>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 200;
                }
            });
    // xml → {nativeUrl}（解析层命中后缓存，供入库路径复用，避免重复反射解析）
    private static final Map<String, String> sXmlNativeUrl = Collections.synchronizedMap(
            new LinkedHashMap<String, String>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 200;
                }
            });
    private static final Set<String> sHandled = ConcurrentHashMap.newKeySet();
    private static final List<Long> sRecent = Collections.synchronizedList(new ArrayList<>());
    private static final Random sRandom = new Random();

    private static Constructor<?> sSceneCtor;
    private static Class<?> sRecvCls;
    private static Class<?> sOpenCls;
    // H2 统一出口：q5（NetSceneLuckyMoneyBase）7 参 onGYNetEnd（文档 §4 H2）
    private static Class<?> sNetBaseCls;
    // 兜底 ClassLoader：本地已领查询（ph5.n0 / lj0.a3）用
    private static volatile ClassLoader sCl;
    // 第二步 openwxhb：h6 构造器与响应解析（文档 §2.3/§2.4）
    private static Constructor<?> sOpenCtor;
    private static Method sHeadImgMethod;
    private static Method sNickMethod;
    // sendId → sessionUsername（群=聊天厅 id / 私聊=对方 wxid），发送 n6 时登记，第二步复用
    private static final Map<String, String> sSendIdSession = new ConcurrentHashMap<>();
    // AppMessage 解析入口：dx0.r.v(String) → dx0.r，nativeUrl 为返回对象字段（文档 §2.2）。
    private static volatile Class<?> sAppMsgCls;
    private static volatile Method sAppMsgParse;

    private RedPacketHook() {}

    // ---------------- 配置 ----------------

    public static void updateConfig() {
        SharedPreferences sp = safePrefs();
        if (sp == null) return;
        sEnabled = sp.getBoolean(K_ENABLED, false);
        sGroupOnly = sp.getBoolean(K_GROUP_ONLY, true);
        sMaxPerMin = sp.getInt(K_MAX_PER_MIN, 10);
        sDelayMax = sp.getInt(K_DELAY_MAX, 0);
        sWhitelist = sp.getString(K_WHITELIST, "");
        LogWriter.log(TAG, "config enabled=" + sEnabled + " groupOnly=" + sGroupOnly
                + " maxPerMin=" + sMaxPerMin);
    }

    private static SharedPreferences safePrefs() {
        try {
            return ContextManager.getPrefs();
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isEnabled() {
        SharedPreferences sp = safePrefs();
        return sp != null && sp.getBoolean(K_ENABLED, false);
    }

    public static void setEnabled(boolean on) {
        putBool(K_ENABLED, on);
        sEnabled = on;
    }

    public static boolean isGroupOnly() {
        SharedPreferences sp = safePrefs();
        return sp == null || sp.getBoolean(K_GROUP_ONLY, true);
    }

    public static void setGroupOnly(boolean on) { putBool(K_GROUP_ONLY, on); sGroupOnly = on; }

    public static int getMaxPerMin() {
        SharedPreferences sp = safePrefs();
        return sp == null ? 10 : sp.getInt(K_MAX_PER_MIN, 10);
    }

    public static void setMaxPerMin(int v) { putInt(K_MAX_PER_MIN, v); sMaxPerMin = v; }

    public static int getDelayMax() {
        SharedPreferences sp = safePrefs();
        return sp == null ? 0 : sp.getInt(K_DELAY_MAX, 0);
    }

    public static void setDelayMax(int v) { putInt(K_DELAY_MAX, v); sDelayMax = v; }

    public static String getWhitelist() {
        SharedPreferences sp = safePrefs();
        return sp == null ? "" : sp.getString(K_WHITELIST, "");
    }

    public static void setWhitelist(String v) { putStr(K_WHITELIST, v); sWhitelist = v; }

    private static void putBool(String k, boolean v) {
        try { ContextManager.getPrefs().edit().putBoolean(k, v).apply(); } catch (Throwable ignored) {}
    }

    private static void putInt(String k, int v) {
        try { ContextManager.getPrefs().edit().putInt(k, v).apply(); } catch (Throwable ignored) {}
    }

    private static void putStr(String k, String v) {
        try { ContextManager.getPrefs().edit().putString(k, v).apply(); } catch (Throwable ignored) {}
    }

    // ---------------- Hook ----------------

    public static void hook(final ClassLoader cl) {
        sCl = cl;
        updateConfig();
        DexKitHelper.addPostScanCallback(() -> install(cl));
    }

    private static void install(ClassLoader cl) {
        try {
            resolveScene(cl);
            resolveOpenScene(cl);
            resolveNetBase(cl);
            hookAppMsgParse(cl);
            hookTalkerMapping(cl);
            hookNetBaseEnd(cl);
            hookRecvEnd(cl);
            hookOpenEnd(cl);
            LogWriter.log(TAG, "hooked");
        } catch (Throwable e) {
            LogWriter.log(TAG, "hook err: " + e);
        }
    }

    private static void resolveScene(ClassLoader cl) {
        List<String> cands = HookUtil.classCandidates(cl, ANCHOR_CGI, "NetSceneReceiveLuckyMoney request");
        if (cands.isEmpty()) {
            LogWriter.log(TAG, "n6 未定位，抢红包不可用");
            return;
        }
        for (String sceneCls : cands) {
            for (Class<?> c : HookUtil.loadClasses(cl, sceneCls)) {
                Constructor<?> hit = null;
                for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length == 7
                            && HookUtil.isInt(p[0]) && HookUtil.isInt(p[1])
                            && p[2] == String.class && p[3] == String.class
                            && HookUtil.isInt(p[4])
                            && p[5] == String.class && p[6] == String.class) {
                        ctor.setAccessible(true);
                        hit = ctor;
                        break;
                    }
                }
                if (hit == null) continue;
                if (findDoScene(c) == null) continue;
                sSceneCtor = hit;
                sRecvCls = c;
                LogWriter.log(TAG, "scene resolved cls=" + c.getName());
                return;
            }
        }
        LogWriter.log(TAG, "n6 构造签名未匹配 candidates=" + cands);
    }

    private static Method findDoScene(Class<?> c) {
        Class<?> cur = c;
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                if ("doScene".equals(m.getName()) && m.getParameterTypes().length == 2) {
                    m.setAccessible(true);
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /** 定位第二步 h6（NetSceneOpenLuckyMoney，openwxhb）：10 参构造器 + 自身类。 */
    private static void resolveOpenScene(ClassLoader cl) {
        List<String> cands = HookUtil.classCandidates(cl, ANCHOR_CGI_OPEN, "NetSceneOpenLuckyMoney request");
        if (cands.isEmpty()) {
            LogWriter.log(TAG, "h6 未定位，第二步不可用");
            return;
        }
        for (String sceneCls : cands) {
            for (Class<?> c : HookUtil.loadClasses(cl, sceneCls)) {
                for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length == 10
                            && HookUtil.isInt(p[0]) && HookUtil.isInt(p[1])
                            && p[2] == String.class && p[3] == String.class
                            && p[4] == String.class && p[5] == String.class
                            && p[6] == String.class && p[7] == String.class
                            && p[8] == String.class && p[9] == String.class) {
                        ctor.setAccessible(true);
                        sOpenCtor = ctor;
                        sOpenCls = c;
                        LogWriter.log(TAG, "h6 resolved cls=" + c.getName());
                        resolveHeadNick(cl);
                        return;
                    }
                }
            }
        }
        LogWriter.log(TAG, "h6 构造签名未匹配 candidates=" + cands);
    }

    /** 定位 o5.l()（自己头像 headImg）与 b41.y1.m()（自己昵称），供 openwxhb 请求体使用。 */
    private static void resolveHeadNick(ClassLoader cl) {
        try {
            Class<?> o5 = XposedHelpers.findClass(C_LUCKY_UTIL, cl);
            for (Method m : o5.getDeclaredMethods()) {
                if ("l".equals(m.getName()) && m.getParameterTypes().length == 0
                        && m.getReturnType() == String.class) {
                    m.setAccessible(true);
                    sHeadImgMethod = m;
                    break;
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "o5.l 未定位: " + t);
        }
        try {
            Class<?> y1 = XposedHelpers.findClass(C_CFG_LOGIC, cl);
            for (Method m : y1.getDeclaredMethods()) {
                if ("m".equals(m.getName()) && m.getParameterTypes().length == 0
                        && m.getReturnType() == String.class) {
                    m.setAccessible(true);
                    sNickMethod = m;
                    break;
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "b41.y1.m 未定位: " + t);
        }
    }

    /** 定位 H2 统一出口：q5（NetSceneLuckyMoneyBase）的 7 参 onGYNetEnd（文档 §4 H2）。 */
    private static void resolveNetBase(ClassLoader cl) {
        Class<?> c = sRecvCls != null ? sRecvCls : sOpenCls;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if ("onGYNetEnd".equals(m.getName()) && m.getParameterTypes().length == 7) {
                    sNetBaseCls = c;
                    LogWriter.log(TAG, "netbase resolved cls=" + c.getName());
                    return;
                }
            }
            c = c.getSuperclass();
        }
        LogWriter.log(TAG, "q5.onGYNetEnd(7参) 未定位，错误通知不可用");
    }

    /** H2：q5.onGYNetEnd(7参) after → errType/errCode/errMsg 统一出口。 */
    private static void hookNetBaseEnd(ClassLoader cl) {
        if (sNetBaseCls == null) return;
        try {
            for (Method m : sNetBaseCls.getDeclaredMethods()) {
                if (!"onGYNetEnd".equals(m.getName()) || m.getParameterTypes().length != 7) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args == null || param.args.length != 7) return;
                            onNetBaseEnd(param);
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "netBaseEnd after err: " + t);
                        }
                    }
                });
            }
            LogWriter.log(TAG, "hooked q5.onGYNetEnd cls=" + sNetBaseCls.getName());
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookNetBaseEnd err: " + t);
        }
    }

    /** H2 回调：retcode!=0 时只走 q5.onGYNetEnd，错误只能在这里拿（文档 §4 说明）。 */
    private static void onNetBaseEnd(XC_MethodHook.MethodHookParam p) {
        Object scene = p.thisObject;
        if (scene == null) return;
        int errType = p.args[1] instanceof Number ? ((Number) p.args[1]).intValue() : 0;
        int errCode = p.args[2] instanceof Number ? ((Number) p.args[2]).intValue() : 0;
        String errMsg = p.args[3] == null ? "" : p.args[3].toString();
        if (errType == 0 && errCode == 0) return;
        String sendId = strField(scene, "m");
        LogWriter.log(TAG, "H2 err sendId=" + sendId + " " + errType + "/" + errCode + " " + errMsg);
        if (sendId == null || !sHandled.contains(sendId)) return;
        String session = sSendIdSession.get(sendId);
        notifyGrab(sendId, session, -1L, null, "失败 " + errCode + " " + errMsg);
    }

    /** H3：n6.onGYNetEnd(int,String,JSONObject) → 解析 timingIdentifier 并发第二步。 */
    private static void hookRecvEnd(ClassLoader cl) {
        if (sRecvCls == null) return;
        try {
            XposedBridge.hookAllMethods(sRecvCls, "onGYNetEnd", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length != 3) return;
                        onRecvEnd(param.thisObject);
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "recvEnd after err: " + t);
                    }
                }
            });
            LogWriter.log(TAG, "hooked n6.onGYNetEnd cls=" + sRecvCls.getName());
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookRecvEnd err: " + t);
        }
    }

    /** H4：h6.onGYNetEnd(int,String,JSONObject) → 读取金额。 */
    private static void hookOpenEnd(ClassLoader cl) {
        if (sOpenCls == null) return;
        try {
            XposedBridge.hookAllMethods(sOpenCls, "onGYNetEnd", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length != 3) return;
                        onOpenEnd(param.thisObject);
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "openEnd after err: " + t);
                    }
                }
            });
            LogWriter.log(TAG, "hooked h6.onGYNetEnd cls=" + sOpenCls.getName());
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookOpenEnd err: " + t);
        }
    }

    /** n6 响应：拿 timingIdentifier 等字段，构造 h6（openwxhb）并丢回网络队列。 */
    private static void onRecvEnd(Object scene) {
        if (scene == null) return;
        String sendId = strField(scene, "m");
        if (sendId == null || !sHandled.contains(sendId)) return;
        String nurl = strField(scene, "n");
        int msgType = intField(scene, "h", 1);
        int channel = intField(scene, "i", 1);
        String timing = strField(scene, "P");
        int hbStatus = intField(scene, "s", 0);
        int recvStat = intField(scene, "t", 0);
        String statusMess = strField(scene, "u");
        String session = sSendIdSession.get(sendId);
        LogWriter.log(TAG, "n6 resp sendId=" + sendId + " hbStatus=" + hbStatus
                + " recv=" + recvStat + " timingLen=" + (timing == null ? 0 : timing.length()));

        if (recvStat == 2 || hbStatus == 4 || hbStatus == 5) {
            LogWriter.log(TAG, "红包不可领 sendId=" + sendId + " hbStatus=" + hbStatus
                    + " recv=" + recvStat + " " + statusMess);
            notifyGrab(sendId, session, -1L, null,
                    (statusMess == null || statusMess.isEmpty()) ? "已领过/已过期" : statusMess);
            return;
        }
        if (timing == null || timing.isEmpty()) {
            LogWriter.log(TAG, "timingIdentifier 为空，放弃第二步 sendId=" + sendId);
            return;
        }
        if (sOpenCtor == null) {
            LogWriter.log(TAG, "h6 构造器未解析，无法第二步 sendId=" + sendId);
            return;
        }
        String headImg = callStaticStr(sHeadImgMethod);
        String nick = callStaticStr(sNickMethod);
        try {
            Object open = sOpenCtor.newInstance(msgType, channel, sendId,
                    nurl == null ? "" : nurl,
                    headImg == null ? "" : headImg,
                    nick == null ? "" : nick,
                    session == null ? "" : session,
                    "v1.0", timing, "");
            Object queue = StorageHub.get().netSceneQueue();
            if (queue == null) {
                LogWriter.log(TAG, "NetSceneQueue 不可用，openwxhb 发送失败 sendId=" + sendId);
                return;
            }
            XposedHelpers.callMethod(queue, "g", open);
            LogWriter.log(TAG, "openwxhb sent sendId=" + sendId + " session=" + session);
        } catch (Throwable t) {
            LogWriter.log(TAG, "openwxhb 发送失败 sendId=" + sendId + " err=" + t);
        }
    }

    /** h6 响应：读取 e1 模型金额（q 字段，单位分），并发送到账通知（文档 §5.3 H4）。 */
    private static void onOpenEnd(Object scene) {
        if (scene == null) return;
        String sendId = strField(scene, "m");
        if (sendId == null || !sHandled.contains(sendId)) return;
        Object e1 = XposedHelpers.getObjectField(scene, "h");
        if (e1 == null) return;
        long amountFen = getLong(e1, "q");
        String nick = strField(e1, "i");
        String user = strField(e1, "Q");
        String statusMess = strField(e1, "f");
        int recvStatus = intField(e1, "A", 0);
        long recNum = getLong(e1, "r");
        long totalNum = getLong(e1, "t");
        long totalAmount = getLong(e1, "u");
        String session = sSendIdSession.get(sendId);
        LogWriter.log(TAG, "红包到账 sendId=" + sendId + " amount=" + amountFen + "分"
                + " nick=" + nick + " user=" + user + " recv=" + recvStatus
                + " " + recNum + "/" + totalNum + " msg=" + statusMess);

        String who = (nick == null || nick.isEmpty()) ? user : nick;
        if (amountFen > 0) {
            notifyGrab(sendId, session, amountFen, who,
                    recNum + "/" + totalNum + "个 · 共" + fmtFen(totalAmount));
        } else {
            notifyGrab(sendId, session, -1L, who,
                    (statusMess == null || statusMess.isEmpty()) ? ("状态码 " + recvStatus) : statusMess);
        }
    }

    // ---------------- 专属红包 / 本地已领 / 到账通知（文档 §5.2/§5.3） ----------------

    /** 解析 CDATA 包裹的元素值（文档 §5.2 cdata 工具）。 */
    private static String cdata(String xml, String tag) {
        if (xml == null || tag == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<" + tag + ">\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</" + tag + ">",
                        java.util.regex.Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    /** 专属红包判断：exclusive_recv_username 非空且不是自己 wxid → 跳过（文档 §5.2/§6.3-5）。 */
    private static boolean isExclusiveNotMine(String xml) {
        if (xml == null || !xml.contains("exclusive_recv_username")) return false;
        String exclusive = cdata(xml, "exclusive_recv_username");
        if (exclusive == null || exclusive.isEmpty()) return false;
        String self = selfWxid();
        return self == null || !self.equals(exclusive);
    }

    /** 自己 wxid：StorageHub.selfWxid()（文档 b41.y1.u()）优先，ModuleConfig 兜底。 */
    private static String selfWxid() {
        try {
            String w = StorageHub.get().selfWxid();
            if (w != null && !w.isEmpty()) return w;
        } catch (Throwable ignored) {}
        try {
            return com.leshao.v3.model.ModuleConfig.getCurrentWxid();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 本地红包记录表已领判断：field_receiveAmount>0 即跳过（文档 §5.2）。 */
    private static boolean isAlreadyReceived(String nativeUrl) {
        if (sCl == null || nativeUrl == null) return false;
        try {
            Class<?> pluginCls = XposedHelpers.findClass("ph5.n0", sCl);
            Class<?> pluginItf = XposedHelpers.findClass("lj0.a3", sCl);
            Object plugin = XposedHelpers.callStaticMethod(pluginCls, "c", pluginItf);
            if (plugin == null) return false;
            Object dao = XposedHelpers.callMethod(plugin, "ij");
            if (dao == null) return false;
            Object rec = XposedHelpers.callMethod(dao, "t1", nativeUrl);
            if (rec == null) return false;
            Object amt = XposedHelpers.getObjectField(rec, "field_receiveAmount");
            return amt instanceof Number && ((Number) amt).longValue() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 到账/失败通知（文档 §5.3）。 */
    private static void notifyGrab(String sendId, String session, long amountFen, String who, String extra) {
        Context ctx = ContextManager.getAppContext();
        if (ctx == null) return;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            final String ch = "wx_lucky_grab";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel c = new NotificationChannel(ch, "红包到账", NotificationManager.IMPORTANCE_HIGH);
                c.setDescription("自动领取微信红包结果");
                nm.createNotificationChannel(c);
            }
            boolean ok = amountFen > 0;
            String title = ok ? ("抢到红包 ¥" + fmtFen(amountFen)) : "红包未抢到";
            StringBuilder sb = new StringBuilder();
            sb.append(session != null && session.endsWith(SESSION_SUFFIX) ? "群聊" : "私聊");
            if (who != null && !who.isEmpty()) sb.append(" · ").append(who);
            if (extra != null && !extra.isEmpty()) sb.append(" · ").append(extra);
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
            if (launch == null) launch = new Intent();
            int pf = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) pf |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, launch, pf);
            Notification.Builder b = new Notification.Builder(ctx)
                    .setAutoCancel(true)
                    .setSmallIcon(ctx.getApplicationInfo().icon)
                    .setContentTitle(title)
                    .setContentText(sb.toString())
                    .setContentIntent(pi);
            if (Build.VERSION.SDK_INT >= 26) b.setChannelId(ch);
            nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), b.build());
        } catch (Throwable t) {
            LogWriter.log(TAG, "notify err sendId=" + sendId + " " + t);
        }
    }

    private static String fmtFen(long fen) {
        return String.format(Locale.US, "%.2f", fen / 100.0);
    }

    // ---------------- 反射工具（第二步用） ----------------

    private static String strField(Object o, String name) {
        try {
            Object v = XposedHelpers.getObjectField(o, name);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intField(Object o, String name, int def) {
        try {
            return XposedHelpers.getIntField(o, name);
        } catch (Throwable t) {
            return def;
        }
    }

    private static long getLong(Object o, String name) {
        try {
            return XposedHelpers.getLongField(o, name);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String callStaticStr(Method m) {
        if (m == null) return null;
        try {
            Object v = m.invoke(null);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void hookAppMsgParse(ClassLoader cl) {
        List<String> cands = HookUtil.classCandidates(cl,
                ANCHOR_APPMSG_HB, ANCHOR_APPMSG_BIZ, ANCHOR_APPMSG, ANCHOR_APPMSG_XML, "parse msg failed");
        if (cands.isEmpty()) {
            LogWriter.log(TAG, "AppMessage 未定位");
            return;
        }
        int n = 0;
        for (String appMsgCls : cands) {
            for (Class<?> c : HookUtil.loadClasses(cl, appMsgCls)) {
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && p[0] == String.class && c.isAssignableFrom(m.getReturnType())) {
                        try {
                            m.setAccessible(true);
                            if (sAppMsgCls == null) {
                                sAppMsgCls = c;
                                sAppMsgParse = m;
                            }
                            XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        onParsed(param.args[0] instanceof String ? (String) param.args[0] : null,
                                                param.getResult());
                                    } catch (Throwable t) {
                                        LogWriter.log(TAG, "parse after err: " + t);
                                    }
                                }
                            });
                            n++;
                        } catch (Throwable ignored) {}
                    }
                }
            }
            if (n > 0) {
                LogWriter.log(TAG, "hooked AppMessage parse x" + n + " cls=" + appMsgCls);
                return;
            }
        }
        LogWriter.log(TAG, "AppMessage parse 未匹配 candidates=" + cands);
    }

    private static void hookTalkerMapping(ClassLoader cl) {
        Class<?> e9 = VersionCompat.findMsgInfoStorageClass(cl);
        if (e9 == null) {
            LogWriter.log(TAG, "MsgInfo 类未定位，talker 映射不可用");
            return;
        }
        // 优先固定类名（与 MessageHook 一致，实测稳定），DexKit 结果仅兜底。
        java.util.LinkedHashSet<Class<?>> classes = new java.util.LinkedHashSet<>();
        Class<?> fixed = VersionCompat.findMsgStorageClass(cl);
        if (fixed != null) classes.add(fixed);
        for (String storageCls : HookUtil.classCandidates(cl, ANCHOR_MSG_STORAGE, "MicroMsg.MsgInfoStorage")) {
            classes.addAll(HookUtil.loadClasses(cl, storageCls));
        }
        int n = 0;
        for (Class<?> c : classes) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && e9.isAssignableFrom(p[0])
                        && (p[1] == boolean.class || p[1] == Boolean.class)) {
                    try {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            // 与 MessageHook 一致：before 阶段 MsgInfo.content 尚未填充，
                            // 必须用 after 才能拿到 content/talker。
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    cacheTalker(param.args[0]);
                                    maybeGrabFromMsg(param.args[0]);
                                } catch (Throwable ignored) {}
                            }
                        });
                        n++;
                    } catch (Throwable ignored) {}
                }
            }
        }
        LogWriter.log(TAG, "hooked MsgInfoStorage insert x" + n);
    }

    private static void cacheTalker(Object msg) {
        if (msg == null) return;
        if (isSendOf(msg)) return;
        String content = contentOf(msg);
        if (content == null || !content.contains("wcpayinfo")) return;
        String talker = strCall(msg, "N0", "getTalker", "getTalkerName");
        if (talker == null || talker.isEmpty()) return;
        synchronized (sXmlTalker) {
            sXmlTalker.put(content, talker);
        }
    }

    /** 入库路径（文档 §2.1 首选）：直接从 MsgInfo 拿 content + talker 后解析并抢。 */
    private static void maybeGrabFromMsg(Object msg) {
        if (!sEnabled || msg == null) return;
        // 自己发出的红包不抢（否则服务端会以“系统繁忙”拒绝，且无意义）。
        if (isSendOf(msg)) return;
        String content = contentOf(msg);
        if (content == null) return;
        if (!content.contains("wcpayinfo")
                && !content.contains("wxpay://")
                && !content.contains("weixin://openNativeUrl/weixinHB")) return;
        // 专属红包：exclusive_recv_username 非自己 wxid 必须跳过（文档 §6.3-5）。
        if (isExclusiveNotMine(content)) {
            LogWriter.log(TAG, "专属红包非本人，跳过");
            return;
        }
        String talker = strCall(msg, "N0", "getTalker", "getTalkerName");
        String nativeUrl = parseNativeUrl(content);
        if (nativeUrl == null || nativeUrl.isEmpty()) return;
        LogWriter.log(TAG, "storage path hit talker=" + talker);
        handle(nativeUrl, talker);
    }

    // ---------------- 识别与领取 ----------------

    private static void onParsed(String xml, Object parsed) {
        if (!sEnabled || xml == null || !xml.contains("wcpayinfo")) return;
        LogWriter.log(TAG, "redpacket candidate xml len=" + xml.length());
        // 专属红包：exclusive_recv_username 非自己 wxid 必须跳过（文档 §6.3-5）。
        if (isExclusiveNotMine(xml)) {
            LogWriter.log(TAG, "专属红包非本人，跳过");
            return;
        }
        // 文档 §2.2：nativeUrl 是解析后对象 dx0.r 的字段（s1），不是 XML 属性。
        String nativeUrl = nativeUrlFromObject(parsed);
        if (nativeUrl == null || nativeUrl.isEmpty()) nativeUrl = extractNativeUrl(xml);
        if (nativeUrl == null || nativeUrl.isEmpty()) {
            LogWriter.log(TAG, "nativeUrl 缺失，跳过 parsed=" + (parsed == null ? "null" : parsed.getClass().getName()));
            return;
        }
        cacheNativeUrl(xml, nativeUrl);
        String talker = lookupTalker(xml, nativeUrl);
        if (talker != null && !talker.isEmpty()) {
            handle(nativeUrl, talker);
        } else {
            // 解析层早于入库层，此刻 talker 未知：交由入库路径(maybeGrabFromMsg)补全；
            // 仅当未开群聊限制时挂短延时兜底，避免完全不抢。
            LogWriter.log(TAG, "talker 未知，等待入库路径补全");
            scheduleFallback(nativeUrl);
        }
    }

    private static void cacheNativeUrl(String xml, String nativeUrl) {
        if (xml == null || nativeUrl == null || nativeUrl.isEmpty()) return;
        synchronized (sXmlNativeUrl) {
            sXmlNativeUrl.put(xml, nativeUrl);
        }
    }

    /** 入库路径未命中时的兜底：短延时后用 sendusername（单聊即会话方）领取。 */
    private static void scheduleFallback(final String nativeUrl) {
        if (sGroupOnly) return;
        final String sendId = query(nativeUrl, "sendid");
        if (sendId == null || sendId.isEmpty()) return;
        final int channelId = parseInt(query(nativeUrl, "channelid"), 1);
        // 单聊场景 sendusername 即会话对方，可直接作为 sessionUsername；
        // 自己发出的包直接跳过。
        final String sender = query(nativeUrl, "sendusername");
        if (sender != null && !sender.isEmpty() && isSelf(sender)) return;
        final String talker = (sender != null && !sender.isEmpty()) ? sender : null;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (!sEnabled || sHandled.contains(sendId)) return;
                if (!sHandled.add(sendId)) return;
                if (!allowByFrequency()) return;
                LogWriter.log(TAG, "fallback grab sendId=" + sendId + " talker=" + talker);
                grabAsync(sendId, channelId, nativeUrl, talker);
            } catch (Throwable ignored) {}
        }, 1500L);
    }

    /** 统一处理：解析 sendId/channelId + 风控 + 异步领取。 */
    private static void handle(String nativeUrl, String talker) {
        if (!sEnabled) return;
        if (nativeUrl == null || nativeUrl.isEmpty()) return;
        String sendId = query(nativeUrl, "sendid");
        if (sendId == null || sendId.isEmpty()) return;
        int channelId = parseInt(query(nativeUrl, "channelid"), 1);

        if (sGroupOnly && (talker == null || !talker.endsWith(SESSION_SUFFIX))) {
            LogWriter.log(TAG, "非群聊/会话未知，跳过 talker=" + talker);
            return;
        }
        if (isSelf(talker)) return;

        String key = sendId;
        if (!sHandled.add(key)) return;
        // 本地红包记录表已领过（field_receiveAmount>0）则跳过（文档 §5.2）。
        if (isAlreadyReceived(nativeUrl)) {
            LogWriter.log(TAG, "本地已领，跳过 key=" + key);
            return;
        }
        if (!allowByFrequency()) {
            LogWriter.log(TAG, "频控拦截 key=" + key);
            return;
        }
        if (!whitelisted(talker)) return;

        LogWriter.log(TAG, "nativeUrl=" + nativeUrl + " talker=" + talker);
        grabAsync(sendId, channelId, nativeUrl, talker);
    }

    /** 从解析后的 AppMessage 对象扫描出 nativeUrl 字段。 */
    private static String nativeUrlFromObject(Object obj) {
        if (obj == null) return null;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (!(v instanceof String)) continue;
                    String s = (String) v;
                    if (s.startsWith("wxpay://") || s.startsWith("weixin://openNativeUrl/weixinHB")) {
                        return s;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** 用 AppMessage.v(String) 解析 XML 并取 nativeUrl（字段），失败再退回字符串解析。 */
    private static String parseNativeUrl(String content) {
        if (content != null) {
            synchronized (sXmlNativeUrl) {
                String cached = sXmlNativeUrl.get(content);
                if (cached != null && !cached.isEmpty()) return cached;
            }
        }
        Method parse = sAppMsgParse;
        if (parse != null) {
            try {
                Object target = java.lang.reflect.Modifier.isStatic(parse.getModifiers())
                        ? null : XposedHelpers.newInstance(sAppMsgCls);
                Object r = parse.invoke(target, content);
                String u = nativeUrlFromObject(r);
                if (u != null && !u.isEmpty()) return u;
            } catch (Throwable ignored) {}
        }
        return extractNativeUrl(content);
    }

    private static boolean isSelf(String talker) {
        try {
            String me = com.leshao.v3.model.ModuleConfig.getCurrentWxid();
            return me != null && !me.isEmpty() && me.equals(talker);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean whitelisted(String talker) {
        if (sWhitelist == null || sWhitelist.trim().isEmpty()) return true;
        if (talker == null) return false;
        for (String w : sWhitelist.split(",")) {
            if (w.trim().equals(talker)) return true;
        }
        return false;
    }

    private static boolean allowByFrequency() {
        long now = System.currentTimeMillis();
        synchronized (sRecent) {
            sRecent.removeIf(ts -> now - ts > 60000L);
            if (sRecent.size() >= sMaxPerMin) return false;
            sRecent.add(now);
            return true;
        }
    }

    private static void grabAsync(final String sendId, final int channelId,
                                  final String nativeUrl, final String talker) {
        final int delay = sRandom.nextInt(Math.max(1, sDelayMax));
        Thread t = new Thread(() -> {
            try {
                if (delay > 0) Thread.sleep(delay);
                grab(sendId, channelId, nativeUrl, talker);
            } catch (Throwable e) {
                LogWriter.log(TAG, "grab failed: " + e);
            }
        }, "leshao-redpacket");
        t.setDaemon(true);
        t.start();
    }

    private static void grab(String sendId, int channelId, String nativeUrl, String talker) throws Throwable {
        if (sSceneCtor == null) {
            LogWriter.log(TAG, "scene ctor 未解析，放弃");
            return;
        }
        if (talker != null && !talker.isEmpty()) {
            sSendIdSession.put(sendId, talker);
        }
        // 文档 §2.2/§5.2：inWay=0（后台领取），msgType 固定 1。
        Object scene = sSceneCtor.newInstance(1, channelId, sendId, nativeUrl, 0, "v1.0",
                talker == null ? "" : talker);

        Object queue = StorageHub.get().netSceneQueue();
        if (queue == null) {
            LogWriter.log(TAG, "NetSceneQueue 不可用");
            return;
        }
        // 文档 §3.1/§5.2：submitScene = gp0.j1.j().q().b.g(scene)，即 queue.g(scene)。
        try {
            XposedHelpers.callMethod(queue, "g", scene);
            LogWriter.log(TAG, "queue.g 入队 sendId=" + sendId + " talker=" + talker);
        } catch (Throwable e) {
            LogWriter.log(TAG, "queue.g err: " + e);
        }
    }

    // ---------------- 解析工具 ----------------

    private static String lookupTalker(String xml, String nativeUrl) {
        synchronized (sXmlTalker) {
            String t = sXmlTalker.get(xml);
            if (t != null) return t;
            for (Map.Entry<String, String> e : sXmlTalker.entrySet()) {
                if (e.getKey() != null && e.getKey().contains(nativeUrl)) return e.getValue();
            }
        }
        return null;
    }

    private static String extractNativeUrl(String xml) {
        if (xml == null) return null;
        // 属性式 nativeurl="..." / nativeUrl="..."
        String raw = attr(xml, "nativeurl=\"");
        if (raw == null) raw = attr(xml, "nativeUrl=\"");
        if (raw == null) raw = attr(xml, "nativeurl='");
        // 元素式 <nativeurl>...</nativeurl>（可能带 CDATA）
        if (raw == null) raw = element(xml, "nativeurl");
        if (raw == null) raw = element(xml, "nativeUrl");
        if (raw == null) return null;
        raw = raw.replace("&amp;", "&").replace("&#38;", "&").trim();
        if (raw.startsWith("<![CDATA[") && raw.endsWith("]]>")) {
            raw = raw.substring(9, raw.length() - 3).trim();
        }
        try {
            return URLDecoder.decode(raw, "UTF-8");
        } catch (Throwable t) {
            return raw;
        }
    }

    private static String attr(String xml, String prefix) {
        int i = xml.indexOf(prefix);
        if (i < 0) return null;
        i += prefix.length();
        char end = prefix.endsWith("'") ? '\'' : '"';
        int j = xml.indexOf(end, i);
        return j < 0 ? null : xml.substring(i, j);
    }

    private static String element(String xml, String name) {
        String open = "<" + name + ">";
        int i = xml.indexOf(open);
        if (i < 0) return null;
        i += open.length();
        int j = xml.indexOf("</" + name + ">", i);
        return j < 0 ? null : xml.substring(i, j);
    }

    private static String query(String url, String key) {
        try {
            int q = url.indexOf('?');
            String s = q >= 0 ? url.substring(q + 1) : url;
            for (String pair : s.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                if (pair.substring(0, eq).equalsIgnoreCase(key)) {
                    return pair.substring(eq + 1);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String strCall(Object o, String... names) {
        for (String name : names) {
            try {
                Object v = XposedHelpers.callMethod(o, name);
                if (v instanceof String) return (String) v;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 与 MessageHook 一致的 MsgInfo 内容读取：字段优先，再逐方法兜底（8.0.78 混淆差异）。 */
    private static String contentOf(Object msg) {
        if (msg == null) return null;
        try {
            java.lang.reflect.Field f = msg.getClass().getDeclaredField("field_content");
            f.setAccessible(true);
            Object v = f.get(msg);
            if (v != null) return v.toString();
        } catch (Throwable ignored) {}
        return strCall(msg, "getContent", "I0", "j", "N1");
    }

    /** MsgInfo 是否为自己发出的消息（z0==1）。 */
    private static boolean isSendOf(Object msg) {
        if (msg == null) return false;
        for (String n : new String[]{"z0", "isSend"}) {
            try {
                Object v = XposedHelpers.callMethod(msg, n);
                if (v instanceof Number) return ((Number) v).intValue() == 1;
                if (v instanceof Boolean) return (Boolean) v;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return def;
        }
    }

    /** 供 UI 显示统计。 */
    public static int handledCount() {
        return sHandled.size();
    }
}
