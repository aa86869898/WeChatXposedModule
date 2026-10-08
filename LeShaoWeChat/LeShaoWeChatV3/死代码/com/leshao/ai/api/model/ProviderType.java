package com.leshao.ai.api.model;

/**
 * 供应商（Provider）类型枚举。
 * <p>
 * 用于标识当前客户端应使用哪一家 API 的哪种协议格式：
 * <ul>
 *   <li>{@link #OPENAI_CHAT}         — OpenAI Chat Completions，端点 /v1/chat/completions</li>
 *   <li>{@link #OPENAI_RESPONSES}    — OpenAI Responses，端点 /v1/responses</li>
 *   <li>{@link #ANTHROPIC}           — Anthropic Claude，端点 /v1/messages</li>
 * </ul>
 */
public enum ProviderType {

    /** OpenAI Chat Completions 协议 */
    OPENAI_CHAT,

    /** OpenAI Responses 协议 */
    OPENAI_RESPONSES,

    /** Anthropic Messages 协议 */
    ANTHROPIC
}
