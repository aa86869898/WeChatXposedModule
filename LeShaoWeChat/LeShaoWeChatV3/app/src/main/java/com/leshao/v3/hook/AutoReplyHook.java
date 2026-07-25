package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.hook.HookConfig;

/**
 * [功能5] AutoReply — 完整修复版
 * ==============================
 * 
 * ⚠️ 修复: isSend 判断
 *   storage.e9 没有 field_isSend 字段!
 *   G1() → boolean  是正确的 isSend 判断方法(混淆名)
 *   备选: Q1() → int  可能是另一个发送标志
 * 
 * 消息入库检测: f9.Ra(long, e9)
 * 发送回复: 构造 e9 → f9.Ra() 写入DB → 微信同步机制自动发送
 */
public class AutoReplyHook {

    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    private static final long COOLDOWN_MS = 5000;
    private static final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();
    private static ClassLoader classLoader;
    private static Object msgStorage;

    public static void hook(ClassLoader cl) {
        XposedBridge.log("[AutoReplyHook] hook() ENTER sEnabled=" + sEnabled);
        if (!sEnabled) return;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        XposedBridge.log("[AutoReplyHook] config.autoReplyEnabled=" + config.autoReplyEnabled);
        if (config == null || !config.autoReplyEnabled) return;
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

    /** 获取 f9 实例 */
    private static void hookMsgStorage(ClassLoader cl) {
        try {
            Class<?> d9 = VersionCompat.findMsgStorageShortClass(cl);
            if (d9 == null) return;
            Object service = XposedHelpers.callStaticMethod(d9, "b");
            if (service != null) {
                msgStorage = XposedHelpers.callMethod(service, "u");
                XposedBridge.log("[AutoReply] f9实例获取成功");
            }
        } catch (Throwable t) {
            XposedBridge.log("[AutoReply] f9实例获取失败: " + t.getMessage());
        }
    }

    /** Hook f9.Ra() — 新消息入库 */
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
                    if (msgStorage == null) msgStorage = param.thisObject;
                    if (param.args.length >= 2) handleNewMsg(param.args[1]);
                }
            });
            XposedBridge.log("[AutoReply] f9.Ra() Hook完成");
        } catch (Throwable t) {}
    }

    private static void handleNewMsg(Object msgInfo) {
        if (msgInfo == null) return;
        try {
            // ⚠️ 修复: 使用 G1() 而非 field_isSend
            if (isSentByMe(msgInfo)) return;

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
     * ⚠️ 修复: 正确的 isSend 判断
     * 
     * storage.e9 没有 field_isSend 字段!
     * G1() → boolean  混淆名, 返回 true=自己发送
     */
    private static boolean isSentByMe(Object msgInfo) {
        try {
            return (Boolean) XposedHelpers.callMethod(msgInfo, "G1");
        } catch (Throwable e1) {
            try {
                int q1 = (Integer) XposedHelpers.callMethod(msgInfo, "Q1");
                return q1 == 1;
            } catch (Throwable e2) {
                return false;
            }
        }
    }

    private static void doSendReply(String talker, String replyText) {
        try {
            Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(classLoader);
            if (e9Class == null) return;
            Object msg = XposedHelpers.newInstance(e9Class, talker);
            XposedHelpers.callMethod(msg, "A1", 1);          // setType(1)=文本
            XposedHelpers.callMethod(msg, "X0", replyText);   // setContent
            XposedHelpers.callMethod(msg, "L1", System.currentTimeMillis());

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

            Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(classLoader);
            if (e9Class == null) return;
            Object msg = XposedHelpers.newInstance(e9Class, talker);
            XposedHelpers.callMethod(msg, "A1", 1);
            XposedHelpers.callMethod(msg, "X0", replyText);
            XposedHelpers.callMethod(footer, "F", msg, null);
            XposedBridge.log("[AutoReply] ✅ 回复(via Footer): " + replyText);
        } catch (Throwable t) {}
    }
}
