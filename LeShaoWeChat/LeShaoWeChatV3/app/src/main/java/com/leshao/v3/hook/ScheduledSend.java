package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;

/**
 * [功能6] 消息定时发送
 * ===================
 *
 * 微信消息发送链路:
 *
 * ChatFooterCustom (com.tencent.mm.ui.chatting.ChatFooterCustom):
 *   onClick(View) → void                         发送按钮点击入口
 *   r(String, String, String, String, int, int, int, String, String, String) → void
 *     完整发送方法(10个参数) — 最终调用此方法发送
 *
 * ChatFooter (com.tencent.mm.pluginsdk.ui.chat.ChatFooter):
 *   F(e9 msgInfo, a35.g callback) → boolean      核心发送
 *     参数1: storage.e9 — 消息对象
 *     参数2: a35.g — 发送回调接口
 *     源码逻辑:
 *       this.L.setTag(new ba(e9Var, gVar))       // 缓存消息+回调
 *       y1(true, false)                           // 触发发送
 *
 * 定时发送实现:
 *   Hook ChatFooter.F() → beforeHookedMethod
 *   → 检查是否设置了定时
 *   → 延迟调用原方法
 */
public class ScheduledSend {

    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    // 预设的延迟毫秒数 (0=立即发送)
    public static long delayMs = 0;

    public static void hook(ClassLoader cl) {
        Logger.i("--- [6] 定时发送 ---");

        // Hook ChatFooter.F() — 核心发送方法
        hookCoreSend(cl);

        // Hook ChatFooterCustom.r() — 完整发送
        hookFullSend(cl);

        // Hook ChatFooterCustom.onClick() — 发送按钮
        hookSendClick(cl);
    }

    /**
     * Hook ChatFooter.F(e9 msgInfo, a35.g callback) → boolean
     *
     * 这是微信发送消息的核心方法。
     * 在beforeHookedMethod中，如果delayMs>0，延迟执行。
     */
    private static void hookCoreSend(ClassLoader cl) {
        try {
            Class<?> chatFooter = XposedHelpers.findClass(
                    "com.tencent.mm.pluginsdk.ui.chat.ChatFooter", cl);

            XposedBridge.hookAllMethods(chatFooter, "F", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 1 && param.args[0] != null) {
                        try {
                            Object msgInfo = param.args[0];
                            String content = (String) XposedHelpers.getObjectField(
                                    msgInfo, "field_content");
                            Logger.i("[定时发送] ChatFooter.F() 准备发送: "
                                    + (content != null ? content.substring(0,
                                            Math.min(30, content.length())) : "null"));

                            if (delayMs > 0) {
                                // 定时发送: 延迟后执行
                                Logger.i("[定时发送] ⏰ 延迟 " + delayMs + "ms 发送");
                                param.setResult(false); // 先阻止本次发送
                                new android.os.Handler(android.os.Looper.getMainLooper())
                                        .postDelayed(() -> {
                                            try {
                                                // 重新调用原方法
                                                XposedHelpers.callMethod(param.thisObject,
                                                        "F", param.args);
                                                Logger.i("[定时发送] ✅ 定时消息已发送!");
                                            } catch (Throwable t) {
                                                Logger.e("[定时发送] 重发失败: " + t.getMessage());
                                            }
                                        }, delayMs);
                            }
                        } catch (Throwable t) {
                            Logger.e("[定时发送] 异常: " + t.getMessage());
                        }
                    }
                }
            });
            Logger.i("  ChatFooter.F(e9,a35.g)→boolean ✓");
        } catch (Throwable t) {
            Logger.w("  ChatFooter.F: " + t.getMessage());
        }
    }

    /**
     * Hook ChatFooterCustom.r() → void
     *
     * 10个参数的完整发送方法。
     */
    private static void hookFullSend(ClassLoader cl) {
        try {
            Class<?> footerCustom = XposedHelpers.findClass(
                    "com.tencent.mm.ui.chatting.ChatFooterCustom", cl);

            XposedBridge.hookAllMethods(footerCustom, "r", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Logger.i("[定时发送] ChatFooterCustom.r() 完整发送, "
                            + param.args.length + "个参数");
                    // 10个参数: 前4个String, 3个int, 3个String
                }
            });
            Logger.i("  ChatFooterCustom.r(10 params) ✓");
        } catch (Throwable t) {
            Logger.w("  ChatFooterCustom.r: " + t.getMessage());
        }
    }

    /**
     * Hook ChatFooterCustom.onClick(View) — 发送按钮
     */
    private static void hookSendClick(ClassLoader cl) {
        try {
            Class<?> footerCustom = XposedHelpers.findClass(
                    "com.tencent.mm.ui.chatting.ChatFooterCustom", cl);

            XposedBridge.hookAllMethods(footerCustom, "onClick",
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Logger.i("[定时发送] 发送按钮被点击");
                }
            });
            Logger.i("  ChatFooterCustom.onClick(View) ✓");
        } catch (Throwable ignored) {}
    }
}
