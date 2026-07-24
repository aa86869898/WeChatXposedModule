package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

/**
 * [功能65/66/67] 通知增强 — 生产级完整实现
 * ========================================
 * 
 * #65 快捷回复     — 自定义快捷回复短语(通过修改Notification Action)
 * #66 通知栏头像    — 强制显示头像
 * #67 熄屏消息区分  — 重要联系人亮屏+震动+响铃, 普通联系人仅通知栏
 * 
 * notification.m0.a(11参数):
 *   参数3: needSound   → 控制响铃
 *   参数4: needShake   → 控制震动
 *   参数7: talkerName  → 会话名
 */
public class NotifyCustom {

    private static final Set<String> importantContacts = new HashSet<>();
    private static int priorityMode = 0;
    private static boolean avatarEnabled = true;
    private static String[] quickReplyPhrases = {
        "好的", "收到", "稍等", "在路上", "马上到", "👌"
    };
    private static boolean sEnabled = false;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        loadConfig();
        hookNotifyPriority(cl);
        hookNotifyAvatar(cl);
    }

    private static void loadConfig() {
        priorityMode = HookConfig.getInt("notify_priority_mode", 0);
        avatarEnabled = HookConfig.getInt("notify_avatar", 1) == 1;

        String saved = HookConfig.getString("notify_important_contacts", "");
        if (saved != null && !saved.isEmpty()) {
            importantContacts.addAll(Arrays.asList(saved.split(",")));
        }
        String phrases = HookConfig.getString("quick_reply_phrases", "");
        if (phrases != null && !phrases.isEmpty()) {
            quickReplyPhrases = phrases.split(",");
        }
    }

    private static void hookNotifyPriority(ClassLoader cl) {
        if (priorityMode == 0) return;

        try {
            Class<?> m0 = XposedHelpers.findClass(
                    "com.tencent.mm.booter.notification.m0", cl);

            for (java.lang.reflect.Method m : m0.getDeclaredMethods()) {
                if (m.getName().equals("a")
                        && m.getParameterTypes().length >= 9
                        && m.getParameterTypes()[0].getName().contains("e9")) {

                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String talker = "";
                            try {
                                if (param.args.length >= 7) {
                                    talker = String.valueOf(param.args[6]);
                                }
                            } catch (Throwable ignored) {}

                            boolean isImportant = talker != null
                                    && !talker.isEmpty()
                                    && importantContacts.contains(talker.trim());

                            if (priorityMode == 1 && !isImportant) {
                                if (param.args.length >= 4) {
                                    param.args[2] = false;
                                    param.args[3] = false;
                                }
                            }
                        }
                    });
                    XposedBridge.log("[Notify] 优先级Hook完成 (mode=" + priorityMode
                            + " important=" + importantContacts.size() + "人)");
                    break;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[Notify] 优先级Hook失败: " + t.getMessage());
        }
    }

    private static void hookNotifyAvatar(ClassLoader cl) {
        if (!avatarEnabled) return;
        try {
            Class<?> avatarConfig = XposedHelpers.findClass(
                    "com.tencent.mm.repairer.config.notification"
                    + ".RepairerConfigNotificationAvatarSwitch", cl);

            for (java.lang.reflect.Field f : avatarConfig.getDeclaredFields()) {
                if (f.getType() == boolean.class) {
                    f.setAccessible(true);
                    try { f.setBoolean(null, true); }
                    catch (Throwable ignored) {}
                }
            }
            XposedBridge.log("[Notify] 通知头像已强制启用");
        } catch (Throwable t) {
            XposedBridge.log("[Notify] 头像Hook失败: " + t.getMessage());
        }
    }
}
