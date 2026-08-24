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
import com.leshao.v3.hook.PrivacyFeatures;
import com.leshao.v3.hook.LoginMonitor;
import com.leshao.v3.hook.HideContactFields;
import com.leshao.v3.hook.ConvPrivacy;
import com.leshao.v3.model.ModuleConfig;

public class PrivacyPageView {

    public static View create(Context ctx, Activity parentAct) {
        final SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);
        final Activity act = parentAct;
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(8 * d), (int)(16 * d), (int)(8 * d), (int)(16 * d));

        root.addView(sLabel(ctx, d, "隐私安全"));

        LinearLayout card = makeCard(ctx, d);
        card.addView(switchRow(ctx, d, "隐私保护 (截图检测/剪贴板/WebView/指纹锁定)",
                null, cfg.privacyFeaturesEnabled, (v, on) -> {
            cfg.privacyFeaturesEnabled = on; cfg.save(prefs); PrivacyFeatures.setEnabled(on);
        }, v -> ConfigPanels.showFingerprintLock(act, prefs)));
        card.addView(switchRow(ctx, d, "登录设备监控",
                null, cfg.loginMonitorEnabled, (v, on) -> {
            cfg.loginMonitorEnabled = on; cfg.save(prefs); LoginMonitor.setEnabled(on);
        }, null));
        card.addView(switchRow(ctx, d, "隐藏联系人敏感字段",
                null, cfg.hideContactFieldsEnabled, (v, on) -> {
            cfg.hideContactFieldsEnabled = on; cfg.save(prefs); HideContactFields.setEnabled(on);
        }, v -> ConfigPanels.showHideContactFields(act, prefs)));
        card.addView(switchRow(ctx, d, "会话隐私保护",
                null, cfg.convPrivacyEnabled, (v, on) -> {
            cfg.convPrivacyEnabled = on; cfg.save(prefs); ConvPrivacy.setEnabled(on);
        }, v -> ConfigPanels.showConvPrivacy(act, prefs)));
        root.addView(card);

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

    private static TextView sLabel(Context ctx, float d, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(8 * d));
        return tv;
    }
}
