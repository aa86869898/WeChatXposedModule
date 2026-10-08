package com.leshao.v3.ui.widgets;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.leshao.v3.ui.AppColors;

/**
 * v30021: 播放器「音乐碟片」动画（第 10 套「玻璃高光」）。
 *
 * <p>淡色渐变盘体持续旋转，叠一条随盘扫过的斜向镜面高光带与一枚偏左上的硬高光点，
 * 盘体外部有随主题变化的柔光；盘心圆形为封面位（后续可替换为真实封面），与盘体同步
 * 旋转。</p>
 */
public class DiscView extends View {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHilite = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCover = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNote = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mMatrix = new Matrix();

    private ValueAnimator mAnim;
    private float mAngle;
    private int mBuiltW, mBuiltH;

    private android.graphics.Bitmap mCoverBitmap;
    private final android.graphics.Path mClipPath = new android.graphics.Path();
    private final android.graphics.Rect mSrcRect = new android.graphics.Rect();
    private final android.graphics.RectF mDstRect = new android.graphics.RectF();

    private LinearGradient mBodyGrad;
    private LinearGradient mSheenGrad;
    private RadialGradient mHiliteGrad;
    private RadialGradient mGlowGrad;
    private LinearGradient mCoverGrad;

    public DiscView(Context ctx) {
        super(ctx);
        mNote.setColor(0xFFFFFFFF);
        mNote.setTextAlign(Paint.Align.CENTER);
        mNote.setFakeBoldText(true);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    public void start() {
        if (mAnim != null && mAnim.isStarted()) return;
        mAnim = ValueAnimator.ofFloat(0f, 360f);
        mAnim.setDuration(12000L);
        mAnim.setRepeatCount(ValueAnimator.INFINITE);
        mAnim.setInterpolator(new LinearInterpolator());
        mAnim.addUpdateListener(a -> {
            mAngle = (float) a.getAnimatedValue();
            invalidate();
        });
        mAnim.start();
    }

    public void stop() {
        if (mAnim != null) {
            mAnim.cancel();
            mAnim = null;
        }
    }

    /** 设置盘心封面；传 null 恢复渐变占位。 */
    public void setCoverBitmap(android.graphics.Bitmap bm) {
        mCoverBitmap = bm;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        mBuiltW = w;
        mBuiltH = h;
        buildShaders(w, h);
    }

    private void buildShaders(int w, int h) {
        float cx = w / 2f, cy = h / 2f;
        float R = Math.min(w, h) / 2f * 0.90f;
        boolean dark = AppColors.isDarkMode();

        // 盘体：全局糖果粉纯色（浅色/暗色一致）
        int c1 = AppColors.gradientStart();
        int c2 = AppColors.gradientStart();
        int c3 = AppColors.gradientStart();
        mBodyGrad = new LinearGradient(cx - R, cy - R, cx + R, cy + R,
                new int[]{c1, c2, c3}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);

        // 斜向镜面高光带
        mSheenGrad = new LinearGradient(cx - R, cy + R, cx + R, cy - R,
                new int[]{0x00FFFFFF, 0x88FFFFFF, 0x00FFFFFF},
                new float[]{0.38f, 0.50f, 0.62f}, Shader.TileMode.CLAMP);

        // 偏左上的硬高光点
        float hx = cx - R * 0.48f, hy = cy - R * 0.56f;
        mHiliteGrad = new RadialGradient(hx, hy, R * 0.62f,
                new int[]{0xFFFFFFFF, 0xE0FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.16f, 1f}, Shader.TileMode.CLAMP);

        // 外部柔光
        int glowC = (AppColors.gradientEnd() & 0x00FFFFFF) | 0x44000000;
        mGlowGrad = new RadialGradient(cx, cy, R * 0.8f,
                new int[]{glowC, (glowC & 0x00FFFFFF)}, new float[]{0f, 1f}, Shader.TileMode.CLAMP);

        // 盘心封面位
        mCoverGrad = new LinearGradient(cx - R * 0.4f, cy - R * 0.4f, cx + R * 0.4f, cy + R * 0.4f,
                new int[]{AppColors.gradientStart(), AppColors.gradientEnd()}, null,
                Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (w != mBuiltW || h != mBuiltH) buildShaders(w, h);

        float cx = w / 2f, cy = h / 2f;
        float R = Math.min(w, h) / 2f * 0.90f;

        // 外部柔光（不随盘旋转）
        mGlow.setShader(mGlowGrad);
        canvas.drawCircle(cx, cy, R * 1.22f, mGlow);

        mMatrix.setRotate(mAngle, cx, cy);

        // 盘体（淡色渐变，旋转）
        mBodyGrad.setLocalMatrix(mMatrix);
        mPaint.setShader(mBodyGrad);
        canvas.drawCircle(cx, cy, R, mPaint);

        // 斜向镜面高光带（旋转）
        mSheenGrad.setLocalMatrix(mMatrix);
        mSheen.setShader(mSheenGrad);
        canvas.drawCircle(cx, cy, R, mSheen);

        // 硬高光点（旋转）
        mHiliteGrad.setLocalMatrix(mMatrix);
        mHilite.setShader(mHiliteGrad);
        canvas.drawCircle(cx, cy, R, mHilite);

        // 盘心封面位（与盘体同步旋转）
        float cr = R * 0.44f;
        canvas.save();
        canvas.rotate(mAngle, cx, cy);
        if (mCoverBitmap != null && !mCoverBitmap.isRecycled()) {
            mClipPath.reset();
            mClipPath.addCircle(cx, cy, cr, android.graphics.Path.Direction.CW);
            canvas.clipPath(mClipPath);
            int bw = mCoverBitmap.getWidth(), bh = mCoverBitmap.getHeight();
            float side = Math.min(bw, bh);
            if (side > 0) {
                int l = (int) ((bw - side) / 2f), t = (int) ((bh - side) / 2f);
                mSrcRect.set(l, t, l + (int) side, t + (int) side);
                mDstRect.set(cx - cr, cy - cr, cx + cr, cy + cr);
                mCover.setShader(null);
                canvas.drawBitmap(mCoverBitmap, mSrcRect, mDstRect, mCover);
            }
        } else {
            mCoverGrad.setLocalMatrix(mMatrix);
            mCover.setShader(mCoverGrad);
            canvas.drawCircle(cx, cy, cr, mCover);
            mNote.setTextSize(cr * 1.0f);
            canvas.drawText("\u266A", cx, cy - (mNote.descent() + mNote.ascent()) / 2f, mNote);
        }
        canvas.restore();

        // 中心轴孔
        mPaint.setShader(null);
        mPaint.setColor(0xCCFFFFFF);
        canvas.drawCircle(cx, cy, cr * 0.16f, mPaint);
    }
}
