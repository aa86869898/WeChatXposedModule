package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
        final SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);
        final Activity act = parentAct;
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(8 * d), (int)(16 * d), (int)(8 * d), (int)(16 * d));

        root.addView(sLabel(ctx, d, "朋友圈增强"));

        LinearLayout cardMain = makeCard(ctx, d);
        cardMain.addView(switchRow(ctx, d, "启用朋友圈增强",
                "关闭后所有子功能均不生效", cfg.snsFeaturesEnabled, (v, on) -> {
            cfg.snsFeaturesEnabled = on; cfg.save(prefs); SnsFeatures.setEnabled(on);
        }, null));
        root.addView(cardMain);

        root.addView(candyDivider(ctx, d));
        root.addView(sLabel(ctx, d, "功能开关"));

        LinearLayout cardSub = makeCard(ctx, d);
        cardSub.addView(subSwitch(ctx, d, prefs, "sns_ad_block", "去广告", "隐藏朋友圈中的广告内容", true, null));
        cardSub.addView(subSwitch(ctx, d, prefs, "sns_forward", "转发与复制", "支持转发到聊天和复制文字内容", true, null));
        cardSub.addView(subSwitch(ctx, d, prefs, "sns_simulate_like", "假点赞", "强制点赞结果返回成功", false, null));
        cardSub.addView(subSwitch(ctx, d, prefs, "sns_time_edit", "时间修改", "修改朋友圈发布时间的偏移量", false,
                v -> ConfigPanels.showSnsTimeOffset(act, prefs)));
        cardSub.addView(subSwitch(ctx, d, prefs, "sns_video_quality", "视频画质增强", "解锁朋友圈视频的高画质播放", true, null));
        cardSub.addView(subSwitch(ctx, d, prefs, "sns_long_video", "长视频", "解除朋友圈视频时长限制", true, null));
        root.addView(cardSub);

        ScrollView sv = new ScrollView(ctx);
        sv.addView(root);
        return sv;
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(2 * d), (int)(2 * d), (int)(2 * d), (int)(2 * d));
        card.setBackgroundColor(AppColors.bg());
        return card;
    }

    private static LinearLayout switchRow(Context ctx, float d, String title, String desc,
                                          boolean checked, CompoundButton.OnCheckedChangeListener l,
                                          View.OnClickListener config) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(AppColors.card());

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView tv = new TextView(ctx);
        tv.setText(title); tv.setTextSize(15);
        tv.setTextColor(AppColors.text1()); tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(ctx);
            dv.setText(desc); dv.setTextSize(12); dv.setTextColor(AppColors.text2());
            dv.setPadding(0, (int)(3 * d), 0, 0);
            textCol.addView(dv);
        }

        row.addView(textCol);

        if (config != null) {
            TextView btn = new TextView(ctx);
            btn.setText("[设置]"); btn.setTextSize(12); btn.setTextColor(AppColors.accent());
            btn.setPadding((int)(6 * d), 0, (int)(6 * d), 0);
            btn.setPaintFlags(btn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            btn.setOnClickListener(config);
            row.addView(btn);
        }

        Switch sw = new Switch(ctx); sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        return row;
    }

    private static LinearLayout subSwitch(Context ctx, float d, SharedPreferences prefs,
                                          String key, String title, String desc, boolean defVal,
                                          View.OnClickListener config) {
        boolean checked = prefs != null ? prefs.getBoolean(key, defVal) : defVal;
        return switchRow(ctx, d, title, desc, checked, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(key, on).apply();
        }, config);
    }

    private static TextView sLabel(Context ctx, float d, String t) {
        TextView tv = new TextView(ctx);
        tv.setText(t); tv.setTextSize(13); tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(8 * d));
        return tv;
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
