package com.leshao.ai.config

import android.text.TextUtils

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Map

class ConversationConfig {
    private val file: File
    private val overrides = LinkedHashMap<String, Entry>()
    private val templates = LinkedHashMap<String, Entry>()

    constructor(hostDataDir: String) : this(File(hostDataDir, DEFAULT_FILE))

    constructor(file: File) {
        this.file = file
    }

    class Entry {
        @JvmField var enabled: Boolean? = null
        @JvmField var autoReply: Boolean? = null
        @JvmField var onlyWhenMentioned: Boolean? = null
        @JvmField var ttsEnabled: Boolean? = null
        @JvmField var systemPrompt: String? = null
        @JvmField var aiIdentity: String? = null
        @JvmField var aiName: String? = null
        @JvmField var memoryEnabled: Boolean? = null
        @JvmField var memoryLimit: Int? = null
        @JvmField var model: String? = null
        @JvmField var temperature: Double? = null
        @JvmField var voices: MutableList<String>? = null
        @JvmField var randomVoice: Boolean? = null
        @JvmField var quoteReply: Boolean? = null
        @JvmField var autoAt: Boolean? = null
        @JvmField var keywordReplyEnabled: Boolean? = null
        @JvmField var keywordReplyRules: MutableList<com.leshao.v3.model.KeywordRule>? = null
        @JvmField var keywordAutoAt: Boolean? = null
        @JvmField var keywordQuote: Boolean? = null

        fun isEmpty(): Boolean {
            val vs = voices
            val kr = keywordReplyRules
            return enabled == null && autoReply == null && onlyWhenMentioned == null
                    && ttsEnabled == null
                    && TextUtils.isEmpty(systemPrompt) && TextUtils.isEmpty(model)
                    && TextUtils.isEmpty(aiIdentity) && TextUtils.isEmpty(aiName)
                    && memoryEnabled == null && memoryLimit == null
                    && temperature == null
                    && (vs == null || vs.isEmpty()) && randomVoice == null
                    && quoteReply == null && autoAt == null
                    && keywordReplyEnabled == null
                    && (kr == null || kr.isEmpty())
                    && keywordAutoAt == null && keywordQuote == null
        }

        fun isActive(): Boolean {
            if (enabled != null) return enabled!!
            if (autoReply != null) return autoReply!!
            return true
        }

        fun copy(): Entry {
            val e = Entry()
            e.enabled = enabled
            e.autoReply = autoReply
            e.onlyWhenMentioned = onlyWhenMentioned
            e.ttsEnabled = ttsEnabled
            e.systemPrompt = systemPrompt
            e.aiIdentity = aiIdentity
            e.aiName = aiName
            e.memoryEnabled = memoryEnabled
            e.memoryLimit = memoryLimit
            e.model = model
            e.temperature = temperature
            e.voices = voices?.let { ArrayList(it) }
            e.randomVoice = randomVoice
            e.quoteReply = quoteReply
            e.autoAt = autoAt
            e.keywordReplyEnabled = keywordReplyEnabled
            e.keywordReplyRules = keywordReplyRules?.let { ArrayList(it) }
            e.keywordAutoAt = keywordAutoAt
            e.keywordQuote = keywordQuote
            return e
        }

