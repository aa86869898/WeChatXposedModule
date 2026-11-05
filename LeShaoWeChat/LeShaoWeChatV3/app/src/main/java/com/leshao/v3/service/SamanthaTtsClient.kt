// SamanthaTtsClient.kt —— 豆包登录态 samantha 直连 TTS（Xposed 模块内嵌版，2026-10-17）
//
// 与 DoubaoTtsPool（voicegenie 免登录）互补的另一条路：
//   * 不需要本地网关 / 不需要局域网 / 不需要 Python —— 模块进程内直接连公网 WebSocket
//   * 登录 cookie 获取（按优先级）：
//       1) cookieOverride 直接传 —— **微信等非豆包进程的主路径**（模块设置页/SharedPreferences/硬编码）
//       2) cookieProvider 回调（模块自己从目标进程读取）
//       3) CookieManager.getInstance().getCookie("https://www.doubao.com")
//          —— 仅当模块 hook 豆包 App 时才自动拿到登录态；微信模块拿不到，别依赖这条
//   * 微信模块更推荐免登录方案（DoubaoTtsPool，零配置零维护），登录态只是可选增强：
//     详见 15_微信模块集成指南_20261017.md
//   * 协议比 voicegenie 简单得多：连接 -> 服务器发 open_success ->
//     客户端发 {"event":"text","text":"..."} + {"event":"finish"} -> 二进制 AAC 帧流式回调
//     （voicegenie 需要 StartTask/StartSession/BidirectionalTTS protobuf 帧，这里全不需要）
//
// 已实测事实（2026-10-17）：
//   * 登录态首帧 6-10s（服务端排队主导），但帧连续无句间静默，31 帧约 2.35s 泼完
//   * mp3 格式会被上游登录态拦截，用 aac 或 wav
//   * 服务端对闲置预握手连接有极短超时（闲置十几秒后发 text 被拒回空音频）
//     —— 不要做连接池，请求时即时建连、用完即弃
//
// 依赖：okhttp3 / okio / kotlinx-coroutines-core / org.json（与 DoubaoTtsPool 同款，Android 内置 org.json）
//
// 用法：
//   // 微信模块（推荐先看 15_微信模块集成指南）——cookie 从模块配置读：
//   SamanthaTtsClient.init(context, cookieOverride = prefs.getString("doubao_cookie", ""))
//   // 仅当模块 hook 豆包 App 进程时才可自动取 CookieManager：
//   SamanthaTtsClient.init(context)
//   // 合成（回调在后台线程，碰 UI 自己 post）：
//   SamanthaTtsClient.speak("你好，豆包", "zh_female_wenroutaozi_uranus_bigtts",
//       "aac", 1.0f, 0f, object : SamanthaTtsClient.Callback {
//           override fun onAudio(chunk: ByteArray) { /* 喂播放器/落盘 */ }
//           override fun onComplete() { }
//           override fun onError(msg: String) { }
//       })
package com.leshao.v3.service

import android.content.Context
import android.webkit.CookieManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

object SamanthaTtsClient {

    // ── 端点与请求参数（对齐 Python 网关 gateway_samantha.py） ──
    private const val WS_URL = "wss://ws-samantha.doubao.com/samantha/audio/tts"
    private const val ORIGIN = "https://www.doubao.com"
    private const val AID = 497858
    private const val VERSION_CODE = 20800
    private const val PC_VERSION = "2.46.3"

    // ── 超时（服务端慢时首帧 5-12s，别用 6s 误杀） ──
    private const val FIRST_FRAME_MS = 30_000L   // 等待 open_success / 首帧
    private const val IDLE_END_MS = 20_000L      // 首帧后帧间静默兜底（协议文档 §3.5: 20s）
    private const val CONNECT_MS = 8_000L

    interface Callback {
        fun onAudio(chunk: ByteArray)                 // AAC/音频帧（format 决定编码）
        fun onEvent(name: String, data: JSONObject?) {}
        fun onComplete() {}
        fun onError(msg: String) {}
    }

