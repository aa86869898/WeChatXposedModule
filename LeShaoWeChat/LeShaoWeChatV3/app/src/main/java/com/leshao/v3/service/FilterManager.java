package com.leshao.v3.service;

import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;

import java.util.Calendar;
import java.util.Set;

public class FilterManager {

    private static final String TAG = "FilterManager";

    public boolean shouldProcess(String talker, int msgType, String content, ModuleConfig cfg) {
        if (!cfg.masterSwitch) return false;
        if (isQuietTime(cfg)) return false;

        boolean isGroup = talker != null && talker.endsWith("@chatroom");
        if (isGroup && !cfg.announceGroup) return false;

        // 自动播报黑名单: 黑名单内的会话/好友一律不播报(优先于白名单)
        if (!cfg.announceBlacklist.isEmpty()) {
            if (cfg.announceBlacklist.contains(talker)) return false;
        }

        // v1132: 严格白名单仅在「白名单非空」时生效; 白名单为空视为不限制,
        // 避免升级后默认全静音(与 TTSPageView 状态提示一致)。
        // - 白名单非空 + 严格模式: 仅放行白名单;
        // - 白名单非空 + 非严格模式: 白名单优先, 其他会话仍放行;
        // - 白名单为空: 不做限制, 全部按消息类型放行。
        if (!cfg.announceWhitelist.isEmpty()
                && cfg.whitelistStrict
                && !cfg.announceWhitelist.contains(talker)) {
            LogWriter.log(TAG, "whitelistStrict 拦截非白名单会话 talker=" + talker);
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
