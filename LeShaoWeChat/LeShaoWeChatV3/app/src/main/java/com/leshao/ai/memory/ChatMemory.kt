package com.leshao.ai.memory

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Map
import java.util.concurrent.ConcurrentHashMap

class ChatMemory {
    private val file: File
    private val maxPerChat: Int
    private val buckets = ConcurrentHashMap<String, MutableList<ChatMessage>>()

    constructor(hostDataDir: String, maxPerChat: Int) : this(File(hostDataDir, DEFAULT_FILE), maxPerChat)

    constructor(file: File, maxPerChat: Int) {
        this.file = file
        this.maxPerChat = Math.max(1, maxPerChat)
    }

    @Synchronized
    fun add(chatId: String?, msg: ChatMessage?) {
        if (chatId == null || msg == null) {
            return
        }
        val list = buckets.computeIfAbsent(chatId) { ArrayList() }
        list.add(msg)
        trim(list)
    }

    @Synchronized
    fun add(chatId: String?, role: String, content: String) {
        add(chatId, ChatMessage(role, content))
    }

    private fun trim(list: MutableList<ChatMessage>) {
        while (list.size > maxPerChat) {
            list.removeAt(0)
        }
    }

    @Synchronized
    fun getRecent(chatId: String?, n: Int): List<ChatMessage> {
        val all = buckets[chatId]
        if (all == null || all.isEmpty()) {
            return ArrayList()
        }
        val take = if (n <= 0) maxPerChat else Math.min(n, all.size)
        val start = all.size - take
        return ArrayList(all.subList(start, all.size))
    }

    @Synchronized
    fun getAll(chatId: String?): List<ChatMessage> {
        val all = buckets[chatId]
        return all?.let { ArrayList(it) } ?: ArrayList()
    }

    @Synchronized
    fun count(chatId: String?): Int {
        val list = buckets[chatId]
        return list?.size ?: 0
    }

    @Synchronized
    fun clear(chatId: String?) {
        buckets.remove(chatId)
    }

    @Synchronized
    fun clearAll() {
        buckets.clear()
    }

    fun groupCount(): Int {
        return buckets.size
    }

    @Synchronized
    fun chatIds(): List<String> {
        return ArrayList(buckets.keys)
    }

    @Synchronized
    fun setMaxPerChat(n: Int) {
    }

    @Synchronized
    fun persist(): Boolean {
        if (file == null) {
            return false
        }
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false
            }
            val root = JSONObject()
            root.put("maxPerChat", maxPerChat)
            val chats = JSONObject()
            for (e in buckets) {
                val arr = JSONArray()
                for (m in e.value) {
                    arr.put(m.toJson())
                }
                chats.put(e.key, arr)
            }
            root.put("chats", chats)
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
    fun load() {
        if (file == null || !file.exists()) {
            return
        }
        try {
            FileInputStream(file).use { fis ->
                val data = ByteArray(file.length().toInt())
                val read = fis.read(data)
                if (read <= 0) {
                    return
                }
                val root = JSONObject(String(data, 0, read, StandardCharsets.UTF_8))
                val chats = root.optJSONObject("chats")
                if (chats == null) {
                    return
                }
                val it = chats.keys()
                while (it.hasNext()) {
                    val chatId = it.next() as String
                    val arr = chats.optJSONArray(chatId)
                    if (arr == null) {
                        continue
                    }
                    val list = buckets.computeIfAbsent(chatId) { ArrayList() }
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i)
                        if (m != null) {
                            list.add(ChatMessage.fromJson(m))
                        }
                    }
                    trim(list)
                }
            }
        } catch (e: IOException) {
        } catch (e: JSONException) {
        }
    }

    @Synchronized
    fun reset(deleteFile: Boolean) {
        clearAll()
        if (deleteFile && file != null) {
            file.delete()
        }
    }

    companion object {
        const val DEFAULT_FILE = "leshao_ai/memory.json"
    }
}