package com.leshao.v3.hook;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;

/**
 * 「自动扫码进群」配置读写与配置弹窗。
 *
 * <p>只负责配置持久化与自包含配置弹窗，业务执行见 {@link AutoGroupQrHook}。
 * 配置存于全局 SharedPreferences（与模块其它功能一致）。</p>
 *
 * <p>配置键：</p>
 * <ul>
 *   <li>{@link #K_ENABLED}=gqr_enabled，总开关，默认 false。</li>
 *   <li>{@link #K_MAX_PER_DAY}=gqr_max_per_day，每日入群上限，默认 5（手册 11.2 风控建议）。</li>
 *   <li>{@link #K_DELAY_MAX}=gqr_delay_max，触发入群前的随机延迟上限（秒），默认 15（3~15s）。</li>
 *   <li>{@link #K_AUTO_APPLY}=gqr_auto_apply，需验证群自动点「提交申请」，默认 true。</li>
 *   <li>{@link #K_WHITELIST}=gqr_whitelist，白名单群 talker（逗号分隔），空 = 全部群。</li>
 * </ul>
 */
public final class AutoGroupQrConfig {

    private static final String TAG = "AutoGroupQrConfig";

    public static final String K_ENABLED = "gqr_enabled";
    public static final String K_MAX_PER_DAY = "gqr_max_per_day";
    public static final String K_DELAY_MAX = "gqr_delay_max";
    public static final String K_AUTO_APPLY = "gqr_auto_apply";
    public static final String K_WHITELIST = "gqr_whitelist";

    public static final boolean DEFAULT_ENABLED = false;
    public static final int DEFAULT_MAX_PER_DAY = 5;
    public static final int DEFAULT_DELAY_MAX = 15;
    public static final boolean DEFAULT_AUTO_APPLY = true;
    public static final String DEFAULT_WHITELIST = "";

    public static final int MAX_PER_DAY_LIMIT = 100;
    public static final int MAX_DELAY_SEC = 120;

    private AutoGroupQrConfig() {}

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

    public static int getMaxPerDay() {
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            int v = sp != null ? sp.getInt(K_MAX_PER_DAY, DEFAULT_MAX_PER_DAY) : DEFAULT_MAX_PER_DAY;
            return clamp(v, 1, MAX_PER_DAY_LIMIT);
        } catch (Throwable t) {
            return DEFAULT_MAX_PER_DAY;
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

    public static boolean isAutoApply() {
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            return sp == null || sp.getBoolean(K_AUTO_APPLY, DEFAULT_AUTO_APPLY);
        } catch (Throwable t) {
            return DEFAULT_AUTO_APPLY;
        }
    }

