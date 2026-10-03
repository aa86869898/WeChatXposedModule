package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.ContactPickerDialog;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 微信原生转发按钮替换 —— 把聊天长按菜单「转发」/ 多选左下角「转发」拉起的
 * 原生联系人选择器（MvvmContactListUI / SelectContactUI 等）替换为模块联系人选择器。
 *
 * <p>实现（对应《微信原生转发按钮替换.md》§4.10 方案 A）：hook {@code Activity#onCreate}，
 * 通过 intent extras（list_type / titile / Select_Conv_User / Select_Contact）与
 * 血缘锚点（BaseMvvmListActivity / MMBaseSelectContactUI）识别微信转发选择器，
 * 命中后弹出模块 {@link ContactPickerDialog}；确认后按微信结果契约返回
 * {@code Select_Conv_User}（逗号分隔 wxid）并 finish，从而完成转发按钮替换。
 *
 * <p>单点、跨版本、零混淆依赖：不依赖微信方法名，仅用 Intent 指纹与父类链锚点。</p>
 */
public final class WxForwardReplaceHook {

    public static final String TAG = "WxFwdReplace";
    public static final String K_ENABLED = "ls_wx_forward_replace";

    private static final String[] ANCHORS = {
            "com.tencent.mm.plugin.mvvmlist.BaseMvvmListActivity",
            "com.tencent.mm.ui.contact.MMBaseSelectContactUI"
    };

    private static volatile boolean sEnabled = false;
    private static volatile boolean sHooked = false;

    private WxForwardReplaceHook() {}

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

    public static void hook(ClassLoader cl) {
        try {
            sEnabled = isEnabled();
        } catch (Throwable ignored) {}
        if (sHooked) return;
        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    try {
                        Activity act = (Activity) param.thisObject;
                        if (act == null || act.isFinishing()) return;
                        if (!"com.tencent.mm".equals(act.getPackageName())) return;
                        Intent it = act.getIntent();
                        if (it == null) return;
                        if (!isForwardSelector(act, it)) return;
                        LogWriter.log(TAG, "HIT " + act.getClass().getName()
                                + " list_type=" + it.getIntExtra("list_type", -1));
                        act.getWindow().getDecorView().post(() -> replacePicker(act));
                    } catch (Throwable ignored) {}
                }
            });
            sHooked = true;
            LogWriter.log(TAG, "hooks installed");
        } catch (Throwable t) {
            LogWriter.log(TAG, "install failed: " + t.getMessage());
        }
    }

    /** 指纹：intent extras（文档 §5.1 契约 key）或类名 / 血缘锚点命中即识别为转发选择器。 */
    private static boolean isForwardSelector(Activity act, Intent it) {
        int listType = it.getIntExtra("list_type", -1);
        boolean hasListType = listType == 5 || listType == 14;
        boolean hasTitle = it.hasExtra("titile");
        boolean hasConvKey = it.hasExtra("Select_Conv_User") || it.hasExtra("Select_Contact");
        String cls = act.getClass().getName();
        boolean nameHit = cls.contains("MvvmContactListUI") || cls.contains("SelectContactUI");
        boolean anchorHit = hasAnchor(act.getClass());
        boolean hit = (hasListType && hasTitle) || hasConvKey || nameHit || anchorHit;
        LogWriter.log(TAG, "sig cls=" + cls + " listType=" + listType + " title=" + hasTitle
                + " convKey=" + hasConvKey + " anchor=" + anchorHit + " => " + hit);
        return hit;
    }

    private static boolean hasAnchor(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            String n = k.getName();
            for (String a : ANCHORS) {
                if (a.equals(n)) return true;
            }
        }
        return false;
    }

    /** 用模块联系人选择器覆盖，确认后按微信结果契约返回。 */
    private static void replacePicker(final Activity act) {
        try {
            ContactPickerDialog.show(act, "", ContactPickerDialog.MODE_FRIEND,
                    (wxids, display) -> onConfirmed(act, wxids),
                    () -> onCanceled(act));
        } catch (Throwable t) {
            LogWriter.log(TAG, "replacePicker err: " + t.getMessage());
        }
    }

    private static void onConfirmed(Activity act, Set<String> wxids) {
        try {
            if (wxids == null || wxids.isEmpty()) {
                LogWriter.log(TAG, "empty selection, cancel");
                act.setResult(Activity.RESULT_CANCELED);
                act.finish();
                return;
            }
            Intent data = new Intent();
            String csv = TextUtils.join(",", wxids);
            data.putExtra("Select_Conv_User", csv);
            data.putExtra("Select_Contact", csv);
            act.setResult(Activity.RESULT_OK, data);
            act.finish();
            LogWriter.log(TAG, "confirm users=" + csv);
        } catch (Throwable t) {
            LogWriter.log(TAG, "confirm err: " + t.getMessage());
        }
    }

    private static void onCanceled(Activity act) {
        try {
            LogWriter.log(TAG, "picker canceled");
            act.setResult(Activity.RESULT_CANCELED);
            act.finish();
        } catch (Throwable ignored) {}
    }
}