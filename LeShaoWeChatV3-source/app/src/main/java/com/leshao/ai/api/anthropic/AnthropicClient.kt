package com.leshao.ai.api.anthropic

import com.leshao.ai.api.model.ChatMessage

import org.json.JSONArray
import org.json.JSONObject

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class AnthropicClient {
    private val baseUrl: String
    private val apiKey: String
    private val model: String
    private var temperature: Double? = null
    private var maxTokens: Int? = null
    private var connectTimeout = 30_000
    private var readTimeout = 120_000

    constructor(apiKey: String, model: String) {
        this.baseUrl = DEFAULT_BASE_URL
        this.apiKey = apiKey
        this.model = model
    }

    constructor(baseUrl: String?, apiKey: String, model: String) {
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
        val body = JSONObject()
        body.put("model", model)
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens)
        } else {
            body.put("max_tokens", 1024)
        }
        if (temperature != null) {
            body.put("temperature", temperature)
        }
        if (system != null && !system.isEmpty()) {
            body.put("system", system)
        }

        val arr = JSONArray()
        if (messages != null) {
            for (m in messages) {
                val msg = JSONObject()
                msg.put("role", m.role)
                val contentArr = JSONArray()
                val contentItem = JSONObject()
                contentItem.put("type", "text")
                contentItem.put("text", m.content ?: "")
                contentArr.put(contentItem)
                msg.put("content", contentArr)
                arr.put(msg)
            }
        }
        body.put("messages", arr)

        val raw = postJson(com.leshao.ai.api.ApiUrl.join(baseUrl, "/v1/messages"), body)
        val json = JSONObject(raw)

        val content = json.optJSONArray("content")
        if (content != null && content.length() > 0) {
            val first = content.optJSONObject(0)
            if (first != null) {
                return first.optString("text", "")
            }
        }
        return ""
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
            conn.setRequestProperty("x-api-key", com.leshao.ai.api.ApiUrl.normalizeKey(apiKey))
            conn.setRequestProperty("anthropic-version", API_VERSION)

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
                throw Exception("Anthropic 请求失败，HTTP 状态码：" + code + "，响应：" + errBody)
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

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val API_VERSION = "2023-06-01"
    }
}