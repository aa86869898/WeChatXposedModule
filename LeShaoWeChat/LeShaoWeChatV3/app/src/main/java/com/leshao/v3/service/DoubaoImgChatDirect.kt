package com.leshao.v3.service

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.zip.CRC32

/**
 * 豆包图片识别【直连版】（无网关，微信进程内直接联网）
 *
 * 全链路：prepare_upload(STS) -> SigV4 ApplyImageUpload -> TOS 直传 -> CommitImageUpload -> completion(SSE)
 *
 * 用法：
 *   DoubaoImgChatDirect.cookie = "sessionid=..."            // 模块启动时注入豆包网页 cookie
 *   DoubaoImgChatDirect.recognize(imageBytes, "图片里写了什么？") { answer ->
 *       // answer 为识别文字，直接经你的模块发消息能力回传群聊/联系人
 *   }
 *
 * 依赖：okhttp 4.x + org.json（Android 内置），无其他依赖
 * 注意：带图对话首包 6-9s，整体 15-30s，readTimeout 已设 120s
 */
object DoubaoImgChatDirect {

    /** 豆包网页 cookie（与网页登录态一致，失效需更新） */
    @Volatile
    var cookie: String = ""

    /** 调试开关：打印实际请求 URL/headers/body */
    @Volatile
    var debug: Boolean = false

    private const val BASE = "https://www.doubao.com"
    private const val BOT_ID = "7338286299411103781"
    private const val COLLECT_ID = "05c4824b-c12c-49a0-888a-e21c9a077a4f"
    private const val IMAGEX_REGION = "cn-north-1"
    private const val IMAGEX_SERVICE = "imagex"
    private const val UA_DEFAULT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    @Volatile
    var ua: String = UA_DEFAULT

    private const val COMMON_QUERY =
        "aid=497858&device_id=7695743740019656242&device_platform=web&doubao_device_platform=web" +
        "&doubao_pc_version=3.41.4&fp=verify_mv56f3fs_aYiMrASe_cFZE_4cMW_9yrL_rRTzy9rbzQ2j" +
        "&language=zh&pc_version=3.41.4&pkg_type=release_version&real_aid=497858&region=CN" +
        "&samantha_web=1&sys_region=CN&tea_uuid=7695743752698234368&tz_name=Asia/Shanghai" +
        "&use-olympus-account=1&version_code=20800&web_id=7695743752698234368&web_platform=browser" +
        "&web_tab_id=cdfc275f-dc76-4a1c-afec-ee3e3a1f29b6"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val OCTET_MEDIA = "application/octet-stream".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ---------------- 公共入口 ----------------

    /** 异步识别：成功回调文字，失败回调 null */
    fun recognize(imageBytes: ByteArray, question: String, callback: (String?) -> Unit) {
        Thread {
            var answer: String? = null
            try {
                answer = recognizeBlocking(imageBytes, question)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            callback(answer)
        }.start()
    }

    /** 同步识别（阻塞，勿在主线程调用） */
    fun recognizeBlocking(imageBytes: ByteArray, question: String): String {
        val ext = detectExt(imageBytes)
        val sts = getSts()
        val addr = applyImageUpload(sts, imageBytes.size, ext)
        val up = uploadFile(addr, imageBytes)
        commitImageUpload(sts, up)
        return completion(up.uri, question)
    }

    // ---------------- 1. prepare_upload：拿 STS 临时凭证 ----------------

    private fun getSts(): JSONObject {
        val body = JSONObject()
            .put("tenant_id", "5")
            .put("scene_id", "5")
            .put("resource_type", 2)
            .toString()
        val req = Request.Builder()
            .url("$BASE/alice/resource/prepare_upload?$COMMON_QUERY")
            .post(body.toRequestBody(JSON_MEDIA))
            .header("Cookie", cookie)
            .header("Origin", BASE)
            .header("Referer", "$BASE/chat/")
            .header("User-Agent", ua)
            .header("Agw-Js-Conv", "str")
            .header("x-flow-trace", UUID.randomUUID().toString())
            .build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string() ?: ""
            val j = JSONObject(txt)
            check(j.optInt("code", -1) == 0) { "prepare_upload 失败 code=${resp.code}: $txt" }
            return j.getJSONObject("data")
        }
    }

    // ---------------- 2. ApplyImageUpload：SigV4 签名申请上传地址 ----------------

