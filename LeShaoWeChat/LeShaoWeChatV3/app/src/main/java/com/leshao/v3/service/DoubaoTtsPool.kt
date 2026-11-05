// DoubaoTtsPool.kt —— 豆包 voicegenie 免登录 TTS task-ready 连接池（Xposed Kotlin 模块嵌入版, 2026-10-17）
//
// Python 网关 gateway_icl_fast.py v2 的 Kotlin 移植, 逻辑一比一:
//   * 预热只做 建连+StartTask（参数无关）；请求到达时 StartSession(参数)+BidirectionalTTS(文本)
//     背靠背连发（不等 SessionStarted）→ 任何音色/语速/音调首发即热, 零冷开（首帧 ~0.7-1.0s）
//   * 后台主动轮换: 每 45s 把池中超龄连接换新（服务器 task 保鲜期实测≥60s）,
//     "回收重建"全部摊进空闲期, 请求永远吃新鲜连接
//   * 串行队列（Mutex, 一次一个合成任务防风控）; 热连接死亡自动冷建重发一次
//   * 流式回调 onAudio(oggChunk); 首帧等待 30s, 首帧后静默 2.5s 收尾; TTSEnded 提前收尾
//   * 网络切换自动重置池（ConnectivityManager callback）
//
// 依赖（Xposed Kotlin 模块标配）: okhttp3 / okio / kotlinx-coroutines-core / org.json(Android 内置)
//
// 用法:
//   // Application.attach 或模块入口处初始化一次:
//   DoubaoTtsPool.init(context)
//   // 合成（回调在后台线程, 需要碰 UI 自己 post）:
//   DoubaoTtsPool.speak("你好", "ICL_da05661f8c64", 1.0f, 0, object : DoubaoTtsPool.Callback {
//       override fun onAudio(oggChunk: ByteArray) { /* 流式喂播放器 */ }
//       override fun onComplete() { }
//       override fun onError(msg: String) { }
//   })
package com.leshao.v3.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 设备指纹配置（"另一套参数"的载体）。
 * 池按 profile 轮询建连 + 失败 retry 自动迁号, 指纹被 ban 的恢复 = 下一次请求。
 * 换新指纹只需新增一个 DeviceProfile 传给 init(ctx, profiles), 池/协议/重试零改动。
 */
data class DeviceProfile(
    val name: String,
    val appkey: String,
    val egdi: String,
    val did: String,
    val iid: String,
    val cdid: String,
    val origin: String = "https://www.doubao.com",
    val userAgent: String = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
) {
    fun wssUrl(): String =
        "wss://audio.doubao.com/api/v2/sami/voicegenie" +
            "?api_app_key=$appkey&namespace=VoiceGenie&version_code=20800&language=zh" +
            "&device_platform=android&os=android&pkg_type=release_version&pc_version=15.3.0" +
            "&version_name=15.3.0&region=CN&sys_region=CN&samantha_web=1&use-olympus-account=1" +
            "&uid=$did&device_id=$did&iid=$iid&cdid=$cdid&aid=497858" +
            "&klink_egdi=$egdi&channel=debug&app_name=nova_ai"

    fun headers(): Headers = okhttp3.Headers.Builder()
        .add("Origin", origin)
        .add("User-Agent", userAgent)
        .add("Cache-Control", "no-cache")
        .add("Pragma", "no-cache")
        .add("sec-ch-ua", "\"Chromium\";v=\"120\"")
        .add("sec-ch-ua-mobile", "?1")
        .add("sec-ch-ua-platform", "\"Android\"")
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "websocket")
        .add("Sec-Fetch-Site", "same-site")
        .build()

    companion object {
        /** 当前实测通过的一套（LO 10-16 前台抓包真值, 04_reversed_sources 有原始报文） */
        val DEFAULT = DeviceProfile(
            name = "A-online",
            appkey = "F5tdjWzsqEUUQsLJ",
            egdi = "AAIQ2POTBRrgAdXoP8P_RVFHfi6W7GuvFLha86Gxq30xzSf9t1N4CYit",
            did = "445730965969120",
            iid = "445768456238314",
            cdid = "56a1a776-6b22-4b6d-9ae1-b949c46c3b7c",
        )
    }
}

