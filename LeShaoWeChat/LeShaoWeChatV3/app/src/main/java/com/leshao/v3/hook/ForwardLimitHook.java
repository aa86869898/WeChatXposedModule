package com.leshao.v3.hook;

import android.content.Intent;
import android.content.SharedPreferences;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 突破转发/群发多选联系人 9 人上限 —— 严格按《微信突破转发群发9个联系人上限_完整逆向分析.md》
 * 方案 A 实现：hook {@code Intent#getIntExtra(String,int)}，当 key == "max_limit_num"
 * 且原值为 9/10 时改写为 {@link Integer#MAX_VALUE}。
 *
 * <p>单点、跨版本、零混淆依赖：同时覆盖新架构 sr5.w1（单点/批量判定）、老版
 * SelectContactUI.w7 与子页面（标签选人）透传；发送链路 MsgRetransmitUI.t7 无条数校验。</p>
 */
public final class ForwardLimitHook {

    public static final String TAG = "ForwardLimit";
    public static final String K_ENABLED = "ls_forward_limit_unlock";

    private static final String KEY = "max_limit_num";

    private static volatile boolean sEnabled = false;
    private static volatile boolean sHooked = false;

    private ForwardLimitHook() {}

    // ---------------- 配置 ----------------

    public static boolean isEnabled() {
        SharedPreferences sp = safePrefs();
        return sp != null && sp.getBoolean(K_ENABLED, false);
    }

    public static void setEnabled(boolean on) {
        try {
            ContextManager.getPrefs().edit().putBoolean(K_ENABLED, on).apply();
        } catch (Throwable ignored) {}
        sEnabled = on;
        LogWriter.log(TAG, "setEnabled=" + on);
    }

    private static SharedPreferences safePrefs() {
        try {
            return ContextManager.getPrefs();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- Hook 安装 ----------------

    public static void hook(final ClassLoader cl) {
        try {
            sEnabled = isEnabled();
        } catch (Throwable ignored) {}
        if (sHooked) return;
        try {
            installHook();
            sHooked = true;
            LogWriter.log(TAG, "hooks installed");
        } catch (Throwable t) {
            LogWriter.log(TAG, "install failed: " + t.getMessage());
        }
    }

    /** 方案 A：hook Intent.getIntExtra(String,int)，max_limit_num=9/10 -> Integer.MAX_VALUE。 */
    private static void installHook() throws Throwable {
        Method m = Intent.class.getDeclaredMethod("getIntExtra", String.class, int.class);
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!sEnabled) return;
                Object key = param.args[0];
                if (!(key instanceof String)) return;
                if (!KEY.equals(key)) return;
                int origin = (int) param.getResult();
                if (origin == 9 || origin == 10) {
                    param.setResult(Integer.MAX_VALUE);
                    LogWriter.log(TAG, "max_limit_num " + origin + " -> MAX_INT");
                }
            }
        });
        LogWriter.log(TAG, "hooked Intent.getIntExtra");
    }
}