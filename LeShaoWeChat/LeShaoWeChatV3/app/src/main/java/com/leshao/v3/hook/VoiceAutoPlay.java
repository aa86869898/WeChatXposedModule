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
 * 语音消息自动播放
 *
 * 方案:
 *   1. Hook c0.a(View,fd5.d,e9) → 过滤 dq 实例 → 自动播放 (参考 LSPilot)
 *   2. Hook ChattingUI.onResume() → 获取 ChattingContext → VoiceComponent (备用)
 *   3. Hook e01.x9.n(e9,p0) → 检测 type==34 → 自动播放 (备用)
 *
 * 参考: 语音播放.zip (已验证方案)
 */
public class VoiceAutoPlay {

    private static final String TAG = "VoiceAutoPlay";
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private static boolean sEnabled = true;
    private static long sLastPlayedMsgId = -1L;

    private static ClassLoader sClassLoader;
    private static volatile Object sCurrentVoiceComp;
    private static volatile Object sCurrentChattingContext;

    private static String sVoice2BasePath;

    public static void hook(ClassLoader cl) {
        sClassLoader = cl;

        findVoice2BasePath();
        hookDqClass(cl);
        hookChattingUIResume(cl);
        hookMessageListener(cl);
        findVoiceComponentClass(cl);
        registerActivityCallback();
    }

    private static void findVoice2BasePath() {
        try {
            java.io.File microMsgDir = new java.io.File(
                    com.leshao.v3.ContextManager.getAppContext().getDataDir(), "MicroMsg");
            if (!microMsgDir.exists()) return;
            java.io.File[] subdirs = microMsgDir.listFiles();
            if (subdirs == null) return;
            for (java.io.File dir : subdirs) {
                if (dir.isDirectory() && dir.getName().length() == 32) {
                    java.io.File voice2 = new java.io.File(dir, "voice2");
                    if (voice2.exists()) {
                        sVoice2BasePath = voice2.getAbsolutePath();
                        LogWriter.log(TAG, "voice2 base: " + sVoice2BasePath);
                        return;
                    }
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findVoice2BasePath fail: " + e.getMessage());
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
                    try {
                        if (a.getClass().getName().contains("ChattingUI")) {
                            LogWriter.log(TAG, "ActivityLC: ChattingUI resumed, refreshing context");
                            refreshChattingContext(a);
                        }
                    } catch (Throwable ignored) {}
                }
                @Override public void onActivityPaused(Activity a) {}
                @Override public void onActivityStopped(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) {}
                @Override public void onActivityDestroyed(Activity a) {}
            });
            LogWriter.log(TAG, "ActivityLifecycleCallbacks registered OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "ActivityLifecycleCallbacks fail: " + t.getMessage());
        }
    }

    // ============ 层1: c0.a() — 消息视图绑定调度器 (参考 LSPilot) ============

