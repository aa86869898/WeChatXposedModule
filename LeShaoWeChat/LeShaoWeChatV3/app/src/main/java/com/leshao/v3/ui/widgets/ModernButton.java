package com.leshao.v3.ui.widgets;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;

/** 现代化按钮：primary(主) / ghost(描边) / danger(危险) / text(文字) 四型，统一圆角与按压态 */
public class ModernButton extends LinearLayout {

    public static final int STYLE_PRIMARY = 0;
    public static final int STYLE_GHOST = 1;
    public static final int STYLE_DANGER = 2;
    public static final int STYLE_TEXT = 3;

    private final TextView mLabel;
    private Runnable mAction;
    private boolean mEnabled = true;

    public ModernButton(Context ctx, String text, int style) {
        super(ctx);
        float d = getResources().getDisplayMetrics().density;
        setMinimumHeight((int) (AppColors.BUTTON_HEIGHT_DP * d));
        setGravity(Gravity.CENTER);
        setClickable(true);
        setFocusable(true);
        setPadding((int) (16 * d), 0, (int) (16 * d), 0);

        mLabel = new TextView(ctx);
        mLabel.setText(text);
        mLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mLabel.setTypeface(Typeface.DEFAULT);
        mLabel.setGravity(Gravity.CENTER);
        applyStyle(style);
        addView(mLabel, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        setOnClickListener(v -> {
            if (!mEnabled || mAction == null) return;
            try { mAction.run(); } catch (Throwable ignored) {}
        });
    }

    private void applyStyle(int style) {
        switch (style) {
            case STYLE_PRIMARY:
                setBackground(CandyUi.buttonBg(getContext()));
                mLabel.setTextColor(AppColors.onGradient());
                break;
            case STYLE_DANGER:
                setBackground(CandyUi.buttonDangerBg(getContext()));
                mLabel.setTextColor(0xFFFFFFFF);
                break;
            case STYLE_GHOST:
                setBackground(CandyUi.buttonGhostBg(getContext()));
                mLabel.setTextColor(AppColors.primary());
                break;
            case STYLE_TEXT:
            default:
                setBackground(CandyUi.buttonTextBg(getContext()));
                mLabel.setTextColor(AppColors.primary());
                break;
        }
    }

    public ModernButton setText(String t) { mLabel.setText(t); return this; }

    public ModernButton onClick(Runnable r) { mAction = r; return this; }

    @Override
    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
        setAlpha(enabled ? 1f : 0.45f);
        setClickable(enabled);
    }
}
