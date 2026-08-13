package com.leshao.v3.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChatMemory {
    private static final Map<String, Deque<MessageReader.ChatMsg>> MEM = new ConcurrentHashMap<>();

    public static void append(String talker, MessageReader.ChatMsg msg) {
        if (!AiConfig.memoryEnabled()) return;
        Deque<MessageReader.ChatMsg> q = MEM.computeIfAbsent(talker, k -> new ArrayDeque<>());
        synchronized (q) { q.addLast(msg); evict(q); }
    }

    public static void preload(String talker, List<MessageReader.ChatMsg> msgs) {
        if (!AiConfig.memoryEnabled()) return;
        Deque<MessageReader.ChatMsg> q = MEM.computeIfAbsent(talker, k -> new ArrayDeque<>());
        synchronized (q) { q.clear(); q.addAll(msgs); evict(q); }
    }

    public static List<MessageReader.ChatMsg> get(String talker) {
        Deque<MessageReader.ChatMsg> q = MEM.get(talker);
        if (q == null) return new ArrayList<>();
        synchronized (q) { return new ArrayList<>(q); }
    }

    public static void clear(String talker) { MEM.remove(talker); }
    public static void clearAll() { MEM.clear(); }

    private static void evict(Deque<MessageReader.ChatMsg> q) {
        int maxCount = AiConfig.memoryCount();
        long maxDur = AiConfig.memoryDurationMs();
        long now = System.currentTimeMillis();
        while (q.size() > maxCount) q.removeFirst();
        while (!q.isEmpty() && now - q.peekFirst().time > maxDur) q.removeFirst();
    }
}
