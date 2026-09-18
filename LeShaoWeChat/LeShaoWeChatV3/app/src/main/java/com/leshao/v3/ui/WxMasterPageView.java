package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.leshao.v3.hook.WxMasterFeatures;

public class WxMasterPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;
        ClassLoader cl = parentAct.getClassLoader();

        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());
        root.setPadding((int)(8 * d), (int)(16 * d), (int)(8 * d), (int)(16 * d));

        root.addView(headerBanner(ctx, d,
                "乐少万群定时群发",
                "勾选多个群 + 输入内容 + 可选定时，一键群发"));

        root.addView(sectionLabel(ctx, "📨 群发"));
        LinearLayout card1 = makeCard(ctx, d);
        card1.addView(buttonRow(ctx, d, "乐少万群定时群发", "勾选多个群+定时发送", () -> WxMasterFeatures.batchSend(parentAct, cl)));
        root.addView(card1);

        root.addView(candyDivider(ctx, d));

        TextView tip = new TextView(ctx);
        tip.setText("提示：\n· 点击后从联系人中选择多个群聊\n· 可立即发送，也可设置定时时间");
        tip.setTextSize(12);
        tip.setTextColor(AppColors.text2());
        tip.setPadding((int)(4 * d), 0, (int)(4 * d), 0);
        root.addView(tip);

        sv.addView(root);
        return sv;
    }

    private static View headerBanner(Context ctx, float d, String title, String desc) {
        LinearLayout banner = new LinearLayout(ctx);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding((int)(14 * d), (int)(14 * d), (int)(14 * d), (int)(14 * d));
        banner.setBackgroundColor(AppColors.card());

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(16);
        tv.setTextColor(AppColors.text1());
        tv.setTypeface(null, Typeface.BOLD);
        banner.addView(tv);

        TextView dv = new TextView(ctx);
        dv.setText(desc);
        dv.setTextSize(12);
        dv.setTextColor(AppColors.text2());
        dv.setPadding(0, (int)(6 * d), 0, 0);
        banner.addView(dv);

        return banner;
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(2 * d), (int)(2 * d), (int)(2 * d), (int)(2 * d));
        card.setBackgroundColor(AppColors.card());
        return card;
    }

    private static View buttonRow(Context ctx, float d, String title, String desc, Runnable action) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(AppColors.whiteCard());

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(AppColors.text1());
        tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(ctx);
            dv.setText(desc);
            dv.setTextSize(12);
            dv.setTextColor(AppColors.text2());
            dv.setPadding(0, (int)(3 * d), 0, 0);
            textCol.addView(dv);
        }
        row.addView(textCol);

        TextView arrow = new TextView(ctx);
        arrow.setText(">");
        arrow.setTextSize(16);
        arrow.setTextColor(AppColors.arrow());
        row.addView(arrow);

        row.setOnClickListener(v -> {
            try { action.run(); } catch (Throwable ignored) {}
        });
        return row;
    }

    private static TextView sectionLabel(Context ctx, String text) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(8 * d));
        return tv;
    }

    private static View divider(Context ctx, float d) {
        View div = new View(ctx);
        div.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        div.setBackgroundColor(AppColors.divider());
        return div;
    }

    private static View spacer(Context ctx, float d, int dpVal) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dpVal * d)));
        return v;
    }

    private static View candyDivider(Context ctx, float d) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{AppColors.candyPink(), AppColors.candyYellow(), AppColors.accent(), AppColors.candyPink()});
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int)(1.5f * d));
        lp.setMargins((int)(12 * d), (int)(6 * d), (int)(12 * d), (int)(6 * d));
        v.setLayoutParams(lp);
        v.setBackground(gd);
        return v;
    }
}
