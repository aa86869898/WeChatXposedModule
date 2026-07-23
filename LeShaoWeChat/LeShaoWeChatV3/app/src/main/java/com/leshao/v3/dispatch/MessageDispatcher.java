package com.leshao.v3.dispatch;

import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.model.WeChatMessage;
import com.leshao.v3.service.AutoReplyManager;
import com.leshao.v3.service.DingDong;
import com.leshao.v3.service.GroupGuard;
import com.leshao.v3.service.StatsCollector;
import com.leshao.v3.service.TTSBroadcaster;
import com.leshao.v3.service.AIService;

public class MessageDispatcher {

    private static final String TAG = "MessageDispatcher";

    public static void dispatch(WeChatMessage msg, ModuleConfig cfg) {
        if (msg == null) return;
        if (!cfg.masterSwitch) {
            LogWriter.log(TAG, "masterSwitch OFF, drop msg type=" + msg.type + " from=" + msg.talker);
            return;
        }

        LogWriter.log(TAG, "dispatch msg type=" + msg.type + " from=" + msg.talker + " text="
            + (msg.content != null ? msg.content.substring(0, Math.min(30, msg.content.length())) : "null"));

        new Thread(() -> {
            try {
                Pipeline pipeline = buildPipeline(msg, cfg);
                pipeline.process(msg, cfg);
            } catch (Throwable t) {
                LogWriter.log(TAG, "dispatch FAILED: " + t.getMessage());
            }
        }, "leshao-dispatch").start();
    }

    private static Pipeline buildPipeline(WeChatMessage msg, ModuleConfig cfg) {
        Pipeline chain = new Pipeline("filter", (m, c) -> {
            if (containsSensitiveWord(m.content, c.sensitiveWords)) {
                LogWriter.log(TAG, "sensitive word blocked: " + m.content);
                return false;
            }
            return true;
        });

        chain.setNext(new Pipeline("keyword", (m, c) -> {
            if (AutoReplyManager.matchAndReply(m, c)) return false;
            return true;
        }));

        chain.setNext(new Pipeline("dingdong", (m, c) -> {
            DingDong.process(m, c);
            return true;
        }));

        chain.setNext(new Pipeline("ai", (m, c) -> {
            AIService.process(m, c);
            return true;
        }));

        chain.setNext(new Pipeline("broadcast", (m, c) -> {
            TTSBroadcaster.process(m, c);
            return true;
        }));

        chain.setNext(new Pipeline("stats", (m, c) -> {
            StatsCollector.record(m);
            return true;
        }));

        chain.setNext(new Pipeline("groupguard", (m, c) -> {
            GroupGuard.process(m, c);
            return true;
        }));

        return chain;
    }

    private static boolean containsSensitiveWord(String content, java.util.Set<String> words) {
        if (content == null || words == null || words.isEmpty()) return false;
        String lower = content.toLowerCase();
        for (String w : words) {
            if (lower.contains(w.toLowerCase())) return true;
        }
        return false;
    }
}
