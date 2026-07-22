package com.leshao.wechat;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WeChatHooks {
    private static ClassLoader cl;
    private static String apkPath;
    private static Object cachedDbObj;
    private static final Map<String,String> nameCache = new ConcurrentHashMap<>();
    private static final java.util.Set<String> processedAmrSet = new java.util.HashSet<>();

    public static Map<String,String> getNameCache() { return nameCache; }

    public static void init(ClassLoader cl_, String apkPath_) {
        cl = cl_;
        apkPath = apkPath_;
        hookMessageProcessor();
        hookAntiRecall();
        hookRedPacket();
        hookAutoAcceptFriend();
        hookChatroomEvents();
        hookChatroomMemberChange();
        Utils.xlog("WeChatHooks OK");
    }

    // ===== 消息处理 (核心) =====
    private static void hookMessageProcessor() {
        try {
            Class<?> a2 = cl.loadClass("com.tencent.mm.plugin.messenger.foundation.a2");
            Utils.xlog("hookMsg a2 class found");
            for (final java.lang.reflect.Method m : a2.getDeclaredMethods()) {
                if (m.getName().equals("b") && m.getParameterTypes().length == 3) {
                    Utils.xlog("hookMsg matched method b(3 params)");
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object q0 = param.getResult(); if (q0 == null) return;
                                Object e9 = XposedHelpers.getObjectField(q0, "a"); if (e9 == null) return;
                                WeChatMsg msg = WeChatMsg.fromE9(e9);
                                if (msg != null && msg.talker != null && !msg.talker.isEmpty()) {
                                    Utils.xlog("MSG type=" + msg.type + " talker=" + msg.talker + " sender=" + msg.senderWxid + " content=" + (msg.content != null ? msg.content.substring(0, Math.min(50, msg.content.length())) : "null"));
                                    // 敏感词过滤
                                    if (ModuleSettings.sensitiveFilterEnabled && msg.isText() && msg.content != null) {
                                        for (String sw : ModuleSettings.sensitiveWords) {
                                            if (msg.content.contains(sw)) { Utils.xlog("Sensitive word blocked: " + sw); return; }
                                        }
                                    }
                                    // 防广告检测
                                    if (ModuleSettings.antiAdEnabled && msg.isText() && msg.content != null && msg.isGroup) {
                                        String lc = msg.content.toLowerCase();
                                        for (String kw : ModuleSettings.adKeywords) {
                                            if (lc.contains(kw.toLowerCase())) {
                                                Utils.xlog("Ad detected from " + msg.senderWxid + " keyword=" + kw);
                                                // 记录违规
                                                incrViolation(msg.talker, msg.senderWxid);
                                                int count = getViolation(msg.talker, msg.senderWxid);
                                                if (ModuleSettings.autoKickEnabled && count >= ModuleSettings.kickThreshold) {
                                                    delChatroomMember(msg.talker, msg.senderWxid);
                                                    sendTextMessage(msg.talker, "\uD83D\uDEAB " + resolveSenderName(msg.senderWxid, msg.talker) + " " + ModuleSettings.farewellMsg);
                                                    clearViolation(msg.talker, msg.senderWxid);
                                                } else if (ModuleSettings.warnType > 0) {
                                                    sendTextMessage(msg.talker, "\u26A0 " + resolveSenderName(msg.senderWxid, msg.talker) + " " + ModuleSettings.warnMsg + " (" + count + "/" + ModuleSettings.kickThreshold + ")");
                                                }
                                                return;
                                            }
                                        }
                                    }
                                    // 关键词回复
                                    if (ModuleSettings.keywordReplyEnabled && msg.isText() && msg.content != null) {
                                        for (String kw : ModuleSettings.keywordReplyMap.keySet()) {
                                            if (msg.content.trim().equals(kw)) {
                                                Map<String,String> m = ModuleSettings.keywordReplyMap.get(kw);
                                                String reply = m != null ? m.get("reply") : null;
                                                if (reply != null && !reply.isEmpty()) sendTextMessage(msg.talker, reply);
                                                return;
                                            }
                                        }
                                    }
                                    // JDY叮咚指令
                                    if (ModuleSettings.dianGeEnabled && msg.isText() && msg.content != null) {
                                        handleDingDong(msg);
                                    }
                                    // DeepSeek处理
                                    if (ModuleSettings.deepseekEnabled && msg.isText() && msg.content != null) {
                                        handleDeepSeek(msg);
                                    }
                                    // AI工具箱
                                    if (ModuleSettings.aiToolboxEnabled && msg.isText() && msg.content != null) {
                                        handleAIToolbox(msg);
                                    }
                                    AnnounceManager.handleMessage(msg);
                                }
                            } catch (Exception e) { Utils.xlog("hookMsg proc err: " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
                        }
                    });
                    return;
                }
            }
            Utils.xlog("hookMsg FAILED — no matching method found in a2");
        } catch (Exception e) { Utils.xlog("hookMsg err: " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
    }

    // ===== JDY叮咚指令 =====
    private static void handleDingDong(WeChatMsg msg) {
        String ct = msg.content.trim();
        String talker = msg.talker;
        if (ct.startsWith("点歌 ")) {
            String kw = ct.substring(3).trim();
            if (kw.isEmpty()) { sendTextMessage(talker, "请输入歌名，如: 点歌 稻香"); return; }
            new Thread(() -> {
                try {
                    String url = "https://api.suxun.com/music/search?keyword=" + java.net.URLEncoder.encode(kw, "UTF-8") + "&type=song";
                    String json = ModuleSettings.jdyHttpGet(url);
                    if (json != null) {
                        org.json.JSONObject obj = new org.json.JSONObject(json);
                        org.json.JSONArray list = obj.optJSONArray("data");
                        if (list != null && list.length() > 0) {
                            StringBuilder sb = new StringBuilder("\uD83C\uDFB5 搜索结果:\n");
                            for (int i = 0; i < Math.min(5, list.length()); i++) {
                                org.json.JSONObject it = list.getJSONObject(i);
                                sb.append(i+1).append(". ").append(it.optString("name","?")).append(" - ").append(it.optString("artist","?")).append("\n");
                            }
                            sendTextMessage(talker, sb.toString().trim());
                        } else { sendTextMessage(talker, "未找到相关歌曲"); }
                    }
                } catch (Exception e) { sendTextMessage(talker, "搜索失败: " + e.getMessage()); }
            }).start();
        } else if (ct.startsWith("歌词 ")) {
            String kw = ct.substring(3).trim();
            if (kw.isEmpty()) { sendTextMessage(talker, "请输入歌名，如: 歌词 晴天"); return; }
            new Thread(() -> {
                try {
                    String url = "https://api.suxun.com/music/lyric?keyword=" + java.net.URLEncoder.encode(kw, "UTF-8");
                    String json = ModuleSettings.jdyHttpGet(url);
                    if (json != null) {
                        org.json.JSONObject obj = new org.json.JSONObject(json);
                        String lyric = obj.optString("lyric", obj.optString("data", ""));
                        if (!lyric.isEmpty() && lyric.length() > 500) lyric = lyric.substring(0, 500) + "...";
                        sendTextMessage(talker, lyric.isEmpty() ? "未找到歌词" : "\uD83C\uDFB6 歌词:\n" + lyric);
                    }
                } catch (Exception e) { sendTextMessage(talker, "查询失败"); }
            }).start();
        } else if (ct.startsWith("天气 ")) {
            String city = ct.substring(3).trim();
            if (city.isEmpty()) { sendTextMessage(talker, "请输入城市名，如: 天气 北京"); return; }
            new Thread(() -> {
                try {
                    String url = "https://api.suxun.com/weather?city=" + java.net.URLEncoder.encode(city, "UTF-8");
                    String json = ModuleSettings.jdyHttpGet(url);
                    if (json != null) {
                        org.json.JSONObject obj = new org.json.JSONObject(json);
                        String weather = obj.optString("weather", obj.optString("data", ""));
                        sendTextMessage(talker, weather.isEmpty() ? "未查到天气信息" : "\u2600 天气: " + weather);
                    }
                } catch (Exception e) { sendTextMessage(talker, "查询失败"); }
            }).start();
        } else if ("笑话".equals(ct)) {
            String[] jokes = ModuleSettings.JOKE_LIB;
            String joke = jokes[new java.util.Random().nextInt(jokes.length)];
            sendTextMessage(talker, "\uD83E\uDD23 " + joke);
        } else if ("金句".equals(ct)) {
            String[] quotes = ModuleSettings.QUOTE_LIB;
            String quote = quotes[new java.util.Random().nextInt(quotes.length)];
            sendTextMessage(talker, "\uD83D\uDC8E " + quote);
        }
    }

    // ===== AI工具箱 =====
    private static void handleAIToolbox(WeChatMsg msg) {
        String ct = msg.content.trim();
        String talker = msg.talker;
        if (ModuleSettings.imageGenEnabled && ct.startsWith("生图 ")) {
            String prompt = ct.substring(3).trim();
            if (prompt.isEmpty()) { sendTextMessage(talker, "请输入图片描述，如: 生图 一只可爱的猫咪"); return; }
            new Thread(() -> {
                try {
                    String result = AiImageManager.generateImage(prompt);
                    sendTextMessage(talker, result != null ? "\uD83C\uDFA8 图片已生成: " + result : "图片生成失败");
                } catch (Exception e) { sendTextMessage(talker, "生成失败: " + e.getMessage()); }
            }).start();
        } else if (ModuleSettings.videoGenEnabled && ct.startsWith("生视频 ")) {
            String prompt = ct.substring(4).trim();
            if (prompt.isEmpty()) { sendTextMessage(talker, "请输入视频描述，如: 生视频 海边日落"); return; }
            new Thread(() -> {
                try {
                    String result = AiImageManager.generateVideo(prompt);
                    sendTextMessage(talker, result != null ? "\uD83C\uDFAC 视频已生成: " + result : "视频生成失败");
                } catch (Exception e) { sendTextMessage(talker, "生成失败: " + e.getMessage()); }
            }).start();
        } else if (ModuleSettings.voiceToTextEnabled && ct.startsWith("#v2t")) {
            sendTextMessage(talker, "\uD83C\uDF99 语音转文字功能需要微信语音消息触发");
        }
    }

    private static void handleDeepSeek(WeChatMsg msg) {
        String ct = msg.content.trim();
        String talker = msg.talker;
        if (ModuleSettings.deepseekSmartReply && !ModuleSettings.WHITE_LIST.isEmpty()
            && ModuleSettings.WHITE_LIST.contains(talker)) {
            String reply = DeepSeekManager.chat(talker, ct);
            if (reply != null && !reply.isEmpty()) sendTextMessage(talker, reply);
        }
        if (ModuleSettings.deepseekTranslate && ct.startsWith("翻译 ")) {
            String reply = DeepSeekManager.translate(ct.substring(3));
            if (reply != null && !reply.isEmpty()) sendTextMessage(talker, reply);
        }
        if (ModuleSettings.deepseekSummary && ct.startsWith("摘要 ")) {
            String reply = DeepSeekManager.summarize(ct.substring(3));
            if (reply != null && !reply.isEmpty()) sendTextMessage(talker, reply);
        }
        if (ModuleSettings.deepseekAtReply && msg.isGroup) {
            try {
                String selfWxid = getSelfWxid();
                if (selfWxid != null && !selfWxid.isEmpty() && msg.dbContent != null && msg.dbContent.contains(selfWxid)) {
                    String reply = DeepSeekManager.chat(talker, ct);
                    if (reply != null && !reply.isEmpty()) sendTextMessage(talker, reply);
                }
            } catch (Exception e) {}
        }
    }

    // ===== 防撤回 =====
    private static void hookAntiRecall() {
        if (!ModuleSettings.recallLogEnabled) { Utils.xlog("Anti-recall disabled"); return; }
        try {
            Class<?> recallCls = cl.loadClass("com.tencent.mm.modelmulti.j");
            Utils.xlog("Anti-recall class found");
            for (final java.lang.reflect.Method m : recallCls.getDeclaredMethods()) {
                if (m.getParameterTypes().length >= 1) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object args = param.args[0];
                                long msgId = XposedHelpers.getLongField(args, "msgId");
                                String talker = (String) XposedHelpers.getObjectField(args, "talker");
                                Utils.xlog("Anti-recall blocked msgId=" + msgId);
                                WeChatHooks.sendTextMessage(talker, "\u26A0 检测到消息撤回 [msgId=" + msgId + "]");
                                param.setResult(null);
                            } catch (Exception e) {}
                        }
                    });
                    Utils.xlog("Anti-recall hooked");
                    return;
                }
            }
            Utils.xlog("Anti-recall FAILED — no matching method");
        } catch (Exception e) { Utils.xlog("Anti-recall err: " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
    }

    // ===== 抢红包 =====
    private static void hookRedPacket() {
        if (!ModuleSettings.redPacketGrabEnabled) { Utils.xlog("RedPacket disabled"); return; }
        try {
            Class<?> rpCls = cl.loadClass("com.tencent.mm.plugin.luckymoney.model.z");
            Utils.xlog("RedPacket class found");
            for (final java.lang.reflect.Method m : rpCls.getDeclaredMethods()) {
                if (m.getParameterTypes().length >= 2) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Utils.xlog("Red packet detected, auto-opening...");
                                Object result = param.getResult();
                                if (result != null) {
                                    String talker = (String) XposedHelpers.callMethod(result, "getTalker");
                                    if (talker != null) WeChatHooks.sendTextMessage(talker, "\uD83C\uDF89 已自动领取红包");
                                }
                            } catch (Exception e) {}
                        }
                    });
                    Utils.xlog("RedPacket hooked");
                    return;
                }
            }
            Utils.xlog("RedPacket FAILED — no matching method");
        } catch (Exception e) { Utils.xlog("RedPacket err: " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
    }

    // ===== 自动通过好友申请 =====
    private static void hookAutoAcceptFriend() {
        if (!ModuleSettings.autoAcceptFriend) { Utils.xlog("AutoAcceptFriend disabled"); return; }
        try {
            Class<?> nfsCls = null;
            for (String cn : new String[]{
                "com.tencent.mm.modelmulti.k",
                "com.tencent.mm.modelmulti.j",
                "com.tencent.mm.modelmulti.l",
                "com.tencent.mm.modelmulti.i",
            }) {
                try { nfsCls = cl.loadClass(cn); Utils.xlog("AutoAcceptFriend class found: " + cn); break; }
                catch (Throwable e) {}
            }
            if (nfsCls == null) { Utils.xlog("AutoAcceptFriend FAILED — class not found"); return; }
            for (final java.lang.reflect.Method m : nfsCls.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length >= 1 && String.class.isAssignableFrom(pts[0]) && m.getName().startsWith("a")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String wxid = (String) param.args[0];
                                if (wxid != null && !wxid.isEmpty()) {
                                    Utils.xlog("AutoAcceptFriend: processing " + wxid);
                                    boolean ok = AutoAcceptFriend.accept(wxid);
                                    Utils.xlog("AutoAcceptFriend: result=" + ok + " for " + wxid);
                                }
                            } catch (Throwable e) { Utils.xlog("AutoAcceptFriend hook err: " + e.getMessage()); }
                        }
                    });
                    Utils.xlog("AutoAcceptFriend hooked on method: " + m.getName());
                    return;
                }
            }
            Utils.xlog("AutoAcceptFriend FAILED — no matching method");
        } catch (Throwable e) { Utils.xlog("AutoAcceptFriend err: " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
    }

    // ===== 群事件 =====
    private static void hookChatroomEvents() {
        try {
            Class<?> crCls = null;
            for (String cn : new String[]{
                "com.tencent.mm.plugin.chatroom.a.b", "com.tencent.mm.plugin.chatroom.a.c",
                "com.tencent.mm.plugin.chatroom.a.d", "com.tencent.mm.plugin.chatroom.a.a",
                "com.tencent.mm.plugin.chatroom.b.a", "com.tencent.mm.plugin.chatroom.b.b",
            }) {
                try { crCls = cl.loadClass(cn); Utils.xlog("ChatroomEvents class found: " + cn); break; }
                catch (Throwable e) {}
            }
            if (crCls == null) { Utils.xlog("ChatroomEvents FAILED — all class names tried"); return; }
            for (final java.lang.reflect.Method m : crCls.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 2 && m.getName().equals("a")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String groupId = (String) param.args[0];
                                String memberId = (String) param.args[1];
                                if (groupId != null && ModuleSettings.leftGroupTipEnabled) {
                                    String tip = ModuleSettings.leftGroupTipMsg;
                                    if (tip == null || tip.isEmpty()) tip = "有成员离开了群聊";
                                    sendTextMessage(groupId, "\uD83D\uDCE2 " + tip);
                                }
                            } catch (Exception e) {}
                        }
                    });
                    Utils.xlog("ChatroomEvents hooked");
                    return;
                }
            }
            Utils.xlog("ChatroomEvents FAILED — no matching method a(2 params)");
        } catch (Exception e) { Utils.xlog("ChatroomEvents err: " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
    }

    // ===== 群成员变更 (欢迎/黑名单/群邀请) =====
    private static void hookChatroomMemberChange() {
        try {
            Class<?> memberCls = null;
            for (String cn : new String[]{
                "com.tencent.mm.chatroom.c.b","com.tencent.mm.chatroom.d.b",
                "com.tencent.mm.plugin.chatroom.a.c","com.tencent.mm.plugin.chatroom.a.d",
                "com.tencent.mm.plugin.chatroom.b.c","com.tencent.mm.plugin.chatroom.c.d",
            }) {
                try { memberCls = cl.loadClass(cn); Utils.xlog("MemberChange class found: " + cn); break; } catch (Exception e) {}
            }
            if (memberCls == null) { Utils.xlog("MemberChange FAILED — no class found"); return; }
            for (final java.lang.reflect.Method m : memberCls.getDeclaredMethods()) {
                if (m.getParameterTypes().length >= 2) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String groupId = null, memberId = null, type = null;
                                for (Object arg : param.args) {
                                    if (arg instanceof String) {
                                        String s = (String) arg;
                                        if (s.endsWith("@chatroom")) groupId = s;
                                        else if (s.startsWith("wxid_") || s.endsWith("@im.wechat")) memberId = s;
                                        else if ("join".equals(s) || "leave".equals(s) || "invite".equals(s)) type = s;
                                    }
                                }
                                if (groupId == null || memberId == null) return;
                                if ("join".equals(type)) {
                                    // 黑名单检测
                                    if (ModuleSettings.blacklistEnabled && ModuleSettings.blacklistMap.containsKey(memberId)) {
                                        delChatroomMember(groupId, memberId);
                                        Utils.xlog("Blacklist member kicked: " + memberId + " @ " + groupId);
                                        return;
                                    }
                                    // 入群欢迎
                                    if (ModuleSettings.welcomeEnabled) {
                                        String name = resolveSenderName(memberId, groupId);
                                        String welcome = ModuleSettings.welcomeMsg;
                                        if (welcome == null || welcome.trim().isEmpty()) welcome = "欢迎加入群聊!";
                                        sendTextMessage(groupId, "\uD83C\uDF89 " + name + " " + welcome);
                                    }
                                }
                            } catch (Exception e) {}
                        }
                    });
                    Utils.xlog("ChatroomMemberChange hooked");
                    return;
                }
            }
            Utils.xlog("ChatroomMemberChange FAILED — no matching method");
        } catch (Exception e) { Utils.xlog("ChatroomMemberChange err: " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
    }

    // ===== 违规记录 =====
    private static void incrViolation(String gid, String wxid) {
        Map<String, Integer> m = ModuleSettings.userViolationMap.get(gid);
        if (m == null) { m = new java.util.HashMap<>(); ModuleSettings.userViolationMap.put(gid, m); }
        Integer c = m.get(wxid);
        m.put(wxid, (c == null ? 0 : c) + 1);
    }
    private static int getViolation(String gid, String wxid) {
        Map<String, Integer> m = ModuleSettings.userViolationMap.get(gid);
        if (m == null) return 0;
        Integer c = m.get(wxid);
        return c == null ? 0 : c;
    }
    private static void clearViolation(String gid, String wxid) {
        Map<String, Integer> m = ModuleSettings.userViolationMap.get(gid);
        if (m != null) m.remove(wxid);
    }

    public static void delChatroomMember(String gid, String wxid) {
        try {
            Class<?> netSceneCls = null;
            for (String sn : new String[]{"com.tencent.mm.modelmulti.k","com.tencent.mm.modelmulti.l"}) {
                try { netSceneCls = cl.loadClass(sn); break; } catch (Exception e) {}
            }
            if (netSceneCls != null) {
                Object req = XposedHelpers.newInstance(netSceneCls, gid, wxid);
                Object netScene = XposedHelpers.newInstance(netSceneCls, gid, java.util.Collections.singletonList(wxid));
                XposedHelpers.callMethod(netScene, "doScene");
            }
        } catch (Exception e) { Utils.xlog("delChatroomMember error: " + e.getMessage()); }
    }

    // ===== 名称解析 =====
    public static String resolveSenderName(String wxid, String groupWxid) {
        if (wxid == null || wxid.isEmpty()) return "好友";
        String key = wxid + "@@" + (groupWxid != null ? groupWxid : "");
        if (nameCache.containsKey(key)) return nameCache.get(key);
        String name = null;
        if (groupWxid != null) name = getFriendDisplayName(wxid, groupWxid);
        if (name == null || name.isEmpty()) name = callContactSvc(wxid, "getRemarkName");
        if (name == null || name.isEmpty()) name = callContactSvc(wxid, "getNickname");
        if (name == null || name.isEmpty()) name = callContactSvc(wxid, "getUsername");
        if (name == null || name.isEmpty()) { name = wxid; if (name.contains("@")) name = name.substring(0, name.indexOf("@")); }
        nameCache.put(key, name); return name;
    }

    public static String resolveGroupName(String gid) {
        if (gid == null || !gid.contains("@chatroom")) return null;
        if (nameCache.containsKey(gid)) return nameCache.get(gid);
        String name = callContactSvc(gid, "getUsername");
        if (name == null || name.isEmpty()) name = callContactSvc(gid, "getNickname");
        if (name == null || name.isEmpty()) { name = gid; if (name.contains("@")) name = name.substring(0, name.indexOf("@")); }
        nameCache.put(gid, name); return name;
    }

    private static String getFriendDisplayName(String wxid, String gid) {
        try { for (String cn : new String[]{"com.tencent.mm.chatroom.c$a","com.tencent.mm.chatroom.c.a"}) {
            try { Class<?> cls = cl.loadClass(cn); Object info = null;
                try { info = XposedHelpers.callStaticMethod(cls, "a", gid, wxid); } catch(Exception e){}
                if (info != null) { try { return (String) XposedHelpers.callMethod(info, "getDisplayName"); } catch(Exception e){ try { return (String) XposedHelpers.callMethod(info, "getNickname"); } catch(Exception e2){} } }
            } catch(Exception e){} }
        } catch(Exception e){} return null;
    }

    private static String callContactSvc(String wxid, String method) {
        try {
            Object cs = findSvc("com.tencent.mm.plugin.contact.a$b","com.tencent.mm.plugin.contact.a.b");
            if (cs == null) return null;
            Object ct = null;
            try { ct = XposedHelpers.callMethod(cs, "KN", wxid); } catch(Exception e){}
            if (ct != null) { try { return (String) XposedHelpers.callMethod(ct, method); } catch(Exception e){} }
        } catch(Exception e){} return null;
    }

    private static Object findSvc(String... cns) {
        try {
            Class<?> k = findKernelClass();
            if (k == null) return null;
            for (String cn : cns) { try { return XposedHelpers.callStaticMethod(k, "ax", cl.loadClass(cn)); } catch(Exception e){} }
        } catch(Exception e){} return null;
    }

    // ===== WeKit方案: 直接SQL读取联系人 =====

    public static Object getWechatDatabase() {
        if (cachedDbObj != null) return cachedDbObj;
        Class<?> k = findKernelClass();
        if (k == null) {
            Utils.xlog("WeChatHooks: initDatabase - kernel class not found");
            return null;
        }
        for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 0) continue;
            Class<?> rt = m.getReturnType();
            if (rt == void.class || rt.isPrimitive()) continue;
            try {
                Object storage = m.invoke(null);
                if (storage == null) continue;
                Object db = extractSqliteDbFromStorage(storage);
                if (db != null) {
                    cachedDbObj = db;
                    Utils.xlog("WeChatHooks: DB via " + k.getName() + "." + m.getName() + "() OK");
                    return db;
                }
            } catch (Throwable e) {}
        }
        Utils.xlog("WeChatHooks: getWechatDatabase failed");
        return null;
    }

    private static Object extractSqliteDbFromStorage(Object storage) {
        for (java.lang.reflect.Field f : storage.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object val = f.get(storage);
                if (val == null) continue;
                try { return XposedHelpers.callMethod(val, "getWritableDatabase"); } catch (Throwable e1) {}
                try { return XposedHelpers.callMethod(val, "getDatabase"); } catch (Throwable e2) {}
                try { XposedHelpers.callMethod(val, "rawQuery", "SELECT 1", null); return val; } catch (Throwable e3) {}
            } catch (Throwable e) {}
        }
        return null;
    }

    public static java.util.List<String[]> getContactsFromDB() {
        java.util.List<String[]> list = new java.util.ArrayList<>();
        Object db = getWechatDatabase();
        if (db == null) return list;
        try {
            String sql = "SELECT username, alias, conRemark, nickname, type FROM rcontact";
            Object cursor = XposedHelpers.callMethod(db, "rawQuery", sql, null);
            while ((Boolean) XposedHelpers.callMethod(cursor, "moveToNext")) {
                int ciU = (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", "username");
                int ciA = (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", "alias");
                int ciR = (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", "conRemark");
                int ciN = (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", "nickname");
                int ciT = (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", "type");
                String wxid = (String) XposedHelpers.callMethod(cursor, "getString", ciU);
                if (wxid == null || wxid.isEmpty()) continue;
                String name = (String) XposedHelpers.callMethod(cursor, "getString", ciR);
                if (name == null || name.isEmpty()) name = (String) XposedHelpers.callMethod(cursor, "getString", ciN);
                if (name == null || name.isEmpty()) name = wxid;
                list.add(new String[]{wxid, name});
            }
            XposedHelpers.callMethod(cursor, "close");
            Utils.xlog("WeChatHooks: getContactsFromDB count=" + list.size());
        } catch (Throwable e) {
            Utils.xlog("WeChatHooks: getContactsFromDB error: " + e.getMessage());
        }
        return list;
    }

    // ===== 原有 kernel 类查找 =====

    /** 动态查找kernel服务类 - 使用DexFile枚举APK中所有类 */
    private static Class<?> cachedKernelClass = null;
    public static Class<?> findKernelClass() {
        if (cachedKernelClass != null) return cachedKernelClass;

        // Step 1: 尝试所有已知类名(单字母/双字母/常见名)
        for (String pkg : new String[]{"com.tencent.mm.kernel.", "com.tencent.mm.app."}) {
            for (String name : new String[]{
                "h","g","i","j","f","e","d","c","b","a",
                "k","l","m","n","o","p","q","r","s","t","u","v","w","x","y","z",
                "aa","ab","ac","ad","ae","af","ag","ah",
                "Core","Kernel","MMCore","MMKernel","App","MMApp",
                "kernel","plugin","service","Platform","SdkPlatform",
            }) {
                try {
                    Class<?> c = cl.loadClass(pkg + name);
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                        if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                            && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0] == Class.class) {
                            cachedKernelClass = c;
                            Utils.xlog("findKernelClass: FOUND " + pkg + name + " method=" + m.getName());
                            return c;
                        }
                    }
                } catch (Throwable e) {}
            }
        }

        // Step 2: DexFile枚举 - 用保存的APK路径直读，绕过ClassLoader隔离
        if (apkPath != null) {
            try {
                Utils.xlog("findKernelClass: DexFile scanning " + apkPath);
                dalvik.system.DexFile df = new dalvik.system.DexFile(apkPath);
                java.util.Enumeration<String> entries = df.entries();
                int scanned = 0, failed = 0;
                String lastErr = null;
                while (entries.hasMoreElements()) {
                    String cn = entries.nextElement();
                    if (cn.startsWith("com.tencent.mm.kernel.") || cn.startsWith("com.tencent.mm.app.")) {
                        scanned++;
                        try {
                            Class<?> c = df.loadClass(cn, cl);
                            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                                    && m.getParameterTypes().length == 1
                                    && m.getParameterTypes()[0] == Class.class) {
                                    cachedKernelClass = c;
                                    Utils.xlog("findKernelClass: DexFile FOUND " + cn + " method=" + m.getName());
                                    df.close();
                                    return c;
                                }
                            }
                        } catch (Throwable e) {
                            failed++;
                            if (lastErr == null) lastErr = cn + " → " + e.getClass().getSimpleName() + ": " + e.getMessage();
                        }
                    }
                }
                df.close();
                Utils.xlog("findKernelClass: DexFile done, scanned=" + scanned + " failed=" + failed + " firstErr=" + lastErr);
            } catch (Throwable e) {
                Utils.xlog("findKernelClass: DexFile scan failed: " + e.getClass().getName() + ": " + e.getMessage());
            }
        } else {
            Utils.xlog("findKernelClass: apkPath is null, cannot use DexFile scan");
        }

        Utils.xlog("findKernelClass: ALL attempts exhausted!");
        return null;
    }

    public static boolean sendTextMessage(String talker, String content) {
        try {
            Class<?> e9Cls = cl.loadClass("com.tencent.mm.storage.e9");
            Object msg = XposedHelpers.newInstance(e9Cls);
            XposedHelpers.callMethod(msg, "d1", content);
            XposedHelpers.callMethod(msg, "y1", talker);
            XposedHelpers.callMethod(msg, "setType", Integer.valueOf(1));
            XposedHelpers.callMethod(msg, "t1", Integer.valueOf(3));
            XposedHelpers.callMethod(msg, "e1", Long.valueOf(System.currentTimeMillis()));
            XposedHelpers.callMethod(msg, "k1", Integer.valueOf(1));
            Class<?> k = findKernelClass();
            if (k == null) { Utils.xlog("sendTextMessage: kernel class not found!"); return false; }
            Object svc = null;
            for (String sn : new String[]{"com.tencent.mm.plugin.messenger.foundation.a$y","com.tencent.mm.plugin.messenger.foundation.a.y"})
                try { svc = XposedHelpers.callStaticMethod(k, "ax", cl.loadClass(sn)); break; } catch(Exception e){}
            if (svc != null) { XposedHelpers.callMethod(svc, "a", msg, Boolean.FALSE); return true; }
        } catch(Exception e){} return false;
    }

    public static boolean isGroupChat(String w) { return w != null && w.endsWith("@chatroom"); }
    public static ClassLoader getCL() { return cl; }

    /** 字段扫描解析wxid - 参照WAuxiliary源码resolveObjWxid */
    public static String resolveObjWxid(Object info) {
        if (info == null) return null;
        // Try known getter methods
        String[] getters = {"getWxid", "getUsername", "getChatRoomName", "getChatroomName", "getRoomId", "getTalker"};
        for (String mn : getters) {
            try { String r = (String) info.getClass().getMethod(mn).invoke(info); if (r != null && !r.isEmpty()) return r; }
            catch (Throwable e) {}
        }
        // Try known public fields
        String[] pubFields = {"wxid", "username", "chatroomName", "chatRoomName", "mUsername"};
        for (String fn : pubFields) {
            try { String r = (String) info.getClass().getField(fn).get(info); if (r != null && !r.isEmpty()) return r; }
            catch (Throwable e1) {
                try {
                    java.lang.reflect.Field f = info.getClass().getDeclaredField(fn);
                    f.setAccessible(true);
                    String r = (String) f.get(info); if (r != null && !r.isEmpty()) return r;
                } catch (Throwable e2) {}
            }
        }
        // Scan all declared fields for wxid patterns
        try {
            for (java.lang.reflect.Field f : info.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(info);
                    if (val instanceof String) {
                        String s = (String) val;
                        if (s.contains("@chatroom") || s.startsWith("wxid_")) return s;
                    }
                } catch (Throwable e) {}
            }
        } catch (Throwable e) {}
        return null;
    }

    /** 从任意对象中提取名称 */
    public static String resolveObjName(Object info) {
        if (info == null) return null;
        String[] nameGetters = {"getNickname", "getRemarkName", "getDisplayName", "getName", "getChatroomName", "getConRemark"};
        for (String mn : nameGetters) {
            try { Object r = info.getClass().getMethod(mn).invoke(info); if (r instanceof String && !((String)r).isEmpty()) return (String) r; }
            catch (Throwable e) {}
        }
        String[] nameFields = {"nickname", "name", "remark", "displayName", "chatroomName"};
        for (String fn : nameFields) {
            try { Object r = info.getClass().getField(fn).get(info); if (r instanceof String && !((String)r).isEmpty()) return (String) r; }
            catch (Throwable e1) {
                try {
                    java.lang.reflect.Field f = info.getClass().getDeclaredField(fn);
                    f.setAccessible(true);
                    Object r = f.get(info); if (r instanceof String && !((String)r).isEmpty()) return (String) r;
                } catch (Throwable e2) {}
            }
        }
        return null;
    }

    private static String cachedSelfWxid = null;
    private static String getSelfWxid() {
        if (cachedSelfWxid != null) return cachedSelfWxid;
        try {
            try {
                Class<?> zCls = cl.loadClass("com.tencent.mm.model.z");
                cachedSelfWxid = (String) de.robv.android.xposed.XposedHelpers.callStaticMethod(zCls, "b");
                if (cachedSelfWxid != null && !cachedSelfWxid.isEmpty()) {
                    Utils.xlog("getSelfWxid from model.z.b(): " + cachedSelfWxid);
                    return cachedSelfWxid;
                }
            } catch (Throwable e) {}
            try {
                Class<?> kernelCls = findKernelClass();
                if (kernelCls != null) {
                    String[] accClasses = {"com.tencent.mm.kernel.b", "com.tencent.mm.kernel.a"};
                    for (String acn : accClasses) {
                        try {
                            Class<?> acCls = cl.loadClass(acn);
                            Object accSvc = de.robv.android.xposed.XposedHelpers.callStaticMethod(kernelCls, "ax", acCls);
                            if (accSvc != null) {
                                for (String mn : new String[]{"getUin", "getUsername", "bFp"}) {
                                    try {
                                        Object val = de.robv.android.xposed.XposedHelpers.callMethod(accSvc, mn);
                                        if (val instanceof String && !((String)val).isEmpty()) {
                                            cachedSelfWxid = (String) val;
                                            Utils.xlog("getSelfWxid from kernel." + acn + "." + mn + "(): " + cachedSelfWxid);
                                            return cachedSelfWxid;
                                        }
                                    } catch (Throwable e) {}
                                }
                            }
                        } catch (Throwable e) {}
                    }
                }
            } catch (Throwable e) {}
        } catch (Throwable e) {
            Utils.xlog("getSelfWxid failed: " + e.getMessage());
        }
        return null;
    }
}
