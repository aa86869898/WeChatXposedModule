package com.leshao.v3.ai;

import java.util.List;

public class MessageReader {

    public static class ChatMsg {
        public final String role;
        public final String name;
        public final String content;
        public final long time;
        public final int type;
        public ChatMsg(String role, String name, String content, long time) {
            this(role, name, content, time, AiConst.TYPE_TEXT);
        }
        public ChatMsg(String role, String name, String content, long time, int type) {
            this.role = role; this.name = name; this.content = content; this.time = time; this.type = type;
        }
        public boolean isText() { return type == AiConst.TYPE_TEXT; }
    }

    private final AiMsgDb db;
    public MessageReader(ClassLoader cl) { this.db = new AiMsgDb(cl); }

    public List<ChatMsg> readRecent(String talker, int limit) {
        return db.readRecent(talker, limit);
    }

    public List<ChatMsg> readRecentFull(String talker, int limit) {
        return db.readRecentFull(talker, limit);
    }

    public static String toDialogText(List<ChatMsg> msgs) {
        StringBuilder sb = new StringBuilder();
        for (ChatMsg m : msgs) {
            if (!m.isText()) continue;
            String who = m.role.equals("me") ? "我" : (m.role.equals("system") ? "系统" : "对方");
            sb.append(who).append("：").append(m.content).append('\n');
        }
        return sb.toString();
    }

    public static int countText(List<ChatMsg> msgs) {
        int n = 0;
        for (ChatMsg m : msgs) if (m.isText()) n++;
        return n;
    }
}
