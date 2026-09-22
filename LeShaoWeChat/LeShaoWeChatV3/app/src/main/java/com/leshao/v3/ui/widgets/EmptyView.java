package com.leshao.v3.ui.widgets;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.ui.AppColors;

/** 空状态占位：大 emoji + 提示文案 + 可选副文案 */
public class EmptyView extends LinearLayout {

    private final TextView mIcon;
    private final TextView mMsg;

    public EmptyView(Context ctx, String icon, String msg) {
        super(ctx);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        float d = getResources().getDisplayMetrics().density;
        setPadding((int) (24 * d), (int) (48 * d), (int) (24 * d), (int) (48 * d));

        mIcon = new TextView(ctx);
        mIcon.setText(icon != null ? icon : "📭");
        mIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 42);        mIcon.setGravity(Gravity.CENTER);
        addView(mIcon);

        mMsg = new TextView(ctx);
        mMsg.setText(msg != null ? msg : "暂无数据");
        mMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mMsg.setTypeface(Typeface.DEFAULT_BOLD);
        mMsg.setTextColor(AppColors.textTertiary());
        mMsg.setGravity(Gravity.CENTER);
        mMsg.setPadding(0, (int) (12 * d), 0, 0);
        addView(mMsg);
    }

    public EmptyView setMsg(String s) { mMsg.setText(s); return this; }
}
