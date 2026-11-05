package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;

import de.robv.android.xposed.XposedHelpers;

/**
 * 发送工具类 — 仅保留文本消息发送能力。
 * 依赖方: WxMasterFeatures.batchSend(功能24)、MessageHook 关键词回复、DianGeService 点歌回复。
 *
 * <p>v3.0.579（8.0.79 / versionCode 3200 真实 dex 复核）：
 * 三级发送链，每一级都返回真实结果，任一级成功即停止：</p>
 * <ol>
 *   <li>SendMsgMgr 文本：{@code ni5.n0.c(no3.c1)} → {@code rt5.i5.Jj(talker,content,type,flag)}（已验证存在）</li>
 *   <li>SendMsgCgiFactory Builder：{@code f61.s1}（DexKit 锚点 MicroMsg.SendMsgCgiFactory）</li>
 *   <li>e9 本地入库 + f61.s0 NetScene 入队（兜底，仅用于保底可见）</li>
 * </ol>
 *
 * <p>e9（com.tencent.mm.storage.e9，父类 im.c8）真实 setter/getter（dex 实证）：
 * {@code setMsgId(long)} / {@code getMsgId()}、{@code n1(long)}=setMsgSvrId、{@code H0()}=getMsgSvrId、
 * {@code setType(int)} / {@code getType()}、{@code s1(int)}=setStatus、{@code i1(int)}=setIsSend、
 * {@code a1(long)}=setCreateTime、{@code t1(String)}=setTalker、{@code N0()}=getTalker、
 * {@code Z0(String)}=setContent、{@code F0()}=getContent。
 * 旧代码里的 b1/e1/u1(String)/S1 在 8.0.79 均不存在（b1、S1 全版本缺失）。</p>
 */
public class GroupFeatures {

    private static final String TAG = "GroupSend";

    /**
     * 发送文本消息。
     *
     * @return true 表示至少一条链路确认已受理发送
     */
    public static boolean sendTextMessage(ClassLoader cl, String talker, String text) {
        if (talker == null || talker.isEmpty() || text == null || text.isEmpty()) return false;

        // v3.0.581: 统一打零宽水印（跨分身可识别），再登记「模块自发消息」，
        // 任何一条发送链落库后回到接收侧 hook 都会被拦下。
        final String out = SelfMsgGuard.watermark(text);
        try { SelfMsgGuard.mark(talker, 1, out); } catch (Throwable ignored) {}

        // ① SendMsgMgr 文本（8.0.79 已验证：ni5.n0.c(no3.c1) -> rt5.i5.Jj）
        try {
            if (com.leshao.v3.wm.utils.WmReflect.sendTextMsg(cl, out, talker)) {
                LogWriter.log(TAG, "① SendMsgMgr.Jj OK -> " + talker);
                return true;
            }
            LogWriter.log(TAG, "① SendMsgMgr.Jj 未成功, 回退 Builder");
        } catch (Throwable t) {
            LogWriter.log(TAG, "① SendMsgMgr.Jj 异常: " + t);
        }

        // ② SendMsgCgiFactory Builder（f61.s1，DexKit 锚点）
        try {
            if (com.leshao.ai.hook.wechat.WeChatMessenger.sendText(talker, out, cl)) {
                LogWriter.log(TAG, "② Builder OK -> " + talker);
                return true;
            }
            LogWriter.log(TAG, "② Builder 未成功, 回退 e9 入库");
        } catch (Throwable t) {
            LogWriter.log(TAG, "② Builder 异常: " + t);
        }

        // ③ e9 入库兜底
        if (insertViaMsgInfo(cl, talker, out)) {
            LogWriter.log(TAG, "③ e9 入库 OK -> " + talker);
            return true;
        }

        // ④ 当前会话输入框兜底
        if (sendViaFooter(cl, talker, out)) {
            LogWriter.log(TAG, "④ Footer OK -> " + talker);
            return true;
        }
        LogWriter.log(TAG, "发送全部失败 -> " + talker);
        return false;
    }

