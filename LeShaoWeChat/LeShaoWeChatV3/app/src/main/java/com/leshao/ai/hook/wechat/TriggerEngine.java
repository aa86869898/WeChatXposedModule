package com.leshao.ai.hook.wechat;

import android.content.Context;
import android.util.Log;

import com.leshao.ai.config.AppConfig;
import com.leshao.ai.hook.HookEntry;
import com.leshao.ai.tts.TtsEngine;
import com.leshao.ai.util.Whitelist;

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
 *   <li>回复：零宽水印防循环 + v51.r1 发送 + 可选 TTS 播报</li>
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

            // ① 群/私聊分开关 + 群内容拆分
            boolean isGroup = GroupMsgParser.isGroupTalker(talker);
            if (isGroup && !c.isAutoReplyInGroups()) {
                return;
            }
            if (!isGroup && !c.isAutoReplyInPrivate()) {
                return;
            }
            String[] sp = GroupMsgParser.splitGroupContent(rawContent, isGroup);
            final String sender = sp[0];
            String body = sp[1];
            if (body == null || body.trim().isEmpty()) {
                return;
            }

            // ② 群聊唤醒判定
            if (isGroup) {
                boolean atMe = GroupMsgParser.isAtMe(msgInfo, StorageHub.get().selfWxid(),
                        body, c.getBotName());
                boolean kwHit = GroupMsgParser.matchKeyword(body, c.getWakeKeywords());
                if (c.isOnlyWhenMentioned()) {
                    // 仅 @/关键词 模式：未唤醒不响应
                    if (!atMe && !kwHit) {
                        return;
                    }
                } else if (!atMe && !kwHit) {
                    // 文档 §16.5：群聊默认不响应未唤醒消息
                    return;
                }
            }

            // ③ 白名单（非空时仅名单内会话）
            Whitelist wl = AIBotCore.whitelist();
            if (wl != null && !wl.isEmpty() && !wl.contains(talker)) {
                return;
            }

            // ④⑤ 生成 + 回复
            final AppConfig cfg = c;
            final String incoming = body;
            final ClassLoader cl = HookEntry.appClassLoader;
            Log.i(TAG, "触发 AI: talker=" + talker + " group=" + isGroup
                    + " sender=" + sender + " len=" + body.length());
            AIBotCore.ask(talker, incoming, "", new AIBotCore.ResultCallback() {
                @Override
                public void onResult(String reply) {
                    if (reply == null || reply.isEmpty()) {
                        return;
                    }
                    try {
                        WeChatMessenger.sendText(talker, SendGuard.mark(reply), cl);
                    } catch (Throwable t) {
                        Log.w(TAG, "AI 回复发送失败: " + t);
                    }
                    try {
                        if (cfg.isTtsEnabled()) {
                            Context ctx = appContext;
                            if (ctx != null) {
                                TtsEngine.get(ctx).speak(reply);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "dispatch 异常: " + t);
        }
    }
}
