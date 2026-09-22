package com.leshao.ai.hook.wechat;

import android.util.Log;

import com.leshao.ai.hook.dexkit.DexKitAdapter;

import de.robv.android.xposed.XposedHelpers;

/**
 * 微信文本消息发送器（文档 §5 线路二）。
 * <p>
 * 唯一发送通道为 {@code v51.r1}（SendMsgCgiFactory.Builder）：
 * <pre>
 *   r1 req = new r1();
 *   req.l = p1.d;      // ★ 必须：消息类型枚举 TEXT，否则 b()/c() 空转
 *   req.h(talker);     // 目标会话
 *   req.e(content);    // 正文
 *   req.i(1);          // scene/type
 *   req.b();           // 异步执行（内部协程）
 * </pre>
 * 类名由 {@link DexKitAdapter} 按字符串锚点动态定位（{@code MicroMsg.SendMsgCgiFactory}
 * + {@code executeByPPC() called with: content size = }），跨版本自适应。
 * <p>
 * 群聊 @：v51.p1 枚举无 VOICE 同理亦无 @ 语义，k7 附加参数字段版本差异大，
 * 按文档 §5.4 兜底方案在正文前直接写 {@code @显示名}。
 * <p>
 * b() 为协程异步执行，返回即表示「已发起」；最终落库会经过 f9.Bb，
 * 由 {@link MsgReceiveHook} 观察水印验证闭环。
 */
public final class WeChatMessenger {

    private static final String TAG = "LeshaoAI.Messenger";

    /** p1 枚举中 TEXT 常量的字段名（文档 §5.2 实证：d = TEXT）。 */
    private static final String FIELD_TYPE_TEXT = "d";

    private WeChatMessenger() {
    }

    /**
     * 向指定会话发送文本消息。
     *
     * @param talker  会话 id（群/联系人）
     * @param content 正文（调用方负责已打防循环水印）
     * @param cl      微信 classLoader（仅为兼容旧签名，实际用 HookEntry.appClassLoader）
     * @return 是否成功发起发送
     */
    public static boolean sendText(String talker, String content, ClassLoader cl) {
        if (talker == null || talker.isEmpty() || content == null || content.isEmpty()) {
            return false;
        }
        try {
            Class<?> r1 = DexKitAdapter.findSendFactoryClass();
            Class<?> p1 = DexKitAdapter.findSendTypeEnumClass();
            if (r1 == null || p1 == null) {
                Log.w(TAG, "发送失败: 发送类未定位 (r1=" + r1 + ", p1=" + p1 + ")");
                return false;
            }
            Object textType;
            try {
                textType = XposedHelpers.getStaticObjectField(p1, FIELD_TYPE_TEXT);
            } catch (Throwable t) {
                Log.w(TAG, "p1.d(TEXT) 读取失败: " + t);
                return false;
            }
            if (textType == null) {
                Log.w(TAG, "发送失败: p1.d(TEXT) 为 null");
                return false;
            }

            Object req = XposedHelpers.newInstance(r1);
            // ★ 类型枚举必须设置，否则 b()/c() 空转
            XposedHelpers.setObjectField(req, "l", textType);
            XposedHelpers.callMethod(req, "h", talker);  // talker
            XposedHelpers.callMethod(req, "e", content); // content
            XposedHelpers.callMethod(req, "i", 1);       // scene/type
            XposedHelpers.callMethod(req, "b");          // 异步执行

            Log.i(TAG, "已发起发送 -> " + talker + " len=" + content.length());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "sendText 失败: " + t);
            return false;
        }
    }

    /**
     * 群聊 @ 发送（文档 §5.4 兜底：正文前直接写 @显示名）。
     *
     * @param talker    群 id
     * @param content   正文
     * @param atDisplay 被 @ 人的显示名（备注/昵称）
     * @param cl        微信 classLoader（兼容旧签名）
     */
    public static boolean sendTextWithAt(String talker, String content, String atDisplay,
                                         ClassLoader cl) {
        if (atDisplay == null || atDisplay.isEmpty()) {
            return sendText(talker, content, cl);
        }
        return sendText(talker, "@" + atDisplay + " " + content, cl);
    }
}
