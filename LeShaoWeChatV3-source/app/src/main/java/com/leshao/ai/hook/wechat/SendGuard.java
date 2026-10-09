package com.leshao.ai.hook.wechat;

/**
 * AI 发送防死循环守卫（文档 §6.3）。
 * <p>
 * v51.r1 的发送走 Kotlin 协程异步执行，ThreadLocal 标记无法覆盖
 * 「发送返回后 insert 才发生」的时间窗，因此采用零宽水印方案：
 * <ul>
 *   <li>AI 回复发送时在正文前嵌入 {@code \u200B\u200C}（零宽空格 + 非连接符）</li>
 *   <li>f9.Bb 收消息 hook 见到该前缀即判定为机器人自己所发，直接跳过</li>
 * </ul>
 * 水印零宽不可见，不影响接收方阅读。
 */
public final class SendGuard {

    /** 零宽空格 + 零宽非连接符。 */
    public static final String MARK = "\u200B\u200C";

    private SendGuard() {
    }

    /** 给待发送文本打水印。 */
    public static String mark(String text) {
        if (text == null) {
            return null;
        }
        if (text.startsWith(MARK)) {
            return text;
        }
        return MARK + text;
    }

    /** 是否是机器人自己所发（带水印）的消息。 */
    public static boolean isBotSent(String content) {
        return content != null && content.startsWith(MARK);
    }
}
