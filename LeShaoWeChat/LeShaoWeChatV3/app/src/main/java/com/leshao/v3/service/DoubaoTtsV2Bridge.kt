package com.leshao.v3.service

import android.content.Context
import com.leshao.v3.LogWriter
import com.leshao.v3.wm.utils.WmPrefs
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 傻包TTS V2 引擎同步桥接（v2.4-r4 双车道，纯进程内直连，无局域网/网关依赖）。
 * 主车道 = samantha 登录态（AAC，SamanthaTtsClient 直连，Cookie 可 WebView 登录自动获取）；
 * 兜底车道 = voicegenie 免登录（OGG，DoubaoTtsPool 进程内连接池）。
 * synthesize 返回原始音频字节（AAC 或 OGG），调用点用 writeWav 统一解码。
 */
object DoubaoTtsV2Bridge {

    private const val TAG = "DoubaoTtsV2"
    private const val KEY_SAMANTHA_COOKIE = "tts_samantha_cookie"
    private const val KEY_LANE_MODE = "tts_lane_mode"   // "samantha"=登录态主车道 / "voicegenie"=免登录

    /** 默认登录 Cookie（来自 v2.4-r4 参考包 08_icl_multivoice/cookie.txt 注入的完整 doubao 网页登录态，
     *  36 个字段，含 sessionid/sid_guard/uid_tt/ttwid/odin_tt 等，实测有效；
     *  可在设置中修改/清除，也可通过内置网页登录自动获取）。 */
    const val DEFAULT_SAMANTHA_COOKIE =
        "hook_slardar_session_id=2026101908573070555411F2F38594A225; i18next=zh;" +
        " dbx-web-theme=light; flow_user_country=CN; s_v_web_id=verify_mv56f3fs_" +
        "aYiMrASe_cFZE_4cMW_9yrL_rRTzy9rbzQ2j; passport_csrf_token=5ab12ae6187d0" +
        "240474c560301d85fb2; passport_csrf_token_default=5ab12ae6187d0240474c56" +
        "0301d85fb2; x-tt-multi-sids=VF1ZThVHVlUfW1dGXxhWRlxDNQtJWgdAA1tcQRsWUQc" +
        "cXVRGUhxWFBtARVdPXFcdXghW; flow_cur_user_sec_id=Kz9bAGEdJCBsLCAyI2YpBSF" +
        "GLhtcMy8+VxslIRhPVSpCOVNBIxwMBgxHTBdbLykBARoEBx4tLxteIFcCIF4hETIeGCp7WQ" +
        "4RBF87DQ==; flow_multi_user_sec_info=Kz9bAGEdJCBsLCAyI2YpBSFGLhtcMy8+Vx" +
        "slIRhPVSpCOVNBIxwMBgxHTBdbLykBARoEBx4tLxteIFcCIF4hETIeGCp7WQ4RBF87DRdNV" +
        "FUUXFlDXh5RREhDTA==; conversation_list_v2_group_mode=project; d_ticket=" +
        "6cfcff71947021e0a21624d5cd8e28d04062a; multi_sids=2169803726654554%3Add" +
        "1b9e7366a4e1055915fb018b72d8d9; n_mh=p6Lh8TiVSilbUm7kWZldBjBC2I5cb991ZN" +
        "Df8NGiBCk; passport_auth_status=1c4966b111939c4dee956f52da17d590%2C; pa" +
        "ssport_auth_status_ss=1c4966b111939c4dee956f52da17d590%2C; uid_tt=ce6de" +
        "5866765816505b5473dc96090b3; uid_tt_ss=ce6de5866765816505b5473dc96090b3" +
        "; sid_tt=dd1b9e7366a4e1055915fb018b72d8d9; sessionid=dd1b9e7366a4e10559" +
        "15fb018b72d8d9; sessionid_ss=dd1b9e7366a4e1055915fb018b72d8d9; is_staff" +
        "_user=false; has_biz_token=false; bd_sso_hi3jfd=cGXhPxGxjn0CZFU6EP3P%2B" +
        "gjFSmLufS4Q0iIfXA%3D%3D; biz_trace_id=27395eb1; sid_guard=dd1b9e7366a4e" +
        "1055915fb018b72d8d9%7C1791805327%7C2592000%7CWed%2C%2011-Nov-2026%2011%" +
        "3A42%3A07%20GMT"

