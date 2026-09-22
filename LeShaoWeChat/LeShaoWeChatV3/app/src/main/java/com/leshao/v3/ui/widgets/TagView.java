package com.leshao.v3.ui.widgets;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;

/** 状态标签：primary / success / warning / danger / info 五色，小圆角 */
public class TagView extends TextView {

    public static final int VARIANT_PRIMARY = 0;
    public static final int VARIANT_SUCCESS = 1;
    public static final int VARIANT_WARNING = 2;
    public static final int VARIANT_DANGER = 3;
    public static final int VARIANT_INFO = 4;

    public TagView(Context ctx, String text, int variant) {
        super(ctx);
        float d = getResources().getDisplayMetrics().density;
        setText(text);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        setTypeface(Typeface.DEFAULT_BOLD);
        setGravity(Gravity.CENTER);
        int padH = (int) (8 * d);
        int padV = (int) (3 * d);
        setPadding(padH, padV, padH, padV);
        setSingleLine(true);
        applyVariant(variant);
    }

    private void applyVariant(int variant) {
        int color;
        switch (variant) {
            case VARIANT_SUCCESS: color = AppColors.success(); break;
            case VARIANT_WARNING: color = AppColors.warning(); break;
            case VARIANT_DANGER: color = AppColors.danger(); break;
            case VARIANT_INFO: color = AppColors.info(); break;
            case VARIANT_PRIMARY:
            default: color = AppColors.primary(); break;
        }
        // 10% 底色 + 纯色文字，浅暗模式下均清晰
        int bg = (color & 0x00FFFFFF) | 0x1A000000;
        try {
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            gd.setCornerRadius(5 * getResources().getDisplayMetrics().density);
            gd.setColor(bg);
            gd.setStroke((int) (0.5f * getResources().getDisplayMetrics().density), color);
            setBackground(gd);
        } catch (Throwable ignored) {}
        setTextColor(color);
    }

    public TagView setTagText(String t) { setText(t); return this; }
}
