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
 * 语音消息自动播放 — ChattingUI + MessageHook 双 Hook 方案
 *
 * 方案:
 *   1. Hook ChattingUI.onResume() → 获取 ChattingContext → VoiceComponent
 *   2. Hook e01.x9.n(e9,p0) → 检测 type==34 → 自动播放
 *   3. Hook dq.c() 扫描 → 如果在当前版本存在则作为更早的触发点
 *
 * 参考: WeChatVoiceAutoPlay (dq.c 方案) + MsgInfo_ANALYSIS (e9 分析)
 */
public class VoiceAutoPlay {

    private static final String TAG = "VoiceAutoPlay";
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private static boolean sEnabled = true;
    private static long sLastPlayedMsgId = -1L;

    private static ClassLoader sClassLoader;
    private static volatile Object sCurrentVoiceComp;
    private static volatile Object sCurrentChattingContext;

    public static void hook(ClassLoader cl) {
        sClassLoader = cl;

        hookDqClass(cl);
        hookChattingUIResume(cl);
        hookMessageListener(cl);
        findVoiceComponentClass(cl);
    }

    // ============ 层1: dq.c() 扫描 (语音气泡渲染 — 最理想的触发点) ============

    private static void hookDqClass(ClassLoader cl) {
        String[] shortNames = new String[26 * 26];
        int idx = 0;
        for (char c1 = 'a'; c1 <= 'z'; c1++) {
            for (char c2 = 'a'; c2 <= 'z'; c2++) {
                shortNames[idx++] = String.valueOf(c1) + String.valueOf(c2);
            }
        }
        String pkg = "com.tencent.mm.ui.chatting.viewitems.";
        String[] methodNames = {"c", "b", "d", "e", "a"};

        for (String name : shortNames) {
            try {
                Class<?> cls = cl.loadClass(pkg + name);
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    boolean rightName = false;
                    for (String mn : methodNames) {
                        if (m.getName().equals(mn)) { rightName = true; break; }
                    }
                    if (!rightName) continue;
                    if (m.getParameterTypes().length < 3) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    String thirdName = pts[2].getName();
                    if (!thirdName.contains("storage")) continue;

                    LogWriter.log(TAG, "found voice bubble binder: " + pkg + name
                        + "." + m.getName() + "(View," + pts[1].getSimpleName() + "," + pts[2].getSimpleName() + ")");

                    // 直接 XposedBridge.hookMethod，不依赖 findAndHookMethod/hookAllMethods
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                onVoiceBubbleRender(param);
                            }
                        });
                        LogWriter.log(TAG, "voice bubble hook OK on " + pkg + name + "." + m.getName());
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "voice bubble hook install fail: " + t.getMessage());
                    }
                    return;
                }
            } catch (Throwable ignored) {}
        }

        LogWriter.log(TAG, "no voice bubble binder found by scan, using ChattingUI+MessageHook fallback");
    }

    // ============ 层2: ChattingUI.onResume() — 获取 VoiceComponent ============

    private static void hookChattingUIResume(ClassLoader cl) {
        try {
            Class<?> chattingUI = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUI", cl);
            XposedBridge.hookAllMethods(chattingUI, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        LogWriter.log(TAG, "ChattingUI.onResume, refreshing context");
                        sMainHandler.postDelayed(() -> refreshChattingContext(activity), 500);
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "ChattingUI.onResume error: " + e.getMessage());
                    }
                }
            });
            LogWriter.log(TAG, "ChattingUI.onResume hooked OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "ChattingUI.onResume fail: " + t.getMessage());
        }
    }

    private static void refreshChattingContext(Activity activity) {
        try {
            // ChattingUI 有一个成员存储 ChattingContext
            // 尝试多种方式获取: chattingContext, mChattingContext, 或通过 getChattingContext()
            Object cc = null;

            // 方式1: 读字段 "d" (ChattingUI 常见的 ChattingContext 字段名)
            try { cc = XposedHelpers.getObjectField(activity, "d"); } catch (Throwable ignored) {}

            // 方式2: 读字段 "c"
            if (cc == null) try { cc = XposedHelpers.getObjectField(activity, "c"); } catch (Throwable ignored) {}

            // 方式3: 读字段 "B" 或 "C"
            if (cc == null) try { cc = XposedHelpers.getObjectField(activity, "B"); } catch (Throwable ignored) {}
            if (cc == null) try { cc = XposedHelpers.getObjectField(activity, "C"); } catch (Throwable ignored) {}

            // 方式4: 调用 getChattingContext() 方法
            if (cc == null) try { cc = XposedHelpers.callMethod(activity, "getChattingContext"); } catch (Throwable ignored) {}

            if (cc == null) {
                LogWriter.log(TAG, "refreshChattingContext: all methods failed");
                return;
            }

            sCurrentChattingContext = cc;
            LogWriter.log(TAG, "ChattingContext obtained: " + cc.getClass().getName());

            // 获取 VoiceComponent
            Object voiceComp = getVoiceComponent(cc);
            if (voiceComp != null) {
                sCurrentVoiceComp = voiceComp;
                LogWriter.log(TAG, "VoiceComponent obtained and stored");
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "refreshChattingContext error: " + e.getMessage());
        }
    }

    // ============ 层3: MessageHook — 检测语音消息 ============

    private static void hookMessageListener(ClassLoader cl) {
        try {
            Class<?> e9Cls = cl.loadClass("com.tencent.mm.storage.e9");

            Class<?> x9Cls;
            try {
                x9Cls = XposedHelpers.findClass("e01.x9", cl);
            } catch (Throwable t) {
                x9Cls = XposedHelpers.findClass("com.tencent.mm.model.x9", cl);
            }

            // 用 XposedBridge.hookMethod 直接 hook 精确 Method 对象
            for (java.lang.reflect.Method m : x9Cls.getDeclaredMethods()) {
                if (!m.getName().equals("n") || m.getParameterCount() != 2) continue;
                if (m.getParameterTypes()[0] != e9Cls) continue;

                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        onMessageReceived(param.args[0]);
                    }
                });
                LogWriter.log(TAG, "VoiceAutoPlay hooked e01.x9.n(e9,p0) via hookMethod OK");
                return;
            }

            // 备选: C(e9)
            for (java.lang.reflect.Method m : x9Cls.getDeclaredMethods()) {
                if (!m.getName().equals("C") || m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != e9Cls) continue;

                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        onMessageReceived(param.args[0]);
                    }
                });
                LogWriter.log(TAG, "VoiceAutoPlay hooked e01.x9.C(e9) via hookMethod OK");
                return;
            }

            LogWriter.log(TAG, "VoiceAutoPlay message hook: no matching method found");
        } catch (Throwable t) {
            LogWriter.log(TAG, "VoiceAutoPlay message hook fail: " + t.getMessage());
        }
    }

    private static void onMessageReceived(Object msg) {
        try {
            int type = (Integer) XposedHelpers.callMethod(msg, "getType");
            if (type != 34) return; // 只处理语音消息

            if (!sEnabled) return;
            boolean activated = ModuleConfig.load(
                com.leshao.v3.ContextManager.getPrefs()
            ).autoPlayVoice;
            if (!activated) return;

            long msgId = (Long) XposedHelpers.callMethod(msg, "H0");
            if (msgId == sLastPlayedMsgId) return;

            // 跳过自己发的 (G1() = isSend, 1=自己发的)
            try {
                boolean isSend = (Boolean) XposedHelpers.callMethod(msg, "G1");
                if (isSend) return;
            } catch (Throwable ignored) {}

            // 跳过正在发送中的
            try {
                if ((Integer) XposedHelpers.callMethod(msg, "M0") == 5) return;
            } catch (Throwable ignored) {}

            LogWriter.log(TAG, "voice msg detected: msgId=" + msgId + " talker="
                + XposedHelpers.callMethod(msg, "N0"));

            // 等待 TTS 结束再播放
            if (TTSBroadcaster.isSpeaking()) {
                LogWriter.log(TAG, "TTS speaking, defer voice play for msgId=" + msgId);
                sLastPlayedMsgId = msgId;
                final long fMsgId = msgId;
                sMainHandler.postDelayed(() -> {
                    if (!TTSBroadcaster.isSpeaking()) {
                        tryPlayVoice(msg, fMsgId);
                    }
                }, 500);
                return;
            }

            tryPlayVoice(msg, msgId);

        } catch (Throwable e) {
            LogWriter.log(TAG, "onMessageReceived error: " + e.getMessage());
        }
    }

    private static void tryPlayVoice(Object msg, long msgId) {
        try {
            Object voiceComp = sCurrentVoiceComp;
            if (voiceComp == null) {
                LogWriter.log(TAG, "no VoiceComponent stored, skip msgId=" + msgId);
                return;
            }

            Object player = XposedHelpers.callMethod(voiceComp, "n0");
            if (player == null) {
                LogWriter.log(TAG, "n0() returned null player for msgId=" + msgId);
                return;
            }

            // 不打断当前播放
            try {
                boolean isPlaying = (Boolean) XposedHelpers.callMethod(player, "o");
                if (isPlaying) {
                    LogWriter.log(TAG, "player already playing, skip msgId=" + msgId);
                    return;
                }
            } catch (Throwable ignored) {}

            // 调用 I(msg, false) 播放
            XposedHelpers.callMethod(player, "I", msg, false);
            sLastPlayedMsgId = msgId;
            LogWriter.log(TAG, "auto-play voice OK! msgId=" + msgId);

        } catch (Throwable e) {
            LogWriter.log(TAG, "tryPlayVoice error: " + e.getMessage());
        }
    }

    // ============ dq.c() 回调 (如果扫描到的话) ============

    private static void onVoiceBubbleRender(XC_MethodHook.MethodHookParam param) {
        try {
            if (!sEnabled) return;
            boolean activated = ModuleConfig.load(
                com.leshao.v3.ContextManager.getPrefs()
            ).autoPlayVoice;
            if (!activated) return;

            if (param.args.length < 3) return;
            Object msg = param.args[2];
            if (msg == null) return;

            int msgType = (Integer) XposedHelpers.callMethod(msg, "getType");
            if (msgType != 34) return;

            try {
                int isSend = XposedHelpers.getIntField(msg, "field_isSend");
                if (isSend == 1) return;
            } catch (Throwable ignored) {}

            long msgId = (Long) XposedHelpers.callMethod(msg, "H0");
            if (msgId == sLastPlayedMsgId) return;

            try {
                if ((Integer) XposedHelpers.callMethod(msg, "M0") == 5) return;
            } catch (Throwable ignored) {}

            Object chattingContext = param.args[1];
            if (chattingContext != null) {
                sCurrentChattingContext = chattingContext;
                Object voiceComp = getVoiceComponent(chattingContext);
                if (voiceComp != null) sCurrentVoiceComp = voiceComp;
            }

            Object voiceComp = sCurrentVoiceComp;
            if (voiceComp == null) return;

            Object player = XposedHelpers.callMethod(voiceComp, "n0");
            if (player == null) return;

            try {
                if ((Boolean) XposedHelpers.callMethod(player, "o")) return;
            } catch (Throwable ignored) {}

            if (TTSBroadcaster.isSpeaking()) {
                sLastPlayedMsgId = msgId;
                return;
            }

            XposedHelpers.callMethod(player, "I", msg, false);
            sLastPlayedMsgId = msgId;
            LogWriter.log(TAG, "auto-play via dq.c: msgId=" + msgId);

        } catch (Throwable e) {
            LogWriter.log(TAG, "onVoiceBubbleRender error: " + e.getMessage());
        }
    }

    // ============ 工具方法 ============

    private static Object getVoiceComponent(Object chattingContext) {
        try {
            Object manager = XposedHelpers.getObjectField(chattingContext, "c");
            if (manager == null) return null;

            Class<?> q2Class = XposedHelpers.findClass("zc5.q2", sClassLoader);
            if (q2Class == null) return null;

            return XposedHelpers.callMethod(manager, "a", q2Class);
        } catch (Throwable e) {
            LogWriter.log(TAG, "getVoiceComponent error: " + e.getMessage());
            return null;
        }
    }

    private static void findVoiceComponentClass(ClassLoader cl) {
        try {
            XposedHelpers.findClass("com.tencent.mm.ui.chatting.component.so", cl);
            LogWriter.log(TAG, "VoiceComponent so loaded OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "VoiceComponent so not found: " + t.getMessage());
        }
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    // ============ 供 MessageHook 调用 ============

    public static void tryAutoPlayVoice(Object msg, long msgId, Object p0) {
        try {
            if (!sEnabled) return;
            boolean activated = ModuleConfig.load(
                com.leshao.v3.ContextManager.getPrefs()
            ).autoPlayVoice;
            if (!activated) {
                LogWriter.log(TAG, "autoPlayVoice disabled");
                return;
            }

            LogWriter.log(TAG, "tryAutoPlay: enter msgId=" + msgId + " lastId=" + sLastPlayedMsgId);

            if (msgId == sLastPlayedMsgId) {
                LogWriter.log(TAG, "tryAutoPlay: skip dup msgId=" + msgId);
                return;
            }

            try {
                boolean isSend = (Boolean) XposedHelpers.callMethod(msg, "G1");
                if (isSend) return;
            } catch (Throwable ignored) {}

            try {
                if ((Integer) XposedHelpers.callMethod(msg, "M0") == 5) return;
            } catch (Throwable ignored) {}

            Object voiceComp = sCurrentVoiceComp;
            if (voiceComp == null && p0 != null) {
                voiceComp = sCurrentVoiceComp = getVoiceComponent(p0);
                if (voiceComp != null) {
                    LogWriter.log(TAG, "VoiceComponent from p0 OK");
                }
            }
            if (voiceComp == null) {
                LogWriter.log(TAG, "tryAutoPlay: no VoiceComponent, msgId=" + msgId);
                return;
            }

            Object player = XposedHelpers.callMethod(voiceComp, "n0");
            if (player == null) {
                LogWriter.log(TAG, "tryAutoPlay: n0() null, msgId=" + msgId);
                return;
            }

            try {
                if ((Boolean) XposedHelpers.callMethod(player, "o")) return;
            } catch (Throwable ignored) {}

            XposedHelpers.callMethod(player, "I", msg, false);
            sLastPlayedMsgId = msgId;
            LogWriter.log(TAG, "auto-play OK! msgId=" + msgId
                + " talker=" + XposedHelpers.callMethod(msg, "N0"));

        } catch (Throwable e) {
            LogWriter.log(TAG, "tryAutoPlayVoice error: " + e.getMessage());
        }
    }
}
