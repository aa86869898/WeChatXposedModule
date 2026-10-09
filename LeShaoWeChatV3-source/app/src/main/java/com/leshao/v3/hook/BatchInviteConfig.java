package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.AppColors;

/**
 * 「一键拉群」配置读写与配置页。
 *
 * <p>只负责配置持久化与一个自包含的配置弹窗，业务执行见 {@link BatchInviteManager}，
 * 快捷栏入口见 {@link ChatFooterInviteHook}。</p>
 *
 * <p>配置键（存于全局 SharedPreferences，与模块其它功能一致）：</p>
 * <ul>
 *   <li>{@link #K_ENABLED}=ls_batch_invite_enabled，总开关，默认 false。</li>
 *   <li>{@link #K_DELAY_MIN}=ls_batch_invite_delay_min，每个群邀请随机延迟下限（秒），默认 2。</li>
 *   <li>{@link #K_DELAY_MAX}=ls_batch_invite_delay_max，每个群邀请随机延迟上限（秒），默认 5。</li>
 * </ul>
 */
public final class BatchInviteConfig {

    private static final String TAG = "BatchInviteConfig";

    public static final String K_ENABLED = "ls_batch_invite_enabled";
    public static final String K_DELAY_MIN = "ls_batch_invite_delay_min";
    public static final String K_DELAY_MAX = "ls_batch_invite_delay_max";
    public static final String K_REASON = "ls_batch_invite_reason";

    public static final boolean DEFAULT_ENABLED = false;
    public static final int DEFAULT_DELAY_MIN = 2;
    public static final int DEFAULT_DELAY_MAX = 5;
    /** 默认邀请理由：非好友/强校验群留空可能被拒，给一个中性默认值。 */
    public static final String DEFAULT_REASON = "邀请你加入群聊";
    /** 延迟上限的安全封顶（秒），避免误填造成长时间占用。 */
    public static final int MAX_DELAY_SEC = 60;

    private BatchInviteConfig() {}

    // ==================== 读写 ====================

