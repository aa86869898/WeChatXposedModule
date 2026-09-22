package com.leshao.v3.ui.widgets;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.leshao.v3.ui.AppColors;

/** 模块统一加载动画：主色圆弧旋转 */
public class LoadingView extends View {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float mSweepAngle = 30f;
    private ValueAnimator mAnimator;

    public LoadingView(Context ctx) {
        super(ctx);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public LoadingView sizeDp(int dp) {
        float d = getResources().getDisplayMetrics().density;
        mPaint.setStrokeWidth(2.5f * d);
        return this;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            mAnimator = ValueAnimator.ofFloat(0f, 360f);
            mAnimator.setDuration(900);
            mAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mAnimator.setInterpolator(new LinearInterpolator());
            mAnimator.addUpdateListener(a -> {
                mSweepAngle = (float) a.getAnimatedValue();
                invalidate();
            });
            mAnimator.start();
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        try { if (mAnimator != null) mAnimator.cancel(); } catch (Throwable ignored) {}
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        try {
            float pad = mPaint.getStrokeWidth() / 2f + 1f;
            float r = Math.min(getWidth(), getHeight()) / 2f - pad;
            if (r <= 0) return;
            mPaint.setColor(AppColors.primary());
            canvas.drawArc(pad, pad, getWidth() - pad, getHeight() - pad,
                mSweepAngle, 90f, false, mPaint);
            mPaint.setColor(AppColors.stroke());
            canvas.drawArc(pad, pad, getWidth() - pad, getHeight() - pad,
                mSweepAngle + 90f, 270f, false, mPaint);
        } catch (Throwable ignored) {}
    }
}
