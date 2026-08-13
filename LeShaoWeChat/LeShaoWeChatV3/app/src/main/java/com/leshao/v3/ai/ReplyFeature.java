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
        String curNow = ChatHooks.currentTalker();
        if (!talker.equals(curNow)) {
            LogWriter.log(TAG, "onSessionOpened: 已切走 talker=" + talker + " cur=" + curNow + "，取消");
            return;
        }
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

        final StringBuilder buf = new StringBuilder();
        final List<String> replies = new ArrayList<>();
        final int max = AiConfig.replyCount();

        AiClient.chatStream(prompt, req, new AiClient.StreamCallback() {
            @Override public void onDelta(String delta) {
                buf.append(delta);
                for (String line : extractCompleteLines(buf)) {
                    String cleaned = cleanLine(line);
                    if (!cleaned.isEmpty() && replies.size() < max) replies.add(cleaned);
                }
                if (!replies.isEmpty()) {
                    List<String> snap = new ArrayList<>(replies);
                    ChatHooks.MAIN.post(() -> showOrUpdate(talker, snap));
                }
            }
            @Override public void onDone(String fullText) {
                String remain = buf.toString().trim();
                if (!remain.isEmpty() && replies.size() < max) {
                    String cleaned = cleanLine(remain);
                    if (!cleaned.isEmpty()) replies.add(cleaned);
                }
                if (replies.isEmpty()) {
                    for (String l : parseReplies(fullText, max)) {
                        if (replies.size() >= max) break;
                        replies.add(l);
                    }
                }
                LogWriter.log(TAG, "onDone: 共 " + replies.size() + " 条回复");
                if (!replies.isEmpty()) {
                    List<String> snap = new ArrayList<>(replies);
                    ChatHooks.MAIN.post(() -> showOrUpdate(talker, snap));
                }
            }
            @Override public void onError(String msg) {
                LogWriter.log(TAG, "推荐回复失败: " + msg);
            }
        });
    }

    private static void showOrUpdate(String talker, List<String> replies) {
        String curNow = ChatHooks.currentTalker();
        if (!talker.equals(curNow)) {
            LogWriter.log(TAG, "showOrUpdate: 会话已切换 talker=" + talker + " cur=" + curNow + "，丢弃");
            return;
        }
        if (!ChatHooks.isChatWindowOpen()) { LogWriter.log(TAG, "showOrUpdate: 窗口已关闭，丢弃"); return; }
        Activity act = ChatHooks.currentActivity();
        if (act == null) { LogWriter.log(TAG, "showOrUpdate: 无 Activity，丢弃"); return; }

        ReplyBanner.OnPick pick = chosen -> {
            if (AiConfig.replyMode() == 1) {
                ChatHooks.fillAndSend(act, chosen);
            } else {
                ChatHooks.fillInput(act, chosen);
            }
            ChatMemory.append(talker, new MessageReader.ChatMsg("me", "", chosen, System.currentTimeMillis()));
        };
        if (ReplyBanner.isShowing()) ReplyBanner.update(replies);
        else ReplyBanner.show(act, replies, pick);
    }

    private static List<String> extractCompleteLines(StringBuilder buf) {
        List<String> out = new ArrayList<>();
        String s = buf.toString();
        int idx;
        while ((idx = s.indexOf('\n')) >= 0) {
            out.add(s.substring(0, idx));
            s = s.substring(idx + 1);
        }
        buf.setLength(0);
        buf.append(s);
        return out;
    }

    private static String cleanLine(String line) {
        line = line.trim();
        line = line.replaceFirst("^[0-9]+[.、)）]\\s*", "");
        return line.trim();
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
