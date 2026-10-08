package com.leshao.v3.music

import android.content.SharedPreferences
import com.leshao.v3.ContextManager
import java.util.Collections
import java.util.LinkedHashSet

/**
 * 「在线音乐 / 点歌」功能偏好。
 *
 * 点歌指令仅在白名单会话内生效;白名单为空时不触发。误报时长默认 60 秒,可自定义 1-60 秒
 * (对应微信语音 60 秒上限)。
 */
object OnlineMusicPrefs {

    const val K_ENABLED = "ls_kge_enabled"
    const val K_WHITELIST = "ls_kge_whitelist"
    const val K_ALIASES = "ls_kge_aliases"
    const val K_FALSE_DUR = "ls_kge_false_dur_sec"
    const val K_QUALITY = "ls_kge_quality"
    const val K_NOTICE = "ls_kge_notice"
    const val K_NOTICE_TEXT = "ls_kge_notice_text"
    const val K_AUTO_DOWNLOAD = "ls_om_auto_download"
    const val K_FAVORITES = "ls_om_favorites"
    const val K_SEARCH_HISTORY = "ls_om_search_history"
    // v1105: 播放状态持久化
    const val K_PLAY_QUEUE = "ls_om_play_queue"
    const val K_PLAY_INDEX = "ls_om_play_index"
    const val K_PLAY_POS = "ls_om_play_pos"
    const val K_PLAY_MODE = "ls_om_play_mode"
    const val K_PLAY_PAUSED = "ls_om_play_paused"
    const val K_AUTO_RANDOM = "ls_om_auto_random"

    /** 搜索历史最多保留条数。 */
    private const val MAX_SEARCH_HISTORY = 20
    /** 搜索历史内部存储分隔符（SOH，关键词不会包含）。 */
    private const val HIST_SEP = "\u0001"

    const val DEFAULT_ALIASES = "点歌,点唱,来一首,我想听"
    /** 点歌处理前的提示语默认文案; 支持 {song} 占位符代表歌名。 */
    const val DEFAULT_NOTICE_TEXT = "正在点歌…"

    private fun sp(): SharedPreferences? {
        return ContextManager.getPrefs()
    }

    // ---------- 点歌 ----------

