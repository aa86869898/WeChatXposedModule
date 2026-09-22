package com.leshao.v3.service;

import com.leshao.v3.model.ModuleConfig;

import java.util.Calendar;
import java.util.Set;

public class FilterManager {

    public boolean shouldProcess(String talker, int msgType, String content, ModuleConfig cfg) {
        if (!cfg.masterSwitch) return false;
        if (isQuietTime(cfg)) return false;

        boolean isGroup = talker != null && talker.endsWith("@chatroom");
        if (isGroup && !cfg.announceGroup) return false;

        // 自动播报黑名单: 黑名单内的会话/好友一律不播报(优先于白名单)
        if (!cfg.announceBlacklist.isEmpty()) {
            if (cfg.announceBlacklist.contains(talker)) return false;
        }

        // 自动播报白名单: 白名单非空时, 仅白名单内的会话/好友播报
        if (!cfg.announceWhitelist.isEmpty()) {
            boolean inWl = cfg.announceWhitelist.contains(talker);
            if (!inWl) return false;
        }
        // 白名单为空时 strict 模式无拦截对象, 全部放行(避免默认配置下 TTS 被静音)。
        // 只有"配置了白名单且开启严格模式"才对非白名单会话拦截。

        switch (msgType) {
            case com.leshao.v3.model.WeChatMessage.TYPE_TEXT:
                return cfg.announceText;
            case com.leshao.v3.model.WeChatMessage.TYPE_VOICE:
                return true;
            case com.leshao.v3.model.WeChatMessage.TYPE_IMAGE:
                return cfg.announceImage;
            case com.leshao.v3.model.WeChatMessage.TYPE_CARD:
                return cfg.announceCard;
            case com.leshao.v3.model.WeChatMessage.TYPE_VIDEO:
                return cfg.announceVideo;
            case com.leshao.v3.model.WeChatMessage.TYPE_APPMSG:
                if (content == null) return true;
                if (content.contains("<location")) return cfg.announceLocation;
                if (content.contains("<type>57</type>")) return cfg.announceQuote;
                if (content.contains("luckymoney") || content.contains("lucky money")
                        || content.contains("红包")) return cfg.announceRedBag;
                if (content.contains("transferid") || content.contains("remittance")
                        || content.contains("transfer")) return cfg.announceTransfer;
                return true;
            case com.leshao.v3.model.WeChatMessage.TYPE_STICKER:
                return cfg.announceSticker;
            case com.leshao.v3.model.WeChatMessage.TYPE_VOIP:
                return cfg.announceCall;
            default:
                return true;
        }
    }

    private boolean isQuietTime(ModuleConfig cfg) {
        if (!cfg.quietEnabled) return false;
        try {
            String[] ps = cfg.quietStart.split(":");
            String[] pe = cfg.quietEnd.split(":");
            int stHour = Integer.parseInt(ps[0]);
            int edHour = Integer.parseInt(pe[0]);
            int now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            if (stHour < edHour) return now >= stHour && now < edHour;
            else return now >= stHour || now < edHour;
        } catch (Throwable t) { return false; }
    }
}
