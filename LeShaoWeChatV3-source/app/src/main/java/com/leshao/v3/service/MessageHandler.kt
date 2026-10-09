package com.leshao.v3.service

import com.leshao.v3.LogWriter
import com.leshao.v3.model.ModuleConfig
import com.leshao.v3.ui.MainActivity
import com.leshao.v3.wm.utils.WmPrefs

import java.util.regex.Matcher
import java.util.regex.Pattern

class MessageHandler(
    private val mTts: TtsEngine?,
    private val mCubeTts: CubeTtsPlayer?,
    private val mFilter: FilterManager?,
    private val mNick: NicknameResolver?
) {

    // 当前消息的 sender/group 名称, 由 handle() 设置
    private var mSenderName: String? = null
    private var mGroupName: String? = null
    private var mSenderWxid: String? = null
    private var mIsSelf = false

    fun speak(text: String?) {
        if (text == null || text.isEmpty()) return
        if (mCubeTts != null && WmPrefs.isTTSCube()) {
            mCubeTts.speak(text)
        } else if (mTts != null) {
            mTts.speak(text)
        }
    }

    fun handle(msgInfo: Any?, msgType: Int, talker: String?, content: String?, cfg: ModuleConfig) {
        val isGroup = talker != null && talker.endsWith("@chatroom")
        var effectiveContent = content

        mGroupName = null
        mSenderName = null
        mSenderWxid = null
        mIsSelf = false

        if (isGroup) {
            mGroupName = mNick?.resolveDisplayName(talker)
            val senderWxid = extractSenderWxid(content)
            if (senderWxid != null) {
                effectiveContent = removeSenderPrefix(content)
                mSenderName = mNick?.resolveDisplayName(senderWxid)
                mSenderWxid = senderWxid
            }
        } else {
            mSenderName = mNick?.resolveDisplayName(talker)
            mSenderWxid = talker
        }

        val myWxid = ModuleConfig.getCurrentWxid()
        if (myWxid != null && mSenderWxid != null && myWxid == mSenderWxid) {
            mIsSelf = true
        }

        LogWriter.log("MessageHandler", "handle type=" + msgType + " talker=" + talker
                + " sender=" + mSenderName + " group=" + mGroupName + " self=" + mIsSelf)

        val pass = mFilter?.shouldProcess(talker, msgType, content, cfg) ?: false
        if (!pass) {
            LogWriter.log("MessageHandler", "DROPPED by filter: type=" + msgType + " from=" + talker
                    + " masterSwitch=" + cfg.masterSwitch + " announceText=" + cfg.announceText
                    + " wl=" + cfg.announceWhitelist.size + " bl=" + cfg.announceBlacklist.size
                    + " strict=" + cfg.whitelistStrict
                    + " wlHit=" + (if (cfg.announceWhitelist.isEmpty()) "-" else cfg.announceWhitelist.contains(talker)))
            return
        }

        when (msgType) {
            1 -> handleText(effectiveContent, talker, isGroup, cfg)
            3 -> handleImage()
            6 -> handleFile()
            34 -> {
                handleVoice()
                VoiceRelay.process(talker, msgType)
            }
            42 -> handleCard()
            43 -> handleVideo()
            47 -> handleSticker()
            48 -> handleLocation(effectiveContent)
            49 -> handleAppMsg(effectiveContent, isGroup, cfg)
            50 -> handleVoip()
        }
    }

    private fun handleText(text: String?, talker: String?, isGroup: Boolean, cfg: ModuleConfig) {
        if (isGroup && cfg.announceAt) {
            val userNickname = MainActivity.getUserNickname()
            if (userNickname != null && userNickname.isNotEmpty()
                    && text != null && text.contains("@" + userNickname)) {
                var cleaned = cleanText(text)
                if (cleaned.isNotEmpty()) {
                    if (cfg.textTruncateEnabled && cfg.textCutoffLen > 0 && cleaned.length > cfg.textCutoffLen)
                        cleaned = cleaned.substring(0, cfg.textCutoffLen) + "等长内容"
                    speak(str(mSenderName) + "在" + str(mGroupName) + "群艾特了我说:" + cleaned)
                    return
                }
            }
        }

        var cleaned = cleanText(text)
        if (cleaned.isEmpty()) return

        if (cfg.textTruncateEnabled && cfg.textCutoffLen > 0 && cleaned.length > cfg.textCutoffLen)
            cleaned = cleaned.substring(0, cfg.textCutoffLen) + "等长内容"

        if (isGroup)
            speak(str(mSenderName) + "在" + str(mGroupName) + "群说:" + cleaned)
        else
            speak(str(mSenderName) + "说:" + cleaned)
    }

    private fun handleVoice() {
        val g = mGroupName
        if (g != null)
            speak(str(mSenderName) + "在" + g + "群说:")
        else
            speak(str(mSenderName) + "说:")
    }

    private fun handleImage() {
        val g = mGroupName
        if (g != null)
            speak(str(mSenderName) + "在" + g + "群分享一张照片")
        else
            speak(str(mSenderName) + "给你分享一张照片")
    }

    private fun handleVideo() {
        val g = mGroupName
        if (g != null)
            speak(str(mSenderName) + "在" + g + "群分享一段视频")
        else
            speak(str(mSenderName) + "给你分享一段视频")
    }

    private fun handleCard() {
        val g = mGroupName
        if (g != null)
            speak(str(mSenderName) + "在" + g + "群分享一张名片")
        else
            speak(str(mSenderName) + "发来一张名片")
    }

    private fun handleFile() {
        val g = mGroupName
        if (g != null)
            speak(str(mSenderName) + "在" + g + "群分享一个文件")
        else
            speak(str(mSenderName) + "给你发来一个文件")
    }

    private fun handleLocation(content: String?) {
        val loc = parseLocation(content)
        val g = mGroupName
        if (g != null)
            speak(str(mSenderName) + "在" + g + "群分享定位:" + loc)
        else
            speak(str(mSenderName) + "给你分享定位:" + loc)
    }

    private fun handleSticker() {
        val g = mGroupName
        if (g != null)
            speak(str(mSenderName) + "在" + g + "群发了一个表情")
        else
            speak(str(mSenderName) + "发来一个表情")
    }

    private fun handleVoip() {
        val g = mGroupName
        if (g != null)
            speak(str(mSenderName) + "在" + g + "群发起语音通话")
        else
            speak(str(mSenderName) + "给你发起语音通话")
    }

    private fun handleAppMsg(content: String?, isGroup: Boolean, cfg: ModuleConfig) {
        if (content == null) return
        if (content.contains("<location")) {
            handleLocation(content)
            return
        }
        if (content.contains("<type>57</type>")) {
            handleQuote(content, isGroup)
            return
        }
        if (cfg.announceMiniProgram && (content.contains("<weappinfo>") || content.contains("<type>33</type>"))) {
            if (isGroup)
                speak(str(mSenderName) + "在" + str(mGroupName) + "群分享一个小程序")
            else
                speak(str(mSenderName) + "给你分享一个小程序")
            return
        }
        if (cfg.announceVideoChannel && (content.contains("<finderFeed>") || content.contains("<type>2001</type>"))) {
            if (isGroup)
                speak(str(mSenderName) + "在" + str(mGroupName) + "群分享一个视频号")
            else
                speak(str(mSenderName) + "给你分享一个视频号")
            return
        }
        if (cfg.announceChatHistory && (content.contains("<recorditem>") || content.contains("<type>19</type>"))) {
            if (isGroup)
                speak(str(mSenderName) + "在" + str(mGroupName) + "群分享了聊天记录")
            else
                speak(str(mSenderName) + "给你发来聊天记录")
            return
        }
        LogWriter.log("MessageHandler", "handleAppMsg unknown: " + (if (content.length > 200) content.substring(0, 200) + "..." else content))
    }

    private fun handleQuote(content: String, isGroup: Boolean) {
        val myWxid = ModuleConfig.getCurrentWxid()
        if (myWxid == null || myWxid.isEmpty()) return

        val referBlock = extractXmlBlock(content, "refermsg")
        if (referBlock.isEmpty()) {
            LogWriter.log("MessageHandler", "handleQuote: referBlock empty, fallback speech")
            speak(str(mSenderName) + "发来一条引用消息")
            return
        }

        val fromUsr = extractXmlTag(referBlock, "fromusr")
        if (fromUsr.isEmpty()) {
            LogWriter.log("MessageHandler", "handleQuote: fromUsr empty, skip")
            return
        }

        val isSelfQuote = myWxid == fromUsr
        if (!isSelfQuote && !isGroup) {
            LogWriter.log("MessageHandler", "handleQuote: not self-quote and not group, skip. from=" + fromUsr + " my=" + myWxid)
            return
        }

        val quotedName = extractXmlTag(referBlock, "displayname")
        val quoteContent = extractXmlTag(referBlock, "content")
        val refType = extractXmlTag(referBlock, "type")
        val title = extractXmlTag(content, "title")

        LogWriter.log("MessageHandler", "handleQuote: self=" + isSelfQuote + " refType=[" + refType + "] quoteContent=[" + trunc(quoteContent) + "] title=[" + trunc(title) + "]")

        val mediaDesc = resolveMediaDesc(refType, quoteContent)
        val replyText = cleanText(title)

        val sb = StringBuilder()
        sb.append(str(mSenderName))
        if (isSelfQuote) {
            sb.append("引用你")
        } else {
            sb.append("在群引用")
            if (quotedName.isNotEmpty()) sb.append(quotedName)
        }
        if (mediaDesc.isNotEmpty()) sb.append("发的").append(mediaDesc)
        if (replyText.isNotEmpty()) sb.append("说:").append(replyText)

        LogWriter.log("MessageHandler", "handleQuote: speech=[" + sb.toString() + "]")
        speak(sb.toString())
    }

    private fun resolveMediaDesc(refType: String, quoteContent: String): String {
        if (refType == "1") {
            var cleaned = cleanText(quoteContent)
            if (cleaned.length > 50) cleaned = cleaned.substring(0, 50) + "等"
            return cleaned
        }
        return when (refType) {
            "3" -> "照片"
            "34" -> "语音"
            "43" -> "视频"
            "47" -> "表情"
            "49" -> "链接"
            else -> "消息"
        }
    }

    companion object {

        private val SENDER_PREFIX_WXID = Pattern.compile("^(wxid_[a-zA-Z0-9]+):\\s*")
        private val SENDER_PREFIX_ANY = Pattern.compile("^([a-zA-Z0-9_]+):\\s*")
        /** v961: 中文/混合昵称前缀(兜底, 避免前缀残留被 TTS 念出); 不含 / . < > 防误伤 URL/XML */
        private val SENDER_PREFIX_CN = Pattern.compile("^([\\u4e00-\\u9fa5\\w][\\u4e00-\\u9fa5\\w\\-]{0,31}):\\s*")

        // ========== 工具方法 ==========

        @JvmStatic
        fun cleanText(content: String?): String {
            if (content == null) return ""
            var t = sanitizeForTts(content)
            t = t
                .replace("<![CDATA[", "")
                .replace("]]>", "")
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("https?://\\S+"), "链接")
                .replace(Regex("@\\S+\\s+"), "")
                .replace(Regex("\\[\\w+\\]"), "")
                .replace("\n", " ")
                .trim()
            // 连续空白压缩为单个空格(零宽过滤后可能残留)
            t = t.replace(Regex("\\s{2,}"), " ").trim()
            return if (t.length > 300) t.substring(0, 300) + "等长内容" else t
        }

        /**
         * v961: TTS 朗读文本清洗 —— 去除 emoji/零宽水印/控制字符/HTML实体,
         * 修复"播报乱码"(TTS 引擎把 emoji、零宽字符、&amp; 实体读成异常音节)。
         */
        @JvmStatic
        fun sanitizeForTts(s: String?): String {
            if (s == null || s.isEmpty()) return ""
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                // 控制字符(含 \r \t)与不可见格式字符
                if (c.code < 0x20 || c.code == 0x7F) {
                    sb.append(' ')
                    i++
                    continue
                }
                // 零宽字符/水印/BOM/变体选择符/双向控制符
                if ((c.code >= 0x200B && c.code <= 0x200F) || c.code == 0x2060 || c.code == 0xFEFF
                        || (c.code >= 0x202A && c.code <= 0x202E) || (c.code >= 0x2066 && c.code <= 0x2069)) {
                    i++
                    continue
                }
                // 代理对高位: 整个码点判定, emoji/符号区直接丢弃
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                        val cp = Character.toCodePoint(c, s[i + 1])
                        if (isEmojiOrSymbol(cp)) {
                            i += 2 // 跳过低位代理
                            continue
                        }
                        sb.append(c).append(s[i + 1])
                        i += 2
                        continue
                    }
                    i++ // 孤立高位代理, 丢弃
                    continue
                }
                if (Character.isLowSurrogate(c)) {
                    i++ // 孤立低位代理, 丢弃
                    continue
                }
                // BMP 内 emoji/符号/装饰区块
                if (c.code >= 0x2190 && c.code <= 0x2BFF) { i++; continue }   // 箭头/数学/杂项符号/装饰
                if (c.code >= 0x1F000 && c.code <= 0x1FAFF) { i++; continue } // 部分 ROM 的 BMP 映射区
                if (c.code >= 0x2600 && c.code <= 0x27BF) { i++; continue }   // 杂项符号/装饰符号(☀☎✂)
                if (c.code >= 0xFE00 && c.code <= 0xFE0F) { i++; continue }   // 变体选择符
                if (c.code >= 0x1F1E6 && c.code <= 0x1F1FF) { i++; continue } // 区域指示符(旗帜)
                if (c.code >= 0xFE0F || (c.code >= 0x2B00 && c.code <= 0x2BFF)) { i++; continue }
                if (c.code >= 0xFF00 && c.code <= 0xFF0F) { i++; continue }   // 全角符号(！＠＃等非字母数字)
                sb.append(c)
                i++
            }
            var out = sb.toString()
            // 常见 HTML 实体
            out = out.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                     .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                     .replace("&nbsp;", " ").replace("&#x27;", "'")
            // 其余数字/十六进制实体
            val m = Pattern.compile("&#(x?[0-9a-fA-F]+);").matcher(out)
            val decoded = StringBuffer()
            while (m.find()) {
                val body = m.group(1) ?: continue
                try {
                    val cp = if (body.startsWith("x") || body.startsWith("X"))
                            Integer.parseInt(body.substring(1), 16)
                        else
                            Integer.parseInt(body)
                    if (cp > 0 && cp < 0x110000 && !isEmojiOrSymbol(cp)) {
                        m.appendReplacement(decoded, String(Character.toChars(cp)))
                    }
                } catch (ignored: Throwable) {
                }
            }
            m.appendTail(decoded)
            return decoded.toString()
        }

        private fun isEmojiOrSymbol(cp: Int): Boolean {
            return (cp >= 0x1F000 && cp <= 0x1FAFF)   // emoji/ pictographs
                    || (cp >= 0x2600 && cp <= 0x27BF) // 杂项符号
                    || (cp >= 0x2190 && cp <= 0x21FF) // 箭头
                    || (cp >= 0x2B00 && cp <= 0x2BFF) // 箭头补充/杂项
                    || (cp >= 0x1F1E6 && cp <= 0x1F1FF) // 旗帜
                    || (cp >= 0xFE00 && cp <= 0xFE0F)    // 变体选择符
        }

        @JvmStatic
        fun parseLocation(content: String?): String {
            if (content == null) return "未知位置"
            // v960: 微信位置消息中 label/poiname 是 XML 标签文本(带坐标属性), 不是属性;
            // 旧实现按属性提取必然落空 -> "未知位置"。先标签提取, 属性方式仅作兜底。
            var label = stripCdata(extractXmlTag(content, "label"))
            var poiname = stripCdata(extractXmlTag(content, "poiname"))
            if (label.isEmpty()) label = extractXmlAttr(content, "label")
            if (poiname.isEmpty()) poiname = extractXmlAttr(content, "poiname")

            if (label.isEmpty() && poiname.isEmpty()) return "未知位置"
            if (label.isEmpty()) return poiname
            if (poiname.isEmpty()) return label
            if (poiname.startsWith(label)) return poiname
            return label + poiname
        }

        private fun stripCdata(s: String?): String {
            if (s == null || s.isEmpty()) return ""
            return s.replace("<![CDATA[", "").replace("]]>", "").trim()
        }

        private fun str(s: String?): String { return s ?: "" }

        private fun extractXmlAttr(content: String, name: String): String {
            val i = content.indexOf(name + "=\"")
            if (i < 0) return ""
            val s = i + name.length + 2
            val e = content.indexOf("\"", s)
            if (e <= s) return ""
            return content.substring(s, e)
        }

        @JvmStatic
        fun extractSenderWxid(content: String?): String? {
            if (content == null) return null
            var m: Matcher = SENDER_PREFIX_WXID.matcher(content)
            if (m.find()) return m.group(1)
            m = SENDER_PREFIX_ANY.matcher(content)
            if (m.find()) return m.group(1)
            m = SENDER_PREFIX_CN.matcher(content)
            if (m.find()) return m.group(1)
            return null
        }

        @JvmStatic
        fun removeSenderPrefix(content: String?): String {
            if (content == null) return ""
            var m: Matcher = SENDER_PREFIX_WXID.matcher(content)
            if (m.find()) return content.substring(m.end())
            m = SENDER_PREFIX_ANY.matcher(content)
            if (m.find()) return content.substring(m.end())
            m = SENDER_PREFIX_CN.matcher(content)
            if (m.find()) return content.substring(m.end())
            return content
        }

        private fun trunc(s: String?): String {
            if (s == null) return "null"
            return if (s.length > 100) s.substring(0, 100) + "..." else s
        }

        private fun extractXmlBlock(xml: String?, tagName: String?): String {
            if (xml == null || tagName == null) return ""
            var start = xml.indexOf("<" + tagName + ">")
            if (start < 0) {
                start = xml.indexOf("<" + tagName + " ")
                if (start < 0) return ""
            }
            start = xml.indexOf(">", start)
            if (start < 0) return ""
            start++
            val end = xml.indexOf("</" + tagName + ">", start)
            if (end < 0) return ""
            return xml.substring(start, end)
        }

        private fun extractXmlTag(xml: String?, tagName: String?): String {
            if (xml == null || tagName == null) return ""
            var startIdx = xml.indexOf("<" + tagName + ">")
            if (startIdx < 0) {
                startIdx = xml.indexOf("<" + tagName + " ")
                if (startIdx < 0) return ""
                val valStart = xml.indexOf(">", startIdx) + 1
                val valEnd = xml.indexOf("</" + tagName + ">", valStart)
                if (valEnd < 0) return ""
                return xml.substring(valStart, valEnd)
            }
            startIdx += tagName.length + 2
            val endIdx = xml.indexOf("</" + tagName + ">", startIdx)
            if (endIdx < 0) return ""
            return xml.substring(startIdx, endIdx)
        }
    }
}
