package com.leshao.ai.hook.wechat;

import android.content.Context;
import android.util.Log;

import com.leshao.ai.api.anthropic.AnthropicClient;
import com.leshao.ai.api.model.ChatMessage;
import com.leshao.ai.api.model.ProviderType;
import com.leshao.ai.api.openai.OpenAIClient;
import com.leshao.ai.config.AppConfig;
import com.leshao.ai.config.ConversationConfig;
import com.leshao.ai.knowledge.KnowledgeBase;
import com.leshao.ai.memory.ChatMemory;
import com.leshao.ai.util.ChatUtils;
import com.leshao.v3.LogWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XposedHelpers;

/**
 * AI 机器人业务中枢。
 * <p>
 * 串联「配置 + 记忆 + 知识库 + LLM 客户端」，对外提供线程安全的能力：
 * <ul>
 *   <li>{@link #ask} 异步生成回复并写回记忆</li>
 *   <li>内部组装人设 + 知识库上下文 + 历史记忆后调用 LLM</li>
 * </ul>
 * 触发门控（总开关 / 全局类型开关 / 会话个性化配置）见 {@link TriggerEngine}。
 */
public final class AIBotCore {

    private static final String TAG = "LeshaoAI.Core";
    private static final Object LOCK = new Object();
    private static final int DEFAULT_MAX_MEMORY = 200;

    private static volatile AppConfig config;
    private static volatile ConversationConfig conversationConfig;
    private static volatile ChatMemory memory;
    private static volatile KnowledgeBase knowledge;
    private static final ExecutorService executor = Executors.newFixedThreadPool(2);

    private AIBotCore() {
    }

    /** 初始化（惰性、幂等、线程安全）。hostDataDir 例：/data/user/0/com.tencent.mm */
    public static synchronized void ensureInit(String hostDataDir) {
        if (config != null) {
            return;
        }
        try {
            config = new AppConfig(hostDataDir);
            config.load();
            conversationConfig = new ConversationConfig(hostDataDir);
            conversationConfig.load();
            memory = new ChatMemory(hostDataDir, DEFAULT_MAX_MEMORY);
            knowledge = new KnowledgeBase(hostDataDir);
            try {
                memory.load();
            } catch (Throwable t) {
                Log.w(TAG, "memory.load err: " + t);
            }
            try {
                // ★ 按用户配置的记忆条数调整桶上限（默认 200，可更高）
                if (config != null) {
                    memory.setMaxPerChat(Math.max(DEFAULT_MAX_MEMORY, config.getMaxHistoryMessages()));
                }
            } catch (Throwable ignored) {
            }
            try {
                knowledge.load();
            } catch (Throwable t) {
                Log.w(TAG, "knowledge.load err: " + t);
            }
            int convCount = 0;
            try {
                convCount = conversationConfig.keys().size();
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "AIBotCore 初始化完成: enabled=" + config.isEnabled()
                    + " convConfig条目=" + convCount);
            LogWriter.log(TAG, "AIBotCore 初始化完成: enabled=" + config.isEnabled()
                    + " convConfig条目=" + convCount);
        } catch (Throwable t) {
            Log.w(TAG, "AIBotCore 初始化异常: " + t, t);
            LogWriter.log(TAG, "AIBotCore 初始化异常: " + t);
        }
    }

    /**
     * 从微信侧 Context 推导宿主 data 目录后初始化（供面板自愈兜底调用）。
     * 与 {@link #ensureInit(String)} 等价，幂等、线程安全。
     */
    public static void ensureInit(Context ctx) {
        if (ctx == null) {
            return;
        }
        String hostDataDir = null;
        try {
            hostDataDir = ctx.getFilesDir().getParent();
        } catch (Throwable ignored) {
        }
        if (hostDataDir == null) {
            hostDataDir = "/data/user/0/com.tencent.mm";
        }
        ensureInit(hostDataDir);
    }

    private static AppConfig cfg() {
        return config;
    }