        @Throws(JSONException::class)
        fun toJson(): JSONObject {
            val o = JSONObject()
            if (enabled != null) o.put("enabled", enabled!!)
            if (autoReply != null) o.put("autoReply", autoReply!!)
            if (onlyWhenMentioned != null) o.put("onlyWhenMentioned", onlyWhenMentioned!!)
            if (ttsEnabled != null) o.put("ttsEnabled", ttsEnabled!!)
            if (!TextUtils.isEmpty(systemPrompt)) o.put("systemPrompt", systemPrompt)
            if (!TextUtils.isEmpty(aiIdentity)) o.put("aiIdentity", aiIdentity)
            if (!TextUtils.isEmpty(aiName)) o.put("aiName", aiName)
            if (memoryEnabled != null) o.put("memoryEnabled", memoryEnabled!!)
            if (memoryLimit != null) o.put("memoryLimit", memoryLimit!!)
            if (!TextUtils.isEmpty(model)) o.put("model", model)
            if (temperature != null) o.put("temperature", temperature!!)
            val vs = voices
            if (vs != null && !vs.isEmpty()) {
                val arr = JSONArray()
                for (v in vs) {
                    if (!TextUtils.isEmpty(v)) arr.put(v)
                }
                o.put("voices", arr)
            }
            if (randomVoice != null) o.put("randomVoice", randomVoice!!)
            if (quoteReply != null) o.put("quoteReply", quoteReply!!)
            if (autoAt != null) o.put("autoAt", autoAt!!)
            if (keywordReplyEnabled != null) {
                o.put("keywordReplyEnabled", keywordReplyEnabled!!)
            }
            val kr = keywordReplyRules
            if (kr != null && !kr.isEmpty()) {
                o.put("keywordReplyRules",
                        com.leshao.v3.model.KeywordRule.listToJson(kr))
            }
            if (keywordAutoAt != null) o.put("keywordAutoAt", keywordAutoAt!!)
            if (keywordQuote != null) o.put("keywordQuote", keywordQuote!!)
            return o
        }

        companion object {
            @JvmStatic
            fun fromJson(o: JSONObject?): Entry {
                val e = Entry()
                if (o == null) return e
                if (o.has("enabled") && !o.isNull("enabled")) e.enabled = o.optBoolean("enabled")
                if (o.has("autoReply") && !o.isNull("autoReply")) e.autoReply = o.optBoolean("autoReply")
                if (o.has("onlyWhenMentioned") && !o.isNull("onlyWhenMentioned")) {
                    e.onlyWhenMentioned = o.optBoolean("onlyWhenMentioned")
                }
                if (o.has("ttsEnabled") && !o.isNull("ttsEnabled")) e.ttsEnabled = o.optBoolean("ttsEnabled")
                e.systemPrompt = o.optString("systemPrompt", null)
                e.aiIdentity = o.optString("aiIdentity", null)
                e.aiName = o.optString("aiName", null)
                if (o.has("memoryEnabled") && !o.isNull("memoryEnabled")) {
                    e.memoryEnabled = o.optBoolean("memoryEnabled")
                }
                if (o.has("memoryLimit") && !o.isNull("memoryLimit")) {
                    e.memoryLimit = o.optInt("memoryLimit")
                }
                e.model = o.optString("model", null)
                if (o.has("temperature") && !o.isNull("temperature")) {
                    e.temperature = o.optDouble("temperature")
                }
                val voices = o.optJSONArray("voices")
                if (voices != null && voices.length() > 0) {
                    val vs = ArrayList<String>()
                    for (i in 0 until voices.length()) {
                        val v = voices.optString(i, null)
                        if (!TextUtils.isEmpty(v)) vs.add(v)
                    }
                    if (!vs.isEmpty()) e.voices = vs
                }
                if (o.has("randomVoice") && !o.isNull("randomVoice")) {
                    e.randomVoice = o.optBoolean("randomVoice")
                }
                if (o.has("quoteReply") && !o.isNull("quoteReply")) {
                    e.quoteReply = o.optBoolean("quoteReply")
                }
                if (o.has("autoAt") && !o.isNull("autoAt")) {
                    e.autoAt = o.optBoolean("autoAt")
                }
                if (o.has("keywordReplyEnabled") && !o.isNull("keywordReplyEnabled")) {
                    e.keywordReplyEnabled = o.optBoolean("keywordReplyEnabled")
                }
                val kr = com.leshao.v3.model.KeywordRule
                        .listFromJson(o.optJSONArray("keywordReplyRules"))
                if (!kr.isEmpty()) e.keywordReplyRules = java.util.ArrayList(kr)
                if (o.has("keywordAutoAt") && !o.isNull("keywordAutoAt")) {
                    e.keywordAutoAt = o.optBoolean("keywordAutoAt")
                }
                if (o.has("keywordQuote") && !o.isNull("keywordQuote")) {
                    e.keywordQuote = o.optBoolean("keywordQuote")
                }
                return e
            }
        }
    }

