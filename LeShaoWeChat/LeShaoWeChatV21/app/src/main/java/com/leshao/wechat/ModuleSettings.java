package com.leshao.wechat;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

public class ModuleSettings {
    private static SharedPreferences sp; private static Context ctx;
    public static boolean masterSwitch = true, entryCardVisible = true;
    public static String ttsEngine = "system", peiyinApiKey = "", peiyinVoiceId = "", peiyinVoiceName = "";
    public static String wusoundApiKey = "", wusoundVoiceId = "", wusoundPromptId = "default";
    public static boolean announceText=true, announceImage=true, announceVideo=true, announceRedBag=true;
    public static boolean announceTransfer=true, announceCard=true, announceFile=true, announceLocation=true, announceSticker=false;
    public static boolean announceCall=true, announceDouyin=true, announceNickname=true, announceGroup=false;
    public static boolean keyVoiceAnnounce=false, autoPlayVoiceEnabled=true;
    public static String customAnnounceFormat = "{sender}: {content}";
    public static long announceIntervalMs = 0;
    public static long lastAnnounceTime = 0;
    public static int textTruncateLength = 150;
    public static boolean textTruncateEnabled = true;
    public static Set<String> WHITE_LIST = new HashSet<>();
    public static Set<String> autoVoiceWhitelist = new HashSet<>();
    public static boolean dianGeEnabled=true, ddMusicEnabled=true, ddVoiceSongEnabled=true, ddVideoAudioEnabled=true, ddVideoMsgEnabled=true;
    public static boolean ddLyricsEnabled=true, ddWeatherEnabled=true, ddFunEnabled=true, ddSelfTrigger=true;
    public static boolean autoAcceptFriend=false, groupInviteEnabled=false, leftGroupTipEnabled=false;
    public static String autoAcceptFriendMsg="你好呀，很高兴认识你!", groupInviteKeyword="加群", leftGroupTipMsg="";
    public static int groupInviteMaxMembers=40, manualInviteMaxMembers=200;
    public static boolean manualInviteEnabled=false, antiAdEnabled=false, autoKickEnabled=false;
    public static int kickThreshold=3;
    public static Set<String> adKeywords=new HashSet<>(), kickKeywords=new HashSet<>();
    public static boolean aiToolboxEnabled=false, imageGenEnabled=false, videoGenEnabled=false;
    public static String arkApiKey="", arkImageModel="doubao-seedream-4-5-251128", arkImageSize="2K", arkImageFormat="png";
    public static String arkVideoModel="doubao-seedance-2-0-260128", arkVideoResolution="720p"; public static int arkVideoDuration=8;
    public static boolean quietEnabled=false, textTruncate2=false;
    public static String quietStart="23:00", quietEnd="07:00";
    public static boolean videoParseEnabled=true, douyinProfileEnabled=false; public static String douyinProfileCkey="";
    public static Set<String> massSendTargetWxids = new HashSet<>();
    public static String massSendTextContent = ""; public static long massSendInterval = 3000;
    public static String cacheDir, mediaDir;
    public static boolean deepseekEnabled=false, deepseekSmartReply=false, deepseekTranslate=false, deepseekSummary=false, deepseekAtReply=false;
    public static boolean deepseekWriting=false, deepseekQA=false;
    public static String deepseekApiKey="", deepseekModel="deepseek-chat", deepseekPersona="";
    public static boolean recallLogEnabled=false, fileClassifyEnabled=false, unreadStatsEnabled=false;
    public static boolean sensitiveFilterEnabled=false; public static Set<String> sensitiveWords=new HashSet<>();
    public static boolean keywordReplyEnabled=false;
    public static Map<String, Map<String, String>> keywordReplyMap = new ConcurrentHashMap<>();
    public static boolean scheduleEnabled=false; public static int scheduleHour=8, scheduleMin=0, scheduleEndHour=22, scheduleEndMin=0;
    public static int scheduleStartMinute=0, scheduleEndMinute=0, scheduleDayMask=0x7F;
    public static boolean redPacketGrabEnabled=false;

