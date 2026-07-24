package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.*;
import com.leshao.v3.model.ModuleConfig;

public class ContactGroupPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;
        SharedPreferences prefs = ContextManager.getPrefs();
        ModuleConfig cfg = ModuleConfig.load(prefs);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());
        root.setPadding((int)(16 * d), (int)(16 * d), (int)(16 * d), (int)(16 * d));

        root.addView(sectionLabel(ctx, "联系人管理"));

        LinearLayout cardContact = makeCard(ctx, d);
        cardContact.addView(switchRow(ctx, d, "通讯录导出", null, cfg.contactExportEnabled, (v, on) -> {
            cfg.contactExportEnabled = on; cfg.save(prefs); ContactExport.setEnabled(on);
        }));
        cardContact.addView(switchRow(ctx, d, "联系人变更日志", null, cfg.contactChangeLogEnabled, (v, on) -> {
            cfg.contactChangeLogEnabled = on; cfg.save(prefs); ContactChangeLog.setEnabled(on);
        }));
        cardContact.addView(switchRow(ctx, d, "隐藏联系人敏感字段", null, cfg.hideContactFieldsEnabled, (v, on) -> {
            cfg.hideContactFieldsEnabled = on; cfg.save(prefs); HideContactFields.setEnabled(on);
        }));
        root.addView(cardContact);

        root.addView(spacerV(ctx, d, 12));
        root.addView(sectionLabel(ctx, "群管理"));

        LinearLayout cardGroup = makeCard(ctx, d);
        cardGroup.addView(switchRow(ctx, d, "群功能增强 (踢人/禁言/群发)", null, cfg.groupFeaturesEnabled, (v, on) -> {
            cfg.groupFeaturesEnabled = on; cfg.save(prefs); GroupFeatures.setEnabled(on);
        }));
        root.addView(cardGroup);

        return root;
    }

    // ===== 组件工厂 (复用 ChatPageView 样式) =====

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

    private static View spacerV(Context ctx, float d, int dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dp * d)));
        return v;
    }
}
