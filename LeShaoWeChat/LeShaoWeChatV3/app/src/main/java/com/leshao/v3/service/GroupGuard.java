package com.leshao.v3.service;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.model.WeChatMessage;

import de.robv.android.xposed.XposedHelpers;

public class GroupGuard {

    private static final String TAG = "GroupGuard";
    private static final java.util.Map<String, java.util.Map<String, Integer>> sViolations = new java.util.concurrent.ConcurrentHashMap<>();

    public static void process(WeChatMessage msg, ModuleConfig cfg) {
        if (msg == null || cfg == null || !msg.isGroup()) return;

        // 广告检测
        if (cfg.autoKickEnabled && containsAd(msg.content, cfg.adKeywords)) {
            recordViolation(msg, cfg);
        }
    }

    public static void onMemberJoin(String groupId, String memberWxid, ModuleConfig cfg) {
        if (!cfg.welcomeEnabled) return;
        sendGroupMsg(groupId, cfg.welcomeMsg.replace("{member}", memberWxid));
    }

    public static void onMemberLeave(String groupId, String memberWxid, ModuleConfig cfg) {
        LogWriter.log(TAG, "member left: group=" + groupId + " member=" + memberWxid);
    }

    private static boolean containsAd(String content, java.util.Set<String> keywords) {
        if (content == null || keywords == null || keywords.isEmpty()) return false;
        String lower = content.toLowerCase();
        for (String kw : keywords) {
            if (lower.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    private static void recordViolation(WeChatMessage msg, ModuleConfig cfg) {
        sViolations.putIfAbsent(msg.talker, new java.util.concurrent.ConcurrentHashMap<>());
        java.util.Map<String, Integer> gv = sViolations.get(msg.talker);
        int count = gv.getOrDefault(msg.senderWxid, 0) + 1;
        gv.put(msg.senderWxid, count);

        LogWriter.log(TAG, "ad violation: group=" + msg.talker + " member=" + msg.senderWxid + " count=" + count);

        if (count >= cfg.kickThreshold) {
            removeMember(msg.talker, msg.senderWxid);
            gv.remove(msg.senderWxid);
        }
    }

    private static void removeMember(String groupId, String memberWxid) {
        try {
            ClassLoader cl = ContextManager.getClassLoader();
            Class<?> chatroomApi = cl.loadClass("com.tencent.mm.model.u");
            Object result = XposedHelpers.callStaticMethod(chatroomApi, "a", groupId, memberWxid);
            LogWriter.log(TAG, "removeMember: group=" + groupId + " member=" + memberWxid + " result=" + result);
        } catch (Throwable t) {
            LogWriter.log(TAG, "removeMember FAILED: " + t.getMessage());
        }
    }

    private static void sendGroupMsg(String groupId, String text) {
        try {
            ClassLoader cl = ContextManager.getClassLoader();
            Class<?> msgClass = cl.loadClass("com.tencent.mm.modelmulti.n");
            Object msg = XposedHelpers.newInstance(msgClass, groupId, text, 1);
            XposedHelpers.callStaticMethod(msgClass, "b", msg);
            LogWriter.log(TAG, "sendGroupMsg: group=" + groupId + " text=" + text);
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendGroupMsg FAILED: " + t.getMessage());
        }
    }
}
