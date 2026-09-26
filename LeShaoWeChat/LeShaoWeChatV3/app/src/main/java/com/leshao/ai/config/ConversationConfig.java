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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按会话(联系人 / 群)独立配置 + 可复用模板。
 *
 * <p>以 {@code leshao_ai/conversations.json} 持久化。字段为“未设置 = 继承全局”语义：
 * 布尔为 null、字符串为空即表示跟随 {@link AppConfig} 的全局值。</p>
 */
public class ConversationConfig {

    public static final String DEFAULT_FILE = "leshao_ai/conversations.json";

    private final File file;
    private final Map<String, Entry> overrides = new LinkedHashMap<>();
    private final Map<String, Entry> templates = new LinkedHashMap<>();

    /** 单条独立配置 / 模板数据。null 布尔与空字符串代表继承全局。 */
    public static class Entry {
        /**
         * v996: 本会话是否已启用 AI 服务。存在独立配置即代表「已配置」，
         * 但只有 {@link #isActive()} 为 true 才会真正触发 AI（替代旧白名单）。
         */
        public Boolean enabled;
        public Boolean autoReply;
        public Boolean onlyWhenMentioned;
        public Boolean ttsEnabled;
        public String systemPrompt;
        /** v1019: 本会话 AI 的身份描述(短), 注入 system 提示词。 */
        public String aiIdentity;
        /** v1019: 本会话 AI 的名称/昵称。 */
        public String aiName;
        /** v1019: 本会话是否启用上下文记忆(各会话独立)。null=继承全局启用。 */
        public Boolean memoryEnabled;
        /** v1019: 本会话上下文记忆条数(窗口)。null=继承全局。 */
        public Integer memoryLimit;
        public String model;
        public Double temperature;
        /** v985: 本会话/模板可多选的配音魔方音色 voiceId 列表。 */
        public List<String> voices;
        /** v985: 多音色随机回复开关: 开=每条随机取 voices 中一个; 关=用第一个/全局。 */
        public Boolean randomVoice;
        /** v1073: 文本回复时是否使用(文本式)引用原消息。 */
        public Boolean quoteReply;
        /** v1073: 群聊回复时是否自动 @ 提问人(仅群聊)。 */
        public Boolean autoAt;
        /** v1085: 本会话关键词自动回复开关(null=继承全局)。 */
        public Boolean keywordReplyEnabled;
        /** v1085: 本会话关键词问答规则(为空时用全局规则)。 */
        public List<com.leshao.v3.model.KeywordRule> keywordReplyRules;
        /** v1085: 关键词回复是否自动 @ 提问人(仅群聊)。 */
        public Boolean keywordAutoAt;
        /** v1085: 关键词回复是否引用原消息。 */
        public Boolean keywordQuote;

        public boolean isEmpty() {
            return enabled == null && autoReply == null && onlyWhenMentioned == null
                    && ttsEnabled == null
                    && TextUtils.isEmpty(systemPrompt) && TextUtils.isEmpty(model)
                    && TextUtils.isEmpty(aiIdentity) && TextUtils.isEmpty(aiName)
                    && memoryEnabled == null && memoryLimit == null
                    && temperature == null
                    && (voices == null || voices.isEmpty()) && randomVoice == null
                    && quoteReply == null && autoAt == null
                    && keywordReplyEnabled == null
                    && (keywordReplyRules == null || keywordReplyRules.isEmpty())
                    && keywordAutoAt == null && keywordQuote == null;
        }

        /**
         * v996: 是否对 AI 触发生效。显式 {@link #enabled} 优先；兼容旧配置回退
         * {@link #autoReply}；仅有其它独立设置时视为已启用。
         */
        public boolean isActive() {
            if (enabled != null) return enabled.booleanValue();
            if (autoReply != null) return autoReply.booleanValue();
            return true;
        }

