package com.wechatplus;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoReply — 完整修复版 (isSend + msgId + f9实例 + 语音发送)
 * ============================================================
 * 
 * 修复项:
 *   1. f9实例获取: e01.d9.b().u() (不是旧的 d9.b().u())
 *   2. msgId:      H9(msg) 自动分配 (不是 System.currentTimeMillis())
 *   3. createTime: e1(long)  (不是 L1(long), L1是msgId校验器)
 *   4. isSend:     G1() 读, k1(1) 写
 *   5. 语音发送:    y21.x0.t(filePath, duration, 0, null) 一行搞定
 */
public class AutoReply {

    private static final long COOLDOWN_MS = 5000;
    private static final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();
    private static ClassLoader classLoader;
    private static Object msgStorage;

    public static void hook(ClassLoader cl) {
        if (!WeChatPlusConfig.isEnabled("auto_reply")) return;
        classLoader = cl;
        loadRules();
        hookMsgStorage(cl);
        hookMessageReceive(cl);
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
        String saved = WeChatPlusConfig.getString("auto_reply_rules", "");
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

    /**
     * ✅ 修复: 获取 f9 实例
     *   旧: d9.b().u() 
     *   新: e01.d9.b().u()
     * 
     *   从 af5.a.run() smali 第26-27行确认:
     *     invoke-static {}, Le01/d9;->b()Le01/f;
     */
    private static void hookMsgStorage(ClassLoader cl) {
        try {
            Class<?> e01d9 = XposedHelpers.findClass("e01.d9", cl);
            Object service = XposedHelpers.callStaticMethod(e01d9, "b");   // e01.f
            if (service != null) {
                msgStorage = XposedHelpers.callMethod(service, "u");       // storage.f9
                XposedBridge.log("[AutoReply] ✅ f9实例获取成功 (e01.d9.b().u())");
            }
        } catch (Throwable t) {
            XposedBridge.log("[AutoReply] ❌ f9实例获取失败: " + t.getMessage());
        }
    }

    /**
     * Hook f9.Ra() — 新消息入库检测
     */
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
            // ✅ 修复: 用 G1() 判断是否自己发送
            if (isSentByMe(msgInfo)) return;

            int type = (Integer) XposedHelpers.callMethod(msgInfo, "getType");
            if (type != 1) return; // 只处理文本

            String content = (String) XposedHelpers.getObjectField(msgInfo, "field_content");
            String talker = (String) XposedHelpers.getObjectField(msgInfo, "field_talker");
            if (content == null || talker == null) return;

            Long last = cooldowns.get(talker);
            long now = System.currentTimeMillis();
            if (last != null && now - last < COOLDOWN_MS) return;

            for (Map.Entry<String, String> rule : rules.entrySet()) {
                if (content.contains(rule.getKey())) {
                    cooldowns.put(talker, now);
                    doSendTextReply(talker, rule.getValue());
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * ✅ 修复: G1() → boolean  正确的 isSend 判断
     */
    private static boolean isSentByMe(Object msgInfo) {
        try { return (Boolean) XposedHelpers.callMethod(msgInfo, "G1"); }
        catch (Throwable e) { return false; }
    }

    /**
     * ✅ 修复: 发送文本回复
     *   - 用 e01.d9.b().u() 获取 f9
     *   - 用 H9(msg) 自动分配 msgId
     *   - 用 e1(long) 设置创建时间
     *   - 用 k1(1) 标记为自己发送
     */
    private static void doSendTextReply(String talker, String replyText) {
        try {
            if (msgStorage == null) {
                XposedBridge.log("[AutoReply] msgStorage为null, 无法发送");
                return;
            }

            Class<?> e9Class = XposedHelpers.findClass("com.tencent.mm.storage.e9", classLoader);
            Object msg = XposedHelpers.newInstance(e9Class, talker);

            // 设置消息属性
            XposedHelpers.callMethod(msg, "A1", 1);                 // setType=文本
            XposedHelpers.callMethod(msg, "X0", replyText);          // setContent
            XposedHelpers.callMethod(msg, "e1", System.currentTimeMillis()); // ✅ setCreateTime

            // ✅ 标记为自己发送
            XposedHelpers.callMethod(msg, "k1", 1);                 // isSend=1

            // ✅ 自动分配 msgId 并插入DB
            long msgId = (Long) XposedHelpers.callMethod(msgStorage, "H9", msg);

            if (msgId > 0) {
                XposedBridge.log("[AutoReply] ✅ 回复: "
                        + replyText.substring(0, Math.min(20, replyText.length()))
                        + " → " + talker + " (msgId=" + msgId + ")");
            } else {
                XposedBridge.log("[AutoReply] ❌ 插入失败 msgId=" + msgId);
            }
        } catch (Throwable t) {
            XposedBridge.log("[AutoReply] 发送失败: " + t.getMessage());
        }
    }

    /**
     * ✅ 发送语音回复 (显示为语音气泡, 不是卡片XML)
     * 
     * 调用微信内置 y21.x0.t() 一行搞定:
     *   - d1(u0.c(nickname, duration, false))  → 正确的语音XML
     *   - j1(filePath)                          → 文件路径
     *   - t1(1)                                 → 语音状态
     *   - setType(34)                           → 语音类型
     *   - 写入DB
     */
    public static boolean sendVoiceReply(ClassLoader cl, String voiceFilePath, int durationMs) {
        try {
            Class<?> y21x0 = XposedHelpers.findClass("y21.x0", cl);
            boolean ok = (Boolean) XposedHelpers.callStaticMethod(y21x0, "t",
                    voiceFilePath,    // String: 语音文件完整路径
                    durationMs,       // int:    时长(毫秒)
                    0,                // int:    flag
                    null);            // e9:     引用消息(无)

            if (ok) {
                XposedBridge.log("[AutoReply] ✅ 语音回复已发送: " + durationMs + "ms");
            }
            return ok;
        } catch (Throwable t) {
            XposedBridge.log("[AutoReply] ❌ 语音发送失败: " + t.getMessage());
            return false;
        }
    }
}
