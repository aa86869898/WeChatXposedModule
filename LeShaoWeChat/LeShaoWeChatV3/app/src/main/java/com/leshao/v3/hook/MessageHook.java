package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;
import com.leshao.v3.model.WeChatMessage;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class MessageHook {

    private static final String TAG = "MessageHook";
    private static final String PKG_WECHAT = "com.tencent.mm";
    private static volatile MessageCallback sCallback;

    public interface MessageCallback {
        void onMessage(WeChatMessage msg);
    }

    public static void setCallback(MessageCallback cb) { sCallback = cb; }

    public static void hook(ClassLoader cl) {
        int hooked = 0;

        // Strategy 1: e01.x9.e(e9, boolean) — 参考自动播报.md
        hooked += tryHookE01X9(cl);

        // Strategy 2: e01.x9 所有方法 (兜底诊断)
        hooked += tryHookE01X9AllMethods(cl);

        // Strategy 3: com.tencent.mm.storage.e9 构造函数 (消息对象创建时)
        hooked += tryHookE9Constructor(cl);

        LogWriter.log(TAG, "hooks registered: " + hooked + " strategies");
    }

    // ===== Strategy 1: e01.x9.e(e9, boolean) =====
    private static int tryHookE01X9(ClassLoader cl) {
        try {
            Class<?> msgLogicCls = cl.loadClass("e01.x9");
            Class<?> msgInfoCls = cl.loadClass(PKG_WECHAT + ".storage.e9");

            XposedHelpers.findAndHookMethod(msgLogicCls, "e",
                msgInfoCls, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object msgInfo = param.args[0];
                            boolean isNew = (boolean) param.args[1];
                            LogWriter.log(TAG, "S1-HIT: isNew=" + isNew);

                            if (!isNew) return;

                            int msgType = (int) XposedHelpers.callMethod(msgInfo, "getType");
                            int isSend = (int) XposedHelpers.callMethod(msgInfo, "O0");
                            if (isSend == 1) return;

                            String talker = (String) XposedHelpers.callMethod(msgInfo, "N0");
                            String content = (String) XposedHelpers.callMethod(msgInfo, "I0");

                            WeChatMessage wm = new WeChatMessage(talker, "", content, msgType, System.currentTimeMillis());
                            if (sCallback != null) sCallback.onMessage(wm);
                            LogWriter.log(TAG, "S1 msg type=" + msgType + " talker=" + talker);

                        } catch (Throwable t) {
                            LogWriter.log(TAG, "S1 err: " + t.getClass().getSimpleName() + " " + t.getMessage());
                        }
                    }
                });
            LogWriter.log(TAG, "S1: e01.x9.e(e9,boolean) OK");
            return 1;
        } catch (Throwable t) {
            LogWriter.log(TAG, "S1 FAILED: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return 0;
        }
    }

    // ===== Strategy 2: e01.x9 所有2参数方法 (诊断盲hook) =====
    private static int tryHookE01X9AllMethods(ClassLoader cl) {
        try {
            Class<?> msgLogicCls = cl.loadClass("e01.x9");
            int count = 0;

            for (Method m : msgLogicCls.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 2) continue;
                String mName = m.getName();
                Class<?> p0 = m.getParameterTypes()[0];
                Class<?> p1 = m.getParameterTypes()[1];
                LogWriter.log(TAG, "S2 scan: e01.x9." + mName + "("
                    + p0.getSimpleName() + "," + p1.getSimpleName() + ")");

                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object arg0 = param.args[0];
                            Object arg1 = param.args[1];
                            LogWriter.log(TAG, "S2-HIT: " + mName + "("
                                + (arg0 != null ? arg0.getClass().getSimpleName() : "null")
                                + "," + (arg1 != null ? arg1.getClass().getSimpleName() + "=" + arg1 : "null") + ")");

                            try {
                                Object msgInfo = arg0;
                                boolean isNew = arg1 instanceof Boolean && (Boolean) arg1;

                                int msgType = (int) XposedHelpers.callMethod(msgInfo, "getType");
                                int isSend = 0;
                                try { isSend = (int) XposedHelpers.callMethod(msgInfo, "O0"); } catch (Throwable e) {}

                                String talker = "";
                                try { talker = (String) XposedHelpers.callMethod(msgInfo, "N0"); } catch (Throwable e) {}
                                String content = "";
                                try { content = (String) XposedHelpers.callMethod(msgInfo, "I0"); } catch (Throwable e) {}

                                LogWriter.log(TAG, "S2 msg: type=" + msgType + " isSend=" + isSend
                                    + " talker=" + talker + " content="
                                    + (content != null ? content.substring(0, Math.min(40, content.length())) : "null"));

                                if (isSend == 1) return;
                                if (sCallback != null) {
                                    WeChatMessage wm = new WeChatMessage(talker, "", content, msgType, System.currentTimeMillis());
                                    sCallback.onMessage(wm);
                                }
                            } catch (Throwable t) {
                                LogWriter.log(TAG, "S2 parse err: " + t.getMessage());
                            }
                        } catch (Throwable t) {}
                    }
                });
                count++;
            }
            LogWriter.log(TAG, "S2: hooked " + count + " methods in e01.x9");
            return count > 0 ? 1 : 0;
        } catch (Throwable t) {
            LogWriter.log(TAG, "S2 FAILED: " + t.getClass().getSimpleName());
            return 0;
        }
    }

    // ===== Strategy 3: com.tencent.mm.storage.e9 构造函数 =====
    private static int tryHookE9Constructor(ClassLoader cl) {
        try {
            Class<?> msgInfoCls = cl.loadClass(PKG_WECHAT + ".storage.e9");
            int count = 0;
            for (java.lang.reflect.Constructor<?> ctor : msgInfoCls.getDeclaredConstructors()) {
                if (ctor.getParameterTypes().length == 0) continue;
                LogWriter.log(TAG, "S3: e9 ctor params=" + ctor.getParameterTypes().length);
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object msgInfo = param.thisObject;
                            LogWriter.log(TAG, "S3-HIT: e9 created");
                            try {
                                int msgType = (int) XposedHelpers.callMethod(msgInfo, "getType");
                                int isSend = 0;
                                try { isSend = (int) XposedHelpers.callMethod(msgInfo, "O0"); } catch (Throwable e) {}
                                String talker = "";
                                try { talker = (String) XposedHelpers.callMethod(msgInfo, "N0"); } catch (Throwable e) {}
                                String content = "";
                                try { content = (String) XposedHelpers.callMethod(msgInfo, "I0"); } catch (Throwable e) {}

                                LogWriter.log(TAG, "S3 e9: type=" + msgType + " isSend=" + isSend
                                    + " talker=" + talker);

                                if (isSend == 1) return;
                                if (sCallback != null) {
                                    WeChatMessage wm = new WeChatMessage(talker, "", content, msgType, System.currentTimeMillis());
                                    sCallback.onMessage(wm);
                                }
                            } catch (Throwable t) {
                                LogWriter.log(TAG, "S3 parse err: " + t.getMessage());
                            }
                        } catch (Throwable t) {}
                    }
                });
                count++;
            }
            LogWriter.log(TAG, "S3: hooked " + count + " constructors of e9");
            return count > 0 ? 1 : 0;
        } catch (Throwable t) {
            LogWriter.log(TAG, "S3 FAILED: " + t.getClass().getSimpleName());
            return 0;
        }
    }
}