    @Volatile
    private var inited = false
    private var appCtx: Context? = null

    /** 初始化（幂等）：内部会预热 voicegenie 进程内兜底连接池。任何 context 均可。 */
    @JvmStatic
    @Synchronized
    fun ensureInit(ctx: Context?) {
        val c = ctx ?: return
        if (inited) return
        inited = true
        appCtx = c.applicationContext
        try {
            val okCl = okhttp3.OkHttpClient::class.java.classLoader
            LogWriter.log(TAG, "ensureInit okhttpCl=" + okCl)
        } catch (t: Throwable) {
            LogWriter.log(TAG, "ensureInit okhttp load FAIL: " + t)
        }
        DoubaoTtsPool.init(c.applicationContext, warmup = true)
        LogWriter.log(TAG, "ensureInit done (voicegenie pool prewarm started, warmup=true)")
    }

    /**
     * 同步合成一段文本，返回原始音频字节（samantha=AAC / voicegenie=OGG）。
     * 阻塞，调用方需放子线程。车道顺序（与 v2.4-r1 文档一致）：
     *   samantha 主车道（Cookie 进程内直连）→ voicegenie 兜底车道（进程内连接池）。
     */
    @JvmStatic
    @Throws(Exception::class)
    fun synthesize(text: String, voice: String?, timeoutSec: Int): ByteArray {
        val v = if (voice.isNullOrEmpty()) DoubaoTtsPool.DEFAULT_VOICE else voice
        val cookie = resolveCookie()
        // 车道模式：samantha=登录态主车道（默认）/ voicegenie=免登录直连
        val lane = WmPrefs.getStr(KEY_LANE_MODE, "samantha").trim()
            .ifEmpty { "samantha" }
        LogWriter.log(TAG, "synthesize lane=" + lane + " cookieSet=" + (cookie.isNotEmpty()) + " cookieLen=" + cookie.length
                + " source=" + (if (cookie == DEFAULT_SAMANTHA_COOKIE) "default" else "user"))

        // ── 主车道：samantha 登录态优先，voicegenie 仅在 samantha 失败/超时时兜底 ──
        // v3.0.564: 之前是“并发竞速谁快用谁”，voicegenie 总是 5s 胜出导致用户选的傻包
        // (samantha) 从未真正生效，且 voicegenie 流式尾部被提前截断，表现为“最后面内容不完整”。
        if (lane != "voicegenie" && cookie.isNotEmpty()) {
            return samanthaFirstWithVoicegenieFallback(text, v, cookie, timeoutSec, voice)
        }
        LogWriter.log(TAG, "lane=voicegenie: skip samantha main lane")

        // ── 仅免登录车道：voicegenie 直连（OGG） ──
        return synthesizeVoicegenie(text, voice, timeoutSec)
    }

    private val mergeErrs = java.util.function.BinaryOperator<String?> { a, b ->
        if (a.isNullOrEmpty()) b else a + "; " + b
    }

    /** samantha（傻包登录态）优先，失败/超时才回退 voicegenie。 */
    private fun samanthaFirstWithVoicegenieFallback(
            text: String, voice: String, cookie: String, timeoutSec: Int, voiceOrNull: String?
    ): ByteArray {
        val t0 = System.currentTimeMillis()
        // 1) 先尝试 samantha 主车道（登录态，完整 AAC）
        try {
            val b = synthesizeAac(text, voice, cookie, timeoutSec)
            LogWriter.log(TAG, "samantha OK elapsedMs=" + (System.currentTimeMillis() - t0)
                    + " bytes=" + b.size)
            return b
        } catch (t: Throwable) {
            LogWriter.log(TAG, "samantha FAILED, fallback voicegenie: " + t.message)
        }
        // 2) samantha 失败/超时 → voicegenie 兜底
        return synthesizeVoicegenie(text, voiceOrNull, timeoutSec)
    }

