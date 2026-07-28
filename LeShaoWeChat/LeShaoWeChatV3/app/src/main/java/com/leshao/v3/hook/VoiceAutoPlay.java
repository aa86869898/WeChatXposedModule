package com.leshao.v3.hook;

import android.os.Handler;
import android.os.Looper;

import com.leshao.v3.LogWriter;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 语音消息自动播放 v46
 * - 收到语音 → 入队(保存msg对象引用)
 * - so.y() → 直接调用 player.I(savedMsg, false) 播放队列中所有语音
 * - 不再尝试后台解码SILK → 借助微信自有播放器,不崩溃
 */
public class VoiceAutoPlay {

    private static final String TAG = "VAP";

    private static boolean sEnabled = true;
    private static long sLastPlayedMsgId = -1L;

    private static ClassLoader sClassLoader;
    private static Handler sHandler;

    private static final ConcurrentLinkedQueue<PendingVoiceMsg> sPendingQueue = new ConcurrentLinkedQueue<>();

    private static Class<?> sK0Class;
    private static Class<?> sSoClass;
    private static String sVoice2Dir;

    private static volatile Object sCurrentVoiceComp;

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
        sHandler = new Handler(Looper.getMainLooper());

        try { sK0Class = XposedHelpers.findClass("com.tencent.mm.model.k0", cl); }
        catch (Throwable t) { LogWriter.log(TAG, "k0 class NOT found: " + t.getMessage()); }
        try { sSoClass = XposedHelpers.findClass("com.tencent.mm.ui.chatting.component.so", cl); }
        catch (Throwable t) { LogWriter.log(TAG, "so class NOT found: " + t.getMessage()); }

        LogWriter.log(TAG, "init: k0=" + (sK0Class != null) + " so=" + (sSoClass != null));
        LogWriter.log(TAG, "init: strat=savedMsg→v0.I()");

        findVoice2Dir();
        hookVoiceComponent(cl);
    }

    // ========== so.y() hook ==========

    private static void hookVoiceComponent(ClassLoader cl) {
        if (sSoClass == null) {
            LogWriter.log(TAG, "so class null, skip so.y() hook");
            return;
        }
        try {
            XposedBridge.hookAllMethods(sSoClass, "y", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object so = param.thisObject;
                        sCurrentVoiceComp = so;
                        LogWriter.log(TAG, "so.y() fired, q=" + sPendingQueue.size());

                        Object context = XposedHelpers.getObjectField(so, "d");
                        if (context == null) {
                            LogWriter.log(TAG, "so.d null");
                            return;
                        }

                        String talker = extractTalkerFromContext(context);
                        LogWriter.log(TAG, "so.y() talker=" + talker);
                        if (talker == null || talker.isEmpty()) return;

                        sHandler.postDelayed(() -> playQueuedVoices(talker, so), 500);

                    } catch (Throwable e) {
                        LogWriter.log(TAG, "so.y() err: " + e.getMessage());
                    }
                }
            });
            LogWriter.log(TAG, "so.y() hook OK");
        } catch (Throwable e) {
            LogWriter.log(TAG, "so.y() hook fail: " + e.getMessage());
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
            if (talker == null) {
                LogWriter.log(TAG, "talker null, skip");
                return;
            }

            LogWriter.log(TAG, "recv: msgId=" + msgId + " talker=" + talker);

            // 入队,保存 msg 对象引用供 so.y() 时直接播放
            sPendingQueue.offer(new PendingVoiceMsg(e9, msgId, talker));
            LogWriter.log(TAG, "enqueue: msgId=" + msgId + " q=" + sPendingQueue.size());

            // 如果正在聊天页面,sCurrentVoiceComp 存在 → 直接播放
            final Object so = sCurrentVoiceComp;
            if (so != null) {
                try {
                    String curTalker = extractTalkerFromContext(
                            XposedHelpers.getObjectField(so, "d"));
                    if (talker.equals(curTalker)) {
                        final String fTalker = talker;
                        sHandler.post(() -> playQueuedVoices(fTalker, so));
                    }
                } catch (Throwable ignored) {}
            }

        } catch (Throwable e) {
            LogWriter.log(TAG, "onVoiceMsg err: " + e.getMessage());
        }
    }

    public static void tryAutoPlayVoice(Object e9, long msgId, Object p0) {
        onVoiceMsg(e9, msgId, p0);
    }

    // ========== so.y() → v0.I(msg) 播放 ==========

    private static void playQueuedVoices(String talker, Object so) {
        try {
            List<PendingVoiceMsg> pending = dequeue(talker);
            if (pending.isEmpty()) return;
            LogWriter.log(TAG, "playQueued: pending=" + pending.size() + " talker=" + talker);

            Object player = XposedHelpers.callMethod(so, "n0");
            if (player == null) {
                LogWriter.log(TAG, "playQueued: player null");
                return;
            }

            for (PendingVoiceMsg pvm : pending) {
                try {
                    XposedHelpers.callMethod(player, "I", pvm.msg, false);
                    LogWriter.log(TAG, "PLAY id=" + pvm.msgId);
                    Thread.sleep(200);
                } catch (Throwable e) {
                    LogWriter.log(TAG, "playQueued: play err id=" + pvm.msgId + " " + e.getMessage());
                }
            }

        } catch (Throwable e) {
            LogWriter.log(TAG, "playQueuedVoices err: " + e.getMessage());
        }
    }

    // ========== 供 TtsVoiceSender onDone 回调 ==========

    public static void playPendingVoice(String talker) {
        try {
            Object so = sCurrentVoiceComp;
            if (so == null) return;

            List<PendingVoiceMsg> pending = dequeue(talker);
            if (pending.isEmpty()) return;

            Object player = XposedHelpers.callMethod(so, "n0");
            if (player == null) return;

            for (PendingVoiceMsg pvm : pending) {
                try {
                    XposedHelpers.callMethod(player, "I", pvm.msg, false);
                    LogWriter.log(TAG, "PLAY (tts) id=" + pvm.msgId);
                    Thread.sleep(200);
                } catch (Throwable e) {
                    LogWriter.log(TAG, "playPendingVoice: play err id=" + pvm.msgId);
                }
            }

        } catch (Throwable e) {
            LogWriter.log(TAG, "playPendingVoice err: " + e.getMessage());
        }
    }

    // ========== 队列 ==========

    private static List<PendingVoiceMsg> dequeue(String talker) {
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

    private static void findVoice2Dir() {
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
                            LogWriter.log(TAG, "voice2: " + sVoice2Dir);
                            return;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "findVoice2Dir err: " + t.getMessage());
        }
        LogWriter.log(TAG, "voice2 NOT found");
    }

    // ========== ChattingUI 辅助 ==========

    public static void notifyChattingUIResume(android.app.Activity activity) {}
}
