package com.leshao.v3.ui.widgets;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;
import com.leshao.v3.ui.InsetsUtil;

/**
 * v3.0.128 自定义色板取色器。
 *
 * <p>不依赖任何第三方库：内置一组常用色板 + #RRGGBB 十六进制输入 + 实时预览，
 * 供「气泡内文字颜色」等场景取色。回调 {@code color == 0} 表示"不修改/恢复默认"。</p>
 */
public final class ColorPickerDialog {

    public interface OnPick {
        /** @param color 0 = 不修改(恢复微信原生)。 */
        void onPick(int color);
    }

    /** 内置色板（40 色）。 */
    private static final int[] PALETTE = {
            0xFF000000, 0xFF424242, 0xFF757575, 0xFF9E9E9E, 0xFFBDBDBD, 0xFFE0E0E0, 0xFFFFFFFF, 0xFFF44336,
            0xFFE91E63, 0xFF9C27B0, 0xFF673AB7, 0xFF3F51B5, 0xFF2196F3, 0xFF03A9F4, 0xFF00BCD4, 0xFF009688,
            0xFF4CAF50, 0xFF8BC34A, 0xFFCDDC39, 0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800, 0xFFFF5722, 0xFF795548,
            0xFF607D8B, 0xFFB71C1C, 0xFF880E4F, 0xFF4A148C, 0xFF1A237E, 0xFF0D47A1, 0xFF006064, 0xFF1B5E20,
            0xFF33691E, 0xFF827717, 0xFFE65100, 0xFF3E2723, 0xFF263238, 0xFF00E5FF, 0xFF76FF03, 0xFFFF4081
    };

    private ColorPickerDialog() {}

    public static void show(Context ctx, String title, int initialColor,
                            boolean allowDefault, final OnPick cb) {
        if (ctx == null) return;
        final float d = ctx.getResources().getDisplayMetrics().density;
        int dlgTheme = AppColors.isDarkMode()
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        final AlertDialog dialog = new AlertDialog.Builder(ctx, dlgTheme).create();

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * d);
        root.setPadding(pad, (int) (14 * d), pad, (int) (8 * d));
        root.setBackground(CandyUi.dialogBg(ctx));
        InsetsUtil.clipRounded(root);

        TextView titleTv = new TextView(ctx);
        titleTv.setText(title == null ? "选择颜色" : title);
        titleTv.setTextSize(16);
        titleTv.setTextColor(AppColors.text1());
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setPadding(0, 0, 0, (int) (12 * d));
        root.addView(titleTv);

