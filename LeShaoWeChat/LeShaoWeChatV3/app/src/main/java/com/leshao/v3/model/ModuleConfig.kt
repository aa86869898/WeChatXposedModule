package com.leshao.v3.model

import android.content.Context
import android.content.SharedPreferences
import com.leshao.v3.LogWriter
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet

open class ModuleConfig {

    @JvmField
    var masterSwitch = true

    @JvmField
    var peiyinApiKey = ""

    @JvmField
    var peiyinVoiceId = ""

    @JvmField
    var wusoundVoiceId = ""

    @JvmField
    var wusoundPromptId = "default"

    @JvmField
    var announceText = true

    @JvmField
    var announceImage = true

    @JvmField
    var announceVideo = true

    @JvmField
    var announceCard = true

    @JvmField
    var announceFile = true

    @JvmField
    var announceLocation = true

    @JvmField
    var announceSticker = false

    @JvmField
    var announceCall = true

    @JvmField
    var announceNickname = true

    @JvmField
    var announceGroup = false

    @JvmField
    var announceQuote = true

    @JvmField
    var announceAt = true

    @JvmField
    var announceMiniProgram = true

    @JvmField
    var announceVideoChannel = true

    @JvmField
    var announceChatHistory = true

    @JvmField
    var customAnnounceFormat = "{sender}: {content}"

    @JvmField
    var announceIntervalMs = 0L

    @JvmField
    var textCutoffLen = 150

    @JvmField
    var textTruncateEnabled = true

    @JvmField
    var announceWhitelist: MutableSet<String> = HashSet()

    @JvmField
    var announceBlacklist: MutableSet<String> = HashSet()

    @JvmField
    var whitelistStrict = true

    @JvmField
    var quietEnabled = false

    @JvmField
    var quietStart = "23:00"

    @JvmField
    var quietEnd = "07:00"

    @JvmField
    var autoAcceptFriend = false

    @JvmField
    var autoAcceptFriendMsg = "你好呀，很高兴认识你!"

    @JvmField
    var sensitiveWords: MutableSet<String> = HashSet()

    @JvmField
    var sensitiveFilterEnabled = false

    @JvmField
    var keywordReplyEnabled = false

    @JvmField
    var keywordReplyMap: MutableMap<String, MutableMap<String, String>> = HashMap()

    @JvmField
    var aiToolboxEnabled = false

    @JvmField
    var imageGenEnabled = false

    @JvmField
    var videoGenEnabled = false

    @JvmField
    var arkApiKey = ""

    @JvmField
    var arkImageSize = "2K"

    @JvmField
    var arkImageFormat = "png"

    @JvmField
    var arkVideoResolution = "720p"

    @JvmField
    var arkVideoDuration = 8

    @JvmField
    var deepseekEnabled = false

    @JvmField
    var deepseekSmartReply = false

    @JvmField
    var deepseekTranslate = false

    @JvmField
    var deepseekSummary = false

    @JvmField
    var deepseekAtReply = false

    @JvmField
    var deepseekModel = "deepseek-chat"

    @JvmField
    var deepseekPersona = ""

    @JvmField
    var antiRecall = false

    @JvmField
    var antiDetection = true

    @JvmField
    var typingIndicatorEnabled = true

    @JvmField
    var chatFooterEnhanceEnabled = true

    @JvmField
    var chatUICustomEnabled = true

    @JvmField
    var batchMessageEnabled = true

    @JvmField
    var autoRemarkEnabled = true

    @JvmField
    var searchEnhanceEnabled = true

    @JvmField
    var autoReplyEnabled = true

    @JvmField
    var snsFeaturesEnabled = true

    @JvmField
    var privacyFeaturesEnabled = true

    @JvmField
    var loginMonitorEnabled = true

    @JvmField
    var hideContactFieldsEnabled = true

    @JvmField
    var deleteDetectEnabled = true

    @JvmField
    var stickyEnhanceEnabled = true

