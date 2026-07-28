package com.leshao.v3.hook;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import com.leshao.v3.LogWriter;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 语音消息自动播放 v48
 * - 后台: SilkDecoder库 SILK→WAV→MediaPlayer 后台播放
 * - 聊天内: so.y()→v0.I(msg) 直接微信播放
 * - 双路径并行,后台优先
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

    private static volatile Object sCurrentSo;
    private static volatile Object sCurrentPlayer;

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
        LogWriter.log(TAG, "init: v48 SilkDecoder bg + v0.I chat");

        findVoice2Dir();
        hookVoiceComponent(cl);
    }

    // ========== so.y() hook ==========

    private static void hookVoiceComponent(ClassLoader cl) {
        if (sSoClass == null) return;
        try {
            XposedBridge.hookAllMethods(sSoClass, "y", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object so = param.thisObject;
                        sCurrentSo = so;
                        LogWriter.log(TAG, "so.y() fired, q=" + sPendingQueue.size());
                        sCurrentPlayer = getPlayer(so);
                        if (!sPendingQueue.isEmpty()) {
                            sHandler.postDelayed(() -> playAllFromQueue(), 800);
                        }
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

            // 后台 SILK→WAV→MediaPlayer 播放
            final String tTalker = talker;
            new Thread(() -> {
                boolean bgOk = playBackground(e9, tTalker, msgId);
                if (!bgOk) {
                    // 后台失败 → 入队等聊天时播放
                    sPendingQueue.offer(new PendingVoiceMsg(e9, msgId, tTalker));
                    LogWriter.log(TAG, "bgFail→queue: msgId=" + msgId + " q=" + sPendingQueue.size());
                    if (sCurrentSo != null) {
                        sHandler.post(() -> playAllFromQueue());
                    }
                }
            }, "VAP-bg-decode").start();

        } catch (Throwable e) {
            LogWriter.log(TAG, "onVoiceMsg err: " + e.getMessage());
        }
    }

    public static void tryAutoPlayVoice(Object e9, long msgId, Object p0) {
        onVoiceMsg(e9, msgId, p0);
    }

    // ========== 后台 SILK→WAV→MediaPlayer ==========

    private static boolean playBackground(Object msg, String talker, long msgId) {
        try {
            String path = getVoicePath(msg);
            if (path == null) {
                LogWriter.log(TAG, "bg: no path msgId=" + msgId);
                return false;
            }
            File f = new File(path);
            if (!f.exists()) {
                LogWriter.log(TAG, "bg: file not exist: " + path);
                return false;
            }

            LogWriter.log(TAG, "bg: path=" + path + " size=" + f.length() + " msgId=" + msgId);

            // SILK → WAV
            String wavPath = path + ".dec.wav";
            File wavFile = new File(wavPath);
            if (wavFile.exists()) wavFile.delete();

            xyz.xxin.silkdecoder.SilkDecoder.decodeToWav(path, wavPath);
            if (!wavFile.exists() || wavFile.length() == 0) {
                LogWriter.log(TAG, "bg: decode failed, wav not created");
                return false;
            }

            LogWriter.log(TAG, "bg: wav=" + wavPath + " size=" + wavFile.length());

            // MediaPlayer 播放
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(wavPath);
            mp.prepare();
            final String fWav = wavPath;
            mp.setOnCompletionListener(m -> {
                LogWriter.log(TAG, "bgDone: msgId=" + msgId);
                m.release();
                new File(fWav).delete();
            });
            mp.setOnErrorListener((m, what, extra) -> {
                LogWriter.log(TAG, "bgErr: msgId=" + msgId + " what=" + what);
                m.release();
                new File(fWav).delete();
                return true;
            });
            mp.start();
            LogWriter.log(TAG, "bgStarted: msgId=" + msgId);

            return true;

        } catch (Throwable e) {
            LogWriter.log(TAG, "bg err: " + e.getMessage());
            return false;
        }
    }

    // ========== 路径解析 ==========

    private static String getVoicePath(Object msg) {
        try {
            String cid = (String) XposedHelpers.callMethod(msg, "y0");
            if (cid != null && !cid.isEmpty()) {
                String path = resolveClientMsgIdPath(cid);
                if (path != null) return path;
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "getVoicePath: y0() err: " + e.getMessage());
        }
        return null;
    }

    private static String resolveClientMsgIdPath(String cid) {
        if (sVoice2Dir == null) return null;
        String md5 = md5(cid);
        if (md5 == null || md5.length() < 4) return null;
        String path = sVoice2Dir + "/" + md5.substring(0, 2) + "/" + md5.substring(2, 4) + "/msg_" + cid + ".amr";
        if (new File(path).exists()) return path;
        return null;
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) { return null; }
    }

    // ========== Player 获取 (聊天内) ==========

    private static Object getPlayer(Object so) {
        if (so == null) return null;
        for (String method : new String[]{"n0", "getPlayer", "N0", "getVoicePlayer", "p0", "o0", "k0", "I0"}) {
            try {
                Object r = XposedHelpers.callMethod(so, method);
                if (r != null) {
                    LogWriter.log(TAG, "getPlayer: so." + method + "()=" + r.getClass().getSimpleName());
                    return r;
                }
            } catch (Throwable ignored) {}
        }
        for (String field : new String[]{"p", "player", "n0", "mPlayer", "m", "N", "e", "f", "g"}) {
            try {
                Object r = XposedHelpers.getObjectField(so, field);
                if (r != null) {
                    LogWriter.log(TAG, "getPlayer: so." + field + "=" + r.getClass().getSimpleName());
                    return r;
                }
            } catch (Throwable ignored) {}
        }
        dumpSoMethods(so);
        return null;
    }

    private static void dumpSoMethods(Object so) {
        try {
            StringBuilder sb = new StringBuilder("so[methods:");
            int count = 0;
            for (Method m : so.getClass().getDeclaredMethods()) {
                if (count >= 40) break;
                Class<?>[] p = m.getParameterTypes();
                if (p.length <= 2) {
                    sb.append(" ").append(m.getName()).append("(").append(p.length).append(")");
                    count++;
                }
            }
            sb.append("]");
            LogWriter.log(TAG, sb.toString());
        } catch (Throwable t) {
            LogWriter.log(TAG, "dumpSoMethods err: " + t.getMessage());
        }
    }

    // ========== 聊天内播放 ==========

    private static void playAllFromQueue() {
        try {
            List<PendingVoiceMsg> pending = dequeue(null);
            if (pending.isEmpty()) return;
            LogWriter.log(TAG, "playAll: pending=" + pending.size());

            if (sCurrentPlayer == null && sCurrentSo != null) {
                sCurrentPlayer = getPlayer(sCurrentSo);
            }
            if (sCurrentPlayer == null) {
                LogWriter.log(TAG, "playAll: player null");
                return;
            }

            for (PendingVoiceMsg pvm : pending) {
                try {
                    XposedHelpers.callMethod(sCurrentPlayer, "I", pvm.msg, false);
                    LogWriter.log(TAG, "PLAY id=" + pvm.msgId);
                    Thread.sleep(250);
                } catch (Throwable e) {
                    LogWriter.log(TAG, "playAll: play err id=" + pvm.msgId + " " + e.getMessage());
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "playAll err: " + e.getMessage());
        }
    }

    // ========== 供 TtsVoiceSender onDone 回调 ==========

    public static void playPendingVoice(String talker) {
        try {
            if (sCurrentSo == null) return;
            List<PendingVoiceMsg> pending = dequeue(talker);
            if (pending.isEmpty()) return;
            if (sCurrentPlayer == null) sCurrentPlayer = getPlayer(sCurrentSo);
            if (sCurrentPlayer == null) return;

            for (PendingVoiceMsg pvm : pending) {
                try {
                    XposedHelpers.callMethod(sCurrentPlayer, "I", pvm.msg, false);
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

    public static void notifyChattingUIResume(android.app.Activity activity) {}
}
