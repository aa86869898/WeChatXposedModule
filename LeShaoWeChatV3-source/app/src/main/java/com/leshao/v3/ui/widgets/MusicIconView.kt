package com.leshao.v3.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.leshao.v3.ui.AppColors

/**
 * 在线音乐矢量图标（v30033 落地「实心渐变 + 实心节点」方案）。
 *
 * 所有图形在 24x24 viewBox 内描述，绘制时按视图短边等比缩放并居中；描边色统一取自
 * 模块「糖果粉 · 纯色」同系色（粉 → 深玫，暗色自动切换），setActive(boolean)
 * 关闭时退化为 AppColors.text3() 单色（用于底部导航未选中态）。
 *
 * 两种渲染模式：
 * - 描边模式（默认）：渐变描边，粗轮廓圆头；QUALITY/DISC 额外绘制实心渐变节点。
 * - 实心模式（setSolid(boolean) 或 PLAY/PAUSE/MORE）：渐变实心填充，用于歌曲行右侧按钮。
 *
 * 纯皮肤绘制，不承载业务语义。
 */
class MusicIconView : View {

    private var name = PLAY
    private var solid = false
    private var active = true
    private var strokeWidth = 3.0f
    private var sizePx = 0

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    constructor(c: Context) : super(c) {
        init()
    }

    constructor(c: Context, icon: String?) : super(c) {
        name = icon ?: PLAY
        init()
    }

    private fun init() {
        stroke.style = Paint.Style.STROKE
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        stroke.strokeWidth = strokeWidth
        fill.style = Paint.Style.FILL
    }

    fun setIcon(icon: String?): MusicIconView {
        name = icon ?: PLAY
        invalidate()
        return this
    }

    fun icon(): String {
        return name
    }

    fun setSolid(s: Boolean): MusicIconView {
        solid = s
        invalidate()
        return this
    }

    fun setActive(a: Boolean): MusicIconView {
        active = a
        invalidate()
        return this
    }

    fun setStrokeWidthDp(w: Float): MusicIconView {
        strokeWidth = w
        stroke.strokeWidth = w
        invalidate()
        return this
    }

    fun setIconSizeDp(sizeDp: Int): MusicIconView {
        sizePx = dp(sizeDp.toFloat())
        requestLayout()
        invalidate()
        return this
    }

