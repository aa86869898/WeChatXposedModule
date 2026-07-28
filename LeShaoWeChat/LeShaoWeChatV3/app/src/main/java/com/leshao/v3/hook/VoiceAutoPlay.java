package com.leshao.v3.hook;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 语音消息自动播放 — 基于 语音消息自动播放.java 参考实现
 *
 * 微信语音由 VoiceComponent (so) 统一管理
 * 第1层: Hook VoiceComponent.y() → 进入聊天后自动播放
 * 第2层: Hook h5.m() → 检测新语音气泡, 模拟点击
 * 第3层: Hook v0 播放器 → 跟踪播放状态
 */
public class VoiceAutoPlay {

    private static final String TAG = "VoiceAutoPlay";
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static final Set<String> sPlayedMsgIds = new HashSet<>();

    private static boolean sEnabled = true;
    private static int sPlayDelay = 800;

    private static ClassLoader sClassLoader;
    private static Class<?> sVoiceComponentClass;
    private static Class<?> sH5Class;
    private static Class<?> sV0Class;

    public static void hook(ClassLoader cl) {
        sClassLoader = cl;

        findVoiceComponentClass(cl);
        findH5Class(cl);
        findV0Class(cl);

        if (sVoiceComponentClass != null) {
            hookVoiceComponent();
        } else {
            LogWriter.log(TAG, "VoiceComponent class not found, using fallback");
            hookFallback();
        }
    }