        // 预览：色块 + hex 文本
        LinearLayout previewRow = new LinearLayout(ctx);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);

        final View swatch = new View(ctx);
        int sw = (int) (34 * d);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(sw, sw);
        swatch.setLayoutParams(swLp);
        previewRow.addView(swatch);

        final TextView hexTv = new TextView(ctx);
        hexTv.setTextSize(15);
        hexTv.setTextColor(AppColors.text1());
        hexTv.setPadding((int) (12 * d), 0, 0, 0);
        previewRow.addView(hexTv);
        root.addView(previewRow);

        root.addView(spacer(ctx, d, 12));

        // 色板网格（每行 8 个）
        final int initial = initialColor == 0 ? 0xFF000000 : initialColor;
        final int[] selected = {initial};
        final EditText hexInput = new EditText(ctx);
        final LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        final int perRow = 8;
        final View[] swatches = new View[PALETTE.length];
        for (int i = 0; i < PALETTE.length; i += perRow) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < perRow && i + j < PALETTE.length; j++) {
                final int color = PALETTE[i + j];
                View cell = new View(ctx);
                int size = (int) (30 * d);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                int gap = (int) (4 * d);
                lp.setMargins(gap, gap, gap, gap);
                cell.setLayoutParams(lp);
                cell.setBackground(swatchBg(color, false, d));
                final int idx = i + j;
                cell.setOnClickListener(v -> {
                    selected[0] = color;
                    // v3.0.132: 点击色板同步 hex 输入框与预览（否则确定时取到 hexInput 旧值）
                    hexTv.setText(toHex(color));
                    hexInput.setText(toHex(color));
                    swatch.setBackground(swatchBg(color, false, d));
                    for (int k = 0; k < swatches.length; k++) {
                        if (swatches[k] != null) swatches[k].setBackground(
                                swatchBg(PALETTE[k], k == idx, d));
                    }
                });
                swatches[idx] = cell;
                row.addView(cell);
            }
            grid.addView(row);
        }
        root.addView(grid);

        root.addView(spacer(ctx, d, 12));

        // 十六进制输入
        hexInput.setText(toHex(initial));
        hexInput.setHint("#RRGGBB");
        hexInput.setTextSize(14);
        hexInput.setSingleLine(true);
        hexInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hexInput.setTextColor(AppColors.text1());
        hexInput.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(AppColors.inputBg());
        inputBg.setCornerRadius((int) (AppColors.SHAPE_INPUT_DP * d));
        inputBg.setStroke((int) (1.5f * d), AppColors.outlineVariant());
        hexInput.setBackground(inputBg);
        root.addView(hexInput);

        root.addView(spacer(ctx, d, 10));

        // 按钮行
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);

        if (allowDefault) {
            TextView def = textBtn(ctx, d, "默认", AppColors.text2(), false);
            def.setOnClickListener(v -> {
                if (cb != null) cb.onPick(0);
                dialog.dismiss();
            });
            btnRow.addView(def);
        }
        View filler = new View(ctx);
        filler.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        btnRow.addView(filler);

        TextView cancel = textBtn(ctx, d, "取消", AppColors.text2(), false);
        cancel.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(cancel);

        TextView confirm = textBtn(ctx, d, "确定", AppColors.accent(), true);
        confirm.setOnClickListener(v -> {
            int c = parseHex(hexInput.getText().toString(), selected[0]);
            if (cb != null) cb.onPick(c);
            dialog.dismiss();
        });
        btnRow.addView(confirm);
        root.addView(btnRow);

        // 输入联动预览 + 色板高亮
        hexInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                Integer c = tryParse(s == null ? null : s.toString());
                if (c == null) return;
                selected[0] = c;
                swatch.setBackground(swatchBg(c, false, d));
                for (int k = 0; k < swatches.length; k++) {
                    if (swatches[k] != null) swatches[k].setBackground(
                            swatchBg(PALETTE[k], PALETTE[k] == c, d));
                }
            }
        });

        swatch.setBackground(swatchBg(initial, false, d));
        for (int k = 0; k < swatches.length; k++) {
            if (swatches[k] != null) swatches[k].setBackground(
                    swatchBg(PALETTE[k], PALETTE[k] == initial, d));
        }

        dialog.setView(root);
        InsetsUtil.transparentWindow(dialog);
        dialog.show();
    }

    private static View spacer(Context ctx, float d, float dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int) (dp * d)));
        return v;
    }

    private static TextView textBtn(Context ctx, float d, String text, int color, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), (int) (8 * d));
        CandyUi.ripple(tv, AppColors.SHAPE_FULL_DP);
        return tv;
    }

    private static GradientDrawable swatchBg(int color, boolean selected, float d) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(color);
        gd.setCornerRadius((int) (7 * d));
        if (selected) {
            gd.setStroke((int) (2.5f * d), AppColors.accent());
        } else {
            gd.setStroke((int) (1 * d), 0x33000000);
        }
        return gd;
    }

    private static String toHex(int color) {
        return String.format("#%06X", 0xFFFFFF & color);
    }

    private static Integer tryParse(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (!t.startsWith("#")) t = "#" + t;
        try {
            return Color.parseColor(t);
        } catch (Throwable t2) {
            return null;
        }
    }

    private static int parseHex(String s, int fallback) {
        Integer c = tryParse(s);
        return c != null ? c : fallback;
    }
}
