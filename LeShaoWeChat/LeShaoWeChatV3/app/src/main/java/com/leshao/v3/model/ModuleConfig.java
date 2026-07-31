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
    public String ttsEngine = "system";
    public String peiyinApiKey = "", peiyinVoiceId = "";
    public String wusoundApiKey = "", wusoundVoiceId = "", wusoundPromptId = "default";

    // 播报类型
    public boolean announceText = true, announceImage = true, announceVideo = true;
    public boolean announceRedBag = true, announceTransfer = true, announceCard = true;
    public boolean announceFile = true, announceLocation = true, announceSticker = false;
    public boolean announceCall = true, announceNickname = true, announceGroup = false;
    public boolean announceQuote = true;
    public boolean announceAt = true;
    public boolean announceMiniProgram = true;
    public boolean announceVideoChannel = true;
    public boolean announceChatHistory = true;
    public boolean autoPlayVoice = true;

    public String customAnnounceFormat = "{sender}: {content}";
    public long announceIntervalMs = 0;
    public int textCutoffLen = 150;
    public boolean textTruncateEnabled = true;

    public Set<String> announceWhitelist = new HashSet<>();
    public int announceTypeMask = 0;

    // 免打扰
    public boolean quietEnabled = false;
    public String quietStart = "23:00", quietEnd = "07:00";

    // 群管
    public boolean autoAcceptFriend = false;
    public String autoAcceptFriendMsg = "你好呀，很高兴认识你!";
    public boolean groupInviteEnabled = false;
    public String groupInviteKeyword = "加群";
    public int groupInviteMaxMembers = 40;
    public boolean leftGroupTipEnabled = false;
    public String leftGroupTipMsg = "";
    public boolean antiAdEnabled = false, autoKickEnabled = false;
    public int kickThreshold = 3;
    public Set<String> adKeywords = new HashSet<>();
    public Set<String> kickKeywords = new HashSet<>();
    public Set<String> sensitiveWords = new HashSet<>();
    public boolean sensitiveFilterEnabled = false;
    public int warnType = 0;
    public String warnMsg = "请勿发送违规内容，警告！";
    public int farewellType = 0;
    public String farewellMsg = "已被移出群聊";
    public boolean blacklistEnabled = true;
    public Set<String> blacklistWxids = new HashSet<>();

    // 欢迎
    public boolean welcomeEnabled = false;
    public String welcomeMsg = "欢迎加入群聊!";
    public int welcomeType = 0;

    // 关键词回复
    public boolean keywordReplyEnabled = false;
    public Map<String, Map<String, String>> keywordReplyMap = new HashMap<>();

    // AI
    public boolean aiToolboxEnabled = false, imageGenEnabled = false, videoGenEnabled = false;
    public String arkApiKey = "";
    public String arkImageModel = "doubao-seedream-4-5-251128";
    public String arkImageSize = "2K", arkImageFormat = "png";
    public String arkVideoModel = "doubao-seedance-2-0-260128";
    public String arkVideoResolution = "720p";
    public int arkVideoDuration = 8;
    public boolean voiceToTextEnabled = false;

    // DeepSeek
    public boolean deepseekEnabled = false;
    public boolean deepseekSmartReply = false, deepseekTranslate = false;
    public boolean deepseekSummary = false, deepseekAtReply = false;
    public String deepseekApiKey = "", deepseekModel = "deepseek-chat";
    public String deepseekPersona = "";

    // 安全
    public boolean antiRecall = false;
    public boolean redPacketGrab = false;
    public boolean redPacketAlertEnabled = true;  // 红包震动/响铃提醒
    public boolean recallLogEnabled = false;
    public boolean antiDetection = true;          // 反Xposed/LSPosed检测

    // ============ WeChatPlus 增强功能 (24项) ============
    // 聊天增强
    public boolean typingIndicatorEnabled = true;
    public boolean chatFooterEnhanceEnabled = true;
    public boolean chatUICustomEnabled = true;
    public boolean batchMessageEnabled = true;
    public boolean autoRemarkEnabled = true;
    public boolean searchEnhanceEnabled = true;
    public boolean notifyCustomEnabled = true;
    public boolean autoReplyEnabled = true;

    // 朋友圈
    public boolean snsFeaturesEnabled = true;

    // 隐私安全
    public boolean privacyFeaturesEnabled = true;
    public boolean loginMonitorEnabled = true;
    public boolean hideContactFieldsEnabled = true;
    public boolean convPrivacyEnabled = true;

    // 联系人与群管
    public boolean deleteDetectEnabled = true;
    public boolean contactExportEnabled = true;
    public boolean contactChangeLogEnabled = true;
    public boolean groupFeaturesEnabled = true;

    // 设置/其他
    public boolean stickyEnhanceEnabled = true;
    public boolean unreadBadgeEnabled = true;
    public boolean tabCustomEnabled = true;
    public boolean callFeaturesEnabled = true;
    public boolean msgExportEnabled = true;
    public boolean chatBackupEnabled = true;
    public boolean shakeCustomEnabled = true;

    // 视频解析
    public boolean videoParseEnabled = true;

    // 语音转发
    public boolean voiceForwardEnabled = true;

    // 群发
    public Set<String> massSendTargetWxids = new HashSet<>();
    public String massSendTextContent = "";
    public long massSendInterval = 3000;

    // 统计/投票
    public boolean activityStatsEnabled = false, voteEnabled = false;
    public List<String> groupManageList = new ArrayList<>();

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

    public static ModuleConfig load(SharedPreferences prefs) {
        ModuleConfig cfg = new ModuleConfig();
        if (prefs == null) return cfg;

        // 激活门控: 未激活时所有功能默认关闭
        boolean activated = isActivatedCheck(prefs);

        cfg.masterSwitch = prefs.getBoolean("ls_master_switch", true);
        cfg.ttsEngine = prefs.getString("ls_tts_engine", "system");
        cfg.peiyinApiKey = prefs.getString("ls_peiyin_apikey", "");
        cfg.peiyinVoiceId = prefs.getString("ls_peiyin_voiceid", "");
        cfg.wusoundApiKey = prefs.getString("ls_wusound_apikey", "");
        cfg.wusoundVoiceId = prefs.getString("ls_wusound_voiceid", "");
        cfg.wusoundPromptId = prefs.getString("ls_wusound_promptid", "default");

        cfg.announceText = prefs.getBoolean("ls_announce_text", true);
        cfg.announceImage = prefs.getBoolean("ls_announce_image", true);
        cfg.announceVideo = prefs.getBoolean("ls_announce_video", true);
        cfg.announceRedBag = prefs.getBoolean("ls_announce_redbag", true);
        cfg.announceTransfer = prefs.getBoolean("ls_announce_transfer", true);
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
        cfg.autoPlayVoice = true;

        cfg.announceIntervalMs = parseInt(prefs.getString("ls_announce_interval_ms", "0"), 0);
        cfg.textTruncateEnabled = prefs.getBoolean("ls_text_truncate", true);
        cfg.textCutoffLen = parseInt(prefs.getString("ls_text_truncate_len", "150"), 150);

        cfg.quietEnabled = prefs.getBoolean("ls_quiet_enabled", false);
        cfg.quietStart = prefs.getString("ls_quiet_start", "23:00");
        cfg.quietEnd = prefs.getString("ls_quiet_end", "07:00");

        cfg.autoAcceptFriend = prefs.getBoolean("ls_auto_accept_friend", false);
        cfg.autoAcceptFriendMsg = prefs.getString("ls_auto_accept_friend_msg", "你好呀，很高兴认识你!");
        cfg.groupInviteEnabled = prefs.getBoolean("ls_group_invite_enabled", false);
        cfg.groupInviteKeyword = prefs.getString("ls_group_invite_keyword", "加群");
        cfg.leftGroupTipEnabled = prefs.getBoolean("ls_left_tip_enabled", false);
        cfg.leftGroupTipMsg = prefs.getString("ls_left_tip_msg", "");

        cfg.aiToolboxEnabled = prefs.getBoolean("ls_aitoolbox_enabled", false);
        cfg.arkApiKey = prefs.getString("ls_ark_apikey", "");
        cfg.arkImageModel = prefs.getString("ls_ark_img_model", "doubao-seedream-4-5-251128");
        cfg.arkImageSize = prefs.getString("ls_ark_img_size", "2K");
        cfg.arkImageFormat = prefs.getString("ls_ark_img_format", "png");
        cfg.arkVideoModel = prefs.getString("ls_ark_vid_model", "doubao-seedance-2-0-260128");
        cfg.arkVideoDuration = parseInt(prefs.getString("ls_ark_vid_duration", "8"), 8);
        cfg.arkVideoResolution = prefs.getString("ls_ark_vid_resolution", "720p");
        cfg.imageGenEnabled = prefs.getBoolean("ls_img_gen_enabled", false);
        cfg.videoGenEnabled = prefs.getBoolean("ls_vid_gen_enabled", false);

        cfg.deepseekEnabled = prefs.getBoolean("ls_deepseek_enabled", false);
        cfg.deepseekSmartReply = prefs.getBoolean("ls_ds_smart_reply", false);
        cfg.deepseekTranslate = prefs.getBoolean("ls_ds_translate", false);
        cfg.deepseekSummary = prefs.getBoolean("ls_ds_summary", false);
        cfg.deepseekAtReply = prefs.getBoolean("ls_ds_at_reply", false);
        cfg.deepseekApiKey = prefs.getString("ls_ds_apikey", "");
        cfg.deepseekModel = prefs.getString("ls_ds_model", "deepseek-chat");
        cfg.deepseekPersona = prefs.getString("ls_ds_persona", "");

        cfg.recallLogEnabled = prefs.getBoolean("ls_recall_log", false);
        cfg.redPacketGrab = prefs.getBoolean("ls_redpacket_enabled", false);
        cfg.redPacketAlertEnabled = prefs.getBoolean("ls_wp_redalert", true);

        // WeChatPlus 增强功能
        cfg.typingIndicatorEnabled = prefs.getBoolean("ls_wp_typing", true);
        cfg.chatFooterEnhanceEnabled = prefs.getBoolean("ls_wp_chatfooter", true);
        cfg.chatUICustomEnabled = prefs.getBoolean("ls_wp_chatui", true);
        cfg.batchMessageEnabled = prefs.getBoolean("ls_wp_batchmsg", true);
        cfg.autoRemarkEnabled = prefs.getBoolean("ls_wp_autoremark", true);
        cfg.searchEnhanceEnabled = prefs.getBoolean("ls_wp_search", true);
        cfg.notifyCustomEnabled = prefs.getBoolean("ls_wp_notify", true);
        cfg.autoReplyEnabled = prefs.getBoolean("ls_wp_autoreply", true);
        cfg.snsFeaturesEnabled = prefs.getBoolean("ls_wp_sns", true);
        cfg.privacyFeaturesEnabled = prefs.getBoolean("ls_wp_privacy", true);
        cfg.loginMonitorEnabled = prefs.getBoolean("ls_wp_loginmon", true);
        cfg.hideContactFieldsEnabled = prefs.getBoolean("ls_wp_hidecontact", true);
        cfg.convPrivacyEnabled = prefs.getBoolean("ls_wp_convprivacy", true);
        cfg.deleteDetectEnabled = prefs.getBoolean("ls_wp_deldetect", true);
        cfg.contactExportEnabled = prefs.getBoolean("ls_wp_contactexp", true);
        cfg.contactChangeLogEnabled = prefs.getBoolean("ls_wp_contactlog", true);
        cfg.groupFeaturesEnabled = prefs.getBoolean("ls_wp_group", true);
        cfg.stickyEnhanceEnabled = prefs.getBoolean("ls_wp_sticky", true);
        cfg.unreadBadgeEnabled = prefs.getBoolean("ls_wp_unread", true);
        cfg.tabCustomEnabled = prefs.getBoolean("ls_wp_tabcustom", true);
        cfg.callFeaturesEnabled = prefs.getBoolean("ls_wp_call", true);
        cfg.msgExportEnabled = prefs.getBoolean("ls_wp_msgexport", true);
        cfg.chatBackupEnabled = prefs.getBoolean("ls_wp_chatbackup", true);
        cfg.shakeCustomEnabled = prefs.getBoolean("ls_wp_shake", true);
        cfg.sensitiveFilterEnabled = prefs.getBoolean("ls_sensitive_enabled", false);
        cfg.sensitiveWords.clear();
        try {
            JSONArray swArr = new JSONArray(prefs.getString("ls_sensitive_words", "[]"));
            for (int i = 0; i < swArr.length(); i++) cfg.sensitiveWords.add(swArr.getString(i));
        } catch (Exception e) {}

        cfg.welcomeEnabled = prefs.getBoolean("ls_welcome_enabled", false);
        cfg.welcomeMsg = prefs.getString("ls_welcome_msg", "欢迎加入群聊!");
        cfg.welcomeType = parseInt(prefs.getString("ls_welcome_type", "0"), 0);
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
        cfg.antiAdEnabled = prefs.getBoolean("ls_antiad_enabled", false);
        cfg.adKeywords.clear();
        try {
            JSONArray adArr = new JSONArray(prefs.getString("ls_ad_keywords", "[]"));
            for (int i = 0; i < adArr.length(); i++) cfg.adKeywords.add(adArr.getString(i));
        } catch (Exception e) {}
        cfg.autoKickEnabled = prefs.getBoolean("ls_autokick_enabled", false);
        cfg.kickThreshold = parseInt(prefs.getString("ls_kick_threshold", "3"), 3);
        cfg.warnType = parseInt(prefs.getString("ls_warn_type", "0"), 0);
        cfg.warnMsg = prefs.getString("ls_warn_msg", "请勿发送违规内容，警告！");
        cfg.farewellType = parseInt(prefs.getString("ls_farewell_type", "0"), 0);
        cfg.farewellMsg = prefs.getString("ls_farewell_msg", "已被移出群聊");
        cfg.kickKeywords.clear();
        try {
            JSONArray kkArr = new JSONArray(prefs.getString("ls_kick_keywords", "[]"));
            for (int i = 0; i < kkArr.length(); i++) cfg.kickKeywords.add(kkArr.getString(i));
        } catch (Exception e) {}
        cfg.blacklistEnabled = prefs.getBoolean("ls_blacklist_enabled", true);
        cfg.blacklistWxids.clear();
        try {
            JSONArray blArr = new JSONArray(prefs.getString("ls_blacklist", "[]"));
            for (int i = 0; i < blArr.length(); i++) {
                JSONObject o = blArr.optJSONObject(i);
                if (o != null) {
                    String w = o.optString("wxid", "");
                    if (!w.isEmpty()) cfg.blacklistWxids.add(w);
                } else {
                    String w = blArr.optString(i, "");
                    if (!w.isEmpty()) cfg.blacklistWxids.add(w);
                }
            }
        } catch (Exception e) {}
        cfg.videoParseEnabled = prefs.getBoolean("ls_video_parse_enabled", true);
        cfg.voiceToTextEnabled = prefs.getBoolean("ls_v2t_enabled", false);
        cfg.customAnnounceFormat = prefs.getString("ls_announce_fmt", "{sender}: {content}");
        cfg.activityStatsEnabled = prefs.getBoolean("ls_activity_enabled", false);
        cfg.voteEnabled = prefs.getBoolean("ls_vote_enabled", false);
        cfg.groupManageList.clear();
        try {
            JSONArray gmArr = new JSONArray(prefs.getString("ls_group_manage_list", "[]"));
            for (int i = 0; i < gmArr.length(); i++) cfg.groupManageList.add(gmArr.getString(i));
        } catch (Exception e) {}
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

        if (!activated) {
            applyActivationGate(cfg);
        } else {
            cfg.masterSwitch = true;
            applyFeatureMask(cfg, prefs);
        }

        return cfg;
    }

    private static boolean isActivatedCheck(SharedPreferences prefs) {
        boolean testMode = com.leshao.v3.service.ActivationManager.isTestMode();

        if (!testMode) {
            Context ctx = com.leshao.v3.ContextManager.getAppContext();
            if (ctx != null) initWxid(ctx);
            if (sCurrentWxid != null && com.leshao.v3.service.ActivationManager.isAdmin(sCurrentWxid)) {
                com.leshao.v3.LogWriter.log("ModuleConfig", "isActivatedCheck: admin exempt, return true");
                return true;
            }
        }

        String code = prefs.getString("ls_act_code", "");
        String wxid = prefs.getString("ls_act_wxid", "");
        if (code.isEmpty() || wxid.isEmpty()) {
            com.leshao.v3.LogWriter.log("ModuleConfig", "isActivatedCheck: no code/wxid, code=[" + code + "] wxid=[" + wxid + "], return false");
            return false;
        }

        String crcS = prefs.getString("ls_act_crc", "");
        if (crcS.isEmpty()) {
            com.leshao.v3.LogWriter.log("ModuleConfig", "isActivatedCheck: no CRC, return false");
            return false;
        }
        try {
            String levelS = prefs.getString("ls_act_level", "");
            String expireS = prefs.getString("ls_act_expire", "");
            String featS = prefs.getString("ls_act_feature_mask", "");
            String data = code + "|" + wxid + "|" + levelS + "|" + expireS + "|" + featS;
            long storedCrc = Long.parseLong(crcS);
            long computedCrc = crc32Static(data);
            if (storedCrc != computedCrc) {
                com.leshao.v3.LogWriter.log("ModuleConfig", "isActivatedCheck: CRC mismatch stored=" + storedCrc + " computed=" + computedCrc + " data=[" + data + "], return false");
                return false;
            }
        } catch (Throwable t) {
            com.leshao.v3.LogWriter.log("ModuleConfig", "isActivatedCheck: CRC exception " + t.getMessage());
            return false;
        }

        try {
            int expireDays = Integer.parseInt(prefs.getString("ls_act_expire", "0"));
            if (expireDays > 0) {
                String actTimeStr = prefs.getString("ls_act_time", "");
                if (!actTimeStr.isEmpty()) {
                    long actTime = Long.parseLong(actTimeStr);
                    if (System.currentTimeMillis() > actTime + expireDays * 86400000L) {
                        com.leshao.v3.LogWriter.log("ModuleConfig", "isActivatedCheck: expired, return false");
                        return false;
                    }
                }
            }
        } catch (Throwable ignored) {}

        com.leshao.v3.LogWriter.log("ModuleConfig", "isActivatedCheck: OK, return true");
        return true;
    }

    private static long crc32Static(String data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return crc.getValue();
    }

    private static void applyActivationGate(ModuleConfig cfg) {
        // 未激活: 所有功能关闭
        cfg.masterSwitch = false;
        cfg.announceText = false;
        cfg.announceImage = false;
        cfg.announceVideo = false;
        cfg.announceRedBag = false;
        cfg.announceTransfer = false;
        cfg.announceCard = false;
        cfg.announceFile = false;
        cfg.announceLocation = false;
        cfg.announceSticker = false;
        cfg.announceCall = false;
        cfg.announceQuote = false;
        cfg.announceAt = false;
        cfg.announceMiniProgram = false;
        cfg.announceVideoChannel = false;
        cfg.announceChatHistory = false;
        cfg.announceNickname = false;
        cfg.announceGroup = false;
        cfg.textTruncateEnabled = false;
        cfg.quietEnabled = false;
        cfg.autoAcceptFriend = false;
        cfg.groupInviteEnabled = false;
        cfg.leftGroupTipEnabled = false;
        cfg.aiToolboxEnabled = false;
        cfg.imageGenEnabled = false;
        cfg.videoGenEnabled = false;
        cfg.deepseekEnabled = false;
        cfg.deepseekSmartReply = false;
        cfg.deepseekTranslate = false;
        cfg.deepseekSummary = false;
        cfg.deepseekAtReply = false;
        cfg.antiRecall = false;
        cfg.redPacketGrab = false;
        cfg.redPacketAlertEnabled = false;
        cfg.typingIndicatorEnabled = false;
        cfg.chatUICustomEnabled = false;
        cfg.batchMessageEnabled = false;
        cfg.autoRemarkEnabled = false;
        cfg.autoReplyEnabled = false;
        cfg.snsFeaturesEnabled = false;
        cfg.privacyFeaturesEnabled = false;
        cfg.deleteDetectEnabled = false;
        cfg.contactExportEnabled = false;
        cfg.contactChangeLogEnabled = false;
        cfg.groupFeaturesEnabled = false;
        cfg.keywordReplyEnabled = false;
        cfg.antiAdEnabled = false;
        cfg.autoKickEnabled = false;
        cfg.sensitiveFilterEnabled = false;
        cfg.welcomeEnabled = false;
        cfg.voiceToTextEnabled = false;
        cfg.loginMonitorEnabled = false;
        cfg.hideContactFieldsEnabled = false;
        cfg.convPrivacyEnabled = false;
        cfg.stickyEnhanceEnabled = false;
        cfg.unreadBadgeEnabled = false;
        cfg.tabCustomEnabled = false;
        cfg.callFeaturesEnabled = false;
        cfg.msgExportEnabled = false;
        cfg.chatBackupEnabled = false;
        cfg.shakeCustomEnabled = false;
        cfg.videoParseEnabled = false;
        cfg.chatFooterEnhanceEnabled = false;
        cfg.searchEnhanceEnabled = false;
        cfg.notifyCustomEnabled = false;
        cfg.activityStatsEnabled = false;
        cfg.voteEnabled = false;
    }

    private static void applyFeatureMask(ModuleConfig cfg, SharedPreferences prefs) {
        int mask = 0;
        try {
            mask = Integer.parseInt(prefs.getString("ls_act_feature_mask", "0"));
        } catch (Throwable ignored) {}
        if (mask == 0) {
            applyActivationGate(cfg);
            return;
        }
        // 仅强制关闭未授权的功能；已授权的功能保留用户设定，不强制开启
        int f = com.leshao.v3.service.ActivationManager.F_MASTER;
        if (!isBit(mask, f)) cfg.masterSwitch = false;  f++;
        if (!isBit(mask, f)) cfg.announceQuote = false;  f++;
        if (!isBit(mask, f)) cfg.announceText = false;  f++;
        f++; // F_VOICE(3) — voice playback handled by autoPlayVoice
        if (!isBit(mask, f)) cfg.announceImage = false;  f++;
        if (!isBit(mask, f)) cfg.announceVideo = false;  f++;
        if (!isBit(mask, f)) cfg.announceLocation = false;  f++;
        if (!isBit(mask, f)) cfg.announceRedBag = false;  f++;
        if (!isBit(mask, f)) cfg.announceTransfer = false;  f++;
        if (!isBit(mask, f)) cfg.announceCard = false;  f++;
        if (!isBit(mask, f)) cfg.announceFile = false;  f++;
        if (!isBit(mask, f)) cfg.announceSticker = false;  f++;
        if (!isBit(mask, f)) cfg.announceGroup = false;  f++;
        if (!isBit(mask, f)) cfg.antiRecall = false;  f++;
        if (!isBit(mask, f)) cfg.redPacketGrab = false;  f++;
        if (!isBit(mask, f)) cfg.autoReplyEnabled = false;  f++;
        if (!isBit(mask, f)) cfg.keywordReplyEnabled = false;  f++;
        if (!isBit(mask, f)) cfg.voiceForwardEnabled = false;  f++;
        if (!isBit(mask, f)) cfg.typingIndicatorEnabled = false;  f++;
        if (!isBit(mask, f)) cfg.chatFooterEnhanceEnabled = false;  f++;
        if (!isBit(mask, f)) cfg.chatUICustomEnabled = false;  f++;
        if (!isBit(mask, f)) cfg.batchMessageEnabled = false;  f++;
        f++;
        if (!isBit(mask, f)) cfg.autoRemarkEnabled = false;  f++;
        if (!isBit(mask, f)) cfg.deleteDetectEnabled = false;  f++;
        if (!isBit(mask, f)) cfg.contactExportEnabled = false;  f++;
        if (!isBit(mask, f)) cfg.snsFeaturesEnabled = false;  f++;
        if (!isBit(mask, f)) cfg.privacyFeaturesEnabled = false;  f++;
        if (!isBit(mask, f)) cfg.chatBackupEnabled = false;  f++;
        if (!isBit(mask, f)) cfg.aiToolboxEnabled = false;  f++;
        if (!isBit(mask, f)) cfg.deepseekEnabled = false;  f++;
    }

    private static boolean isBit(int mask, int bit) {
        return (mask & (1 << bit)) != 0;
    }

    public void save(SharedPreferences prefs) {
        if (prefs == null) return;
        SharedPreferences.Editor e = prefs.edit();
        e.putBoolean("ls_master_switch", masterSwitch);
        e.putString("ls_tts_engine", ttsEngine);
        e.putString("ls_peiyin_apikey", peiyinApiKey);
        e.putString("ls_peiyin_voiceid", peiyinVoiceId);
        e.putString("ls_wusound_apikey", wusoundApiKey);
        e.putString("ls_wusound_voiceid", wusoundVoiceId);
        e.putString("ls_wusound_promptid", wusoundPromptId);
        e.putBoolean("ls_announce_text", announceText);
        e.putBoolean("ls_announce_image", announceImage);
        e.putBoolean("ls_announce_video", announceVideo);
        e.putBoolean("ls_announce_redbag", announceRedBag);
        e.putBoolean("ls_announce_transfer", announceTransfer);
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
        e.putBoolean("ls_group_invite_enabled", groupInviteEnabled);
        e.putString("ls_group_invite_keyword", groupInviteKeyword);
        e.putBoolean("ls_left_tip_enabled", leftGroupTipEnabled);
        e.putString("ls_left_tip_msg", leftGroupTipMsg);
        e.putBoolean("ls_aitoolbox_enabled", aiToolboxEnabled);
        e.putString("ls_ark_apikey", arkApiKey);
        e.putString("ls_ark_img_model", arkImageModel);
        e.putString("ls_ark_img_size", arkImageSize);
        e.putString("ls_ark_img_format", arkImageFormat);
        e.putString("ls_ark_vid_model", arkVideoModel);
        e.putString("ls_ark_vid_duration", String.valueOf(arkVideoDuration));
        e.putString("ls_ark_vid_resolution", arkVideoResolution);
        e.putBoolean("ls_img_gen_enabled", imageGenEnabled);
        e.putBoolean("ls_vid_gen_enabled", videoGenEnabled);
        e.putBoolean("ls_deepseek_enabled", deepseekEnabled);
        e.putBoolean("ls_ds_smart_reply", deepseekSmartReply);
        e.putBoolean("ls_ds_translate", deepseekTranslate);
        e.putBoolean("ls_ds_summary", deepseekSummary);
        e.putBoolean("ls_ds_at_reply", deepseekAtReply);
        e.putString("ls_ds_apikey", deepseekApiKey);
        e.putString("ls_ds_model", deepseekModel);
        e.putString("ls_ds_persona", deepseekPersona);
        e.putBoolean("ls_recall_enabled", antiRecall);
        e.putBoolean("ls_anti_detection", antiDetection);
        e.putBoolean("ls_recall_log", recallLogEnabled);
        e.putBoolean("ls_redpacket_enabled", redPacketGrab);
        e.putBoolean("ls_wp_redalert", redPacketAlertEnabled);

        // WeChatPlus 增强功能
        e.putBoolean("ls_wp_typing", typingIndicatorEnabled);
        e.putBoolean("ls_wp_chatfooter", chatFooterEnhanceEnabled);
        e.putBoolean("ls_wp_chatui", chatUICustomEnabled);
        e.putBoolean("ls_wp_batchmsg", batchMessageEnabled);
        e.putBoolean("ls_wp_autoremark", autoRemarkEnabled);
        e.putBoolean("ls_wp_search", searchEnhanceEnabled);
        e.putBoolean("ls_wp_notify", notifyCustomEnabled);
        e.putBoolean("ls_wp_autoreply", autoReplyEnabled);
        e.putBoolean("ls_wp_sns", snsFeaturesEnabled);
        e.putBoolean("ls_wp_privacy", privacyFeaturesEnabled);
        e.putBoolean("ls_wp_loginmon", loginMonitorEnabled);
        e.putBoolean("ls_wp_hidecontact", hideContactFieldsEnabled);
        e.putBoolean("ls_wp_convprivacy", convPrivacyEnabled);
        e.putBoolean("ls_wp_deldetect", deleteDetectEnabled);
        e.putBoolean("ls_wp_contactexp", contactExportEnabled);
        e.putBoolean("ls_wp_contactlog", contactChangeLogEnabled);
        e.putBoolean("ls_wp_group", groupFeaturesEnabled);
        e.putBoolean("ls_wp_sticky", stickyEnhanceEnabled);
        e.putBoolean("ls_wp_unread", unreadBadgeEnabled);
        e.putBoolean("ls_wp_tabcustom", tabCustomEnabled);
        e.putBoolean("ls_wp_call", callFeaturesEnabled);
        e.putBoolean("ls_wp_msgexport", msgExportEnabled);
        e.putBoolean("ls_wp_chatbackup", chatBackupEnabled);
        e.putBoolean("ls_wp_shake", shakeCustomEnabled);
        e.putBoolean("ls_sensitive_enabled", sensitiveFilterEnabled);
        JSONArray swArr = new JSONArray();
        for (String w : sensitiveWords) swArr.put(w);
        e.putString("ls_sensitive_words", swArr.toString());
        e.putBoolean("ls_welcome_enabled", welcomeEnabled);
        e.putString("ls_welcome_msg", welcomeMsg);
        e.putString("ls_welcome_type", String.valueOf(welcomeType));
        e.putBoolean("ls_kwreply_enabled", keywordReplyEnabled);
        JSONObject kwObj = new JSONObject();
        for (String kw : keywordReplyMap.keySet()) {
            Map<String, String> m = keywordReplyMap.get(kw);
            String reply = m != null ? m.get("reply") : "";
            try { kwObj.put(kw, reply == null ? "" : reply); } catch (Exception ex) {}
        }
        e.putString("ls_kwreply_map", kwObj.toString());
        e.putBoolean("ls_antiad_enabled", antiAdEnabled);
        JSONArray adArr = new JSONArray();
        for (String a : adKeywords) adArr.put(a);
        e.putString("ls_ad_keywords", adArr.toString());
        e.putBoolean("ls_autokick_enabled", autoKickEnabled);
        e.putString("ls_kick_threshold", String.valueOf(kickThreshold));
        e.putString("ls_warn_type", String.valueOf(warnType));
        e.putString("ls_warn_msg", warnMsg);
        e.putString("ls_farewell_type", String.valueOf(farewellType));
        e.putString("ls_farewell_msg", farewellMsg);
        JSONArray kkArr = new JSONArray();
        for (String k : kickKeywords) kkArr.put(k);
        e.putString("ls_kick_keywords", kkArr.toString());
        e.putBoolean("ls_blacklist_enabled", blacklistEnabled);
        JSONArray blArr = new JSONArray();
        for (String w : blacklistWxids) {
            JSONObject o = new JSONObject();
            try { o.put("wxid", w); } catch (Exception ex) {}
            blArr.put(o);
        }
        e.putString("ls_blacklist", blArr.toString());
        e.putBoolean("ls_video_parse_enabled", videoParseEnabled);
        e.putBoolean("ls_v2t_enabled", voiceToTextEnabled);
        e.putBoolean("ls_activity_enabled", activityStatsEnabled);
        e.putBoolean("ls_vote_enabled", voteEnabled);
        JSONArray gmArr = new JSONArray();
        for (String g : groupManageList) gmArr.put(g);
        e.putString("ls_group_manage_list", gmArr.toString());

        StringBuilder wlSb = new StringBuilder();
        for (String id : announceWhitelist) {
            if (wlSb.length() > 0) wlSb.append(",");
            wlSb.append(id);
        }
        e.putString("ls_tts_whitelist", wlSb.toString());

        e.apply();
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Throwable t) { return def; }
    }
}
