package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.hook.HookConfig;
import android.content.Context;

import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Vibrator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [功能52] RedPacketAlert — 完整修复版
 * =====================================
 * 
 * ⚠️ 修复: 同一条红包消息 getType() 被调用4-6次导致重复提醒
 *   消息入库时、UI渲染时、通知构建时、会话列表更新时各调用一次
 * 
 *   修复: ConcurrentHashMap<Long,Long> 记录已提醒的 msgId(H0())
 *         5分钟自动过期清理
 * 
 * 微信消息唯一ID: H0() → long (getMsgId混淆名)
 * 红包类型码: 0x1A000031 (436207665)
 */
public class RedPacketAlert {
    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    private static final int LUCKY_MONEY_TYPE = 0x1A000031;

    // ⚠️ 修复: 去重Map
    private static final ConcurrentHashMap<Long, Long> alertedMsgs =
            new ConcurrentHashMap<>();
    private static final long ALERT_EXPIRE_MS = 5 * 60 * 1000; // 5分钟过期

    private static boolean forceVibrate = true;
    private static boolean forceRing = true;

    public static void hook(ClassLoader cl) {
        if (!sEnabled || !ModuleConfig.load(ContextManager.getPrefs()).redPacketAlertEnabled) return;
        forceVibrate = HookConfig.getInt("rp_alert_vibrate", 1) == 1;
        forceRing = HookConfig.getInt("rp_alert_ring", 1) == 1;

        hookRedPacketDetect(cl);
        hookNotificationOverride(cl);
    }

    /** 检测红包消息类型 — ⚠️修复: 增加去重 */
    private static void hookRedPacketDetect(ClassLoader cl) {
        try {
            Class<?> e9 = XposedHelpers.findClass("com.tencent.mm.storage.e9", cl);
            XposedBridge.hookAllMethods(e9, "getType", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int type = (Integer) param.getResult();
                    if (type == LUCKY_MONEY_TYPE) {
                        try {
                            // ⚠️ 修复: 先检查去重
                            if (!shouldAlert(param.thisObject)) return;

                            String talker = (String) XposedHelpers.getObjectField(
                                    param.thisObject, "field_talker");
                            XposedBridge.log("[RedPacket] 🧧 红包! from=" + talker);
                            triggerAlert();
                        } catch (Throwable ignored) {}
                    }
                }
            });
            XposedBridge.log("[RedPacket] 消息检测 Hook完成 (带去重)");
        } catch (Throwable t) {
            XposedBridge.log("[RedPacket] 消息检测失败: " + t.getMessage());
        }
    }

    /**
     * ⚠️ 修复: 去重检查
     * H0() → long (getMsgId混淆名)
     */
    private static boolean shouldAlert(Object msgInfo) {
        if (msgInfo == null) return false;
        try {
            // ⚠️ 使用 H0() 获取消息ID
            long msgId = (Long) XposedHelpers.callMethod(msgInfo, "H0");

            // 清理过期记录
            long now = System.currentTimeMillis();
            for (Long key : alertedMsgs.keySet().toArray(new Long[0])) {
                Long time = alertedMsgs.get(key);
                if (time != null && now - time > ALERT_EXPIRE_MS) {
                    alertedMsgs.remove(key);
                }
            }

            // 检查是否已提醒
            if (alertedMsgs.containsKey(msgId)) {
                return false;
            }

            // 标记已提醒
            alertedMsgs.put(msgId, now);
            return true;
        } catch (Throwable t) {
            return true; // 获取msgId失败仍提醒(避免漏提醒)
        }
    }

    /** Hook notification.m0.a() 强制震动/响铃 */
    private static void hookNotificationOverride(ClassLoader cl) {
        try {
            Class<?> m0 = XposedHelpers.findClass(
                    "com.tencent.mm.booter.notification.m0", cl);
            for (java.lang.reflect.Method m : m0.getDeclaredMethods()) {
                if (m.getName().equals("a") && m.getParameterTypes().length >= 9) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length >= 4) {
                                if (forceRing) param.args[2] = true;
                                if (forceVibrate) param.args[3] = true;
                            }
                        }
                    });
                }
            }
        } catch (Throwable t) {}
    }

    private static void triggerAlert() {
        try {
            Context ctx = ContextManager.getAppContext();
            if (ctx == null) return;
            if (forceVibrate) {
                Vibrator v = (Vibrator) ctx.getSystemService(
                        android.content.Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(new long[]{0, 300, 100, 300}, -1);
            }
            if (forceRing) {
                Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                android.media.Ringtone r = RingtoneManager.getRingtone(ctx, uri);
                if (r != null) r.play();
            }
        } catch (Throwable ignored) {}
    }
}