        public Entry copy() {
            Entry e = new Entry();
            e.enabled = enabled;
            e.autoReply = autoReply;
            e.onlyWhenMentioned = onlyWhenMentioned;
            e.ttsEnabled = ttsEnabled;
            e.systemPrompt = systemPrompt;
            e.aiIdentity = aiIdentity;
            e.aiName = aiName;
            e.memoryEnabled = memoryEnabled;
            e.memoryLimit = memoryLimit;
            e.model = model;
            e.temperature = temperature;
            e.voices = voices == null ? null : new ArrayList<>(voices);
            e.randomVoice = randomVoice;
            e.quoteReply = quoteReply;
            e.autoAt = autoAt;
            e.keywordReplyEnabled = keywordReplyEnabled;
            e.keywordReplyRules = keywordReplyRules == null
                    ? null : new ArrayList<>(keywordReplyRules);
            e.keywordAutoAt = keywordAutoAt;
            e.keywordQuote = keywordQuote;
            return e;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            if (enabled != null) o.put("enabled", enabled.booleanValue());
            if (autoReply != null) o.put("autoReply", autoReply.booleanValue());
            if (onlyWhenMentioned != null) o.put("onlyWhenMentioned", onlyWhenMentioned.booleanValue());
            if (ttsEnabled != null) o.put("ttsEnabled", ttsEnabled.booleanValue());
            if (!TextUtils.isEmpty(systemPrompt)) o.put("systemPrompt", systemPrompt);
            if (!TextUtils.isEmpty(aiIdentity)) o.put("aiIdentity", aiIdentity);
            if (!TextUtils.isEmpty(aiName)) o.put("aiName", aiName);
            if (memoryEnabled != null) o.put("memoryEnabled", memoryEnabled.booleanValue());
            if (memoryLimit != null) o.put("memoryLimit", memoryLimit.intValue());
            if (!TextUtils.isEmpty(model)) o.put("model", model);
            if (temperature != null) o.put("temperature", temperature.doubleValue());
            if (voices != null && !voices.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (String v : voices) {
                    if (!TextUtils.isEmpty(v)) arr.put(v);
                }
                o.put("voices", arr);
            }
            if (randomVoice != null) o.put("randomVoice", randomVoice.booleanValue());
            if (quoteReply != null) o.put("quoteReply", quoteReply.booleanValue());
            if (autoAt != null) o.put("autoAt", autoAt.booleanValue());
            if (keywordReplyEnabled != null) {
                o.put("keywordReplyEnabled", keywordReplyEnabled.booleanValue());
            }
            if (keywordReplyRules != null && !keywordReplyRules.isEmpty()) {
                o.put("keywordReplyRules",
                        com.leshao.v3.model.KeywordRule.listToJson(keywordReplyRules));
            }
            if (keywordAutoAt != null) o.put("keywordAutoAt", keywordAutoAt.booleanValue());
            if (keywordQuote != null) o.put("keywordQuote", keywordQuote.booleanValue());
            return o;
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            if (o == null) return e;
            if (o.has("enabled") && !o.isNull("enabled")) e.enabled = o.optBoolean("enabled");
            if (o.has("autoReply") && !o.isNull("autoReply")) e.autoReply = o.optBoolean("autoReply");
            if (o.has("onlyWhenMentioned") && !o.isNull("onlyWhenMentioned")) {
                e.onlyWhenMentioned = o.optBoolean("onlyWhenMentioned");
            }
            if (o.has("ttsEnabled") && !o.isNull("ttsEnabled")) e.ttsEnabled = o.optBoolean("ttsEnabled");
            e.systemPrompt = o.optString("systemPrompt", null);
            e.aiIdentity = o.optString("aiIdentity", null);
            e.aiName = o.optString("aiName", null);
            if (o.has("memoryEnabled") && !o.isNull("memoryEnabled")) {
                e.memoryEnabled = o.optBoolean("memoryEnabled");
            }
            if (o.has("memoryLimit") && !o.isNull("memoryLimit")) {
                e.memoryLimit = o.optInt("memoryLimit");
            }
            e.model = o.optString("model", null);
            if (o.has("temperature") && !o.isNull("temperature")) {
                e.temperature = o.optDouble("temperature");
            }
            JSONArray voices = o.optJSONArray("voices");
            if (voices != null && voices.length() > 0) {
                List<String> vs = new ArrayList<>();
                for (int i = 0; i < voices.length(); i++) {
                    String v = voices.optString(i, null);
                    if (!TextUtils.isEmpty(v)) vs.add(v);
                }
                if (!vs.isEmpty()) e.voices = vs;
            }
            if (o.has("randomVoice") && !o.isNull("randomVoice")) {
                e.randomVoice = o.optBoolean("randomVoice");
            }
            if (o.has("quoteReply") && !o.isNull("quoteReply")) {
                e.quoteReply = o.optBoolean("quoteReply");
            }
            if (o.has("autoAt") && !o.isNull("autoAt")) {
                e.autoAt = o.optBoolean("autoAt");
            }
            if (o.has("keywordReplyEnabled") && !o.isNull("keywordReplyEnabled")) {
                e.keywordReplyEnabled = o.optBoolean("keywordReplyEnabled");
            }
            List<com.leshao.v3.model.KeywordRule> kr = com.leshao.v3.model.KeywordRule
                    .listFromJson(o.optJSONArray("keywordReplyRules"));
            if (!kr.isEmpty()) e.keywordReplyRules = kr;
            if (o.has("keywordAutoAt") && !o.isNull("keywordAutoAt")) {
                e.keywordAutoAt = o.optBoolean("keywordAutoAt");
            }
            if (o.has("keywordQuote") && !o.isNull("keywordQuote")) {
                e.keywordQuote = o.optBoolean("keywordQuote");
            }
            return e;
        }
    }

