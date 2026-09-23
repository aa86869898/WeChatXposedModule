package com.leshao.ai.hook.wechat;

import android.util.Log;

import java.lang.reflect.Field;

/**
 * 群聊消息解析（文档 §7 线路四）。
 * <p>
 * 覆盖三项能力：
 * <ul>
 *   <li><b>群判定</b>：talker 以 {@code @chatroom} 结尾（45 个类交叉验证）。</li>
 *   <li><b>sender 解析</b>：群消息 content 存储格式为 {@code <senderWxId>:\n<实际正文>}
 *       （等价 b41.aa.u() 的 getGroupChatMsgTalkerPos 逻辑，模块内零反射复现）。</li>
 *   <li><b>@判定</b>：解析 msgsource 中的 {@code <atuserlist>} 列表（等价 e9.s2()），
 *       并兜底匹配机器人名 / @所有人 / @all。</li>
 * </ul>
 */
public final class GroupMsgParser {

    private static final String TAG = "LeshaoAI.GroupMsg";

    private GroupMsgParser() {
    }

    /** 群判定：talker 以 @chatroom 结尾。 */
    public static boolean isGroupTalker(String talker) {
        return talker != null && talker.endsWith("@chatroom");
    }

    /**
     * 拆分群消息 content。
     * <p>
     * 与 b41.aa.u() 实证逻辑一致：
     * 取第一个 ':' 前的 sender，拒绝含 '&lt;' 的非法前缀，去掉正文前导 '\n'。
     *
     * @return {@code [senderWxId, realContent]}；私聊或解析失败返回 {@code [null, content]}
     */
    public static String[] splitGroupContent(String content, boolean isGroup) {
        if (!isGroup || content == null) {
            return new String[]{null, content};
        }
        if (content.startsWith("~SEMI_XML~")) {
            return new String[]{null, content};
        }
        int pos = content.indexOf(':');
        if (pos <= 0) {
            return new String[]{null, content};
        }
        String sender = content.substring(0, pos);
        if (sender.indexOf('<') >= 0) {
            return new String[]{null, content};
        }
        String body = content.substring(pos + 1);
        if (body.startsWith("\n")) {
            body = body.substring(1);
        }
        return new String[]{sender, body};
    }

    /**
     * 从 MsgInfo 对象读取 msgsource 字段。
     * <p>
     * 文档 §14：e9 的混淆字段 {@code x2:String} 即 msgSource；
     * im.c8 的 DB 字段 {@code field_msgSource} 未混淆。两者都尝试，最后兜底遍历 String 字段。
     */
    public static String getMsgSource(Object msgInfo) {
        if (msgInfo == null) {
            return null;
        }
        String src = readStringField(msgInfo, "x2");
        if (src != null && src.contains("msgsource")) {
            return src;
        }
        src = readStringField(msgInfo, "field_msgSource");
        if (src != null && src.contains("msgsource")) {
            return src;
        }
        // 兜底：遍历所有 String 字段找 <msgsource
        try {
            for (java.lang.reflect.Field f : msgInfo.getClass().getFields()) {
                if (f.getType() != String.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object o = f.get(msgInfo);
                    if (o instanceof String && ((String) o).contains("<msgsource")) {
                        return (String) o;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * @判定（文档 §7.3，e9.s2() 的模块内零反射复现版）。
     *
     * @param msgInfo MsgInfo 对象（可为 null，则仅走兜底匹配）
     * @param meWxid  自己 wxid
     * @param body    正文（用于兜底匹配）
     * @param botName 机器人名（用于兜底匹配）
     */
    public static boolean isAtMe(Object msgInfo, String meWxid, String body, String botName) {
        return isAtMe(msgInfo, meWxid, body, botName, null);
    }

    /**
     * v985: 追加自身昵称兜底。机器人以本人微信号发言, 群里 @本人 时正文是
     * {@code @本人昵称}, 仅比对 botName(默认"小乐")会漏判, 故同时比对 selfNick。
     *
     * @param selfNick 自己微信昵称(可为 null)
     */
    public static boolean isAtMe(Object msgInfo, String meWxid, String body, String botName,
                                 String selfNick) {
        // 1) msgsource.atuserlist 精确判定
        if (meWxid != null && !meWxid.isEmpty()) {
            String src = getMsgSource(msgInfo);
            if (src != null && src.contains(meWxid)) {
                int a = src.indexOf("<atuserlist>");
                if (a >= 0) {
                    int b = src.indexOf("</atuserlist>", a);
                    if (b > a) {
                        for (String u : src.substring(a + 12, b).split(",")) {
                            if (u.trim().equals(meWxid)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        // 2) 兜底：正文含 @机器人名 / @自己昵称 / @所有人 / @all
        if (body != null && !body.isEmpty()) {
            if (botName != null && !botName.isEmpty() && body.contains("@" + botName)) {
                return true;
            }
            if (selfNick != null && !selfNick.isEmpty() && body.contains("@" + selfNick)) {
                return true;
            }
            if (body.contains("@所有人") || body.contains("@all") || body.contains("@All")) {
                return true;
            }
        }
        return false;
    }

    /** 正文是否命中任一唤醒关键词。 */
    public static boolean matchKeyword(String body, String[] keywords) {
        if (body == null || keywords == null || keywords.length == 0) {
            return false;
        }
        for (String kw : keywords) {
            if (kw != null && !kw.isEmpty() && body.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static String readStringField(Object obj, String name) {
        try {
            Class<?> c = obj.getClass();
            while (c != null && c != Object.class) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
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
}