    /** 判断 Cookie 是否完整（含 sessionid + sid_guard + 字段数足够）。 */
    @JvmStatic
    fun isCompleteCookie(cookie: String): Boolean {
        if (cookie.isEmpty()) return false
        return cookie.contains("sessionid=") && cookie.contains("sessionid_ss=")
            && cookie.contains("sid_tt=") && cookie.contains("uid_tt=")
            && cookie.contains("sid_guard=")
    }

    /** 取完整 Cookie：prefs 中不完整/为空时回退到内置完整默认值（仅主微信回退，多开分身穿隔离）。 */
    @JvmStatic
    fun resolveCookie(): String {
        val saved = WmPrefs.getStr(KEY_SAMANTHA_COOKIE, "").trim()
        if (isCompleteCookie(saved)) return saved
        // 主/分身行为一致：未手动配置时统一回退内置默认 Cookie
        return DEFAULT_SAMANTHA_COOKIE
    }

    /**
     * 豆包 completion（对话/图片识别）专用 Cookie。
     * 网页登录（WebView）拿到的 Cookie 绑定 WebView 环境 UA/设备指纹，completion 端点校验会拒绝；
     * 因此 completion 默认使用内置完整浏览器 Cookie（来自浏览器 cookie.txt，未过期时有效）。
     * 若用户手动粘贴了 PC 浏览器完整 Cookie，则优先使用该值（key: doubao_chat_cookie）。
     */
    @JvmStatic
    fun resolveChatCookie(): String {
        val manual = WmPrefs.getStr("doubao_chat_cookie", "").trim()
        if (isCompleteCookie(manual)) return manual
        // 主/分身行为一致：未手动配置时统一回退内置默认 Cookie，保证多开功能完全一致。
        return DEFAULT_SAMANTHA_COOKIE
    }

    /**
     * 写 WAV 文件：输入为 WAV（RIFF）直接写；否则交给 AudioCodecUtil 通用解码
     * （MediaExtractor 自动识别 OGG/Opus 与 AAC/ADTS）。
     */
    @JvmStatic
    fun writeWav(audioBytes: ByteArray, wavOut: File): Boolean {
        return try {
            if (audioBytes.size > 4 &&
                audioBytes[0] == 'R'.code.toByte() &&
                audioBytes[1] == 'I'.code.toByte() &&
                audioBytes[2] == 'F'.code.toByte() &&
                audioBytes[3] == 'F'.code.toByte()
            ) {
                wavOut.parentFile?.mkdirs()
                wavOut.writeBytes(audioBytes)
                true
            } else {
                AudioCodecUtil.oggToWav(audioBytes, wavOut)
            }
        } catch (t: Throwable) {
            LogWriter.log(TAG, "writeWav err: " + t.message)
            false
        }
    }

    // ── samantha 登录态直连（压缩包最新：DoubaoSamanthaPool 薄封装，正文零改动替换，延迟 2s 内） ──

    private fun synthesizeAac(text: String, voice: String, cookie: String, timeoutSec: Int): ByteArray {
        val ctx = appCtx ?: throw RuntimeException("DoubaoTtsV2Bridge not initialized")
        // 压缩包最新方案：DoubaoSamanthaPool.init(ctx, cookie) + 热更新，正文接口零改动
        DoubaoSamanthaPool.format = "aac"
        DoubaoSamanthaPool.init(ctx, cookie)
        DoubaoSamanthaPool.updateCookie(cookie)
        val latch = CountDownLatch(1)
        val audio = ByteArrayOutputStream()
        val errRef = AtomicReference<String?>()
        val t0 = System.currentTimeMillis()
        DoubaoSamanthaPool.speak(text, voice, 1.0f, 0, object : DoubaoSamanthaPool.Callback {
            override fun onAudio(chunk: ByteArray) {
                synchronized(audio) {
                    audio.write(chunk, 0, chunk.size)
                }
            }

            override fun onComplete() {
                LogWriter.log(TAG, "samantha onComplete totalAudio=" + audio.size()
                        + " elapsedMs=" + (System.currentTimeMillis() - t0))
                latch.countDown()
            }

            override fun onError(msg: String) {
                LogWriter.log(TAG, "samantha onError msg=" + msg)
                errRef.set(msg)
                latch.countDown()
            }
        })
        val finished = latch.await(timeoutSec.toLong(), TimeUnit.SECONDS)
        val err = errRef.get()
        if (err != null) {
            LogWriter.log(TAG, "samantha FAILED: " + err)
            throw RuntimeException("SamanthaTts failed: " + err)
        }
        if (!finished || audio.size() == 0) {
            LogWriter.log(TAG, "samantha timeout/no audio after " + timeoutSec + "s")
            throw RuntimeException("SamanthaTts timeout (" + timeoutSec + "s)")
        }
        return audio.toByteArray()
    }

