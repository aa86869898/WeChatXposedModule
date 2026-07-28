package com.leshao.v3.hook;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import com.leshao.v3.LogWriter;

import java.io.File;
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
 * 语音消息自动播放 v44
 * - 收到语音 → playBackground(MediaPlayer) 后台立即播放
 * - 播失败 → 入队 → so.y() 进入聊天时 v0.I(msg) 兜底
 */
public class VoiceAutoPlay {

    private static final String TAG = "VAP";

    private static boolean sEnabled = true;
    private static long sLastPlayedMsgId = -1L;

    private static ClassLoader sClassLoader;
    private static Handler sHandler;

    private static final Queue<PendingVoiceMsg> sPendingQueue = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<Long, Object> sMsgMap = new ConcurrentHashMap<>();

    private static Class<?> sK0Class;
    private static Class<?> sU0Class;
    private static Class<?> sG1Class;
    private static Class<?> sZ1Class;
    private static Class<?> sSoClass;
    private static String sVoice2Dir;

    private static volatile Object sCurrentVoiceComp;

    private static class PendingVoiceMsg {
        final long msgId;
        final String talker;
        final Object msg;

        PendingVoiceMsg(long msgId, String talker, Object msg) {
            this.msgId = msgId;
            this.talker = talker;
            this.msg = msg;
        }
    }

    public static void hook(ClassLoader cl) {
        sClassLoader = cl;
        sHandler = new Handler(Looper.getMainLooper());

        try { sK0Class = XposedHelpers.findClass("com.tencent.mm.model.k0", cl); }
        catch (Throwable ignored) {}
        try { sU0Class = XposedHelpers.findClass("y21.u0", cl); }
        catch (Throwable ignored) {}
        try { sG1Class = XposedHelpers.findClass("y21.g1", cl); }
        catch (Throwable ignored) {}
        try { sZ1Class = XposedHelpers.findClass("y21.z1", cl); }
        catch (Throwable ignored) {}
        try { sSoClass = XposedHelpers.findClass("com.tencent.mm.ui.chatting.component.so", cl); }
        catch (Throwable ignored) {}

        android.util.Log.e(TAG, "init: k0=" + (sK0Class != null)
                + " u0=" + (sU0Class != null) + " g1=" + (sG1Class != null)
                + " z1=" + (sZ1Class != null) + " so=" + (sSoClass != null));

        hookVoiceComponent(cl);
    }

    // ========== so.y() hook — 进入聊天时兜底播放 ==========

