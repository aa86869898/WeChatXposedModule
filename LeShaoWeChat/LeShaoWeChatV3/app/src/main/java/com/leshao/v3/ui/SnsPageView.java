package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.SnsFeatures;
import com.leshao.v3.model.ModuleConfig;

public class SnsPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;
        SharedPreferences prefs = ContextManager.getPrefs();
        ModuleConfig cfg = ModuleConfig.load(prefs);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());
        root.setPadding((int)(16 * d), (int)(16 * d), (int)(16 * d), (int)(16 * d));

        root.addView(sectionLabel(ctx, "朋友圈功能"));

        LinearLayout card1 = makeCard(ctx, d);
        card1.addView(switchRow(ctx, d, "朋友圈增强", "总开关:去广告/转发/假点赞/时间修改/视频画质/长视频",
                cfg.snsFeaturesEnabled, (v, on) -> {
            cfg.snsFeaturesEnabled = on; cfg.save(prefs); SnsFeatures.setEnabled(on);
        }));
        root.addView(card1);

        root.addView(spacer(ctx, d, 8));
        root.addView(sectionLabel(ctx, "子功能开关"));

        LinearLayout card2 = makeCard(ctx, d);
        card2.addView(switchRow(ctx, d, "去广告",
                "屏蔽朋友圈广告内容",
                prefs.getBoolean("sns_ad_block", true), (v, on) -> {
            prefs.edit().putBoolean("sns_ad_block", on).apply();
        }));
        card2.addView(switchRow(ctx, d, "转发与复制",
                "长按朋友圈内容支持转发/复制",
                prefs.getBoolean("sns_forward", true), (v, on) -> {
            prefs.edit().putBoolean("sns_forward", on).apply();
        }));
        card2.addView(switchRow(ctx, d, "假点赞",
                "标记所有朋友圈为已点赞",
                prefs.getBoolean("sns_fake_like", false), (v, on) -> {
            prefs.edit().putBoolean("sns_fake_like", on).apply();
        }));
        card2.addView(switchRow(ctx, d, "时间修改",
                "修改朋友圈发布时间显示",
                prefs.getBoolean("sns_time_edit", false), (v, on) -> {
            prefs.edit().putBoolean("sns_time_edit", on).apply();
        }));
        card2.addView(switchRow(ctx, d, "视频画质增强",
                "提升朋友圈视频上传画质",
                prefs.getBoolean("sns_video_quality", true), (v, on) -> {
            prefs.edit().putBoolean("sns_video_quality", on).apply();
        }));
        card2.addView(switchRow(ctx, d, "长视频",
                "突破朋友圈视频时长限制",
                prefs.getBoolean("sns_long_video", true), (v, on) -> {
            prefs.edit().putBoolean("sns_long_video", on).apply();
        }));
        root.addView(card2);

        ScrollView sv = new ScrollView(ctx);
        sv.addView(root);
        return sv;
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(2 * d), (int)(2 * d), (int)(2 * d), (int)(2 * d));
        card.setBackgroundColor(AppColors.card());
        return card;
    }

    private static LinearLayout switchRow(Context ctx, float d, String title, String desc,
                                           boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
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

        Switch sw = new Switch(ctx); sw.setChecked(checked);
        try { if (checked) sw.setThumbResource(android.R.drawable.btn_star_big_on); } catch (Throwable ignored) {}
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        return row;
    }

    private static TextView sectionLabel(Context ctx, String text) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        tv.setText(text); tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(8 * d));
        return tv;
    }

    private static View spacer(Context ctx, float d, int dpVal) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dpVal * d)));
        return v;
    }
}