    // ── voicegenie 进程内免登录兜底车道（DoubaoTtsPool 流式 OGG 收集） ──

    private fun synthesizeVoicegenie(text: String, voice: String?, timeoutSec: Int): ByteArray {
        val v = if (voice.isNullOrEmpty()) DoubaoTtsPool.DEFAULT_VOICE else voice
        LogWriter.log(TAG, "voicegenie inproc fallback start textLen=" + (text?.length ?: 0)
                + " voice=" + v + " timeout=" + timeoutSec)
        val latch = CountDownLatch(1)
        val audio = ByteArrayOutputStream()
        val errRef = AtomicReference<String?>()
        val doneRef = AtomicReference(false)
        DoubaoTtsPool.speak(text, v, 1.0f, 0, object : DoubaoTtsPool.Callback {
            override fun onAudio(oggChunk: ByteArray) {
                synchronized(audio) {
                    audio.write(oggChunk, 0, oggChunk.size)
                }
            }

            override fun onComplete() {
                doneRef.set(true)
                LogWriter.log(TAG, "voicegenie inproc onComplete totalAudio=" + audio.size())
                latch.countDown()
            }

            override fun onError(msg: String) {
                errRef.set(msg)
                LogWriter.log(TAG, "voicegenie inproc onError msg=" + msg)
                latch.countDown()
            }
        })
        // DoubaoTtsPool 内部自带 2 次 attempt（每次首帧预算 30s），给足总预算避免误判超时
        val waitMs = (timeoutSec + 35L) * 1000L
        val finished = awaitWithDeadline(latch, waitMs)
        val err = errRef.get()
        if (err != null) {
            LogWriter.log(TAG, "voicegenie inproc FAILED: " + err)
            throw RuntimeException("DoubaoTtsV2 failed: " + err)
        }
        if (!finished) {
            if (doneRef.get() || audio.size() > 0) {
                if (audio.size() > 0) {
                    LogWriter.log(TAG, "voicegenie inproc OK (late) bytes=" + audio.size())
                    return audio.toByteArray()
                }
                LogWriter.log(TAG, "voicegenie inproc FAILED: no audio received")
                throw RuntimeException("DoubaoTtsV2 no audio received")
            }
            LogWriter.log(TAG, "voicegenie inproc TIMEOUT after " + (timeoutSec + 35L) + "s")
            throw RuntimeException("DoubaoTtsV2 timeout (" + timeoutSec + "s)")
        }
        if (audio.size() == 0) {
            LogWriter.log(TAG, "voicegenie inproc FAILED: no audio received (completed)")
            throw RuntimeException("DoubaoTtsV2 no audio received")
        }
        LogWriter.log(TAG, "voicegenie inproc OK bytes=" + audio.size())
        return audio.toByteArray()
    }

    /** 带超时的 latch 等待：返回是否在超时前 countDown。 */
    private fun awaitWithDeadline(latch: CountDownLatch, waitMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline && latch.count > 0) {
            try {
                if (latch.await(200L, TimeUnit.MILLISECONDS)) return true
            } catch (ignored: InterruptedException) {
                return false
            }
        }
        return latch.count == 0L
    }
}