package com.leshao.v3.hook;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 语音消息自动播放 v50 (8.0.78 适配)
 * - 方案A(首选): 等语音文件写入 voice2 后按格式直接播放 (AMR-NB 系统 MediaPlayer / SILK SilkDecoder)
 * - 方案B(回退): so.y()→p 聊天内播放
 */
public class VoiceAutoPlay {

    private static final String TAG = "VAP";

    // v986: 这几个状态在主线程(x0 hook)/MessageHook/后台 worker 之间共享, 必须 volatile 保证可见性。
    private static volatile boolean sEnabled = true;
    private static volatile long sLastPlayedMsgId = -1L;
    private static volatile long sX0HandledMsgId = -1L;

    private static ClassLoader sClassLoader;
    private static Handler sHandler;

    private static final ConcurrentLinkedQueue<PendingVoiceMsg> sPendingQueue = new ConcurrentLinkedQueue<>();

    private static Class<?> sK0Class;
    private static Class<?> sSoClass;
    private static String sVoice2Dir;

    // 8.0.78(3180) 自动播放调度器: com.tencent.mm.ui.chatting.x0 (日志 tag MicroMsg.AutoPlay)
    private static Class<?> sX0Class;
    private static volatile boolean sX0Hooked;
    private static volatile Object sX0Instance;

    // v1135: 模块兜底播放器是否正在出声, 用于避免与原生 x0 双播。
    private static volatile boolean sModulePlaying;

    private static volatile Object sCurrentSo;
    private static volatile Object sCurrentPlayer;

    // v1137: 兜底 SILK→WAV 播放器的静态强引用。旧实现仅用局部变量持有 MediaPlayer, 播放中
    // 一旦被 GC 回收就会中途无声停止; 且 completion/error 回调未统一清理会导致 sModulePlaying
    // 永久为 true。改为在主线程创建/起播 + 静态强引用 + 看门狗兜底清理。
    private static volatile MediaPlayer sFallbackPlayer;
    private static volatile android.media.AudioManager sAudioManager;
    private static volatile android.media.AudioManager.OnAudioFocusChangeListener sFocusListener;

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

        // v955: 3180 实证 k0 存活于 com.tencent.mm.app.k0(model.k0/k0 已改名), 优先现行包名
        try { sK0Class = XposedHelpers.findClass("com.tencent.mm.app.k0", cl); }
        catch (Throwable t) {
            try { sK0Class = XposedHelpers.findClass("com.tencent.mm.model.k0", cl); }
            catch (Throwable t2) {
                try { sK0Class = XposedHelpers.findClass("com.tencent.mm.k0", cl); }
                catch (Throwable t3) { sK0Class = null; }
            }
        }
        if (sK0Class == null) {
            LogWriter.log(TAG, "k0 class NOT found in any of the 4 alternatives");
        }
        try { sSoClass = XposedHelpers.findClass("com.tencent.mm.ui.chatting.component.so", cl); }
        catch (Throwable t) { LogWriter.log(TAG, "so class NOT found: " + t.getMessage()); }

        // 8.0.78(3180): 微信旧 CDN 流式类 y21.u0/y21.x0/y21.j 已全部失效, 不再加载。
        // 自动播放改为等语音文件写入 voice2 后按格式直接播放 (AMR-NB / SILK)。

        LogWriter.log(TAG, "init: k0=" + (sK0Class != null) + " so=" + (sSoClass != null));