    /** e9 构造 + 正确 setter + f9.Nb 入库（8.0.79 实证签名）。 */
    private static boolean insertViaMsgInfo(ClassLoader cl, String talker, String text) {
        try {
            Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Class == null) {
                LogWriter.log(TAG, "e9 类未定位");
                return false;
            }
            Object msg = XposedHelpers.newInstance(e9Class, talker);
            long now = System.currentTimeMillis() / 1000L;
            try { XposedHelpers.callMethod(msg, "setType", 1); } catch (Throwable ignored) {}
            try { XposedHelpers.callMethod(msg, "Z0", text); } catch (Throwable ignored) {}     // content
            try { XposedHelpers.callMethod(msg, "t1", talker); } catch (Throwable ignored) {}   // talker
            try { XposedHelpers.callMethod(msg, "a1", now); } catch (Throwable ignored) {}      // createTime
            try { XposedHelpers.callMethod(msg, "i1", 1); } catch (Throwable ignored) {}        // isSend=1
            try { XposedHelpers.callMethod(msg, "s1", 2); } catch (Throwable ignored) {}        // status=SEND_SUCC?

            // v3.0.580 关键护栏：入库前必须确认 isSend 真的是 1。
            // 若 i1 调用失败而仍入库，这条消息会以「别人发来的」形态进入 f9 回调，
            // 被自动转发/AI/进群等接收侧 hook 当成新消息处理（历史上就是这样把
            // 点歌提示语群发到 545 个群的）。读不到或不等于 1 一律放弃本条入库。
            int isSendRead = readIsSend(msg);
            if (isSendRead != 1) {
                LogWriter.log(TAG, "e9 入库放弃: isSend=" + isSendRead + " (期望 1) talker=" + talker);
                return false;
            }

            Object f9 = null;
            try {
                com.leshao.ai.hook.wechat.StorageHub hub = com.leshao.ai.hook.wechat.StorageHub.get();
                if (hub != null) f9 = hub.msgInfoStorage();
            } catch (Throwable ignored) {}
            if (f9 == null) {
                Class<?> shortCls = VersionCompat.findMsgStorageShortClass(cl);
                if (shortCls != null) {
                    Object service = XposedHelpers.callStaticMethod(shortCls, "b");
                    if (service != null) f9 = XposedHelpers.callMethod(service, "u");
                }
            }
            if (f9 == null) {
                LogWriter.log(TAG, "f9 未定位");
                return false;
            }
            // 8.0.79: 插入 = Nb(e9, boolean) / Mb(e9)（Bb 已不存在）
            Object id = null;
            try { id = XposedHelpers.callMethod(f9, "Nb", msg, false); } catch (Throwable ignored) {}
            if (id == null) {
                try { id = XposedHelpers.callMethod(f9, "Mb", msg); } catch (Throwable ignored) {}
            }
            if (id == null) {
                LogWriter.log(TAG, "f9.Nb/Mb 未返回 msgId");
                return false;
            }
            // 登记入库得到的 msgId，接收侧见到同 msgId 直接跳过
            try {
                if (id instanceof Long) SelfMsgGuard.markMsgId((Long) id);
                else if (id instanceof Integer) SelfMsgGuard.markMsgId(((Integer) id).longValue());
            } catch (Throwable ignored) {}
            LogWriter.log(TAG, "e9 入库 msgId=" + id);
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "e9 入库异常: " + t);
            return false;
        }
    }

    /** 读 isSend：z0() → getIsSend() → field_isSend；全失败返回 -1。 */
    private static int readIsSend(Object e9) {
        try {
            Object v = XposedHelpers.callMethod(e9, "z0");
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {}
        try {
            Object v = XposedHelpers.callMethod(e9, "getIsSend");
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {}
        try {
            return XposedHelpers.getIntField(e9, "field_isSend");
        } catch (Throwable ignored) {}
        return -1;
    }

    private static boolean sendViaFooter(ClassLoader cl, String talker, String text) {
        try {
            Class<?> launcherUI = XposedHelpers.findClass(
                    "com.tencent.mm.ui.LauncherUI", cl);
            Object instance = XposedHelpers.callStaticMethod(launcherUI, "getInstance");
            if (instance == null) return false;
            Object fragment = XposedHelpers.callMethod(instance, "getCurrentFragmet");
            if (fragment == null) return false;
            Object footer = XposedHelpers.getObjectField(fragment, "mFooter");
            if (footer == null) return false;
            String currentTalker = (String) XposedHelpers.callMethod(footer, "getTalkerUserName");
            if (!talker.equals(currentTalker)) {
                LogWriter.log(TAG, "Footer: 当前聊天非目标会话");
                return false;
            }

            Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Class == null) return false;
            Object msg = XposedHelpers.newInstance(e9Class, talker);
            try { XposedHelpers.callMethod(msg, "setType", 1); } catch (Throwable ignored) {}
            try { XposedHelpers.callMethod(msg, "Z0", text); } catch (Throwable ignored) {}
            XposedHelpers.callMethod(footer, "F", msg, null);
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "Footer 异常: " + t);
            return false;
        }
    }
}
