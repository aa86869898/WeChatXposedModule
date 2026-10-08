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
        // 全局规范: 分区标题与上方卡片间距 13dp（卡片底部 4dp + 本标题顶部 9dp）
        setPadding((int) (12 * d), (int) (9 * d), (int) (12 * d), (int) (13 * d));

        mTitle = new TextView(ctx);
        mTitle.setText(title);
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, AppColors.TYPE_SECTION_TITLE);
        mTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mTitle.setTextColor(AppColors.textPrimary());
        mTitle.setLetterSpacing(0.01f);
        addView(mTitle);

        mSub = new TextView(ctx);
        mSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
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
