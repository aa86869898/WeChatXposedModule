package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;

/**
 * [功能10] 好友删除检测
 * ====================
 *
 * 微信联系人类型码 (rcontact表 type字段):
 *   0  = 正常好友
 *   1  = 对方未添加自己
 *   2  = 被对方删除 ⚠️
 *   3  = 自己删除对方
 *   4  = 已拉黑 🚫
 *   33 = 对方已添加但自己未确认
 *
 * 核心类:
 *   com.tencent.mm.plugin.profile.ui.ContactInfoUI
 *     onResume()                  每次查看资料时刷新
 *     D2() → void                 加载联系人详情
 *     E5(boolean) → void          更新状态
 *     onSceneEnd(int,int,String,m1) → void  网络回调
 *     W6(String) → void           修改备注
 *     X6(String) → void
 *     c7(String) → void
 *     onNotifyChange(String, w0)  联系人数据变更通知
 *
 * 检测方法:
 *   1. Hook ContactInfoUI.D2() → 读取联系人type
 *   2. Hook SendMsgSuccessEvent → 检测发送失败(被拒收)
 */
public class DeleteDetect {

    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static final int TYPE_FRIEND = 0;
    public static final int TYPE_NOT_ADDED = 1;
    public static final int TYPE_DELETED_BY_OTHER = 2;
    public static final int TYPE_DELETED_OTHER = 3;
    public static final int TYPE_BLACKLIST = 4;

    public static void hook(ClassLoader cl) {
        Logger.i("--- [10] 好友删除检测 ---");
        hookContactInfoUI(cl);
        hookMessageSendFail(cl);
    }

    /**
     * Hook ContactInfoUI.D2() — 加载联系人数据后检查type
     *
     * D2() 在 onResume 时被调用，加载联系人完整信息。
     * 之后通过反射读取联系人type字段判断状态。
     */
    private static void hookContactInfoUI(ClassLoader cl) {
        try {
            Class<?> contactInfoUI = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.profile.ui.ContactInfoUI", cl);

            // Hook D2() — 加载联系人数据
            XposedBridge.hookAllMethods(contactInfoUI, "D2", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    checkContactStatus(param.thisObject);
                }
            });

            // Hook onResume — 兜底检查
            XposedBridge.hookAllMethods(contactInfoUI, "onResume",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    checkContactStatus(param.thisObject);
                }
            });

            Logger.i("  ContactInfoUI.D2() + onResume() ✓");
        } catch (XposedHelpers.ClassNotFoundError e) {
            Logger.w("  ContactInfoUI 未找到");
        } catch (Throwable t) {
            Logger.e("  ContactInfoUI: " + t.getMessage());
        }
    }

    /**
     * 检查联系人状态
     *
     * ContactInfoUI内部字段（混淆后）可能是 mContact, n, o, p 等。
     * 读取其 field_type 字段判断好友关系。
     */
    private static void checkContactStatus(Object activity) {
        try {
            // 尝试多个可能的字段名获取联系人对象
            Object contact = null;
            for (String fieldName : new String[]{
                    "mContact", "n", "o", "p", "q", "r"}) {
                try {
                    contact = XposedHelpers.getObjectField(activity, fieldName);
                    if (contact != null) break;
                } catch (Throwable ignored) {}
            }

            if (contact == null) return;

            // 读取联系人信息
            String username = "";
            try {
                username = (String) XposedHelpers.getObjectField(
                        contact, "field_username");
            } catch (Throwable ignored) {}

            String nickname = "";
            try {
                nickname = (String) XposedHelpers.getObjectField(
                        contact, "field_nickname");
            } catch (Throwable ignored) {}

            // 读取type字段
            int type = -1;
            try {
                type = XposedHelpers.getIntField(contact, "field_type");
            } catch (Throwable e) {
                try {
                    type = (Integer) XposedHelpers.callMethod(contact, "getType");
                } catch (Throwable ignored) {}
            }

            // 判断状态
            String typeStr;
            switch (type) {
                case TYPE_DELETED_BY_OTHER:
                    typeStr = "⚠️ 你已被对方删除!";
                    break;
                case TYPE_BLACKLIST:
                    typeStr = "🚫 对方已拉黑你!";
                    break;
                case TYPE_NOT_ADDED:
                    typeStr = "📝 对方未添加你为好友";
                    break;
                case TYPE_FRIEND:
                    typeStr = "✅ 正常好友";
                    break;
                case TYPE_DELETED_OTHER:
                    typeStr = "🗑️ 你已删除对方";
                    break;
                default:
                    typeStr = "❓ 状态未知 type=" + type;
            }

            Logger.i("[删除检测] " + nickname + "(" + username + ") → " + typeStr);
        } catch (Throwable t) {
            Logger.e("[删除检测] 异常: " + t.getMessage());
        }
    }

    /**
     * Hook 消息发送失败检测
     *
     * 类: com.tencent.mm.autogen.events.SendMsgSuccessEvent
     * data.errCode ≠ 0 → 发送失败(可能被删除/拉黑)
     */
    private static void hookMessageSendFail(ClassLoader cl) {
        try {
            Class<?> event = XposedHelpers.findClass(
                    "com.tencent.mm.autogen.events.SendMsgSuccessEvent", cl);

            XposedBridge.hookAllMethods(event, "callback",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object e = param.args[0];
                        Object data = XposedHelpers.getObjectField(e, "data");
                        if (data != null) {
                            int errCode = XposedHelpers.getIntField(data, "errCode");
                            if (errCode != 0) {
                                Logger.i("[删除检测] 发送失败 errCode=" + errCode
                                        + " → 可能被对方删除或拉黑!");
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            Logger.i("  SendMsgSuccessEvent ✓");
        } catch (Throwable t) {
            Logger.w("  SendMsgSuccessEvent: " + t.getMessage());
        }
    }
}
