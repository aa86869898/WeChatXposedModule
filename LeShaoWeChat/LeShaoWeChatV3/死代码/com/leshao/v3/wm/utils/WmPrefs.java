package com.leshao.v3.wm.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.leshao.v3.ContextManager;
import com.leshao.v3.UnifiedPrefs;

/** 偏好存储 — 复刻自微信大师 Prefs，接入现有 ContextManager */
public class WmPrefs {
    private static SharedPreferences sp;

    public static void init() {
        try {
            Context c = ContextManager.getAppContext();
            if (c != null) sp = UnifiedPrefs.get(c, "wm_prefs");
        } catch (Throwable ignored) {}
    }

    public static boolean get(String k, boolean d) {
        return sp != null ? sp.getBoolean(k, d) : d;
    }

    public static void set(String k, boolean v) {
        if (sp != null) sp.edit().putBoolean(k, v).apply();
    }

    public static String getStr(String k, String d) {
        return sp != null ? sp.getString(k, d) : d;
    }

    public static void setStr(String k, String v) {
        if (sp != null) sp.edit().putString(k, v).apply();
    }

    // 各功能开关
    public static boolean isQuickReply() { return get("quick_reply", true); }
    public static boolean isExportChat() { return get("export_chat", true); }
    public static boolean isKeywordAlert() { return get("keyword_alert", true); }
    public static boolean isAutoTranslate() { return get("auto_translate", false); }
    public static boolean isAutoVoice() { return get("auto_voice", true); }
    public static boolean isTTS() { return get("tts", false); }
    public static boolean isAntiRevoke() { return get("anti_revoke", true); }
    public static boolean isAtRemind() { return get("at_remind", true); }
    public static boolean isAutoSaveMedia() { return get("auto_save_media", false); }
    public static boolean isPrivateNote() { return get("private_note", true); }
    public static boolean isChatStats() { return get("chat_stats", true); }
    public static boolean isClearScreen() { return get("clear_screen", false); }
    public static boolean isMsgSearch() { return get("msg_search", true); }
    public static boolean isTTSCube() { return get("tts_cube", true); }
    public static boolean isBatchSend() { return get("batch_send", true); }
    public static boolean isLongPressMenu() { return get("long_press_menu", true); }
    public static boolean isCardOrder() { return get("card_order", false); }
    public static boolean isVoiceOrder() { return get("voice_order", false); }
    public static boolean isQuoteEnhance() { return get("quote_enhance", true); }
    public static boolean isMergeForward() { return get("merge_forward", true); }
    public static boolean isWatermark() { return get("watermark", false); }
    public static boolean isGrpExport() { return get("grp_export", true); }
    public static boolean isGrpReport() { return get("grp_report", true); }
    public static boolean isGrpNotice() { return get("grp_notice", true); }
    public static boolean isGrpBroadcast() { return get("grp_broadcast", true); }
    public static boolean isGrpInvite() { return get("grp_invite", true); }
    public static boolean isGrpKick() { return get("grp_kick", true); }
    public static boolean isGrpQuit() { return get("grp_quit", true); }
    public static boolean isGrpAll() { return get("grp_all", true); }

    public static boolean isCornerMenu() { return get("corner_menu", true); }

    // ===== 消息长按菜单净化 =====
    public static void ensureInit() {
        if (sp == null) init();
    }

    public static boolean isMsgMenuEnabled() { return get("msg_menu_enabled", true); }
    public static void setMsgMenuEnabled(boolean v) { ensureInit(); set("msg_menu_enabled", v); }
    public static String getMsgMenuHidden() { ensureInit(); return getStr("msg_menu_hidden", ""); }
    public static void setMsgMenuHidden(String v) { ensureInit(); setStr("msg_menu_hidden", v); }
    public static String getMsgMenuObserved() { ensureInit(); return getStr("msg_menu_observed", ""); }
    public static void setMsgMenuObserved(String v) { ensureInit(); setStr("msg_menu_observed", v); }
    /** v3.0.170：文档逐按钮开关（\n 分隔的按钮名集合，见 MessageMenuHook.BUTTONS）。 */
    public static String getMsgMenuBtnHidden() { ensureInit(); return getStr("msg_menu_btn_hidden", ""); }
    public static void setMsgMenuBtnHidden(String v) { ensureInit(); setStr("msg_menu_btn_hidden", v); }
    /** v3.0.170：C 层全局长按熄菜（勾选后长按任何消息都不弹菜单）。 */
    public static boolean isMsgMenuAllOff() { return get("msg_menu_all_off", false); }
    public static void setMsgMenuAllOff(boolean v) { ensureInit(); set("msg_menu_all_off", v); }

    public static boolean defaultFor(String key) {
        return "auto_voice".equals(key);
    }

    public static String getQuickReplyTexts() {
        return getStr("quick_reply_phrases", "好的|收到|稍等|在路上|马上到");
    }

    public static String getKeywords() { return getStr("keywords", ""); }

    public static int getInt(String k, int d) {
        return sp != null ? sp.getInt(k, d) : d;
    }

    public static void setInt(String k, int v) {
        if (sp != null) sp.edit().putInt(k, v).apply();
    }
}
