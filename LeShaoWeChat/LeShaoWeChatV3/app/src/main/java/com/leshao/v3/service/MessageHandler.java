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
    /** v961: 中文/混合昵称前缀(兜底, 避免前缀残留被 TTS 念出); 不含 / . < > 防误伤 URL/XML */
    private static final Pattern SENDER_PREFIX_CN = Pattern.compile("^([\\u4e00-\\u9fa5\\w][\\u4e00-\\u9fa5\\w\\-]{0,31}):\\s*");

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
        String t = sanitizeForTts(content);
        t = t
            .replace("<![CDATA[", "").replace("]]>", "")
            .replaceAll("<[^>]+>", "")
            .replaceAll("https?://\\S+", "链接")
            .replaceAll("@\\S+\\s+", "")
            .replaceAll("\\[\\w+\\]", "")
            .replace("\n", " ").trim();
        // 连续空白压缩为单个空格(零宽过滤后可能残留)
        t = t.replaceAll("\\s{2,}", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) + "等长内容" : t;
    }

    /**
     * v961: TTS 朗读文本清洗 —— 去除 emoji/零宽水印/控制字符/HTML实体,
     * 修复"播报乱码"(TTS 引擎把 emoji、零宽字符、&amp; 实体读成异常音节)。
     */
    static String sanitizeForTts(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // 控制字符(含 \r \t)与不可见格式字符
            if (c < 0x20 || c == 0x7F) {
                sb.append(' ');
                continue;
            }
            // 零宽字符/水印/BOM/变体选择符/双向控制符
            if ((c >= 0x200B && c <= 0x200F) || c == 0x2060 || c == 0xFEFF
                    || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)) {
                continue;
            }
            // 代理对高位: 整个码点判定, emoji/符号区直接丢弃
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    int cp = Character.toCodePoint(c, s.charAt(i + 1));
                    if (isEmojiOrSymbol(cp)) {
                        i++; // 跳过低位代理
                        continue;
                    }
                    sb.append(c).append(s.charAt(i + 1));
                    i++;
                    continue;
                }
                continue; // 孤立高位代理, 丢弃
            }
            if (Character.isLowSurrogate(c)) {
                continue; // 孤立低位代理, 丢弃
            }
            // BMP 内 emoji/符号/装饰区块
            if (c >= 0x2190 && c <= 0x2BFF) continue;   // 箭头/数学/杂项符号/装饰
            if (c >= 0x1F000 && c <= 0x1FAFF) continue; // 部分 ROM 的 BMP 映射区
            if (c >= 0x2600 && c <= 0x27BF) continue;   // 杂项符号/装饰符号(☀☎✂)
            if (c >= 0xFE00 && c <= 0xFE0F) continue;   // 变体选择符
            if (c >= 0x1F1E6 && c <= 0x1F1FF) continue; // 区域指示符(旗帜)
            if (c >= 0xFE0F || (c >= 0x2B00 && c <= 0x2BFF)) continue;
            if (c >= 0xFF00 && c <= 0xFF0F) continue;   // 全角符号(！＠＃等非字母数字)
            sb.append(c);
        }
        String out = sb.toString();
        // 常见 HTML 实体
        out = out.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                 .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                 .replace("&nbsp;", " ").replace("&#x27;", "'");
        // 其余数字/十六进制实体
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("&#(x?[0-9a-fA-F]+);").matcher(out);
        StringBuffer decoded = new StringBuffer();
        while (m.find()) {
            String body = m.group(1);
            try {
                int cp = body.startsWith("x") || body.startsWith("X")
                        ? Integer.parseInt(body.substring(1), 16)
                        : Integer.parseInt(body);
                if (cp > 0 && cp < 0x110000 && !isEmojiOrSymbol(cp)) {
                    m.appendReplacement(decoded, new String(Character.toChars(cp)));
                }
            } catch (Throwable ignored) {
            }
        }
        m.appendTail(decoded);
        return decoded.toString();
    }

    private static boolean isEmojiOrSymbol(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)   // emoji/ pictographs
                || (cp >= 0x2600 && cp <= 0x27BF) // 杂项符号
                || (cp >= 0x2190 && cp <= 0x21FF) // 箭头
                || (cp >= 0x2B00 && cp <= 0x2BFF) // 箭头补充/杂项
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF) // 旗帜
                || (cp >= 0xFE00 && cp <= 0xFE0F);   // 变体选择符
    }

    static String parseLocation(String content) {
        if (content == null) return "未知位置";
        // v960: 微信位置消息中 label/poiname 是 XML 标签文本(带坐标属性), 不是属性;
        // 旧实现按属性提取必然落空 -> "未知位置"。先标签提取, 属性方式仅作兜底。
        String label = stripCdata(extractXmlTag(content, "label"));
        String poiname = stripCdata(extractXmlTag(content, "poiname"));
        if (label.isEmpty()) label = extractXmlAttr(content, "label");
        if (poiname.isEmpty()) poiname = extractXmlAttr(content, "poiname");

        if (label.isEmpty() && poiname.isEmpty()) return "未知位置";
        if (label.isEmpty()) return poiname;
        if (poiname.isEmpty()) return label;
        if (poiname.startsWith(label)) return poiname;
        return label + poiname;
    }

    private static String stripCdata(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("<![CDATA[", "").replace("]]>", "").trim();
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
        m = SENDER_PREFIX_CN.matcher(content);
        if (m.find()) return m.group(1);
        return null;
    }

    public static String removeSenderPrefix(String content) {
        if (content == null) return "";
        Matcher m = SENDER_PREFIX_WXID.matcher(content);
        if (m.find()) return content.substring(m.end());
        m = SENDER_PREFIX_ANY.matcher(content);
        if (m.find()) return content.substring(m.end());
        m = SENDER_PREFIX_CN.matcher(content);
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
