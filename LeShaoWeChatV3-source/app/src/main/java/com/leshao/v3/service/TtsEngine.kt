package com.leshao.v3.service

import android.content.Context
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.leshao.v3.ContextManager
import com.leshao.v3.LogWriter
import java.util.Locale
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

class TtsEngine(ctx: Context) {

    private var mTts: TextToSpeech? = null
    @Volatile
    private var mReady = false
    @Volatile
    private var mSpeaking = false
    private val mQueue: Queue<String> = ConcurrentLinkedQueue()
    private var mWakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var mErrorCount = 0
    private var mSpeechRate = 1.1f
    @Volatile
    private var mSpeakSeq = 0
    @Volatile
    private var mDoneSeq = 0
    @Volatile
    private var mPaused = false

    init {
        try {
            val prefs = ContextManager.getPrefs()
            if (prefs != null) {
                val saved = prefs.getFloat(KEY_SPEECH_RATE, 1.1f)
                if (saved >= 0.5f && saved <= 2.5f) mSpeechRate = saved
            }
        } catch (ignored: Throwable) {}

        val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "leshao:tts")

        mTts = TextToSpeech(ctx) { status ->
            // v986: 部分引擎会在构造函数返回前同步回调, 此时 mTts 尚未完成赋值, 直接使用会 NPE。
            val engine = mTts
            if (engine == null) {
                LogWriter.log(TAG, "TTS onInit 回调时 mTts 尚未赋值, 跳过本次初始化")
                return@TextToSpeech
            }
            if (status == TextToSpeech.SUCCESS) {
                engine.language = Locale.CHINESE
                engine.setSpeechRate(mSpeechRate)
                engine.setPitch(1.0f)
                mReady = true
                LogWriter.log(TAG, "TTS init OK rate=$mSpeechRate")
                flushQueue()
            } else {
                LogWriter.log(TAG, "TTS init FAILED, status=$status")
            }
        }
        mTts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mSpeaking = true
            }

            override fun onDone(utteranceId: String?) {
                mSpeaking = false
                mDoneSeq++
                mErrorCount = 0
                releaseWakeLock()
                flushQueue()
            }

            override fun onError(utteranceId: String?) {
                mSpeaking = false
                mDoneSeq++
                mErrorCount++
                LogWriter.log(TAG, "TTS onError count=$mErrorCount")
                releaseWakeLock()
                // pause() 调用 mTts.stop() 会触发 onError/onStop：
                // 此时不应继续播放下一条，否则暂停退化为"跳过当前条"
                if (mPaused) {
                    mPaused = false
                    mErrorCount = 0
                    return
                }
                if (mErrorCount < 3) flushQueue()
                else {
                    mQueue.clear()
                    mErrorCount = 0
                }
            }
        })
    }

    fun isSpeaking(): Boolean {
        return mSpeaking
    }

    fun hasPendingSpeak(): Boolean {
        return mSpeaking || mSpeakSeq > mDoneSeq || !mQueue.isEmpty()
    }

    fun setSpeechRate(rate: Float) {
        if (rate < 0.5f || rate > 2.5f) return
        mSpeechRate = rate
        mTts?.setSpeechRate(rate)
        try {
            val prefs = ContextManager.getPrefs()
            if (prefs != null) prefs.edit().putFloat(KEY_SPEECH_RATE, rate).apply()
        } catch (ignored: Throwable) {}
    }

    fun getSpeechRate(): Float {
        return mSpeechRate
    }

    fun speak(text: String?) {
        if (text == null || text.isEmpty()) return
        if (!mReady || mTts == null) {
            mQueue.offer(text)
            return
        }
        mSpeakSeq++
        acquireWakeLock()
        mTts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_" + System.currentTimeMillis())
    }

    fun speakQueued(text: String?) {
        if (text == null || text.isEmpty()) return
        if (!mReady || mTts == null) {
            mQueue.offer(text)
            return
        }
        mSpeakSeq++
        acquireWakeLock()
        mTts!!.speak(text, TextToSpeech.QUEUE_ADD, null, "tts_" + System.currentTimeMillis())
    }

    fun pause() {
        if (mTts != null && mSpeaking) {
            mPaused = true
            mTts!!.stop()
            mSpeaking = false
        }
    }

    private fun acquireWakeLock() {
        try {
            val wl = mWakeLock
            if (wl != null && !wl.isHeld) {
                wl.acquire(30000)
            }
        } catch (ignored: Throwable) {}
    }

    private fun releaseWakeLock() {
        try {
            val wl = mWakeLock
            if (wl != null && wl.isHeld) {
                wl.release()
            }
        } catch (ignored: Throwable) {}
    }

    private fun flushQueue() {
        val text = mQueue.poll() ?: return
        speak(text)
    }

    fun stop() {
        // v986: stop 后必须复位播放态, 否则 hasPendingSpeak() 可能因 mSpeaking 残留而长期为 true,
        // 导致 VoiceAutoPlay 侧长时间等待。
        mSpeaking = false
        mPaused = false
        try {
            mTts?.stop()
        } catch (ignored: Throwable) {}
        mQueue.clear()
        releaseWakeLock()
    }

    fun shutdown() {
        stop()
        try {
            mTts?.shutdown()
        } catch (ignored: Throwable) {}
    }

    fun isReady(): Boolean {
        return mReady
    }

    companion object {
        private const val TAG = "TtsEngine"
        private const val KEY_SPEECH_RATE = "ls_speech_rate"
    }
}