    // ===== 入群欢迎 =====
    public static boolean welcomeEnabled = false;
    public static String welcomeMsg = "欢迎加入群聊!";
    public static int welcomeType = 0;

    // ===== 群管理/黑名单/警告 =====
    public static boolean blacklistEnabled = true;
    public static Map<String, JSONObject> blacklistMap = new ConcurrentHashMap<>();
    public static int warnType = 0;
    public static String warnMsg = "请勿发送违规内容，警告！";
    public static int farewellType = 0;
    public static String farewellMsg = "已被移出群聊";
    public static Map<String, Map<String, Integer>> userViolationMap = new ConcurrentHashMap<>();
    public static List<String> groupManageList = new ArrayList<>();
    public static int mtTypeMask = 0;

    // ===== 定时公告 =====
    public static boolean schedAnnounceEnabled = false;
    public static String schedAnnounceGroup = "";
    public static String schedAnnounceMsg = "";
    public static long schedAnnounceInterval = 3600L;
    public static long schedAnnounceLastTime = 0;
    public static int schedAnnounceHour = 9;
    public static int schedAnnounceMinute = 0;

    // ===== 活跃统计/投票/AI工具 =====
    public static boolean activityStatsEnabled = false;
    public static Map<String, Map<String, Integer>> activityStatsMap = new ConcurrentHashMap<>();
    public static boolean voteEnabled = false;
    public static Map<String, JSONObject> activeVotes = new ConcurrentHashMap<>();
    public static boolean voiceToTextEnabled = false;
    public static boolean linkSummaryEnabled = false;
    public static boolean reminderEnabled = false;
    public static Set<String> aiWhitelist = new HashSet<>();
    public static Map<String, JSONObject> reminderTasks = new ConcurrentHashMap<>();

    // ===== 媒体目录 =====
    public static String LS_MEDIA_DIR = "/storage/emulated/0/Download/乐少助手/乐少助手AI存储文件/";
    public static int playbackVolumePercent = 100;

    // ===== DD作用域 =====(新无用词条)
    public static Set<String> ddMusicScope = new HashSet<>();
    public static Set<String> ddLyricsScope = new HashSet<>();
    public static Set<String> ddWeatherScope = new HashSet<>();
    public static Set<String> ddFunScope = new HashSet<>();
    public static Set<String> ddVoiceScope = new HashSet<>();
    public static Set<String> ddAudioScope = new HashSet<>();
    public static Set<String> ddVideoScope = new HashSet<>();

    // ===== JDY离线授权/完整性 =====
    public static boolean jdyDingdongEnabled = true;
    public static boolean jdyMusicEnabled = true;
    public static boolean jdyWeatherEnabled = true;
    public static boolean jdyLyricsEnabled = true;
    public static boolean jdyJokeEnabled = true;
    public static Boolean jdyCachedAuthVerified = null;
    public static boolean jdyAuthVerified = false;
    public static boolean jdyLockDialogShowing = false;
    public static int jdyAuthAttempts = 0;
    public static boolean jdyInitDone = false;
    public static boolean jdyIntegrityOk = false;

    // ===== JDY语音中继 =====
    public static boolean jdyVoiceRelayEnabled = false;
    public static boolean jdyVoiceRelaySpeakSender = true;
    public static boolean jdyVoiceRelayQuietEnabled = false;
    public static String jdyVoiceRelayQuietStart = "23:00";
    public static String jdyVoiceRelayQuietEnd = "08:00";
    public static Set<String> jdyVoiceRelayAllowSet = new HashSet<>();
    public static java.util.Vector<Object> jdyVoiceProcessedAmr = new java.util.Vector<>();

    // ===== JDY联系人缓存 =====
    public static java.util.List<String[]> jdyCachedGroups = null;
    public static java.util.List<String[]> jdyCachedFriends = null;
    public static long jdyCachedGroupsTime = 0;
    public static long jdyCachedFriendsTime = 0;

    // ===== 离线笑话库 =====
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

    // ===== 离线金句库 =====
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