object DoubaoTtsPool {

    // ── 指纹池（多套参数轮询; init(ctx) 不传 = 默认 DEFAULT 单套, 行为与 v2.3-r1 完全一致） ──
    private var profiles: List<DeviceProfile> = listOf(DeviceProfile.DEFAULT)
    private val profileIdx = java.util.concurrent.atomic.AtomicInteger(0)
    private fun nextProfile(): DeviceProfile {
        if (profiles.size == 1) return profiles[0]
        return profiles[Math.floorMod(profileIdx.getAndIncrement(), profiles.size)]
    }
    // 设备指纹已提为 DeviceProfile（文件顶部）——"另一套参数"= 新增一个 DeviceProfile 传给 init(ctx, profiles)
    // ── 参数（与 Python 网关一致） ──
    const val DEFAULT_VOICE = "zh_female_wenroutaozi_uranus_bigtts"
    private const val POOL_SIZE = 3           // task-ready 连接数（与 Python 网关一致）
    private const val ROTATE_MS = 45_000L     // 后台轮换周期（K 版增强: 回收重建摊进空闲期）
    private const val TASK_TTL_MS = 60_000L   // 取出时超龄丢弃（协议文档: task 保鲜 ≥60s）
    private const val FIRST_FRAME_MS = 30_000L // 首帧等待（v1 synthesize timeout=30; 2026-10-17 实测服务端慢时首帧 5-12s, 6s 会误杀）
    private const val IDLE_END_MS = 8_000L    // 首帧后静默判定（v3.0.564: 2500ms 过短，长文本句号停顿/分片间隙>2.5s 导致尾部音频被提前截断）
    private const val TAIL_GRACE_MS = 2_000L // 尾部宽限窗口：静默超时后再等一次，确保最后一块音频不丢
    private const val HANDSHAKE_MS = 6_000L   // 建连+StartTask 总超时（协议文档 §2.8: 6s）
    private const val ACQUIRE_MS = 2_000L     // 池等待上限, 超过即冷开

    interface Callback {
        fun onAudio(oggChunk: ByteArray)
        fun onComplete()
        fun onError(msg: String) {}
    }

