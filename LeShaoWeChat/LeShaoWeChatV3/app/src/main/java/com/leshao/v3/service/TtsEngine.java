package com.leshao.v3.service;

import android.content.Context;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.leshao.v3.LogWriter;

import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;

public class TtsEngine {

    private static final String TAG = "TtsEngine";

    private TextToSpeech mTts;
    private volatile boolean mReady = false;
    private final Queue<String> mQueue = new LinkedList<>();
    private PowerManager.WakeLock mWakeLock;
    private volatile int mErrorCount = 0;

    public TtsEngine(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getApplicationContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "leshao:tts");

        mTts = new TextToSpeech(ctx, status -> {
            if (status == TextToSpeech.SUCCESS) {
                mTts.setLanguage(Locale.CHINESE);
                mTts.setSpeechRate(1.1f);
                mTts.setPitch(1.0f);
                mReady = true;
                LogWriter.log(TAG, "TTS init OK");
                flushQueue();
            } else {
                LogWriter.log(TAG, "TTS init FAILED, status=" + status);
            }
        });
        mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {}
            @Override public void onDone(String utteranceId) {
                mErrorCount = 0;
                releaseWakeLock();
                flushQueue();
            }
            @Override public void onError(String utteranceId) {
                mErrorCount++;
                LogWriter.log(TAG, "TTS onError count=" + mErrorCount);
                releaseWakeLock();
                if (mErrorCount < 3) flushQueue();
                else {
                    mQueue.clear();
                    mErrorCount = 0;
                }
            }
        });
    }

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
        if (mTts != null) { mTts.shutdown(); }
    }

    public boolean isReady() { return mReady; }
}
