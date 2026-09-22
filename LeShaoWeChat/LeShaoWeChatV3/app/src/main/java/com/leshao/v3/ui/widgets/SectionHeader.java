package com.leshao.v3.ui.widgets;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.ui.AppColors;

/** 分组标题：粗体主标题 + 可选灰色副标题 */
public class SectionHeader extends LinearLayout {

    private final TextView mTitle;
    private final TextView mSub;

    public SectionHeader(Context ctx, String title) {
        this(ctx, title, null);
    }

    public SectionHeader(Context ctx, String title, String sub) {
        super(ctx);
        setOrientation(VERTICAL);
        float d = getResources().getDisplayMetrics().density;
        setPadding((int) (4 * d), (int) (14 * d), (int) (4 * d), (int) (6 * d));

        mTitle = new TextView(ctx);
        mTitle.setText(title);
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mTitle.setTypeface(Typeface.DEFAULT);
        mTitle.setTextColor(AppColors.textPrimary());
        addView(mTitle);

        mSub = new TextView(ctx);
        mSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mSub.setTextColor(AppColors.textTertiary());
        mSub.setGravity(Gravity.START);
        if (sub != null && !sub.isEmpty()) {
            mSub.setText(sub);
            mSub.setPadding(0, (int) (2 * d), 0, 0);
            addView(mSub);
        }
    }

    public SectionHeader setSub(String s) { mSub.setText(s); return this; }
}
