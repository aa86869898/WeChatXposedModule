package com.leshao.ai.api

class ApiUrl private constructor() {

    companion object {
        @JvmStatic
        fun join(baseUrl: String?, path: String?): String {
            var base = baseUrl?.trim() ?: ""
            var p = path?.trim() ?: ""
            while (base.endsWith("/")) base = base.substring(0, base.length - 1)
            if (!p.startsWith("/")) p = "/" + p
            if (endsWithIgnoreCase(base, "/v1") && startsWithIgnoreCase(p, "/v1/")) {
                base = base.substring(0, base.length - 3)
            }
            return base + p
        }

        @JvmStatic
        fun normalizeKey(apiKey: String?): String {
            if (apiKey == null) return ""
            var k = apiKey.trim()
            if (startsWithIgnoreCase(k, "bearer ")) {
                k = k.substring(7).trim()
            }
            val sb = StringBuilder(k.length)
            for (i in 0 until k.length) {
                val c = k[i]
                if ((c in 'A'..'Z') || (c in 'a'..'z')
                        || (c in '0'..'9') || c == '-' || c == '_' || c == '.') {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        @JvmStatic
        fun mask(key: String?): String {
            if (key == null || key.isEmpty()) return "(空)"
            val n = key.length
            if (n <= 8) return key.substring(0, 1) + "***"
            return key.substring(0, 3) + "****" + key.substring(n - 4)
        }

        private fun endsWithIgnoreCase(s: String, suffix: String): Boolean {
            return s.endsWith(suffix, ignoreCase = true)
        }

        private fun startsWithIgnoreCase(s: String, prefix: String): Boolean {
            return s.startsWith(prefix, ignoreCase = true)
        }
    }
}