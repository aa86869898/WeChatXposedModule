package com.leshao.v3.hook;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.WeChatMessage;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public class MessageHook {

    private static final String TAG = "MessageHook";
    private static volatile MessageCallback sCallback;

    public interface MessageCallback {
        void onMessage(WeChatMessage msg);
    }

    public static void setCallback(MessageCallback cb) { sCallback = cb; }

    /**
     * Hook 微信消息处理，尝试多个可能的目标方法
     */
    public static void hook() {
        if (!ContextManager.isReady()) {
            LogWriter.log(TAG, "hook ABORTED: ContextManager not ready");
            return;
        }

        ClassLoader cl = ContextManager.getClassLoader();

        // 策略1: 尝试 hook com.tencent.mm.plugin.base.stub.WXMsgBizEntry
        tryHookClass(cl, "com.tencent.mm.plugin.base.stub.WXMsgBizEntry", "handleMessage");

        // 策略2: 尝试 hook com.tencent.mm.modelmulti 相关
        for (String cls : new String[]{
            "com.tencent.mm.modelmulti.p",
            "com.tencent.mm.modelmulti.q",
            "com.tencent.mm.modelmulti.r",
        }) {
            try {
                Class<?> c = cl.loadClass(cls);
                LogWriter.log(TAG, "tryHookClass: " + cls);
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
                                            }
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            });
                        LogWriter.log(TAG, "hooked " + cls + "." + m.getName());
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 策略3: Hook plugin.notification 消息通知
        tryHookClass(cl, "com.tencent.mm.plugin.notification.b.a", "a");

        LogWriter.log(TAG, "hook complete");
    }

    private static void tryHookClass(ClassLoader cl, String className, String methodName) {
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
                                        }
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
                    LogWriter.log(TAG, "hooked " + className + "." + m.getName());
                }
            }
        } catch (Throwable ignored) {}
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
