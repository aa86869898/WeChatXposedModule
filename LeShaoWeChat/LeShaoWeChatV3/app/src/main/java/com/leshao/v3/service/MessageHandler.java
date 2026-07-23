package com.leshao.v3.service;

import com.leshao.v3.model.ModuleConfig;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageHandler {

    private static final Pattern SENDER_PREFIX = Pattern.compile("^(wxid_[a-zA-Z0-9]+):\\s*");

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

        boolean isGroup = talker != null && talker.endsWith("@chatroom");
        String displayName;
        String effectiveContent = content;

        if (isGroup) {
            String groupName = mNick.resolveDisplayName(talker);
            String senderWxid = extractSenderWxid(content);
            if (senderWxid != null) {
                effectiveContent = removeSenderPrefix(content);
                String senderName = mNick.resolveDisplayName(senderWxid);
                if (senderName.equals(senderWxid)) {
                    displayName = groupName + "群" + "群成员";
                } else {
                    displayName = groupName + "群" + senderName;
                }
            } else {
                displayName = groupName + "群";
            }
        } else {
            displayName = mNick.resolveDisplayName(talker);
        }

        switch (msgType) {
            case 1:  handleText(displayName, effectiveContent, cfg); break;
            case 3:  handleImage(displayName); break;
            case 34: handleVoice(displayName); break;
            case 43: handleVideo(displayName); break;
            case 48: handleLocation(displayName, effectiveContent); break;
            case 49: handleAppMsg(displayName, effectiveContent); break;
        }
    }

    private void handleText(String name, String text, ModuleConfig cfg) {
        String cleaned = cleanText(text);
        if (cleaned.isEmpty()) return;

        if (cfg.textTruncateEnabled && cfg.textCutoffLen > 0 && cleaned.length() > cfg.textCutoffLen) {
            cleaned = cleaned.substring(0, cfg.textCutoffLen) + "等长内容";
        }

        mTts.speak(name + "说：" + cleaned);
    }

    private void handleVoice(String name) {
        mTts.speak(name + "发来语音，请在手机上收听");
    }

    private void handleImage(String name) {
        mTts.speak(name + "发来一张照片");
    }

    private void handleVideo(String name) {
        mTts.speak(name + "发来一段视频");
    }

    private void handleLocation(String name, String content) {
        String loc = parseLocation(content);
        mTts.speak(name + "发来定位在：" + loc);
    }

    private void handleAppMsg(String name, String content) {
        if (content == null) return;
        if (content.contains("<location")) {
            handleLocation(name, content);
            return;
        }
        if (content.contains("luckymoney") || content.contains("lucky money")) {
            mTts.speak(name + "发来一个红包");
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

    static String extractSenderWxid(String content) {
        if (content == null) return null;
        Matcher m = SENDER_PREFIX.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    static String removeSenderPrefix(String content) {
        if (content == null) return "";
        Matcher m = SENDER_PREFIX.matcher(content);
        return m.find() ? content.substring(m.end()) : content;
    }
}
