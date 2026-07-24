package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * [功能4] 对方正在输入提示 — 生产级完整实现
 * =========================================
 * 
 * ReceiveTypingEvent:
 *   data.talker → String   会话ID
 *   data.status → int      1=开始输入 0=停止
 * 
 * 功能:
 *   1. 监听输入事件 → 标题栏追加"(正在输入...)"
 *   2. 记录最后输入时间
 *   3. 支持Toast浮动提示
 *   4. 去重(同一talker短时间内不重复提示)
 */
public class TypingIndicator {

    private static final long TYPING_TIMEOUT = 10000;
    private static final long MIN_INTERVAL = 3000;

    private static String currentTypingTalker = "";
    private static long lastTypingTime = 0;
    private static boolean isTyping = false;
    private static Handler mainHandler = new Handler(Looper.getMainLooper());
    private static Runnable typingTimeoutRunnable;
    private static ClassLoader sClassLoader;

    private static boolean showInTitle = true;
    private static boolean showToast = false;
    private static boolean showInNotification = false;
    private static volatile boolean sEnabled = true;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        sClassLoader = cl;
        showToast = HookConfig.getInt("typing_show_toast", 0) == 1;

        hookTypingEvent(cl);
        hookTitleUpdate(cl);
    }

    private static void hookTypingEvent(ClassLoader cl) {
        try {
            Class<?> typingEvent = XposedHelpers.findClass(
                    "com.tencent.mm.autogen.events.ReceiveTypingEvent", cl);

            XposedBridge.hookAllMethods(typingEvent, "callback", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object event = param.args[0];
                        Object data = XposedHelpers.getObjectField(event, "data");
                        if (data == null) return;

                        String talker = (String) XposedHelpers.getObjectField(data, "talker");
                        int status = XposedHelpers.getIntField(data, "status");

                        long now = System.currentTimeMillis();

                        if (status == 1) {
                            if (talker != null && !talker.equals(currentTypingTalker)
                                    && (now - lastTypingTime > MIN_INTERVAL)) {
                                currentTypingTalker = talker;
                                isTyping = true;
                                lastTypingTime = now;

                                XposedBridge.log("[Typing] " + talker + " 正在输入...");

                                if (showToast) {
                                    showTypingToast(talker);
                                }

                                scheduleTypingTimeout();
                            }
                        } else {
                            stopTyping();
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[Typing] ReceiveTypingEvent Hook完成");
        } catch (Throwable t) {
            XposedBridge.log("[Typing] Hook失败: " + t.getMessage());
        }
    }

    private static void hookTitleUpdate(ClassLoader cl) {
        if (!showInTitle) return;
        try {
            Class<?> chattingUI = XposedHelpers.findClass(
                    "com.tencent.mm.ui.chatting.ChattingUIFragment", cl);

            XposedBridge.hookAllMethods(chattingUI, "setMMTitle", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isTyping) return;
                    String title = (String) param.args[0];
                    if (title != null && !title.contains("正在输入")) {
                        param.args[0] = title + " (正在输入...)";
                    }
                }
            });
            XposedBridge.log("[Typing] setMMTitle Hook完成");
        } catch (Throwable t) {}
    }

    private static void showTypingToast(final String talker) {
        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    android.content.Context act = com.leshao.v3.ContextManager.getAppContext();
                    if (act != null) {
                        String name = talker;
                        try {
                            Object contact = XposedHelpers.callStaticMethod(
                                    XposedHelpers.findClass("d9", sClassLoader), "b");
                            Object convStorage = XposedHelpers.callMethod(contact, "r");
                            Object y3 = XposedHelpers.callMethod(convStorage, "p", talker);
                            if (y3 != null) {
                                String nickname = (String) XposedHelpers.getObjectField(
                                        y3, "field_nickname");
                                if (nickname != null && !nickname.isEmpty()) name = nickname;
                            }
                        } catch (Throwable ignored) {}
                        if (act instanceof android.app.Activity) {
                            Toast.makeText((android.app.Activity) act, name + " 正在输入...",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private static void scheduleTypingTimeout() {
        if (typingTimeoutRunnable != null) {
            mainHandler.removeCallbacks(typingTimeoutRunnable);
        }
        typingTimeoutRunnable = new Runnable() {
            public void run() {
                stopTyping();
            }
        };
        mainHandler.postDelayed(typingTimeoutRunnable, TYPING_TIMEOUT);
    }

    private static void stopTyping() {
        isTyping = false;
        currentTypingTalker = "";
    }
}
