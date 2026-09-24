package com.leshao.ai.hook.wechat;

import android.util.Log;

import com.leshao.ai.hook.HookEntry;
import com.leshao.ai.hook.dexkit.DexKitAdapter;
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.VersionCompat;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedHelpers;

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
        if (talker == null || talker.isEmpty() || content == null || content.isEmpty()) {
            return false;
        }
        // 优先 v1043 标准链：e9 构造 → f9.Bb(true) → v51.r0 → queue.h 入队
        if (sendViaMsgInfo(talker, content, cl)) {
            return true;
        }
        LogWriter.log(TAG, "sendText: v51.r0 链路失败, 回退 v51.r1 Builder");
        return sendViaBuilder(talker, content);
    }

    /**
     * 标准链：构造 e9(isSend=1,type=1) → f9.Bb(msg,true) → new v51.r0(msgId,talker) → queue.h(scene,0)。
     * 发送前 lj.N3(talker,msgId) 自检读表，避免 resend 读表失败静默掉。
     */
    private static boolean sendViaMsgInfo(String talker, String content, ClassLoader cl) {
        try {
            ClassLoader tk = VersionCompat.findTinkerClassLoader(cl != null ? cl : HookEntry.appClassLoader);
            LogWriter.log(TAG, "sendViaMsgInfo: tk=" + (tk == null ? "null" : tk.getClass().getName()));
            if (tk == null) {
                LogWriter.log(TAG, "sendViaMsgInfo: Tinker CL 未就绪");
                return false;
            }
            Class<?> e9 = XposedHelpers.findClass("com.tencent.mm.storage.e9", tk);
            Class<?> r0 = XposedHelpers.findClass("v51.r0", tk);
            LogWriter.log(TAG, "sendViaMsgInfo: e9=" + e9.getName() + " r0=" + r0.getName()
                    + " r0ctors=" + dumpCtors(r0));
            Object f9 = StorageHub.get().msgInfoStorage();
            LogWriter.log(TAG, "sendViaMsgInfo: f9=" + (f9 == null ? "null" : f9.getClass().getName()));
            if (f9 == null) {
                LogWriter.log(TAG, "sendViaMsgInfo: f9(MsgInfoStorage) 未绑定");
                return false;
            }

            // 1) 构造 e9
            Object msg = XposedHelpers.newInstance(e9);
            XposedHelpers.callMethod(msg, "u1", talker);
            XposedHelpers.callMethod(msg, "b1", content);
            XposedHelpers.callMethod(msg, "setType", 1);
            XposedHelpers.callMethod(msg, "e1", System.currentTimeMillis());
            XposedHelpers.callMethod(msg, "k1", 1);   // isSend = 1
            XposedHelpers.callMethod(msg, "t1", 1);   // status = 1
            XposedHelpers.callMethod(msg, "r3", "");  // msgSource

            // 2) 本地插入(REPLACE)
            long msgId;
            try {
                Object ret = XposedHelpers.callMethod(f9, "Bb", msg, true);
                msgId = ((Number) ret).longValue();
            } catch (Throwable t) {
                LogWriter.log(TAG, "sendViaMsgInfo: Bb 失败: " + t.getMessage());
                return false;
            }
            // 自检：resend 读表失败会静默(doScene=-2)，发送前确认能读回
            Object back = XposedHelpers.callMethod(f9, "N3", talker, msgId);
            if (back == null) {
                LogWriter.log(TAG, "sendViaMsgInfo: N3 自检读回 null (msgId=" + msgId + "), 表名可能不符");
            } else {
                LogWriter.log(TAG, "sendViaMsgInfo: Bb OK msgId=" + msgId
                        + " 回读=" + back.getClass().getName());
            }

            // 3) v51.r0 发送 scene
            Object scene = null;
            try {
                scene = XposedHelpers.newInstance(r0, msgId, talker);
                LogWriter.log(TAG, "sendViaMsgInfo: new r0 OK msgId=" + msgId
                        + " scene=" + (scene == null ? "null" : scene.getClass().getName()));
            } catch (Throwable t) {
                LogWriter.log(TAG, "sendViaMsgInfo: new v51.r0 失败: " + stackOf(t));
                return false;
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
     * 回退链：v51.r1 (SendMsgCgiFactory.Builder)。类名由 DexKitAdapter 动态定位。
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

            Object req = XposedHelpers.newInstance(r1);
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
        return sendText(talker, "@" + atDisplay + " " + content, cl);
    }

    // ---------- 诊断辅助 ----------

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
