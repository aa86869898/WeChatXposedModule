package com.leshao.v3.service

import com.leshao.v3.ContactRepository
import com.leshao.v3.model.ContactCard
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

class NicknameResolver {

    fun resolveDisplayName(wxid: String?): String {
        if (wxid == null || wxid.isEmpty()) return "未知"

        val cached = getCached(wxid)
        if (cached != null) return cached

        // 优先从已加载的 ContactRepository 内存查找(非 DB, 允许同步)
        val c = ContactRepository.findByUsername(wxid)
        if (c != null) {
            val name = c.displayName()
            putCached(wxid, name)
            return name ?: "未知"
        }

        // 群成员/非好友: rcontact 全表查询改为异步, 避免阻塞调用线程(问题21)
        val w = wxid
        if (sPending.add(w)) {
            try {
                ContactRepository.loadAsync {
                    try {
                        val c2 = ContactRepository.findByUsername(w)
                        if (c2 != null) {
                            putCached(w, c2.displayName())
                            return@loadAsync
                        }
                        val anyName = ContactRepository.queryAnyContactName(w)
                        if (anyName != null && anyName.isNotEmpty()) {
                            putCached(w, anyName)
                        }
                    } finally {
                        sPending.remove(w)
                    }
                }
            } catch (ignored: Throwable) {
                sPending.remove(w)
            }
        }

        // 立即返回兜底名(不写缓存, 待异步查到真名后由 putCached 覆盖)
        return fallbackName(wxid)
    }

    companion object {
        /** v955(问题21): 缓存设上限 + LRU 淘汰, 避免无限增长(原 ConcurrentHashMap 无界)。 */
        private const val MAX_CACHE = 512
        private val sLock = Any()

        private val sCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
                return size > MAX_CACHE
            }
        }

        /** 正在异步解析的 wxid, 避免重复排队(问题21)。 */
        private val sPending = ConcurrentHashMap.newKeySet<String>()

        @JvmStatic
        fun init() {
            synchronized(sLock) {
                sCache.clear()
            }
            sPending.clear()
        }

        private fun getCached(key: String): String? {
            synchronized(sLock) {
                return sCache[key]
            }
        }

        private fun putCached(key: String, value: String?) {
            if (key.isEmpty() || value == null || value.isEmpty()) return
            synchronized(sLock) {
                sCache[key] = value
            }
        }

        private fun fallbackName(wxid: String): String {
            if (wxid.isEmpty()) return "未知"
            if (wxid.startsWith("gh_")) return "公众号"
            if (wxid.endsWith("@chatroom")) return "群聊"
            return "好友"
        }
    }
}