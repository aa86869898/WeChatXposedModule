package com.leshao.ai.tts;

import android.content.Context;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/**
 * 极简 TTS 封装（Android 系统 TTS），用于机器人语音回复。
 * 在微信进程内使用系统 TextToSpeech 引擎朗读回复文本。
 */
public final class TtsEngine implements TextToSpeech.OnInitListener {

    private static final String TAG = "LeshaoAI.TTS";

    private static volatile TtsEngine instance;

    private final Object lock = new Object();
    private TextToSpeech tts;
    private volatile boolean ready = false;
    private volatile String pendingText;

    private TtsEngine() {
    }

    /** 惰性单例。 */
    public static TtsEngine get(Context context) {
        if (instance == null) {
            synchronized (TtsEngine.class) {
                if (instance == null) {
                    instance = new TtsEngine();
                }
            }
        }
        instance.ensureInit(context);
        return instance;
    }

    private void ensureInit(Context context) {
        if (tts == null && context != null) {
            try {
                synchronized (lock) {
                    if (tts == null) {
                        tts = new TextToSpeech(context.getApplicationContext(), this);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "TTS 初始化失败: " + t);
            }
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            // v986: 部分引擎会在构造函数内同步回调 onInit, 此时 tts 字段尚未完成赋值,
            // 直接调用会 NPE。这里做空值防护, 真正的回调(异步)会再次进入本方法。
            TextToSpeech engine = tts;
            if (engine == null) {
                Log.w(TAG, "onInit 时 tts 尚未赋值，跳过本次初始化");
                return;
            }
            int result = engine.setLanguage(Locale.CHINESE);
            if (result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "中文语音包缺失，可能无法朗读");
            }
            ready = true;
            if (pendingText != null) {
                speak(pendingText);
                pendingText = null;
            }
        } else {
            Log.w(TAG, "TTS 初始化失败 status=" + status);
        }
    }

    /** 朗读文本（异步）。返回是否已接受。 */
    public boolean speak(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        if (!ready) {
            pendingText = text;
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "leshao-ai");
            } else {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "TTS 朗读失败: " + t);
            return false;
        }
    }

    /** 停止朗读。 */
    public void stop() {
        try {
            if (tts != null && ready) {
                tts.stop();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 释放资源。 */
    public void shutdown() {
        try {
            if (tts != null) {
                tts.shutdown();
                tts = null;
                ready = false;
            }
        } catch (Throwable ignored) {
        }
    }
}