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

        if (!cfg.announceWhitelist.isEmpty()) {
            boolean inWl = cfg.announceWhitelist.contains(talker);
            if (!inWl) return false;
        } else {
            // 白名单为空时不播报任何消息
            return false;
        }

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