    /**
     * 重新加载配置（设置页保存后由 {@link ConfigBridge} 广播触发）。
     * 记忆/知识库为微信侧自管数据，不在此列。
     */
    public static synchronized void reload() {
        try {
            if (config != null) {
                config.load();
            }
            if (conversationConfig != null) {
                conversationConfig.load();
            }
            // ★ 记忆/知识库也一并重载，保证持久化内容可恢复
            try {
                if (memory != null) memory.load();
            } catch (Throwable t) {
                Log.w(TAG, "reload memory err: " + t);
            }
            try {
                if (knowledge != null) knowledge.load();
            } catch (Throwable t) {
                Log.w(TAG, "reload knowledge err: " + t);
            }
            Log.i(TAG, "AIBotCore 配置已重载: enabled=" + (config != null && config.isEnabled()));
        } catch (Throwable t) {
            Log.w(TAG, "reload 异常: " + t);
        }
    }

    /** 获取当前配置（可能为 null，代表未初始化）。 */
    /** 访问上下文记忆（豆包对话/图片识别复用模块记忆）。 */
    public static ChatMemory memory() {
        return memory;
    }

    public static AppConfig config() {
        if (config == null) {
            return null;
        }
        return config;
    }

    /** 获取按会话独立配置 / 模板（可能为 null，代表未初始化）。 */
    public static ConversationConfig conversationConfig() {
        return conversationConfig;
    }

    /**
     * 异步生成回复。
     *
     * @param chatId       会话标识（群 id 或联系人 id）
     * @param incoming     入站消息文本（已去除 @ 机器人）
     * @param queryThread 备用上下文（可为空字符串）
     * @param callback     结果回调（工作线程）
     */
    public static void ask(final String chatId, final String incoming,
                           final String queryThread, final ResultCallback callback) {
        ask(chatId, incoming, queryThread, null, callback);
    }

