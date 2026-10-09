package com.leshao.ai.util

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
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.CopyOnWriteArraySet

class Whitelist {
    private val file: File
    private val ids = CopyOnWriteArraySet<String>()

    constructor(hostDataDir: String) : this(File(hostDataDir, DEFAULT_FILE))

    constructor(file: File) {
        this.file = file
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
                val obj = JSONObject(String(data, 0, read, StandardCharsets.UTF_8))
                val arr = obj.optJSONArray("ids")
                if (arr != null) {
                    ids.clear()
                    for (i in 0 until arr.length()) {
                        val id = arr.optString(i)
                        if (!TextUtils.isEmpty(id)) {
                            ids.add(id)
                        }
                    }
                }
            }
        } catch (e: IOException) {
        } catch (e: JSONException) {
        }
    }

    @Synchronized
    fun save(): Boolean {
        if (file == null) {
            return false
        }
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false
            }
            val obj = JSONObject()
            val arr = JSONArray()
            for (id in ids) {
                arr.put(id)
            }
            obj.put("ids", arr)
            FileOutputStream(file, false).use { out ->
                out.write(obj.toString(2).toByteArray(StandardCharsets.UTF_8))
            }
            return true
        } catch (e: IOException) {
            return false
        } catch (e: JSONException) {
            return false
        }
    }

    @Synchronized
    fun add(id: String?): Boolean {
        if (TextUtils.isEmpty(id)) {
            return false
        }
        return ids.add(id)
    }

    @Synchronized
    fun addAll(list: List<String>?): Int {
        var added = 0
        if (list != null) {
            for (id in list) {
                if (add(id)) {
                    added++
                }
            }
        }
        return added
    }

    @Synchronized
    fun remove(id: String?): Boolean {
        return id != null && ids.remove(id)
    }

    fun contains(id: String?): Boolean {
        return id != null && ids.contains(id)
    }

    @Synchronized
    fun clear() {
        ids.clear()
    }

    fun size(): Int {
        return ids.size
    }

    fun isEmpty(): Boolean {
        return ids.isEmpty()
    }

    fun list(): List<String> {
        return ArrayList(ids)
    }

    fun asSet(): Set<String> {
        return Collections.unmodifiableSet(ids)
    }

    fun groupIds(): List<String> {
        val groups = ArrayList<String>()
        for (id in ids) {
            if (id.endsWith("@chatroom")) {
                groups.add(id)
            }
        }
        return groups
    }

    fun contactIds(): List<String> {
        val contacts = ArrayList<String>()
        for (id in ids) {
            if (!id.endsWith("@chatroom")) {
                contacts.add(id)
            }
        }
        return contacts
    }

    fun sorted(): List<String> {
        val list = ArrayList(LinkedHashSet(ids))
        Collections.sort(list)
        return list
    }

    companion object {
        const val DEFAULT_FILE = "leshao_ai/whitelist.json"
    }
}