    // ===== JDY功能列表 (用于UI) =====
    public static final String[][] JDY_FEATURE_LIST = {
        {"diange_music_enabled", "diange_scope_music", "点歌(音乐卡片)", "发送 \"点歌 歌名\""},
        {"dd_voice_song_enabled", "dd_scope_voice", "语音点歌", "发送 \"语音点歌 歌名\""},
        {"dd_video_audio_enabled", "dd_scope_audio", "视频提取语音", "发送 \"语音 视频链接\""},
        {"dd_video_msg_enabled", "dd_scope_video", "视频直发", "发送 \"视频 视频链接\""},
        {"diange_lyrics_enabled", "diange_scope_lyrics", "歌词搜索", "发送 \"歌词 歌名\""},
        {"diange_weather_enabled", "diange_scope_weather", "天气查询", "发送 \"天气 城市名\""},
        {"diange_fun_enabled", "diange_scope_fun", "笑话/金句", "发送 \"笑话\" 或 \"金句\""}
    };

    public static void init(Context c, SharedPreferences p) {
        ctx = c; sp = p;
        cacheDir = c.getFilesDir().getAbsolutePath() + "/leshao_cache";
        mediaDir = c.getFilesDir().getAbsolutePath() + "/leshao_media";
        new File(cacheDir).mkdirs(); new File(mediaDir).mkdirs();
        loadAll(); loadWhitelist();
        try { KeywordReplyManager.init(); } catch (Exception e) {}
    }

    private static void loadWhitelist() {
        try { File wf = new File(cacheDir, "whitelist.txt");
            if (wf.exists()) { BufferedReader br = new BufferedReader(new FileReader(wf)); String line;
                while ((line = br.readLine()) != null) { line = line.trim(); if (!line.isEmpty()) WHITE_LIST.add(line); }
                br.close(); }
        } catch (Exception e) {}
    }