    private fun applyImageUpload(sts: JSONObject, fileSize: Int, ext: String): JSONObject {
        val at = sts.getJSONObject("upload_auth_token")
        val ak = at.getString("access_key")
        val sk = at.getString("secret_key")
        val st = at.getString("session_token")
        val serviceId = sts.getString("service_id")
        val params = sortedMapOf(
            "Action" to "ApplyImageUpload",
            "Version" to "2018-08-01",
            "ServiceId" to serviceId,
            "NeedFallback" to "true",
            "FileSize" to fileSize.toString(),
            "FileExtension" to ext,
            "s" to randomStr(10)
        )
        val (auth, amzDate) = sign("GET", "$BASE/top/v1", params, ak, sk, st)
        val url = "$BASE/top/v1?" + params.entries.joinToString("&") { "${rfc3986(it.key)}=${rfc3986(it.value)}" }
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", auth)
            .header("X-Amz-Date", amzDate)
            .header("x-amz-security-token", st)
            .header("User-Agent", ua)
            .header("Referer", "$BASE/chat/")
            .build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string() ?: ""
            val j = JSONObject(txt)
            check(resp.code == 200 && j.has("Result")) { "ApplyImageUpload 失败: $txt" }
            return j.getJSONObject("Result").getJSONObject("UploadAddress")
        }
    }

    // ---------------- 3. TOS 直传 ----------------

    private data class UploadResult(val uri: String, val auth: String, val uploadId: String, val host: String)

    private fun uploadFile(uploadAddr: JSONObject, fileBytes: ByteArray): UploadResult {
        val store = uploadAddr.getJSONArray("StoreInfos").getJSONObject(0)
        val storeUri = store.getString("StoreUri")
        val storeAuth = store.getString("Auth")
        val uploadId = store.getString("UploadID")
        val hostsArr = uploadAddr.getJSONArray("UploadHosts")
        val first = hostsArr.get(0)
        val tosDomain = if (first is JSONObject) first.getString("UploadHost") else first.toString()
        val crc = CRC32().apply { update(fileBytes) }
        val crcHex = String.format(Locale.US, "%08x", crc.value)
        val url = "https://$tosDomain/upload/v1/$storeUri"
        val req = Request.Builder()
            .url(url)
            .post(fileBytes.toRequestBody(OCTET_MEDIA))
            .header("Authorization", storeAuth)
            .header("Content-CRC32", crcHex)
            .header("X-Storage-U", "")
            .header("Referer", "$BASE/chat/")
            .header("Origin", BASE)
            .header("User-Agent", ua)
            .build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string() ?: ""
            check(resp.code == 200) { "TOS 直传失败 ${resp.code}: $txt" }
        }
        return UploadResult(storeUri, storeAuth, uploadId, tosDomain)
    }

    // ---------------- 4. CommitImageUpload：SigV4（body 参与签名） ----------------

    private fun commitImageUpload(sts: JSONObject, up: UploadResult) {
        val at = sts.getJSONObject("upload_auth_token")
        val ak = at.getString("access_key")
        val sk = at.getString("secret_key")
        val st = at.getString("session_token")
        val serviceId = sts.getString("service_id")

        val storeInfos = JSONArray().put(
            JSONObject()
                .put("StoreUri", up.uri)
                .put("Auth", up.auth)
                .put("UploadID", up.uploadId)
                .put("UploadHeader", JSONObject.NULL)
                .put("StorageHeader", JSONObject.NULL)
        ).toString()

        val sessionKey = JSONObject()
            .put("accountType", "ImageX")
            .put("appId", "")
            .put("bizType", "")
            .put("fileType", "image")
            .put("legal", "")
            .put("storeInfos", storeInfos)
            .put("uploadHost", up.host)
            .put("uri", up.uri)
            .put("userId", "")
        val sessionKeyB64 = b64encode(sessionKey.toString().toByteArray(Charsets.UTF_8))

        val params = sortedMapOf(
            "Action" to "CommitImageUpload",
            "Version" to "2018-08-01",
            "ServiceId" to serviceId
        )
        // 关键：body 必须模拟 Python json.dumps 的默认格式（": " 冒号后带空格）——
        // 服务端转发链路会规范化 body 再校验签名，JSONObject.toString() 的紧凑格式会被网关重写导致 SignatureDoesNotMatch
        val body = "{\"SessionKey\": \"$sessionKeyB64\"}"
        // 注意：Content-Type 必须与实际发送头完全一致（OkHttp 会把 MediaType 原样发送），
        // 这里用裸 "application/json"（无 charset），与签名一致
        val ct = "application/json"
        val (auth, amzDate) = sign(
            "POST", "$BASE/top/v1", params, ak, sk, st,
            body = body.toByteArray(Charsets.UTF_8),
            extraHeaders = mapOf("Content-Type" to ct)
        )
        val url = "$BASE/top/v1?" + params.entries.joinToString("&") { "${rfc3986(it.key)}=${rfc3986(it.value)}" }
        val req = Request.Builder()
            .url(url)
            // ⚠️必须 ByteArray 版：String.toRequestBody 会自动给无 charset 的 MediaType 追加 "; charset=utf-8"，
            // 导致 Content-Type 与签名时的 canonical 不一致 → SignatureDoesNotMatch
            .post(body.toByteArray(Charsets.UTF_8).toRequestBody(ct.toMediaType()))
            .header("Authorization", auth)
            .header("X-Amz-Date", amzDate)
            .header("x-amz-security-token", st)
            .header("User-Agent", ua)
            .header("Referer", "$BASE/chat/")
            .build()
        if (debug) {
            System.err.println("=== KOTLIN COMMIT URL ===")
            System.err.println(url)
            System.err.println("=== KOTLIN COMMIT HEADERS ===")
            req.headers.forEach { (n, v) -> System.err.println("$n: $v") }
            System.err.println("=== KOTLIN COMMIT BODY ===")
            System.err.println(body)
            System.err.println("=== KOTLIN COMMIT BODY SHA256 ===")
            System.err.println(sha256Hex(body.toByteArray(Charsets.UTF_8)))
        }
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string() ?: ""
            if (debug) System.err.println("=== KOTLIN COMMIT RESP ===\n$txt")
            val j = JSONObject(txt)
            val uriStatus = j.optJSONObject("Result")?.optJSONArray("Results")?.optJSONObject(0)?.optInt("UriStatus", -1)
            check(resp.code == 200 && uriStatus == 2000) { "CommitImageUpload 失败 ${resp.code}: $txt" }
        }
    }

    // ---------------- 5. completion：带图对话 + SSE 解析 ----------------

    private fun buildCompletionBody(imageUri: String, question: String): JSONObject {
        val nowMs = System.currentTimeMillis()
        val name = imageUri.substringAfterLast('/')
        val messages = JSONArray().put(
            JSONObject()
                .put("local_message_id", UUID.randomUUID().toString())
                .put(
                    "content_block",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("block_type", 10052)
                                .put(
                                    "content",
                                    JSONObject()
                                        .put(
                                            "attachment_block",
                                            JSONObject().put(
                                                "attachments",
                                                JSONArray().put(
                                                    JSONObject()
                                                        .put("type", 1)
                                                        .put("identifier", UUID.randomUUID().toString())
                                                        .put(
                                                            "image",
                                                            JSONObject()
                                                                .put("name", name)
                                                                .put("uri", imageUri)
                                                                .put(
                                                                    "image_ori",
                                                                    JSONObject()
                                                                        .put("url", "")
                                                                        .put("width", 0)
                                                                        .put("height", 0)
                                                                        .put("format", "png")
                                                                        .put("url_formats", JSONObject())
                                                                )
                                                        )
                                                        .put("parse_state", 0)
                                                        .put("review_state", 1)
                                                        .put("upload_status", 1)
                                                        .put("progress", 100)
                                                        .put("src", "")
                                                )
                                            )
                                        )
                                        .put("pc_event_block", "")
                                )
                                .put("block_id", UUID.randomUUID().toString())
                                .put("parent_id", "")
                                .put("meta_info", JSONArray())
                                .put("extra_info", JSONObject())
                                .put("append_fields", JSONArray())
                        )
                        .put(
                            JSONObject()
                                .put("block_type", 1)
                                .put("content", JSONObject().put("text_block", JSONObject().put("text", question)))
                                .put("block_id", UUID.randomUUID().toString())
                                .put("parent_id", "")
                                .put("meta_info", JSONArray())
                                .put("extra_info", JSONObject())
                                .put("append_fields", JSONArray())
                        )
                )
                .put("message_status", 0)
        )

        val option = JSONObject()
            .put("send_message_scene", "")
            .put("create_time_ms", nowMs)
            .put("collect_id", COLLECT_ID)
            .put("is_audio", false)
            .put("answer_with_suggest", false)
            .put("agent_mode", 2)
            .put("tts_switch", false)
            .put("need_deep_think", 0)
            .put("click_clear_context", false)
            .put("from_suggest", false)
            .put("is_regen", false)
            .put("is_replace", false)
            .put("is_from_click_option", false)
            .put("is_from_click_softlink", false)
            .put("disable_sse_cache", false)
            .put("select_text_action", "")
            .put("is_select_text", false)
            .put("resend_for_regen", false)
            .put("scene_type", 0)
            .put("unique_key", UUID.randomUUID().toString())
            .put("start_seq", 0)
            .put("need_create_conversation", true)
            .put("conversation_init_option", JSONObject().put("need_ack_conversation", true))
            .put(
                "conversation_init_ext",
                JSONObject().put("model_item_key", "0").put("reasoning_effort", "3").put("mode_id", "1")
            )
            .put("regen_query_id", JSONArray())
            .put("edit_query_id", JSONArray())
            .put("regen_instruction", "")
            .put("no_replace_for_regen", false)
            .put("message_from", 0)
            .put("shared_app_name", "")
            .put("shared_app_id", "")
            .put("sse_recv_event_options", JSONObject().put("support_chunk_delta", true))
            .put("support_lazy_fetch_stream", true)
            .put("is_ai_playground", false)
            .put("is_old_user", true)
            .put("general_task_param", JSONObject().put("server_do_compare", true))
            .put(
                "recovery_option",
                JSONObject()
                    .put("is_recovery", false)
                    .put("req_create_time_sec", nowMs / 1000)
                    .put("append_sse_event_scene", 0)
            )
            .put("message_storage_type", 0)
            .put("related_deleted_message_ids", JSONObject())
            .put("connector_info_list", JSONArray())
            .put(
                "model_config",
                JSONObject()
                    .put("model_item_key", "0")
                    .put("model_extra_params", JSONObject())
                    .put("reasoning_effort", 3)
            )
            .put(
                "aggregate_params",
                JSONObject()
                    .put("mention_skill_list", "[]")
                    .put("mention_plugin_list", "[]")
                    .put("mention_ext", "[{}]")
                    .put("conversation_mode", "1")
                    .put("mode_id", "1")
                    .put("model_item_key", "0")
                    .put("agent_mode", "2")
                    .put("reasoning_effort", "3")
                    .put("provider_id", "")
            )
            .put("conversation_mode", 1)

        val ext = JSONObject()
            .put("agent_mode", "2")
            .put("use_deep_think", "0")
            .put("is_finish", "1")
            .put("conversation_init_option", "{\"need_ack_conversation\":true}")
            .put("commerce_credit_config_enable", "0")

        return JSONObject()
            .put(
                "client_meta",
                JSONObject()
                    .put("local_conversation_id", "local_" + System.nanoTime())
                    .put("conversation_id", "")
                    .put("bot_id", BOT_ID)
                    .put("last_section_id", "")
                    .put("last_message_index", JSONObject.NULL)
                    .put("local_permissions", JSONArray())
            )
            .put("messages", messages)
            .put("option", option)
            .put("user_context", JSONArray())
            .put("ext", ext)
    }

    private fun completion(imageUri: String, question: String): String {
        val body = buildCompletionBody(imageUri, question).toString()
        val req = Request.Builder()
            .url("$BASE/chat/completion?$COMMON_QUERY")
            .post(body.toRequestBody(JSON_MEDIA))
            .header("Cookie", cookie)
            .header("Origin", BASE)
            .header("Referer", "$BASE/chat/")
            .header("User-Agent", ua)
            .header("Agw-Js-Conv", "str")
            .header("x-flow-trace", UUID.randomUUID().toString())
            .header("Accept", "text/event-stream")
            .build()
        val text = StringBuilder()
        val rawTail = StringBuilder()
        client.newCall(req).execute().use { resp ->
            if (resp.code != 200) {
                val errBody = try { resp.body?.string()?.take(500) ?: "" } catch (t: Throwable) { "" }
                com.leshao.v3.LogWriter.log("DoubaoImgChatDirect",
                    "completion HTTP ${resp.code} cookieLen=" + cookie.length + " errBody=" + errBody)
                throw RuntimeException("completion HTTP ${resp.code} body=$errBody")
            }
            val source = resp.body!!.source()
            var event = ""
            while (true) {
                val line = source.readUtf8Line() ?: break
                val l = line.trim()
                if (l.startsWith("event:")) {
                    event = l.removePrefix("event:").trim()
                } else if (l.startsWith("data:")) {
                    val data = l.removePrefix("data:").trim()
                    if (rawTail.length < 3000) rawTail.append(data).append('\n')
                    try {
                        val obj = JSONObject(data)
                        when (event) {
                            // CHUNK_DELTA：纯增量文本 {"text":" Seed"}
                            "CHUNK_DELTA" -> {
                                val t = obj.optString("text")
                                if (t.isNotEmpty()) text.append(t)
                            }
                            // FULL_MSG_NOTIFY 是用户消息回显，跳过
                            "FULL_MSG_NOTIFY" -> Unit
                            // STREAM_CHUNK：AI 回复块（递归找 text_block.text）
                            else -> text.append(extractTextBlocks(obj))
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
        if (text.isEmpty()) {
            com.leshao.v3.LogWriter.log("DoubaoImgChatDirect",
                "completion 返回空 cookieLen=" + cookie.length + " rawTail=" + rawTail.take(1500))
        }
        return text.toString()
    }

    /** 递归提取 JSON 里所有 text_block.text（AI 回复流式块） */
    private fun extractTextBlocks(o: Any?): String {
        if (o == null || o == JSONObject.NULL) return ""
        return when (o) {
            is JSONObject -> {
                val sb = StringBuilder()
                val tb = o.optJSONObject("text_block")
                if (tb != null) {
                    val t = tb.optString("text")
                    if (t.isNotEmpty()) sb.append(t)
                }
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    if (k != "text_block") sb.append(extractTextBlocks(o.opt(k)))
                }
                sb.toString()
            }
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until o.length()) sb.append(extractTextBlocks(o.opt(i)))
                sb.toString()
            }
            else -> ""
        }
    }

    // ---------------- SigV4 签名 ----------------

    private fun sign(
        method: String,
        url: String,
        params: Map<String, String>,
        ak: String,
        sk: String,
        st: String,
        body: ByteArray = ByteArray(0),
        extraHeaders: Map<String, String> = emptyMap()
    ): Pair<String, String> {
        val now = Date()
        val amzFmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        amzFmt.timeZone = TimeZone.getTimeZone("UTC")
        val dateFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        dateFmt.timeZone = TimeZone.getTimeZone("UTC")
        val amzDate = amzFmt.format(now)
        val dateStamp = dateFmt.format(now)

        val uri = URI(url)
        val host = uri.host
        val path = if (uri.path.isNullOrEmpty()) "/" else uri.path

        val cq = params.toSortedMap().entries.joinToString("&") { "${rfc3986(it.key)}=${rfc3986(it.value)}" }

        val hdrs = sortedMapOf<String, String>()
        hdrs["host"] = host
        hdrs["x-amz-date"] = amzDate
        hdrs["x-amz-security-token"] = st
        extraHeaders.forEach { hdrs[it.key.lowercase(Locale.US)] = it.value }
        val canonicalHeaders = hdrs.entries.joinToString("") { "${it.key}:${it.value}\n" }
        val signedHeaders = hdrs.keys.joinToString(";")

        val payloadHash = sha256Hex(body)
        val canonicalRequest = "$method\n$path\n$cq\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val scope = "$dateStamp/$IMAGEX_REGION/$IMAGEX_SERVICE/aws4_request"
        val stringToSign =
            "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))}"

        var key = hmacSha256(("AWS4$sk").toByteArray(Charsets.UTF_8), dateStamp)
        key = hmacSha256(key, IMAGEX_REGION)
        key = hmacSha256(key, IMAGEX_SERVICE)
        key = hmacSha256(key, "aws4_request")
        val signature = hex(hmacSha256(key, stringToSign))

        val auth = "AWS4-HMAC-SHA256 Credential=$ak/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
        return Pair(auth, amzDate)
    }

    // ---------------- 工具函数 ----------------

    private fun rfc3986(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%7E", "~")

    private fun sha256Hex(data: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(data)
        return hex(d)
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }

    private fun hmacSha256(key: ByteArray, msg: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8))
    }

    private fun randomStr(n: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val sb = StringBuilder(n)
        repeat(n) { sb.append(chars[(Math.random() * chars.length).toInt()]) }
        return sb.toString()
    }

    /** 自实现 Base64（兼容 API<26，不依赖 java.util.Base64） */
    private fun b64encode(data: ByteArray): String {
        val tbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val has1 = i + 1 < data.size
            val has2 = i + 2 < data.size
            val b1 = if (has1) data[i + 1].toInt() and 0xff else 0
            val b2 = if (has2) data[i + 2].toInt() and 0xff else 0
            sb.append(tbl[b0 ushr 2])
            sb.append(tbl[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            sb.append(if (has1) tbl[((b1 and 0x0f) shl 2) or (b2 ushr 6)] else '=')
            sb.append(if (has2) tbl[b2 and 0x3f] else '=')
            i += 3
        }
        return sb.toString()
    }

    private fun detectExt(b: ByteArray): String = when {
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> ".jpg"
        b.size > 4 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() -> ".png"
        else -> ".png"
    }
}