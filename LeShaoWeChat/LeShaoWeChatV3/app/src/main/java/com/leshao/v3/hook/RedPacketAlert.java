package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import android.os.Vibrator;
import android.media.RingtoneManager;
import android.net.Uri;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [功能52] 红包提醒
 * 检测红包消息 → 强制震动+响铃(覆盖静音模式)
 */
public class RedPacketAlert {

    private static volatile boolean sEnabled = true;
    private static final int LUCKY_MONEY_TYPE = 0x1A000031;
    private static final ConcurrentHashMap<Long, Long> alertedMsgs = new ConcurrentHashMap<>();
    private static final long ALERT_EXPIRE_MS = 5 * 60 * 1000;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.redPacketAlertEnabled) return;
        hookRedPacketDetect(cl);
        hookNotificationOverride(cl);
        Logger.i("[RedPacketAlert] 红包提醒 Hook完成");
    }

    private static boolean shouldAlert(Object msgInfo) {
        if (msgInfo == null) return false;
        try {
            long msgId = (Long) XposedHelpers.callMethod(msgInfo, "H0");
            long now = System.currentTimeMillis();
            alertedMsgs.entrySet().removeIf(e -> now - e.getValue() > ALERT_EXPIRE_MS);
            if (alertedMsgs.containsKey(msgId)) return false;
            alertedMsgs.put(msgId, now);
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 检测红包消息类型 */
    private static void hookRedPacketDetect(ClassLoader cl) {
        try {
            Class<?> e9 = XposedHelpers.findClass("com.tencent.mm.storage.e9", cl);
            XposedBridge.hookAllMethods(e9, "getType", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    int type = (Integer) param.getResult();
                    if (type == LUCKY_MONEY_TYPE) {
                        try {
                            if (!shouldAlert(param.thisObject)) return;
                            String talker = (String) XposedHelpers.getObjectField(
                                    param.thisObject, "field_talker");
                            Logger.i("[RedPacketAlert] 红包! from=" + talker);
                            triggerAlert();
                        } catch (Throwable ignored) {}
                    }
                }
            });
            Logger.i("[RedPacketAlert] 消息检测 Hook完成");
        } catch (Throwable t) {
            Logger.w("[RedPacketAlert] 消息检测失败: " + t.getMessage());
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
                            if (!sEnabled) return;
                            try {
                                if (param.args.length >= 4) {
                                    param.args[2] = true; // force sound
                                    param.args[3] = true; // force vibrate
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                    Logger.i("[RedPacketAlert] 通知强制 Hook完成");
                }
            }
        } catch (Throwable t) {
            Logger.w("[RedPacketAlert] 通知Hook失败: " + t.getMessage());
        }
    }

    private static void triggerAlert() {
        try {
            android.content.Context ctx = ContextManager.getAppContext();
            if (ctx == null) return;
            Vibrator v = (Vibrator) ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(new long[]{0, 300, 100, 300}, -1);
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            android.media.Ringtone r = RingtoneManager.getRingtone(ctx, uri);
            if (r != null) r.play();
        } catch (Throwable ignored) {}
    }
}
