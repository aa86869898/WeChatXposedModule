package com.leshao.v3.hook;

import android.app.Activity;

import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 语音消息自动播放 v33
 *
 * 通过 ChattingUI.h(ChattingUIFragment) → fd5.d(ChattingContext) → c.a(zc5.q2) → so
 * 获取 VoiceComponent，用微信内部播放器自动播放。
 *
 * 已知: dq.c() 只处理失败重发，不是语音播放回调; dq.e0() 是用户点击播放入口。
 */
public class VoiceAutoPlay {

    private static final String TAG = "VoiceAutoPlay";

    private static boolean sEnabled = true;
    private static long sLastPlayedMsgId = -1L;

    private static ClassLoader sClassLoader;
    private static volatile Object sCurrentVoiceComp;
    private static volatile Object sCurrentChattingContext;
    private static volatile Activity sChatAct;

    public static void hook(ClassLoader cl) {
        sClassLoader = cl;

        hookChattingUIOnResume();
        registerActivityCallback();
    }

    // ============ 获取 ChattingContext / VoiceComponent ============

    private static void hookChattingUIOnResume() {
        try {
            Class<?> chattingUI = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUI", sClassLoader);
            XposedBridge.hookAllMethods(chattingUI, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity act = (Activity) param.thisObject;
                        sChatAct = act;
                        LogWriter.log(TAG, "ChattingUI.onResume");
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(() -> refreshChattingContext(act), 500);
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "ChattingUI.onResume err: " + e.getMessage());
                    }
                }
            });
            LogWriter.log(TAG, "ChattingUI.onResume hooked OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "ChattingUI.onResume fail: " + t.getMessage());
        }
    }

    private static void registerActivityCallback() {
        try {
            android.app.Application app = (android.app.Application)
                    com.leshao.v3.ContextManager.getAppContext();
            app.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity a, android.os.Bundle b) {}
                @Override public void onActivityStarted(Activity a) {}
                @Override
                public void onActivityResumed(Activity a) {
                    if (a.getClass().getName().contains("ChattingUI")) {
                        sChatAct = a;
                    }
                }
                @Override public void onActivityPaused(Activity a) {}
                @Override public void onActivityStopped(Activity a) {
                    if (a.getClass().getName().contains("ChattingUI")) {
                        sCurrentVoiceComp = null;
                        sCurrentChattingContext = null;
                        LogWriter.log(TAG, "ChattingUI paused, cleared context");
                    }
                }
                @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) {}
                @Override public void onActivityDestroyed(Activity a) {}
            });
            LogWriter.log(TAG, "ActivityLifecycleCallbacks registered OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "ActivityLifecycleCallbacks fail: " + t.getMessage());
        }
    }

    private static void refreshChattingContext(Activity activity) {
        try {
            // ChattingUI.h = ChattingUIFragment
            Object fragment;
            try {
                fragment = XposedHelpers.getObjectField(activity, "h");
            } catch (Throwable e) {
                LogWriter.log(TAG, "ChattingUI.h not found: " + e.getMessage());
                return;
            }
            if (fragment == null) {
                LogWriter.log(TAG, "ChattingUI.h is null");
                return;
            }

            Object cc = findChattingContext(fragment);
            if (cc == null) {
                LogWriter.log(TAG, "ChattingContext not found in fragment fields");
                return;
            }

            sCurrentChattingContext = cc;
            LogWriter.log(TAG, "ChattingContext: " + cc.getClass().getName());

            Object mgr = XposedHelpers.getObjectField(cc, "c");
            if (mgr == null) return;
            Class<?> q2Cls = XposedHelpers.findClass("zc5.q2", sClassLoader);
            Object vc = XposedHelpers.callMethod(mgr, "a", q2Cls);
            if (vc != null) {
                sCurrentVoiceComp = vc;
                LogWriter.log(TAG, "VoiceComponent: " + vc.getClass().getName());
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "refreshChattingContext err: " + e.getMessage());
        }
    }

    private static Object findChattingContext(Object fragment) {
        for (java.lang.reflect.Field f : fragment.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object val = f.get(fragment);
                if (val == null) continue;
                try {
                    Object mgr = XposedHelpers.getObjectField(val, "c");
                    if (mgr == null) continue;
                    XposedHelpers.callMethod(mgr, "a", Class.class);
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

            final Object finalMsg = msg;
            final long finalMsgId = msgId;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    // 优先使用缓存的 VoiceComponent
                    Object vc = sCurrentVoiceComp;
                    if (vc != null) {
                        Object player = XposedHelpers.callMethod(vc, "n0");
                        if (player != null && !(Boolean)XposedHelpers.callMethod(player, "o")) {
                            XposedHelpers.callMethod(player, "I", finalMsg, false);
                            LogWriter.log(TAG, "auto-play via cached VC: msgId=" + finalMsgId);
                            return;
                        }
                    }

                    // 如果 ChattingUI 还在，尝试重新获取
                    if (sChatAct != null) {
                        refreshChattingContext(sChatAct);
                        vc = sCurrentVoiceComp;
                        if (vc != null) {
                            Object player = XposedHelpers.callMethod(vc, "n0");
                            if (player != null && !(Boolean)XposedHelpers.callMethod(player, "o")) {
                                XposedHelpers.callMethod(player, "I", finalMsg, false);
                                LogWriter.log(TAG, "auto-play via refreshed VC: msgId=" + finalMsgId);
                                return;
                            }
                        }
                    }

                    LogWriter.log(TAG, "no VoiceComponent available, skip");
                } catch (Throwable e) {
                    LogWriter.log(TAG, "tryAutoPlayVoice err: " + e.getMessage());
                }
            });

        } catch (Throwable e) {
            LogWriter.log(TAG, "tryAutoPlayVoice outer err: " + e.getMessage());
        }
    }

    public static void notifyChattingUIResume(Activity activity) {
        try {
            sChatAct = activity;
            refreshChattingContext(activity);
        } catch (Throwable ignored) {}
    }
}