    private static void hookVoiceComponent(ClassLoader cl) {
        if (sSoClass == null) return;
        try {
            XposedBridge.hookAllMethods(sSoClass, "y", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object so = param.thisObject;
                        sCurrentVoiceComp = so;

                        Object context = XposedHelpers.getObjectField(so, "d");
                        if (context == null) return;

                        String talker = extractTalkerFromContext(context);
                        if (talker == null || talker.isEmpty()) return;

                        sHandler.postDelayed(() -> playQueuedVoices(talker, so), 300);

                    } catch (Throwable e) {
                        android.util.Log.e(TAG, "so.y() err: " + e.getMessage());
                    }
                }
            });
            android.util.Log.e(TAG, "so.y() hook OK");
        } catch (Throwable e) {
            android.util.Log.e(TAG, "so.y() hook fail: " + e.getMessage());
        }
    }

    private static String extractTalkerFromContext(Object context) {
        for (String m : new String[]{"getTalker", "GT", "M0", "d1", "getUsername"}) {
            try { return (String) XposedHelpers.callMethod(context, m); }
            catch (Throwable ignored) {}
        }
        for (String f : new String[]{"field_talker", "talker", "mTalker", "a"}) {
            try { return (String) XposedHelpers.getObjectField(context, f); }
            catch (Throwable ignored) {}
        }
        return null;
    }

    // ========== 供 MessageHook 调用 ==========

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    /**
     * MessageHook type==34 → 后台立即播放
     */
    public static void onVoiceMsg(Object e9, long msgId, Object p0) {
        try {
            if (!sEnabled) return;

            try {
                int isSend = (Integer) XposedHelpers.callMethod(e9, "z0");
                if (isSend == 1) return;
            } catch (Throwable ignored) {}

            if (msgId == sLastPlayedMsgId) return;

            try {
                if ((Integer) XposedHelpers.callMethod(e9, "M0") == 5) return;
            } catch (Throwable ignored) {}

            sLastPlayedMsgId = msgId;

            String talker = null;
            try { talker = (String) XposedHelpers.callMethod(e9, "N0"); }
            catch (Throwable ignored) {}
            if (talker == null) try { talker = (String) XposedHelpers.getObjectField(e9, "field_talker"); }
            catch (Throwable ignored) {}
            if (talker == null) return;

            android.util.Log.e(TAG, "recv voice: msgId=" + msgId + " talker=" + talker);
            LogWriter.log(TAG, "recv: msgId=" + msgId + " talker=" + talker);

            sMsgMap.put(msgId, e9);

            boolean played = playBackground(e9, talker);
            if (played) {
                // 后台播放成功 → 不入队
                return;
            }

            // 后台播放失败 → 入队 + 如果在聊天中 try v0
            android.util.Log.e(TAG, "bg failed, enqueue: msgId=" + msgId);
            sPendingQueue.offer(new PendingVoiceMsg(msgId, talker, e9));

            // 如果正在聊天页面，直接用 v0 兜底
            final Object so = sCurrentVoiceComp;
            if (so != null) {
                String curTalker = extractTalkerFromContext(
                        XposedHelpers.getObjectField(so, "d"));
                if (talker.equals(curTalker)) {
                    final String fTalker = talker;
                    sHandler.post(() -> playQueuedVoices(fTalker, so));
                }
            }

        } catch (Throwable e) {
            android.util.Log.e(TAG, "onVoiceMsg err: " + e.getMessage());
        }
    }

    public static void tryAutoPlayVoice(Object e9, long msgId, Object p0) {
        onVoiceMsg(e9, msgId, p0);
    }

    // ========== 后台 MediaPlayer 播放 ==========

    private static boolean playBackground(Object msg, String talker) {
        try {
            String path = getVoicePath(msg, talker);
            if (path == null) {
                android.util.Log.e(TAG, "bg: no path for talker=" + talker);
                return false;
            }

            File f = new File(path);
            if (!f.exists()) {
                android.util.Log.e(TAG, "bg: file not exist " + path);
                return false;
            }

            long msgId = (Long) XposedHelpers.callMethod(msg, "H0");
            android.util.Log.e(TAG, "bg: " + path + " size=" + f.length() + " msgId=" + msgId);
            LogWriter.log(TAG, "bgPlay: msgId=" + msgId + " path=" + path + " size=" + f.length());

            final long fMsgId = msgId;
            final String fPath = path;
            sHandler.post(() -> {
                try {
                    MediaPlayer mp = new MediaPlayer();
                    mp.setDataSource(fPath);
                    mp.prepare();
                    mp.setOnCompletionListener(m -> {
                        android.util.Log.e(TAG, "bg done msgId=" + fMsgId);
                        LogWriter.log(TAG, "bgDone: msgId=" + fMsgId);
                        m.release();
                    });
                    mp.setOnErrorListener((m, what, extra) -> {
                        android.util.Log.e(TAG, "bg err msgId=" + fMsgId + " what=" + what);
                        LogWriter.log(TAG, "bgErr: msgId=" + fMsgId + " what=" + what);
                        m.release();
                        return true;
                    });
                    mp.start();
                    android.util.Log.e(TAG, "bg started msgId=" + fMsgId);
                } catch (Throwable e) {
                    android.util.Log.e(TAG, "bg play err: " + e.getMessage());
                }
            });
            return true;

        } catch (Throwable e) {
            android.util.Log.e(TAG, "playBackground err: " + e.getMessage());
            return false;
        }
    }

    // ========== 路径解析: e9.j() → y21.u0.a() → g1.x0() / z1.r() ==========

    private static String getVoicePath(Object msg, String talker) {
        try {
            String content = null;
            try { content = (String) XposedHelpers.callMethod(msg, "j"); }
            catch (Throwable ignored) {}
            if (content == null) try { content = (String) XposedHelpers.callMethod(msg, "I0"); }
            catch (Throwable ignored) {}
            if (content == null) {
                android.util.Log.e(TAG, "getVoicePath: content null");
                return null;
            }

            // y21.u0(content).a() → voiceId
            String voiceId = null;
            if (sU0Class != null) {
                try {
                    Object u0Obj = XposedHelpers.newInstance(sU0Class, content);
                    voiceId = (String) XposedHelpers.callMethod(u0Obj, "a");
                    android.util.Log.e(TAG, "u0.a() = " + voiceId);
                } catch (Throwable e) {
                    android.util.Log.e(TAG, "u0.a() err: " + e.getMessage());
                }
            }

            // a. voiceId 是绝对路径
            if (voiceId != null && voiceId.startsWith("/") && new File(voiceId).exists()) {
                return voiceId;
            }

            // b. z1.r() + g1.x0()
            if (sZ1Class != null && sG1Class != null) {
                try {
                    String accountPath = (String) XposedHelpers.callStaticMethod(sZ1Class, "r");
                    long msgId = (Long) XposedHelpers.callMethod(msg, "H0");
                    String g1Path = (String) XposedHelpers.callStaticMethod(sG1Class, "x0", accountPath, String.valueOf(msgId));
                    android.util.Log.e(TAG, "g1.x0 = " + g1Path);
                    if (g1Path != null) {
                        String full = accountPath + g1Path + ".amr";
                        if (new File(full).exists()) return full;
                    }
                } catch (Throwable e) {
                    android.util.Log.e(TAG, "z1/g1 err: " + e.getMessage());
                }
            }

            // c. voice2/talker/voiceId
            if (voiceId != null) {
                String v2 = findVoice2Dir();
                if (v2 != null) {
                    File f = new File(v2, talker + "/" + voiceId);
                    if (f.exists()) return f.getAbsolutePath();
                    f = new File(v2, voiceId);
                    if (f.exists()) return f.getAbsolutePath();
                }
            }

        } catch (Throwable e) {
            android.util.Log.e(TAG, "getVoicePath err: " + e.getMessage());
        }
        return null;
    }

    // ========== so.y() → v0 兜底播放 ==========

    private static void playQueuedVoices(String talker, Object so) {
        try {
            List<PendingVoiceMsg> pending = dequeueAll(talker);
            if (pending.isEmpty()) return;
            android.util.Log.e(TAG, "playQueuedVoices: pending=" + pending.size());

            for (PendingVoiceMsg pvm : pending) {
                Object msg = XposedHelpers.callStaticMethod(sK0Class, "Wi", talker, String.valueOf(pvm.msgId));
                Object player = XposedHelpers.callMethod(so, "n0");
                XposedHelpers.callMethod(player, "I", msg, false);
                android.util.Log.e(TAG, "PLAY id=" + pvm.msgId);
            }

        } catch (Throwable e) {
            android.util.Log.e(TAG, "playQueuedVoices err: " + e.getMessage());
        }
    }

    // ========== 供 TtsVoiceSender onDone 回调 ==========

    public static void playPendingVoice(String talker) {
        try {
            Object so = sCurrentVoiceComp;
            if (so == null) return;

            List<PendingVoiceMsg> pending = dequeueAll(talker);
            if (pending.isEmpty()) return;

            for (PendingVoiceMsg pvm : pending) {
                Object msg = XposedHelpers.callStaticMethod(sK0Class, "Wi", talker, String.valueOf(pvm.msgId));
                Object player = XposedHelpers.callMethod(so, "n0");
                XposedHelpers.callMethod(player, "I", msg, false);
                android.util.Log.e(TAG, "PLAY (tts) id=" + pvm.msgId);
            }

        } catch (Throwable e) {
            android.util.Log.e(TAG, "playPendingVoice err: " + e.getMessage());
        }
    }

    // ========== 队列 ==========

    private static List<PendingVoiceMsg> dequeueAll(String talker) {
        List<PendingVoiceMsg> result = new ArrayList<>();
        Iterator<PendingVoiceMsg> it = sPendingQueue.iterator();
        while (it.hasNext()) {
            PendingVoiceMsg pvm = it.next();
            if (talker == null || pvm.talker == null || talker.equals(pvm.talker)) {
                it.remove();
                result.add(pvm);
            }
        }
        return result;
    }

    // ========== Voice2 目录 ==========

    private static String findVoice2Dir() {
        if (sVoice2Dir != null) return sVoice2Dir;
        try {
            String[] roots = {
                "/data/data/com.tencent.mm/MicroMsg",
                "/data/user/0/com.tencent.mm/MicroMsg",
            };
            for (String root : roots) {
                File md = new File(root);
                if (!md.exists()) continue;
                File[] dirs = md.listFiles();
                if (dirs == null) continue;
                for (File dir : dirs) {
                    if (dir.isDirectory() && dir.getName().length() >= 32) {
                        File v2 = new File(dir, "voice2");
                        if (v2.exists() && v2.isDirectory()) {
                            sVoice2Dir = v2.getAbsolutePath();
                            return sVoice2Dir;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void notifyChattingUIResume(android.app.Activity activity) {}
}
