package com.leshao.v3.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * 流光进度条（v1138 样式定稿 X6/Y6）：
 *   · 轨道胶囊 + 左→右填充（模块统一主题色渐变，循环流动）
 *   · 填充内叠加「流动虚线」装饰元素
 *   · 领先端点处一只斜向上飞行的鸟（侧面剪影，双翅 + 收拢双脚），
 *     填充使用与进度条同一套动态渐变
 *   · 百分比在条下方（由调用方 TextView 承担，见 setProgressListener）
 *
 * 自绘、无资源依赖；显示值缓动追随目标值，跳变上报也能丝滑过渡。
 */
public class FlyingProgressBar extends View {

    public interface ProgressListener {
        void onDisplay(int percent);
    }

    /** 可拖动模式下的拖拽回调（percent 均为 0–100）。 */
    public interface OnSeekListener {
        void onSeekStart();
        void onSeek(int percent);
        void onSeekEnd(int percent);
    }

    private float mTarget = 0f;
    private float mDisplay = 0f;
    private ProgressListener mListener;
    private boolean mRunning;

    private boolean mSeekable;
    private boolean mDragging;
    private OnSeekListener mSeekListener;

    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBirdPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBirdFarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFeetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBeakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEyePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEyeHiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF mRect = new RectF();
    private final Matrix mBarMat = new Matrix();
    private final Matrix mBirdMat = new Matrix();
    private final Matrix mBirdXform = new Matrix();
    private final Path mTmp = new Path();
    private final Matrix mWingMat = new Matrix();
    private final Path mWingTmp = new Path();

    private int mBarHeightPx;

    // 主题渐变（模块统一）
    private int[] mColors;

    // 鸟的各部件（局部坐标 96×72）
    private Path mFarWing, mTail, mBody, mNearWing, mHead, mBeak, mFeet, mEye, mEyeHi;

    public FlyingProgressBar(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        mBarHeightPx = Math.round(12 * density);

        int g1 = AppColors.gradientStart();
        int g2 = AppColors.gradientMid();
        int g3 = AppColors.gradientEnd();
        mColors = new int[]{g1, g2, g3, g2, g1};

        mTrackPaint.setColor(AppColors.surfaceContainerHighest());
        mTrackPaint.setStyle(Paint.Style.FILL);

        mFillPaint.setStyle(Paint.Style.FILL);
        mDashPaint.setColor(0xA6FFFFFF);
        mDashPaint.setStyle(Paint.Style.FILL);

        mGlowPaint.setStyle(Paint.Style.FILL);
        mGlowPaint.setColor((g3 & 0x00FFFFFF) | 0x55000000);

        mBirdPaint.setStyle(Paint.Style.FILL);
        mBirdFarPaint.setStyle(Paint.Style.FILL);
        mBirdFarPaint.setAlpha(184);
        mFeetPaint.setStyle(Paint.Style.STROKE);
        mFeetPaint.setStrokeCap(Paint.Cap.ROUND);
        mFeetPaint.setStrokeJoin(Paint.Join.ROUND);
        mFeetPaint.setStrokeWidth(2.4f * density);

        mBeakPaint.setColor(0xFFFFB25E);
        mBeakPaint.setStyle(Paint.Style.FILL);
        mEyePaint.setColor(0xFF2F2A45);
        mEyePaint.setStyle(Paint.Style.FILL);
        mEyeHiPaint.setColor(0xFFFFFFFF);
        mEyeHiPaint.setStyle(Paint.Style.FILL);

        buildBird();
    }

