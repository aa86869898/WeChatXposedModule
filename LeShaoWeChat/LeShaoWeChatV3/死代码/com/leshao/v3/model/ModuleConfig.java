package com.leshao.v3.model;

import android.content.Context;
import android.content.SharedPreferences;

import com.leshao.v3.LogWriter;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class ModuleConfig {

    public boolean masterSwitch = true;

    // TTS 引擎
    public String peiyinApiKey = "", peiyinVoiceId = "";
    public String wusoundVoiceId = "", wusoundPromptId = "default";

    // 播报类型
    public boolean announceText = true, announceImage = true, announceVideo = true;
    public boolean announceCard = true;
    public boolean announceFile = true, announceLocation = true, announceSticker = false;
    public boolean announceCall = true, announceNickname = true, announceGroup = false;
    public boolean announceQuote = true;
    public boolean announceAt = true;
    public boolean announceMiniProgram = true;
    public boolean announceVideoChannel = true;
    public boolean announceChatHistory = true;

    public String customAnnounceFormat = "{sender}: {content}";
    public long announceIntervalMs = 0;
    public int textCutoffLen = 150;
    public boolean textTruncateEnabled = true;

    public Set<String> announceWhitelist = new HashSet<>();
    public Set<String> announceBlacklist = new HashSet<>();
    // 白名单严格模式: 白名单为空时不播报任何消息(默认开启)
    public boolean whitelistStrict = true;

    // 免打扰
    public boolean quietEnabled = false;
    public String quietStart = "23:00", quietEnd = "07:00";

    // 群管
    public boolean autoAcceptFriend = false;
    public String autoAcceptFriendMsg = "你好呀，很高兴认识你!";
    public Set<String> sensitiveWords = new HashSet<>();
    public boolean sensitiveFilterEnabled = false;

    // 关键词回复
    public boolean keywordReplyEnabled = false;
    public Map<String, Map<String, String>> keywordReplyMap = new HashMap<>();

    // AI
    public boolean aiToolboxEnabled = false, imageGenEnabled = false, videoGenEnabled = false;
    public String arkApiKey = "";
    public String arkImageSize = "2K", arkImageFormat = "png";
    public String arkVideoResolution = "720p";
    public int arkVideoDuration = 8;

    // DeepSeek
    public boolean deepseekEnabled = false;
    public boolean deepseekSmartReply = false, deepseekTranslate = false;
    public boolean deepseekSummary = false, deepseekAtReply = false;
    public String deepseekModel = "deepseek-chat";
    public String deepseekPersona = "";

    // 安全
    public boolean antiRecall = false;
    public boolean antiDetection = true;          // 反Xposed/LSPosed检测

    // ============ WeChatPlus 增强功能 (24项) ============
    // 聊天增强
    public boolean typingIndicatorEnabled = true;
    public boolean chatFooterEnhanceEnabled = true;
    public boolean chatUICustomEnabled = true;
    public boolean batchMessageEnabled = true;
    public boolean autoRemarkEnabled = true;
    public boolean searchEnhanceEnabled = true;
    public boolean autoReplyEnabled = true;

    // 朋友圈
    public boolean snsFeaturesEnabled = true;

    // 隐私安全
    public boolean privacyFeaturesEnabled = true;
    public boolean loginMonitorEnabled = true;
    public boolean hideContactFieldsEnabled = true;

    // 联系人与群管
    public boolean deleteDetectEnabled = true;

    // 设置/其他
    public boolean stickyEnhanceEnabled = true;
    public boolean unreadBadgeEnabled = true;
    public boolean tabCustomEnabled = true;
    public boolean callFeaturesEnabled = true;
    public boolean shakeCustomEnabled = true;

    // 语音转发
    public boolean voiceForwardEnabled = true;

    // 禁止微信热更新(版本升级/Tinker热补丁)
    public boolean blockWechatUpdate = true;

    // 缓存目录
    public String cacheDir, mediaDir;

    // 关键词规则列表
    public List<KeywordRule> keywordRules = new ArrayList<>();

    // ===== 离线笑话/金句库 (V2.1 原版) =====
    public static final String[] JOKE_LIB = {
        "老师：小明，你来说说'有备无患'是什么意思？\n小明：就是形容一个人备胎很多，不用担心找不到对象。\n老师：滚出去！",
        "今天去面试。面试官问：你最大的缺点是什么？\n我说：我说话太直。\n面试官说：我觉得这不算缺点啊。\n我说：我觉不觉得不重要，你怎么觉得才重要，笨蛋。",
        "一只北极熊孤单地呆在冰上发呆，实在无聊就开始拔自己的毛玩。一根、两根、三根……最后拔得一根不剩，他突然说：好冷啊！",
        "问：什么样的速度最快？\n答：曹操。说曹操曹操就到。",
        "程序员最讨厌的四件事：写注释、写文档、别人不写注释、别人不写文档。",
        "语文课上，老师问：'谁能用果然造句？' 小明站起来说：'我先吃水蜜桃，然后吃苹果。' 老师：'这跟果然有什么关系？' 小明：'水果然后，果然！'",
        "医生：你的X光片显示你骨头断了。患者：那怎么办？医生：我已经用PS帮你修好了。",
        "小时候妈妈告诉我：不要跟陌生人说话。长大后发现：不跟陌生人说话根本没法工作。"
    };

    public static final String[] QUOTE_LIB = {
        "生活不是等待暴风雨过去，而是学会在雨中翩翩起舞。",
        "你今天的努力，是幸运的伏笔。当下的付出，是明日的花开。",
        "即使前方黑暗无边，只要心中有光，便能照亮前行的路。",
        "不要因为走得太远，而忘记为什么出发。",
        "人生就像骑自行车，要想保持平衡，就必须不断前进。",
        "星光不问赶路人，时光不负有心人。",
        "你现在的气质里，藏着你走过的路，读过的书和爱过的人。",
        "与其用泪水悔恨今天，不如用汗水拼搏今天。",
        "没有比脚更长的路，没有比人更高的山。",
        "世界上只有一种英雄主义，那就是在认清生活真相之后，依然热爱生活。",
        "每一个不曾起舞的日子，都是对生命的辜负。",
        "愿你有前进一寸的勇气，亦有后退一尺的从容。"
    };

    public ModuleConfig() {}

    private static String sCurrentWxid = null;

    public static void initWxid(Context ctx) {
        sCurrentWxid = null;
        String[] prefNames = {
            "system_config_prefs", "com.tencent.mm_preferences",
            "notify_sync_pref", "auth_info_key_prefs",
            "app_brand_global_sp", "exdevice_pref",
        };
        String[] keyNames = {
            "login_weixin_username", "login_user_name", "last_login_username",
            "auth_uin", "username", "uin", "_auth_uin",
        };
        for (String pn : prefNames) {
            try {
                java.util.Map<String, ?> all = ctx.getSharedPreferences(pn, 0).getAll();
                for (String key : keyNames) {
                    Object v = all.get(key);
                    if (v != null && v.toString().startsWith("wxid_")) {
                        sCurrentWxid = v.toString();
                        LogWriter.log("ModuleConfig", "initWxid: " + sCurrentWxid);
                        return;
                    }
                }
                for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
                    Object v = entry.getValue();
                    if (v != null) {
                        String val = v.toString();
                        if (val.startsWith("wxid_") && !val.contains("@")) {
                            sCurrentWxid = val;
                            LogWriter.log("ModuleConfig", "initWxid scan: " + sCurrentWxid);
                            return;
                        }
                    }
                }
            } catch (Throwable e) {}
        }
    }

    public static String getCurrentWxid() { return sCurrentWxid; }

    /**
     * 清理已移除功能遗留的配置键。
     *
     * <p>历史版本曾实现「会话消息预览隐私」(ConvPrivacy, 挂 notification.m0.a 改写通知内容为
     * "[新消息]")、「通知增强」(NotifyCustom) 与「红包响铃/震动」(RedPacketAlert)。这些功能已
     * 彻底移除, 其开关键若残留在存储中会误导排查, 故在加载配置时一次性清除。</p>
     */
    private static void purgeLegacyKeys(SharedPreferences prefs) {
        String[] legacy = {
            "ls_wp_convprivacy", "ls_wp_notify", "ls_wp_redalert",
            "conv_privacy_level", "conv_hide_notification", "conv_hide_convlist",
            "conv_privacy_list", "notify_priority_mode", "notify_avatar",
            "notify_important_contacts", "quick_reply_phrases",
            "rp_alert_vibrate", "rp_alert_ring",
        };
        try {
            SharedPreferences.Editor e = null;
            for (String k : legacy) {
                if (prefs.contains(k)) {
                    if (e == null) e = prefs.edit();
                    e.remove(k);
                }
            }
            if (e != null) e.apply();
        } catch (Throwable ignored) {}
    }

    public static ModuleConfig load(SharedPreferences prefs) {
        ModuleConfig cfg = new ModuleConfig();
        if (prefs == null) return cfg;
        purgeLegacyKeys(prefs);

        cfg.masterSwitch = prefs.getBoolean("ls_master_switch", true);
        cfg.peiyinApiKey = prefs.getString("ls_peiyin_apikey", "");
        cfg.peiyinVoiceId = prefs.getString("ls_peiyin_voiceid", "");
        cfg.wusoundVoiceId = prefs.getString("ls_wusound_voiceid", "");
        cfg.wusoundPromptId = prefs.getString("ls_wusound_promptid", "default");

        cfg.announceText = prefs.getBoolean("ls_announce_text", true);
        cfg.announceImage = prefs.getBoolean("ls_announce_image", true);
        cfg.announceVideo = prefs.getBoolean("ls_announce_video", true);
        cfg.announceCard = prefs.getBoolean("ls_announce_card", true);
        cfg.announceFile = prefs.getBoolean("ls_announce_file", true);
        cfg.announceLocation = prefs.getBoolean("ls_announce_location", true);
        cfg.announceSticker = prefs.getBoolean("ls_announce_sticker", false);
        cfg.announceCall = prefs.getBoolean("ls_announce_call", true);
        cfg.announceQuote = prefs.getBoolean("ls_announce_quote", true);
        cfg.announceNickname = prefs.getBoolean("ls_announce_nickname", true);
        cfg.announceGroup = prefs.getBoolean("ls_announce_group", false);
        cfg.announceAt = prefs.getBoolean("ls_announce_at", true);
        cfg.announceMiniProgram = prefs.getBoolean("ls_announce_miniprogram", true);
        cfg.announceVideoChannel = prefs.getBoolean("ls_announce_videochannel", true);
        cfg.announceChatHistory = prefs.getBoolean("ls_announce_chathistory", true);

        cfg.announceIntervalMs = parseInt(prefs.getString("ls_announce_interval_ms", "0"), 0);
        cfg.textTruncateEnabled = prefs.getBoolean("ls_text_truncate", true);
        cfg.textCutoffLen = parseInt(prefs.getString("ls_text_truncate_len", "150"), 150);

        cfg.quietEnabled = prefs.getBoolean("ls_quiet_enabled", false);
        cfg.quietStart = prefs.getString("ls_quiet_start", "23:00");
        cfg.quietEnd = prefs.getString("ls_quiet_end", "07:00");

        cfg.autoAcceptFriend = prefs.getBoolean("ls_auto_accept_friend", false);
        cfg.autoAcceptFriendMsg = prefs.getString("ls_auto_accept_friend_msg", "你好呀，很高兴认识你!");

        cfg.aiToolboxEnabled = prefs.getBoolean("ls_aitoolbox_enabled", false);
        cfg.arkApiKey = prefs.getString("ls_ark_apikey", "");
        cfg.arkImageSize = prefs.getString("ls_ark_img_size", "2K");
        cfg.arkImageFormat = prefs.getString("ls_ark_img_format", "png");
        cfg.arkVideoDuration = parseInt(prefs.getString("ls_ark_vid_duration", "8"), 8);
        cfg.arkVideoResolution = prefs.getString("ls_ark_vid_resolution", "720p");
        cfg.imageGenEnabled = prefs.getBoolean("ls_img_gen_enabled", false);
        cfg.videoGenEnabled = prefs.getBoolean("ls_vid_gen_enabled", false);

        cfg.deepseekEnabled = prefs.getBoolean("ls_deepseek_enabled", false);
        cfg.deepseekSmartReply = prefs.getBoolean("ls_ds_smart_reply", false);
        cfg.deepseekTranslate = prefs.getBoolean("ls_ds_translate", false);
        cfg.deepseekSummary = prefs.getBoolean("ls_ds_summary", false);
        cfg.deepseekAtReply = prefs.getBoolean("ls_ds_at_reply", false);
        cfg.deepseekModel = prefs.getString("ls_ds_model", "deepseek-chat");
        cfg.deepseekPersona = prefs.getString("ls_ds_persona", "");

        // WeChatPlus 增强功能
        cfg.typingIndicatorEnabled = prefs.getBoolean("ls_wp_typing", true);
        cfg.chatFooterEnhanceEnabled = prefs.getBoolean("ls_wp_chatfooter", true);
        cfg.chatUICustomEnabled = prefs.getBoolean("ls_wp_chatui", true);
        cfg.batchMessageEnabled = prefs.getBoolean("ls_wp_batchmsg", true);
        cfg.autoRemarkEnabled = prefs.getBoolean("ls_wp_autoremark", true);
        cfg.searchEnhanceEnabled = prefs.getBoolean("ls_wp_search", true);
        cfg.autoReplyEnabled = prefs.getBoolean("ls_wp_autoreply", true);
        cfg.snsFeaturesEnabled = prefs.getBoolean("ls_wp_sns", true);
        cfg.privacyFeaturesEnabled = prefs.getBoolean("ls_wp_privacy", true);
        cfg.loginMonitorEnabled = prefs.getBoolean("ls_wp_loginmon", true);
        cfg.hideContactFieldsEnabled = prefs.getBoolean("ls_wp_hidecontact", true);
        cfg.deleteDetectEnabled = prefs.getBoolean("ls_wp_deldetect", true);
        cfg.stickyEnhanceEnabled = prefs.getBoolean("ls_wp_sticky", true);
        cfg.unreadBadgeEnabled = prefs.getBoolean("ls_wp_unread", true);
        cfg.tabCustomEnabled = prefs.getBoolean("ls_wp_tabcustom", true);
        cfg.callFeaturesEnabled = prefs.getBoolean("ls_wp_call", true);
        cfg.shakeCustomEnabled = prefs.getBoolean("ls_wp_shake", true);
        cfg.blockWechatUpdate = prefs.getBoolean("ls_wp_blockupdate", true);

        cfg.sensitiveFilterEnabled = prefs.getBoolean("ls_sensitive_enabled", false);
        cfg.sensitiveWords.clear();
        try {
            JSONArray swArr = new JSONArray(prefs.getString("ls_sensitive_words", "[]"));
            for (int i = 0; i < swArr.length(); i++) cfg.sensitiveWords.add(swArr.getString(i));
        } catch (Exception e) {}

        cfg.keywordReplyEnabled = prefs.getBoolean("ls_kwreply_enabled", false);
        cfg.keywordReplyMap.clear();
        try {
            JSONObject kwObj = new JSONObject(prefs.getString("ls_kwreply_map", "{}"));
            java.util.Iterator<?> kwKeys = kwObj.keys();
            while (kwKeys.hasNext()) {
                String kw = (String) kwKeys.next();
                Map<String, String> m = new HashMap<>();
                m.put("reply", kwObj.optString(kw));
                cfg.keywordReplyMap.put(kw, m);
            }
        } catch (Exception e) {}
        cfg.keywordRules.clear();
        cfg.keywordRules.addAll(KeywordRule.fromJson(prefs.getString("ls_kwreply_rules", "[]")));
        cfg.customAnnounceFormat = prefs.getString("ls_announce_fmt", "{sender}: {content}");
        cfg.antiRecall = prefs.getBoolean("ls_recall_enabled", false);
        cfg.antiDetection = prefs.getBoolean("ls_anti_detection", true);

        cfg.announceWhitelist.clear();
        String wlStr = prefs.getString("ls_tts_whitelist", "");
        if (!wlStr.isEmpty()) {
            for (String id : wlStr.split(",")) {
                String t = id.trim();
                if (!t.isEmpty()) cfg.announceWhitelist.add(t);
            }
        }

        cfg.announceBlacklist.clear();
        String blStr = prefs.getString("ls_tts_blacklist", "");
        if (!blStr.isEmpty()) {
            for (String id : blStr.split(",")) {
                String t = id.trim();
                if (!t.isEmpty()) cfg.announceBlacklist.add(t);
            }
        }
        cfg.whitelistStrict = prefs.getBoolean("ls_tts_whitelist_strict", true);

        return cfg;
    }

    public void save(SharedPreferences prefs) {
        if (prefs == null) return;
        SharedPreferences.Editor e = prefs.edit();
        e.putBoolean("ls_master_switch", masterSwitch);
        e.putString("ls_peiyin_apikey", peiyinApiKey);
        e.putString("ls_peiyin_voiceid", peiyinVoiceId);
        e.putString("ls_wusound_voiceid", wusoundVoiceId);
        e.putString("ls_wusound_promptid", wusoundPromptId);
        e.putBoolean("ls_announce_text", announceText);
        e.putBoolean("ls_announce_image", announceImage);
        e.putBoolean("ls_announce_video", announceVideo);
        e.putBoolean("ls_announce_card", announceCard);
        e.putBoolean("ls_announce_file", announceFile);
        e.putBoolean("ls_announce_location", announceLocation);
        e.putBoolean("ls_announce_sticker", announceSticker);
        e.putBoolean("ls_announce_call", announceCall);
        e.putBoolean("ls_announce_quote", announceQuote);
        e.putBoolean("ls_announce_nickname", announceNickname);
        e.putBoolean("ls_announce_group", announceGroup);
        e.putBoolean("ls_announce_at", announceAt);
        e.putBoolean("ls_announce_miniprogram", announceMiniProgram);
        e.putBoolean("ls_announce_videochannel", announceVideoChannel);
        e.putBoolean("ls_announce_chathistory", announceChatHistory);
        e.putString("ls_announce_interval_ms", String.valueOf(announceIntervalMs));
        e.putString("ls_announce_fmt", customAnnounceFormat);
        e.putBoolean("ls_text_truncate", textTruncateEnabled);
        e.putString("ls_text_truncate_len", String.valueOf(textCutoffLen));
        e.putBoolean("ls_quiet_enabled", quietEnabled);
        e.putString("ls_quiet_start", quietStart);
        e.putString("ls_quiet_end", quietEnd);
        e.putBoolean("ls_auto_accept_friend", autoAcceptFriend);
        e.putString("ls_auto_accept_friend_msg", autoAcceptFriendMsg);
        e.putBoolean("ls_aitoolbox_enabled", aiToolboxEnabled);
        e.putString("ls_ark_apikey", arkApiKey);
        e.putString("ls_ark_img_size", arkImageSize);
        e.putString("ls_ark_img_format", arkImageFormat);
        e.putString("ls_ark_vid_duration", String.valueOf(arkVideoDuration));
        e.putString("ls_ark_vid_resolution", arkVideoResolution);
        e.putBoolean("ls_img_gen_enabled", imageGenEnabled);
        e.putBoolean("ls_vid_gen_enabled", videoGenEnabled);
        e.putBoolean("ls_deepseek_enabled", deepseekEnabled);
        e.putBoolean("ls_ds_smart_reply", deepseekSmartReply);
        e.putBoolean("ls_ds_translate", deepseekTranslate);
        e.putBoolean("ls_ds_summary", deepseekSummary);
        e.putBoolean("ls_ds_at_reply", deepseekAtReply);
        e.putString("ls_ds_model", deepseekModel);
        e.putString("ls_ds_persona", deepseekPersona);
        e.putBoolean("ls_recall_enabled", antiRecall);
        e.putBoolean("ls_anti_detection", antiDetection);

        // WeChatPlus 增强功能
        e.putBoolean("ls_wp_typing", typingIndicatorEnabled);
        e.putBoolean("ls_wp_chatfooter", chatFooterEnhanceEnabled);
        e.putBoolean("ls_wp_chatui", chatUICustomEnabled);
        e.putBoolean("ls_wp_batchmsg", batchMessageEnabled);
        e.putBoolean("ls_wp_autoremark", autoRemarkEnabled);
        e.putBoolean("ls_wp_search", searchEnhanceEnabled);
        e.putBoolean("ls_wp_autoreply", autoReplyEnabled);
        e.putBoolean("ls_wp_sns", snsFeaturesEnabled);
        e.putBoolean("ls_wp_privacy", privacyFeaturesEnabled);
        e.putBoolean("ls_wp_loginmon", loginMonitorEnabled);
        e.putBoolean("ls_wp_hidecontact", hideContactFieldsEnabled);
        e.putBoolean("ls_wp_deldetect", deleteDetectEnabled);
        e.putBoolean("ls_wp_sticky", stickyEnhanceEnabled);
        e.putBoolean("ls_wp_unread", unreadBadgeEnabled);
        e.putBoolean("ls_wp_tabcustom", tabCustomEnabled);
        e.putBoolean("ls_wp_call", callFeaturesEnabled);
        e.putBoolean("ls_wp_shake", shakeCustomEnabled);
        e.putBoolean("ls_wp_blockupdate", blockWechatUpdate);

        e.putBoolean("ls_sensitive_enabled", sensitiveFilterEnabled);
        JSONArray swArr = new JSONArray();
        for (String w : sensitiveWords) swArr.put(w);
        e.putString("ls_sensitive_words", swArr.toString());
        e.putBoolean("ls_kwreply_enabled", keywordReplyEnabled);
        JSONObject kwObj = new JSONObject();
        for (String kw : keywordReplyMap.keySet()) {
            Map<String, String> m = keywordReplyMap.get(kw);
            String reply = m != null ? m.get("reply") : "";
            try { kwObj.put(kw, reply == null ? "" : reply); } catch (Exception ex) {}
        }
        e.putString("ls_kwreply_map", kwObj.toString());
        e.putString("ls_kwreply_rules", KeywordRule.toJson(keywordRules));

        StringBuilder wlSb = new StringBuilder();
        for (String id : announceWhitelist) {
            if (wlSb.length() > 0) wlSb.append(",");
            wlSb.append(id);
        }
        e.putString("ls_tts_whitelist", wlSb.toString());
        e.putBoolean("ls_tts_whitelist_strict", whitelistStrict);

        StringBuilder blSb = new StringBuilder();
        for (String id : announceBlacklist) {
            if (blSb.length() > 0) blSb.append(",");
            blSb.append(id);
        }
        e.putString("ls_tts_blacklist", blSb.toString());

        e.apply();
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Throwable t) { return def; }
    }
}
