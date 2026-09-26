package com.leshao.v3.hook;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Process;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.leshao.v3.ContextManager;
import com.leshao.v3.ChatFooterLongPressMenu;
import com.leshao.v3.LogWriter;
import com.leshao.v3.db.VoiceHistoryDbHelper;
import com.leshao.v3.wm.utils.WmPrefs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * TTS 文字转语音 v2.2
 *
 * 1. Hook e9.d1(String) — 负责内容转换和语音文件生成
 * 2. before: TTS→WAV→PCM→Silk(MediaRecorder.Silk*)→voice2/标准路径
 * 3. Hook e9.setType(int) — voiceXml 类型守卫
 * 4. 就地修改 e9: d1(voiceXml), j1(voicePath)
 */
public class TtsVoiceSender {

    private static final String TAG = "TtsVoiceSender";
    private static final String TTS_PREFIX = "#tts ";
    private static final int TARGET_SAMPLE_RATE = 24000;    // 还原音质(B方案): 32k→24k(回退, v931 32k 接收端翻车)
    private static final int TARGET_CHANNELS = 1;
    private static final int TARGET_BITS_PER_SAMPLE = 16;
    private static final int FRAME_DURATION_MS = 20;
    private static final int FRAME_SAMPLES = TARGET_SAMPLE_RATE * FRAME_DURATION_MS / 1000;
    private static final int FRAME_PCM_BYTES = FRAME_SAMPLES * TARGET_CHANNELS * TARGET_BITS_PER_SAMPLE / 8;
    private static final long FAILURE_SUPPRESS_WINDOW_MS = 8000;
    /** v985: TTS 合成等待上限。原 30s 过长, 一旦引擎卡住会占满单线程池拖慢后续回复, 缩短到 12s。 */
    private static final long SYNTH_TIMEOUT_MS = 12000;
    private static final int SILK_BITRATE = 60000;          // 微信原生 SILK 高码率档(v930 为 50000, 升 60k 承载更多细节)
    private static final int SILK_COMPLEXITY = 5;           // 参照 8.0.78 v61.w.c 转码参数 new v61/c0(16000,16000,4), 升满复杂度
    private static volatile boolean sCrashHandlerInstalled;
    private static final java.util.concurrent.ExecutorService sTtsPool = 
            java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "TtsVoiceSender");
                    t.setDaemon(true);
                    return t;
                }
            });

    private static void installCrashReporter() {
        if (sCrashHandlerInstalled) return;
        sCrashHandlerInstalled = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringBuilder sb = new StringBuilder();
                    sb.append("FATAL thread=").append(t.getName()).append(" process=").append(getProcessName())
                            .append("\n").append(e.toString());
                    StackTraceElement[] st = e.getStackTrace();
                    if (st != null) {
                        for (StackTraceElement el : st) {
                            sb.append("\n  at ").append(el.getClassName()).append('.').append(el.getMethodName())
                                    .append('(').append(el.getFileName() == null ? "?" : el.getFileName())
                                    .append(':').append(el.getLineNumber()).append(')');
                        }
                    }
                    Throwable c = e.getCause();
                    while (c != null) {
                        sb.append("\nCaused by: ").append(c.toString());
                        StackTraceElement[] cst = c.getStackTrace();
                        if (cst != null) {
                            for (StackTraceElement el : cst) {
                                sb.append("\n  at ").append(el.getClassName()).append('.').append(el.getMethodName())
                                        .append('(').append(el.getFileName() == null ? "?" : el.getFileName())
                                        .append(':').append(el.getLineNumber()).append(')');
                            }
                        }
                        c = c.getCause();
                    }
                    LogWriter.log(TAG, sb.toString());
                } catch (Throwable ignored) {}
                if (prev != null && prev != this) {
                    prev.uncaughtException(t, e);
                }
            }
        });
    }

    private static String getProcessName() {
        try {
            return ContextManager.getAppContext().getPackageName();
        } catch (Throwable ignored) {}
        return "?";
    }

    private static TextToSpeech sTts;
    private static ClassLoader sClassLoader;
    private static volatile boolean sReady;

    /**
     * v1028: 语音/内核 API 必须走微信运行时真实 ClassLoader(Tinker DelegateLastClassLoader)。
     * 若用 lpparam.classLoader(base.apk 的 PathClassLoader), 会加载到内核类的平行副本,
     * 其静态内核未初始化 -> b96.b "Kernel not initialized by MMApplication!"。
     * 仅替换类加载目标, 不改动任何音频编码参数(v985 原样保留)。
     */
    private static ClassLoader voiceCl() {
        try {
            ClassLoader real = com.leshao.v3.db.DatabaseProvider.getRealClassLoader();
            if (real != null) return real;
        } catch (Throwable ignored) {}
        return sClassLoader;
    }
    private static String sAccPath;
    private static String sMyWxId;
    private static String sVoiceGClass;
    private static String sVoiceGMethod;
    private static String sVoiceTClass;
    private static String sVoiceTMethod;
    private static int sVoiceTParamCount = 4;
    private static String sVoiceSClass;
    private static String sVoiceSMethod;
    private static volatile long sLastTtsCommandAt;
    private static volatile String sLastTtsTalker;
    public static volatile long sOrderCardSentAt;
    private static final Object sLock = new Object();
    private static final Set<String> sSceneSentIds = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> sSuppressedMessages = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> sBlockedOriginalMessages = ConcurrentHashMap.newKeySet();
    private static final Set<Long> sMarkedMsgIds = ConcurrentHashMap.newKeySet();
    private static final java.util.Map<Long, String> sIncomingVoiceIds = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Long, String> sIncomingVoiceCids = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Integer, String> sSyncAmrMap = new HashMap<>();
    private static final StringBuilder sTraceBuf = new StringBuilder(2048);
    private static volatile long sTraceBufResetAt;
    private static final Set<String> sA21ParamTypesLogged = new HashSet<>();
    private static final Set<String> sContFieldsLogged = new HashSet<>();
    private static final Set<String> sArg0FieldsLogged = new HashSet<>();
    private static final Set<String> sArg0EFieldsLogged = new HashSet<>();
    private static final LinkedHashMap<String, Long> sRecentTexts = new LinkedHashMap<String, Long>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 16;
        }
    };

    // ===== TTS 模式（按会话持久化）: 在当前聊天发 "#tts" 开启/再发关闭,
    //       开启后直接发送文字会自动合成语音, 无需前缀 =====
    private static final String PREF_TTS_MODE_CONVS = "ls_tts_mode_convs";
    private static volatile Set<String> sTtsModeConvs;

    private static Set<String> ttsModeSet() {
        Set<String> set = sTtsModeConvs;
        if (set == null) {
            synchronized (TtsVoiceSender.class) {
                set = sTtsModeConvs;
                if (set == null) {
                    set = ConcurrentHashMap.newKeySet();
                    try {
                        android.content.SharedPreferences p = ContextManager.getPrefs();
                        if (p != null) {
                            Set<String> saved = p.getStringSet(PREF_TTS_MODE_CONVS, null);
                            if (saved != null) set.addAll(saved);
                        }
                    } catch (Throwable ignored) {}
                    sTtsModeConvs = set;
                }
            }
        }
        return set;
    }

    /** 该会话是否处于 TTS 模式 */
    public static boolean isTtsMode(String talker) {
        if (talker == null || talker.isEmpty()) return false;
        return ttsModeSet().contains(talker);
    }

    /** 切换该会话 TTS 模式, 返回切换后是否开启 */
    public static boolean toggleTtsMode(String talker) {
        if (talker == null || talker.isEmpty()) return false;
        Set<String> set = ttsModeSet();
        boolean on;
        if (set.contains(talker)) { set.remove(talker); on = false; }
        else { set.add(talker); on = true; }
        try {
            android.content.SharedPreferences p = ContextManager.getPrefs();
            if (p != null) p.edit().putStringSet(PREF_TTS_MODE_CONVS, new HashSet<>(set)).apply();
        } catch (Throwable ignored) {}
        LogWriter.log(TAG, "TTS mode " + (on ? "ON" : "OFF") + " talker=" + talker);
        return on;
    }

    private static void showTtsModeToast(String talker, boolean on) {
        try {
            final android.content.Context ctx = ContextManager.getAppContext();
            if (ctx == null) return;
            final String tip = on ? "已开启本会话语音模式，直接发文字即转语音"
                    : "已关闭本会话语音模式";
            new android.os.Handler(android.os.Looper.getMainLooper()).post(
                    () -> android.widget.Toast.makeText(ctx, tip, android.widget.Toast.LENGTH_SHORT).show());
        } catch (Throwable ignored) {}
    }

    private static volatile long sLastToggleAt;
    private static volatile String sLastToggleTalker;
    private static final Map<String, Long> sRecentModeSends = new java.util.concurrent.ConcurrentHashMap<>();

    /** TTS 模式直发文字的跨链路去重(ChatFooter.F 与 f9.Bb 会在同一毫秒级窗口内双命中) */
    private static boolean markRecentModeSend(String talker, String text) {
        long now = System.currentTimeMillis();
        String key = (talker == null ? "" : talker) + "|" + text;
        Long last = sRecentModeSends.get(key);
        if (last != null && now - last < 1500) return true;
        sRecentModeSends.put(key, now);
        if (sRecentModeSends.size() > 64) sRecentModeSends.clear();
        return false;
    }

    /** 处理 "#tts" 切换指令; ChatFooter.F 与 f9.Bb 双链路会同时命中, 做短窗口去重避免来回翻转 */
    private static synchronized void handleTtsToggleCommand(String talker, String source) {
        long now = System.currentTimeMillis();
        boolean same = (sLastToggleTalker == null || talker == null)
                || sLastToggleTalker.equals(talker);
        if (now - sLastToggleAt < 1500 && same) {
            LogWriter.log(TAG, source + " duplicate #tts toggle suppressed");
            return;
        }
        sLastToggleAt = now;
        sLastToggleTalker = talker;
        boolean on = toggleTtsMode(talker);
        showTtsModeToast(talker, on);
    }

    /** 彻底阻止该消息入库(清空内容 + 覆盖返回), 从源头消除空消息残留 */
    private static void blockCurrentInsert(XC_MethodHook.MethodHookParam p, Object msg) {
        if (msg != null) {
            try { XposedHelpers.setObjectField(msg, "field_content", ""); } catch (Throwable ignored) {}
            try { XposedHelpers.callMethod(msg, "j1", ""); } catch (Throwable ignored) {}
            synchronized (sSuppressedMessages) {
                sSuppressedMessages.add(System.identityHashCode(msg));
            }
            try { markBlockedOriginal(msg); } catch (Throwable ignored) {}
        }
        try { p.setResult(defaultReturnValue(methodReturnType(p))); } catch (Throwable ignored) {}
    }

    /** 是否为普通文本消息(type=1 且非 xml 富文本), 用于 TTS 模式自动转语音的判定 */
    private static boolean isPlainTextMsg(Object msg, String content) {
        int t;
        try {
            t = getMsgType(msg);
        } catch (Throwable ignored) {
            t = -1;
        }
        // type 尚未写入(-1)时按文本候选处理, 由内容形态二次判定
        if (t != 1 && t != -1) return false;
        if (content == null || content.trim().isEmpty()) return false;
        return !content.trim().startsWith("<");
    }

    /**
     * x9 分发链路的发出消息处理(8.0.78 真正命中的发送路径, 由 MessageHook 调用).
     * 返回 true 表示该消息应被拦截(不发送/不入库)。
     */
    public static boolean handleOutgoingX9(Object msg) {
        try {
            if (msg == null) return false;
            if (getMsgIsSend(msg) != 1) return false;
            String content = getMsgContent(msg);
            String talker = getTalker(msg);
            String cid = getClientMsgId(msg);
            String trimmed = content == null ? "" : content.trim();

            if ("#tts".equalsIgnoreCase(trimmed)) {
                handleTtsToggleCommand(talker, "x9");
                return true;
            }
            if (content != null && content.startsWith(TTS_PREFIX)) {
                String text = content.substring(TTS_PREFIX.length()).trim();
                if (text.isEmpty()) return true;
                if (markRecentText(text, System.currentTimeMillis())) return true;
                startAsyncTts(talker, cid, text, "x9");
                return true;
            }
            if (isTtsMode(talker) && isPlainTextMsg(msg, content)) {
                if (markRecentModeSend(talker, trimmed)) return true;
                LogWriter.log(TAG, "x9 TTS-mode text -> voice talker=" + talker + " text='" + truncStr(trimmed, 40) + "'");
                startAsyncTts(talker, cid, trimmed, "x9-mode");
                return true;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "handleOutgoingX9 err: " + t);
        }
        return false;
    }

    // ===== TTS 模式: 输入框文字显示粉色 =====
    private static final int TTS_MODE_TEXT_COLOR = 0xFFFF5FA2;
    private static final java.util.Map<android.widget.EditText, Boolean> sComposerWatchers =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<android.widget.EditText, Integer> sComposerOrigColor =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static void hookComposerColor(ClassLoader cl) {
        try {
            try {
                ClassLoader tk = VersionCompat.findTinkerClassLoader(cl);
                if (tk != null && !tk.getClass().getName().contains("Leshao") && tk != cl) {
                    cl = tk;
                }
            } catch (Throwable ignored) {
            }
            Class<?> footer = XposedHelpers.findClass(
                    "com.tencent.mm.pluginsdk.ui.chat.ChatFooter", cl);
            XposedBridge.hookAllConstructors(footer, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof android.view.View)) return;
                        final android.view.View root = (android.view.View) param.thisObject;
                        final Object footerObj = param.thisObject;
                        root.post(() -> attachComposerWatcher(root, footerObj));
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "TTS mode composer color hook installed");
        } catch (Throwable t) {
            LogWriter.log(TAG, "TTS mode composer color hook FAIL: " + t.getMessage());
        }
    }

    private static void attachComposerWatcher(final android.view.View root, final Object footerObj) {
        try {
            final android.widget.EditText et = findEditText(root);
            if (et == null) return;
            if (Boolean.TRUE.equals(sComposerWatchers.get(et))) return;
            sComposerWatchers.put(et, Boolean.TRUE);
            et.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    applyComposerColor(et, footerObj);
                }
            });
            applyComposerColor(et, footerObj);
        } catch (Throwable ignored) {}
    }

    private static void applyComposerColor(final android.widget.EditText et, final Object footerObj) {
        try {
            String talker = null;
            try {
                Object t = XposedHelpers.callMethod(footerObj, "getTalkerUserName");
                if (t instanceof String) talker = (String) t;
            } catch (Throwable ignored) {}
            boolean on = isTtsMode(talker);
            Integer orig = sComposerOrigColor.get(et);
            if (orig == null) {
                orig = et.getCurrentTextColor();
                sComposerOrigColor.put(et, orig);
            }
            int want = on ? TTS_MODE_TEXT_COLOR : orig;
            if (et.getCurrentTextColor() != want) et.setTextColor(want);
        } catch (Throwable ignored) {}
    }

    /** 清空 ChatFooter 输入框(拦截发送后避免残留文本) */
    private static void clearComposer(Object footer) {
        try {
            if (!(footer instanceof android.view.View)) return;
            android.widget.EditText et = findEditText((android.view.View) footer);
            if (et != null) et.setText("");
        } catch (Throwable ignored) {}
    }

    /** 从 ChatFooter 视图树读取输入框当前文本(发送瞬间 msg content 为空时的兜底) */
    private static String readComposerText(Object footer) {
        try {
            if (!(footer instanceof android.view.View)) return null;
            android.widget.EditText et = findEditText((android.view.View) footer);
            if (et == null) return null;
            CharSequence cs = et.getText();
            return cs == null ? null : cs.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static android.widget.EditText findEditText(android.view.View v) {        if (v instanceof android.widget.EditText) return (android.widget.EditText) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.EditText e = findEditText(g.getChildAt(i));
                if (e != null) return e;
            }
        }
        return null;
    }

    private static class VoiceFileInfo {
        final String path;
        final String clientMsgId;

        VoiceFileInfo(String path, String clientMsgId) {
            this.path = path;
            this.clientMsgId = clientMsgId;
        }
    }

    private static class TtsSendResult {
        final boolean sceneSent;
        final String clientMsgId;

        TtsSendResult(boolean sceneSent, String clientMsgId) {
            this.sceneSent = sceneSent;
            this.clientMsgId = clientMsgId;
        }
    }

    public static void hook(ClassLoader cl) {
        sClassLoader = cl;
        installCrashReporter();
        // Defer voice API discovery until DexKit scan completes
        com.leshao.v3.hook.DexKitHelper.addPostScanCallback(() -> {
            discoverVoiceApi(cl);
            LogWriter.log(TAG, "TtsVoiceSender post-scan init done");
        });
        DexKitHelper.addPostScanCallback(() -> hookE9D1(cl));
        hookSetTypeGuard(cl);
        hookChatFooterSend(cl);
        hookComposerColor(cl);
        hookChattingUiSend(cl);
        hookE9Trace(cl);
        hookE9AllTrace(cl);
        hookE9Render(cl);
        DexKitHelper.addPostScanCallback(() -> hookA21Oi(cl));
        hookChattingUiAll(cl);
        hookChattingUIFragmentAll(cl);
        hookConvertTo(cl);
        hookB31W(cl);
        hookAdapterKJ(cl);
        hookF9I9(cl);
        hookF9Bb(cl);
        hookF9RaForTts(cl);
        // v1073: TTS 兜底 —— 直接拦截 UI 发送入口 om.A0(content, atType, atMap),
        // 覆盖 f9.Bb / x9 / f9.Ra 均未命中的机型(文档《音频转语音_新》发送链)。
        DexKitHelper.addPostScanCallback(() -> hookOmA0(cl));
        // 自动发现微信内部类（功能所需）
        autoDiscoverClasses(cl, "com.tencent.mm.ui.chatting.ChattingUIFragment");
        autoDiscoverClasses(cl, "com.tencent.mm.ui.chatting.view.MMChattingListView");
        autoDiscoverClasses(cl, "com.tencent.mm.ui.chatting.ChattingUI");
    }

    // ========== 懒初始化 TTS ==========

    private static synchronized boolean ensureTtsReady() {
        if (sReady) return true;

        synchronized (sLock) {
            if (sReady) return true;

            try {
                sAccPath = findAccPath();
                LogWriter.log(TAG, "AccPath: " + sAccPath);
            } catch (Throwable t) {
                LogWriter.log(TAG, "AccPath err: " + t.getMessage());
            }

            try {
                Context ctx = ContextManager.getAppContext();
                if (ctx == null) {
                    LogWriter.log(TAG, "TTS init fail: appContext null");
                    return false;
                }
                sTts = new TextToSpeech(ctx, status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        try {
                            int result = sTts.setLanguage(Locale.CHINESE);
                            sReady = (result != TextToSpeech.LANG_MISSING_DATA
                                    && result != TextToSpeech.LANG_NOT_SUPPORTED);
                            LogWriter.log(TAG, "TTS init: " + (sReady ? "OK" : "FAIL lang"));
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "TTS setLanguage err: " + t.getMessage());
                        }
                    } else {
                        LogWriter.log(TAG, "TTS init fail: status=" + status);
                    }
                });
            } catch (Throwable t) {
                LogWriter.log(TAG, "TTS init crash: " + t.getMessage());
                return false;
            }
        }
        // Wait with timeout on a background thread, not blocking caller
        if (!sReady) {
            final long deadline = System.currentTimeMillis() + 3000;
            while (!sReady && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
            if (!sReady) {
                LogWriter.log(TAG, "TTS not ready after 3s wait");
            }
        }
        return sReady;
    }

    private static String findAccPath() {
        if (sAccPath != null) return sAccPath;
        try {
            // Try multiple kernel classes for acc path
            String[] kernelCandidates = {"com.tencent.mm.kernel.h", "com.tencent.mm.kernel.g"};
            for (String kernelName : kernelCandidates) {
                try {
                    String path = (String) XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass(kernelName, voiceCl()), "getAccPath");
                    if (path != null && !path.isEmpty()) {
                        sAccPath = path;
                        return path;
                    }
                } catch (Throwable ignored) {}
            }
            LogWriter.log(TAG, "findAccPath: no kernel class found");
        } catch (Throwable ignored) {}

        try {
            Context ctx = ContextManager.getAppContext();
            long uin = getDefaultUin(ctx);
            if (uin > 0) {
                String hash = VersionCompat.getDbHash(voiceCl(), (int) uin);
                String[] roots = buildAccRoots();
                for (String root : roots) {
                    File dir = new File(root, hash);
                    if (new File(dir, "EnMicroMsg.db").exists()) {
                        LogWriter.log(TAG, "Acc via default_uin hash: " + hash);
                        return dir.getAbsolutePath() + "/";
                    }
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "Acc default_uin err: " + t.getMessage());
        }

        String[] roots = buildAccRoots();
        for (String root : roots) {
            File md = new File(root);
            if (!md.exists() || !md.isDirectory()) continue;
            File[] subs = md.listFiles();
            if (subs == null) continue;
            for (File sub : subs) {
                if (!sub.isDirectory()) continue;
                if (sub.getName().matches("[a-f0-9]{32}")) {
                    File db = new File(sub, "EnMicroMsg.db");
                    if (db.exists()) {
                        LogWriter.log(TAG, "Acc via scan: " + sub.getName());
                        return sub.getAbsolutePath() + "/";
                    }
                }
            }
        }

        LogWriter.log(TAG, "Acc fallback: no hash dir found");
        return "/data/user/" + getCurrentUserId() + "/com.tencent.mm/MicroMsg/";
    }

    private static long getDefaultUin(Context ctx) {
        if (ctx == null) return 0;
        try {
            Object v = ctx.getSharedPreferences("system_config_prefs", 0).getAll().get("default_uin");
            if (v != null) return Long.parseLong(v.toString());
        } catch (Throwable ignored) {}
        return 0;
    }

    private static String ensureTrailingSlash(String path) {
        if (path == null) return "/data/user/" + getCurrentUserId() + "/com.tencent.mm/MicroMsg/";
        return path.endsWith("/") ? path : path + "/";
    }

    private static String getVoice2Dir() {
        int expectedUser = getCurrentUserId();
        String expectedPrefix = "/data/user/" + expectedUser + "/";
        if (sAccPath != null) {
            String normalized = normalizeDataPath(sAccPath);
            if (!normalized.startsWith(expectedPrefix)) {
                LogWriter.log(TAG, "AccPath mismatch: " + sAccPath + " vs user " + expectedUser + ", re-find");
                sAccPath = null;
            } else {
                sAccPath = normalized;
            }
        }
        if (sAccPath == null) {
            sAccPath = findAccPath();
        }
        return sAccPath + "voice2";
    }

    private static String normalizeDataPath(String path) {
        if (path == null) return null;
        if (path.startsWith("/data/data/")) {
            return path.replaceFirst("^/data/data/", "/data/user/0/");
        }
        return path;
    }

    private static void fileCopy(String src, String dst) {
        java.io.FileInputStream fis = null;
        java.io.FileOutputStream fos = null;
        try {
            fis = new java.io.FileInputStream(src);
            fos = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
        } catch (Throwable t) {
            LogWriter.log(TAG, "fileCopy err: " + t.getMessage());
        } finally {
            try { if (fis != null) fis.close(); } catch (Throwable ignored) {}
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
        }
    }

    private static void fileWrite(File file, byte[] data) {
        java.io.FileOutputStream fos = null;
        try {
            fos = new java.io.FileOutputStream(file);
            fos.write(data);
        } catch (Throwable t) {
            LogWriter.log(TAG, "fileWrite err: " + t.getMessage());
        } finally {
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
        }
    }

    private static String getMyWxId() {
        if (sMyWxId == null) {
            synchronized (sLock) {
                if (sMyWxId == null) {
                    sMyWxId = findMyWxId();
                }
            }
        }
        return sMyWxId;
    }

    private static String findMyWxId() {
        String[] prefNames = {
            "system_config_prefs", "com.tencent.mm_preferences",
            "notify_sync_pref", "auth_info_key_prefs",
            "app_brand_global_sp", "exdevice_pref",
        };
        String[] keyNames = {
            "login_weixin_username", "login_user_name", "last_login_username",
            "auth_uin", "username", "uin", "_auth_uin",
        };

        android.content.Context ctx = ContextManager.getAppContext();
        if (ctx == null) {
            LogWriter.log(TAG, "wxId: appContext null");
            return "";
        }

        for (String pn : prefNames) {
            try {
                java.util.Map<String, ?> all = ctx.getSharedPreferences(pn, 0).getAll();
                for (String key : keyNames) {
                    Object v = all.get(key);
                    if (v != null && v.toString().startsWith("wxid_")) {
                        String wxid = v.toString();
                        LogWriter.log(TAG, "wxId from " + pn + "/" + key + ": " + wxid);
                        return wxid;
                    }
                }
                for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
                    Object v = entry.getValue();
                    if (v != null) {
                        String val = v.toString();
                        if (val.startsWith("wxid_") && !val.contains("@")) {
                            LogWriter.log(TAG, "wxId from " + pn + " scan: " + val);
                            return val;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        LogWriter.log(TAG, "wxId not found in any prefs");
        return "";
    }

    // ========== Hook e9.d1(String) ==========

    private static void hookE9D1(ClassLoader cl) {
        try {
            Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Class == null) {
                LogWriter.log(TAG, "e9 class not found");
                return;
            }
            LogWriter.log(TAG, "e9 class: " + e9Class.getName());

            StringBuilder methods = new StringBuilder("e9 methods:");
            for (java.lang.reflect.Method m : e9Class.getDeclaredMethods()) {
                if (m.getName().length() > 4) continue;
                methods.append("\n  ").append(m.getName()).append('(');
                Class<?>[] pts = m.getParameterTypes();
                for (int i = 0; i < pts.length; i++) {
                    if (i > 0) methods.append(',');
                    methods.append(pts[i].getSimpleName());
                }
                methods.append(')').append(m.getReturnType().getSimpleName());
            }
            LogWriter.log(TAG, methods.toString());

            // 1) 先用 DexKit 动态发现：查找 e9 类中包含 "voicemsg" 字符串的方法
            java.lang.reflect.Method contentSetter = null;
            List<String> methodCandidates = DexKitHelper.findMethodsByString(cl, e9Class.getName(), "voicemsg");
            for (String sig : methodCandidates) {
                try {
                    String methodName = sig.substring(sig.indexOf('.') + 1, sig.indexOf('('));
                    String paramStr = sig.substring(sig.indexOf('(') + 1, sig.indexOf(')'));
                    if (paramStr.isEmpty()) continue;
                    String[] paramNames = paramStr.split(",");
                    Class<?>[] paramTypes = new Class<?>[paramNames.length];
                    for (int i = 0; i < paramNames.length; i++) {
                        paramTypes[i] = mapBasicType(paramNames[i].trim());
                    }
                    java.lang.reflect.Method m = e9Class.getDeclaredMethod(methodName, paramTypes);
                    // 优先找 (String)void 类型的内容设置方法
                    if (m.getReturnType() == void.class && paramTypes.length == 1 && paramTypes[0] == String.class) {
                        contentSetter = m;
                        LogWriter.log(TAG, "e9 content setter found via DexKit: " + methodName + "(String)");
                        break;
                    }
                } catch (Throwable ignored) {}
            }

            // 2) 兜底：遍历 e9 自身方法找 (String)void 方法
            if (contentSetter == null) {
                for (java.lang.reflect.Method m : e9Class.getDeclaredMethods()) {
                    if (m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == String.class
                            && m.getReturnType() == void.class) {
                        String n = m.getName();
                        // 8.0.78: 方法名已混淆为 X0/Y0/j1 等短名，不再有 d1(String)
                        if ("d1".equals(n) || "d2".equals(n) || n.startsWith("set")
                                || n.equals("X0") || n.equals("Y0") || n.equals("j1")
                                || n.equals("b1") || n.equals("i1") || n.equals("m3")
                                || n.equals("o3") || n.equals("p3") || n.equals("r1")
                                || n.equals("r3") || n.equals("u3") || n.equals("w1")
                                || n.equals("x1")) {
                            contentSetter = m;
                            LogWriter.log(TAG, "e9.d1 fallback: using " + n + "(String)");
                            break;
                        }
                    }
                }
            }

            if (contentSetter == null) {
                // 8.0.78: 最后兜底 - 取第一个 (String)void 方法
                for (java.lang.reflect.Method m : e9Class.getDeclaredMethods()) {
                    if (m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == String.class
                            && m.getReturnType() == void.class) {
                        contentSetter = m;
                        LogWriter.log(TAG, "e9.d1 last resort: using " + m.getName() + "(String)");
                        break;
                    }
                }
            }

            if (contentSetter == null) {
                LogWriter.log(TAG, "Hook e9.d1 FAIL: no (String)void method found in e9");
                return;
            }

            final java.lang.reflect.Method targetMethod = contentSetter;
            XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                                        String content = (String) param.args[0];
                                        if (content == null) return;
                                        captureIncomingVoice(param.thisObject, content);
                                        if (content.startsWith(TTS_PREFIX)) {
                                            LogWriter.log(TAG, "e9.d1 before: thread=" + Thread.currentThread().getName()
                                                    + " content='" + truncStr(content, 40) + "' this="
                                                    + (param.thisObject == null ? "null" : param.thisObject.getClass().getName()));
                                        } else if (isMarkedMessage(param.thisObject)) {
                                            LogWriter.log(TAG, "e9.d1 before(marked): thread=" + Thread.currentThread().getName()
                                                    + " content='" + truncStr(content, 40) + "'");
                                        }
                                        if (content == null || !content.startsWith(TTS_PREFIX)) return;

                                        String text = content.substring(TTS_PREFIX.length()).trim();
                                        LogWriter.log(TAG, "e9.d1 #tts matched: text='" + truncStr(text, 40) + "'");
                                        if (text.isEmpty()) return;

                                        if (markRecentText(text, System.currentTimeMillis())) {
                                            Object dupMsg = param.thisObject;
                                            suppressOriginal(param, dupMsg);
                                            LogWriter.log(TAG, "e9.d1 duplicate #tts suppressed: " + text);
                                            return;
                                        }

                                        Object msg = param.thisObject;
                                        String talker = getTalker(msg);
                                        String clientMsgId = getClientMsgId(msg);
                                        markBlockedOriginal(msg);
                                        markRecentTtsCommand(msg);

                                        suppressOriginal(param, msg);
                                        LogWriter.log(TAG, "e9.d1 suppress done -> async SceneVoice talker=" + talker
                                                + " cid=" + clientMsgId + " text='" + truncStr(text, 40) + "'");
                                        startAsyncTts(talker, clientMsgId, text, "d1");
                    } catch (Throwable e) {
                        LogWriter.log("TtsVoiceSender", "cb err: " + e);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object msg = param.thisObject;
                    if (msg == null || !isMarkedMessage(msg)) return;
                    try {
                        int type = getMsgType(msg);
                        LogWriter.log(TAG, "d1 after: type=" + type + " (no forced type change)");
                    } catch (Throwable ignored) {}
                }
            });

            LogWriter.log(TAG, "Hook e9.d1(String) OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "Hook e9.d1 FAIL: " + t.getMessage());
        }
    }

    private static Class<?> mapBasicType(String typeName) {
        switch (typeName) {
            case "int": return int.class;
            case "long": return long.class;
            case "boolean": return boolean.class;
            case "byte": return byte.class;
            case "short": return short.class;
            case "char": return char.class;
            case "float": return float.class;
            case "double": return double.class;
            case "void": return void.class;
            case "java.lang.String": return String.class;
            case "java.lang.Integer": return int.class;
            case "java.lang.Long": return long.class;
            case "java.lang.Boolean": return boolean.class;
            default:
                try { return Class.forName(typeName); } catch (Throwable e) { return Object.class; }
        }
    }

    public static String getCapturedVoiceId(long msgId) {
        return sIncomingVoiceIds.get(msgId);
    }

    public static String getCapturedVoiceCid(long msgId) {
        return sIncomingVoiceCids.get(msgId);
    }

    private static void captureIncomingVoice(Object msg, String content) {
        try {
            if (content == null || !content.contains("<voicemsg")) return;
            long msgId;
            try { msgId = (Long) XposedHelpers.callMethod(msg, "getMsgId"); }
            catch (Throwable t) { msgId = 0; }
            if (msgId == 0) {
                try { msgId = (Long) XposedHelpers.callMethod(msg, "H0"); }
                catch (Throwable ignored) {}
            }
            if (msgId == 0) return;

            String voiceId = extractXmlAttr(content, "voiceid");
            String cid = extractXmlAttr(content, "clientmsgid");
            if (voiceId == null && cid == null) return;
            if (voiceId != null) sIncomingVoiceIds.put(msgId, voiceId);
            if (cid != null) sIncomingVoiceCids.put(msgId, cid);
            String voicemd5 = extractXmlAttr(content, "voicemd5");
            String filename = extractXmlAttr(content, "filename");
            String voiceformat = extractXmlAttr(content, "voiceformat");
            String length = extractXmlAttr(content, "length");
            String fromusername = extractXmlAttr(content, "fromusername");
            LogWriter.log(TAG, "captureIncomingVoice msgId=" + msgId
                + " voiceId=" + (voiceId != null ? truncStr(voiceId, 30) : "null")
                + " voicemd5=" + (voicemd5 != null ? truncStr(voicemd5, 30) : "null")
                + " filename=" + (filename != null ? truncStr(filename, 40) : "null")
                + " fmt=" + (voiceformat != null ? voiceformat : "null")
                + " len=" + (length != null ? length : "null")
                + " from=" + (fromusername != null ? truncStr(fromusername, 20) : "null")
                + " cid=" + (cid != null ? truncStr(cid, 30) : "null"));
        } catch (Throwable t) {
            LogWriter.log(TAG, "captureIncomingVoice err: " + t.getMessage());
        }
    }

    private static TtsSendResult handleTtsMessage(Object msg, String text, String source) {
        LogWriter.log(TAG, "================================");
        LogWriter.log(TAG, "#tts(" + source + "): " + text);
        LogWriter.log(TAG, "================================");

        if (!ensureTtsReady()) {
            LogWriter.log(TAG, "TTS not ready: " + source);
            return null;
        }

        VoiceFileInfo voiceFile = getVoiceFileInfo(msg);
        if (voiceFile == null) {
            LogWriter.log(TAG, "getVoicePath fail: " + source);
            return null;
        }

        Object[] ttsResult = doTTS(text, voiceFile.path);
        if (ttsResult == null) {
            LogWriter.log(TAG, "TTS fail: " + source);
            return null;
        }

        int amrSize = (Integer) ttsResult[0];
        int durationMs = (Integer) ttsResult[1];
        String voiceXml = "<msg><voicemsg voiceformat=\"4\" length=\""
                + amrSize + "\" endflag=\"1\" cancelflag=\"0\" voicelength=\""
                + durationMs + "\" fromusername=\"" + getMyWxId()
                + "\" clientmsgid=\"" + voiceFile.clientMsgId + "\"/></msg>";
        LogWriter.log(TAG, "VoiceXml: " + voiceXml);

        boolean sceneSent = sendViaSceneVoice(getTalker(msg), voiceFile.path, durationMs);
        LogWriter.log(TAG, "SceneVoice send: " + sceneSent + " cid=" + voiceFile.clientMsgId);
        LogWriter.log(TAG, "before OK: " + amrSize + "b " + durationMs + "ms");
        LogWriter.log(TAG, "================================");
        return new TtsSendResult(sceneSent, voiceFile.clientMsgId);
    }

    /** AI 回复转语音消息发出(异步)。失败只记日志, 由调用方决定是否回退发文本。 */
    public static void sendAiReplyAsVoice(String talker, String text, String clientMsgId) {
        sendAiReplyAsVoice(talker, text, clientMsgId, null);
    }

    /**
     * AI 回复转语音消息发出(异步 + 失败回退)。
     *
     * <p>合成/发送失败或超时时, 在 TTS 后台线程回调 {@code onFail}, 供调用方回退发文本,
     * 避免"AI 没回消息"。传入 null 表示不回调。</p>
     */
    public static void sendAiReplyAsVoice(String talker, String text, String clientMsgId,
                                          Runnable onFail) {
        sendAiReplyAsVoice(talker, text, clientMsgId, null, onFail);
    }

    /**
     * v985: 带音色覆盖的 AI 回复转语音。voiceId 非空时用该音色(会话/模板多音色随机选出的)，
     * 否则回退全局 tts_cube_voice。
     */
    public static void sendAiReplyAsVoice(String talker, String text, String clientMsgId,
                                          String voiceId, Runnable onFail) {
        if (talker == null || talker.isEmpty() || text == null || text.trim().isEmpty()) {
            LogWriter.log(TAG, "sendAiReplyAsVoice skip: empty talker/text");
            notifyTtsFail(onFail, "empty talker/text");
            return;
        }
        String cid = (clientMsgId == null || clientMsgId.isEmpty())
                ? ("ai-" + System.currentTimeMillis()) : clientMsgId;
        startAsyncTts(talker, cid, text.trim(), "AiReply", onFail, voiceId);
    }

    /** TTS 失败/超时时回调回退动作(发送文本)。 */
    private static void notifyTtsFail(Runnable onFail, String reason) {
        LogWriter.log(TAG, "TTS 失败回退文本: " + reason);
        if (onFail == null) return;
        try {
            onFail.run();
        } catch (Throwable t) {
            LogWriter.log(TAG, "TTS onFail 回退异常: " + t);
        }
    }

    private static void startAsyncTts(final String talker, final String clientMsgId,
                                     final String text, final String source) {
        startAsyncTts(talker, clientMsgId, text, source, null, null);
    }

    private static void startAsyncTts(final String talker, final String clientMsgId,
                                     final String text, final String source, final Runnable onFail) {
        startAsyncTts(talker, clientMsgId, text, source, onFail, null);
    }

    private static void startAsyncTts(final String talker, final String clientMsgId,
                                     final String text, final String source, final Runnable onFail,
                                     final String voiceOverride) {
        sTtsPool.execute(() -> {
            try {
                LogWriter.log(TAG, "async start: source=" + source + " talker=" + talker
                        + " cid=" + clientMsgId + " voice=" + voiceOverride
                        + " text='" + truncStr(text, 40) + "'");

                boolean useCube = WmPrefs.isTTSCube();
                LogWriter.log(TAG, "async useCube=" + useCube + " source=" + source);

                if (useCube) {
                    if (sAccPath == null || sClassLoader == null) {
                        ensureTtsReady();
                    }
                    if (sAccPath == null || sClassLoader == null) {
                        LogWriter.log(TAG, "async cube accPath/classLoader null: " + source);
                        notifyTtsFail(onFail, "cube accPath/classLoader null");
                        return;
                    }
                    String amrPath = buildVoicePath(clientMsgId);
                    Object[] ttsResult = doCubeTTS(text, amrPath, voiceOverride);
                    if (ttsResult == null) {
                        LogWriter.log(TAG, "async cube TTS synth fail: " + source);
                        notifyTtsFail(onFail, "cube synth fail");
                        return;
                    }
                    int amrSize = (Integer) ttsResult[0];
                    int durationMs = (Integer) ttsResult[1];
                    LogWriter.log(TAG, "async cube scene send start: dur=" + durationMs);
                    boolean sceneSent = sendViaSceneVoice(talker, amrPath, durationMs);
                    LogWriter.log(TAG, "async cube scene sent=" + sceneSent + " cid=" + clientMsgId);
                    if (!sceneSent) {
                        notifyTtsFail(onFail, "cube scene send fail");
                    }
                } else {
                    if (!ensureTtsReady()) {
                        LogWriter.log(TAG, "async TTS not ready: " + source);
                        notifyTtsFail(onFail, "TTS not ready");
                        return;
                    }
                    LogWriter.log(TAG, "async ensureTtsReady OK: " + source);
                    String amrPath = buildVoicePath(clientMsgId);
                    LogWriter.log(TAG, "async amrPath=" + amrPath + " source=" + source);
                    Object[] ttsResult = doTTS(text, amrPath);
                    if (ttsResult == null) {
                        LogWriter.log(TAG, "async TTS synth fail: " + source);
                        notifyTtsFail(onFail, "TTS synth fail");
                        return;
                    }
                    int amrSize = (Integer) ttsResult[0];
                    int durationMs = (Integer) ttsResult[1];
                    LogWriter.log(TAG, "async scene send start: dur=" + durationMs + " source=" + source);
                    boolean sceneSent = sendViaSceneVoice(talker, amrPath, durationMs);
                    LogWriter.log(TAG, "async scene sent=" + sceneSent + " cid=" + clientMsgId
                            + " bytes=" + amrSize + " dur=" + durationMs);
                    if (!sceneSent) {
                        notifyTtsFail(onFail, "scene send fail");
                    }
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "async TTS crash: " + t.getClass().getSimpleName() + " " + t.getMessage());
                notifyTtsFail(onFail, "crash " + t.getClass().getSimpleName());
            }
        });
    }

    private static String buildVoiceXmlStr(int amrSize, int durationMs, String clientMsgId) {
        return "<msg><voicemsg voiceformat=\"4\" length=\""
                + amrSize + "\" endflag=\"1\" cancelflag=\"0\" voicelength=\""
                + durationMs + "\" fromusername=\"" + getMyWxId()
                + "\" clientmsgid=\"" + clientMsgId + "\"/></msg>";
    }

    private static void hookConvertTo(ClassLoader cl) {
        try {
            Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Class == null) return;
            for (java.lang.reflect.Method m : e9Class.getDeclaredMethods()) {
                if (!m.getName().equals("convertTo")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (!isMarkedMessage(p.thisObject)) return;
                            int type = getMsgType(p.thisObject);
                            LogWriter.log(TAG, "convertTo after: type=" + type + " (no forced type change)");
                            scheduleAmrFixup(p.thisObject);
                        } catch (Throwable ignored) {}
                    }
                });
                LogWriter.log(TAG, "Hook convertTo OK");
                return;
            }
            LogWriter.log(TAG, "Hook convertTo: method not found");
        } catch (Throwable t) {
            LogWriter.log(TAG, "Hook convertTo FAIL: " + t.getMessage());
        }
    }

    private static void scheduleAmrFixup(final Object msg) {
        sTtsPool.execute(() -> {
            String amrPath;
            synchronized (sSyncAmrMap) {
                amrPath = sSyncAmrMap.get(System.identityHashCode(msg));
            }
            if (amrPath == null) {
                LogWriter.log(TAG, "amr fixup: no amr path for marked msg");
                return;
            }
            String talker = getTalker(msg);
            for (int i = 0; i < 80; i++) {
                long msgId = 0;
                try { msgId = (Long) XposedHelpers.callMethod(msg, "H0"); } catch (Throwable ignored) {}
                if (msgId > 0) {
                    try {
                        if (sAccPath == null) { findAccPath(); if (sAccPath == null) return; }
                        String dst = sAccPath + "voice2/" + talker + "/msg_" + msgId + ".amr";
                        File parent = new File(dst).getParentFile();
                        if (parent != null) parent.mkdirs();
                        fileCopy(amrPath, dst);
                        try { XposedHelpers.callMethod(msg, "j1", dst); } catch (Throwable ignored) {}
                        LogWriter.log(TAG, "amr fixup: msgId=" + msgId + " copied " + amrPath + " -> " + dst);
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "amr fixup err: " + e.getMessage());
                    }
                    return;
                }
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
            LogWriter.log(TAG, "amr fixup: msgId not assigned within 8s, type=" + getMsgType(msg));
        });
    }

    private static String buildVoicePath(String clientMsgId) {
        if (sAccPath == null) { findAccPath(); if (sAccPath == null) return null; }
        String voice2Dir = sAccPath + "voice2/";
        String path = buildVoice2Path(voice2Dir, clientMsgId);
        File parent = new File(path).getParentFile();
        if (parent != null) parent.mkdirs();
        return path;
    }

    private static String stackTrace(int depth) {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        for (int i = 3; i < Math.min(st.length, 3 + depth); i++) {
            StackTraceElement el = st[i];
            sb.append("\n  at ").append(el.getClassName()).append('.').append(el.getMethodName());
            sb.append('(').append(el.getFileName() == null ? "?" : el.getFileName())
              .append(':').append(el.getLineNumber()).append(')');
        }
        return sb.toString();
    }

    private static void suppressOriginal(XC_MethodHook.MethodHookParam param, Object msg) {
        param.args[0] = "";
        try { XposedHelpers.setObjectField(msg, "field_content", ""); } catch (Throwable ignored) {}
        try { XposedHelpers.callMethod(msg, "j1", ""); } catch (Throwable ignored) {}
        if (msg != null) {
            synchronized (sSuppressedMessages) {
                sSuppressedMessages.add(System.identityHashCode(msg));
            }
        }
    }

    private static void suppressOriginalMessage(XC_MethodHook.MethodHookParam param, Object msg, String cid) {
        param.args[0] = "";
        try { XposedHelpers.setObjectField(msg, "field_content", ""); } catch (Throwable ignored) {}
        try { XposedHelpers.callMethod(msg, "j1", ""); } catch (Throwable ignored) {}
        synchronized (sSuppressedMessages) {
            sSuppressedMessages.add(System.identityHashCode(msg));
        }
        if (cid != null) {
            synchronized (sSceneSentIds) {
                sSceneSentIds.add(cid);
            }
        }
        try {
            param.setResult(defaultReturnValue(methodReturnType(param)));
        } catch (Throwable ignored) {}
    }

    private static void markBlockedOriginal(Object msg) {
        if (msg == null) return;
        synchronized (sBlockedOriginalMessages) {
            sBlockedOriginalMessages.add(System.identityHashCode(msg));
            if (sBlockedOriginalMessages.size() > 32) sBlockedOriginalMessages.clear();
        }
    }

    public static boolean consumeBlockedOriginal(Object msg) {
        if (msg == null) return false;
        int key = System.identityHashCode(msg);
        synchronized (sBlockedOriginalMessages) {
            return sBlockedOriginalMessages.remove(key);
        }
    }

    public static boolean shouldConsumeTtsFailureMessage(Object msg) {
        if (msg == null || System.currentTimeMillis() - sLastTtsCommandAt > FAILURE_SUPPRESS_WINDOW_MS) {
            return false;
        }
        String talker = getTalker(msg);
        if (sLastTtsTalker != null && talker != null && !sLastTtsTalker.equals(talker)) return false;

        int type = getMsgType(msg);
        int isSend = getMsgIsSend(msg);
        String content = getMsgContent(msg);
        boolean lengthNotice = type == 10000 && content != null && content.contains("消息超过字数限制");
        boolean blankSelfText = type == 1 && (content == null || content.trim().isEmpty());
        if (lengthNotice || blankSelfText) {
            LogWriter.log(TAG, "consume TTS failure residue: type=" + type
                    + " isSend=" + isSend + " talker=" + talker
                    + " content=" + (content == null ? "null" : content));
            return true;
        }
        return false;
    }

    private static void markRecentTtsCommand(Object msg) {
        sLastTtsCommandAt = System.currentTimeMillis();
        sLastTtsTalker = getTalker(msg);
    }

    /**
     * 统一拦截"用户从输入框发出"的文本（ChatFooter.d/V0、om.A0 共用）。
     * 命中 #tts 指令 / #tts 前缀 / TTS 模式直发文本时触发语音并阻止原文本发出。
     *
     * @return true 表示已拦截, 调用方需 setResult 阻止原方法
     */
    private static boolean interceptOutgoingInput(String talker, String content, String source) {
        if (content == null) return false;
        String trimmed = content.trim();
        if (trimmed.isEmpty()) return false;
        if ("#tts".equalsIgnoreCase(trimmed)) {
            handleTtsToggleCommand(talker, source);
            LogWriter.log(TAG, source + " #tts toggle talker=" + talker);
            return true;
        }
        if (content.startsWith(TTS_PREFIX)) {
            String text = content.substring(TTS_PREFIX.length()).trim();
            if (text.isEmpty()) return true;
            if (markRecentText(text, System.currentTimeMillis())) return true;
            LogWriter.log(TAG, source + " #tts-prefix -> voice talker=" + talker
                    + " text='" + truncStr(text, 40) + "'");
            startAsyncTts(talker, "ci-" + System.currentTimeMillis(), text, source);
            return true;
        }
        if (isTtsMode(talker) && !trimmed.startsWith("<")) {
            if (markRecentModeSend(talker, trimmed)) return true;
            LogWriter.log(TAG, source + " TTS-mode text -> voice talker=" + talker
                    + " text='" + truncStr(trimmed, 40) + "'");
            startAsyncTts(talker, "ci-" + System.currentTimeMillis(), trimmed, source);
            return true;
        }
        return false;
    }

    private static String firstStringArg(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof String) return (String) a;
        }
        return null;
    }

    private static String footerTalker(Object footer) {
        if (footer != null) {
            try {
                Object t = XposedHelpers.callMethod(footer, "getTalkerUserName");
                if (t instanceof String && !((String) t).isEmpty()) return (String) t;
            } catch (Throwable ignored) {
            }
        }
        return ChatFooterLongPressMenu.currentTalker();
    }

    private static void hookChatFooterSend(ClassLoader cl) {
        try {
            // v1078: 必须切到 Tinker 真实 CL, 否则 hook 挂在 base.apk 平行副本的
            // ChatFooter 上, 运行时零命中(实测 d/V0 从不触发, 文字直接发出)。
            try {
                ClassLoader tk = VersionCompat.findTinkerClassLoader(cl);
                if (tk != null && !tk.getClass().getName().contains("Leshao") && tk != cl) {
                    LogWriter.log(TAG, "hookChatFooterSend: 使用 Tinker 真实 CL "
                            + tk.getClass().getSimpleName());
                    cl = tk;
                }
            } catch (Throwable ignored) {
            }
            Class<?> chatFooter = XposedHelpers.findClass("com.tencent.mm.pluginsdk.ui.chat.ChatFooter", cl);
            XposedBridge.hookAllMethods(chatFooter, "F", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                        int msgKey = System.identityHashCode(param.args[0]);
                        synchronized (sSuppressedMessages) {
                            if (sSuppressedMessages.remove(msgKey)) {
                                LogWriter.log(TAG, "ChatFooter.F consumed suppressed TTS msg=" + msgKey);
                                setResultBoolean(param, true);
                                return;
                            }
                        }
                        Object msg = param.args[0];
                        String content = getMsgContent(msg);
                        // 回退: 发送瞬间 msg 的 content 可能尚未写入, 直接从输入框读取
                        if (content == null || content.trim().isEmpty()) {
                            String et = readComposerText(param.thisObject);
                            if (et != null && !et.trim().isEmpty()) content = et;
                        }
                        String talker = getTalker(msg);
                        // 回退: 从当前 ChatFooter 取会话 id
                        if (talker == null || talker.isEmpty()) {
                            try {
                                Object t = XposedHelpers.callMethod(param.thisObject, "getTalkerUserName");
                                if (t instanceof String) talker = (String) t;
                            } catch (Throwable ignored) {}
                        }
                        String trimmed = content == null ? "" : content.trim();
                        LogWriter.log(TAG, "ChatFooter.F: content='" + truncStr(trimmed, 40)
                                + "' talker=" + talker);
                        // (1) 纯指令 "#tts": 切换当前会话 TTS 模式, 拦截该消息
                        if ("#tts".equalsIgnoreCase(trimmed)) {
                            handleTtsToggleCommand(talker, "ChatFooter.F");
                            LogWriter.log(TAG, "ChatFooter.F #tts toggle talker=" + talker);
                            clearComposer(param.thisObject);
                            setResultBoolean(param, true);
                            return;
                        }
                        if (content != null && content.startsWith(TTS_PREFIX)) {
                            String text = content.substring(TTS_PREFIX.length()).trim();
                            if (text.isEmpty()) { setResultBoolean(param, true); return; }
                            if (markRecentText(text, System.currentTimeMillis())) {
                                LogWriter.log(TAG, "ChatFooter.F consumed duplicate #tts: " + text);
                                setResultBoolean(param, true);
                                return;
                            }
                            String clientMsgId = getClientMsgId(msg);
                            LogWriter.log(TAG, "ChatFooter.F #tts hit -> async SceneVoice cid="
                                    + clientMsgId + " talker=" + talker + " text='" + truncStr(text, 40) + "'");
                            startAsyncTts(talker, clientMsgId, text, "ChatFooter.F");
                            clearComposer(param.thisObject);
                            setResultBoolean(param, true);
                            return;
                        }
                        // (2) TTS 模式下直接发送文字: 自动合成为语音, 拦截原文本
                        if (isTtsMode(talker) && isPlainTextMsg(msg, content)) {
                            if (markRecentModeSend(talker, trimmed)) {
                                LogWriter.log(TAG, "ChatFooter.F consumed duplicate TTS-mode text");
                                clearComposer(param.thisObject);
                                setResultBoolean(param, true);
                                return;
                            }
                            String clientMsgId = getClientMsgId(msg);
                            LogWriter.log(TAG, "ChatFooter.F TTS-mode auto -> talker=" + talker
                                    + " text='" + truncStr(trimmed, 40) + "'");
                            final String fTalker = talker;
                            final String fText = trimmed;
                            startAsyncTts(talker, clientMsgId, trimmed, "ChatFooter.F-mode", () -> {
                                try {
                                    com.leshao.v3.wm.utils.WmReflect.sendTextMsg(sClassLoader, fText, fTalker);
                                    LogWriter.log(TAG, "TTS-mode fallback sent as text: " + fTalker);
                                } catch (Throwable ignored) {}
                            });
                            clearComposer(param.thisObject);
                            setResultBoolean(param, true);
                            return;
                        }
                        if (content == null || content.trim().isEmpty()) {
                            LogWriter.log(TAG, "ChatFooter.F empty content msg=" + msgKey);
                            if (System.currentTimeMillis() - sLastTtsCommandAt <= FAILURE_SUPPRESS_WINDOW_MS) {
                                String t = getTalker(msg);
                                if (sLastTtsTalker == null || t == null || sLastTtsTalker.equals(t)) {
                                    LogWriter.log(TAG, "ChatFooter.F consumed empty #tts residue msg=" + msgKey);
                                    setResultBoolean(param, true);
                                    return;
                                }
                            }
                            return;
                        }
                        if (!isVoiceXml(content)) return;
                        String cid = extractXmlAttr(content, "clientmsgid");
                        synchronized (sSceneSentIds) {
                            if (cid == null || !sSceneSentIds.remove(cid)) return;
                        }
                        LogWriter.log(TAG, "ChatFooter.F consumed TTS voice cid=" + cid);
                        setResultBoolean(param, true);
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "ChatFooter.F consume err: " + t.getMessage());
                    }
                }
            });
            LogWriter.log(TAG, "Hook ChatFooter.F consume OK");

            // v1076: 直接在"文本发送入口"拦截。F 在真机不是文本发送方法,
            // 用户输入的文字会经 ChatFooter.d → V0 → om.A0 发出, 原文本在
            // 这些 before 钩子命中前就入库了, 故必须在前两处拦截。
            XC_MethodHook inputCb = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String content = firstStringArg(param.args);
                        if (content == null) return;
                        String trimmed = content.trim();
                        boolean cmd = trimmed.equalsIgnoreCase("#tts") || content.startsWith(TTS_PREFIX);
                        if (!cmd && (trimmed.isEmpty() || trimmed.startsWith("<"))) return;
                        Object footer = param.thisObject;
                        String talker = footerTalker(footer);
                        if (talker == null || talker.isEmpty()) {
                            talker = ChatFooterLongPressMenu.resolveTalkerFrom(footer);
                        }
                        if (cmd) {
                            LogWriter.log(TAG, "ChatFooter input: content='"
                                    + truncStr(trimmed, 40) + "' talker=" + talker);
                        }
                        if (interceptOutgoingInput(talker, content, "ChatFooter")) {
                            setResultBoolean(param, true);
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "ChatFooter input cb err: " + t.getMessage());
                    }
                }
            };
            int dv = 0;
            java.util.Set<String> seenSig = new java.util.HashSet<>();
            Class<?> hc = chatFooter;
            while (hc != null && hc != Object.class) {
                for (java.lang.reflect.Method m : hc.getDeclaredMethods()) {
                    String n = m.getName();
                    if (!"d".equals(n) && !"V0".equals(n)) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    boolean hasString = false;
                    for (Class<?> p : pts) { if (p == String.class) { hasString = true; break; } }
                    if (!hasString) continue;
                    StringBuilder sig = new StringBuilder(n).append('(');
                    for (Class<?> p : pts) sig.append(p.getName()).append(',');
                    if (!seenSig.add(sig.toString())) continue;
                    try {
                        XposedBridge.hookMethod(m, inputCb);
                        dv++;
                    } catch (Throwable ignored) {
                    }
                }
                hc = hc.getSuperclass();
            }
            LogWriter.log(TAG, "Hook ChatFooter.d/V0 input intercept OK count=" + dv);
        } catch (Throwable t) {
            LogWriter.log(TAG, "Hook ChatFooter.F consume FAIL: " + t.getMessage());
        }
    }

    private static void hookSetTypeGuard(ClassLoader cl) {
        try {
            Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Class == null) {
                LogWriter.log(TAG, "setType guard: e9 class not found");
                return;
            }

            java.lang.reflect.Method setType = findSetTypeMethod(e9Class);
            if (setType == null) {
                LogWriter.log(TAG, "setType guard: setType(int) method not found");
                return;
            }
            setType.setAccessible(true);
            XposedBridge.hookMethod(setType, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0) return;
                    Object arg = param.args[0];
                    if (!(arg instanceof Integer)) return;
                    int type = (Integer) arg;
                    if (type == 34) return;

                    try {
                        String content = getMsgContent(param.thisObject);
                        if (isVoiceXml(content)) {
                            param.args[0] = 34;
                            LogWriter.log(TAG, "setType guard: " + type + " -> 34");
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "setType guard err: " + t.getMessage());
                    }
                }
            });
            LogWriter.log(TAG, "Hook setType(int) guard OK: " + setType.getDeclaringClass().getName());
        } catch (Throwable t) {
            LogWriter.log(TAG, "Hook e9.setType guard FAIL: " + t.getMessage());
        }
    }

    private static java.lang.reflect.Method findSetTypeMethod(Class<?> e9Class) {
        Class<?> cls = e9Class;
        while (cls != null && cls != Object.class) {
            try {
                return cls.getDeclaredMethod("setType", int.class);
            } catch (Throwable ignored) {}
            cls = cls.getSuperclass();
        }

        try {
            Class<?> c8 = XposedHelpers.findClass("dm.c8", voiceCl());
            return c8.getDeclaredMethod("setType", int.class);
        } catch (Throwable ignored) {}

        return null;
    }

    private static void hookA21Oi(ClassLoader cl) {
        // 8.0.78: a21.o 已混淆，使用 DexKit 扫描结果
        Class<?> a21o = null;
        String a21FromDexKit = DexKitHelper.getA21ClassName();
        if (a21FromDexKit != null && !a21FromDexKit.isEmpty()) {
            try {
                a21o = XposedHelpers.findClass(a21FromDexKit, cl);
                LogWriter.log(TAG, "hookA21Oi: a21 from DexKit: " + a21FromDexKit);
            } catch (Throwable ignored) {}
        }
        if (a21o == null) {
            String[] a21Candidates = {"a21.o", "a22.o", "a20.o", "a23.o", "b21.o", "b22.o"};
            for (String candidate : a21Candidates) {
                try {
                    a21o = XposedHelpers.findClass(candidate, cl);
                    LogWriter.log(TAG, "hookA21Oi: a21 from candidate: " + candidate);
                    break;
                } catch (Throwable ignored) {}
            }
        }
        if (a21o == null) {
            // 兜底：搜索包含 "a21" 的类
            List<String> candidates = DexKitHelper.findClassesByString(cl, "a21");
            for (String cn : candidates) {
                try {
                    Class<?> c = XposedHelpers.findClass(cn, cl);
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                        if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class) {
                            a21o = c;
                            LogWriter.log(TAG, "hookA21Oi: a21 from DexKit string: " + cn);
                            break;
                        }
                    }
                    if (a21o != null) break;
                } catch (Throwable ignored) {}
            }
        }
        if (a21o == null) {
            // 兜底：搜索包含 "a21" 的类
            List<String> candidates = DexKitHelper.findClassesByString(cl, "a21");
            for (String cn : candidates) {
                try {
                    Class<?> c = XposedHelpers.findClass(cn, cl);
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                        if ("i".equals(m.getName()) && m.getParameterCount() >= 2) {
                            a21o = c;
                            break;
                        }
                    }
                    if (a21o != null) break;
                } catch (Throwable ignored) {}
            }
        }
        if (a21o == null) {
            LogWriter.log(TAG, "Hook a21.o.i: a21.o class not found (8.0.78 renamed)");
            return;
        }
        try {
            checkCoroutineSuspended(cl);
            final Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            // v963: 实机 dump a21.o 仅 Object invoke() — Kotlin lambda stub, 真正方法在外层 a21
            // 或同包其它类。先认 stub, 再扩到 enclosing / 同包 / DexKit a21* 候选。
            List<Class<?>> searchClasses = new ArrayList<>();
            searchClasses.add(a21o);
            Class<?> enclosing = a21o.getEnclosingClass();
            if (enclosing != null) {
                searchClasses.add(enclosing);
                LogWriter.log(TAG, "hookA21Oi: enclosing=" + enclosing.getName());
            }
            String pkg = a21o.getName();
            int lastDot = pkg.lastIndexOf('.');
            if (lastDot > 0) {
                String pkgName = pkg.substring(0, lastDot);
                String outerSimple = pkgName.substring(pkgName.lastIndexOf('.') + 1);
                if (outerSimple.length() <= 4) {
                    try {
                        Class<?> outer = XposedHelpers.findClass(pkgName, cl);
                        if (!searchClasses.contains(outer)) {
                            searchClasses.add(outer);
                            LogWriter.log(TAG, "hookA21Oi: outer pkg class=" + outer.getName());
                        }
                    } catch (Throwable ignored) {}
                }
                List<String> a21Classes = DexKitHelper.findClassesByString(cl, pkgName);
                int added = 0;
                for (String cn : a21Classes) {
                    if (cn == null || !cn.startsWith(pkgName)) continue;
                    try {
                        Class<?> c = XposedHelpers.findClass(cn, cl);
                        if (!searchClasses.contains(c)) {
                            searchClasses.add(c);
                            added++;
                            if (added >= 24) break;
                        }
                    } catch (Throwable ignored) {}
                }
                if (added > 0) LogWriter.log(TAG, "hookA21Oi: +DexKit siblings=" + added);
            }
            java.lang.reflect.Method target = null;
            String hitHint = null;
            for (Class<?> cls : searchClasses) {
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    if (!m.getName().equals("i")) continue;
                    if (m.getParameterCount() < 1) continue;
                    target = m;
                    a21o = cls;
                    hitHint = "name=i class=" + cls.getName();
                    break;
                }
                if (target != null) break;
            }
            if (target == null) {
                for (Class<?> cls : searchClasses) {
                    for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                        if (m.getParameterCount() < 2) continue;
                        if (m.getReturnType().isPrimitive()) continue;
                        try {
                            Class<?>[] pts = m.getParameterTypes();
                            boolean match = false;
                            for (Class<?> p : pts) {
                                if (e9Class != null && e9Class.isAssignableFrom(p)) { match = true; break; }
                                for (Class<?> c = p; c != null && c != Object.class && !match; c = c.getSuperclass()) {
                                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                                        if ("b".equals(f.getName())
                                                || (e9Class != null && f.getType() == e9Class)) {
                                            match = true;
                                            break;
                                        }
                                    }
                                }
                            }
                            if (!match) continue;
                            target = m;
                            a21o = cls;
                            hitHint = "sig class=" + cls.getName() + " " + m.getName()
                                    + "(" + m.getParameterCount() + ")";
                            break;
                        } catch (Throwable ignored) {}
                    }
                    if (target != null) break;
                }
            }
            if (hitHint != null) LogWriter.log(TAG, "hookA21Oi: 命中 " + hitHint);
            if (target == null) {
                StringBuilder dump = new StringBuilder("Hook a21.o.i: method not found, searched=")
                        .append(searchClasses.size()).append(" classes:");
                for (Class<?> cls : searchClasses) {
                    dump.append("\n  class=").append(cls.getName());
                    for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                        dump.append("\n    ").append(m.getReturnType().getSimpleName()).append(' ')
                            .append(m.getName()).append('(');
                        Class<?>[] pts = m.getParameterTypes();
                        for (int i = 0; i < pts.length; i++) {
                            if (i > 0) dump.append(',');
                            dump.append(pts[i].getSimpleName());
                        }
                        dump.append(')');
                    }
                }
                LogWriter.log(TAG, dump.toString());
                return;
            }
            {
                java.lang.reflect.Method m = target;
                final String mName = m.getName();
                StringBuilder sig = new StringBuilder(mName).append('(');
                for (Class<?> pt : m.getParameterTypes()) {
                    if (sig.length() > mName.length() + 1) sig.append(',');
                    sig.append(pt.getName());
                }
                sig.append(')');
                final String sigStr = sig.toString();
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (p.args == null || p.args.length < 2) return;
                            if (sA21ParamTypesLogged.add(sigStr)) {
                                StringBuilder sb = new StringBuilder("a21.o.i sig=").append(sigStr);
                                for (int i = 0; i < p.args.length; i++) {
                                    Object a = p.args[i];
                                    sb.append("\n  arg").append(i).append(' ')
                                      .append(a == null ? "null" : a.getClass().getName());
                                }
                                sb.append("\n  this=").append(p.thisObject == null ? "null"
                                        : p.thisObject.getClass().getName());
                                LogWriter.log(TAG, sb.toString());
                            }
                            if (e9Class == null) return;
                            Object e9 = XposedHelpers.getObjectField(p.args[1], "b");
                            if (e9 == null || !e9Class.isInstance(e9)) return;
                            String content = getMsgContent(e9);
                            String talker = getTalker(e9);
                            int type = getMsgType(e9);
                            LogWriter.log(TAG, "a21.o.i e9.b: type=" + type + " talker=" + talker
                                    + " content=" + truncStr(content, 60));
                            if (content != null && content.startsWith(TTS_PREFIX)) {
                                LogWriter.log(TAG, "a21.o.i MARKED #tts pre-populated (content present at before) "
                                        + "talker=" + talker + " stack=" + stackTrace(4));
                            }
                            if (p.args.length >= 3 && p.args[2] != null && sContFieldsLogged.add(p.args[2].getClass().getName())) {
                                StringBuilder sb = new StringBuilder("a21.o.i cont "
                                        + p.args[2].getClass().getName() + " fields:");
                                for (java.lang.reflect.Field f : p.args[2].getClass().getDeclaredFields()) {
                                    try {
                                        f.setAccessible(true);
                                        Object v = f.get(p.args[2]);
                                        sb.append("\n  ").append(f.getType().getSimpleName()).append(' ')
                                          .append(f.getName());
                                        if (v instanceof String) {
                                            sb.append(" = ").append(truncStr((String) v, 50));
                                        } else if (v instanceof Integer || v instanceof Long
                                                || v instanceof Boolean) {
                                            sb.append(" = ").append(v);
                                        }
                                    } catch (Throwable ignored) {}
                                }
                                LogWriter.log(TAG, sb.toString());
                            }
                            if (p.args[0] != null && sArg0FieldsLogged.add(p.args[0].getClass().getName())) {
                                StringBuilder sb = new StringBuilder("a21.o.i arg0 "
                                        + p.args[0].getClass().getName() + " fields:");
                                for (java.lang.reflect.Field f : p.args[0].getClass().getDeclaredFields()) {
                                    try {
                                        f.setAccessible(true);
                                        Object v = f.get(p.args[0]);
                                        sb.append("\n  ").append(f.getType().getSimpleName()).append(' ')
                                          .append(f.getName());
                                        if (v instanceof String) {
                                            sb.append(" = ").append(truncStr((String) v, 60));
                                        } else if (v instanceof Integer || v instanceof Long
                                                || v instanceof Boolean) {
                                            sb.append(" = ").append(v);
                                        } else if (v != null) {
                                            sb.append(" : ").append(v.getClass().getName());
                                        }
                                    } catch (Throwable ignored) {}
                                }
                                LogWriter.log(TAG, sb.toString());
                            }
                            if (p.args[0] != null) {
                                Object g = null;
                                try {
                                    g = XposedHelpers.getObjectField(p.args[0], "e");
                                } catch (Throwable ignored) {}
                                if (g != null && sArg0EFieldsLogged.add(g.getClass().getName())) {
                                    StringBuilder sb = new StringBuilder("a21.o.i arg0.e = "
                                            + g.getClass().getName() + " fields:");
                                    for (java.lang.reflect.Field f : g.getClass().getDeclaredFields()) {
                                        try {
                                            f.setAccessible(true);
                                            Object v = f.get(g);
                                            sb.append("\n  ").append(f.getType().getSimpleName()).append(' ')
                                              .append(f.getName());
                                            if (v instanceof String) {
                                                sb.append(" = ").append(truncStr((String) v, 60));
                                            } else if (v instanceof Integer || v instanceof Long
                                                    || v instanceof Boolean) {
                                                sb.append(" = ").append(v);
                                            } else if (v != null) {
                                                sb.append(" : ").append(v.getClass().getName());
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                    LogWriter.log(TAG, sb.toString());
                                }
                                if (g != null) {
                                    try {
                                        Object mapObj = XposedHelpers.getObjectField(g, "e");
                                        if (mapObj instanceof java.util.Map) {
                                            StringBuilder sb = new StringBuilder("a21.o.i q06.n map:");
                                            for (java.util.Map.Entry<?, ?> entry
                                                    : ((java.util.Map<?, ?>) mapObj).entrySet()) {
                                                sb.append("\n  ").append(String.valueOf(entry.getKey()))
                                                  .append(" = ").append(truncStr(String.valueOf(entry.getValue()), 60));
                                            }
                                            LogWriter.log(TAG, sb.toString());
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                LogWriter.log(TAG, "Hook a21.o.i OK sig=" + sigStr);
                return;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "Hook a21.o.i FAIL: " + t.getMessage());
        }
    }

    private static void checkCoroutineSuspended(ClassLoader cl) {
        String[] attempts = {
            "kotlin.coroutines.intrinsics.CoroutineSingletons",
            "kotlin.coroutines.intrinsics.IntrinsicsKt",
            "kotlinx.coroutines.intrinsics.CoroutineSingletons",
            "kotlin.coroutines.intrinsics.b"
        };
        for (String name : attempts) {
            try {
                Class<?> cs = XposedHelpers.findClass(name, cl);
                Object suspended = XposedHelpers.getStaticObjectField(cs, "COROUTINE_SUSPENDED");
                sCoroutineSuspended = suspended;
                LogWriter.log(TAG, "COROUTINE_SUSPENDED accessible: " + (suspended != null)
                        + " class=" + cs.getName());
                return;
            } catch (Throwable ignored) {}
        }
        try {
            Class<?> cs = XposedHelpers.findClass("kotlin.Result", cl);
            try {
                sCoroutineSuspended = XposedHelpers.getStaticObjectField(cs, "Companion");
                if (sCoroutineSuspended != null) return;
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static Object sCoroutineSuspended;

    private static void dumpClass(String name, ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(name, cl);
            StringBuilder sb = new StringBuilder("dump " + name + " methods:");
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (m.getName().length() > 5) continue;
                sb.append("\n  ").append(m.getName()).append('(');
                Class<?>[] pts = m.getParameterTypes();
                for (int i = 0; i < pts.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(pts[i].getSimpleName());
                }
                sb.append(')').append(m.getReturnType().getSimpleName());
            }
            LogWriter.log(TAG, sb.toString());
        } catch (Throwable t) {
            LogWriter.log(TAG, "dumpClass " + name + " FAIL: " + t.getClass().getSimpleName());
        }
    }

    private static final Set<String> sDiscoveredClasses = new HashSet<>();

    private static void autoDiscoverClasses(ClassLoader cl, String hostClass) {
        try {
            Class<?> host = XposedHelpers.findClass(hostClass, cl);
            for (java.lang.reflect.Field f : host.getDeclaredFields()) {
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft.isArray()) continue;
                String fn = ft.getName();
                String sn = ft.getSimpleName();
                if (fn.startsWith("java.") || fn.startsWith("android.") || fn.startsWith("kotlin.")) continue;
                if (fn.startsWith("com.tencent.mm.ui.chatting.")) continue;
                if (sn.length() > 6) continue;
                if (sDiscoveredClasses.add(fn)) {
                    hookNamedClassAll(fn, cl, sn);
                }
            }
        } catch (Throwable t) {
            // silent - class not found, skip
        }
    }

    private static void hookB31W(ClassLoader cl) {
        try {
            Class<?> b31w = XposedHelpers.findClass("b31.w", cl);
            int hooked = 0;
            for (java.lang.reflect.Method m : b31w.getDeclaredMethods()) {
                String n = m.getName();
                if (!n.equals("start") && !n.equals("init") && !n.equals("onSceneEnd")
                        && !n.equals("stop") && !n.equals("doScene")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            StringBuilder sb = new StringBuilder("b31.w ").append(m.getName()).append('(');
                            if (p.args != null) {
                                for (int i = 0; i < p.args.length; i++) {
                                    if (i > 0) sb.append(',');
                                    Object a = p.args[i];
                                    sb.append(a == null ? "null"
                                            : (a instanceof String ? "'" + truncStr((String) a, 60) + "'"
                                                    : a.getClass().getSimpleName()));
                                }
                            }
                            sb.append(')');
                            LogWriter.log(TAG, sb.toString());
                        } catch (Throwable ignored) {}
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "b31.w " + m.getName() + " ret=" + p.getResult()
                                    + " this=" + (p.thisObject == null ? "null"
                                            : p.thisObject.getClass().getName()));
                        } catch (Throwable ignored) {}
                    }
                });
                hooked++;
            }
            LogWriter.log(TAG, "b31.w hooks: " + hooked);
        } catch (Throwable t) {
            LogWriter.log(TAG, "b31.w FAIL: " + t.getMessage());
        }
    }

    private static void hookF9I9Diag(ClassLoader cl) {
        try {
            Class<?> f9 = XposedHelpers.findClass("com.tencent.mm.storage.f9", cl);
            Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Class == null) return;
            int hooked = 0;
            for (java.lang.reflect.Method m : f9.getDeclaredMethods()) {
                if (!m.getName().equals("I9")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            Object msg = p.args[0];
                            if (msg == null || !e9Class.isInstance(msg)) return;
                            LogWriter.log(TAG, "f9.I9 insert: marked=" + isMarkedMessage(msg)
                                    + " content=" + truncStr(getMsgContent(msg), 40)
                                    + " talker=" + getTalker(msg) + " type=" + getMsgType(msg));
                        } catch (Throwable ignored) {}
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "f9.I9 inserted ret=" + p.getResult());
                        } catch (Throwable ignored) {}
                    }
                });
                hooked++;
            }
            LogWriter.log(TAG, "f9.I9 diag hooks: " + hooked);
        } catch (Throwable t) {
            LogWriter.log(TAG, "f9.I9 diag FAIL: " + t.getMessage());
        }
    }

    private static void hookAdapterKJ(ClassLoader cl) {
        try {
            final Class<?> kClass = XposedHelpers.findClass("com.tencent.mm.ui.chatting.adapter.k", cl);
            final Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Class == null) {
                LogWriter.log(TAG, "adapter.k.j: e9 class not found");
                return;
            }
            int hooked = 0;
            for (java.lang.reflect.Method m : kClass.getDeclaredMethods()) {
                if (!m.getName().equals("j") || m.getParameterCount() != 1) continue;
                final String sig = "j(" + m.getParameterTypes()[0].getSimpleName() + ")";
                final Class<?> rt = m.getReturnType();
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            Object item = p.args[0];
                            if (item == null) return;
                            // v980: 快速路径 —— 无任何标记消息且不在 TTS 抑制窗口内时, 本条消息
                            // 与标记移除无关, 直接返回; 避免每次消息渲染都做深度反射扫描
                            // (findMarkedMessageIn 最深递归 4 层), 这是聊天窗口卡顿的主因之一。
                            if (sBlockedOriginalMessages.isEmpty() && sMarkedMsgIds.isEmpty()
                                    && System.currentTimeMillis() - sLastTtsCommandAt
                                        > FAILURE_SUPPRESS_WINDOW_MS) {
                                return;
                            }
                            long now = System.currentTimeMillis();
                            if (now - sLastKjCallLogAt > 3000) {
                                sLastKjCallLogAt = now;
                                LogWriter.log(TAG, "adapter.k." + sig + " called item="
                                        + item.getClass().getName() + " window="
                                        + (now - sLastTtsCommandAt));
                                StringBuilder dbg = new StringBuilder("adapter.k." + sig + " item fields:");
                                for (java.lang.reflect.Field f : item.getClass().getDeclaredFields()) {
                                    try {
                                        f.setAccessible(true);
                                        Object v = f.get(item);
                                        dbg.append("\n  ").append(f.getName()).append(' ')
                                           .append(f.getType().getSimpleName())
                                           .append(" = ").append(v == null ? "null"
                                                : (v.getClass().getName() + ":" + truncStr(v.toString(), 20)));
                                    } catch (Throwable ignored) {}
                                }
                                LogWriter.log(TAG, dbg.toString());
                            }
                            Object marked = findMarkedMessageIn(item, e9Class);
                            if (marked == null) {
                                if (System.currentTimeMillis() - sLastTtsCommandAt <= FAILURE_SUPPRESS_WINDOW_MS) {
                                    int lr = removeMarkedFromLists(item, e9Class);
                                    if (lr > 0) {
                                        LogWriter.log(TAG, "adapter.k." + sig + " loose REMOVE removed=" + lr
                                                + " window=" + (System.currentTimeMillis() - sLastTtsCommandAt));
                                        return;
                                    }
                                }
                                return;
                            }
                            LogWriter.log(TAG, "adapter.k." + sig + " REMOVE marked #tts item="
                                    + item.getClass().getName() + " msg=" + System.identityHashCode(marked));
                            int removed = removeMarkedFromLists(item, e9Class);
                            LogWriter.log(TAG, "adapter.k." + sig + " removed=" + removed
                                    + " continue render");
                        } catch (Throwable ignored) {}
                    }
                });
                LogWriter.log(TAG, "Hook adapter.k.j OK " + sig);
                hooked++;
            }
            LogWriter.log(TAG, "adapter.k.j hooks: " + hooked);
        } catch (Throwable t) {
            LogWriter.log(TAG, "adapter.k.j FAIL: " + t.getMessage());
        }
    }

    private static int removeMarkedFromLists(Object item, Class<?> e9Class) {
        int removed = 0;
        if (item == null) return 0;
        for (java.lang.reflect.Field f : item.getClass().getDeclaredFields()) {
            try {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(item);
                if (v instanceof java.util.List) {
                    java.util.List<?> list = (java.util.List<?>) v;
                    for (java.util.Iterator<?> it = list.iterator(); it.hasNext(); ) {
                        Object e = it.next();
                        if (e9Class.isInstance(e) && (isMarkedMessage(e) || isMarkedByMsgId(e)
                                || isLikelySuppressedTts(e))) {
                            long mid = 0;
                            try { mid = (Long) XposedHelpers.callMethod(e, "H0"); } catch (Throwable ignored) {}
                            LogWriter.log(TAG, "adapter.k REMOVE e9 msgId=" + mid
                                    + " idHash=" + System.identityHashCode(e));
                            it.remove();
                            removed++;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return removed;
    }

    private static void hookF9I9(ClassLoader cl) {
        try {
            try {
                ClassLoader tk = VersionCompat.findTinkerClassLoader(cl);
                if (tk != null && !tk.getClass().getName().contains("Leshao") && tk != cl) {
                    cl = tk;
                }
            } catch (Throwable ignored) {
            }
            final Class<?> f9 = XposedHelpers.findClass("com.tencent.mm.storage.f9", cl);
            final Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Class == null) {
                LogWriter.log(TAG, "f9.I9: e9 class not found");
                return;
            }
            int hooked = 0;
            for (java.lang.reflect.Method m : f9.getDeclaredMethods()) {
                if (!m.getName().equals("I9")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            Object msg = p.args[0];
                            if (msg == null || !e9Class.isInstance(msg)) return;
                            boolean marked = isMarkedMessage(msg);
                            String content = getMsgContent(msg);
                            LogWriter.log(TAG, "f9.I9 called marked=" + marked + " content="
                                    + truncStr(content, 800) + " talker=" + getTalker(msg)
                                    + " msg=" + System.identityHashCode(msg));
                            captureIncomingVoice(msg, content);
                            tryInterceptMusicCard(msg, content);
                            int msgKey = System.identityHashCode(msg);
                            boolean blocked = false;
                            synchronized (sSuppressedMessages) { blocked = sSuppressedMessages.remove(msgKey); }
                            if (!blocked) {
                                synchronized (sBlockedOriginalMessages) { blocked = sBlockedOriginalMessages.remove(msgKey); }
                            }
                            if (!blocked && System.currentTimeMillis() - sLastTtsCommandAt <= FAILURE_SUPPRESS_WINDOW_MS) {
                                if (content == null || content.trim().isEmpty()) {
                                    String t = getTalker(msg);
                                    if (sLastTtsTalker == null || t == null || sLastTtsTalker.equals(t)) {
                                        blocked = true;
                                    }
                                }
                            }
                            if (blocked) {
                                Class<?> rt = p.method instanceof java.lang.reflect.Method
                                        ? ((java.lang.reflect.Method) p.method).getReturnType() : null;
                                p.setResult(defaultReturnValue(rt));
                                LogWriter.log(TAG, "f9.I9 BLOCK #tts suppressed msg=" + msgKey + " rt=" + (rt != null ? rt.getSimpleName() : "null"));
                                return;
                            }
                            if (marked) {
                                LogWriter.log(TAG, "f9.I9 PASS marked #tts insert (no block)");
                            }
                        } catch (Throwable ignored) {}
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object msg = p.args[0];
                            if (msg == null || !e9Class.isInstance(msg)) return;
                            String afterContent = getMsgContent(msg);
                            if (afterContent != null && afterContent.contains("<voicemsg")) {
                                captureIncomingVoice(msg, afterContent);
                            }
                            if (!isMarkedMessage(msg)) return;
                            long msgId = 0;
                            try { msgId = (Long) XposedHelpers.callMethod(msg, "H0"); } catch (Throwable ignored) {}
                            if (msgId > 0) {
                                synchronized (sMarkedMsgIds) {
                                    sMarkedMsgIds.add(msgId);
                                    if (sMarkedMsgIds.size() > 64) sMarkedMsgIds.clear();
                                }
                                LogWriter.log(TAG, "f9.I9 after: captured marked msgId=" + msgId);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                hooked++;
                LogWriter.log(TAG, "Hook f9.I9 OK " + m.getParameterCount() + " params");
            }
            LogWriter.log(TAG, "f9.I9 hooks: " + hooked);
        } catch (Throwable t) {
            LogWriter.log(TAG, "f9.I9 FAIL: " + t.getMessage());
        }
    }

    /**
     * 8.0.78(3180) TTS #tts 主入口适配: hook f9.Bb(e9, boolean) = MsgInfoStorage.insertMsgInfo。
     * 文档方案A推荐的最稳入库入口，MessageHook 已验证当前版本可命中。
     * 在入库前拦截发送的自定义 #tts 文本, 触发 TTS 合成 + SceneVoice 语音发送, 并抑制原文本入库。
     */
    private static void hookF9Bb(ClassLoader cl) {
        try {
            // v1042: 切换真实 Tinker CL, 否则 hook 挂在 base.apk 平行副本上运行时零捕获
            try {
                ClassLoader tk = VersionCompat.findTinkerClassLoader(cl);
                if (tk != null && !tk.getClass().getName().contains("Leshao") && tk != cl) {
                    LogWriter.log(TAG, "hookF9Bb: 使用 Tinker 真实 CL " + tk.getClass().getSimpleName());
                    cl = tk;
                }
            } catch (Throwable ignored) {
            }
            final Class<?> f9 = XposedHelpers.findClass("com.tencent.mm.storage.f9", cl);
            final Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Class == null) {
                LogWriter.log(TAG, "f9.Bb: e9 class not found");
                return;
            }
            int hooked = 0;
            for (java.lang.reflect.Method m : f9.getDeclaredMethods()) {
                if (!m.getName().equals("Bb")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 1 || pts[0] == null) continue;
                String p0 = pts[0].getName();
                if (!p0.endsWith(".e9") && !p0.contains("MsgInfo")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (p.args.length < 1 || p.args[0] == null) return;
                            Object msg = p.args[0];
                            if (!e9Class.isInstance(msg)) return;
                            int isSend = getMsgIsSend(msg);
                            if (isSend != 1) return;
                            String content = getMsgContent(msg);
                            String trimmed = content == null ? "" : content.trim();
                            String talker = getTalker(msg);
                            // 纯指令 "#tts": 切换本会话 TTS 模式, 并阻止入库(避免空消息残留)
                            if ("#tts".equalsIgnoreCase(trimmed)) {
                                handleTtsToggleCommand(talker, "f9.Bb");
                                blockCurrentInsert(p, msg);
                                return;
                            }
                            String text;
                            if (content != null && content.startsWith(TTS_PREFIX)) {
                                text = content.substring(TTS_PREFIX.length()).trim();
                                if (text.isEmpty()) { blockCurrentInsert(p, msg); return; }
                                if (markRecentText(text, System.currentTimeMillis())) {
                                    LogWriter.log(TAG, "f9.Bb duplicate #tts suppressed: " + text);
                                    blockCurrentInsert(p, msg);
                                    return;
                                }
                            } else if (isTtsMode(talker) && isPlainTextMsg(msg, content)) {
                                // TTS 模式下直发文字: 自动转语音
                                if (markRecentModeSend(talker, trimmed)) {
                                    LogWriter.log(TAG, "f9.Bb duplicate TTS-mode text suppressed");
                                    blockCurrentInsert(p, msg);
                                    return;
                                }
                                text = trimmed;
                                LogWriter.log(TAG, "f9.Bb TTS-mode auto talker=" + talker
                                        + " text='" + truncStr(text, 40) + "'");
                            } else {
                                return;
                            }
                            String clientMsgId = getClientMsgId(msg);
                            markBlockedOriginal(msg);
                            markRecentTtsCommand(msg);
                            // 彻底阻止原文本入库: 旧实现仅清空 content 后仍入库,
                            // 在适配器移除 hook 失效的机型上会残留一条空消息。
                            blockCurrentInsert(p, msg);
                            if (clientMsgId != null) {
                                synchronized (sSceneSentIds) { sSceneSentIds.add(clientMsgId); }
                            }
                            LogWriter.log(TAG, "f9.Bb #tts matched -> async SceneVoice talker=" + talker
                                    + " cid=" + clientMsgId + " text='" + truncStr(text, 40) + "'");
                            startAsyncTts(talker, clientMsgId, text, "f9.Bb");
                        } catch (Throwable e) {
                            LogWriter.log(TAG, "f9.Bb cb err: " + e.getMessage());
                        }
                    }
                });
                LogWriter.log(TAG, "Hook f9.Bb OK " + pts.length + " params p0=" + p0);
                hooked++;
            }
            LogWriter.log(TAG, "f9.Bb hooks: " + hooked);
        } catch (Throwable t) {
            LogWriter.log(TAG, "f9.Bb FAIL: " + t.getMessage());
        }
    }

    /**
     * f9.Ra(long msgId, e9 msg) 是真正的消息入库层(与"敏感词入库拦截"同款入口,
     * 也是模块 GroupFeatures 主动发文本时调用的入库 API)。在此拦截自己发出的
     * #tts / TTS 模式文本, 阻止其入库为文本消息, 改发语音。
     */
    private static void hookF9RaForTts(ClassLoader cl) {
        try {
            try {
                ClassLoader tk = VersionCompat.findTinkerClassLoader(cl);
                if (tk != null && !tk.getClass().getName().contains("Leshao") && tk != cl) {
                    cl = tk;
                }
            } catch (Throwable ignored) {}
            Class<?> f9 = VersionCompat.findMsgStorageClass(cl);
            if (f9 == null) {
                LogWriter.log(TAG, "f9.Ra TTS: f9 class not found");
                return;
            }
            final Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            XC_MethodHook cb = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (e9Class == null || p.args == null) return;
                        Object msg = null;
                        for (Object a : p.args) {
                            if (e9Class.isInstance(a)) { msg = a; break; }
                        }
                        if (msg == null) return;
                        if (getMsgIsSend(msg) != 1) return;
                        if (handleOutgoingX9(msg)) {
                            LogWriter.log(TAG, "f9.Ra/yb BLOCK outgoing #tts/mode msg="
                                    + System.identityHashCode(msg));
                            p.setResult(defaultReturnValue(methodReturnType(p)));
                        }
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "f9.Ra TTS cb err: " + e.getMessage());
                    }
                }
            };
            XposedBridge.hookAllMethods(f9, "Ra", cb);
            XposedBridge.hookAllMethods(f9, "yb", cb);
            LogWriter.log(TAG, "f9.Ra/yb TTS block hooks installed");
        } catch (Throwable t) {
            LogWriter.log(TAG, "f9.Ra TTS FAIL: " + t.getMessage());
        }
    }

    /**
     * v1073 兜底：hook UI 发送入口 {@code om.A0(String content, int atType, Map atMap)}
     * （DexKit 锚点字符串 {@code MicroMsg.ChattingUI.SendTextComponent}）。
     * 覆盖 f9.Bb / x9 / f9.Ra 均未命中的机型：命中 #tts / TTS 模式文本时阻止原文本发出并转语音。
     * 当前会话 talker 取 {@link ChatFooterLongPressMenu#currentTalker()} 缓存。
     */
    private static void hookOmA0(ClassLoader cl) {
        try {
            try {
                ClassLoader tk = VersionCompat.findTinkerClassLoader(cl);
                if (tk != null && !tk.getClass().getName().contains("Leshao") && tk != cl) {
                    cl = tk;
                }
            } catch (Throwable ignored) {
            }
            final ClassLoader fcl = cl;
            java.util.List<String> names = new java.util.ArrayList<>(
                    DexKitHelper.findClassesByString(
                            fcl, "MicroMsg.ChattingUI.SendTextComponent"));
            if (!names.contains("com.tencent.mm.ui.chatting.component.om")) {
                names.add("com.tencent.mm.ui.chatting.component.om");
            }
            int installed = 0;
            for (String cn : names) {
                try {
                    Class<?> c = XposedHelpers.findClass(cn, fcl);
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length < 2 || pts[0] != String.class) continue;
                        // 发送签名: (String content, int atType, Map/HashMap atMap);
                        // 兼容 A0 及混淆后可能改名的方法, 用形态匹配而非方法名。
                        boolean mapTail = false;
                        for (int i = 1; i < pts.length; i++) {
                            if (java.util.Map.class.isAssignableFrom(pts[i])) { mapTail = true; break; }
                        }
                        if (!mapTail && !m.getName().equals("A0")) continue;
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                try {
                                    if (p.args == null || p.args.length < 1
                                            || !(p.args[0] instanceof String)) return;
                                    String content = (String) p.args[0];
                                    String talker = ChatFooterLongPressMenu.currentTalker();
                                    if (talker == null || talker.isEmpty()) {
                                        talker = ChatFooterLongPressMenu.resolveTalkerFrom(p.thisObject);
                                    }
                                    if (interceptOutgoingInput(talker, content, "om.A0")) {
                                        LogWriter.log(TAG, "om.A0 BLOCK outgoing, talker=" + talker
                                                + " text='" + truncStr(content.trim(), 40) + "'");
                                        p.setResult(defaultReturnValue(methodReturnType(p)));
                                    }
                                } catch (Throwable e) {
                                    LogWriter.log(TAG, "om.A0 cb err: " + e.getMessage());
                                }
                            }
                        });
                        installed++;
                    }
                } catch (Throwable ignored) {
                }
            }
            LogWriter.log(TAG, "om.A0 TTS fallback hooks installed=" + installed
                    + " candidates=" + names.size());
        } catch (Throwable t) {
            LogWriter.log(TAG, "om.A0 TTS FAIL: " + t.getMessage());
        }
    }

    private static Object findMarkedMessageIn(Object item, Class<?> e9Class) {        return findMarkedMessageIn(item, e9Class, 0);
    }

    private static Object findMarkedMessageIn(Object item, Class<?> e9Class, int depth) {
        if (item == null) return null;
        if (e9Class.isInstance(item)) {
            return (isMarkedMessage(item) || isMarkedByMsgId(item)) ? item : null;
        }
        if (depth > 4) return null;
        for (java.lang.reflect.Field f : item.getClass().getDeclaredFields()) {
            try {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(item);
                if (v == null) continue;
                if (e9Class.isInstance(v)) {
                    if (isMarkedMessage(v) || isMarkedByMsgId(v)) return v;
                    continue;
                }
                if (v instanceof Iterable) {
                    for (Object e : (Iterable<?>) v) {
                        Object r = findMarkedMessageIn(e, e9Class, depth + 1);
                        if (r != null) return r;
                    }
                    continue;
                }
                if (v instanceof Object[]) {
                    for (Object e : (Object[]) v) {
                        Object r = findMarkedMessageIn(e, e9Class, depth + 1);
                        if (r != null) return r;
                    }
                    continue;
                }
                if (v.getClass().getName().startsWith("com.tencent.mm")) {
                    Object r = findMarkedMessageIn(v, e9Class, depth + 1);
                    if (r != null) return r;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static void hookNamedClassAll(String name, ClassLoader cl, String label) {
        try {
            Class<?> cls = XposedHelpers.findClass(name, cl);
            int hooked = hookClassAll(cls, cl, label);
            LogWriter.log(TAG, label + " all hooks: " + hooked);
        } catch (Throwable t) {
            LogWriter.log(TAG, label + " all FAIL: " + t.getMessage());
        }
    }

    private static void hookChattingUiAll(ClassLoader cl) {
        try {
            Class<?> chattingUi = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUI", cl);
            int hooked = hookClassAll(chattingUi, cl, "ChattingUI");
            LogWriter.log(TAG, "ChattingUI all hooks: " + hooked);
        } catch (Throwable t) {
            LogWriter.log(TAG, "ChattingUI all FAIL: " + t.getMessage());
        }
    }

    private static void hookChattingUIFragmentAll(ClassLoader cl) {
        try {
            Class<?> chattingFragment = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUIFragment", cl);
            int hooked = hookClassAll(chattingFragment, cl, "ChattingUIFragment");
            LogWriter.log(TAG, "ChattingUIFragment all hooks: " + hooked);
            for (java.lang.reflect.Method m : chattingFragment.getDeclaredMethods()) {
                if (!m.getName().equals("onResume")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object t = XposedHelpers.getObjectField(p.thisObject, "t");
                            Object u = XposedHelpers.getObjectField(p.thisObject, "u");
                            Object F = XposedHelpers.getObjectField(p.thisObject, "F");
                            StringBuilder sb = new StringBuilder("ChattingUIFragment runtime fields:");
                            sb.append("\n  t=").append(t == null ? "null" : t.getClass().getName());
                            sb.append("\n  u=").append(u == null ? "null" : u.getClass().getName());
                            sb.append("\n  F=").append(F == null ? "null" : F.getClass().getName());
                            LogWriter.log(TAG, sb.toString());
                            if (t != null) {
                                String tn = t.getClass().getName();
                                if (sDiscoveredClasses.add("rt:" + tn)) {
                                    hookNamedClassAll(tn, cl, t.getClass().getSimpleName());
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                break;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "ChattingUIFragment all FAIL: " + t.getMessage());
        }
    }

    private static int hookClassAll(final Class<?> cls, ClassLoader cl, final String label) {
        final Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
        if (e9Class == null) {
            LogWriter.log(TAG, label + " all: e9 class not found");
            return 0;
        }
        int hooked = 0;
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        long window = System.currentTimeMillis() - sLastTtsCommandAt;
                        if (window < 0 || window > 5000) return;
                        if (findMarkedInArgs(p.args, e9Class) == null) return;
                        LogWriter.log(TAG, label + "." + m.getName() + "(" + argSummary(p.args)
                                + ") contains marked e9");
                    } catch (Throwable ignored) {}
                }
            });
            hooked++;
            } catch (Throwable ignored) {}
        }
        return hooked;
    }

    private static Object findMarkedInArgs(Object[] args, Class<?> e9Class) {
        if (args == null) return null;
        for (Object a : args) {
            if (a == null) continue;
            if (e9Class.isInstance(a)) {
                if (isMarkedMessage(a)) return a;
            } else if (a instanceof java.util.List) {
                for (Object item : (java.util.List<?>) a) {
                    if (item != null && e9Class.isInstance(item) && isMarkedMessage(item)) return item;
                }
            } else if (a instanceof Object[]) {
                for (Object item : (Object[]) a) {
                    if (item != null && e9Class.isInstance(item) && isMarkedMessage(item)) return item;
                }
            }
        }
        return null;
    }

    private static String argSummary(Object[] args) {
        if (args == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(',');
            Object a = args[i];
            if (a == null) {
                sb.append("null");
            } else {
                sb.append(a.getClass().getSimpleName());
            }
        }
        return sb.toString();
    }

    private static final Set<String> sRenderLogged = new HashSet<>();
    private static volatile long sLastKjCallLogAt;

    private static void hookE9Render(ClassLoader cl) {
        try {
            Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Class == null) {
                LogWriter.log(TAG, "e9 render: class not found");
                return;
            }
            String[] names = {"isVideo", "S1", "j"};
            int hooked = 0;
            for (String name : names) {
                for (java.lang.reflect.Method m : e9Class.getDeclaredMethods()) {
                    if (!m.getName().equals(name)) continue;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (!isMarkedMessage(p.thisObject)) return;
                                if (!sRenderLogged.add(m.getName())) return;
                                String thread = Thread.currentThread().getName();
                                LogWriter.log(TAG, "e9 render " + m.getName() + " thread=" + thread
                                        + " stack=" + stackTrace(12));
                            } catch (Throwable ignored) {}
                        }
                    });
                    hooked++;
                }
            }
            LogWriter.log(TAG, "e9 render hooks: " + hooked);
        } catch (Throwable t) {
            LogWriter.log(TAG, "e9 render FAIL: " + t.getMessage());
        }
    }

    private static void hookE9Trace(ClassLoader cl) {
        try {
            Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Class == null) {
                LogWriter.log(TAG, "e9 trace: class not found");
                return;
            }
            String[] names = {"A1", "j1", "U0", "B1", "F1", "X0", "Z0", "i1", "n3", "p3",
                    "q1", "q3", "s2", "s3", "w3", "m3", "o3", "L1", "P1"};
            int hooked = 0;
            for (String name : names) {
                for (java.lang.reflect.Method m : e9Class.getDeclaredMethods()) {
                    if (!m.getName().equals(name)) continue;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (!isMarkedMessage(p.thisObject)) return;
                                StringBuilder sb = new StringBuilder("e9 trace ").append(m.getName()).append('(');
                                if (p.args != null) {
                                    for (int i = 0; i < p.args.length; i++) {
                                        if (i > 0) sb.append(',');
                                        Object a = p.args[i];
                                        sb.append(a == null ? "null"
                                                : (a instanceof String ? "'" + truncStr((String) a, 24) + "'"
                                                        : String.valueOf(a)));
                                    }
                                }
                                sb.append(')');
                                LogWriter.log(TAG, sb.toString());
                            } catch (Throwable ignored) {}
                        }
                    });
                    hooked++;
                    break;
                }
            }
            LogWriter.log(TAG, "e9 trace hooks installed: " + hooked);
        } catch (Throwable t) {
            LogWriter.log(TAG, "e9 trace FAIL: " + t.getMessage());
        }
    }

    private static String truncStr(String s, int m) {
        return s == null ? "" : s.length() > m ? s.substring(0, m) + "..." : s;
    }

    private static Object defaultReturnValue(Class<?> rt) {
        if (rt == null || rt == void.class || rt == Void.class) return null;
        if (rt == boolean.class) return false;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == float.class) return 0f;
        if (rt == double.class) return 0d;
        if (rt == short.class) return (short) 0;
        if (rt == byte.class) return (byte) 0;
        if (rt == char.class) return '\0';
        return null;
    }

    private static Class<?> methodReturnType(XC_MethodHook.MethodHookParam param) {
        try {
            if (param.method instanceof java.lang.reflect.Method) {
                return ((java.lang.reflect.Method) param.method).getReturnType();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void setResultBoolean(XC_MethodHook.MethodHookParam param, boolean val) {
        try {
            Class<?> rt = methodReturnType(param);
            if (rt == boolean.class) param.setResult(val);
            else param.setResult(defaultReturnValue(rt));
        } catch (Throwable ignored) {}
    }

    private static boolean isMarkedMessage(Object msg) {
        if (msg == null) return false;
        int key = System.identityHashCode(msg);
        synchronized (sBlockedOriginalMessages) {
            return sBlockedOriginalMessages.contains(key);
        }
    }

    private static boolean isMarkedByMsgId(Object msg) {
        if (msg == null) return false;
        long msgId = 0;
        try { msgId = (Long) XposedHelpers.callMethod(msg, "H0"); } catch (Throwable ignored) {}
        if (msgId <= 0) return false;
        synchronized (sMarkedMsgIds) {
            return sMarkedMsgIds.contains(msgId);
        }
    }

    private static boolean isLikelySuppressedTts(Object msg) {
        if (msg == null) return false;
        if (System.currentTimeMillis() - sLastTtsCommandAt > FAILURE_SUPPRESS_WINDOW_MS) return false;
        String content = null;
        try {
            Object c = XposedHelpers.getObjectField(msg, "field_content");
            if (c instanceof String) content = (String) c;
        } catch (Throwable ignored) {}
        if (content == null || content.trim().length() > 0) return false;
        int type = getMsgType(msg);
        if (type != 34 && type != 228 && type != 1) return false;
        String talker = getTalker(msg);
        if (talker == null || sLastTtsTalker == null || !sLastTtsTalker.equals(talker)) return false;
        return true;
    }

    private static void resetTraceBuf() {
        synchronized (sTraceBuf) {
            sTraceBuf.setLength(0);
            sTraceBufResetAt = System.currentTimeMillis();
        }
    }

    private static void appendTrace(String line) {
        synchronized (sTraceBuf) {
            if (System.currentTimeMillis() - sTraceBufResetAt > 3000) return;
            if (sTraceBuf.length() > 6000) return;
            sTraceBuf.append(line);
        }
    }

    private static void flushTrace() {
        synchronized (sTraceBuf) {
            if (sTraceBuf.length() > 0) {
                LogWriter.log(TAG, "e9 full trace:" + sTraceBuf);
                sTraceBuf.setLength(0);
            }
        }
    }

    private static void hookE9AllTrace(ClassLoader cl) {
        try {
            Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Class == null) {
                LogWriter.log(TAG, "e9 all trace: class not found");
                return;
            }
            int hooked = 0;
            for (java.lang.reflect.Method m : e9Class.getDeclaredMethods()) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!isMarkedMessage(p.thisObject)) return;
                            StringBuilder sb = new StringBuilder("\n  ").append(m.getName()).append('(');
                            if (p.args != null) {
                                for (int i = 0; i < p.args.length; i++) {
                                    if (i > 0) sb.append(',');
                                    Object a = p.args[i];
                                    if (a instanceof String) sb.append('\'').append(truncStr((String) a, 16)).append('\'');
                                    else if (a instanceof Integer) sb.append('i').append(a);
                                    else if (a instanceof Long) sb.append('l').append(a);
                                    else if (a instanceof Boolean) sb.append('b').append(a);
                                    else if (a == null) sb.append("null");
                                    else sb.append(a.getClass().getSimpleName());
                                }
                            }
                            sb.append(')');
                            appendTrace(sb.toString());
                        } catch (Throwable ignored) {}
                    }
                });
                hooked++;
            }
            LogWriter.log(TAG, "e9 all trace hooks installed: " + hooked);
        } catch (Throwable t) {
            LogWriter.log(TAG, "e9 all trace FAIL: " + t.getMessage());
        }
    }

    private static void scheduleTraceState(final Object msg) {
        sTtsPool.execute(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {}
            try {
                int type = getMsgType(msg);
                int isSend = getMsgIsSend(msg);
                String content = getMsgContent(msg);
                String status = "?";
                try {
                    Object st = XposedHelpers.getObjectField(msg, "field_status");
                    status = String.valueOf(st);
                } catch (Throwable ignored) {}
                long msgId = 0;
                try { msgId = (Long) XposedHelpers.callMethod(msg, "H0"); } catch (Throwable ignored) {}
                LogWriter.log(TAG, "e9 late state: type=" + type + " isSend=" + isSend
                        + " status=" + status + " msgId=" + msgId
                        + " talker=" + getTalker(msg)
                        + " content=" + (content == null ? "null" : truncStr(content, 40)));
                flushTrace();
            } catch (Throwable t) {
                LogWriter.log(TAG, "e9 late state err: " + t.getMessage());
            }
        });
    }

    private static void hookChattingUiSend(ClassLoader cl) {
        try {
            Class<?> chattingUi = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUI", cl);
            Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Class == null) {
                LogWriter.log(TAG, "ChattingUI guard: e9 class not found");
                return;
            }
            int hooked = 0;
            for (java.lang.reflect.Method m : chattingUi.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length >= 1 && pts[0] == e9Class) {
                    final Class<?> rt = m.getReturnType();
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                Object msg = p.args[0];
                                if (msg == null) return;
                                int key = System.identityHashCode(msg);
                                synchronized (sBlockedOriginalMessages) {
                                    if (sBlockedOriginalMessages.remove(key)) {
                                        LogWriter.log(TAG, "ChattingUI guard blocked #tts original method="
                                                + m.getName() + " msg=" + key);
                                        p.setResult(defaultReturnValue(rt));
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                    LogWriter.log(TAG, "ChattingUI guard hooked " + m.getName() + "(" + pts.length + ")");
                    hooked++;
                }
            }
            LogWriter.log(TAG, "ChattingUI guard hooks installed: " + hooked);
        } catch (Throwable t) {
            LogWriter.log(TAG, "ChattingUI guard FAIL: " + t.getMessage());
        }
    }

    private static String getMsgContent(Object msg) {
        try {
            Object content = XposedHelpers.callMethod(msg, "S1");
            if (content instanceof String) return (String) content;
        } catch (Throwable ignored) {}
        try {
            Object content = XposedHelpers.getObjectField(msg, "field_content");
            if (content instanceof String) return (String) content;
        } catch (Throwable ignored) {}
        return null;
    }

    private static int getMsgType(Object msg) {
        try { return (Integer) XposedHelpers.callMethod(msg, "getType"); } catch (Throwable ignored) {}
        try { return XposedHelpers.getIntField(msg, "field_type"); } catch (Throwable ignored) {}
        return -1;
    }

    private static int getMsgIsSend(Object msg) {
        try { return (Integer) XposedHelpers.callMethod(msg, "z0"); } catch (Throwable ignored) {}
        try { return XposedHelpers.getIntField(msg, "field_isSend"); } catch (Throwable ignored) {}
        return -1;
    }

    private static boolean isVoiceXml(String content) {
        return content != null && content.startsWith("<msg><voicemsg");
    }

    private static boolean markRecentText(String text, long now) {
        synchronized (sRecentTexts) {
            Long last = sRecentTexts.get(text);
            if (last != null && (now - last) < 5000) return true;
            sRecentTexts.put(text, now);
            return false;
        }
    }

    private static String getTalker(Object msg) {
        try {
            Object talker = XposedHelpers.callMethod(msg, "N0");
            if (talker instanceof String) return (String) talker;
        } catch (Throwable ignored) {}
        try {
            Object talker = XposedHelpers.getObjectField(msg, "field_talker");
            if (talker instanceof String) return (String) talker;
        } catch (Throwable ignored) {}
        return null;
    }

    private static String extractXmlAttr(String xml, String attr) {
        if (xml == null || attr == null) return null;
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

    // ========== 音乐卡片转语音 ==========

    private static void tryInterceptMusicCard(Object msg, String content) {
        try {
            if (content == null || !content.contains("<appmsg")) return;
            if (!content.contains("<type>76</type>")) return;
            dumpMusicSendStack();
            if (getMsgIsSend(msg) != 1) return;
            if (System.currentTimeMillis() - sOrderCardSentAt < 5000) {
                LogWriter.log(TAG, "音乐卡片拦截: 点歌功能卡片，跳过转语音");
                return;
            }
            String dataurl = extractXmlElement(content, "dataurl");
            if (dataurl == null || dataurl.isEmpty()) return;
            dataurl = xmlEntityDecode(dataurl);
            String title = xmlEntityDecode(extractXmlElement(content, "title"));
            String des = xmlEntityDecode(extractXmlElement(content, "des"));
            String webUrl = xmlEntityDecode(extractXmlElement(content, "url"));
            final String songmid = extractSongMid(webUrl);
            final String talker = getTalker(msg);
            LogWriter.log(TAG, "音乐卡片拦截: title=" + title + " des=" + des
                    + " talker=" + talker + " dataurl=" + dataurl + " songmid=" + songmid);
            final String url = dataurl;
            final String tTitle = title;
            sTtsPool.execute(new Runnable() {
                @Override public void run() {
                    downloadAndSendVoice(talker, url, tTitle, songmid);
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "音乐卡片拦截 err: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private static void dumpMusicSendStack() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder("音乐发送栈:");
            int count = 0;
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn.startsWith("com.leshao.v3") || cn.startsWith("de.robv.android.xposed")
                        || cn.startsWith("dalvik.") || cn.startsWith("java.lang")
                        || cn.startsWith("java.util")) continue;
                if (count >= 25) break;
                if (count > 0) sb.append(" <- ");
                sb.append(cn).append(".").append(e.getMethodName());
                count++;
            }
            LogWriter.log(TAG, sb.toString());
        } catch (Throwable ignored) {}
    }

    private static void downloadAndSendVoice(String talker, String url, String title, String songmid) {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            String dir = ContextManager.getAppContext().getCacheDir().getAbsolutePath() + "/music_card";
            new File(dir).mkdirs();
            String dlUrl = url;
            if (songmid != null && !songmid.isEmpty()) {
                String hq = getHighQualityUrl(songmid);
                if (hq != null && !hq.isEmpty()) {
                    dlUrl = hq;
                    LogWriter.log(TAG, "音乐下载改用高音质: " + hq);
                }
            }
            String ext = dlUrl.contains(".m4a") ? ".m4a" : (dlUrl.contains(".mp3") ? ".mp3" : ".audio");
            final String path = dir + "/ting_" + System.currentTimeMillis() + ext;

            conn = (HttpURLConnection) new URL(dlUrl).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                LogWriter.log(TAG, "音乐下载失败 code=" + code + " url=" + dlUrl);
                return;
            }
            in = conn.getInputStream();
            out = new FileOutputStream(path);
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            LogWriter.log(TAG, "音乐下载完成: " + path + " size=" + total + "B title=" + title);
            sendMp3Voice(talker, path);
        } catch (Throwable t) {
            LogWriter.log(TAG, "音乐转语音 err: " + t.getClass().getSimpleName() + " " + t.getMessage());
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    private static String extractSongMid(String webUrl) {
        try {
            if (webUrl == null) return null;
            int idx = webUrl.indexOf("songmid=");
            if (idx < 0) return null;
            int end = webUrl.indexOf('&', idx);
            if (end < 0) end = webUrl.length();
            return webUrl.substring(idx + 8, end);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String getHighQualityUrl(String songmid) {
        if (songmid == null || songmid.isEmpty()) return null;
        try {
            String reqJson = "{\"req_0\":{\"module\":\"vkey.GetVkeyServer\",\"method\":\"CgiGetVkey\","
                    + "\"param\":{\"guid\":\"2000000049\",\"songmid\":[\"" + songmid + "\"],\"songtype\":[0,1],"
                    + "\"uin\":\"0\",\"loginflag\":1,\"platform\":\"20\"}},"
                    + "\"comm\":{\"uin\":0,\"format\":\"json\",\"ct\":24,\"cv\":0}}";
            HttpURLConnection conn = (HttpURLConnection) new URL("https://u.y.qq.com/cgi-bin/musicu.fcg").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36");
            conn.setRequestProperty("Referer", "https://y.qq.com/");
            conn.setRequestProperty("Origin", "https://y.qq.com");
            OutputStream os = conn.getOutputStream();
            os.write(reqJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            if (code != 200) {
                String errBody = "";
                try {
                    InputStream es = conn.getErrorStream();
                    if (es != null) {
                        java.io.ByteArrayOutputStream ebaos = new java.io.ByteArrayOutputStream();
                        byte[] ebuf = new byte[2048];
                        int en;
                        while ((en = es.read(ebuf)) > 0) ebaos.write(ebuf, 0, en);
                        es.close();
                        errBody = new String(ebaos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                } catch (Throwable ignored) {}
                LogWriter.log(TAG, "[高音质] HTTP " + code + " err=" + errBody);
                conn.disconnect();
                return null;
            }
            InputStream is = conn.getInputStream();
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            is.close();
            conn.disconnect();
            String resp = new String(baos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            LogWriter.log(TAG, "[高音质] resp=" + resp);
            org.json.JSONObject root = new org.json.JSONObject(resp);
            org.json.JSONObject req0 = root.getJSONObject("req_0");
            org.json.JSONObject data = req0.getJSONObject("data");
            org.json.JSONArray midurlinfo = data.getJSONArray("midurlinfo");
            org.json.JSONArray sip = data.getJSONArray("sip");
            if (midurlinfo.length() == 0) return null;
            String purl = null;
            for (int i = midurlinfo.length() - 1; i >= 0; i--) {
                String p = midurlinfo.getJSONObject(i).optString("purl");
                if (p != null && !p.isEmpty()) { purl = p; break; }
            }
            if (purl == null || purl.isEmpty()) return null;
            String base = sip.getString(0);
            String fullUrl = base + purl;
            LogWriter.log(TAG, "[高音质] songmid=" + songmid + " -> " + fullUrl);
            return fullUrl;
        } catch (Throwable t) {
            LogWriter.log(TAG, "[高音质] 获取失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        }
    }

    private static String extractXmlElement(String xml, String tag) {
        try {
            String open = "<" + tag + ">";
            int s = xml.indexOf(open);
            if (s < 0) return null;
            s += open.length();
            int e = xml.indexOf("</" + tag + ">", s);
            if (e < 0) return null;
            return xml.substring(s, e);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String xmlEntityDecode(String s) {
        if (s == null) return null;
        s = s.replace("&amp;", "&");
        s = s.replace("&lt;", "<");
        s = s.replace("&gt;", ">");
        s = s.replace("&quot;", "\"");
        s = s.replace("&apos;", "'");
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("&#(\\d+);").matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1)))));
            }
            m.appendTail(sb);
            s = sb.toString();
        } catch (Throwable ignored) {}
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("&#x([0-9a-fA-F]+);").matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1), 16))));
            }
            m.appendTail(sb);
            s = sb.toString();
        } catch (Throwable ignored) {}
        return s;
    }

    // ========== 路径工具 ==========

    private static VoiceFileInfo getVoiceFileInfo(Object msg) {
        try {
            String talker = (String) XposedHelpers.callMethod(msg, "N0");
            String clientMsgId = getClientMsgId(msg);

            String voice2Dir = sAccPath + "voice2/";
            String path = buildVoice2Path(voice2Dir, clientMsgId);
            File parent = new File(path).getParentFile();
            if (parent != null) parent.mkdirs();

            LogWriter.log(TAG, "voice file: talker=" + talker + " cid=" + clientMsgId);
            return new VoiceFileInfo(path, clientMsgId);
        } catch (Throwable t) {
            LogWriter.log(TAG, "getVoicePath err: " + t.getMessage());
            return null;
        }
    }

    private static String getClientMsgId(Object msg) {
        try {
            String y0 = (String) XposedHelpers.callMethod(msg, "y0");
            String parsed = normalizeClientMsgId(y0);
            if (parsed != null) return parsed;
        } catch (Throwable ignored) {}

        try {
            long msgId = (Long) XposedHelpers.callMethod(msg, "getMsgId");
            if (msgId > 0) return String.valueOf(msgId);
        } catch (Throwable ignored) {}

        long now = System.currentTimeMillis();
        return "amr_" + now + Long.toHexString(System.nanoTime());
    }

    private static String normalizeClientMsgId(String value) {
        if (value == null || value.isEmpty()) return null;
        String v = value;
        int slash = v.lastIndexOf('/');
        if (slash >= 0) v = v.substring(slash + 1);
        if (v.endsWith(".amr")) v = v.substring(0, v.length() - 4);
        if (v.startsWith("msg_")) v = v.substring(4);
        if (v.isEmpty() || v.contains("/")) return null;
        return v;
    }

    private static String buildVoice2Path(String voice2Dir, String clientMsgId) {
        // 8.0.78(3180): 优先用 pv.p0.ej / wb0.b.Ej 解析完整路径 (getAmrFullPath)
        String viaService = resolveVoicePathViaService(voice2Dir, clientMsgId);
        if (viaService != null) return viaService;

        // Fallback: md5 两级 hash 目录 (与 k1.d mode=2 规则一致)
        String md5 = md5(clientMsgId);
        if (md5.length() >= 4) {
            return ensureTrailingSlash(voice2Dir) + md5.substring(0, 2) + "/"
                    + md5.substring(2, 4) + "/msg_" + clientMsgId + ".amr";
        }
        return ensureTrailingSlash(voice2Dir) + "msg_" + clientMsgId + ".amr";
    }

    /** 通过新版语音路径服务 (pv.p0.ej / wb0.b.Ej) 解析完整路径 */
    private static String resolveVoicePathViaService(String voice2Dir, String baseName) {
        try {
            Class<?> pathCls = VersionCompat.findVoicePathServiceClass(voiceCl());
            if (pathCls == null) return null;
            // 先试静态方法 (wb0.b.Ej(x, base, true) 形态)
            for (Method m : pathCls.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (m.getReturnType() == String.class && pts.length == 3
                        && pts[1] == String.class && pts[2] == boolean.class) {
                    try {
                        Object r = XposedHelpers.callStaticMethod(pathCls, m.getName(),
                                null, baseName, true);
                        if (r instanceof String && !((String) r).isEmpty()) {
                            LogWriter.log(TAG, "voice path via " + pathCls.getName() + "." + m.getName() + ": " + r);
                            return (String) r;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            // 再试实例方法: 从类上取第一个无参静态单例方法
            for (Method m : pathCls.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterTypes().length != 0) continue;
                if (m.getReturnType() != pathCls && !pathCls.isAssignableFrom(m.getReturnType())) continue;
                try {
                    Object inst = XposedHelpers.callStaticMethod(pathCls, m.getName());
                    if (inst == null) continue;
                    for (Method m2 : pathCls.getDeclaredMethods()) {
                        if (Modifier.isStatic(m2.getModifiers())) continue;
                        Class<?>[] pts = m2.getParameterTypes();
                        if (m2.getReturnType() == String.class && pts.length == 3
                                && pts[1] == String.class && pts[2] == boolean.class) {
                            try {
                                Object r = XposedHelpers.callMethod(inst, m2.getName(),
                                        inst, baseName, true);
                                if (r instanceof String && !((String) r).isEmpty()) {
                                    LogWriter.log(TAG, "voice path via " + pathCls.getName() + "." + m2.getName() + ": " + r);
                                    return (String) r;
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "resolveVoicePathViaService err: " + t.getMessage());
        }
        return null;
    }

    private static String md5(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    static void discoverVoiceApi(ClassLoader cl) {
        try {
            // Try DexKit-discovered voice API class first
            String dexKitVoiceApi = com.leshao.v3.hook.DexKitHelper.getVoiceApiClass();
            if (dexKitVoiceApi != null && !dexKitVoiceApi.isEmpty()) {
                try {
                    Class<?> cls = XposedHelpers.findClass(dexKitVoiceApi, cl);
                    for (Method m : cls.getDeclaredMethods()) {
                        if (!Modifier.isStatic(m.getModifiers())) continue;
                        Class<?>[] pts = m.getParameterTypes();
                        if (sVoiceGMethod == null && m.getReturnType() == String.class
                                && pts.length == 2 && pts[0] == String.class && pts[1] == String.class) {
                            sVoiceGClass = dexKitVoiceApi;
                            sVoiceGMethod = m.getName();
                        }
                        if (sVoiceSMethod == null && m.getReturnType() == String.class
                                && pts.length == 3 && pts[0] == String.class
                                && pts[1] == String.class && pts[2] == int.class) {
                            sVoiceSClass = dexKitVoiceApi;
                            sVoiceSMethod = m.getName();
                        }
                        if (sVoiceTMethod == null && m.getReturnType() == boolean.class
                                && pts.length >= 4 && pts[0] == String.class
                                && pts[1] == int.class && pts[2] == int.class) {
                            sVoiceTClass = dexKitVoiceApi;
                            sVoiceTMethod = m.getName();
                            sVoiceTParamCount = pts.length;
                        }
                    }
                    if (sVoiceGMethod != null && (sVoiceTMethod != null || sVoiceSMethod != null)) {
                        LogWriter.log(TAG, "VoiceApi from DexKit: " + dexKitVoiceApi);
                        return;
                    }
                } catch (Throwable ignored) {}
            }
            // Try hardcoded candidates (8.0.78 3180: VoiceLogic v61.d1)
            String[] dexKitCandidates = {"v61.d1", "v61.d2", "v61.d0", "v61.d3",
                "v61.e1", "v61.e0", "v61.f1", "v61.d4"};
            for (String name : dexKitCandidates) {
                try {
                    Class<?> cls = XposedHelpers.findClass(name, cl);
                    for (Method m : cls.getDeclaredMethods()) {
                        if (!Modifier.isStatic(m.getModifiers())) continue;
                        Class<?>[] pts = m.getParameterTypes();
                        if (sVoiceGMethod == null && m.getReturnType() == String.class
                                && pts.length == 2 && pts[0] == String.class && pts[1] == String.class) {
                            sVoiceGClass = name;
                            sVoiceGMethod = m.getName();
                        }
                        if (sVoiceSMethod == null && m.getReturnType() == String.class
                                && pts.length == 3 && pts[0] == String.class
                                && pts[1] == String.class && pts[2] == int.class) {
                            sVoiceSClass = name;
                            sVoiceSMethod = m.getName();
                        }
                        if (sVoiceTMethod == null && m.getReturnType() == boolean.class
                                && pts.length >= 4 && pts[0] == String.class
                                && pts[1] == int.class && pts[2] == int.class) {
                            sVoiceTClass = name;
                            sVoiceTMethod = m.getName();
                            sVoiceTParamCount = pts.length;
                        }
                    }
                    if (sVoiceGMethod != null && (sVoiceTMethod != null || sVoiceSMethod != null)) break;
                } catch (Throwable ignored) {}
            }
            // DexKit fallback: search for (String,String)→String and (String,int,int,...)→boolean
            if ((sVoiceGMethod == null || (sVoiceTMethod == null && sVoiceSMethod == null))) {
                try {
                    List<String> candidates = com.leshao.v3.hook.DexKitHelper.findClassesByString(cl, "voice2");
                    for (String cn : candidates) {
                        try {
                            Class<?> cls = XposedHelpers.findClass(cn, cl);
                            for (Method m : cls.getDeclaredMethods()) {
                                if (!Modifier.isStatic(m.getModifiers())) continue;
                                Class<?>[] pts = m.getParameterTypes();
                                if (sVoiceGMethod == null && m.getName().length() <= 3 && m.getReturnType() == String.class
                                        && pts.length == 2 && pts[0] == String.class && pts[1] == String.class) {
                                    sVoiceGClass = cn;
                                    sVoiceGMethod = m.getName();
                                }
                                if (sVoiceSMethod == null && m.getName().length() <= 3 && m.getReturnType() == String.class
                                        && pts.length == 3 && pts[0] == String.class
                                        && pts[1] == String.class && pts[2] == int.class) {
                                    sVoiceSClass = cn;
                                    sVoiceSMethod = m.getName();
                                }
                                if (sVoiceTMethod == null && m.getName().length() <= 3 && m.getReturnType() == boolean.class
                                        && pts.length >= 4 && pts[0] == String.class
                                        && pts[1] == int.class && pts[2] == int.class) {
                                    sVoiceTClass = cn;
                                    sVoiceTMethod = m.getName();
                                    sVoiceTParamCount = pts.length;
                                }
                            }
                            if (sVoiceGMethod != null && (sVoiceTMethod != null || sVoiceSMethod != null)) break;
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
            LogWriter.log(TAG, "VoiceApi: g=" + sVoiceGClass + "." + sVoiceGMethod
                    + " t=" + sVoiceTClass + "." + sVoiceTMethod + "/" + sVoiceTParamCount
                    + " s=" + sVoiceSClass + "." + sVoiceSMethod);
        } catch (Throwable t) {
            LogWriter.log(TAG, "VoiceApi discover err: " + t.getMessage());
        }
    }

    /** 误报语音时长(毫秒); 返回 -1 表示已关闭误报 */
    public static int resolveFakeDurationMs() {
        try {
            if (!WmPrefs.get("ls_tts_false_dur_on", true)) return -1;
            int sec = WmPrefs.getInt("ls_tts_false_dur_sec", 60);
            if (sec <= 0) sec = 60;
            if (sec > 3600) sec = 3600;
            return sec * 1000;
        } catch (Throwable t) {
            return 60000;
        }
    }

    public static boolean sendViaSceneVoice(String talker, String voiceFile, int durationMs) {
        try {
            // v1059: 微信语音消息时长上限 60 秒, 超过会被拒绝/发不出去。60 秒以内按真实时长发送;
            // v1085: 超过 60 秒时按「误报语音时长」上报, 默认误报 60 秒, 用户可在 TTS 页面自定义秒数。
            if (durationMs > 60000) {
                int fakeMs = resolveFakeDurationMs();
                if (fakeMs > 0) {
                    LogWriter.log(TAG, "SceneVoice: durationMs=" + durationMs
                            + "ms 超过 60s, 误报时长为 " + fakeMs + "ms");
                    durationMs = fakeMs;
                } else {
                    LogWriter.log(TAG, "SceneVoice: durationMs=" + durationMs
                            + "ms 超过 60s, 误报时长已关闭, 按真实时长上报");
                }
            }
            LogWriter.log(TAG, "SceneVoice: start voiceFile=" + voiceFile + " talker=" + talker + " durationMs=" + durationMs);
            if (talker == null || talker.isEmpty()) {
                LogWriter.log(TAG, "SceneVoice: talker null");
                return false;
            }
            if (sVoiceGClass == null || sVoiceGMethod == null || sVoiceTClass == null || sVoiceTMethod == null) {
                discoverVoiceApi(voiceCl());
            }
            if (sVoiceGClass == null || sVoiceGMethod == null || sVoiceTClass == null || sVoiceTMethod == null) {
                LogWriter.log(TAG, "SceneVoice: voice API not discovered");
                return false;
            }

            String newName = (String) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(sVoiceGClass, voiceCl()), sVoiceGMethod, talker, "amr_");
            LogWriter.log(TAG, "SceneVoice: newName=" + newName + " talker=" + talker);
            if (newName == null || newName.isEmpty()) return false;

            String voice2Dir = getVoice2Dir(voiceFile);
            LogWriter.log(TAG, "SceneVoice: voice2Dir=" + voice2Dir + " newName=" + newName);
            String dstPath = buildVoice2Path(voice2Dir, newName);
            LogWriter.log(TAG, "SceneVoice: dstPath=" + dstPath);
            File dstParent = new File(dstPath).getParentFile();
            if (dstParent != null) dstParent.mkdirs();
            fileCopy(voiceFile, dstPath);
            LogWriter.log(TAG, "SceneVoice: copied to " + dstPath);

            boolean ok;
            Class<?> vTClass = XposedHelpers.findClass(sVoiceTClass, voiceCl());
            if (sVoiceTParamCount >= 5) {
                ok = (Boolean) XposedHelpers.callStaticMethod(vTClass, sVoiceTMethod,
                        newName, durationMs, 0, null, null);
            } else {
                ok = (Boolean) XposedHelpers.callStaticMethod(vTClass, sVoiceTMethod,
                        newName, durationMs, 0, null);
            }
            LogWriter.log(TAG, "SceneVoice: send(" + newName + "," + durationMs + ",0,...)="
                    + ok + " method=" + sVoiceTClass + "." + sVoiceTMethod + " arity=" + sVoiceTParamCount);
            if (!ok) return false;

            // 语音转发_新.md §3: v61.d1.u 建消息后必须 v61.v0.dj().e() 踢 SceneVoiceService 上传队列
            kickVoiceUploadQueue(voiceCl());

            try {
                // 3180: 刷新语音缓存使用 pv.p0 (VoiceLogicService) 上的实例方法
                Class<?> player = VersionCompat.findVoicePlayerClass(voiceCl());
                if (player != null) {
                    boolean refreshed = false;
                    for (Method m : player.getDeclaredMethods()) {
                        if (!Modifier.isStatic(m.getModifiers())) continue;
                        if (m.getParameterTypes().length == 0 && m.getReturnType() != void.class
                                && m.getReturnType() != String.class) {
                            try {
                                Object svc = XposedHelpers.callStaticMethod(player, m.getName());
                                if (svc != null) {
                                    for (Method m2 : svc.getClass().getDeclaredMethods()) {
                                        if (m2.getName().equals("e") && m2.getParameterTypes().length == 0) {
                                            XposedHelpers.callMethod(svc, "e");
                                            refreshed = true;
                                            LogWriter.log(TAG, "SceneVoice: refresh via "
                                                    + player.getName() + "." + m.getName() + "().e()");
                                            break;
                                        }
                                    }
                                }
                                if (refreshed) break;
                            } catch (Throwable ignored) {}
                        }
                    }
                    if (refreshed) return true;
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "SceneVoice: refresh err: " + t.getMessage());
            }
            try {
                Class<?> y21p0 = VersionCompat.findVoicePlayerClass(voiceCl());
                if (y21p0 != null) {
                    Object q0 = XposedHelpers.callStaticMethod(y21p0, "kj");
                    if (q0 != null) {
                        XposedHelpers.callMethod(q0, "e");
                        LogWriter.log(TAG, "SceneVoice: refresh OK (legacy)");
                    }
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "SceneVoice: refresh err (legacy): " + t.getMessage());
            }
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "SceneVoice err: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return false;
        }
    }

    private static String getVoice2Dir(String voiceFile) {
        int idx = voiceFile.indexOf("/voice2/");
        if (idx >= 0) return voiceFile.substring(0, idx + 8);
        return voiceFile.substring(0, voiceFile.lastIndexOf('/') + 1);
    }

    /**
     * 语音转发_新.md §3: 发送入口 v61.d1.u 之后必须 v61.v0.dj().e() 踢 SceneVoiceService 上传队列。
     * 3180 候选: v61.v0 / v61.v1 / yl.y0(文档, jadx 误显示), 方法名为 dj/di/d/a, 返回后调用无参 e()。
     */
    private static void kickVoiceUploadQueue(ClassLoader cl) {
        String[] candidates = {"v61.v0", "v61.v1", "yl.y0", "v61.y0", "v61.v2", "yl.v0"};
        String[] instMeth = {"dj", "di", "a", "b", "getInstance", "f"};
        for (String cn : candidates) {
            try {
                Class<?> cls = XposedHelpers.findClass(cn, cl);
                for (Method m : cls.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterTypes().length != 0) continue;
                    if (m.getReturnType() == void.class || m.getReturnType() == String.class) continue;
                    for (String im : instMeth) {
                        if (m.getName().equals(im)) {
                            Object svc = null;
                            try { svc = m.invoke(null); } catch (Throwable ignored) {}
                            if (svc == null) break;
                            for (Method e2 : svc.getClass().getDeclaredMethods()) {
                                if (e2.getName().equals("e") && e2.getParameterTypes().length == 0) {
                                    try { e2.invoke(svc); }
                                    catch (Throwable t) { LogWriter.log(TAG, "kick queue svc.e() err: " + t.getMessage()); }
                                    LogWriter.log(TAG, "kickVoiceUploadQueue OK: " + cn + "." + im + "().e()");
                                    return;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        LogWriter.log(TAG, "kickVoiceUploadQueue: no SceneVoiceService found, refresh fallback only");
    }

    // ========== MP3 转语音消息 ==========

    /**
     * 将本地 MP3 文件转为微信语音消息并发送 (默认参数)
     */
    public interface VoiceSendCallback {
        void onProgress(int current, int total);
    }

    public static boolean sendMp3Voice(String talker, String mp3Path) {
        return sendMp3Voice(talker, mp3Path, 0, 1000, 0, 0, null);
    }

    public static boolean sendMp3Voice(String talker, String mp3Path, int splitSeconds, int fakeDurationMs) {
        return sendMp3Voice(talker, mp3Path, splitSeconds, fakeDurationMs, 0, 0, null);
    }

    /**
     * 将本地 MP3 文件转为微信语音消息并发送 (支持音频裁剪)
     * @param cutBeginSec 裁剪起始秒数, 0=不裁剪
     * @param cutEndSec 裁剪结束秒数, 0=到末尾
     */
    public static boolean sendMp3Voice(String talker, String mp3Path, int splitSeconds, int fakeDurationMs,
            float cutBeginSec, float cutEndSec, VoiceSendCallback callback) {
        if (sClassLoader == null) {
            LogWriter.log(TAG, "sendMp3Voice: sClassLoader null");
            return false;
        }
        try {
            LogWriter.log(TAG, "sendMp3Voice start: " + mp3Path
                    + " split=" + splitSeconds + "s fakeMs=" + fakeDurationMs
                    + " cut=" + cutBeginSec + "-" + cutEndSec);

            final VoiceSendCallback decodePhaseCb = (callback != null) ? new VoiceSendCallback() {
                @Override public void onProgress(int current, int total) {
                    try { callback.onProgress(current, total); } catch (Throwable ignored) {}
                }
            } : null;
            byte[] pcm = mp3ToPcm(new File(mp3Path), decodePhaseCb);
            if (pcm == null || pcm.length == 0) {
                LogWriter.log(TAG, "sendMp3Voice: MP3 解码失败");
                return false;
            }
            pcm = enhanceVoicePcm(pcm);

            int bytesPerSec = TARGET_SAMPLE_RATE * TARGET_CHANNELS * TARGET_BITS_PER_SAMPLE / 8;

            if (cutBeginSec > 0 || cutEndSec > 0) {
                int cutStart = (int)(cutBeginSec * bytesPerSec);
                if (cutStart % 2 != 0) cutStart++; // align to 16-bit
                int cutEnd = (int)(cutEndSec * bytesPerSec);
                if (cutEnd % 2 != 0) cutEnd++;
                if (cutEnd <= 0 || cutEnd > pcm.length) cutEnd = pcm.length;
                if (cutEnd % 2 != 0) cutEnd--;
                if (cutStart >= cutEnd) {
                    LogWriter.log(TAG, "sendMp3Voice: invalid cut range");
                    return false;
                }
                int newLen = cutEnd - cutStart;
                byte[] cutPcm = new byte[newLen];
                System.arraycopy(pcm, cutStart, cutPcm, 0, newLen);
                pcm = cutPcm;
                LogWriter.log(TAG, "sendMp3Voice: cut pcm " + cutStart + "-" + cutEnd + " -> " + pcm.length + " bytes");
            }

            LogWriter.log(TAG, "sendMp3Voice: pcm " + pcm.length + " bytes");

            String voice2 = getVoice2Dir();
            boolean anySent = false;

            if (splitSeconds <= 0) {
                if (callback != null) callback.onProgress(0, 1);
                byte[] padPcm = padPcmToFrame(pcm);
                byte[] voiceData = encodeVoiceHighestQuality(padPcm);
                if (voiceData == null || voiceData.length == 0) return false;
                String tmpPath = voice2 + "/mp3_" + System.currentTimeMillis() + ".amr";
                fileWrite(new File(tmpPath), voiceData);
                anySent = sendViaSceneVoice(talker, tmpPath, fakeDurationMs);
                if (callback != null) callback.onProgress(1, 1);
            } else {
                int segmentBytes = splitSeconds * bytesPerSec;
                int totalSegments = (pcm.length + segmentBytes - 1) / segmentBytes;

                LogWriter.log(TAG, "sendMp3Voice: splitting into " + totalSegments
                        + " segments, " + segmentBytes + " bytes/segment");

                if (callback != null) callback.onProgress(0, totalSegments);

                for (int seg = 0; seg < totalSegments; seg++) {
                    int off = seg * segmentBytes;
                    int len = Math.min(segmentBytes, pcm.length - off);
                    byte[] segPcm = new byte[len];
                    System.arraycopy(pcm, off, segPcm, 0, len);

                    byte[] padPcm = padPcmToFrame(segPcm);
                    byte[] amrData = encodeVoiceHighestQuality(padPcm);
                    if (amrData == null || amrData.length == 0) {
                        LogWriter.log(TAG, "sendMp3Voice: segment " + (seg+1) + "/" + totalSegments + " encode fail");
                        if (callback != null) callback.onProgress(seg + 1, totalSegments);
                        continue;
                    }

                    String tmpPath = voice2 + "/mp3_" + System.currentTimeMillis() + "_" + (seg + 1) + ".amr";
                    fileWrite(new File(tmpPath), amrData);

                    boolean sent = sendViaSceneVoice(talker, tmpPath, fakeDurationMs);
                    LogWriter.log(TAG, "sendMp3Voice: segment " + (seg+1) + "/"
                            + totalSegments + " sent=" + sent);
                    if (sent) anySent = true;
                    if (callback != null) callback.onProgress(seg + 1, totalSegments);

                    if (sent && seg < totalSegments - 1) {
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    }
                }
            }

            if (anySent) {
                try {
                    File mp3File = new File(mp3Path);
                    String fileName = mp3File.getName();
                    int durationMs = (int)((pcm.length * 1000L) / (TARGET_SAMPLE_RATE * TARGET_CHANNELS * 2));
                    android.content.Context ctx = ContextManager.getAppContext();
                    if (ctx != null) {
                        VoiceHistoryDbHelper.getInstance(ctx).insert(mp3Path, fileName, talker, durationMs);
                    }
                } catch (Throwable ignored) {}
            }

            return anySent;
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendMp3Voice err: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return false;
        }
    }

    /**
     * 使用 Android MediaExtractor/MediaCodec 将 MP3 解码为 16kHz 16bit mono PCM
     * @param decodeCb 解码进度回调(percent 0-100), 可为 null
     */
    private static byte[] mp3ToPcm(File mp3File, VoiceSendCallback decodeCb) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        File tempPcmFile = null;
        FileOutputStream fos = null;
        long startMs = System.currentTimeMillis();
        try {
            extractor.setDataSource(mp3File.getAbsolutePath());
            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    break;
                }
            }
            if (trackIndex < 0) {
                LogWriter.log(TAG, "mp3ToPcm: 无音频轨道");
                return null;
            }

            extractor.selectTrack(trackIndex);
            format = extractor.getTrackFormat(trackIndex);

            long fileSize = mp3File.length();
            long totalDurationUs = format.containsKey(MediaFormat.KEY_DURATION)
                ? format.getLong(MediaFormat.KEY_DURATION) : fileSize * 60; // rough estimate

            int srcRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

            LogWriter.log(TAG, "mp3ToPcm: file=" + fileSize + "B dur="
                + (totalDurationUs / 1000000) + "s srcRate=" + srcRate + " ch=" + channels);

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            tempPcmFile = File.createTempFile("pcm_dec_", ".raw",
                ContextManager.getAppContext().getCacheDir());
            fos = new FileOutputStream(tempPcmFile);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;

            long maxWaitMs = Math.min(3600000, Math.max(60000,
                Math.max(totalDurationUs / 1000 * 3, fileSize / 1024 * 10)));
            long lastProgressMs = startMs;
            int drainPct = -1;

            while (!outputDone && (System.currentTimeMillis() - startMs) < maxWaitMs) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(inIndex);
                        if (buffer != null) {
                            buffer.clear();
                            int sampleSize = extractor.readSampleData(buffer, 0);
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize,
                                    extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(outIndex);
                    if (buffer != null && info.size > 0) {
                        byte[] chunk = new byte[info.size];
                        buffer.position(info.offset);
                        buffer.get(chunk);
                        fos.write(chunk);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }

                long now = System.currentTimeMillis();
                if (now - lastProgressMs > 500) {
                    lastProgressMs = now;
                    if (decodeCb != null) {
                        int pct;
                        if (inputDone) {
                            // 输入已耗尽，输出排空中：从上次百分比向上递增
                            if (drainPct < 0) drainPct = 90;
                            drainPct = Math.min(99, drainPct + 1);
                            pct = drainPct;
                        } else {
                            long sampleTimeUs = extractor.getSampleTime();
                            pct = (sampleTimeUs > 0 && totalDurationUs > 0)
                                ? (int) (sampleTimeUs * 100 / totalDurationUs) : -1;
                            if (pct < 1) pct = 1;
                            if (pct > 98) pct = 98;
                            drainPct = pct + 1; // 为排空阶段预留起始值
                        }
                        try { decodeCb.onProgress(pct, 100); } catch (Throwable ignored) {}
                    }
                }
            }

            if (!outputDone) {
                LogWriter.log(TAG, "mp3ToPcm: decode timeout after " + (System.currentTimeMillis() - startMs) + "ms");
                return null;
            }

            fos.flush();
            fos.close();
            fos = null;

            long elapsedDecode = System.currentTimeMillis() - startMs;
            LogWriter.log(TAG, "mp3ToPcm: decode done " + tempPcmFile.length() + "B raw, "
                + elapsedDecode + "ms, srcRate=" + srcRate + " ch=" + channels);

            byte[] rawPcm = fileReadAll(tempPcmFile);
            int realRate = srcRate;
            if (totalDurationUs > 0 && rawPcm.length > 0 && channels > 0) {
                int inferred = (int) (rawPcm.length / 2L / channels * 1000000L / totalDurationUs);
                if (inferred > 4000 && Math.abs(inferred - srcRate) > srcRate / 10) {
                    LogWriter.log(TAG, "mp3ToPcm: 采样率修正 " + srcRate + " -> " + inferred
                            + " (raw=" + rawPcm.length + "B durUs=" + totalDurationUs + ")");
                    realRate = inferred;
                }
            }
            byte[] result;
            if (realRate == TARGET_SAMPLE_RATE && channels == 1) {
                result = rawPcm;
                LogWriter.log(TAG, "mp3ToPcm: already 16kHz mono, skip resample");
            } else {
                result = resampleLanczos3(rawPcm, realRate, channels, TARGET_SAMPLE_RATE);
            }

            long elapsedTotal = System.currentTimeMillis() - startMs;
            LogWriter.log(TAG, "mp3ToPcm: total " + elapsedTotal + "ms, output " + result.length + "B");
            return result;

        } catch (Throwable t) {
            LogWriter.log(TAG, "mp3ToPcm err: " + t.getMessage());
            return null;
        } finally {
            try { extractor.release(); } catch (Throwable ignored) {}
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) {}
            try { if (tempPcmFile != null) tempPcmFile.delete(); } catch (Throwable ignored) {}
        }
    }

    private static byte[] fileReadAll(File f) throws Exception {
        byte[] data = new byte[(int) f.length()];
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            int off = 0;
            while (off < data.length) {
                int r = fis.read(data, off, data.length - off);
                if (r < 0) break;
                off += r;
            }
            return data;
        } finally {
            try { if (fis != null) fis.close(); } catch (Throwable ignored) {}
        }
    }

    private static byte[] resampleLanczos3(byte[] rawPcm, int srcRate, int channels, int dstRate) {
        int srcSamples = rawPcm.length / 2 / channels;
        int dstSamples = (int) ((long) srcSamples * dstRate / srcRate);
        byte[] out = new byte[dstSamples * 2];
        int lanczosWindow = 5; // 方案2: Lanczos-3 -> Lanczos-5, 更少混叠/更高保真
        for (int i = 0; i < dstSamples; i++) {
            double srcPos = (double) i * srcRate / dstRate;
            int srcBase = (int) srcPos - lanczosWindow + 1;
            double sum = 0, weightSum = 0;
            for (int tap = -lanczosWindow + 1; tap <= lanczosWindow; tap++) {
                int si = srcBase + tap;
                if (si < 0) si = 0;
                if (si >= srcSamples) si = srcSamples - 1;
                double x = srcPos - si;
                double w;
                if (x == 0) {
                    w = 1.0;
                } else {
                    double piX = Math.PI * x;
                    w = lanczosWindow * Math.sin(piX) * Math.sin(piX / lanczosWindow) / (piX * x);
                }
                weightSum += w;
                double sample = 0;
                for (int ch = 0; ch < channels; ch++) {
                    int idx = (si * channels + ch) * 2;
                    short s = (short) ((rawPcm[idx + 1] << 8) | (rawPcm[idx] & 0xFF));
                    sample += s;
                }
                sample /= channels;
                sum += w * sample;
            }
            short outSample = (short) Math.max(-32768, Math.min(32767, sum / weightSum));
            out[i * 2] = (byte) (outSample & 0xFF);
            out[i * 2 + 1] = (byte) ((outSample >> 8) & 0xFF);
        }
        LogWriter.log(TAG, "resample Lanczos-5: " + srcRate + "Hz/" + channels + "ch -> "
                + dstRate + "Hz/mono, " + rawPcm.length + " -> " + out.length + " bytes");
        return out;
    }

    // ========== 配音魔方 TTS (Cube) ==========

    private static Object[] doCubeTTS(String text, String outAmrPath) {
        return doCubeTTS(text, outAmrPath, null);
    }

    private static Object[] doCubeTTS(String text, String outAmrPath, String voiceOverride) {
        try {
            String apiKey = WmPrefs.getStr("tts_cube_key", "");
            String voiceId = (voiceOverride != null && !voiceOverride.trim().isEmpty())
                    ? voiceOverride.trim()
                    : WmPrefs.getStr("tts_cube_voice", "");
            if (apiKey.isEmpty() || voiceId.isEmpty()) {
                LogWriter.log(TAG, "CubeTTS: key or voice empty");
                return null;
            }

            String apiUrl = "https://peiyinmofang.com/api/open/v1/tts/simple-generate";
            URL url = new URL(apiUrl);
             HttpURLConnection conn = (HttpURLConnection) url.openConnection();
             try {
             conn.setRequestMethod("POST");
             conn.setConnectTimeout(20000);
             conn.setReadTimeout(20000);
             conn.setDoOutput(true);
             conn.setRequestProperty("Content-Type", "application/json");
             conn.setRequestProperty("X-API-Key", apiKey);

             String jsonBody = "{\"voiceId\":\"" + voiceId + "\",\"text\":\"" + escapeJson(text) + "\"}";
             OutputStream os = conn.getOutputStream();
             try {
             os.write(jsonBody.getBytes("UTF-8"));
             os.flush();
             } finally { os.close(); }

             int code = conn.getResponseCode();
             if (code != 200) {
                 LogWriter.log(TAG, "CubeTTS: API HTTP " + code);
                 return null;
             }

             InputStream is = conn.getInputStream();
             try {
             StringBuilder sb = new StringBuilder();
             byte[] buf = new byte[4096];
             int n;
             while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));

             String resp = sb.toString();
             int audioIdx = resp.indexOf("\"audio\":\"");
             if (audioIdx < 0) {
                 LogWriter.log(TAG, "CubeTTS: no audio field in resp");
                 return null;
             }
             int audioStart = audioIdx + 9;
             int audioEnd = resp.indexOf("\"", audioStart);
             if (audioEnd < 0) {
                 LogWriter.log(TAG, "CubeTTS: audio URL parse fail");
                 return null;
             }
             String audioUrl = resp.substring(audioStart, audioEnd)
                     .replace("\\/", "/");

             URL audioURL = new URL(audioUrl);
             HttpURLConnection audioConn = (HttpURLConnection) audioURL.openConnection();
             try {
             audioConn.setConnectTimeout(15000);
             audioConn.setReadTimeout(15000);
             int audioCode = audioConn.getResponseCode();
             if (audioCode != 200) {
                 LogWriter.log(TAG, "CubeTTS: audio download HTTP " + audioCode);
                 return null;
             }

             File tmpDir = new File(sAccPath, "cube_temp");
             tmpDir.mkdirs();
             File wavFile = new File(tmpDir, "cube_" + System.currentTimeMillis() + ".wav");
             InputStream audioIs = audioConn.getInputStream();
             FileOutputStream fos = new FileOutputStream(wavFile);
             try {
             byte[] wBuf = new byte[8192];
             int rn;
             while ((rn = audioIs.read(wBuf)) > 0) fos.write(wBuf, 0, rn);
             fos.flush();
             } finally {
             try { fos.close(); } catch (Exception ignored) {}
             try { audioIs.close(); } catch (Exception ignored) {}
             }

             LogWriter.log(TAG, "CubeTTS: wav saved " + wavFile.length() + "b");

             byte[] pcm = wavToPcm(wavFile);
             wavFile.delete();
             if (pcm == null || pcm.length == 0) {
                 LogWriter.log(TAG, "CubeTTS: wavToPcm empty");
                 return null;
             }

             byte[] encodePcm = padPcmToFrame(pcm);
             int amrSize = encodePcmToAmr(encodePcm, outAmrPath, pcm.length);
             int durationMs = pcmBytesToDurationMs(pcm.length);
             LogWriter.log(TAG, "CubeTTS: amr " + amrSize + "b " + durationMs + "ms");
             return new Object[]{amrSize, durationMs};
             } finally { audioConn.disconnect(); }
             } finally { is.close(); }
             } finally { conn.disconnect(); }
        } catch (Throwable t) {
            LogWriter.log(TAG, "CubeTTS error: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    // ========== TTS 合成 + Silk 编码 ==========

    private static Object[] doTTS(String text, String outAmrPath) {
        if (!sReady) {
            LogWriter.log(TAG, "TTS not ready");
            return null;
        }

        try {
            File tmpDir = new File(sAccPath, "tts_temp/");
            tmpDir.mkdirs();
            long ts = System.currentTimeMillis();
            File wavFile = new File(tmpDir, "tts_" + ts + ".wav");
            LogWriter.log(TAG, "doTTS start: text='" + truncStr(text, 40) + "' out=" + outAmrPath
                    + " thread=" + Thread.currentThread().getName());

            CountDownLatch latch = new CountDownLatch(1);
            boolean[] ok = {false};
            sTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                public void onStart(String id) { LogWriter.log(TAG, "TTS onStart id=" + id); }
                public void onDone(String id) { ok[0] = true; LogWriter.log(TAG, "TTS onDone id=" + id); latch.countDown(); }
                public void onError(String id) { LogWriter.log(TAG, "TTS onError id=" + id); latch.countDown(); }
            });

            LogWriter.log(TAG, "synth call synthesizeToFile...");
            int r = sTts.synthesizeToFile(text, new android.os.Bundle(), wavFile, "tts-" + ts);
            LogWriter.log(TAG, "synth ret=" + r + " (SUCCESS=" + TextToSpeech.SUCCESS + ")");
            if (r != TextToSpeech.SUCCESS) {
                LogWriter.log(TAG, "synthesizeToFile fail: " + r);
                return null;
            }

            latch.await(SYNTH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            LogWriter.log(TAG, "synth latch released ok=" + ok[0]);
            if (!ok[0]) {
                LogWriter.log(TAG, "synth timeout");
                return null;
            }
            LogWriter.log(TAG, "WAV file exists=" + wavFile.exists() + " len=" + wavFile.length());
            if (wavFile.length() < 100) {
                LogWriter.log(TAG, "WAV too small: " + wavFile.length());
                return null;
            }
            LogWriter.log(TAG, "WAV: " + wavFile.length() + " bytes");

            byte[] pcm = wavToPcm(wavFile);
            wavFile.delete();
            if (pcm == null || pcm.length == 0) {
                LogWriter.log(TAG, "PCM extract fail");
                return null;
            }
            LogWriter.log(TAG, "PCM: " + pcm.length + " bytes");

            byte[] encodePcm = padPcmToFrame(pcm);
            LogWriter.log(TAG, "AMR encode start: pcm=" + pcm.length + " padded=" + encodePcm.length);
            int amrSize = encodePcmToAmr(encodePcm, outAmrPath, pcm.length);
            LogWriter.log(TAG, "AMR encode done amrSize=" + amrSize);
            if (amrSize <= 0) {
                LogWriter.log(TAG, "AMR encode fail");
                return null;
            }

            int durationMs = pcmBytesToDurationMs(pcm.length);
            LogWriter.log(TAG, "AMR: " + amrSize + " bytes " + durationMs + "ms");
            LogWriter.log(TAG, "AMR: " + outAmrPath);

            return new Object[]{amrSize, durationMs};

        } catch (Throwable e) {
            LogWriter.log(TAG, "doTTS err: " + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        }
    }

    public static byte[] wavToPcm(File wavFile) {
        try (FileInputStream fis = new FileInputStream(wavFile)) {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = fis.read(buf)) != -1) raw.write(buf, 0, r);
            byte[] wav = raw.toByteArray();
            if (wav.length < 44) return null;

            if (wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F'
                    || wav[8] != 'W' || wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E') {
                byte[] pcm = new byte[wav.length - 44];
                System.arraycopy(wav, 44, pcm, 0, pcm.length);
                LogWriter.log(TAG, "WAV header invalid, fallback strip 44");
                return pcm;
            }

            int audioFormat = 0;
            int channels = 0;
            int sampleRate = 0;
            int bitsPerSample = 0;
            int dataOffset = -1;
            int dataSize = 0;

            int off = 12;
            while (off + 8 <= wav.length) {
                String chunk = new String(wav, off, 4, "US-ASCII");
                int size = readLe32(wav, off + 4);
                int body = off + 8;
                if (size < 0 || body + size > wav.length) break;

                if ("fmt ".equals(chunk) && size >= 16) {
                    audioFormat = readLe16(wav, body);
                    channels = readLe16(wav, body + 2);
                    sampleRate = readLe32(wav, body + 4);
                    bitsPerSample = readLe16(wav, body + 14);
                } else if ("data".equals(chunk)) {
                    dataOffset = body;
                    dataSize = size;
                    break;
                }

                off = body + size + (size & 1);
            }

            if (dataOffset < 0 || dataSize <= 0) {
                LogWriter.log(TAG, "WAV data chunk missing");
                return null;
            }
            if (audioFormat != 1 || bitsPerSample != 16 || channels <= 0 || sampleRate <= 0) {
                LogWriter.log(TAG, "WAV unsupported fmt: format=" + audioFormat
                        + " sr=" + sampleRate + " ch=" + channels + " bit=" + bitsPerSample);
                return null;
            }

            LogWriter.log(TAG, "WAV fmt: " + sampleRate + "Hz " + channels + "ch " + bitsPerSample + "bit");
            return resamplePcm16Mono(wav, dataOffset, dataSize, sampleRate, channels, TARGET_SAMPLE_RATE);
        } catch (Throwable e) {
            LogWriter.log(TAG, "wavToPcm err: " + e.getMessage());
            return null;
        }
    }

    private static int readLe16(byte[] data, int off) {
        return (data[off] & 0xff) | ((data[off + 1] & 0xff) << 8);
    }

    private static int readLe32(byte[] data, int off) {
        return (data[off] & 0xff) | ((data[off + 1] & 0xff) << 8)
                | ((data[off + 2] & 0xff) << 16) | ((data[off + 3] & 0xff) << 24);
    }

    public static byte[] resamplePcm16Mono(byte[] data, int dataOffset, int dataSize,
            int srcRate, int channels, int dstRate) {
        int frameSize = channels * 2;
        int srcSamples = dataSize / frameSize;
        if (srcSamples <= 0) return null;

        short[] mono = new short[srcSamples];
        for (int i = 0; i < srcSamples; i++) {
            int sum = 0;
            int base = dataOffset + i * frameSize;
            for (int ch = 0; ch < channels; ch++) {
                int p = base + ch * 2;
                sum += (short) ((data[p] & 0xff) | (data[p + 1] << 8));
            }
            mono[i] = (short) (sum / channels);
        }

        int dstSamples = (int) ((long) srcSamples * dstRate / srcRate);
        byte[] out = new byte[dstSamples * 2];
        int lanczosWindow = 5; // 方案2: Lanczos-3 -> Lanczos-5, 更少混叠/更高保真
        for (int i = 0; i < dstSamples; i++) {
            double srcPos = (double) i * srcRate / dstRate;
            int srcBase = (int) srcPos - lanczosWindow + 1;
            double sum = 0, weightSum = 0;
            for (int tap = -lanczosWindow + 1; tap <= lanczosWindow; tap++) {
                int si = srcBase + tap;
                if (si < 0) si = 0;
                if (si >= srcSamples) si = srcSamples - 1;
                double x = srcPos - si;
                double w;
                if (x == 0) {
                    w = 1.0;
                } else {
                    double piX = Math.PI * x;
                    w = lanczosWindow * Math.sin(piX) * Math.sin(piX / lanczosWindow) / (piX * piX);
                }
                weightSum += w;
                sum += w * mono[si];
            }
            short outSample = (short) Math.max(-32768, Math.min(32767, sum / weightSum));
            out[i * 2] = (byte) (outSample & 0xff);
            out[i * 2 + 1] = (byte) ((outSample >> 8) & 0xff);
        }

        LogWriter.log(TAG, "PCM resample (Lanczos-5): " + srcRate + "Hz/" + channels + "ch -> "
                + dstRate + "Hz/" + TARGET_CHANNELS + "ch " + TARGET_BITS_PER_SAMPLE
                + "bit " + out.length + " bytes");
        return out;
    }

    public static byte[] padPcmToFrame(byte[] pcm) {
        byte[] clamped = clampAmplitude(pcm, 32000);

        int remainder = pcm.length % FRAME_PCM_BYTES;
        int padEnd = (remainder == 0) ? 0 : (FRAME_PCM_BYTES - remainder);
        byte[] result = new byte[pcm.length + padEnd];
        System.arraycopy(clamped, 0, result, 0, clamped.length);

        if (result.length % FRAME_PCM_BYTES != 0) {
            throw new RuntimeException("padPcmToFrame: not aligned " + result.length);
        }

        LogWriter.log(TAG, "PCM pad: " + pcm.length + " -> " + result.length
                + " (clamp+align), frame=" + FRAME_PCM_BYTES);
        return result;
    }

    private static byte[] clampAmplitude(byte[] pcm, int maxVal) {
        byte[] result = new byte[pcm.length];
        System.arraycopy(pcm, 0, result, 0, pcm.length);
        for (int i = 0; i < pcm.length - 1; i += 2) {
            int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            if (sample > maxVal) sample = maxVal;
            else if (sample < -maxVal) sample = -maxVal;
            result[i] = (byte) (sample & 0xff);
            result[i + 1] = (byte) ((sample >> 8) & 0xff);
        }
        return result;
    }

    public static int pcmBytesToDurationMs(int pcmBytes) {
        return (int) ((long) pcmBytes * 1000
                / (TARGET_SAMPLE_RATE * TARGET_CHANNELS * TARGET_BITS_PER_SAMPLE / 8));
    }

    private static final String AMR_WB_MIME = "audio/amr-wb";

    private static final String AMR_NB_MIME = "audio/3gpp";

    private static final String AMR_NB_MIME_ALT = "audio/amr";

    /**
     * 还原音质(透明化处理): 16k->24k 采样后不再施加高通/EQ/压缩等音色改动,
     * 仅做峰值归一化至 90% FS(只调音量防削波, 不改音色), 最大限度保留原曲保真度。
     * SILK/AMR 编码共用此段。
     */
    private static byte[] enhanceVoicePcm(byte[] pcm) {
        if (pcm == null || pcm.length < 4) return pcm;
        try {
            // 方案7: 双模式开关 — voice_enhance=true 走人声增强链(v928), false 走原音还原链(v929/默认)
            if (WmPrefs.get("voice_enhance", false)) {
                return enhanceVoicePcmTop(pcm);
            }
            final double fullScale = 32767.0;
            int n = pcm.length / 2;
            double peak = 0;
            for (int i = 0; i < n; i++) {
                int idx = i * 2;
                int s = (pcm[idx] & 0xFF) | (pcm[idx + 1] << 8);
                double v = (short) s;
                double m = Math.abs(v);
                if (m > peak) peak = m;
            }
            double normTarget = 0.90 * fullScale;
            double gain = (peak > 0) ? normTarget / peak : 1.0;
            if (gain > 12.0) gain = 12.0; // 增益上限, 防噪声放大
            byte[] out = new byte[pcm.length];
            for (int i = 0; i < n; i++) {
                int idx = i * 2;
                int s = (pcm[idx] & 0xFF) | (pcm[idx + 1] << 8);
                double v = (short) s * gain;
                if (v > 32767.0) v = 32767.0;
                if (v < -32768.0) v = -32768.0;
                int yi = (int) Math.round(v);
                out[idx] = (byte) (yi & 0xFF);
                out[idx + 1] = (byte) ((yi >> 8) & 0xFF);
            }
            LogWriter.log(TAG, "enhanceVoicePcm(restore): 透明化仅峰值归一化90%FS peak="
                    + Math.round(peak) + " gain=" + ((int)(gain*1000)/1000.0) + " " + pcm.length + "B");
            return out;
        } catch (Throwable t) {
            LogWriter.log(TAG, "enhanceVoicePcm err: " + t.getMessage());
            return pcm;
        }
    }

    /** 人声增强链(v928): 高通120Hz + Peaking EQ 2.4k/1.2k + 压缩器(-18dB 2.5:1) + 峰值归一化88%FS */
    private static byte[] enhanceVoicePcmTop(byte[] pcm) {
        if (pcm == null || pcm.length < 4) return pcm;
        try {
            final double fs = TARGET_SAMPLE_RATE;
            int n = pcm.length / 2;
            double[] samples = new double[n];
            for (int i = 0; i < n; i++) {
                int idx = i * 2;
                int s = (pcm[idx] & 0xFF) | (pcm[idx + 1] << 8);
                samples[i] = (short) s;
            }

            // --- 1. 二阶 Butterworth 高通 fc=120Hz (RBJ audio EQ cookbook) ---
            double w0 = 2.0 * Math.PI * 120.0 / fs;
            double alpha = Math.sin(w0) / Math.sqrt(2.0); // Q=1/sqrt(2)
            double cosw = Math.cos(w0);
            double b0 = (1.0 + cosw) / 2.0, b1 = -(1.0 + cosw), b2 = (1.0 + cosw) / 2.0;
            double a0 = 1.0 + alpha, a1 = -2.0 * cosw, a2 = 1.0 - alpha;
            double ib0 = b0 / a0, ib1 = b1 / a0, ib2 = b2 / a0, ia1 = a1 / a0, ia2 = a2 / a0;
            double hpZ1 = 0, hpZ2 = 0;
            for (int i = 0; i < n; i++) {
                double x = samples[i];
                double y = ib0 * x + hpZ1;
                hpZ1 = ib1 * x - ia1 * y + hpZ2;
                hpZ2 = ib2 * x - ia2 * y;
                samples[i] = y;
            }

            // --- 2. Peaking EQ +3dB Q=1.0 fc=2.4kHz (presence/齿音) ---
            double[][] eqs = {
                {2400.0, 1.0, 3.0},   // fc, Q, gainDb
                {1200.0, 0.6, 2.0}    // 方案D: 300Hz-3kHz 人声主体抬升
            };
            for (double[] eq : eqs) {
                double f0 = eq[0], q = eq[1], gDb = eq[2];
                w0 = 2.0 * Math.PI * f0 / fs;
                alpha = Math.sin(w0) / (2.0 * q);
                cosw = Math.cos(w0);
                double A = Math.pow(10.0, gDb / 40.0);
                b0 = 1.0 + alpha * A; b1 = -2.0 * cosw; b2 = 1.0 - alpha * A;
                a0 = 1.0 + alpha / A; a1 = -2.0 * cosw; a2 = 1.0 - alpha / A;
                double e0 = b0 / a0, e1 = b1 / a0, e2 = b2 / a0, ea1 = a1 / a0, ea2 = a2 / a0;
                double eqZ1 = 0, eqZ2 = 0;
                for (int i = 0; i < n; i++) {
                    double x = samples[i];
                    double y = e0 * x + eqZ1;
                    eqZ1 = e1 * x - ea1 * y + eqZ2;
                    eqZ2 = e2 * x - ea2 * y;
                    samples[i] = y;
                }
            }

            // --- 4. 轻量压缩器 threshold=-18dBFS ratio=2.5:1 (方案C) ---
            final double fullScale = 32767.0;
            final double thresholdDb = -18.0;
            final double ratio = 2.5;
            final double attackSmp = 0.002 * fs;   // 2ms
            final double releaseSmp = 0.100 * fs;  // 100ms
            double envDb = -120.0;
            double smoothGain = 1.0;
            for (int i = 0; i < n; i++) {
                double absX = Math.abs(samples[i]) / fullScale;
                double instDb = (absX > 1e-9) ? 20.0 * Math.log10(absX) : -120.0;
                if (instDb > envDb) {
                    envDb += (instDb - envDb) / attackSmp;
                } else {
                    envDb += (instDb - envDb) / releaseSmp;
                }
                double target = 1.0;
                double over = envDb - thresholdDb;
                if (over > 0) {
                    double compressedDb = thresholdDb + over / ratio;
                    target = Math.pow(10.0, (compressedDb - envDb) / 20.0);
                }
                double k = (target < smoothGain) ? 1.0 / attackSmp : 1.0 / releaseSmp;
                if (k > 1.0) k = 1.0;
                smoothGain += (target - smoothGain) * k;
                samples[i] *= smoothGain;
            }

            // --- 5. 峰值归一化至 88% FS ---
            double peak = 0;
            for (int i = 0; i < n; i++) {
                double m = Math.abs(samples[i]);
                if (m > peak) peak = m;
            }
            double normTarget = 0.88 * 32767.0;
            double gain = normTarget / peak;
            if (gain > 12.0) gain = 12.0; // 增益上限, 防噪声放大
            byte[] out = new byte[pcm.length];
            for (int i = 0; i < n; i++) {
                double y = samples[i] * gain;
                if (y > 32767.0) y = 32767.0;
                if (y < -32768.0) y = -32768.0;
                int yi = (int) Math.round(y);
                out[i * 2] = (byte) (yi & 0xFF);
                out[i * 2 + 1] = (byte) ((yi >> 8) & 0xFF);
            }
            LogWriter.log(TAG, "enhanceVoicePcm(top): HPF120+EQ2400+EQ1200+comp(-18dB/2.5)+norm peak="
                    + Math.round(peak) + " gain=" + ((int)(gain*1000)/1000.0) + " " + pcm.length + "B");
            return out;
        } catch (Throwable t) {
            LogWriter.log(TAG, "enhanceVoicePcm err: " + t.getMessage());
            return pcm;
        }
    }

    /**
     * 编码策略(8.0.78/3180 反编译适配):
     *  SILK(MediaRecorder.Silk*) 优先 —— 16k/30kbps, 音质远好于 AMR-NB(8k/12.2k)。
     *  8.0.78 判定端 v61.b1.l: seek(1) 读9字节 endsWith("#!SILK_V3"), 文件形态
     *  [1字节flag][#!SILK_V3][payload]; SilkDoEnc 首帧自身即含该头, 直接落盘即可。
     *  若设备无 SILK native 编码, 回退 AMR-NB(8k/12200, #!AMR\n)。
     */
    private static byte[] encodeVoiceHighestQuality(byte[] padPcm) {
        try {
            byte[] silk = encodeSilkViaMediaRecorder(padPcm);
            if (silk != null && silk.length > 6) {
                LogWriter.log(TAG, "encodeVoiceHighestQuality: SILK(MediaRecorder) OK, " + silk.length + "b");
                return silk;
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "encodeSILK(MediaRecorder) err: " + e.getMessage());
        }
        byte[] amr = encodeAmrNbRaw(padPcm);
        if (amr != null && amr.length > 6) {
            LogWriter.log(TAG, "encodeVoiceHighestQuality: AMR-NB fallback OK, " + amr.length + "b");
            return amr;
        }
        return null;
    }

    /**
     * 参照旧版(v8xx) MediaRecorder.SilkEncInit 链路: 16k/30kbps 最高音质
     */
    private static byte[] encodeSilkViaMediaRecorder(byte[] padPcm) {
        try {
            Class<?> rec = XposedHelpers.findClass("com.tencent.mm.modelvoice.MediaRecorder", voiceCl());
            long handle = (Long) XposedHelpers.callStaticMethod(rec,
                    "SilkEncInit", TARGET_SAMPLE_RATE, SILK_BITRATE, SILK_COMPLEXITY, 0L);
            LogWriter.log(TAG, "SILK(MediaRecorder) handle=" + handle);
            if (handle == 0) return null;

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] ob = new byte[FRAME_PCM_BYTES * 6];
            short[] ol = new short[1];
            int fb = FRAME_PCM_BYTES;
            int tf = padPcm.length / fb;

            for (int i = 0; i < tf; i++) {
                byte[] f = new byte[fb];
                System.arraycopy(padPcm, i * fb, f, 0, fb);
                boolean last = (i == tf - 1);
                java.util.Arrays.fill(ob, (byte) 0);
                ol[0] = 0;
                XposedHelpers.callStaticMethod(rec, "SilkDoEnc", f, (short) fb, ob, ol, last, handle);
                int len = ol[0];
                if (len <= 0) continue;
                baos.write(ob, 0, len);
            }
            try {
                XposedHelpers.callStaticMethod(rec, "SetVoiceSilkControl", 201, 1, handle);
            } catch (Throwable ignored) {}
            try {
                XposedHelpers.callStaticMethod(rec, "SilkEncUnInit", handle);
            } catch (Throwable ignored) {}

            byte[] silkBody = baos.toByteArray();
            LogWriter.log(TAG, "SILK(MediaRecorder) body: " + padPcm.length + "b PCM -> " + silkBody.length + "b");
            if (silkBody.length <= 0) return null;
            // 8.0.78: SilkDoEnc 首帧自身即为 [flag][#!SILK_V3][payload], 判定端 v61.b1.l
            // seek(1) 读9字节 endsWith("#!SILK_V3")。头已含于输出, 直接落盘, 不再自定义 wrap。
            if (silkHasV3Header(silkBody)) return silkBody;
            return wrapSilkV3(silkBody);
        } catch (Throwable e) {
            LogWriter.log(TAG, "encodeSilkViaMediaRecorder err: " + e.getMessage());
            return null;
        }
    }

    /**
     * 8.0.78 v61.b1.m: SILK 存储形态 = [1字节flag][#!SILK_V3][payload];
     * 判定 v61.b1.l seek(1) 读9字节 endsWith("#!SILK_V3")。头必须为大写 #!SILK_V3。
     * flag 字节: 真机样品 msg_amr_*.amr 实测头 = 02 #!SILK_V3 ...
     */
    private static final byte[] SILK_V3_HEAD = {(byte) '#', (byte) '!', (byte) 'S', (byte) 'I',
            (byte) 'L', (byte) 'K', (byte) '_', (byte) 'V', (byte) '3'};
    /** 真机样品确认: SILK 文件首字节 flag = 0x02 */
    private static final byte SILK_FLAG_BYTE = 0x02;

    /** 判断 body 是否已含 [flag][#!SILK_V3] 结构(offset 0..8 或 1..9 匹配) */
    private static boolean silkHasV3Header(byte[] body) {
        if (body == null || body.length < 10) return false;
        boolean at0 = true, at1 = true;
        for (int i = 0; i < SILK_V3_HEAD.length; i++) {
            if (body[i] != SILK_V3_HEAD[i]) at0 = false;
            if (body[i + 1] != SILK_V3_HEAD[i]) at1 = false;
        }
        return at0 || at1;
    }

    /** SILK body 加容器头: [1字节flag=0x02][#!SILK_V3][payload] (大写V3, 无换行) */
    private static byte[] wrapSilkV3(byte[] body) {
        byte[] out = new byte[1 + SILK_V3_HEAD.length + body.length];
        out[0] = SILK_FLAG_BYTE; // 真机样品确认 0x02
        System.arraycopy(SILK_V3_HEAD, 0, out, 1, SILK_V3_HEAD.length);
        System.arraycopy(body, 0, out, 1 + SILK_V3_HEAD.length, body.length);
        return out;
    }

    /**
     * 8.0.78(3180): 反编译确认 MediaRecorder.Silk* native 方法健在(yl.e1 加载 libwechatvoicesilk),
     * v61.w.c 转码用 new v61/c0(16000,16000,4)+SilkDoEnc。encodeVoiceHighestQuality 已切 SILK 优先。
     * AMR-NB(#!AMR\n, 8k/12200) 仅作无 SILK native 编码器兜底。
     */
    private static byte[] encodeAmrNbRaw(byte[] padPcm) {
        byte[] amrNb = encodeAmrWith(AMR_NB_MIME, 8000, 12200, downsampleTo8k(padPcm), "#!AMR\n");
        if (amrNb != null && amrNb.length > 6) return amrNb;
        byte[] amrNbAlt = encodeAmrWith(AMR_NB_MIME_ALT, 8000, 12200, downsampleTo8k(padPcm), "#!AMR\n");
        if (amrNbAlt != null && amrNbAlt.length > 6) return amrNbAlt;

        LogWriter.log(TAG, "encodeAmrNbRaw: AMR-NB unavailable, return null (AMR-WB/SILK 对端空, 不做兜底)");
        dumpAudioEncoders();
        return null;
    }

    private static void dumpAudioEncoders() {
        try {
            android.media.MediaCodecList list = new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS);
            StringBuilder sb = new StringBuilder("audio encoders: ");
            for (android.media.MediaCodecInfo ci : list.getCodecInfos()) {
                if (!ci.isEncoder()) continue;
                for (String t : ci.getSupportedTypes()) {
                    if (t.startsWith("audio/")) sb.append(t).append(' ');
                }
            }
            LogWriter.log(TAG, sb.toString());
        } catch (Throwable t) {
            LogWriter.log(TAG, "dumpAudioEncoders err: " + t.getMessage());
        }
    }

    private static byte[] encodeAmrWith(String mime, int sampleRate, int bitRate, byte[] pcm, String magicHeader) {
        if (pcm == null || pcm.length == 0) return null;
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createEncoderByType(mime);
            MediaFormat fmt = MediaFormat.createAudioFormat(mime, sampleRate, 1);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int pos = 0;
            boolean inputDone = false;
            boolean outputDone = false;
            long ptsUs = 0;

            while (!outputDone) {
                // 喂入: 仅用已 dequeue 的 buffer 容量, 切勿用 getInputBuffer(0) 探测
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(20000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        inBuf.clear();
                        int avail = inBuf.remaining();
                        int chunk = Math.min(avail, pcm.length - pos);
                        if (chunk > 0) inBuf.put(pcm, pos, chunk);
                        pos += chunk;
                        boolean eosInput = pos >= pcm.length;
                        codec.queueInputBuffer(inIdx, 0, chunk, ptsUs,
                                eosInput ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        ptsUs += (long) chunk * 1000000L / (sampleRate * 2);
                        if (eosInput) inputDone = true;
                        if (chunk <= 0) {
                            inputDone = true;
                            break;
                        }
                    }
                }

                // 收输出
                int outIdx = codec.dequeueOutputBuffer(info, 20000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    continue;
                }
                if (outIdx >= 0) {
                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    byte[] chunkData = new byte[info.size];
                    outBuf.get(chunkData);
                    baos.write(chunkData);
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outIdx, false);
                    if (eos) outputDone = true;
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // 已喂完则短暂等待编码器吐帧, 避免忙等空转
                    if (inputDone) {
                        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                    }
                }
            }

            byte[] body = baos.toByteArray();
            byte[] magic = magicHeader.getBytes("UTF-8");
            byte[] result = new byte[magic.length + body.length];
            System.arraycopy(magic, 0, result, 0, magic.length);
            System.arraycopy(body, 0, result, magic.length, body.length);

            LogWriter.log(TAG, mime + " encode: " + pcm.length + "b PCM -> " + result.length + "b sr=" + sampleRate);
            return result;
        } catch (Throwable e) {
            LogWriter.log(TAG, "encodeAmrWith(" + mime + ") err: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignored) {}
                try { codec.release(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 通用降采样: 从 TARGET_SAMPLE_RATE(现为24k) 降到 8k, 供 AMR-NB 兜底 */
    private static byte[] downsampleTo8k(byte[] pcm) {
        if (pcm == null || pcm.length < 4) return pcm;
        int factor = TARGET_SAMPLE_RATE / 8000; // 24k/8k = 3
        if (factor < 1) factor = 1;
        int inSamples = pcm.length / 2;
        int outSamples = inSamples / factor;
        byte[] out = new byte[outSamples * 2];
        for (int i = 0; i < outSamples; i++) {
            long sum = 0;
            for (int j = 0; j < factor; j++) {
                int o = (i * factor + j) * 2;
                sum += (short) ((pcm[o] & 0xff) | (pcm[o + 1] << 8));
            }
            int avg = (int) (sum / factor);
            out[i * 2] = (byte) (avg & 0xff);
            out[i * 2 + 1] = (byte) ((avg >> 8) & 0xff);
        }
        return out;
    }

    private static byte[] encodePcmToAmrWb(byte[] pcm, int sampleRate) {
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createEncoderByType(AMR_WB_MIME);
            MediaFormat fmt = MediaFormat.createAudioFormat(AMR_WB_MIME, sampleRate, 1);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 15850);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            int idx = codec.dequeueInputBuffer(10000);
            ByteBuffer inBuf = codec.getInputBuffer(idx);
            inBuf.clear();
            inBuf.put(pcm);
            codec.queueInputBuffer(idx, 0, pcm.length, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
                if (outIdx < 0) break;
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                byte[] chunk = new byte[info.size];
                outBuf.get(chunk);
                baos.write(chunk);
                codec.releaseOutputBuffer(outIdx, false);
            }

            byte[] body = baos.toByteArray();
            byte[] result = new byte[6 + body.length];
            result[0] = '#';
            result[1] = '!';
            result[2] = 'A';
            result[3] = 'M';
            result[4] = 'R';
            result[5] = '\n';
            System.arraycopy(body, 0, result, 6, body.length);

            LogWriter.log(TAG, "AMR-WB encode: " + pcm.length + "b PCM -> " + result.length + "b sr=" + sampleRate);
            return result;
        } catch (Throwable e) {
            LogWriter.log(TAG, "encodePcmToAmrWb err: " + e.getMessage());
            return null;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignored) {}
                try { codec.release(); } catch (Throwable ignored) {}
            }
        }
    }

    public static int encodePcmToAmr(byte[] pcm, String outPath, int originalPcmBytes) {
        try {
            // 统一为音频转语音高音质参数: SILK(24k/60k/复杂度5) 优先, AMR-NB 仅作兜底。
            // 此前 TTS 专用参数(AMR-NB 8k/12.2k)音质偏低, 现复用 encodeVoiceHighestQuality。
            byte[] voice = encodeVoiceHighestQuality(pcm);
            if (voice == null || voice.length == 0) return 0;
            File parent = new File(outPath).getParentFile();
            if (parent != null) parent.mkdirs();
            fileWrite(new File(outPath), voice);
            int fileSize = (int) new File(outPath).length();
            LogWriter.log(TAG, "encodePcmToAmr(high-q): " + fileSize + " bytes -> " + outPath);
            return fileSize;
        } catch (Throwable e) {
            LogWriter.log(TAG, "encodePcmToAmr err: " + e.getMessage());
            return 0;
        }
    }

    // ========== WAV → AMR 转码器 (配音魔方) ==========

    public static String wavToSilk(String wavFilePath, String clientMsgId) {
        if (!sReady || sAccPath == null || sClassLoader == null) {
            ensureTtsReady();
        }
        if (sAccPath == null || sClassLoader == null) {
            LogWriter.log(TAG, "wavToSilk: accPath/classloader null");
            return null;
        }

        File wavFile = new File(wavFilePath);
        if (!wavFile.exists()) {
            LogWriter.log(TAG, "wavToSilk: wav not found " + wavFilePath);
            return null;
        }

        try {
            byte[] pcm = wavToPcm(wavFile);
            if (pcm == null || pcm.length == 0) {
                LogWriter.log(TAG, "wavToSilk: wavToPcm empty");
                return null;
            }

            byte[] encodePcm = padPcmToFrame(pcm);
            String amrPath = buildVoicePath(clientMsgId);
            int amrSize = encodePcmToAmr(encodePcm, amrPath, pcm.length);
            if (amrSize <= 0) {
                LogWriter.log(TAG, "wavToAmr: amr encode fail");
                return null;
            }
            LogWriter.log(TAG, "wavToAmr: ok " + amrSize + "b -> " + amrPath);
            return amrPath;
        } catch (Throwable t) {
            LogWriter.log(TAG, "wavToSilk error: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        }
    }

    public static void sendWavAsVoice(final String talker, final String wavFilePath, final String clientMsgId) {
        if (talker == null || talker.isEmpty() || wavFilePath == null || wavFilePath.isEmpty()) {
            LogWriter.log(TAG, "sendWavAsVoice: invalid args");
            return;
        }
        sTtsPool.execute(() -> {
            try {
                            String amrPath = wavToSilk(wavFilePath, clientMsgId);
                if (amrPath == null) {
                    LogWriter.log(TAG, "sendWavAsVoice: wavToAmr failed");
                    return;
                }
                File wavFile = new File(wavFilePath);
                byte[] pcm = wavToPcm(wavFile);
                int durationMs = pcmBytesToDurationMs(pcm != null ? pcm.length : 0);
                boolean ok = sendViaSceneVoice(talker, amrPath, durationMs);
                LogWriter.log(TAG, "sendWavAsVoice sent=" + ok + " talker=" + talker);
            } catch (Throwable t2) {
                LogWriter.log(TAG, "sendWavAsVoice error: " + t2.getClass().getSimpleName() + " " + t2.getMessage());
            }
        });
    }

    private static String[] buildAccRoots() {
        int currentUser = getCurrentUserId();
        java.util.List<String> roots = new java.util.ArrayList<>();
        roots.add("/data/user/" + currentUser + "/com.tencent.mm/MicroMsg");
        if (currentUser != 0) {
            roots.add("/data/user/0/com.tencent.mm/MicroMsg");
        }
        java.io.File userBase = new java.io.File("/data/user");
        java.io.File[] userDirs = userBase.listFiles();
        if (userDirs != null) {
            for (java.io.File ud : userDirs) {
                if (!ud.isDirectory()) continue;
                int id = parseIntSafe(ud.getName(), -1);
                if (id < 0 || id == currentUser || (currentUser != 0 && id == 0)) continue;
                roots.add(ud.getAbsolutePath() + "/com.tencent.mm/MicroMsg");
            }
        }
        return roots.toArray(new String[0]);
    }

    private static int getCurrentUserId() {
        return Process.myUid() / 100000;
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (Throwable ignored) { return def; }
    }
}
