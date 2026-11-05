package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 微信消息自动转发（自动群发）
 *
 * 链路（见 自动转发.md）：
 *   消息入库 hook: f9.Bb/Db/Hb(e9, ...) after 阶段取 e9（消息实体）
 *   → 过滤: e9.N0()∈指定来源 && type∈{文本族/图片/AppMsg} && !isSend(e9.z0()!=1)
 *   → 分派: 文本→WmReflect.sendTextMsg；图片/AppMsg→AppMsg 发送辅助
 *   → 目标列表循环 = 自动群发
 *
 * 配置存储于 prefs:
 *   ls_autofw_enabled   Boolean
 *   ls_autofw_sources   String (逗号分隔, v3.0.273 起留空=不转发任何来源, 必须显式选择)
 *   ls_autofw_targets   String (逗号分隔)
 *   ls_autofw_types     String (逗号分隔, 留空=文本+AppMsg)
 */
public class AutoForwardHook {

    private static final String TAG = "AutoForward";
    private static final String PKG_WECHAT = "com.tencent.mm";
    private static final String PREF_ENABLED = "ls_autofw_enabled";
    private static final String PREF_SOURCES = "ls_autofw_sources";
    private static final String PREF_TARGETS = "ls_autofw_targets";
    private static final String PREF_TYPES = "ls_autofw_types";
    private static final String PREF_RECORDS = "ls_autofw_records";
    private static final int RECORD_MAX = 200;

    private static volatile boolean sEnabled = false;
    private static volatile Set<String> sSources = ConcurrentHashMap.newKeySet();
    private static volatile List<String> sTargets = new ArrayList<>();
    private static volatile Set<String> sTypes = ConcurrentHashMap.newKeySet(); // "1","3","49"...
    private static final Set<String> sForwarded = ConcurrentHashMap.newKeySet();
    private static volatile boolean sHooked = false;

