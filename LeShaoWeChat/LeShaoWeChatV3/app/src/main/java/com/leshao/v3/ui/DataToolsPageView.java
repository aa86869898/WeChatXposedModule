package com.leshao.v3.ui;

import android.app.AlertDialog;
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
import com.leshao.v3.hook.*;
import com.leshao.v3.model.ModuleConfig;

public class DataToolsPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;
        SharedPreferences prefs = ContextManager.getPrefs();
        ModuleConfig cfg = ModuleConfig.load(prefs);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());
        root.setPadding((int)(16 * d), (int)(16 * d), (int)(16 * d), (int)(16 * d));

        root.addView(sectionLabel(ctx, "数据与备份"));

        LinearLayout card = makeCard(ctx, d);
        card.addView(switchRow(ctx, d, "消息导出", null, cfg.msgExportEnabled, (v, on) -> {
            cfg.msgExportEnabled = on; cfg.save(prefs); MsgExport.setEnabled(on);
        }));
        card.addView(switchRow(ctx, d, "聊天记录备份", null, cfg.chatBackupEnabled, (v, on) -> {
            cfg.chatBackupEnabled = on; cfg.save(prefs); ChatBackup.setEnabled(on);
        }));
        root.addView(card);

        root.addView(spacer(ctx, d, 8));
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding((int)(2*d), 0, (int)(2*d), 0);
        btnRow.addView(actionButton(ctx, d, "查看记录", 1f, () -> SubPageActivity.open(parentAct, "通讯录更新日志", 13)));
        View gap = new View(ctx);
        gap.setLayoutParams(new LinearLayout.LayoutParams((int)(8*d), -2));
        btnRow.addView(gap);
        btnRow.addView(actionButton(ctx, d, "清除记录", 1f, () -> {
            new AlertDialog.Builder(ctx)
                .setTitle("确认清除")
                .setMessage("确定要清除所有通讯录变更记录吗？")
                .setPositiveButton("清除", (dialog, which) -> ContactChangeLog.clearRecords())
                .setNegativeButton("取消", null)
                .show();
        }));
        root.addView(btnRow);

        return root;
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

    private static View actionButton(Context ctx, float d, String label, float weight, Runnable action) {
        LinearLayout btn = new LinearLayout(ctx);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding((int)(16*d), (int)(12*d), (int)(16*d), (int)(12*d));
        btn.setBackgroundColor(AppColors.accent());
        btn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, weight));
        btn.setClickable(true);
        btn.setOnClickListener(v -> {
            try { action.run(); } catch (Throwable ignored) {}
        });

        TextView tv = new TextView(ctx);
        tv.setText(label); tv.setTextSize(14);
        tv.setTextColor(AppColors.whiteTextOnAccent());
        tv.setGravity(Gravity.CENTER);
        btn.addView(tv);

        return btn;
    }

    private static View spacer(Context ctx, float d, int dpVal) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dpVal * d)));
        return v;
    }

    private static View buttonRow(Context ctx, Activity parentAct, float d, String label, Runnable action) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14*d), (int)(12*d), (int)(14*d), (int)(12*d));
        row.setBackgroundColor(AppColors.whiteCard());

        TextView tv = new TextView(ctx);
        tv.setText(label); tv.setTextSize(15);
        tv.setTextColor(AppColors.accent());
        tv.setTypeface(null, Typeface.BOLD);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView arrow = new TextView(ctx);
        arrow.setText(">"); arrow.setTextSize(16);
        arrow.setTextColor(AppColors.arrow());

        row.addView(tv);
        row.addView(arrow);
        row.setOnClickListener(v -> {
            try { action.run(); } catch (Throwable ignored) {}
        });
        return row;
    }
}