    public static void loadAll() {
        masterSwitch = sp.getBoolean("ls_master_switch", true);
        entryCardVisible = sp.getBoolean("ls_entry_card_visible", true);
        ttsEngine = sp.getString("ls_tts_engine", "system");
        peiyinApiKey = sp.getString("ls_peiyin_apikey", "");
        peiyinVoiceId = sp.getString("ls_peiyin_voiceid", "");
        wusoundApiKey = sp.getString("ls_wusound_apikey", "");
        wusoundVoiceId = sp.getString("ls_wusound_voiceid", "");
        wusoundPromptId = sp.getString("ls_wusound_promptid", "default");
        announceText = sp.getBoolean("ls_announce_text", true);
        announceImage = sp.getBoolean("ls_announce_image", true);
        announceVideo = sp.getBoolean("ls_announce_video", true);
        announceRedBag = sp.getBoolean("ls_announce_redbag", true);
        announceTransfer = sp.getBoolean("ls_announce_transfer", true);
        announceCard = sp.getBoolean("ls_announce_card", true);
        announceFile = sp.getBoolean("ls_announce_file", true);
        announceLocation = sp.getBoolean("ls_announce_location", true);
        announceGroup = sp.getBoolean("ls_announce_group", false);
        announceNickname = sp.getBoolean("ls_announce_nickname", true);
        announceCall = sp.getBoolean("ls_announce_call", true);
        keyVoiceAnnounce = sp.getBoolean("ls_keyvoice", false);
        announceIntervalMs = Integer.parseInt(sp.getString("ls_announce_interval_ms", "0"));
        autoPlayVoiceEnabled = sp.getBoolean("ls_auto_voice", true);
        textTruncateEnabled = sp.getBoolean("ls_text_truncate", true);
        textTruncateLength = parseInt(sp.getString("ls_text_truncate_len", "150"), 150);
        dianGeEnabled = sp.getBoolean("ls_diange_enabled", true);
        ddMusicEnabled = sp.getBoolean("diange_music_enabled", true);
        ddVoiceSongEnabled = sp.getBoolean("dd_voice_song_enabled", true);
        ddVideoAudioEnabled = sp.getBoolean("dd_video_audio_enabled", true);
        ddVideoMsgEnabled = sp.getBoolean("dd_video_msg_enabled", true);
        ddLyricsEnabled = sp.getBoolean("diange_lyrics_enabled", true);
        ddWeatherEnabled = sp.getBoolean("diange_weather_enabled", true);
        ddFunEnabled = sp.getBoolean("diange_fun_enabled", true);
        ddSelfTrigger = sp.getBoolean("diange_self_trigger", true);
        autoAcceptFriend = sp.getBoolean("ls_auto_accept_friend", false);
        autoAcceptFriendMsg = sp.getString("ls_auto_accept_friend_msg", "你好呀，很高兴认识你!");
        groupInviteEnabled = sp.getBoolean("ls_group_invite_enabled", false);
        groupInviteKeyword = sp.getString("ls_group_invite_keyword", "加群");
        leftGroupTipEnabled = sp.getBoolean("ls_left_tip_enabled", false);
        leftGroupTipMsg = sp.getString("ls_left_tip_msg", "");
        aiToolboxEnabled = sp.getBoolean("ls_aitoolbox_enabled", false);
        arkApiKey = sp.getString("ls_ark_apikey", "");
        arkImageModel = sp.getString("ls_ark_img_model", "doubao-seedream-4-5-251128");
        arkImageSize = sp.getString("ls_ark_img_size", "2K");
        arkImageFormat = sp.getString("ls_ark_img_format", "png");
        arkVideoModel = sp.getString("ls_ark_vid_model", "doubao-seedance-2-0-260128");
        arkVideoDuration = parseInt(sp.getString("ls_ark_vid_duration", "8"), 8);
        arkVideoResolution = sp.getString("ls_ark_vid_resolution", "720p");
        imageGenEnabled = sp.getBoolean("ls_img_gen_enabled", false);
        videoGenEnabled = sp.getBoolean("ls_vid_gen_enabled", false);
        quietEnabled = sp.getBoolean("ls_quiet_enabled", false);
        quietStart = sp.getString("ls_quiet_start", "23:00");
        quietEnd = sp.getString("ls_quiet_end", "07:00");
        videoParseEnabled = sp.getBoolean("ls_video_parse_enabled", true);
        deepseekEnabled = sp.getBoolean("ls_deepseek_enabled", false);
        deepseekSmartReply = sp.getBoolean("ls_ds_smart_reply", false);
        deepseekTranslate = sp.getBoolean("ls_ds_translate", false);
        deepseekSummary = sp.getBoolean("ls_ds_summary", false);
        deepseekAtReply = sp.getBoolean("ls_ds_at_reply", false);
        deepseekWriting = sp.getBoolean("ls_ds_writing", false);
        deepseekQA = sp.getBoolean("ls_ds_qa", false);
        deepseekApiKey = sp.getString("ls_ds_apikey", "");
        deepseekModel = sp.getString("ls_ds_model", "deepseek-chat");
        deepseekPersona = sp.getString("ls_ds_persona", "");
        recallLogEnabled = sp.getBoolean("ls_recall_enabled", false);
        redPacketGrabEnabled = sp.getBoolean("ls_redpacket_enabled", false);
        sensitiveFilterEnabled = sp.getBoolean("ls_sensitive_enabled", false);
        sensitiveWords.clear();
        try {
            JSONArray swArr = new JSONArray(sp.getString("ls_sensitive_words", "[]"));
            for (int i = 0; i < swArr.length(); i++) sensitiveWords.add(swArr.getString(i));
        } catch (Exception e) {}
        welcomeEnabled = sp.getBoolean("ls_welcome_enabled", false);
        welcomeMsg = sp.getString("ls_welcome_msg", "欢迎加入群聊!");
        welcomeType = parseInt(sp.getString("ls_welcome_type", "0"), 0);
        keywordReplyEnabled = sp.getBoolean("ls_kwreply_enabled", false);
        keywordReplyMap.clear();
        try {
            JSONObject kwObj = new JSONObject(sp.getString("ls_kwreply_map", "{}"));
            java.util.Iterator kwKeys = kwObj.keys();
            while (kwKeys.hasNext()) {
                String kw = (String) kwKeys.next();
                Map<String, String> m = new HashMap<>(); m.put("reply", kwObj.optString(kw));
                keywordReplyMap.put(kw, m);
            }
        } catch (Exception e) {}
        antiAdEnabled = sp.getBoolean("ls_antiad_enabled", false);
        adKeywords.clear();
        try {
            JSONArray adArr = new JSONArray(sp.getString("ls_ad_keywords", "[]"));
            for (int i = 0; i < adArr.length(); i++) adKeywords.add(adArr.getString(i));
        } catch (Exception e) {}
        autoKickEnabled = sp.getBoolean("ls_autokick_enabled", false);
        kickThreshold = parseInt(sp.getString("ls_kick_threshold", "3"), 3);
        warnType = parseInt(sp.getString("ls_warn_type", "0"), 0);
        warnMsg = sp.getString("ls_warn_msg", "请勿发送违规内容，警告！");
        farewellType = parseInt(sp.getString("ls_farewell_type", "0"), 0);
        farewellMsg = sp.getString("ls_farewell_msg", "已被移出群聊");
        kickKeywords.clear();
        try {
            JSONArray kkArr = new JSONArray(sp.getString("ls_kick_keywords", "[]"));
            for (int i = 0; i < kkArr.length(); i++) kickKeywords.add(kkArr.getString(i));
        } catch (Exception e) {}
        userViolationMap.clear();
        try {
            JSONObject vmObj = new JSONObject(sp.getString("ls_violation_map", "{}"));
            java.util.Iterator gk = vmObj.keys();
            while (gk.hasNext()) {
                String gid = (String) gk.next();
                JSONObject members = vmObj.optJSONObject(gid);
                if (members == null) continue;
                Map<String, Integer> mm = new HashMap<>();
                java.util.Iterator mk = members.keys();
                while (mk.hasNext()) {
                    String wxid = (String) mk.next();
                    mm.put(wxid, members.optInt(wxid, 0));
                }
                userViolationMap.put(gid, mm);
            }
        } catch (Exception e) {}
        blacklistEnabled = sp.getBoolean("ls_blacklist_enabled", true);
        blacklistMap.clear();
        try {
            JSONArray blArr = new JSONArray(sp.getString("ls_blacklist", "[]"));
            for (int i = 0; i < blArr.length(); i++) {
                JSONObject o = blArr.optJSONObject(i);
                if (o != null) { String w = o.optString("wxid", ""); if (!w.isEmpty()) blacklistMap.put(w, o); }
            }
        } catch (Exception e) {}
        mtTypeMask = parseInt(sp.getString("ls_mt_type_mask", "0"), 0);
        schedAnnounceEnabled = sp.getBoolean("ls_schedanno_enabled", false);
        schedAnnounceGroup = sp.getString("ls_schedanno_group", "");
        schedAnnounceMsg = sp.getString("ls_schedanno_msg", "");
        schedAnnounceInterval = Long.parseLong(sp.getString("ls_schedanno_interval", "3600"));
        schedAnnounceHour = parseInt(sp.getString("ls_schedanno_hour", "9"), 9);
        schedAnnounceMinute = parseInt(sp.getString("ls_schedanno_minute", "0"), 0);
        activityStatsEnabled = sp.getBoolean("ls_activity_enabled", false);
        voteEnabled = sp.getBoolean("ls_vote_enabled", false);
        groupManageList.clear();
        try {
            JSONArray gmArr = new JSONArray(sp.getString("ls_group_manage_list", "[]"));
            for (int i = 0; i < gmArr.length(); i++) groupManageList.add(gmArr.getString(i));
        } catch (Exception e) {}
        voiceToTextEnabled = sp.getBoolean("ls_v2t_enabled", false);
        linkSummaryEnabled = sp.getBoolean("ls_linksum_enabled", false);
        fileClassifyEnabled = sp.getBoolean("ls_filecls_enabled", false);
        reminderEnabled = sp.getBoolean("ls_reminder_enabled", false);
        unreadStatsEnabled = sp.getBoolean("ls_unread_enabled", false);
        recallLogEnabled = sp.getBoolean("ls_recall_enabled", false);
        customAnnounceFormat = sp.getString("ls_announce_fmt", "{sender}: {content}");
        keyVoiceAnnounce = sp.getBoolean("ls_keyvoice", false);
        announceIntervalMs = Integer.parseInt(sp.getString("ls_announce_interval_ms", "0"));
        scheduleEnabled = sp.getBoolean("ls_schedule_enabled", false);
        scheduleHour = parseInt(sp.getString("ls_schedule_hour", "8"), 8);
        scheduleMin = parseInt(sp.getString("ls_schedule_start_min", "0"), 0);
        scheduleStartMinute = parseInt(sp.getString("ls_schedule_start_min", "0"), 0);
        scheduleEndHour = parseInt(sp.getString("ls_schedule_end", "22"), 22);
        scheduleEndMinute = parseInt(sp.getString("ls_schedule_end_min", "0"), 0);
        scheduleDayMask = parseInt(sp.getString("ls_schedule_daymask", "127"), 0x7F);
        playbackVolumePercent = parseInt(sp.getString("ls_playback_volume_pct", "100"), 100);
    }

