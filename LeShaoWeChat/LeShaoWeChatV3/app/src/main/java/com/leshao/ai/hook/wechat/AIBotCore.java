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
    private static final int DEFAULT_MAX_MEMORY = 100;

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
            Log.i(TAG, "AIBotCore 配置已重载: enabled=" + (config != null && config.isEnabled()));
        } catch (Throwable t) {
            Log.w(TAG, "reload 异常: " + t);
        }
    }

    /** 获取当前配置（可能为 null，代表未初始化）。 */
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
                    callback.onResult("AI 服务暂时不可用");
                    return;
                }
                // 记忆回写（注意用 api.model.ChatMessage 传给 LLM，memory.ChatMessage 存本地）
                try {
                    if (memory != null) {
                        memory.add(chatId, "user", incoming);
                        memory.add(chatId, "assistant", reply);
                    }
                } catch (Throwable ignored) {
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

        switch (provider) {
            case OPENAI_RESPONSES:
            case OPENAI_CHAT: {
                OpenAIClient client = new OpenAIClient(provider, baseUrl, c.getApiKey(), model);
                return client.chat(messages, system);
            }
            default: {
                // Anthropic
                String base = c.getBaseUrl();
                if (base == null || base.trim().isEmpty()) {
                    base = "https://api.anthropic.com";
                }
                AnthropicClient client = new AnthropicClient(base, c.getApiKey(), model);
                return client.chat(messages, system);
            }
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