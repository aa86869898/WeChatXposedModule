package com.leshao.v3.wm.utils

import android.content.Context
import android.content.SharedPreferences
import com.leshao.v3.ContextManager
import com.leshao.v3.UnifiedPrefs

/** 偏好存储 — 复刻自微信大师 Prefs，接入现有 ContextManager */
object WmPrefs {
    private var sp: SharedPreferences? = null

    @JvmStatic
    fun init() {
        try {
            val c = ContextManager.getAppContext()
            if (c != null) sp = UnifiedPrefs.get(c, "wm_prefs")
        } catch (ignored: Throwable) {}
    }

    @JvmStatic
    fun get(k: String, d: Boolean): Boolean {
        return sp?.getBoolean(k, d) ?: d
    }

    @JvmStatic
    fun set(k: String, v: Boolean) {
        sp?.edit()?.putBoolean(k, v)?.apply()
    }

    @JvmStatic
    fun getStr(k: String, d: String): String {
        return sp?.getString(k, d) ?: d
    }

    @JvmStatic
    fun setStr(k: String, v: String) {
        sp?.edit()?.putString(k, v)?.apply()
    }

    // 各功能开关
    @JvmStatic fun isQuickReply() = get("quick_reply", true)
    @JvmStatic fun isExportChat() = get("export_chat", true)
    @JvmStatic fun isKeywordAlert() = get("keyword_alert", true)
    @JvmStatic fun isAutoTranslate() = get("auto_translate", false)
    @JvmStatic fun isAutoVoice() = get("auto_voice", true)
    @JvmStatic fun isTTS() = get("tts", false)
    @JvmStatic fun isAntiRevoke() = get("anti_revoke", true)
    @JvmStatic fun isAtRemind() = get("at_remind", true)
    @JvmStatic fun isAutoSaveMedia() = get("auto_save_media", false)
    @JvmStatic fun isPrivateNote() = get("private_note", true)
    @JvmStatic fun isChatStats() = get("chat_stats", true)
    @JvmStatic fun isClearScreen() = get("clear_screen", false)
    @JvmStatic fun isMsgSearch() = get("msg_search", true)
    @JvmStatic fun isTTSCube() = get("tts_cube", true)
    @JvmStatic fun isBatchSend() = get("batch_send", true)
    @JvmStatic fun isLongPressMenu() = get("long_press_menu", true)
    @JvmStatic fun isCardOrder() = get("card_order", false)
    @JvmStatic fun isVoiceOrder() = get("voice_order", false)
    @JvmStatic fun isQuoteEnhance() = get("quote_enhance", true)
    @JvmStatic fun isMergeForward() = get("merge_forward", true)
    @JvmStatic fun isWatermark() = get("watermark", false)
    @JvmStatic fun isGrpExport() = get("grp_export", true)
    @JvmStatic fun isGrpReport() = get("grp_report", true)
    @JvmStatic fun isGrpNotice() = get("grp_notice", true)
    @JvmStatic fun isGrpBroadcast() = get("grp_broadcast", true)
    @JvmStatic fun isGrpInvite() = get("grp_invite", true)
    @JvmStatic fun isGrpKick() = get("grp_kick", true)
    @JvmStatic fun isGrpQuit() = get("grp_quit", true)
    @JvmStatic fun isGrpAll() = get("grp_all", true)

    @JvmStatic fun isCornerMenu() = get("corner_menu", true)

    // ===== 消息长按菜单净化 =====
    @JvmStatic
    fun ensureInit() {
        if (sp == null) init()
    }

    @JvmStatic fun isMsgMenuEnabled() = get("msg_menu_enabled", true)
    @JvmStatic fun setMsgMenuEnabled(v: Boolean) { ensureInit(); set("msg_menu_enabled", v) }
    @JvmStatic fun getMsgMenuHidden(): String { ensureInit(); return getStr("msg_menu_hidden", "") }
    @JvmStatic fun setMsgMenuHidden(v: String) { ensureInit(); setStr("msg_menu_hidden", v) }
    @JvmStatic fun getMsgMenuObserved(): String { ensureInit(); return getStr("msg_menu_observed", "") }
    @JvmStatic fun setMsgMenuObserved(v: String) { ensureInit(); setStr("msg_menu_observed", v) }
    /** v3.0.170：文档逐按钮开关（\n 分隔的按钮名集合，见 MessageMenuHook.BUTTONS）。 */
    @JvmStatic fun getMsgMenuBtnHidden(): String { ensureInit(); return getStr("msg_menu_btn_hidden", "") }
    @JvmStatic fun setMsgMenuBtnHidden(v: String) { ensureInit(); setStr("msg_menu_btn_hidden", v) }
    /** v3.0.170：C 层全局长按熄菜（勾选后长按任何消息都不弹菜单）。 */
    @JvmStatic fun isMsgMenuAllOff() = get("msg_menu_all_off", false)
    @JvmStatic fun setMsgMenuAllOff(v: Boolean) { ensureInit(); set("msg_menu_all_off", v) }

    @JvmStatic
    fun defaultFor(key: String): Boolean {
        return "auto_voice" == key
    }

    @JvmStatic
    fun getQuickReplyTexts(): String {
        return getStr("quick_reply_phrases", "好的|收到|稍等|在路上|马上到")
    }

    @JvmStatic
    fun getKeywords(): String {
        return getStr("keywords", "")
    }

    @JvmStatic
    fun getInt(k: String, d: Int): Int {
        return sp?.getInt(k, d) ?: d
    }

    @JvmStatic
    fun setInt(k: String, v: Int) {
        sp?.edit()?.putInt(k, v)?.apply()
    }
}