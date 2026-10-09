package com.leshao.ai.memory

import org.json.JSONException
import org.json.JSONObject

class ChatMessage(
    val role: String,
    val content: String,
    val time: Long
) {
    constructor(role: String, content: String) : this(role, content, System.currentTimeMillis())

    fun toJson(): JSONObject {
        val obj = JSONObject()
        try {
            obj.put("role", role)
            obj.put("content", content)
            obj.put("time", time)
        } catch (ignored: JSONException) {
        }
        return obj
    }

    override fun toString(): String {
        return "ChatMessage{role='" + role + "', content='" + content + "', time=" + time + '}'
    }

    companion object {
        @JvmStatic
        fun fromJson(obj: JSONObject): ChatMessage {
            return ChatMessage(
                    obj.optString("role", "user"),
                    obj.optString("content", ""),
                    obj.optLong("time", 0L)
            )
        }
    }
}