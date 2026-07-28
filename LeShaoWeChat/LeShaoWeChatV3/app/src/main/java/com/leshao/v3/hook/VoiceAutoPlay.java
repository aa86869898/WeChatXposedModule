package com.leshao.v3.hook;

import android.media.MediaPlayer;
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

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 语音消息自动播放 v43
 * - 废弃 so.y()，改用 TTS onDone → MediaPlayer 直接播文件
 * - y21.x0.g(talker, msgId) 取语音文件路径
 */
public class VoiceAutoPlay {

    private static final String TAG = "VoiceAutoPlay";

    private static boolean sEnabled = true;
    private static long sLastPlayedMsgId = -1L;

    private static ClassLoader sClassLoader;

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
        return sMsgMap.get(msgId);
    }

    // =========== 供 MessageHook / TtsVoiceSender 调用 ===========

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

            LogWriter.log(TAG, "enqueue: msgId=" + msgId + " talker=" + trunc(talker));

            // 诊断: 打印 y21.x0.g() 路径
            try {
                Class<?> y21x0 = XposedHelpers.findClass("y21.x0", sClassLoader);
                String path = (String) XposedHelpers.callStaticMethod(y21x0, "g", talker, String.valueOf(msgId));
                android.util.Log.e(TAG, "!!! y21.x0.g() path=" + path);
                LogWriter.log(TAG, "y21.x0.g(" + talker + "," + msgId + ") = " + path);
            } catch (Throwable e) {
                android.util.Log.e(TAG, "!!! y21.x0.g() err: " + e.getMessage());
            }

            sMsgMap.put(msgId, msg);
            sPendingQueue.offer(new PendingVoiceMsg(msgId, talker));

            // 直接播放语音文件，不走 TTS 通知
            playPendingVoice(talker);

        } catch (Throwable e) {
            LogWriter.log(TAG, "tryAutoPlay err: " + e.getMessage());
        }
    }

    public static void notifyChattingUIResume(android.app.Activity activity) {}

    /**
     * 供 TtsVoiceSender TTS onDone 回调 → MediaPlayer 播放
     */
    public static void playPendingVoice(String talker) {
        android.util.Log.e(TAG, ">>> playPendingVoice talker=" + talker);
        try {
            List<Long> pending = dequeue(talker);
            if (pending.isEmpty()) {
                android.util.Log.e(TAG, ">>> no pending voice for talker=" + talker);
                return;
            }

            sHandler.post(() -> {
                for (long msgId : pending) {
                    try {
                        playVoiceFile(talker, msgId);
                    } catch (Throwable e) {
                        android.util.Log.e(TAG, ">>> playVoiceFile err: " + e.getMessage());
                    }
                }
            });
        } catch (Throwable e) {
            android.util.Log.e(TAG, "playPendingVoice err: " + e.getMessage());
        }
    }

    private static void playVoiceFile(String talker, long msgId) {
        try {
            Class<?> y21x0 = XposedHelpers.findClass("y21.x0", sClassLoader);
            String path = (String) XposedHelpers.callStaticMethod(y21x0, "g", talker, String.valueOf(msgId));

            android.util.Log.e(TAG, ">>> playVoiceFile path=" + path);
            LogWriter.log(TAG, "playVoiceFile: msgId=" + msgId + " path=" + path);

            if (path == null || path.isEmpty()) {
                android.util.Log.e(TAG, ">>> path is null/empty for msgId=" + msgId);
                return;
            }

            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mp.setDataSource(path);
            mp.prepare();
            mp.setOnCompletionListener(m -> {
                android.util.Log.e(TAG, ">>> playback completed msgId=" + msgId);
                LogWriter.log(TAG, "play done msgId=" + msgId);
                m.release();
            });
            mp.setOnErrorListener((m, what, extra) -> {
                android.util.Log.e(TAG, ">>> playback error msgId=" + msgId + " what=" + what + " extra=" + extra);
                LogWriter.log(TAG, "play error msgId=" + msgId);
                m.release();
                return true;
            });
            mp.start();
            android.util.Log.e(TAG, ">>> PLAY START msgId=" + msgId);
        } catch (Throwable e) {
            android.util.Log.e(TAG, ">>> playVoiceFile err: " + e.getMessage());
            LogWriter.log(TAG, "playVoiceFile err: " + e.getMessage());
        }
    }

    private static String trunc(String s) {
        return s == null ? "null" : s.length() > 15 ? s.substring(0, 15) + "..." : s;
    }
}
