package com.leshao.ai.memory;

import org.json.JSONObject;

/**
 * 单条聊天消息模型。
 *
 * <p>用于记忆模块持久化，字段与 LLM 对话消息对齐：role / content / time。</p>
 */
public class ChatMessage {

    /** 消息角色：system / user / assistant。 */
    private final String role;
    /** 消息内容。 */
    private final String content;
    /** 时间戳（毫秒）。 */
    private final long time;

    public ChatMessage(String role, String content, long time) {
        this.role = role;
        this.content = content;
        this.time = time;
    }

    public ChatMessage(String role, String content) {
        this(role, content, System.currentTimeMillis());
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public long getTime() {
        return time;
    }

    /** 序列化为 JSON。 */
    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("role", role);
            obj.put("content", content);
            obj.put("time", time);
        } catch (org.json.JSONException ignored) {
            // 字段均为可序列化类型，理论不会出错
        }
        return obj;
    }

    /** 从 JSON 反序列化。 */
    public static ChatMessage fromJson(JSONObject obj) {
        return new ChatMessage(
                obj.optString("role", "user"),
                obj.optString("content", ""),
                obj.optLong("time", 0L)
        );
    }

    @Override
    public String toString() {
        return "ChatMessage{role='" + role + "', content='" + content + "', time=" + time + '}';
    }
}
