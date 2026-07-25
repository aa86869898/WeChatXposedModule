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
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.*;
import com.leshao.v3.model.ModuleConfig;

public class ContactGroupPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;
        final SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);
        final Activity act = parentAct;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());
        root.setPadding((int)(16 * d), (int)(16 * d), (int)(16 * d), (int)(16 * d));

        root.addView(sectionLabel(ctx, "联系人管理"));

        LinearLayout cardContact = makeCard(ctx, d);
        cardContact.addView(switchRow(ctx, d, "通讯录导出", null, cfg.contactExportEnabled, (v, on) -> {
            cfg.contactExportEnabled = on; cfg.save(prefs); ContactExport.setEnabled(on);
        }, v -> ContactExport.startCustomExport(act)));
        cardContact.addView(switchRow(ctx, d, "联系人变更日志", null, cfg.contactChangeLogEnabled, (v, on) -> {
            cfg.contactChangeLogEnabled = on; cfg.save(prefs); ContactChangeLog.setEnabled(on);
        }, v -> SubPageActivity.open(act, "通讯录更新日志", 13)));
        cardContact.addView(switchRow(ctx, d, "隐藏联系人敏感字段", null, cfg.hideContactFieldsEnabled, (v, on) -> {
            cfg.hideContactFieldsEnabled = on; cfg.save(prefs); HideContactFields.setEnabled(on);
        }, v -> ConfigPanels.showHideContactFields(act, prefs)));
        root.addView(cardContact);

        root.addView(spacerV(ctx, d, 12));
        root.addView(sectionLabel(ctx, "群管理"));

        LinearLayout cardGroup = makeCard(ctx, d);
        cardGroup.addView(switchRow(ctx, d, "群功能增强",
                "关闭后所有群子功能均不生效", cfg.groupFeaturesEnabled, (v, on) -> {
            cfg.groupFeaturesEnabled = on; cfg.save(prefs); GroupFeatures.setEnabled(on);
        }, null));

        cardGroup.addView(subSwitch(ctx, prefs, d, "group_member_log", "群成员变更日志",
                "记录群内踢人/退群/邀请等操作", true));
        cardGroup.addView(subSwitch(ctx, prefs, d, "group_announce", "群公告已读回执",
                "进入群信息页时自动检测公告更新", true));
        cardGroup.addView(subSwitch(ctx, prefs, d, "group_batch_op", "批量操作",
                "批量踢人 + 导出成员列表", true));
        cardGroup.addView(subSwitch(ctx, prefs, d, "anonymous_chat", "匿名发言",
                "在群聊中以匿名身份发送消息", false,
                v -> ConfigPanels.showAnonymousName(act, prefs)));
        root.addView(cardGroup);

        return root;
    }

    private static LinearLayout subSwitch(Context ctx, SharedPreferences prefs, float d,
                                           String key, String title, String desc, boolean defVal) {
        return subSwitch(ctx, prefs, d, key, title, desc, defVal, null);
    }

    private static LinearLayout subSwitch(Context ctx, SharedPreferences prefs, float d,
                                           String key, String title, String desc, boolean defVal,
                                           View.OnClickListener config) {
        boolean checked = prefs != null ? prefs.getBoolean(key, defVal) : defVal;
        return switchRow(ctx, d, title, desc, checked, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(key, on).apply();
        }, config);
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(2 * d), (int)(2 * d), (int)(2 * d), (int)(2 * d));
        card.setBackgroundColor(AppColors.card());
        return card;
    }

    private static LinearLayout switchRow(Context ctx, float d, String title, String desc,
                                           boolean checked, CompoundButton.OnCheckedChangeListener listener,
                                           View.OnClickListener configListener) {
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

        if (configListener != null) {
            LogWriter.log("ContactGroupPageView", "switchRow [" + title + "] 显示[设置]按钮");
            TextView btn = new TextView(ctx);
            btn.setText("[设置]"); btn.setTextSize(12); btn.setTextColor(0xFF4A90D9);
            btn.setPadding((int)(6 * d), 0, (int)(6 * d), 0);
            btn.setOnClickListener(configListener);
            row.addView(btn);
        }

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
