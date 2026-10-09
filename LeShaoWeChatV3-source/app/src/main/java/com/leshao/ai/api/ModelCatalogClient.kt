package com.leshao.ai.api

import com.leshao.ai.api.model.ProviderType

import org.json.JSONArray
import org.json.JSONObject

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Collections

class ModelCatalogClient private constructor() {

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun fetch(providerType: String?, baseUrl: String?, apiKey: String?): List<String> {
            val base = baseUrl?.trim() ?: ""
            if (base.isEmpty()) {
                throw IllegalArgumentException("接口地址为空")
            }
            val key = ApiUrl.normalizeKey(apiKey)
            val anthropic = isAnthropic(providerType)
            var last: Exception? = null
            for (path in arrayOf("/v1/models", "/models")) {
                val endpoint = ApiUrl.join(base, path)
                try {
                    return request(endpoint, key, anthropic)
                } catch (e: HttpError) {
                    last = e
                    if (e.code != 404) throw e
                }
            }
            throw last ?: IllegalStateException("获取模型失败")
        }

        @JvmStatic
        @Throws(Exception::class)
        fun fetch(type: ProviderType?, baseUrl: String?, apiKey: String?): List<String> {
            return fetch(type?.name, baseUrl, apiKey)
        }

        private class HttpError(val code: Int, message: String) : Exception(message)

        private fun isAnthropic(providerType: String?): Boolean {
            if (providerType == null) return false
            val t = providerType.lowercase()
            return t.contains("anthropic") || t.contains("claude")
        }

        @Throws(Exception::class)
        private fun request(endpoint: String, key: String, anthropic: Boolean): List<String> {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.setRequestProperty("Accept", "application/json")
                if (anthropic) {
                    conn.setRequestProperty("x-api-key", key)
                    conn.setRequestProperty("anthropic-version", "2023-06-01")
                } else {
                    conn.setRequestProperty("Authorization", "Bearer " + key)
                }
                val code = conn.responseCode
                val stream: InputStream = if (code >= 200 && code < 300) conn.inputStream else conn.errorStream
                val body = readAll(stream)
                if (code < 200 || code >= 300) {
                    throw HttpError(code, "HTTP " + code + " (" + endpoint + "): " + brief(body))
                }
                return parseModelIds(body)
            } finally {
                conn?.let {
                    try {
                        it.disconnect()
                    } catch (ignored: Throwable) {}
                }
            }
        }

        @Throws(Exception::class)
        private fun parseModelIds(body: String?): List<String> {
            val out = ArrayList<String>()
            if (body == null || body.trim().isEmpty()) return out
            val obj = JSONObject(body)
            var arr = obj.optJSONArray("data")
            if (arr == null) arr = obj.optJSONArray("models")
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val it = arr.opt(i)
                var id: String? = null
                if (it is String) {
                    id = it
                } else if (it is JSONObject) {
                    val o = it
                    id = o.optString("id", null)
                    if (id == null) id = o.optString("name", null)
                }
                if (id != null && !id.trim().isEmpty()) out.add(id.trim())
            }
            Collections.sort(out)
            return out
        }

        @Throws(Exception::class)
        private fun readAll(stream: InputStream?): String {
            if (stream == null) return ""
            val sb = StringBuilder()
            val br = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
            br.use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    sb.append(line)
                }
            }
            return sb.toString()
        }

        private fun brief(s: String?): String {
            if (s == null) return ""
            return if (s.length > 200) s.substring(0, 200) else s
        }
    }
}