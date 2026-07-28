package com.leshao.v3.hook;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import com.leshao.v3.LogWriter;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 语音消息自动播放 v45
 * - 收到语音 → y0()取clientmsgid → MD5路径 → MediaPlayer后台立即播放
 * - 播失败 → 入队 → so.y() 进入聊天时 k0.Wi()→so.n0()→v0.I(msg) 兜底
 * - 全部诊断走 LogWriter.log
 */
public class VoiceAutoPlay {

    private static final String TAG = "VAP";

    private static boolean sEnabled = true;
    private static long sLastPlayedMsgId = -1L;

    private static ClassLoader sClassLoader;
    private static Handler sHandler;

    private static final Queue<PendingVoiceMsg> sPendingQueue = new ConcurrentLinkedQueue<>();

    private static Class<?> sK0Class;
    private static Class<?> sU0Class;
    private static Class<?> sSoClass;
    private static String sVoice2Dir;

    private static volatile Object sCurrentVoiceComp;

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
        sHandler = new Handler(Looper.getMainLooper());

        try { sK0Class = XposedHelpers.findClass("com.tencent.mm.model.k0", cl); }
        catch (Throwable t) { LogWriter.log(TAG, "k0 class NOT found: " + t.getMessage()); }
        try { sU0Class = XposedHelpers.findClass("y21.u0", cl); }
        catch (Throwable t) { LogWriter.log(TAG, "u0 class NOT found: " + t.getMessage()); }
        try { sSoClass = XposedHelpers.findClass("com.tencent.mm.ui.chatting.component.so", cl); }
        catch (Throwable t) { LogWriter.log(TAG, "so class NOT found: " + t.getMessage()); }

        LogWriter.log(TAG, "init: k0=" + (sK0Class != null)
                + " u0=" + (sU0Class != null) + " so=" + (sSoClass != null));

