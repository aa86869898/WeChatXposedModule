package com.leshao.v3.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.leshao.v3.ui.AppColors

/**
 * 流光进度条（v1138 样式定稿 X6/Y6）：
 *  - 轨道胶囊 + 左→右填充（模块统一主题色纯色）
 *  - 填充内叠加「流动虚线」装饰元素
 *  - 领先端点处一只斜向上飞行的鸟（侧面剪影，双翅 + 收拢双脚）
 *  - 百分比在条下方（由调用方 TextView 承担，见 setProgressListener）
 *
 * 自绘、无资源依赖；显示值缓动追随目标值，跳变上报也能丝滑过渡。
 */
class FlyingProgressBar(context: Context) : View(context) {

    interface ProgressListener {
        fun onDisplay(percent: Int)
    }

    /** 可拖动模式下的拖拽回调（percent 均为 0–100）。 */
    interface OnSeekListener {
        fun onSeekStart()
        fun onSeek(percent: Int)
        fun onSeekEnd(percent: Int)
    }

    private var mTarget = 0f
    private var mDisplay = 0f
    private var mListener: ProgressListener? = null
    private var mRunning = false

    private var mSeekable = false
    private var mDragging = false
    private var mSeekListener: OnSeekListener? = null

    private val mTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mDashPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mBirdPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mBirdFarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mFeetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mBeakPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mEyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mEyeHiPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val mRect = RectF()
    private val mBirdXform = Matrix()
    private val mTmp = Path()
    private val mWingMat = Matrix()
    private val mWingTmp = Path()

    private val mBarHeightPx: Int

    // 鸟的各部件（局部坐标 96×72）
    private var mFarWing: Path? = null
    private var mTail: Path? = null
    private var mBody: Path? = null
    private var mNearWing: Path? = null
    private var mHead: Path? = null
    private var mBeak: Path? = null
    private var mFeet: Path? = null
    private var mEye: Path? = null
    private var mEyeHi: Path? = null

    private lateinit var mTick: Runnable

    init {
        mTick = Runnable {
            if (!mRunning) return@Runnable
            if (!mDragging) {
                val diff = mTarget - mDisplay
                if (Math.abs(diff) < 0.2f) {
                    mDisplay = mTarget
                } else {
                    mDisplay += diff * 0.14f
                }
                mListener?.onDisplay(Math.round(mDisplay))
                invalidate()
            }
            postOnAnimation(mTick)
        }
    }

    init {
        val density = context.resources.displayMetrics.density
        mBarHeightPx = Math.round(12 * density)

        mTrackPaint.color = AppColors.surfaceContainerHighest()
        mTrackPaint.style = Paint.Style.FILL

        mFillPaint.color = AppColors.primary()
        mFillPaint.style = Paint.Style.FILL
        mDashPaint.color = (AppColors.whiteTextOnAccent() and 0x00FFFFFF) or 0xA6000000.toInt()
        mDashPaint.style = Paint.Style.FILL

        mGlowPaint.style = Paint.Style.FILL
        mGlowPaint.color = (AppColors.primary() and 0x00FFFFFF) or 0x55000000

        mBirdPaint.color = AppColors.primary()
        mBirdPaint.style = Paint.Style.FILL
        mBirdFarPaint.color = AppColors.primary()
        mBirdFarPaint.style = Paint.Style.FILL
        mBirdFarPaint.alpha = 184
        mFeetPaint.color = AppColors.primary()
        mFeetPaint.style = Paint.Style.STROKE
        mFeetPaint.strokeCap = Paint.Cap.ROUND
        mFeetPaint.strokeJoin = Paint.Join.ROUND
        mFeetPaint.strokeWidth = 2.4f * density

        mBeakPaint.color = AppColors.warning()
        mBeakPaint.style = Paint.Style.FILL
        mEyePaint.color = AppColors.onSurface()
        mEyePaint.style = Paint.Style.FILL
        mEyeHiPaint.color = AppColors.whiteTextOnAccent()
        mEyeHiPaint.style = Paint.Style.FILL

        buildBird()
    }

