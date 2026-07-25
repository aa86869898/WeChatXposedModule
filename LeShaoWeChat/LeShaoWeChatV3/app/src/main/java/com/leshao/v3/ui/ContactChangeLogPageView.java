package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.leshao.v3.hook.ContactChangeLog;
import com.leshao.v3.model.ContactChangeRecord;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class ContactChangeLogPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        List<ContactChangeRecord> records = ContactChangeLog.loadRecords();

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding((int)(16*d), (int)(16*d), (int)(16*d), (int)(24*d));

        TextView section = new TextView(ctx);
        section.setText("通讯录更新记录");
        section.setTextSize(13);
        section.setTextColor(AppColors.text2());
        section.setPadding(0, 0, 0, (int)(8*d));
        body.addView(section);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(AppColors.card());
        card.setPadding((int)(2*d), (int)(2*d), (int)(2*d), (int)(2*d));

        if (records.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无变更记录");
            empty.setTextSize(14);
            empty.setTextColor(AppColors.text2());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding((int)(16*d), (int)(30*d), (int)(16*d), (int)(30*d));
            card.addView(empty);
        } else {
            for (int i = 0; i < Math.min(records.size(), 200); i++) {
                if (i > 0) card.addView(divider(ctx, d));
                card.addView(recordRow(ctx, d, records.get(i)));
            }
            if (records.size() > 200) {
                TextView more = new TextView(ctx);
                more.setText("... 仅显示最近 200 条记录 ...");
                more.setTextSize(12);
                more.setTextColor(AppColors.text2());
                more.setGravity(Gravity.CENTER);
                more.setPadding(0, (int)(12*d), 0, (int)(4*d));
                card.addView(more);
            }
        }

        body.addView(card);

        return body;
    }

    private static View recordRow(Context ctx, float d, ContactChangeRecord r) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding((int)(14*d), (int)(10*d), (int)(14*d), (int)(10*d));
        row.setBackgroundColor(AppColors.whiteCard());

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd HH:mm");
        String timeStr = sdf.format(new Date(r.time));

        TextView timeView = new TextView(ctx);
        timeView.setText(timeStr);
        timeView.setTextSize(11);
        timeView.setTextColor(AppColors.text2());
        timeView.setPadding(0, 0, (int)(10*d), 0);
        header.addView(timeView);

        TextView nameView = new TextView(ctx);
        nameView.setText(r.nickname != null && !r.nickname.isEmpty() ? r.nickname : r.wxid);
        nameView.setTextSize(14);
        nameView.setTextColor(AppColors.text1());
        nameView.setTypeface(null, Typeface.BOLD);
        header.addView(nameView);

        TextView typeView = new TextView(ctx);
        typeView.setText(" " + r.changeType);
        typeView.setTextSize(12);
        typeView.setTextColor(AppColors.accent());
        header.addView(typeView);

        row.addView(header);

        String old = r.oldValue != null ? r.oldValue : "(无)";
        String newVal = r.newValue != null ? r.newValue : "(无)";
        TextView detail = new TextView(ctx);
        detail.setText(old + " -> " + newVal);
        detail.setTextSize(12);
        detail.setTextColor(AppColors.text2());
        detail.setPadding(0, (int)(4*d), 0, 0);
        row.addView(detail);

        return row;
    }

    private static View divider(Context ctx, float d) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(1*d)));
        v.setBackgroundColor(AppColors.divider());
        return v;
    }
}
