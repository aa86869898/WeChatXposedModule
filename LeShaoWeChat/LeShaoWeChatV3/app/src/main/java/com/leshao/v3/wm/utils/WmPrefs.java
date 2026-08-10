package com.leshao.v3.wm.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.leshao.v3.ContextManager;

/** 偏好存储 — 复刻自微信大师 Prefs，接入现有 ContextManager */
public class WmPrefs {
    private static SharedPreferences sp;

    public static void init() {
        try {
            Context c = ContextManager.getAppContext();
            if (c != null) sp = c.getSharedPreferences("wm_prefs", 0);
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
    public static boolean isScheduledMsg() { return get("scheduled_msg", true); }
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
    public static boolean isGroupList() { return get("group_list", true); }
    public static boolean isQuickScan() { return get("quick_scan", true); }
    public static boolean isScheduledMoment() { return get("scheduled_moment", false); }
    public static boolean isFileHelper() { return get("file_helper", true); }
    public static boolean isLongPressMenu() { return get("long_press_menu", true); }
    public static boolean isQuoteEnhance() { return get("quote_enhance", true); }
    public static boolean isMergeForward() { return get("merge_forward", true); }
    public static boolean isWatermark() { return get("watermark", false); }
    public static boolean isGrpExport() { return get("grp_export", true); }
    public static boolean isGrpWxid() { return get("grp_wxid", true); }
    public static boolean isGrpReport() { return get("grp_report", true); }
    public static boolean isGrpNotice() { return get("grp_notice", true); }
    public static boolean isGrpBroadcast() { return get("grp_broadcast", true); }
    public static boolean isGrpInvite() { return get("grp_invite", true); }
    public static boolean isGrpKick() { return get("grp_kick", true); }
    public static boolean isGrpQuit() { return get("grp_quit", true); }
    public static boolean isGrpAll() { return get("grp_all", true); }

    public static boolean isCornerMenu() { return get("corner_menu", true); }
    public static boolean isInputButtons() { return get("input_buttons", true); }

    public static boolean defaultFor(String key) {
        return "auto_voice".equals(key);
    }

    public static String getQuickReplyTexts() {
        return getStr("quick_reply_texts", "好的,收到|稍等,马上|在忙,晚点回复|OK|👍");
    }

    public static String getKeywords() { return getStr("keywords", ""); }

    public static int getInt(String k, int d) {
        return sp != null ? sp.getInt(k, d) : d;
    }

    public static void setInt(String k, int v) {
        if (sp != null) sp.edit().putInt(k, v).apply();
    }
}