    private void buildBird() {
        mFarWing = new Path();
        mFarWing.moveTo(44, 37);
        mFarWing.cubicTo(38, 19, 25, 8, 8, 12);
        mFarWing.cubicTo(20, 22, 33, 28, 38, 43);
        mFarWing.close();

        mTail = new Path();
        mTail.moveTo(22, 42);
        mTail.lineTo(2, 32);
        mTail.lineTo(16, 41);
        mTail.lineTo(0, 50);
        mTail.lineTo(22, 46);
        mTail.close();

        mBody = new Path();
        mBody.moveTo(20, 42);
        mBody.cubicTo(30, 31, 56, 29, 68, 35);
        mBody.cubicTo(75, 38, 75, 44, 66, 47);
        mBody.cubicTo(52, 51, 30, 51, 20, 42);
        mBody.close();

        mNearWing = new Path();
        mNearWing.moveTo(46, 39);
        mNearWing.cubicTo(49, 16, 63, 4, 82, 6);
        mNearWing.cubicTo(70, 16, 57, 27, 53, 43);
        mNearWing.close();

        mHead = new Path();
        mHead.addOval(new RectF(60, 23, 80, 43), Path.Direction.CW);

        mBeak = new Path();
        mBeak.moveTo(78, 30);
        mBeak.lineTo(93, 34.5f);
        mBeak.lineTo(78, 39);
        mBeak.close();

        mFeet = new Path();
        mFeet.moveTo(40, 46);
        mFeet.cubicTo(35, 50, 29, 51, 24, 50);
        mFeet.moveTo(24, 50); mFeet.lineTo(19, 51);
        mFeet.moveTo(24, 50); mFeet.lineTo(23, 53);
        mFeet.moveTo(45, 48);
        mFeet.cubicTo(40, 52, 34, 54, 28, 53);
        mFeet.moveTo(28, 53); mFeet.lineTo(23, 54);
        mFeet.moveTo(28, 53); mFeet.lineTo(27, 56);

        mEye = new Path();
        mEye.addCircle(72, 31, 2f, Path.Direction.CW);
        mEyeHi = new Path();
        mEyeHi.addCircle(72.7f, 30.2f, 0.7f, Path.Direction.CW);
    }

    /** 设置目标进度（0–100） */
    public void setProgress(int progress) {
        mTarget = Math.max(0f, Math.min(100f, progress));
        startLoop();
    }

    public void setProgressListener(ProgressListener listener) {
        mListener = listener;
    }

    /** 开启拖拽调节（播放器进度条）；默认关闭，不影响转码进度弹窗。 */
    public void setSeekable(boolean seekable) {
        mSeekable = seekable;
    }

    public void setOnSeekListener(OnSeekListener listener) {
        mSeekListener = listener;
    }

    /** 拖拽中把条与鸟立即定位到指定百分比（0–100），不走缓动。 */
    private void applyDrag(int percent) {
        mDisplay = Math.max(0f, Math.min(100f, percent));
        mTarget = mDisplay;
        if (mListener != null) mListener.onDisplay(Math.round(mDisplay));
        invalidate();
    }

    private float barLeftPx() {
        return mBarHeightPx * 1.6f;
    }

    private float barRightPx() {
        return getWidth() - mBarHeightPx * 1.6f;
    }

