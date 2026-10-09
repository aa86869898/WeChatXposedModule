package com.leshao.v3.hook

import com.tencent.mmkv.MMKV
import org.luckypray.dexkit.DexKitCacheBridge

class MmkvCacheStorage : DexKitCacheBridge.Cache {

    private val mmkv = MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE)

    override fun getString(key: String, default: String?): String? {
        val v = mmkv.decodeString(key, null)
        return v ?: default
    }

    override fun putString(key: String, value: String) {
        mmkv.encode(key, value)
        // v955(问题11): 多进程模式下写后 sync, 确保刷新到文件供其它进程读取
        mmkv.sync()
    }

    override fun getStringList(key: String, default: List<String>?): List<String>? {
        val raw = mmkv.decodeString(key, null)
        if (raw == null) return default
        if (raw.isEmpty()) return ArrayList()
        return raw.split(LIST_SEPARATOR)
    }

    override fun putStringList(key: String, value: List<String>) {
        if (value.isEmpty()) {
            mmkv.encode(key, "")
            mmkv.sync()
            return
        }
        val sb = StringBuilder()
        for (i in value.indices) {
            if (i > 0) sb.append(LIST_SEPARATOR)
            sb.append(value[i])
        }
        mmkv.encode(key, sb.toString())
        // v955(问题11): 写后 sync
        mmkv.sync()
    }

    override fun remove(key: String) {
        mmkv.removeValueForKey(key)
        mmkv.sync()
    }

    override fun getAllKeys(): Collection<String> {
        val keys = mmkv.allKeys() ?: return emptyList()
        return keys.toList()
    }

    override fun clearAll() {
        mmkv.clearAll()
        // v955(问题11): 清空后 sync, 避免其它进程仍读到旧缓存
        mmkv.sync()
    }

    companion object {
        private const val MMKV_ID = "dexkit_cache"
        private const val LIST_SEPARATOR = "\u0000"
    }
}