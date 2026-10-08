package com.leshao.ai.api.model

import org.json.JSONException
import org.json.JSONObject

class ChatMessage {
    var role: String? = null
    var content: String? = null
    var timestamp: Long = 0L

    constructor()

    constructor(role: String?, content: String?) : this() {
        this.role = role
        this.content = content
        this.timestamp = System.currentTimeMillis()
    }

    constructor(role: String?, content: String?, timestamp: Long) : this() {
        this.role = role
        this.content = content
        this.timestamp = timestamp
    }

    fun toJson(): JSONObject {
        val obj = JSONObject()
        try {
            obj.put("role", role)
            obj.put("content", content)
        } catch (ignored: JSONException) {
        }
        return obj
    }
}