    public static void saveAll() {
        SharedPreferences.Editor e = sp.edit();
        e.putBoolean("ls_master_switch", masterSwitch);
        e.putBoolean("ls_entry_card_visible", entryCardVisible);
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
        e.putBoolean("ls_announce_group", announceGroup);
        e.putBoolean("ls_announce_nickname", announceNickname);
        e.putBoolean("ls_announce_call", announceCall);
        e.putBoolean("ls_keyvoice", keyVoiceAnnounce);
        e.putString("ls_announce_interval_ms", String.valueOf(announceIntervalMs));
        e.putString("ls_announce_fmt", customAnnounceFormat);
        e.putBoolean("ls_auto_voice", autoPlayVoiceEnabled);
        e.putBoolean("ls_text_truncate", textTruncateEnabled);
        e.putString("ls_text_truncate_len", String.valueOf(textTruncateLength));
        e.putBoolean("ls_diange_enabled", dianGeEnabled);
        e.putBoolean("diange_music_enabled", ddMusicEnabled);
        e.putBoolean("dd_voice_song_enabled", ddVoiceSongEnabled);
        e.putBoolean("dd_video_audio_enabled", ddVideoAudioEnabled);
        e.putBoolean("dd_video_msg_enabled", ddVideoMsgEnabled);
        e.putBoolean("diange_lyrics_enabled", ddLyricsEnabled);
        e.putBoolean("diange_weather_enabled", ddWeatherEnabled);
        e.putBoolean("diange_fun_enabled", ddFunEnabled);
        e.putBoolean("diange_self_trigger", ddSelfTrigger);
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
        e.putBoolean("ls_quiet_enabled", quietEnabled);
        e.putString("ls_quiet_start", quietStart);
        e.putString("ls_quiet_end", quietEnd);
        e.putBoolean("ls_video_parse_enabled", videoParseEnabled);
        e.putBoolean("ls_deepseek_enabled", deepseekEnabled);
        e.putString("ls_ds_apikey", deepseekApiKey);
        e.putString("ls_ds_model", deepseekModel);
        e.putString("ls_ds_persona", deepseekPersona);
        e.putBoolean("ls_ds_smart_reply", deepseekSmartReply);
        e.putBoolean("ls_ds_translate", deepseekTranslate);
        e.putBoolean("ls_ds_summary", deepseekSummary);
        e.putBoolean("ls_ds_at_reply", deepseekAtReply);
        e.putBoolean("ls_ds_writing", deepseekWriting);
        e.putBoolean("ls_ds_qa", deepseekQA);
        e.putBoolean("ls_recall_enabled", recallLogEnabled);
        e.putBoolean("ls_redpacket_enabled", redPacketGrabEnabled);
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
        JSONObject vmObj = new JSONObject();
        for (String gid : userViolationMap.keySet()) {
            Map<String, Integer> mm = userViolationMap.get(gid);
            if (mm == null) continue;
            JSONObject members = new JSONObject();
            for (String wxid : mm.keySet()) {
                try { members.put(wxid, mm.get(wxid)); } catch (Exception ex) {}
            }
            try { vmObj.put(gid, members); } catch (Exception ex) {}
        }
        e.putString("ls_violation_map", vmObj.toString());
        e.putBoolean("ls_blacklist_enabled", blacklistEnabled);
        JSONArray blArr = new JSONArray();
        for (JSONObject o : blacklistMap.values()) blArr.put(o);
        e.putString("ls_blacklist", blArr.toString());
        e.putString("ls_mt_type_mask", String.valueOf(mtTypeMask));
        e.putBoolean("ls_schedanno_enabled", schedAnnounceEnabled);
        e.putString("ls_schedanno_group", schedAnnounceGroup);
        e.putString("ls_schedanno_msg", schedAnnounceMsg);
        e.putString("ls_schedanno_interval", String.valueOf(schedAnnounceInterval));
        e.putString("ls_schedanno_hour", String.valueOf(schedAnnounceHour));
        e.putString("ls_schedanno_minute", String.valueOf(schedAnnounceMinute));
        e.putBoolean("ls_activity_enabled", activityStatsEnabled);
        e.putBoolean("ls_vote_enabled", voteEnabled);
        e.putBoolean("ls_v2t_enabled", voiceToTextEnabled);
        e.putBoolean("ls_linksum_enabled", linkSummaryEnabled);
        e.putBoolean("ls_filecls_enabled", fileClassifyEnabled);
        e.putBoolean("ls_reminder_enabled", reminderEnabled);
        e.putBoolean("ls_unread_enabled", unreadStatsEnabled);
        e.putBoolean("ls_recall_enabled", recallLogEnabled);
        JSONArray gmArr = new JSONArray();
        for (String g : groupManageList) gmArr.put(g);
        e.putString("ls_group_manage_list", gmArr.toString());
        e.putBoolean("ls_schedule_enabled", scheduleEnabled);
        e.putString("ls_schedule_hour", String.valueOf(scheduleHour));
        e.putString("ls_schedule_start_min", String.valueOf(scheduleStartMinute));
        e.putString("ls_schedule_end", String.valueOf(scheduleEndHour));
        e.putString("ls_schedule_end_min", String.valueOf(scheduleEndMinute));
        e.putString("ls_schedule_daymask", String.valueOf(scheduleDayMask));
        e.putString("ls_playback_volume_pct", String.valueOf(playbackVolumePercent));
        e.apply();
    }