    @Synchronized
    fun load() {
        overrides.clear()
        templates.clear()
        if (file == null || !file.exists()) return
        try {
            FileInputStream(file).use { fis ->
                val data = ByteArray(file.length().toInt())
                val read = fis.read(data)
                if (read <= 0) return
                val root = JSONObject(String(data, 0, read, StandardCharsets.UTF_8))
                val ov = root.optJSONObject("overrides")
                if (ov != null) {
                    val it = ov.keys()
                    while (it.hasNext()) {
                        val k = it.next() as String
                        overrides[k] = Entry.fromJson(ov.optJSONObject(k))
                    }
                }
                val tpl = root.optJSONArray("templates")
                if (tpl != null) {
                    for (i in 0 until tpl.length()) {
                        val o = tpl.optJSONObject(i)
                        if (o == null) continue
                        val name = o.optString("name", null)
                        if (TextUtils.isEmpty(name)) continue
                        templates[name] = Entry.fromJson(o)
                    }
                }
            }
        } catch (ignored: IOException) {
        } catch (ignored: JSONException) {
        }
    }

    @Synchronized
    fun save(): Boolean {
        if (file == null) return false
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) return false
            val root = JSONObject()
            val ov = JSONObject()
            for (e in overrides) {
                ov.put(e.key, e.value.toJson())
            }
            root.put("overrides", ov)
            val tpl = JSONArray()
            for (e in templates) {
                val o = e.value.toJson()
                o.put("name", e.key)
                tpl.put(o)
            }
            root.put("templates", tpl)
            FileOutputStream(file, false).use { out ->
                out.write(root.toString(2).toByteArray(StandardCharsets.UTF_8))
            }
            return true
        } catch (e: IOException) {
            return false
        } catch (e: JSONException) {
            return false
        }
    }

    @Synchronized
    fun get(talker: String?): Entry? {
        return overrides[talker]
    }

    @Synchronized
    fun put(talker: String?, entry: Entry?) {
        if (TextUtils.isEmpty(talker)) return
        if (entry == null || entry.isEmpty()) {
            overrides.remove(talker)
        } else {
            overrides[talker!!] = entry
        }
    }

    @Synchronized
    fun remove(talker: String?) {
        overrides.remove(talker)
    }

    @Synchronized
    fun keys(): List<String> {
        return ArrayList(overrides.keys)
    }

    @Synchronized
    fun keysByType(group: Boolean): List<String> {
        val out = ArrayList<String>()
        for (k in overrides.keys) {
            if (isGroupTalker(k) == group) out.add(k)
        }
        return out
    }

    companion object {
        const val DEFAULT_FILE = "leshao_ai/conversations.json"

        @JvmStatic
        fun isGroupTalker(talker: String?): Boolean {
            if (talker == null) return false
            return talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")
        }
    }

    @Synchronized
    fun templateNames(): List<String> {
        return ArrayList(templates.keys)
    }

    @Synchronized
    fun getTemplate(name: String?): Entry? {
        return templates[name]
    }

    @Synchronized
    fun putTemplate(name: String?, entry: Entry?) {
        if (TextUtils.isEmpty(name)) return
        templates[name!!] = entry ?: Entry()
    }

    @Synchronized
    fun removeTemplate(name: String?) {
        templates.remove(name)
    }

    @Synchronized
    fun applyTemplate(talker: String?, templateName: String?): Boolean {
        val t = templates[templateName]
        if (t == null || TextUtils.isEmpty(talker)) return false
        overrides[talker!!] = t.copy()
        return true
    }
}