    /** v3.0.580: 转发在独立单线程执行，避免在微信 insert 回调线程里长时间阻塞。 */
    private static final java.util.concurrent.ExecutorService POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ls-autofw");
                t.setDaemon(true);
                return t;
            });
    private static final java.util.concurrent.atomic.AtomicBoolean sBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 单条转发之间的随机间隔，避免 0.5 秒内连发上千条被微信风控。 */
    private static final int SEND_GAP_MIN_MS = 180;
    private static final int SEND_GAP_MAX_MS = 420;
    /** 连续失败达到该值即中止本批，防止配置失效时对着 500 个群空转。 */
    private static final int MAX_CONSEC_FAIL = 5;

    private AutoForwardHook() {}

    public static boolean isEnabled() { return sEnabled; }
    public static void setEnabled(boolean v) { sEnabled = v; }
    /** 当前转发目标数量（UI 展示 / 熔断提示用）。 */
    public static int targetCount() { return sTargets.size(); }

    public static void updateConfig(SharedPreferences prefs) {
        try {
            if (prefs == null) return;
            sEnabled = prefs.getBoolean(PREF_ENABLED, false);
            sSources.clear();
            sSources.addAll(split(prefs.getString(PREF_SOURCES, "")));
            sTargets = split(prefs.getString(PREF_TARGETS, ""));
            sTypes.clear();
            sTypes.addAll(split(prefs.getString(PREF_TYPES, "")));
            LogWriter.log(TAG, "config: enabled=" + sEnabled + " sources=" + sSources.size()
                + " targets=" + sTargets.size() + " types=" + sTypes);
            if (sTargets.size() > 50) {
                LogWriter.log(TAG, "⚠ 转发目标数量偏大(" + sTargets.size()
                    + ")，单条消息会触发同等数量的发送，已启用限速与熔断");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "updateConfig err: " + t.getMessage());
        }
    }

    public static void setSources(Set<String> src) { sSources = src != null ? src : new HashSet<>(); }
    public static void setTargets(List<String> t) { sTargets = t != null ? t : new ArrayList<>(); }
    public static void setTypes(Set<String> t) { sTypes = t != null ? t : new HashSet<>(); }

    private static List<String> split(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null) return list;
        for (String part : raw.split("[,，;；\n]")) {
            String s = part.trim();
            if (!s.isEmpty()) list.add(s);
        }
        return list;
    }

    /** 消息类型过滤: 匹配指定类型集合或默认识别文本族/图片/AppMsg */
    static boolean typeAllowed(int type) {
        if (!sTypes.isEmpty()) {
            // 语音族：用户勾选了"语音"(34) 时，一并放行旧格式 228 / 0x42000022（与 forwardOne 的语音识别一致）
            if ((type == 34 || type == 228 || type == 0x42000022) && sTypes.contains("34")) return true;
            return sTypes.contains(String.valueOf(type));
        }
        // 默认: 文本族 {1,11,21,31,36,0x42000031} | 图片 3 | AppMsg 卡片族 49/0x42000031/0x11000031
        if (type == 1 || type == 11 || type == 21 || type == 31 || type == 36
            || type == 0x42000031) return true;
        if (type == 3) return true;
        if ((type & 0xffff) == 49 || type == 0x42000031 || type == 0x11000031) return true;
        return false;
    }

    public static void hook(ClassLoader cl) {
        try {
            SharedPreferences prefs = ContextManager.getPrefs();
            updateConfig(prefs);
            // 始终安装消息监听（即使当前 disabled/无目标），这样 UI 开启后立即生效；
            // 运行时由 onMsgInsert 内的 sEnabled/sSources/sTypes 过滤，避免"开启后不转发"。
            if (sHooked) return;
            sHooked = true;

            Class<?> f9 = VersionCompat.findMsgStorageClass(cl);
            if (f9 == null) {
                LogWriter.log(TAG, "hook: f9 null");
                sHooked = false;
                return;
            }
            int hooked = 0;
            for (java.lang.reflect.Method m : f9.getDeclaredMethods()) {
                // v3.0.555: 8.0.79 中 Bb 已改名 Nb/Mb，补充方法名数组
                if (!m.getName().equals("Nb") && !m.getName().equals("Mb")
                    && !m.getName().equals("Bb") && !m.getName().equals("Db")
                    && !m.getName().equals("Hb") && !m.getName().equals("yb")
                    && !m.getName().equals("insertMsgInfo") && !m.getName().equals("processAddMsg")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 1 || pts[0] == null) continue;
                String p0 = pts[0].getName();
                if (!p0.endsWith(".e9") && !p0.contains("MsgInfo")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length < 1 || param.args[0] == null) return;
                            onMsgInsert(param.args[0]);
                        } catch (Throwable e) {
                            LogWriter.log("AutoForward", "cb err: " + e);
                        }
                    }
                });
                LogWriter.log(TAG, "hooked f9." + m.getName() + "(" + pts.length + ") p0=" + p0);
                hooked++;
            }
            LogWriter.log(TAG, "fw hooks installed: " + hooked);
            if (hooked == 0) sHooked = false;
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook FAIL: " + t.getMessage());
        }
    }

    static void onMsgInsert(Object e9) {
        try {
            if (e9 == null) return;
            if (!sEnabled) return;

            String talker = callStr(e9, "N0");
            int type = 0;
            try { type = (Integer) XposedHelpers.callMethod(e9, "getType"); } catch (Throwable ignored) {}

            // ---- v3.0.580: isSend 改为「读不到就不转发」 ----
            // 旧实现 isSend 初值 -1，getter 一旦抛异常就当作「不是自己发的」放行，
            // 于是模块自己插入的消息（点歌提示等）会被当成收到的新消息全量群发。
            int isSend = readIsSend(e9);
            if (isSend < 0) {
                LogWriter.log(TAG, "skip: isSend 读取失败(不转发) talker=" + talker + " type=" + type);
                return;
            }
            if (isSend != 0) return; // 自己发送的不转发

            if (talker == null || talker.isEmpty()) return;
            // 来源过滤: v3.0.273 起来源名单为空 = 不转发任何来源（必须显式选择才工作）
            if (sSources.isEmpty() || !sSources.contains(talker)) return;

            if (!typeAllowed(type)) return;

            String content = readContent(e9);

            // ---- v3.0.580: 模块自发消息护栏（本次事故的根因） ----
            long msgId = 0;
            try { msgId = (Long) XposedHelpers.callMethod(e9, "getMsgId"); } catch (Throwable ignored) {}
            if (msgId != 0 && SelfMsgGuard.isSelfMsgId(msgId)) {
                LogWriter.log(TAG, "skip: 模块自发消息(msgId=" + msgId + ") talker=" + talker);
                return;
            }
            if (SelfMsgGuard.isSelf(talker, type, content)) {
                LogWriter.log(TAG, "skip: 模块自发消息(内容命中) talker=" + talker
                        + " content=" + trunc(content, 30));
                return;
            }

            // 去重: msgSvrId 优先；svrId 为 0 时用「会话+类型+内容」短窗口兜底
            // （旧实现 svrId==0 直接放行 → 同一条消息被转发两次，事故里 545 群各收两条）
            long svrId = 0;
            try { svrId = (Long) XposedHelpers.callMethod(e9, "H0"); } catch (Throwable ignored) {}
            long createTime = 0;
            try { createTime = (Long) XposedHelpers.callMethod(e9, "getCreateTime"); } catch (Throwable ignored) {}
            String dedupKey = svrId != 0
                    ? ("s:" + svrId)
                    : ("c:" + talker + "|" + type + "|" + (content == null ? "" : content.hashCode())
                        + "|" + (createTime / 10));
            if (!sForwarded.add(dedupKey)) {
                LogWriter.log(TAG, "skip: 重复消息 key=" + dedupKey);
                return;
            }
            if (sForwarded.size() > 500) sForwarded.clear();

            // ---- v3.0.580: 群发熔断 ----
            if (!SelfMsgGuard.allowBroadcast(TAG, sTargets.size())) {
                LogWriter.log(TAG, "skip: 群发熔断生效，本批目标 " + sTargets.size() + " 未执行");
                return;
            }

            LogWriter.log(TAG, "fwd trigger: type=" + type + " talker=" + talker
                + " targets=" + sTargets.size() + " content=" + trunc(content, 30));
            forwardAll(type, talker, content, e9);
            addRecord(talker, type, content);
        } catch (Throwable t) {
            LogWriter.log(TAG, "onMsgInsert err: " + t.getMessage());
        }
    }

    /**
     * 读取 isSend：z0() → getIsSend() → field_isSend。
     * 全部失败返回 -1，调用方据此拒绝转发（失败即不转发，宁可漏转不可乱转）。
     */
    private static int readIsSend(Object e9) {
        try {
            Object v = XposedHelpers.callMethod(e9, "z0");
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {}
        try {
            Object v = XposedHelpers.callMethod(e9, "getIsSend");
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {}
        try {
            return XposedHelpers.getIntField(e9, "field_isSend");
        } catch (Throwable ignored) {}
        return -1;
    }

    static String readContent(Object e9) {
        // v3.0.580: 8.0.79 实证 F0() = getContent（旧实现未包含，I0/j/N1 在部分版本不存在）
        for (String mn : new String[]{"F0", "I0", "j", "N1"}) {
            try {
                Object v = XposedHelpers.callMethod(e9, mn);
                if (v != null) return v.toString();
            } catch (Throwable ignored) {}
        }
        // 字段兜底：field_content 声明在父类 im.c8，必须沿继承链查找
        try {
            Class<?> c = e9.getClass();
            while (c != null && c != Object.class) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("field_content");
                    f.setAccessible(true);
                    Object v = f.get(e9);
                    if (v != null) return v.toString();
                    return null;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 转发按钮替换复用入口：把任意消息 e9 转发给指定目标列表（微信原生转发流程共用）。
     * 返回成功数。类型覆盖：文本族/图片/AppMsg 卡片；语音走 TtsVoiceSender；其余暂不支持。
     */
    public static int forwardE9ToTargets(Object e9, Set<String> targets) {
        if (e9 == null || targets == null || targets.isEmpty()) return 0;
        ClassLoader cl = ContextManager.getClassLoader();
        if (cl == null) return 0;
        int type = 0;
        try { type = (Integer) XposedHelpers.callMethod(e9, "getType"); } catch (Throwable ignored) {}
        String content = readContent(e9);
        int done = 0;
        for (String target : targets) {
            if (target == null || target.isEmpty()) continue;
            try {
                forwardOne(cl, type, content, target, e9);
                LogWriter.log(TAG, "fwdReplace -> " + target + " type=" + type + " ok");
                done++;
            } catch (Throwable t) {
                LogWriter.log(TAG, "fwdReplace -> " + target + " err: " + t.getMessage());
            }
        }
        return done;
    }

    private static String callStr(Object obj, String name) {
        try {
            Object v = XposedHelpers.callMethod(obj, name);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * v3.0.580: 批量转发改为「单线程异步 + 限速 + 连续失败中止」。
     *
     * <p>真实事故（分身leshao_v3_log.txt 07:13:32）里，一次误触发在 0.57 秒内向 545 个群
     * 连发 1090 条，既造成刷屏，也极易触发微信风控。现在：</p>
     * <ul>
     *   <li>同一时刻只允许一批在跑，重复触发直接丢弃并记日志</li>
     *   <li>每条之间随机间隔 180~420ms</li>
     *   <li>连续失败 5 条即中止本批</li>
     * </ul>
     */
    private static void forwardAll(int type, String fromTalker, String content, Object originalE9) {
        final ClassLoader cl = ContextManager.getClassLoader();
        if (cl == null) return;
        final List<String> targets = new ArrayList<>(sTargets);
        if (targets.isEmpty()) return;
        if (!sBusy.compareAndSet(false, true)) {
            LogWriter.log(TAG, "skip: 上一批转发仍在进行，本次触发丢弃 (" + targets.size() + " 目标)");
            return;
        }
        try {
            POOL.execute(() -> {
                int ok = 0, fail = 0, consecFail = 0;
                try {
                    for (int i = 0; i < targets.size(); i++) {
                        String target = targets.get(i);
                        if (target == null || target.isEmpty()) continue;
                        boolean sent;
                        try {
                            forwardOne(cl, type, content, target, originalE9);
                            sent = true;
                        } catch (Throwable t) {
                            sent = false;
                            LogWriter.log(TAG, "fwd -> " + target + " err: " + t.getMessage());
                        }
                        if (sent) {
                            ok++;
                            consecFail = 0;
                            LogWriter.log(TAG, "fwd -> " + target + " ok (" + ok + "/" + targets.size() + ")");
                        } else {
                            fail++;
                            if (++consecFail >= MAX_CONSEC_FAIL) {
                                LogWriter.log(TAG, "中止本批: 连续失败 " + consecFail
                                        + " 条，剩余 " + (targets.size() - i - 1) + " 个目标未发送");
                                break;
                            }
                        }
                        if (i < targets.size() - 1) {
                            try {
                                int gap = SEND_GAP_MIN_MS
                                        + (int) (Math.random() * (SEND_GAP_MAX_MS - SEND_GAP_MIN_MS));
                                Thread.sleep(gap);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                    LogWriter.log(TAG, "本批完成: 成功 " + ok + " 失败 " + fail
                            + " 目标 " + targets.size());
                } finally {
                    sBusy.set(false);
                }
            });
        } catch (Throwable t) {
            sBusy.set(false);
            LogWriter.log(TAG, "forwardAll 提交失败: " + t.getMessage());
        }
    }

    private static boolean forwardOne(ClassLoader cl, int type, String content, String target, Object originalE9) {
        boolean isTextFamily = (type == 1 || type == 11 || type == 21 || type == 31 || type == 36
            || type == 0x42000031) && type != 0x11000031;
        boolean isImage = type == 3;
        boolean isAppMsg = (type & 0xffff) == 49 || type == 0x42000031 || type == 0x11000031;
        boolean isVoice = type == 34 || type == 228 || type == 0x42000022;

        if (isVoice) {
            com.leshao.v3.hook.VoiceForwardHook.forwardVoiceToTarget(target, originalE9);
            return true;
        }
        if (isTextFamily && content != null && !content.isEmpty()) {
            // 纯文本直发
            return com.leshao.v3.wm.utils.WmReflect.sendTextMsg(cl, content, target);
        }
        if (isImage) {
            // 图片: 取 imgPath 发送原图（8.0.79+ 方法名 u0，旧版 x0）
            String path = callStr(originalE9, "u0");
            if ((path == null || path.isEmpty()) && originalE9 != null) {
                path = callStr(originalE9, "x0");
            }
            if (path == null || path.isEmpty() || !new java.io.File(path).exists()) {
                LogWriter.log(TAG, "fwd image: no local path, fallback xml send");
                sendXmlAsAppMsg(cl, content, target, 3);
                return true;
            }
            sendImage(cl, target, path);
            return true;
        }
        if (isAppMsg) {
            sendXmlAsAppMsg(cl, content, target, 49);
            return true;
        }
        LogWriter.log(TAG, "fwd: unsupported type " + type + " (skip)");
        return false;
    }

    private static void sendImage(ClassLoader cl, String target, String path) {
        // 复用 WmReflect SendMsgMgr 图片发送（qs5.v5.b CDN 图片）
        try {
            Object sendMgr = com.leshao.v3.wm.utils.WmReflect.getSendMsgMgr(cl);
            if (sendMgr == null) { LogWriter.log(TAG, "sendImage: mgr null"); return; }
            Context ctx = ContextManager.getAppContext();
            for (java.lang.reflect.Method m : sendMgr.getClass().getDeclaredMethods()) {
                if (!m.getName().equals("b")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 4 || pts.length > 18) continue;
                Object[] args = new Object[pts.length];
                for (int i = 0; i < pts.length; i++) {
                    Class<?> p = pts[i];
                    if (i == 0 && (p == Context.class || p.getName().endsWith("Context"))) {
                        args[i] = ctx;
                    } else if (i == 1 && p == String.class) {
                        args[i] = target;
                    } else if (i == 2 && p == String.class) {
                        args[i] = path;
                    } else if (i == 3 && p == int.class) {
                        args[i] = 4; // 原图
                    } else if (p == int.class) {
                        args[i] = 0;
                    } else if (p == long.class) {
                        args[i] = 0L;
                    } else if (p == boolean.class) {
                        args[i] = false;
                    } else if (p == String.class) {
                        args[i] = "";
                    } else {
                        args[i] = null;
                    }
                }
                m.setAccessible(true);
                m.invoke(sendMgr, args);
                return;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendImage err: " + t.getMessage());
        }
    }

    /** AppMsg/图片 xml 直发: q3.G(Context, talker, appmsgXml, type, flag) */
    private static void sendXmlAsAppMsg(ClassLoader cl, String xml, String target, int type) {
        Context ctx = ContextManager.getAppContext();
        if (ctx == null) return;
        String[] q3Cands = {"q3", "q2", "q4"};
        for (String qn : q3Cands) {
            try {
                Class<?> c = cl.loadClass(qn);
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals("G")) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length < 4) continue;
                    Class<?> p0 = pts[0];
                    if (p0 != Context.class && !p0.getName().endsWith("Context")) continue;
                    if (pts[1] != String.class || pts[2] != String.class) continue;
                    m.setAccessible(true);
                    Object[] args = new Object[pts.length];
                    args[0] = ctx;
                    args[1] = target;
                    args[2] = xml;
                    for (int i = 3; i < pts.length; i++) {
                        Class<?> p = pts[i];
                        if (p == int.class) args[i] = type;
                        else if (p == boolean.class) args[i] = i == 3 ? false : true;
                        else if (p == String.class) args[i] = "";
                        else args[i] = null;
                    }
                    m.invoke(null, args);
                    LogWriter.log(TAG, "q3.G invoked");
                    return;
                }
            } catch (Throwable ignored) {}
        }
        LogWriter.log(TAG, "sendXmlAsAppMsg: q3.G not found");
    }

    // ==================== 自动转发记录 ====================

    private static void addRecord(String talker, int type, String content) {
        try {
            SharedPreferences prefs = ContextManager.getPrefs();
            if (prefs == null) return;
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("t", talker == null ? "" : talker);
            o.put("n", contactName(talker));
            o.put("c", describeContent(type, content));
            o.put("ts", System.currentTimeMillis());
            org.json.JSONArray arr = new org.json.JSONArray(prefs.getString(PREF_RECORDS, "[]"));
            org.json.JSONArray out = new org.json.JSONArray();
            out.put(o);
            for (int i = 0; i < arr.length() && i < RECORD_MAX - 1; i++) out.put(arr.getJSONObject(i));
            prefs.edit().putString(PREF_RECORDS, out.toString()).apply();
        } catch (Throwable t) {
            LogWriter.log(TAG, "addRecord err: " + t.getMessage());
        }
    }

    private static String contactName(String wxid) {
        if (wxid == null || wxid.isEmpty()) return "";
        try {
            for (com.leshao.v3.model.ContactCard c : com.leshao.v3.ContactRepository.getAll()) {
                if (c != null && wxid.equals(c.username)) return c.displayName();
            }
        } catch (Throwable ignored) {}
        return wxid;
    }

    private static String describeContent(int type, String content) {
        switch (type) {
            case 1: case 11: case 21: case 31: case 36:
                return content == null ? "" : content;
            case 3: return "[图片]";
            case 34: case 228: return "[语音]";
            case 43: return "[视频]";
            case 47: return "[表情]";
            case 42: return "[名片]";
            case 48: return "[位置]";
            case 6: return "[文件]";
            default:
                if ((type & 0xffff) == 49) return "[链接/卡片]";
                return "[消息]";
        }
    }

    private static String formatTime(long ts) {
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                    .format(new java.util.Date(ts));
        } catch (Throwable t) {
            return "";
        }
    }

    private static void showRecordsDialog(Context ctx) {
        try {
            SharedPreferences prefs = ContextManager.getPrefs();
            org.json.JSONArray arr = new org.json.JSONArray(
                    prefs == null ? "[]" : prefs.getString(PREF_RECORDS, "[]"));
            LinearLayout root = com.leshao.v3.ui.widgets.M3Page.root(ctx);
            root.addView(com.leshao.v3.ui.widgets.M3Page.section(ctx, "自动转发记录",
                    "共 " + arr.length() + " 条"));
            ScrollView sv = new ScrollView(ctx);
            LinearLayout list = new LinearLayout(ctx);
            list.setOrientation(LinearLayout.VERTICAL);
            final float d = ctx.getResources().getDisplayMetrics().density;
            if (arr.length() == 0) {
                TextView empty = new TextView(ctx);
                empty.setText("暂无转发记录");
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, (int) (24 * d), 0, 0);
                empty.setTextColor(com.leshao.v3.ui.AppColors.onSurfaceVariant());
                list.addView(empty);
            }
            for (int i = 0; i < arr.length(); i++) {
                try {
                    org.json.JSONObject o = arr.getJSONObject(i);
                    list.addView(buildRecordRow(ctx, d, o.optString("t"),
                            o.optString("n"), o.optString("c"), o.optLong("ts")));
                } catch (Throwable ignored) {}
            }
            sv.addView(list);
            root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

            final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(ctx,
                    com.leshao.v3.ui.AppColors.isDarkMode()
                            ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                            : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setView(com.leshao.v3.ui.InsetsUtil.window(null, root, 0.9f, -1f))
                    .setCancelable(true)
                    .create();
            com.leshao.v3.ui.InsetsUtil.centerAutoHeight(dlg, 0.9f);
            dlg.show();
        } catch (Throwable t) {
            Toast.makeText(ctx, "打开记录失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static View buildRecordRow(Context ctx, float d, String talker, String name,
                                       String content, long ts) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, (int) (8 * d), 0, (int) (8 * d));

        int sz = (int) (40 * d);
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        iv.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        boolean bound = false;
        try { bound = com.leshao.v3.ui.AvatarHelper.bindAvatar(iv, talker); } catch (Throwable ignored) {}
        if (!bound) {
            try {
                String letter = (name != null && !name.isEmpty()) ? name.substring(0, 1) : "?";
                iv.setImageBitmap(com.leshao.v3.ui.AvatarHelper.letterAvatar(letter, sz));
            } catch (Throwable ignored) {}
        }
        row.addView(iv);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
        clp.setMargins((int) (10 * d), 0, 0, 0);
        col.setLayoutParams(clp);

        TextView tvName = new TextView(ctx);
        tvName.setText(name == null || name.isEmpty() ? talker : name);
        tvName.setTextSize(14);
        tvName.setTextColor(com.leshao.v3.ui.AppColors.onSurface());
        tvName.setSingleLine(true);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(tvName);

        TextView tvContent = new TextView(ctx);
        tvContent.setText(content == null ? "" : content);
        tvContent.setTextSize(13);
        tvContent.setTextColor(com.leshao.v3.ui.AppColors.onSurfaceVariant());
        tvContent.setMaxLines(2);
        tvContent.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(tvContent);

        TextView tvTime = new TextView(ctx);
        tvTime.setText(formatTime(ts));
        tvTime.setTextSize(11);
        tvTime.setTextColor(com.leshao.v3.ui.AppColors.onSurfaceVariant());
        col.addView(tvTime);

        row.addView(col);
        return row;
    }

    // ==================== 配置 UI 弹窗（v955 M3 重排 + 联系人选择器 + 类型多选按钮） ====================

    /** 可转发的消息类型（WeChat msgType）: 值 → 显示名 */
    private static final int[] FW_TYPE_CODES = {1, 3, 43, 34, 47, 49, 42, 48, 6};
    private static final String[] FW_TYPE_NAMES = {"文本", "图片", "视频", "语音", "表情", "链接", "名片", "位置", "文件"};

    public static void showConfigDialog(Context ctx) {
        SharedPreferences prefs = ContextManager.getPrefs();
        if (prefs == null) { Toast.makeText(ctx, "prefs 不可用", Toast.LENGTH_SHORT).show(); return; }
        final Activity act = ctx instanceof Activity ? (Activity) ctx : null;

        float d = ctx.getResources().getDisplayMetrics().density;

        // ===== M3 弹窗根 =====
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(com.leshao.v3.ui.CandyUi.dialogBg(ctx));
        root.setPadding((int) (12 * d), (int) (12 * d), (int) (12 * d), (int) (12 * d));
        com.leshao.v3.ui.InsetsUtil.clipRounded(root);

        ScrollView sv = new ScrollView(ctx);
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding((int) (4 * d), 0, (int) (4 * d), 0);

        // ===== 总开关 =====
        content.addView(new com.leshao.v3.ui.widgets.SectionHeader(ctx, "自动转发", "命中来源的消息自动转发到目标"));
        LinearLayout cardMain = com.leshao.v3.ui.widgets.M3Page.card(ctx);
        final android.widget.Switch swEnabled = com.leshao.v3.ui.CandyUi.newSwitch(ctx);
        swEnabled.setChecked(prefs.getBoolean(PREF_ENABLED, false));
        cardMain.addView(com.leshao.v3.ui.widgets.M3Page.tailRow(ctx, "🔁", "启用自动转发",
                "关闭后不转发任何消息", swEnabled));
        content.addView(cardMain);

        // ===== 来源（联系人选择器） =====
        LinearLayout cardSrc = com.leshao.v3.ui.widgets.M3Page.card(ctx);
        final String[] srcHolder = {prefs.getString(PREF_SOURCES, "")};
        final TextView srcVal = new TextView(ctx);
        srcVal.setText(srcHolder[0].isEmpty() ? "未选择（不转发）" : displayList(srcHolder[0]));
        srcVal.setTextSize(13);
        srcVal.setTextColor(com.leshao.v3.ui.AppColors.onSurfaceVariant());
        srcVal.setSingleLine(true);
        srcVal.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cardSrc.addView(com.leshao.v3.ui.widgets.M3Page.clickRow(ctx, "📥", "选择转发来源",
                "必须选择至少一个来源，否则不转发任何消息", () -> {
            if (act == null) { Toast.makeText(ctx, "当前上下文不支持选择器", Toast.LENGTH_SHORT).show(); return; }
            com.leshao.v3.ui.ContactPickerDialog.show(act, srcHolder[0],
                    com.leshao.v3.ui.ContactPickerDialog.MODE_FRIEND,
                    (wxids, display) -> {
                        srcHolder[0] = wxids == null || wxids.isEmpty() ? ""
                                : android.text.TextUtils.join(",", wxids);
                        srcVal.setText(srcHolder[0].isEmpty() ? "未选择（不转发）" : displayList(srcHolder[0]));
                    });
        }));
        content.addView(cardSrc);

        // ===== 目标（联系人选择器, 群聊优先） =====
        LinearLayout cardTgt = com.leshao.v3.ui.widgets.M3Page.card(ctx);
        final String[] tgtHolder = {prefs.getString(PREF_TARGETS, "")};
        final TextView tgtVal = new TextView(ctx);
        tgtVal.setText(tgtHolder[0].isEmpty() ? "未设置" : displayList(tgtHolder[0]));
        tgtVal.setTextSize(13);
        tgtVal.setTextColor(com.leshao.v3.ui.AppColors.onSurfaceVariant());
        tgtVal.setSingleLine(true);
        tgtVal.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // v3.0.580: 目标数量提示 —— 大批量群发风险要显式可见
        final TextView tgtCount = new TextView(ctx);
        tgtCount.setTextSize(12);
        tgtCount.setPadding((int) (12 * d), (int) (2 * d), (int) (12 * d), (int) (8 * d));
        updateTargetCountHint(tgtCount, tgtHolder[0]);
        cardTgt.addView(com.leshao.v3.ui.widgets.M3Page.clickRow(ctx, "📤", "选择转发目标",
                "打开联系人选择器多选(可切好友/群聊)", () -> {
            if (act == null) { Toast.makeText(ctx, "当前上下文不支持选择器", Toast.LENGTH_SHORT).show(); return; }
            com.leshao.v3.ui.ContactPickerDialog.show(act, tgtHolder[0],
                    com.leshao.v3.ui.ContactPickerDialog.MODE_GROUP,
                    (wxids, display) -> {
                        tgtHolder[0] = wxids == null || wxids.isEmpty() ? ""
                                : android.text.TextUtils.join(",", wxids);
                        tgtVal.setText(tgtHolder[0].isEmpty() ? "未设置" : displayList(tgtHolder[0]));
                        updateTargetCountHint(tgtCount, tgtHolder[0]);
                    });
        }));
        cardTgt.addView(tgtCount);
        cardTgt.addView(com.leshao.v3.ui.widgets.M3Page.clickRow(ctx, "📝", "自动转发记录",
                "查看转发历史(头像/昵称/内容/时间)", () -> showRecordsDialog(ctx)));
        content.addView(cardTgt);

        // ===== 类型（多选按钮组, 仅保留按钮） =====
        LinearLayout cardType = com.leshao.v3.ui.widgets.M3Page.card(ctx);
        final java.util.Set<Integer> typeSel = new java.util.LinkedHashSet<>();
        String savedTypes = prefs.getString(PREF_TYPES, "");
        if (savedTypes != null && !savedTypes.trim().isEmpty()) {
            for (String t : savedTypes.split("[,，]")) {
                try { typeSel.add(Integer.parseInt(t.trim())); } catch (Throwable ignored) {}
            }
        } else {
            typeSel.add(1); typeSel.add(3); typeSel.add(49);
        }
        cardType.setPadding((int) (8 * d), (int) (8 * d), (int) (8 * d), (int) (8 * d));

        // 多选按钮流式网格(每行3个)
        LinearLayout typeGrid = new LinearLayout(ctx);
        typeGrid.setOrientation(LinearLayout.VERTICAL);
        typeGrid.setPadding((int) (12 * d), (int) (4 * d), (int) (12 * d), (int) (10 * d));
        final java.util.List<TextView> typeBtns = new java.util.ArrayList<>();
        for (int i = 0; i < FW_TYPE_CODES.length; i++) {
            if (i % 3 == 0) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, (int) (4 * d), 0, 0);
                typeGrid.addView(row);
            }
            final int code = FW_TYPE_CODES[i];
            TextView tb = new TextView(ctx);
            tb.setText(FW_TYPE_NAMES[i]);
            tb.setTextSize(13);
            tb.setGravity(Gravity.CENTER);
            tb.setSingleLine(true);
            tb.setClickable(true);
            tb.setFocusable(true);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, (int) (36 * d), 1f);
            tlp.setMargins((int) (3 * d), 0, (int) (3 * d), 0);
            tb.setLayoutParams(tlp);
            tb.setOnClickListener(v -> {
                if (typeSel.contains(code)) typeSel.remove(code); else typeSel.add(code);
                refreshTypeButtons(typeBtns, FW_TYPE_CODES, typeSel, null);
            });
            typeBtns.add(tb);
            ((LinearLayout) typeGrid.getChildAt(typeGrid.getChildCount() - 1)).addView(tb);
        }
        refreshTypeButtons(typeBtns, FW_TYPE_CODES, typeSel, null);
        cardType.addView(typeGrid);
        content.addView(cardType);

        sv.addView(content);

        // v1143: 高度自适应内容并收紧窗口(仅内容过长时才滚动), 去掉窗口下方大片空白
        int sheetW = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.9f);
        int maxContentH = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.72f);
        int innerW = Math.max(1, sheetW - (int) (24 * d) - (int) (8 * d));
        content.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(innerW, android.view.View.MeasureSpec.AT_MOST),
                android.view.View.MeasureSpec.makeMeasureSpec(maxContentH, android.view.View.MeasureSpec.AT_MOST));
        int contentH = Math.min(content.getMeasuredHeight(), maxContentH);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(-1, contentH);
        sv.setLayoutParams(svLp);
        root.addView(sv);

        // ===== 底部按钮 =====
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        btnRow.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (4 * d));
        com.leshao.v3.ui.widgets.ModernButton btnCancel =
                new com.leshao.v3.ui.widgets.ModernButton(ctx, "取消", com.leshao.v3.ui.widgets.ModernButton.STYLE_GHOST);
        com.leshao.v3.ui.widgets.ModernButton btnSave =
                new com.leshao.v3.ui.widgets.ModernButton(ctx, "保存", com.leshao.v3.ui.widgets.ModernButton.STYLE_PRIMARY);
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
            StringBuilder types = new StringBuilder();
            for (Integer t : typeSel) {
                if (types.length() > 0) types.append(",");
                types.append(t);
            }
            final String typesCsv = types.toString();
            final int tgtN = split(tgtHolder[0]).size();
            final Runnable doSave = () -> {
                prefs.edit()
                    .putBoolean(PREF_ENABLED, swEnabled.isChecked())
                    .putString(PREF_SOURCES, srcHolder[0].trim())
                    .putString(PREF_TARGETS, tgtHolder[0].trim())
                    .putString(PREF_TYPES, typesCsv)
                    .apply();
                updateConfig(prefs);
                if (sSources.isEmpty()) {
                    Toast.makeText(ctx, "已保存：来源为空，不会转发任何消息", Toast.LENGTH_SHORT).show();
                } else if (tgtN == 0) {
                    Toast.makeText(ctx, "已保存：未设置转发目标，不会转发", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ctx, "已保存" + (sEnabled ? "，自动转发已开启（" + tgtN + " 个目标）" : ""),
                            Toast.LENGTH_SHORT).show();
                }
                dlg.dismiss();
            };
            // v3.0.580: 目标数超过 50 时二次确认，避免误选全部群后一次刷屏
            if (tgtN > 50) {
                new android.app.AlertDialog.Builder(ctx)
                        .setTitle("确认大批量群发")
                        .setMessage("当前已选择 " + tgtN + " 个转发目标。\n\n"
                                + "每条命中的消息都会被转发到这 " + tgtN + " 个会话，"
                                + "量大会触发微信风控甚至封号。\n\n确定保存吗？")
                        .setPositiveButton("确定保存", (dd, w) -> doSave.run())
                        .setNegativeButton("返回修改", null)
                        .show();
            } else {
                doSave.run();
            }
        });
        com.leshao.v3.ui.InsetsUtil.centerAutoHeight(dlg, 0.9f);
        dlg.show();
        com.leshao.v3.ui.WindowLayer.track(dlg.getWindow());
    }

    /** v3.0.580: 目标数量与风险提示。 */
    private static void updateTargetCountHint(TextView tv, String csv) {
        if (tv == null) return;
        int n = split(csv).size();
        if (n == 0) {
            tv.setText("⚠ 未设置目标，不会转发任何消息");
            tv.setTextColor(com.leshao.v3.ui.AppColors.onSurfaceVariant());
        } else if (n > 200) {
            tv.setText("⚠ 已选 " + n + " 个目标：超大群发，极易触发微信风控，请确认是你要的");
            tv.setTextColor(0xFFD32F2F);
        } else if (n > 50) {
            tv.setText("⚠ 已选 " + n + " 个目标：大批量群发，保存时会二次确认");
            tv.setTextColor(0xFFF57C00);
        } else {
            tv.setText("已选 " + n + " 个目标");
            tv.setTextColor(com.leshao.v3.ui.AppColors.onSurfaceVariant());
        }
    }

    /** 刷新类型多选按钮态 + 摘要文字 */
    private static void refreshTypeButtons(java.util.List<TextView> btns, int[] codes,
                                           java.util.Set<Integer> sel, TextView summary) {
        for (int i = 0; i < btns.size() && i < codes.length; i++) {
            TextView tb = btns.get(i);
            boolean on = sel.contains(codes[i]);
            tb.setBackground(com.leshao.v3.ui.CandyUi.pillBg(on, tb.getContext()));
            tb.setTextColor(on ? com.leshao.v3.ui.AppColors.textOnPrimary()
                    : com.leshao.v3.ui.AppColors.onSurfaceVariant());
        }
        if (summary != null) {
            if (sel.isEmpty()) {
                summary.setText("当前: 默认(文本+图片+链接)");
            } else {
                StringBuilder sb = new StringBuilder("当前: ");
                for (int i = 0; i < codes.length; i++) {
                    if (sel.contains(codes[i])) {
                        if (sb.length() > 4) sb.append("、");
                        sb.append(FW_TYPE_NAMES[i]);
                    }
                }
                summary.setText(sb.toString());
            }
        }
    }

    /** 解析逗号分隔 wxid 列表为可读昵称串 */
    private static String displayList(String csv) {
        List<String> ids = split(csv);
        if (ids.isEmpty()) return "";
        java.util.Map<String, String> names = contactNames();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append("、");
            String n = names.get(ids.get(i));
            sb.append(n != null ? n : ids.get(i));
            if (i == 4 && ids.size() > 6) {
                sb.append("…等").append(ids.size()).append("个");
                break;
            }
        }
        return sb.toString();
    }

    private static java.util.Map<String, String> contactNames() {
        java.util.Map<String, String> names = new java.util.HashMap<>();
        try {
            for (com.leshao.v3.model.ContactCard c : com.leshao.v3.ContactRepository.getAll()) {
                if (c != null && c.username != null) names.put(c.username, c.displayName());
            }
        } catch (Throwable ignored) {}
        return names;
    }

    /** 已选名单查看弹窗（只读展示, 支持清空） */
    private static void showSelectedListDialog(Context ctx, String title, String csv, Runnable onClear) {
        List<String> ids = split(csv);
        if (ids.isEmpty()) {
            Toast.makeText(ctx, "暂无已选名单", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout root = com.leshao.v3.ui.widgets.M3Page.root(ctx);
        root.addView(com.leshao.v3.ui.widgets.M3Page.section(ctx, title, "共 " + ids.size() + " 项"));
        java.util.Map<String, String> names = contactNames();
        LinearLayout card = com.leshao.v3.ui.widgets.M3Page.card(ctx);
        for (String id : ids) {
            String n = names.get(id);
            card.addView(com.leshao.v3.ui.widgets.M3Page.clickRow(ctx, "•",
                    n != null ? n : id, id, () -> {}));
            card.addView(com.leshao.v3.ui.widgets.M3Page.divider(ctx));
        }
        root.addView(card);

        int theme = com.leshao.v3.ui.AppColors.isDarkMode()
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(ctx, theme)
                .setView(com.leshao.v3.ui.InsetsUtil.window(null, root, 0.9f, 0.7f))
                .setCancelable(true)
                .create();
        View clearBtn = com.leshao.v3.ui.widgets.M3Page.ghostButton(ctx, "清空名单",
                () -> { if (onClear != null) onClear.run(); dlg.dismiss(); });
        View closeBtn = com.leshao.v3.ui.widgets.M3Page.button(ctx, "关闭", dlg::dismiss);
        root.addView(com.leshao.v3.ui.widgets.M3Page.buttonRow(ctx, closeBtn, clearBtn));
        com.leshao.v3.ui.InsetsUtil.transparentWindow(dlg);
        dlg.show();
        com.leshao.v3.ui.WindowLayer.track(dlg.getWindow());
    }

    private static class TextViewTitle extends android.widget.TextView {
        TextViewTitle(Context ctx, String text) {
            super(ctx);
            setText(text);
            setTextSize(14);
            setTextColor(com.leshao.v3.ui.AppColors.text1());
            setPadding(0, (int)(8 * ctx.getResources().getDisplayMetrics().density), 0, 4);
        }
    }

    static String trunc(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}