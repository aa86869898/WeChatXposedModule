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

    /**
     * 规范化密钥: 去掉误粘贴的 "Bearer " 前缀、首尾空白、包裹引号,
     * 并剔除所有不可见字符(零宽空格 U+200B/U+FEFF、不换行空格 U+00A0、制表/换行等)。
     *
     * <p>从网页复制密钥时常会带入不可见 Unicode 字符, {@code String.trim()} 只能去除
     * {@code <= U+0020} 的字符, 无法去掉 U+200B/U+FEFF/U+00A0, 会导致服务端判为无效密钥。</p>
     */
    public static String normalizeKey(String apiKey) {
        if (apiKey == null) return "";
        String k = apiKey.trim();
        if (startsWithIgnoreCase(k, "bearer ")) {
            k = k.substring(7).trim();
        }
        StringBuilder sb = new StringBuilder(k.length());
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            // 仅保留密钥允许的可见 token 字符, 其余(引号/空白/不可见字符)全部丢弃
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 生成用于日志/提示的脱敏密钥(保留前三与后四位), 不含完整密钥。 */
    public static String mask(String key) {
        if (key == null || key.isEmpty()) return "(空)";
        int n = key.length();
        if (n <= 8) return key.charAt(0) + "***";
        return key.substring(0, 3) + "****" + key.substring(n - 4);
    }

    private static boolean endsWithIgnoreCase(String s, String suffix) {
        return s.regionMatches(true, s.length() - suffix.length(), suffix, 0, suffix.length());
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
