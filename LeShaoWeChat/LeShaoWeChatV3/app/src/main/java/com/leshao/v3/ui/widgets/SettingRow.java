package com.leshao.v3.ui.widgets;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;

/**
 * 统一设置行：emoji 图标 + 标题 + 副标题 + 尾部控件（开关 / 箭头 / 自定义）。
 * 用法：new SettingRow(ctx, "⚙", "标题", "副标题").switchOn(true, listener)
 *      new SettingRow(ctx, "👤", "标题", null).arrow(click)
 */
public class SettingRow extends LinearLayout {

    private final TextView mIcon;
    private final TextView mTitle;
    private final TextView mSub;
    private final LinearLayout mTail;

    public SettingRow(Context ctx, String icon, String title, String sub) {
        super(ctx);
        float d = getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int h = (int) (AppColors.ROW_HEIGHT_DP * d);
        setMinimumHeight(h);
        setPadding((int) (16 * d), (int) (10 * d), (int) (16 * d), (int) (10 * d));
        setClickable(true);
        setFocusable(true);
        try { setBackground(CandyUi.rowPressBg(ctx)); } catch (Throwable ignored) {}

        // v955 M3: 图标容器 40dp 圆角方块(secondaryContainer 底)
        mIcon = new TextView(ctx);
        mIcon.setText(icon != null ? icon : "•");
        mIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        mIcon.setGravity(Gravity.CENTER);
        int iconSize = (int) (40 * d);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setShape(GradientDrawable.RECTANGLE);
        iconBg.setCornerRadius(12 * d);
        iconBg.setColor(AppColors.secondaryContainer());
        mIcon.setBackground(iconBg);
        LayoutParams iconLp = new LayoutParams(iconSize, iconSize);
        iconLp.setMarginEnd((int) (16 * d));
        addView(mIcon, iconLp);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(VERTICAL);
        LayoutParams colLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        addView(textCol, colLp);

        mTitle = new TextView(ctx);
        mTitle.setText(title);
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mTitle.setTypeface(Typeface.DEFAULT);
        mTitle.setTextColor(AppColors.textPrimary());
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(mTitle);

        mSub = new TextView(ctx);
        mSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mSub.setTextColor(AppColors.textTertiary());
        mSub.setSingleLine(true);
        mSub.setEllipsize(TextUtils.TruncateAt.END);
        if (!TextUtils.isEmpty(sub)) {
            mSub.setText(sub);
            mSub.setPadding(0, (int) (2 * d), 0, 0);
            textCol.addView(mSub);
        }

        mTail = new LinearLayout(ctx);
        mTail.setOrientation(HORIZONTAL);
        mTail.setGravity(Gravity.CENTER_VERTICAL);
        addView(mTail, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    /** 尾部开关；checked 初始态，listener 可空 */
    public SettingRow switchOn(boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        try {
            Switch sw = CandyUi.newSwitch(getContext());
            sw.setChecked(checked);
            if (listener != null) sw.setOnCheckedChangeListener(listener);
            mTail.addView(sw);
            // v967 M3 规范: 整行可点 —— 点击行进任意位置切换开关, 修复仅能点中开关
            // 才生效导致的"点按钮没反应"体验问题。
            setOnClickListener(v -> {
                try { sw.toggle(); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
        return this;
    }

    /** 尾部箭头，点击行走点击 */
    public SettingRow arrow(Runnable onClick) {
        try {
            TextView arrow = new TextView(getContext());
            arrow.setText("›");
            arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            arrow.setTextColor(AppColors.arrow());
            mTail.addView(arrow);
            if (onClick != null) setOnClickListener(v -> {
                try { onClick.run(); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
        return this;
    }

    /** 尾部自定义视图 */
    public SettingRow tail(View v) {
        try { mTail.addView(v); } catch (Throwable ignored) {}
        return this;
    }

    public SettingRow setSub(String s) { mSub.setText(s); return this; }

    public SettingRow titleBold(boolean bold) {
        mTitle.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return this;
    }
}
