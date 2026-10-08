package com.leshao.ai.util

import android.text.TextUtils

import java.text.SimpleDateFormat
import java.util.Collection
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class ChatUtils private constructor() {

    companion object {
        private val GROUP_ID_PATTERN = Pattern.compile(".*@chatroom$")
        private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        @JvmStatic
        fun containsMention(msg: String?, botName: String?): Boolean {
            if (TextUtils.isEmpty(msg) || TextUtils.isEmpty(botName)) {
                return false
            }
            return msg!!.contains("@" + botName) || msg.contains(botName!!)
        }

        @JvmStatic
        fun containsKeyword(msg: String?, keywords: Array<String?>?): Boolean {
            if (TextUtils.isEmpty(msg) || keywords == null || keywords.isEmpty()) {
                return false
            }
            for (kw in keywords) {
                if (!TextUtils.isEmpty(kw) && msg!!.contains(kw!!)) {
                    return true
                }
            }
            return false
        }

        @JvmStatic
        fun containsMention(msg: String?, botNames: Collection<String>?): Boolean {
            if (TextUtils.isEmpty(msg) || botNames == null) {
                return false
            }
            for (name in botNames) {
                if (!TextUtils.isEmpty(name) && containsMention(msg, name)) {
                    return true
                }
            }
            return false
        }

        @JvmStatic
        fun stripMention(msg: String?, botName: String?): String {
            if (TextUtils.isEmpty(msg) || TextUtils.isEmpty(botName)) {
                return msg ?: ""
            }
            val atMention = "@" + botName
            var result = msg!!.replace(atMention, "")
            result = result.replace(botName!!, "")
            return result.trim()
        }

        @JvmStatic
        fun isWhitelisted(chatId: String?, whitelist: Collection<String>?): Boolean {
            if (TextUtils.isEmpty(chatId) || whitelist == null || whitelist.isEmpty()) {
                return false
            }
            return whitelist.contains(chatId)
        }

        @JvmStatic
        fun isGroupId(chatId: String?): Boolean {
            return chatId != null && GROUP_ID_PATTERN.matcher(chatId).matches()
        }

        @JvmStatic
        fun isPrivateId(chatId: String?): Boolean {
            return chatId != null && !GROUP_ID_PATTERN.matcher(chatId).matches()
        }

        @JvmStatic
        fun similarity(a: String?, b: String?): Double {
            if (a == null || b == null) {
                return 0.0
            }
            if (a == b) {
                return 1.0
            }
            val shorter = if (a.length < b.length) a else b
            val longer = if (a.length < b.length) b else a
            if (TextUtils.isEmpty(shorter)) {
                return 0.0
            }
            var hits = 0
            for (i in 0 until shorter.length) {
                if (longer.indexOf(shorter[i]) >= 0) {
                    hits++
                }
            }
            return hits.toDouble() / shorter.length
        }

        @JvmStatic
        fun now(): Long {
            return System.currentTimeMillis()
        }

        @JvmStatic
        fun formatTime(millis: Long): String {
            return TIME_FORMAT.format(Date(millis))
        }

        @JvmStatic
        fun ellipsize(text: String?, maxLen: Int): String {
            if (text == null || text.isEmpty() || text.length <= maxLen) return text ?: ""
            if (maxLen <= 0) {
                return ""
            }
            return text.substring(0, maxLen) + "…"
        }

        @JvmStatic
        fun isBlank(s: String?): Boolean {
            return s == null || s.trim().isEmpty()
        }
    }
}