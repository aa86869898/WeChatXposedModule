package com.leshao.v3.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.animation.LinearInterpolator
import java.util.WeakHashMap

/**
 * v3.0.101 糖果粉 · 纯色 Drawable。
 *
 * 三色（糖果粉 #FF99C2 → #FF99C2 → #FF99C2）循环渐变，并以周期平移形成「流光」动效。
 * 三色相同，视觉上保持糖果粉纯色，同时保留循环平移机制与既有 API 兼容。
 * 渐变的颜色函数以自身宽度为一个周期，平移一周后与起始画面完全重合，因此循环无缝、无跳变。
 *
 * 实现要点：单例全局 ValueAnimator 驱动所有可见实例（onVisible 注册/注销，WeakHashMap 防止泄漏），
 * 动画只做 invalidateSelf()，绘制时用 Matrix 平移 shader，零冗余分配。
 *
 * 仅用于皮肤绘制，不承载任何业务语义。
 */
class FlowingGradientDrawable(start: Int, mid: Int, end: Int) : Drawable() {

    private val mColors = intArrayOf(start, mid, end)
    private val mSamples = IntArray(10)
    private val mPositions = FloatArray(10)
    private val mPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mMatrix = Matrix()
    private val mRadiiBuf = FloatArray(8)

    private var mCornerRadius = 0f
    private var mCornerRadii: FloatArray? = null
    private var mStrokeWidth = 0f
    private var mStrokeColor = 0
    private var mSizeW = -1
    private var mSizeH = -1
    private var mPhaseOffset = 0f
    private var mAnimated = true

    private var mShader: LinearGradient? = null
    private var mShaderWidth = -1
    private var mShaderLeft = Int.MIN_VALUE
    private val mRect = RectF()
    private val mStrokeRect = RectF()
    private val mPath = Path()

    init {
        for (i in mSamples.indices) {
            mSamples[i] = mColors[i % 3]
            mPositions[i] = i / (mSamples.size - 1).toFloat()
        }
    }

    /** 圆角半径（px） */
    fun setCornerRadius(radiusPx: Float): FlowingGradientDrawable {
        mCornerRadius = Math.max(0f, radiusPx)
        mCornerRadii = null
        invalidateSelf()
        return this
    }

    /** 每角圆角半径（px），顺序 [tl, tr, br, bl]，用于顶栏等只需部分圆角的场景 */
    fun setCornerRadii(radii: FloatArray?): FlowingGradientDrawable {
        if (radii == null || radii.size != 4) return this
        mCornerRadii = floatArrayOf(
            Math.max(0f, radii[0]), Math.max(0f, radii[1]),
            Math.max(0f, radii[2]), Math.max(0f, radii[3]))
        invalidateSelf()
        return this
    }

    /** 描边（px + 颜色），宽度 <=0 表示无描边 */
    fun setStroke(widthPx: Float, color: Int): FlowingGradientDrawable {
        mStrokeWidth = Math.max(0f, widthPx)
        mStrokeColor = color
        mStrokePaint.style = Paint.Style.STROKE
        mStrokePaint.strokeWidth = mStrokeWidth
        mStrokePaint.color = mStrokeColor
        invalidateSelf()
        return this
    }

    /** 提供 intrinsic 尺寸，供 StateListDrawable / 框架控件测量时使用 */
    fun setSize(w: Int, h: Int): FlowingGradientDrawable {
        mSizeW = w
        mSizeH = h
        return this
    }

    /** 关闭/开启流光动效（关闭后为静态渐变，用于大面积底色等场景） */
    fun setAnimated(animated: Boolean): FlowingGradientDrawable {
        if (mAnimated == animated) return this
        mAnimated = animated
        if (!animated) unregister(this)
        else if (isVisible) register(this)
        return this
    }

    /** 相位偏移（0~1），让多个控件不同步流动 */
    fun setPhaseOffset(offset: Float): FlowingGradientDrawable {
        mPhaseOffset = offset
        invalidateSelf()
        return this
    }

    override fun getIntrinsicWidth(): Int {
        return mSizeW
    }

    override fun getIntrinsicHeight(): Int {
        return mSizeH
    }

