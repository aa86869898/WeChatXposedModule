package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.hook.BatchAddRecordStore;
import com.leshao.v3.ui.widgets.M3Page;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 批量加好友「添加记录」页：顶部 全部/成功/失败 三个切换按钮 + 清空按钮 + 记录列表。
 * 记录由 BatchAddRecordStore 持久化，重启微信不丢失。
 */
public class BatchAddRecordPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(12 * d), (int)(12 * d), (int)(12 * d), (int)(16 * d));

        final LinearLayout listArea = new LinearLayout(ctx);
        listArea.setOrientation(LinearLayout.VERTICAL);

        final int[] currentTab = {0}; // 0 全部 1 成功 2 失败

        LinearLayout tabBar = new LinearLayout(ctx);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER);
        tabBar.setBackground(CandyUi.cardBg(ctx));
        InsetsUtil.clipRounded(tabBar);
        tabBar.setPadding((int)(4 * d), (int)(4 * d), (int)(4 * d), (int)(4 * d));
        GradientDrawable tabBg = new GradientDrawable();
        tabBg.setColor(AppColors.card());
        tabBg.setCornerRadius((int)(8 * d));
        tabBar.setBackground(tabBg);

        String[] tabs = {"全部", "成功", "失败"};

        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            TextView tab = new TextView(ctx);
            tab.setText(tabs[i]);
            tab.setTextSize(13);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding((int)(8 * d), (int)(8 * d), (int)(8 * d), (int)(8 * d));
            tab.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            tab.setOnClickListener(v -> {
                currentTab[0] = idx;
                for (int j = 0; j < tabBar.getChildCount(); j++) {
                    TextView child = (TextView) tabBar.getChildAt(j);
                    child.setTextColor(j == idx ? AppColors.accent() : AppColors.text2());
                    child.setTypeface(null, j == idx ? Typeface.BOLD : Typeface.NORMAL);
                }
                rebuildList(ctx, parentAct, d, listArea, currentTab[0]);
            });
            tab.setTextColor(i == 0 ? AppColors.accent() : AppColors.text2());
            tab.setTypeface(null, i == 0 ? Typeface.BOLD : Typeface.NORMAL);
            tabBar.addView(tab);
        }
        root.addView(tabBar);

        root.addView(candyDivider(ctx, d));

        // 清空按钮行
        LinearLayout actionRow = new LinearLayout(ctx);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setPadding((int)(14 * d), (int)(6 * d), (int)(14 * d), (int)(6 * d));

        TextView countTv = new TextView(ctx);
        countTv.setTextSize(12);
        countTv.setTextColor(AppColors.text2());
        countTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        actionRow.addView(countTv);

        TextView clearBtn = new TextView(ctx);
        clearBtn.setText("清空记录");
        clearBtn.setTextSize(12);
        clearBtn.setTextColor(0xFFE53935);
        clearBtn.setPadding((int)(10 * d), (int)(6 * d), (int)(10 * d), (int)(6 * d));
        clearBtn.setOnClickListener(v -> {
            AlertDialog dlgClear = new AlertDialog.Builder(ctx)
                .setTitle("清空记录")
                .setMessage("确定要清空全部添加记录吗？此操作不可恢复。")
                .setPositiveButton("清空", (dlg, w) -> {
                    BatchAddRecordStore.clear();
                    rebuildList(ctx, parentAct, d, listArea, currentTab[0]);
                    Toast.makeText(ctx, "记录已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .create();
            dlgClear.show();
        });
        actionRow.addView(clearBtn);
        root.addView(actionRow);

        root.addView(listArea);

        rebuildList(ctx, parentAct, d, listArea, currentTab[0]);

        // 更新计数（rebuild 后重新读取）
        List<BatchAddRecordStore.Record> all = BatchAddRecordStore.getAll();
        countTv.setText("共 " + all.size() + " 条记录");

        return root;
    }

    private static void rebuildList(Context ctx, Activity parentAct, float d,
                                    LinearLayout listArea, int tab) {
        listArea.removeAllViews();
        List<BatchAddRecordStore.Record> all = BatchAddRecordStore.getAll();
        if (all.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无添加记录");
            empty.setTextSize(13);
            empty.setTextColor(AppColors.text2());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, (int)(40 * d), 0, (int)(40 * d));
            listArea.addView(empty);
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
        int shown = 0;
        for (BatchAddRecordStore.Record r : all) {
            if (tab == 1 && !r.success) continue;
            if (tab == 2 && r.success) continue;
            listArea.addView(buildRow(ctx, d, r, fmt.format(new Date(r.time))));
            shown++;
            if (shown >= 200) break;
        }
        if (shown == 0) {
            TextView empty = new TextView(ctx);
            empty.setText("无匹配记录");
            empty.setTextSize(13);
            empty.setTextColor(AppColors.text2());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, (int)(40 * d), 0, (int)(40 * d));
            listArea.addView(empty);
        }
    }

    private static View buildRow(Context ctx, float d, BatchAddRecordStore.Record r, String timeStr) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14 * d), (int)(10 * d), (int)(14 * d), (int)(10 * d));
        row.setBackground(CandyUi.cardBg(ctx));
        InsetsUtil.clipRounded(row);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView name = new TextView(ctx);
        String label = (r.displayName != null && !r.displayName.isEmpty()) ? r.displayName : r.username;
        name.setText(label);
        name.setTextSize(13);
        name.setTextColor(AppColors.text1());
        name.setTypeface(null, Typeface.BOLD);
        textCol.addView(name);

        TextView sub = new TextView(ctx);
        sub.setText(timeStr + (r.username != null && !r.username.isEmpty() && !r.username.equals(r.displayName)
                ? "  " + r.username : ""));
        sub.setTextSize(11);
        sub.setTextColor(AppColors.text3());
        sub.setPadding(0, (int)(2 * d), 0, 0);
        textCol.addView(sub);

        TextView reason = new TextView(ctx);
        reason.setText(r.reason != null ? r.reason : "");
        reason.setTextSize(11);
        reason.setTextColor(AppColors.text3());
        reason.setPadding(0, (int)(2 * d), 0, 0);
        if (r.reason != null && !r.reason.isEmpty()) textCol.addView(reason);

        row.addView(textCol);

        TextView badge = new TextView(ctx);
        badge.setText(r.success ? "成功" : "失败");
        badge.setTextSize(11);
        badge.setTextColor(0xFFFFFFFF);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding((int)(10 * d), (int)(4 * d), (int)(10 * d), (int)(4 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(r.success ? 0xFF10B981 : 0xFFE53935);
        bg.setCornerRadius((int)(10 * d));
        badge.setBackground(bg);
        row.addView(badge);

        return row;
    }

    private static View candyDivider(Context ctx, float d) {
        return M3Page.divider(ctx);
    }
}
