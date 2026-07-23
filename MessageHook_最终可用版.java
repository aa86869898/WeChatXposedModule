package com.example.leshao;

import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 最终可用版 MessageHook — 已修复TTS不响的问题
 *
 * 修复点:
 *   1. Android TTS 必须在主线程调用 → Handler(Looper.getMainLooper())
 *   2. O0()返回值不是0/1 → 不检查isSend
 *   3. 加[TTS-OK]日志确认是否成功调用
 */
public class MessageHook {

    static int sCount = 0;
    static Handler sMainHandler;

    public static void hook(ClassLoader cl) {
        sMainHandler = new Handler(Looper.getMainLooper());

        try {
            Class<?> x9Cls = cl.loadClass("e01.x9");
            Class<?> e9Cls = cl.loadClass("com.tencent.mm.storage.e9");

            // ── n(e9,p0) — 主力 ──
            for (java.lang.reflect.Method m : x9Cls.getDeclaredMethods()) {
                if (m.getName().equals("n") && m.getParameterCount() == 2
                    && m.getParameterTypes()[0] == e9Cls) {
                    Class<?> p0Cls = m.getParameterTypes()[1];
                    XposedHelpers.findAndHookMethod(x9Cls, "n", e9Cls, p0Cls,
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                onMessage(p.args[0]);
                            }
                        });
                    XposedBridge.log("[MsgHook] n(e9,p0) OK");
                    break;
                }
            }

            // ── C(e9) — 备选 ──
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
        } catch (Throwable t) {
            XposedBridge.log("[MsgHook] FAIL: " + t);
            t.printStackTrace();
        }
    }

    // ===== 统一入口 =====
    static void onMessage(Object e9) {
        try {
            int type   = (int) XposedHelpers.callMethod(e9, "getType");
            String talker = (String) XposedHelpers.callMethod(e9, "N0");
            String content = (String) XposedHelpers.callMethod(e9, "j");

            // 过滤XML元数据
            if (content != null && (content.startsWith("<msgsource")
                || content.startsWith("<pushcontent")
                || content.startsWith("<?xml")))
                return;

            XposedBridge.log("[MsgHook] #" + (++sCount)
                + " type=" + type + " talker=" + trunc(talker, 20)
                + " content=" + trunc(content, 40));

            // 只处理文本消息
            if (type != 1) return;
            if (content == null || content.isEmpty()) return;

            // ★★★ 在主线程调用TTS ★★★
            final String fTalker = talker;
            final String fContent = content;
            sMainHandler.post(() -> speak(fTalker, fContent));

        } catch (Throwable t) {
            XposedBridge.log("[MsgHook] err: " + t);
            t.printStackTrace();
        }
    }

    // ===== 主线程播报 =====
    static void speak(String talker, String content) {
        try {
            String name = NicknameResolver.resolve(talker);
            boolean isGroup = talker != null && talker.endsWith("@chatroom");
            String prefix = isGroup ? "群聊" : "";

            String text = clean(content);
            if (text.isEmpty()) return;
            if (text.contains("微信红包") || text.contains("已收款")) return;

            String speak = prefix + name + "说：" + text;

            if (TtsEngine.isReady()) {
                XposedBridge.log("[TTS-OK] " + speak);
                TtsEngine.speak(speak);
            } else {
                XposedBridge.log("[TTS-FAIL] TTS not ready!");
            }
        } catch (Throwable t) {
            XposedBridge.log("[TTS-ERR] " + t);
            t.printStackTrace();
        }
    }

    static String clean(String s) {
        if (s == null || s.isEmpty()) return "";
        s = s.replace("<![CDATA[","").replace("]]>","");
        s = s.replaceAll("<[^>]+>","").replaceAll("https?://\\S+","链接");
        s = s.replaceAll("@\\S+\\s+","").replaceAll("\\[\\w+\\]","").replace("\n"," ").trim();
        return s.length() > 300 ? s.substring(0,300)+"等" : s;
    }

    static String trunc(String s, int m) {
        return s == null ? "" : s.length() > m ? s.substring(0, m) + "..." : s;
    }
}
