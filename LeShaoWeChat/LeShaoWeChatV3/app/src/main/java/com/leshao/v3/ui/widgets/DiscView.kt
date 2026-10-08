package com.leshao.v3.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import com.leshao.v3.ui.AppColors

/**
 * v30021: 播放器「音乐碟片」动画（第 10 套「玻璃高光」）。
 *
 * 淡色渐变盘体持续旋转，叠一条随盘扫过的斜向镜面高光带与一枚偏左上的硬高光点，
 * 盘体外部有随主题变化的柔光；盘心圆形为封面位（后续可替换为真实封面），与盘体同步
 * 旋转。
 */
class DiscView(ctx: Context) : View(ctx) {

    private val mPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mSheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mHilite = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mCover = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mNote = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mMatrix = Matrix()

    private var mAnim: ValueAnimator? = null
    private var mAngle = 0f
    private var mBuiltW = 0
    private var mBuiltH = 0

    private var mCoverBitmap: Bitmap? = null
    private val mClipPath = Path()
    private val mSrcRect = Rect()
    private val mDstRect = RectF()

    private var mBodyGrad: LinearGradient? = null
    private var mSheenGrad: LinearGradient? = null
    private var mHiliteGrad: RadialGradient? = null
    private var mGlowGrad: RadialGradient? = null
    private var mCoverGrad: LinearGradient? = null

    init {
        mNote.color = 0xFFFFFFFF.toInt()
        mNote.textAlign = Paint.Align.CENTER
        mNote.isFakeBoldText = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    fun start() {
        if (mAnim != null && mAnim!!.isStarted) return
        mAnim = ValueAnimator.ofFloat(0f, 360f)
        mAnim!!.duration = 12000L
        mAnim!!.repeatCount = ValueAnimator.INFINITE
        mAnim!!.interpolator = LinearInterpolator()
        mAnim!!.addUpdateListener { a ->
            mAngle = a.animatedValue as Float
            invalidate()
        }
        mAnim!!.start()
    }

    fun stop() {
        if (mAnim != null) {
            mAnim!!.cancel()
            mAnim = null
        }
    }

    /** 设置盘心封面；传 null 恢复渐变占位。 */
    fun setCoverBitmap(bm: Bitmap?) {
        mCoverBitmap = bm
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mBuiltW = w
        mBuiltH = h
        buildShaders(w, h)
    }

    private fun buildShaders(w: Int, h: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val R = Math.min(w, h) / 2f * 0.90f
        AppColors.isDarkMode()

        // 盘体：全局糖果粉纯色（浅色/暗色一致）
        val c1 = AppColors.gradientStart()
        val c2 = AppColors.gradientStart()
        val c3 = AppColors.gradientStart()
        mBodyGrad = LinearGradient(cx - R, cy - R, cx + R, cy + R,
            intArrayOf(c1, c2, c3), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)

        // 斜向镜面高光带
        mSheenGrad = LinearGradient(cx - R, cy + R, cx + R, cy - R,
            intArrayOf(0x00FFFFFF, 0x88FFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0.38f, 0.50f, 0.62f), Shader.TileMode.CLAMP)

        // 偏左上的硬高光点
        val hx = cx - R * 0.48f
        val hy = cy - R * 0.56f
        mHiliteGrad = RadialGradient(hx, hy, R * 0.62f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xE0FFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.16f, 1f), Shader.TileMode.CLAMP)

        // 外部柔光
        val glowC = (AppColors.gradientEnd() and 0x00FFFFFF) or 0x44000000
        mGlowGrad = RadialGradient(cx, cy, R * 0.8f,
            intArrayOf(glowC, glowC and 0x00FFFFFF), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)

        // 盘心封面位
        mCoverGrad = LinearGradient(cx - R * 0.4f, cy - R * 0.4f, cx + R * 0.4f, cy + R * 0.4f,
            intArrayOf(AppColors.gradientStart(), AppColors.gradientEnd()), null,
            Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        if (w != mBuiltW || h != mBuiltH) buildShaders(w, h)

        val cx = w / 2f
        val cy = h / 2f
        val R = Math.min(w, h) / 2f * 0.90f

        // 外部柔光（不随盘旋转）
        mGlow.shader = mGlowGrad
        canvas.drawCircle(cx, cy, R * 1.22f, mGlow)

        mMatrix.setRotate(mAngle, cx, cy)

        // 盘体（淡色渐变，旋转）
        mBodyGrad!!.setLocalMatrix(mMatrix)
        mPaint.shader = mBodyGrad
        canvas.drawCircle(cx, cy, R, mPaint)

        // 斜向镜面高光带（旋转）
        mSheenGrad!!.setLocalMatrix(mMatrix)
        mSheen.shader = mSheenGrad
        canvas.drawCircle(cx, cy, R, mSheen)

        // 硬高光点（旋转）
        mHiliteGrad!!.setLocalMatrix(mMatrix)
        mHilite.shader = mHiliteGrad
        canvas.drawCircle(cx, cy, R, mHilite)

        // 盘心封面位（与盘体同步旋转）
        val cr = R * 0.44f
        canvas.save()
        canvas.rotate(mAngle, cx, cy)
        val bm = mCoverBitmap
        if (bm != null && !bm.isRecycled) {
            mClipPath.reset()
            mClipPath.addCircle(cx, cy, cr, Path.Direction.CW)
            canvas.clipPath(mClipPath)
            val bw = bm.width
            val bh = bm.height
            val side = Math.min(bw, bh)
            if (side > 0) {
                val l = ((bw - side) / 2f).toInt()
                val t = ((bh - side) / 2f).toInt()
                mSrcRect.set(l, t, l + side.toInt(), t + side.toInt())
                mDstRect.set(cx - cr, cy - cr, cx + cr, cy + cr)
                mCover.shader = null
                canvas.drawBitmap(bm, mSrcRect, mDstRect, mCover)
            }
        } else {
            mCoverGrad!!.setLocalMatrix(mMatrix)
            mCover.shader = mCoverGrad
            canvas.drawCircle(cx, cy, cr, mCover)
            mNote.textSize = cr * 1.0f
            canvas.drawText("\u266A", cx, cy - (mNote.descent() + mNote.ascent()) / 2f, mNote)
        }
        canvas.restore()

        // 中心轴孔
        mPaint.shader = null
        mPaint.color = 0xCCFFFFFF.toInt()
        canvas.drawCircle(cx, cy, cr * 0.16f, mPaint)
    }
}