    private fun buildBird() {
        mFarWing = Path().apply {
            moveTo(44f, 37f)
            cubicTo(38f, 19f, 25f, 8f, 8f, 12f)
            cubicTo(20f, 22f, 33f, 28f, 38f, 43f)
            close()
        }
        mTail = Path().apply {
            moveTo(22f, 42f)
            lineTo(2f, 32f)
            lineTo(16f, 41f)
            lineTo(0f, 50f)
            lineTo(22f, 46f)
            close()
        }
        mBody = Path().apply {
            moveTo(20f, 42f)
            cubicTo(30f, 31f, 56f, 29f, 68f, 35f)
            cubicTo(75f, 38f, 75f, 44f, 66f, 47f)
            cubicTo(52f, 51f, 30f, 51f, 20f, 42f)
            close()
        }
        mNearWing = Path().apply {
            moveTo(46f, 39f)
            cubicTo(49f, 16f, 63f, 4f, 82f, 6f)
            cubicTo(70f, 16f, 57f, 27f, 53f, 43f)
            close()
        }
        mHead = Path().apply {
            addOval(RectF(60f, 23f, 80f, 43f), Path.Direction.CW)
        }
        mBeak = Path().apply {
            moveTo(78f, 30f)
            lineTo(93f, 34.5f)
            lineTo(78f, 39f)
            close()
        }
        mFeet = Path().apply {
            moveTo(40f, 46f)
            cubicTo(35f, 50f, 29f, 51f, 24f, 50f)
            moveTo(24f, 50f); lineTo(19f, 51f)
            moveTo(24f, 50f); lineTo(23f, 53f)
            moveTo(45f, 48f)
            cubicTo(40f, 52f, 34f, 54f, 28f, 53f)
            moveTo(28f, 53f); lineTo(23f, 54f)
            moveTo(28f, 53f); lineTo(27f, 56f)
        }
        mEye = Path().apply {
            addCircle(72f, 31f, 2f, Path.Direction.CW)
        }
        mEyeHi = Path().apply {
            addCircle(72.7f, 30.2f, 0.7f, Path.Direction.CW)
        }
    }

    /** 设置目标进度（0–100） */
    fun setProgress(progress: Int) {
        mTarget = Math.max(0f, Math.min(100f, progress.toFloat()))
        startLoop()
    }

    fun setProgressListener(listener: ProgressListener?) {
        mListener = listener
    }

    /** 开启拖拽调节（播放器进度条）；默认关闭，不影响转码进度弹窗。 */
    fun setSeekable(seekable: Boolean) {
        mSeekable = seekable
    }

    fun setOnSeekListener(listener: OnSeekListener?) {
        mSeekListener = listener
    }

    /** 拖拽中把条与鸟立即定位到指定百分比（0–100），不走缓动。 */
    private fun applyDrag(percent: Int) {
        mDisplay = Math.max(0f, Math.min(100f, percent.toFloat()))
        mTarget = mDisplay
        mListener?.onDisplay(Math.round(mDisplay))
        invalidate()
    }

    private fun barLeftPx(): Float {
        return mBarHeightPx * 1.6f
    }

    private fun barRightPx(): Float {
        return width - mBarHeightPx * 1.6f
    }

