package com.leshao.v3.ui.widgets;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;

/** 分段选择器：替代 spinner/胶囊组，选中项主色底白字，未选中卡片底主色字 */
public class SegmentedControl extends LinearLayout {

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private int mSelected = -1;
    private OnSegmentChangedListener mListener;

    public interface OnSegmentChangedListener {
        void onChanged(int index, String label);
    }

    public SegmentedControl(Context ctx, String[] items, int initial) {
        super(ctx);
        setOrientation(HORIZONTAL);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (3 * d);
        setPadding(pad, pad, pad, pad);
        try {
            GradientDrawable containerBg = new GradientDrawable();
            containerBg.setShape(GradientDrawable.RECTANGLE);
            containerBg.setCornerRadius(dp(ctx, AppColors.SHAPE_FULL_DP));
            containerBg.setColor(0x00000000);
            containerBg.setStroke(dp(ctx, 1), AppColors.outline());
            setBackground(containerBg);
        } catch (Throwable ignored) {}

        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            final String label = items[i];
            TextView seg = new TextView(ctx);
            seg.setText(label);
            seg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            seg.setGravity(Gravity.CENTER);
            seg.setSingleLine(true);
            seg.setClickable(true);
            seg.setFocusable(true);
            LayoutParams lp = new LayoutParams(0, (int) (36 * d), 1f);
            seg.setLayoutParams(lp);
            seg.setOnClickListener(v -> select(idx, true));
            addView(seg);
        }
        if (initial >= 0 && initial < items.length) select(initial, false);
    }

    public void select(int index, boolean notify) {
        if (index < 0 || index >= getChildCount()) return;
        mSelected = index;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            boolean sel = (i == index);
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                tv.setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                tv.setTextColor(sel ? AppColors.textOnPrimary() : AppColors.textSecondary());
            }
            try {
                child.setBackground(sel ? CandyUi.pillBg(true, getContext()) : null);
            } catch (Throwable ignored) {}
        }
        if (notify && mListener != null) {            try {
                String label = "";
                if (getChildAt(index) instanceof TextView) {
                    label = String.valueOf(((TextView) getChildAt(index)).getText());
                }
                mListener.onChanged(index, label);
            } catch (Throwable ignored) {}
        }
    }

    public int getSelectedIndex() { return mSelected; }

    public SegmentedControl setOnSegmentChangedListener(OnSegmentChangedListener l) {
        mListener = l;
        return this;
    }
}
