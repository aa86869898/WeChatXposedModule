package com.leshao.v3.hook;

import android.app.Notification;
import android.os.Bundle;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.WeChatMessage;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public class MessageHook {

    private static final String TAG = "MessageHook";
    private static volatile MessageCallback sCallback;
    private static long sLastDedupTime = 0;
    private static String sLastDedupKey = "";

    public interface MessageCallback {
        void onMessage(WeChatMessage msg);
    }

    public static void setCallback(MessageCallback cb) { sCallback = cb; }

    /**
     * Hook 微信消息处理，四层策略:
     *   P1: modelmulti.p/q/r (WeChat 内部消息引擎)
     *   P2: WXMsgBizEntry (WebView JS 桥)
     *   P3: plugin.notification (微信通知插件)
     *   P4: NotificationManager.notify (系统通知，最可靠兜底)
     */
    public static void hook() {
        if (!ContextManager.isReady()) {
            LogWriter.log(TAG, "hook ABORTED: ContextManager not ready");
            return;
        }

        ClassLoader cl = ContextManager.getClassLoader();

        // ===== P1: modelmulti.p/q/r =====
        int p1Hooked = 0;
        for (String cls : new String[]{
            "com.tencent.mm.modelmulti.p",
            "com.tencent.mm.modelmulti.q",
            "com.tencent.mm.modelmulti.r",
        }) {
            try {
                Class<?> c = cl.loadClass(cls);
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (m.getParameterTypes().length >= 3) {
                        XposedHelpers.findAndHookMethod(cls, cl, m.getName(),
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        Object msgObj = findMsgObject(param.args);
                                        if (msgObj != null) {
                                            WeChatMessage wm = WeChatMessage.fromReflectedObject(msgObj);
                                            if (wm != null && sCallback != null) {
                                                sCallback.onMessage(wm);
                                                LogWriter.log(TAG, "P1:" + cls + "." + m.getName() + " msg type=" + wm.type);
                                            }
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            });
                        p1Hooked++;
                    }
                }
            } catch (Throwable e) {
                LogWriter.log(TAG, "P1: " + cls + " not found: " + e.getClass().getSimpleName());
            }
        }

        // ===== P2: WXMsgBizEntry =====
        int p2Hooked = tryHookClass(cl, "com.tencent.mm.plugin.base.stub.WXMsgBizEntry", TAG, "P2");

        // ===== P3: plugin.notification =====
        int p3Hooked = tryHookClass(cl, "com.tencent.mm.plugin.notification.b.a", TAG, "P3");

        // ===== P4: NotificationManager 兜底 =====
        hookNotificationManager();

        LogWriter.log(TAG, "hook complete: P1=" + p1Hooked + " P2=" + p2Hooked
            + " P3=" + p3Hooked + " P4=ok");
    }

    private static void hookNotificationManager() {
        try {
            // Hook notify(String, int, Notification)
            XposedHelpers.findAndHookMethod(
                android.app.NotificationManager.class,
                "notify",
                String.class, Integer.TYPE, Notification.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            String tag = (String) param.args[0];
                            Notification n = (Notification) param.args[2];
                            if (n == null || tag == null) return;

                            // 跳过非微信的包（但 checkOp 需要 Context，这里用 heuristic）
                            Bundle extras = n.extras;
                            if (extras == null) return;

                            String title = extras.getString(Notification.EXTRA_TITLE, "");
                            String text = extras.getString(Notification.EXTRA_TEXT, "");
                            if (text.isEmpty() && title.isEmpty()) return;

                            // 去重: 1.5s 内相同内容跳过
                            String dedupKey = tag + "|" + text;
                            long now = System.currentTimeMillis();
                            if (dedupKey.equals(sLastDedupKey) && now - sLastDedupTime < 1500) return;
                            sLastDedupKey = dedupKey;
                            sLastDedupTime = now;

                            // 构建 WeChatMessage
                            boolean isGroup = tag != null && tag.endsWith("@chatroom");
                            String senderWxid = isGroup ? "" : tag;
                            int type = WeChatMessage.TYPE_TEXT;
                            WeChatMessage wm = new WeChatMessage(tag, senderWxid, text, type, now);

                            if (sCallback != null) {
                                sCallback.onMessage(wm);
                                LogWriter.log(TAG, "P4: notify tacker=" + tag
                                    + " group=" + isGroup + " text="
                                    + (text.length() > 20 ? text.substring(0, 20) + "..." : text));
                            }
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "P4: notify err: " + t.getMessage());
                        }
                    }
                });
        } catch (Throwable e) {
            LogWriter.log(TAG, "P4: NotificationManager hook FAILED: " + e.getClass().getSimpleName());
        }
    }

    private static int tryHookClass(ClassLoader cl, String className, String tag, String label) {
        int count = 0;
        try {
            Class<?> c = cl.loadClass(className);
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getParameterTypes().length >= 2) {
                    XposedHelpers.findAndHookMethod(className, cl, m.getName(),
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object msgObj = findMsgObject(param.args);
                                    if (msgObj != null) {
                                        WeChatMessage wm = WeChatMessage.fromReflectedObject(msgObj);
                                        if (wm != null && sCallback != null) {
                                            sCallback.onMessage(wm);
                                            LogWriter.log(tag, label + ": msg type=" + wm.type
                                                + " from=" + wm.talker);
                                        }
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
                    count++;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(tag, label + ": " + className + " not found: " + e.getClass().getSimpleName());
        }
        return count;
    }

    private static Object findMsgObject(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg == null) continue;
            String cn = arg.getClass().getName();
            if (cn.contains("MsgInfo") || cn.contains("kvstat") || cn.contains("AddMsgInfo")) {
                return arg;
            }
        }
        return null;
    }
}