        findVoice2Dir();
        hookVoiceComponent(cl);
        hookVolumeKeyPause(cl);
        hookX0AutoPlay(cl);
    }

    // ========== 8.0.78(3180) x0.q(e9) 自动播放调度器 hook ==========
    // 新文档: 自动播放收敛在 com.tencent.mm.ui.chatting.x0 (tag MicroMsg.AutoPlay)。
    // 方案A: hook x0.q(Lcom/tencent/mm/storage/e9;)V 放开内部条件判断, 让微信原生自动播放。

    private static void hookX0AutoPlay(ClassLoader cl) {
        try {
            sX0Class = XposedHelpers.findClass("com.tencent.mm.ui.chatting.x0", cl);
        } catch (Throwable t) {
            LogWriter.log(TAG, "x0 class NOT found: " + t.getMessage());
            return;
        }
        try {
            Class<?> e9Class = XposedHelpers.findClass("com.tencent.mm.storage.e9", cl);
            java.lang.reflect.Method qMethod = sX0Class.getDeclaredMethod("q", e9Class);
            XposedBridge.hookMethod(qMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        if (self != null) sX0Instance = self;
                        Object e9 = param.args[0];
                        if (e9 == null) return;

                        String talker = null;
                        try { talker = (String) XposedHelpers.callMethod(e9, "N0"); }
                        catch (Throwable ignored) {}
                        if (talker == null) {
                            try { talker = (String) XposedHelpers.getObjectField(e9, "field_talker"); }
                            catch (Throwable ignored) {}
                        }
                        long msgId = -1L;
                        try { msgId = (Long) XposedHelpers.callMethod(e9, "getMsgId"); }
                        catch (Throwable t) {
                            try { msgId = (Long) XposedHelpers.callMethod(e9, "H0"); }
                            catch (Throwable t2) { msgId = -1L; }
                        }

                        LogWriter.log(TAG, "x0.q(before) id=" + msgId + " talker=" + talker
                                + " enabled=" + sEnabled + " play=" + shouldAutoPlay(talker));

                        if (!sEnabled || !shouldAutoPlay(talker)) return;
                        if (msgId == sX0HandledMsgId) {
                            LogWriter.log(TAG, "x0.q(before) skip handled id=" + msgId);
                            return;
                        }

                        // 放开微信内部抑制标记 g (false = 不抑制), 允许微信原生自动播放
                        try {
                            XposedHelpers.setBooleanField(self, "g", false);
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "x0.q(before) set g fail: " + t.getMessage());
                        }
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "x0.q(before) err: " + e.getMessage());
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        Object e9 = param.args[0];
                        if (e9 == null) return;

                        String talker = null;
                        try { talker = (String) XposedHelpers.callMethod(e9, "N0"); }
                        catch (Throwable ignored) {}
                        if (talker == null) {
                            try { talker = (String) XposedHelpers.getObjectField(e9, "field_talker"); }
                            catch (Throwable ignored) {}
                        }
                        long msgId = -1L;
                        try { msgId = (Long) XposedHelpers.callMethod(e9, "getMsgId"); }
                        catch (Throwable t) {
                            try { msgId = (Long) XposedHelpers.callMethod(e9, "H0"); }
                            catch (Throwable t2) { msgId = -1L; }
                        }
                        if (!sEnabled || !shouldAutoPlay(talker)) return;
                        if (msgId == sX0HandledMsgId) return;

                        // 微信原生 q() 可能因 r.i / h9.e().n / 熄屏等条件跳过播放,
                        // 这里强制入队 + 播放队首兜底。
                        boolean playing = false;
                        try { playing = (Boolean) XposedHelpers.callMethod(self, "o"); }
                        catch (Throwable t) {
                            try { playing = (Boolean) XposedHelpers.callMethod(self, "isPlaying"); }
                            catch (Throwable t2) { playing = false; }
                        }
                        if (playing) {
                            LogWriter.log(TAG, "x0.q(after) wx already playing id=" + msgId);
                            sX0HandledMsgId = msgId;
                            sLastPlayedMsgId = msgId;
                            return;
                        }

                        // v1135: TTS 播报("某人说:")未结束时不得抢播原生语音, 否则两者抢音频焦点
                        // 会导致"语音只播一小段就停"。延迟到 TTS 播报结束后再强制原生播放。
                        if (TTSBroadcaster.hasPendingSpeak()) {
                            LogWriter.log(TAG, "x0.q(after) defer for TTS id=" + msgId);
                            scheduleDeferredX0Play(self, e9, msgId);
                            return;
                        }

                        forceX0Play(self, e9, msgId);
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "x0.q(after) err: " + e.getMessage());
                    }
                }
            });
            sX0Hooked = true;
            LogWriter.log(TAG, "x0.q(e9) auto-play hook OK");
        } catch (Throwable e) {
            LogWriter.log(TAG, "x0.q(e9) hook fail: " + e.getMessage());
        }
    }

    /** v1135: 强制微信原生 x0 播放指定语音 (清 g 抑制位 → f 入队 → t 起播)。 */
    private static void forceX0Play(Object self, Object e9, long msgId) {
        try {
            if (self == null || e9 == null) return;
            if (msgId != -1L && msgId == sX0HandledMsgId) return;
            if (sModulePlaying) {
                // 模块兜底播放器已在出声, 不要原生再抢一份。
                LogWriter.log(TAG, "forceX0Play: module already playing, skip id=" + msgId);
                return;
            }
            boolean playing = false;
            try { playing = (Boolean) XposedHelpers.callMethod(self, "o"); }
            catch (Throwable t) {
                try { playing = (Boolean) XposedHelpers.callMethod(self, "isPlaying"); }
                catch (Throwable t2) { playing = false; }
            }
            if (playing) {
                sX0HandledMsgId = msgId;
                sLastPlayedMsgId = msgId;
                LogWriter.log(TAG, "forceX0Play: already playing id=" + msgId);
                return;
            }
            XposedHelpers.setBooleanField(self, "g", false);
            XposedHelpers.callMethod(self, "f", e9);
            XposedHelpers.callMethod(self, "t");
            sX0HandledMsgId = msgId;
            sLastPlayedMsgId = msgId;
            LogWriter.log(TAG, "forced f+t id=" + msgId);
        } catch (Throwable t) {
            LogWriter.log(TAG, "forceX0Play fail: " + t.getMessage());
        }
    }

    /** v1135: TTS 播报期间挂起原生强制播放, 等 TTS 结束后在主线程补播, 避免抢音频焦点。 */
    private static void scheduleDeferredX0Play(final Object self, final Object e9, final long msgId) {
        if (sHandler == null) return;
        sHandler.post(new Runnable() {
            private int tries = 0;

            @Override
            public void run() {
                if (TTSBroadcaster.hasPendingSpeak() && tries++ < 150) {
                    sHandler.postDelayed(this, 200);
                    return;
                }
                if (msgId != -1L && msgId == sX0HandledMsgId) return;
                LogWriter.log(TAG, "deferred x0 play now id=" + msgId);
                forceX0Play(self, e9, msgId);
            }
        });
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
            if (msgId == sX0HandledMsgId) {
                android.util.Log.e(TAG, ">>> onVoiceMsg: X0-HANDLED msgId=" + msgId);
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

    // ========== voiceId 提取 ==========
    // v986: 清理死成员 —— playViaWxStream(空占位)、extractVoiceId/extractXmlVoiceId(全仓无引用)
    // 已删除; getVoicePath 等现行路径不再依赖它们。

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

            // v1136: 微信语音落盘形态为 [1字节flag][#!SILK_V3][payload] 或 [1字节flag][#!AMR\n][payload]
            // (个别版本无前导 flag)。旧实现只按 hdr[0]=='#' 判 AMR, 微信 SILK 文件因此被判为非 AMR,
            // 又被直接送进要求"文件从 #!SILK_V3 开始"的 native 解码器, 前导 flag 字节导致解码失败。
            byte[] hdr = new byte[18];
            int hn = 0;
            try {
                java.io.InputStream in = new java.io.FileInputStream(f);
                hn = in.read(hdr);
                in.close();
            } catch (Throwable ignored) {}

            int skip = 0;
            boolean isAmr = false;
            boolean isSilk = false;
            if (startsWith(hdr, hn, 0, "#!AMR")) { isAmr = true; skip = 0; }
            else if (startsWith(hdr, hn, 1, "#!AMR")) { isAmr = true; skip = 1; }
            else if (startsWith(hdr, hn, 0, "#!SILK_V3")) { isSilk = true; skip = 0; }
            else if (startsWith(hdr, hn, 1, "#!SILK_V3")) { isSilk = true; skip = 1; }

            if (!isAmr && !isSilk) {
                LogWriter.log(TAG, "bg: unknown voice format header=" + hex(hdr, hn) + " msgId=" + msgId);
                return false;
            }

            String srcPath = path;
            String wavPath = null;
            String normPath = null;
            File normFile = null;
            if (skip > 0) {
                // 去掉微信前导 flag 字节, 归一化成标准语音文件后再交给解码器/播放器。
                normPath = path + ".norm";
                normFile = new File(normPath);
                if (normFile.exists()) normFile.delete();
                if (!copySkipBytes(f, normFile, skip)) {
                    LogWriter.log(TAG, "bg: normalize failed msgId=" + msgId);
                    return false;
                }
                srcPath = normPath;
            }

            if (isSilk) {
                // SILK → WAV
                wavPath = path + ".dec.wav";
                File wavFile = new File(wavPath);
                if (wavFile.exists()) wavFile.delete();

                boolean decOk = false;
                try {
                    decOk = xyz.xxin.silkdecoder.SilkDecoder.decodeToWav(srcPath, wavPath);
                } catch (Throwable dt) {
                    LogWriter.log(TAG, "bg: decode throw " + dt.getMessage());
                }
                if (!decOk || !wavFile.exists() || wavFile.length() == 0) {
                    LogWriter.log(TAG, "bg: silk decode failed ok=" + decOk + " msgId=" + msgId);
                    if (normFile != null) normFile.delete();
                    return false;
                }
                srcPath = wavPath;
                LogWriter.log(TAG, "bg: silk decoded wav size=" + wavFile.length() + " msgId=" + msgId);
            }

            final int voiceLenMs = getVoiceLengthMs(msg);
            LogWriter.log(TAG, "bg: voicelength=" + voiceLenMs + " msgId=" + msgId);

            // v1137: 播放器改为主线程创建/起播 + 静态强引用 (防 GC 回收/无 Looper 回调丢失),
            // completion/error/看门狗 统一清理并归还音频焦点。
            // 注意: 原始语音 AMR 文件属于微信消息数据, 播放后绝不可删除(旧实现会误删导致无法重播)。
            final String fSrc = srcPath;
            final String fWav = wavPath;
            final String fNorm = normPath;
            final long mid = msgId;
            Runnable start = () -> startFallbackPlayback(fSrc, fWav, fNorm, mid, voiceLenMs);
            if (sHandler != null) sHandler.post(start);
            else start.run();

            return true;

        } catch (Throwable e) {
            LogWriter.log(TAG, "bg err: " + e.getMessage());
            return false;
        }
    }

    // ========== 兜底播放器生命周期 (v1137) ==========

    /** v1137: 在主线程创建并起播兜底 MediaPlayer, 持有静态强引用防止被 GC 回收。 */
    private static void startFallbackPlayback(String src, String wav, String norm, long msgId, int voiceLenMs) {
        try {
            releaseFallbackPlayer();
            MediaPlayer mp = new MediaPlayer();
            sFallbackPlayer = mp;
            mp.setOnCompletionListener(m -> {
                LogWriter.log(TAG, "bgDone: msgId=" + msgId);
                finishFallbackPlayback(m, wav, norm);
            });
            mp.setOnErrorListener((m, what, extra) -> {
                LogWriter.log(TAG, "bgErr: msgId=" + msgId + " what=" + what);
                finishFallbackPlayback(m, wav, norm);
                return true;
            });
            try { mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC); }
            catch (Throwable ignored) {}
            mp.setDataSource(src);
            mp.prepare();
            int dur = 0;
            try { dur = mp.getDuration(); } catch (Throwable ignored) {}
            LogWriter.log(TAG, "bgReady: msgId=" + msgId + " durationMs=" + dur);
            requestAudioFocus();
            sModulePlaying = true;
            mp.start();
            LogWriter.log(TAG, "bgStarted: msgId=" + msgId);
            // 看门狗: completion/error 偶发不回调时兜底清理, 避免 sModulePlaying 永久为 true。
            if (sHandler != null && dur > 0) {
                final MediaPlayer fmp = mp;
                long timeout = dur + 2500L;
                // v1137: 解码产物时长远超消息真实时长时(解码器多解出尾部数据), 按真实时长截断播放。
                if (voiceLenMs > 0 && (long) dur > (long) voiceLenMs * 2L + 3000L) {
                    timeout = voiceLenMs + 500L;
                    LogWriter.log(TAG, "bg: decoded " + dur + "ms >> voicelength " + voiceLenMs
                            + "ms, cap playback msgId=" + msgId);
                }
                final long relMs = timeout;
                sHandler.postDelayed(() -> {
                    if (sFallbackPlayer == fmp) {
                        LogWriter.log(TAG, "bg watchdog stop msgId=" + msgId);
                        finishFallbackPlayback(fmp, wav, norm);
                    }
                }, relMs);
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "startFallbackPlayback err: " + e.getMessage());
            sModulePlaying = false;
            if (wav != null) try { new File(wav).delete(); } catch (Throwable ignored) {}
            if (norm != null) try { new File(norm).delete(); } catch (Throwable ignored) {}
        }
    }

    /** v1137: 释放兜底播放器, 清理临时解码文件并归还音频焦点。 */
    private static void finishFallbackPlayback(MediaPlayer mp, String wav, String norm) {
        try { mp.stop(); } catch (Throwable ignored) {}
        try { mp.release(); } catch (Throwable ignored) {}
        if (sFallbackPlayer == mp) sFallbackPlayer = null;
        sModulePlaying = false;
        abandonAudioFocus();
        if (wav != null) try { new File(wav).delete(); } catch (Throwable ignored) {}
        if (norm != null) try { new File(norm).delete(); } catch (Throwable ignored) {}
    }

    /** v1137: 起播新语音前释放上一条, 避免并发多实例抢音频焦点。 */
    private static void releaseFallbackPlayer() {
        MediaPlayer mp = sFallbackPlayer;
        if (mp != null) {
            try { mp.stop(); } catch (Throwable ignored) {}
            try { mp.release(); } catch (Throwable ignored) {}
            sFallbackPlayer = null;
        }
        sModulePlaying = false;
    }

    private static void requestAudioFocus() {
        try {
            android.content.Context ctx = com.leshao.v3.ContextManager.getAppContext();
            if (ctx == null) return;
            android.media.AudioManager am = (android.media.AudioManager)
                    ctx.getSystemService(android.content.Context.AUDIO_SERVICE);
            if (am == null) return;
            sAudioManager = am;
            if (sFocusListener == null) {
                sFocusListener = focusChange -> {};
            }
            am.requestAudioFocus(sFocusListener,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        } catch (Throwable ignored) {}
    }

    private static void abandonAudioFocus() {
        try {
            if (sAudioManager != null && sFocusListener != null) {
                sAudioManager.abandonAudioFocus(sFocusListener);
            }
        } catch (Throwable ignored) {}
    }

    /** v1137: 从消息 XML 读取真实语音时长(ms), 用于诊断解码产物是否异常; 读不到返回 -1。 */
    private static int getVoiceLengthMs(Object msg) {
        try {
            Object content = XposedHelpers.getObjectField(msg, "field_content");
            if (content instanceof String) {
                String xml = (String) content;
                for (String attr : new String[]{"voicelength", "length"}) {
                    int idx = xml.indexOf(attr + "=\"");
                    if (idx >= 0) {
                        idx += attr.length() + 2;
                        int end = xml.indexOf("\"", idx);
                        if (end > idx) return Integer.parseInt(xml.substring(idx, end));
                    }
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    // ========== 路径解析 ==========

    /** v1136: 判断 hdr 自 off 起是否匹配 ASCII 前缀 (越界安全)。 */
    private static boolean startsWith(byte[] hdr, int len, int off, String prefix) {
        if (hdr == null || prefix == null) return false;
        if (off < 0 || len < off + prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if ((hdr[off + i] & 0xFF) != (prefix.charAt(i) & 0xFF)) return false;
        }
        return true;
    }

    /** v1136: 文件头转十六进制, 用于诊断未知语音格式。 */
    private static String hex(byte[] b, int len) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(len > 0 ? len : b.length, b.length);
        for (int i = 0; i < n && i < 16; i++) {
            sb.append(String.format("%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }

    /** v1136: 跳过前 skip 字节复制文件 (去除微信前导 flag 字节, 归一化为标准语音文件)。 */
    private static boolean copySkipBytes(File src, File dst, int skip) {
        java.io.FileInputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            in = new java.io.FileInputStream(src);
            long skipped = 0;
            while (skipped < skip) {
                long s = in.skip(skip - skipped);
                if (s <= 0) {
                    if (in.read() < 0) break;
                    skipped++;
                } else {
                    skipped += s;
                }
            }
            out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return dst.exists() && dst.length() > 0;
        } catch (Throwable t) {
            LogWriter.log(TAG, "copySkipBytes err: " + t.getMessage());
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }

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

        // 8.0.78(3180): e9.y0()(clientmsgid) 已删除, 补充可靠兜底:
        // j()=field_content(格式 clientmsgid:时长:...) 冒号前缀 → resolveClientMsgIdPath
        try {
            String content = (String) XposedHelpers.callMethod(msg, "j");
            if (content != null && !content.isEmpty()) {
                int colon = content.indexOf(':');
                if (colon > 0) {
                    String cid = content.substring(0, colon).trim();
                    if (!cid.isEmpty()) {
                        String path = resolveClientMsgIdPath(cid);
                        if (path != null) {
                            LogWriter.log(TAG, "getVoicePath: via j() content cid=" + trunc(cid, 30));
                            return path;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "getVoicePath: j() err: " + e.getMessage());
        }

        // x0()=field_imgPath 语音文件名 → 反查 voice2 目录
        try {
            String imgPath = (String) XposedHelpers.callMethod(msg, "x0");
            if (imgPath != null && !imgPath.isEmpty()) {
                String name = imgPath;
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                if (name.startsWith("msg_")) name = name.substring(4);
                if (name.endsWith(".amr")) name = name.substring(0, name.length() - 4);
                if (!name.isEmpty()) {
                    String path = resolveClientMsgIdPath(name);
                    if (path != null) {
                        LogWriter.log(TAG, "getVoicePath: via x0() img " + trunc(imgPath, 40));
                        return path;
                    }
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "getVoicePath: x0() err: " + e.getMessage());
        }
        return null;
    }

    private static String resolveClientMsgIdPath(String cid) {
        if (sVoice2Dir == null) return null;

        String clean = normalizeCid(cid);
        if (clean == null) return null;

        String md5 = md5(clean);
        if (md5 == null || md5.length() < 4) return null;
        String path = sVoice2Dir + "/" + md5.substring(0, 2) + "/" + md5.substring(2, 4) + "/msg_" + clean + ".amr";
        if (new File(path).exists()) return path;
        // 8.0.78 下载语音分组: dir/<base>_<x>.amr, x = voice2 长度/分组反推
        String dir = sVoice2Dir + "/" + md5.substring(0, 2) + "/" + md5.substring(2, 4);
        File[] group = new File(dir).listFiles();
        if (group != null) {
            String prefix = "msg_" + clean + "_";
            for (File f : group) {
                if (f.getName().startsWith(prefix) && f.getName().endsWith(".amr")) {
                    return f.getAbsolutePath();
                }
            }
        }
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
                        // v986: 必须在锁内复查队列再退出。否则 poll 返回空与 offer 之间的
                        // lost-wakeup 会让生产者看到 sWorkerRunning=true 而不启动新 worker, 消息永久滞留。
                        synchronized (VoiceAutoPlay.class) {
                            if (sPendingQueue.isEmpty()) {
                                sWorkerRunning = false;
                                return;
                            }
                        }
                        continue;
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

    /** v1134: 回主线程查询 x0 自动播放器当前是否真的在播放 (o()/isPlaying)。 */
    private static boolean x0IsPlaying() {
        final Object inst = sX0Instance;
        if (inst == null || sHandler == null) return false;
        final AtomicBoolean playing = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            sHandler.post(() -> {
                try {
                    Object r = null;
                    try { r = XposedHelpers.callMethod(inst, "o"); }
                    catch (Throwable t) {
                        try { r = XposedHelpers.callMethod(inst, "isPlaying"); }
                        catch (Throwable ignored) {}
                    }
                    if (r instanceof Boolean) playing.set((Boolean) r);
                } finally {
                    latch.countDown();
                }
            });
            latch.await(400, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {}
        return playing.get();
    }

    private static void playOne(PendingVoiceMsg pvm) {
        try {
            // v1135: 先等 TTS 播报结束再决定播放, 避免语音与 TTS 抢音频焦点导致"播一会就停"。
            long waitStart = System.currentTimeMillis();
            while (TTSBroadcaster.hasPendingSpeak() && (System.currentTimeMillis() - waitStart) < 15000) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) { break; }
            }
            if (TTSBroadcaster.hasPendingSpeak()) {
                LogWriter.log(TAG, "tts wait timeout id=" + pvm.msgId);
            }

            // 8.0.78(3180): 若 x0.q(e9) 调度器 hook 生效, 微信会原生自动播放。
            // hook 侧在 TTS 播报期间挂起强制播放, TTS 结束后补播; 这里等它确认真的出声, 避免重复发声。
            if (sX0Hooked) {
                // v1137: x0.q 在消息到达同一主线程派发阶段就会触发; 等到 TTS 播完时该标记早已确定,
                // 无需再干等 2.5s(旧值造成 TTS 结束后数秒才起播)。仅短暂确认即可。
                long x0WaitStart = System.currentTimeMillis();
                while ((System.currentTimeMillis() - x0WaitStart) < 600) {
                    if (sX0HandledMsgId == pvm.msgId && x0IsPlaying()) {
                        LogWriter.log(TAG, "handled by x0 native, skip id=" + pvm.msgId);
                        return;
                    }
                    try { Thread.sleep(50); } catch (InterruptedException ignored) { break; }
                }
                if (sX0HandledMsgId == pvm.msgId) {
                    LogWriter.log(TAG, "x0 marked handled but not playing, fallback id=" + pvm.msgId);
                }
            }

            // 8.0.78(3180): 旧 y21.x0 CDN 流式链路已失效。
            // 兜底: 等语音文件写入 voice2 后直接用系统 MediaPlayer 播放 (AMR-NB)。
            long fileWaitStart = System.currentTimeMillis();
            boolean bgOk = false;
            while ((System.currentTimeMillis() - fileWaitStart) < 5000) {
                try { Thread.sleep(250); } catch (InterruptedException ignored) { break; }
                bgOk = playBackground(pvm.msg, pvm.talker, pvm.msgId);
                if (bgOk) break;
            }
            if (bgOk) {
                LogWriter.log(TAG, "DONE(bg) id=" + pvm.msgId);
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
