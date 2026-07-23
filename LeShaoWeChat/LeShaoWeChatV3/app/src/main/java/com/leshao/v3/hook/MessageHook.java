package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;
import com.leshao.v3.model.WeChatMessage;

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
                            if (!isNew) return;

                            int msgType = (int) XposedHelpers.callMethod(msgInfo, "getType");
                            int isSend = (int) XposedHelpers.callMethod(msgInfo, "O0");
                            if (isSend == 1) return;

                            String talker = (String) XposedHelpers.callMethod(msgInfo, "N0");
                            String content = (String) XposedHelpers.callMethod(msgInfo, "I0");
                            long createTime = System.currentTimeMillis();

                            WeChatMessage wm = new WeChatMessage(talker, "", content, msgType, createTime);

                            if (sCallback != null) {
                                sCallback.onMessage(wm);
                            }

                            LogWriter.log(TAG, "msg type=" + msgType + " talker=" + talker
                                + " content=" + (content != null ? content.substring(0, Math.min(30, content.length())) : "null"));

                        } catch (Throwable t) {
                            LogWriter.log(TAG, "e01.x9.e err: " + t.getClass().getSimpleName() + " " + t.getMessage());
                        }
                    }
                });
            LogWriter.log(TAG, "e01.x9.e hook OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "e01.x9.e hook FAILED: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }
}
