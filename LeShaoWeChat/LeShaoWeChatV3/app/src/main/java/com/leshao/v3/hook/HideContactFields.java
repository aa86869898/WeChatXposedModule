package com.leshao.v3.hook;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.leshao.v3.LogWriter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class HideContactFields {

    private static final String TAG = "HideFields";

    private static final Map<String, String> FIELD_LABELS = new HashMap<>();
    static {
        FIELD_LABELS.put("contact_info_mobile", "手机号");
        FIELD_LABELS.put("contact_info_region", "地区");
        FIELD_LABELS.put("contact_info_source", "来源");
        FIELD_LABELS.put("contact_info_alias", "微信号");
        FIELD_LABELS.put("contact_info_signature", "签名");
        FIELD_LABELS.put("contact_info_remark", "备注名");
        FIELD_LABELS.put("contact_info_chatroom", "共同群聊");
        FIELD_LABELS.put("contact_info_linkedin", "领英");
    }

    private static Set<String> hiddenFields = new HashSet<>();
    private static volatile boolean sEnabled = true;
    private static final boolean DEBUG_NOOP = true; // v182c

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;

        hiddenFields.clear();
        String custom = HookConfig.getString("hidden_fields_list", "");
        if (custom != null && !custom.isEmpty()) {
            for (String s : custom.split(",")) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) hiddenFields.add(trimmed);
            }
        }
        if (hiddenFields.isEmpty()) {
            hiddenFields.add("contact_info_mobile");
            hiddenFields.add("contact_info_region");
            hiddenFields.add("contact_info_source");
        }

        hookInitView(cl);
    }

    private static void hookInitView(ClassLoader cl) {
        try {
            Class<?> contactInfoUI = VersionCompat.findContactInfoUIClass(cl);
            if (contactInfoUI == null) {
                LogWriter.log(TAG, "ContactInfoUI class not found");
                return;
            }

            XposedBridge.hookAllMethods(contactInfoUI, "initView",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!DEBUG_NOOP) applyHiddenFields(param.thisObject);
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "applyHiddenFields err: " + t.getClass().getSimpleName());
                    }
                }
            });

            XposedBridge.hookAllMethods(contactInfoUI, "D2",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!DEBUG_NOOP) applyHiddenFields(param.thisObject);
                    } catch (Throwable t) {}
                }
            });

            LogWriter.log(TAG, "hook initView OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook FAIL: " + t.getClass().getSimpleName());
        }
    }

    private static void applyHiddenFields(Object activityObj) {
        try {
            Activity act = (Activity) activityObj;
            View root = act.getWindow().getDecorView();
            if (root == null) return;

            int hidden = hideByLabel(root);
            LogWriter.log(TAG, "hidden " + hidden + " fields");
        } catch (Throwable t) {
            LogWriter.log(TAG, "apply err: " + t.getClass().getSimpleName());
        }
    }

    private static int hideByLabel(View root) {
        int count = 0;
        for (String fieldKey : hiddenFields) {
            String label = FIELD_LABELS.get(fieldKey);
            if (label == null) continue;
            if (hideRowByLabel(root, label)) count++;
        }
        return count;
    }

    private static boolean hideRowByLabel(View root, String labelText) {
        java.util.List<TextView> matching = new java.util.ArrayList<>();
        findTextViewsByText(root, labelText, matching);

        for (TextView tv : matching) {
            ViewGroup row = findRowParent(tv, 4);
            if (row != null) {
                row.setVisibility(View.GONE);
                return true;
            }
        }
        return false;
    }

    private static void findTextViewsByText(View root, String text, java.util.List<TextView> out) {
        if (root instanceof TextView) {
            TextView tv = (TextView) root;
            CharSequence cs = tv.getText();
            if (cs != null && cs.toString().trim().equals(text)) {
                out.add(tv);
                return;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                findTextViewsByText(vg.getChildAt(i), text, out);
            }
        }
    }

    private static ViewGroup findRowParent(View child, int maxDepth) {
        for (int i = 0; i < maxDepth; i++) {
            if (child.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) child.getParent();
                if (parent.getChildCount() == 2 || parent.getChildCount() == 3) {
                    return parent;
                }
                child = parent;
            } else {
                break;
            }
        }
        return null;
    }
}
