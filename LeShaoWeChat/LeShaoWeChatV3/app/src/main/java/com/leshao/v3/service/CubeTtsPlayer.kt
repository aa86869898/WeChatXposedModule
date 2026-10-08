package com.leshao.v3.service

import android.content.Context
import android.media.MediaPlayer
import android.os.PowerManager
import com.leshao.v3.LogWriter
import com.leshao.v3.wm.utils.WmPrefs
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import org.json.JSONObject

class CubeTtsPlayer(ctx: Context) {

    private val mCacheDir: File
    private val mWakeLock: PowerManager.WakeLock?
    private val mQueue = ConcurrentLinkedQueue<String>()
    @Volatile
    private var mPlaying = false
    @Volatile
    private var mPaused = false
    @Volatile
    private var mSpeakSeq = 0
    @Volatile
    private var mDoneSeq = 0

    init {
        mCacheDir = File(ctx.filesDir, "cube_tts_cache")
        mCacheDir.mkdirs()

        val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "leshao:cube_tts")
    }

    fun isSpeaking(): Boolean {
        return mPlaying
    }

    fun hasPendingSpeak(): Boolean {
        return mPlaying || mSpeakSeq > mDoneSeq || !mQueue.isEmpty()
    }

    fun speak(text: String?) {
        if (text == null || text.isEmpty()) return
        mQueue.offer(text)
        processQueue()
    }

    fun pause() {
        mPaused = true
        mPlaying = false
    }

    fun stop() {
        mPaused = false
        mPlaying = false
        mQueue.clear()
    }

    fun shutdown() {
        stop()
    }

    private fun processQueue() {
        // v986: 原子地判定/置位 mPlaying 并取队首, 避免 speak() 与 worker 收尾并发时双重起播。
        val text: String
        synchronized(this) {
            if (mPlaying) return
            text = mQueue.poll() ?: return
            mPlaying = true
            mSpeakSeq++
        }
        acquireWakeLock()

        Thread({
            try {
                val wav = synthesize(text)
                if (wav != null && !mPaused) {
                    playWavSync(wav)
                    wav.delete()
                }
            } catch (t: Throwable) {
                LogWriter.log(TAG, "process err: " + t.message)
            } finally {
                synchronized(this) {
                    mPlaying = false
                    mDoneSeq++
                }
                releaseWakeLock()
                if (!mPaused) processQueue()
            }
        }, "leshao-cube-tts").start()
    }

    private fun synthesize(text: String): File? {
        try {
            val apiKey = WmPrefs.getStr("tts_cube_key", "")
            val voiceId = WmPrefs.getStr("tts_cube_voice", "")
            if (apiKey.isEmpty() || voiceId.isEmpty()) {
                LogWriter.log(TAG, "synthesize: key or voice empty, fallback to system TTS")
                return null
            }
            // v986: 密钥脱敏, 禁止打印完整 Key(参考 AppConfig 掩码策略)。
            LogWriter.log(TAG, "synthesize: key=" + maskKey(apiKey) + " voice=" + voiceId)

            val url = URL(API_URL)
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-API-Key", apiKey)

                val jsonBody = JSONObject()
                    .put("voiceId", voiceId)
                    .put("text", text)
                    .toString()
                var os: OutputStream? = null
                try {
                    os = conn.outputStream
                    os!!.write(jsonBody.toByteArray(Charsets.UTF_8))
                    os!!.flush()
                } finally {
                    if (os != null) {
                        try {
                            os.close()
                        } catch (ignored: Throwable) {}
                    }
                }

                val code = conn.responseCode
                if (code != 200) {
                    LogWriter.log(TAG, "synthesize: API HTTP " + code)
                    return null
                }

                // v986: 先整体读字节再一次性 UTF-8 解码, 避免按 4KB 分块打断多字节字符。
                val rbaos = ByteArrayOutputStream()
                var ins: InputStream? = null
                try {
                    ins = conn.inputStream
                    val buf = ByteArray(4096)
                    var n = ins.read(buf)
                    while (n > 0) {
                        rbaos.write(buf, 0, n)
                        n = ins.read(buf)
                    }
                } finally {
                    if (ins != null) {
                        try {
                            ins.close()
                        } catch (ignored: Throwable) {}
                    }
                }

                val resp = String(rbaos.toByteArray(), Charsets.UTF_8)
                val audioUrl = parseAudioUrl(resp)
                if (audioUrl == null || audioUrl.isEmpty()) {
                    LogWriter.log(TAG, "synthesize: no audio field")
                    return null
                }

                val audioURL = URL(audioUrl)
                val aConn = audioURL.openConnection() as HttpURLConnection
                try {
                    aConn.connectTimeout = 15000
                    aConn.readTimeout = 15000
                    if (aConn.responseCode != 200) return null

                    val wav = File(mCacheDir, "tts_" + System.currentTimeMillis() + ".wav")
                    var ais: InputStream? = null
                    var fos: FileOutputStream? = null
                    try {
                        ais = aConn.inputStream
                        fos = FileOutputStream(wav)
                        val wBuf = ByteArray(8192)
                        var rn: Int
                        while (ais!!.read(wBuf).also { rn = it } > 0) fos!!.write(wBuf, 0, rn)
                        fos!!.flush()
                    } finally {
                        if (ais != null) {
                            try {
                                ais.close()
                            } catch (ignored: Throwable) {}
                        }
                        if (fos != null) {
                            try {
                                fos.close()
                            } catch (ignored: Throwable) {}
                        }
                    }

                    LogWriter.log(TAG, "synthesize OK: " + wav.length() + "b")
                    return wav
                } finally {
                    aConn.disconnect()
                }
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            LogWriter.log(TAG, "synthesize err: " + t.message)
            return null
        }
    }

    /** v986: 解析合成响应中的音频 URL, 优先 JSON, 回退字符串定位。 */
    private fun parseAudioUrl(resp: String?): String? {
        if (resp == null || resp.isEmpty()) return null
        try {
            val jo = JSONObject(resp)
            val data = jo.optJSONObject("data")
            var audio = if (data != null) data.optString("audio", "") else ""
            if (audio.isEmpty()) audio = jo.optString("audio", "")
            if (audio.isNotEmpty()) return audio.replace("\\/", "/")
        } catch (ignored: Throwable) {}
        try {
            val audioIdx = resp.indexOf("\"audio\":\"")
            if (audioIdx < 0) return null
            val audioStart = audioIdx + 9
            val audioEnd = resp.indexOf("\"", audioStart)
            if (audioEnd < 0) return null
            return resp.substring(audioStart, audioEnd).replace("\\/", "/")
        } catch (ignored: Throwable) {
            return null
        }
    }

    /** v986: 日志密钥脱敏, 只保留首尾各 2 位。 */
    private fun maskKey(key: String): String {
        if (key.isEmpty()) return "<empty>"
        val n = key.length
        if (n <= 4) return "****"
        return key.substring(0, 2) + "****" + key.substring(n - 2)
    }

    private fun playWavSync(wav: File) {
        val mp = MediaPlayer()
        try {
            mp.setDataSource(wav.absolutePath)
            mp.prepare()
            mp.setOnCompletionListener { m ->
                synchronized(m) {
                    (m as java.lang.Object).notifyAll()
                }
            }
            // v986: 出错时也要唤醒等待线程, 否则异常路径会挂满 duration+5000ms。
            mp.setOnErrorListener { m, what, extra ->
                LogWriter.log(TAG, "play err callback what=" + what)
                synchronized(m) {
                    (m as java.lang.Object).notifyAll()
                }
                true
            }
            mp.start()
            var dur = 0
            try {
                dur = mp.duration
            } catch (ignored: Throwable) {}
            if (dur <= 0) dur = 15000
            synchronized(mp) {
                try {
                    (mp as java.lang.Object).wait((dur + 5000).toLong())
                } catch (ignored: InterruptedException) {}
            }
        } catch (t: Throwable) {
            LogWriter.log(TAG, "play err: " + t.message)
        } finally {
            try {
                mp.release()
            } catch (ignored: Throwable) {}
        }
    }

    private fun acquireWakeLock() {
        try {
            val wl = mWakeLock
            if (wl != null && !wl.isHeld) {
                wl.acquire(60000)
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

    companion object {
        private const val TAG = "CubeTtsPlayer"
        private const val API_URL = "https://peiyinmofang.com/api/open/v1/tts/simple-generate"
    }
}