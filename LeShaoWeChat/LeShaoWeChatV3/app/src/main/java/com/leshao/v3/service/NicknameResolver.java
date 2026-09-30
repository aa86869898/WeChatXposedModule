package com.leshao.v3.service;

import com.leshao.v3.ContactRepository;
import com.leshao.v3.model.ContactCard;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class NicknameResolver {

    /** v955(问题21): 缓存设上限 + LRU 淘汰, 避免无限增长(原 ConcurrentHashMap 无界)。 */
    private static final int MAX_CACHE = 512;
    private static final Object sLock = new Object();
    private static final LinkedHashMap<String, String> sCache =
        new LinkedHashMap<String, String>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > MAX_CACHE;
            }
        };
    /** 正在异步解析的 wxid, 避免重复排队(问题21)。 */
    private static final Set<String> sPending = ConcurrentHashMap.newKeySet();

    public static void init() {
        synchronized (sLock) {
            sCache.clear();
        }
        sPending.clear();
    }

    public String resolveDisplayName(String wxid) {
        if (wxid == null || wxid.isEmpty()) return "未知";

        String cached = getCached(wxid);
        if (cached != null) return cached;

        // 优先从已加载的 ContactRepository 内存查找(非 DB, 允许同步)
        ContactCard c = ContactRepository.findByUsername(wxid);
        if (c != null) {
            String name = c.displayName();
            putCached(wxid, name);
            return name;
        }

        // 群成员/非好友: rcontact 全表查询改为异步, 避免阻塞调用线程(问题21)
        final String w = wxid;
        if (sPending.add(w)) {
            try {
                ContactRepository.loadAsync(() -> {
                    try {
                        ContactCard c2 = ContactRepository.findByUsername(w);
                        if (c2 != null) {
                            putCached(w, c2.displayName());
                            return;
                        }
                        String anyName = ContactRepository.queryAnyContactName(w);
                        if (anyName != null && !anyName.isEmpty()) {
                            putCached(w, anyName);
                        }
                    } finally {
                        sPending.remove(w);
                    }
                });
            } catch (Throwable ignored) {
                sPending.remove(w);
            }
        }

        // 立即返回兜底名(不写缓存, 待异步查到真名后由 putCached 覆盖)
        return fallbackName(wxid);
    }

    private static String getCached(String key) {
        synchronized (sLock) {
            return sCache.get(key);
        }
    }

    private static void putCached(String key, String value) {
        if (key == null || value == null) return;
        synchronized (sLock) {
            sCache.put(key, value);
        }
    }

    private static String fallbackName(String wxid) {
        if (wxid == null || wxid.isEmpty()) return "未知";
        if (wxid.startsWith("gh_")) return "公众号";
        if (wxid.endsWith("@chatroom")) return "群聊";
        if (wxid.startsWith("wxid_")) return "好友";
        return "好友";
    }
}
