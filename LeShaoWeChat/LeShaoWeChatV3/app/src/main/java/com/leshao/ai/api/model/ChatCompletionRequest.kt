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
        val sys = system
        if (sys != null && !sys.isEmpty()) {
            arr.put(ChatMessage("system", sys).toJson())
        }
        if (messages != null) {
            for (m in messages) {
                arr.put(m.toJson())
            }
        }
        json.put("messages", arr)
        return json
    }
}