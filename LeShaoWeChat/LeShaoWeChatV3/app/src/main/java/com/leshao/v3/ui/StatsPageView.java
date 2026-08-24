package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.leshao.v3.ContactRepository;
import com.leshao.v3.service.StatsCollector;

import java.util.List;

public class StatsPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(d, 16), dp(d, 16), dp(d, 16), dp(d, 16));

        root.addView(sLabel(ctx, d, "数据统计"));

        final TextView friendTv = new TextView(ctx);
        final TextView groupTv = new TextView(ctx);
        root.addView(infoRow(ctx, d, "好友数", friendTv));
        root.addView(infoRow(ctx, d, "群聊数", groupTv));
        updateCounts(friendTv, groupTv);
        ContactRepository.loadAsync(() ->
                new Handler(Looper.getMainLooper()).post(() -> updateCounts(friendTv, groupTv)));

        root.addView(candyDivider(ctx, d));

        root.addView(sLabel(ctx, d, "最近撤回记录"));
        List<String> recalls = StatsCollector.getRecallRecords();
        if (recalls.isEmpty()) {
            root.addView(tv(ctx, d, "暂无撤回记录"));
        } else {
            int start = Math.max(0, recalls.size() - 20);
            for (int i = start; i < recalls.size(); i++) {
                root.addView(tv(ctx, d, recalls.get(i)));
            }
        }

        ScrollView sv = new ScrollView(ctx);
        sv.addView(root);
        return sv;
    }

    private static TextView sLabel(Context ctx, float d, String t) {
        TextView tv = new TextView(ctx); tv.setText(t); tv.setTextSize(18);
        tv.setPadding(0, dp(d, 16), 0, dp(d, 8)); tv.getPaint().setFakeBoldText(true); return tv;
    }

    private static LinearLayout infoRow(Context ctx, float d, String label, String value) {
        LinearLayout row = new LinearLayout(ctx); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(d, 4), 0, dp(d, 4));
        TextView t1 = new TextView(ctx); t1.setText(label + ": "); t1.setTextSize(14);
        t1.getPaint().setFakeBoldText(true); row.addView(t1);
        TextView t2 = new TextView(ctx); t2.setText(value); t2.setTextSize(14);
        row.addView(t2); return row;
    }

    private static LinearLayout infoRow(Context ctx, float d, String label, TextView valueTv) {
        LinearLayout row = new LinearLayout(ctx); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(d, 4), 0, dp(d, 4));
        TextView t1 = new TextView(ctx); t1.setText(label + ": "); t1.setTextSize(14);
        t1.getPaint().setFakeBoldText(true); row.addView(t1);
        valueTv.setTextSize(14);
        row.addView(valueTv); return row;
    }

    private static void updateCounts(TextView friendTv, TextView groupTv) {
        friendTv.setText(String.valueOf(ContactRepository.getFriends().size()));
        groupTv.setText(String.valueOf(ContactRepository.getGroups().size()));
    }

    private static TextView tv(Context ctx, float d, String text) {
        TextView t = new TextView(ctx); t.setText(text); t.setTextSize(13);
        t.setPadding(0, dp(d, 2), 0, dp(d, 2)); return t;
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

    private static int dp(float density, int dp) { return (int) (dp * density + 0.5f); }
}
