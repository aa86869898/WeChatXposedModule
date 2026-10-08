package com.leshao.v3.ui.widgets;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.widget.Switch;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;

/**
 * 统一设置行：标题 + 副标题 + 尾部控件（开关 / 箭头 / 自定义）。
 * 用法：new SettingRow(ctx, "⚙", "标题", "副标题").switchOn(true, listener)
 *      new SettingRow(ctx, "👤", "标题", null).arrow(click)
 * 注：v3.0.99 起按需求去掉左侧图标，icon 参数保留仅作兼容，不再渲染。
 */
public class SettingRow extends LinearLayout {

    private final TextView mIcon = null;
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
        setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        setClickable(true);
        setFocusable(true);
        try { setBackground(CandyUi.rowPressBg(ctx)); } catch (Throwable ignored) {}

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(VERTICAL);
        LayoutParams colLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        addView(textCol, colLp);

        mTitle = new TextView(ctx);
        mTitle.setText(title);
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mTitle.setTextColor(AppColors.textPrimary());
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(mTitle);

        mSub = new TextView(ctx);
        mSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
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

    /** 用自绘 Drawable 作为图标(彩色圆底样式)，替换默认 emoji 图标位。 */
    public static SettingRow withIconDrawable(Context ctx, Drawable icon, String title, String sub) {
        SettingRow row = new SettingRow(ctx, "", title, sub);
        row.applyDrawableIcon(icon);
        return row;
    }

    private void applyDrawableIcon(Drawable icon) {
        try {
            if (mIcon == null || icon == null) return;
            float den = getResources().getDisplayMetrics().density;
            int size = (int) (36 * den + 0.5f);
            int end = (int) (12 * den + 0.5f);
            LayoutParams old = (LayoutParams) mIcon.getLayoutParams();
            if (old != null) end = old.getMarginEnd();
            ImageView iv = new ImageView(getContext());
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(icon);
            LayoutParams lp = new LayoutParams(size, size);
            lp.setMarginEnd(end);
            int idx = indexOfChild(mIcon);
            if (idx >= 0) {
                removeView(mIcon);
                addView(iv, idx, lp);
            } else {
                addView(iv, 0, lp);
            }
        } catch (Throwable ignored) {}
    }

    /** 尾部开关；checked 初始态，listener 可空 */
    public SettingRow switchOn(boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        try {
            Switch sw = CandyUi.newSwitch(getContext());
            sw.setChecked(checked);
            if (listener != null) sw.setOnCheckedChangeListener(listener);
            // v968: 固定开关为 52×32dp, 防止父容器把轨道拉伸变形
            float d = getResources().getDisplayMetrics().density;
            int swW = (int) (AppColors.SWITCH_WIDTH_DP * d + 0.5f);
            int swH = (int) (AppColors.SWITCH_HEIGHT_DP * d + 0.5f);
            mTail.addView(sw, new LayoutParams(swW, swH));
            // v967 M3 规范: 整行可点 —— 点击行进任意位置切换开关, 修复仅能点中开关
            // 才生效导致的"点按钮没反应"体验问题。
            setOnClickListener(v -> {
                try { sw.toggle(); } catch (Throwable ignored) {}
            });
        } catch (Throwable t) {
            com.leshao.v3.LogWriter.log("SettingRow",
                    "switchOn err: " + android.util.Log.getStackTraceString(t));
        }
        return this;
    }

    /** 尾部箭头，点击行走点击 */
    public SettingRow arrow(Runnable onClick) {
        try {
            TextView arrow = new TextView(getContext());
            arrow.setText("›");
            arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            arrow.setTextColor(AppColors.arrow());
            mTail.addView(arrow);
            if (onClick != null) setOnClickListener(v -> {
                try {
                    onClick.run();
                } catch (Throwable t) {
                    com.leshao.v3.LogWriter.log("SettingRow",
                            "arrow onClick err: " + android.util.Log.getStackTraceString(t));
                }
            });
        } catch (Throwable ignored) {}
        return this;
    }

    /** 尾部自定义视图 */
    public SettingRow tail(View v) {
        try { mTail.addView(v); } catch (Throwable ignored) {}
        return this;
    }

    /** v974: 用联系人/群真实头像替换图标位(加载失败回退首字母底色块)。 */
    public SettingRow avatar(String username) {
        try {
            if (mIcon == null || TextUtils.isEmpty(username)) return this;
            float d = getResources().getDisplayMetrics().density;
            int size = (int) (36 * d);
            ImageView iv = new ImageView(getContext());
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap fallback = com.leshao.v3.ui.AvatarHelper.letterAvatar(
                    username.substring(0, Math.min(1, username.length())), size);
            com.leshao.v3.ui.AvatarHelper.loadAvatarAsync(iv, username, size, fallback);
            LayoutParams lp = (LayoutParams) mIcon.getLayoutParams();
            if (lp == null) {
                lp = new LayoutParams(size, size);
                lp.setMarginEnd((int) (12 * d));
            }
            int idx = indexOfChild(mIcon);
            if (idx >= 0) {
                removeView(mIcon);
                addView(iv, idx, lp);
            } else {
                addView(iv, 0, lp);
            }
        } catch (Throwable ignored) {}
        return this;
    }

    public SettingRow setSub(String s) { mSub.setText(s); return this; }

    /** v1085: 副标题自定义颜色(已配置名单用绿色) */
    public SettingRow subColor(int color) {
        if (mSub != null) mSub.setTextColor(color);
        return this;
    }

    public SettingRow titleBold(boolean bold) {
        mTitle.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return this;
    }
}
