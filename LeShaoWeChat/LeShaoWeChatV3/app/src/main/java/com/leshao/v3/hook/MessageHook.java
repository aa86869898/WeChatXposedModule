package com.leshao.v3.hook;

import android.os.Handler;
import android.os.Looper;

import com.leshao.v3.LogWriter;
import com.leshao.v3.service.TTSBroadcaster;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class MessageHook {

    private static final String TAG = "MessageHook";
    private static int sCount = 0;
    private static Handler sMainHandler;
    private static java.lang.reflect.Method sTypeMapper;

    public static void hook(ClassLoader cl) {
        sMainHandler = new Handler(Looper.getMainLooper());

        try {
            Class<?> x9Cls = null;
            for (String name : new String[]{"e01.x9", "e02.x9", "e00.x9", "e01.x8", "e01.y9"}) {
                try { x9Cls = cl.loadClass(name); break; } catch (Throwable ignored) {}
            }
            if (x9Cls == null) {
                LogWriter.log(TAG, "FAIL: dispatch class not found");
                return;
            }
            Class<?> e9Cls = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Cls == null) {
                LogWriter.log(TAG, "FAIL: storage class not found");
                return;
            }

            for (java.lang.reflect.Method m : x9Cls.getDeclaredMethods()) {
                if (m.getName().equals("n") && m.getParameterCount() == 2
                    && m.getParameterTypes()[0] == e9Cls) {
                    XposedBridge.hookMethod(m,
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                onMessage(p.args[0], p.args[1]);
                            }
                        });
                    LogWriter.log(TAG, "n(e9,p0) OK");
                    break;
                }
            }

            for (java.lang.reflect.Method m : x9Cls.getDeclaredMethods()) {
                if (m.getName().equals("C") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == e9Cls) {
                    XposedBridge.hookMethod(m,
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                onMessage(p.args[0], null);
                            }
                        });
                    LogWriter.log(TAG, "C(e9) OK");
                    break;
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "FAIL: " + t);
        }
    }

    static void onMessage(Object e9, Object p0) {
        try {
            int rawType = (int) XposedHelpers.callMethod(e9, "getType");
            int type = mapType(rawType);
            String talker = (String) XposedHelpers.callMethod(e9, "N0");
            String content = null;
            try { content = (String) XposedHelpers.callMethod(e9, "I0"); } catch (Throwable ignored) {}
            if (content == null) try { content = (String) XposedHelpers.callMethod(e9, "j"); } catch (Throwable ignored) {}

            if (content != null && (content.startsWith("<msgsource")
                || content.startsWith("<pushcontent")))
                return;

            sCount++;
            LogWriter.log(TAG, "#" + sCount
                + " type=" + rawType + "->" + type
                + " talker=" + trunc(talker, 20)
                + " content=" + trunc(content, 40));

            final int fType = type;
            final String fTalker = talker;
            final String fContent = content;
            sMainHandler.post(() -> {
                try {
                    TTSBroadcaster.handleMessageRaw(fType, fTalker, fContent);
                } catch (Throwable e) {
                    LogWriter.log("TTS", "err: " + e.getMessage());
                }
            });

            // 语音自动播放 (type==34)
            if (rawType == 34) {
                final long msgId = (Long) XposedHelpers.callMethod(e9, "H0");
                LogWriter.log("VoiceAutoPlay", "MSG-HOOK-TV: rawType=34 msgId=" + msgId + " talker=" + talker);
                sMainHandler.post(() -> {
                    try {
                        VoiceAutoPlay.tryAutoPlayVoice(e9, msgId, p0);
                    } catch (Throwable e) {
                        LogWriter.log("VoiceAutoPlay", "msgHook err: " + e.getMessage());
                    }
                });
            }

            // TTS #tts 检测：自己是发出的 type=1 且 content 以 #tts 开头
            try {
                int isSend = (Integer) XposedHelpers.callMethod(e9, "z0");
                if (isSend == 1 && rawType == 1 && content != null && content.startsWith("#tts ")) {
                    final String ttsText = content.substring(5).trim();
                    final String ttsTalker = talker;
                    android.util.Log.e(TAG, "!!! #tts outgoing: " + ttsText + " talker=" + ttsTalker);
                    LogWriter.log("TtsVoiceSender", "#tts detected in outgoing: " + ttsText.substring(0, Math.min(ttsText.length(), 40)) + " talker=" + ttsTalker);
                    sMainHandler.post(() -> {
                        try {
                            TtsVoiceSender.synthesizeAndSend(ttsText, ttsTalker);
                        } catch (Throwable e) {
                            LogWriter.log("TtsVoiceSender", "err: " + e.getMessage());
                        }
                    });
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            LogWriter.log(TAG, "err: " + t);
        }
    }

    static String trunc(String s, int m) {
        return s == null ? "" : s.length() > m ? s.substring(0, m) + "..." : s;
    }

    static int mapType(int t) {
        if (t >= 268435456 || t == 74 || t == 83 || t == 84 || t == 87
            || t == 95 || t == 102 || t == 103 || t == 131 || t == 132
            || t == 1048625 || t == 16777265) return 49;
        return t;
    }
}
