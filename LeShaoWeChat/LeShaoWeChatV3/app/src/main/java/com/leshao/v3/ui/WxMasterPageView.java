package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.leshao.v3.hook.WxMasterFeatures;

public class WxMasterPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;
        ClassLoader cl = parentAct.getClassLoader();

        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());
        root.setPadding((int)(8 * d), (int)(16 * d), (int)(8 * d), (int)(16 * d));

        root.addView(headerBanner(ctx, d,
                "⚡ 乐少助手增强",
                "进聊天页右上角有 ⚡ 功能按钮；进群聊还会多一个红色 🛡 群管理按钮"));

        root.addView(sectionLabel(ctx, "📨 群发 / 广播"));
        LinearLayout card1 = makeCard(ctx, d);
        card1.addView(buttonRow(ctx, d, "广播到所有群", "一条消息发送到全部群聊", () -> WxMasterFeatures.broadcastAll(parentAct, cl)));
        card1.addView(divider(ctx, d));
        card1.addView(buttonRow(ctx, d, "乐少万群定时群发", "勾选多个群+定时发送", () -> WxMasterFeatures.batchSend(parentAct, cl)));
        root.addView(card1);

        root.addView(spacer(ctx, d, 10));

        root.addView(sectionLabel(ctx, "🛡 群工具"));
        LinearLayout card2 = makeCard(ctx, d);
        card2.addView(buttonRow(ctx, d, "所有群列表", "查看全部群名+人数", () -> WxMasterFeatures.listAllRooms(parentAct, cl)));
        card2.addView(divider(ctx, d));
        card2.addView(buttonRow(ctx, d, "群信息报告", "导出群资料到 TXT", () -> WxMasterFeatures.exportRoomReportPicker(parentAct, cl)));
        card2.addView(divider(ctx, d));
        card2.addView(buttonRow(ctx, d, "查看群公告", "公告内容 + 编辑者 + 时间", () -> WxMasterFeatures.showRoomNoticePicker(parentAct, cl)));
        card2.addView(divider(ctx, d));
        card2.addView(buttonRow(ctx, d, "导出全部群", "所有群信息 CSV 导出", () -> WxMasterFeatures.exportAllRooms(parentAct, cl)));
        root.addView(card2);

        root.addView(spacer(ctx, d, 10));

        root.addView(sectionLabel(ctx, "🔧 聊天工具"));
        LinearLayout card3 = makeCard(ctx, d);
        card3.addView(buttonRow(ctx, d, "定时发送", "设置延迟后自动发送", () -> WxMasterFeatures.scheduledSend(parentAct, cl)));
        card3.addView(divider(ctx, d));
        card3.addView(buttonRow(ctx, d, "快捷扫码", "一键启动扫一扫", () -> WxMasterFeatures.quickScan(parentAct)));
        card3.addView(divider(ctx, d));
        card3.addView(buttonRow(ctx, d, "私密备注", "本地保存备注,不写入微信", () -> WxMasterFeatures.privateNote(parentAct, cl)));
        root.addView(card3);

        root.addView(spacer(ctx, d, 10));

        TextView tip = new TextView(ctx);
        tip.setText("提示：\n· 导出文件保存在 微信文档目录 WeChatMaster/\n"
                + "· 进群聊点右上角 🛡 可直接操作当前群\n"
                + "· 群信息报告 / 公告需手动输入目标群 wxid");
        tip.setTextSize(12);
        tip.setTextColor(AppColors.text2());
        tip.setPadding((int)(4 * d), 0, (int)(4 * d), 0);
        root.addView(tip);

        sv.addView(root);
        return sv;
    }

    private static View headerBanner(Context ctx, float d, String title, String desc) {
        LinearLayout banner = new LinearLayout(ctx);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding((int)(14 * d), (int)(14 * d), (int)(14 * d), (int)(14 * d));
        banner.setBackgroundColor(AppColors.card());

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(16);
        tv.setTextColor(AppColors.text1());
        tv.setTypeface(null, Typeface.BOLD);
        banner.addView(tv);

        TextView dv = new TextView(ctx);
        dv.setText(desc);
        dv.setTextSize(12);
        dv.setTextColor(AppColors.text2());
        dv.setPadding(0, (int)(6 * d), 0, 0);
        banner.addView(dv);

        return banner;
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(2 * d), (int)(2 * d), (int)(2 * d), (int)(2 * d));
        card.setBackgroundColor(AppColors.card());
        return card;
    }

    private static View buttonRow(Context ctx, float d, String title, String desc, Runnable action) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(AppColors.whiteCard());

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

        row.setOnClickListener(v -> {
            try { action.run(); } catch (Throwable ignored) {}
        });
        return row;
    }

    private static TextView sectionLabel(Context ctx, String text) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(8 * d));
        return tv;
    }

    private static View divider(Context ctx, float d) {
        View div = new View(ctx);
        div.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        div.setBackgroundColor(AppColors.divider());
        return div;
    }

    private static View spacer(Context ctx, float d, int dpVal) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dpVal * d)));
        return v;
    }
}
