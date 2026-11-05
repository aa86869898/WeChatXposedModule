package com.leshao.v3.hook;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONObject;
import org.json.JSONArray;
import android.os.Build;

import com.leshao.ai.hook.wechat.StorageHub;
import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

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
    private static final String K_DELAY_ENABLED = "ls_redpacket_delay_enabled";
    private static final String K_DELAY_MAX = "ls_redpacket_delay_max_ms";
    private static final String K_WHITELIST = "ls_redpacket_whitelist";
    private static final String K_STATS = "ls_redpacket_stats";
    private static final String K_VOICE_ENABLED = "ls_redpacket_voice_enabled";
    private static final String K_AUTO_REPLY = "ls_redpacket_auto_reply";
    private static final String K_AUTO_REPLY_TTS = "ls_redpacket_auto_reply_tts";
    private static final String K_AUTO_REPLY_TEXTS = "ls_redpacket_auto_reply_texts";
    private static final String K_AUTO_REPLY_TTS_TEXTS = "ls_redpacket_auto_reply_tts_texts";
    private static final String K_RECENT = "ls_redpacket_recent";

    private static final String ANCHOR_CGI = "/cgi-bin/mmpay-bin/receivewxhb";
    private static final String ANCHOR_CGI_OPEN = "/cgi-bin/mmpay-bin/openwxhb";
    /** v3.0.566: 8.0.79 红包 XML 可能无 <nativeurl> 标签，直接匹配 wxpay:// 红包 URL。 */
    private static final java.util.regex.Pattern P_WXPAY_URL =
            java.util.regex.Pattern.compile("wxpay://[^\"'\\s<]+|weixin://openNativeUrl/weixinHB[^\"'\\s<]+");
    private static final String C_LUCKY_UTIL = "com.tencent.mm.plugin.luckymoney.model.o5";
    private static final String C_CFG_LOGIC = "b41.y1";
    private static final String ANCHOR_APPMSG_HB = "wxpay://c2cbizmessagehandler/hongbao/receivehongbao";
    private static final String ANCHOR_APPMSG_BIZ = "weixin://openNativeUrl/weixinHB/startreceivebizhbrequest";
    private static final String ANCHOR_APPMSG = "MicroMsg.AppMessage";
    private static final String ANCHOR_APPMSG_XML = "originXml";
    private static final String ANCHOR_APPMSG_NEW = "summerbig cdntra doscene AppMessage.Content.parse";
    private static final String ANCHOR_MSG_STORAGE = "CREATE TABLE IF NOT EXISTS message";
    private static final String ANCHOR_MSG_INSERT = "insert failed";
    private static final String SESSION_SUFFIX = "@chatroom";

    private static volatile boolean sEnabled = false;
    private static volatile boolean sGroupOnly = true;
    private static volatile int sMaxPerMin = 10;
    private static volatile boolean sDelayEnabled = false;
    private static volatile int sDelayMax = 0;
    private static volatile String sWhitelist = "";
    private static volatile boolean sVoiceEnabled = false;

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
    /** 已播报金额的红包 sendId（防止手动/自动抢重复播报）。 */
    private static final Set<String> sSpoken = ConcurrentHashMap.newKeySet();
    private static final Set<String> sStatsRecorded = ConcurrentHashMap.newKeySet();
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
    /** sendId → 是否群红包。入库路径按会话 id 判定，手动抢由 h6 scene 字段扫描补全。 */
    private static final Map<String, Boolean> sSendIdGroup = new ConcurrentHashMap<>();
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
        sDelayEnabled = sp.getBoolean(K_DELAY_ENABLED, false);
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
    public static boolean isDelayEnabled() { return sDelayEnabled; }
    public static void setDelayEnabled(boolean on) { putBool(K_DELAY_ENABLED, on); sDelayEnabled = on; }

    public static String getWhitelist() {
        SharedPreferences sp = safePrefs();
        return sp == null ? "" : sp.getString(K_WHITELIST, "");
    }

    public static void setWhitelist(String v) { putStr(K_WHITELIST, v); sWhitelist = v; }

    /** 红包语音播报开关（读 prefs，默认关——v3.0.570 修复：未开开关不再播报） */
    public static boolean isVoiceEnabled() {
        SharedPreferences sp = safePrefs();
        return sp != null && sp.getBoolean(K_VOICE_ENABLED, false);
    }

    /** 设置红包语音播报开关 */
    public static void setVoiceEnabled(boolean on) {
        putBool(K_VOICE_ENABLED, on);
        sVoiceEnabled = on;
    }

    // ==================== 抢到红包自动回复（v3.0.560 新增） ====================

    /** 抢到红包自动回复开关 */
    public static boolean isAutoReplyEnabled() {
        SharedPreferences sp = safePrefs();
        return sp != null && sp.getBoolean(K_AUTO_REPLY, false);
    }
    public static void setAutoReplyEnabled(boolean on) { putBool(K_AUTO_REPLY, on); }

    /** 自动回复使用 TTS 语音（false=文本） */
    public static boolean isAutoReplyTts() {
        SharedPreferences sp = safePrefs();
        return sp != null && sp.getBoolean(K_AUTO_REPLY_TTS, false);
    }
    public static void setAutoReplyTts(boolean on) { putBool(K_AUTO_REPLY_TTS, on); }

    /** 回复语列表（text + enabled 勾选状态），返回 String[]{text, enabled} 数组 */
    public static java.util.List<String[]> getReplyItems() {
        java.util.List<String[]> out = new ArrayList<>();
        SharedPreferences sp = safePrefs();
        if (sp == null) return out;
        try {
            JSONArray arr = new JSONArray(sp.getString(K_AUTO_REPLY_TEXTS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String t = o.optString("text", "").trim();
                if (t.isEmpty()) continue;
                out.add(new String[]{t, o.optBoolean("enabled", true) ? "1" : "0"});
            }
        } catch (Throwable ignored) {}
        return out;
    }
    public static void setReplyItems(java.util.List<String[]> items) {
        try {
            JSONArray arr = new JSONArray();
            if (items != null) {
                for (String[] it : items) {
                    if (it == null || it[0] == null || it[0].trim().isEmpty()) continue;
                    JSONObject o = new JSONObject();
                    o.put("text", it[0].trim());
                    o.put("enabled", it.length > 1 && ("1".equals(it[1]) || "true".equalsIgnoreCase(it[1])));
                    arr.put(o);
                }
            }
            putStr(K_AUTO_REPLY_TEXTS, arr.toString());
        } catch (Throwable ignored) {}
    }

    /** TTS 回复语列表（text + enabled 勾选状态），与文字回复语分开存储 */
    public static java.util.List<String[]> getTtsReplyItems() {
        java.util.List<String[]> out = new ArrayList<>();
        SharedPreferences sp = safePrefs();
        if (sp == null) return out;
        try {
            JSONArray arr = new JSONArray(sp.getString(K_AUTO_REPLY_TTS_TEXTS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String t = o.optString("text", "").trim();
                if (t.isEmpty()) continue;
                out.add(new String[]{t, o.optBoolean("enabled", true) ? "1" : "0"});
            }
        } catch (Throwable ignored) {}
        return out;
    }
    public static void setTtsReplyItems(java.util.List<String[]> items) {
        try {
            JSONArray arr = new JSONArray();
            if (items != null) {
                for (String[] it : items) {
                    if (it == null || it[0] == null || it[0].trim().isEmpty()) continue;
                    JSONObject o = new JSONObject();
                    o.put("text", it[0].trim());
                    o.put("enabled", it.length > 1 && ("1".equals(it[1]) || "true".equalsIgnoreCase(it[1])));
                    arr.put(o);
                }
            }
            putStr(K_AUTO_REPLY_TTS_TEXTS, arr.toString());
        } catch (Throwable ignored) {}
    }

    /** 仅返回勾选启用的 TTS 回复语 */
    public static String[] getEnabledTtsReplyTexts() {
        java.util.List<String> out = new ArrayList<>();
        for (String[] it : getTtsReplyItems()) {
            if ("1".equals(it[1])) out.add(it[0]);
        }
        return out.toArray(new String[0]);
    }

    /** 仅返回勾选启用的回复语 */
    public static String[] getEnabledReplyTexts() {
        java.util.List<String> out = new ArrayList<>();
        for (String[] it : getReplyItems()) {
            if ("1".equals(it[1])) out.add(it[0]);
        }
        return out.toArray(new String[0]);
    }

    /** 抢到红包后随机选一条回复语，发送文本或 TTS 语音到红包会话。 */
    private static void maybeAutoReply(String session, String who) {
        try {
            if (session == null || session.isEmpty()) return;
            if (!isAutoReplyEnabled()) return;
            boolean tts = isAutoReplyTts();
            // TTS 回复用 TTS 专属勾选回复语；文字回复用文字回复语
            String[] texts = tts ? getEnabledTtsReplyTexts() : getEnabledReplyTexts();
            if (texts == null || texts.length == 0) return;
            String reply = texts[sRandom.nextInt(texts.length)];
            if (reply == null || reply.isEmpty()) return;
            ClassLoader cl = ContextManager.getClassLoader();
            if (tts) {
                // TTS 语音回复到红包会话（发语音消息）；合成失败回退文本
                com.leshao.v3.hook.TtsVoiceSender.sendAiReplyAsVoice(session, reply, null,
                        () -> {
                            try {
                                if (cl != null) com.leshao.v3.wm.utils.WmReflect.sendTextMsg(cl, reply, session);
                            } catch (Throwable ignored) {}
                        });
            } else {
                // 文本回复
                if (cl != null) {
                    com.leshao.v3.wm.utils.WmReflect.sendTextMsg(cl, reply, session);
                }
            }
            LogWriter.log(TAG, "红包自动回复 session=" + session + " reply=" + reply + " tts=" + tts);
        } catch (Throwable t) {
            LogWriter.log(TAG, "maybeAutoReply err: " + t);
        }
    }

    /** 红包收入统计：[总金额, 群聊金额, 私聊金额]（单位元，字符串） */
    public static String[] getIncomeStats() {
        try {
            SharedPreferences sp = safePrefs();
            if (sp == null) return new String[]{"0.00", "0.00", "0.00"};
            JSONObject o = new JSONObject(sp.getString(K_STATS, "{}"));
            return new String[]{
                fmtFen(o.optLong("total", 0L)),
                fmtFen(o.optLong("group", 0L)),
                fmtFen(o.optLong("private", 0L))
            };
        } catch (Throwable t) {
            return new String[]{"0.00", "0.00", "0.00"};
        }
    }

    /** 群聊红包排行：每项 [群名, 昵称, 次数, 金额元]（按金额降序） */
    public static java.util.List<String[]> getRankList() {
        java.util.List<String[]> list = new java.util.ArrayList<>();
        try {
            SharedPreferences sp = safePrefs();
            if (sp == null) return list;
            JSONObject rank = new JSONObject(sp.getString(K_STATS, "{}")).optJSONObject("rank");
            if (rank == null) return list;
            java.util.Iterator<String> keys = rank.keys();
            while (keys.hasNext()) {
                String room = keys.next();
                JSONObject it = rank.optJSONObject(room);
                if (it != null) {
                    list.add(new String[]{
                        room,
                        it.optString("name", room),
                        String.valueOf(it.optInt("count", 0)),
                        fmtFen(it.optLong("amount", 0L))
                    });
                }
            }
            list.sort(new java.util.Comparator<String[]>() {
                @Override public int compare(String[] a, String[] b) {
                    return Long.compare(parseFen(b[3]), parseFen(a[3]));
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "getRankList err: " + t);
        }
        return list;
    }

    /** 金额字符串（元）转分 */
    private static long parseFen(String s) {
        try {
            return new java.math.BigDecimal(s).movePointRight(2).longValue();
        } catch (Throwable t) {
            return 0L;
        }
    }

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
            // v3.0.132: 安装阶段 prefs 已就绪，强制刷新配置（hook() 的 defer 早期 prefs 可能为 null）
            updateConfig();
            // v3.0.577: 红包 scene 类(n6)构造器内部会调用 pp0.i1.j()/x() 访问内核 ConfigStorage。
            // 若 n6 由 base.apk 平行 CL(PathClassLoader) 加载, 其 pp0.i1 静态内核从未初始化
            // → newInstance 抛 InvocationTargetException(实测 grab failed)。必须切真实 Tinker CL。
            ClassLoader real = resolveRealCl(cl);
            resolveScene(real);
            resolveOpenScene(real);
            resolveNetBase(real);
            hookAppMsgParse(real);
            hookTalkerMapping(real);
            hookNetBaseEnd(real);
            hookRecvEnd(real);
            hookOpenEnd(real);
            LogWriter.log(TAG, "hooked (cl=" + real.getClass().getSimpleName()
                    + " sceneCtor=" + (sSceneCtor != null) + ")");
            // v3.0.577: 启动早期 Tinker CL 可能尚未就绪, 用 base CL 解析到的 n6 其内部内核未初始化
            // → 抢红包 newInstance 抛异常。后台等真实 CL 就绪后用真实 CL 重新解析覆盖。
            scheduleSceneResolveRetry(cl);
        } catch (Throwable e) {
            LogWriter.log(TAG, "hook err: " + e);
        }
    }

    /** 切到微信真实运行时 CL(Tinker/DelegateLastClassLoader), 拿不到则返回原 CL。 */
    private static ClassLoader resolveRealCl(ClassLoader cl) {
        try {
            ClassLoader tk = com.leshao.v3.hook.VersionCompat.findTinkerClassLoader(cl);
            if (tk != null && !tk.getClass().getName().contains("Leshao") && tk != cl) {
                LogWriter.log(TAG, "resolveRealCl: 使用 Tinker 真实 CL " + tk.getClass().getSimpleName());
                return tk;
            }
        } catch (Throwable ignored) {
        }
        try {
            ClassLoader cm = com.leshao.v3.ContextManager.getTinkerClassLoader();
            if (cm != null && !cm.getClass().getName().contains("Leshao") && cm != cl) {
                return cm;
            }
        } catch (Throwable ignored) {
        }
        return cl;
    }

    /** v3.0.577: scene 未解析时后台重试(等 Tinker CL 就绪), 直到 sSceneCtor 命中。 */
    private static void scheduleSceneResolveRetry(final ClassLoader baseCl) {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                try { Thread.sleep(800L); } catch (InterruptedException ignored) { return; }
                ClassLoader real = resolveRealCl(baseCl);
                if (real == baseCl) continue; // 真实 CL 仍未就绪
                // 已用真实 CL 解析过 → 完成
                if (sRecvCls != null && sRecvCls.getClassLoader() == real) {
                    return;
                }
                try {
                    // 用真实 CL 重新解析(覆盖此前 base CL 解析到的错误 scene 类)
                    sSceneCtor = null;
                    sRecvCls = null;
                    resolveScene(real);
                    if (sOpenCls == null || sOpenCls.getClassLoader() != real) {
                        sOpenCtor = null;
                        sOpenCls = null;
                        resolveOpenScene(real);
                    }
                    if (sSceneCtor != null && sRecvCls != null
                            && sRecvCls.getClassLoader() == real) {
                        LogWriter.log(TAG, "scheduleSceneResolveRetry: 真实 CL 重解析命中(try=" + i
                                + ") " + sRecvCls.getName());
                        // v3.0.579: 关键修复 —— 重解析后必须在新类上补装 onGYNetEnd hook，
                        // 否则 hook 仍挂在 base CL 的旧类上，运行时不触发（红包到账通知永不播报）。
                        hookRecvEnd(real);
                        hookOpenEnd(real);
                        return;
                    }
                } catch (Throwable e) {
                    LogWriter.log(TAG, "scheduleSceneResolveRetry err: " + e);
                }
            }
        }, "ls-redpacket-scene");
        t.setDaemon(true);
        t.start();
    }

    private static void resolveScene(ClassLoader cl) {
        // v3.0.464: 8.0.79 中 n6(NetSceneReceiveLuckyMoney) 已改混淆名，权威类名失效。
        // 改用运行时动态查找：NetSceneLuckyMoneyBase(q5, 已反编译确认存在) 的所有子类中找
        // 7 参构造器 (int,int,String,String,int,String,String) + doScene(dispatcher, callback)。
        Class<?> bySuper = findSceneBySuper(cl, "com.tencent.mm.plugin.luckymoney.model.q5", 7, true);
        if (bySuper != null) {
            LogWriter.log(TAG, "scene resolved by q5-subclass: " + bySuper.getName());
            return;
        }
        Class<?> direct = tryLoadScene(cl, "com.tencent.mm.plugin.luckymoney.model.n6", 7);
        if (direct != null) return;
        List<String> cands = HookUtil.classCandidates(cl, ANCHOR_CGI, "NetSceneReceiveLuckyMoney request");
        if (cands.isEmpty()) {
            LogWriter.log(TAG, "n6 未定位，抢红包不可用");
            return;
        }
        for (String sceneCls : cands) {
            if (tryLoadScene(cl, sceneCls, 7) != null) return;
        }
        LogWriter.log(TAG, "n6 构造签名未匹配 candidates=" + cands);
    }

    /**
     * v3.0.464: 用 DexKit 查找指定类的所有子类，再按构造器参数个数/类型匹配红包场景类。
     * 8.0.79 中 NetSceneReceiveLuckyMoney/NetSceneOpenLuckyMoney 的混淆类名无法静态确认，
     * 但它们的父类 NetSceneLuckyMoneyBase 即 com.tencent.mm.plugin.luckymoney.model.q5
     * （已反编译验证存在，含 7 参 onGYNetEnd），子类构造器签名模式保持稳定。
     */
    private static Class<?> findSceneBySuper(ClassLoader cl, String superClass, int params, boolean checkDoScene) {
        try {
            ClassDataList list = DexKitHelper.withWechatBridge(cl, new DexKitHelper.BridgeAction<ClassDataList>() {
                @Override
                public ClassDataList run(org.luckypray.dexkit.DexKitBridge bridge) {
                    return bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                            .superClass(superClass, StringMatchType.Equals, false)));
                }
            });
            if (list == null || list.isEmpty()) {
                LogWriter.log(TAG, "findSceneBySuper: q5 无子类命中");
                return null;
            }
            for (ClassData cd : list) {
                String name = cd.getName();
                if (name == null || name.equals(superClass)) continue;
                Class<?> c = null;
                try { c = XposedHelpers.findClass(name, cl); } catch (Throwable ignored) {}
                if (c == null) continue;
                for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length != params) continue;
                    if (params == 7) {
                        if (!(HookUtil.isInt(p[0]) && HookUtil.isInt(p[1])
                                && p[2] == String.class && p[3] == String.class
                                && HookUtil.isInt(p[4])
                                && p[5] == String.class && p[6] == String.class)) {
                            continue;
                        }
                        if (checkDoScene && findDoScene(c) == null) continue;
                        ctor.setAccessible(true);
                        sSceneCtor = ctor;
                        sRecvCls = c;
                        return c;
                    } else {
                        ctor.setAccessible(true);
                        sOpenCtor = ctor;
                        sOpenCls = c;
                        return c;
                    }
                }
            }
            LogWriter.log(TAG, "findSceneBySuper: 子类构造器未匹配 params=" + params);
        } catch (Throwable t) {
            LogWriter.log(TAG, "findSceneBySuper err: " + t);
        }
        return null;
    }

    /** 尝试加载指定红包 scene 类并匹配构造器(构造参数个数 params)，成功返回该类。 */
    private static Class<?> tryLoadScene(ClassLoader cl, String cls, int params) {
        // v3.0.465: 先经 WxResolver 解析（n6/h6 已在注册表注册），但解析成功不能提前返回——
        // 必须继续匹配构造器并设置 sSceneCtor/sOpenCtor，否则运行时 scene ctor 为 null。
        Class<?> c = WxResolver.resolve(cl, cls);
        if (c == null) {
            try {
                c = XposedHelpers.findClass(cls, cl);
            } catch (Throwable t) {
                return null;
            }
        }
        if (c == null) return null;
        for (Constructor<?> ctor : c.getDeclaredConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            if (p.length != params) continue;
            if (params == 7) {
                if (HookUtil.isInt(p[0]) && HookUtil.isInt(p[1])
                        && p[2] == String.class && p[3] == String.class
                        && HookUtil.isInt(p[4])
                        && p[5] == String.class && p[6] == String.class) {
                    ctor.setAccessible(true);
                    if (findDoScene(c) == null) return null;
                    sSceneCtor = ctor;
                    sRecvCls = c;
                    LogWriter.log(TAG, "scene resolved cls=" + c.getName());
                    return c;
                }
            } else {
                ctor.setAccessible(true);
                sOpenCtor = ctor;
                sOpenCls = c;
                LogWriter.log(TAG, "h6 resolved cls=" + c.getName());
                resolveHeadNick(cl);
                return c;
            }
        }
        return null;
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
        // v3.0.464: 同样先走 q5 子类动态查找（10 参构造器），不再依赖已失效的 h6。
        Class<?> bySuper = findSceneBySuper(cl, "com.tencent.mm.plugin.luckymoney.model.q5", 10, false);
        if (bySuper != null) {
            LogWriter.log(TAG, "open scene resolved by q5-subclass: " + bySuper.getName());
            resolveHeadNick(cl);
            return;
        }
        Class<?> direct = tryLoadScene(cl, "com.tencent.mm.plugin.luckymoney.model.h6", 10);
        if (direct != null) return;
        List<String> cands = HookUtil.classCandidates(cl, ANCHOR_CGI_OPEN, "NetSceneOpenLuckyMoney request");
        if (cands.isEmpty()) {
            LogWriter.log(TAG, "h6 未定位，第二步不可用");
            return;
        }
        for (String sceneCls : cands) {
            if (tryLoadScene(cl, sceneCls, 10) != null) return;
        }
        LogWriter.log(TAG, "h6 构造签名未匹配 candidates=" + cands);
    }

    /** 定位自己头像 headImg 与昵称，供 openwxhb 请求体使用。
     *  v3.0.464: 8.0.79 中 o5(b41.y1) 已删除，改用 DexKit 在 luckymoney 包内动态查找：
     *  头像 = 静态无参方法 l() 返回 String；昵称 = 静态无参方法 m() 返回 String。找不到时
     *  降级为 null（打开红包请求体缺省字段，微信容忍），不影响抢红包主流程。 */
    private static void resolveHeadNick(ClassLoader cl) {
        try {
            Class<?> luckyUtil = findLuckyMoneyUtil(cl);
            if (luckyUtil != null) {
                for (Method m : luckyUtil.getDeclaredMethods()) {
                    if ("l".equals(m.getName()) && m.getParameterTypes().length == 0
                            && m.getReturnType() == String.class) {
                        m.setAccessible(true);
                        sHeadImgMethod = m;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "o5.l 未定位: " + t);
        }
        try {
            Class<?> luckyNick = findLuckyMoneyNickClass(cl);
            if (luckyNick != null) {
                for (Method m : luckyNick.getDeclaredMethods()) {
                    if ("m".equals(m.getName()) && m.getParameterTypes().length == 0
                            && m.getReturnType() == String.class) {
                        m.setAccessible(true);
                        sNickMethod = m;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "b41.y1.m 未定位: " + t);
        }
    }

    /** 在 luckymoney 包内找无参 l() 返回 String 的类（8.0.79 头像工具）。 */
    private static Class<?> findLuckyMoneyUtil(ClassLoader cl) {
        try {
            final List<String> hits = new ArrayList<>();
            DexKitHelper.withWechatBridge(cl, new DexKitHelper.BridgeAction<Void>() {
                @Override
                public Void run(org.luckypray.dexkit.DexKitBridge bridge) {
                    org.luckypray.dexkit.result.MethodDataList list = bridge.findMethod(
                            org.luckypray.dexkit.query.FindMethod.create().matcher(
                                    org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                                            .name("l", StringMatchType.Equals, false)
                                            .paramCount(0)
                                            .returnType("java.lang.String", StringMatchType.Equals, false)));
                    if (list != null) {
                        for (org.luckypray.dexkit.result.MethodData md : list) {
                            String cn = md.getDeclaredClassName();
                            if (cn != null && cn.contains("luckymoney")) hits.add(cn);
                        }
                    }
                    return null;
                }
            });
            if (!hits.isEmpty()) {
                for (String cn : hits) {
                    try { return XposedHelpers.findClass(cn, cl); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 在 luckymoney 包内找无参 m() 返回 String 的类（8.0.79 昵称工具）。 */
    private static Class<?> findLuckyMoneyNickClass(ClassLoader cl) {
        try {
            final List<String> hits = new ArrayList<>();
            DexKitHelper.withWechatBridge(cl, new DexKitHelper.BridgeAction<Void>() {
                @Override
                public Void run(org.luckypray.dexkit.DexKitBridge bridge) {
                    org.luckypray.dexkit.result.MethodDataList list = bridge.findMethod(
                            org.luckypray.dexkit.query.FindMethod.create().matcher(
                                    org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                                            .name("m", StringMatchType.Equals, false)
                                            .paramCount(0)
                                            .returnType("java.lang.String", StringMatchType.Equals, false)));
                    if (list != null) {
                        for (org.luckypray.dexkit.result.MethodData md : list) {
                            String cn = md.getDeclaredClassName();
                            if (cn != null && cn.contains("luckymoney")) hits.add(cn);
                        }
                    }
                    return null;
                }
            });
            if (!hits.isEmpty()) {
                for (String cn : hits) {
                    try { return XposedHelpers.findClass(cn, cl); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 定位 H2 统一出口：q5（NetSceneLuckyMoneyBase）的 7 参 onGYNetEnd（文档 §4 H2）。 */
    private static void resolveNetBase(ClassLoader cl) {
        // v3.0.464: 8.0.79 中 q5 即 com.tencent.mm.plugin.luckymoney.model.q5（已反编译确认），
        // 直接加载；不再依赖从子类父类链向上找（旧 n6/h6 类名已失效）。
        try {
            Class<?> q5 = XposedHelpers.findClass("com.tencent.mm.plugin.luckymoney.model.q5", cl);
            for (Method m : q5.getDeclaredMethods()) {
                if ("onGYNetEnd".equals(m.getName()) && m.getParameterTypes().length == 7) {
                    sNetBaseCls = q5;
                    LogWriter.log(TAG, "netbase resolved cls=" + q5.getName());
                    return;
                }
            }
            LogWriter.log(TAG, "q5 无 7 参 onGYNetEnd");
        } catch (Throwable t) {
            LogWriter.log(TAG, "q5 未定位: " + t);
        }
        // 兜底：从已解析场景类父类链找（旧逻辑，兼容旧版微信）
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

    /** v3.0.579: 已安装过 onGYNetEnd hook 的类，防止 Tinker CL 重解析后重复挂载。 */
    private static final java.util.Set<Class<?>> sEndHooked = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean>());

    /** H3：n6.onGYNetEnd(int,String,JSONObject) → 解析 timingIdentifier 并发第二步。 */
    private static void hookRecvEnd(ClassLoader cl) {
        if (sRecvCls == null || sEndHooked.contains(sRecvCls)) return;
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
            sEndHooked.add(sRecvCls);
            LogWriter.log(TAG, "hooked n6.onGYNetEnd cls=" + sRecvCls.getName());
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookRecvEnd err: " + t);
        }
    }

    /** H4：h6.onGYNetEnd(int,String,JSONObject) → 读取金额。 */
    private static void hookOpenEnd(ClassLoader cl) {
        if (sOpenCls == null || sEndHooked.contains(sOpenCls)) return;
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
            sEndHooked.add(sOpenCls);
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
        Object e1 = XposedHelpers.getObjectField(scene, "h");
        // v3.0.579: 8.0.79 真实 dex 复核 —— h6 字段语义：
        //   i = sendId, m = nativeUrl, n = sessionUserName, h = e1(金额模型)
        //   旧实现取 "m"(nativeUrl) 当 sendId，导致到账/播报/统计全链路错位。
        // e1.n 也是 sendId（i5.e 解析 "sendId"），优先取它，其次 h6.i。
        String sendId = null;
        if (e1 != null) sendId = strField(e1, "n");
        if (sendId == null || sendId.isEmpty()) sendId = strField(scene, "i");
        if (sendId == null || sendId.isEmpty()) return;
        // 手动抢的红包 sendId 不在 sHandled 中；这里不依赖自动抢标记，只做去重防重复播报
        if (!sSpoken.add(sendId)) return;
        if (sSpoken.size() > 10000) sSpoken.clear();
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
        // v3.0.579: h6.n = sessionUserName（构造器 str5），直接读，最可靠
        if (session == null || session.isEmpty()) {
            String direct = strField(scene, "n");
            if (direct != null && !direct.isEmpty()) session = direct;
        }
        // 兜底：从 scene 字段扫描会话 id（群=*@chatroom，私聊=wxid_*）
        if (session == null || session.isEmpty()) {
            String sceneSession = scanSessionFromScene(scene);
            if (sceneSession != null && !sceneSession.isEmpty()) session = sceneSession;
        }
        Boolean groupFlag = sSendIdGroup.get(sendId);
        boolean isGroup = groupFlag != null ? groupFlag : isGroupTalker(session);
        LogWriter.log(TAG, "红包到账 sendId=" + sendId + " amount=" + amountFen + "分"
                + " nick=" + nick + " user=" + user + " recv=" + recvStatus
                + " " + recNum + "/" + totalNum + " session=" + session
                + " group=" + isGroup + " msg=" + statusMess);

        String who = (nick == null || nick.isEmpty()) ? user : nick;
        if (amountFen > 0) {
            recordIncome(sendId, session, who, amountFen, isGroup);
            speakAmount(who, amountFen);
            notifyGrab(sendId, session, amountFen, who,
                    recNum + "/" + totalNum + "个 · 共" + fmtFen(totalAmount));
            maybeAutoReply(session, who);
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

    /** 本地红包记录表已领判断：field_receiveAmount>0 即跳过（文档 §5.2）。
     *  v3.0.529: 8.0.79 红包记录服务由 lj0.a3/ij()/t1() 迁移到 tj0.a3/Ej()/Q1(String)，
     *  Q1 返回 p15.o1(extends im.ec)，同样含 field_receiveAmount。按版本分流，旧锚点保留。 */
    private static boolean isAlreadyReceived(String nativeUrl) {
        if (sCl == null || nativeUrl == null) return false;
        try {
            Class<?> pluginCls = XposedHelpers.findClass("ni5.n0", sCl);
            boolean isNew = WeChatVersion.isNew();
            Class<?> pluginItf = XposedHelpers.findClass(
                    isNew ? "tj0.a3" : "lj0.a3", sCl);
            Object plugin;
            try {
                plugin = XposedHelpers.callStaticMethod(pluginCls, "c", pluginItf);
            } catch (Throwable t1) {
                // 旧版定位器兜底
                pluginCls = XposedHelpers.findClass("ph5.n0", sCl);
                plugin = XposedHelpers.callStaticMethod(pluginCls, "c", pluginItf);
            }
            if (plugin == null) return false;
            Object dao = XposedHelpers.callMethod(plugin, isNew ? "Ej" : "ij");
            if (dao == null) return false;
            Object rec = XposedHelpers.callMethod(dao, isNew ? "Q1" : "t1", nativeUrl);
            if (rec == null) return false;
            Object amt = XposedHelpers.getObjectField(rec, "field_receiveAmount");
            return amt instanceof Number && ((Number) amt).longValue() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 到账/失败通知（文档 §5.3）。 */
    /** 红包到账语音播报：开关开启时用 TTS 播报金额（「领取红包自动播报金额」） */
    private static void speakAmount(String who, long amountFen) {
        try {
            // 用 isVoiceEnabled()（读 prefs，默认开）而非静态字段（可能未从 prefs 加载）
            if (isVoiceEnabled() && amountFen > 0) {
                String name = (who == null || who.isEmpty()) ? "朋友" : who;
                String text = "收到" + name + "的红包，金额" + fmtFen(amountFen) + "元";
                LogWriter.log(TAG, "voice announce: " + text);
                com.leshao.v3.service.TTSBroadcaster.INSTANCE.speakText(text);
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "speakAmount err: " + t);
        }
    }

    /** 红包到账统计：按群/私聊累加金额与次数，写入 ls_redpacket_stats（供设置页统计/排行） */
    private static synchronized void recordIncome(String sendId, String session, String who,
                                                  long amountFen, boolean group) {
        try {
            if (!sStatsRecorded.add(sendId)) return; // 同一红包只统计一次
            if (sStatsRecorded.size() > 10000) sStatsRecorded.clear();
            SharedPreferences sp = safePrefs();
            if (sp == null) return;
            JSONObject o = new JSONObject(sp.getString(K_STATS, "{}"));
            o.put("total", o.optLong("total", 0L) + amountFen);
            if (group) {
                o.put("group", o.optLong("group", 0L) + amountFen);
            } else {
                o.put("private", o.optLong("private", 0L) + amountFen);
            }
            // v3.0.561: 所有红包（群/私聊）都写入排行榜，按会话聚合（修复排行榜始终为空）
            JSONObject rank = o.optJSONObject("rank");
            if (rank == null) { rank = new JSONObject(); o.put("rank", rank); }
            String key = (session != null && !session.isEmpty()) ? session : who;
            if (key != null && !key.isEmpty()) {
                JSONObject room = rank.optJSONObject(key);
                if (room == null) { room = new JSONObject(); rank.put(key, room); }
                // v3.0.561: 排行榜显示名优先映射会话名（群名/联系人备注），失败才用发红包者昵称
                String display = null;
                try {
                    com.leshao.v3.model.ContactCard cc = com.leshao.v3.ContactRepository.findByUsername(key);
                    if (cc != null && cc.displayName() != null && !cc.displayName().isEmpty()) display = cc.displayName();
                } catch (Throwable ignored) {}
                if (display == null || display.isEmpty()) display = (who == null ? key : who);
                room.put("name", display)
                    .put("count", room.optInt("count", 0) + 1)
                    .put("amount", room.optLong("amount", 0L) + amountFen);
            }
            sp.edit().putString(K_STATS, o.toString()).apply();
        } catch (Throwable ignored) {}
    }

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
            int notifyId = sendId == null ? (int) (System.currentTimeMillis() & 0x7fffffff) : sendId.hashCode();
            nm.notify(notifyId, b.build());
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
                ANCHOR_APPMSG_HB, ANCHOR_APPMSG_BIZ, ANCHOR_APPMSG, ANCHOR_APPMSG_XML,
                ANCHOR_APPMSG_NEW, "parse msg failed");
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
        // v3.0.132: 实时读 prefs，避免静态 sEnabled 与 UI 开关不同步
        if (!isEnabled() || msg == null) return;
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
        // v3.0.132: 实时读 prefs，避免静态 sEnabled 与 UI 开关不同步
        if (!isEnabled() || xml == null || !xml.contains("wcpayinfo")) return;
        LogWriter.log(TAG, "redpacket candidate xml len=" + xml.length());
        // v3.0.570: 提取失败时记录 XML 片段（前 600 字符），便于远程定位红包类型。
        String nativeUrl0 = nativeUrlFromObject(parsed);
        if (nativeUrl0 == null || nativeUrl0.isEmpty()) nativeUrl0 = extractNativeUrl(xml);
        if (nativeUrl0 == null || nativeUrl0.isEmpty()) {
            String snippet = xml.length() > 600 ? xml.substring(0, 600) : xml;
            LogWriter.log(TAG, "nativeUrl 缺失 xml=" + snippet.replace('\n', ' '));
        }
        // 专属红包：exclusive_recv_username 非自己 wxid 必须跳过（文档 §6.3-5）。
        if (isExclusiveNotMine(xml)) {
            LogWriter.log(TAG, "专属红包非本人，跳过");
            return;
        }
        // 文档 §2.2：nativeUrl 是解析后对象 dx0.r 的字段（s1），不是 XML 属性。
        String nativeUrl = nativeUrl0;
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
        if (sGroupOnly) {
            LogWriter.log(TAG, "fallback skip: groupOnly 模式且 talker 未知（解析层早于入库层）");
            return;
        }
        LogWriter.log(TAG, "fallback scheduled: talker 未在解析层命中，0.15s 后按 sendusername 兜底领取");
        final String sendId = query(nativeUrl, "sendid");
        if (sendId == null || sendId.isEmpty()) {
            LogWriter.log(TAG, "fallback abort: sendid 缺失 nativeUrl=" + nativeUrl);
            return;
        }
        final int channelId = parseInt(query(nativeUrl, "channelid"), 1);
        // 单聊场景 sendusername 即会话对方，可直接作为 sessionUsername；
        // 自己发出的包直接跳过。
        final String sender = query(nativeUrl, "sendusername");
        if (sender != null && !sender.isEmpty() && isSelf(sender)) return;
        final String talker = (sender != null && !sender.isEmpty()) ? sender : null;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                // v3.0.132: 实时读 prefs
                if (!isEnabled() || sHandled.contains(sendId)) return;
                // 0.15s 内消息可能已入库：能拿到真实会话就按可信登记（群=@chatroom），
                // 否则仍按 sendusername 兜底，但该路径不污染群/私聊统计。
                String realTalker = lookupTalker(null, nativeUrl);
                if (realTalker == null || realTalker.isEmpty()) realTalker = talker;
                if (!sHandled.add(sendId)) return;
                if (sHandled.size() > 10000) sHandled.clear();
                if (!allowByFrequency()) return;
                boolean trusted = realTalker != null && !realTalker.isEmpty()
                        && !realTalker.equals(sender);
                LogWriter.log(TAG, "fallback grab sendId=" + sendId
                        + " talker=" + realTalker + " trusted=" + trusted);
                grabAsync(sendId, channelId, nativeUrl, realTalker, trusted);
            } catch (Throwable ignored) {}
        }, 150L);
    }

    /** 统一处理：解析 sendId/channelId + 风控 + 异步领取。 */
    private static void handle(String nativeUrl, String talker) {
        // v3.0.132: 实时读 prefs，避免静态 sEnabled 与 UI 开关不同步
        if (!isEnabled()) return;
        if (nativeUrl == null || nativeUrl.isEmpty()) return;
        String sendId = query(nativeUrl, "sendid");
        if (sendId == null || sendId.isEmpty()) {
            LogWriter.log(TAG, "handle abort: sendid 缺失 nativeUrl=" + nativeUrl);
            return;
        }
        int channelId = parseInt(query(nativeUrl, "channelid"), 1);

        if (sGroupOnly && (talker == null || !talker.endsWith(SESSION_SUFFIX))) {
            LogWriter.log(TAG, "非群聊/会话未知，跳过 talker=" + talker);
            return;
        }
        if (isSelf(talker)) {
            LogWriter.log(TAG, "自己发出的红包跳过 sendId=" + sendId);
            return;
        }

        String key = sendId;
        if (!sHandled.add(key)) return;
        // 入库/解析层拿到的 talker 是可信会话 id：群=xxx@chatroom，私聊=对方 wxid
        if (talker != null && !talker.isEmpty()) {
            sSendIdSession.put(sendId, talker);
            sSendIdGroup.put(sendId, isGroupTalker(talker));
        }
        // 本地红包记录表已领过（field_receiveAmount>0）则跳过（文档 §5.2）。
        if (isAlreadyReceived(nativeUrl)) {
            LogWriter.log(TAG, "本地已领，跳过 key=" + key);
            return;
        }
        if (!allowByFrequency()) {
            LogWriter.log(TAG, "频控拦截 key=" + key);
            return;
        }
        if (!whitelisted(talker)) {
            LogWriter.log(TAG, "白名单拦截 sendId=" + key + " talker=" + talker);
            return;
        }

        LogWriter.log(TAG, "nativeUrl=" + nativeUrl + " talker=" + talker);
        grabAsync(sendId, channelId, nativeUrl, talker, true);
    }

    /** 从解析后的 AppMessage 对象扫描出 nativeUrl 字段（8.0.79 为 s1，深层字段也兜底）。 */
    private static String nativeUrlFromObject(Object obj) {
        if (obj == null) return null;
        // v3.0.570: 8.0.79 的 nativeUrl 存在 nx0.r.s1（wcpayinfo.nativeurl 经 v8.s1 解码后的值）。
        // 该值可能是完整 wxpay:// URL 或编码后的 URL，直接反射读取，不依赖前缀匹配。
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField("s1");
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v instanceof String) {
                            String s = (String) v;
                            if (s != null && !s.isEmpty()) return s;
                        }
                    }
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (Throwable ignored) {}
        String found = findWxpayString(obj, new java.util.HashSet<>(), 0);
        if (found != null) return found;
        // 旧版兜底：直接遍历 String 字段（dx0.r.s1 等）
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

    /** 递归遍历对象图，找 wxpay:// 或 weixin://openNativeUrl/weixinHB 开头的字符串。 */
    private static String findWxpayString(Object o, java.util.Set<Object> seen, int depth) {
        if (o == null || depth > 5 || seen.contains(o)) return null;
        if (o instanceof String) {
            String s = (String) o;
            return (s.startsWith("wxpay://") || s.startsWith("weixin://openNativeUrl/weixinHB")) ? s : null;
        }
        if (o instanceof CharSequence || o instanceof Number || o instanceof Boolean
                || o instanceof Character) return null;
        seen.add(o);
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v == null) continue;
                    String r = findWxpayString(v, seen, depth + 1);
                    if (r != null) return r;
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
        // v3.0.561: 去掉每分钟最多抢次数限制，默认无上限（不再做 prefs 频控写盘）
        return true;
    }

    /** 会话 id 是否群聊：@chatroom / @im.chatroom / @lbsroom（与企业微信互通群一致）。 */
    private static boolean isGroupTalker(String talker) {
        if (talker == null || talker.isEmpty()) return false;
        return talker.endsWith(SESSION_SUFFIX)
                || talker.endsWith("@im.chatroom")
                || talker.endsWith("@lbsroom");
    }

    /**
     * 扫描红包 scene（n6/h6）所有 String 字段，找出会话 id。
     * 群红包 = xxx@chatroom/@im.chatroom/@lbsroom；私聊 = wxid_*。
     * 排除 nativeUrl（wxpay://...）、头像 URL、昵称等明显非会话字段。
     */
    private static String scanSessionFromScene(Object scene) {
        if (scene == null) return null;
        for (Class<?> c = scene.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(scene);
                    if (!(v instanceof String)) continue;
                    String s = (String) v;
                    if (s == null || s.isEmpty()) continue;
                    if (isGroupTalker(s)) return s;
                    if (s.startsWith("wxid_") || s.startsWith("@openim")
                            || s.startsWith("gh_")) return s;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static void grabAsync(final String sendId, final int channelId,
                                  final String nativeUrl, final String talker,
                                  final boolean sessionTrusted) {
        // v3.0.561: 随机延迟受开关控制（关闭=无延迟；开启但未设上限时默认 2000ms）
        final int delayMax = sDelayEnabled ? (sDelayMax > 0 ? sDelayMax : 2000) : 0;
        final int delay = sDelayEnabled ? sRandom.nextInt(delayMax) : 0;
        Thread t = new Thread(() -> {
            try {
                if (delay > 0) Thread.sleep(delay);
                grab(sendId, channelId, nativeUrl, talker, sessionTrusted);
            } catch (Throwable e) {
                LogWriter.log(TAG, "grab failed: " + e);
            }
        }, "leshao-redpacket");
        t.setDaemon(true);
        t.start();
    }

    private static void grab(String sendId, int channelId, String nativeUrl, String talker,
                             boolean sessionTrusted) throws Throwable {
        if (sSceneCtor == null) {
            LogWriter.log(TAG, "scene ctor 未解析，放弃");
            return;
        }
        // v3.0.577: 若 scene 类来自非真实 CL(启动早期 base CL 解析, 内部内核未初始化),
        // 抢红包前用真实 CL 重新解析覆盖, 避免 newInstance 抛 InvocationTargetException。
        ClassLoader realCl = sCl != null ? resolveRealCl(sCl) : null;
        if (realCl != null && realCl != sCl
                && (sRecvCls == null || sRecvCls.getClassLoader() != realCl)) {
            try {
                sSceneCtor = null;
                sRecvCls = null;
                resolveScene(realCl);
                LogWriter.log(TAG, "grab: scene 切到真实 CL -> "
                        + (sRecvCls != null ? sRecvCls.getName() : "null"));
            } catch (Throwable e) {
                LogWriter.log(TAG, "grab: scene 真实 CL 重解析失败 " + e);
            }
            if (sSceneCtor == null) {
                LogWriter.log(TAG, "scene ctor 未解析(真实 CL)，放弃");
                return;
            }
        }
        // 只有可信会话（消息入库拿到的 talker）才登记统计分类；
        // fallback 的 talker 是 sendusername（发送者 wxid），群红包会污染群/私聊判定。
        if (sessionTrusted && talker != null && !talker.isEmpty()) {
            sSendIdSession.put(sendId, talker);
            sSendIdGroup.put(sendId, isGroupTalker(talker));
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
        // v3.0.566: 8.0.79 红包 XML 可能不含 <nativeurl> 标签，nativeUrl 存在解析对象的 s1 字段。
        // 直接从 XML 文本里搜 wxpay:// 红包 URL（receivehongbao / openwxhb），不依赖标签名。
        java.util.regex.Matcher m = P_WXPAY_URL.matcher(xml);
        if (m.find()) {
            String u = m.group(0);
            if (u.contains("&amp;")) u = u.replace("&amp;", "&");
            if (u.contains("&#38;")) u = u.replace("&#38;", "&");
            try { return URLDecoder.decode(u, "UTF-8"); } catch (Throwable t) { return u; }
        }
        // v3.0.570: wcpayinfo 块内提取 nativeurl 元素（微信解析路径 .msg.appmsg.wcpayinfo.nativeurl）。
        // 块内可能带 CDATA/实体转义/大小写变体/命名空间前缀。
        String wc = block(xml, "wcpayinfo");
        if (wc != null) {
            String raw = element(wc, "nativeurl");
            if (raw == null) raw = element(wc, "nativeUrl");
            if (raw == null) raw = attr(wc, "nativeurl=\"");
            if (raw == null) raw = attr(wc, "nativeUrl=\"");
            if (raw == null) raw = attr(wc, "url=\"");
            if (raw != null && !raw.isEmpty()) {
                return decodeUrl(raw);
            }
        }
        // 属性式 nativeurl="..." / nativeUrl="..."（全 XML 兜底）
        String raw = attr(xml, "nativeurl=\"");
        if (raw == null) raw = attr(xml, "nativeUrl=\"");
        if (raw == null) raw = attr(xml, "nativeurl='");
        // 元素式 <nativeurl>...</nativeurl>（可能带 CDATA）
        if (raw == null) raw = element(xml, "nativeurl");
        if (raw == null) raw = element(xml, "nativeUrl");
        if (raw == null) return null;
        return decodeUrl(raw);
    }

    private static String decodeUrl(String raw) {
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

    /** 提取第一个 <name>...</name> 块（含全部内容，便于处理嵌套）。 */
    private static String block(String xml, String name) {
        if (xml == null) return null;
        int i = xml.indexOf("<" + name);
        if (i < 0) return null;
        int j = xml.indexOf('>', i);
        if (j < 0) return null;
        j++;
        String openTag = xml.substring(i, j);
        String close = "</" + name + ">";
        int k = xml.indexOf(close, j);
        return k < 0 ? null : xml.substring(j, k);
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
