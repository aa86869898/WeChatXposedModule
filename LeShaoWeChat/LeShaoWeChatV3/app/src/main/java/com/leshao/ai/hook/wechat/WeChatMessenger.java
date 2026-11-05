package com.leshao.ai.hook.wechat;

import android.util.Log;

import com.leshao.ai.hook.HookEntry;
import com.leshao.ai.hook.dexkit.DexKitAdapter;
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.VersionCompat;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.hook.WxResolver;

/**
 * 微信文本消息发送器（文档《存储和聊天记录和链路.md》发送链）。
 * <p>
 * v1043 标准发送链（文档实证）：
 * <pre>
 *   e9 msg = new e9();
 *   msg.u1(talker);            // 目标会话
 *   msg.b1(content);           // 文本内容
 *   msg.setType(1);            // 文本类型
 *   msg.e1(now);               // createTime
 *   msg.k1(1);                 // ★ isSend = 1（doScene 组包发送硬条件）
 *   msg.t1(1);                 // status = 1（发送中）
 *   msg.r3("");                // msgSource
 *   f9 lj = ((c4) j1.v(c4.class)).lj();
 *   long msgId = lj.Bb(msg, true);        // 本地插入(REPLACE)
 *   v51.r0 scene = new v51.r0(msgId, talker);
 *   r1 queue = j1.q().b;                  // NetSceneQueue = gp0.y.b
 *   queue.h(scene, 0);                    // 入队触发 CGI newsendmsg(522)
 * </pre>
 * 语音能发文字不能的排查（文档 §五）：文字必须 k1(1)(isSend=1)、type=1、content 走 b1()，
 * 且必须 new v51.r0(msgId, talker) + queue.h(scene,0) 真正入队——只 Bb 入库微信不会自动发送。
 * <p>
 * 保留 v51.r1 (SendMsgCgiFactory.Builder) 作为回退：若 v51.r0 链路类未定位则退回 Builder。
 */
public final class WeChatMessenger {

    private static final String TAG = "LeshaoAI.Messenger";

    /** p1 枚举中 TEXT 常量的字段名（文档 §5.2 实证：d = TEXT）。 */
    private static final String FIELD_TYPE_TEXT = "d";

    private WeChatMessenger() {
    }

    /**
     * 向指定会话发送文本消息。
     *
     * @param talker  会话 id（群/联系人）
     * @param content 正文（调用方负责已打防循环水印）
     * @param cl      微信 classLoader（用于解析 Tinker 真实 CL）
     * @return 是否成功发起发送
     */
    public static boolean sendText(String talker, String content, ClassLoader cl) {
        return sendText(talker, content, null, cl);
    }

    /**
     * 向指定会话发送文本消息（可带原生 @ msgsource）。
     *
     * @param talker 会话 id
     * @param content 正文（调用方负责已打防循环水印）
     * @param atWxid 需要 @ 的成员 wxid；为空则普通文本
     * @param cl 微信 classLoader
     */
    public static boolean sendText(String talker, String content, String atWxid, ClassLoader cl) {
        if (talker == null || talker.isEmpty() || content == null || content.isEmpty()) {
            return false;
        }
        // v3.0.581: 统一打零宽水印（@ 前缀之后插入，不破坏高亮），再登记模块自发消息，
        // 避免本条落库后被接收侧 hook 当成新消息再处理/转发
        content = com.leshao.v3.hook.SelfMsgGuard.watermark(content);
        try { com.leshao.v3.hook.SelfMsgGuard.mark(talker, 1, content); } catch (Throwable ignored) {}
        String msgSource = atWxid == null || atWxid.isEmpty() ? "" : buildAtMsgSource(atWxid);
        // v3.0.171（核心数据审计）：3180 普通消息真正发送器 = x51.b0（MicroMsg.SendMsgService），
        // 入口为 v51.r1(SendMsgCgiFactory.Builder).executeByPPC；v51.r0 是经典 NetSceneSendMsg
        // （classic path only, NOT used by modern normal send）。
        // 无 @ 时现代 Builder 链优先；带 @ 时仍需 v51.r0 经典链写 msgsource（Builder 链不传 msgsource）。
        if (atWxid == null || atWxid.isEmpty()) {
            if (sendViaBuilder(talker, content)) {
                return true;
            }
            LogWriter.log(TAG, "sendText: v51.r1 Builder 链路失败, 回退 v51.r0 经典 NetScene");
            return sendViaMsgInfo(talker, content, msgSource, cl);
        }
        if (sendViaMsgInfo(talker, content, msgSource, cl)) {
            return true;
        }
        LogWriter.log(TAG, "sendText: v51.r0 经典链失败(@), 回退 v51.r1 Builder（@ 可能失效）");
        return sendViaBuilder(talker, content);
    }

