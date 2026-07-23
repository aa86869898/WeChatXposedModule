package com.leshao.v3.service;

import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageHandler {

    private static final Pattern SENDER_PREFIX_WXID = Pattern.compile("^(wxid_[a-zA-Z0-9]+):\\s*");
    private static final Pattern SENDER_PREFIX_ANY = Pattern.compile("^([a-zA-Z0-9_]+):\\s*");

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
                displayName = groupName + "群" + senderName;
            } else {
                displayName = groupName + "群";
            }
        } else {
            displayName = mNick.resolveDisplayName(talker);
        }

        LogWriter.log("MessageHandler", "handle type=" + msgType + " talker=" + talker + " name=" + displayName);

        switch (msgType) {
            case 1:  handleText(displayName, effectiveContent, cfg); break;
            case 3:  handleImage(displayName); break;
            case 34: handleVoice(displayName); VoiceRelay.process(talker, msgType); break;
            case 42: handleCard(displayName); break;
            case 43: handleVideo(displayName); break;
            case 48: handleLocation(displayName, effectiveContent); break;
            case 49: handleAppMsg(displayName, effectiveContent); break;
            case 318767153: handleRedPacket(displayName); break;
            case 419430449: handleTransfer(displayName); break;
            case 436207665: handleLuckyCard(displayName); break;
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
        mTts.speak(name + "发来语音");
    }

    private void handleImage(String name) {
        mTts.speak(name + "发来一张照片");
    }

    private void handleVideo(String name) {
        mTts.speak(name + "发来一段视频");
    }

    private void handleCard(String name) {
        mTts.speak(name + "发来一张名片");
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

    private void handleRedPacket(String name) {
        mTts.speak(name + "发来一个红包");
    }

    private void handleTransfer(String name) {
        mTts.speak(name + "发来一个转账");
    }

    private void handleLuckyCard(String name) {
        mTts.speak(name + "发来一张聚会券");
    }

    public void announceRedPacket(String sender, String chatroom, String wishing, String amount) {
        String senderName = sender != null ? mNick.resolveDisplayName(sender) : "好友";
        boolean isGroup = chatroom != null && chatroom.endsWith("@chatroom");

        if (isGroup) {
            String groupName = mNick.resolveDisplayName(chatroom);
            mTts.speak("成功抢到" + groupName + "群" + senderName + "发送的红包，金额" + amount + "元");
        } else {
            mTts.speak("成功领取" + senderName + "发来的红包，金额" + amount + "元");
        }
    }

    public void announceTransfer(String sender, String chatroom, String amount, String desc) {
        String senderName = sender != null ? mNick.resolveDisplayName(sender) : "好友";
        mTts.speak("成功领取" + senderName + "发来的转账，金额" + amount + "元");
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
        String label = extractXmlAttr(content, "label");
        String poiname = extractXmlAttr(content, "poiname");

        if (label.isEmpty() && poiname.isEmpty()) return "未知位置";
        if (label.isEmpty()) return poiname;
        if (poiname.isEmpty()) return label;
        if (poiname.startsWith(label)) return poiname;
        return label + poiname;
    }

    private static String extractXmlAttr(String content, String name) {
        int i = content.indexOf(name + "=\"");
        if (i < 0) return "";
        int s = i + name.length() + 2;
        int e = content.indexOf("\"", s);
        if (e <= s) return "";
        return content.substring(s, e);
    }

    static String extractSenderWxid(String content) {
        if (content == null) return null;
        Matcher m = SENDER_PREFIX_WXID.matcher(content);
        if (m.find()) return m.group(1);
        m = SENDER_PREFIX_ANY.matcher(content);
        if (m.find()) return m.group(1);
        return null;
    }

    static String removeSenderPrefix(String content) {
        if (content == null) return "";
        Matcher m = SENDER_PREFIX_WXID.matcher(content);
        if (m.find()) return content.substring(m.end());
        m = SENDER_PREFIX_ANY.matcher(content);
        if (m.find()) return content.substring(m.end());
        return content;
    }
}
