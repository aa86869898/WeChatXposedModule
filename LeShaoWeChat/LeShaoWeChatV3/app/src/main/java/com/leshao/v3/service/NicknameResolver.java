package com.leshao.v3.service;

import com.leshao.v3.db.ContactRepository;
import com.leshao.v3.model.Contact;

import java.util.HashMap;
import java.util.Map;

public class NicknameResolver {

    private final Map<String, String> mCache = new HashMap<>();

    public String resolveDisplayName(String wxid) {
        if (wxid == null || wxid.isEmpty()) return "未知";
        String cached = mCache.get(wxid);
        if (cached != null) return cached;

        String name = null;

        try {
            name = ContactRepository.queryNickFromDB(wxid);
        } catch (Throwable ignored) {}

        if (name == null) {
            try {
                Contact c = ContactRepository.findByWxid(wxid);
                if (c != null) {
                    if (c.remarkName != null && !c.remarkName.isEmpty()) {
                        name = c.remarkName;
                    } else if (c.nickname != null && !c.nickname.isEmpty()) {
                        name = c.nickname;
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (name == null) name = fallbackName(wxid);
        mCache.put(wxid, name);
        return name;
    }

    private static String fallbackName(String wxid) {
        if (wxid == null || wxid.isEmpty()) return "未知";
        if (wxid.startsWith("gh_")) return "公众号";
        return wxid.length() > 20 ? wxid.substring(0, 20) : wxid;
    }
}
