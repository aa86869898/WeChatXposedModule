package com.leshao.v3.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;

public class TtsEngine {

    private static final String TAG = "TtsEngine";
    private static final String KEY_SPEECH_RATE = "ls_speech_rate";

    private TextToSpeech mTts;
    private volatile boolean mReady = false;
    private volatile boolean mSpeaking = false;
    private final Queue<String> mQueue = new LinkedList<>();
    private PowerManager.WakeLock mWakeLock;
    private volatile int mErrorCount = 0;
    private float mSpeechRate = 1.1f;

    public TtsEngine(Context ctx) {
        try {
            SharedPreferences prefs = ContextManager.getPrefs();
            if (prefs != null) {
                float saved = prefs.getFloat(KEY_SPEECH_RATE, 1.1f);
                if (saved >= 0.5f && saved <= 2.5f) mSpeechRate = saved;
            }
        } catch (Throwable ignored) {}

        PowerManager pm = (PowerManager) ctx.getApplicationContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "leshao:tts");

        mTts = new TextToSpeech(ctx, status -> {
            if (status == TextToSpeech.SUCCESS) {
                mTts.setLanguage(Locale.CHINESE);
                mTts.setSpeechRate(mSpeechRate);
                mTts.setPitch(1.0f);
                mReady = true;
                LogWriter.log(TAG, "TTS init OK rate=" + mSpeechRate);
                flushQueue();
            } else {
                LogWriter.log(TAG, "TTS init FAILED, status=" + status);
            }
        });
        mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { mSpeaking = true; }
            @Override public void onDone(String utteranceId) {
                mSpeaking = false;
                mErrorCount = 0;
                releaseWakeLock();
                flushQueue();
            }
            @Override public void onError(String utteranceId) {
                mSpeaking = false;
                mErrorCount++;
                LogWriter.log(TAG, "TTS onError count=" + mErrorCount);
                releaseWakeLock();
                if (mErrorCount < 3) flushQueue();
                else { mQueue.clear(); mErrorCount = 0; }
            }
        });
    }

    public boolean isSpeaking() { return mSpeaking; }

    public void setSpeechRate(float rate) {
        if (rate < 0.5f || rate > 2.5f) return;
        mSpeechRate = rate;
        if (mTts != null) mTts.setSpeechRate(rate);
        try {
            SharedPreferences prefs = ContextManager.getPrefs();
            if (prefs != null) prefs.edit().putFloat(KEY_SPEECH_RATE, rate).apply();
        } catch (Throwable ignored) {}
    }

    public float getSpeechRate() { return mSpeechRate; }

    public void speak(String text) {
        if (text == null || text.isEmpty()) return;
        if (!mReady || mTts == null) { mQueue.offer(text); return; }
        acquireWakeLock();
        mTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_" + System.currentTimeMillis());
    }

    public void speakQueued(String text) {
        if (text == null || text.isEmpty()) return;
        if (!mReady || mTts == null) { mQueue.offer(text); return; }
        acquireWakeLock();
        mTts.speak(text, TextToSpeech.QUEUE_ADD, null, "tts_" + System.currentTimeMillis());
    }

    public void pause() {
        if (mTts != null && mSpeaking) {
            mTts.stop();
            mSpeaking = false;
        }
    }

    private void acquireWakeLock() {
        try {
            if (mWakeLock != null && !mWakeLock.isHeld()) {
                mWakeLock.acquire(30000);
            }
        } catch (Throwable ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        } catch (Throwable ignored) {}
    }

    private void flushQueue() {
        if (!mQueue.isEmpty()) {
            String text = mQueue.poll();
            speak(text);
        }
    }

    public void stop() {
        if (mTts != null) mTts.stop();
        mQueue.clear();
        releaseWakeLock();
    }

    public void shutdown() {
        stop();
        if (mTts != null) mTts.shutdown();
    }

    public boolean isReady() { return mReady; }
}
