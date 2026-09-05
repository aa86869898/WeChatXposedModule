package com.leshao.v3.hook;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.KeyEvent;

import com.leshao.v3.LogWriter;
import com.leshao.v3.service.TTSBroadcaster;

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
 * 语音消息自动播放 v49
 * - 方案A(首选): 微信CDN流式API y21.x0.h(e9,voiceId)→y21.j→AudioTrack 后台播放
 * - 方案B(回退): SilkDecoder库 SILK→WAV→MediaPlayer 后台播放
 * - 方案C(回退): so.y()→v0.I(msg) 聊天内播放
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

    // 微信CDN流式API类
    private static Class<?> sU0Cls;       // y21.u0 — 解析voice内容
    private static Class<?> sX0Cls;       // y21.x0 — 下载+解码
    private static Class<?> sJCls;       // y21.j  — 音频流

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
        catch (Throwable t) {
            try { sK0Class = XposedHelpers.findClass("com.tencent.mm.k0", cl); }
            catch (Throwable t2) {
                try { sK0Class = XposedHelpers.findClass("com.tencent.mm.app.k0", cl); }
                catch (Throwable t3) { sK0Class = null; }
            }
        }
        if (sK0Class == null) {
            LogWriter.log(TAG, "k0 class NOT found in any of the 4 alternatives");
        }
        try { sSoClass = XposedHelpers.findClass("com.tencent.mm.ui.chatting.component.so", cl); }
        catch (Throwable t) { LogWriter.log(TAG, "so class NOT found: " + t.getMessage()); }

        // 微信CDN流式API类
        try { sU0Cls = cl.loadClass("y21.u0"); }
        catch (Throwable t) { LogWriter.log(TAG, "y21.u0 NOT found: " + t.getMessage()); }
        try { sX0Cls = cl.loadClass("y21.x0"); }
        catch (Throwable t) { LogWriter.log(TAG, "y21.x0 NOT found: " + t.getMessage()); }
        try { sJCls = cl.loadClass("y21.j"); }
        catch (Throwable t) { LogWriter.log(TAG, "y21.j NOT found: " + t.getMessage()); }

        LogWriter.log(TAG, "init: k0=" + (sK0Class != null) + " so=" + (sSoClass != null)
            + " u0=" + (sU0Cls != null) + " x0=" + (sX0Cls != null) + " j=" + (sJCls != null));

        findVoice2Dir();
        hookVoiceComponent(cl);
        hookVolumeKeyPause(cl);
    }

    // ========== 音量键暂停播报 ==========

    private static void hookVolumeKeyPause(ClassLoader cl) {
        try {
            java.lang.reflect.Method dispatchKeyEvent = android.app.Activity.class
                .getDeclaredMethod("dispatchKeyEvent", KeyEvent.class);
            XposedBridge.hookMethod(dispatchKeyEvent, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            KeyEvent event = (KeyEvent) param.args[0];
                            int keyCode = event.getKeyCode();
                            int action = event.getAction();
                            if (action == KeyEvent.ACTION_DOWN
                                && (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                                 || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
                                if (TTSBroadcaster.isSpeaking()) {
                                    TTSBroadcaster.pause();
                                    LogWriter.log(TAG, "volumeKeyPause: paused TTS");
                                    param.setResult(true);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            LogWriter.log(TAG, "volumeKeyPause hook OK");
        } catch (Throwable e) {
            LogWriter.log(TAG, "volumeKeyPause hook fail: " + e.getMessage());
        }
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
                        if (!sPendingQueue.isEmpty()) {
                            ensureWorker();
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

    public static boolean shouldAutoPlay(String talker) {
        if (talker == null) return false;
        try {
            // 从 WmPrefs 读取实时开关状态
            android.content.Context ctx = com.leshao.v3.ContextManager.getAppContext();
            if (ctx != null) {
                boolean prefsOn = com.leshao.v3.UnifiedPrefs.get(ctx, "wm_prefs")
                        .getBoolean("auto_voice", true);
                if (!prefsOn) return false;
            }
        } catch (Throwable ignored) {}
        if (!sEnabled) return false;
        // 与文本播报(ls_tts_whitelist/ls_tts_blacklist)保持一致:
        // 黑名单内一律不自动播放; 白名单非空时仅白名单内自动播放;
        // 白名单为空且严格模式开启时不自动播放
        try {
            com.leshao.v3.model.ModuleConfig cfg = com.leshao.v3.model.ModuleConfig.load(com.leshao.v3.ContextManager.getPrefs());
            if (!cfg.announceBlacklist.isEmpty() && cfg.announceBlacklist.contains(talker)) {
                return false;
            }
            if (cfg.announceWhitelist != null && !cfg.announceWhitelist.isEmpty()) {
                return cfg.announceWhitelist.contains(talker);
            }
            if (cfg.whitelistStrict) return false;
        } catch (Throwable t) {
            LogWriter.log(TAG, "shouldAutoPlay cfg err: " + t.getMessage());
        }
        return true;
    }

    public static void onVoiceMsg(Object e9, long msgId, Object p0) {
        try {
            android.util.Log.e(TAG, ">>> onVoiceMsg ENTER msgId=" + msgId + " enabled=" + sEnabled + " lastPlayed=" + sLastPlayedMsgId);
            if (!sEnabled) {
                android.util.Log.e(TAG, ">>> onVoiceMsg: DISABLED");
                return;
            }

            try {
                int isSend = (Integer) XposedHelpers.callMethod(e9, "z0");
                if (isSend == 1) {
                    android.util.Log.e(TAG, ">>> onVoiceMsg: isSend=1 SKIP");
                    return;
                }
            } catch (Throwable ignored) {}

            if (msgId == sLastPlayedMsgId) {
                android.util.Log.e(TAG, ">>> onVoiceMsg: DUPLICATE msgId=" + msgId);
                return;
            }

            try {
                if ((Integer) XposedHelpers.callMethod(e9, "M0") == 5) {
                    android.util.Log.e(TAG, ">>> onVoiceMsg: M0==5 SKIP");
                    return;
                }
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

            final String tTalker = talker;
            final Object e9Final = e9;
            final long msgIdFinal = msgId;

            sPendingQueue.offer(new PendingVoiceMsg(e9Final, msgIdFinal, tTalker));
            LogWriter.log(TAG, "enqueue: msgId=" + msgIdFinal + " q=" + sPendingQueue.size());
            ensureWorker();

        } catch (Throwable e) {
            LogWriter.log(TAG, "onVoiceMsg err: " + e.getMessage());
        }
    }

    public static void tryAutoPlayVoice(Object e9, long msgId, Object p0) {
        onVoiceMsg(e9, msgId, p0);
    }

    // ========== 方案A: 微信CDN流式API ==========

    private static boolean playViaWxStream(Object e9, String talker, long msgId) {
        try {
            // Step 1: 提取 voiceId
            String voiceId = extractVoiceId(e9, msgId);
            if (voiceId == null) {
                LogWriter.log(TAG, "stream: voiceId null msgId=" + msgId);
                return false;
            }
            if (voiceId.equals(talker) || voiceId.contains("wxid_")) {
                LogWriter.log(TAG, "stream: voiceId invalid (looks like talker)=" + trunc(voiceId, 40) + " msgId=" + msgId);
                return false;
            }
            LogWriter.log(TAG, "stream: voiceId=" + trunc(voiceId, 40) + " msgId=" + msgId);

            if (sX0Cls == null) {
                LogWriter.log(TAG, "stream: y21.x0 class not found, cannot download");
                return false;
            }

            // Step 2: y21.x0.h(e9, voiceId) → y21.j 音频流
            Object stream = XposedHelpers.callStaticMethod(sX0Cls, "h", e9, voiceId);
            if (stream == null) {
                LogWriter.log(TAG, "stream: y21.x0.h() returned null msgId=" + msgId);
                return false;
            }
            LogWriter.log(TAG, "stream: got stream class=" + stream.getClass().getName());

            // Step 3: 读取PCM → AudioTrack播放
            // 微信语音采样率16kHz,单声道,16bit PCM
            int sampleRate = 16000;
            int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            int bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);

            AudioTrack track = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                audioFormat,
                Math.max(bufferSize, 8192),
                AudioTrack.MODE_STREAM
            );
            track.play();

            byte[] buffer = new byte[4096];
            int totalBytes = 0;
            int read;
            try {
                while ((read = (Integer) XposedHelpers.callMethod(stream, "c", buffer, 0, buffer.length)) > 0) {
                    track.write(buffer, 0, read);
                    totalBytes += read;
                }
            } finally {
                // 异常路径也释放 AudioTrack，避免资源泄漏
                try { track.stop(); } catch (Throwable ignored) {}
                try { track.release(); } catch (Throwable ignored) {}
            }

            LogWriter.log(TAG, "streamDone: msgId=" + msgId + " pcmBytes=" + totalBytes);

            // Step 4: 清理
            try { XposedHelpers.callMethod(stream, "b"); } catch (Throwable ignored) {}

            return totalBytes > 0;

        } catch (Throwable e) {
            LogWriter.log(TAG, "stream err: " + e.getMessage());
            return false;
        }
    }

    // ========== voiceId 提取 ==========

    private static String extractVoiceId(Object e9, long msgId) {
        // 方法0: 优先使用 d1 捕获的 XML voiceid
        try {
            String captured = com.leshao.v3.hook.TtsVoiceSender.getCapturedVoiceId(msgId);
            if (captured != null && !captured.isEmpty()) {
                LogWriter.log(TAG, "voiceId(from xml capture)=[" + trunc(captured, 40) + "] msgId=" + msgId);
                return captured;
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "extractVoiceId: capture err: " + e.getMessage());
        }

        try {
            // 方法1: e9.j() → y21.u0(content) → u0.a()
            String content = (String) XposedHelpers.callMethod(e9, "j");
            if (content != null && sU0Cls != null) {
                LogWriter.log(TAG, "voice content=[" + trunc(content, 60) + "]");
                Object u0Obj = XposedHelpers.newInstance(sU0Cls, content);
                String voiceId = (String) XposedHelpers.callMethod(u0Obj, "a");
                if (voiceId != null && !voiceId.isEmpty()) {
                    LogWriter.log(TAG, "voiceId(from u0)=[" + trunc(voiceId, 40) + "]");
                    return voiceId;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "extractVoiceId: u0 err: " + e.getMessage());
        }

        try {
            // 方法2: e9.j() → 直接解析 "voiceId:duration:flag:\n"
            String content = (String) XposedHelpers.callMethod(e9, "j");
            if (content != null && content.contains("<voicemsg")) {
                String v = extractXmlVoiceId(content);
                if (v != null && !v.isEmpty()) {
                    LogWriter.log(TAG, "voiceId(from xml parse)=[" + trunc(v, 40) + "]");
                    return v;
                }
            }
            if (content != null) {
                int colon = content.indexOf(':');
                if (colon > 0) {
                    String voiceId = content.substring(0, colon);
                    LogWriter.log(TAG, "voiceId(from parse)=[" + trunc(voiceId, 40) + "]");
                    return voiceId;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "extractVoiceId: parse err: " + e.getMessage());
        }

        try {
            // 方法3: e9.I0() 内容
            String content = (String) XposedHelpers.callMethod(e9, "I0");
            LogWriter.log(TAG, "voice I0 content=[" + trunc(content, 60) + "]");
        } catch (Throwable ignored) {}

        return null;
    }

    private static String extractXmlVoiceId(String xml) {
        try {
            String key = "voiceid=\"";
            int idx = xml.indexOf(key);
            if (idx < 0) return null;
            int start = idx + key.length();
            int end = xml.indexOf('"', start);
            if (end < 0) return null;
            return xml.substring(start, end);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String trunc(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ========== 方案B: SilkDecoder SILK→WAV→MediaPlayer ==========

    private static boolean playBackground(Object msg, String talker, long msgId) {
        try {
            String path = getVoicePath(msg, msgId);
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
            final String fWav = wavPath;
            MediaPlayer mp = new MediaPlayer();
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
            try {
                mp.setDataSource(wavPath);
                mp.prepare();
            } catch (Throwable e) {
                try { mp.release(); } catch (Throwable ignored) {}
                throw e;
            }
            mp.start();
            LogWriter.log(TAG, "bgStarted: msgId=" + msgId);

            return true;

        } catch (Throwable e) {
            LogWriter.log(TAG, "bg err: " + e.getMessage());
            return false;
        }
    }

    // ========== 路径解析 ==========

    private static String getVoicePath(Object msg, long msgId) {
        try {
            String capturedCid = msgId != 0 ? com.leshao.v3.hook.TtsVoiceSender.getCapturedVoiceCid(msgId) : null;
            if (capturedCid != null && !capturedCid.isEmpty()) {
                String path = resolveClientMsgIdPath(capturedCid);
                if (path != null) return path;
            }
        } catch (Throwable ignored) {}

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

        String clean = normalizeCid(cid);
        if (clean == null) return null;

        try {
            Class<?> h1Cls = com.leshao.v3.hook.VersionCompat.findPlayThreadClass(sClassLoader);
            if (h1Cls != null) {
                Object path = XposedHelpers.callStaticMethod(h1Cls, "d",
                        sVoice2Dir.endsWith("/") ? sVoice2Dir : sVoice2Dir + "/",
                        "msg_", clean, ".amr", 2, true);
                if (path instanceof String && !((String) path).isEmpty() && new File((String) path).exists()) {
                    return (String) path;
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "resolveClientMsgIdPath h1.d err: " + t.getMessage());
        }

        String md5 = md5(clean);
        if (md5 == null || md5.length() < 4) return null;
        String path = sVoice2Dir + "/" + md5.substring(0, 2) + "/" + md5.substring(2, 4) + "/msg_" + clean + ".amr";
        if (new File(path).exists()) return path;
        return null;
    }

    private static String normalizeCid(String cid) {
        if (cid == null || cid.isEmpty()) return null;
        String v = cid;
        int slash = v.lastIndexOf('/');
        if (slash >= 0) v = v.substring(slash + 1);
        if (v.endsWith(".amr")) v = v.substring(0, v.length() - 4);
        if (v.startsWith("msg_")) v = v.substring(4);
        if (v.isEmpty() || v.contains("/")) return null;
        return v;
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

    private static volatile boolean sWorkerRunning = false;

    private static void ensureWorker() {
        synchronized (VoiceAutoPlay.class) {
            if (sWorkerRunning) return;
            sWorkerRunning = true;
        }
        Thread w = new Thread(() -> {
            try {
                while (true) {
                    PendingVoiceMsg pvm = sPendingQueue.poll();
                    if (pvm == null) {
                        synchronized (VoiceAutoPlay.class) { sWorkerRunning = false; }
                        return;
                    }
                    try {
                        playOne(pvm);
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "worker play err: " + e.getMessage());
                    }
                }
            } catch (Throwable e) {
                LogWriter.log(TAG, "worker died: " + e.getMessage());
                synchronized (VoiceAutoPlay.class) { sWorkerRunning = false; }
            }
        }, "VAP-worker");
        w.setDaemon(true);
        w.start();
    }

    private static void playOne(PendingVoiceMsg pvm) {
        try {
            long waitStart = System.currentTimeMillis();
            while (TTSBroadcaster.hasPendingSpeak() && (System.currentTimeMillis() - waitStart) < 8000) {
                try { Thread.sleep(150); } catch (InterruptedException ignored) { break; }
            }
            if (TTSBroadcaster.hasPendingSpeak()) {
                LogWriter.log(TAG, "tts wait timeout id=" + pvm.msgId);
            }

            boolean streamOk = playViaWxStream(pvm.msg, pvm.talker, pvm.msgId);
            if (streamOk) {
                LogWriter.log(TAG, "DONE(stream) id=" + pvm.msgId);
                return;
            }

            boolean bgOk = playBackground(pvm.msg, pvm.talker, pvm.msgId);
            if (bgOk) {
                LogWriter.log(TAG, "DONE(bg) id=" + pvm.msgId);
                return;
            }

            // 方案B'：等待语音文件下载到 voice2 后再解码播放（最多 ~5 秒）
            long fileWaitStart = System.currentTimeMillis();
            boolean bgWaitOk = false;
            while ((System.currentTimeMillis() - fileWaitStart) < 5000) {
                try { Thread.sleep(250); } catch (InterruptedException ignored) { break; }
                bgWaitOk = playBackground(pvm.msg, pvm.talker, pvm.msgId);
                if (bgWaitOk) break;
            }
            if (bgWaitOk) {
                LogWriter.log(TAG, "DONE(bg-wait) id=" + pvm.msgId);
                return;
            }

            // 方案C: 聊天内播放当前这条
            if (sCurrentSo != null) {
                sHandler.post(() -> {
                    playInChat(pvm);
                });
            } else {
                LogWriter.log(TAG, "DONE(fail, no player) id=" + pvm.msgId);
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "playOne err: " + e.getMessage());
        }
    }

    private static void playInChat(PendingVoiceMsg pvm) {
        try {
            if (sCurrentPlayer == null && sCurrentSo != null) {
                sCurrentPlayer = getPlayer(sCurrentSo);
            }
            if (sCurrentPlayer == null) {
                LogWriter.log(TAG, "playInChat: player null id=" + pvm.msgId);
                return;
            }
            XposedHelpers.callMethod(sCurrentPlayer, "I", pvm.msg, false);
            LogWriter.log(TAG, "PLAY(inchat) id=" + pvm.msgId);
        } catch (Throwable e) {
            LogWriter.log(TAG, "playInChat err id=" + pvm.msgId + " " + e.getMessage());
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
        return dequeue(talker, 0);
    }

    private static List<PendingVoiceMsg> dequeue(String talker, int max) {
        List<PendingVoiceMsg> result = new ArrayList<>();
        Iterator<PendingVoiceMsg> it = sPendingQueue.iterator();
        while (it.hasNext()) {
            PendingVoiceMsg pvm = it.next();
            if (talker == null || pvm.talker == null || talker.equals(pvm.talker)) {
                it.remove();
                result.add(pvm);
                if (max > 0 && result.size() >= max) break;
            }
        }
        return result;
    }

    // ========== Voice2 目录 ==========

    private static void findVoice2Dir() {
        try {
            int currentUser = Process.myUid() / 100000;
            java.util.List<String> roots = new java.util.ArrayList<>();
            roots.add("/data/user/" + currentUser + "/com.tencent.mm/MicroMsg");
            if (currentUser != 0) {
                roots.add("/data/user/0/com.tencent.mm/MicroMsg");
            }
            File[] userDirs = new File("/data/user").listFiles();
            if (userDirs != null) {
                for (File u : userDirs) {
                    if (!u.isDirectory()) continue;
                    try {
                        int id = Integer.parseInt(u.getName());
                        if (id == currentUser || (currentUser != 0 && id == 0)) continue;
                    } catch (Throwable ignored) { continue; }
                    roots.add(u.getAbsolutePath() + "/com.tencent.mm/MicroMsg");
                }
            }
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
