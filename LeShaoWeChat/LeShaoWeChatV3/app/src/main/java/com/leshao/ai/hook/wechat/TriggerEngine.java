package com.leshao.ai.hook.wechat;

import android.content.Context;
import android.util.Log;

import com.leshao.ai.config.AppConfig;
import com.leshao.ai.config.ConversationConfig;
import com.leshao.ai.hook.HookEntry;
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.TtsVoiceSender;

/**
 * AI 触发决策引擎（文档 §16.5，14 项功能的决策核心）。
 * <p>
 * 决策链（自上而下，任一不满足即静默返回）：
 * <ol>
 *   <li>AI 总开关</li>
 *   <li>会话级个性化配置：仅「已配置且启用」的群聊/联系人触发（v996 替代旧白名单与全局类型开关）</li>
 *   <li>群聊唤醒：@我（msgsource atuserlist，兜底 @机器人名/@所有人/@all）或关键词；
 *       {@code onlyWhenMentioned} 关时群聊全回</li>
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
            LogWriter.log(TAG, "dispatch: talker=" + talker
                    + " len=" + (rawContent == null ? -1 : rawContent.length())
                    + " svrId=" + svrId);
            AppConfig c = AIBotCore.config();
            if (c == null) {
                LogWriter.log(TAG, "跳过: AppConfig 未初始化");
                return;
            }
            if (!c.isEnabled()) {
                LogWriter.log(TAG, "跳过: AI 总开关关闭");
                return;
            }

            // ① 会话级个性化配置门控(v996): 仅「已配置且启用」的群聊/联系人触发 AI,
            //    未配置的会话一律不触发任意 AI 功能(替代旧白名单与全局类型开关)。
            boolean isGroup = GroupMsgParser.isGroupTalker(talker);
            ConversationConfig cc = AIBotCore.conversationConfig();
            ConversationConfig.Entry ov = cc != null ? cc.get(talker) : null;
            if (ov == null || !ov.isActive()) {
                LogWriter.log(TAG, "跳过: 未配置/未启用个性化配置 talker=" + talker
                        + " group=" + isGroup + " 已配置=" + (ov != null));
                return;
            }

            String[] sp = GroupMsgParser.splitGroupContent(rawContent, isGroup);
            final String sender = sp[0];
            String body = sp[1];
            if (body == null || body.trim().isEmpty()) {
                LogWriter.log(TAG, "跳过: 正文为空 talker=" + talker);
                return;
            }

            // ③ 群聊唤醒判定
            boolean atMe = false;
            if (isGroup) {
                String selfWxid = StorageHub.get().selfWxid();
                String selfNick = null;
                try {
                    selfNick = StorageHub.get().selfNickname();
                } catch (Throwable ignored) {
                }
                atMe = GroupMsgParser.isAtMe(msgInfo, selfWxid, body, c.getBotName(), selfNick);
                boolean kwHit = GroupMsgParser.matchKeyword(body, c.getWakeKeywords());
                // v985: 只有开启「仅被@时回复」才限制为 @/关键词; 关闭时群聊全回(文档 §16.5)。
                boolean onlyMentioned = (ov != null && ov.onlyWhenMentioned != null)
                        ? ov.onlyWhenMentioned.booleanValue() : c.isOnlyWhenMentioned();
                if (onlyMentioned && !atMe && !kwHit) {
                    LogWriter.log(TAG, "跳过: 群消息未唤醒(仅@模式) talker=" + talker
                            + " atMe=false kwHit=false body='" + trunc(body) + "'");
                    return;
                }
            }

            // ④⑤ 生成 + 回复
            final AppConfig cfg = c;
            final String incoming = body;
            final ClassLoader cl = HookEntry.appClassLoader;
            final ConversationConfig.Entry over = ov;
            final boolean tts = (ov != null && ov.ttsEnabled != null)
                    ? ov.ttsEnabled.booleanValue() : c.isTtsEnabled();
            // v985: 会话/模板多音色: 开启随机时每条从音色列表随机取一个, 否则用列表首个。
            String voiceOverride = null;
            if (ov != null && ov.voices != null && !ov.voices.isEmpty()) {
                boolean random = ov.randomVoice != null && ov.randomVoice.booleanValue();
                if (random && ov.voices.size() > 1) {
                    voiceOverride = ov.voices.get(
                            new java.util.Random().nextInt(ov.voices.size()));
                } else {
                    voiceOverride = ov.voices.get(0);
                }
            }
            final String voiceFinal = voiceOverride;
            LogWriter.log(TAG, "触发 AI: talker=" + talker + " group=" + isGroup
                    + " sender=" + sender + " len=" + body.length() + " atMe=" + atMe
                    + " tts=" + tts + " voice=" + voiceOverride);
            AIBotCore.ask(talker, incoming, "", over, new AIBotCore.ResultCallback() {
                @Override
                public void onResult(String reply) {
                    if (reply == null || reply.isEmpty()) {
                        LogWriter.log(TAG, "AI 空回复, 不发送 talker=" + talker);
                        return;
                    }
                    try {
                        if (tts) {
                            String cid = "ai-" + System.currentTimeMillis();
                            LogWriter.log(TAG, "AI 回复走语音消息 talker=" + talker
                                    + " cid=" + cid + " voice=" + voiceFinal);
                            // v985: 语音合成/发送失败或超时时自动回退发文本, 避免"AI 没回复"。
                            TtsVoiceSender.sendAiReplyAsVoice(talker, reply, cid, voiceFinal, () -> {
                                try {
                                    WeChatMessenger.sendText(talker, SendGuard.mark(reply), cl);
                                } catch (Throwable t2) {
                                    LogWriter.log(TAG, "AI 文本回退失败: " + t2);
                                }
                            });
                        } else {
                            LogWriter.log(TAG, "AI 回复走文本 talker=" + talker);
                            WeChatMessenger.sendText(talker, SendGuard.mark(reply), cl);
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "AI 回复发送失败: " + t);
                        try {
                            WeChatMessenger.sendText(talker, SendGuard.mark(reply), cl);
                        } catch (Throwable t2) {
                            LogWriter.log(TAG, "AI 文本回退也失败: " + t2);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "dispatch 异常: " + t);
        }
    }

    /** 日志用截断, 避免超长正文刷屏。 */
    private static String trunc(String s) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').trim();
        return one.length() > 60 ? one.substring(0, 60) + "..." : one;
    }
}
