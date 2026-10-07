package com.leshao.v3.service;

import com.leshao.v3.LogWriter;
import com.leshao.v3.model.WeChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StatsCollector {

    private static final String TAG = "StatsCollector";
    private static final Map<String, GroupStats> sGroupStats = new ConcurrentHashMap<>();
    private static final List<String> sRecallRecords = Collections.synchronizedList(new ArrayList<>());

    public static void record(WeChatMessage msg) {
        if (msg == null) return;
        if (msg.isGroup()) {
            sGroupStats.computeIfAbsent(msg.talker, k -> new GroupStats()).record(msg);
        }
    }

    public static void recordRecall(String talker, String content) {
        sRecallRecords.add(talker + ": " + content);
        LogWriter.log(TAG, "recall recorded: " + talker);
    }

    public static List<String> getRecallRecords() {
        return new ArrayList<>(sRecallRecords);
    }

    public static GroupStats getGroupStats(String groupId) {
        return sGroupStats.get(groupId);
    }

    public static class GroupStats {
        public int messageCount;
        public final Map<String, Integer> memberCounts = new ConcurrentHashMap<>();
        public long lastActive;

        synchronized void record(WeChatMessage msg) {
            messageCount++;
            lastActive = System.currentTimeMillis();
            memberCounts.merge(msg.senderWxid, 1, Integer::sum);
        }

        public int getActiveMemberCount() { return memberCounts.size(); }

        public List<Map.Entry<String, Integer>> getTopMembers(int n) {
            return memberCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(n)
                .collect(java.util.stream.Collectors.toList());
        }
    }
}
