package com.leshao.v3.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.service.ActivationManager;

public class AdminPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());
        root.setPadding((int)(8 * d), (int)(16 * d), (int)(8 * d), (int)(16 * d));

        root.addView(sectionLabel(ctx, d, "模块黑名单"));
        root.addView(buildBlacklistCard(ctx, d));

        sv.addView(root);
        return sv;
    }

    // ===== 模块黑名单 =====

    private static View buildBlacklistCard(Context ctx, float d) {
        LinearLayout card = makeCard(ctx, d);

        final LinearLayout listContainer = new LinearLayout(ctx);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        refreshBlacklistList(ctx, d, listContainer);
        card.addView(listContainer);

        LinearLayout inputRow = new LinearLayout(ctx);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding((int)(14*d), (int)(10*d), (int)(14*d), (int)(12*d));
        inputRow.setBackgroundColor(AppColors.whiteCard());

        final EditText wxidInput = new EditText(ctx);
        wxidInput.setHint("输入要拉黑的 wxid");
        wxidInput.setTextSize(13);
        wxidInput.setTextColor(AppColors.text1());
        wxidInput.setHintTextColor(AppColors.text2());
        wxidInput.setSingleLine(true);
        wxidInput.setPadding((int)(8*d), (int)(6*d), (int)(8*d), (int)(6*d));
        GradientDrawable wBg = new GradientDrawable();
        wBg.setColor(AppColors.card());
        wBg.setCornerRadius((int)(6 * d));
        wBg.setStroke((int)(1.5f * d), AppColors.candyPink());
        wxidInput.setBackground(wBg);
        wxidInput.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        inputRow.addView(wxidInput);

        Button addBtn = new Button(ctx);
        addBtn.setText("拉黑");
        addBtn.setTextSize(12);
        addBtn.setTextColor(Color.WHITE);
        addBtn.setPadding((int)(16*d), (int)(4*d), (int)(16*d), (int)(4*d));
        GradientDrawable addBg = new GradientDrawable();
        addBg.setCornerRadius((int)(6*d));
        addBg.setColor(AppColors.accent());
        addBtn.setBackground(addBg);
        addBtn.setOnClickListener(v -> {
            String wxid = wxidInput.getText().toString().trim();
            if (wxid.isEmpty()) { toast(ctx, "请输入 wxid"); return; }
            if (ActivationManager.isAdmin(wxid)) {
                toast(ctx, "不能拉黑管理员");
                return;
            }
            ActivationManager.addBlacklist(wxid);
            wxidInput.setText("");
            toast(ctx, "已拉黑 " + wxid + ",即时生效");
            refreshBlacklistList(ctx, d, listContainer);
        });
        addBtn.setPaintFlags(addBtn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        inputRow.addView(addBtn);
        card.addView(inputRow);

        TextView tip = new TextView(ctx);
        tip.setText("被拉黑的 wxid 将无法打开模块主页、无法进入任何功能页面，且聊天窗口功能按钮不再显示。解除后立即恢复。");
        tip.setTextSize(11);
        tip.setTextColor(AppColors.text2());
        tip.setPadding((int)(14*d), (int)(4*d), (int)(14*d), (int)(12*d));
        tip.setLineSpacing((int)(2*d), 1.2f);
        card.addView(tip);

        return card;
    }

    private static void refreshBlacklistList(Context ctx, float d, LinearLayout container) {
        container.removeAllViews();
        java.util.Set<String> blacklist = ActivationManager.getBlacklist();
        if (blacklist.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无拉黑用户");
            empty.setTextSize(13);
            empty.setTextColor(AppColors.text2());
            empty.setPadding((int)(14*d), (int)(12*d), (int)(14*d), (int)(12*d));
            empty.setBackgroundColor(AppColors.whiteCard());
            container.addView(empty);
            return;
        }
        int i = 0;
        for (final String wxid : blacklist) {
            if (i > 0) container.addView(candyDivider(ctx, d));
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int)(14*d), (int)(10*d), (int)(14*d), (int)(10*d));
            row.setBackgroundColor(AppColors.whiteCard());

            TextView wxidTv = new TextView(ctx);
            wxidTv.setText(wxid);
            wxidTv.setTextSize(13);
            wxidTv.setTextColor(AppColors.text1());
            wxidTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
            row.addView(wxidTv);

            Button removeBtn = new Button(ctx);
            removeBtn.setText("解除");
            removeBtn.setTextSize(11);
            removeBtn.setTextColor(Color.WHITE);
            removeBtn.setPadding((int)(12*d), (int)(2*d), (int)(12*d), (int)(2*d));
            GradientDrawable rmBg = new GradientDrawable();
            rmBg.setCornerRadius((int)(4*d));
            rmBg.setColor(AppColors.arrow());
            removeBtn.setBackground(rmBg);
            removeBtn.setOnClickListener(v -> {
                ActivationManager.removeBlacklist(wxid);
                toast(ctx, "已解除 " + wxid + ",即时恢复");
                refreshBlacklistList(ctx, d, container);
            });
            removeBtn.setPaintFlags(removeBtn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            row.addView(removeBtn);
            container.addView(row);
            i++;
        }
    }

    private static void toast(Context ctx, String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    private static TextView sectionLabel(Context ctx, float d, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(8*d));
        return tv;
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(2*d), (int)(2*d), (int)(2*d), (int)(2*d));
        card.setBackgroundColor(AppColors.card());
        return card;
    }

    private static View itemDivider(Context ctx, float d) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        v.setBackgroundColor(AppColors.divider());
        return v;
    }

    private static View spacerV(Context ctx, float d, int dpVal) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dpVal*d)));
        return v;
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
