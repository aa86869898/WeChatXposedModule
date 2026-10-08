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
import android.widget.ScrollView;
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
    /** v3.0.156：记录最近用于显示进度弹窗的 Activity，供"部分功能未适配"弹窗复用以避开已销毁的 Splash。 */
    private static volatile java.lang.ref.WeakReference<android.app.Activity> sHostAct =
            new java.lang.ref.WeakReference<>(null);
    /** 防重复弹出"部分功能未适配"。 */
    private static volatile boolean sMissingShown = false;
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
        if (ctx instanceof android.app.Activity) {
            sHostAct = new java.lang.ref.WeakReference<>((android.app.Activity) ctx);
        }
        MAIN.post(() -> {
            try {
                AlertDialog cur = dialog();
                if (cur != null && cur.isShowing()) return;
                AlertDialog d = buildDialog(ctx);
                setDialog(d);
                d.setCancelable(false);
                d.show();
                com.leshao.v3.LogWriter.log("DexKitScanDialog", "scan dialog shown on "
                        + (ctx == null ? "null" : ctx.getClass().getName()));
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
                if (d != null && d.isShowing()) {
                    d.dismiss();
                    com.leshao.v3.LogWriter.log("DexKitScanDialog", "scan dialog dismissed");
                }
            } catch (Throwable ignored) {}
            setDialog(null);
            clearViewRefs();
        });
    }

    /** 扫描完成: 全部命中则直接补到 100% 并自动关闭弹窗(无需手动关闭)。 */
    public static void onScanComplete() {
        onScanComplete(null);
    }

    /** v3.0.153: 全部命中则自动关闭; 存在未定位到的目标时弹出提示, 列出缺失项。
     *  v3.0.170: 扫描通常数秒内完成、进度条只跑到个位数，立即关闭会让用户以为没扫完。
     *  这里先把进度条补到 100%（自绘条会缓动追目标），并保持 1.5s 让用户看到“扫完了”再关。
     *  @param missingSummary 缺失项摘要, null/空表示全部命中。 */
    public static void onScanComplete(String missingSummary) {
        com.leshao.v3.LogWriter.log("DexKitScanDialog", "onScanComplete called, missing="
                + (missingSummary == null ? "null" : missingSummary.length() + " chars"));
        MAIN.post(() -> {
            try {
                if (sProgressBar != null) sProgressBar.setProgress(100);
                if (sPercentText != null) sPercentText.setText("100%");
                if (sStatusText != null) sStatusText.setText("扫描完成");
            } catch (Throwable ignored) {}
            if (missingSummary != null && !missingSummary.isEmpty()) {
                if (sMissingShown) return;
                sMissingShown = true;
                showMissingSummary(missingSummary, 0);
            } else {
                MAIN.postDelayed(DexKitScanDialog::hideDialog, 1500L);
            }
        });
    }

    /** 弹出"部分功能未适配"提示, 列出本次扫描未定位到的目标。
     *  v3.0.156：进度弹窗常挂在快速销毁的 WeChatSplashActivity 上，导致完成提示随 Splash 一起消失。
     *  这里在弹窗时选取"当前未销毁的 Activity"（优先 LauncherUI），Splash 已销毁则延时重试。 */
    private static void showMissingSummary(String summary, int attempt) {
        try {
            android.app.Activity host = pickLiveActivity();
            if (host == null) {
                if (attempt < 25) {
                    MAIN.postDelayed(() -> showMissingSummary(summary, attempt + 1), 800L);
                } else {
                    android.app.Activity any = com.leshao.v3.MainHook.currentActivity();
                    if (any != null && !any.isFinishing()) host = any;
                    if (host == null) {
                        com.leshao.v3.LogWriter.logSync("DexKitScanDialog",
                                "showMissingSummary: no live activity, give up");
                        return;
                    }
                }
            }
            if (host == null) return;
            hideDialog();
            TextView tv = new TextView(host);
            tv.setText(summary);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setPadding(dp(host, 8), dp(host, 8), dp(host, 8), dp(host, 8));
            ScrollView sv = new ScrollView(host);
            sv.addView(tv);
            new AlertDialog.Builder(host)
                    .setTitle("部分功能未适配")
                    .setView(sv)
                    .setPositiveButton("我知道了", null)
                    .setCancelable(true)
                    .show();
            com.leshao.v3.LogWriter.logSync("DexKitScanDialog",
                    "missing summary shown on " + host.getClass().getName());
        } catch (Throwable t) {
            com.leshao.v3.LogWriter.log("DexKitScanDialog", "showMissingSummary err: " + t.getMessage());
            if (attempt < 8) {
                MAIN.postDelayed(() -> showMissingSummary(summary, attempt + 1), 800L);
            }
        }
    }

    /** 选一个当前存活、可用的 Activity：优先记录的主机(非 Splash)，其次 MainHook 当前 Activity。 */
    private static android.app.Activity pickLiveActivity() {
        android.app.Activity act = sHostAct != null ? sHostAct.get() : null;
        String cn = act != null ? act.getClass().getName() : "";
        boolean splash = cn.contains("WeChatSplashActivity");
        if (act != null && !act.isFinishing() && !splash) return act;
        try {
            android.app.Activity cur = com.leshao.v3.MainHook.currentActivity();
            if (cur != null && !cur.isFinishing()
                    && !cur.getClass().getName().contains("WeChatSplashActivity")) {
                sHostAct = new java.lang.ref.WeakReference<>(cur);
                return cur;
            }
        } catch (Throwable ignored) {}
        // v3.0.158：不再回退到 Splash。Splash 上弹窗会随其销毁而消失/错位。
        // 仍为 Splash 时返回 null，让上层继续重试直到真正的宿主页(LauncherUI/朋友圈/聊天)出现。
        return null;
    }

    /** 关闭弹窗但不清空可用状态(自动关闭用, 不锁定后续再次展示)。 */
    public static void hideDialog() {
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
