package com.leshao.v3.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class NicknameResolver {

    private static final Map<String, String> sCache = new ConcurrentHashMap<>();

    public static void init() {
        // 联系人数据源已清空，待重写
        sCache.clear();
    }

    public String resolveDisplayName(String wxid) {
        if (wxid == null || wxid.isEmpty()) return "未知";

        String cached = sCache.get(wxid);
        if (cached != null) return cached;

        String name = fallbackName(wxid);
        sCache.put(wxid, name);
        return name;
    }

    private static String fallbackName(String wxid) {
        if (wxid == null || wxid.isEmpty()) return "未知";
        if (wxid.startsWith("gh_")) return "公众号";
        return wxid.length() > 20 ? wxid.substring(0, 20) : wxid;
    }
}
