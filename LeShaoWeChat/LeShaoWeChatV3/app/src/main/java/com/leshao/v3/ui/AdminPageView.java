package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.service.ActivationManager;
import com.leshao.v3.ui.widgets.M3Page;
import com.leshao.v3.ui.widgets.ModernButton;
import com.leshao.v3.ui.widgets.ToastHelper;

/**
 * 管理员页（v955 M3 重排）：模块黑名单管理。
 * 业务逻辑（ActivationManager 拉黑/解除/管理员判定）与原版一致。
 */
public class AdminPageView {

    public static View create(Context ctx, Activity parentAct) {
        LinearLayout root = M3Page.root(ctx);

        root.addView(M3Page.section(ctx, "模块黑名单", "被拉黑的 wxid 无法使用模块任何功能"));

        LinearLayout card = M3Page.card(ctx);

        final LinearLayout listContainer = new LinearLayout(ctx);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        refreshBlacklistList(ctx, listContainer);
        card.addView(listContainer);

        card.addView(M3Page.divider(ctx));

        // 输入行：M3 input + filled button
        LinearLayout inputRow = new LinearLayout(ctx);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        int p = dpInt(ctx, 16);
        inputRow.setPadding(p, dpInt(ctx, 10), p, dpInt(ctx, 12));

        final EditText wxidInput = M3Page.input(ctx, "输入要拉黑的 wxid");
        wxidInput.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        inputRow.addView(wxidInput);

        ModernButton addBtn = new ModernButton(ctx, "拉黑", ModernButton.STYLE_PRIMARY);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-2, -2);
        btnLp.setMarginStart(dpInt(ctx, 8));
        addBtn.setLayoutParams(btnLp);
        addBtn.onClick(() -> {
            String wxid = wxidInput.getText().toString().trim();
            if (wxid.isEmpty()) { ToastHelper.show(ctx, "请输入 wxid"); return; }
            if (ActivationManager.isAdmin(wxid)) {
                ToastHelper.error(ctx, "不能拉黑管理员");
                return;
            }
            ActivationManager.addBlacklist(wxid);
            wxidInput.setText("");
            ToastHelper.success(ctx, "已拉黑 " + wxid + "，即时生效");
            refreshBlacklistList(ctx, listContainer);
        });
        inputRow.addView(addBtn);
        card.addView(inputRow);

        card.addView(M3Page.divider(ctx));

        TextView tip = new TextView(ctx);
        tip.setText("被拉黑的 wxid 将无法打开模块主页、无法进入任何功能页面，且聊天窗口功能按钮不再显示。解除后立即恢复。");
        tip.setTextSize(12);
        tip.setTextColor(AppColors.onSurfaceVariant());
        tip.setPadding(p, dpInt(ctx, 4), p, dpInt(ctx, 12));
        tip.setLineSpacing(dpInt(ctx, 2), 1.2f);
        card.addView(tip);

        root.addView(card);

        return M3Page.scroll(ctx, root);
    }

    private static void refreshBlacklistList(Context ctx, LinearLayout container) {
        container.removeAllViews();
        java.util.Set<String> blacklist = ActivationManager.getBlacklist();
        if (blacklist.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无拉黑用户");
            empty.setTextSize(14);
            empty.setTextColor(AppColors.onSurfaceVariant());
            empty.setPadding(dpInt(ctx, 16), dpInt(ctx, 14), dpInt(ctx, 16), dpInt(ctx, 14));
            container.addView(empty);
            return;
        }
        boolean first = true;
        for (final String wxid : blacklist) {
            if (!first) container.addView(M3Page.divider(ctx));
            first = false;

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(com.leshao.v3.ui.CandyUi.rowPressBg(ctx));
            int p = dpInt(ctx, 16);
            row.setPadding(p, dpInt(ctx, 10), p, dpInt(ctx, 10));

            TextView wxidTv = new TextView(ctx);
            wxidTv.setText(wxid);
            wxidTv.setTextSize(14);
            wxidTv.setTextColor(AppColors.onSurface());
            wxidTv.setSingleLine(true);
            wxidTv.setEllipsize(TextUtils.TruncateAt.END);
            wxidTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(wxidTv);

            ModernButton removeBtn = new ModernButton(ctx, "解除", ModernButton.STYLE_TEXT);
            removeBtn.onClick(() -> {
                ActivationManager.removeBlacklist(wxid);
                ToastHelper.success(ctx, "已解除 " + wxid + "，即时恢复");
                refreshBlacklistList(ctx, container);
            });
            row.addView(removeBtn);
            container.addView(row);
        }
    }

    private static int dpInt(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
