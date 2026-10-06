package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 快捷菜单页（主页「快捷菜单」卡片入口，v3.0.165）。
 *
 * <p>占位页：后续版本在此放置高频快捷功能入口。</p>
 */
public final class QuickMenuPageView {

    private QuickMenuPageView() {}

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());
        InsetsUtil.clipRounded(root);
        root.setGravity(Gravity.CENTER);
        root.setPadding((int)(AppColors.SPACE_LG_DP * d), (int)(AppColors.SPACE_MD_DP * d),
                (int)(AppColors.SPACE_LG_DP * d), (int)(AppColors.SPACE_MD_DP * d));

        TextView placeholder = new TextView(ctx);
        placeholder.setText("快捷菜单建设中...\n后续版本将在此放置高频快捷功能入口");
        placeholder.setTextSize(15);
        placeholder.setTextColor(AppColors.text2());
        placeholder.setGravity(Gravity.CENTER);
        root.addView(placeholder);

        return root;
    }
}