    public static boolean isEnabled() {
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            return sp != null && sp.getBoolean(K_ENABLED, DEFAULT_ENABLED);
        } catch (Throwable t) {
            return DEFAULT_ENABLED;
        }
    }

    public static void setEnabled(boolean on) {
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            if (sp != null) sp.edit().putBoolean(K_ENABLED, on).apply();
        } catch (Throwable ignored) {}
        LogWriter.log(TAG, "setEnabled=" + on);
    }

    public static int getDelayMin() {
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            int v = sp != null ? sp.getInt(K_DELAY_MIN, DEFAULT_DELAY_MIN) : DEFAULT_DELAY_MIN;
            return clamp(v, 0, MAX_DELAY_SEC);
        } catch (Throwable t) {
            return DEFAULT_DELAY_MIN;
        }
    }

    public static int getDelayMax() {
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            int v = sp != null ? sp.getInt(K_DELAY_MAX, DEFAULT_DELAY_MAX) : DEFAULT_DELAY_MAX;
            return clamp(v, 0, MAX_DELAY_SEC);
        } catch (Throwable t) {
            return DEFAULT_DELAY_MAX;
        }
    }

    public static String getReason() {
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            String v = sp != null ? sp.getString(K_REASON, DEFAULT_REASON) : DEFAULT_REASON;
            if (v == null) return DEFAULT_REASON;
            v = v.trim();
            return v.isEmpty() ? DEFAULT_REASON : v;
        } catch (Throwable t) {
            return DEFAULT_REASON;
        }
    }

    public static void setReason(String reason) {
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            if (sp != null) sp.edit().putString(K_REASON, reason == null ? DEFAULT_REASON : reason).apply();
        } catch (Throwable ignored) {}
    }

    public static void setDelayRange(int min, int max) {
        min = clamp(min, 0, MAX_DELAY_SEC);
        max = clamp(max, 0, MAX_DELAY_SEC);
        if (max < min) max = min;
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            if (sp != null) {
                sp.edit().putInt(K_DELAY_MIN, min).putInt(K_DELAY_MAX, max).apply();
            }
        } catch (Throwable ignored) {}
        LogWriter.log(TAG, "setDelayRange=" + min + "~" + max + "s");
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    // ==================== 配置弹窗 ====================

    /** 供主页「设置」入口调用，打开一键拉群配置弹窗。 */
    public static void showConfigDialog(final Activity act) {
        if (act == null || act.isFinishing()) return;
        try {
            buildDialog(act);
        } catch (Throwable t) {
            LogWriter.log(TAG, "showConfigDialog fail: " + t);
        }
    }

    private static void buildDialog(final Activity act) {
        final float d = act.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 20), dp(act, 18), dp(act, 20), dp(act, 16));
        root.setBackground(com.leshao.v3.ui.CandyUi.dialogBg(act));

        TextView title = new TextView(act);
        title.setText("一键拉群");
        title.setTextSize(18);
        title.setTextColor(AppColors.text1());
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView desc = new TextView(act);
        desc.setText("在聊天窗口快捷栏点击「拉群」，选择好友与要拉入的群聊后逐个邀请。");
        desc.setTextSize(12);
        desc.setTextColor(AppColors.text2());
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, dp(act, 6), 0, dp(act, 12));
        root.addView(desc);

        // 随机延迟行
        LinearLayout rowDelay = new LinearLayout(act);
        rowDelay.setOrientation(LinearLayout.HORIZONTAL);
        rowDelay.setGravity(Gravity.CENTER_VERTICAL);
        rowDelay.setPadding(0, dp(act, 4), 0, dp(act, 10));

        TextView tvDelay = new TextView(act);
        tvDelay.setText("每个群邀请的随机延迟");
        tvDelay.setTextSize(14);
        tvDelay.setTextColor(AppColors.text1());
        tvDelay.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        rowDelay.addView(tvDelay);

        final EditText etMin = numInput(act, String.valueOf(getDelayMin()));
        TextView tilde = new TextView(act);
        tilde.setText("~");
        tilde.setTextColor(AppColors.text2());
        tilde.setPadding(dp(act, 6), 0, dp(act, 6), 0);
        final EditText etMax = numInput(act, String.valueOf(getDelayMax()));
        TextView sec = new TextView(act);
        sec.setText("秒");
        sec.setTextSize(13);
        sec.setTextColor(AppColors.text2());
        sec.setPadding(dp(act, 6), 0, 0, 0);

        rowDelay.addView(etMin, new LinearLayout.LayoutParams(dp(act, 52), -2));
        rowDelay.addView(tilde);
        rowDelay.addView(etMax, new LinearLayout.LayoutParams(dp(act, 52), -2));
        rowDelay.addView(sec);
        root.addView(rowDelay);

        // 邀请理由行
        LinearLayout rowReason = new LinearLayout(act);
        rowReason.setOrientation(LinearLayout.HORIZONTAL);
        rowReason.setGravity(Gravity.CENTER_VERTICAL);
        rowReason.setPadding(0, dp(act, 2), 0, dp(act, 10));
        TextView tvReason = new TextView(act);
        tvReason.setText("邀请理由");
        tvReason.setTextSize(14);
        tvReason.setTextColor(AppColors.text1());
        tvReason.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        rowReason.addView(tvReason);
        final EditText etReason = new EditText(act);
        etReason.setInputType(InputType.TYPE_CLASS_TEXT);
        etReason.setText(getReason());
        etReason.setTextSize(13);
        etReason.setTextColor(AppColors.text1());
        etReason.setSingleLine(true);
        etReason.setPadding(dp(act, 8), dp(act, 5), dp(act, 8), dp(act, 5));
        android.graphics.drawable.GradientDrawable rg = new android.graphics.drawable.GradientDrawable();
        rg.setColor(AppColors.inputBg());
        rg.setCornerRadius(dp(act, 10));
        rg.setStroke(dp(act, 1), AppColors.divider());
        etReason.setBackground(rg);
        rowReason.addView(etReason, new LinearLayout.LayoutParams(0, -2, 1.4f));
        root.addView(rowReason);

        TextView note = new TextView(act);
        note.setText("提示：连续邀请易触发微信风控，建议单群单人、保持 2 秒以上间隔。");
        note.setTextSize(11);
        note.setTextColor(AppColors.text2());
        note.setPadding(0, 0, 0, dp(act, 12));
        root.addView(note);

        // 底部按钮
        LinearLayout bottom = new LinearLayout(act);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);

        TextView cancel = pillBtn(act, "取消", AppColors.text2(), false);
        TextView save = pillBtn(act, "保存", AppColors.whiteTextOnAccent(), true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.leftMargin = dp(act, 10);
        blp.rightMargin = dp(act, 10);
        bottom.addView(cancel, blp);
        bottom.addView(save, blp);
        root.addView(bottom);

        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(act)
                .setCancelable(true).create();
        dialog.setView(root);

        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            int min = parseInt(etMin.getText().toString().trim(), getDelayMin());
            int max = parseInt(etMax.getText().toString().trim(), getDelayMax());
            setDelayRange(min, max);
            setReason(etReason.getText().toString().trim());
            android.widget.Toast.makeText(act, "一键拉群配置已保存", android.widget.Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        com.leshao.v3.ui.InsetsUtil.transparentWindow(dialog);
        dialog.show();
        com.leshao.v3.ui.WindowLayer.track(dialog.getWindow());
    }

    // ==================== UI 辅助 ====================

    private static EditText numInput(Activity act, String value) {
        EditText et = new EditText(act);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(value);
        et.setTextSize(13);
        et.setTextColor(AppColors.text1());
        et.setGravity(Gravity.CENTER);
        et.setSingleLine(true);
        et.setPadding(dp(act, 6), dp(act, 5), dp(act, 6), dp(act, 5));
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(AppColors.inputBg());
        gd.setCornerRadius(dp(act, 10));
        gd.setStroke(dp(act, 1), AppColors.divider());
        et.setBackground(gd);
        return et;
    }

    private static TextView pillBtn(Activity act, String text, int color, boolean filled) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(act, 26), dp(act, 8), dp(act, 26), dp(act, 8));
        if (filled) {
            tv.setTextColor(color);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setColor(AppColors.accent());
            gd.setCornerRadius(dp(act, 20));
            tv.setBackground(gd);
        } else {
            tv.setTextColor(color);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setColor(AppColors.inputBg());
            gd.setCornerRadius(dp(act, 20));
            gd.setStroke(dp(act, 1), AppColors.divider());
            tv.setBackground(gd);
        }
        return tv;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