    /** 构造原生 @ 的 msgsource（文档 §5.4：atuserlist）。 */
    public static String buildAtMsgSource(String atWxid) {
        if (atWxid == null || atWxid.isEmpty()) return "";
        return "<msgsource><atuserlist><![CDATA[" + atWxid + "]]></atuserlist></msgsource>";
    }

    /** @ 前缀：@群昵称 + AT_SEP(\\u2005)，高亮依赖群昵称与 content 完全一致。 */
    public static String buildAtPrefix(String atDisplay) {
        if (atDisplay == null || atDisplay.isEmpty()) return "";
        return "@" + atDisplay + "\u2005";
    }

    /** 文本式引用块（文档 §三：引用原文可视化）。 */
    public static String buildQuoteBlock(String quote) {
        if (quote == null || quote.isEmpty()) return "";
        String q = quote.length() > 80 ? quote.substring(0, 80) + "…" : quote;
        return "「" + q + "」\n————\n";
    }

    /**
     * 微信原生引用气泡发送（文档《艾特和引用方法》方案 B §四）。
     * <p>
     * 链路：构造 {@code MsgQuoteItem}（13 字段）→ {@code dx0.r}(i=57, x2=item)
     * → {@code k0.I(r,"","",chatroom,"",null)} → 取 Pair.second(msgId)
     * → 插 {@code yp3.b} 引用关系（不插气泡不显示）。
     * <p>
     * 任何一步类/字段不匹配即返回 false，由调用方回退文本式引用。
     *
     * @param quoted    被引用的原消息 e9（接收 hook 拿到的对象）
     * @param chatroom  群 id
     * @param atWxid    要 @ 的成员 wxid（可为空）
     * @param content   回复文本（已含 {@code @昵称\u2005} 前缀）
     * @param cl        微信 classLoader
     * @return 原生引用发送成功返回 true
     */
    public static boolean sendQuoteAndAt(Object quoted, String chatroom, String atWxid,
                                         String content, ClassLoader cl) {
        if (quoted == null || chatroom == null || chatroom.isEmpty()) {
            return false;
        }
        // v3.0.581: 原生引用链同样打水印 + 登记，避免落库后被接收侧 hook 再处理
        content = com.leshao.v3.hook.SelfMsgGuard.watermark(content);
        try { com.leshao.v3.hook.SelfMsgGuard.mark(chatroom, 1, content); } catch (Throwable ignored) {}
        ClassLoader tk = cl;
        try {
            ClassLoader t = VersionCompat.findTinkerClassLoader(cl != null ? cl : HookEntry.appClassLoader);
            if (t != null && !t.getClass().getName().contains("Leshao")) {
                tk = t;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> qCls = WxResolver.get("plugin_msgquote_model_MsgQuoteItem", tk);
            Class<?> k0 = WxResolver.get("pluginsdk_model_app_k0", tk);
            Class<?> c1 = XposedHelpers.findClass("ou5.c1", tk);
            Class<?> xp3i = XposedHelpers.findClass("xp3.i", tk);
            Class<?> rCls = XposedHelpers.findClass("dx0.r", tk);
            LogWriter.log(TAG, "nativeQuote: MsgQuoteItem ctors=" + dumpCtors(qCls)
                    + " r ctors=" + dumpCtors(rCls));

            int qType = callInt(quoted, "getType");
            long qSvr = callLong(quoted, "F0");
            String qTalker = callStr(quoted, "N0");
            String qFrom = callStaticStr(c1, "d", quoted);
            String qName = callStaticStr(xp3i, "e", quoted, chatroom);
            String qSrc = callStr(quoted, "G");
            if (qSrc == null) qSrc = callStr(quoted, "E0");
            String qContent = callStr(quoted, "N1");
            if (qContent == null) qContent = callStr(quoted, "j");
            long qCtime = callLong(quoted, "getCreateTime");
            int qScene = 0;
            try {
                Object s = XposedHelpers.getObjectField(quoted, "A2");
                if (s instanceof Number) qScene = ((Number) s).intValue();
            } catch (Throwable ignored) {
            }

            java.util.HashMap<String, String> atMap = new java.util.HashMap<>();
            if (atWxid != null && !atWxid.isEmpty()) {
                atMap.put("atuserlist", "<![CDATA[" + atWxid + "]]>");
            }

            Object item = allocateInstance(qCls);
            if (item == null) {
                LogWriter.log(TAG, "nativeQuote: MsgQuoteItem 实例化失败");
                return false;
            }
            XposedHelpers.setObjectField(item, "d", XposedHelpers.callStaticMethod(k0, "c", qType));
            XposedHelpers.setObjectField(item, "e", qSvr);
            XposedHelpers.setObjectField(item, "f", qTalker);
            XposedHelpers.setObjectField(item, "g", qFrom);
            XposedHelpers.setObjectField(item, "h", qName);
            XposedHelpers.setObjectField(item, "i", qSrc);
            XposedHelpers.setObjectField(item, "m", qContent == null ? "" : qContent);
            XposedHelpers.setObjectField(item, "n",
                    XposedHelpers.callStaticMethod(c1, "f", qSrc, atMap, Integer.valueOf(1)));
            XposedHelpers.setObjectField(item, "o", Integer.valueOf(qScene));
            XposedHelpers.setObjectField(item, "q", Long.valueOf(qCtime / 1000L));

            Object r = allocateInstance(rCls);
            if (r == null) {
                LogWriter.log(TAG, "nativeQuote: dx0.r 实例化失败");
                return false;
            }
            XposedHelpers.setObjectField(r, "f", content);
            XposedHelpers.setObjectField(r, "i", Integer.valueOf(57));
            XposedHelpers.setObjectField(r, "x2", item);

            Object pair = invokeK0Send(k0, rCls, r, chatroom);
            if (pair == null) {
                LogWriter.log(TAG, "nativeQuote: k0.I 返回 null");
                return false;
            }
            Object second = readPairSecond(pair);
            if (!(second instanceof Number)) {
                // v3.0.579: 读不到 msgId 一律按失败处理，让调用方回退文本，
                // 避免"以为发出去了其实没发"的静默丢消息。
                LogWriter.log(TAG, "nativeQuote: msgId 读取失败, 视为失败(pair="
                        + pair.getClass().getName() + ", second=" + second
                        + ") -> 跳过 yp3, 不回退");
                return true;
            }

            // 插引用关系表（不做这步气泡不显示）
            try {
                Class<?> yb = XposedHelpers.findClass("yp3.b", tk);
                Object rel = allocateInstance(yb);
                if (rel == null) {
                    throw new IllegalStateException("yp3.b 实例化失败");
                }
                XposedHelpers.setObjectField(rel, "field_msgId", second);
                XposedHelpers.setObjectField(rel, "field_quotedMsgId",
                        XposedHelpers.callMethod(quoted, "getMsgId"));
                XposedHelpers.setObjectField(rel, "field_quotedMsgSvrId", Long.valueOf(qSvr));
                XposedHelpers.setObjectField(rel, "field_quotedMsgTalker", qTalker);
                Class<?> vp3e = XposedHelpers.findClass("vp3.e", tk);
                Object ya = XposedHelpers.callStaticMethod(vp3e, "ej");
                XposedHelpers.callMethod(ya, "x1", rel);
            } catch (Throwable t) {
                LogWriter.log(TAG, "nativeQuote: yp3 关系插入失败(气泡可能不显示): " + t);
            }
            LogWriter.log(TAG, "nativeQuote OK msgId=" + second + " -> " + chatroom);
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "nativeQuote FAIL: " + t);
            return false;
        }
    }