    private static void hookDqClass(ClassLoader cl) {
        try {
            Class<?> c0 = XposedHelpers.findClass("com.tencent.mm.ui.chatting.viewitems.c0", cl);
            Class<?> fd5_d = XposedHelpers.findClass("fd5.d", cl);
            Class<?> e9Cls = cl.loadClass("com.tencent.mm.storage.e9");
            Class<?> dqCls = XposedHelpers.findClass("com.tencent.mm.ui.chatting.viewitems.dq", cl);

            for (java.lang.reflect.Method m : c0.getDeclaredMethods()) {
                if (!m.getName().equals("a") || m.getParameterCount() != 3) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts[0] != android.view.View.class) continue;
                if (pts[1] != fd5_d) continue;
                if (pts[2] != e9Cls) continue;

                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        LogWriter.log(TAG, "c0.a FIRED! args=" + p.args.length);
                        try {
                            Object g = XposedHelpers.getObjectField(p.thisObject, "g");
                            if (g == null) return;
                            if (!dqCls.isInstance(g)) {
                                LogWriter.log(TAG, "c0.a g class: " + g.getClass().getName());
                                return;
                            }

                            Object msg = p.args[2];
                            if (msg == null) return;

                            int type = (Integer) XposedHelpers.callMethod(msg, "getType");
                            if (type != 34) return;

                            long msgId = (Long) XposedHelpers.callMethod(msg, "H0");
                            String talker = (String) XposedHelpers.callMethod(msg, "N0");

                            if (msgId == sLastPlayedMsgId) return;

                            try {
                                boolean isSend = (Boolean) XposedHelpers.callMethod(msg, "G1");
                                if (isSend) return;
                            } catch (Throwable ignored) {}

                            try {
                                if ((Integer) XposedHelpers.callMethod(msg, "M0") == 5) return;
                            } catch (Throwable ignored) {}

                            // ChattingContext → manager → VoiceComponent
                            Object cc = p.args[1];
                            if (cc == null) return;

                            Object mgr = XposedHelpers.getObjectField(cc, "c");
                            if (mgr == null) return;

                            Class<?> q2Cls = XposedHelpers.findClass("zc5.q2", sClassLoader);
                            Object vc = XposedHelpers.callMethod(mgr, "a", q2Cls);
                            if (vc == null) return;

                            sCurrentChattingContext = cc;
                            sCurrentVoiceComp = vc;

                            Object player = XposedHelpers.callMethod(vc, "n0");
                            if (player == null) return;

                            if ((Boolean) XposedHelpers.callMethod(player, "o")) return;

                            XposedHelpers.callMethod(player, "I", msg, false);
                            sLastPlayedMsgId = msgId;
                            LogWriter.log(TAG, "auto-play OK! msgId=" + msgId + " talker=" + talker);

                        } catch (Throwable e) {
                            LogWriter.log(TAG, "c0.a err: " + e.getMessage());
                        }
                    }
                });
                LogWriter.log(TAG, "c0.a(View,fd5.d,e9) hooked OK");
                return;
            }
            LogWriter.log(TAG, "c0.a: method not found, fallback to scan");
            fallbackScanDq(cl);
        } catch (Throwable t) {
            LogWriter.log(TAG, "c0.a hook fail: " + t.getMessage() + ", fallback scan");
            fallbackScanDq(cl);
        }
    }

    private static void fallbackScanDq(ClassLoader cl) {
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
            if (type != 34) return;

            long msgId = (Long) XposedHelpers.callMethod(msg, "H0");
            tryAutoPlayVoice(msg, msgId, null);
        } catch (Throwable e) {
            LogWriter.log(TAG, "onMessageReceived err: " + e.getMessage());
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

    public static void notifyChattingUIResume(android.app.Activity activity) {
        try {
            refreshChattingContext(activity);
        } catch (Throwable ignored) {}
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

            String voicePath = getVoiceFilePath(msg);
            if (voicePath == null || voicePath.isEmpty()) {
                LogWriter.log(TAG, "no voice path for msgId=" + msgId);
                return;
            }

            String fullPath = new java.io.File(sVoice2BasePath, voicePath).getAbsolutePath();
            java.io.File f = new java.io.File(fullPath);
            if (!f.exists()) {
                LogWriter.log(TAG, "voice file not found: " + fullPath);
                return;
            }

            sLastPlayedMsgId = msgId;

            final String fPath = fullPath;
            sMainHandler.post(() -> {
                try {
                    android.media.MediaPlayer mp = new android.media.MediaPlayer();
                    mp.setDataSource(fPath);
                    mp.setOnCompletionListener(android.media.MediaPlayer::release);
                    mp.setOnErrorListener((p, what, extra) -> {
                        p.release();
                        return true;
                    });
                    mp.prepare();
                    mp.start();
                    LogWriter.log(TAG, "MediaPlayer started: msgId=" + msgId);
                } catch (Throwable e) {
                    LogWriter.log(TAG, "MediaPlayer fail: " + e.getMessage());
                }
            });

        } catch (Throwable e) {
            LogWriter.log(TAG, "tryAutoPlayVoice error: " + e.getMessage());
        }
    }

    private static String getVoiceFilePath(Object msg) {
        try {
            String cid = (String) XposedHelpers.callMethod(msg, "y0");
            if (cid == null || cid.isEmpty()) return null;
            String md5 = md5(cid);
            if (md5.isEmpty()) return null;
            String path = md5.substring(0, 2) + "/" + md5.substring(2, 4) + "/msg_" + cid + ".amr";
            return path;
        } catch (Throwable ignored) {}
        return null;
    }

    private static String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }
}
