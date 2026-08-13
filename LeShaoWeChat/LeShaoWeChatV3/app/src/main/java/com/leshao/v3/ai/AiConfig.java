package com.leshao.v3.ai;

import android.content.Context;
import android.content.SharedPreferences;

public class AiConfig {
    private static final String SP = "leshao_v3_prefs";
    private static SharedPreferences sp;

    public static void init(Context ctx) {
        if (sp == null && ctx != null) {
            sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE);
        }
    }

    private static SharedPreferences prefs() {
        return sp;
    }

    public static boolean masterEnabled()     { return get("ai_master", true); }
    public static void setMaster(boolean v)   { put("ai_master", v); }

    public static boolean summaryEnabled()    { return get("ai_summary", true); }
    public static boolean replyEnabled()      { return get("ai_reply", true); }
    public static boolean memoryEnabled()     { return get("ai_memory", true); }
    public static boolean polishEnabled()     { return get("ai_polish", true); }
    public static boolean emotionEnabled()    { return get("ai_emotion", true); }
    public static boolean keywordEnabled()    { return get("ai_keyword", true); }
    public static boolean customPromptEnabled(){ return get("ai_customPrompt", false); }

    public static void setSummary(boolean v)   { put("ai_summary", v); }
    public static void setReply(boolean v)     { put("ai_reply", v); }
    public static void setMemory(boolean v)    { put("ai_memory", v); }
    public static void setPolish(boolean v)    { put("ai_polish", v); }
    public static void setEmotion(boolean v)   { put("ai_emotion", v); }
    public static void setKeyword(boolean v)   { put("ai_keyword", v); }
    public static void setCustomPrompt(boolean v){ put("ai_customPrompt", v); }

    public static int provider()              { return get("ai_provider", 0); }
    public static void setProvider(int v)     { put("ai_provider", v); }

    public static String openaiBaseUrl()      { return getStr("ai_openai_base", "https://api.openai.com"); }
    public static String openaiKey()          { return getStr("ai_openai_key", ""); }
    public static String openaiModel()        { return getStr("ai_openai_model", "gpt-4o-mini"); }
    public static String deepseekBaseUrl()    { return getStr("ai_deepseek_base", "https://api.deepseek.com"); }
    public static String deepseekKey()        { return getStr("ai_deepseek_key", ""); }
    public static String deepseekModel()      { return getStr("ai_deepseek_model", "deepseek-chat"); }

    public static void setOpenaiBaseUrl(String v){ put("ai_openai_base", v); }
    public static void setOpenaiKey(String v)   { put("ai_openai_key", v); }
    public static void setOpenaiModel(String v) { put("ai_openai_model", v); }
    public static void setDeepseekBaseUrl(String v){ put("ai_deepseek_base", v); }
    public static void setDeepseekKey(String v) { put("ai_deepseek_key", v); }
    public static void setDeepseekModel(String v){ put("ai_deepseek_model", v); }

    public static String activeBaseUrl() { return provider() == 0 ? openaiBaseUrl() : deepseekBaseUrl(); }
    public static String activeKey()     { return provider() == 0 ? openaiKey() : deepseekKey(); }
    public static String activeModel()   { return provider() == 0 ? openaiModel() : deepseekModel(); }

    public static int summaryCount()         { return get("ai_summary_count", 200); }
    public static void setSummaryCount(int v){ put("ai_summary_count", v); }

    public static int memoryCount()          { return get("ai_memory_count", 20); }
    public static void setMemoryCount(int v) { put("ai_memory_count", v); }

    public static long memoryDurationMs()    { return get("ai_memory_duration", 30L) * 60_000L; }
    public static void setMemoryDuration(int minutes){ put("ai_memory_duration", (long) minutes); }

    public static int replyMode()            { return get("ai_reply_mode", 0); }
    public static void setReplyMode(int v)   { put("ai_reply_mode", v); }

    public static int replyCount()           { return get("ai_reply_count", 3); }
    public static void setReplyCount(int v)  { put("ai_reply_count", v); }

    public static float temperature()        { return get("ai_temperature", 0.7f); }
    public static void setTemperature(float v){ put("ai_temperature", v); }

    public static String promptSummary()     { return getStr("ai_prompt_summary", DEF_SUMMARY); }
    public static String promptReply()       { return getStr("ai_prompt_reply", DEF_REPLY); }
    public static String promptPolish()      { return getStr("ai_prompt_polish", DEF_POLISH); }
    public static String promptEmotion()     { return getStr("ai_prompt_emotion", DEF_EMOTION); }
    public static String promptKeyword()     { return getStr("ai_prompt_keyword", DEF_KEYWORD); }
    public static void setPromptSummary(String v){ put("ai_prompt_summary", v); }
    public static void setPromptReply(String v)  { put("ai_prompt_reply", v); }
    public static void setPromptPolish(String v) { put("ai_prompt_polish", v); }
    public static void setPromptEmotion(String v){ put("ai_prompt_emotion", v); }
    public static void setPromptKeyword(String v){ put("ai_prompt_keyword", v); }

    public static final String DEF_SUMMARY =
        "你是资深社交关系分析专家。基于我提供的【数据统计】和【聊天记录】，生成一份深入的个性化分析报告。\n" +
        "要求：引用具体数字支撑结论，避免空泛套话，简体中文。\n" +
        "输出格式（Markdown）：\n" +
        "## 核心话题\n" +
        "一句话概括中心内容\n" +
        "## 关系画像\n" +
        "- 亲密度、角色关系、相处模式\n" +
        "## 双方性格\n" +
        "- 我：表达风格、主动性、语气特征\n" +
        "- 对方：表达风格、主动性、语气特征\n" +
        "## 情绪曲线\n" +
        "- 随时间的情绪变化（区分不同阶段，而非单一结论）\n" +
        "## 关注点\n" +
        "- 反复出现但未明说的需求或话题\n" +
        "## 待办事项\n" +
        "- 明确的待办（没有则写\"无\"）\n" +
        "## 沟通建议\n" +
        "- 3 条可操作的建议";

    public static final String DEF_REPLY =
        "根据以下聊天上下文，生成 %d 条合适的回复话术，语气自然、符合语境，直接输出每句话（每行一条，不要编号、不要解释）。";

    public static final String DEF_POLISH =
        "请把下面这段话润色改写，语气：%s。只输出改写后的文本，不要解释。\n原文：";

    public static final String DEF_EMOTION =
        "分析下面这段聊天内容的情绪倾向，输出：情绪=正面/中性/负面；强度=1-5；一句话理由。\n内容：";

    public static final String DEF_KEYWORD =
        "从下面这段聊天内容中提取关键信息，输出：\n【关键词】k1、k2、k3\n【待办事项】\n- 事项1\n- 事项2\n（没有则输出\"无\"）\n内容：";

    private static boolean get(String k, boolean def){ SharedPreferences p = prefs(); return p != null ? p.getBoolean(k, def) : def; }
    private static int get(String k, int def){ SharedPreferences p = prefs(); return p != null ? p.getInt(k, def) : def; }
    private static long get(String k, long def){ SharedPreferences p = prefs(); return p != null ? p.getLong(k, def) : def; }
    private static float get(String k, float def){ SharedPreferences p = prefs(); return p != null ? p.getFloat(k, def) : def; }
    private static String getStr(String k, String def){ SharedPreferences p = prefs(); return p != null ? p.getString(k, def) : def; }
    private static void put(String k, boolean v){ SharedPreferences p = prefs(); if (p != null) p.edit().putBoolean(k, v).apply(); }
    private static void put(String k, int v){ SharedPreferences p = prefs(); if (p != null) p.edit().putInt(k, v).apply(); }
    private static void put(String k, long v){ SharedPreferences p = prefs(); if (p != null) p.edit().putLong(k, v).apply(); }
    private static void put(String k, float v){ SharedPreferences p = prefs(); if (p != null) p.edit().putFloat(k, v).apply(); }
    private static void put(String k, String v){ SharedPreferences p = prefs(); if (p != null) p.edit().putString(k, v).apply(); }
}
