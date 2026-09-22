package com.leshao.ai.config;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 微信 AI 模块全局配置。
 *
 * <p>配置以磁盘 JSON 文件形式持久化，供宿主进程读写。
 * 默认存储在 /data/data/{hostPackage}/files/leshao_ai/config.json，
 * 也支持通过构造传入自定义文件路径。</p>
 *
 * <p>所有读取/写入方法均做 synchronized 同步，保证多线程安全。</p>
 */
public class AppConfig {

    /** 配置文件名（默认路径下）。 */
    public static final String DEFAULT_CONFIG_FILE = "leshao_ai/config.json";

    private final File configFile;

    // ---------- 配置字段 ----------
    /** 总开关：是否启用微信 AI 回复。 */
    private boolean enabled = false;
    /** 服务商类型，如 "openai" / "deepseek" / "qwen" / "custom" 等。 */
    private String providerType = "openai";
    /** 服务商 API Base URL。 */
    private String baseUrl = "https://api.openai.com/v1";
    /** API 密钥。 */
    private String apiKey = "";
    /** 使用的模型名，如 gpt-4o-mini / deepseek-chat。 */
    private String model = "gpt-4o-mini";
    /** 采样温度，0.0 ~ 2.0。 */
    private double temperature = 0.7;
    /** 生成回复的最大 token 数。 */
    private int maxTokens = 800;
    /** 系统提示词（人设）。 */
    private String systemPrompt = "你是一个贴心、幽默的微信AI助手，用中文回答用户问题。";
    /** 是否启用文本转语音。 */
    private boolean ttsEnabled = false;
    /** 机器人名字，如 小乐。 */
    private String botName = "小乐";
    /** 唤醒关键词（触发 AI 的关键词）。 */
    private String wakeKeyword = "";
    /** 是否在群聊中自动回复。 */
    private boolean autoReplyInGroups = true;
    /** 是否在私聊中自动回复。 */
    private boolean autoReplyInPrivate = true;
    /** 是否仅在 @机器人/提到机器名 时回复。 */
    private boolean onlyWhenMentioned = false;
    /** 记忆保留的最大历史消息条数（环形上限）。 */
    private int maxHistoryMessages = 50;

    /**
     * 使用默认路径构造配置对象。
     *
     * @param hostDataDir 宿主应用 data 目录（Context.getFilesDir().getParent()）
     */
    public AppConfig(String hostDataDir) {
        this(new File(hostDataDir, DEFAULT_CONFIG_FILE));
    }

    /**
     * 使用自定义文件路径构造配置对象。
     *
     * @param configFile 配置文件路径
     */
    public AppConfig(File configFile) {
        this.configFile = configFile;
    }

    /**
     * 从磁盘加载配置。若文件不存在或解析失败，则保留当前默认值。
     */
    public synchronized void load() {
        if (configFile == null || !configFile.exists()) {
            return;
        }
        try (FileInputStream in = new FileInputStream(configFile)) {
            byte[] data = new byte[(int) configFile.length()];
            int read = in.read(data);
            if (read <= 0) {
                return;
            }
            JSONObject obj = new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            parse(obj);
        } catch (IOException | JSONException e) {
            // 解析失败时保留内存中的默认值，不影响运行
        }
    }

