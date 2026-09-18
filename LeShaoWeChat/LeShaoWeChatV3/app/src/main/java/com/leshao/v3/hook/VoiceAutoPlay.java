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

    private static boolean sEnabled = true;
    private static long sLastPlayedMsgId = -1L;
    private static long sX0HandledMsgId = -1L;

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

                        try {
                            XposedHelpers.callMethod(self, "f", e9);
                            XposedHelpers.callMethod(self, "t");
                            sX0HandledMsgId = msgId;
                            sLastPlayedMsgId = msgId;
                            LogWriter.log(TAG, "x0.q(after) forced f+t id=" + msgId + " talker=" + talker);
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "x0.q(after) force f/t fail: " + t.getMessage());
                        }
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

    // ========== 方案A: 微信CDN流式API ==========

    /** 8.0.78(3180): y21.x0 CDN 流式链路已失效, 此方法不再使用, 保留空实现占位 */
    private static boolean playViaWxStream(Object e9, String talker, long msgId) {
        return false;
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
            // 方法1: e9.j() → 直接解析 XML voiceid (8.0.78 旧 y21.u0 已失效)
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

             // 8.0.78: 语音为 AMR (系统 MediaPlayer 原生支持 AMR-NB #!AMR\n 与 AMR-WB #!AMR-WB\n);
             // 仅当文件头非 AMR (旧 SILK) 时才走 SilkDecoder 转 WAV。
             boolean isAmr = false;
             try {
                 @SuppressWarnings("resource")
                 java.io.InputStream in = new java.io.FileInputStream(f);
                 byte[] hdr = new byte[6];
                 int n = in.read(hdr);
                 in.close();
                 isAmr = n >= 6 && hdr[0] == '#' && hdr[1] == '!'
                     && hdr[2] == 'A' && hdr[3] == 'M' && hdr[4] == 'R'
                     && (hdr[5] == '\n' || hdr[5] == '-');
             } catch (Throwable ignored) {}

            String srcPath = path;
            String wavPath = null;
            if (!isAmr) {
                // SILK → WAV
                wavPath = path + ".dec.wav";
                File wavFile = new File(wavPath);
                if (wavFile.exists()) wavFile.delete();

                xyz.xxin.silkdecoder.SilkDecoder.decodeToWav(path, wavPath);
                if (!wavFile.exists() || wavFile.length() == 0) {
                    LogWriter.log(TAG, "bg: decode failed, wav not created");
                    return false;
                }
                srcPath = wavPath;
                LogWriter.log(TAG, "bg: wav=" + wavPath + " size=" + wavFile.length());
            }

            // MediaPlayer 播放
            final String fWav = wavPath;
            final String fAmr = isAmr ? path : null;
            MediaPlayer mp = new MediaPlayer();
            mp.setOnCompletionListener(m -> {
                LogWriter.log(TAG, "bgDone: msgId=" + msgId);
                m.release();
                if (fWav != null) new File(fWav).delete();
                if (fAmr != null) new File(fAmr).delete();
            });
            mp.setOnErrorListener((m, what, extra) -> {
                LogWriter.log(TAG, "bgErr: msgId=" + msgId + " what=" + what);
                m.release();
                if (fWav != null) new File(fWav).delete();
                if (fAmr != null) new File(fAmr).delete();
                return true;
            });
            try {
                mp.setDataSource(srcPath);
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
            // 8.0.78(3180): 若 x0.q(e9) 调度器 hook 生效, 微信会原生自动播放。
            // 等待一小段, 若该消息已由 x0 原生播放则跳过, 避免重复发声。
            if (sX0Hooked) {
                long x0WaitStart = System.currentTimeMillis();
                while ((System.currentTimeMillis() - x0WaitStart) < 1500) {
                    if (sX0HandledMsgId == pvm.msgId) {
                        LogWriter.log(TAG, "handled by x0 native, skip id=" + pvm.msgId);
                        return;
                    }
                    try { Thread.sleep(100); } catch (InterruptedException ignored) { break; }
                }
            }

            long waitStart = System.currentTimeMillis();
            while (TTSBroadcaster.hasPendingSpeak() && (System.currentTimeMillis() - waitStart) < 8000) {
                try { Thread.sleep(150); } catch (InterruptedException ignored) { break; }
            }
            if (TTSBroadcaster.hasPendingSpeak()) {
                LogWriter.log(TAG, "tts wait timeout id=" + pvm.msgId);
            }

            // 8.0.78(3180): 旧 y21.x0 CDN 流式链路已失效。
            // 首选: 等待语音文件写入 voice2 后直接播放 (AMR-NB 由系统 MediaPlayer 解码)
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
