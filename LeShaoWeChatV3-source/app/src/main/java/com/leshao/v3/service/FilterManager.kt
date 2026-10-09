package com.leshao.v3.service

import com.leshao.v3.LogWriter
import com.leshao.v3.model.ModuleConfig
import com.leshao.v3.model.WeChatMessage
import java.util.Calendar

class FilterManager {

    fun shouldProcess(talker: String?, msgType: Int, content: String?, cfg: ModuleConfig): Boolean {
        if (!cfg.masterSwitch) return false
        if (isQuietTime(cfg)) return false

        val isGroup = talker != null && talker.endsWith("@chatroom")
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
            WeChatMessage.TYPE_VOICE -> return true
            WeChatMessage.TYPE_IMAGE -> return cfg.announceImage
            WeChatMessage.TYPE_CARD -> return cfg.announceCard
            WeChatMessage.TYPE_VIDEO -> return cfg.announceVideo
            WeChatMessage.TYPE_APPMSG -> {
                if (content == null) return true
                if (content.contains("<location")) return cfg.announceLocation
                if (content.contains("<type>57</type>")) return cfg.announceQuote
                return true
            }
            WeChatMessage.TYPE_STICKER -> return cfg.announceSticker
            WeChatMessage.TYPE_VOIP -> return cfg.announceCall
            else -> return true
        }
    }

    private fun isQuietTime(cfg: ModuleConfig): Boolean {
        if (!cfg.quietEnabled) return false
        try {
            val ps = cfg.quietStart.split(":")
            val pe = cfg.quietEnd.split(":")
            val stHour = ps[0].toInt()
            val edHour = pe[0].toInt()
            val now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return if (stHour < edHour) now >= stHour && now < edHour
            else now >= stHour || now < edHour
        } catch (t: Throwable) {
            return false
        }
    }

    companion object {
        private const val TAG = "FilterManager"
    }
}