package com.leshao.ai.knowledge

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
import java.util.concurrent.CopyOnWriteArrayList

class KnowledgeBase {
    private val file: File
    private val items = CopyOnWriteArrayList<KnowledgeItem>()

    constructor(hostDataDir: String) : this(File(hostDataDir, DEFAULT_FILE))

    constructor(file: File) {
        this.file = file
    }

    class KnowledgeItem(
        @JvmField val term: String,
        @JvmField val text: String?
    )

    @Synchronized
    fun add(term: String?, text: String?): Boolean {
        if (TextUtils.isEmpty(term)) {
            return false
        }
        var t = text
        if (!TextUtils.isEmpty(t)) {
            t = t!!.trim()
        }
        items.removeIf { it.term == term }
        items.add(KnowledgeItem(term!!, t))
        return true
    }

    @Synchronized
    fun add(item: KnowledgeItem?): Boolean {
        return item != null && add(item.term, item.text)
    }

    @Synchronized
    fun remove(term: String?): Boolean {
        return items.removeIf { it.term == term }
    }

    fun get(term: String?): KnowledgeItem? {
        for (it in items) {
            if (it.term == term) {
                return it
            }
        }
        return null
    }

    fun search(query: String?): List<KnowledgeItem> {
        val result = ArrayList<KnowledgeItem>()
        if (TextUtils.isEmpty(query)) {
            return result
        }
        val q = query!!.lowercase()
        for (it in items) {
            val hit = (it.term?.lowercase()?.contains(q) == true)
                    || (it.text != null && it.text.lowercase().contains(q))
            if (hit) {
                result.add(it)
            }
        }
        return result
    }

    fun retrieve(query: String?, k: Int): List<KnowledgeItem> {
        if (TextUtils.isEmpty(query) || items.isEmpty()) {
            return ArrayList()
        }
        val q = query!!.lowercase()
        val scored = ArrayList<KnowledgeItem>()
        for (it in items) {
            if (score(it, q) > 0) {
                scored.add(it)
            }
        }
        scored.sortWith { a, b -> Integer.compare(score(b, q), score(a, q)) }
        val limit = Math.min(k, scored.size)
        return ArrayList(scored.subList(0, limit))
    }

    private fun score(it: KnowledgeItem, qLower: String): Int {
        var s = 0
        val t = it.term
        if (t != null) {
            val tl = t.lowercase()
            if (tl == qLower) {
                s += 100
            } else if (qLower.contains(tl)) {
                s += 60
            } else if (tl.contains(qLower)) {
                s += 40
            }
        }
        if (it.text != null && it.text.lowercase().contains(qLower)) {
            s += 30
        }
        return s
    }

    fun retrieveAsContext(query: String?, k: Int): String {
        val list = retrieve(query, k)
        if (list.isEmpty()) {
            return ""
        }
        val sb = StringBuilder()
        sb.append("[知识库参考]\n")
        for (it in list) {
            sb.append("• ").append(it.term)
            if (!TextUtils.isEmpty(it.text)) {
                sb.append(": ").append(it.text)
            }
            sb.append('\n')
            if (sb.length > MAX_CONTEXT_LENGTH) {
                sb.setLength(MAX_CONTEXT_LENGTH)
                sb.append("…")
                break
            }
        }
        return sb.toString()
    }

    fun size(): Int {
        return items.size
    }

    fun isEmpty(): Boolean {
        return items.isEmpty()
    }

    fun all(): List<KnowledgeItem> {
        return ArrayList(items)
    }

    @Synchronized
    fun clear() {
        items.clear()
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
            val arr = JSONArray()
            for (it in items) {
                val obj = JSONObject()
                obj.put("term", it.term)
                obj.put("text", it.text)
                arr.put(obj)
            }
            val root = JSONObject()
            root.put("items", arr)
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
                val arr = root.optJSONArray("items")
                if (arr == null) {
                    return
                }
                val loaded = ArrayList<KnowledgeItem>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i)
                    if (obj != null) {
                        loaded.add(KnowledgeItem(
                                obj.optString("term", ""),
                                obj.optString("text", "")
                        ))
                    }
                }
                items.clear()
                items.addAll(loaded)
            }
        } catch (e: IOException) {
        } catch (e: JSONException) {
        }
    }

    companion object {
        const val DEFAULT_FILE = "leshao_ai/knowledge.json"
        private const val MAX_CONTEXT_LENGTH = 2000
    }
}