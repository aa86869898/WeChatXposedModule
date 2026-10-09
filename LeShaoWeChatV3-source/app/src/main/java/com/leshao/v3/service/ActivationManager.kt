package com.leshao.v3.service

import android.content.SharedPreferences
import com.leshao.v3.ContextManager
import com.leshao.v3.LogWriter
import com.leshao.v3.model.ModuleConfig

/**
 * 模块黑名单与管理员管理
 * 管理员可查看和编辑模块黑名单
 */
object ActivationManager {

    private const val TAG = "ActivationManager"

    @JvmField
    val ADMIN_WXIDS = arrayOf(
        "wxid_9ohhf82mrlgc22",
        "wxid_qf3ok46p08v922"
    )

    @JvmStatic
    fun isAdmin(wxid: String?): Boolean {
        if (wxid == null) return false
        for (a in ADMIN_WXIDS) {
            if (wxid == a) return true
        }
        return false
    }

    private const val PREF_KEY_BLACKLIST = "ls_module_blacklist"

    @JvmStatic
    fun getBlacklist(): MutableSet<String> {
        val set = HashSet<String>()
        val prefs = ContextManager.getPrefs()
        if (prefs == null) return set
        val raw = prefs.getString(PREF_KEY_BLACKLIST, "")
        if (raw == null || raw.isEmpty()) return set
        for (s in raw.split("\\|")) {
            if (s.isNotBlank()) set.add(s.trim())
        }
        return set
    }

    @JvmStatic
    fun isBlacklisted(wxid: String?): Boolean {
        if (wxid == null || wxid.isEmpty()) return false
        return getBlacklist().contains(wxid)
    }

    @JvmStatic
    fun isCurrentUserBlocked(): Boolean {
        val wxid = ModuleConfig.getCurrentWxid()
        return wxid != null && wxid.isNotEmpty() && !isAdmin(wxid) && isBlacklisted(wxid)
    }

    @JvmStatic
    fun addBlacklist(wxid: String?) {
        if (wxid == null || wxid.trim().isEmpty()) return
        val set = getBlacklist()
        if (set.contains(wxid)) return
        set.add(wxid)
        saveBlacklist(set)
        LogWriter.log(TAG, "blacklist add: " + wxid)
    }

    @JvmStatic
    fun removeBlacklist(wxid: String?) {
        if (wxid == null) return
        val set = getBlacklist()
        if (!set.remove(wxid)) return
        saveBlacklist(set)
        LogWriter.log(TAG, "blacklist remove: " + wxid)
    }

    private fun saveBlacklist(set: MutableSet<String>) {
        val prefs = ContextManager.getPrefs()
        if (prefs == null) return
        val sb = StringBuilder()
        for (s in set) {
            if (sb.length > 0) sb.append('|')
            sb.append(s)
        }
        prefs.edit().putString(PREF_KEY_BLACKLIST, sb.toString()).apply()
    }
}