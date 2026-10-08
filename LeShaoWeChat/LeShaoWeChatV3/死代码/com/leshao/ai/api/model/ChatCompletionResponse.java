package com.leshao.ai.api.model;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Chat 补全响应模型。
 * <p>
 * 封装 OpenAI Chat Completions 响应的关键字段，尤其提供从结构体中
 * 提取纯文本内容的方法。兼容 Chat Completions 与 Responses 两种格式的结构化解析。
 */
public class ChatCompletionResponse {

    private String id;
    private String model;
    private String content;
    /** 原始响应文本，便于上层自行解析 */
    private String raw;

    public ChatCompletionResponse() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getRaw() {
        return raw;
    }

    public void setRaw(String raw) {
        this.raw = raw;
    }

    /**
     * 从 OpenAI Chat Completions 响应 JSON 中提取文本。
     * 文本位于 choices[0].message.content。
     *
     * @param json 完整响应 JSON
     * @return 提取到的文本，若不存在返回空串
     */
    public static String extractTextFromChat(JSONObject json) {
        if (json == null) {
            return "";
        }
        try {
            JSONArray choices = json.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject first = choices.optJSONObject(0);
                if (first != null) {
                    JSONObject message = first.optJSONObject("message");
                    if (message != null) {
                        return String.valueOf(message.opt("content") == null ? "" : message.opt("content"));
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败时返回空串
        }
        return "";
    }

    /**
     * 从 OpenAI Responses 响应 JSON 中提取文本。
     * 文本位于 output[].content[].text（需遍历数组累加）。
     *
     * @param json 完整响应 JSON
     * @return 提取到的文本
     */
    public static String extractTextFromResponses(JSONObject json) {
        if (json == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try {
            JSONArray output = json.optJSONArray("output");
            if (output != null) {
                for (int i = 0; i < output.length(); i++) {
                    JSONObject item = output.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    JSONArray content = item.optJSONArray("content");
                    if (content != null) {
                        for (int j = 0; j < content.length(); j++) {
                            JSONObject c = content.optJSONObject(j);
                            if (c != null && c.optString("type", "").equals("output_text")) {
                                sb.append(c.optString("text", ""));
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败则返回已累积内容
        }
        return sb.toString();
    }
}
