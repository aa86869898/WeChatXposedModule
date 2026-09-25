package com.leshao.v3.ui;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.animation.LinearInterpolator;

import java.util.WeakHashMap;

/**
 * v1067 葡萄气泡 · 流光渐变 Drawable。
 *
 * <p>三色（紫 → 淡紫 → 粉）循环渐变，并以周期平移形成「流光」动效。渐变的颜色函数
 * 以自身宽度为一个周期，平移一周后与起始画面完全重合，因此循环无缝、无跳变。</p>
 *
 * <p>实现要点：单例全局 {@link android.animation.ValueAnimator} 驱动所有可见实例
 * （{@link #onVisible} 注册/注销，WeakHashMap 防止泄漏），避免每个控件各起一个动画。
 * 动画只做 {@code invalidateSelf()}，绘制时用 {@link Matrix} 平移 shader，零冗余分配。</p>
 *
 * <p>仅用于皮肤绘制，不承载任何业务语义。</p>
 */
public class FlowingGradientDrawable extends Drawable {

    private static final long DURATION_MS = 3200L;
    private static final WeakHashMap<FlowingGradientDrawable, Boolean> ACTIVE = new WeakHashMap<>();
    private static android.animation.ValueAnimator sTicker;

    private final int[] mColors;
    private final int[] mSamples = new int[10];
    private final float[] mPositions = new float[10];
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mMatrix = new Matrix();
    private final float[] mRadiiBuf = new float[8];

    private float mCornerRadius;
    private float[] mCornerRadii;
    private float mStrokeWidth;
    private int mStrokeColor = 0;
    private int mSizeW = -1;
    private int mSizeH = -1;
    private float mPhaseOffset;
    private boolean mAnimated = true;

    private LinearGradient mShader;
    private int mShaderWidth = -1;
    private int mShaderLeft = Integer.MIN_VALUE;
    private final android.graphics.RectF mRect = new android.graphics.RectF();
    private final android.graphics.RectF mStrokeRect = new android.graphics.RectF();
    private final android.graphics.Path mPath = new android.graphics.Path();

    public FlowingGradientDrawable(int start, int mid, int end) {
        mColors = new int[]{start, mid, end};
        for (int i = 0; i < mSamples.length; i++) {
            mSamples[i] = mColors[i % 3];
            mPositions[i] = i / (float) (mSamples.length - 1);
        }
    }

    /** 圆角半径（px） */
    public FlowingGradientDrawable setCornerRadius(float radiusPx) {
        mCornerRadius = Math.max(0f, radiusPx);
        mCornerRadii = null;
        invalidateSelf();
        return this;
    }

    /** 每角圆角半径（px），顺序 [tl, tr, br, bl]，用于顶栏等只需部分圆角的场景 */
    public FlowingGradientDrawable setCornerRadii(float[] radii) {
        if (radii == null || radii.length != 4) return this;
        mCornerRadii = new float[]{
                Math.max(0f, radii[0]), Math.max(0f, radii[1]),
                Math.max(0f, radii[2]), Math.max(0f, radii[3])};
        invalidateSelf();
        return this;
    }

    /** 描边（px + 颜色），宽度 <=0 表示无描边 */
    public FlowingGradientDrawable setStroke(float widthPx, int color) {
        mStrokeWidth = Math.max(0f, widthPx);
        mStrokeColor = color;
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeWidth(mStrokeWidth);
        mStrokePaint.setColor(mStrokeColor);
        invalidateSelf();
        return this;
    }

    /** 提供 intrinsic 尺寸，供 StateListDrawable / 框架控件测量时使用 */
    public FlowingGradientDrawable setSize(int w, int h) {
        mSizeW = w;
        mSizeH = h;
        return this;
    }

    /** 关闭/开启流光动效（关闭后为静态渐变，用于大面积底色等场景） */
    public FlowingGradientDrawable setAnimated(boolean animated) {
        if (mAnimated == animated) return this;
        mAnimated = animated;
        if (!animated) unregister(this);
        else if (isVisible()) register(this);
        return this;
    }

    /** 相位偏移（0~1），让多个控件不同步流动 */
    public FlowingGradientDrawable setPhaseOffset(float offset) {
        mPhaseOffset = offset;
        invalidateSelf();
        return this;
    }