    override fun setAlpha(alpha: Int) {
        mPaint.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int {
        return mPaint.alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        mPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        mShader = null
        mShaderWidth = -1
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible && mAnimated) register(this)
        else unregister(this)
        return changed
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width()
        val h = b.height()
        if (w <= 0 || h <= 0) return

        // 安全网：只要被绘制就确保已接入全局动画驱动（部分宿主不回调 setVisible）
        if (mAnimated) register(this)

        if (mShader == null || mShaderWidth != w || mShaderLeft != b.left) {
            mShader = LinearGradient(b.left.toFloat(), 0f, b.left.toFloat() + 3f * w, 0f,
                mSamples, mPositions, Shader.TileMode.CLAMP)
            mShaderWidth = w
            mShaderLeft = b.left
        }
        var offset = (if (mAnimated) phase(0f) else 0f) + mPhaseOffset
        offset -= Math.floor(offset.toDouble()).toFloat()
        mMatrix.setTranslate(-offset * w, 0f)
        mShader!!.setLocalMatrix(mMatrix)
        mPaint.shader = mShader

        mRect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val radii = mCornerRadii
        if (radii != null) {
            val maxR = Math.min(w, h) / 2f
            val tl = Math.min(radii[0], maxR)
            val tr = Math.min(radii[1], maxR)
            val br = Math.min(radii[2], maxR)
            val bl = Math.min(radii[3], maxR)
            mRadiiBuf[0] = tl; mRadiiBuf[1] = tl
            mRadiiBuf[2] = tr; mRadiiBuf[3] = tr
            mRadiiBuf[4] = br; mRadiiBuf[5] = br
            mRadiiBuf[6] = bl; mRadiiBuf[7] = bl
            mPath.reset()
            mPath.addRoundRect(mRect, mRadiiBuf, Path.Direction.CW)
            canvas.drawPath(mPath, mPaint)

            if (mStrokeWidth > 0) {
                val half = mStrokeWidth / 2f
                mStrokeRect.set(mRect.left + half, mRect.top + half,
                    mRect.right - half, mRect.bottom - half)
                mPath.reset()
                mPath.addRoundRect(mStrokeRect, mRadiiBuf, Path.Direction.CW)
                canvas.drawPath(mPath, mStrokePaint)
            }
        } else {
            val r = Math.min(mCornerRadius, Math.min(w, h) / 2f)
            canvas.drawRoundRect(mRect, r, r, mPaint)
            if (mStrokeWidth > 0) {
                val half = mStrokeWidth / 2f
                mStrokeRect.set(mRect.left + half, mRect.top + half,
                    mRect.right - half, mRect.bottom - half)
                val rs = Math.max(0f, r - half)
                canvas.drawRoundRect(mStrokeRect, rs, rs, mStrokePaint)
            }
        }
    }

    companion object {
        private const val DURATION_MS = 3200L
        private val ACTIVE = WeakHashMap<FlowingGradientDrawable, Boolean>()
        private var sTicker: ValueAnimator? = null

        private fun phase(extra: Float): Float {
            return ((SystemClock.uptimeMillis() % DURATION_MS) / DURATION_MS.toFloat() + extra) % 1f
        }

        private fun register(d: FlowingGradientDrawable) {
            ACTIVE.put(d, java.lang.Boolean.TRUE)
            if (sTicker == null) {
                sTicker = ValueAnimator.ofFloat(0f, 1f)
                sTicker!!.duration = DURATION_MS
                sTicker!!.repeatCount = ValueAnimator.INFINITE
                sTicker!!.interpolator = LinearInterpolator()
                sTicker!!.addUpdateListener {
                    // 快照遍历：绘制/注册过程可能在同一帧内改动 ACTIVE，避免并发修改
                    val snapshot = ACTIVE.keys.toTypedArray()
                    for (x in snapshot) {
                        if (x != null) {
                            try {
                                x.invalidateSelf()
                            } catch (ignored: Throwable) {
                            }
                        }
                    }
                }
            }
            if (!sTicker!!.isStarted) {
                try {
                    sTicker!!.start()
                } catch (ignored: Throwable) {
                }
            }
        }

        private fun unregister(d: FlowingGradientDrawable) {
            ACTIVE.remove(d)
            if (ACTIVE.isEmpty() && sTicker != null) {
                try {
                    sTicker!!.cancel()
                } catch (ignored: Throwable) {
                }
                sTicker = null
            }
        }
    }
}
