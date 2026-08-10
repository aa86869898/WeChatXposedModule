package com.leshao.v3.service;

import com.leshao.v3.ContactRepository;
import com.leshao.v3.model.ContactCard;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class NicknameResolver {

    private static final Map<String, String> sCache = new ConcurrentHashMap<>();

    public static void init() {
        sCache.clear();
    }

    public String resolveDisplayName(String wxid) {
        if (wxid == null || wxid.isEmpty()) return "未知";

        String cached = sCache.get(wxid);
        if (cached != null) return cached;

        // 优先从已加载的 ContactRepository 同步查找
        ContactCard c = ContactRepository.findByUsername(wxid);
        if (c != null) {
            String name = c.displayName();
            sCache.put(wxid, name);
            return name;
        }

        // 触发异步加载供后续使用
        ContactRepository.loadAsync(() -> {
            ContactCard c2 = ContactRepository.findByUsername(wxid);
            if (c2 != null) {
                sCache.put(wxid, c2.displayName());
            }
        });

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
