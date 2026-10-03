package com.leshao.v3.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * DexKit 扫描进度弹窗（v1138 样式定稿 X6）。
 *
 * <p>静态 API（show/initSteps/updateProgress/dismiss/onScanComplete/isShowing）与原版完全一致，
 * DexKitHelper 调用点零改动。</p>
 *
 * <p>v1138：移除标题/副标题与步骤打勾列表，仅保留「流光进度条 + 百分比 + 加粗渐变文字」；
 * 窗口按内容自适应高度并居中显示，去掉了底部多余空白。</p>
 */
public class DexKitScanDialog {

    // v1131: 静态强引用 Dialog/View 会泄漏 Activity, 改为 WeakReference 并在关闭时清空视图引用
    private static volatile java.lang.ref.WeakReference<AlertDialog> sDialogRef =
            new java.lang.ref.WeakReference<>(null);
    private static volatile TextView sPercentText;
    private static volatile FlyingProgressBar sProgressBar;
    private static volatile TextView sStatusText;
    private static volatile boolean sDismissed = false;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static AlertDialog dialog() { return sDialogRef.get(); }

    private static void setDialog(AlertDialog d) {
        sDialogRef = new java.lang.ref.WeakReference<>(d);
    }

    private static void clearViewRefs() {
        sPercentText = null;
        sStatusText = null;
        sProgressBar = null;
    }

    public static void show(Context ctx) {
        if (sDismissed) return;
        MAIN.post(() -> {
            try {
                AlertDialog cur = dialog();
                if (cur != null && cur.isShowing()) return;
                AlertDialog d = buildDialog(ctx);
                setDialog(d);
                d.setCancelable(false);
                d.show();
                Window window = d.getWindow();
                if (window != null) {
                    window.setDimAmount(0.6f);
                    WindowManager.LayoutParams lp = window.getAttributes();
                    lp.width = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.9f);
                    lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                    lp.gravity = Gravity.CENTER;
                    window.setAttributes(lp);
                }
            } catch (Throwable t) {
                com.leshao.v3.LogWriter.log("DexKitScanDialog", "show err: " + t.getMessage());
            }
        });
    }

    /** 兼容旧 API：步骤数据不再展示（样式定稿为纯进度条）。 */
    public static void initSteps(String[] names, String[] details) {
    }

    public static void updateProgress(int percent, String status, String detail) {
        if (sDismissed) return;
        MAIN.post(() -> {
            try {
                if (sProgressBar != null) sProgressBar.setProgress(percent);
            } catch (Throwable ignored) {}
        });
    }

    public static void dismiss() {
        sDismissed = true;
        MAIN.post(() -> {
            try {
                AlertDialog d = dialog();
                if (d != null && d.isShowing()) d.dismiss();
            } catch (Throwable ignored) {}
            setDialog(null);
            clearViewRefs();
        });
    }

    /** 扫描完成: 直接补到 100% 并自动关闭弹窗(无需手动关闭)。 */
    public static void onScanComplete() {
        MAIN.post(() -> {
            try {
                if (sProgressBar != null) sProgressBar.setProgress(100);
                if (sPercentText != null) sPercentText.setText("100%");
                MAIN.postDelayed(DexKitScanDialog::hideDialog, 700L);
            } catch (Throwable ignored) {}
        });
    }

    /** 关闭弹窗但不清空可用状态(自动关闭用, 不锁定后续再次展示)。 */
    private static void hideDialog() {
        MAIN.post(() -> {
            try {
                AlertDialog d = dialog();
                if (d != null && d.isShowing()) d.dismiss();
            } catch (Throwable ignored) {}
            setDialog(null);
            clearViewRefs();
        });
    }

    public static boolean isShowing() {
        AlertDialog d = dialog();
        return d != null && d.isShowing();
    }

    private static AlertDialog buildDialog(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 14));
        // v1145: 按当前浮层层级取底色，并补上统一阴影
        root.setBackground(CandyUi.dialogBg(ctx, WindowLayer.depth()));
        InsetsUtil.clipRounded(root);
        CandyUi.elevate(root);

        // 流光进度条（左→右填充 + 流动虚线 + 斜飞鸟）
        sProgressBar = new FlyingProgressBar(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(ctx, 4), 0, dp(ctx, 4), 0);
        sProgressBar.setLayoutParams(lp);
        root.addView(sProgressBar);

        // 百分比（条下方，渐变流光）
        sPercentText = new TextView(ctx);
        sPercentText.setText("0%");
        sPercentText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        sPercentText.setTypeface(null, android.graphics.Typeface.BOLD);
        sPercentText.setGravity(Gravity.CENTER);
        sPercentText.setPadding(0, dp(ctx, 6), 0, dp(ctx, 2));
        root.addView(sPercentText);
        GradientText.apply(sPercentText);
        sProgressBar.setProgressListener(p -> {
            if (sPercentText != null) sPercentText.setText(p + "%");
        });

        // 加粗渐变文字
        sStatusText = new TextView(ctx);
        sStatusText.setText("正在适配微信 请勿切换后台");
        sStatusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sStatusText.setTypeface(null, android.graphics.Typeface.BOLD);
        sStatusText.setGravity(Gravity.CENTER);
        root.addView(sStatusText);
        GradientText.apply(sStatusText);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setView(root)
                .create();

        InsetsUtil.transparentWindow(dialog);
        return dialog;
    }

    private static int dp(Context ctx, int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }
}
