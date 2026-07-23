package com.example.leshao;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 最终正确版 — 基于第9轮日志发现
 *
 * 关键发现:
 *   N钩子#135: content=<?xml version="1.0"?><pushcontent content="乐少 : 9...
 *   → proto中取到的不是消息正文而是 msgsource/pushcontent 元数据!
 *   → type=-1 talker=null → proto字段名被混淆了
 *
 * e9.getType() → ✅ 正常工作
 * e9.O0()     → ✅ 正常工作
 * e9.j()      → ✅ n()源码里就调用了它, S1()已设置
 * e9.N0()     → C()源码中调用, 继承自 dm.c8
 *
 * 修复: n()钩子中不用proto反射, 直接用e9自身方法!
 */
public class MessageHook {

    static int sCount = 0;

    public static void hook(ClassLoader cl) {
        try {
            Class<?> x9Cls = cl.loadClass("e01.x9");
            Class<?> e9Cls = cl.loadClass("com.tencent.mm.storage.e9");

            // ── C(e9) 消息入库 ──
            for (java.lang.reflect.Method m : x9Cls.getDeclaredMethods()) {
                if (m.getName().equals("C") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == e9Cls) {
                    XposedHelpers.findAndHookMethod(x9Cls, "C", e9Cls,
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                onMessage(p.args[0]);
                            }
                        });
                    XposedBridge.log("[MsgHook] C(e9) OK");
                    break;
                }
            }

            // ── n(e9,p0) — 主力: 用e9自身方法 ──
            for (java.lang.reflect.Method m : x9Cls.getDeclaredMethods()) {
                if (m.getName().equals("n") && m.getParameterCount() == 2
                    && m.getParameterTypes()[0] == e9Cls) {
                    XposedHelpers.findAndHookMethod(x9Cls, "n",
                        e9Cls, m.getParameterTypes()[1],
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                onMessageFromE9(p.args[0]);
                            }
                        });
                    XposedBridge.log("[MsgHook] n(e9,p0) OK");
                    break;
                }
            }

        } catch (Throwable t) {
            XposedBridge.log("[MsgHook] FAIL: " + t);
            t.printStackTrace();
        }
    }

    // ===== 从 e9 直接取 =====
    static void onMessageFromE9(Object e9) {
        try {
            int type   = (int) XposedHelpers.callMethod(e9, "getType");
            int isSend = (int) XposedHelpers.callMethod(e9, "O0");
            String talker;
            try {
                talker = (String) XposedHelpers.callMethod(e9, "N0");
            } catch (Throwable t) {
                talker = (String) XposedHelpers.getObjectField(e9, "N0");
            }
            String content = (String) XposedHelpers.callMethod(e9, "j");

            // ★ 如果 j()返回的是XML元数据(<msgsource>/<pushcontent>),跳过
            if (content != null && (content.startsWith("<msgsource>")
                || content.startsWith("<pushcontent>")
                || content.startsWith("<?xml")))
                return;

            XposedBridge.log("[MsgHook] #" + (++sCount)
                + " type=" + type + " isSend=" + isSend
                + " talker=" + trunc(talker, 20)
                + " content=" + trunc(content, 50));

            if (isSend == 1) return;
            dispatch(type, talker, content);

        } catch (Throwable t) {
            // 频繁调用,不打印
        }
    }

    // ===== C(e9) 入口 =====
    static void onMessage(Object e9) {
        try {
            int type   = (int) XposedHelpers.callMethod(e9, "getType");
            int isSend = (int) XposedHelpers.callMethod(e9, "O0");
            String talker;
            try {
                talker = (String) XposedHelpers.callMethod(e9, "N0");
            } catch (Throwable t) {
                talker = (String) XposedHelpers.getObjectField(e9, "N0");
            }
            String content = (String) XposedHelpers.callMethod(e9, "j");

            XposedBridge.log("[MsgHook-C] #" + (++sCount)
                + " type=" + type + " isSend=" + isSend
                + " talker=" + trunc(talker, 20)
                + " content=" + trunc(content, 50));

            if (isSend == 1) return;
            dispatch(type, talker, content);

        } catch (Throwable t) {
            XposedBridge.log("[MsgHook-C] err: " + t);
        }
    }

    static void dispatch(int type, String talker, String content) {
        String name = NicknameResolver.resolve(talker);
        boolean g = talker != null && talker.endsWith("@chatroom");
        String p = g ? "群聊" : "";

        String speak = null;
        switch (type) {
            case 1:
                String t = clean(content);
                if (t == null || t.isEmpty()) return;
                if (t.contains("微信红包") || t.contains("已收款")) return;
                speak = p + name + "说：" + t; break;
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
    static String trunc(String s, int m) { return s==null?"":s.length()>m?s.substring(0,m)+"...":s; }
}
