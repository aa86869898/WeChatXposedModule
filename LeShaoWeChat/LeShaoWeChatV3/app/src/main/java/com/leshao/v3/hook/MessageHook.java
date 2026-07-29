package com.leshao.v3.hook;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.service.TTSBroadcaster;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class MessageHook {

    private static final String TAG = "MessageHook";
    private static int sCount = 0;
    private static Handler sMainHandler;
    private static ClassLoader sClassLoader;

    public static void hook(ClassLoader cl) {
        sClassLoader = cl;
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

            // Hook 所有带 e9 参数的方法, 确保收入/发出消息都能捕获
            int hooked = 0;
            for (java.lang.reflect.Method m : x9Cls.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length >= 1 && pts[0] == e9Cls) {
                    final int paramCount = pts.length;
                    XposedBridge.hookMethod(m,
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                onMessage(p.args[0], paramCount >= 2 ? p.args[1] : null);
                            }
                        });
                    LogWriter.log(TAG, "hooked x9." + m.getName() + "(" + pts.length + ")");
                    hooked++;
                }
            }
            LogWriter.log(TAG, "x9 hooks installed: " + hooked + " methods");

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

            int isSend = -1;
            try { isSend = (Integer) XposedHelpers.callMethod(e9, "z0"); } catch (Throwable ignored) {}
            long msgId = 0;
            try { msgId = (Long) XposedHelpers.callMethod(e9, "H0"); } catch (Throwable ignored) {}

            sCount++;
            LogWriter.log(TAG, "#" + sCount
                + " type=" + rawType + "->" + type
                + " isSend=" + isSend + " msgId=" + msgId
                + " talker=" + trunc(talker, 20)
                + " content=" + trunc(content, 40));
            android.util.Log.e(TAG, "!!! RAW #" + sCount + ": isSend=" + isSend + " rawType=" + rawType
                    + " msgId=" + msgId + " talker=" + talker
                    + " content=[" + (content == null ? "null" : content.substring(0, Math.min(content.length(), 60))) + "]");

            // #tts 指令: 自己发出的文字消息 (需开关开启)
            if (isSend == 1 && content != null && content.startsWith("#tts ")) {
                SharedPreferences prefs = ContextManager.getPrefs();
                if (prefs == null || !prefs.getBoolean("ls_tts_command", false)) {
                    android.util.Log.e(TAG, "*** #tts ignored: ls_tts_command is OFF");
                    return;
                }
                final String ttsText = content.substring(5).trim();
                final String ttsTalker = talker;
                android.util.Log.e(TAG, "*** #tts DETECTED: text=" + ttsText + " talker=" + ttsTalker);
                LogWriter.log("TtsVoiceSender", "#tts: " + ttsText.substring(0, Math.min(ttsText.length(), 40)) + " talker=" + ttsTalker);
                sMainHandler.post(() -> {
                    try {
                        TtsVoiceSender.synthesizeAndSend(ttsText, ttsTalker);
                    } catch (Throwable e) {
                        LogWriter.log("TtsVoiceSender", "err: " + e.getMessage());
                    }
                });
                return;
            }

            // 收到的消息
            if (isSend != 1) {
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

                // 语音自动播放
                if (rawType == 34) {
                    final long voiceMsgId = msgId;
                    LogWriter.log("VoiceAutoPlay", "rawType=34 msgId=" + voiceMsgId + " talker=" + talker);
                    sMainHandler.post(() -> {
                        try {
                            VoiceAutoPlay.onVoiceMsg(e9, voiceMsgId, p0);
                        } catch (Throwable e) {
                            LogWriter.log("VoiceAutoPlay", "msgHook err: " + e.getMessage());
                        }
                    });
                }
            }

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
