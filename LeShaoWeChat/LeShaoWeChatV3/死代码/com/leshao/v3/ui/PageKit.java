package com.leshao.v3.ui;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.leshao.v3.ui.widgets.M3Page;

/** 新增功能页共用的卡片/开关/分隔线构建器（沿用手工页的糖果风格）。 */
public final class PageKit {

    private PageKit() {}

    public static LinearLayout pageRoot(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());
        InsetsUtil.clipRounded(root);
        // 全局规范: 页面左右边距 12dp，底部留白 12dp，避免页面根圆角区裁切底部按钮/圆角描边
        root.setPadding((int) (AppColors.SPACE_MD_DP * d), (int) (6 * d),
                (int) (AppColors.SPACE_MD_DP * d), (int) (12 * d));
        return root;
    }

    public static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int) (0 * d), (int) (0 * d), (int) (0 * d), (int) (0 * d));
        card.setBackground(CandyUi.cardBg(ctx));
        InsetsUtil.clipRounded(card);
        return card;
    }

    public static View divider(Context ctx) {
        return M3Page.divider(ctx);
    }

    public static void addDivider(Context ctx, LinearLayout root) {
        root.addView(divider(ctx));
    }

    public static LinearLayout switchRow(Context ctx, float d, String title, String desc,
                                         boolean checked,
                                         CompoundButton.OnCheckedChangeListener listener,
                                         View.OnClickListener configListener) {
        return switchRow(ctx, d, title, desc, checked, listener, configListener, null);
    }

    public static LinearLayout switchRow(Context ctx, float d, String title, String desc,
                                         boolean checked,
                                         CompoundButton.OnCheckedChangeListener listener,
                                         View.OnClickListener configListener,
                                         Switch[] swHolder) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // 全局规范: 行触控区域不低于 48dp
        row.setMinimumHeight((int)(48 * d));
        row.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        row.setBackground(CandyUi.cardBg(ctx));
        InsetsUtil.clipRounded(row);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(16);
        tv.setTextColor(AppColors.text1());
        tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(ctx);
            dv.setText(desc);
            dv.setTextSize(12);
            dv.setTextColor(AppColors.text2());
            dv.setPadding(0, (int) (3 * d), 0, 0);
            textCol.addView(dv);
        }
        row.addView(textCol);

        if (configListener != null) {
            TextView btn = new TextView(ctx);
            btn.setText("[设置]");
            btn.setTextSize(12);
            btn.setTextColor(AppColors.accent());
            btn.setPadding((int) (6 * d), 0, (int) (6 * d), 0);
            btn.setPaintFlags(btn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            CandyUi.ripple(btn, AppColors.SHAPE_FULL_DP);
            btn.setOnClickListener(configListener);
            row.addView(btn);
        }

        Switch sw = CandyUi.newSwitch(ctx);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        if (swHolder != null && swHolder.length > 0) swHolder[0] = sw;
        return row;
    }

    public static TextView sectionLabel(Context ctx, String text) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int) (8 * d));
        return tv;
    }

    public static TextView actionButton(Context ctx, String text, View.OnClickListener l) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setMinimumHeight((int) (48 * d));
        tv.setTextColor(AppColors.text1());
        tv.setPadding((int) (10 * d), (int) (10 * d), (int) (10 * d), (int) (10 * d));
        tv.setBackground(CandyUi.rowBg(ctx));
        InsetsUtil.clipRounded(tv);
        CandyUi.ripple(tv, AppColors.SHAPE_FULL_DP);
        if (l != null) tv.setOnClickListener(l);
        return tv;
    }

    public static TextView bodyText(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        float d = ctx.getResources().getDisplayMetrics().density;
        tv.setPadding((int) (4 * d), (int) (4 * d), (int) (4 * d), (int) (4 * d));
        return tv;
    }
}
