package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.LinearLayout;

import com.leshao.v3.ContactRepository;
import com.leshao.v3.service.StatsCollector;
import com.leshao.v3.ui.widgets.M3Page;

import java.util.List;

/**
 * 数据统计页（v955 M3 重排）：联系人统计 + 撤回记录。
 * 业务逻辑（ContactRepository 异步加载/StatsCollector）与原版一致。
 */
public class StatsPageView {

    public static View create(Context ctx, Activity parentAct) {
        LinearLayout root = M3Page.root(ctx);

        // ============ 数据统计 ============
        root.addView(M3Page.section(ctx, "数据统计"));

        LinearLayout cardStats = M3Page.card(ctx);
        final TextView friendTv = new TextView(ctx);
        friendTv.setTextSize(14);
        friendTv.setTextColor(AppColors.onSurface());
        final TextView groupTv = new TextView(ctx);
        groupTv.setTextSize(14);
        groupTv.setTextColor(AppColors.onSurface());

        View friendRow = M3Page.infoRow(ctx, "好友数", "");
        replaceValueSlot(friendRow, friendTv);
        cardStats.addView(friendRow);
        cardStats.addView(M3Page.divider(ctx));
        View groupRow = M3Page.infoRow(ctx, "群聊数", "");
        replaceValueSlot(groupRow, groupTv);
        cardStats.addView(groupRow);
        root.addView(cardStats);

        updateCounts(friendTv, groupTv);
        ContactRepository.loadAsync(() ->
                new Handler(Looper.getMainLooper()).post(() -> updateCounts(friendTv, groupTv)));

        // ============ 最近撤回记录 ============
        root.addView(M3Page.section(ctx, "最近撤回记录"));
        List<String> recalls = StatsCollector.getRecallRecords();
        if (recalls.isEmpty()) {
            root.addView(M3Page.empty(ctx, "📭", "暂无撤回记录"));
        } else {
            LinearLayout cardRecall = M3Page.card(ctx);
            int start = Math.max(0, recalls.size() - 20);
            boolean first = true;
            for (int i = start; i < recalls.size(); i++) {
                if (!first) cardRecall.addView(M3Page.divider(ctx));
                first = false;
                cardRecall.addView(M3Page.infoRow(ctx, recalls.get(i), null));
            }
            root.addView(cardRecall);
        }

        return M3Page.scroll(ctx, root);
    }

    /** 把 infoRow 的值槽位替换为可动态更新的 TextView */
    private static void replaceValueSlot(View row, TextView valueTv) {
        try {
            if (row instanceof ViewGroup && ((ViewGroup) row).getChildCount() >= 2) {
                ViewGroup vg = (ViewGroup) row;
                vg.removeViewAt(1);
                vg.addView(valueTv);
            }
        } catch (Throwable ignored) {}
    }

    private static void updateCounts(TextView friendTv, TextView groupTv) {
        try {
            friendTv.setText(String.valueOf(ContactRepository.getFriends().size()));
            groupTv.setText(String.valueOf(ContactRepository.getGroups().size()));
        } catch (Throwable ignored) {}
    }
}
