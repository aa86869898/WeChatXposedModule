package com.leshao.v3.service;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.KeywordRule;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.model.WeChatMessage;

import java.util.List;

import de.robv.android.xposed.XposedHelpers;

public class AutoReplyManager {

    private static final String TAG = "AutoReplyManager";

    public static boolean matchAndReply(WeChatMessage msg, ModuleConfig cfg) {
        if (msg == null || cfg == null) return false;
        if (msg.type != WeChatMessage.TYPE_TEXT) return false;
        if (cfg.keywordRules == null || cfg.keywordRules.isEmpty()) return false;

        String content = msg.content != null ? msg.content.toLowerCase() : "";
        if (content.isEmpty()) return false;

        for (KeywordRule rule : cfg.keywordRules) {
            if (rule.keyword == null || rule.reply == null) continue;

            boolean matched = rule.fuzzyMatch
                ? content.contains(rule.keyword.toLowerCase())
                : content.equals(rule.keyword.toLowerCase());

            if (matched) {
                sendReply(msg.talker, rule.reply);
                LogWriter.log(TAG, "keyword matched: " + rule.keyword + " -> " + rule.reply);
                return true;
            }
        }
        return false;
    }

    private static void sendReply(String talker, String text) {
        try {
            ClassLoader cl = ContextManager.getClassLoader();
            Class<?> msgClass = cl.loadClass("com.tencent.mm.modelmulti.n");
            Object msg = XposedHelpers.newInstance(msgClass, talker, text, 1);
            XposedHelpers.callStaticMethod(msgClass, "b", msg);
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendReply FAILED: " + t.getMessage());
        }
    }
}
