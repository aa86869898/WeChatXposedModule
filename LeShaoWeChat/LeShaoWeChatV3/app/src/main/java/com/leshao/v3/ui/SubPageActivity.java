package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SubPageActivity {

    private static AlertDialog sSubDialog;
    private static Activity sParentAct;
    private static String sTitle;
    private static int sPageId;
    private static int sThemeFeaturePageId = 0;

    private static boolean sIsThemeSubPage;

    public static void setThemeFeaturePageId(int id) {
        sThemeFeaturePageId = id;
    }

    public static void open(Activity parentAct, String title, int pageId) {
        sParentAct = parentAct;
        sTitle = title;
        sPageId = pageId;
        sIsThemeSubPage = (pageId == 20);
        show(parentAct, title, pageId);
    }

    public static void reloadThemePage() {
        if (sParentAct != null) {
            dismissSub();
            show(sParentAct, sTitle, sPageId);
        }
    }

    private static void show(Activity parentAct, String title, int pageId) {
        MainActivity.dismissDialog();
        dismissSub();

        float d = parentAct.getResources().getDisplayMetrics().density;
        Context ctx = parentAct;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());

        root.addView(MainActivity.makeTitleBar(ctx, title, true, () -> goBack(parentAct)));

        View body = createPageBody(ctx, parentAct, pageId);
        android.widget.LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        body.setLayoutParams(blp);
        root.addView(body);

        AlertDialog.Builder b = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        b.setView(root);
        b.setCancelable(true);
        AlertDialog dlg = b.create();
        sSubDialog = dlg;

        // 系统返回键或点击对话框外关闭时，回到主页面
        dlg.setOnCancelListener(dialog -> goBack(parentAct));
        dlg.setOnDismissListener(dialog -> {
            if (sSubDialog == dlg) sSubDialog = null;
        });

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.90),
                        (int)(ctx.getResources().getDisplayMetrics().heightPixels * 0.82));
            w.setGravity(Gravity.CENTER);
        }
        dlg.show();
    }

    private static void goBack(Activity parentAct) {
        dismissSub();
        if (sIsThemeSubPage) {
            sIsThemeSubPage = false;
            open(parentAct, "主题美化", 2);
        } else {
            MainActivity.open(parentAct);
        }
    }

    private static void dismissSub() {
        if (sSubDialog != null && sSubDialog.isShowing()) {
            try { sSubDialog.dismiss(); } catch (Throwable ignored) {}
        }
        sSubDialog = null;
    }

    private static View createPageBody(Context ctx, Activity parentAct, int pageId) {
        switch (pageId) {
            case 1:  // 聊天功能
                return ChatPageView.create(ctx, parentAct);
            case 2:  // 主题美化
                return ThemePageView.create(ctx, parentAct);
            case 9:  // 红包转账
                return RedPacketPageView.create(ctx, parentAct);
            case 91: // 红包转账 > 自动秒抢红包
                return RedPacketConfigView.create(ctx, parentAct);
            case 20: // 主题美化 > 具体功能配置
                return ThemePageView.createFeatureConfigPage(ctx, parentAct, sThemeFeaturePageId);
            default:
                return makePlaceholder(ctx, parentAct);
        }
    }

    private static View makePlaceholder(Context ctx, Activity parentAct) {
        float d = parentAct.getResources().getDisplayMetrics().density;

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        body.setPadding((int)(20 * d), (int)(40 * d), (int)(20 * d), (int)(40 * d));

        TextView placeholder = new TextView(ctx);
        placeholder.setText("功能开发中...");
        placeholder.setTextSize(15);
        placeholder.setTextColor(AppColors.text2());
        placeholder.setGravity(Gravity.CENTER);
        body.addView(placeholder);

        return body;
    }
}