    private fun dp(v: Float): Int {
        return (v * resources.displayMetrics.density + 0.5f).toInt()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val def = if (sizePx > 0) sizePx else dp(26f)
        val w = def + paddingLeft + paddingRight
        val h = def + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(w, wSpec), resolveSize(h, hSpec))
    }

    private fun isFilled(): Boolean {
        if (PLAY == name || PAUSE == name || MORE == name) return true
        return solid
    }

    private fun configurePaints() {
        if (active) {
            stroke.shader = null
            fill.shader = null
            stroke.color = AppColors.primary()
            fill.color = AppColors.primary()
        } else {
            val c = AppColors.text3()
            stroke.shader = null
            fill.shader = null
            stroke.color = c
            fill.color = c
        }
    }

    override fun onDraw(cv: Canvas) {
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return
        val s = Math.min(w, h) / VB
        configurePaints()
        cv.save()
        cv.translate(paddingLeft + (w - VB * s) / 2f, paddingTop + (h - VB * s) / 2f)
        cv.scale(s, s)

        path.reset()
        buildPath(path)
        cv.drawPath(path, if (isFilled()) fill else stroke)

        if (!isFilled()) drawNodes(cv)

        cv.restore()
    }

    /** 仅描边模式下的实心节点（音质推子旋钮、碟心）。 */
    private fun drawNodes(cv: Canvas) {
        when (name) {
            QUALITY -> {
                cv.drawCircle(9.2f, 8f, 2.5f, fill)
                cv.drawCircle(14.8f, 16f, 2.5f, fill)
            }
            DISC -> cv.drawCircle(12f, 12f, 2.3f, fill)
        }
    }

    private fun buildPath(p: Path) {
        when (name) {
            FAV -> {
                p.moveTo(12f, 20f)
                p.cubicTo(12f, 20f, 3.8f, 14.8f, 3.8f, 9.8f)
                p.cubicTo(3.8f, 6.6f, 6.2f, 4.6f, 8.7f, 4.6f)
                p.cubicTo(10.4f, 4.6f, 11.6f, 5.5f, 12f, 6.4f)
                p.cubicTo(12.4f, 5.5f, 13.6f, 4.6f, 15.3f, 4.6f)
                p.cubicTo(17.8f, 4.6f, 20.2f, 6.6f, 20.2f, 9.8f)
                p.cubicTo(20.2f, 14.8f, 12f, 20f, 12f, 20f)
                p.close()
            }
            DL -> {
                if (isFilled()) {
                    p.moveTo(10.8f, 3.4f)
                    p.lineTo(13.2f, 3.4f)
                    p.lineTo(13.2f, 9.6f)
                    p.lineTo(15.5f, 9.6f)
                    p.lineTo(12f, 13.7f)
                    p.lineTo(8.5f, 9.6f)
                    p.lineTo(10.8f, 9.6f)
                    p.close()
                    p.addRoundRect(RectF(5f, 16.8f, 19f, 19.6f), 1.35f, 1.35f, Path.Direction.CW)
                } else {
                    p.moveTo(12f, 3.8f)
                    p.lineTo(12f, 14f)
                    p.moveTo(7.5f, 9.6f)
                    p.lineTo(12f, 14.1f)
                    p.lineTo(16.5f, 9.6f)
                    p.moveTo(5.1f, 19.5f)
                    p.lineTo(18.9f, 19.5f)
                }
            }
            SEND -> {
                if (isFilled()) {
                    p.moveTo(2.6f, 20.5f)
                    p.lineTo(21.4f, 12f)
                    p.lineTo(2.6f, 3.5f)
                    p.lineTo(2.55f, 10.1f)
                    p.lineTo(16.2f, 12f)
                    p.lineTo(2.55f, 13.9f)
                    p.close()
                } else {
                    p.moveTo(20.5f, 4.1f)
                    p.lineTo(3.9f, 11.2f)
                    p.lineTo(10.5f, 13.3f)
                    p.lineTo(12.7f, 19.9f)
                    p.close()
                    p.moveTo(10.5f, 13.3f)
                    p.lineTo(20.5f, 4.1f)
                }
            }
            QUALITY -> {
                p.moveTo(4.5f, 8f)
                p.lineTo(19.5f, 8f)
                p.moveTo(4.5f, 16f)
                p.lineTo(19.5f, 16f)
            }
            ORDER_LIST -> orderLoop(p)
            ORDER_SINGLE -> {
                orderLoop(p)
                p.moveTo(11.2f, 10.6f)
                p.lineTo(12.6f, 9.7f)
                p.lineTo(12.6f, 14.4f)
            }
            ORDER_SHUFFLE -> {
                p.moveTo(3.5f, 6.5f)
                p.lineTo(7f, 6.5f)
                p.lineTo(17f, 17.5f)
                p.lineTo(20.5f, 17.5f)
                p.moveTo(17.6f, 15.2f)
                p.lineTo(20.9f, 17.5f)
                p.lineTo(17.6f, 19.8f)
                p.moveTo(3.5f, 17.5f)
                p.lineTo(7f, 17.5f)
                p.lineTo(17f, 6.5f)
                p.lineTo(20.5f, 6.5f)
                p.moveTo(17.6f, 4.2f)
                p.lineTo(20.9f, 6.5f)
                p.lineTo(17.6f, 8.8f)
            }
            PREV -> {
                p.moveTo(6f, 5.6f)
                p.lineTo(6f, 18.4f)
                p.moveTo(18f, 5.6f)
                p.lineTo(8.6f, 12f)
                p.lineTo(18f, 18.4f)
                p.close()
            }
            NEXT -> {
                p.moveTo(18f, 5.6f)
                p.lineTo(18f, 18.4f)
                p.moveTo(6f, 5.6f)
                p.lineTo(15.4f, 12f)
                p.lineTo(6f, 18.4f)
                p.close()
            }
            PLAY -> {
                p.moveTo(8.6f, 5.7f)
                p.cubicTo(8.6f, 4.68f, 9.73f, 4.08f, 10.56f, 4.67f)
                p.lineTo(18.76f, 10.97f)
                p.cubicTo(19.44f, 11.49f, 19.44f, 12.51f, 18.76f, 13.03f)
                p.lineTo(10.56f, 19.33f)
                p.cubicTo(9.73f, 19.92f, 8.6f, 19.32f, 8.6f, 18.3f)
                p.close()
            }
            PAUSE -> {
                p.addRoundRect(RectF(7f, 5f, 10.2f, 19f), 1.6f, 1.6f, Path.Direction.CW)
                p.addRoundRect(RectF(13.8f, 5f, 17f, 19f), 1.6f, 1.6f, Path.Direction.CW)
            }
            HOME -> {
                p.moveTo(4f, 11f)
                p.lineTo(12f, 4.1f)
                p.lineTo(20f, 11f)
                p.moveTo(6.2f, 9.6f)
                p.lineTo(6.2f, 19f)
                p.cubicTo(6.2f, 19.55f, 6.65f, 20f, 7.2f, 20f)
                p.lineTo(16.8f, 20f)
                p.cubicTo(17.35f, 20f, 17.8f, 19.55f, 17.8f, 19f)
                p.lineTo(17.8f, 9.6f)
            }
            DISC -> p.addOval(RectF(3.8f, 3.8f, 20.2f, 20.2f), Path.Direction.CW)
            USER -> {
                p.addOval(RectF(8.1f, 4.4f, 15.9f, 12.2f), Path.Direction.CW)
                p.moveTo(4.8f, 19.9f)
                p.cubicTo(4.8f, 16.3f, 8f, 14.3f, 12f, 14.3f)
                p.cubicTo(16f, 14.3f, 19.2f, 16.3f, 19.2f, 19.9f)
            }
            MORE -> {
                p.addOval(RectF(4f, 10f, 8f, 14f), Path.Direction.CW)
                p.addOval(RectF(10f, 10f, 14f, 14f), Path.Direction.CW)
                p.addOval(RectF(16f, 10f, 20f, 14f), Path.Direction.CW)
            }
            SEARCH -> {
                p.addOval(RectF(4.7f, 4.7f, 16.9f, 16.9f), Path.Direction.CW)
                p.moveTo(15.4f, 15.4f)
                p.lineTo(20f, 20f)
            }
        }
    }

    /** 两条水平方向箭头组成的循环回路（列表循环 / 单曲循环共用底图）。 */
    private fun orderLoop(p: Path) {
        p.moveTo(4f, 10f)
        p.cubicTo(4f, 6.8f, 6.5f, 5.4f, 8.6f, 5.4f)
        p.lineTo(17f, 5.4f)
        p.moveTo(14.6f, 2.9f)
        p.lineTo(17.7f, 5.4f)
        p.lineTo(14.6f, 7.9f)
        p.moveTo(20f, 14f)
        p.cubicTo(20f, 17.2f, 17.5f, 18.6f, 15.4f, 18.6f)
        p.lineTo(7f, 18.6f)
        p.moveTo(9.4f, 21.1f)
        p.lineTo(6.3f, 18.6f)
        p.lineTo(9.4f, 16.1f)
    }

    companion object {
        const val FAV = "fav"
        const val DL = "dl"
        const val SEND = "send"
        const val QUALITY = "quality"
        const val ORDER_LIST = "order_list"
        const val ORDER_SINGLE = "order_single"
        const val ORDER_SHUFFLE = "order_shuffle"
        const val PREV = "prev"
        const val NEXT = "next"
        const val PLAY = "play"
        const val PAUSE = "pause"
        const val HOME = "home"
        const val DISC = "disc"
        const val USER = "user"
        const val MORE = "more"
        const val SEARCH = "search"

        private const val VB = 24f
    }
}
