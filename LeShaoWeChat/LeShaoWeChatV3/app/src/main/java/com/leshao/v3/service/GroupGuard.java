package com.leshao.v3.service;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.model.WeChatMessage;

import de.robv.android.xposed.XposedHelpers;

public class GroupGuard {

    private static final String TAG = "GroupGuard";
    private static final java.util.Map<String, java.util.Map<String, Integer>> sViolations = new java.util.concurrent.ConcurrentHashMap<>();

    private static final String[] CHATROOM_API_CANDIDATES = {
        "com.tencent.mm.model.u", "com.tencent.mm.model.v", "com.tencent.mm.model.w",
        "com.tencent.mm.model.t", "com.tencent.mm.model.s", "com.tencent.mm.model.r"
    };

    private static final String[] MSG_SENDER_CANDIDATES = {
        "com.tencent.mm.modelmulti.n", "com.tencent.mm.modelmulti.m", "com.tencent.mm.modelmulti.l",
        "com.tencent.mm.modelmulti.k", "com.tencent.mm.modelmulti.j", "com.tencent.mm.modelmulti.i"
    };

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
            Class<?> chatroomApi = null;
            for (String candidate : CHATROOM_API_CANDIDATES) {
                try {
                    chatroomApi = cl.loadClass(candidate);
                    if (chatroomApi != null) {
                        LogWriter.log(TAG, "removeMember: found " + candidate);
                        break;
                    }
                } catch (Throwable ignored) {}
            }
            if (chatroomApi == null) {
                LogWriter.log(TAG, "removeMember: no chatroom API class found");
                return;
            }
            java.lang.reflect.Method aMethod = null;
            for (java.lang.reflect.Method m : chatroomApi.getDeclaredMethods()) {
                if (m.getParameterCount() == 2 && m.getParameterTypes()[0] == String.class && m.getParameterTypes()[1] == String.class) {
                    aMethod = m;
                    break;
                }
            }
            if (aMethod == null) {
                LogWriter.log(TAG, "removeMember: no matching method found in " + chatroomApi.getName());
                return;
            }
            aMethod.setAccessible(true);
            Object result = aMethod.invoke(null, groupId, memberWxid);
            LogWriter.log(TAG, "removeMember: group=" + groupId + " member=" + memberWxid + " result=" + result);
        } catch (Throwable t) {
            LogWriter.log(TAG, "removeMember FAILED: " + t.getMessage());
        }
    }

    private static void sendGroupMsg(String groupId, String text) {
        try {
            ClassLoader cl = ContextManager.getClassLoader();
            Class<?> msgClass = null;
            for (String candidate : MSG_SENDER_CANDIDATES) {
                try {
                    msgClass = cl.loadClass(candidate);
                    if (msgClass != null) {
                        LogWriter.log(TAG, "sendGroupMsg: found " + candidate);
                        break;
                    }
                } catch (Throwable ignored) {}
            }
            if (msgClass == null) {
                LogWriter.log(TAG, "sendGroupMsg: no msg sender class found");
                return;
            }
            java.lang.reflect.Constructor<?> ctor = null;
            for (java.lang.reflect.Constructor<?> c : msgClass.getDeclaredConstructors()) {
                if (c.getParameterCount() == 3 && c.getParameterTypes()[0] == String.class && c.getParameterTypes()[1] == String.class && c.getParameterTypes()[2] == int.class) {
                    ctor = c;
                    break;
                }
            }
            if (ctor == null) {
                LogWriter.log(TAG, "sendGroupMsg: no matching constructor in " + msgClass.getName());
                return;
            }
            ctor.setAccessible(true);
            Object msg = ctor.newInstance(groupId, text, 1);
            java.lang.reflect.Method bMethod = null;
            for (java.lang.reflect.Method m : msgClass.getDeclaredMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == msgClass && m.getName().equals("b")) {
                    bMethod = m;
                    break;
                }
            }
            if (bMethod == null) {
                LogWriter.log(TAG, "sendGroupMsg: no b() method in " + msgClass.getName());
                return;
            }
            bMethod.setAccessible(true);
            XposedHelpers.callStaticMethod(msgClass, "b", msg);
            LogWriter.log(TAG, "sendGroupMsg: group=" + groupId + " text=" + text);
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendGroupMsg FAILED: " + t.getMessage());
        }
    }
}