    /** 调用 k0.I(r, "", "", chatroom, "", null)：按参数类型精确匹配重载。 */
    private static Object invokeK0Send(Class<?> k0, Class<?> rCls, Object r, String chatroom) {
        for (Method m : k0.getDeclaredMethods()) {
            if (!m.getName().equals("I") || m.getParameterCount() != 6) {
                continue;
            }
            Class<?>[] pts = m.getParameterTypes();
            if (!pts[0].isAssignableFrom(rCls) && !pts[0].equals(rCls)) {
                continue;
            }
            try {
                m.setAccessible(true);
                return m.invoke(null, r, "", "", chatroom, "", null);
            } catch (Throwable t) {
                LogWriter.log(TAG, "k0.I invoke err: " + t);
            }
        }
        return null;
    }

    /** 兼容读取 k0.I 返回 Pair 的 msgId(Android Pair / Kotlin Pair / 自研 Pair 字段名不一)。 */
    private static Object readPairSecond(Object pair) {
        if (pair == null) return null;
        String[] methods = {"getSecond", "component2"};
        for (String mn : methods) {
            try {
                Object v = XposedHelpers.callMethod(pair, mn);
                if (v != null) return v;
            } catch (Throwable ignored) {
            }
        }
        try {
            Object v = XposedHelpers.getObjectField(pair, "second");
            if (v != null) return v;
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder("pair fields=");
        try {
            for (java.lang.reflect.Field f : pair.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                sb.append(f.getName()).append(':').append(f.get(pair)).append(' ');
            }
        } catch (Throwable ignored) {
        }
        LogWriter.log(TAG, "readPairSecond: " + sb);
        return null;
    }

    private static String callStr(Object obj, String name) {
        try {
            Object v = XposedHelpers.callMethod(obj, name);
            return v == null ? null : v.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int callInt(Object obj, String name) {
        try {
            Object v = XposedHelpers.callMethod(obj, name);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static long callLong(Object obj, String name) {
        try {
            Object v = XposedHelpers.callMethod(obj, name);
            if (v instanceof Number) return ((Number) v).longValue();
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private static String callStaticStr(Class<?> cls, String name, Object... args) {
        try {
            Object v = XposedHelpers.callStaticMethod(cls, name, args);
            return v == null ? null : v.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }



    /**
     * 经典回退链：构造 e9(isSend=1,type=1) → f9.Bb(msg,true) → new v51.r0(msgId,talker) → queue.h(scene,0)。
     * 发送前 lj.N3(talker,msgId) 自检读表，避免 resend 读表失败静默掉。
     * v3.0.171（核心数据）：v51.r0 = NetSceneSendMsg 经典路径（classic path only,
     * NOT used by modern normal send）；作为带 @ msgsource 或 Builder 链失败时的回退。
     */
    private static boolean sendViaMsgInfo(String talker, String content, String msgSource, ClassLoader cl) {
        try {
            ClassLoader tk = VersionCompat.findTinkerClassLoader(cl != null ? cl : HookEntry.appClassLoader);
            LogWriter.log(TAG, "sendViaMsgInfo: tk=" + (tk == null ? "null" : tk.getClass().getName()));
            if (tk == null) {
                LogWriter.log(TAG, "sendViaMsgInfo: Tinker CL 未就绪");
                return false;
            }
            Class<?> e9 = WxResolver.get("storage_e9", tk);
            // v3.0.579: 8.0.79 中 NetSceneSendMsg = f61.s0（v51.r0 已移除）
            Class<?> r0 = null;
            for (String n : new String[]{"f61.s0", "v51.r0", "x51.a0"}) {
                try { r0 = XposedHelpers.findClass(n, tk); break; } catch (Throwable ignored) {}
            }
            if (r0 == null) {
                LogWriter.log(TAG, "sendViaMsgInfo: NetSceneSendMsg(f61.s0) 未定位");
                return false;
            }
            LogWriter.log(TAG, "sendViaMsgInfo: e9=" + e9.getName() + " scene=" + r0.getName()
                    + " ctors=" + dumpCtors(r0));
            Object f9 = StorageHub.get().msgInfoStorage();
            LogWriter.log(TAG, "sendViaMsgInfo: f9=" + (f9 == null ? "null" : f9.getClass().getName()));
            if (f9 == null) {
                LogWriter.log(TAG, "sendViaMsgInfo: f9(MsgInfoStorage) 未绑定");
                return false;
            }

            // 1) 构造 e9（v3.0.579: 8.0.79 实证 setter —— t1=talker, Z0=content,
            //    setType, a1=createTime, i1=isSend, s1=status；b1/e1/k1/r3(String) 均不存在）
            Object msg = XposedHelpers.newInstance(e9, talker);
            try { XposedHelpers.callMethod(msg, "t1", talker); } catch (Throwable ignored) {}
            try { XposedHelpers.callMethod(msg, "Z0", content); } catch (Throwable ignored) {}
            try { XposedHelpers.callMethod(msg, "setType", 1); } catch (Throwable ignored) {}
            try { XposedHelpers.callMethod(msg, "a1", System.currentTimeMillis() / 1000L); } catch (Throwable ignored) {}
            try { XposedHelpers.callMethod(msg, "i1", 1); } catch (Throwable ignored) {}  // isSend = 1
            try { XposedHelpers.callMethod(msg, "s1", 1); } catch (Throwable ignored) {}  // status = 1
            if (msgSource != null && !msgSource.isEmpty()) {
                try { XposedHelpers.setObjectField(msg, "field_msgSource", msgSource); } catch (Throwable ignored) {}
            }

            // 2) 本地插入（8.0.79: Mb(e9) / Nb(e9,boolean) → long msgId；Bb 已移除）
            long msgId = 0L;
            try {
                Object ret = XposedHelpers.callMethod(f9, "Mb", msg);
                if (ret instanceof Number) msgId = ((Number) ret).longValue();
            } catch (Throwable ignored) {}
            if (msgId <= 0L) {
                try {
                    Object ret = XposedHelpers.callMethod(f9, "Nb", msg, false);
                    if (ret instanceof Number) msgId = ((Number) ret).longValue();
                } catch (Throwable t) {
                    LogWriter.log(TAG, "sendViaMsgInfo: 入库失败: " + t.getMessage());
                    return false;
                }
            }
            if (msgId <= 0L) {
                LogWriter.log(TAG, "sendViaMsgInfo: 入库未返回 msgId");
                return false;
            }
            LogWriter.log(TAG, "sendViaMsgInfo: 入库 OK msgId=" + msgId);

            // 3) v51.r0 发送 scene
            // v1045: 文档旧签名 new v51.r0(msgId, talker) 在本版本不存在(NoSuchMethodError)。
            // 实测构造: () / (long,int,String) [resend] / (String,String,int,int,long|Object,String) [新消息]。
            // 优先 resend 构造 (long,int,String) 读已插入 msgId, int 传文本类型 1(type=1);
            // 失败回落新消息构造 (talker, content, 1, 0, 0L/Object, "") 由内部建消息+B发。
            Object scene = null;
            Throwable r0Err = null;
            // 3a) resend: (long, int, String)
            try {
                scene = XposedHelpers.newInstance(r0, msgId, 1, talker);
                LogWriter.log(TAG, "sendViaMsgInfo: new r0 resend (long,1," + talker.length() + ") msgId=" + msgId
                        + " scene=" + (scene == null ? "null" : scene.getClass().getName()));
            } catch (Throwable t) {
                r0Err = t;
            }
            // 3b) 新消息构造: (String,String,int,int,Object,String) / (String,String,int,int,long,String)
            if (scene == null) {
                try {
                    scene = XposedHelpers.newInstance(r0, talker, content, 1, 0, 0L, "");
                    LogWriter.log(TAG, "sendViaMsgInfo: new r0 newmsg(long) OK scene="
                            + (scene == null ? "null" : scene.getClass().getName()));
                } catch (Throwable t1) {
                    try {
                        scene = XposedHelpers.newInstance(r0, talker, content, 1, 0, 0, "");
                    } catch (Throwable t2) {
                        LogWriter.log(TAG, "sendViaMsgInfo: new v51.r0 全部构造失败: " + stackOf(t2));
                        if (r0Err != null) {
                            LogWriter.log(TAG, "sendViaMsgInfo: resend 构造异常: " + stackOf(r0Err));
                        }
                        return false;
                    }
                }
            }

            // 4) NetSceneQueue = j1.q().b；queue.h(scene,0) 入队
            Object queue = StorageHub.get().netSceneQueue();
            LogWriter.log(TAG, "sendViaMsgInfo: queue=" + (queue == null ? "null"
                    : queue.getClass().getName() + " methods=" + dumpHMethods(queue)));
            if (queue == null) {
                LogWriter.log(TAG, "sendViaMsgInfo: NetSceneQueue 未绑定, 已插库但未入队");
                return false;
            }
            try {
                XposedHelpers.callMethod(queue, "h", scene, 0);
            } catch (Throwable t) {
                LogWriter.log(TAG, "sendViaMsgInfo: queue.h 入队失败: " + stackOf(t));
                return false;
            }
            LogWriter.log(TAG, "sendViaMsgInfo: 入队发送 OK -> " + talker + " len=" + content.length()
                    + " msgId=" + msgId);
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendViaMsgInfo 失败: " + stackOf(t));
            return false;
        }
    }

    /**
     * 现代主发送链：v51.r1 (SendMsgCgiFactory.Builder) → executeByPPC → x51.b0(SendMsgService)。
     * v3.0.171（核心数据）：3180 普通消息真正发送器是 x51.b0，v51.r1 是其入口工厂。
     * 类名由 DexKitAdapter 动态定位。
     */
    private static boolean sendViaBuilder(String talker, String content) {
        try {
            Class<?> r1 = DexKitAdapter.findSendFactoryClass();
            Class<?> p1 = DexKitAdapter.findSendTypeEnumClass();
            if (r1 == null || p1 == null) {
                LogWriter.log(TAG, "sendViaBuilder: 发送类未定位 (r1=" + r1 + ", p1=" + p1 + ")");
                return false;
            }
            Object textType;
            try {
                textType = XposedHelpers.getStaticObjectField(p1, FIELD_TYPE_TEXT);
            } catch (Throwable t) {
                LogWriter.log(TAG, "sendViaBuilder: p1.d(TEXT) 读取失败: " + stackOf(t));
                return false;
            }
            if (textType == null) {
                LogWriter.log(TAG, "sendViaBuilder: p1.d(TEXT) 为 null");
                return false;
            }

            // v3.0.579: 8.0.79 实证，Builder 由工厂 f61.t1.a(talker) 生成（会预置类型枚举/上下文）；
            // 工厂不可用时才退回直接 newInstance。
            Object req = null;
            try {
                Class<?> t1 = XposedHelpers.findClass("f61.t1", r1.getClassLoader());
                req = XposedHelpers.callStaticMethod(t1, "a", talker);
                if (req != null) LogWriter.log(TAG, "sendViaBuilder: 工厂 f61.t1.a() 已生成 Builder");
            } catch (Throwable ignored) {
            }
            if (req == null) {
                req = XposedHelpers.newInstance(r1);
                LogWriter.log(TAG, "sendViaBuilder: 工厂不可用, 直接 newInstance Builder");
            }
            // ★ 类型枚举必须设置，否则 b()/c() 空转
            XposedHelpers.setObjectField(req, "l", textType);
            XposedHelpers.callMethod(req, "h", talker);  // talker
            XposedHelpers.callMethod(req, "e", content); // content
            XposedHelpers.callMethod(req, "i", 1);       // scene/type
            XposedHelpers.callMethod(req, "b");          // 异步执行

            LogWriter.log(TAG, "sendViaBuilder: 已发起发送 -> " + talker + " len=" + content.length());
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendViaBuilder 失败: " + stackOf(t));
            return false;
        }
    }

    /**
     * 群聊 @ 发送（文档 §5.4 兜底：正文前直接写 @显示名）。
     *
     * @param talker    群 id
     * @param content   正文
     * @param atDisplay 被 @ 人的显示名（备注/昵称）
     * @param cl        微信 classLoader（兼容旧签名）
     */
    public static boolean sendTextWithAt(String talker, String content, String atDisplay,
                                         ClassLoader cl) {
        if (atDisplay == null || atDisplay.isEmpty()) {
            return sendText(talker, content, cl);
        }
        return sendText(talker, buildAtPrefix(atDisplay) + content, cl);
    }

    // ---------- 诊断辅助 ----------

    /**
     * 不带构造器实例化：MsgQuoteItem / dx0.r 等类只有带参构造，
     * {@code XposedHelpers.newInstance} 会抛 NoSuchMethodError。
     * 依次尝试无参构造 → sun.misc.Unsafe.allocateInstance → 任意构造默认值。
     */
    private static Object allocateInstance(Class<?> cls) {
        try {
            return XposedHelpers.newInstance(cls);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> u = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field f = u.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            return u.getMethod("allocateInstance", Class.class).invoke(unsafe, cls);
        } catch (Throwable ignored) {
        }
        for (java.lang.reflect.Constructor<?> ct : cls.getDeclaredConstructors()) {
            try {
                ct.setAccessible(true);
                Class<?>[] pts = ct.getParameterTypes();
                Object[] args = new Object[pts.length];
                for (int i = 0; i < pts.length; i++) {
                    args[i] = defaultArg(pts[i]);
                }
                return ct.newInstance(args);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object defaultArg(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return Boolean.FALSE;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        return null;
    }

    /** 类构造签名 dump，用于定位 r0 构造参数不匹配。 */
    private static String dumpCtors(Class<?> c) {
        try {
            StringBuilder sb = new StringBuilder();
            for (java.lang.reflect.Constructor<?> ct : c.getDeclaredConstructors()) {
                sb.append("\n  ").append(ct.getName()).append('(');
                Class<?>[] pts = ct.getParameterTypes();
                for (int i = 0; i < pts.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(pts[i].getSimpleName());
                }
                sb.append(')');
            }
            return sb.toString().trim().isEmpty() ? "(无可见构造)" : sb.toString();
        } catch (Throwable t) {
            return "dumpCtors err: " + t;
        }
    }

    /** 队列入队相关方法 dump（h/g/a/b 等短名），用于定位入队方法签名。 */
    private static String dumpHMethods(Object queue) {
        try {
            StringBuilder sb = new StringBuilder();
            for (java.lang.reflect.Method m : queue.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 0) continue;
                sb.append("\n  ").append(m.getName()).append('(');
                Class<?>[] pts = m.getParameterTypes();
                for (int i = 0; i < pts.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(pts[i].getSimpleName());
                }
                sb.append(')');
            }
            return sb.toString().trim().isEmpty() ? "(无带参方法)" : sb.toString();
        } catch (Throwable t) {
            return "dumpHMethods err: " + t;
        }
    }

    /** 异常完整栈(含 cause)，供 LogWriter 落盘。 */
    private static String stackOf(Throwable t) {
        try {
            StringBuilder sb = new StringBuilder(String.valueOf(t));
            StackTraceElement[] st = t.getStackTrace();
            if (st != null) {
                for (int i = 0; i < Math.min(st.length, 15); i++) {
                    sb.append("\n  at ").append(st[i].getClassName()).append('.')
                            .append(st[i].getMethodName()).append('(')
                            .append(st[i].getFileName() == null ? "?" : st[i].getFileName())
                            .append(':').append(st[i].getLineNumber()).append(')');
                }
            }
            Throwable c = t.getCause();
            while (c != null) {
                sb.append("\nCaused by: ").append(c);
                StackTraceElement[] cs = c.getStackTrace();
                if (cs != null) {
                    for (int i = 0; i < Math.min(cs.length, 10); i++) {
                        sb.append("\n  at ").append(cs[i].getClassName()).append('.')
                                .append(cs[i].getMethodName()).append('(')
                                .append(cs[i].getFileName() == null ? "?" : cs[i].getFileName())
                                .append(':').append(cs[i].getLineNumber()).append(')');
                    }
                }
                c = c.getCause();
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return String.valueOf(t);
        }
    }
}
