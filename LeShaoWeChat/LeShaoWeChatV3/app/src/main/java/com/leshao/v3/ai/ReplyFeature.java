package com.leshao.v3.ai;

import android.app.Activity;

import java.util.ArrayList;
import java.util.List;

import com.leshao.v3.LogWriter;

public class ReplyFeature {
    private static final String TAG = "ReplyFeature";
    private static final java.util.Map<String, String> lastKeyMap = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long RECENT_WINDOW_MS = 1_800_000L;

    public static void onIncoming(String talker, String content, ClassLoader cl) {
        if (content == null || content.isEmpty()) return;
        String cur = ChatHooks.currentTalker();
        boolean curMatches = (cur != null && !cur.isEmpty() && cur.equals(talker));
        boolean windowOpen = ChatHooks.isChatWindowOpen();
        if (!windowOpen && !curMatches) {
            LogWriter.log(TAG, "onIncoming: 窗口未打开且非当前会话，跳过 talker=" + talker + " cur=" + cur);
            return;
        }
        if (cur != null && !cur.isEmpty() && !curMatches) {
            LogWriter.log(TAG, "onIncoming: 非当前会话，跳过 talker=" + talker + " cur=" + cur);
            return;
        }
        doGenerate(talker, content, cl);
    }

    public static void onSessionOpened(String talker, ClassLoader cl) {
        if (!AiConfig.masterEnabled() || !AiConfig.replyEnabled()) return;
        if (talker == null || talker.isEmpty()) return;
        List<MessageReader.ChatMsg> mem = ChatMemory.get(talker);
        MessageReader.ChatMsg lastOther = null;
        for (int i = mem.size() - 1; i >= 0; i--) {
            MessageReader.ChatMsg m = mem.get(i);
            if ("other".equals(m.role)) { lastOther = m; break; }
        }
        if (lastOther == null || lastOther.content == null || lastOther.content.isEmpty()) {
            LogWriter.log(TAG, "onSessionOpened: 无最近对方消息 talker=" + talker);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastOther.time > RECENT_WINDOW_MS) {
            LogWriter.log(TAG, "onSessionOpened: 最近消息超时 " + (now - lastOther.time) + "ms，跳过 talker=" + talker);
            return;
        }
        LogWriter.log(TAG, "onSessionOpened: 基于最近消息生成推荐回复 talker=" + talker);
        doGenerate(talker, lastOther.content, cl);
    }

    private static void doGenerate(String talker, String content, ClassLoader cl) {
        String key = talker + "|" + content.hashCode();
        if (key.equals(lastKeyMap.get(talker))) return;
        lastKeyMap.put(talker, key);
        LogWriter.log(TAG, "doGenerate: 处理 talker=" + talker + " content=" + content);

        List<MessageReader.ChatMsg> mem = ChatMemory.get(talker);
        StringBuilder ctx = new StringBuilder();
        for (MessageReader.ChatMsg m : mem) {
            String who = m.role.equals("me") ? "我" : "对方";
            ctx.append(who).append("：").append(m.content).append('\n');
        }

        String prompt = String.format(AiConfig.promptReply(), AiConfig.replyCount());
        List<AiClient.ChatMessage> req = new ArrayList<>();
        req.add(new AiClient.ChatMessage("user", ctx.toString()));

        AiClient.chatAsync(prompt, req, new AiClient.Callback() {
            @Override public void onResult(String text) {
                List<String> replies = parseReplies(text, AiConfig.replyCount());
                LogWriter.log(TAG, "onResult: 收到 " + replies.size() + " 条回复");
                ChatHooks.MAIN.post(() -> {
                    String curNow = ChatHooks.currentTalker();
                    if (!talker.equals(curNow)) {
                        LogWriter.log(TAG, "onResult: 会话已切换 talker=" + talker + " cur=" + curNow + "，丢弃");
                        return;
                    }
                    if (!ChatHooks.isChatWindowOpen()) { LogWriter.log(TAG, "onResult: 窗口已关闭，丢弃"); return; }
                    Activity act = ChatHooks.currentActivity();
                    if (act == null) { LogWriter.log(TAG, "onResult: 无 Activity，丢弃"); return; }
                    ReplyBanner.show(act, replies, chosen -> {
                        if (AiConfig.replyMode() == 1) {
                            ChatHooks.fillAndSend(act, chosen);
                        } else {
                            ChatHooks.fillInput(act, chosen);
                        }
                        ChatMemory.append(talker, new MessageReader.ChatMsg("me", "", chosen, System.currentTimeMillis()));
                    });
                });
            }
            @Override public void onError(String msg) {
                LogWriter.log(TAG, "推荐回复失败: " + msg);
            }
        });
    }

    private static List<String> parseReplies(String text, int max) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\n")) {
            line = line.trim();
            line = line.replaceFirst("^[0-9]+[.、)）]\\s*", "");
            if (!line.isEmpty()) out.add(line);
            if (out.size() >= max) break;
        }
        return out;
    }
}
