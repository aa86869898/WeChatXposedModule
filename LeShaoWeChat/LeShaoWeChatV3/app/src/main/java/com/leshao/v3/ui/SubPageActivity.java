package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.service.ActivationManager;
import com.leshao.v3.ui.widgets.M3Page;

public class SubPageActivity {

    private static AlertDialog sSubDialog;
    private static Activity sParentAct;
    private static String sTitle;
    private static int sPageId;
    private static boolean sStandalone = false;
    private static final java.util.Stack<Integer> sNavStack = new java.util.Stack<>();

    public static void open(Activity parentAct, String title, int pageId) {
        sStandalone = false;
        openInternal(parentAct, title, pageId, true);
    }

    public static void openFromMain(Activity parentAct, String title, int pageId) {
        sNavStack.clear();
        sStandalone = false;
        openInternal(parentAct, title, pageId, false);
    }

    /**
     * 从微信原生页面（如群聊详情页）独立打开子页面。
     * 返回时清空导航栈并直接关闭弹窗回到原页面，不跳转模块主页。
     */
    public static void openStandalone(Activity parentAct, String title, int pageId) {
        sNavStack.clear();
        sStandalone = true;
        openInternal(parentAct, title, pageId, false);
    }

    private static void openInternal(Activity parentAct, String title, int pageId, boolean pushCurrent) {
        if (ActivationManager.isCurrentUserBlocked()) {
            showBlacklistBlock(parentAct);
            return;
        }
        if (pushCurrent && sPageId != 0) sNavStack.push(sPageId);
        sParentAct = parentAct;
        sTitle = title;
        sPageId = pageId;
        show(parentAct, title, pageId);
    }

    // ===== 黑名单拦截 =====

    private static void showBlacklistBlock(Activity parentAct) {
        dismissSub();
        MainActivity.dismissDialog();
        if (sParentAct == null) sParentAct = parentAct;
        final Activity act = parentAct;
        act.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(act)
                        .setTitle("\uD83D\uDD12 模块已被禁用")
                        .setMessage("您已被管理员列入模块黑名单，当前微信无法使用乐少助手的任何功能，也无法进入任何功能页面。\n\n如有疑问请联系管理员解除限制。")
                        .setPositiveButton("知道了", null)
                        .setCancelable(false)
                        .show();
            } catch (Throwable ignored) {}
        });
    }

    private static void show(Activity parentAct, String title, int pageId) {
        MainActivity.dismissDialog();
        dismissSub();

        float d = parentAct.getResources().getDisplayMetrics().density;
        Context ctx = parentAct;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());

        root.addView(MainActivity.makeTitleBar(ctx, title, true, () -> goBack(parentAct)));

        View body = createPageBody(ctx, parentAct, pageId);
        android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(true);
        android.widget.LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        sv.setLayoutParams(svLp);
        sv.addView(body);
        root.addView(sv);

        AlertDialog.Builder b = new AlertDialog.Builder(ctx, AppColors.isDarkMode()
                ? android.R.style.Theme_DeviceDefault_NoActionBar
                : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        b.setView(root);
        b.setCancelable(true);
        AlertDialog dlg = b.create();
        sSubDialog = dlg;

        dlg.setOnCancelListener(dialog -> goBack(parentAct));
        dlg.setOnDismissListener(dialog -> {
            if (sSubDialog == dlg) sSubDialog = null;
        });

        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new ColorDrawable(AppColors.bg()));
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dlg.show();
    }

    private static void goBack(Activity parentAct) {
        dismissSub();
        if (!sNavStack.isEmpty()) {
            int prevPageId = sNavStack.pop();
            openInternal(parentAct, "返回", prevPageId, false);
        } else if (!sStandalone) {
            MainActivity.open(parentAct);
        }
        sStandalone = false;
    }

    private static void dismissSub() {
        if (sSubDialog != null && sSubDialog.isShowing()) {
            try { sSubDialog.dismiss(); } catch (Throwable ignored) {}
        }
        sSubDialog = null;
        sParentAct = null;
    }

    private static View createPageBody(Context ctx, Activity parentAct, int pageId) {
        switch (pageId) {
            case 3:  // 联系人和群聊
                return ContactGroupPageView.create(ctx, parentAct);
            case 4:  // 群管理助手
                return WxMasterPageView.create(ctx, parentAct);
            case 8:  // TTS语音播报
                return TTSPageView.create(ctx, parentAct);
            case 20: // 关于模块
                return AboutPageView.create(ctx, parentAct);
            case 99: // 个人中心
                return ProfilePageView.create(ctx, parentAct);
            case 14: // 聊天分组
                return ChatGroupPageView.create(ctx, parentAct);
            case 15: // 批量加好友记录
                return BatchAddRecordPageView.create(ctx, parentAct);
            case 98: // 管理员工具
                return AdminPageView.create(ctx, parentAct);
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
