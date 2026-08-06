package com.leshao.v3.hook;

import android.os.Handler;
import android.os.Looper;


import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.service.TTSBroadcaster;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class MessageHook {

    private static final String TAG = "MessageHook";
    private static int sCount = 0;
    private static Handler sMainHandler;
    private static ClassLoader sClassLoader;
    private static final Set<Long> sSeenMsgIds = new HashSet<>();
    private static final ThreadLocal<Boolean> sConsumedTtsOriginal = new ThreadLocal<>();

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
            String[] candidates = {"e01.x9", "e02.x9", "e00.x9", "e01.x8", "e01.y9",
                    "e01.w9", "e02.x8", "e01.z9", "e00.y9", "e03.x9", "e01.x10"};
            for (String name : candidates) {
                try { x9Cls = cl.loadClass(name); break; } catch (Throwable ignored) {}
            }
            if (x9Cls == null) {
                LogWriter.log(TAG, "x9 class not found, trying DexFile enumeration");
                x9Cls = VersionCompat.findMsgDispatchClass(cl);
            }
            if (x9Cls == null) {
                LogWriter.log(TAG, "x9 class ALL strategies failed");
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
                    final Class<?> returnType = m.getReturnType();
                    XposedBridge.hookMethod(m,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                sConsumedTtsOriginal.set(Boolean.FALSE);
                                if (TtsVoiceSender.consumeBlockedOriginal(p.args[0])) {
                                    LogWriter.log(TAG, "consume blocked #tts original x9." + m.getName()
                                            + " return=" + returnType.getName());
                                    sConsumedTtsOriginal.set(Boolean.TRUE);
                                    p.setResult(defaultReturnValue(returnType));
                                    return;
                                }
                                if (TtsVoiceSender.shouldConsumeTtsFailureMessage(p.args[0])) {
                                    LogWriter.log(TAG, "consume #tts failure residue x9." + m.getName()
                                            + " return=" + returnType.getName());
                                    sConsumedTtsOriginal.set(Boolean.TRUE);
                                    p.setResult(defaultReturnValue(returnType));
                                }
                            }

                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                if (Boolean.TRUE.equals(sConsumedTtsOriginal.get())) {
                                    sConsumedTtsOriginal.remove();
                                    return;
                                }
                                sConsumedTtsOriginal.remove();
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
            boolean isVoice = (rawType == 34 || rawType == 228);
            boolean isTts = content != null && content.startsWith("#tts");
            if (isVoice || isTts) {
                LogWriter.log(TAG, "#" + sCount
                    + " type=" + rawType + "->" + type
                    + " isSend=" + isSend + " msgId=" + msgId
                    + " talker=" + trunc(talker, 20)
                    + " content=" + trunc(content, 40));
            }

            // 去重: 同一个 msgId 只处理一次
            synchronized (sSeenMsgIds) {
                if (msgId != 0 && !sSeenMsgIds.add(msgId)) {
                    android.util.Log.e(TAG, "!!! SKIP duplicate msgId=" + msgId);
                    return;
                }
                // 防止内存膨胀: 超过 400 条时移除最旧条目，而非整体清空
                // （整体清空会导致同一消息的多重 hook 回调再次触发重复播报）
                if (sSeenMsgIds.size() > 400) {
                    Long oldest = sSeenMsgIds.iterator().next();
                    sSeenMsgIds.remove(oldest);
                }
            }


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

                if (rawType == 34 || rawType == 228) {
                    final long voiceMsgId = msgId;
                    final String vTalker = talker;
                    if (!VoiceAutoPlay.shouldAutoPlay(vTalker)) {
                        android.util.Log.e(TAG, ">>> VOICE skipped (whitelist) talker=" + vTalker + " msgId=" + voiceMsgId);
                        LogWriter.log("VoiceAutoPlay", "skip whitelist talker=" + vTalker + " msgId=" + voiceMsgId);
                        return;
                    }
                    android.util.Log.e(TAG, ">>> VOICE msgId=" + voiceMsgId + " talker=" + vTalker + " isSend=" + isSend);
                    LogWriter.log("VoiceAutoPlay", "rawType=" + rawType + " msgId=" + voiceMsgId + " talker=" + vTalker);
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

            java.lang.reflect.Method eMethod = iEventClz.getDeclaredMethod("e");
            XposedBridge.hookMethod(eMethod, new XC_MethodHook() {
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

    private static Object defaultReturnValue(Class<?> returnType) {
        if (returnType == Void.TYPE) return null;
        if (returnType == Boolean.TYPE) return false;
        if (returnType == Byte.TYPE) return (byte) 0;
        if (returnType == Short.TYPE) return (short) 0;
        if (returnType == Integer.TYPE) return 0;
        if (returnType == Long.TYPE) return 0L;
        if (returnType == Float.TYPE) return 0f;
        if (returnType == Double.TYPE) return 0d;
        if (returnType == Character.TYPE) return (char) 0;
        return null;
    }
}
