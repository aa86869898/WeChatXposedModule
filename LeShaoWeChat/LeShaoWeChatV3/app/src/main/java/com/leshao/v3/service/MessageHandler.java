package com.leshao.v3.service;

import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.ui.MainActivity;
import com.leshao.v3.wm.utils.WmPrefs;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageHandler {

    private static final Pattern SENDER_PREFIX_WXID = Pattern.compile("^(wxid_[a-zA-Z0-9]+):\\s*");
    private static final Pattern SENDER_PREFIX_ANY = Pattern.compile("^([a-zA-Z0-9_]+):\\s*");

    private final TtsEngine mTts;
    private final CubeTtsPlayer mCubeTts;
    private final FilterManager mFilter;
    private final NicknameResolver mNick;

    // 当前消息的 sender/group 名称, 由 handle() 设置
    private String mSenderName;
    private String mGroupName;
    private String mSenderWxid;
    private boolean mIsSelf;

    public MessageHandler(TtsEngine tts, CubeTtsPlayer cubeTts, FilterManager filter, NicknameResolver nick) {
        this.mTts = tts;
        this.mCubeTts = cubeTts;
        this.mFilter = filter;
        this.mNick = nick;
    }

    void speak(String text) {
        if (text == null || text.isEmpty()) return;
        if (mCubeTts != null && WmPrefs.isTTSCube()) {
            mCubeTts.speak(text);
        } else if (mTts != null) {
            mTts.speak(text);
        }
    }

    public void handle(Object msgInfo, int msgType, String talker, String content, ModuleConfig cfg) {
        boolean isGroup = talker != null && talker.endsWith("@chatroom");
        String effectiveContent = content;

        mGroupName = null;
        mSenderName = null;
        mSenderWxid = null;
        mIsSelf = false;

        if (isGroup) {
            mGroupName = mNick.resolveDisplayName(talker);
            String senderWxid = extractSenderWxid(content);
            if (senderWxid != null) {
                effectiveContent = removeSenderPrefix(content);
                mSenderName = mNick.resolveDisplayName(senderWxid);
                mSenderWxid = senderWxid;
            }
        } else {
            mSenderName = mNick.resolveDisplayName(talker);
            mSenderWxid = talker;
        }

        String myWxid = ModuleConfig.getCurrentWxid();
        if (myWxid != null && mSenderWxid != null && myWxid.equals(mSenderWxid)) {
            mIsSelf = true;
        }

        LogWriter.log("MessageHandler", "handle type=" + msgType + " talker=" + talker
                + " sender=" + mSenderName + " group=" + mGroupName + " self=" + mIsSelf);

        if (!mFilter.shouldProcess(talker, msgType, content, cfg)) {
            LogWriter.log("MessageHandler", "DROPPED by filter: type=" + msgType + " from=" + talker
                    + " masterSwitch=" + cfg.masterSwitch + " announceText=" + cfg.announceText
                    + " wl=" + cfg.announceWhitelist.size() + " bl=" + cfg.announceBlacklist.size()
                    + " strict=" + cfg.whitelistStrict
                    + " wlHit=" + (cfg.announceWhitelist.isEmpty() ? "-" : cfg.announceWhitelist.contains(talker)));
            return;
        }

        switch (msgType) {
            case 1:  handleText(effectiveContent, talker, isGroup, cfg); break;
            case 3:  handleImage(); break;
            case 6:  handleFile(); break;
            case 34: handleVoice(); VoiceRelay.process(talker, msgType); break;
            case 42: handleCard(); break;
            case 43: handleVideo(); break;
            case 47: handleSticker(); break;
            case 48: handleLocation(effectiveContent); break;
            case 49: handleAppMsg(effectiveContent, isGroup, cfg); break;
            case 50: handleVoip(); break;
        }
    }

    private void handleText(String text, String talker, boolean isGroup, ModuleConfig cfg) {
        if (isGroup && cfg.announceAt) {
            String userNickname = MainActivity.getUserNickname();
            if (userNickname != null && !userNickname.isEmpty() && text.contains("@" + userNickname)) {
                String cleaned = cleanText(text);
                if (!cleaned.isEmpty()) {
                    if (cfg.textTruncateEnabled && cfg.textCutoffLen > 0 && cleaned.length() > cfg.textCutoffLen)
                        cleaned = cleaned.substring(0, cfg.textCutoffLen) + "等长内容";
                    speak(str(mSenderName) + "在" + str(mGroupName) + "群艾特了我说:" + cleaned);
                    return;
                }
            }
        }

        String cleaned = cleanText(text);
        if (cleaned.isEmpty()) return;

        if (cfg.textTruncateEnabled && cfg.textCutoffLen > 0 && cleaned.length() > cfg.textCutoffLen)
            cleaned = cleaned.substring(0, cfg.textCutoffLen) + "等长内容";

        if (isGroup)
            speak(str(mSenderName) + "在" + str(mGroupName) + "群说:" + cleaned);
        else
            speak(str(mSenderName) + "说:" + cleaned);
    }

    private void handleVoice() {
        if (mGroupName != null)
            speak(str(mSenderName) + "在" + mGroupName + "群说:");
        else
            speak(str(mSenderName) + "说:");
    }

    private void handleImage() {
        if (mGroupName != null)
            speak(str(mSenderName) + "在" + mGroupName + "群分享一张照片");
        else
            speak(str(mSenderName) + "给你分享一张照片");
    }

    private void handleVideo() {
        if (mGroupName != null)
            speak(str(mSenderName) + "在" + mGroupName + "群分享一段视频");
        else
            speak(str(mSenderName) + "给你分享一段视频");
    }

    private void handleCard() {
        if (mGroupName != null)
            speak(str(mSenderName) + "在" + mGroupName + "群分享一张名片");
        else
            speak(str(mSenderName) + "发来一张名片");
    }

    private void handleFile() {
        if (mGroupName != null)
            speak(str(mSenderName) + "在" + mGroupName + "群分享一个文件");
        else
            speak(str(mSenderName) + "给你发来一个文件");
    }

    private void handleLocation(String content) {
        String loc = parseLocation(content);
        if (mGroupName != null)
            speak(str(mSenderName) + "在" + mGroupName + "群分享定位:" + loc);
        else
            speak(str(mSenderName) + "给你分享定位:" + loc);
    }

    private void handleSticker() {
        if (mGroupName != null)
            speak(str(mSenderName) + "在" + mGroupName + "群发了一个表情");
        else
            speak(str(mSenderName) + "发来一个表情");
    }

    private void handleVoip() {
        if (mGroupName != null)
            speak(str(mSenderName) + "在" + mGroupName + "群发起语音通话");
        else
            speak(str(mSenderName) + "给你发起语音通话");
    }

    private void handleAppMsg(String content, boolean isGroup, ModuleConfig cfg) {
        if (content == null) return;
        if (content.contains("<location")) {
            handleLocation(content);
            return;
        }
        if (content.contains("luckymoney") || content.contains("lucky money")
                || content.contains("红包")) {
            if (isGroup)
                speak(str(mGroupName) + "群正在发红包");
            else
                speak(str(mSenderName) + "给你发来一个红包");
            return;
        }
        if (cfg.announceTransfer && (content.contains("transferid") || content.contains("remittance")
                || content.contains("transfer"))) {
            if (isGroup)
                speak(str(mSenderName) + "在" + str(mGroupName) + "群发来转账");
            else
                speak(str(mSenderName) + "给你发来转账");
            return;
        }
        if (content.contains("<type>57</type>")) {
            handleQuote(content, isGroup);
            return;
        }
        if (cfg.announceMiniProgram && (content.contains("<weappinfo>") || content.contains("<type>33</type>"))) {
            if (isGroup)
                speak(str(mSenderName) + "在" + str(mGroupName) + "群分享一个小程序");
            else
                speak(str(mSenderName) + "给你分享一个小程序");
            return;
        }
        if (cfg.announceVideoChannel && (content.contains("<finderFeed>") || content.contains("<type>2001</type>"))) {
            if (isGroup)
                speak(str(mSenderName) + "在" + str(mGroupName) + "群分享一个视频号");
            else
                speak(str(mSenderName) + "给你分享一个视频号");
            return;
        }
        if (cfg.announceChatHistory && (content.contains("<recorditem>") || content.contains("<type>19</type>"))) {
            if (isGroup)
                speak(str(mSenderName) + "在" + str(mGroupName) + "群分享了聊天记录");
            else
                speak(str(mSenderName) + "给你发来聊天记录");
            return;
        }
        LogWriter.log("MessageHandler", "handleAppMsg unknown: " + (content.length() > 200 ? content.substring(0, 200) + "..." : content));
    }

    private void handleQuote(String content, boolean isGroup) {
        String myWxid = ModuleConfig.getCurrentWxid();
        if (myWxid == null || myWxid.isEmpty()) return;

        String referBlock = extractXmlBlock(content, "refermsg");
        if (referBlock.isEmpty()) {
            LogWriter.log("MessageHandler", "handleQuote: referBlock empty, fallback speech");
            speak(str(mSenderName) + "发来一条引用消息");
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
        sb.append(str(mSenderName));
        if (isSelfQuote) {
            sb.append("引用你");
        } else {
            sb.append("在群引用");
            if (!quotedName.isEmpty()) sb.append(quotedName);
        }
        if (!mediaDesc.isEmpty()) sb.append("发的").append(mediaDesc);
        if (!replyText.isEmpty()) sb.append("说:").append(replyText);

        LogWriter.log("MessageHandler", "handleQuote: speech=[" + sb.toString() + "]");
        speak(sb.toString());
    }

    private String resolveMediaDesc(String refType, String quoteContent) {
        if (refType.equals("1")) {
            String cleaned = cleanText(quoteContent);
            if (cleaned.length() > 50) cleaned = cleaned.substring(0, 50) + "等";
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

    // ========== 红包/转账领取播报 ==========

    public void announceRedPacket(String sender, String chatroom, String wishing, String amount) {
        String senderName = sender != null ? mNick.resolveDisplayName(sender) : "好友";
        boolean isGroup = chatroom != null && chatroom.endsWith("@chatroom");

        if (isGroup) {
            String groupName = mNick.resolveDisplayName(chatroom);
            speak("成功抢到" + groupName + "群" + senderName + "发的红包，金额" + amount + "元");
        } else {
            speak("成功领取" + senderName + "给你的红包，金额" + amount + "元");
        }
    }

    public void announceTransfer(String sender, String chatroom, String amount, String desc) {
        String senderName = sender != null ? mNick.resolveDisplayName(sender) : "好友";
        speak("成功领取" + senderName + "给你的转账，金额" + amount + "元");
    }

    // ========== 工具方法 ==========

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

    private static String str(String s) { return s != null ? s : ""; }

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

    public static String removeSenderPrefix(String content) {
        if (content == null) return "";
        Matcher m = SENDER_PREFIX_WXID.matcher(content);
        if (m.find()) return content.substring(m.end());
        m = SENDER_PREFIX_ANY.matcher(content);
        if (m.find()) return content.substring(m.end());
        return content;
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
}
