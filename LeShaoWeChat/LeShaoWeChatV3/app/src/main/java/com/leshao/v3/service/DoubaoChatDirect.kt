package com.leshao.v3.service

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 豆包网页纯文本模型会话【直连版】（v2.5 completion 协议，无图片块）。
 * 与 DoubaoImgChatDirect（图片识别）互补：本客户端只发文本消息，走豆包网页模型会话。
 * 用法：
 *   DoubaoChatDirect.cookie = "sessionid=..."
 *   DoubaoChatDirect.chat("你好", { answer -> ... })
 */
object DoubaoChatDirect {

    @Volatile
    var cookie: String = ""

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

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val sessionLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun convKey(sessionKey: String) = "doubao_conv_" + sessionKey
    private fun histKey(sessionKey: String) = "doubao_hist_" + sessionKey

    /** 异步纯文本对话：成功回调文字，失败回调 null（第7章：会话记忆 + 多轮上下文） */
    fun chat(text: String, sessionKey: String, callback: (String?) -> Unit) {
        Thread {
            var answer: String? = null
            try {
                answer = chatBlocking(text, sessionKey)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            callback(answer)
        }.start()
    }

    /** 同步纯文本对话（阻塞，勿在主线程调用）。第7章：FULL_MSG_NOTIFY 提取 conversation_id/message_id，历史+当前消息一起发送。 */
    fun chatBlocking(text: String, sessionKey: String): String {
        // 会话级串行锁：同一会话的消息顺序处理，避免历史读写竞态导致上下文互相覆盖
        val lock = sessionLocks.computeIfAbsent(sessionKey) { Any() }
        return synchronized(lock) { chatBlockingLocked(text, sessionKey) }
    }

    private fun chatBlockingLocked(text: String, sessionKey: String): String {
        val savedConv = try { com.leshao.v3.wm.utils.WmPrefs.getStr(convKey(sessionKey), "") } catch (t: Throwable) { "" }
        val historyJson = try { com.leshao.v3.wm.utils.WmPrefs.getStr(histKey(sessionKey), "[]") } catch (t: Throwable) { "[]" }
        val body = buildChatBody(text, savedConv, historyJson)
        val req = Request.Builder()
            .url("$BASE/chat/completion?$COMMON_QUERY")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("Cookie", cookie)
            .header("Origin", BASE)
            .header("Referer", "$BASE/chat/")
            .header("User-Agent", ua)
            .header("Agw-Js-Conv", "str")
            .header("x-flow-trace", UUID.randomUUID().toString())
            .header("Accept", "text/event-stream")
            .build()
        val answer = StringBuilder()
        val rawData = StringBuilder()
        var newConv = savedConv
        var userMsgId = ""
        client.newCall(req).execute().use { resp ->
            if (resp.code != 200) {
                val errBody = try { resp.body?.string()?.take(500) ?: "" } catch (t: Throwable) { "" }
                com.leshao.v3.LogWriter.log("DoubaoChatDirect",
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
                    rawData.append(data).append('\n')
                    try {
                        val obj = JSONObject(data)
                        when (event) {
                            // CHUNK_DELTA：AI 增量文本
                            "CHUNK_DELTA" -> {
                                val t = obj.optString("text")
                                if (t.isNotEmpty()) answer.append(t)
                            }
                            // 第7章：FULL_MSG_NOTIFY 是用户消息回显，递归提取 conversation_id + message_id
                            "FULL_MSG_NOTIFY" -> {
                                val cid = findFirst(obj, "conversation_id")
                                if (cid.isNotEmpty()) newConv = cid
                                val mid = findFirst(obj, "message_id")
                                if (mid.isNotEmpty()) userMsgId = mid
                            }
                            // STREAM_CHUNK 等其他事件：递归找 text_block.text
                            else -> answer.append(extractTextBlocks(obj))
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
        // 持久化 conversation_id（第二轮起非空）
        if (newConv.isNotEmpty() && newConv != savedConv) {
            try { com.leshao.v3.wm.utils.WmPrefs.setStr(convKey(sessionKey), newConv) } catch (t: Throwable) {}
        }
        // 更新会话历史：用户消息 + AI 回复，保留最近 10 条
        try {
            val hist = JSONArray(historyJson)
            hist.put(makeTextMessage(text, userMsgId, true))
            if (answer.isNotEmpty()) {
                hist.put(makeTextMessage(answer.toString(), "", false))
            }
            while (hist.length() > 10) hist.remove(0)
            com.leshao.v3.wm.utils.WmPrefs.setStr(histKey(sessionKey), hist.toString())
        } catch (t: Throwable) {}
        if (answer.isEmpty()) {
            com.leshao.v3.LogWriter.log("DoubaoChatDirect",
                "completion 返回空 cookieLen=" + cookie.length + " rawTail=" + rawData.take(1500))
        }
        return answer.toString()
    }

    /** 递归查找 JSON 树中第一个指定 key 的字符串值（第7章：FULL_MSG_NOTIFY 提取） */
    private fun findFirst(o: Any?, key: String): String {
        if (o == null || o == JSONObject.NULL) return ""
        return when (o) {
            is JSONObject -> {
                val direct = o.opt(key)
                if (direct is String && direct.isNotEmpty()) return direct
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    if (k == key) {
                        val v = o.opt(k)
                        if (v is String) return v
                    } else {
                        val r = findFirst(o.opt(k), key)
                        if (r.isNotEmpty()) return r
                    }
                }
                ""
            }
            is JSONArray -> {
                for (i in 0 until o.length()) {
                    val r = findFirst(o.opt(i), key)
                    if (r.isNotEmpty()) return r
                }
                ""
            }
            else -> ""
        }
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

    /** 构造一条纯文本消息（block_type=1，与官方去图块结构一致）。AI 消息带 message_id。 */
    private fun makeTextMessage(text: String, messageId: String, isUser: Boolean): JSONObject {
        val msg = JSONObject()
            .put("local_message_id", UUID.randomUUID().toString())
            .put(
                "content_block",
                JSONArray().put(
                    JSONObject()
                        .put("block_type", 1)
                        .put("content", JSONObject().put("text_block", JSONObject().put("text", text)))
                        .put("block_id", UUID.randomUUID().toString())
                        .put("parent_id", "")
                        .put("meta_info", JSONArray())
                        .put("extra_info", JSONObject())
                        .put("append_fields", JSONArray())
                )
            )
            .put("message_status", 0)
        if (messageId.isNotEmpty()) msg.put("message_id", messageId)
        return msg
    }

    // ---------------- completion 请求体（纯文本版：历史 + 当前消息，第7章） ----------------

    private fun buildChatBody(text: String, conversationId: String, historyJson: String): JSONObject {
        val nowMs = System.currentTimeMillis()
        val messages = JSONArray()
        if (historyJson.isNotEmpty() && historyJson != "[]") {
            try {
                val hist = JSONArray(historyJson)
                for (i in 0 until hist.length()) messages.put(hist.getJSONObject(i))
            } catch (t: Throwable) {}
        }
        messages.put(makeTextMessage(text, "", true))

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
            .put("need_create_conversation", conversationId.isEmpty())
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
                    .put("conversation_id", conversationId)
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
}
