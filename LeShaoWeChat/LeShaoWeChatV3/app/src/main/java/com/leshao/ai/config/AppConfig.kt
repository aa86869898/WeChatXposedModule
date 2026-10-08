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

class AppConfig(private val configFile: File) {

    constructor(hostDataDir: String) : this(File(hostDataDir, DEFAULT_CONFIG_FILE))

    @get:Synchronized @get:JvmName("isEnabled") @set:Synchronized
    var enabled: Boolean = false

    @get:Synchronized @set:Synchronized
    var providerType: String = "openai"
        set(value) {
            field = value ?: ""
        }

    @get:Synchronized @set:Synchronized
    var baseUrl: String = ""
        set(value) {
            field = value ?: ""
        }

    @get:Synchronized @set:Synchronized
    var apiKey: String = ""
        set(value) {
            field = value ?: ""
        }

    @get:Synchronized @set:Synchronized
    var model: String = ""
        set(value) {
            field = value ?: ""
        }

    @get:Synchronized @set:Synchronized
    var temperature: Double = 0.7

    @get:Synchronized @set:Synchronized
    var maxTokens: Int = 800

    @get:Synchronized @set:Synchronized
    var systemPrompt: String = "你是一个贴心、幽默的微信AI助手，用中文回答用户问题。"
        set(value) {
            field = value ?: ""
        }

    @get:Synchronized @get:JvmName("isTtsEnabled") @set:Synchronized
    var ttsEnabled: Boolean = false

    @get:Synchronized @set:Synchronized
    var botName: String = "小乐"
        set(value) {
            field = value ?: ""
        }

    @get:Synchronized @set:Synchronized
    var wakeKeyword: String = ""
        set(value) {
            field = value ?: ""
        }

    @get:Synchronized @get:JvmName("isAutoReplyInGroups") @set:Synchronized
    var autoReplyInGroups: Boolean = false

    @get:Synchronized @get:JvmName("isAutoReplyInPrivate") @set:Synchronized
    var autoReplyInPrivate: Boolean = false

    @get:Synchronized @get:JvmName("isOnlyWhenMentioned") @set:Synchronized
    var onlyWhenMentioned: Boolean = false

    @get:Synchronized @get:JvmName("isQuoteReply") @set:Synchronized
    var quoteReply: Boolean = false

    @get:Synchronized @get:JvmName("isAutoAt") @set:Synchronized
    var autoAt: Boolean = false

    @get:Synchronized @set:Synchronized
    var maxHistoryMessages: Int = 100
        set(value) {
            field = if (value < 1) 1 else value
        }

    @get:Synchronized @get:JvmName("isKeywordReplyEnabled") @set:Synchronized
    var keywordReplyEnabled: Boolean = false

    @get:Synchronized @set:Synchronized
    var keywordReplyRules: MutableList<com.leshao.v3.model.KeywordRule> = ArrayList()
        set(value) {
            field = value ?: ArrayList()
        }

    @get:Synchronized @set:Synchronized
    var modelHistory: MutableList<String> = ArrayList()

    @Synchronized
    fun load() {
        if (configFile == null || !configFile.exists()) {
            return
        }
        try {
            FileInputStream(configFile).use { fis ->
                val data = ByteArray(configFile.length().toInt())
                val read = fis.read(data)
                if (read <= 0) {
                    return
                }
                val obj = JSONObject(String(data, 0, read, StandardCharsets.UTF_8))
                parse(obj)
            }
        } catch (e: IOException) {
        } catch (e: JSONException) {
        }
    }

