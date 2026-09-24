package com.leshao.v3.ui.widgets;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.ui.AppColors;

/** 模块统一顶栏：返回箭头 + 标题 + 右侧动作区 */
public class ModernTopBar extends LinearLayout {

    private final TextView mBack;
    private final TextView mTitle;
    private final LinearLayout mActions;
    public ModernTopBar(Context ctx, String title, boolean showBack, Runnable onBack) {
        super(ctx);
        float d = getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight((int) (AppColors.TOP_BAR_HEIGHT_DP * d));
        setPadding((int) (12 * d), 0, (int) (12 * d), 0);

        mBack = new TextView(ctx);
        mBack.setText("‹");
        mBack.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        mBack.setTextColor(AppColors.primary());
        mBack.setGravity(Gravity.CENTER);
        mBack.setPadding((int) (6 * d), 0, (int) (6 * d), 0);
        mBack.setClickable(true);
        applyRipple(mBack, AppColors.SHAPE_FULL_DP);
        if (showBack) {
            mBack.setOnClickListener(v -> {
                if (onBack != null) {
                    try { onBack.run(); } catch (Throwable ignored) {}
                }
            });
        } else {
            mBack.setVisibility(GONE);
        }
        addView(mBack, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));

        mTitle = new TextView(ctx);
        mTitle.setText(title);
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        mTitle.setTypeface(Typeface.DEFAULT);
        mTitle.setTextColor(AppColors.onSurface());
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // v998: 标题栏标题居中显示
        mTitle.setGravity(Gravity.CENTER);
        LayoutParams titleLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginStart((int) (4 * d));
        addView(mTitle, titleLp);

        mActions = new LinearLayout(ctx);
        mActions.setOrientation(HORIZONTAL);
        mActions.setGravity(Gravity.CENTER_VERTICAL);
        addView(mActions, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    public ModernTopBar addAction(String text, Runnable onClick) {
        try {
            float d = getResources().getDisplayMetrics().density;
            TextView a = new TextView(getContext());
            a.setText(text);
            a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            a.setTextColor(AppColors.primary());
            a.setPadding((int) (10 * d), (int) (6 * d), (int) (10 * d), (int) (6 * d));
            a.setClickable(true);
            applyRipple(a, AppColors.SHAPE_FULL_DP);
            a.setOnClickListener(v -> {
                if (onClick != null) {
                    try { onClick.run(); } catch (Throwable ignored) {}
                }
            });
            mActions.addView(a);
        } catch (Throwable ignored) {}
        return this;
    }

    public void setTitle(String t) { mTitle.setText(t); }

    /** v1033 M3: 给透明底的文字按钮挂全圆角涟漪边界 */
    private static void applyRipple(TextView v, float radiusDp) {
        try {
            float d = v.getResources().getDisplayMetrics().density;
            android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
            mask.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            mask.setCornerRadius(radiusDp * d);
            mask.setColor(0xFFFFFFFF);
            v.setForeground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(AppColors.stateLayerPressed()),
                    null, mask));
        } catch (Throwable ignored) {}
    }
}
