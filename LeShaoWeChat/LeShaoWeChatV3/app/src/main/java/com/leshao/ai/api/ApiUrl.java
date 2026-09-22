package com.leshao.ai.api;

/**
 * API 地址拼接工具。
 *
 * <p>统一处理"基础地址可能已包含 /v1, 而端点也以 /v1 开头"导致的 {@code /v1/v1/...}
 * 重复问题; 同时容忍首尾空格与多余斜杠。</p>
 */
public final class ApiUrl {

    private ApiUrl() {
    }

    /**
     * 拼接基础地址与端点路径。
     *
     * @param baseUrl 基础地址, 例如 {@code https://api.deepseek.com} 或 {@code https://api.deepseek.com/v1}
     * @param path    端点路径, 例如 {@code /v1/chat/completions}
     * @return 规范化后的完整 URL
     */
    public static String join(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        String p = path == null ? "" : path.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!p.startsWith("/")) p = "/" + p;
        // 基础地址已含 /v1 且端点也以 /v1/ 开头时, 去掉基础地址末尾的 /v1, 避免重复
        if (endsWithIgnoreCase(base, "/v1") && startsWithIgnoreCase(p, "/v1/")) {
            base = base.substring(0, base.length() - 3);
        }
        return base + p;
    }

    /** 去掉密钥首尾空白, 并移除误粘贴的 "Bearer " 前缀。 */
    public static String normalizeKey(String apiKey) {
        if (apiKey == null) return "";
        String k = apiKey.trim();
        if (startsWithIgnoreCase(k, "bearer ")) {
            k = k.substring(7).trim();
        }
        return k;
    }

    private static boolean endsWithIgnoreCase(String s, String suffix) {
        return s.regionMatches(true, s.length() - suffix.length(), suffix, 0, suffix.length());
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
