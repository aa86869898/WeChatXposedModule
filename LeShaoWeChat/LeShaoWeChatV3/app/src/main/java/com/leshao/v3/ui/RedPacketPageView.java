package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.RedPacketAlert;
import com.leshao.v3.model.ModuleConfig;

public class RedPacketPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());

        root.addView(candyDivider(ctx, d));

        root.addView(makeMenuEntry(ctx, d, 0x1F4B0, "自动秒抢红包",
            "配置红包自动领取功能，支持私聊/群聊、时间段过滤、关键词过滤、秒抢名单",
            v -> SubPageActivity.open(parentAct, "自动秒抢红包", 91)));

        root.addView(candyDivider(ctx, d));

        // 红包提醒开关
        SharedPreferences prefs = ContextManager.getPrefs();
        ModuleConfig cfg = ModuleConfig.load(prefs);
        root.addView(candyDivider(ctx, d));
        root.addView(switchRow(ctx, d, "红包震动+响铃提醒", "收到红包时强制震动和响铃",
                cfg.redPacketAlertEnabled, (v, on) -> {
            cfg.redPacketAlertEnabled = on; cfg.save(prefs); RedPacketAlert.setEnabled(on);
        }));

        return root;
    }

    private static View makeMenuEntry(Context ctx, float d, int emoji, String title,
                                       String desc, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(18 * d), (int)(14 * d), (int)(18 * d), (int)(14 * d));
        row.setBackgroundColor(AppColors.whiteCard());
        row.setOnClickListener(onClick);

        TextView icon = new TextView(ctx);
        icon.setText(new String(Character.toChars(emoji)));
        icon.setTextSize(22);
        icon.setPadding(0, 0, (int)(14 * d), 0);
        row.addView(icon);

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

        return row;
    }

    private static View spacerV(Context ctx, float d, int dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dp * d)));
        return v;
    }

    private static LinearLayout switchRow(Context ctx, float d, String title, String desc,
                                           boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(18 * d), (int)(14 * d), (int)(18 * d), (int)(14 * d));
        row.setBackgroundColor(AppColors.whiteCard());

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView tv = new TextView(ctx);
        tv.setText(title); tv.setTextSize(15);
        tv.setTextColor(AppColors.text1()); tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(ctx);
            dv.setText(desc); dv.setTextSize(12);
            dv.setTextColor(AppColors.text2());
            dv.setPadding(0, (int)(3 * d), 0, 0);
            textCol.addView(dv);
        }
        row.addView(textCol);

        Switch sw = CandyUi.newSwitch(ctx); sw.setChecked(checked);
        try { sw.setThumbResource(android.R.drawable.btn_star_big_on); } catch (Throwable ignored) {}
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        return row;
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
