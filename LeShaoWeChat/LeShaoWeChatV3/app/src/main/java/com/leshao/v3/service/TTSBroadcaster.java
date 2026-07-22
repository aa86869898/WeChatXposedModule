package com.leshao.v3.service;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.model.WeChatMessage;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public class TTSBroadcaster {

    private static final String TAG = "TTSBroadcaster";
    private static TextToSpeech sTts;
    private static MediaPlayer sMp;
    private static final ArrayList<HashMap<String, String>> sQueue = new ArrayList<>();
    private static volatile boolean sSpeaking = false;
    private static final Object sLock = new Object();
    private static String sVoiceDir;
    private static long sLastAnnounce = 0;

    public static synchronized void process(WeChatMessage msg, ModuleConfig cfg) {
        if (msg == null || cfg == null) return;
        if (!isTypeEnabled(msg.type, cfg.announceTypeMask)) return;
        if (!cfg.announceWhitelist.isEmpty()) {
            boolean inWl = cfg.announceWhitelist.contains(msg.talker);
            if (msg.isGroup()) inWl = inWl || cfg.announceWhitelist.contains(msg.senderWxid);
            if (!inWl) return;
        }
        if (cfg.quietEnabled && isQuietTime(cfg)) return;

        String text = buildAnnounce(msg, cfg);
        if (text == null || text.isEmpty()) return;

        long now = System.currentTimeMillis();
        if (now - sLastAnnounce < cfg.announceIntervalMs) return;
        sLastAnnounce = now;

        if (cfg.textCutoffLen > 0 && text.length() > cfg.textCutoffLen)
            text = text.substring(0, cfg.textCutoffLen) + "...";

        String uid = "tts_" + System.currentTimeMillis();
        HashMap<String, String> item = new HashMap<>();
        item.put("text", text);
        item.put("id", uid);
        synchronized (sLock) {
            sQueue.add(item);
        }
        if (!sSpeaking) processNextQueued();
    }

    private static void processNextQueued() {
        HashMap<String, String> item;
        synchronized (sLock) {
            if (sQueue.isEmpty()) { sSpeaking = false; return; }
            item = sQueue.remove(0);
            sSpeaking = true;
        }
        final String text = item.get("text");
        final String uid = item.get("id");
        String eng = ModuleConfig.load(ContextManager.getPrefs()).ttsEngine;
        LogWriter.log(TAG, "TTS[" + eng + "]: " + text.substring(0, Math.min(40, text.length())));
        if ("peiyin".equals(eng)) speakPeiyin(text, uid);
        else if ("wusound".equals(eng)) speakWusound(text, uid);
        else speakSys(text, uid);
    }

    // ===== 配音魔方 TTS =====
    private static void speakPeiyin(final String text, final String uid) {
        new Thread(() -> {
            try {
                ModuleConfig cfg = ModuleConfig.load(ContextManager.getPrefs());
                if (cfg.peiyinApiKey.isEmpty() || cfg.peiyinVoiceId.isEmpty()) {
                    speakSys(text, uid);
                    return;
                }
                String reqBody = "{\"voiceId\":\"" + cfg.peiyinVoiceId + "\",\"text\":\"" + escapeJson(text) + "\"}";
                String resp = httpPost("https://peiyinmofang.com/api/open/v1/tts/simple-generate", reqBody, "application/json");
                if (resp != null && resp.contains("\"status\":200")) {
                    String audioUrl = extractJson(resp, "audio");
                    if (audioUrl != null && !audioUrl.isEmpty()) {
                        String f = getVoiceDir() + "/peiyin_" + uid + ".mp3";
                        if (downloadFile(audioUrl, f)) { playAudio(f); return; }
                    }
                }
                LogWriter.log(TAG, "TTS: 配音魔方失败, 回退系统TTS");
                speakSys(text, uid);
            } catch (Throwable e) {
                LogWriter.log(TAG, "TTS: speakPeiyin异常: " + e.getMessage());
                speakSys(text, uid);
            }
        }, "leshao-tts-py").start();
    }

    // ===== Wusound TTS =====
    private static void speakWusound(final String text, final String uid) {
        new Thread(() -> {
            try {
                ModuleConfig cfg = ModuleConfig.load(ContextManager.getPrefs());
                if (cfg.wusoundApiKey.isEmpty() || cfg.wusoundVoiceId.isEmpty()) {
                    speakSys(text, uid);
                    return;
                }
                String reqBody = "{\"voiceId\":\"" + cfg.wusoundVoiceId
                    + "\",\"promptId\":\"" + cfg.wusoundPromptId
                    + "\",\"text\":\"" + escapeJson(text) + "\"}";
                String resp = httpPost("https://v1.wusound.cn/api/tts/simple-generate", reqBody, "application/json");
                if (resp != null && resp.contains("\"status\":200")) {
                    String audioUrl = extractJson(resp, "audio");
                    if (audioUrl != null && !audioUrl.isEmpty()) {
                        String f = getVoiceDir() + "/wusound_" + uid + ".wav";
                        if (downloadFile(audioUrl, f)) { playAudio(f); return; }
                    }
                }
                LogWriter.log(TAG, "TTS: 五声失败, 回退系统TTS");
                speakSys(text, uid);
            } catch (Throwable e) {
                LogWriter.log(TAG, "TTS: speakWusound异常: " + e.getMessage());
                speakSys(text, uid);
            }
        }, "leshao-tts-ws").start();
    }

    // ===== 系统 TTS =====
    private static void speakSys(final String text, final String uid) {
        Context ctx = ContextManager.getAppContext();
        if (ctx == null) { queueNextAfterDelay(); return; }
        if (sTts == null) {
            sTts = new TextToSpeech(ctx, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    int r = sTts.setLanguage(Locale.CHINA);
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        sTts.setLanguage(Locale.US);
                    }
                    sTts.setSpeechRate(0.9f);
                }
            });
        }
        final TextToSpeech localTts = sTts;
        if (localTts == null) { queueNextAfterDelay(); return; }
        try {
            localTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                public void onStart(String id) {}
                public void onDone(String id) { queueNextAfterDelay(); }
                public void onError(String id) { queueNextAfterDelay(); }
            });
            localTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid);
        } catch (Throwable e) {
            LogWriter.log(TAG, "TTS: speakSys异常: " + e.getMessage());
            queueNextAfterDelay();
        }
    }

    // ===== 音频播放 =====
    private static void playAudio(final String path) {
        try {
            stopMp();
            sMp = new MediaPlayer();
            sMp.setDataSource(path);
            sMp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            sMp.setOnCompletionListener(p -> {
                try { p.release(); } catch (Throwable e) {}
                sMp = null;
                new File(path).delete();
                processNextQueued();
            });
            sMp.setOnErrorListener((p, what, extra) -> {
                LogWriter.log(TAG, "TTS: MediaPlayer error what=" + what);
                try { p.release(); } catch (Throwable e) {}
                sMp = null;
                new File(path).delete();
                processNextQueued();
                return true;
            });
            sMp.prepare();
            sMp.start();
        } catch (Throwable e) {
            LogWriter.log(TAG, "TTS: playAudio异常: " + e.getMessage());
            stopMp();
            new File(path).delete();
            processNextQueued();
        }
    }

    private static void stopMp() {
        if (sMp != null) { try { sMp.release(); } catch (Throwable e) {} sMp = null; }
    }

    private static void queueNextAfterDelay() {
        new Thread(() -> {
            try { Thread.sleep(ModuleConfig.load(ContextManager.getPrefs()).announceIntervalMs); } catch (Throwable e) {}
            processNextQueued();
        }).start();
    }

    public static void stopAll() {
        synchronized (sLock) { sQueue.clear(); sSpeaking = false; }
        stopMp();
        if (sTts != null) { try { sTts.stop(); } catch (Throwable e) {} }
    }

    public static void shutdown() {
        stopAll();
        if (sTts != null) { try { sTts.shutdown(); } catch (Throwable e) {} sTts = null; }
    }

    // ===== 播报文本构建 =====
    private static String buildAnnounce(WeChatMessage msg, ModuleConfig cfg) {
        String senderName = msg.senderWxid;
        if (senderName == null || senderName.isEmpty()) senderName = msg.talker;

        if (msg.isText()) {
            if (!cfg.announceText) return null;
            String ct = msg.content != null ? msg.content.trim() : "";
            if (ct.isEmpty()) return null;
            StringBuilder p = new StringBuilder();
            if (msg.isGroup() && cfg.announceGroup) p.append(msg.talker).append(" ");
            if (cfg.announceNickname) p.append(senderName);
            return p.length() > 0 ? p.toString() + ct : senderName + "发来消息";
        }
        String prefix;
        if (msg.isGroup() && cfg.announceGroup) {
            prefix = msg.talker + "的" + senderName;
        } else {
            prefix = senderName;
        }
        if (msg.isImage()) { if (!cfg.announceImage) return null; return prefix + "发来[图片]"; }
        if (msg.isVideo()) { if (!cfg.announceVideo) return null; return prefix + "发来[视频]"; }
        if (msg.isVoice()) {
            StringBuilder vp = new StringBuilder();
            if (msg.isGroup() && cfg.announceGroup) vp.append(msg.talker).append(" ");
            if (cfg.announceNickname) vp.append(senderName);
            if (vp.length() > 0) vp.append(" 说 ");
            return vp.toString() + "语音播放";
        }
        if (msg.isCard()) { if (!cfg.announceCard) return null; return prefix + "发来[名片]"; }
        if (msg.isSticker()) { if (!cfg.announceSticker) return null; return prefix + "发来一个表情"; }
        if (msg.isRedBag()) { if (!cfg.announceRedBag) return null; return prefix + "发来[红包]"; }
        if (msg.isTransfer()) { if (!cfg.announceTransfer) return null; return prefix + "发来[转账]"; }
        if (msg.isFile()) { if (!cfg.announceFile) return null; return prefix + "发来[文件]"; }
        if (msg.isLocation()) { if (!cfg.announceLocation) return null; return prefix + "发来[位置]"; }
        if (msg.isVoip()) { if (!cfg.announceCall) return null; return prefix + "发起语音通话"; }
        return prefix + "发来一条消息";
    }

    private static boolean isTypeEnabled(int type, int mask) {
        return (mask & type) != 0;
    }

    private static boolean isQuietTime(ModuleConfig cfg) {
        try {
            java.util.Calendar now = java.util.Calendar.getInstance();
            int nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
            String[] ps = cfg.quietStart.split(":");
            String[] pe = cfg.quietEnd.split(":");
            int stMin = Integer.parseInt(ps[0]) * 60 + Integer.parseInt(ps[1]);
            int edMin = Integer.parseInt(pe[0]) * 60 + Integer.parseInt(pe[1]);
            if (stMin <= edMin) return nowMin >= stMin && nowMin <= edMin;
            return nowMin >= stMin || nowMin <= edMin;
        } catch (Throwable e) { return false; }
    }

    // ===== HTTP 工具 =====
    private static String httpPost(String url, String body, String ct) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(30000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", ct != null ? ct : "application/json");
            java.io.OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8")); os.flush(); os.close();
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.InputStream is = c.getInputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            c.disconnect();
            return bos.toString("UTF-8");
        } catch (Throwable e) { return null; }
    }

    private static String extractJson(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":\"");
        if (idx < 0) return null;
        idx += key.length() + 4;
        int end = json.indexOf("\"", idx);
        if (end < 0) return json.substring(idx);
        return json.substring(idx, end).replace("\\/", "/");
    }

    private static boolean downloadFile(String url, String path) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(60000);
            if (c.getResponseCode() != 200) { c.disconnect(); return false; }
            File f = new File(path); f.getParentFile().mkdirs();
            java.io.InputStream is = c.getInputStream();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            byte[] b = new byte[8192]; int n;
            while ((n = is.read(b)) != -1) fos.write(b, 0, n);
            fos.close(); is.close(); c.disconnect();
            return f.exists();
        } catch (Throwable e) { return false; }
    }

    private static String getVoiceDir() {
        if (sVoiceDir == null) {
            sVoiceDir = ContextManager.getAppContext() != null
                ? ContextManager.getAppContext().getFilesDir() + "/leshao_voice"
                : "/data/data/com.tencent.mm/files/leshao_voice";
            new File(sVoiceDir).mkdirs();
        }
        return sVoiceDir;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static boolean isText(WeChatMessage msg) { return msg.type == WeChatMessage.TYPE_TEXT; }
    private static boolean isImage(WeChatMessage msg) { return msg.type == WeChatMessage.TYPE_IMAGE; }
    private static boolean isVideo(WeChatMessage msg) { return msg.type == WeChatMessage.TYPE_VIDEO; }
}