    @JvmField
    var unreadBadgeEnabled = true

    @JvmField
    var tabCustomEnabled = true

    @JvmField
    var callFeaturesEnabled = true

    @JvmField
    var shakeCustomEnabled = true

    @JvmField
    var voiceForwardEnabled = true

    @JvmField
    var blockWechatUpdate = true

    @JvmField
    var cacheDir: String? = null

    @JvmField
    var mediaDir: String? = null

    @JvmField
    var keywordRules: MutableList<KeywordRule> = ArrayList()

    companion object {
        @JvmField
        val JOKE_LIB = arrayOf(
            "老师：小明，你来说说'有备无患'是什么意思？\n小明：就是形容一个人备胎很多，不用担心找不到对象。\n老师：滚出去！",
            "今天去面试。面试官问：你最大的缺点是什么？\n我说：我说话太直。\n面试官说：我觉得这不算缺点啊。\n我说：我觉不觉得不重要，你怎么觉得才重要，笨蛋。",
            "一只北极熊孤单地呆在冰上发呆，实在无聊就开始拔自己的毛玩。一根、两根、三根……最后拔得一根不剩，他突然说：好冷啊！",
            "问：什么样的速度最快？\n答：曹操。说曹操曹操就到。",
            "程序员最讨厌的四件事：写注释、写文档、别人不写注释、别人不写文档。",
            "语文课上，老师问：'谁能用果然造句？' 小明站起来说：'我先吃水蜜桃，然后吃苹果。' 老师：'这跟果然有什么关系？' 小明：'水果然后，果然！'",
            "医生：你的X光片显示你骨头断了。患者：那怎么办？医生：我已经用PS帮你修好了。",
            "小时候妈妈告诉我：不要跟陌生人说话。长大后发现：不跟陌生人说话根本没法工作。"
        )

        @JvmField
        val QUOTE_LIB = arrayOf(
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
        )

        private var sCurrentWxid: String? = null

        @JvmStatic
        fun initWxid(ctx: Context?) {
            sCurrentWxid = null
            if (ctx == null) return
            val prefNames = arrayOf(
                "system_config_prefs", "com.tencent.mm_preferences",
                "notify_sync_pref", "auth_info_key_prefs",
                "app_brand_global_sp", "exdevice_pref"
            )
            val keyNames = arrayOf(
                "login_weixin_username", "login_user_name", "last_login_username",
                "auth_uin", "username", "uin", "_auth_uin"
            )
            for (pn in prefNames) {
                try {
                    val all = ctx.getSharedPreferences(pn, 0).all
                    for (key in keyNames) {
                        val v = all[key]
                        if (v != null && v.toString().startsWith("wxid_")) {
                            sCurrentWxid = v.toString()
                            LogWriter.log("ModuleConfig", "initWxid: $sCurrentWxid")
                            return
                        }
                    }
                    for ((_, value) in all) {
                        val v = value
                        if (v != null) {
                            val valStr = v.toString()
                            if (valStr.startsWith("wxid_") && !valStr.contains("@")) {
                                sCurrentWxid = valStr
                                LogWriter.log("ModuleConfig", "initWxid scan: $sCurrentWxid")
                                return
                            }
                        }
                    }
                } catch (e: Throwable) {}
            }
        }

        @JvmStatic
        fun getCurrentWxid(): String? {
            return sCurrentWxid
        }

        private fun purgeLegacyKeys(prefs: SharedPreferences?) {
            if (prefs == null) return
            val legacy = arrayOf(
                "ls_wp_convprivacy", "ls_wp_notify", "ls_wp_redalert",
                "conv_privacy_level", "conv_hide_notification", "conv_hide_convlist",
                "conv_privacy_list", "notify_priority_mode", "notify_avatar",
                "notify_important_contacts", "quick_reply_phrases",
                "rp_alert_vibrate", "rp_alert_ring"
            )
            try {
                val editor = prefs.edit()
                for (k in legacy) {
                    if (prefs.contains(k)) {
                        editor.remove(k)
                    }
                }
                editor.apply()
            } catch (ignored: Throwable) {}
        }

        @JvmStatic
        fun load(prefs: SharedPreferences?): ModuleConfig {
            val cfg = ModuleConfig()
            if (prefs == null) return cfg
            purgeLegacyKeys(prefs)

            cfg.masterSwitch = prefs.getBoolean("ls_master_switch", true)
            cfg.peiyinApiKey = prefs.getStr("ls_peiyin_apikey", "")
            cfg.peiyinVoiceId = prefs.getStr("ls_peiyin_voiceid", "")
            cfg.wusoundVoiceId = prefs.getStr("ls_wusound_voiceid", "")
            cfg.wusoundPromptId = prefs.getStr("ls_wusound_promptid", "default")

            cfg.announceText = prefs.getBoolean("ls_announce_text", true)
            cfg.announceImage = prefs.getBoolean("ls_announce_image", true)
            cfg.announceVideo = prefs.getBoolean("ls_announce_video", true)
            cfg.announceCard = prefs.getBoolean("ls_announce_card", true)
            cfg.announceFile = prefs.getBoolean("ls_announce_file", true)
            cfg.announceLocation = prefs.getBoolean("ls_announce_location", true)
            cfg.announceSticker = prefs.getBoolean("ls_announce_sticker", false)
            cfg.announceCall = prefs.getBoolean("ls_announce_call", true)
            cfg.announceQuote = prefs.getBoolean("ls_announce_quote", true)
            cfg.announceNickname = prefs.getBoolean("ls_announce_nickname", true)
            cfg.announceGroup = prefs.getBoolean("ls_announce_group", false)
            cfg.announceAt = prefs.getBoolean("ls_announce_at", true)
            cfg.announceMiniProgram = prefs.getBoolean("ls_announce_miniprogram", true)
            cfg.announceVideoChannel = prefs.getBoolean("ls_announce_videochannel", true)
            cfg.announceChatHistory = prefs.getBoolean("ls_announce_chathistory", true)

            cfg.announceIntervalMs = parseInt(prefs.getStr("ls_announce_interval_ms", "0"), 0L)
            cfg.textTruncateEnabled = prefs.getBoolean("ls_text_truncate", true)
            cfg.textCutoffLen = parseInt(prefs.getStr("ls_text_truncate_len", "150"), 150)

            cfg.quietEnabled = prefs.getBoolean("ls_quiet_enabled", false)
            cfg.quietStart = prefs.getStr("ls_quiet_start", "23:00")
            cfg.quietEnd = prefs.getStr("ls_quiet_end", "07:00")

            cfg.autoAcceptFriend = prefs.getBoolean("ls_auto_accept_friend", false)
            cfg.autoAcceptFriendMsg = prefs.getStr("ls_auto_accept_friend_msg", "你好呀，很高兴认识你!")

            cfg.aiToolboxEnabled = prefs.getBoolean("ls_aitoolbox_enabled", false)
            cfg.arkApiKey = prefs.getStr("ls_ark_apikey", "")
            cfg.arkImageSize = prefs.getStr("ls_ark_img_size", "2K")
            cfg.arkImageFormat = prefs.getStr("ls_ark_img_format", "png")
            cfg.arkVideoDuration = parseInt(prefs.getStr("ls_ark_vid_duration", "8"), 8)
            cfg.arkVideoResolution = prefs.getStr("ls_ark_vid_resolution", "720p")
            cfg.imageGenEnabled = prefs.getBoolean("ls_img_gen_enabled", false)
            cfg.videoGenEnabled = prefs.getBoolean("ls_vid_gen_enabled", false)

            cfg.deepseekEnabled = prefs.getBoolean("ls_deepseek_enabled", false)
            cfg.deepseekSmartReply = prefs.getBoolean("ls_ds_smart_reply", false)
            cfg.deepseekTranslate = prefs.getBoolean("ls_ds_translate", false)
            cfg.deepseekSummary = prefs.getBoolean("ls_ds_summary", false)
            cfg.deepseekAtReply = prefs.getBoolean("ls_ds_at_reply", false)
            cfg.deepseekModel = prefs.getStr("ls_ds_model", "deepseek-chat")
            cfg.deepseekPersona = prefs.getStr("ls_ds_persona", "")

            cfg.typingIndicatorEnabled = prefs.getBoolean("ls_wp_typing", true)
            cfg.chatFooterEnhanceEnabled = prefs.getBoolean("ls_wp_chatfooter", true)
            cfg.chatUICustomEnabled = prefs.getBoolean("ls_wp_chatui", true)
            cfg.batchMessageEnabled = prefs.getBoolean("ls_wp_batchmsg", true)
            cfg.autoRemarkEnabled = prefs.getBoolean("ls_wp_autoremark", true)
            cfg.searchEnhanceEnabled = prefs.getBoolean("ls_wp_search", true)
            cfg.autoReplyEnabled = prefs.getBoolean("ls_wp_autoreply", true)
            cfg.snsFeaturesEnabled = prefs.getBoolean("ls_wp_sns", true)
            cfg.privacyFeaturesEnabled = prefs.getBoolean("ls_wp_privacy", true)
            cfg.loginMonitorEnabled = prefs.getBoolean("ls_wp_loginmon", true)
            cfg.hideContactFieldsEnabled = prefs.getBoolean("ls_wp_hidecontact", true)
            cfg.deleteDetectEnabled = prefs.getBoolean("ls_wp_deldetect", true)
            cfg.stickyEnhanceEnabled = prefs.getBoolean("ls_wp_sticky", true)
            cfg.unreadBadgeEnabled = prefs.getBoolean("ls_wp_unread", true)
            cfg.tabCustomEnabled = prefs.getBoolean("ls_wp_tabcustom", true)
            cfg.callFeaturesEnabled = prefs.getBoolean("ls_wp_call", true)
            cfg.shakeCustomEnabled = prefs.getBoolean("ls_wp_shake", true)
            cfg.blockWechatUpdate = prefs.getBoolean("ls_wp_blockupdate", true)

            cfg.sensitiveFilterEnabled = prefs.getBoolean("ls_sensitive_enabled", false)
            cfg.sensitiveWords.clear()
            try {
                val swArr = JSONArray(prefs.getStr("ls_sensitive_words", "[]"))
                for (i in 0 until swArr.length()) cfg.sensitiveWords.add(swArr.getString(i))
            } catch (e: Exception) {}

            cfg.keywordReplyEnabled = prefs.getBoolean("ls_kwreply_enabled", false)
            cfg.keywordReplyMap.clear()
            try {
                val kwObj = JSONObject(prefs.getStr("ls_kwreply_map", "{}"))
                val kwKeys = kwObj.keys()
                while (kwKeys.hasNext()) {
                    val kw = kwKeys.next() as String
                    val m = HashMap<String, String>()
                    m["reply"] = kwObj.optString(kw)
                    cfg.keywordReplyMap[kw] = m
                }
            } catch (e: Exception) {}
            cfg.keywordRules.clear()
            cfg.keywordRules.addAll(KeywordRule.fromJson(prefs.getStr("ls_kwreply_rules", "[]")))
            cfg.customAnnounceFormat = prefs.getStr("ls_announce_fmt", "{sender}: {content}")
            cfg.antiRecall = prefs.getBoolean("ls_recall_enabled", false)
            cfg.antiDetection = prefs.getBoolean("ls_anti_detection", true)

            cfg.announceWhitelist.clear()
            val wlStr = prefs.getStr("ls_tts_whitelist", "")
            if (!wlStr.isNullOrEmpty()) {
                for (id in wlStr.split(",")) {
                    val t = id.trim()
                    if (t.isNotEmpty()) cfg.announceWhitelist.add(t)
                }
            }

            cfg.announceBlacklist.clear()
            val blStr = prefs.getStr("ls_tts_blacklist", "")
            if (!blStr.isNullOrEmpty()) {
                for (id in blStr.split(",")) {
                    val t = id.trim()
                    if (t.isNotEmpty()) cfg.announceBlacklist.add(t)
                }
            }
            cfg.whitelistStrict = prefs.getBoolean("ls_tts_whitelist_strict", true)

            return cfg
        }

        @JvmStatic
        fun save(prefs: SharedPreferences?, cfg: ModuleConfig) {
            if (prefs == null) return
            val e = prefs.edit()
            e.putBoolean("ls_master_switch", cfg.masterSwitch)
            e.putString("ls_peiyin_apikey", cfg.peiyinApiKey)
            e.putString("ls_peiyin_voiceid", cfg.peiyinVoiceId)
            e.putString("ls_wusound_voiceid", cfg.wusoundVoiceId)
            e.putString("ls_wusound_promptid", cfg.wusoundPromptId)
            e.putBoolean("ls_announce_text", cfg.announceText)
            e.putBoolean("ls_announce_image", cfg.announceImage)
            e.putBoolean("ls_announce_video", cfg.announceVideo)
            e.putBoolean("ls_announce_card", cfg.announceCard)
            e.putBoolean("ls_announce_file", cfg.announceFile)
            e.putBoolean("ls_announce_location", cfg.announceLocation)
            e.putBoolean("ls_announce_sticker", cfg.announceSticker)
            e.putBoolean("ls_announce_call", cfg.announceCall)
            e.putBoolean("ls_announce_quote", cfg.announceQuote)
            e.putBoolean("ls_announce_nickname", cfg.announceNickname)
            e.putBoolean("ls_announce_group", cfg.announceGroup)
            e.putBoolean("ls_announce_at", cfg.announceAt)
            e.putBoolean("ls_announce_miniprogram", cfg.announceMiniProgram)
            e.putBoolean("ls_announce_videochannel", cfg.announceVideoChannel)
            e.putBoolean("ls_announce_chathistory", cfg.announceChatHistory)
            e.putString("ls_announce_interval_ms", cfg.announceIntervalMs.toString())
            e.putString("ls_announce_fmt", cfg.customAnnounceFormat)
            e.putBoolean("ls_text_truncate", cfg.textTruncateEnabled)
            e.putString("ls_text_truncate_len", cfg.textCutoffLen.toString())
            e.putBoolean("ls_quiet_enabled", cfg.quietEnabled)
            e.putString("ls_quiet_start", cfg.quietStart)
            e.putString("ls_quiet_end", cfg.quietEnd)
            e.putBoolean("ls_auto_accept_friend", cfg.autoAcceptFriend)
            e.putString("ls_auto_accept_friend_msg", cfg.autoAcceptFriendMsg)
            e.putBoolean("ls_aitoolbox_enabled", cfg.aiToolboxEnabled)
            e.putString("ls_ark_apikey", cfg.arkApiKey)
            e.putString("ls_ark_img_size", cfg.arkImageSize)
            e.putString("ls_ark_img_format", cfg.arkImageFormat)
            e.putString("ls_ark_vid_duration", cfg.arkVideoDuration.toString())
            e.putString("ls_ark_vid_resolution", cfg.arkVideoResolution)
            e.putBoolean("ls_img_gen_enabled", cfg.imageGenEnabled)
            e.putBoolean("ls_vid_gen_enabled", cfg.videoGenEnabled)
            e.putBoolean("ls_deepseek_enabled", cfg.deepseekEnabled)
            e.putBoolean("ls_ds_smart_reply", cfg.deepseekSmartReply)
            e.putBoolean("ls_ds_translate", cfg.deepseekTranslate)
            e.putBoolean("ls_ds_summary", cfg.deepseekSummary)
            e.putBoolean("ls_ds_at_reply", cfg.deepseekAtReply)
            e.putString("ls_ds_model", cfg.deepseekModel)
            e.putString("ls_ds_persona", cfg.deepseekPersona)
            e.putBoolean("ls_recall_enabled", cfg.antiRecall)
            e.putBoolean("ls_anti_detection", cfg.antiDetection)

            e.putBoolean("ls_wp_typing", cfg.typingIndicatorEnabled)
            e.putBoolean("ls_wp_chatfooter", cfg.chatFooterEnhanceEnabled)
            e.putBoolean("ls_wp_chatui", cfg.chatUICustomEnabled)
            e.putBoolean("ls_wp_batchmsg", cfg.batchMessageEnabled)
            e.putBoolean("ls_wp_autoremark", cfg.autoRemarkEnabled)
            e.putBoolean("ls_wp_search", cfg.searchEnhanceEnabled)
            e.putBoolean("ls_wp_autoreply", cfg.autoReplyEnabled)
            e.putBoolean("ls_wp_sns", cfg.snsFeaturesEnabled)
            e.putBoolean("ls_wp_privacy", cfg.privacyFeaturesEnabled)
            e.putBoolean("ls_wp_loginmon", cfg.loginMonitorEnabled)
            e.putBoolean("ls_wp_hidecontact", cfg.hideContactFieldsEnabled)
            e.putBoolean("ls_wp_deldetect", cfg.deleteDetectEnabled)
            e.putBoolean("ls_wp_sticky", cfg.stickyEnhanceEnabled)
            e.putBoolean("ls_wp_unread", cfg.unreadBadgeEnabled)
            e.putBoolean("ls_wp_tabcustom", cfg.tabCustomEnabled)
            e.putBoolean("ls_wp_call", cfg.callFeaturesEnabled)
            e.putBoolean("ls_wp_shake", cfg.shakeCustomEnabled)
            e.putBoolean("ls_wp_blockupdate", cfg.blockWechatUpdate)

            e.putBoolean("ls_sensitive_enabled", cfg.sensitiveFilterEnabled)
            val swArr = JSONArray()
            for (w in cfg.sensitiveWords) swArr.put(w)
            e.putString("ls_sensitive_words", swArr.toString())
            e.putBoolean("ls_kwreply_enabled", cfg.keywordReplyEnabled)
            val kwObj = JSONObject()
            for ((kw, m) in cfg.keywordReplyMap) {
                val reply = m?.get("reply")
                try {
                    kwObj.put(kw, reply ?: "")
                } catch (ex: Exception) {}
            }
            e.putString("ls_kwreply_map", kwObj.toString())
            e.putString("ls_kwreply_rules", KeywordRule.toJson(cfg.keywordRules))

            val wlSb = StringBuilder()
            for (id in cfg.announceWhitelist) {
                if (wlSb.length > 0) wlSb.append(",")
                wlSb.append(id)
            }
            e.putString("ls_tts_whitelist", wlSb.toString())
            e.putBoolean("ls_tts_whitelist_strict", cfg.whitelistStrict)

            val blSb = StringBuilder()
            for (id in cfg.announceBlacklist) {
                if (blSb.length > 0) blSb.append(",")
                blSb.append(id)
            }
            e.putString("ls_tts_blacklist", blSb.toString())

            e.apply()
        }

        private fun parseInt(s: String?, def: Int): Int {
            return try {
                s?.toInt() ?: def
            } catch (t: Throwable) {
                def
            }
        }

        private fun SharedPreferences.getStr(key: String, def: String): String =
            getString(key, def) ?: def

        private fun parseInt(s: String?, def: Long): Long {
            return try {
                s?.toLong() ?: def
            } catch (t: Throwable) {
                def
            }
        }
    }

    fun save(prefs: SharedPreferences?) {
        save(prefs, this)
    }
}