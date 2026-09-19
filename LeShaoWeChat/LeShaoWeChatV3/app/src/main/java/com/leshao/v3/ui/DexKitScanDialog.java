package com.leshao.v3.ui;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
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

public class DexKitScanDialog {

    private static volatile AlertDialog sDialog;
    private static volatile LinearLayout sStepContainer;
    private static volatile TextView sTitleText;
    private static volatile TextView sCloseButton;
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
                    window.setDimAmount(0.7f);
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
                // Mark steps as done based on progress
                if (sTotalSteps > 0) {
                    int doneCount = Math.min((int) (percent / 100.0 * sTotalSteps), sTotalSteps);
                    for (int i = 0; i < sSteps.size(); i++) {
                        StepEntry step = sSteps.get(i);
                        if (i < doneCount && !step.done) {
                            step.done = true;
                            if (step.checkMark != null) step.checkMark.setText("✓");
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

    /** 扫描完成: 显示关闭按钮, 允许用户关闭弹窗 */
    public static void onScanComplete() {
        sScanFinished = true;
        MAIN.post(() -> {
            try {
                if (sCloseButton != null) sCloseButton.setVisibility(View.VISIBLE);
            } catch (Throwable ignored) {}
        });
    }

    public static boolean isShowing() {
        return sDialog != null && sDialog.isShowing();
    }

    private static AlertDialog buildDialog(Context ctx) {
        int cardBg = AppColors.CARD_BG;
        int textTitle = AppColors.TEXT_TITLE;
        int textBody = AppColors.TEXT_BODY;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 24), dp(ctx, 20), dp(ctx, 24), dp(ctx, 16));
        root.setBackgroundColor(cardBg);

        // Title
        sTitleText = new TextView(ctx);
        sTitleText.setText("LeShaoV3 功能扫描");
        sTitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        sTitleText.setTextColor(textTitle);
        sTitleText.setTypeface(null, Typeface.BOLD);
        sTitleText.setPadding(0, 0, 0, dp(ctx, 4));
        root.addView(sTitleText);

        // Subtitle
        TextView subtitle = new TextView(ctx);
        subtitle.setText("动态适配微信混淆类名");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setTextColor(AppColors.TEXT_NOTE);
        subtitle.setPadding(0, 0, 0, dp(ctx, 12));
        root.addView(subtitle);

        // Progress bar
        sProgressBar = new NeonProgressBar(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 10));
        lp.setMargins(0, 0, 0, dp(ctx, 12));
        sProgressBar.setLayoutParams(lp);
        root.addView(sProgressBar);

        // Step container (vertical list)
        sStepContainer = new LinearLayout(ctx);
        sStepContainer.setOrientation(LinearLayout.VERTICAL);
        sStepContainer.setPadding(0, dp(ctx, 8), 0, 0);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sStepContainer.setLayoutParams(scLp);
        root.addView(sStepContainer);

        // Add steps
        for (StepEntry step : sSteps) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(ctx, 4), dp(ctx, 5), dp(ctx, 4), dp(ctx, 5));

            // Check mark
            step.checkMark = new TextView(ctx);
            step.checkMark.setText("");
            step.checkMark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            step.checkMark.setTextColor(AppColors.TEXT_NOTE);
            step.checkMark.setWidth(dp(ctx, 28));
            row.addView(step.checkMark);

            // Step text
            LinearLayout textCol = new LinearLayout(ctx);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textColLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textCol.setLayoutParams(textColLp);

            step.label = new TextView(ctx);
            step.label.setText(step.name);
            step.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            step.label.setTextColor(textBody);
            textCol.addView(step.label);

            if (step.detail != null && !step.detail.isEmpty()) {
                TextView detailTv = new TextView(ctx);
                detailTv.setText(step.detail);
                detailTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                detailTv.setTextColor(AppColors.TEXT_NOTE);
                textCol.addView(detailTv);
            }

            row.addView(textCol);
            sStepContainer.addView(row);
        }

        // Close button (initially hidden, shown after scan)
        TextView closeBtn = new TextView(ctx);
        closeBtn.setText("关闭");
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        closeBtn.setTextColor(textTitle);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10));
        closeBtn.setBackgroundColor(Color.parseColor("#33FFFFFF"));
        closeBtn.setOnClickListener(v -> dismiss());
        closeBtn.setVisibility(View.GONE);
        sCloseButton = closeBtn;
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.setMargins(0, dp(ctx, 16), 0, 0);
        closeBtn.setLayoutParams(closeLp);
        root.addView(closeBtn);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
            .setView(root)
            .create();

        return dialog;
    }

    private static int dp(Context ctx, int v) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }

    private static class NeonProgressBar extends View {
        private int mProgress = 0;
        private int mDisplayProgress = 0;
        private ValueAnimator mAnimator;
        private final Paint mBgPaint;
        private final Paint mProgressPaint;
        private final Paint mGlowPaint;
        private final RectF mRect;
        private final int[] mColors = {
            Color.parseColor("#FF6B9D"),
            Color.parseColor("#C44DDA"),
            Color.parseColor("#00D4FF"),
            Color.parseColor("#FFE66D"),
            Color.parseColor("#FF6B9D"),
        };

        public NeonProgressBar(Context context) {
            super(context);
            mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mBgPaint.setColor(Color.parseColor("#22FFFFFF"));
            mBgPaint.setStyle(Paint.Style.FILL);

            mProgressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mProgressPaint.setStyle(Paint.Style.FILL);

            mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mGlowPaint.setStyle(Paint.Style.FILL);
            mGlowPaint.setColor(Color.parseColor("#66FF6B9D"));

            mRect = new RectF();
        }

        public void setProgress(int progress) {
            progress = Math.max(0, Math.min(100, progress));
            if (progress == mProgress) return;
            mProgress = progress;

            if (mAnimator != null) mAnimator.cancel();
            mAnimator = ValueAnimator.ofInt(mDisplayProgress, progress);
            mAnimator.setDuration(400);
            mAnimator.addUpdateListener(animation -> {
                mDisplayProgress = (int) animation.getAnimatedValue();
                updateGradient();
                invalidate();
            });
            mAnimator.start();
        }

        private void updateGradient() {
            float width = getWidth();
            if (width <= 0) return;
            float progressWidth = width * mDisplayProgress / 100f;
            float offset = width * (System.currentTimeMillis() % 2000) / 2000f;

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
                shinePaint.setColor(Color.parseColor("#40FFFFFF"));
                mRect.set(0, 0, progressWidth, height / 2f);
                canvas.drawRoundRect(mRect, radius, radius, shinePaint);
            }
        }
    }
}