    private int percentAt(float x) {
        float l = barLeftPx();
        float r = barRightPx();
        if (r <= l) return 0;
        return Math.round((x - l) / (r - l) * 100f);
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent e) {
        if (!mSeekable) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                mDragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                if (mSeekListener != null) mSeekListener.onSeekStart();
                applyDrag(percentAt(e.getX()));
                return true;
            case android.view.MotionEvent.ACTION_MOVE:
                if (mDragging) {
                    int p = percentAt(e.getX());
                    applyDrag(p);
                    if (mSeekListener != null) mSeekListener.onSeek(p);
                }
                return true;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                if (mDragging) {
                    mDragging = false;
                    int p = percentAt(e.getX());
                    applyDrag(p);
                    if (mSeekListener != null) mSeekListener.onSeekEnd(p);
                }
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            if (!mRunning) return;
            if (!mDragging) {
                float diff = mTarget - mDisplay;
                if (Math.abs(diff) < 0.2f) {
                    mDisplay = mTarget;
                } else {
                    mDisplay += diff * 0.14f;
                }
                if (mListener != null) mListener.onDisplay(Math.round(mDisplay));
                invalidate();
            }
            postOnAnimation(this);
        }
    };

    private void startLoop() {
        if (mRunning) return;
        mRunning = true;
        removeCallbacks(mTick);
        postOnAnimation(mTick);
    }

    private void stopLoop() {
        mRunning = false;
        removeCallbacks(mTick);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startLoop();
    }

    @Override protected void onDetachedFromWindow() {
        stopLoop();
        super.onDetachedFromWindow();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredW = MeasureSpec.getSize(widthMeasureSpec);
        int desiredH = Math.round(mBarHeightPx * 3.2f);
        setMeasuredDimension(
                resolveSize(desiredW, widthMeasureSpec),
                resolveSize(desiredH, heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        int W = getWidth();
        int H = getHeight();
        if (W <= 0 || H <= 0) return;

        float barH = mBarHeightPx;
        float cy = H / 2f;
        float top = cy - barH / 2f;
        float bot = cy + barH / 2f;
        float radius = barH / 2f;
        // 水平内边距：为领先端点处的斜飞鸟留出溢出空间（View 会裁剪自身绘制范围）
        float padX = barH * 1.6f;
        float barLeft = padX;
        float barRight = W - padX;
        float barW = barRight - barLeft;
        if (barW <= 0) return;
        float progressW = barLeft + barW * mDisplay / 100f;

        long now = SystemClock.uptimeMillis();
        float flow = (now % 2000L) / 2000f;

        mRect.set(barLeft, top, barRight, bot);
        canvas.drawRoundRect(mRect, radius, radius, mTrackPaint);

        if (mDisplay > 0.4f) {
            mRect.set(barLeft, top - barH * 0.35f, progressW, bot + barH * 0.35f);
            canvas.drawRoundRect(mRect, radius, radius, mGlowPaint);

            RectF clip = new RectF(barLeft, top, progressW, bot);
            Path clipPath = new Path();
            clipPath.addRoundRect(clip, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);

            LinearGradient barShader = new LinearGradient(
                    barLeft, 0, barRight, 0, mColors, null, Shader.TileMode.MIRROR);
            mBarMat.setTranslate(-flow * barW, 0);
            barShader.setLocalMatrix(mBarMat);
            mFillPaint.setShader(barShader);
            canvas.drawRect(clip, mFillPaint);

            float period = Math.max(barH, 8);
            float dashW = period * 0.45f;
            float offset = flow * period;
            for (float x = barLeft - period + offset; x < progressW; x += period) {
                canvas.drawRoundRect(x, top, x + dashW, bot, dashW / 2f, dashW / 2f, mDashPaint);
            }
            canvas.restore();

            drawBird(canvas, progressW, cy, barH, flow);
        }
    }

    /** 在进度领先端点绘制斜飞鸟，填充与进度条同一套动态渐变 */
    private void drawBird(Canvas canvas, float edgeX, float centerY, float barH, float flow) {
        float birdH = barH * 2.1f;
        float scale = birdH / 72f;
        float bob = (float) Math.sin(flow * Math.PI * 2) * (barH * 0.12f);

        mBirdXform.reset();
        mBirdXform.postTranslate(-48, -36);
        mBirdXform.postScale(scale, scale);
        mBirdXform.postRotate(-18);
        mBirdXform.postTranslate(edgeX, centerY + bob);

        float bw = 96 * scale;
        LinearGradient birdShader = new LinearGradient(
                0, 0, Math.max(bw, 1), 0, mColors, null, Shader.TileMode.MIRROR);
        mBirdMat.setTranslate(-flow * bw, 0);
        birdShader.setLocalMatrix(mBirdMat);
        mBirdPaint.setShader(birdShader);
        mBirdFarPaint.setShader(birdShader);
        mFeetPaint.setShader(birdShader);

        // 翅膀持续扇动：绕各自翅根摆动，双翅同向起落，其余部件保持原位。
        float flap = (float) Math.sin(SystemClock.uptimeMillis() / 120.0) * 26f;

        canvas.drawPath(wingXform(mFarWing, 44f, 37f, -flap), mBirdFarPaint);
        canvas.drawPath(applyXform(mTail), mBirdPaint);
        canvas.drawPath(applyXform(mBody), mBirdPaint);
        canvas.drawPath(applyXform(mFeet), mFeetPaint);
        canvas.drawPath(wingXform(mNearWing, 46f, 39f, flap), mBirdPaint);
        canvas.drawPath(applyXform(mHead), mBirdPaint);
        canvas.drawPath(applyXform(mBeak), mBeakPaint);
        canvas.drawPath(applyXform(mEye), mEyePaint);
        canvas.drawPath(applyXform(mEyeHi), mEyeHiPaint);
    }

    private Path applyXform(Path src) {
        mTmp.set(src);
        mTmp.transform(mBirdXform);
        return mTmp;
    }

    /** 绕翅根旋转后套用整鸟变换；用于扇翅的翅膀路径。 */
    private Path wingXform(Path src, float pivotX, float pivotY, float deg) {
        mWingTmp.set(src);
        mWingMat.reset();
        mWingMat.setRotate(deg, pivotX, pivotY);
        mWingTmp.transform(mWingMat);
        mWingTmp.transform(mBirdXform);
        return mWingTmp;
    }
}
