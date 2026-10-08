package com.leshao.ai.api.openai

import com.leshao.ai.api.model.ChatCompletionRequest
import com.leshao.ai.api.model.ChatCompletionResponse
import com.leshao.ai.api.model.ChatMessage
import com.leshao.ai.api.model.ProviderType

import org.json.JSONObject

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class OpenAIClient {
    private val type: ProviderType
    private val baseUrl: String
    private val apiKey: String
    private val model: String
    private var temperature: Double? = null
    private var maxTokens: Int? = null
    private var connectTimeout = 30_000
    private var readTimeout = 120_000

    constructor(type: ProviderType?, baseUrl: String?, apiKey: String, model: String) {
        var resolved = type ?: ProviderType.OPENAI_CHAT
        if (resolved != ProviderType.OPENAI_CHAT && resolved != ProviderType.OPENAI_RESPONSES) {
            resolved = ProviderType.OPENAI_CHAT
        }
        this.type = resolved
        this.baseUrl = trimTrailingSlash(baseUrl)
        this.apiKey = apiKey
        this.model = model
    }

    fun setTemperature(temperature: Double?) {
        this.temperature = temperature
    }

    fun setMaxTokens(maxTokens: Int?) {
        this.maxTokens = maxTokens
    }

    fun setConnectTimeout(connectTimeout: Int) {
        this.connectTimeout = connectTimeout
    }

    fun setReadTimeout(readTimeout: Int) {
        this.readTimeout = readTimeout
    }

    @Throws(Exception::class)
    fun chat(messages: List<ChatMessage>?, system: String?): String {
        val request = ChatCompletionRequest()
        request.model = model
        request.messages = messages ?: emptyList()
        request.system = system
        request.temperature = temperature
        request.maxTokens = maxTokens

        var endpoint = "/v1/chat/completions"
        val responsesMode = (type == ProviderType.OPENAI_RESPONSES)
        if (responsesMode) {
            endpoint = "/v1/responses"
        }

        val body = request.toJson()
        if (responsesMode) {
            val input = body.optJSONArray("messages")
            body.remove("messages")
            body.put("input", input)
        }

        val raw = postJson(com.leshao.ai.api.ApiUrl.join(baseUrl, endpoint), body)
        val json = JSONObject(raw)

        var text = if (responsesMode) {
            ChatCompletionResponse.extractTextFromResponses(json)
        } else {
            ChatCompletionResponse.extractTextFromChat(json)
        }
        if (text == null || text.isEmpty()) {
            text = ChatCompletionResponse.extractTextFromChat(json)
        }
        return text
    }

    @Throws(Exception::class)
    private fun postJson(urlStr: String, json: JSONObject): String {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeout
            conn.readTimeout = readTimeout
            conn.doOutput = true
            conn.useCaches = false

            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            val key = com.leshao.ai.api.ApiUrl.normalizeKey(apiKey)
            if (key.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + key)
            }

            val bodyBytes = json.toString().toByteArray(StandardCharsets.UTF_8)
            conn.getOutputStream().use { os: OutputStream ->
                os.write(bodyBytes)
                os.flush()
            }

            val code = conn.responseCode
            return if (code >= 200 && code < 300) {
                readStream(conn.inputStream)
            } else {
                val errBody = readStream(conn.errorStream)
                throw Exception("请求失败，HTTP 状态码：" + code + "，响应：" + errBody)
            }
        } finally {
            conn?.disconnect()
        }
    }

    @Throws(Exception::class)
    private fun readStream(stream: InputStream?): String {
        if (stream == null) {
            return ""
        }
        val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
        val sb = StringBuilder()
        reader.use { r ->
            var line: String?
            while (r.readLine().also { line = it } != null) {
                sb.append(line)
            }
        }
        return sb.toString()
    }

    private fun trimTrailingSlash(url: String?): String {
        if (url == null) {
            return ""
        }
        var s = url.trim()
        while (s.endsWith("/")) {
            s = s.substring(0, s.length - 1)
        }
        return s
    }
}