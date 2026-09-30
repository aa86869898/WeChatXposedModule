package com.leshao.v3.hook;

import android.content.Context;
import android.content.SharedPreferences;

import com.leshao.ai.hook.wechat.StorageHub;
import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static volatile int sDelayMax = 50;
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
    private static Method sDoScene;
    private static Class<?> sCallbackItf;
    private static Class<?> sDispatcherCls;
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
        sDelayMax = sp.getInt(K_DELAY_MAX, 50);
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
        return sp == null ? 50 : sp.getInt(K_DELAY_MAX, 50);
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
        updateConfig();
        DexKitHelper.addPostScanCallback(() -> install(cl));
    }

    private static void install(ClassLoader cl) {
        try {
            resolveScene(cl);
            hookAppMsgParse(cl);
            hookTalkerMapping(cl);
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
                Method doScene = findDoScene(c);
                if (doScene == null) continue;
                sSceneCtor = hit;
                sDoScene = doScene;
                Class<?>[] p = doScene.getParameterTypes();
                sDispatcherCls = p[0];
                sCallbackItf = p[1];
                LogWriter.log(TAG, "scene resolved cls=" + c.getName()
                        + " doScene=" + doScene.getName());
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
        Object scene = sSceneCtor.newInstance(1, channelId, sendId, nativeUrl, 1, "v1.0",
                talker == null ? "" : talker);

        Object queue = StorageHub.get().netSceneQueue();
        if (queue == null) {
            LogWriter.log(TAG, "NetSceneQueue 不可用");
            return;
        }
        Object dispatcher = null;
        try {
            dispatcher = XposedHelpers.callMethod(queue, "k");
        } catch (Throwable ignored) {}

        boolean dispatched = false;
        if (sDoScene != null && dispatcher != null && sCallbackItf != null
                && sDispatcherCls != null && sDispatcherCls.isInstance(dispatcher)) {
            try {
                Object cb = buildCallback(sendId, talker);
                Object ret = sDoScene.invoke(scene, dispatcher, cb);
                LogWriter.log(TAG, "doScene ret=" + ret + " sendId=" + sendId + " talker=" + talker);
                dispatched = true;
            } catch (Throwable e) {
                LogWriter.log(TAG, "doScene err: " + e);
            }
        }
        if (!dispatched) {
            try {
                XposedHelpers.callMethod(queue, "h", scene, 0);
                LogWriter.log(TAG, "queue.h 入队 sendId=" + sendId + " talker=" + talker);
            } catch (Throwable e) {
                LogWriter.log(TAG, "queue.h err: " + e);
            }
        }
    }

    private static Object buildCallback(final String sendId, final String talker) {
        InvocationHandler handler = (proxy, method, args) -> {
            try {
                if ("onSceneEnd".equals(method.getName()) && args != null && args.length >= 4) {
                    int errType = toInt(args[0]);
                    int errCode = toInt(args[1]);
                    String errMsg = args[2] == null ? "" : args[2].toString();
                    Object scene = args[3];
                    onGrabResult(sendId, talker, errType, errCode, errMsg, scene);
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "callback err: " + t);
            }
            return null;
        };
        return Proxy.newProxyInstance(sCallbackItf.getClassLoader(),
                new Class<?>[]{sCallbackItf}, handler);
    }

    private static void onGrabResult(String sendId, String talker,
                                     int errType, int errCode, String errMsg, Object scene) {
        if (errType != 0 || errCode != 0) {
            LogWriter.log(TAG, "grab result fail " + errType + "/" + errCode + " " + errMsg
                    + " sendId=" + sendId + " talker=" + talker);
            return;
        }
        long amount = adaptiveAmount(scene);
        LogWriter.log(TAG, "GRAB OK sendId=" + sendId + " talker=" + talker
                + " amount=" + (amount < 0 ? "?" : amount));
    }

    private static long adaptiveAmount(Object scene) {
        if (scene == null) return -1;
        try {
            for (Field f : scene.getClass().getFields()) {
                if (f.getType() == long.class) {
                    f.setAccessible(true);
                    long v = f.getLong(scene);
                    if (v > 0 && v < 1000000L) return v;
                }
            }
        } catch (Throwable ignored) {}
        return -1;
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

    private static int toInt(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
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
