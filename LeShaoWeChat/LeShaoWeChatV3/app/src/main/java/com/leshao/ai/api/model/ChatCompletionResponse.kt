package com.leshao.ai.api.model

import org.json.JSONArray
import org.json.JSONObject

class ChatCompletionResponse {
    var id: String? = null
    var model: String? = null
    var content: String? = null
    var raw: String? = null

    constructor()

    companion object {
        @JvmStatic
        fun extractTextFromChat(json: JSONObject?): String {
            if (json == null) {
                return ""
            }
            try {
                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val first = choices.optJSONObject(0)
                    if (first != null) {
                        val message = first.optJSONObject("message")
                        if (message != null) {
                            val c = message.opt("content")
                            return if (c == null) "" else c.toString()
                        }
                    }
                }
            } catch (ignored: Exception) {
            }
            return ""
        }

        @JvmStatic
        fun extractTextFromResponses(json: JSONObject?): String {
            if (json == null) {
                return ""
            }
            val sb = StringBuilder()
            try {
                val output = json.optJSONArray("output")
                if (output != null) {
                    for (i in 0 until output.length()) {
                        val item = output.optJSONObject(i)
                        if (item == null) {
                            continue
                        }
                        val content = item.optJSONArray("content")
                        if (content != null) {
                            for (j in 0 until content.length()) {
                                val c = content.optJSONObject(j)
                                if (c != null && c.optString("type", "") == "output_text") {
                                    sb.append(c.optString("text", ""))
                                }
                            }
                        }
                    }
                }
            } catch (ignored: Exception) {
            }
            return sb.toString()
        }
    }
}