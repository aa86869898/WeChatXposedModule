package com.leshao.v3.hook;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Process;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.leshao.v3.ContextManager;
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
    private static final int SILK_BITRATE = 30000;
    private static final int SILK_COMPLEXITY = 2;
    private static final long FAILURE_SUPPRESS_WINDOW_MS = 8000;
    private static volatile boolean sCrashHandlerInstalled;

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
    private static String sAccPath;
    private static String sMyWxId;
    private static String sVoiceGClass;
    private static String sVoiceGMethod;
    private static String sVoiceTClass;
    private static String sVoiceTMethod;
    private static volatile long sLastTtsCommandAt;
    private static volatile String sLastTtsTalker;
    public static volatile long sOrderCardSentAt;
    private static final Object sLock = new Object();
    private static final Set<String> sSceneSentIds = new HashSet<>();
    private static final Set<Integer> sSuppressedMessages = new HashSet<>();
    private static final Set<Integer> sBlockedOriginalMessages = new HashSet<>();
    private static final Set<Long> sMarkedMsgIds = new HashSet<>();
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
                path = normalizeDataPath(path);
                LogWriter.log(TAG, "Acc via kernel.h.getAccPath");
                return ensureTrailingSlash(path);
            }
        } catch (Throwable ignored) {}

        try {
            Context ctx = ContextManager.getAppContext();
            long uin = getDefaultUin(ctx);
            if (uin > 0) {
                String hash = VersionCompat.getDbHash(sClassLoader, (int) uin);
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

            java.lang.reflect.Method d1 = e9Class.getDeclaredMethod("d1", String.class);
            XposedBridge.hookMethod(d1, new XC_MethodHook() {
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

    private static void startAsyncTts(final String talker, final String clientMsgId,
            final String text, final String source) {
        Thread worker = new Thread(() -> {
            try {
                LogWriter.log(TAG, "async start: source=" + source + " talker=" + talker
                        + " cid=" + clientMsgId + " text='" + truncStr(text, 40) + "'");

                boolean useCube = WmPrefs.isTTSCube();
                LogWriter.log(TAG, "async useCube=" + useCube + " source=" + source);

                if (useCube) {
                    if (sAccPath == null || sClassLoader == null) {
                        ensureTtsReady();
                    }
                    if (sAccPath == null || sClassLoader == null) {
                        LogWriter.log(TAG, "async cube accPath/classLoader null: " + source);
                        return;
                    }
                    String amrPath = buildVoicePath(clientMsgId);
                    Object[] ttsResult = doCubeTTS(text, amrPath);
                    if (ttsResult == null) {
                        LogWriter.log(TAG, "async cube TTS synth fail: " + source);
                        return;
                    }
                    int amrSize = (Integer) ttsResult[0];
                    int durationMs = (Integer) ttsResult[1];
                    LogWriter.log(TAG, "async cube scene send start: dur=" + durationMs);
                    boolean sceneSent = sendViaSceneVoice(talker, amrPath, durationMs);
                    LogWriter.log(TAG, "async cube scene sent=" + sceneSent + " cid=" + clientMsgId);
                } else {
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
                }
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
        }, "leshao-amr-fixup");
        t.start();
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

    private static Object findMarkedMessageIn(Object item, Class<?> e9Class) {
        return findMarkedMessageIn(item, e9Class, 0);
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
            new Thread(new Runnable() {
                @Override public void run() {
                    downloadAndSendVoice(talker, url, tTitle, songmid);
                }
            }, "music-to-voice").start();
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
            String dir = ContextManager.getAppContext().getCacheDir().getAbsolutePath() + "/ting_music";
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

    public static boolean sendViaSceneVoice(String talker, String voiceFile, int durationMs) {
        try {
            LogWriter.log(TAG, "SceneVoice: start voiceFile=" + voiceFile + " talker=" + talker + " durationMs=" + durationMs);
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
            LogWriter.log(TAG, "SceneVoice: voice2Dir=" + voice2Dir + " newName=" + newName);
            String dstPath = buildVoice2Path(voice2Dir, newName);
            LogWriter.log(TAG, "SceneVoice: dstPath=" + dstPath);
            File dstParent = new File(dstPath).getParentFile();
            if (dstParent != null) dstParent.mkdirs();
            fileCopy(voiceFile, dstPath);
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
                byte[] silkData = encodeSilkRaw(padPcm);
                if (silkData == null || silkData.length == 0) return false;
                String tmpPath = voice2 + "/mp3_" + System.currentTimeMillis();
                fileWrite(new File(tmpPath), silkData);
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
                    byte[] silkData = encodeSilkRaw(padPcm);
                    if (silkData == null || silkData.length == 0) {
                        LogWriter.log(TAG, "sendMp3Voice: segment " + (seg+1) + "/" + totalSegments + " encode fail");
                        if (callback != null) callback.onProgress(seg + 1, totalSegments);
                        continue;
                    }

                    String tmpPath = voice2 + "/mp3_" + System.currentTimeMillis() + "_" + (seg + 1);
                    fileWrite(new File(tmpPath), silkData);

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
        int lanczosWindow = 3;
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
        LogWriter.log(TAG, "resample Lanczos-3: " + srcRate + "Hz/" + channels + "ch -> "
                + dstRate + "Hz/mono, " + rawPcm.length + " -> " + out.length + " bytes");
        return out;
    }

    // ========== 配音魔方 TTS (Cube) ==========

    private static Object[] doCubeTTS(String text, String outAmrPath) {
        try {
            String apiKey = WmPrefs.getStr("tts_cube_key", "");
            String voiceId = WmPrefs.getStr("tts_cube_voice", "");
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
             int amrSize = encodePcmToSilk(encodePcm, outAmrPath, pcm.length);
             int durationMs = pcmBytesToDurationMs(pcm.length);
             LogWriter.log(TAG, "CubeTTS: silk " + amrSize + "b " + durationMs + "ms");
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
        int lanczosWindow = 3;
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

        LogWriter.log(TAG, "PCM resample (Lanczos-3): " + srcRate + "Hz/" + channels + "ch -> "
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

    private static byte[] encodeSilkRaw(byte[] padPcm) {
        try {
            ClassLoader cl = sClassLoader;
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

            long handle = (Long) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.tencent.mm.modelvoice.MediaRecorder", cl),
                    "SilkEncInit", TARGET_SAMPLE_RATE, SILK_BITRATE, SILK_COMPLEXITY, 0L);
            LogWriter.log(TAG, "silk handle=" + handle);
            if (handle == 0) return null;

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
                XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("com.tencent.mm.modelvoice.MediaRecorder", cl),
                        "SilkDoEnc", f, (short) fb, ob, ol, last, handle);
                int len = ol[0];
                if (len <= 0) continue;
                baos.write(ob, 0, len);
            }

            XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.tencent.mm.modelvoice.MediaRecorder", cl),
                    "SetVoiceSilkControl", 201, 1, handle);

            XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.tencent.mm.modelvoice.MediaRecorder", cl),
                    "SilkEncUnInit", handle);

            byte[] silkBody = baos.toByteArray();

            LogWriter.log(TAG, "SILK encode: " + padPcm.length + "b PCM -> " + silkBody.length + "b");
            return silkBody;
        } catch (Throwable e) {
            LogWriter.log(TAG, "encodeSilkRaw err: " + e.getMessage());
            return null;
        }
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

    public static int encodePcmToSilk(byte[] pcm, String outPath, int originalPcmBytes) {
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
            int totalFrames = pcm.length / frameSize;

            LogWriter.log(TAG, "Silk V3 encoding: " + TARGET_SAMPLE_RATE + "Hz "
                    + TARGET_CHANNELS + "ch " + TARGET_BITS_PER_SAMPLE + "bit, "
                    + FRAME_DURATION_MS + "ms/frame, " + FRAME_SAMPLES + " samples, "
                    + FRAME_PCM_BYTES + " bytes/frame, bitrate=" + SILK_BITRATE
                    + "bps, complexity=" + SILK_COMPLEXITY + ", total="
                    + originalPcmBytes + " bytes, encodedTotal=" + pcm.length
                    + " bytes -> " + totalFrames + " frames");

            int frameIndex = 0;
            for (int off = 0; off < pcm.length; off += frameSize, frameIndex++) {
                byte[] frame = new byte[frameSize];
                System.arraycopy(pcm, off, frame, 0, frameSize);
                boolean isLast = frameIndex == totalFrames - 1;

                Object h0 = newH0(h0Cls, frame, frameSize, isLast);
                if (h0 == null) return 0;

                XposedHelpers.callMethod(silk, "a", h0, 0);
                if (frameIndex == 0 || isLast) {
                    LogWriter.log(TAG, "Silk frame push: idx=" + frameIndex
                            + " ts=0 size=" + frameSize
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

    // ========== WAV → SILK 转码器 (配音魔方) ==========

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
            String silkPath = buildVoicePath(clientMsgId);
            int silkSize = encodePcmToSilk(encodePcm, silkPath, pcm.length);
            if (silkSize <= 0) {
                LogWriter.log(TAG, "wavToSilk: silk encode fail");
                return null;
            }
            LogWriter.log(TAG, "wavToSilk: ok " + silkSize + "b -> " + silkPath);
            return silkPath;
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
        Thread t = new Thread(() -> {
            try {
                String silkPath = wavToSilk(wavFilePath, clientMsgId);
                if (silkPath == null) {
                    LogWriter.log(TAG, "sendWavAsVoice: wavToSilk failed");
                    return;
                }
                File wavFile = new File(wavFilePath);
                byte[] pcm = wavToPcm(wavFile);
                int durationMs = pcmBytesToDurationMs(pcm != null ? pcm.length : 0);
                boolean ok = sendViaSceneVoice(talker, silkPath, durationMs);
                LogWriter.log(TAG, "sendWavAsVoice sent=" + ok + " talker=" + talker);
            } catch (Throwable t2) {
                LogWriter.log(TAG, "sendWavAsVoice error: " + t2.getClass().getSimpleName() + " " + t2.getMessage());
            }
        }, "leshao-wav-voice");
        t.setDaemon(true);
        t.start();
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
