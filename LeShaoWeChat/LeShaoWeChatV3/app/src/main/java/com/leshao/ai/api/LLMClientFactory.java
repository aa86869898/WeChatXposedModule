package com.leshao.ai.api;

import com.leshao.ai.api.anthropic.AnthropicClient;
import com.leshao.ai.api.model.ProviderType;
import com.leshao.ai.api.openai.OpenAIClient;

/**
 * LLM 客户端工厂。
 * <p>
 * 依据 {@link ProviderType} 与配置项创建对应协议的具体客户端实例。
 * 调用方只需拿到统一的客户端对象调用 {@code chat(messages, system)} 即可，无需关心底层协议差异。
 */
public final class LLMClientFactory {

    private LLMClientFactory() {
        // 工具类，禁止实例化
    }

    /**
     * 创建一个 OpenAI 兼容客户端实例。
     *
     * @param baseUrl API 基础地址，例如 https://api.openai.com；可传 null 使用默认值
     * @param apiKey  API 密钥
     * @param model   模型名
     * @return {@link OpenAIClient} 实例
     */
    public static OpenAIClient createOpenAI(String baseUrl, String apiKey, String model) {
        return new OpenAIClient(ProviderType.OPENAI_CHAT,
                defaultIfEmpty(baseUrl, "https://api.openai.com"), apiKey, model);
    }

    /**
     * 创建一个 OpenAI Responses 协议客户端实例。
     *
     * @param baseUrl API 基础地址；可传 null 使用默认值
     * @param apiKey  API 密钥
     * @param model   模型名
     * @return {@link OpenAIClient} 实例（Responses 模式）
     */
    public static OpenAIClient createOpenAIResponses(String baseUrl, String apiKey, String model) {
        return new OpenAIClient(ProviderType.OPENAI_RESPONSES,
                defaultIfEmpty(baseUrl, "https://api.openai.com"), apiKey, model);
    }

    /**
     * 创建一个 Anthropic 客户端实例。
     *
     * @param baseUrl API 基础地址；可传 null 使用默认 {@code https://api.anthropic.com}
     * @param apiKey  Anthropic 密钥
     * @param model   模型名
     * @return {@link AnthropicClient} 实例
     */
    public static AnthropicClient createAnthropic(String baseUrl, String apiKey, String model) {
        return new AnthropicClient(defaultIfEmpty(baseUrl, AnthropicClient.DEFAULT_BASE_URL), apiKey, model);
    }

    /**
     * 根据类型 + 配置统一创建客户端，返回 Object 以便多协议共存。
     * 调用方根据 {@code type} 强转为 {@link OpenAIClient} 或 {@link AnthropicClient}。
     *
     * @param type    ProviderType 枚举
     * @param baseUrl API 基础地址
     * @param apiKey  API 密钥
     * @param model   模型名
     * @return 对应的客户端实例
     */
    public static Object create(ProviderType type, String baseUrl, String apiKey, String model) {
        if (type == null) {
            return createOpenAI(baseUrl, apiKey, model);
        }
        switch (type) {
            case OPENAI_CHAT:
                return createOpenAI(baseUrl, apiKey, model);
            case OPENAI_RESPONSES:
                return createOpenAIResponses(baseUrl, apiKey, model);
            case ANTHROPIC:
                return createAnthropic(baseUrl, apiKey, model);
            default:
                return createOpenAI(baseUrl, apiKey, model);
        }
    }

    private static String defaultIfEmpty(String value, String fallback) {
        return (value == null || value.trim().isEmpty()) ? fallback : value.trim();
    }
}