    // ── 状态 ──
    private lateinit var appCtx: Context
    private var cookieOverride: String? = null
    private var cookieProvider: (() -> String)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(CONNECT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val serial = Mutex()
    @Volatile private var inited = false

    // 固定持久设备标识（模拟真实浏览器，降低风控概率；进程内随机一次）
    private val deviceId = "76" + randomDigits(17)
    private val webId = "76" + randomDigits(17)

    private fun randomDigits(n: Int): String {
        val sb = StringBuilder()
        val r = java.util.Random()
        repeat(n) { sb.append(r.nextInt(10)) }
        return sb.toString()
    }

    fun init(context: Context, cookieOverride: String? = null, cookieProvider: (() -> String)? = null) {
        appCtx = context.applicationContext
        this.cookieOverride = cookieOverride
        this.cookieProvider = cookieProvider
        inited = true
    }

    /** Cookie 热更新：过期/被踢时换新串，无需重建客户端（模块设置页保存后调用） */
    @Synchronized
    fun setCookieOverride(cookie: String) {
        this.cookieOverride = cookie
    }

    /** 取登录 cookie：手动 > 回调 > CookieManager（hook 豆包进程时自动拿 WebView 登录态） */
    private fun resolveCookie(): String {
        cookieOverride?.let { return it }
        cookieProvider?.let { return it() }
        return runCatching {
            CookieManager.getInstance().getCookie("https://www.doubao.com") ?: ""
        }.getOrElse { "" }
    }

    fun speak(text: String,
              voice: String = "zh_female_wenroutaozi_uranus_bigtts",
              format: String = "aac",
              speed: Float = 1.0f,
              pitch: Float = 0f,
              callback: Callback) {
        if (!inited) { callback.onError("SamanthaTtsClient not inited — 先调 init(context)"); return }
        if (text.isBlank()) { callback.onError("text is empty"); return }
        val cookie = resolveCookie()
        if (cookie.isBlank()) {
            callback.onError("doubao cookie 为空：微信等非豆包进程请用 init(context, cookieOverride=...) " +
                "从模块配置传入（见 15_微信模块集成指南）；仅 hook 豆包 App 进程才会自动取 CookieManager")
            return
        }
        scope.launch {
            serial.withLock {
                try {
                    speakOnce(text, voice, format, speed, pitch, cookie, callback)
                } catch (e: Exception) {
                    callback.onError(e.message ?: e.toString())
                }
            }
        }
    }

    private suspend fun speakOnce(text: String, voice: String, format: String,
                                  speed: Float, pitch: Float, cookie: String,
                                  callback: Callback) {
        val speechRate = ((speed - 1.0f).coerceIn(-1f, 1f) * 100).toInt()
        val pitchI = (pitch.coerceIn(-1f, 1f) * 100).toInt()
        val url = buildUrl(voice, format, speechRate, pitchI)
        val request = Request.Builder().url(url).headers(buildHeaders(cookie)).build()

        val channel = Channel<Any>(Channel.UNLIMITED)
        val done = CompletableDeferred<Unit>()
        var ws: WebSocket? = null

        val listener = object : WebSocketListener() {
            override fun onOpen(w: WebSocket, response: Response) {
                // 什么都不做，等服务器 open_success
            }
            override fun onMessage(w: WebSocket, text: String) {
                channel.trySend(text)
            }
            override fun onMessage(w: WebSocket, bytes: ByteString) {
                channel.trySend(bytes)
            }
            override fun onFailure(w: WebSocket, t: Throwable, response: Response?) {
                channel.trySend(Closed("failure: ${t.message}"))
                done.complete(Unit)
            }
            override fun onClosing(w: WebSocket, code: Int, reason: String) {
                w.close(1000, null)
                done.complete(Unit)
            }
            override fun onClosed(w: WebSocket, code: Int, reason: String) {
                done.complete(Unit)
            }
        }
        ws = http.newWebSocket(request, listener)

        // 1) 等待 open_success（握手被拒 = cookie 失效/风控）
        withTimeoutOrNull(FIRST_FRAME_MS) {
            var opened = false
            while (!opened) {
                when (val item = channel.receive()) {
                    is String -> {
                        if (item.contains("open_success")) {
                            opened = true
                        } else {
                            val j = JSONObject(item)
                            if (j.optString("event") == "error") {
                                try { ws?.cancel() } catch (ignored: Throwable) {}
                                throw RuntimeException("握手失败: $item")
                            }
                        }
                    }
                    is Closed -> throw RuntimeException(item.msg)
                }
            }
        } ?: run {
            ws?.cancel()
            throw RuntimeException("等待 open_success 超时（${FIRST_FRAME_MS}ms）——网络或登录态问题")
        }

        // 2) 发送文本 + finish（背靠背，samantha 事件协议）
        ws.send(JSONObject().put("event", "text").put("text", text).toString())
        ws.send(JSONObject().put("event", "finish").toString())

        // 3) 收音频帧，直到静默超时 / 服务器关闭 / error
        var gotFirst = false
        while (true) {
            val item = withTimeoutOrNull(if (!gotFirst) FIRST_FRAME_MS else IDLE_END_MS) {
                channel.receive()
            } ?: break
            when (item) {
                is ByteString -> {
                    gotFirst = true
                    callback.onAudio(item.toByteArray())
                }
                is String -> {
                    val j = JSONObject(item)
                    val ev = j.optString("event")
                    if (ev == "error") {
                        try { ws?.cancel() } catch (ignored: Throwable) {}
                        throw RuntimeException("上游错误: $item")
                    }
                    callback.onEvent(ev, j)
                }
                is Closed -> {
                    // onFailure 投递的 Closed("failure: ...") 表示异常断开：应走 onError，而不是当作正常完成
                    if (item.msg.startsWith("failure")) {
                        try { ws?.cancel() } catch (ignored: Throwable) {}
                        throw RuntimeException("websocket failure: " + item.msg)
                    }
                    done.await()  // 等连接完全关闭
                    break
                }
            }
        }
        ws?.close(1000, "done")
        callback.onComplete()
    }

    private class Closed(val msg: String)

    private fun buildUrl(speaker: String, format: String, speechRate: Int, pitch: Int): String {
        val qs = listOf(
            "speaker" to speaker,
            "format" to format,
            "speech_rate" to speechRate.toString(),
            "pitch" to pitch.toString(),
            "version_code" to VERSION_CODE.toString(),
            "language" to "zh",
            "device_platform" to "web",
            "aid" to AID.toString(),
            "real_aid" to AID.toString(),
            "pkg_type" to "release_version",
            "device_id" to deviceId,
            "pc_version" to PC_VERSION,
            "web_id" to webId,
            "tea_uuid" to webId,
            "region" to "",
            "sys_region" to "",
            "samantha_web" to "1",
            "use-olympus-account" to "1",
            "web_tab_id" to UUID.randomUUID().toString(),
        ).joinToString("&") { (k, v) -> "$k=$v" }
        return "$WS_URL?$qs"
    }

    private fun buildHeaders(cookie: String): okhttp3.Headers =
        okhttp3.Headers.Builder()
            .add("Origin", ORIGIN)
            .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .add("Accept-Language", "zh-CN,zh;q=0.9")
            .add("Cache-Control", "no-cache")
            .add("Pragma", "no-cache")
            .add("sec-ch-ua", "\"Chromium\";v=\"120\", \"Not_A Brand\";v=\"24\", \"Google Chrome\";v=\"120\"")
            .add("sec-ch-ua-mobile", "?1")
            .add("sec-ch-ua-platform", "\"Android\"")
            .add("Sec-Fetch-Dest", "empty")
            .add("Sec-Fetch-Mode", "websocket")
            .add("Sec-Fetch-Site", "same-site")
            .add("Cookie", cookie)
            .build()
}