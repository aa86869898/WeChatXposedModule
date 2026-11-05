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
    private val mWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "leshao-cube-tts")
    }
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
        mWorker.shutdownNow()
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

        mWorker.execute {
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
        }
    }

    private fun synthesize(text: String): File? {
        // ★ 多音色池优先：随机选一个音色播报（竹节跳动/傻包/配音魔方）
        val poolKey = com.leshao.v3.ui.TTSPageView.randomVoiceFromPool()
        if (poolKey != null) {
            val idx = poolKey.indexOf('|')
            val poolEngine = if (idx > 0) poolKey.substring(0, idx) else ""
            val poolVoice = if (idx > 0 && idx < poolKey.length - 1) poolKey.substring(idx + 1) else ""
            return when (poolEngine) {
                "jianying" -> synthesizeJianYing(text, poolVoice)
                "silly" -> synthesizeFreeLogin(text, poolVoice)
                "cube" -> synthesizeCube(text, poolVoice)
                else -> null
            }
        }
        // 回退：无音色池时按单引擎配置合成
        val engine = WmPrefs.getTtsEngine()
        if (engine == "system") return null
        if (engine == "silly") {
            val freeLogin = WmPrefs.getStr("tts_freelogin_voice", "")
            if (freeLogin.isEmpty()) {
                LogWriter.log(TAG, "synthesize: silly engine but no freelogin voice")
                return null
            }
            return synthesizeFreeLogin(text, freeLogin)
        }
        val gatewayVoice = WmPrefs.getStr("tts_gateway_voice", "")
        if (gatewayVoice.isNotEmpty()) {
            return synthesizeGateway(gatewayVoice, text)
        }
        val apiKey = WmPrefs.getStr("tts_cube_key", "")
        val voiceId = WmPrefs.getStr("tts_cube_voice", "")
        if (apiKey.isEmpty() || voiceId.isEmpty()) {
            LogWriter.log(TAG, "synthesize: key or voice empty, fallback to system TTS")
            return null
        }
        return synthesizeCube(text, voiceId)
    }

    /** 配音魔方 API 合成：POST 魔方接口，解析 audioUrl 下载 WAV。 */
    private fun synthesizeCube(text: String, voiceId: String): File? {
        try {
            val apiKey = WmPrefs.getStr("tts_cube_key", "")
            if (apiKey.isEmpty() || voiceId.isEmpty()) {
                LogWriter.log(TAG, "synthesizeCube: key or voice empty")
                return null
            }
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

    /** 竹节跳动（剪映）TTS 合成：返回 PCM → WAV 文件。 */
    private fun synthesizeJianYing(text: String, voiceType: String): File? {
        try {
            if (voiceType.isEmpty()) return null
            LogWriter.log(TAG, "jianying synthesize voice=" + voiceType)
            val pcm = com.leshao.v3.service.JianYingTtsClient.synthesize(text, voiceType, 90)
            if (pcm == null || pcm.size == 0) return null
            val wavBytes = com.leshao.v3.service.JianYingTtsClient.pcmToWav(pcm, 24000)
            if (wavBytes == null) return null
            val wav = File(mCacheDir, "tts_" + System.currentTimeMillis() + ".wav")
            FileOutputStream(wav).use { it.write(wavBytes) }
            LogWriter.log(TAG, "jianying synthesize OK: " + wav.length() + "b")
            return wav
        } catch (t: Throwable) {
            LogWriter.log(TAG, "jianying synthesize err: " + t.message)
            return null
        }
    }


    /** 免登录豆包 TTS 合成（V2 引擎）：DoubaoTtsV2Bridge 返回 OGG/Opus，解码为 WAV 播放。 */
    private fun synthesizeFreeLogin(text: String, voiceId: String?): File? {
        try {
            val vid = voiceId ?: WmPrefs.getStr("tts_freelogin_voice", "")
            if (vid.isEmpty()) return null
            LogWriter.log(TAG, "freelogin synthesize voice=" + vid)
            val bytes = DoubaoTtsV2Bridge.synthesize(text, vid, 30)
            val wav = File(mCacheDir, "tts_" + System.currentTimeMillis() + ".wav")
            if (!DoubaoTtsV2Bridge.writeWav(bytes, wav)) {
                LogWriter.log(TAG, "freelogin writeWav fail")
                return null
            }
            LogWriter.log(TAG, "freelogin synthesize OK: " + wav.length() + "b")
            return wav
        } catch (t: Throwable) {
            LogWriter.log(TAG, "freelogin synthesize err: " + t.message)
            return null
        }
    }


    /** 本地网关 TTS 合成（OpenAI /audio/speech 标准）：POST {base}/audio/speech，
     *  body {model:"doubao", input:text, voice:voiceId}，响应为 mp3 音频二进制。 */
    private fun synthesizeGateway(voiceId: String, text: String): File? {
        try {
            val base = WmPrefs.getStr("tts_gateway_base", "")
            val key = WmPrefs.getStr("tts_gateway_key", "")
            if (base.isEmpty() || key.isEmpty()) {
                LogWriter.log(TAG, "gateway synthesize: base/key empty, fallback")
                return null
            }
            val apiUrl = base.trimEnd('/') + "/audio/speech"
            LogWriter.log(TAG, "gateway synthesize: voice=" + voiceId + " url=" + apiUrl)
            val req = JSONObject()
                .put("model", "doubao")
                .put("input", text)
                .put("voice", voiceId)
                .toString()
            val resp = GatewayHttpClient.postJson(apiUrl, key, req)
            if (resp.code != 200) {
                LogWriter.log(TAG, "gateway synthesize: HTTP " + resp.code + " " + resp.bodyString())
                return null
            }
            val bytes = resp.body ?: ByteArray(0)
            if (bytes.size < 100) {
                LogWriter.log(TAG, "gateway synthesize: audio too small " + bytes.size)
                return null
            }
            val mp3 = File(mCacheDir, "tts_" + System.currentTimeMillis() + ".mp3")
            FileOutputStream(mp3).use { it.write(bytes) }
            LogWriter.log(TAG, "gateway synthesize OK: " + mp3.length() + "b")
            return mp3
        } catch (t: Throwable) {
            LogWriter.log(TAG, "gateway synthesize err: " + t.message)
            return null
        }
    }

    private fun readErrorBody(conn: HttpURLConnection): String {
        return try {
            val es = conn.errorStream ?: return ""
            val buf = ByteArray(4096)
            val bos = java.io.ByteArrayOutputStream()
            while (true) {
                val n = es.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
            }
            es.close()
            val s = String(bos.toByteArray(), Charsets.UTF_8)
            if (s.length > 300) s.substring(0, 300) + "..." else s
        } catch (e: Throwable) {
            ""
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
                // 不设硬编码超时，播放完成/异常时 finally 必定 release；
                // 长音频播报不会因 60s 超时提前释放导致 CPU 休眠。
                wl.acquire()
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