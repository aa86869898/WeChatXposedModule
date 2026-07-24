package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

/**
 * [功能14/50] 隐藏联系人敏感字段 — 生产级完整实现
 * ===============================================
 * 
 * ContactInfoUI.initView() — 610行初始化所有Preference项
 * 
 * 可隐藏的字段(key):
 *   contact_info_alias      微信号
 *   contact_info_mobile     手机号
 *   contact_info_region     地区
 *   contact_info_signature  签名
 *   contact_info_source     来源
 *   contact_info_remark     备注名(不推荐)
 *   contact_info_chatroom    共同群聊
 *   contact_info_linkedin   领英
 * 
 * 实现:
 *   1. Hook initView()完成后遍历PreferenceScreen
 *   2. 按key查找Preference → removePreference
 *   3. 支持自定义隐藏列表
 */
public class HideContactFields {

    private static final Set<String> DEFAULT_HIDDEN = new HashSet<>(Arrays.asList(
            "contact_info_mobile",
            "contact_info_region",
            "contact_info_source"
    ));

    private static Set<String> hiddenFields = new HashSet<>(DEFAULT_HIDDEN);
    private static volatile boolean sEnabled = true;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        String custom = HookConfig.getString("hidden_fields_list", "");
        if (custom != null && !custom.isEmpty()) {
            hiddenFields.clear();
            for (String s : custom.split(",")) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) hiddenFields.add(trimmed);
            }
        }

        hookInitView(cl);
        hookOnResume(cl);
    }

    private static void hookInitView(ClassLoader cl) {
        try {
            Class<?> contactInfoUI = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.profile.ui.ContactInfoUI", cl);

            XposedBridge.hookAllMethods(contactInfoUI, "initView",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    applyHiddenFields(param.thisObject);
                }
            });
            XposedBridge.log("[HideFields] ContactInfoUI.initView() Hook完成");
        } catch (Throwable t) {
            XposedBridge.log("[HideFields] initView失败: " + t.getMessage());
        }
    }

    private static void hookOnResume(ClassLoader cl) {
        try {
            Class<?> contactInfoUI = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.profile.ui.ContactInfoUI", cl);

            XposedBridge.hookAllMethods(contactInfoUI, "onResume",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    applyHiddenFields(param.thisObject);
                }
            });
        } catch (Throwable t) {}
    }

    private static void applyHiddenFields(Object activity) {
        try {
            Object prefScreen = XposedHelpers.callMethod(activity, "getPreferenceScreen");
            if (prefScreen == null) return;

            int totalBefore = (Integer) XposedHelpers.callMethod(
                    prefScreen, "getPreferenceCount");

            int removed = 0;
            for (String key : hiddenFields) {
                try {
                    Object pref = XposedHelpers.callMethod(prefScreen, "findPreference", key);
                    if (pref != null) {
                        boolean result = (Boolean) XposedHelpers.callMethod(
                                prefScreen, "removePreference", pref);
                        if (result) {
                            removed++;
                            XposedBridge.log("[HideFields] 已隐藏: " + key);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            int totalAfter = (Integer) XposedHelpers.callMethod(
                    prefScreen, "getPreferenceCount");

            XposedBridge.log("[HideFields] 隐藏完成: " + removed + "/"
                    + totalBefore + " → " + totalAfter + "项");
        } catch (Throwable t) {
            XposedBridge.log("[HideFields] 执行失败: " + t.getMessage());
        }
    }
}
