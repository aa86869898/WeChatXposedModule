package com.leshao.v3.hook;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * TTS 文字转语音 v2.2
 *
 * 1. Hook e9.d1(String) — 负责内容转换和语音文件生成
 * 2. before: TTS→WAV→PCM→Silk(yl.g)→voice2/标准路径
 * 3. Hook e9.setType(int) — voiceXml 类型守卫
 * 4. 就地修改 e9: d1(voiceXml), j1(voicePath)
 */
public class TtsVoiceSender {

    private static final String TAG = "TtsVoiceSender";
    private static final String TTS_PREFIX = "#tts ";
    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int TARGET_CHANNELS = 1;
    private static final int TARGET_BITS_PER_SAMPLE = 16;
    private static final int FRAME_DURATION_MS = 20;
    private static final int FRAME_SAMPLES = TARGET_SAMPLE_RATE * FRAME_DURATION_MS / 1000;
    private static final int FRAME_PCM_BYTES = FRAME_SAMPLES * TARGET_CHANNELS * TARGET_BITS_PER_SAMPLE / 8;
    private static final int SILK_BITRATE = 16000;
    private static final int SILK_COMPLEXITY = 5;
    private static final long FAILURE_SUPPRESS_WINDOW_MS = 8000;
    private static volatile boolean sCrashHandlerInstalled;