    /**
     * 把当前配置保存到磁盘。
     *
     * @return 保存成功返回 true
     */
    public synchronized boolean save() {
        if (configFile == null) {
            return false;
        }
        try {
            File parent = configFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            JSONObject obj = toJson();
            try (FileOutputStream out = new FileOutputStream(configFile, false)) {
                out.write(obj.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (IOException | JSONException e) {
            return false;
        }
    }

    /** 解析 JSON 对象到字段。 */
    private void parse(JSONObject obj) {
        enabled = obj.optBoolean("enabled", enabled);
        providerType = obj.optString("providerType", providerType);
        baseUrl = obj.optString("baseUrl", baseUrl);
        apiKey = obj.optString("apiKey", apiKey);
        model = obj.optString("model", model);
        temperature = obj.optDouble("temperature", temperature);
        maxTokens = obj.optInt("maxTokens", maxTokens);
        systemPrompt = obj.optString("systemPrompt", systemPrompt);
        ttsEnabled = obj.optBoolean("ttsEnabled", ttsEnabled);
        botName = obj.optString("botName", botName);
        wakeKeyword = obj.optString("wakeKeyword", wakeKeyword);
        autoReplyInGroups = obj.optBoolean("autoReplyInGroups", autoReplyInGroups);
        autoReplyInPrivate = obj.optBoolean("autoReplyInPrivate", autoReplyInPrivate);
        onlyWhenMentioned = obj.optBoolean("onlyWhenMentioned", onlyWhenMentioned);
        maxHistoryMessages = obj.optInt("maxHistoryMessages", maxHistoryMessages);
    }

    /** 将字段序列化为 JSON 对象。 */
    private JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("enabled", enabled);
        obj.put("providerType", providerType);
        obj.put("baseUrl", baseUrl);
        obj.put("apiKey", apiKey);
        obj.put("model", model);
        obj.put("temperature", temperature);
        obj.put("maxTokens", maxTokens);
        obj.put("systemPrompt", systemPrompt);
        obj.put("ttsEnabled", ttsEnabled);
        obj.put("botName", botName);
        obj.put("wakeKeyword", wakeKeyword);
        obj.put("autoReplyInGroups", autoReplyInGroups);
        obj.put("autoReplyInPrivate", autoReplyInPrivate);
        obj.put("onlyWhenMentioned", onlyWhenMentioned);
        obj.put("maxHistoryMessages", maxHistoryMessages);
        return obj;
    }

    /** 重置为默认值。 */
    public synchronized void reset() {
        enabled = false;
        providerType = "openai";
        baseUrl = "https://api.openai.com/v1";
        apiKey = "";
        model = "gpt-4o-mini";
        temperature = 0.7;
        maxTokens = 800;
        systemPrompt = "你是一个贴心、幽默的微信AI助手，用中文回答用户问题。";
        ttsEnabled = false;
        botName = "小乐";
        wakeKeyword = "";
        autoReplyInGroups = true;
        autoReplyInPrivate = true;
        onlyWhenMentioned = false;
        maxHistoryMessages = 50;
    }

    /** 判断是否是合法可用的配置（总开关打开且填了 baseUrl）。 */
    public synchronized boolean isUsable() {
        return enabled && !TextUtils.isEmpty(baseUrl);
    }

    // ---------- getters / setters ----------
    public synchronized boolean isEnabled() { return enabled; }
    public synchronized void setEnabled(boolean enabled) { this.enabled = enabled; }

    public synchronized String getProviderType() { return providerType; }
    public synchronized void setProviderType(String providerType) {
        this.providerType = providerType == null ? "" : providerType;
    }

    public synchronized String getBaseUrl() { return baseUrl; }
    public synchronized void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl;
    }

    public synchronized String getApiKey() { return apiKey; }
    public synchronized void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey; }

    public synchronized String getModel() { return model; }
    public synchronized void setModel(String model) { this.model = model == null ? "" : model; }

    public synchronized double getTemperature() { return temperature; }
    public synchronized void setTemperature(double temperature) { this.temperature = temperature; }

    public synchronized int getMaxTokens() { return maxTokens; }
    public synchronized void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public synchronized String getSystemPrompt() { return systemPrompt; }
    public synchronized void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
    }

    public synchronized boolean isTtsEnabled() { return ttsEnabled; }
    public synchronized void setTtsEnabled(boolean ttsEnabled) { this.ttsEnabled = ttsEnabled; }

    public synchronized String getBotName() { return botName; }
    public synchronized void setBotName(String botName) { this.botName = botName == null ? "" : botName; }

    public synchronized String getWakeKeyword() { return wakeKeyword; }
    public synchronized void setWakeKeyword(String wakeKeyword) { this.wakeKeyword = wakeKeyword == null ? "" : wakeKeyword; }

    public synchronized boolean isAutoReplyInGroups() { return autoReplyInGroups; }
    public synchronized void setAutoReplyInGroups(boolean autoReplyInGroups) { this.autoReplyInGroups = autoReplyInGroups; }

    public synchronized boolean isAutoReplyInPrivate() { return autoReplyInPrivate; }
    public synchronized void setAutoReplyInPrivate(boolean autoReplyInPrivate) { this.autoReplyInPrivate = autoReplyInPrivate; }

    public synchronized boolean isOnlyWhenMentioned() { return onlyWhenMentioned; }
    public synchronized void setOnlyWhenMentioned(boolean onlyWhenMentioned) { this.onlyWhenMentioned = onlyWhenMentioned; }

    public synchronized int getMaxHistoryMessages() { return maxHistoryMessages; }
    public synchronized void setMaxHistoryMessages(int maxHistoryMessages) {
        if (maxHistoryMessages < 1) {
            maxHistoryMessages = 1;
        }
        this.maxHistoryMessages = maxHistoryMessages;
    }

    public synchronized File getConfigFile() { return configFile; }

    /** 把关键词字符串按分隔符解析为数组。 */
    public synchronized String[] getWakeKeywords() {
        String kw = wakeKeyword;
        if (TextUtils.isEmpty(kw)) {
            return new String[0];
        }
        return kw.split("[,，;；\\s]+");
    }

    /** 供调试/日志使用的完整 JSON 字符串（掩码 apiKey）。 */
    public synchronized String toDebugString() {
        try {
            JSONObject obj = toJson();
            obj.put("apiKey", "******");
            return obj.toString();
        } catch (JSONException e) {
            return toString();
        }
    }
}