    @Synchronized
    fun save(): Boolean {
        if (configFile == null) {
            return false
        }
        try {
            val parent = configFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false
            }
            val obj = toJson()
            FileOutputStream(configFile, false).use { out ->
                out.write(obj.toString(2).toByteArray(StandardCharsets.UTF_8))
            }
            return true
        } catch (e: IOException) {
            return false
        } catch (e: JSONException) {
            return false
        }
    }

    private fun parse(obj: JSONObject) {
        enabled = obj.optBoolean("enabled", enabled)
        providerType = obj.optString("providerType", providerType)
        baseUrl = obj.optString("baseUrl", baseUrl)
        apiKey = obj.optString("apiKey", apiKey)
        model = obj.optString("model", model)
        temperature = obj.optDouble("temperature", temperature)
        maxTokens = obj.optInt("maxTokens", maxTokens)
        systemPrompt = obj.optString("systemPrompt", systemPrompt)
        ttsEnabled = obj.optBoolean("ttsEnabled", ttsEnabled)
        botName = obj.optString("botName", botName)
        wakeKeyword = obj.optString("wakeKeyword", wakeKeyword)
        autoReplyInGroups = obj.optBoolean("autoReplyInGroups", autoReplyInGroups)
        autoReplyInPrivate = obj.optBoolean("autoReplyInPrivate", autoReplyInPrivate)
        onlyWhenMentioned = obj.optBoolean("onlyWhenMentioned", onlyWhenMentioned)
        quoteReply = obj.optBoolean("quoteReply", quoteReply)
        autoAt = obj.optBoolean("autoAt", autoAt)
        maxHistoryMessages = obj.optInt("maxHistoryMessages", maxHistoryMessages)
        keywordReplyEnabled = obj.optBoolean("keywordReplyEnabled", keywordReplyEnabled)
        val rules = com.leshao.v3.model.KeywordRule
                .listFromJson(obj.optJSONArray("keywordReplyRules")) ?: emptyList()
        keywordReplyRules = java.util.ArrayList(rules)
        modelHistory.clear()
        val mh = obj.optJSONArray("modelHistory")
        if (mh != null) {
            for (i in 0 until mh.length()) {
                val m = mh.optString(i, null)
                if (m != null && !m.trim().isEmpty()) modelHistory.add(m.trim())
            }
            trimModelHistory()
        }
    }

    @Throws(JSONException::class)
    private fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("enabled", enabled)
        obj.put("providerType", providerType)
        obj.put("baseUrl", baseUrl)
        obj.put("apiKey", apiKey)
        obj.put("model", model)
        obj.put("temperature", temperature)
        obj.put("maxTokens", maxTokens)
        obj.put("systemPrompt", systemPrompt)
        obj.put("ttsEnabled", ttsEnabled)
        obj.put("botName", botName)
        obj.put("wakeKeyword", wakeKeyword)
        obj.put("autoReplyInGroups", autoReplyInGroups)
        obj.put("autoReplyInPrivate", autoReplyInPrivate)
        obj.put("onlyWhenMentioned", onlyWhenMentioned)
        obj.put("quoteReply", quoteReply)
        obj.put("autoAt", autoAt)
        obj.put("maxHistoryMessages", maxHistoryMessages)
        obj.put("keywordReplyEnabled", keywordReplyEnabled)
        obj.put("keywordReplyRules",
                com.leshao.v3.model.KeywordRule.listToJson(keywordReplyRules))
        val mh = JSONArray()
        for (m in modelHistory) mh.put(m)
        obj.put("modelHistory", mh)
        return obj
    }

    @Synchronized
    fun reset() {
        enabled = false
        providerType = "openai"
        baseUrl = ""
        apiKey = ""
        model = ""
        temperature = 0.7
        maxTokens = 800
        systemPrompt = "你是日常聊天助手，根据对话上下文自动识别情绪氛围。\n" +
                "输出要求：\n" +
                "1.回复简短口语化，不要长篇大论；符合普通人微信说话习惯。\n" +
                "2.自动匹配语气：安慰、开玩笑、温柔客套、吐槽共情、冷淡简洁。\n" +
                "3.不要说教、不要鸡汤，避免书面官话。\n" +
                "4.只输出回复文本，不加解释、标签、标点外多余内容。\n" +
                "场景参考：对方难过→共情安抚；对方开玩笑→幽默接梗；对方说事→正常客套回应。"
        ttsEnabled = false
        botName = "小乐"
        wakeKeyword = ""
        autoReplyInGroups = false
        autoReplyInPrivate = false
        onlyWhenMentioned = false
        quoteReply = false
        autoAt = false
        maxHistoryMessages = 100
        keywordReplyEnabled = false
        keywordReplyRules.clear()
        modelHistory.clear()
    }

    @Synchronized
    fun isUsable(): Boolean {
        return enabled && !TextUtils.isEmpty(baseUrl)
    }

    @Synchronized
    fun recordModel(model: String?) {
        if (model == null) return
        val m = model.trim()
        if (m.isEmpty()) return
        modelHistory.remove(m)
        modelHistory.add(0, m)
        trimModelHistory()
    }

    @Synchronized
    fun removeModelHistory(model: String?) {
        if (model == null) return
        modelHistory.remove(model)
    }

    private fun trimModelHistory() {
        while (modelHistory.size > MODEL_HISTORY_LIMIT) {
            modelHistory.removeAt(modelHistory.size - 1)
        }
    }

    @Synchronized
    fun getWakeKeywords(): Array<String> {
        val kw = wakeKeyword
        if (TextUtils.isEmpty(kw)) {
            return emptyArray()
        }
        return kw.split("[,，;；\\s]+".toRegex()).toTypedArray()
    }

    @Synchronized
    fun toDebugString(): String {
        try {
            val obj = toJson()
            obj.put("apiKey", "******")
            return obj.toString()
        } catch (e: JSONException) {
            return toString()
        }
    }

    companion object {
        const val DEFAULT_CONFIG_FILE = "leshao_ai/config.json"
        const val MODEL_HISTORY_LIMIT = 20
    }
}