    /**
     * 异步生成回复（带按会话覆盖项）。
     *
     * @param override 会话级覆盖(人设/模型/温度)，为 null 时完全使用全局配置
     */
    public static void ask(final String chatId, final String incoming,
                           final String queryThread, final ConversationConfig.Entry override,
                           final ResultCallback callback) {
        final AppConfig c = cfg();
        if (c == null) {
            callback.onResult("配置未初始化");
            return;
        }
        // ★ 聊天总结：用户消息含"总结"且开关开启 → 直接走总结流程（文字回复，不受语音管控）
        if (c.isSummaryEnabled() && containsSummary(incoming)) {
            LogWriter.log(TAG, "ask: 聊天总结触发 chatId=" + chatId
                    + " incomingLen=" + (incoming == null ? -1 : incoming.length()));
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    final String summary;
                    try {
                        summary = generateSummary(c, chatId, incoming, override);
                    } catch (Throwable t) {
                        Log.w(TAG, "生成总结异常: " + t, t);
                        callback.onResult("聊天总结生成失败");
                        return;
                    }
                    // 总结也回写记忆，便于后续追问
                    try {
                        if (memory != null) {
                            memory.add(chatId, "user", incoming);
                            memory.add(chatId, "assistant", summary);
                            memory.persist();
                        }
                    } catch (Throwable ignored) {
                        Log.w(TAG, "总结记忆回写异常: " + ignored);
                    }
                    callback.onResult(summary);
                }
            });
            return;
        }
        LogWriter.log(TAG, "ask: chatId=" + chatId
                + " incomingLen=" + (incoming == null ? -1 : incoming.length()));
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final String reply;
                try {
                    reply = generateReply(c, chatId, incoming, queryThread, override);
                } catch (Throwable t) {
                    Log.w(TAG, "生成回复异常: " + t, t);
                    String msg = t.getMessage();
                    if (msg == null || msg.trim().isEmpty()) {
                        msg = t.getClass().getSimpleName();
                    }
                    callback.onResult("AI 服务异常: " + msg);
                    return;
                }
                // 记忆回写（注意用 api.model.ChatMessage 传给 LLM，memory.ChatMessage 存本地）
                try {
                    if (memory != null) {
                        memory.add(chatId, "user", incoming);
                        memory.add(chatId, "assistant", reply);
                        // ★ 修复"内容保存后没持久化"：回写后立即落盘
                        memory.persist();
                    }
                } catch (Throwable ignored) {
                    Log.w(TAG, "记忆回写/持久化异常: " + ignored);
                }
                callback.onResult(reply);
            }
        });
    }

    /** 组装请求并调用所选 LLM。 */
    private static String generateReply(AppConfig c, String chatId, String incoming,
                                        String queryThread, ConversationConfig.Entry override) throws Exception {
        StringBuilder sys = new StringBuilder();
        // v1019: 会话级身份/名称提升 (各会话独立)
        if (override != null) {
            if (override.aiName != null && !override.aiName.trim().isEmpty()) {
                sys.append("你是").append(override.aiName.trim()).append("\n");
            }
            if (override.aiIdentity != null && !override.aiIdentity.trim().isEmpty()) {
                sys.append(override.aiIdentity.trim()).append('\n');
            }
        }
        String system = (override != null && override.systemPrompt != null
                && !override.systemPrompt.trim().isEmpty())
                ? override.systemPrompt : c.getSystemPrompt();
        if (system == null || system.trim().isEmpty()) {
            system = "你是一个友好的 AI 助手，名叫" + (c.getBotName() == null ? "小乐" : c.getBotName()) + "。";
        }
        sys.append(system);
        final String systemFinal = sys.toString();
        List<ChatMessage> messages = new ArrayList<>();

        // 知识库上下文
        String kb = "";
        try {
            if (knowledge != null) {
                kb = knowledge.retrieveAsContext(incoming, 3);
            }
        } catch (Throwable ignored) {
        }
        if (kb != null && !kb.trim().isEmpty()) {
            messages.add(new ChatMessage("system", "以下是知识库内容，供参考：\n" + kb, 0));
        }

        // 历史记忆（分群隔离）
        StringBuilder hist = new StringBuilder();
        try {
            if (memory != null) {
                // v1019: 会话级记忆开关与窗口覆盖全局
                boolean memOn = override == null || override.memoryEnabled == null
                        || override.memoryEnabled.booleanValue();
                if (memOn) {
                    int n;
                    if (override != null && override.memoryLimit != null && override.memoryLimit.intValue() > 0) {
                        n = override.memoryLimit.intValue();
                    } else {
                        // v1043: 不再 /2 折算, 直接取配置(默认 100, 无上限约束由 UI 引导)
                        n = Math.max(4, c.getMaxHistoryMessages());
                    }
                    List<com.leshao.ai.memory.ChatMessage> recent =
                            memory.getRecent(chatId, n);
                    if (recent != null) {
                        for (com.leshao.ai.memory.ChatMessage m : recent) {
                            String role = m.getRole() == null ? "user" : m.getRole();
                            hist.append("user".equals(role) ? "用户: " : "助手: ")
                                    .append(m.getContent()).append('\n');
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        messages.add(new ChatMessage("system", systemFinal, 0));
        if (hist.length() > 0) {
            messages.add(new ChatMessage("system", "历史对话：\n" + hist, 0));
        }
        // v1043: 注入当前会话近 100 条真实聊天记录(f9 存储, 文档链)作为上下文
        String recentChatLog = loadRecentChatLog(chatId, 100);
        if (recentChatLog.length() > 0) {
            messages.add(new ChatMessage("system", "该会话最近的聊天记录：\n" + recentChatLog, 0));
        }
        messages.add(new ChatMessage("user", incoming, 0));

        ProviderType provider = resolveProvider(c.getProviderType());
        String model = (override != null && override.model != null
                && !override.model.trim().isEmpty()) ? override.model : c.getModel();

        String baseUrl = c.getBaseUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalStateException("未配置模型接口地址(请在 AI 助手 - 模型提供商 中填写)");
        }
        if (model == null || model.trim().isEmpty()) {
            throw new IllegalStateException("未配置模型名称(请在 AI 助手 - 模型提供商 中填写或点击获取模型)");
        }
        if (c.getApiKey() == null || c.getApiKey().trim().isEmpty()) {
            throw new IllegalStateException("未配置 API Key(请在 AI 助手 - 模型提供商 中填写)");
        }
        // ★ 设置有效性修复：温度/最大令牌真正传入 LLM；会话级温度覆盖优先
        double temperature = c.getTemperature();
        if (override != null && override.temperature != null) {
            temperature = override.temperature.doubleValue();
        }
        int maxTokens = c.getMaxTokens();

        switch (provider) {
            case OPENAI_RESPONSES:
            case OPENAI_CHAT: {
                OpenAIClient client = new OpenAIClient(provider, baseUrl, c.getApiKey(), model);
                client.setTemperature(temperature);
                client.setMaxTokens(maxTokens);
                // ★ 必须传组装后的 systemFinal（含会话级 aiName/aiIdentity/systemPrompt），
                // 传原始 system 会导致 Anthropic/Responses 只使用全局人设，会话级配置失效。
                return client.chat(messages, systemFinal);
            }
            default: {
                // Anthropic
                String base = c.getBaseUrl();
                if (base == null || base.trim().isEmpty()) {
                    base = "https://api.anthropic.com";
                }
                AnthropicClient client = new AnthropicClient(base, c.getApiKey(), model);
                client.setTemperature(temperature);
                client.setMaxTokens(maxTokens);
                return client.chat(messages, systemFinal);
            }
        }
    }

    /** 判断消息是否为聊天总结请求（用户发送内容包含"总结"等关键词）。 */
    public static boolean containsSummary(String incoming) {
        if (incoming == null || incoming.trim().isEmpty()) return false;
        String s = incoming.trim();
        // 排除否定/纠正语境，避免误触发
        if (s.contains("不要总结") || s.contains("不用总结") || s.contains("别总结")
                || s.contains("不总结") || s.contains("总结得不对")
                || s.contains("总结错误") || s.contains("别概括") || s.contains("不用概括")) {
            return false;
        }
        return s.contains("总结一下") || s.contains("帮我总结") || s.contains("聊天总结")
                || s.contains("总结下") || s.contains("概括一下") || s.contains("帮我概括")
                || s.contains("做个总结") || s.contains("做总结") || s.contains("总结内容");
    }

    /** 生成聊天总结：拉取该会话大量聊天记录 → 组装 → LLM 总结（纯文字）。 */
    private static String generateSummary(AppConfig c, String chatId, String incoming,
                                          ConversationConfig.Entry override) throws Exception {
        String chatLog = loadRecentChatLog(chatId, 500);
        StringBuilder sys = new StringBuilder();
        String system = (override != null && override.systemPrompt != null
                && !override.systemPrompt.trim().isEmpty())
                ? override.systemPrompt : c.getSystemPrompt();
        if (system == null || system.trim().isEmpty()) {
            system = "你是一个友好的 AI 助手，名叫" + (c.getBotName() == null ? "小乐" : c.getBotName()) + "。";
        }
        sys.append(system)
                .append("\n你是聊天总结助手，请用简洁清晰的中文总结以下聊天记录的重点内容、主要话题、关键结论。");
        String systemFinal = sys.toString();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", systemFinal, 0));
        if (chatLog.length() > 0) {
            messages.add(new ChatMessage("system", "以下是聊天记录：\n" + chatLog, 0));
        } else {
            messages.add(new ChatMessage("system", "（未能读取到聊天记录，请基于现有上下文总结）", 0));
        }
        messages.add(new ChatMessage("user", incoming, 0));

        ProviderType provider = resolveProvider(c.getProviderType());
        String model = (override != null && override.model != null
                && !override.model.trim().isEmpty()) ? override.model : c.getModel();
        String baseUrl = c.getBaseUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalStateException("未配置模型接口地址(请在 AI 助手 - 模型提供商 中填写)");
        }
        if (model == null || model.trim().isEmpty()) {
            throw new IllegalStateException("未配置模型名称(请在 AI 助手 - 模型提供商 中填写或点击获取模型)");
        }
        if (c.getApiKey() == null || c.getApiKey().trim().isEmpty()) {
            throw new IllegalStateException("未配置 API Key(请在 AI 助手 - 模型提供商 中填写)");
        }
        double temperature = c.getTemperature();
        if (override != null && override.temperature != null) {
            temperature = override.temperature.doubleValue();
        }
        int maxTokens = c.getMaxTokens();
        switch (provider) {
            case OPENAI_RESPONSES:
            case OPENAI_CHAT: {
                OpenAIClient client = new OpenAIClient(provider, baseUrl, c.getApiKey(), model);
                client.setTemperature(temperature);
                client.setMaxTokens(maxTokens);
                return client.chat(messages, systemFinal);
            }
            default: {
                String base = c.getBaseUrl();
                if (base == null || base.trim().isEmpty()) {
                    base = "https://api.anthropic.com";
                }
                AnthropicClient client = new AnthropicClient(base, c.getApiKey(), model);
                client.setTemperature(temperature);
                client.setMaxTokens(maxTokens);
                return client.chat(messages, systemFinal);
            }
        }
    }

    /** 重置某会话的 AI 记忆（清空该群/联系人记忆并落盘）。 */
    public static void resetMemory(String chatId) {
        try {
            if (memory != null) {
                memory.clear(chatId);
                memory.persist();
                LogWriter.log(TAG, "resetMemory: " + chatId);
            }
        } catch (Throwable t) {
            Log.w(TAG, "resetMemory err: " + t);
        }
    }

    private static ProviderType resolveProvider(String s) {
        if (s == null) {
            return ProviderType.OPENAI_CHAT;
        }
        String t = s.trim().toLowerCase();
        if (t.contains("responses")) {
            return ProviderType.OPENAI_RESPONSES;
        }
        if (t.contains("anthropic") || t.contains("claude")) {
            return ProviderType.ANTHROPIC;
        }
        return ProviderType.OPENAI_CHAT;
    }

    /**
     * 读取当前会话近 N 条真实聊天记录（f9 查询，文档《存储和聊天记录和链路.md》）：
     * <pre>
     *   f9.H2(talker, createTimeBefore, limit)  →  createTime < before Desc Limit N
     *   e9 getter: N0()=talker, j()=content, getType(), z0()=isSend,
     *              F0()=svrId, getCreateTime()
     * </pre>
     * 自读自发的消息不注入（防内容重复），仅取用户消息(他人/自己)与 AI 回复的历史文本。
     * 取不到（存储未绑定）时返回空字符串，绝不阻塞主链路。
     */
    private static String loadRecentChatLog(String talker, int limit) {
        StringBuilder sb = new StringBuilder();
        try {
            com.leshao.ai.hook.wechat.StorageHub hub = com.leshao.ai.hook.wechat.StorageHub.get();
            if (!hub.ensureBound()) {
                return "";
            }
            Object f9 = hub.msgInfoStorage();
            if (f9 == null) {
                return "";
            }
            long before = System.currentTimeMillis() + 60 * 1000L;
            List<Object> list = hub.history(talker, before, limit);
            if (list == null || list.isEmpty()) {
                return "";
            }
            int shown = 0;
            for (int i = list.size() - 1; i >= 0; i--) {
                try {
                    Object e9 = list.get(i);
                    if (e9 == null) {
                        continue;
                    }
                    Integer type = (Integer) XposedHelpers.callMethod(e9, "getType");
                    if (type == null || type.intValue() != 1) {
                        continue; // 仅文本
                    }
                    Integer isSend = (Integer) XposedHelpers.callMethod(e9, "z0");
                    String content = (String) XposedHelpers.callMethod(e9, "j");
                    if (content == null || content.trim().isEmpty()) {
                        continue;
                    }
                    // ★ 跳过 AI 机器人自己发的消息（零宽水印），避免重复喂给模型
                    if (com.leshao.ai.hook.wechat.SendGuard.isBotSent(content)) {
                        continue;
                    }
                    String role = (isSend != null && isSend.intValue() == 1) ? "我: " : "对方: ";
                    sb.append(role).append(content.trim()).append('\n');
                    shown++;
                } catch (Throwable ignored) {
                }
                if (shown >= limit) {
                    break;
                }
            }
            if (shown > 0) {
                LogWriter.log(TAG, "loadRecentChatLog: talker=" + talker
                        + " 注入 " + shown + " 条真实聊天记录");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "loadRecentChatLog err: " + t);
        }
        return sb.toString();
    }

    /** 结果回调（工作线程）。 */
    public interface ResultCallback {
        void onResult(String reply);
    }
}