package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [功能5] 自动回复 — 生产级完整实现
 * ================================
 * 
 * 完整流程:
 *   监听f9.Ra() → 新消息入库 → 检查type==1(文本) + isSend==0(接收)
 *   → 匹配关键词 → 构造e9消息 → 直接调用f9.Ra()写入本地DB
 *   → 微信消息同步机制会自动将本地消息同步到服务器
 * 
 * 为什么直接写DB而不调ChatFooter.F():
 *   ChatFooter.F()需要当前聊天窗口正好是目标会话，
 *   否则无法发送。直接写DB可以绕过此限制。
 */
public class AutoReplyHook {

    private static final long COOLDOWN_MS = 5000;
    private static final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();
    private static ClassLoader classLoader;
    private static Object msgStorage;
    private static boolean sEnabled = false;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        classLoader = cl;
        loadRules();
        hookMessageReceive(cl);
        hookMsgStorage(cl);
    }

    private static Map<String, String> rules = new HashMap<>();
    static {
        rules.put("在吗", "在的，有什么事吗？");
        rules.put("你好", "你好呀！");
        rules.put("谢谢", "不客气~");
        rules.put("晚安", "晚安，好梦！");
        rules.put("在干嘛", "在想你呀~");
        rules.put("哈哈", "😄");
    }

    private static void loadRules() {
        String saved = HookConfig.getString("auto_reply_rules", "");
        if (saved != null && !saved.isEmpty()) {
            try {
                org.json.JSONObject json = new org.json.JSONObject(saved);
                java.util.Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    rules.put(k, json.getString(k));
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void hookMsgStorage(ClassLoader cl) {
        try {
            Class<?> d9 = XposedHelpers.findClass("d9", cl);
            Object service = XposedHelpers.callStaticMethod(d9, "b");
            if (service != null) {
                msgStorage = XposedHelpers.callMethod(service, "u");
                XposedBridge.log("[AutoReply] f9实例获取成功");
            }
        } catch (Throwable t) {
            XposedBridge.log("[AutoReply] f9实例获取失败: " + t.getMessage());
        }
    }

    private static void hookMessageReceive(ClassLoader cl) {
        try {
            Class<?> f9 = null;
            for (String name : new String[]{"com.tencent.mm.storage.f9",
                    "com.tencent.mm.storage.g9"}) {
                try { f9 = XposedHelpers.findClass(name, cl); break; }
                catch (Throwable ignored) {}
            }
            if (f9 == null) return;

            XposedBridge.hookAllMethods(f9, "Ra", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (msgStorage == null) {
                        msgStorage = param.thisObject;
                    }
                    if (param.args.length >= 2) handleNewMsg(param.args[1]);
                }
            });
            XposedBridge.log("[AutoReply] f9.Ra() Hook完成");
        } catch (Throwable t) {}
    }

    private static void handleNewMsg(Object msgInfo) {
        if (msgInfo == null) return;
        try {
            int isSend = XposedHelpers.getIntField(msgInfo, "field_isSend");
            if (isSend == 1) return;

            int type = (Integer) XposedHelpers.callMethod(msgInfo, "getType");
            if (type != 1) return;

            String content = (String) XposedHelpers.getObjectField(msgInfo, "field_content");
            String talker = (String) XposedHelpers.getObjectField(msgInfo, "field_talker");
            if (content == null || talker == null) return;

            Long last = cooldowns.get(talker);
            long now = System.currentTimeMillis();
            if (last != null && now - last < COOLDOWN_MS) return;

            for (Map.Entry<String, String> rule : rules.entrySet()) {
                if (content.contains(rule.getKey())) {
                    cooldowns.put(talker, now);
                    doSendReply(talker, rule.getValue());
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 发送回复 — 直接构造消息写入DB
     * 
     * 消息构造: new e9(talker) → setType(1) → setContent(reply) → 设置时间
     * 写入: msgStorage.Ra(msgId, msgInfo)
     */
    private static void doSendReply(String talker, String replyText) {
        try {
            Class<?> e9Class = XposedHelpers.findClass("com.tencent.mm.storage.e9", classLoader);
            Object msg = XposedHelpers.newInstance(e9Class, talker);
            XposedHelpers.callMethod(msg, "A1", 1);
            XposedHelpers.callMethod(msg, "X0", replyText);
            XposedHelpers.callMethod(msg, "L1", System.currentTimeMillis());

            try { XposedHelpers.setIntField(msg, "field_isSend", 1); }
            catch (Throwable ignored) {}

            if (msgStorage != null) {
                long msgId = System.currentTimeMillis();
                XposedHelpers.callMethod(msgStorage, "Ra", msgId, msg);
                XposedBridge.log("[AutoReply] ✅ 回复: " 
                        + replyText.substring(0, Math.min(20, replyText.length()))
                        + " → " + talker);
            } else {
                sendViaFooter(talker, replyText);
            }
        } catch (Throwable t) {
            XposedBridge.log("[AutoReply] 发送失败: " + t.getMessage());
            sendViaFooter(talker, replyText);
        }
    }

    private static void sendViaFooter(String talker, String replyText) {
        try {
            Class<?> launcherUI = XposedHelpers.findClass(
                    "com.tencent.mm.ui.LauncherUI", classLoader);
            Object instance = XposedHelpers.callStaticMethod(launcherUI, "getInstance");
            if (instance == null) return;

            Object fragment = XposedHelpers.callMethod(instance, "getCurrentFragmet");
            if (fragment == null) return;

            Object footer = XposedHelpers.getObjectField(fragment, "mFooter");
            if (footer == null) return;

            String currentTalker = (String) XposedHelpers.callMethod(footer, "getTalkerUserName");
            if (!talker.equals(currentTalker)) return;

            Class<?> e9Class = XposedHelpers.findClass("com.tencent.mm.storage.e9", classLoader);
            Object msg = XposedHelpers.newInstance(e9Class, talker);
            XposedHelpers.callMethod(msg, "A1", 1);
            XposedHelpers.callMethod(msg, "X0", replyText);

            XposedHelpers.callMethod(footer, "F", msg, null);
            XposedBridge.log("[AutoReply] ✅ 回复(via Footer): " + replyText);
        } catch (Throwable t) {
            XposedBridge.log("[AutoReply] Footer发送也失败: " + t.getMessage());
        }
    }
}
