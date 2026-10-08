package com.leshao.ai.api.model;

import org.json.JSONObject;
import org.json.JSONException;

/**
 * 对话消息模型。
 * <p>
 * 用于表示一次完整的会话消息内容。纯 Java + lombok 风格的 getter/setter，
 * 兼容 LSPosed 宿主进程（不依赖任何第三方库）。
 */
public class ChatMessage {

    /** 角色：system / user / assistant */
    private String role;
    /** 消息文本内容 */
    private String content;
    /** 时间戳（毫秒），用于排序或展示 */
    private long timestamp;

    public ChatMessage() {
    }

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
        this.timestamp = System.currentTimeMillis();
    }

    public ChatMessage(String role, String content, long timestamp) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * 序列化为 OpenAI Chat 格式的 JSON 对象：{ "role": ..., "content": ... }
     * 便于直接塞入请求体。
     */
    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("role", role);
            obj.put("content", content);
        } catch (JSONException ignored) {
            // role/content 均为可序列化类型，理论不会出错
        }
        return obj;
    }
}