    // ── 状态 ──
    private lateinit var appCtx: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)          // TCP 保活
        .connectTimeout(8, TimeUnit.SECONDS)
        .build()
    private val pool = Channel<PooledConn>(POOL_SIZE) // 池（并发安全）
    private val serial = Mutex()                       // 串行队列
    private val refillGate = AtomicBoolean(false)      // 防并发重复补位
    @Volatile private var inited = false

    private class PooledConn(
        val ws: WebSocket,
        val taskId: String,
        val profile: DeviceProfile,
        val born: Long = SystemClock.elapsedRealtime(),
    ) {
        @Volatile var sink: Channel<ByteString>? = null   // 请求占用时挂接收帧通道
    }

    // ── 初始化（模块入口调一次） ──
    @Synchronized
    fun init(ctx: Context, profiles: List<DeviceProfile> = listOf(DeviceProfile.DEFAULT), warmup: Boolean = true) {
        if (inited) return
        require(profiles.isNotEmpty()) { "profiles must not be empty" }
        this.profiles = profiles.toList()
        appCtx = ctx.applicationContext
        inited = true
        // 立即预热 POOL_SIZE 条（Python on_startup 同款, 串行防风控; 多套指纹时轮询建连, 池天然混号）
        // 分身（多开）默认懒加载：不预热、不常驻连接，避免多个分身共享同一指纹产生大量并发连接触发风控
        if (warmup) {
            scope.launch { repeat(POOL_SIZE) { runCatching { refillOne(nextProfile()) } } }
            scope.launch { rotateLoop() }
        }
        registerNetworkWatcher()
    }

    // ── 对外 API ──
    fun speak(
        text: String,
        voice: String = DEFAULT_VOICE,
        speed: Float = 1.0f,
        pitch: Int = 0,
        cb: Callback,
    ) {
        if (!inited) { cb.onError("DoubaoTtsPool.init(context) not called"); return }
        if (text.isBlank()) { cb.onError("empty text"); return }
        scope.launch {
            serial.withLock {                 // 串行: 一次一个合成任务, 防风控
                try { synthesize(text, voice, speed, pitch, cb) }
                catch (t: Throwable) { runCatching { cb.onError(t.message ?: "error") } }
            }
        }
    }

    /** 立即重置池（平时不用调; 网络切换已自动触发）—— 同步 drain, 保证返回时连接已全部关闭 */
    fun resetPool() {
        while (true) {
            val c = pool.tryReceive().getOrNull() ?: break
            closeQuietly(c)
        }
    }

    fun shutdown() {
        resetPool()
        scope.cancel()
    }

    // ── 核心合成: 连发 + 流式收帧 ──
    private suspend fun synthesize(text: String, voice: String, speed: Float, pitch: Int, cb: Callback) {
        var profile = nextProfile()
        for (attempt in 0..1) {
            if (attempt > 0) profile = nextProfile()   // 重试即迁号: 指纹被 ban 时下一套参数自动接管
            val conn = acquire(profile)
            if (conn == null) { cb.onError("handshake failed"); return }
            val ch = Channel<ByteString>(Channel.UNLIMITED)
            conn.sink = ch
            try {
                val prof = conn.profile
                // StartSession(参数) + BidirectionalTTS(文本) 背靠背连发, 服务器按序处理
                val cid = (10_000_000_000_000_000L + Random.nextLong(90_000_000_000_000_000L)).toString()   // Python random_cid 同款 17-18 位数字
                val sessJson = buildSessJson(cid, voice, speed, pitch)
                if (!conn.ws.send(frame("StartSession", prof.appkey, sessJson, conn.taskId, 1).toByteString())) {
                    throw IllegalStateException("send StartSession failed (conn dead)")
                }
                if (!conn.ws.send(frame("BidirectionalTTS", prof.appkey, JSONObject().put("text", text).toString(), conn.taskId, 2).toByteString())) {
                    throw IllegalStateException("send TTS failed (conn dead)")
                }
                var gotAudio = false
                while (true) {
                    // v3.0.564: 静默超时后不立即收尾，额外给尾部一个宽限窗口，
                    // 避免长文本句号停顿/分片间隙导致的最后一块音频被提前截断（“内容不完整”）。
                    var msg = withTimeoutOrNull(if (gotAudio) IDLE_END_MS else FIRST_FRAME_MS) { ch.receive() }
                    if (msg == null && gotAudio) {
                        msg = withTimeoutOrNull(TAIL_GRACE_MS) { ch.receive() }
                    }
                    if (msg == null) {
                        break // 静默超时 + 尾部宽限结束 → 正常收尾
                    }
                    val f = parseFrame(msg.toByteArray())
                    val ev = str(f[4]).ifEmpty { str(f[5]) }   // 与 Python d.get(4) or d.get(5) 一致
                    when (ev) {
                        "TTSResponse" -> (f[8] as? ByteArray)?.let {
                            gotAudio = true
                            cb.onAudio(it)        // 流式回调
                        }
                        "TTSEnded" -> { cb.onComplete(); return }
                        "SessionFailed", "TaskFailed" -> throw RuntimeException("TTS failed: " + str(f[6]))
                    }
                }
                if (gotAudio) { cb.onComplete(); return }
                throw RuntimeException("no audio (server silent)")
            } catch (t: Throwable) {
                try { conn.ws.cancel() } catch (_: Throwable) {}
                if (attempt == 1) { cb.onError(t.message ?: "error"); return }
                // 热连接死亡 → for 循环下一轮冷建重发
            } finally {
                conn.sink = null
                try { conn.ws.close(1000, "done") } catch (_: Throwable) {}   // 用完即弃
                scheduleRefill(conn.profile)
            }
        }
    }

    private fun buildSessJson(cid: String, voice: String, speed: Float, pitch: Int): String = JSONObject()
        .put("business", 1)
        .put("conversation_id", cid)
        .put("request_type", 5)                  // TEXT_TTS
        .put("enable_text_reading", true)
        .put("interrupt_type", 0)
        .put("query_mode", 2)
        .put("chat", JSONObject()
            .put("bot_id", "0")
            .put("conversation_id", cid)
            .put("question_id", "0")
            .put("message_id", "")
            .put("new_conversation", true)
            .put("extra", JSONObject()))
        .put("tts", JSONObject()
            .put("speaker", voice)
            .put("audio_config", JSONObject()
                .put("bit_rate", 32000)
                .put("format", "ogg_opus")
                .put("sample_rate", 24000))
            .put("extra", JSONObject()
                .put("cache_config", "")
                .put("network_level", 7)
                .put("post_process", JSONObject().put("pitch", pitch).put("speech_rate", speed.toDouble()))
                .put("music_ext_for_tts", "")))
        .put("extra", JSONObject())
        .toString()

    // ── 池管理 ──
    private suspend fun acquire(preferred: DeviceProfile): PooledConn? {
        val deadline = SystemClock.elapsedRealtime() + ACQUIRE_MS
        while (true) {
            val remain = deadline - SystemClock.elapsedRealtime()
            if (remain <= 0) break
            val c = withTimeoutOrNull(remain) { pool.receive() } ?: break
            if (SystemClock.elapsedRealtime() - c.born > TASK_TTL_MS) {   // 超龄兜底丢弃
                closeQuietly(c)
                continue
            }
            scheduleRefill(c.profile)
            return c
        }
        return createConn(preferred)   // 池空 → 冷开（后台轮换让这条路变稀有）
    }

    private fun scheduleRefill(profile: DeviceProfile) {
        if (refillGate.compareAndSet(false, true)) {
            scope.launch {
                try { refillOne(profile) } finally { refillGate.set(false) }
            }
        }
    }

    private suspend fun refillOne(profile: DeviceProfile) {
        val c = createConn(profile) ?: return
        val r = pool.trySend(c)
        if (r.isFailure) closeQuietly(c)
    }

    /** 后台主动轮换: 每 ROTATE_MS 补满 POOL_SIZE 条（不抽空池）; 超龄淘汰交给 acquire 兜底 + 自然补位 */
    private suspend fun rotateLoop() {
        while (true) {
            delay(ROTATE_MS)
            runCatching {
                while (true) {
                    val c = createConn(nextProfile()) ?: break
                    if (pool.trySend(c).isFailure) { closeQuietly(c); break }   // 满了就停, 不硬挤
                }
            }
        }
    }

    // ── 建连 + StartTask（参数无关, 停在 task-ready） ──
    private suspend fun createConn(profile: DeviceProfile): PooledConn? {
        return try { withTimeoutOrNull(HANDSHAKE_MS) { handshake(profile) } } catch (_: Throwable) { null }
    }

    private suspend fun handshake(profile: DeviceProfile): PooledConn = suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)
        val listener = ConnListener().apply {
            pendingProfile = profile
            // okhttp 回调线程与超时取消存在竞态: 对已取消的 continuation resume 会抛 ISE 且无人捕获 → 崩宿主, 必须 runCatching
            onTaskStarted = { c -> if (done.compareAndSet(false, true)) runCatching { cont.resume(c) } }
            onHandshakeFail = { t -> if (done.compareAndSet(false, true)) runCatching { cont.resumeWithException(t) } }
        }
        val ws = http.newWebSocket(buildRequest(profile), listener)
        cont.invokeOnCancellation { runCatching { ws.cancel() } }
    }

    /** 连接级 listener: 握手期(conn==null)处理 TaskStarted, 服务期(conn!=null)转发给当前请求的 sink */
    private class ConnListener : WebSocketListener() {
        @Volatile var conn: PooledConn? = null
        @Volatile var pendingProfile: DeviceProfile = DeviceProfile.DEFAULT
        var onTaskStarted: ((PooledConn) -> Unit)? = null
        var onHandshakeFail: ((Throwable) -> Unit)? = null

        override fun onOpen(w: WebSocket, r: Response) {
            w.send(frame("StartTask", pendingProfile.appkey, "{}", null, 0).toByteString())
        }
        override fun onMessage(w: WebSocket, bytes: ByteString) {
            val c = conn
            if (c == null) {
                val f = parseFrame(bytes.toByteArray())
                val ev = str(f[4]).ifEmpty { str(f[5]) }
                when (ev) {
                    "TaskStarted" -> {
                        val nc = PooledConn(w, str(f[1]).ifEmpty { str(f[8]) }, pendingProfile)   // task_id 在 field1, field8 兜底（Python 同款）
                        conn = nc
                        onTaskStarted?.invoke(nc)
                    }
                    "TaskFailed", "SessionFailed" ->
                        onHandshakeFail?.invoke(RuntimeException(str(f[6])))
                }
            } else {
                c.sink?.trySend(bytes)   // 服务中: 转发给当前请求的收帧循环
            }
        }
        override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
            if (conn == null) onHandshakeFail?.invoke(t)
        }
    }

    private fun buildRequest(profile: DeviceProfile): Request =
        Request.Builder().url(profile.wssUrl()).headers(profile.headers()).build()

    // ── 网络切换监听: 断网瞬间连接全死, 池就地重建 ──
    private fun registerNetworkWatcher() {
        runCatching {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) { resetPool() }
            })
        }
    }

    // ── protobuf 最小实现（field2=appkey str, 3=namespace str, 5=event str, 6=payload str, 8=task_id/audio str, 9=seq varint） ──
    private fun frame(event: String?, appkey: String, payload: String?, taskId: String?, seqId: Int?): ByteArray {
        val o = ByteArrayOutputStream(256)
        writeStr(o, 2, appkey)
        writeStr(o, 3, "VoiceGenie")
        if (event != null) writeStr(o, 5, event)
        if (payload != null) writeStr(o, 6, payload)
        if (!taskId.isNullOrEmpty()) writeStr(o, 8, taskId)
        if (seqId != null) writeUint(o, 9, seqId.toLong())
        return o.toByteArray()
    }

    private fun writeStr(o: ByteArrayOutputStream, field: Int, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeTag(o, field, 2)
        writeVarint(o, b.size.toLong())
        o.write(b, 0, b.size)
    }

    private fun writeUint(o: ByteArrayOutputStream, field: Int, v: Long) {
        writeTag(o, field, 0)
        writeVarint(o, v)
    }

    private fun writeTag(o: ByteArrayOutputStream, field: Int, wt: Int) = writeVarint(o, (field shl 3 or wt).toLong())

    private fun writeVarint(o: ByteArrayOutputStream, v0: Long) {
        var v = v0
        while (true) {
            if (v and 0x7FL.inv() == 0L) { o.write(v.toInt()); return }
            o.write((v and 0x7F or 0x80L).toInt())
            v = v ushr 7
        }
    }

    private fun parseFrame(buf: ByteArray): Map<Int, Any?> {
        val out = HashMap<Int, Any?>(8)
        var i = 0
        while (i < buf.size) {
            val (tag, n) = readVarint(buf, i); i += n
            val f = (tag ushr 3).toInt()
            val wt = (tag and 7L).toInt()
            when (wt) {
                0 -> { val (v, m) = readVarint(buf, i); i += m; out[f] = v }
                2 -> {
                    val (len, m) = readVarint(buf, i); i += m
                    val l = len.toInt()
                    if (l < 0 || i + l > buf.size) return out
                    out[f] = buf.copyOfRange(i, i + l)
                    i += l
                }
                else -> return out
            }
        }
        return out
    }

    private fun readVarint(b: ByteArray, off: Int): Pair<Long, Int> {
        var v = 0L
        var shift = 0
        var i = off
        while (i < b.size) {
            val x = b[i].toInt() and 0xFF
            i++
            v = v or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) break
            shift += 7
            if (shift > 63) break
        }
        return v to (i - off)
    }

    private fun str(o: Any?): String = when (o) {
        is ByteArray -> String(o, Charsets.UTF_8)
        null -> ""
        else -> o.toString()
    }

    private fun closeQuietly(c: PooledConn) {
        runCatching { c.ws.close(1000, "pool") }
    }
}
