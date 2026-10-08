package com.leshao.v3.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.ArrayList
import java.util.Locale

open class KeywordRule {
    @JvmField
    var keyword: String? = null

    @JvmField
    var reply: String? = null

    @JvmField
    var fuzzyMatch: Boolean = false

    constructor() {}

    constructor(keyword: String?, reply: String?, fuzzyMatch: Boolean) {
        this.keyword = keyword
        this.reply = reply
        this.fuzzyMatch = fuzzyMatch
    }

    fun matches(body: String?): Boolean {
        if (body == null || keyword.isNullOrBlank()) return false
        val kw = keyword!!.trim()
        return if (fuzzyMatch) {
            body.lowercase(Locale.ROOT).contains(kw.lowercase(Locale.ROOT))
        } else {
            body.contains(kw)
        }
    }

    companion object {
        @JvmStatic
        fun fromJson(json: String?): List<KeywordRule> {
            val rules = ArrayList<KeywordRule>()
            if (json == null || json.isEmpty() || "[]" == json) return rules
            try {
                val inner = json.substring(1, json.length - 1)
                if (inner.isEmpty()) return rules
                val items = inner.split("\\},\\{".toRegex())
                for (item0 in items) {
                    val item = item0.replace("{", "").replace("}", "")
                    val kw = extract(item, "\"keyword\":\"", "\"")
                    val rp = extract(item, "\"reply\":\"", "\"")
                    var fm = extract(item, "\"fuzzyMatch\":", ",")
                    if (fm == null) fm = extract(item, "\"fuzzyMatch\":", "}")
                    if (kw != null && rp != null) {
                        rules.add(KeywordRule(kw, rp, "true" == fm))
                    }
                }
            } catch (ignored: Throwable) {}
            return rules
        }

        @JvmStatic
        fun toJson(rules: List<KeywordRule>?): String {
            if (rules.isNullOrEmpty()) return "[]"
            val sb = StringBuilder("[")
            for (i in rules.indices) {
                val r = rules[i]
                if (i > 0) sb.append(",")
                sb.append("{\"keyword\":\"")
                    .append(escape(r.keyword))
                    .append("\",\"reply\":\"")
                    .append(escape(r.reply))
                    .append("\",\"fuzzyMatch\":")
                    .append(r.fuzzyMatch)
                    .append("}")
            }
            sb.append("]")
            return sb.toString()
        }

        @JvmStatic
        fun fromObj(o: JSONObject?): KeywordRule? {
            if (o == null) return null
            val kw = o.optString("keyword", null)
            val rp = o.optString("reply", null)
            if (kw == null || kw.trim().isEmpty()) return null
            return KeywordRule(kw.trim(), rp ?: "", o.optBoolean("fuzzyMatch", false))
        }

        @JvmStatic
        @Throws(JSONException::class)
        fun toObj(r: KeywordRule): JSONObject {
            val o = JSONObject()
            o.put("keyword", r.keyword ?: "")
            o.put("reply", r.reply ?: "")
            o.put("fuzzyMatch", r.fuzzyMatch)
            return o
        }

        @JvmStatic
        fun listFromJson(arr: JSONArray?): List<KeywordRule> {
            val out = ArrayList<KeywordRule>()
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val r = fromObj(arr.optJSONObject(i))
                if (r != null) out.add(r)
            }
            return out
        }

        @JvmStatic
        @Throws(JSONException::class)
        fun listToJson(rules: List<KeywordRule>?): JSONArray {
            val arr = JSONArray()
            if (rules == null) return arr
            for (r in rules) {
                val kw = r?.keyword ?: continue
                if (kw.trim().isEmpty()) continue
                arr.put(toObj(r))
            }
            return arr
        }

        private fun extract(src: String, prefix: String, suffix: String): String? {
            var s = src.indexOf(prefix)
            if (s < 0) return null
            s += prefix.length
            val e = src.indexOf(suffix, s)
            if (e < 0) return src.substring(s)
            return src.substring(s, e)
        }

        private fun escape(s: String?): String {
            if (s == null) return ""
            return s.replace("\\", "\\\\").replace("\"", "\\\"")
        }
    }
}