    public ConversationConfig(String hostDataDir) {
        this(new File(hostDataDir, DEFAULT_FILE));
    }

    public ConversationConfig(File file) {
        this.file = file;
    }

    public synchronized void load() {
        overrides.clear();
        templates.clear();
        if (file == null || !file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = in.read(data);
            if (read <= 0) return;
            JSONObject root = new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            JSONObject ov = root.optJSONObject("overrides");
            if (ov != null) {
                java.util.Iterator<String> it = ov.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    overrides.put(k, Entry.fromJson(ov.optJSONObject(k)));
                }
            }
            JSONArray tpl = root.optJSONArray("templates");
            if (tpl != null) {
                for (int i = 0; i < tpl.length(); i++) {
                    JSONObject o = tpl.optJSONObject(i);
                    if (o == null) continue;
                    String name = o.optString("name", null);
                    if (TextUtils.isEmpty(name)) continue;
                    templates.put(name, Entry.fromJson(o));
                }
            }
        } catch (IOException | JSONException ignored) {
        }
    }

    public synchronized boolean save() {
        if (file == null) return false;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
            JSONObject root = new JSONObject();
            JSONObject ov = new JSONObject();
            for (Map.Entry<String, Entry> e : overrides.entrySet()) {
                ov.put(e.getKey(), e.getValue().toJson());
            }
            root.put("overrides", ov);
            JSONArray tpl = new JSONArray();
            for (Map.Entry<String, Entry> e : templates.entrySet()) {
                JSONObject o = e.getValue().toJson();
                o.put("name", e.getKey());
                tpl.put(o);
            }
            root.put("templates", tpl);
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (IOException | JSONException e) {
            return false;
        }
    }

    // ---------- 会话独立配置 ----------

    public synchronized Entry get(String talker) {
        return overrides.get(talker);
    }

    public synchronized void put(String talker, Entry entry) {
        if (TextUtils.isEmpty(talker)) return;
        if (entry == null || entry.isEmpty()) {
            overrides.remove(talker);
        } else {
            overrides.put(talker, entry);
        }
    }

    public synchronized void remove(String talker) {
        overrides.remove(talker);
    }

    public synchronized List<String> keys() {
        return new ArrayList<>(overrides.keySet());
    }

    /** 仅返回群会话(以 @chatroom / @im.chatroom 结尾)或非群会话。 */
    public synchronized List<String> keysByType(boolean group) {
        List<String> out = new ArrayList<>();
        for (String k : overrides.keySet()) {
            if (isGroupTalker(k) == group) out.add(k);
        }
        return out;
    }

    public static boolean isGroupTalker(String talker) {
        if (talker == null) return false;
        return talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom");
    }

    // ---------- 模板 ----------

    public synchronized List<String> templateNames() {
        return new ArrayList<>(templates.keySet());
    }

    public synchronized Entry getTemplate(String name) {
        return templates.get(name);
    }

    public synchronized void putTemplate(String name, Entry entry) {
        if (TextUtils.isEmpty(name)) return;
        templates.put(name, entry == null ? new Entry() : entry);
    }

    public synchronized void removeTemplate(String name) {
        templates.remove(name);
    }

    /** 把模板套用到某会话(深拷贝，后续编辑互不影响)。 */
    public synchronized boolean applyTemplate(String talker, String templateName) {
        Entry t = templates.get(templateName);
        if (t == null || TextUtils.isEmpty(talker)) return false;
        overrides.put(talker, t.copy());
        return true;
    }
}
