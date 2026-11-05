package com.leshao.ai.api.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class ChatCompletionRequest {
    var messages: List<ChatMessage> = ArrayList()
    var model: String? = null
    var temperature: Double? = null
    var maxTokens: Int? = null
    var system: String? = null

    constructor() {
        this.messages = ArrayList()
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("model", model)
        if (temperature != null) {
            json.put("temperature", temperature)
        }
        if (maxTokens != null) {
            json.put("max_tokens", maxTokens)
        }

        val arr = JSONArray()
        // 注意：调用方（AIBotCore.generateReply/generateSummary）已在 messages 中显式加入 system 消息，
        // 此处不再自动插入顶层 system，避免 system 人设重复注入（浪费 token 且可能干扰模型行为）。
        if (messages != null) {
            for (m in messages) {
                arr.put(m.toJson())
            }
        }
        json.put("messages", arr)
        return json
    }
}