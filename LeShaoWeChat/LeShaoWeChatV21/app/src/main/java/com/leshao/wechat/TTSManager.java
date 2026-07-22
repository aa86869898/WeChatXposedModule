package com.leshao.wechat;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import org.json.JSONObject;

public class TTSManager {
    private static Context ctx;
    private static volatile TextToSpeech tts;
    private static MediaPlayer mp;
    private static final ArrayList<HashMap<String,String>> queued = new ArrayList<>();
    private static volatile boolean speaking = false;
    private static final Object lock = new Object();
    private static String voiceDir;

    public static void init(Context c) {
        ctx = c;
        voiceDir = c.getFilesDir() + "/leshao_voice";
        new File(voiceDir).mkdirs();
        destroyTTS();
        try {
            tts = new TextToSpeech(c, new TextToSpeech.OnInitListener() {
                public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS) {
                        int r = tts.setLanguage(Locale.CHINA);
                        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            Utils.flog("TTS: 中文语言包不可用, 回退英文");
                            tts.setLanguage(Locale.US);
                        }
                        tts.setSpeechRate(0.9f);
                        Utils.flog("TTS: 引擎初始化成功");
                    } else {
                        Utils.flog("TTS: 引擎初始化失败 status=" + status);
                    }
                }
            });
        } catch (Throwable e) {
            Utils.flog("TTS: 引擎创建失败: " + e.getMessage());
            tts = null;
        }
    }

    public static void speak(String text) {
        if (text == null || text.isEmpty()) return;
        text = Utils.stt(text);
        if (text.isEmpty() || isQuiet()) return;
        String uid = "tts_" + System.currentTimeMillis();
        HashMap<String,String> item = new HashMap<>();
        item.put("text", text);
        item.put("id", uid);
        synchronized (lock) {
            queued.add(item);
            Utils.flog("TTS: 队列+1 size=" + queued.size() + " speaking=" + speaking);
        }
        if (!speaking) processNextQueued();
    }

    private static void processNextQueued() {
        HashMap<String,String> item;
        synchronized (lock) {
            if (queued.isEmpty()) { speaking = false; return; }
            item = queued.remove(0);
            speaking = true;
        }
        final String text = item.get("text");
        final String uid = item.get("id");
        String eng = ModuleSettings.ttsEngine;
        Utils.flog("TTS[" + eng + "]: " + text.substring(0, Math.min(40, text.length())));
        if ("peiyin".equals(eng)) speakPeiyin(text, uid);
        else if ("wusound".equals(eng)) speakWusound(text, uid);
        else speakSys(text, uid);
    }

    private static void speakPeiyin(final String text, final String uid) {
        Utils.rB(new Runnable() { public void run() {
            try {
                String key = ModuleSettings.peiyinApiKey;
                String vid = ModuleSettings.peiyinVoiceId;
                if (key.isEmpty() || vid.isEmpty()) { speakSys(text, uid); return; }
                JSONObject req = new JSONObject(); req.put("voiceId", vid); req.put("text", text);
                String resp = Utils.httpPost("https://peiyinmofang.com/api/open/v1/tts/simple-generate", req.toString(), "application/json");
                if (resp != null) {
                    JSONObject j = new JSONObject(resp);
                    if (j.optInt("status") == 200) {
                        JSONObject d = j.optJSONObject("data");
                        if (d != null) {
                            String url = d.optString("audio", "");
                            if (!url.isEmpty()) {
                                String f = voiceDir + "/peiyin_" + uid + ".mp3";
                                if (Utils.dl(url, f)) { playAudio(f); return; }
                            }
                        }
                    }
                }
                Utils.flog("TTS: 配音魔方失败, 回退系统TTS");
                speakSys(text, uid);
            } catch (Throwable e) {
                Utils.flog("TTS: speakPeiyin异常: " + e.getMessage());
                speakSys(text, uid);
            }
        }});
    }

    private static void speakWusound(final String text, final String uid) {
        Utils.rB(new Runnable() { public void run() {
            try {
                String key = ModuleSettings.wusoundApiKey;
                String vid = ModuleSettings.wusoundVoiceId;
                if (key.isEmpty() || vid.isEmpty()) { speakSys(text, uid); return; }
                JSONObject req = new JSONObject();
                req.put("voiceId", vid);
                req.put("promptId", ModuleSettings.wusoundPromptId);
                req.put("text", text);
                String resp = Utils.httpPost("https://v1.wusound.cn/api/tts/simple-generate", req.toString(), "application/json");
                if (resp != null) {
                    JSONObject j = new JSONObject(resp);
                    if (j.optInt("status") == 200) {
                        JSONObject d = j.optJSONObject("data");
                        if (d != null) {
                            String url = d.optString("audio", "");
                            if (!url.isEmpty()) {
                                String f = voiceDir + "/wusound_" + uid + ".wav";
                                if (Utils.dl(url, f)) { playAudio(f); return; }
                            }
                        }
                    }
                }
                Utils.flog("TTS: 五声失败, 回退系统TTS");
                speakSys(text, uid);
            } catch (Throwable e) {
                Utils.flog("TTS: speakWusound异常: " + e.getMessage());
                speakSys(text, uid);
            }
        }});
    }

    private static void speakSys(final String text, final String uid) {
        final TextToSpeech localTts = tts;
        if (localTts == null) {
            Utils.flog("TTS: 系统引擎不可用, 跳过");
            queueNextAfterDelay();
            return;
        }
        Utils.rM(new Runnable() { public void run() {
            try {
                if (tts != localTts || tts == null) {
                    Utils.flog("TTS: 引擎已变更, 跳过");
                    queueNextAfterDelay();
                    return;
                }
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    public void onStart(String id) {}
                    public void onDone(String id) {
                        Utils.flog("TTS: onDone id=" + id);
                        queueNextAfterDelay();
                    }
                    public void onError(String id) {
                        Utils.flog("TTS: onError id=" + id);
                        queueNextAfterDelay();
                    }
                });
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid);
            } catch (Throwable e) {
                Utils.flog("TTS: speakSys异常: " + e.getMessage());
                queueNextAfterDelay();
            }
        }});
    }

    private static void queueNextAfterDelay() {
        Utils.rB(new Runnable() { public void run() {
            try { Thread.sleep(ModuleSettings.announceIntervalMs); } catch (Throwable e) {}
            processNextQueued();
        }});
    }

    private static void playAudio(final String path) {
        Utils.rM(new Runnable() { public void run() {
            try {
                stopMP();
                mp = new MediaPlayer();
                mp.setDataSource(path);
                mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
                mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    public void onCompletion(MediaPlayer p) {
                        try { p.release(); } catch (Throwable e) {}
                        mp = null;
                        new File(path).delete();
                        processNextQueued();
                    }
                });
                mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    public boolean onError(MediaPlayer p, int what, int extra) {
                        Utils.flog("TTS: MediaPlayer error what=" + what + " extra=" + extra);
                        try { p.release(); } catch (Throwable e) {}
                        mp = null;
                        new File(path).delete();
                        processNextQueued();
                        return true;
                    }
                });
                mp.prepare();
                mp.start();
            } catch (Throwable e) {
                Utils.flog("TTS: playAudio异常: " + e.getMessage());
                stopMP();
                new File(path).delete();
                processNextQueued();
            }
        }});
    }

    private static void stopMP() {
        if (mp != null) { try { mp.release(); } catch (Throwable e) {} mp = null; }
    }

    public static void stopAll() {
        synchronized (lock) { queued.clear(); speaking = false; }
        stopMP();
        if (tts != null) { try { tts.stop(); } catch (Throwable e) {} }
    }

    public static void release() {
        stopAll();
        destroyTTS();
    }

    private static void destroyTTS() {
        if (tts != null) {
            try { tts.stop(); } catch (Throwable e) {}
            try { tts.shutdown(); } catch (Throwable e) {}
            tts = null;
        }
    }

    private static boolean isQuiet() {
        if (!ModuleSettings.quietEnabled) return false;
        try {
            java.util.Calendar now = java.util.Calendar.getInstance();
            int nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
            String[] ps = ModuleSettings.quietStart.split(":");
            String[] pe = ModuleSettings.quietEnd.split(":");
            int stMin = Integer.parseInt(ps[0]) * 60 + Integer.parseInt(ps[1]);
            int edMin = Integer.parseInt(pe[0]) * 60 + Integer.parseInt(pe[1]);
            if (stMin <= edMin) return nowMin >= stMin && nowMin <= edMin;
            return nowMin >= stMin || nowMin <= edMin;
        } catch (Throwable e) { return false; }
    }
}