    @JvmStatic
    fun enabled(): Boolean {
        return try {
            sp()?.getBoolean(K_ENABLED, false) ?: false
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic
    fun setEnabled(v: Boolean) {
        try {
            sp()?.edit()?.putBoolean(K_ENABLED, v)?.apply()
        } catch (ignored: Throwable) {}
    }

    @JvmStatic
    fun notice(): Boolean {
        return try {
            sp()?.getBoolean(K_NOTICE, true) ?: true
        } catch (t: Throwable) {
            true
        }
    }

    @JvmStatic
    fun setNotice(v: Boolean) {
        try {
            sp()?.edit()?.putBoolean(K_NOTICE, v)?.apply()
        } catch (ignored: Throwable) {}
    }

    /** 点歌提示语内容,支持 {song} 占位符; 默认「正在点歌…」。 */
    @JvmStatic
    fun noticeText(): String {
        return try {
            val s = sp()?.getString(K_NOTICE_TEXT, DEFAULT_NOTICE_TEXT)
            if (s == null || s.trim().isEmpty()) DEFAULT_NOTICE_TEXT else s
        } catch (t: Throwable) {
            DEFAULT_NOTICE_TEXT
        }
    }

    @JvmStatic
    fun setNoticeText(v: String?) {
        try {
            val s = if (v == null || v.trim().isEmpty()) DEFAULT_NOTICE_TEXT else v
            sp()?.edit()?.putString(K_NOTICE_TEXT, s)?.apply()
        } catch (ignored: Throwable) {}
    }

    @JvmStatic
    fun autoDownload(): Boolean {
        return try {
            sp()?.getBoolean(K_AUTO_DOWNLOAD, false) ?: false
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic
    fun setAutoDownload(v: Boolean) {
        try {
            sp()?.edit()?.putBoolean(K_AUTO_DOWNLOAD, v)?.apply()
        } catch (ignored: Throwable) {}
    }

    /** 点歌/试听音质档:auto / lossless / exhigh / standard,默认 auto(优先最高,失败逐级降档)。 */
    @JvmStatic
    fun quality(): String {
        return try {
            val q = sp()?.getString(K_QUALITY, KuwoMusicApi.Q_AUTO)
            if (q == null || q.isEmpty()) KuwoMusicApi.Q_AUTO else q
        } catch (t: Throwable) {
            KuwoMusicApi.Q_AUTO
        }
    }

    @JvmStatic
    fun setQuality(q: String?) {
        try {
            sp()?.edit()?.putString(K_QUALITY, if (q == null) KuwoMusicApi.Q_AUTO else q)?.apply()
        } catch (ignored: Throwable) {}
    }

    // ---------- 收藏 ----------

    /** 收藏的歌曲 id 集合。 */
    @JvmStatic
    fun favorites(): Set<String> {
        val s = try {
            sp()?.getString(K_FAVORITES, "") ?: ""
        } catch (t: Throwable) {
            ""
        }
        return split(s, false)
    }

    @JvmStatic
    fun isFavorite(songId: String?): Boolean {
        return songId != null && songId.isNotEmpty() && favorites().contains(songId)
    }

    /** 切换收藏状态, 返回切换后是否为已收藏。 */
    @JvmStatic
    fun toggleFavorite(songId: String?): Boolean {
        if (songId == null || songId.isEmpty()) return false
        val f = LinkedHashSet(favorites())
        val added: Boolean
        if (f.contains(songId)) {
            f.remove(songId)
            added = false
        } else {
            f.add(songId)
            added = true
        }
        try {
            sp()?.edit()?.putString(K_FAVORITES, f.joinToString(","))?.apply()
        } catch (ignored: Throwable) {}
        return added
    }

    // ---------- 搜索历史 ----------

    /** 搜索历史（最近在前）。 */
    @JvmStatic
    fun searchHistory(): MutableList<String> {
        val s = try {
            sp()?.getString(K_SEARCH_HISTORY, "") ?: ""
        } catch (t: Throwable) {
            ""
        }
        val out = ArrayList<String>()
        if (s.isEmpty()) return out
        for (p in s.split(HIST_SEP)) {
            val t = p.trim()
            if (t.isNotEmpty()) out.add(t)
        }
        return out
    }

    /** 记录一次搜索（去重后置顶，最多 [MAX_SEARCH_HISTORY] 条）。 */
    @JvmStatic
    fun addSearchHistory(keyword: String?) {
        if (keyword == null) return
        val k = keyword.trim()
        if (k.isEmpty()) return
        val list = searchHistory()
        list.remove(k)
        list.add(0, k)
        while (list.size > MAX_SEARCH_HISTORY) list.removeAt(list.size - 1)
        try {
            sp()?.edit()?.putString(K_SEARCH_HISTORY, list.joinToString(HIST_SEP))?.apply()
        } catch (ignored: Throwable) {}
    }

    @JvmStatic
    fun clearSearchHistory() {
        try {
            sp()?.edit()?.remove(K_SEARCH_HISTORY)?.apply()
        } catch (ignored: Throwable) {}
    }

    /** 误报时长(秒),范围 1-60,默认 60。 */
    @JvmStatic
    fun falseDurSec(): Int {
        var v: Int
        try {
            v = sp()?.getInt(K_FALSE_DUR, 60) ?: 60
        } catch (t: Throwable) {
            v = 60
        }
        if (v < 1) v = 1
        if (v > 60) v = 60
        return v
    }

    @JvmStatic
    fun setFalseDurSec(sec: Int) {
        var sec = sec
        if (sec < 1) sec = 1
        if (sec > 60) sec = 60
        try {
            sp()?.edit()?.putInt(K_FALSE_DUR, sec)?.apply()
        } catch (ignored: Throwable) {}
    }

    /** 点歌指令别名前缀集合。 */
    @JvmStatic
    fun aliases(): Set<String> {
        val s = try {
            sp()?.getString(K_ALIASES, DEFAULT_ALIASES) ?: DEFAULT_ALIASES
        } catch (t: Throwable) {
            DEFAULT_ALIASES
        }
        return split(s, true)
    }

    @JvmStatic
    fun setAliases(csv: String?) {
        try {
            val v = if (csv == null || csv.trim().isEmpty()) DEFAULT_ALIASES else csv
            sp()?.edit()?.putString(K_ALIASES, v)?.apply()
        } catch (ignored: Throwable) {}
    }

    /** 点歌白名单会话(好友/群)。 */
    @JvmStatic
    fun whitelist(): Set<String> {
        val s = try {
            sp()?.getString(K_WHITELIST, "") ?: ""
        } catch (t: Throwable) {
            ""
        }
        return split(s, false)
    }

    @JvmStatic
    fun setWhitelist(csv: String?) {
        try {
            sp()?.edit()?.putString(K_WHITELIST, if (csv == null) "" else csv)?.apply()
        } catch (ignored: Throwable) {}
    }

    @JvmStatic
    fun inWhitelist(talker: String?): Boolean {
        if (talker == null || talker.isEmpty()) return false
        val wl = whitelist()
        // 白名单为空时不触发点歌(符合「仅白名单会话生效」预期)
        return !wl.isEmpty() && wl.contains(talker)
    }

    // ---------- 播放状态持久化 ----------

    private const val SONG_SEP = "\u0002"
    private const val FIELD_SEP = "\u0001"

    /** 自动随机播放开关,默认开启:进入在线音乐即随机播放一首。 */
    @JvmStatic
    fun autoRandom(): Boolean {
        return try {
            sp()?.getBoolean(K_AUTO_RANDOM, true) ?: true
        } catch (t: Throwable) {
            true
        }
    }

    @JvmStatic
    fun setAutoRandom(v: Boolean) {
        try {
            sp()?.edit()?.putBoolean(K_AUTO_RANDOM, v)?.apply()
        } catch (ignored: Throwable) {}
    }

    private fun encField(s: String?): String {
        return if (s == null) "" else s.replace(FIELD_SEP, " ").replace(SONG_SEP, " ")
    }

    private fun encodeSong(s: KuwoMusicApi.Song?): String {
        if (s == null) return ""
        return encField(s.id) + FIELD_SEP + encField(s.title) + FIELD_SEP + encField(s.artist) +
            FIELD_SEP + encField(s.album) + FIELD_SEP + encField(s.artwork) +
            FIELD_SEP + encField(s.formats) + FIELD_SEP + s.durationMs
    }

    private fun decodeSong(raw: String?): KuwoMusicApi.Song? {
        if (raw == null || raw.isEmpty()) return null
        val p = raw.split(FIELD_SEP, limit = -1).toTypedArray()
        if (p.size < 4) return null
        val s = KuwoMusicApi.Song()
        s.id = p[0]
        s.title = p[1]
        s.artist = p[2]
        s.album = p[3]
        if (p.size > 4) s.artwork = p[4]
        if (p.size > 5) s.formats = p[5]
        if (p.size > 6) {
            try {
                s.durationMs = p[6].toInt()
            } catch (ignored: Throwable) {}
        }
        return s
    }

    /** 保存当前播放状态(队列/下标/进度/模式/暂停态)。 */
    @JvmStatic
    fun savePlayback(queue: List<KuwoMusicApi.Song>?, index: Int, positionMs: Int, mode: Int, paused: Boolean) {
        try {
            val sb = StringBuilder()
            if (queue != null) {
                for (s in queue) {
                    if (s == null) continue
                    if (sb.length > 0) sb.append(SONG_SEP)
                    sb.append(encodeSong(s))
                }
            }
            sp()?.edit()?.putString(K_PLAY_QUEUE, sb.toString())
                ?.putInt(K_PLAY_INDEX, index)
                ?.putInt(K_PLAY_POS, Math.max(0, positionMs))
                ?.putInt(K_PLAY_MODE, if (mode <= 0) 1 else mode)
                ?.putBoolean(K_PLAY_PAUSED, paused)
                ?.apply()
        } catch (ignored: Throwable) {}
    }

    @JvmStatic
    fun playbackQueue(): MutableList<KuwoMusicApi.Song> {
        val out = ArrayList<KuwoMusicApi.Song>()
        try {
            val raw = sp()?.getString(K_PLAY_QUEUE, "") ?: ""
            if (raw.isEmpty()) return out
            for (part in raw.split(SONG_SEP)) {
                val s = decodeSong(part)
                if (s != null && s.id != null && s.id.isNotEmpty()) out.add(s)
            }
        } catch (ignored: Throwable) {}
        return out
    }

    @JvmStatic
    fun playbackIndex(): Int {
        return try {
            sp()?.getInt(K_PLAY_INDEX, -1) ?: -1
        } catch (t: Throwable) {
            -1
        }
    }

    @JvmStatic
    fun playbackPosition(): Int {
        return try {
            sp()?.getInt(K_PLAY_POS, 0) ?: 0
        } catch (t: Throwable) {
            0
        }
    }

    @JvmStatic
    fun playbackMode(): Int {
        return try {
            sp()?.getInt(K_PLAY_MODE, 1) ?: 1
        } catch (t: Throwable) {
            1
        }
    }

    @JvmStatic
    fun playbackPaused(): Boolean {
        return try {
            sp()?.getBoolean(K_PLAY_PAUSED, true) ?: true
        } catch (t: Throwable) {
            true
        }
    }

    // ---------- 工具 ----------

    private fun split(csv: String?, lower: Boolean): Set<String> {
        if (csv == null || csv.trim().isEmpty()) return Collections.emptySet()
        val out = LinkedHashSet<String>()
        for (p in csv.split("[,，、\\s]+".toRegex())) {
            val t = p.trim()
            if (t.isEmpty()) continue
            out.add(if (lower) t else t)
        }
        return out
    }
}