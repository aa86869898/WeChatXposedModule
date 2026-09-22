package com.leshao.ai.hook.wechat;

import android.util.Log;

import java.lang.reflect.Field;

/**
 * 聊天窗口辅助：从 ChattingUIFragment 中尽力提取当前会话 id（talker）。
 * 微信群：{@code <random>@chatroom}；联系人：{@code wxid_xxx} / {@code gh_xxx}。
 * <p>
 * 因字段名混淆，采用<b>遍历 String 字段并按特征匹配</b>的保守方案，
 * 失败返回 null，由调用方降级。
 */
public final class ChatRoomUtils {

    private static final String TAG = "LeshaoAI.ChatRoom";
    private static final String[] KNOWN_FIELD_NAMES = {
            "talker", "toUser", "toUsername", "username", "chatroomName",
            "talkerId", "sessionUserName", "field_username", "openId"
    };

    private ChatRoomUtils() {
    }

    /**
     * 尝试从聊天 Fragment 提取会话 id。
     */
    public static String extractChatId(Object fragment, ClassLoader cl) {
        if (fragment == null) {
            return null;
        }
        // 1) 优先按已知字段名
        for (String name : KNOWN_FIELD_NAMES) {
            String v = readStringField(fragment, name);
            if (isPlausibleChatId(v)) {
                return v;
            }
        }
        // 2) 兜底：遍历所有 String 字段，取疑似会话 id
        String best = null;
        int bestScore = 0;
        for (Field f : fragment.getClass().getDeclaredFields()) {
            if (f.getType() != String.class) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object o = f.get(fragment);
                if (!(o instanceof String)) {
                    continue;
                }
                String v = (String) o;
                int s = score(v);
                if (s > bestScore) {
                    bestScore = s;
                    best = v;
                }
            } catch (Throwable ignored) {
            }
        }
        if (best != null && bestScore > 0) {
            return best;
        }
        return null;
    }

    private static String readStringField(Object obj, String name) {
        try {
            Class<?> c = obj.getClass();
            while (c != null && c != Object.class) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    Object o = f.get(obj);
                    return (o instanceof String) ? (String) o : null;
                } catch (NoSuchFieldException ignored) {
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            Log.d(TAG, "readStringField(" + name + ") 失败");
        }
        return null;
    }

    /** 判断是否像会话 id。 */
    private static boolean isPlausibleChatId(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        return s.contains("@chatroom")
                || s.startsWith("wxid_")
                || s.startsWith("gh_")
                || s.startsWith("ghs_")
                || s.startsWith("wx_")
                || s.startsWith("o");
    }

    /** 打分：越像会话 id 分越高。 */
    private static int score(String s) {
        if (s == null) {
            return 0;
        }
        int sc = 0;
        if (s.contains("@chatroom")) {
            sc += 100;
        }
        if (s.startsWith("wxid")) {
            sc += 80;
        }
        if (s.startsWith("gh")) {
            sc += 70;
        }
        if (s.length() >= 8 && s.length() <= 64 && !s.contains(" ")) {
            sc += 10;
        }
        return sc;
    }

    /** 判断某会话 id 是否为群聊。 */
    public static boolean isGroup(String chatId) {
        return chatId != null && chatId.endsWith("@chatroom");
    }

    /** 判断会话 id 是否合法。 */
    public static boolean isValid(String chatId) {
        return isPlausibleChatId(chatId);
    }
}