    public static String getStr(String k, String d) { return sp.getString(k, d); }
    public static boolean getBool(String k, boolean d) { return sp.getBoolean(k, d); }
    public static void putStr(String k, String v) { sp.edit().putString(k, v).apply(); }
    public static void putBool(String k, boolean v) { sp.edit().putBoolean(k, v).apply(); }
    private static int parseInt(String s, int def) { try { return Integer.parseInt(s); } catch (Exception e) { return def; } }

    // ===== JDY HTTP/JSON 辅助方法 =====
    public static String jdyHttpGet(String urlStr) {
        try {
            java.net.URL u = new java.net.URL(urlStr);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) { conn.disconnect(); return null; }
            java.io.InputStream is = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            is.close();
            conn.disconnect();
            return bos.toString("UTF-8");
        } catch (Exception e) { return null; }
    }

    public static String jdyHttpGetWithHeaders(String urlStr, String[][] headers) {
        try {
            java.net.URL u = new java.net.URL(urlStr);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            if (headers != null) {
                for (String[] h : headers) conn.setRequestProperty(h[0], h[1]);
            }
            int code = conn.getResponseCode();
            if (code != 200) { conn.disconnect(); return null; }
            java.io.InputStream is = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            is.close();
            conn.disconnect();
            return bos.toString("UTF-8");
        } catch (Exception e) { return null; }
    }

    public static boolean jdyDownloadFile(String urlStr, String savePath) {
        try {
            java.net.URL u = new java.net.URL(urlStr);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) { conn.disconnect(); return false; }
            java.io.InputStream is = conn.getInputStream();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(savePath);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            fos.close();
            is.close();
            conn.disconnect();
            return true;
        } catch (Exception e) { return false; }
    }

    public static JSONObject jdyParseJson(String s) {
        try { return new JSONObject(s); } catch (Exception e) { return null; }
    }

    public static JSONArray jdyParseJsonArray(String s) {
        try { return new JSONArray(s); } catch (Exception e) { return null; }
    }
}
