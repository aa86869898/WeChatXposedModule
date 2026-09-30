package com.leshao.v3.hook;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 发送工具类 — 仅保留文本消息发送能力。
 * 依赖方: WxMasterFeatures.batchSend(功能24)、MessageHook 关键词回复。
 */
public class GroupFeatures {

    public static void sendTextMessage(ClassLoader cl, String talker, String text) {
        if (talker == null || talker.isEmpty() || text == null || text.isEmpty()) return;
        // 8.0.78(3180): 优先走 qs5.v5 新框架文本 (多类型群发2_新.md §3.1)
        try {
            if (com.leshao.v3.wm.utils.WmReflect.sendTextMsg(cl, text, talker)) {
                XposedBridge.log("[Group] 文本已通过 qs5.v5 发送: " + talker);
                return;
            }
            XposedBridge.log("[Group] qs5.v5 sendTextMsg 未成功(返回 false), 回退 WeChatMessenger");
        } catch (Throwable t) {
            XposedBridge.log("[Group] qs5.v5 发送异常, 回退 WeChatMessenger: " + t.getMessage());
        }
        // 回退: AI 模块标准发送链(e9 isSend=1 -> f9.Bb -> v51.r0 -> NetSceneQueue.h)
        try {
            if (com.leshao.ai.hook.wechat.WeChatMessenger.sendText(talker, text, cl)) {
                XposedBridge.log("[Group] 文本已通过 WeChatMessenger 发送: " + talker);
                return;
            }
            XposedBridge.log("[Group] WeChatMessenger 未成功, 回退 e9 入库");
        } catch (Throwable t) {
            XposedBridge.log("[Group] WeChatMessenger 异常, 回退 e9 入库: " + t.getMessage());
        }
        try {
            Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Class == null) return;
            Object msg = XposedHelpers.newInstance(e9Class, talker);
            // 8.0.78(3180) e9 setter: b1(content) u1(talker) e1(createTime) setType(int)
            try {
                XposedHelpers.callMethod(msg, "b1", text);
            } catch (Throwable t1) {
                try { XposedHelpers.callMethod(msg, "X0", text); } catch (Throwable ignored1) {}
            }
            try {
                XposedHelpers.callMethod(msg, "u1", talker);
            } catch (Throwable ignored) {}
            try {
                XposedHelpers.callMethod(msg, "e1", System.currentTimeMillis());
            } catch (Throwable t2) {
                try { XposedHelpers.callMethod(msg, "L1", System.currentTimeMillis()); } catch (Throwable ignored2) {}
            }
            try {
                XposedHelpers.callMethod(msg, "setType", 1);
            } catch (Throwable ignored) {}

            // 兜底: f9.yb(e9) 入库
            try {
                Class<?> f9 = XposedHelpers.findClass("com.tencent.mm.storage.f9", cl);
                Object y = XposedHelpers.callStaticMethod(f9, "yb", msg, 0);
                if (y != null) {
                    XposedBridge.log("[Group] 消息已通过 f9.yb 入库");
                }
            } catch (Throwable t3) {
                XposedBridge.log("[Group] f9.yb 失败, 回退 Footer: " + t3.getMessage());
                sendViaFooter(cl, talker, text);
            }
        } catch (Throwable t) {
            XposedBridge.log("[Group] 发送失败: " + t.getMessage());
            sendViaFooter(cl, talker, text);
        }
    }

    private static void sendViaFooter(ClassLoader cl, String talker, String text) {
        try {
            Class<?> launcherUI = XposedHelpers.findClass(
                    "com.tencent.mm.ui.LauncherUI", cl);
            Object instance = XposedHelpers.callStaticMethod(launcherUI, "getInstance");
            if (instance == null) return;
            Object fragment = XposedHelpers.callMethod(instance, "getCurrentFragmet");
            if (fragment == null) return;
            Object footer = XposedHelpers.getObjectField(fragment, "mFooter");
            if (footer == null) return;
            String currentTalker = (String) XposedHelpers.callMethod(footer, "getTalkerUserName");
            if (!talker.equals(currentTalker)) {
                XposedBridge.log("[Group] 当前聊天非目标群，无法Footer发送");
                return;
            }

            Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Class == null) return;
            Object msg = XposedHelpers.newInstance(e9Class, talker);
            XposedHelpers.callMethod(msg, "A1", 1);
            XposedHelpers.callMethod(msg, "X0", text);
            XposedHelpers.callMethod(footer, "F", msg, null);
            XposedBridge.log("[Group] 消息已发送(via Footer): " + text);
        } catch (Throwable t) {}
    }
}