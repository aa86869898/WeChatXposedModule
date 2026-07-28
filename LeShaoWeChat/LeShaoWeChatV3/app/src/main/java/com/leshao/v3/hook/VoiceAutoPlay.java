package com.leshao.v3.hook;

import android.app.Activity;
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
 * 语音消息自动播放 v35
 * 消息到达 → 缓存(msg+talker) → ChattingUI.onResume → 获取VC → 按talker过滤播放
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
        hookChattingUIOnResume();
    }

    // =========== ChattingUI.onResume hook ===========

    private static void hookChattingUIOnResume() {
        try {
            Class<?> chattingUI = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUI", sClassLoader);
            XposedBridge.hookAllMethods(chattingUI, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        XposedBridge.log("[VAP] onResume FIRED");
                        Activity act = (Activity) param.thisObject;
                        LogWriter.log(TAG, "onResume");
                        sHandler.postDelayed(() -> onChatResume(act, 0), 300);
                    } catch (Throwable e) {
                        XposedBridge.log("[VAP] onResume err: " + e.getMessage());
                        LogWriter.log(TAG, "onResume err: " + e.getMessage());
                    }
                }
            });
            XposedBridge.log("[VAP] onResume hooked OK");
            LogWriter.log(TAG, "onResume hooked OK");
        } catch (Throwable t) {
            XposedBridge.log("[VAP] onResume FAIL: " + t.getMessage());
            LogWriter.log(TAG, "onResume fail: " + t.getMessage());
        }
    }

    // =========== onResume 处理 ===========

    private static void onChatResume(Activity activity, int retry) {
        XposedBridge.log("[VAP] onChatResume retry=" + retry + " pending=" + sPendingQueue.size());
        try {
            if (!refreshChattingContext(activity)) return;
            if (sCurrentVoiceComp == null && retry < 3) {
                LogWriter.log(TAG, "VC null, retry " + (retry + 1) + "/3");
                sHandler.postDelayed(() -> onChatResume(activity, retry + 1), 500);
                return;
            }
            if (sCurrentVoiceComp == null) {
                XposedBridge.log("[VAP] VC still null after retries");
                LogWriter.log(TAG, "VC still null after retries");
                return;
            }

            String currentTalker = null;
            try {
                currentTalker = (String) XposedHelpers.getObjectField(sCurrentChattingContext, "k");
            } catch (Throwable e) {
                LogWriter.log(TAG, "cc.k fail: " + e.getMessage());
            }
            XposedBridge.log("[VAP] currentTalker=" + currentTalker + " pending=" + sPendingQueue.size());
            LogWriter.log(TAG, "play pending for talker=" + currentTalker + " queue=" + sPendingQueue.size());

            Iterator<PendingVoiceMsg> it = sPendingQueue.iterator();
            while (it.hasNext()) {
                PendingVoiceMsg pvm = it.next();
                if (currentTalker != null && !currentTalker.equals(pvm.talker)) {
                    LogWriter.log(TAG, "skip talker=" + trunc(pvm.talker, 15) + " for " + trunc(currentTalker, 15));
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
            XposedBridge.log("[VAP] onChatResume err: " + e.getMessage());
            LogWriter.log(TAG, "onChatResume err: " + e.getMessage());
        }
    }

    private static boolean refreshChattingContext(Activity activity) {
        try {
            Object fragment;
            try {
                fragment = XposedHelpers.getObjectField(activity, "h");
            } catch (Throwable e) {
                LogWriter.log(TAG, "h not found: " + e.getMessage());
                return false;
            }
            if (fragment == null) {
                LogWriter.log(TAG, "h is null");
                return false;
            }

            Object cc = findChattingContext(fragment);
            if (cc == null) {
                LogWriter.log(TAG, "cc not found in fragment fields");
                return false;
            }

            sCurrentChattingContext = cc;
            XposedBridge.log("[VAP] cc: " + cc.getClass().getName());

            Object mgr = XposedHelpers.getObjectField(cc, "c");
            if (mgr == null) {
                XposedBridge.log("[VAP] cc.c is null");
                return false;
            }
            Class<?> q2Cls = XposedHelpers.findClass("zc5.q2", sClassLoader);
            Object vc = XposedHelpers.callMethod(mgr, "a", q2Cls);
            if (vc != null) {
                sCurrentVoiceComp = vc;
                XposedBridge.log("[VAP] VC: " + vc.getClass().getName());
                LogWriter.log(TAG, "VC: " + vc.getClass().getName());
                return true;
            } else {
                XposedBridge.log("[VAP] VC null from mgr.a(q2)");
            }
            return false;
        } catch (Throwable e) {
            LogWriter.log(TAG, "refreshCC err: " + e.getMessage());
            return false;
        }
    }

    private static boolean playVoiceViaComponent(Object msg, long msgId) {
        Object vc = sCurrentVoiceComp;
        if (vc == null) return false;
        try {
            Object player = XposedHelpers.callMethod(vc, "n0");
            if (player == null) {
                LogWriter.log(TAG, "player null");
                return false;
            }
            XposedHelpers.callMethod(player, "I", msg, false);
            LogWriter.log(TAG, "play: msgId=" + msgId);
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

            XposedBridge.log("[VAP] enqueue msgId=" + msgId + " talker=" + trunc(talker, 15));
            LogWriter.log(TAG, "enqueue: msgId=" + msgId + " talker=" + trunc(talker, 15));

            sPendingQueue.offer(new PendingVoiceMsg(msg, msgId, talker));

            sHandler.post(() -> {
                if (sCurrentVoiceComp != null) {
                    Iterator<PendingVoiceMsg> it = sPendingQueue.iterator();
                    while (it.hasNext()) {
                        PendingVoiceMsg pvm = it.next();
                        if (pvm.msgId == msgId) {
                            it.remove();
                            try {
                                if (playVoiceViaComponent(pvm.msg, pvm.msgId)) {
                                    LogWriter.log(TAG, "immediate play: msgId=" + msgId);
                                }
                            } catch (Throwable ignored) {}
                            break;
                        }
                    }
                } else {
                    LogWriter.log(TAG, "VC not ready, queued for onResume");
                }
            });
        } catch (Throwable e) {
            LogWriter.log(TAG, "tryAutoPlay err: " + e.getMessage());
        }
    }

    public static void notifyChattingUIResume(Activity activity) {
        try {
            onChatResume(activity, 0);
        } catch (Throwable ignored) {}
    }

    private static String trunc(String s, int m) {
        return s == null ? "null" : s.length() > m ? s.substring(0, m) + "..." : s;
    }
}