    private fun percentAt(x: Float): Int {
        val l = barLeftPx()
        val r = barRightPx()
        if (r <= l) return 0
        return Math.round((x - l) / (r - l) * 100f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!mSeekable) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mDragging = true
                getParent().requestDisallowInterceptTouchEvent(true)
                mSeekListener?.onSeekStart()
                applyDrag(percentAt(e.x))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mDragging) {
                    val p = percentAt(e.x)
                    applyDrag(p)
                    mSeekListener?.onSeek(p)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mDragging) {
                    mDragging = false
                    val p = percentAt(e.x)
                    applyDrag(p)
                    mSeekListener?.onSeekEnd(p)
                }
                return true
            }
            else -> return super.onTouchEvent(e)
        }
    }

    private fun startLoop() {
        if (mRunning) return
        mRunning = true
        removeCallbacks(mTick)
        postOnAnimation(mTick)
    }

    private fun stopLoop() {
        mRunning = false
        removeCallbacks(mTick)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredW = MeasureSpec.getSize(widthMeasureSpec)
        val desiredH = Math.round(mBarHeightPx * 3.2f)
        setMeasuredDimension(
            resolveSize(desiredW, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val W = width
        val H = height
        if (W <= 0 || H <= 0) return

        val barH = mBarHeightPx.toFloat()
        val cy = H / 2f
        val top = cy - barH / 2f
        val bot = cy + barH / 2f
        val radius = barH / 2f
        // 水平内边距：为领先端点处的斜飞鸟留出溢出空间（View 会裁剪自身绘制范围）
        val padX = barH * 1.6f
        val barLeft = padX
        val barRight = W - padX
        val barW = barRight - barLeft
        if (barW <= 0) return
        val progressW = barLeft + barW * mDisplay / 100f

        val now = SystemClock.uptimeMillis()
        val flow = (now % 2000L) / 2000f

        mRect.set(barLeft, top, barRight, bot)
        canvas.drawRoundRect(mRect, radius, radius, mTrackPaint)

        if (mDisplay > 0.4f) {
            mRect.set(barLeft, top - barH * 0.35f, progressW, bot + barH * 0.35f)
            canvas.drawRoundRect(mRect, radius, radius, mGlowPaint)

            val clip = RectF(barLeft, top, progressW, bot)
            val clipPath = Path()
            clipPath.addRoundRect(clip, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)

            canvas.drawRect(clip, mFillPaint)

            val period = Math.max(barH, 8f)
            val dashW = period * 0.45f
            val offset = flow * period
            var x = barLeft - period + offset
            while (x < progressW) {
                canvas.drawRoundRect(x, top, x + dashW, bot, dashW / 2f, dashW / 2f, mDashPaint)
                x += period
            }
            canvas.restore()

            drawBird(canvas, progressW, cy, barH, flow)
        }
    }

    /** 在进度领先端点绘制斜飞鸟，填充与进度条同一套动态渐变 */
    private fun drawBird(canvas: Canvas, edgeX: Float, centerY: Float, barH: Float, flow: Float) {
        val birdH = barH * 2.1f
        val scale = birdH / 72f
        val bob = Math.sin((flow * Math.PI * 2).toDouble()).toFloat() * (barH * 0.12f)

        mBirdXform.reset()
        mBirdXform.postTranslate(-48f, -36f)
        mBirdXform.postScale(scale, scale)
        mBirdXform.postRotate(-18f)
        mBirdXform.postTranslate(edgeX, centerY + bob)

        // 翅膀持续扇动：绕各自翅根摆动，双翅同向起落，其余部件保持原位。
        val flap = Math.sin(SystemClock.uptimeMillis() / 120.0).toFloat() * 26f

        canvas.drawPath(wingXform(mFarWing!!, 44f, 37f, -flap), mBirdFarPaint)
        canvas.drawPath(applyXform(mTail!!), mBirdPaint)
        canvas.drawPath(applyXform(mBody!!), mBirdPaint)
        canvas.drawPath(applyXform(mFeet!!), mFeetPaint)
        canvas.drawPath(wingXform(mNearWing!!, 46f, 39f, flap), mBirdPaint)
        canvas.drawPath(applyXform(mHead!!), mBirdPaint)
        canvas.drawPath(applyXform(mBeak!!), mBeakPaint)
        canvas.drawPath(applyXform(mEye!!), mEyePaint)
        canvas.drawPath(applyXform(mEyeHi!!), mEyeHiPaint)
    }

    private fun applyXform(src: Path): Path {
        mTmp.set(src)
        mTmp.transform(mBirdXform)
        return mTmp
    }

    /** 绕翅根旋转后套用整鸟变换；用于扇翅的翅膀路径。 */
    private fun wingXform(src: Path, pivotX: Float, pivotY: Float, deg: Float): Path {
        mWingTmp.set(src)
        mWingMat.reset()
        mWingMat.setRotate(deg, pivotX, pivotY)
        mWingTmp.transform(mWingMat)
        mWingTmp.transform(mBirdXform)
        return mWingTmp
    }
}
