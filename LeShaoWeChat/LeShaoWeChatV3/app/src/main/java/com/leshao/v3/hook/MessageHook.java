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

        LogWriter.log(TAG, "=== v56 IEvent.e hook ===");
        android.util.Log.e(TAG, "=== v56 IEvent.e hook ===");

        hookX9Dispatch(cl);
        hookIEventBus(cl);
    }

    // ====== x9 分发 (接收消息: TTS + 语音播放) ======

    private static void hookX9Dispatch(ClassLoader cl) {
        try {
            Class<?> x9Cls = null;
            for (String name : new String[]{"e01.x9", "e02.x9", "e00.x9", "e01.x8", "e01.y9"}) {
                try { x9Cls = cl.loadClass(name); break; } catch (Throwable ignored) {}
            }
            if (x9Cls == null) {
                LogWriter.log(TAG, "x9 class not found");
                return;
            }
            Class<?> e9Cls = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Cls == null) {
                LogWriter.log(TAG, "e9 class not found");
                return;
            }

            int hooked = 0;
            for (java.lang.reflect.Method m : x9Cls.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length >= 1 && pts[0] == e9Cls) {
                    final int paramCount = pts.length;
                    XposedBridge.hookMethod(m,
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                onX9Message(p.args[0], paramCount >= 2 ? p.args[1] : null);
                            }
                        });
                    LogWriter.log(TAG, "hooked x9." + m.getName() + "(" + pts.length + ")");
                    hooked++;
                }
            }
            LogWriter.log(TAG, "x9 hooks installed: " + hooked + " methods");

        } catch (Throwable t) {
            LogWriter.log(TAG, "hookX9 FAIL: " + t.getMessage());
        }
    }

    static void onX9Message(Object e9, Object p0) {
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

    // ====== IEvent.e() 事件总线 (拦截自己发出的 #tts) ======

    private static void hookIEventBus(ClassLoader cl) {
        try {
            Class<?> iEventClz = cl.loadClass("com.tencent.mm.sdk.event.IEvent");
            Class<?> sendOkClz = cl.loadClass("com.tencent.mm.autogen.events.SendMsgSuccessEvent");

            XposedHelpers.findAndHookMethod(iEventClz, "e", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!sendOkClz.isInstance(param.thisObject)) return;

                        Object data = XposedHelpers.getObjectField(param.thisObject, "g");
                        if (data == null) return;
                        Object e9 = XposedHelpers.getObjectField(data, "a");
                        if (e9 == null) return;

                        String content = (String) XposedHelpers.callMethod(e9, "j");
                        String talker = (String) XposedHelpers.callMethod(e9, "N0");
                        long msgId = (Long) XposedHelpers.callMethod(e9, "getMsgId");
                        int type = (Integer) XposedHelpers.callMethod(e9, "getType");

                        LogWriter.log(TAG, "IEvent.e: type=" + type + " talker=" + talker
                            + " msgId=" + msgId + " content=" + trunc(content, 30));
                        android.util.Log.e(TAG, ">>> IEvent.e SendMsgSuccess: type=" + type
                            + " talker=" + talker + " msgId=" + msgId
                            + " content=[" + (content == null ? "null" : content.substring(0, Math.min(content.length(), 60))) + "]");

                        if (type != 1 || content == null || !content.startsWith("#tts ")) return;

                        SharedPreferences prefs = ContextManager.getPrefs();
                        if (prefs == null || !prefs.getBoolean("ls_tts_command", false)) {
                            android.util.Log.e(TAG, ">>> #tts ignored: switch OFF");
                            return;
                        }

                        String text = content.substring(5).trim();
                        if (text.isEmpty()) return;

                        android.util.Log.e(TAG, ">>> #tts DETECTED: " + text + " talker=" + talker);
                        LogWriter.log(TAG, "#tts via IEvent: " + text + " talker=" + talker);

                        final String fText = text;
                        final String fTalker = talker;
                        sMainHandler.post(() -> TtsVoiceSender.synthesizeAndSend(fText, fTalker));

                    } catch (Throwable t) {
                        android.util.Log.e(TAG, ">>> IEvent.e hook err: " + t.getMessage());
                    }
                }
            });
            LogWriter.log(TAG, "IEvent.e() hooked OK");
            android.util.Log.e(TAG, ">>> IEvent.e() hooked OK");

        } catch (Throwable t) {
            LogWriter.log(TAG, "hookIEventBus FAIL: " + t.getMessage());
            android.util.Log.e(TAG, ">>> IEvent.e() FAIL: " + t.getMessage());
        }
    }

    // ====== 工具方法 ======

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
