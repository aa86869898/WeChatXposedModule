package com.leshao.v3.hook;

import android.os.Handler;
import android.os.Looper;


import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.KeywordRule;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.service.TTSBroadcaster;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class MessageHook {

    private static final String TAG = "MessageHook";
    private static int sCount = 0;
    private static Handler sMainHandler;
    private static ClassLoader sClassLoader;
    private static final Set<Long> sSeenMsgIds = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Boolean> sConsumedTtsOriginal = new ThreadLocal<>();

    public static void hook(ClassLoader cl) {
        sClassLoader = cl;
        sMainHandler = new Handler(Looper.getMainLooper());

        LogWriter.log(TAG, "=== v56 IEvent.e hook ===");
        android.util.Log.e(TAG, "=== v56 IEvent.e hook ===");

        // Defer message hook discovery until DexKit scan completes
        com.leshao.v3.hook.DexKitHelper.addPostScanCallback(() -> {
            hookX9Dispatch(cl);
            hookIEventBus(cl);
            LogWriter.log(TAG, "MessageHook post-scan init done");
        });
        hookSensitiveBlock(cl);
    }

    /**
     * 敏感词过滤: 在消息入库层 (f9.Ra) 拦截含敏感词的接收消息, 使其不显示在聊天列表。
     * 与 processKeywordAndSensitive 的「不播报」形成双保险: 这里直接阻断消息入库。
     */
    private static void hookSensitiveBlock(ClassLoader cl) {
        try {
            Class<?> f9 = VersionCompat.findMsgStorageClass(cl);
            if (f9 == null) {
                LogWriter.log(TAG, "sensitive block: f9 storage class not found");
                return;
            }
            XposedBridge.hookAllMethods(f9, "Ra", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length < 2 || param.args[1] == null) return;
                        ModuleConfig cfg = ModuleConfig.load(ContextManager.getPrefs());
                        if (cfg == null || !cfg.sensitiveFilterEnabled
                                || cfg.sensitiveWords == null || cfg.sensitiveWords.isEmpty()) return;

                        Object msg = param.args[1];
                        // 只过滤接收消息
                        int isSend = -1;
                        try { isSend = (Integer) XposedHelpers.callMethod(msg, "z0"); }
                        catch (Throwable ignored) {}
                        try {
                            java.lang.reflect.Field f = msg.getClass().getDeclaredField("field_isSend");
                            f.setAccessible(true);
                            Object v = f.get(msg);
                            if (v instanceof Integer) isSend = (Integer) v;
                        } catch (Throwable ignored) {}
                        if (isSend == 1) return;

                        String content = readMsgContent(msg);
                        if (content == null || content.isEmpty()) return;
                        for (String w : cfg.sensitiveWords) {
                            if (w != null && !w.isEmpty() && content.contains(w)) {
                                LogWriter.log(TAG, "[Sensitive] 拦截敏感词消息入库: " + w
                                        + " msg=" + trunc(content, 40));
                                param.setResult(null);
                                return;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "sensitive block hooks installed (f9.Ra)");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookSensitiveBlock FAIL: " + t.getMessage());
        }
    }

    private static String readMsgContent(Object msg) {
        try {
            java.lang.reflect.Field f = msg.getClass().getDeclaredField("field_content");
            f.setAccessible(true);
            Object v = f.get(msg);
            if (v != null) return v.toString();
        } catch (Throwable ignored) {}
        try {
            Object v = XposedHelpers.callMethod(msg, "getContent");
            if (v != null) return v.toString();
        } catch (Throwable ignored) {}
        try {
            Object v = XposedHelpers.callMethod(msg, "I0");
            if (v != null) return v.toString();
        } catch (Throwable ignored) {}
        return null;
    }

    // ====== x9 分发 (接收消息: TTS + 语音播放) ======

    private static void hookX9Dispatch(ClassLoader cl) {
        try {
            Class<?> x9Cls = null;
            // 1) 先用 DexKit 字符串搜索动态发现 x9 类（消息分发类）
            List<String> candidates = DexKitHelper.findClassesByString(cl, "IEvent");
            for (String cn : candidates) {
                try {
                    Class<?> c = cl.loadClass(cn);
                    // 检查是否有接收 e9 类型参数的方法
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length >= 1) {
                            String pt0 = pts[0].getName();
                            if (pt0.equals("com.tencent.mm.storage.e9") || pt0.endsWith(".e9")) {
                                x9Cls = c;
                                LogWriter.log(TAG, "x9 class found via DexKit IEvent: " + cn);
                                break;
                            }
                        }
                    }
                    if (x9Cls != null) break;
                } catch (Throwable ignored) {}
            }
            // 2) 兜底：搜索包含 "EventBus" 字符串的类
            if (x9Cls == null) {
                List<String> busCandidates = DexKitHelper.findClassesByString(cl, "EventBus");
                for (String cn : busCandidates) {
                    try {
                        Class<?> c = cl.loadClass(cn);
                        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                            Class<?>[] pts = m.getParameterTypes();
                            if (pts.length >= 1) {
                                String pt0 = pts[0].getName();
                                if (pt0.equals("com.tencent.mm.storage.e9") || pt0.endsWith(".e9")) {
                                    x9Cls = c;
                                    LogWriter.log(TAG, "x9 class found via DexKit EventBus: " + cn);
                                    break;
                                }
                            }
                        }
                        if (x9Cls != null) break;
                    } catch (Throwable ignored) {}
                }
            }
            // 3) 兜底：搜索所有包含 "MsgInfo" 字符串的类（8.0.78 消息存储类特征）
            if (x9Cls == null) {
                List<String> msgCandidates = DexKitHelper.findClassesByString(cl, "MsgInfo");
                for (String cn : msgCandidates) {
                    try {
                        Class<?> c = cl.loadClass(cn);
                        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                            Class<?>[] pts = m.getParameterTypes();
                            if (pts.length >= 1) {
                                String pt0 = pts[0].getName();
                                if (pt0.endsWith(".e9") || pt0.contains("MsgInfo")) {
                                    x9Cls = c;
                                    LogWriter.log(TAG, "x9 class found via DexKit MsgInfo: " + cn);
                                    break;
                                }
                            }
                        }
                        if (x9Cls != null) break;
                    } catch (Throwable ignored) {}
                }
            }
            // 4) 兜底：搜索 "handleMsg" 或 "dispatch" 方法名
            if (x9Cls == null) {
                List<String> dispatchCandidates = DexKitHelper.findMethodsByString(cl, null, "dispatch");
                for (String sig : dispatchCandidates) {
                    try {
                        String cn = sig.substring(0, sig.indexOf('.'));
                        Class<?> c = cl.loadClass(cn);
                        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                            Class<?>[] pts = m.getParameterTypes();
                            if (pts.length >= 1) {
                                String pt0 = pts[0].getName();
                                if (pt0.endsWith(".e9") || pt0.contains("MsgInfo")) {
                                    x9Cls = c;
                                    LogWriter.log(TAG, "x9 class found via dispatch method: " + cn);
                                    break;
                                }
                            }
                        }
                        if (x9Cls != null) break;
                    } catch (Throwable ignored) {}
                }
            }
            // 5) 兜底：搜索 any class with method taking e9/MsgInfo param
            if (x9Cls == null) {
                List<String> e9Candidates = DexKitHelper.findClassesByString(cl, "storage");
                for (String cn : e9Candidates) {
                    try {
                        Class<?> c = cl.loadClass(cn);
                        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                            Class<?>[] pts = m.getParameterTypes();
                            if (pts.length >= 1) {
                                String pt0 = pts[0].getName();
                                if (pt0.endsWith(".e9") || pt0.contains("MsgInfo")) {
                                    x9Cls = c;
                                    LogWriter.log(TAG, "x9 class found via storage search: " + cn);
                                    break;
                                }
                            }
                        }
                        if (x9Cls != null) break;
                    } catch (Throwable ignored) {}
                }
            }
            if (x9Cls == null) {
                LogWriter.log(TAG, "x9 class ALL strategies failed");
                return;
            }
            Class<?> e9Cls = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Cls == null) {
                LogWriter.log(TAG, "e9 class not found");
                return;
            }

            int hooked = 0;
            for (java.lang.reflect.Method m : x9Cls.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length >= 1 && pts[0] == e9Cls) {
                    final int paramCount = pts.length;
                    final Class<?> returnType = m.getReturnType();
                    XposedBridge.hookMethod(m,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                try {
                                                                sConsumedTtsOriginal.set(Boolean.FALSE);
                                                                if (TtsVoiceSender.consumeBlockedOriginal(p.args[0])) {
                                                                    LogWriter.log(TAG, "consume blocked #tts original x9." + m.getName()
                                                                            + " return=" + returnType.getName());
                                                                    sConsumedTtsOriginal.set(Boolean.TRUE);
                                                                    p.setResult(defaultReturnValue(returnType));
                                                                    return;
                                                                }
                                                                if (TtsVoiceSender.shouldConsumeTtsFailureMessage(p.args[0])) {
                                                                    LogWriter.log(TAG, "consume #tts failure residue x9." + m.getName()
                                                                            + " return=" + returnType.getName());
                                                                    sConsumedTtsOriginal.set(Boolean.TRUE);
                                                                    p.setResult(defaultReturnValue(returnType));
                                                                }
                                } catch (Throwable e) {
                                    LogWriter.log("MessageHook", "cb err: " + e);
                                }
                            }

                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                try {
                                                                if (Boolean.TRUE.equals(sConsumedTtsOriginal.get())) {
                                                                    sConsumedTtsOriginal.remove();
                                                                    return;
                                                                }
                                                                sConsumedTtsOriginal.remove();
                                                                onX9Message(p.args[0], paramCount >= 2 ? p.args[1] : null);
                                } catch (Throwable e) {
                                    LogWriter.log("MessageHook", "cb err: " + e);
                                }
                            }
                        });
                    LogWriter.log(TAG, "hooked x9." + m.getName() + "(" + pts.length + ")");
                    hooked++;
                }
            }
            LogWriter.log(TAG, "x9 hooks installed: " + hooked + " methods");

        } catch (Throwable t) {
            LogWriter.log(TAG, "hookX9 FAIL: " + t.getMessage());
        }
    }

    static void onX9Message(Object e9, Object p0) {
        try {
            int rawType = (int) XposedHelpers.callMethod(e9, "getType");
            int type = mapType(rawType);
            String talker = (String) XposedHelpers.callMethod(e9, "N0");
            String content = null;
            try { content = (String) XposedHelpers.callMethod(e9, "I0"); } catch (Throwable ignored) {}
            if (content == null) try { content = (String) XposedHelpers.callMethod(e9, "j"); } catch (Throwable ignored) {}

            if (content != null && (content.startsWith("<msgsource")
                || content.startsWith("<pushcontent")))
                return;

            int isSend = -1;
            try { isSend = (Integer) XposedHelpers.callMethod(e9, "z0"); } catch (Throwable ignored) {}
            long msgId = 0;
            try { msgId = (Long) XposedHelpers.callMethod(e9, "H0"); } catch (Throwable ignored) {}

            sCount++;
            boolean isVoice = (rawType == 34 || rawType == 228);
            boolean isTts = content != null && content.startsWith("#tts");
            if (isVoice || isTts) {
                LogWriter.log(TAG, "#" + sCount
                    + " type=" + rawType + "->" + type
                    + " isSend=" + isSend + " msgId=" + msgId
                    + " talker=" + trunc(talker, 20)
                    + " content=" + trunc(content, 40));
            }

            // 去重: 同一个 msgId 只处理一次
            synchronized (sSeenMsgIds) {
                if (msgId != 0 && !sSeenMsgIds.add(msgId)) {
                    android.util.Log.e(TAG, "!!! SKIP duplicate msgId=" + msgId);
                    return;
                }
                // 防止内存膨胀: 超过 400 条时移除最旧条目，而非整体清空
                // （整体清空会导致同一消息的多重 hook 回调再次触发重复播报）
                if (sSeenMsgIds.size() > 400) {
                    Long oldest = sSeenMsgIds.iterator().next();
                    sSeenMsgIds.remove(oldest);
                }
            }


            // 诊断: 对红包/转账特征消息打印 rawType（用于校准当前微信版本的真实类型码）
            if (RedPacketHook.looksLikeMoneyMessage(content)) {
                LogWriter.log(TAG, "[RP-DIAG] rawType=" + rawType + " isSend=" + isSend
                    + " msgId=" + msgId + " talker=" + trunc(talker, 20)
                    + " content=" + trunc(content, 200));
            }

            // 红包/转账消息: 不依赖 isSend 判断（收到的红包/转账在入库时 field_isSend 可能为 1 导致误判）
            if (RedPacketHook.isRedPacketType(rawType) || RedPacketHook.isTransferType(rawType)) {
                LogWriter.log(TAG, "RP/TRANSFER x9: rawType=" + rawType + " isSend=" + isSend
                    + " msgId=" + msgId + " talker=" + trunc(talker, 20));
                final int rpRawType = rawType;
                final String rpTalker = talker;
                final String rpContent = content;
                final long rpMsgId = msgId;
                sMainHandler.post(() -> {
                    try {
                        RedPacketHook.onIncomingMessage(rpRawType, rpTalker, rpContent, rpMsgId);
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "RP notify err: " + e.getMessage());
                    }
                });
            }

            if (isSend != 1) {
                final int fType = type;
                final String fTalker = talker;
                final String fContent = content;
                sMainHandler.post(() -> {
                    try {
                        boolean blocked = processKeywordAndSensitive(fType, fTalker, fContent);
                        if (!blocked) {
                            TTSBroadcaster.handleMessageRaw(fType, fTalker, fContent);
                        }
                    } catch (Throwable e) {
                        LogWriter.log("TTS", "err: " + e.getMessage());
                    }
                });

                if (rawType == 34 || rawType == 228) {
                    final long voiceMsgId = msgId;
                    final String vTalker = talker;
                    if (!VoiceAutoPlay.shouldAutoPlay(vTalker)) {
                        android.util.Log.e(TAG, ">>> VOICE skipped (whitelist) talker=" + vTalker + " msgId=" + voiceMsgId);
                        LogWriter.log("VoiceAutoPlay", "skip whitelist talker=" + vTalker + " msgId=" + voiceMsgId);
                        return;
                    }
                    android.util.Log.e(TAG, ">>> VOICE msgId=" + voiceMsgId + " talker=" + vTalker + " isSend=" + isSend);
                    LogWriter.log("VoiceAutoPlay", "rawType=" + rawType + " msgId=" + voiceMsgId + " talker=" + vTalker);
                    sMainHandler.post(() -> {
                        try {
                            VoiceAutoPlay.onVoiceMsg(e9, voiceMsgId, p0);
                        } catch (Throwable e) {
                            LogWriter.log("VoiceAutoPlay", "msgHook err: " + e.getMessage());
                        }
                    });
                }
            }

        } catch (Throwable t) {
            LogWriter.log(TAG, "err: " + t);
        }
    }

    // ====== 关键词回复 + 敏感词过滤 (接收消息) ======

    private static boolean processKeywordAndSensitive(int type, String talker, String content) {
        if (content == null || content.isEmpty()) return false;
        if (content.startsWith("#tts")) return false;
        ModuleConfig cfg = ModuleConfig.load(ContextManager.getPrefs());
        if (cfg == null) return false;

        boolean blocked = false;
        if (cfg.sensitiveFilterEnabled && !cfg.sensitiveWords.isEmpty()) {
            for (String w : cfg.sensitiveWords) {
                if (w != null && !w.isEmpty() && content.contains(w)) {
                    LogWriter.log(TAG, "[Sensitive] 命中敏感词=" + w + " talker=" + trunc(talker, 20));
                    blocked = true;
                    break;
                }
            }
        }

        if (cfg.keywordReplyEnabled && !cfg.keywordRules.isEmpty()) {
            for (KeywordRule r : cfg.keywordRules) {
                if (r == null || r.keyword == null || r.reply == null) continue;
                boolean match = r.fuzzyMatch
                        ? content.contains(r.keyword)
                        : content.equals(r.keyword);
                if (match) {
                    LogWriter.log(TAG, "[KwReply] 命中=" + r.keyword + " -> " + r.reply);
                    GroupFeatures.sendTextMessage(sClassLoader, talker, r.reply);
                    break;
                }
            }
        }

        return blocked;
    }

    // ====== IEvent.e() 事件总线 (拦截自己发出的 #tts) ======

    private static void hookIEventBus(ClassLoader cl) {
        try {
            Class<?> iEventClz = cl.loadClass("com.tencent.mm.sdk.event.IEvent");
            Class<?> sendOkClz = cl.loadClass("com.tencent.mm.autogen.events.SendMsgSuccessEvent");

            java.lang.reflect.Method eMethod = iEventClz.getDeclaredMethod("e");
            XposedBridge.hookMethod(eMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!sendOkClz.isInstance(param.thisObject)) return;

                        Object data = XposedHelpers.getObjectField(param.thisObject, "g");
                        if (data == null) return;
                        Object e9 = XposedHelpers.getObjectField(data, "a");
                        if (e9 == null) return;

                        String content = (String) XposedHelpers.callMethod(e9, "j");
                        String talker = (String) XposedHelpers.callMethod(e9, "N0");
                        long msgId = (Long) XposedHelpers.callMethod(e9, "getMsgId");
                        int type = (Integer) XposedHelpers.callMethod(e9, "getType");

                        LogWriter.log(TAG, "IEvent.e: type=" + type + " talker=" + talker
                            + " msgId=" + msgId + " content=" + trunc(content, 30));
                        android.util.Log.e(TAG, ">>> IEvent.e SendMsgSuccess: type=" + type
                            + " talker=" + talker + " msgId=" + msgId
                            + " content=[" + (content == null ? "null" : content.substring(0, Math.min(content.length(), 60))) + "]");

                    } catch (Throwable t) {
                        android.util.Log.e(TAG, ">>> IEvent.e hook err: " + t.getMessage());
                    }
                }
            });
            LogWriter.log(TAG, "IEvent.e() hooked OK");
            android.util.Log.e(TAG, ">>> IEvent.e() hooked OK");

        } catch (Throwable t) {
            LogWriter.log(TAG, "hookIEventBus FAIL: " + t.getMessage());
            android.util.Log.e(TAG, ">>> IEvent.e() FAIL: " + t.getMessage());
        }
    }

    // ====== 工具方法 ======

    static String trunc(String s, int m) {
        return s == null ? "" : s.length() > m ? s.substring(0, m) + "..." : s;
    }

    static int mapType(int t) {
        if (t >= 268435456 || t == 74 || t == 83 || t == 84 || t == 87
            || t == 95 || t == 102 || t == 103 || t == 131 || t == 132
            || t == 1048625 || t == 16777265) return 49;
        return t;
    }

    private static Object defaultReturnValue(Class<?> returnType) {
        if (returnType == Void.TYPE) return null;
        if (returnType == Boolean.TYPE) return false;
        if (returnType == Byte.TYPE) return (byte) 0;
        if (returnType == Short.TYPE) return (short) 0;
        if (returnType == Integer.TYPE) return 0;
        if (returnType == Long.TYPE) return 0L;
        if (returnType == Float.TYPE) return 0f;
        if (returnType == Double.TYPE) return 0d;
        if (returnType == Character.TYPE) return (char) 0;
        return null;
    }
}
