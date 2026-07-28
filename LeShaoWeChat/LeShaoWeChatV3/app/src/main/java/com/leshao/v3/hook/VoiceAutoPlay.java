package com.leshao.v3.hook;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.service.TTSBroadcaster;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 语音消息自动播放 — 基于 WeChatVoiceAutoPlay 参考实现
 *
 * 核心思路: Hook dq.c(View, ChattingContext, Message)
 * 这是微信语音气泡渲染时的回调, 每次语音消息出现在屏幕上时触发。
 * 在回调中通过 ChattingContext 获取 VoiceComponent, 再调用播放器的 I() 方法播放。
 */
public class VoiceAutoPlay {

    private static final String TAG = "VoiceAutoPlay";
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private static boolean sEnabled = true;
    private static int sPlayDelay = 800;
    private static long sLastPlayedMsgId = 0L;

    private static ClassLoader sClassLoader;
    private static Class<?> sVoiceComponentClass; // so

    public static void hook(ClassLoader cl) {
        sClassLoader = cl;

        hookDqClass(cl);
        hookChattingUIResume(cl);
        findVoiceComponentClass(cl);
    }

    /**
     * 主 hook: dq.c(View, ChattingContext, e9)
     * 语音气泡每次渲染时调用，是最可靠且最及时的触发点
     */
    private static void hookDqClass(ClassLoader cl) {
        try {
            Class<?> dq = XposedHelpers.findClass("com.tencent.mm.ui.chatting.viewitems.dq", cl);
            XposedBridge.hookAllMethods(dq, "c", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    onVoiceBubbleRender(param);
                }
            });
            LogWriter.log(TAG, "dq.c() hooked OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "dq.c() hook fail: " + t.getMessage());
        }
    }

    /**
     * 备用入口: ChattingUI.onResume() — 进入聊天时尝试播放最新语音
     */
    private static void hookChattingUIResume(ClassLoader cl) {
        try {
            Class<?> chattingUI = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUI", cl);
            XposedBridge.hookAllMethods(chattingUI, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        LogWriter.log(TAG, "ChattingUI.onResume triggered");
                        if (!sEnabled) return;
                        boolean activated = ModuleConfig.load(
                            com.leshao.v3.ContextManager.getPrefs()
                        ).autoPlayVoice;
                        if (!activated) return;
                        sMainHandler.postDelayed(() -> {
                            try {
                                findAndClickLatestVoice(activity);
                            } catch (Throwable e) {
                                LogWriter.log(TAG, "findAndClickLatestVoice error: " + e.getMessage());
                            }
                        }, sPlayDelay);
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "ChattingUI.onResume hook error: " + e.getMessage());
                    }
                }
            });
            LogWriter.log(TAG, "ChattingUI.onResume hooked OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "ChattingUI.onResume hook fail: " + t.getMessage());
        }
    }

    private static void findVoiceComponentClass(ClassLoader cl) {
        try {
            sVoiceComponentClass = XposedHelpers.findClass("com.tencent.mm.ui.chatting.component.so", cl);
            LogWriter.log(TAG, "VoiceComponent so loaded OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "VoiceComponent so not found: " + t.getMessage());
        }
    }

    /**
     * dq.c() 回调 — 语音气泡渲染时触发
     *
     * param.args[0]: View
     * param.args[1]: fd5.d (ChattingContext)
     * param.args[2]: e9 (Message)
     */
    private static void onVoiceBubbleRender(XC_MethodHook.MethodHookParam param) {
        try {
            if (!sEnabled) return;
            boolean activated = ModuleConfig.load(
                com.leshao.v3.ContextManager.getPrefs()
            ).autoPlayVoice;
            if (!activated) {
                LogWriter.log(TAG, "autoPlayVoice disabled in config");
                return;
            }

            if (param.args.length < 3) return;

            Object msg = param.args[2];
            if (msg == null) return;

            // 过滤1: 只处理语音消息 (type == 34)
            int msgType = (Integer) XposedHelpers.callMethod(msg, "getType");
            if (msgType != 34) return;

            // 过滤2: 跳过自己发送的消息
            try {
                int isSend = XposedHelpers.getIntField(msg, "field_isSend");
                if (isSend == 1) return;
            } catch (Throwable ignored) {}

            // 过滤3: 去重
            long msgId = (Long) XposedHelpers.callMethod(msg, "getMsgId");
            if (msgId == sLastPlayedMsgId) return;

            // 过滤4: 跳过正在发送中的消息 (M0() == 5)
            try {
                if ((Integer) XposedHelpers.callMethod(msg, "M0") == 5) return;
            } catch (Throwable ignored) {}

            // 获取 ChattingContext
            Object chattingContext = param.args[1];
            if (chattingContext == null) return;

            // 获取 VoiceComponent 管理器 then 获取 VoiceComponent (so)
            Object voiceComp = getVoiceComponent(chattingContext);
            if (voiceComp == null) {
                LogWriter.log(TAG, "VoiceComponent not found via context manager");
                return;
            }

            // 获取 SceneVoicePlayer (v0)
            Object player = XposedHelpers.callMethod(voiceComp, "n0");
            if (player == null) {
                LogWriter.log(TAG, "SceneVoicePlayer not found");
                return;
            }

            // 如果正在播放，不打断
            try {
                boolean isPlaying = (Boolean) XposedHelpers.callMethod(player, "o");
                if (isPlaying) return;
            } catch (Throwable ignored) {}

            // 等待 TTS 播完
            if (TTSBroadcaster.isSpeaking()) {
                final Object finalPlayer = player;
                final long finalMsgId = msgId;
                sMainHandler.postDelayed(() -> {
                    if (!TTSBroadcaster.isSpeaking()) {
                        playVoice(finalPlayer, msg, finalMsgId);
                    }
                }, 300);
                sLastPlayedMsgId = msgId;
                return;
            }

            playVoice(player, msg, msgId);

        } catch (Throwable e) {
            LogWriter.log(TAG, "onVoiceBubbleRender error: " + e.getMessage());
        }
    }

    /**
     * 通过 ChattingContext 获取 VoiceComponent
     *
     * ChattingContext.c 是一个管理器, 调用其 a(zc5.q2) 方法返回 VoiceComponent
     */
    private static Object getVoiceComponent(Object chattingContext) {
        try {
            Object manager = XposedHelpers.getObjectField(chattingContext, "c");
            if (manager == null) return null;

            Class<?> q2Class = XposedHelpers.findClass("zc5.q2", sClassLoader);
            if (q2Class == null) {
                LogWriter.log(TAG, "zc5.q2 class not found in getVoiceComponent");
                return null;
            }

            Object comp = XposedHelpers.callMethod(manager, "a", q2Class);
            LogWriter.log(TAG, "VoiceComponent obtained via manager.a(zc5.q2)");
            return comp;
        } catch (Throwable e) {
            LogWriter.log(TAG, "getVoiceComponent error: " + e.getMessage());
            return null;
        }
    }

    /**
     * 调用 player.I(msg, false) 播放语音
     *
     * v0.I(e9, boolean) — 第一个参数是消息, 第二个是是否从开头播放(false=正常)
     */
    private static void playVoice(Object player, Object msg, long msgId) {
        try {
            XposedHelpers.callMethod(player, "I", msg, false);
            sLastPlayedMsgId = msgId;
            LogWriter.log(TAG, "auto-play voice msgId=" + msgId);
        } catch (Throwable e) {
            LogWriter.log(TAG, "playVoice error: " + e.getMessage());
        }
    }

    // ============ 备用方案: View 扫描 click ============

    private static void findAndClickLatestVoice(Activity activity) {
        try {
            View root = activity.getWindow().getDecorView();
            java.util.List<View> voices = new java.util.ArrayList<>();
            findVoiceViews(root, voices);

            if (voices.isEmpty()) {
                LogWriter.log(TAG, "findAndClickLatestVoice: no voice views found");
                return;
            }

            View latest = voices.get(voices.size() - 1);
            LogWriter.log(TAG, "findAndClickLatestVoice: found " + voices.size() + " voice views, clicking latest");
            latest.performClick();
        } catch (Throwable e) {
            LogWriter.log(TAG, "findAndClickLatestVoice error: " + e.getMessage());
        }
    }

    private static void findVoiceViews(View view, java.util.List<View> out) {
        if (view == null) return;
        String clsName = view.getClass().getName();
        if (clsName.contains("Voice") || clsName.contains("voice")
            || clsName.contains("Audio") || clsName.contains("audio")) {
            try {
                View clickable = view;
                if (!view.isClickable() && view instanceof android.view.ViewGroup) {
                    android.view.ViewGroup vg = (android.view.ViewGroup) view;
                    for (int i = 0; i < vg.getChildCount(); i++) {
                        View child = vg.getChildAt(i);
                        if (child.isClickable()) { clickable = child; break; }
                    }
                }
                out.add(clickable);
            } catch (Throwable ignored) {}
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                findVoiceViews(vg.getChildAt(i), out);
            }
        }
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }
}
