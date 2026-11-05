package com.leshao.v3.service

import com.leshao.v3.LogWriter
import com.leshao.v3.model.ModuleConfig
import com.leshao.v3.model.WeChatMessage
import java.util.Calendar

class FilterManager {

    fun shouldProcess(talker: String?, msgType: Int, content: String?, cfg: ModuleConfig): Boolean {
        if (!cfg.masterSwitch) return false
        if (isQuietTime(cfg)) return false

        val isGroup = talker != null && (talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom"))
        if (isGroup && !cfg.announceGroup) return false

        // 自动播报黑名单: 黑名单内的会话/好友一律不播报(优先于白名单)
        if (cfg.announceBlacklist.isNotEmpty()) {
            if (cfg.announceBlacklist.contains(talker)) return false
        }

        // v1132: 严格白名单仅在「白名单非空」时生效; 白名单为空视为不限制,
        // 避免升级后默认全静音(与 TTSPageView 状态提示一致)。
        // - 白名单非空 + 严格模式: 仅放行白名单;
        // - 白名单非空 + 非严格模式: 白名单优先, 其他会话仍放行;
        // - 白名单为空: 不做限制, 全部按消息类型放行。
        if (cfg.announceWhitelist.isNotEmpty()
            && cfg.whitelistStrict
            && !cfg.announceWhitelist.contains(talker)
        ) {
            LogWriter.log(TAG, "whitelistStrict 拦截非白名单会话 talker=" + talker)
            return false
        }

        when (msgType) {
            WeChatMessage.TYPE_TEXT -> return cfg.announceText
            // v3.0.571: 语音消息播报受「语音消息播报」开关(announceCall)控制，不再无条件播报
            WeChatMessage.TYPE_VOICE -> return cfg.announceCall
            WeChatMessage.TYPE_IMAGE -> return cfg.announceImage
            WeChatMessage.TYPE_CARD -> return cfg.announceCard
            WeChatMessage.TYPE_VIDEO -> return cfg.announceVideo
            WeChatMessage.TYPE_FILE -> return cfg.announceFile
            WeChatMessage.TYPE_APPMSG -> {
                // AppMessage(49) 按内容细分到对应开关；无法识别的一律不播报(默认关闭)
                if (content == null) return false
                if (content.contains("<location")) return cfg.announceLocation
                if (content.contains("<type>57</type>")) return cfg.announceQuote
                if (content.contains("<weappinfo>") || content.contains("<type>33</type>"))
                    return cfg.announceMiniProgram
                if (content.contains("<finderFeed>") || content.contains("<type>2001</type>"))
                    return cfg.announceVideoChannel
                if (content.contains("<recorditem>") || content.contains("<type>19</type>"))
                    return cfg.announceChatHistory
                if (content.contains("<appmsg") && content.contains("<title>"))
                    return cfg.announceFile
                return false
            }
            WeChatMessage.TYPE_STICKER -> return cfg.announceSticker
            WeChatMessage.TYPE_VOIP -> return cfg.announceCall
            // v3.0.571: 其他类型默认不播报（受总开关+群/白名单控制后仍不放行）
            else -> return false
        }
    }

    private fun isQuietTime(cfg: ModuleConfig): Boolean {
        if (!cfg.quietEnabled) return false
        try {
            val ps = cfg.quietStart.split(":")
            val pe = cfg.quietEnd.split(":")
            // 精确到分钟，避免「23:30~07:30」被解析成 23 点整开始
            val stMin = ps[0].toInt() * 60 + (ps.getOrElse(1) { "0" }.toInt())
            val edMin = pe[0].toInt() * 60 + (pe.getOrElse(1) { "0" }.toInt())
            val nowCal = Calendar.getInstance()
            val nowMin = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)
            return if (stMin < edMin) nowMin >= stMin && nowMin < edMin
            else nowMin >= stMin || nowMin < edMin
        } catch (t: Throwable) {
            return false
        }
    }

    companion object {
        private const val TAG = "FilterManager"
    }
}