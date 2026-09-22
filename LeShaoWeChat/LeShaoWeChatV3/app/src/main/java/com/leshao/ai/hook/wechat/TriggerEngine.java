package com.leshao.ai.hook.wechat;

import android.content.Context;
import android.util.Log;

import com.leshao.ai.config.AppConfig;
import com.leshao.ai.config.ConversationConfig;
import com.leshao.ai.hook.HookEntry;
import com.leshao.ai.util.Whitelist;
import com.leshao.v3.hook.TtsVoiceSender;

/**
 * AI 触发决策引擎（文档 §16.5，14 项功能的决策核心）。
 * <p>
 * 决策链（自上而下，任一不满足即静默返回）：
 * <ol>
 *   <li>总开关 / 群聊·私聊分开关</li>
 *   <li>群聊唤醒：@我（msgsource atuserlist，兜底 @机器人名/@所有人/@all）或关键词；
 *       {@code onlyWhenMentioned} 关时群聊全回</li>
 *   <li>白名单：名单非空时仅名单内会话响应（空名单 = 全回）</li>
 *   <li>分群记忆 + 知识库 top-k + 人设 → LLM（{@link AIBotCore#ask}）</li>
 *   <li>回复：零宽水印防循环 + 开 TTS 则合成语音消息发送, 关则 v51.r1 发文本</li>
 * </ol>
 * 本类只做轻量判定；LLM 调用与发送均在 AIBotCore 的工作线程完成，
 * 不阻塞 f9.Bb 所在的 DB 写锁（文档 §17.2）。
 */
public final class TriggerEngine {

    private static final String TAG = "LeshaoAI.Trigger";

    private static volatile Context appContext;

    private TriggerEngine() {
    }

    /** 由 WeChatHook 装配时注入微信 Application Context（TTS 用）。 */
    public static void setAppContext(Context ctx) {
        if (ctx != null) {
            appContext = ctx.getApplicationContext();
        }
    }

    /**
     * 处理一条收到的文本消息。
     *
     * @param talker    会话 id
     * @param rawContent 原始 content（群聊含 {@code <sender>:\n} 前缀）
     * @param msgInfo   MsgInfo 对象（@判定读 msgsource 用）
     * @param svrId     msgSvrId
     * @param cTime     createTime
     */
    public static void dispatch(String talker, String rawContent, Object msgInfo,
                                long svrId, long cTime) {
        try {
            AppConfig c = AIBotCore.config();
            if (c == null || !c.isEnabled()) {
                return;
            }

            // ① 群/私聊分开关 + 群内容拆分(会话级独立配置优先, 未设置则继承全局)
            boolean isGroup = GroupMsgParser.isGroupTalker(talker);
            ConversationConfig cc = AIBotCore.conversationConfig();
            ConversationConfig.Entry ov = cc != null ? cc.get(talker) : null;
            boolean autoReply = (ov != null && ov.autoReply != null)
                    ? ov.autoReply.booleanValue()
                    : (isGroup ? c.isAutoReplyInGroups() : c.isAutoReplyInPrivate());
            if (!autoReply) {
                return;
            }
            String[] sp = GroupMsgParser.splitGroupContent(rawContent, isGroup);
            final String sender = sp[0];
            String body = sp[1];
            if (body == null || body.trim().isEmpty()) {
                return;
            }

            // ② 群聊唤醒判定
            boolean atMe = false;
            if (isGroup) {
                atMe = GroupMsgParser.isAtMe(msgInfo, StorageHub.get().selfWxid(),
                        body, c.getBotName());
                boolean kwHit = GroupMsgParser.matchKeyword(body, c.getWakeKeywords());
                // 文档 §16.5：群聊默认不响应未唤醒消息(仅@/关键词模式同理)
                if (!atMe && !kwHit) {
                    return;
                }
            }

            // ③ 白名单: 非空时仅名单内会话自动回复; 名单外会话不主动回复,
            // 但群聊中被@时放行(可正常回复)。
            Whitelist wl = AIBotCore.whitelist();
            if (wl != null && !wl.isEmpty() && !wl.contains(talker)) {
                if (!(isGroup && atMe)) {
                    return;
                }
                Log.i(TAG, "白名单外会话被@, 放行: " + talker);
            }

            // ④⑤ 生成 + 回复
            final AppConfig cfg = c;
            final String incoming = body;
            final ClassLoader cl = HookEntry.appClassLoader;
            final ConversationConfig.Entry over = ov;
            final boolean tts = (ov != null && ov.ttsEnabled != null)
                    ? ov.ttsEnabled.booleanValue() : c.isTtsEnabled();
            Log.i(TAG, "触发 AI: talker=" + talker + " group=" + isGroup
                    + " sender=" + sender + " len=" + body.length());
            AIBotCore.ask(talker, incoming, "", over, new AIBotCore.ResultCallback() {
                @Override
                public void onResult(String reply) {
                    if (reply == null || reply.isEmpty()) {
                        return;
                    }
                    try {
                        if (tts) {
                            String cid = "ai-" + System.currentTimeMillis();
                            Log.i(TAG, "AI 回复走语音消息 talker=" + talker + " cid=" + cid);
                            TtsVoiceSender.sendAiReplyAsVoice(talker, reply, cid);
                        } else {
                            WeChatMessenger.sendText(talker, SendGuard.mark(reply), cl);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "AI 回复发送失败: " + t);
                        try {
                            WeChatMessenger.sendText(talker, SendGuard.mark(reply), cl);
                        } catch (Throwable t2) {
                            Log.w(TAG, "AI 文本回退也失败: " + t2);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "dispatch 异常: " + t);
        }
    }
}