    private static void installCrashReporter() {
        if (sCrashHandlerInstalled) return;
        sCrashHandlerInstalled = true;
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
    private static boolean sReady;
    private static String sAccPath;
    private static String sMyWxId;
    private static String sVoiceGClass;
    private static String sVoiceGMethod;
    private static String sVoiceTClass;
    private static String sVoiceTMethod;
    private static volatile long sLastTtsCommandAt;
    private static volatile String sLastTtsTalker;
    private static final Object sLock = new Object();
    private static final Set<String> sSceneSentIds = new HashSet<>();
    private static final Set<Integer> sSuppressedMessages = new HashSet<>();
    private static final Set<Integer> sBlockedOriginalMessages = new HashSet<>();
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
        discoverVoiceApi(cl);
        hookE9D1(cl);
        hookSetTypeGuard(cl);
        hookChatFooterSend(cl);
        hookChattingUiSend(cl);
        hookE9Trace(cl);
        hookE9AllTrace(cl);
        hookE9Render(cl);
        hookA21Oi(cl);
        hookChattingUiAll(cl);
        hookChattingUIFragmentAll(cl);
        hookConvertTo(cl);
        hookB31W(cl);
        hookAdapterKJ(cl);
        hookF9I9(cl);
        dumpClassAll("zn3.t0", cl);
        dumpFields("zn3.t0", cl);
        dumpFields("n85.z", cl);
        dumpFields("a21.e", cl);
        dumpFields("q85.b", cl);
        dumpClassAll("com.tencent.mm.ui.chatting.ChattingUI", cl);
        dumpClassAll("com.tencent.mm.ui.chatting.ChattingUIFragment", cl);
        dumpFields("com.tencent.mm.ui.chatting.ChattingUI", cl);
        dumpFields("com.tencent.mm.ui.chatting.ChattingUIFragment", cl);
        dumpClassAll("com.tencent.mm.ui.chatting.view.MMChattingListView", cl);
        dumpFields("com.tencent.mm.ui.chatting.view.MMChattingListView", cl);
        dumpFields("n85.c0", cl);
        dumpFields("a21.o", cl);
        dumpClassAll("q06.n", cl);
        dumpFields("q06.n", cl);
        dumpFields(VersionCompat.findMsgInfoStorageClass(cl) != null
                ? VersionCompat.findMsgInfoStorageClass(cl).getName() : "e9", cl);
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
                        int result = sTts.setLanguage(Locale.CHINESE);
                        sReady = (result != TextToSpeech.LANG_MISSING_DATA
                                && result != TextToSpeech.LANG_NOT_SUPPORTED);
                        LogWriter.log(TAG, "TTS init: " + (sReady ? "OK" : "FAIL lang"));
                    } else {
                        LogWriter.log(TAG, "TTS init fail: status=" + status);
                    }
                });

                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

                if (!sReady) {
                    LogWriter.log(TAG, "TTS not ready after 2s wait");
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "TTS init crash: " + t.getMessage());
                return false;
            }
        }
        return sReady;
    }

    private static String findAccPath() {
        try {
            String path = (String) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.tencent.mm.kernel.h", sClassLoader), "getAccPath");
            if (path != null && !path.isEmpty()) {
                LogWriter.log(TAG, "Acc via kernel.h.getAccPath");
                return ensureTrailingSlash(path);
            }
        } catch (Throwable ignored) {}

        try {
            Context ctx = ContextManager.getAppContext();
            long uin = getDefaultUin(ctx);
            if (uin > 0) {
                String hash = VersionCompat.getDbHash(sClassLoader, (int) uin);
                String[] roots = {
                    "/data/data/com.tencent.mm/MicroMsg",
                    "/data/user/0/com.tencent.mm/MicroMsg",
                };
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

        String[] roots = {
            "/data/data/com.tencent.mm/MicroMsg",
            "/data/user/0/com.tencent.mm/MicroMsg",
        };
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
        return "/data/data/com.tencent.mm/MicroMsg/";
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
        if (path == null) return "/data/data/com.tencent.mm/MicroMsg/";
        return path.endsWith("/") ? path : path + "/";
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

            java.lang.reflect.Method d1 = e9Class.getDeclaredMethod("d1", String.class);
            XposedBridge.hookMethod(d1, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String content = (String) param.args[0];
                    LogWriter.log(TAG, "e9.d1 before: thread=" + Thread.currentThread().getName()
                            + " content='" + truncStr(content, 40) + "' this="
                            + (param.thisObject == null ? "null" : param.thisObject.getClass().getName()));
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
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object msg = param.thisObject;
                    if (msg == null || !isMarkedMessage(msg)) return;
                    try {
                        XposedHelpers.callMethod(msg, "A1", 34);
                        LogWriter.log(TAG, "d1 after: forced A1(34) type=" + getMsgType(msg));
                    } catch (Throwable t) {
                        try {
                            XposedHelpers.setIntField(msg, "field_type", 34);
                        } catch (Throwable ignored) {}
                    }
                }
            });

            LogWriter.log(TAG, "Hook e9.d1(String) OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "Hook e9.d1 FAIL: " + t.getMessage());
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

    private static void startAsyncTts(final String talker, final String clientMsgId,
            final String text, final String source) {
        Thread worker = new Thread(() -> {
            try {
                LogWriter.log(TAG, "async start: source=" + source + " talker=" + talker
                        + " cid=" + clientMsgId + " text='" + truncStr(text, 40) + "'");
                if (!ensureTtsReady()) {
                    LogWriter.log(TAG, "async TTS not ready: " + source);
                    return;
                }
                LogWriter.log(TAG, "async ensureTtsReady OK: " + source);
                String amrPath = buildVoicePath(clientMsgId);
                LogWriter.log(TAG, "async amrPath=" + amrPath + " source=" + source);
                Object[] ttsResult = doTTS(text, amrPath);
                if (ttsResult == null) {
                    LogWriter.log(TAG, "async TTS synth fail: " + source);
                    return;
                }
                int amrSize = (Integer) ttsResult[0];
                int durationMs = (Integer) ttsResult[1];
                LogWriter.log(TAG, "async scene send start: dur=" + durationMs + " source=" + source);
                boolean sceneSent = sendViaSceneVoice(talker, amrPath, durationMs);
                LogWriter.log(TAG, "async scene sent=" + sceneSent + " cid=" + clientMsgId
                        + " bytes=" + amrSize + " dur=" + durationMs);
            } catch (Throwable t) {
                LogWriter.log(TAG, "async TTS crash: " + t.getClass().getSimpleName() + " " + t.getMessage());
            }
        }, "leshao-tts-send");
        worker.setDaemon(true);
        worker.start();
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
                            XposedHelpers.callMethod(p.thisObject, "A1", 34);
                            LogWriter.log(TAG, "convertTo after: A1(34) type=" + getMsgType(p.thisObject));
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
        Thread t = new Thread(() -> {
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
                        String dst = sAccPath + "voice2/" + talker + "/msg_" + msgId + ".amr";
                        File parent = new File(dst).getParentFile();
                        if (parent != null) parent.mkdirs();
                        java.nio.file.Files.copy(
                                java.nio.file.Paths.get(amrPath),
                                java.nio.file.Paths.get(dst),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
        }, "leshao-amr-fixup");
        t.start();
    }

    private static String buildVoicePath(String clientMsgId) {        String voice2Dir = sAccPath + "voice2/";
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

    private static void hookChatFooterSend(ClassLoader cl) {
        try {
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
                        if (content != null && content.startsWith(TTS_PREFIX)) {
                            String text = content.substring(TTS_PREFIX.length()).trim();
                            if (text.isEmpty()) return;
                            if (markRecentText(text, System.currentTimeMillis())) {
                                LogWriter.log(TAG, "ChatFooter.F consumed duplicate #tts: " + text);
                                setResultBoolean(param, true);
                                return;
                            }
                            String talker = getTalker(msg);
                            String clientMsgId = getClientMsgId(msg);
                            LogWriter.log(TAG, "ChatFooter.F #tts hit -> async SceneVoice cid="
                                    + clientMsgId + " talker=" + talker + " text='" + truncStr(text, 40) + "'");
                            startAsyncTts(talker, clientMsgId, text, "ChatFooter.F");
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
            Class<?> c8 = XposedHelpers.findClass("dm.c8", sClassLoader);
            return c8.getDeclaredMethod("setType", int.class);
        } catch (Throwable ignored) {}

        return null;
    }

    private static void hookA21Oi(ClassLoader cl) {
        try {
            checkCoroutineSuspended(cl);
            Class<?> a21o = XposedHelpers.findClass("a21.o", cl);
            final Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            for (java.lang.reflect.Method m : a21o.getDeclaredMethods()) {
                if (!m.getName().equals("i")) continue;
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
            LogWriter.log(TAG, "Hook a21.o.i: method not found");
        } catch (Throwable t) {
            LogWriter.log(TAG, "Hook a21.o.i FAIL: " + t.getMessage());
        }
    }

    private static void checkCoroutineSuspended(ClassLoader cl) {
        try {
            Class<?> cs = XposedHelpers.findClass("kotlin.coroutines.intrinsics.CoroutineSingletons", cl);
            Object suspended = XposedHelpers.getStaticObjectField(cs, "COROUTINE_SUSPENDED");
            sCoroutineSuspended = suspended;
            LogWriter.log(TAG, "COROUTINE_SUSPENDED accessible: " + (suspended != null)
                    + " class=" + cs.getName());
        } catch (Throwable t) {
            LogWriter.log(TAG, "COROUTINE_SUSPENDED FAIL: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
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

    private static void dumpClassAll(String name, ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(name, cl);
            StringBuilder sb = new StringBuilder("dumpAll " + name + " methods:");
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
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
            LogWriter.log(TAG, "dumpClassAll " + name + " FAIL: " + t.getClass().getSimpleName());
        }
    }

    private static void dumpFields(String name, ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(name, cl);
            StringBuilder sb = new StringBuilder("dumpFields " + name + ":");
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                sb.append("\n  ").append(f.getType().getSimpleName()).append(' ').append(f.getName());
            }
            LogWriter.log(TAG, sb.toString());
        } catch (Throwable t) {
            LogWriter.log(TAG, "dumpFields " + name + " FAIL: " + t.getClass().getSimpleName());
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
                    LogWriter.log(TAG, "autoDiscover from " + host.getSimpleName() + " field "
                            + f.getName() + " -> " + fn);
                    dumpClassAll(fn, cl);
                    dumpFields(fn, cl);
                    hookNamedClassAll(fn, cl, sn);
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "autoDiscover " + hostClass + " FAIL: " + t.getMessage());
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
                            long now = System.currentTimeMillis();
                            if (now - sLastKjCallLogAt > 3000) {
                                sLastKjCallLogAt = now;
                                LogWriter.log(TAG, "adapter.k." + sig + " called item="
                                        + item.getClass().getName() + " window="
                                        + (now - sLastTtsCommandAt));
                            }
                            Object marked = findMarkedMessageIn(item, e9Class);
                            if (marked == null) return;
                            LogWriter.log(TAG, "adapter.k." + sig + " BLOCK marked #tts item="
                                    + item.getClass().getName() + " msg=" + System.identityHashCode(marked));
                            p.setResult(defaultReturnValue(rt));
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

    private static void hookF9I9(ClassLoader cl) {
        try {
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
                            LogWriter.log(TAG, "f9.I9 called marked=" + marked + " content="
                                    + truncStr(getMsgContent(msg), 30) + " talker=" + getTalker(msg)
                                    + " msg=" + System.identityHashCode(msg));
                            if (marked) {
                                LogWriter.log(TAG, "f9.I9 PASS marked #tts insert (no block)");
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

    private static Object findMarkedMessageIn(Object item, Class<?> e9Class) {
        if (item == null) return null;
        if (e9Class.isInstance(item)) {
            return isMarkedMessage(item) ? item : null;
        }
        for (java.lang.reflect.Field f : item.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object v = f.get(item);
                if (v != null && e9Class.isInstance(v) && isMarkedMessage(v)) return v;
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
                                    dumpClassAll(tn, cl);
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
        new Thread(() -> {
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
        }, "leshao-trace-state").start();
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
        try {
            Class<?> h1Cls = VersionCompat.findPlayThreadClass(sClassLoader);
            if (h1Cls != null) {
                Object path = XposedHelpers.callStaticMethod(h1Cls, "d",
                        ensureTrailingSlash(voice2Dir), "msg_", clientMsgId, ".amr", 2, true);
                if (path instanceof String && !((String) path).isEmpty()) return (String) path;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "h1.d voice path fail: " + t.getMessage());
        }

        String md5 = md5(clientMsgId);
        if (md5.length() >= 4) {
            return ensureTrailingSlash(voice2Dir) + md5.substring(0, 2) + "/"
                    + md5.substring(2, 4) + "/msg_" + clientMsgId + ".amr";
        }
        return ensureTrailingSlash(voice2Dir) + "msg_" + clientMsgId + ".amr";
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

    private static void discoverVoiceApi(ClassLoader cl) {
        try {
            String[] candidates = {"y21.x0", "y22.x0", "y20.x0", "y23.x0"};
            for (String name : candidates) {
                try {
                    Class<?> cls = XposedHelpers.findClass(name, cl);
                    for (Method m : cls.getDeclaredMethods()) {
                        if (!Modifier.isStatic(m.getModifiers())) continue;
                        Class<?>[] pts = m.getParameterTypes();
                        if (m.getName().equals("g") && m.getReturnType() == String.class
                                && pts.length == 2 && pts[0] == String.class && pts[1] == String.class) {
                            sVoiceGClass = name;
                            sVoiceGMethod = m.getName();
                        }
                        if (m.getName().equals("t") && m.getReturnType() == boolean.class
                                && pts.length >= 4 && pts[0] == String.class
                                && pts[1] == int.class && pts[2] == int.class) {
                            sVoiceTClass = name;
                            sVoiceTMethod = m.getName();
                        }
                    }
                    if (sVoiceGMethod != null && sVoiceTMethod != null) break;
                } catch (Throwable ignored) {}
            }
            LogWriter.log(TAG, "VoiceApi: g=" + sVoiceGClass + "." + sVoiceGMethod
                    + " t=" + sVoiceTClass + "." + sVoiceTMethod);
        } catch (Throwable t) {
            LogWriter.log(TAG, "VoiceApi discover err: " + t.getMessage());
        }
    }

    private static boolean sendViaSceneVoice(String talker, String voiceFile, int durationMs) {
        try {
            if (talker == null || talker.isEmpty()) {
                LogWriter.log(TAG, "SceneVoice: talker null");
                return false;
            }
            if (sVoiceGClass == null || sVoiceGMethod == null || sVoiceTClass == null || sVoiceTMethod == null) {
                discoverVoiceApi(sClassLoader);
            }
            if (sVoiceGClass == null || sVoiceGMethod == null || sVoiceTClass == null || sVoiceTMethod == null) {
                LogWriter.log(TAG, "SceneVoice: voice API not discovered");
                return false;
            }

            String newName = (String) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(sVoiceGClass, sClassLoader), sVoiceGMethod, talker, "amr_");
            LogWriter.log(TAG, "SceneVoice: newName=" + newName + " talker=" + talker);
            if (newName == null || newName.isEmpty()) return false;

            String voice2Dir = getVoice2Dir(voiceFile);
            String dstPath = buildVoice2Path(voice2Dir, newName);
            File dstParent = new File(dstPath).getParentFile();
            if (dstParent != null) dstParent.mkdirs();
            java.nio.file.Files.copy(
                    java.nio.file.Paths.get(voiceFile),
                    java.nio.file.Paths.get(dstPath),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LogWriter.log(TAG, "SceneVoice: copied to " + dstPath);

            boolean ok = (Boolean) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(sVoiceTClass, sClassLoader), sVoiceTMethod,
                    newName, durationMs, 0, null);
            LogWriter.log(TAG, "SceneVoice: t(" + newName + "," + durationMs + ",0,null)=" + ok);
            if (!ok) return false;

            try {
                Class<?> y21p0 = VersionCompat.findVoicePlayerClass(sClassLoader);
                Object q0 = XposedHelpers.callStaticMethod(y21p0, "kj");
                XposedHelpers.callMethod(q0, "e");
                LogWriter.log(TAG, "SceneVoice: refresh OK");
            } catch (Throwable t) {
                LogWriter.log(TAG, "SceneVoice: refresh err: " + t.getMessage());
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

            latch.await(30, TimeUnit.SECONDS);
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
            LogWriter.log(TAG, "Silk encode start: pcm=" + pcm.length + " padded=" + encodePcm.length);
            int amrSize = encodePcmToSilk(encodePcm, outAmrPath, pcm.length);
            LogWriter.log(TAG, "Silk encode done amrSize=" + amrSize);
            if (amrSize <= 0) {
                LogWriter.log(TAG, "Silk encode fail");
                return null;
            }

            int durationMs = pcmBytesToDurationMs(pcm.length);
            LogWriter.log(TAG, "Silk: " + amrSize + " bytes " + durationMs + "ms");
            LogWriter.log(TAG, "AMR: " + outAmrPath);

            return new Object[]{amrSize, durationMs};

        } catch (Throwable e) {
            LogWriter.log(TAG, "doTTS err: " + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        }
    }

    private static byte[] wavToPcm(File wavFile) {
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

    private static byte[] resamplePcm16Mono(byte[] data, int dataOffset, int dataSize,
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

        int dstSamples = Math.max(1, (int) ((long) srcSamples * dstRate / srcRate));
        byte[] out = new byte[dstSamples * 2];
        for (int i = 0; i < dstSamples; i++) {
            double srcPos = (double) i * srcRate / dstRate;
            int idx = (int) srcPos;
            double frac = srcPos - idx;
            short s0 = mono[Math.min(idx, srcSamples - 1)];
            short s1 = mono[Math.min(idx + 1, srcSamples - 1)];
            int sample = (int) Math.round(s0 + (s1 - s0) * frac);
            out[i * 2] = (byte) (sample & 0xff);
            out[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
        }

        LogWriter.log(TAG, "PCM resample: " + srcRate + "Hz/" + channels + "ch -> "
                + dstRate + "Hz/" + TARGET_CHANNELS + "ch " + TARGET_BITS_PER_SAMPLE
                + "bit " + out.length + " bytes");
        return out;
    }

    private static byte[] padPcmToFrame(byte[] pcm) {
        int remainder = pcm.length % FRAME_PCM_BYTES;
        if (remainder == 0) return pcm;
        int paddedLength = pcm.length + (FRAME_PCM_BYTES - remainder);
        byte[] padded = new byte[paddedLength];
        System.arraycopy(pcm, 0, padded, 0, pcm.length);
        LogWriter.log(TAG, "PCM frame pad: " + pcm.length + " -> " + paddedLength
                + " bytes, frame=" + FRAME_PCM_BYTES);
        return padded;
    }

    private static int pcmBytesToDurationMs(int pcmBytes) {
        return (pcmBytes / (TARGET_CHANNELS * TARGET_BITS_PER_SAMPLE / 8))
                * 1000 / TARGET_SAMPLE_RATE;
    }

    private static int encodePcmToSilk(byte[] pcm, String outPath, int originalPcmBytes) {
        try {
            Class<?> silkCls = XposedHelpers.findClass("yl.g", sClassLoader);
            Object silk = XposedHelpers.newInstance(silkCls, TARGET_SAMPLE_RATE, SILK_BITRATE);
            configureSilkWriter(silk);

            boolean inited = (Boolean) XposedHelpers.callMethod(silk, "b", outPath);
            if (!inited) {
                LogWriter.log(TAG, "SilkWriter.b() init fail");
                return 0;
            }

            Class<?> h0Cls = XposedHelpers.findClass("tl.h0", sClassLoader);
            int frameSize = FRAME_PCM_BYTES;
            int totalFrames = (pcm.length + frameSize - 1) / frameSize;
            int finalFrameBytes = pcm.length % frameSize;
            if (finalFrameBytes == 0) finalFrameBytes = frameSize;

            LogWriter.log(TAG, "Silk V3 encoding: " + TARGET_SAMPLE_RATE + "Hz "
                    + TARGET_CHANNELS + "ch " + TARGET_BITS_PER_SAMPLE + "bit, "
                    + FRAME_DURATION_MS + "ms/frame, " + FRAME_SAMPLES + " samples, "
                    + FRAME_PCM_BYTES + " bytes/frame, bitrate=" + SILK_BITRATE
                    + "bps, complexity=" + SILK_COMPLEXITY + ", total="
                    + originalPcmBytes + " bytes, encodedTotal=" + pcm.length
                    + " bytes -> " + totalFrames + " frames, finalFrame="
                    + finalFrameBytes + " bytes, tailPad=enabled");

            int frameIndex = 0;
            for (int off = 0; off < pcm.length; off += frameSize, frameIndex++) {
                int size = Math.min(frameSize, pcm.length - off);
                byte[] frame = new byte[frameSize];
                System.arraycopy(pcm, off, frame, 0, size);
                boolean isLast = off + size >= pcm.length;

                Object h0 = newH0(h0Cls, frame, size, isLast);
                if (h0 == null) return 0;

                XposedHelpers.callMethod(silk, "a", h0, 0);
                if (frameIndex == 0 || isLast) {
                    LogWriter.log(TAG, "Silk frame push: idx=" + frameIndex
                            + " ts=0 size=" + size
                            + " last=" + isLast);
                }
            }

            XposedHelpers.callMethod(silk, "d");

            int fileSize = (int) new File(outPath).length();
            LogWriter.log(TAG, "Silk encoded: " + fileSize + " bytes");
            return fileSize;

        } catch (Throwable e) {
            LogWriter.log(TAG, "Silk encode err: " + e.getMessage());
            return 0;
        }
    }

    private static void configureSilkWriter(Object silk) {
        boolean complexitySet = false;
        for (String method : new String[]{"setComplexity", "setEncodeComplexity", "setEncComplexity"}) {
            try {
                XposedHelpers.callMethod(silk, method, SILK_COMPLEXITY);
                LogWriter.log(TAG, "Silk complexity set via " + method + "=" + SILK_COMPLEXITY);
                complexitySet = true;
                break;
            } catch (Throwable ignored) {}
        }
        if (!complexitySet) {
            LogWriter.log(TAG, "Silk complexity setter not exposed, requested=" + SILK_COMPLEXITY);
        }
    }

    private static Object newH0(Class<?> h0Cls, byte[] frame, int size, boolean isLast) {
        try {
            Object h0 = XposedHelpers.newInstance(h0Cls);
            XposedHelpers.setObjectField(h0, "a", frame);
            XposedHelpers.setIntField(h0, "b", size);
            XposedHelpers.setBooleanField(h0, "c", isLast);
            return h0;
        } catch (Throwable e1) {
            try {
                return XposedHelpers.newInstance(h0Cls, new Object[]{frame, size, isLast});
            } catch (Throwable e2) {
                try {
                    return XposedHelpers.newInstance(h0Cls, new Object[]{frame,
                            Integer.valueOf(size), Boolean.valueOf(isLast)});
                } catch (Throwable e3) {
                    LogWriter.log(TAG, "newH0 all attempts failed: " + e3.getMessage());
                    return null;
                }
            }
        }
    }
}
