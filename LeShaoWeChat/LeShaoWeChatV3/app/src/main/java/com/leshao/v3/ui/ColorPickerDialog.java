package com.leshao.v3.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.leshao.v3.ui.widgets.M3Page;
import com.leshao.v3.ui.widgets.ModernButton;

/**
 * v1015: 通用 HSV 取色器（色相 / 饱和度 / 明度 三滑杆 + 实时预览 + 十六进制）。
 * 用于「自定义调色板」以及标题栏 / 开关 / 窗口背景的单元素取色。
 */
public final class ColorPickerDialog {

    public interface OnColorPicked {
        void onPick(int color);
    }

    private ColorPickerDialog() {
    }

    public static void show(Context ctx, String title, int initial, final OnColorPicked onPick) {
        if (ctx == null) return;
        final int dp = (int) ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.dialogBg(ctx));
        InsetsUtil.clipRounded(root);
        root.setPadding(dp * 20, dp * 16, dp * 20, dp * 8);

        if (title != null && !title.isEmpty()) {
            TextView tv = new TextView(ctx);
            tv.setText(title);
            tv.setTextSize(17);
            tv.setTextColor(AppColors.onSurface());
            tv.setTypeface(null, Typeface.BOLD);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, 0, 0, dp * 12);
            root.addView(tv);
        }

        final float[] hsv = new float[3];
        Color.colorToHSV(initial == 0 ? AppColors.primary() : initial, hsv);

        final View preview = new View(ctx);
        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(-1, dp * 56);
        pvLp.setMargins(0, 0, 0, dp * 12);
        preview.setLayoutParams(pvLp);
        root.addView(preview);

        final TextView hex = new TextView(ctx);
        hex.setTextColor(AppColors.onSurfaceVariant());
        hex.setTextSize(13);
        hex.setGravity(Gravity.CENTER);
        hex.setPadding(0, 0, 0, dp * 10);
        root.addView(hex);

        final int[] cur = {Color.HSVToColor(hsv)};
        final Runnable refresh = () -> {
            int c = Color.HSVToColor(hsv);
            cur[0] = c;
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setCornerRadius(dp * AppColors.SHAPE_LG_DP);
            gd.setColor(c);
            gd.setStroke(dp, AppColors.outlineVariant());
            preview.setBackground(gd);
            hex.setText(String.format("#%06X", 0xFFFFFF & c)
                    + "  RGB(" + Color.red(c) + "," + Color.green(c) + "," + Color.blue(c) + ")");
        };

        final String[] names = {"色相 H", "饱和 S", "明度 V"};
        final int[] maxes = {360, 100, 100};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp * 2, 0, dp * 2);

            TextView lab = new TextView(ctx);
            lab.setText(names[i]);
            lab.setTextSize(13);
            lab.setTextColor(AppColors.onSurfaceVariant());
            lab.setLayoutParams(new LinearLayout.LayoutParams(dp * 56, -2));
            row.addView(lab);

            final SeekBar sb = M3Page.slider(ctx);
            sb.setMax(maxes[i]);
            sb.setProgress(idx == 0 ? Math.round(hsv[0]) : Math.round(hsv[idx] * 100f));
            sb.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (idx == 0) hsv[0] = progress; else hsv[idx] = progress / 100f;
                    refresh.run();
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {
                }
                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            row.addView(sb);
            root.addView(row);
        }

        refresh.run();

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp * 14, 0, dp * 4);
        final ModernButton cancel = new ModernButton(ctx, "取消", ModernButton.STYLE_GHOST);
        final ModernButton ok = new ModernButton(ctx, "确定", ModernButton.STYLE_PRIMARY);
        LinearLayout.LayoutParams lpL = new LinearLayout.LayoutParams(0, -2, 1f);
        lpL.setMargins(0, 0, dp * 6, 0);
        LinearLayout.LayoutParams lpR = new LinearLayout.LayoutParams(0, -2, 1f);
        lpR.setMargins(dp * 6, 0, 0, 0);
        cancel.setLayoutParams(lpL);
        ok.setLayoutParams(lpR);
        btns.addView(cancel);
        btns.addView(ok);
        root.addView(btns);

        AlertDialog.Builder b = new AlertDialog.Builder(ctx, AppColors.isDarkMode()
                ? android.R.style.Theme_DeviceDefault_NoActionBar
                : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        b.setView(root);
        final AlertDialog dlg = b.create();
        dlg.setCancelable(true);
        try {
            Window w = dlg.getWindow();
            if (w != null) {
                InsetsUtil.transparentWindow(w);
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
        } catch (Throwable ignored) {}
        InsetsUtil.center(dlg, 0.86f, 0.0f);
        InsetsUtil.clearDialogShell(dlg);
        dlg.show();
        InsetsUtil.clearDialogShell(dlg);

        cancel.onClick(dlg::dismiss);
        ok.onClick(() -> {
            if (onPick != null) onPick.onPick(cur[0]);
            dlg.dismiss();
        });
    }
}
