package com.leshao.v3.hook;

import com.tencent.mmkv.MMKV;

import org.luckypray.dexkit.DexKitCacheBridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

public class MmkvCacheStorage implements DexKitCacheBridge.Cache {

    private static final String MMKV_ID = "dexkit_cache";
    private static final String LIST_SEPARATOR = "\u0000";

    private final MMKV mmkv;

    public MmkvCacheStorage() {
        mmkv = MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE);
    }

    @Override
    public String getString(String key, String defaultVal) {
        String v = mmkv.decodeString(key, null);
        return v != null ? v : defaultVal;
    }

    @Override
    public void putString(String key, String value) {
        mmkv.encode(key, value);
    }

    @Override
    public List<String> getStringList(String key, List<String> defaultVal) {
        String raw = mmkv.decodeString(key, null);
        if (raw == null) return defaultVal;
        if (raw.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(raw.split(LIST_SEPARATOR)));
    }

    @Override
    public void putStringList(String key, List<String> value) {
        if (value == null || value.isEmpty()) {
            mmkv.encode(key, "");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.size(); i++) {
            if (i > 0) sb.append(LIST_SEPARATOR);
            sb.append(value.get(i));
        }
        mmkv.encode(key, sb.toString());
    }

    @Override
    public void remove(String key) {
        mmkv.removeValueForKey(key);
    }

    @Override
    public Collection<String> getAllKeys() {
        return new HashSet<>(Arrays.asList(mmkv.allKeys()));
    }

    @Override
    public void clearAll() {
        mmkv.clearAll();
    }
}