    public static String getWhitelist() {
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            String v = sp != null ? sp.getString(K_WHITELIST, DEFAULT_WHITELIST) : DEFAULT_WHITELIST;
            return v == null ? DEFAULT_WHITELIST : v.trim();
        } catch (Throwable t) {
            return DEFAULT_WHITELIST;
        }
    }

    public static void setMaxPerDay(int v) {
        v = clamp(v, 1, MAX_PER_DAY_LIMIT);
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            if (sp != null) sp.edit().putInt(K_MAX_PER_DAY, v).apply();
        } catch (Throwable ignored) {}
        LogWriter.log(TAG, "setMaxPerDay=" + v);
    }

    public static void setDelayMax(int v) {
        v = clamp(v, 0, MAX_DELAY_SEC);
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            if (sp != null) sp.edit().putInt(K_DELAY_MAX, v).apply();
        } catch (Throwable ignored) {}
        LogWriter.log(TAG, "setDelayMax=" + v);
    }

    public static void setAutoApply(boolean on) {
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            if (sp != null) sp.edit().putBoolean(K_AUTO_APPLY, on).apply();
        } catch (Throwable ignored) {}
        LogWriter.log(TAG, "setAutoApply=" + on);
    }

    public static void setWhitelist(String wl) {
        wl = wl == null ? DEFAULT_WHITELIST : wl.trim();
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            if (sp != null) sp.edit().putString(K_WHITELIST, wl).apply();
        } catch (Throwable ignored) {}
        LogWriter.log(TAG, "setWhitelist=" + wl);
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    // ==================== 配置弹窗 ====================

    /** 供主页「联系人和群聊」入口调用，打开自动扫码进群配置弹窗。 */
    public static void showConfigDialog(final Activity act) {
        if (act == null || act.isFinishing()) return;
        try {
            buildDialog(act);
        } catch (Throwable t) {
            LogWriter.log(TAG, "showConfigDialog fail: " + t);
        }
    }

    private static int dp(Activity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static EditText numInput(Activity act, String text) {
        EditText et = new EditText(act);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(text);
        et.setTextSize(13);
        et.setTextColor(AppColors.text1());
        et.setSingleLine(true);
        et.setGravity(Gravity.CENTER);
        et.setPadding(dp(act, 6), dp(act, 4), dp(act, 6), dp(act, 4));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(AppColors.inputBg());
        g.setCornerRadius(dp(act, 8));
        g.setStroke(dp(act, 1), AppColors.divider());
        et.setBackground(g);
        return et;
    }

    private static void buildDialog(final Activity act) {
        final float d = act.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 20), dp(act, 18), dp(act, 20), dp(act, 16));
        root.setBackground(CandyUi.dialogBg(act));

        TextView title = new TextView(act);
        title.setText("自动扫码进群");
        title.setTextSize(18);
        title.setTextColor(AppColors.text1());
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView desc = new TextView(act);
        desc.setText("后台监听群聊图片，识别到群二维码后自动走微信 A8Key 入群流程，并自动点「加入群聊」。");
        desc.setTextSize(12);
        desc.setTextColor(AppColors.text2());
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, dp(act, 6), 0, dp(act, 12));
        root.addView(desc);

        // 总开关
        LinearLayout rowEnable = new LinearLayout(act);
        rowEnable.setOrientation(LinearLayout.HORIZONTAL);
        rowEnable.setGravity(Gravity.CENTER_VERTICAL);
        rowEnable.setPadding(0, dp(act, 2), 0, dp(act, 12));
        TextView tvEnable = new TextView(act);
        tvEnable.setText("启用自动扫码进群");
        tvEnable.setTextSize(14);
        tvEnable.setTextColor(AppColors.text1());
        tvEnable.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        rowEnable.addView(tvEnable);
        final android.widget.Switch swEnable = CandyUi.newSwitch(act);
        swEnable.setChecked(isEnabled());
        rowEnable.addView(swEnable);
        root.addView(rowEnable);

        // 每日入群上限
        LinearLayout rowMax = new LinearLayout(act);
        rowMax.setOrientation(LinearLayout.HORIZONTAL);
        rowMax.setGravity(Gravity.CENTER_VERTICAL);
        rowMax.setPadding(0, dp(act, 4), 0, dp(act, 10));
        TextView tvMax = new TextView(act);
        tvMax.setText("每日入群上限");
        tvMax.setTextSize(14);
        tvMax.setTextColor(AppColors.text1());
        tvMax.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        rowMax.addView(tvMax);
        final EditText etMax = numInput(act, String.valueOf(getMaxPerDay()));
        rowMax.addView(etMax, new LinearLayout.LayoutParams(dp(act, 64), -2));
        root.addView(rowMax);

        // 随机延迟
        LinearLayout rowDelay = new LinearLayout(act);
        rowDelay.setOrientation(LinearLayout.HORIZONTAL);
        rowDelay.setGravity(Gravity.CENTER_VERTICAL);
        rowDelay.setPadding(0, dp(act, 2), 0, dp(act, 10));
        TextView tvDelay = new TextView(act);
        tvDelay.setText("触发前随机延迟");
        tvDelay.setTextSize(14);
        tvDelay.setTextColor(AppColors.text1());
        tvDelay.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        rowDelay.addView(tvDelay);
        final EditText etDelay = numInput(act, String.valueOf(getDelayMax()));
        rowDelay.addView(etDelay, new LinearLayout.LayoutParams(dp(act, 64), -2));
        TextView sec = new TextView(act);
        sec.setText("秒(0~120)");
        sec.setTextSize(12);
        sec.setTextColor(AppColors.text2());
        sec.setPadding(dp(act, 6), 0, 0, 0);
        rowDelay.addView(sec);
        root.addView(rowDelay);

        // 自动提交申请
        LinearLayout rowApply = new LinearLayout(act);
        rowApply.setOrientation(LinearLayout.HORIZONTAL);
        rowApply.setGravity(Gravity.CENTER_VERTICAL);
        rowApply.setPadding(0, dp(act, 2), 0, dp(act, 10));
        TextView tvApply = new TextView(act);
        tvApply.setText("需验证群自动提交申请");
        tvApply.setTextSize(14);
        tvApply.setTextColor(AppColors.text1());
        tvApply.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        rowApply.addView(tvApply);
        final android.widget.Switch swApply = CandyUi.newSwitch(act);
        swApply.setChecked(isAutoApply());
        rowApply.addView(swApply);
        root.addView(rowApply);

        // 白名单群
        TextView tvWl = new TextView(act);
        tvWl.setText("白名单群（逗号分隔，空=全部）");
        tvWl.setTextSize(13);
        tvWl.setTextColor(AppColors.text2());
        tvWl.setPadding(0, dp(act, 2), 0, dp(act, 4));
        root.addView(tvWl);
        final EditText etWl = new EditText(act);
        etWl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etWl.setMinLines(2);
        etWl.setMaxLines(4);
        etWl.setText(getWhitelist());
        etWl.setTextSize(13);
        etWl.setTextColor(AppColors.text1());
        etWl.setPadding(dp(act, 8), dp(act, 5), dp(act, 8), dp(act, 5));
        android.graphics.drawable.GradientDrawable wg = new android.graphics.drawable.GradientDrawable();
        wg.setColor(AppColors.inputBg());
        wg.setCornerRadius(dp(act, 10));
        wg.setStroke(dp(act, 1), AppColors.divider());
        etWl.setBackground(wg);
        root.addView(etWl, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(act);
        note.setText("提示：高频自动加群有封号风险，建议每日上限 ≤5、延迟 3~15 秒。同一群码只处理一次。");
        note.setTextSize(11);
        note.setTextColor(AppColors.text2());
        note.setPadding(0, dp(act, 10), 0, dp(act, 8));
        root.addView(note);

        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        TextView cancel = new TextView(act);
        cancel.setText("取消");
        cancel.setTextSize(15);
        cancel.setTextColor(AppColors.text2());
        cancel.setPadding(dp(act, 16), dp(act, 8), dp(act, 16), dp(act, 8));
        btns.addView(cancel);
        TextView save = new TextView(act);
        save.setText("保存");
        save.setTextSize(15);
        save.setTextColor(AppColors.accent());
        save.setTypeface(null, Typeface.BOLD);
        save.setPadding(dp(act, 16), dp(act, 8), dp(act, 16), dp(act, 8));
        btns.addView(save);
        root.addView(btns);

        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(act)
                .setView(root)
                .create();
        dialog.setCanceledOnTouchOutside(true);

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
            }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    int max = Integer.parseInt(etMax.getText().toString().trim());
                    setMaxPerDay(max);
                } catch (Throwable ignored) {}
                try {
                    int dl = Integer.parseInt(etDelay.getText().toString().trim());
                    setDelayMax(dl);
                } catch (Throwable ignored) {}
                setAutoApply(swApply.isChecked());
                setEnabled(swEnable.isChecked());
                setWhitelist(etWl.getText().toString());
                AutoGroupQrHook.updateConfig();
                Toast.makeText(act, "配置已保存", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            }
        });

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                com.leshao.v3.ui.UiBackStack.remove(dialog);
            }
        });
        com.leshao.v3.ui.UiBackStack.push(dialog, null);
        dialog.show();
    }
}
