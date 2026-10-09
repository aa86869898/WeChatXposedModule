package com.leshao.v3.ui.widgets

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.leshao.v3.ui.AppColors

/**
 * AI 助手功能图标(自绘, 不依赖 appcompat/Material 资源)。
 *
 * 样式对齐「彩色圆底」方案: 同色系浅色圆角方底 + 主题色实心字形。
 * 字形在 24x24 逻辑网格内定义, 绘制时按控件尺寸等比缩放并居中。
 */
class AiIconDrawable private constructor(private val mGlyph: Int, private val mColor: Int) : Drawable() {

    private val mBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mFillPath = Path()
    private val mStrokePath = Path()
    private val mTmp = RectF()
    private val mBox = RectF()

    init {
        mBgPaint.style = Paint.Style.FILL
        mFillPaint.style = Paint.Style.FILL
        mFillPaint.color = mColor
        mStrokePaint.style = Paint.Style.STROKE
        mStrokePaint.color = mColor
        mStrokePaint.strokeCap = Paint.Cap.ROUND
        mStrokePaint.strokeJoin = Paint.Join.ROUND
        buildGlyph()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val w = b.width()
        val h = b.height()
        val box = Math.min(w, h)
        val radius = box * 0.28f
        val base = if (AppColors.isDarkMode()) AppColors.surfaceContainerHigh() else AppColors.surfaceContainerLowest()
        mBgPaint.color = blend(mColor, base, 0.16f)
        mBox.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        canvas.drawRoundRect(mBox, radius, radius, mBgPaint)

        val scale = (box * 0.56f) / 24f
        canvas.save()
        canvas.translate(b.left + (w - 24f * scale) / 2f, b.top + (h - 24f * scale) / 2f)
        canvas.scale(scale, scale)
        mStrokePaint.strokeWidth = 1.9f
        if (!mFillPath.isEmpty) canvas.drawPath(mFillPath, mFillPaint)
        if (!mStrokePath.isEmpty) canvas.drawPath(mStrokePath, mStrokePaint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        mBgPaint.alpha = alpha
        mFillPaint.alpha = alpha
        mStrokePaint.alpha = alpha
    }

    override fun setColorFilter(cf: ColorFilter?) {
        mBgPaint.colorFilter = cf
        mFillPaint.colorFilter = cf
        mStrokePaint.colorFilter = cf
    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }

    private fun buildGlyph() {
        mFillPath.fillType = Path.FillType.WINDING
        when (mGlyph) {
            G_SPARK -> {
                mFillPath.moveTo(12f, 2.4f)
                mFillPath.lineTo(13.95f, 8.05f)
                mFillPath.lineTo(19.6f, 10f)
                mFillPath.lineTo(13.95f, 11.95f)
                mFillPath.lineTo(12f, 17.6f)
                mFillPath.lineTo(10.05f, 11.95f)
                mFillPath.lineTo(4.4f, 10f)
                mFillPath.lineTo(10.05f, 8.05f)
                mFillPath.close()
                mFillPath.moveTo(18.6f, 15.3f)
                mFillPath.lineTo(19.35f, 17.35f)
                mFillPath.lineTo(21.4f, 18.1f)
                mFillPath.lineTo(19.35f, 18.85f)
                mFillPath.lineTo(18.6f, 20.9f)
                mFillPath.lineTo(17.85f, 18.85f)
                mFillPath.lineTo(15.8f, 18.1f)
                mFillPath.lineTo(17.85f, 17.35f)
                mFillPath.close()
            }
            G_CLOUD -> {
                mFillPath.addCircle(9.5f, 13.2f, 4.3f, Path.Direction.CW)
                mFillPath.addCircle(14.6f, 11.6f, 5.2f, Path.Direction.CW)
                mFillPath.addCircle(18.1f, 13.8f, 3.7f, Path.Direction.CW)
                mTmp.set(6.8f, 12.8f, 18.5f, 17.6f)
                mFillPath.addRoundRect(mTmp, 2.4f, 2.4f, Path.Direction.CW)
            }
            G_CHIP -> {
                mTmp.set(5.5f, 5.5f, 18.5f, 18.5f)
                mFillPath.addRoundRect(mTmp, 2.2f, 2.2f, Path.Direction.CW)
                mTmp.set(9.4f, 9.4f, 14.6f, 14.6f)
                mFillPath.addRoundRect(mTmp, 1.0f, 1.0f, Path.Direction.CCW)
                addPin(10f, 2.6f, 11.3f, 5.6f)
                addPin(12.7f, 2.6f, 14f, 5.6f)
                addPin(10f, 18.4f, 11.3f, 21.4f)
                addPin(12.7f, 18.4f, 14f, 21.4f)
                addPin(2.6f, 10f, 5.6f, 11.3f)
                addPin(2.6f, 12.7f, 5.6f, 14f)
                addPin(18.4f, 10f, 21.4f, 11.3f)
                addPin(18.4f, 12.7f, 21.4f, 14f)
            }
            G_USER -> {
                mFillPath.addCircle(12f, 7.9f, 3.5f, Path.Direction.CW)
                mTmp.set(5.1f, 13.2f, 18.9f, 19.3f)
                mFillPath.addRoundRect(mTmp, 6.9f, 6.9f, Path.Direction.CW)
            }
            G_BELL -> {
                mFillPath.addCircle(12f, 8.4f, 4f, Path.Direction.CW)
                mFillPath.moveTo(8f, 8.4f)
                mFillPath.lineTo(8f, 11.8f)
                mFillPath.lineTo(6.4f, 14.2f)
                mFillPath.lineTo(17.6f, 14.2f)
                mFillPath.lineTo(16f, 11.8f)
                mFillPath.lineTo(16f, 8.4f)
                mFillPath.close()
                mTmp.set(9.9f, 15.9f, 14.1f, 18f)
                mFillPath.addRoundRect(mTmp, 1.05f, 1.05f, Path.Direction.CW)
            }
            G_VOICE -> {
                mFillPath.moveTo(5f, 9.4f)
                mFillPath.lineTo(8.2f, 9.4f)
                mFillPath.lineTo(13f, 5.2f)
                mFillPath.lineTo(13f, 18.8f)
                mFillPath.lineTo(8.2f, 14.6f)
                mFillPath.lineTo(5f, 14.6f)
                mFillPath.close()
                addArc(mStrokePath, 13f, 8.4f, 19.6f, 15.6f, -52f, 104f)
                addArc(mStrokePath, 12f, 6f, 22.6f, 18f, -50f, 100f)
            }
            G_EQ -> {
                addBar(5f, 10f, 7.3f, 19f)
                addBar(9f, 4f, 11.3f, 19f)
                addBar(13f, 12f, 15.3f, 19f)
                addBar(17f, 7f, 19.3f, 19f)
            }
            G_DB -> {
                mTmp.set(5.4f, 5.7f, 18.6f, 18.3f)
                mFillPath.addRoundRect(mTmp, 6.6f, 6.6f, Path.Direction.CW)
                mTmp.set(5.4f, 3f, 18.6f, 8.4f)
                mFillPath.addOval(mTmp, Path.Direction.CW)
                addArc(mStrokePath, 5.4f, 10.6f, 18.6f, 13.6f, 0f, 180f)
            }
            G_LAYERS -> {
                mFillPath.moveTo(12f, 3f)
                mFillPath.lineTo(20.5f, 7.4f)
                mFillPath.lineTo(12f, 11.8f)
                mFillPath.lineTo(3.5f, 7.4f)
                mFillPath.close()
                mStrokePath.moveTo(3.5f, 11.9f)
                mStrokePath.lineTo(12f, 16.3f)
                mStrokePath.lineTo(20.5f, 11.9f)
                mStrokePath.moveTo(3.5f, 15.6f)
                mStrokePath.lineTo(12f, 20f)
                mStrokePath.lineTo(20.5f, 15.6f)
            }
            else -> {
                mStrokePath.moveTo(3.4f, 7.1f)
                mStrokePath.lineTo(11.8f, 7.1f)
                mStrokePath.moveTo(18.2f, 7.1f)
                mStrokePath.lineTo(20.6f, 7.1f)
                mFillPath.addCircle(15f, 7.1f, 2.9f, Path.Direction.CW)
                mStrokePath.moveTo(3.4f, 12.1f)
                mStrokePath.lineTo(6.4f, 12.1f)
                mStrokePath.moveTo(12.6f, 12.1f)
                mStrokePath.lineTo(20.6f, 12.1f)
                mFillPath.addCircle(9.5f, 12.1f, 2.9f, Path.Direction.CW)
                mStrokePath.moveTo(3.4f, 17.1f)
                mStrokePath.lineTo(9.9f, 17.1f)
                mStrokePath.moveTo(16.1f, 17.1f)
                mStrokePath.lineTo(20.6f, 17.1f)
                mFillPath.addCircle(13f, 17.1f, 2.9f, Path.Direction.CW)
            }
        }
    }

    private fun addPin(l: Float, t: Float, r: Float, b: Float) {
        mTmp.set(l, t, r, b)
        mFillPath.addRoundRect(mTmp, 0.65f, 0.65f, Path.Direction.CW)
    }

    private fun addBar(l: Float, t: Float, r: Float, b: Float) {
        mTmp.set(l, t, r, b)
        mFillPath.addRoundRect(mTmp, 1.15f, 1.15f, Path.Direction.CW)
    }

    private fun addArc(p: Path, l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float) {
        mTmp.set(l, t, r, b)
        p.addArc(mTmp, start, sweep)
    }

    companion object {
        const val G_SPARK = 0
        const val G_CLOUD = 1
        const val G_CHIP = 2
        const val G_USER = 3
        const val G_BELL = 4
        const val G_VOICE = 5
        const val G_EQ = 6
        const val G_DB = 7
        const val G_LAYERS = 8
        const val G_SLIDERS = 9

        // v30111: 去静态固化 —— 每次创建时获取当前动态主题色板，深色/动态取色切换后自动跟随
        private fun palette(): IntArray {
            return AppColors.aiIconPalette()
        }

        /** 按内置调色板取色。 */
        @JvmStatic
        fun of(glyph: Int): AiIconDrawable {
            val pal = palette()
            val c = if (glyph >= 0 && glyph < pal.size) pal[glyph] else pal[0]
            return AiIconDrawable(glyph, c)
        }

        /** 指定颜色。 */
        @JvmStatic
        fun of(glyph: Int, color: Int): AiIconDrawable {
            return AiIconDrawable(glyph, color)
        }

        private fun blend(fg: Int, bg: Int, a: Float): Int {
            val fr = (fg shr 16) and 0xFF
            val fgc = (fg shr 8) and 0xFF
            val fb = fg and 0xFF
            val br = (bg shr 16) and 0xFF
            val bgc = (bg shr 8) and 0xFF
            val bb = bg and 0xFF
            val r = Math.round(fr * a + br * (1 - a))
            val g = Math.round(fgc * a + bgc * (1 - a))
            val bl = Math.round(fb * a + bb * (1 - a))
            return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or bl
        }
    }
}
