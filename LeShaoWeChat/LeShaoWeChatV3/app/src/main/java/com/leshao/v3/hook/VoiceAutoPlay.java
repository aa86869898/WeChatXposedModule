package com.leshao.v3.hook;

import android.app.Activity;

import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 语音消息自动播放 v34
 *
 * 流程: 消息到达 → 缓存 msgId → ChattingUI.onResume → 获取 VoiceComponent → 播放
 */
public class VoiceAutoPlay {

    private static final String TAG = "VoiceAutoPlay";

    private static boolean sEnabled = true;
    private static long sLastPlayedMsgId = -1L;

    private static ClassLoader sClassLoader;
    private static volatile Object sCurrentVoiceComp;
    private static volatile Object sCurrentChattingContext;

    private static final Queue<PendingVoiceMsg> sPendingQueue = new ConcurrentLinkedQueue<>();

    private static class PendingVoiceMsg {
        final Object msg;
        final long msgId;
        PendingVoiceMsg(Object msg, long msgId) {
            this.msg = msg;
            this.msgId = msgId;
        }
    }

    public static void hook(ClassLoader cl) {
        sClassLoader = cl;

        hookChattingUIOnResume();
    }

    // ============ 获取 ChattingContext / VoiceComponent ============

    private static void hookChattingUIOnResume() {
        try {
            Class<?> chattingUI = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUI", sClassLoader);
            XposedBridge.hookAllMethods(chattingUI, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        XposedBridge.log("[VoiceAutoPlay] onResume CALLBACK FIRED");
                        Activity act = (Activity) param.thisObject;
                        LogWriter.log(TAG, "ChattingUI.onResume");
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(() -> onChatResume(act), 500);
                    } catch (Throwable e) {
                        XposedBridge.log("[VoiceAutoPlay] onResume err: " + e.getMessage());
                        LogWriter.log(TAG, "ChattingUI.onResume err: " + e.getMessage());
                    }
                }
            });
            XposedBridge.log("[VoiceAutoPlay] ChattingUI.onResume hooked OK");
            LogWriter.log(TAG, "ChattingUI.onResume hooked OK");
        } catch (Throwable t) {
            XposedBridge.log("[VoiceAutoPlay] onResume FAIL: " + t.getMessage());
            LogWriter.log(TAG, "ChattingUI.onResume fail: " + t.getMessage());
        }
    }

    private static void onChatResume(Activity activity) {
        try {
            XposedBridge.log("[VoiceAutoPlay] onChatResume start");
            if (!refreshChattingContext(activity)) return;

            XposedBridge.log("[VoiceAutoPlay] VoiceComponent ready, pending=" + sPendingQueue.size());
            LogWriter.log(TAG, "VoiceComponent ready, pending=" + sPendingQueue.size());

            while (!sPendingQueue.isEmpty()) {
                PendingVoiceMsg pvm = sPendingQueue.poll();
                if (pvm == null) break;
                try {
                    if (playVoiceViaComponent(pvm.msg, pvm.msgId)) {
                        LogWriter.log(TAG, "played pending msgId=" + pvm.msgId);
                    }
                } catch (Throwable e) {
                    LogWriter.log(TAG, "play pending err: " + e.getMessage());
                }
            }
        } catch (Throwable e) {
            XposedBridge.log("[VoiceAutoPlay] onChatResume err: " + e.getMessage());
            LogWriter.log(TAG, "onChatResume err: " + e.getMessage());
        }
    }

    private static boolean refreshChattingContext(Activity activity) {
        try {
            Object fragment;
            try {
                fragment = XposedHelpers.getObjectField(activity, "h");
            } catch (Throwable e) {
                LogWriter.log(TAG, "ChattingUI.h not found: " + e.getMessage());
                return false;
            }
            if (fragment == null) {
                LogWriter.log(TAG, "ChattingUI.h is null");
                return false;
            }

            Object cc = findChattingContext(fragment);
            if (cc == null) {
                LogWriter.log(TAG, "ChattingContext not found in fragment fields");
                return false;
            }

            sCurrentChattingContext = cc;
            LogWriter.log(TAG, "ChattingContext: " + cc.getClass().getName());

            Object mgr = XposedHelpers.getObjectField(cc, "c");
            if (mgr == null) return false;
            Class<?> q2Cls = XposedHelpers.findClass("zc5.q2", sClassLoader);
            Object vc = XposedHelpers.callMethod(mgr, "a", q2Cls);
            if (vc != null) {
                sCurrentVoiceComp = vc;
                LogWriter.log(TAG, "VoiceComponent: " + vc.getClass().getName());
                return true;
            }
            return false;
        } catch (Throwable e) {
            LogWriter.log(TAG, "refreshChattingContext err: " + e.getMessage());
            return false;
        }
    }

    private static boolean playVoiceViaComponent(Object msg, long msgId) {
        Object vc = sCurrentVoiceComp;
        if (vc == null) return false;
        try {
            Object player = XposedHelpers.callMethod(vc, "n0");
            if (player == null) return false;
            XposedHelpers.callMethod(player, "I", msg, false);
            LogWriter.log(TAG, "play via VC: msgId=" + msgId);
            return true;
        } catch (Throwable e) {
            LogWriter.log(TAG, "playVoice err: " + e.getMessage());
            return false;
        }
    }

    private static Object findChattingContext(Object fragment) {
        for (java.lang.reflect.Field f : fragment.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object val = f.get(fragment);
                if (val == null) continue;
                try {
                    Object mgrField = XposedHelpers.getObjectField(val, "c");
                    if (mgrField == null) continue;
                    XposedHelpers.callMethod(mgrField, "a", Class.class);
                    LogWriter.log(TAG, "cc in fragment." + f.getName() + ": " + val.getClass().getName());
                    return val;
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ============ 供 MessageHook 调用 ============

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
                boolean isSend = (Boolean) XposedHelpers.callMethod(msg, "G1");
                if (isSend) return;
            } catch (Throwable ignored) {}

            try {
                if ((Integer) XposedHelpers.callMethod(msg, "M0") == 5) return;
            } catch (Throwable ignored) {}

            sLastPlayedMsgId = msgId;
            LogWriter.log(TAG, "voice msg received, enqueue: msgId=" + msgId);

            // 加入待播放队列
            sPendingQueue.offer(new PendingVoiceMsg(msg, msgId));

            // 如果 VoiceComponent 已可用，立即尝试播放
            if (sCurrentVoiceComp != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    try {
                        if (playVoiceViaComponent(msg, msgId)) {
                            LogWriter.log(TAG, "immediate play: msgId=" + msgId);
                        }
                    } catch (Throwable ignored) {}
                });
            } else {
                LogWriter.log(TAG, "VoiceComponent not ready, will play on ChattingUI.onResume");
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "tryAutoPlayVoice outer err: " + e.getMessage());
        }
    }

    public static void notifyChattingUIResume(Activity activity) {
        try {
            onChatResume(activity);
        } catch (Throwable ignored) {}
    }
}
