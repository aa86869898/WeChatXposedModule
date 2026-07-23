/**
 * ===============================================================
 * 最终正确版 MessageHook — 基于 e01.x9 源码反编译
 * ===============================================================
 *
 * e01.x9 方法分析:
 *
 *   m(String talker, long msgId) → 缓存 talker (不是内容!)
 *   n(e9, p0)                   → 只设标志位(get/fault/up/fixTime)
 *   e(e9, boolean)              → 发 DeleteMsgEvent! (不是插入!)
 *   C(e9)                       → ★ 消息入库! wj().Ta(msgId, e9, true)
 *
 * 之前一直 Hook 错误的方法:
 *   e() → 它发的是 DeleteMsgEvent, 仅在 isNew=true 时触发
 *   n() → e9 刚创建, I0() 返回 null
 *   m() → String 参数是 talker, 当成 content 了!
 *
 * 正确方案: Hook C(e9) — 消息入库入口, e9.I0() 可读
 */

package com.example.leshao;

import java.lang.ref.WeakHashMap;
import java.lang.reflect.Field;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class MessageHook {

    static int msgCount = 0;
    static Map<Object, String> contentCache = new WeakHashMap<>();

    public static void hook(ClassLoader cl) {
        try {
            // ★ 正确 Hook: e01.x9.C(e9) — 消息入库
            Class<?> x9Cls = cl.loadClass("e01.x9");
            Class<?> e9Cls = cl.loadClass("com.tencent.mm.storage.e9");

            // ── 主力: C(e9) 消息入库后, e9 内容完整 ──
            for (java.lang.reflect.Method m : x9Cls.getDeclaredMethods()) {
                if (m.getName().equals("C") && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0] == e9Cls) {
                    XposedHelpers.findAndHookMethod(x9Cls, "C", e9Cls,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                handleMessage(param.args[0]);
                            }
                        });
                    XposedBridge.log("[MsgHook] S1-C HOOK OK: e01.x9.C(e9)");
                    break;
                }
            }

            // ── 备选: n(e9, p0) 从 p0.a protobuf 取 ──
            for (java.lang.reflect.Method m : x9Cls.getDeclaredMethods()) {
                if (m.getName().equals("n") && m.getParameterTypes().length == 2
                    && m.getParameterTypes()[0] == e9Cls) {
                    Class<?> p0Cls = m.getParameterTypes()[1];
                    XposedHelpers.findAndHookMethod(x9Cls, "n", e9Cls, p0Cls,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                handleFromProto(param.args[0], param.args[1]);
                            }
                        });
                    XposedBridge.log("[MsgHook] S2-N HOOK OK: e01.x9.n(e9, p0)");
                    break;
                }
            }

        } catch (Throwable t) {
            XposedBridge.log("[MsgHook] ALL FAIL: " + t);
        }
    }

    // ===== 主力: C(e9) 阶段 ── e9.I0() 可读 =====
    static void handleMessage(Object e9Obj) {
        try {
            int type   = (int) XposedHelpers.callMethod(e9Obj, "getType");
            int isSend = (int) XposedHelpers.callMethod(e9Obj, "O0");
            String talker = (String) XposedHelpers.callMethod(e9Obj, "N0");

            // ★ e9.j() 是消息摘要/内容提取方法
            String content = (String) XposedHelpers.callMethod(e9Obj, "j");

            XposedBridge.log("[MsgHook-C] #" + (++msgCount) +
                " type=" + type + " isSend=" + isSend +
                " talker=" + trunc(talker, 20) +
                " content=" + trunc(content, 50));

            if (isSend == 1) return;
            if (type != 1 && type != 3 && type != 34 && type != 43 && type != 48) return;
            dispatch(type, talker, content);

        } catch (Throwable t) {
            XposedBridge.log("[MsgHook-C] err: " + t);
        }
    }

    // ===== 备选: n(e9, p0) 从 p0.a protobuf 取 =====
    static void handleFromProto(Object e9Obj, Object p0Obj) {
        try {
            if (p0Obj == null) return;

            // p0.a 是 a65.j4 (protobuf)
            Field fa = p0Obj.getClass().getDeclaredField("a");
            fa.setAccessible(true);
            Object proto = fa.get(p0Obj);
            if (proto == null) return;

            int type = -1, isSend = 0;
            String talker = null, content = null;

            for (Field f : proto.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                try {
                    Object v = f.get(proto);
                    if (v == null) continue;
                    String name = f.getName();

                    if (f.getType() == int.class && name.contains("type")) type = f.getInt(proto);
                    if (f.getType() == int.class && name.contains("send")) isSend = f.getInt(proto);
                    if (v instanceof String && name.contains("talker")) talker = (String) v;
                    if (v instanceof String && !name.contains("talker")
                        && !name.contains("fromUser") && !name.contains("username")) {
                        String s = (String) v;
                        if (s.length() > 1 && !s.contains("@") && !s.matches("\\d+"))
                            content = s;
                    }
                } catch (Exception ignored) {}
            }

            XposedBridge.log("[MsgHook-N] #" + (++msgCount) +
                " type=" + type + " talker=" + trunc(talker, 20) +
                " content=" + trunc(content, 50));

            if (isSend == 1) return;
            if (type != 1 && type != 3 && type != 34 && type != 43 && type != 48) return;
            dispatch(type, talker, content);

        } catch (Throwable t) {
            // 不打印, n() 频繁调用
        }
    }

    // ===== 分发 =====
    static void dispatch(int type, String talker, String content) {
        String name = NicknameResolver.resolve(talker);
        boolean isGroup = talker != null && talker.endsWith("@chatroom");
        String p = isGroup ? "群聊" : "";

        String speak = null;
        switch (type) {
            case 1:
                String t = clean(content);
                if (t == null || t.isEmpty()) return;
                if (t.contains("微信红包") || t.contains("已收款")) return;
                speak = p + name + "说：" + t;
                break;
            case 3:  speak = p + name + "发来一张照片"; break;
            case 34: speak = p + name + "发来语音"; break;
            case 43: speak = p + name + "发来一段视频"; break;
            case 48: speak = p + name + "发来定位在：" + parseLoc(content); break;
        }
        if (speak != null && TtsEngine.isReady()) {
            TtsEngine.speak(speak);
            XposedBridge.log("[TTS] " + speak);
        }
    }

    static String clean(String s) {
        if (s == null || s.isEmpty()) return "";
        s = s.replace("<![CDATA[","").replace("]]>","");
        s = s.replaceAll("<[^>]+>","").replaceAll("https?://\\S+","链接");
        s = s.replaceAll("@\\S+\\s+","").replaceAll("\\[\\w+\\]","").replace("\n"," ").trim();
        return s.length() > 300 ? s.substring(0,300)+"等" : s;
    }
    static String parseLoc(String c) {
        if (c==null) return "未知";
        for (String t : new String[]{"label","poiname"}) {
            int i=c.indexOf(t+"=\""); if(i>=0){int s=i+t.length()+2,e=c.indexOf("\"",s); if(e>s) return c.substring(s,e);}
        }
        return "未知";
    }
    static String trunc(String s, int m) { return s==null?"null":s.length()>m?s.substring(0,m)+"...":s; }
}