    @Override
    public int getIntrinsicWidth() {
        return mSizeW;
    }

    @Override
    public int getIntrinsicHeight() {
        return mSizeH;
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return mPaint.getAlpha();
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        mShader = null;
        mShaderWidth = -1;
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (visible && mAnimated) register(this);
        else unregister(this);
        return changed;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) return;

        // 安全网：只要被绘制就确保已接入全局动画驱动（部分宿主不回调 setVisible）
        if (mAnimated) register(this);

        if (mShader == null || mShaderWidth != w || mShaderLeft != b.left) {
            mShader = new LinearGradient(b.left, 0f, b.left + 3f * w, 0f,
                    mSamples, mPositions, Shader.TileMode.CLAMP);
            mShaderWidth = w;
            mShaderLeft = b.left;
        }
        float offset = (mAnimated ? phase(0f) : 0f) + mPhaseOffset;
        offset = offset - (float) Math.floor(offset);
        mMatrix.setTranslate(-offset * w, 0f);
        mShader.setLocalMatrix(mMatrix);
        mPaint.setShader(mShader);

        mRect.set(b.left, b.top, b.right, b.bottom);
        if (mCornerRadii != null) {
            float maxR = Math.min(w, h) / 2f;
            float tl = Math.min(mCornerRadii[0], maxR);
            float tr = Math.min(mCornerRadii[1], maxR);
            float br = Math.min(mCornerRadii[2], maxR);
            float bl = Math.min(mCornerRadii[3], maxR);
            mRadiiBuf[0] = tl; mRadiiBuf[1] = tl;
            mRadiiBuf[2] = tr; mRadiiBuf[3] = tr;
            mRadiiBuf[4] = br; mRadiiBuf[5] = br;
            mRadiiBuf[6] = bl; mRadiiBuf[7] = bl;
            mPath.reset();
            mPath.addRoundRect(mRect, mRadiiBuf, android.graphics.Path.Direction.CW);
            canvas.drawPath(mPath, mPaint);
            if (mStrokeWidth > 0) {
                float half = mStrokeWidth / 2f;
                mStrokeRect.set(mRect.left + half, mRect.top + half,
                        mRect.right - half, mRect.bottom - half);
                mPath.reset();
                mPath.addRoundRect(mStrokeRect, mRadiiBuf, android.graphics.Path.Direction.CW);
                canvas.drawPath(mPath, mStrokePaint);
            }
        } else {
            float r = Math.min(mCornerRadius, Math.min(w, h) / 2f);
            canvas.drawRoundRect(mRect, r, r, mPaint);
            if (mStrokeWidth > 0) {
                float half = mStrokeWidth / 2f;
                mStrokeRect.set(mRect.left + half, mRect.top + half,
                        mRect.right - half, mRect.bottom - half);
                float rs = Math.max(0f, r - half);
                canvas.drawRoundRect(mStrokeRect, rs, rs, mStrokePaint);
            }
        }
    }

    // ==================== 全局动画驱动 ====================

    private static float phase(float extra) {
        return ((SystemClock.uptimeMillis() % DURATION_MS) / (float) DURATION_MS + extra) % 1f;
    }

    private static void register(FlowingGradientDrawable d) {
        ACTIVE.put(d, Boolean.TRUE);
        if (sTicker == null) {
            sTicker = android.animation.ValueAnimator.ofFloat(0f, 1f);
            sTicker.setDuration(DURATION_MS);
            sTicker.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            sTicker.setInterpolator(new LinearInterpolator());
            sTicker.addUpdateListener(animation -> {
                // 快照遍历：绘制/注册过程可能在同一帧内改动 ACTIVE，避免并发修改
                FlowingGradientDrawable[] snapshot = ACTIVE.keySet().toArray(new FlowingGradientDrawable[0]);
                for (FlowingGradientDrawable x : snapshot) {
                    if (x != null) {
                        try {
                            x.invalidateSelf();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        }
        if (!sTicker.isStarted()) {
            try {
                sTicker.start();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void unregister(FlowingGradientDrawable d) {
        ACTIVE.remove(d);
        if (ACTIVE.isEmpty() && sTicker != null) {
            try {
                sTicker.cancel();
            } catch (Throwable ignored) {
            }
            sTicker = null;
        }
    }
}
