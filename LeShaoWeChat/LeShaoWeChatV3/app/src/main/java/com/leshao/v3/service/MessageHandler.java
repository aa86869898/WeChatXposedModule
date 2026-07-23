package com.leshao.v3.service;

import com.leshao.v3.model.ModuleConfig;

public class MessageHandler {

    private final TtsEngine mTts;
    private final FilterManager mFilter;
    private final NicknameResolver mNick;

    public MessageHandler(TtsEngine tts, FilterManager filter, NicknameResolver nick) {
        this.mTts = tts;
        this.mFilter = filter;
        this.mNick = nick;
    }

    public void handle(Object msgInfo, int msgType, String talker, String content, ModuleConfig cfg) {
        if (!mFilter.shouldProcess(talker, msgType, content, cfg)) return;

        String displayName = mNick.resolveDisplayName(talker);
        boolean isGroup = talker != null && talker.endsWith("@chatroom");

        switch (msgType) {
            case 1:  handleText(displayName, content, isGroup, cfg); break;
            case 3:  handleImage(displayName, isGroup); break;
            case 34: handleVoice(displayName, isGroup); break;
            case 43: handleVideo(displayName, isGroup); break;
            case 48: handleLocation(displayName, content, isGroup); break;
            case 49: handleAppMsg(displayName, content, isGroup); break;
        }
    }

    private void handleText(String name, String text, boolean isGroup, ModuleConfig cfg) {
        String cleaned = cleanText(text);
        if (cleaned.isEmpty()) return;

        if (cfg.textTruncateEnabled && cfg.textCutoffLen > 0 && cleaned.length() > cfg.textCutoffLen) {
            cleaned = cleaned.substring(0, cfg.textCutoffLen) + "等长内容";
        }

        String speak = isGroup ? groupPrefix(name, isGroup) + "说：" + cleaned
                               : name + "说：" + cleaned;
        mTts.speak(speak);
    }

    private void handleVoice(String name, boolean isGroup) {
        String speak = isGroup ? groupPrefix(name, isGroup) + "发来语音，请在手机上收听"
                               : name + "发来语音，请在手机上收听";
        mTts.speak(speak);
    }

    private void handleImage(String name, boolean isGroup) {
        String speak = isGroup ? groupPrefix(name, isGroup) + "发来一张照片"
                               : name + "发来一张照片";
        mTts.speak(speak);
    }

    private void handleVideo(String name, boolean isGroup) {
        String speak = isGroup ? groupPrefix(name, isGroup) + "发来一段视频"
                               : name + "发来一段视频";
        mTts.speak(speak);
    }

    private void handleLocation(String name, String content, boolean isGroup) {
        String loc = parseLocation(content);
        String speak = isGroup ? groupPrefix(name, isGroup) + "发来定位在：" + loc
                               : name + "发来定位在：" + loc;
        mTts.speak(speak);
    }

    private void handleAppMsg(String name, String content, boolean isGroup) {
        if (content == null) return;
        if (content.contains("<location")) {
            handleLocation(name, content, isGroup);
            return;
        }
        if (content.contains("luckymoney") || content.contains("lucky money")) {
            String speak = isGroup ? groupPrefix(name, isGroup) + "发来一个红包"
                                   : name + "发来一个红包";
            mTts.speak(speak);
        }
    }

    public void announceRedPacket(String sender, String wishing, String amount) {
        String name = sender != null ? mNick.resolveDisplayName(sender) : "好友";
        String wish = wishing != null ? wishing : "恭喜发财";
        String speak = name + "的红包：" + wish + "，你抢到了" + amount + "元";
        mTts.speak(speak);
    }

    public void announceTransfer(String sender, String amount, String desc) {
        String name = sender != null ? mNick.resolveDisplayName(sender) : "好友";
        String speak = "收到" + name + "转账" + amount + "元";
        if (desc != null && !desc.isEmpty()) speak += "，备注：" + desc;
        mTts.speak(speak);
    }

    static String cleanText(String content) {
        if (content == null) return "";
        String t = content
            .replace("<![CDATA[", "").replace("]]>", "")
            .replaceAll("<[^>]+>", "")
            .replaceAll("https?://\\S+", "链接")
            .replaceAll("@\\S+\\s+", "")
            .replaceAll("\\[\\w+\\]", "")
            .replace("\n", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) + "等长内容" : t;
    }

    static String parseLocation(String content) {
        if (content == null) return "未知位置";
        int i = content.indexOf("label=\"");
        if (i >= 0) {
            int s = i + 7, e = content.indexOf("\"", s);
            if (e > s) return content.substring(s, e);
        }
        i = content.indexOf("poiname=\"");
        if (i >= 0) {
            int s = i + 9, e = content.indexOf("\"", s);
            if (e > s) return content.substring(s, e);
        }
        return "未知位置";
    }

    private String groupPrefix(String name, boolean isGroup) {
        return isGroup ? "群聊的" : name;
    }
}
