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
        if (!mFilter.shouldProcess(talker, msgType, content, cfg)) {
            LogWriter.log("MessageHandler", "DROPPED by filter: type=" + msgType + " from=" + talker + " masterSwitch=" + cfg.masterSwitch + " announceText=" + cfg.announceText);
            return;
        }

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
            case 47: handleSticker(displayName); break;
            case 48: handleLocation(displayName, effectiveContent); break;
            case 49: handleAppMsg(displayName, effectiveContent, isGroup); break;
            case 50: handleVoip(displayName); break;
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

    private void handleSticker(String name) {
        mTts.speak(name + "发来一个表情");
    }

    private void handleVoip(String name) {
        mTts.speak(name + "发起语音/视频通话");
    }

    private void handleAppMsg(String name, String content, boolean isGroup) {
        if (content == null) return;
        if (content.contains("<location")) {
            handleLocation(name, content);
            return;
        }
        if (content.contains("luckymoney") || content.contains("lucky money")) {
            mTts.speak(name + "发来一个红包");
            return;
        }
        if (content.contains("<type>57</type>")) {
            handleQuote(name, content, isGroup);
            return;
        }
        LogWriter.log("MessageHandler", "handleAppMsg unknown: " + (content.length() > 200 ? content.substring(0, 200) + "..." : content));
    }

    private void handleQuote(String name, String content, boolean isGroup) {
        String myWxid = ModuleConfig.getCurrentWxid();
        if (myWxid == null || myWxid.isEmpty()) return;

        String referBlock = extractXmlBlock(content, "refermsg");
        if (referBlock.isEmpty()) {
            LogWriter.log("MessageHandler", "handleQuote: referBlock empty, fallback speech");
            mTts.speak(name + "发来一条引用消息");
            return;
        }

        String fromUsr = extractXmlTag(referBlock, "fromusr");
        if (fromUsr.isEmpty()) {
            LogWriter.log("MessageHandler", "handleQuote: fromUsr empty, skip");
            return;
        }

        boolean isSelfQuote = myWxid.equals(fromUsr);
        if (!isSelfQuote && !isGroup) {
            LogWriter.log("MessageHandler", "handleQuote: not self-quote and not group, skip. from=" + fromUsr + " my=" + myWxid);
            return;
        }

        String quotedName = extractXmlTag(referBlock, "displayname");
        String quoteContent = extractXmlTag(referBlock, "content");
        String refType = extractXmlTag(referBlock, "type");
        String title = extractXmlTag(content, "title");

        LogWriter.log("MessageHandler", "handleQuote: self=" + isSelfQuote + " refType=[" + refType + "] quoteContent=[" + trunc(quoteContent) + "] title=[" + trunc(title) + "]");

        String mediaDesc = resolveMediaDesc(refType, quoteContent);
        String replyText = cleanText(title);

        StringBuilder sb = new StringBuilder();
        sb.append(name);
        if (isSelfQuote) {
            sb.append("引用你");
        } else {
            sb.append("在群引用");
            if (!quotedName.isEmpty()) {
                sb.append(quotedName);
            }
        }
        if (!mediaDesc.isEmpty()) {
            sb.append("发的").append(mediaDesc);
        }
        if (!replyText.isEmpty()) {
            sb.append(" 说：").append(replyText);
        }

        LogWriter.log("MessageHandler", "handleQuote: speech=[" + sb.toString() + "]");
        mTts.speak(sb.toString());
    }

    private String resolveMediaDesc(String refType, String quoteContent) {
        if (refType.equals("1")) {
            String cleaned = cleanText(quoteContent);
            if (cleaned.length() > 50) {
                cleaned = cleaned.substring(0, 50) + "等";
            }
            return cleaned;
        }
        switch (refType) {
            case "3":  return "照片";
            case "34": return "语音";
            case "43": return "视频";
            case "47": return "表情";
            case "49": return "链接";
            default:   return "消息";
        }
    }

    private static String trunc(String s) {
        if (s == null) return "null";
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }

    private static String extractXmlBlock(String xml, String tagName) {
        if (xml == null || tagName == null) return "";
        int start = xml.indexOf("<" + tagName + ">");
        if (start < 0) {
            start = xml.indexOf("<" + tagName + " ");
            if (start < 0) return "";
        }
        start = xml.indexOf(">", start);
        if (start < 0) return "";
        start++;
        int end = xml.indexOf("</" + tagName + ">", start);
        if (end < 0) return "";
        return xml.substring(start, end);
    }

    private static String extractXmlTag(String xml, String tagName) {
        if (xml == null || tagName == null) return "";
        int startIdx = xml.indexOf("<" + tagName + ">");
        if (startIdx < 0) {
            // 尝试自闭合标签格式
            startIdx = xml.indexOf("<" + tagName + " ");
            if (startIdx < 0) return "";
            int valStart = xml.indexOf(">", startIdx) + 1;
            int valEnd = xml.indexOf("</" + tagName + ">", valStart);
            if (valEnd < 0) return "";
            return xml.substring(valStart, valEnd);
        }
        startIdx += tagName.length() + 2;
        int endIdx = xml.indexOf("</" + tagName + ">", startIdx);
        if (endIdx < 0) return "";
        return xml.substring(startIdx, endIdx);
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
