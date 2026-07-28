package com.leshao.v3.hook;

import android.os.Handler;
import android.os.Looper;

import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;

import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 语音消息自动播放 v40
 * - hook so.y() (VoiceComponent.resetAutoPlay) 替代 Activity.onResume
 * - so 继承 a，a.d = fd5.d (ChattingContext)
 * - 消息到达入队，so.y() 触发时出队播放
 */
public class VoiceAutoPlay {

    private static final String TAG = "VoiceAutoPlay";

    private static boolean sEnabled = true;
    private static long sLastPlayedMsgId = -1L;

    private static ClassLoader sClassLoader;
    private static volatile Object sCurrentVoiceComp;
    private static volatile Object sCurrentChattingContext;

    private static final Queue<PendingVoiceMsg> sPendingQueue = new ConcurrentLinkedQueue<>();
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private static class PendingVoiceMsg {
        final Object msg;
        final long msgId;
        final String talker;

        PendingVoiceMsg(Object msg, long msgId, String talker) {
            this.msg = msg;
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
                        Object so = param.thisObject;
                        sCurrentVoiceComp = so;
                        XposedBridge.log("[VAP] so.y() fired, VC=" + so.getClass().getName());

                        Object cc = XposedHelpers.getObjectField(so, "d");
                        if (cc != null) {
                            sCurrentChattingContext = cc;
                            XposedBridge.log("[VAP] cc from so.d: " + cc.getClass().getName());

                            String talker = null;
                            try { talker = (String) XposedHelpers.getObjectField(cc, "k"); }
                            catch (Throwable ignored) {}
                            if (talker == null) try { talker = (String) XposedHelpers.callMethod(cc, "x"); }
                            catch (Throwable ignored) {}

                            XposedBridge.log("[VAP] talker=" + trunc(talker) + " pending=" + sPendingQueue.size());
                            LogWriter.log(TAG, "so.y: talker=" + trunc(talker) + " pending=" + sPendingQueue.size());

                            if (!sPendingQueue.isEmpty()) {
                                playPendingForTalker(talker);
                            }
                        } else {
                            XposedBridge.log("[VAP] so.d is null");
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

    // =========== 播放逻辑 ===========

    private static void playPendingForTalker(String currentTalker) {
        try {
            Iterator<PendingVoiceMsg> it = sPendingQueue.iterator();
            while (it.hasNext()) {
                PendingVoiceMsg pvm = it.next();
                if (currentTalker != null && pvm.talker != null &&
                    !currentTalker.equals(pvm.talker)) {
                    continue;
                }
                it.remove();
                try {
                    if (playVoiceViaComponent(pvm.msg, pvm.msgId)) {
                        LogWriter.log(TAG, "played msgId=" + pvm.msgId);
                    } else {
                        LogWriter.log(TAG, "play failed msgId=" + pvm.msgId);
                    }
                } catch (Throwable e) {
                    LogWriter.log(TAG, "play err: " + e.getMessage());
                }
            }
        } catch (Throwable e) {
            XposedBridge.log("[VAP] playPending err: " + e.getMessage());
        }
    }

    private static boolean playVoiceViaComponent(Object msg, long msgId) {
        Object vc = sCurrentVoiceComp;
        if (vc == null) return false;
        try {
            Object player = XposedHelpers.callMethod(vc, "n0");
            if (player == null) return false;
            XposedHelpers.callMethod(player, "I", msg, false);
            LogWriter.log(TAG, "play: msgId=" + msgId);
            return true;
        } catch (Throwable e) {
            LogWriter.log(TAG, "playVoice err: " + e.getMessage());
            return false;
        }
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

            sPendingQueue.offer(new PendingVoiceMsg(msg, msgId, talker));

            sHandler.post(() -> {
                if (sCurrentVoiceComp != null) {
                    String currentTalker = null;
                    if (sCurrentChattingContext != null) {
                        try { currentTalker = (String) XposedHelpers.getObjectField(sCurrentChattingContext, "k"); }
                        catch (Throwable ignored) {}
                    }
                    playPendingForTalker(currentTalker);
                } else {
                    LogWriter.log(TAG, "VC not ready, queued for so.y()");
                }
            });
        } catch (Throwable e) {
            LogWriter.log(TAG, "tryAutoPlay err: " + e.getMessage());
        }
    }

    public static void notifyChattingUIResume(android.app.Activity activity) {
        // 不再需要，so.y() 替代了
    }

    private static String trunc(String s) {
        return s == null ? "null" : s.length() > 15 ? s.substring(0, 15) + "..." : s;
    }
}
