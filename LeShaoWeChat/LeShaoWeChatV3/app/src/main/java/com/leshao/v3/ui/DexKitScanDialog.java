package com.leshao.v3.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * DexKit 扫描进度弹窗（v955 M3 重排）。
 * 静态 API（show/initSteps/updateProgress/dismiss/onScanComplete/isShowing）与原版完全一致，
 * DexKitHelper 调用点零改动；仅视觉切换为 M3（28dp 对话框/主色进度条/M3 步骤行/ModernButton）。
 */
public class DexKitScanDialog {

    private static volatile AlertDialog sDialog;
    private static volatile LinearLayout sStepContainer;
    private static volatile TextView sTitleText;
    private static volatile TextView sPercentText;
    private static volatile NeonProgressBar sProgressBar;
    private static volatile boolean sDismissed = false;
    private static volatile boolean sScanFinished = false;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<StepEntry> sSteps = new ArrayList<>();
    private static int sTotalSteps = 0;

    private static class StepEntry {
        String name;
        String detail;
        TextView label;
        TextView checkMark;
        boolean done;
        StepEntry(String name, String detail) {
            this.name = name;
            this.detail = detail;
            this.done = false;
        }
    }

    public static void show(Context ctx) {
        if (sDismissed) return;
        MAIN.post(() -> {
            try {
                if (sDialog != null && sDialog.isShowing()) return;
                sSteps.clear();
                sScanFinished = false;
                sDialog = buildDialog(ctx);
                sDialog.setCancelable(false);
                sDialog.show();
                Window window = sDialog.getWindow();
                if (window != null) {
                    window.setDimAmount(0.6f);
                    WindowManager.LayoutParams lp = window.getAttributes();
                    lp.width = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.9f);
                    window.setAttributes(lp);
                }
            } catch (Throwable t) {
                com.leshao.v3.LogWriter.log("DexKitScanDialog", "show err: " + t.getMessage());
            }
        });
    }

    public static void initSteps(String[] names, String[] details) {
        sSteps.clear();
        sTotalSteps = names.length;
        for (int i = 0; i < names.length; i++) {
            sSteps.add(new StepEntry(names[i], details != null && i < details.length ? details[i] : ""));
        }
    }

    public static void updateProgress(int percent, String status, String detail) {
        if (sDismissed) return;
        MAIN.post(() -> {
            try {
                if (sProgressBar != null) sProgressBar.setProgress(percent);
                if (sPercentText != null) sPercentText.setText(percent + "%");
                // Mark steps as done based on progress
                if (sTotalSteps > 0) {
                    int doneCount = Math.min((int) (percent / 100.0 * sTotalSteps), sTotalSteps);
                    for (int i = 0; i < sSteps.size(); i++) {
                        StepEntry step = sSteps.get(i);
                        if (i < doneCount && !step.done) {
                            step.done = true;
                            if (step.checkMark != null) {
                                step.checkMark.setText("✓");
                                step.checkMark.setTextColor(AppColors.primary());
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        });
    }

    public static void dismiss() {
        sDismissed = true;
        sScanFinished = false;
        MAIN.post(() -> {
            try {
                if (sDialog != null && sDialog.isShowing()) sDialog.dismiss();
                sDialog = null;
            } catch (Throwable ignored) {}
        });
    }

    /** 扫描完成: 直接补到 100% 并自动关闭弹窗(无需手动关闭)。 */
    public static void onScanComplete() {
        sScanFinished = true;
        MAIN.post(() -> {
            try {
                if (sProgressBar != null) sProgressBar.setProgress(100);
                if (sPercentText != null) sPercentText.setText("100%");
                for (StepEntry step : sSteps) {
                    step.done = true;
                    if (step.checkMark != null) {
                        step.checkMark.setText("✓");
                        step.checkMark.setTextColor(AppColors.primary());
                    }
                }
                MAIN.postDelayed(DexKitScanDialog::hideDialog, 700L);
            } catch (Throwable ignored) {}
        });
    }

    /** 关闭弹窗但不清空可用状态(自动关闭用, 不锁定后续再次展示)。 */
    private static void hideDialog() {
        sScanFinished = false;
        MAIN.post(() -> {
            try {
                if (sDialog != null && sDialog.isShowing()) sDialog.dismiss();
                sDialog = null;
            } catch (Throwable ignored) {}
        });
    }

    public static boolean isShowing() {
        return sDialog != null && sDialog.isShowing();
    }

    private static AlertDialog buildDialog(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 14));
        // v955 M3: 28dp extra-large 圆角对话框
        root.setBackground(CandyUi.dialogBg(ctx));
        InsetsUtil.clipRounded(root);

        // Title（M3 headline small）
        sTitleText = new TextView(ctx);
        sTitleText.setText("LeShaoV3 功能扫描");
        sTitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        sTitleText.setTextColor(AppColors.onSurface());
        sTitleText.setPadding(0, 0, 0, dp(ctx, 4));
        root.addView(sTitleText);

        // Subtitle
        TextView subtitle = new TextView(ctx);
        subtitle.setText("动态适配微信混淆类名");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setTextColor(AppColors.onSurfaceVariant());
        subtitle.setPadding(0, 0, 0, dp(ctx, 16));
        root.addView(subtitle);

        // Progress bar（M3 主色）
        sProgressBar = new NeonProgressBar(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 10));
        lp.setMargins(0, 0, 0, dp(ctx, 6));
        sProgressBar.setLayoutParams(lp);
        root.addView(sProgressBar);

        // 百分比文字（居中显示，跟随进度条平滑值刷新）
        sPercentText = new TextView(ctx);
        sPercentText.setText("0%");
        sPercentText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        sPercentText.setTextColor(AppColors.primary());
        sPercentText.setGravity(Gravity.CENTER);
        sPercentText.setPadding(0, dp(ctx, 4), 0, dp(ctx, 12));
        root.addView(sPercentText);
        sProgressBar.setProgressListener(p -> {
            if (sPercentText != null) sPercentText.setText(p + "%");
        });

        // Step container (vertical list)
        sStepContainer = new LinearLayout(ctx);
        sStepContainer.setOrientation(LinearLayout.VERTICAL);
        sStepContainer.setPadding(0, dp(ctx, 4), 0, 0);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sStepContainer.setLayoutParams(scLp);
        root.addView(sStepContainer);

        // Add steps（M3 列表行）
        for (StepEntry step : sSteps) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6));
            row.setBackground(CandyUi.rowPressBg(ctx));

            // Check mark（完成=primary ✓ / 等待=outline ○）
            step.checkMark = new TextView(ctx);
            step.checkMark.setText("○");
            step.checkMark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            step.checkMark.setTextColor(AppColors.outline());
            step.checkMark.setGravity(Gravity.CENTER);
            step.checkMark.setWidth(dp(ctx, 32));
            row.addView(step.checkMark);

            // Step text
            LinearLayout textCol = new LinearLayout(ctx);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textColLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textCol.setLayoutParams(textColLp);

            step.label = new TextView(ctx);
            step.label.setText(step.name);
            step.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            step.label.setTextColor(AppColors.onSurface());
            textCol.addView(step.label);

            if (step.detail != null && !step.detail.isEmpty()) {
                TextView detailTv = new TextView(ctx);
                detailTv.setText(step.detail);
                detailTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                detailTv.setTextColor(AppColors.onSurfaceVariant());
                textCol.addView(detailTv);
            }

            row.addView(textCol);
            sStepContainer.addView(row);
        }

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

    /** M3 主色进度条：surfaceContainerHighest 轨道 + 流光渐变进度 + 主色光晕；
     *  显示值由每帧缓动追随目标值，跳变式上报也能丝滑过渡。 */
    private static class NeonProgressBar extends View {
        interface ProgressListener {
            void onDisplay(int percent);
        }

        private float mProgress = 0f;
        private float mDisplayProgress = 0f;
        private ProgressListener mListener;
        private boolean mRunning;
        private final Paint mBgPaint;
        private final Paint mProgressPaint;
        private final Paint mGlowPaint;
        private final RectF mRect;
        // v1067 葡萄气泡：扫描进度条走品牌流光渐变
        private final int[] mColors = {
                AppColors.gradientStart(),
                AppColors.gradientMid(),
                AppColors.gradientEnd(),
                AppColors.gradientMid(),
                AppColors.gradientStart(),
        };

        public NeonProgressBar(Context context) {
            super(context);
            mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mBgPaint.setColor(AppColors.surfaceContainerHighest());
            mBgPaint.setStyle(Paint.Style.FILL);

            mProgressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mProgressPaint.setStyle(Paint.Style.FILL);

            mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mGlowPaint.setStyle(Paint.Style.FILL);
            mGlowPaint.setColor((AppColors.gradientStart() & 0x00FFFFFF) | 0x66000000);

            mRect = new RectF();
        }

        public void setProgress(int progress) {
            mProgress = Math.max(0f, Math.min(100f, progress));
            startLoop();
        }

        void setProgressListener(ProgressListener listener) {
            mListener = listener;
        }

        private final Runnable mTick = new Runnable() {
            @Override
            public void run() {
                if (!mRunning) return;
                float diff = mProgress - mDisplayProgress;
                if (Math.abs(diff) < 0.2f) {
                    mDisplayProgress = mProgress;
                } else {
                    mDisplayProgress += diff * 0.14f;
                }
                if (mListener != null) {
                    mListener.onDisplay(Math.round(mDisplayProgress));
                }
                invalidate();
                postOnAnimation(this);
            }
        };

        private void startLoop() {
            removeCallbacks(mTick);
            mRunning = true;
            postOnAnimation(mTick);
        }

        private void stopLoop() {
            mRunning = false;
            removeCallbacks(mTick);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            startLoop();
        }

        @Override
        protected void onDetachedFromWindow() {
            stopLoop();
            super.onDetachedFromWindow();
        }

        private void updateGradient() {
            float width = getWidth();
            if (width <= 0) return;
            float progressWidth = width * mDisplayProgress / 100f;
            float offset = width * (android.os.SystemClock.uptimeMillis() % 2000) / 2000f;

            LinearGradient gradient = new LinearGradient(
                    -offset, 0, progressWidth + offset, 0,
                    mColors, null, Shader.TileMode.CLAMP
            );
            mProgressPaint.setShader(gradient);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            float radius = height / 2f;

            mRect.set(0, 0, width, height);
            canvas.drawRoundRect(mRect, radius, radius, mBgPaint);

            if (mDisplayProgress > 0) {
                float progressWidth = width * mDisplayProgress / 100f;

                mRect.set(0, -height * 0.5f, progressWidth, height * 1.5f);
                canvas.drawRoundRect(mRect, radius, radius, mGlowPaint);

                mRect.set(0, 0, progressWidth, height);
                updateGradient();
                canvas.drawRoundRect(mRect, radius, radius, mProgressPaint);

                Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                shinePaint.setColor(0x40FFFFFF);
                mRect.set(0, 0, progressWidth, height / 2f);
                canvas.drawRoundRect(mRect, radius, radius, shinePaint);
            }
        }
    }
}
