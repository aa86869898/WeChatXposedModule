package com.leshao.v3.hook;

import android.os.Handler;
import android.os.Looper;

import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 语音消息自动播放 v41
 * - hook so.y() 获取 VoiceComponent
 * - voiceComp.d.x() = talker
 * - voiceComp.n0().I(msg, false) = play
 * - loadMsgById 从 ConcurrentHashMap 取原始 e9
 */
public class VoiceAutoPlay {

    private static final String TAG = "VoiceAutoPlay";

    private static boolean sEnabled = true;
    private static long sLastPlayedMsgId = -1L;

    private static ClassLoader sClassLoader;
    private static volatile Object sCurrentVoiceComp;

    private static final Queue<PendingVoiceMsg> sPendingQueue = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<Long, Object> sMsgMap = new ConcurrentHashMap<>();
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private static class PendingVoiceMsg {
        final long msgId;
        final String talker;

        PendingVoiceMsg(long msgId, String talker) {
            this.msgId = msgId;
            this.talker = talker;
        }
    }

    public static void hook(ClassLoader cl) {
        sClassLoader = cl;
        hookVoiceComponentReset();
    }

    // =========== so.y() (resetAutoPlay, 进入聊天时触发) ===========

    private static void hookVoiceComponentReset() {
        try {
            Class<?> soCls = sClassLoader.loadClass("com.tencent.mm.ui.chatting.component.so");
            XposedBridge.hookAllMethods(soCls, "y", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    android.util.Log.e(TAG, "!!! so.y() FIRED");
                    try {
                        Object voiceComp = param.thisObject;
                        sCurrentVoiceComp = voiceComp;
                        XposedBridge.log("[VAP] so.y() VC: " + voiceComp.getClass().getName());

                        Object cc = XposedHelpers.getObjectField(voiceComp, "d");
                        if (cc == null) {
                            XposedBridge.log("[VAP] so.d is null");
                            return;
                        }

                        String talker = null;
                        try { talker = (String) XposedHelpers.callMethod(cc, "x"); }
                        catch (Throwable ignored) {}

                        android.util.Log.e(TAG, "!!! so.y() talker=" + talker + " pending=" + sPendingQueue.size());
                        XposedBridge.log("[VAP] talker=" + talker + " pending=" + sPendingQueue.size());
                        LogWriter.log(TAG, "so.y: talker=" + talker + " pending=" + sPendingQueue.size());

                        List<Long> pending = dequeue(talker);
                        if (pending.isEmpty()) return;

                        Object player = XposedHelpers.callMethod(voiceComp, "n0");
                        if (player == null) {
                            android.util.Log.e(TAG, "!!! player is null");
                            return;
                        }

                        for (long msgId : pending) {
                            Object msg = loadMsgById(msgId);
                            if (msg != null) {
                                try {
                                    XposedHelpers.callMethod(player, "I", msg, false);
                                    android.util.Log.e(TAG, ">>> PLAY msgId=" + msgId);
                                    LogWriter.log(TAG, "PLAY msgId=" + msgId);
                                } catch (Throwable e) {
                                    android.util.Log.e(TAG, ">>> PLAY FAIL msgId=" + msgId + " err=" + e.getMessage());
                                    LogWriter.log(TAG, "play err: " + e.getMessage());
                                }
                            }
                        }
                    } catch (Throwable e) {
                        android.util.Log.e(TAG, "so.y err: " + e.getMessage());
                        XposedBridge.log("[VAP] so.y err: " + e.getMessage());
                    }
                }
            });
            XposedBridge.log("[VAP] so.y() hooked OK");
            LogWriter.log(TAG, "so.y() hooked OK");
        } catch (Throwable t) {
            XposedBridge.log("[VAP] so.y() FAIL: " + t.getMessage());
            LogWriter.log(TAG, "so.y() fail: " + t.getMessage());
        }
    }

    // =========== 队列与查找 ===========

    private static List<Long> dequeue(String talker) {
        List<Long> result = new ArrayList<>();
        Iterator<PendingVoiceMsg> it = sPendingQueue.iterator();
        while (it.hasNext()) {
            PendingVoiceMsg pvm = it.next();
            if (talker == null || pvm.talker == null || talker.equals(pvm.talker)) {
                it.remove();
                result.add(pvm.msgId);
            }
        }
        return result;
    }

    private static Object loadMsgById(long msgId) {
        Object msg = sMsgMap.get(msgId);
        if (msg == null) {
            android.util.Log.e(TAG, "!!! loadMsgById NULL for " + msgId);
        }
        return msg;
    }

    // =========== 供 MessageHook 调用 ===========

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    public static void tryAutoPlayVoice(Object msg, long msgId, Object p0) {
        try {
            if (!sEnabled) return;
            if (!ModuleConfig.load(
                com.leshao.v3.ContextManager.getPrefs()
            ).autoPlayVoice) return;

            if (msgId == sLastPlayedMsgId) return;

            try {
                if ((Boolean) XposedHelpers.callMethod(msg, "G1")) return;
            } catch (Throwable ignored) {}

            try {
                if ((Integer) XposedHelpers.callMethod(msg, "M0") == 5) return;
            } catch (Throwable ignored) {}

            sLastPlayedMsgId = msgId;

            String talker = null;
            try { talker = (String) XposedHelpers.callMethod(msg, "N0"); } catch (Throwable ignored) {}
            if (talker == null) try { talker = (String) XposedHelpers.getObjectField(msg, "field_talker"); } catch (Throwable ignored) {}

            XposedBridge.log("[VAP] enqueue msgId=" + msgId + " talker=" + trunc(talker));
            LogWriter.log(TAG, "enqueue: msgId=" + msgId + " talker=" + trunc(talker));

            sMsgMap.put(msgId, msg);
            sPendingQueue.offer(new PendingVoiceMsg(msgId, talker));

            sHandler.post(() -> {
                if (sCurrentVoiceComp != null) {
                    Object cc = null;
                    try { cc = XposedHelpers.getObjectField(sCurrentVoiceComp, "d"); } catch (Throwable ignored) {}
                    String ct = null;
                    if (cc != null) try { ct = (String) XposedHelpers.callMethod(cc, "x"); } catch (Throwable ignored) {}
                    List<Long> pending = dequeue(ct);
                    if (!pending.isEmpty()) {
                        try {
                            Object player = XposedHelpers.callMethod(sCurrentVoiceComp, "n0");
                            if (player != null) {
                                for (long mid : pending) {
                                    Object m = loadMsgById(mid);
                                    if (m != null) {
                                        XposedHelpers.callMethod(player, "I", m, false);
                                        LogWriter.log(TAG, "immediate PLAY msgId=" + mid);
                                    }
                                }
                            }
                        } catch (Throwable e) {
                            LogWriter.log(TAG, "immediate play err: " + e.getMessage());
                        }
                    }
                } else {
                    LogWriter.log(TAG, "VC not ready, queued for so.y()");
                }
            });
        } catch (Throwable e) {
            LogWriter.log(TAG, "tryAutoPlay err: " + e.getMessage());
        }
    }

    public static void notifyChattingUIResume(android.app.Activity activity) {}

    /**
     * 供 TtsVoiceSender TTS 完成后触发语音播放
     */
    public static void playPendingVoice(String talker) {
        android.util.Log.e(TAG, ">>> playPendingVoice talker=" + talker);
        if (sCurrentVoiceComp == null) return;
        try {
            List<Long> pending = dequeue(talker);
            if (pending.isEmpty()) return;
            Object player = XposedHelpers.callMethod(sCurrentVoiceComp, "n0");
            if (player == null) return;
            for (long msgId : pending) {
                Object msg = loadMsgById(msgId);
                if (msg != null) {
                    XposedHelpers.callMethod(player, "I", msg, false);
                    android.util.Log.e(TAG, ">>> playPendingVoice PLAY msgId=" + msgId);
                    LogWriter.log(TAG, "playPendingVoice PLAY msgId=" + msgId);
                }
            }
        } catch (Throwable e) {
            android.util.Log.e(TAG, "playPendingVoice err: " + e.getMessage());
        }
    }

    private static String trunc(String s) {
        return s == null ? "null" : s.length() > 15 ? s.substring(0, 15) + "..." : s;
    }
}
