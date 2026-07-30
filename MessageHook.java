package com.leshao.v3;

import de.robv.android.xposed.*;
import java.lang.reflect.Method;

/**
 * 消息 Hook — 检测所有消息类型
 */
public class MessageHook {

    private static final String TAG = "MessageHook";
    private static int msgCount = 0;

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        try {
            // === Hook IEvent.e() 作为兜底消息检测 ===
            Class<?> iEventClz = cl.loadClass("com.tencent.mm.sdk.event.IEvent");
            Method eMethod = iEventClz.getDeclaredMethod("e");
            XposedBridge.hookMethod(eMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // 由 AutoJoinGroup 的 hook 处理 QR 结果
                }
            });

            // === Hook e01.x9.C(e9) — 消息入库 ===
            Class<?> x9Clz = cl.loadClass("e01.x9");
            
            // C(e9) — 消息写入 DB
            XposedHelpers.findAndHookMethod(x9Clz, "C", cl.loadClass("com.tencent.mm.storage.e9"), 
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object e9 = param.args[0];
                            int type = (Integer) XposedHelpers.callMethod(e9, "getType");
                            long msgId = (Long) XposedHelpers.callMethod(e9, "getMsgId");
                            String talker = (String) XposedHelpers.callMethod(e9, "N0");
                            int isSend = (Integer) XposedHelpers.callMethod(e9, "z0");
                            String content = (String) XposedHelpers.callMethod(e9, "j");
                            
                            msgCount++;
                            Log.e(TAG, "#" + msgCount + " type=" + type + " msgId=" + msgId 
                                + " talker=" + talker);

                            // === 图片消息 → 自动扫码进群 ===
                            if (type == 3) {
                                AutoJoinGroup.onImageMsg(e9);
                            }
                            
                        } catch (Throwable e) {
                            Log.e(TAG, "C hook err", e);
                        }
                    }
                });
            
            Log.e(TAG, "MessageHook 就绪");
        } catch (Throwable e) {
            Log.e(TAG, "hook失败: " + e.getMessage(), e);
        }
    }
}
