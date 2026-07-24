package com.leshao.v3;

import de.robv.android.xposed.XposedHelpers;
import java.util.HashMap;
import java.util.Map;

/**
 * 微信 8.0.x 关键类名/方法名映射表
 * ================================
 * 微信使用 ProGuard 混淆，类名随版本变化。
 * 通过 DexKit 动态搜索或字符串特征定位实际混淆类名。
 *
 * 数据结构说明:
 *   storage.e9 (com.tencent.mm.storage.e9) = MsgInfo — 消息数据对象
 *     - getType()  返回 int 类型码:
 *         1=文本, 3=图片, 34=语音, 43=视频, 47=表情,
 *         49=AppMsg(链接/文件), 0x2712(10002)=已撤回,
 *         436207665=红包通知(0x1A000031)
 *     - getContent() 返回 String 消息内容
 *     - getCreateTime() 返回 long 时间戳(毫秒)
 *     - getIsSender() 返回 boolean 是否自己发送
 *     - field_username / field_talker  String 会话ID
 *     - field_msgSvrId  long 服务器消息ID
 *
 *   存储层:
 *     f9 类 = 消息存储服务(MessageStorage)
 *       - Ra(msgId, msgInfo) 更新消息
 *       - Ra(long, MsgInfo) 按服务器ID更新
 *       - qU(talker) 查询会话消息列表
 *
 *   撤回链路(XML):
 *     af5.b → 解析 XML 识别 revokemsg
 *     af5.a(Runnable) → run() 中修改 MsgInfo.type=10002
 *
 *   撤回链路(Protobuf):
 *     e01.u → 处理 Protobuf 格式撤回
 *       - f(talker, svrId, ...) 核心撤回方法
 */
public class WeChatClasses {

    private static final Map<String, Class<?>> cache = new HashMap<>();
    private static ClassLoader classLoader;

    public static void init(ClassLoader cl) {
        classLoader = cl;
        cache.clear();
    }

    /**
     * 按优先级尝试多个类名，返回第一个找到的
     */
    public static Class<?> findAny(String... names) {
        for (String name : names) {
            Class<?> c = cache.get(name);
            if (c != null) return c;
            try {
                c = XposedHelpers.findClass(name, classLoader);
                if (c != null) {
                    cache.put(name, c);
                    return c;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static Class<?> clz(String name) {
        Class<?> c = cache.get(name);
        if (c != null) return c;
        try {
            c = XposedHelpers.findClass(name, classLoader);
            cache.put(name, c);
            return c;
        } catch (Throwable t) {
            Logger.e("Class not found: " + name);
            return null;
        }
    }

    // ============== 核心类 ==============

    /** MsgInfo — 消息数据对象 */
    public static Class<?> MsgInfo() { return clz("com.tencent.mm.storage.e9"); }

    /** 消息存储服务 */
    public static Class<?> MsgStorage() { return findAny("com.tencent.mm.storage.f9"); }

    /** ChatFooter — 聊天输入框 */
    public static Class<?> ChatFooter() { return clz("com.tencent.mm.pluginsdk.ui.chat.ChatFooter"); }

    /** ChatFooterCustom — 自定义聊天底部 */
    public static Class<?> ChatFooterCustom() { return clz("com.tencent.mm.ui.chatting.ChatFooterCustom"); }

    /** ChattingUIFragment — 聊天界面Fragment */
    public static Class<?> ChattingUIFragment() { return clz("com.tencent.mm.ui.chatting.ChattingUIFragment"); }

    /** LauncherUI — 主界面Activity */
    public static Class<?> LauncherUI() { return clz("com.tencent.mm.ui.LauncherUI"); }

    /** MainTabUI — 底部Tab */
    public static Class<?> MainTabUI() { return clz("com.tencent.mm.ui.MainTabUI"); }

    /** ContactInfoUI — 联系人详情页 */
    public static Class<?> ContactInfoUI() { return clz("com.tencent.mm.plugin.profile.ui.ContactInfoUI"); }

    /** SnsTimeLineUI — 朋友圈首页 */
    public static Class<?> SnsTimeLineUI() { return clz("com.tencent.mm.plugin.sns.ui.SnsTimeLineUI"); }

    /** RevokeMsgListener — 撤回消息监听器 */
    public static Class<?> RevokeMsgListener() { return clz("com.tencent.mm.ui.chatting.RevokeMsgListener"); }

    // ============== 撤回相关 ==============

    /** XML撤回处理 Runnable (af5.a) */
    public static Class<?> XmlRevokeRunnable() {
        return findAny("af5.a", "af5.b");
    }

    /** Protobuf撤回处理 (e01.u) */
    public static Class<?> ProtoRevokeHandler() {
        return findAny("e01.u", "e01.v");
    }

    // ============== 红包相关 ==============

    /** 红包准备UI */
    public static Class<?> LuckyMoneyPrepareUI() {
        return findAny("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyPrepareUI");
    }

    /** 红包收取UI */
    public static Class<?> LuckyMoneyReceiveUI() {
        return findAny("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyReceiveUI");
    }

    /** 红包详情UI */
    public static Class<?> LuckyMoneyDetailUI() {
        return findAny("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI");
    }

    // ============== SNS/朋友圈 ==============

    /** SNS适配器 */
    public static Class<?> SnsTimeLineAdapter() {
        return findAny("com.tencent.mm.plugin.sns.ui.SnsTimeLineAdapter");
    }

    /** SNS信息 */
    public static Class<?> SnsInfo() {
        return findAny("com.tencent.mm.plugin.sns.storage.n");
    }

    /** 广告SNS信息 */
    public static Class<?> AdSnsInfo() {
        return clz("com.tencent.mm.plugin.sns.storage.AdSnsInfo");
    }

    // ============== 通讯录 ==============

    /** 通讯录Fragment */
    public static Class<?> AddressUIFragment() {
        return clz("com.tencent.mm.ui.contact.AddressUI$AddressUIFragment");
    }

    /** 备注名修改UI */
    public static Class<?> ModRemarkNameUI() {
        return findAny("com.tencent.mm.ui.contact.ModRemarkNameUI");
    }

    // ============== 通话 ==============

    /** VoIP模型 */
    public static Class<?> VoipModel() {
        return findAny("com.tencent.mm.plugin.voip.model.c0");
    }

    /** 多方通话 */
    public static Class<?> MultiTalk() {
        return findAny("com.tencent.mm.plugin.multitalk.model.i0");
    }

    // ============== 群聊 ==============

    /** 群信息UI */
    public static Class<?> ChatroomInfoUI() {
        return findAny("com.tencent.mm.chatroom.ui.ChatroomInfoUI");
    }

    /** 删除群成员UI */
    public static Class<?> DelChatroomMemberUI() {
        return clz("com.tencent.mm.chatroom.ui.DelChatroomMemberUI");
    }

    // ============== 通知 ==============

    /** 通知处理 */
    public static Class<?> NotificationHandler() {
        return findAny("com.tencent.mm.booter.notification.m0");
    }

    // ============== 截图 ==============

    /** 截图检测 */
    public static Class<?> ScreenshotDetector() {
        return findAny("com.tencent.mm.ui.feature.api.screenshot.u");
    }

    // ============== 指纹 ==============

    /** 指纹认证事件 */
    public static Class<?> FingerprintEvent() {
        return findAny("com.tencent.mm.autogen.events.FingerprintLoginAuthEvent");
    }
}
