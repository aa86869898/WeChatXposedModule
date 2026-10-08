package com.leshao.ai.util;

import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 微信聊天通用工具类。
 *
 * <p>提供机器人名匹配、关键词触发、@提及剥离、白名单判断以及时间/字符串等常用工具。</p>
 */
public final class ChatUtils {

    private ChatUtils() {
        // 工具类，禁止实例化
    }

    /** 匹配一个群 ID 全称：微信群 id 以 @chatroom 结尾，如 "12345@chatroom"。 */
    private static final Pattern GROUP_ID_PATTERN = Pattern.compile(".*@chatroom$");

    /** 时区无关的日期时间格式化器。 */
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    /**
     * 判断消息文本是否提到机器人名。
     *
     * <p>会同时匹配“@机器人名”和纯文本提及（包含机器人名）。</p>
     *
     * @param msg     原始消息文本
     * @param botName 机器人名，如 小乐
     * @return 提到返回 true
     */
    public static boolean containsMention(String msg, String botName) {
        if (TextUtils.isEmpty(msg) || TextUtils.isEmpty(botName)) {
            return false;
        }
        return msg.contains("@" + botName) || msg.contains(botName);
    }

    /**
     * 判断消息是否提到或包含任一关键词。
     *
     * @param msg      原始消息文本
     * @param keywords 关键词数组，可为空
     * @return 命中任一关键词返回 true
     */
    public static boolean containsKeyword(String msg, String[] keywords) {
        if (TextUtils.isEmpty(msg) || keywords == null || keywords.length == 0) {
            return false;
        }
        for (String kw : keywords) {
            if (!TextUtils.isEmpty(kw) && msg.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断消息是否提到任意一个机器人名（多个候选名场景）。
     *
     * @param msg       原始消息文本
     * @param botNames  机器人名集合，可为空
     * @return 命中返回 true
     */
    public static boolean containsMention(String msg, Collection<String> botNames) {
        if (TextUtils.isEmpty(msg) || botNames == null) {
            return false;
        }
        for (String name : botNames) {
            if (!TextUtils.isEmpty(name) && containsMention(msg, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 去掉消息中的“@机器人名”提及，返回清理后的文本。
     *
     * <p>支持 "@小乐"、"@小乐 "、"@小乐，" 等常见带空格的格式。</p>
     *
     * @param msg     原始消息文本
     * @param botName 机器人名
     * @return 去掉 @提及后的文本，未提及则原样返回
     */
    public static String stripMention(String msg, String botName) {
        if (TextUtils.isEmpty(msg) || TextUtils.isEmpty(botName)) {
            return msg;
        }
        String atMention = "@" + botName;
        String result = msg.replace(atMention, "");
        // 兜底：直接替换机器人名本身（纯文本提及）
        result = result.replace(botName, "");
        // 清理开头可能的空白，避免 prompt 前有多余空格
        return result.trim();
    }

    /**
     * 判断 chatId 是否在白名单内（talker 精确匹配）。
     *
     * @param chatId    群或联系人 id
     * @param whitelist 白名单 id 集合，可为空
     * @return 在白名单内返回 true
     */
    public static boolean isWhitelisted(String chatId, Collection<String> whitelist) {
        if (TextUtils.isEmpty(chatId) || whitelist == null || whitelist.isEmpty()) {
            return false;
        }
        return whitelist.contains(chatId);
    }

    /**
     * 判断是否为群聊 id（微信群 id 以 @chatroom 结尾）。
     *
     * @param chatId 群或联系人 id
     * @return 群 id 返回 true
     */
    public static boolean isGroupId(String chatId) {
        return chatId != null && GROUP_ID_PATTERN.matcher(chatId).matches();
    }

    /** 判断 chatId 是否为私聊联系人 id（非群）。 */
    public static boolean isPrivateId(String chatId) {
        return chatId != null && !GROUP_ID_PATTERN.matcher(chatId).matches();
    }

    /** 计算两条字符串的相似度，用于简单打分（0~1）。 */
    public static double similarity(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        // 使用简单字符级重合率作为打分近似
        String shorter = a.length() < b.length() ? a : b;
        String longer = a.length() < b.length() ? b : a;
        if (TextUtils.isEmpty(shorter)) {
            return 0.0;
        }
        int hits = 0;
        for (int i = 0; i < shorter.length(); i++) {
            if (longer.indexOf(shorter.charAt(i)) >= 0) {
                hits++;
            }
        }
        return (double) hits / shorter.length();
    }

    /** 当前时间戳（毫秒）。 */
    public static long now() {
        return System.currentTimeMillis();
    }

    /** 格式化时间戳为字符串：yyyy-MM-dd HH:mm:ss。 */
    public static String formatTime(long millis) {
        return TIME_FORMAT.format(new Date(millis));
    }

    /** 把文本裁剪到指定最大长度，超出则加省略号。 */
    public static String ellipsize(String text, int maxLen) {
        if (TextUtils.isEmpty(text) || text.length() <= maxLen) {
            return text;
        }
        if (maxLen <= 0) {
            return "";
        }
        return text.substring(0, maxLen) + "…";
    }

    /** 判断字符串是否为空（null 或空白）。 */
    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
