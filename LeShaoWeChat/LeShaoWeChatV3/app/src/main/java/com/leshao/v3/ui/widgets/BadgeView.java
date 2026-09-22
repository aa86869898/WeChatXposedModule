package com.leshao.v3.ui.widgets;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import com.leshao.v3.ui.AppColors;

/** 数字角标：红底白字小圆点，count<=0 隐藏 */
public class BadgeView extends View {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mCount = 0;
    private float mDensity;

    public BadgeView(Context ctx) {
        super(ctx);
        mDensity = getResources().getDisplayMetrics().density;
        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setTextSize(10 * mDensity);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setFakeBoldText(true);
    }

    public BadgeView setCount(int c) {
        mCount = c;
        setVisibility(c > 0 ? VISIBLE : GONE);
        invalidate();
        return this;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mCount <= 0) return;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy);
        mPaint.setColor(AppColors.danger());
        canvas.drawCircle(cx, cy, r, mPaint);
        String t = mCount > 99 ? "99+" : String.valueOf(mCount);
        float baseline = cy - (mTextPaint.descent() + mTextPaint.ascent()) / 2f;
        canvas.drawText(t, cx, baseline, mTextPaint);
    }
}