    private static void findVoiceComponentClass(ClassLoader cl) {
        String[] candidates = {"so", "sp", "sn", "sq", "sr", "ss", "st", "su", "sv", "sw", "sx", "sy", "sz",
            "co", "cp", "do", "dp", "eo", "ep", "fo", "fp", "go", "gp", "ho", "hp", "io", "ip",
            "jo", "jp", "ko", "kp", "lo", "lp", "mo", "mp", "no", "np", "oo", "op", "po", "pp",
            "qo", "qp", "ro", "rp", "to", "tp", "uo", "up", "vo", "vp", "wo", "wp", "xo", "xp",
            "yo", "yp", "zo", "zp", "ao", "ap", "bo", "bp"};
        String pkg = "com.tencent.mm.ui.chatting.component.";

        for (String name : candidates) {
            try {
                Class<?> cls = cl.loadClass(pkg + name);
                boolean hasY = false, hasL0 = false, hasN0 = false;
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getName().equals("y") && m.getParameterCount() == 0) hasY = true;
                    if (m.getName().equals("l0") && m.getParameterCount() == 1) hasL0 = true;
                    if (m.getName().equals("n0") && m.getParameterCount() == 0) hasN0 = true;
                }
                if (hasY && hasL0 && hasN0) {
                    sVoiceComponentClass = cls;
                    LogWriter.log(TAG, "found VoiceComponent: " + pkg + name);
                    return;
                }
            } catch (Throwable ignored) {}
        }

        try {
            Class<?> cls = XposedHelpers.findClass("com.tencent.mm.ui.chatting.component.so", cl);
            sVoiceComponentClass = cls;
            LogWriter.log(TAG, "found VoiceComponent: so (direct)");
        } catch (Throwable t) {
            LogWriter.log(TAG, "VoiceComponent not found: all candidates failed");
        }
    }

    private static void findH5Class(ClassLoader cl) {
        try {
            sH5Class = XposedHelpers.findClass("com.tencent.mm.ui.chatting.viewitems.h5", cl);
            LogWriter.log(TAG, "found h5");
        } catch (Throwable t) {
            LogWriter.log(TAG, "h5 not found: " + t.getMessage());
        }
    }

    private static void findV0Class(ClassLoader cl) {
        try {
            sV0Class = XposedHelpers.findClass("com.tencent.mm.ui.chatting.viewitems.v0", cl);
            LogWriter.log(TAG, "found v0");
        } catch (Throwable t) {
            LogWriter.log(TAG, "v0 not found: " + t.getMessage());
        }
    }

    private static void hookVoiceComponent() {
        // Hook y() = resetAutoPlay → 进入聊天时自动播放
        try {
            Method yMethod = null;
            for (Method m : sVoiceComponentClass.getDeclaredMethods()) {
                if (m.getName().equals("y") && m.getParameterCount() == 0) {
                    yMethod = m;
                    break;
                }
            }
            if (yMethod != null) {
                hookMethod(sVoiceComponentClass, yMethod.getName(), new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        onResetAutoPlay(param.thisObject);
                    }
                });
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook y() fail: " + t.getMessage());
        }

        // Hook l0(e9) = 语音消息点击处理 → 记录播放状态
        try {
            Method l0Method = null;
            for (Method m : sVoiceComponentClass.getDeclaredMethods()) {
                if (m.getName().equals("l0") && m.getParameterCount() == 1) {
                    l0Method = m;
                    break;
                }
            }
            if (l0Method != null) {
                hookMethod(sVoiceComponentClass, l0Method.getName(), new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object msg = param.args[0];
                            long msgId = (Long) XposedHelpers.callMethod(msg, "getMsgId");
                            sPlayedMsgIds.add(String.valueOf(msgId));
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook l0() fail: " + t.getMessage());
        }

        LogWriter.log(TAG, "VoiceComponent hooks installed");
    }

    private static void hookFallback() {
        if (sH5Class != null) hookH5();
        if (sV0Class != null) hookV0();
    }

    private static void hookH5() {
        try {
            for (Method m : sH5Class.getDeclaredMethods()) {
                if (m.getName().equals("m") && m.getParameterCount() >= 3) {
                    final Class<?>[] paramTypes = m.getParameterTypes();
                    hookMethod(sH5Class, "m", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onVoiceBubbleRendered(param);
                        }
                    });
                    LogWriter.log(TAG, "h5.m hooked");
                    break;
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "h5.m hook fail: " + t.getMessage());
        }
    }

    private static void hookV0() {
        try {
            for (Method m : sV0Class.getDeclaredMethods()) {
                if (m.getName().equals("t") && m.getParameterCount() <= 1) {
                    hookMethod(sV0Class, "t", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object player = param.thisObject;
                                long msgId = XposedHelpers.getLongField(player, "i");
                                LogWriter.log(TAG, "player.t() msgId=" + msgId);
                            } catch (Throwable ignored) {}
                        }
                    });
                    break;
                }
            }
            for (Method m : sV0Class.getDeclaredMethods()) {
                if (m.getName().equals("onClick")) {
                    hookMethod(sV0Class, "onClick", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object player = param.thisObject;
                                long msgId = XposedHelpers.getLongField(player, "i");
                                sPlayedMsgIds.add(String.valueOf(msgId));
                                LogWriter.log(TAG, "v0.onClick() msgId=" + msgId);
                            } catch (Throwable ignored) {}
                        }
                    });
                    break;
                }
            }
            LogWriter.log(TAG, "v0 hooks installed");
        } catch (Throwable t) {
            LogWriter.log(TAG, "v0 hook fail: " + t.getMessage());
        }
    }

    private static void hookMethod(Class<?> cls, String methodName, XC_MethodHook hook) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                try {
                    m.setAccessible(true);
                    de.robv.android.xposed.XposedBridge.hookMethod(m, hook);
                    return;
                } catch (Throwable t) {
                    LogWriter.log(TAG, "hookMethod " + methodName + " fail: " + t.getMessage());
                }
            }
        }
    }

    private static void onResetAutoPlay(Object comp) {
        if (!sEnabled) return;
        try {
            boolean activated = ModuleConfig.load(
                com.leshao.v3.ContextManager.getPrefs()
            ).autoPlayVoice;
            if (!activated) {
                LogWriter.log(TAG, "autoPlayVoice disabled in config");
                return;
            }
        } catch (Throwable ignored) {}

        try {
            Object chatCtx = XposedHelpers.getObjectField(comp, "d");
            if (chatCtx == null) return;
            Object username = XposedHelpers.callMethod(chatCtx, "x");
            if (username == null) return;
            LogWriter.log(TAG, "enter chat: " + username);

            sMainHandler.postDelayed(() -> {
                try {
                    autoPlayLatestVoice(comp, String.valueOf(username));
                } catch (Throwable e) {
                    LogWriter.log(TAG, "autoPlay error: " + e.getMessage());
                }
            }, sPlayDelay);
        } catch (Throwable e) {
            LogWriter.log(TAG, "onResetAutoPlay error: " + e.getMessage());
        }
    }

    private static void onVoiceBubbleRendered(XC_MethodHook.MethodHookParam param) {
        if (!sEnabled) return;
        try {
            Object holder = param.args[0];
            Object adapter = param.args.length > 2 ? param.args[2] : null;
            if (adapter == null) return;

            Object msgContainer = XposedHelpers.getObjectField(adapter, "d");
            if (msgContainer == null) return;
            Object msg = XposedHelpers.getObjectField(msgContainer, "b");
            if (msg == null) return;

            boolean isVoice = (Boolean) XposedHelpers.callMethod(msg, "d3");
            if (!isVoice) return;

            long msgId = (Long) XposedHelpers.callMethod(msg, "getMsgId");
            String msgIdStr = String.valueOf(msgId);

            if (sPlayedMsgIds.contains(msgIdStr)) return;

            int isSend = (Integer) XposedHelpers.callMethod(msg, "O0");
            if (isSend == 1) return;

            View clickArea = null;
            try { clickArea = (View) XposedHelpers.getObjectField(holder, "clickArea"); } catch (Throwable ignored) {}
            if (clickArea == null) {
                try { clickArea = (View) XposedHelpers.getObjectField(holder, "cy"); } catch (Throwable ignored) {}
            }

            if (clickArea != null) {
                final View area = clickArea;
                sPlayedMsgIds.add(msgIdStr);
                LogWriter.log(TAG, "auto-click voice: msgId=" + msgIdStr);
                sMainHandler.postDelayed(() -> area.performClick(), 200);
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "onVoiceBubble error: " + e.getMessage());
        }
    }

    private static void autoPlayLatestVoice(Object comp, String username) {
        try {
            Object player = XposedHelpers.callMethod(comp, "n0");
            if (player == null) return;

            boolean isPlaying = (Boolean) XposedHelpers.callMethod(player, "o");
            if (isPlaying) return;

            boolean isAlive = (Boolean) XposedHelpers.callMethod(player, "m");
            if (!isAlive) return;

            long currentMsgId = XposedHelpers.getLongField(player, "i");
            if (currentMsgId > 0 && !sPlayedMsgIds.contains(String.valueOf(currentMsgId))) {
                LogWriter.log(TAG, "direct play: msgId=" + currentMsgId);
                XposedHelpers.callMethod(player, "t");
                sPlayedMsgIds.add(String.valueOf(currentMsgId));
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "autoPlayLatestVoice error: " + e.getMessage());
        }
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }
}
