package com.leshao.v3.ai;

import java.util.List;

public class MessageReader {

    public static class ChatMsg {
        public final String role;
        public final String name;
        public final String content;
        public final long time;
        public ChatMsg(String role, String name, String content, long time) {
            this.role = role; this.name = name; this.content = content; this.time = time;
        }
    }

    private final AiMsgDb db;
    public MessageReader(ClassLoader cl) { this.db = new AiMsgDb(cl); }

    public List<ChatMsg> readRecent(String talker, int limit) {
        return db.readRecent(talker, limit);
    }

    public static String toDialogText(List<ChatMsg> msgs) {
        StringBuilder sb = new StringBuilder();
        for (ChatMsg m : msgs) {
            String who = m.role.equals("me") ? "我" : (m.role.equals("system") ? "系统" : "对方");
            sb.append(who).append("：").append(m.content).append('\n');
        }
        return sb.toString();
    }
}