        findVoice2Dir();
        hookVoiceComponent(cl);
    }

    // ========== so.y() hook — 进入聊天时兜底播放 ==========

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
                        LogWriter.log(TAG, "so.y() fired");

                        Object context = XposedHelpers.getObjectField(so, "d");
                        if (context == null) {
                            LogWriter.log(TAG, "so.d null");
                            return;
                        }

                        String talker = extractTalkerFromContext(context);
                        LogWriter.log(TAG, "so.y() talker=" + talker);
                        if (talker == null || talker.isEmpty()) return;

                        sHandler.postDelayed(() -> playQueuedVoices(talker, so), 300);

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

            // 后台 MediaPlayer 播放
            boolean played = playBackground(e9, talker);
            if (!played) {
                // 播失败 → 入队等 so.y()
                LogWriter.log(TAG, "bg fail, enqueue: msgId=" + msgId);
                sPendingQueue.offer(new PendingVoiceMsg(msgId, talker));

                // 如果正在聊天页面，直接用 v0 兜底
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
            }

        } catch (Throwable e) {
            LogWriter.log(TAG, "onVoiceMsg err: " + e.getMessage());
        }
    }

    public static void tryAutoPlayVoice(Object e9, long msgId, Object p0) {
        onVoiceMsg(e9, msgId, p0);
    }

    // ========== 后台 SILK→PCM→AudioTrack 播放 ==========

    private static boolean playBackground(Object msg, String talker) {
        try {
            String path = getVoicePath(msg, talker);
            if (path == null) {
                LogWriter.log(TAG, "bg: no path msgId=" + getMsgId(msg));
                return false;
            }

            File f = new File(path);
            if (!f.exists()) {
                LogWriter.log(TAG, "bg: file not exist: " + path);
                return false;
            }

            long msgId = getMsgId(msg);
            LogWriter.log(TAG, "bg: path=" + path + " size=" + f.length() + " msgId=" + msgId);

            // 读文件
            byte[] silkData = new byte[(int) f.length()];
            FileInputStream fis = new FileInputStream(f);
            int total = 0;
            while (total < silkData.length) {
                int r = fis.read(silkData, total, silkData.length - total);
                if (r < 0) break;
                total += r;
            }
            fis.close();

            // SILK → PCM
            byte[] pcmData = SilkDecoder.decode(silkData);
            if (pcmData == null || pcmData.length == 0) {
                LogWriter.log(TAG, "bg: silk decode failed msgId=" + msgId);
                return false;
            }

            LogWriter.log(TAG, "bg: pcm=" + pcmData.length + " bytes msgId=" + msgId);

            final long fMsgId = msgId;
            final byte[] fPcm = pcmData;

            new Thread(() -> {
                try {
                    int sampleRate = 8000;
                    int bufSize = AudioTrack.getMinBufferSize(
                            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);

                    AudioTrack track = new AudioTrack(
                            AudioManager.STREAM_MUSIC,
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(bufSize, fPcm.length / 2),
                            AudioTrack.MODE_STATIC);

                    track.write(fPcm, 0, fPcm.length);
                    track.play();

                    int durationMs = (fPcm.length / 2) * 1000 / sampleRate;
                    Thread.sleep(durationMs + 200);

                    track.stop();
                    track.release();
                    LogWriter.log(TAG, "bgDone: msgId=" + fMsgId + " pcm=" + fPcm.length + " dur=" + durationMs + "ms");
                } catch (Throwable e) {
                    LogWriter.log(TAG, "bg play err: " + e.getMessage());
                }
            }, "VAP-bg-play").start();

            return true;

        } catch (Throwable e) {
            LogWriter.log(TAG, "playBackground err: " + e.getMessage());
            return false;
        }
    }

    // ========== 路径解析 ==========

    private static String getVoicePath(Object msg, String talker) {
        // 方法1: y0() → clientmsgid → MD5 → voice2/{XX}/{YY}/msg_{cid}.amr
        try {
            String cid = (String) XposedHelpers.callMethod(msg, "y0");
            if (cid != null && !cid.isEmpty()) {
                String path = resolveClientMsgIdPath(cid);
                LogWriter.log(TAG, "getVoicePath: y0() cid=" + cid + " → path=" + path);
                if (path != null) return path;
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "getVoicePath: y0() err: " + e.getMessage());
        }

        // 方法2: j() / I0() → XML → y21.u0.a() → g1 + z1
        try {
            String content = null;
            try { content = (String) XposedHelpers.callMethod(msg, "j"); }
            catch (Throwable ignored) {}
            if (content == null) try { content = (String) XposedHelpers.callMethod(msg, "I0"); }
            catch (Throwable ignored) {}

            LogWriter.log(TAG, "getVoicePath: content.len=" + (content != null ? content.length() : 0));

            if (content != null && content.length() > 50 && sU0Class != null) {
                Object u0Obj = XposedHelpers.newInstance(sU0Class, content);
                String voiceId = (String) XposedHelpers.callMethod(u0Obj, "a");
                LogWriter.log(TAG, "getVoicePath: u0.a()=" + voiceId);
                if (voiceId != null && voiceId.startsWith("/") && new File(voiceId).exists()) {
                    return voiceId;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "getVoicePath: u0 err: " + e.getMessage());
        }

        // 方法3: 纯 XML 属性提取 clientmsgid
        try {
            String content = null;
            try { content = (String) XposedHelpers.callMethod(msg, "I0"); }
            catch (Throwable ignored) {}
            if (content == null) try { content = (String) XposedHelpers.callMethod(msg, "j"); }
            catch (Throwable ignored) {}
            if (content != null) {
                String cid = extractXmlAttr(content, "clientmsgid");
                if (cid != null && !cid.isEmpty()) {
                    String path = resolveClientMsgIdPath(cid);
                    LogWriter.log(TAG, "getVoicePath: xml clientmsgid=" + cid + " → path=" + path);
                    if (path != null) return path;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "getVoicePath: xml err: " + e.getMessage());
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

    private static String extractXmlAttr(String xml, String attr) {
        for (String q : new String[]{"\"", "'"}) {
            String pattern = attr + "=" + q;
            int idx = xml.indexOf(pattern);
            if (idx >= 0) {
                idx += pattern.length();
                int end = xml.indexOf(q, idx);
                if (end > idx) return xml.substring(idx, end);
            }
        }
        return null;
    }

    private static String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) { return null; }
    }

    // ========== so.y() → v0 兜底播放 ==========

    private static void playQueuedVoices(String talker, Object so) {
        try {
            List<Long> pending = dequeue(talker);
            if (pending.isEmpty()) return;
            LogWriter.log(TAG, "playQueued: pending=" + pending.size());

            for (Long id : pending) {
                if (sK0Class == null) {
                    LogWriter.log(TAG, "k0 class null, skip");
                    continue;
                }
                Object msg = XposedHelpers.callStaticMethod(sK0Class, "Wi", talker, String.valueOf(id));
                Object player = XposedHelpers.callMethod(so, "n0");
                XposedHelpers.callMethod(player, "I", msg, false);
                LogWriter.log(TAG, "PLAY id=" + id);
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
            if (sK0Class == null) return;

            List<Long> pending = dequeue(talker);
            if (pending.isEmpty()) return;

            for (Long id : pending) {
                Object msg = XposedHelpers.callStaticMethod(sK0Class, "Wi", talker, String.valueOf(id));
                Object player = XposedHelpers.callMethod(so, "n0");
                XposedHelpers.callMethod(player, "I", msg, false);
                LogWriter.log(TAG, "PLAY (tts) id=" + id);
            }

        } catch (Throwable e) {
            LogWriter.log(TAG, "playPendingVoice err: " + e.getMessage());
        }
    }

    // ========== 队列 ==========

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

    private static long getMsgId(Object msg) {
        try { return (Long) XposedHelpers.callMethod(msg, "H0"); }
        catch (Throwable ignored) { return 0; }
    }

    public static void notifyChattingUIResume(android.app.Activity activity) {}
}
