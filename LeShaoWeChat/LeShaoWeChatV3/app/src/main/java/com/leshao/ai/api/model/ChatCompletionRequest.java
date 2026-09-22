package com.leshao.ai.api.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat 补全请求模型。
 * <p>
 * 对应 OpenAI Chat Completions API 的请求体结构，供客户端序列化 JSON 使用。
 */
public class ChatCompletionRequest {

    private List<ChatMessage> messages;
    private String model;
    private Double temperature;
    private Integer maxTokens;
    private String system;

    public ChatCompletionRequest() {
        this.messages = new ArrayList<>();
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public String getSystem() {
        return system;
    }

    public void setSystem(String system) {
        this.system = system;
    }

    /**
     * 序列化为 OpenAI Chat Completions 请求 JSON。
     * system 指令会作为第一条 message（role=system）注入到 messages 之前。
     */
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("model", model);
        if (temperature != null) {
            json.put("temperature", temperature);
        }
        if (maxTokens != null) {
            json.put("max_tokens", maxTokens);
        }

        JSONArray arr = new JSONArray();
        // 如果存在 system 指令且尚未出现在消息首条，则注入
        if (system != null && !system.isEmpty()) {
            arr.put(new ChatMessage("system", system).toJson());
        }
        if (messages != null) {
            for (ChatMessage m : messages) {
                arr.put(m.toJson());
            }
        }
        json.put("messages", arr);
        return json;
    }
}
