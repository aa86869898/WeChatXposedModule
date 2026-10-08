package com.leshao.v3.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout

/**
 * v1105: 在线音乐「悬浮球」控制条。
 *
 * 播放音乐后关闭在线音乐页面时，在宿主 Activity 上叠加一个贴边小圆球：
 * 空闲时缩回屏幕侧边只露半圆，点击拉出，再点展开「上一首 / 播放暂停 / 下一首 / ×」；
 * 无操作 5 秒自动侧边休眠（缩回只露半圆）；无播放时自动消失。
 * × 关闭悬浮球（不停止播放），再次进入在线音乐并返回时会重新出现。
 *
 * 外观跟随模块「糖果粉」主题（#FF99C2 纯色），浅色/暗色模式自动适配。
 *
 * 使用 WindowManager.LayoutParams.TYPE_APPLICATION_PANEL 挂到 Activity 的
 * WindowManager，无需系统悬浮窗权限，随 Activity 一起销毁。
 */
class MusicFloatBall private constructor() {

    companion object {
        private const val BALL_DP = 32
        private const val BTN_DP = 34

        private const val ST_RETRACT = 0
        private const val ST_OUT = 1
        private const val ST_OPEN = 2

        /** 无操作后自动缩回侧边的等待时长。 */
        private const val AUTO_RETRACT_MS = 5000L

        private var sState = ST_RETRACT

        private var sRoot: LinearLayout? = null
        private var sPanel: LinearLayout? = null
        private var sBall: EqIconView? = null
        private var sPlayPause: IconView? = null
        private var sPrev: IconView? = null
        private var sNext: IconView? = null
        private var sClose: IconView? = null
        private var sWM: WindowManager? = null
        private var sAct: Activity? = null
        private var sMiss = 0
        private var sScheme = -1
        private var sAnim: ValueAnimator? = null
        private var sThemeCb: Runnable? = null

        private val H = Handler(Looper.getMainLooper())
        private val POLL = object : Runnable {
            override fun run() {
                sync()
                if (sRoot != null) H.postDelayed(this, 1500)
            }
        }
        private val AUTO_RETRACT = Runnable {
            if (sRoot != null && sState != ST_RETRACT) applyState(ST_RETRACT, true)
        }

        @JvmStatic
        fun show(act: Activity?) {
            if (act == null) return
            if (Build.VERSION.SDK_INT >= 17 && act.isDestroyed) return
            if (act.isFinishing) return
            H.post {
                try {
                    if (sRoot != null && sAct == act) {
                        sync()
                        return@post
                    }
                    if (sRoot != null) remove()
                    build(act)
                } catch (ignored: Throwable) {
                }
            }
        }

        @JvmStatic
        fun hide() {
            H.post { remove() }
        }

        private fun remove() {
            H.removeCallbacks(POLL)
            H.removeCallbacks(AUTO_RETRACT)
            if (sAnim != null) {
                try {
                    sAnim!!.cancel()
                } catch (ignored: Throwable) {
                }
                sAnim = null
            }
            if (sThemeCb != null) {
                try {
                    AppColors.removeThemeListener(sThemeCb)
                } catch (ignored: Throwable) {
                }
                sThemeCb = null
            }
            val b = sRoot
            val wm = sWM
            sRoot = null
            sPanel = null
            sBall = null
            sPlayPause = null
            sPrev = null
            sNext = null
            sClose = null
            sWM = null
            sAct = null
            sMiss = 0
            sScheme = -1
            sState = ST_RETRACT
            try {
                if (wm != null && b != null) wm.removeViewImmediate(b)
            } catch (ignored: Throwable) {
            }
        }

        private fun build(act: Activity) {
            sAct = act
            val wm = act.windowManager
            if (wm == null) return
            sWM = wm
            val ctx: Context = act

            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL

            // 控制条（默认隐藏，展开时出现在球的左侧）
            val panel = LinearLayout(ctx)
            panel.orientation = LinearLayout.HORIZONTAL
            panel.gravity = Gravity.CENTER_VERTICAL
            val pp = dp(ctx, 4)
            panel.setPadding(pp, pp, pp, pp)
            panel.elevation = dp(ctx, 8).toFloat()
            panel.visibility = View.GONE

            sPlayPause = iconBtn(ctx, IconView.PAUSE) { OnlineMusicPageView.togglePlayback(); sync() }
            sPrev = iconBtn(ctx, IconView.PREV) { OnlineMusicPageView.prevPlayback() }
            sNext = iconBtn(ctx, IconView.NEXT) { OnlineMusicPageView.nextPlayback() }
            sClose = iconBtn(ctx, IconView.CLOSE) { hide() }
            panel.addView(sPrev)
            panel.addView(sPlayPause)
            panel.addView(sNext)
            panel.addView(sClose)

            val plp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            plp.rightMargin = dp(ctx, 8)
            panel.layoutParams = plp

            // 圆球（声波音柱图标 + 三色渐变）
            val ball = EqIconView(ctx)
            val bs = dp(ctx, BALL_DP.toFloat())
            ball.layoutParams = LinearLayout.LayoutParams(bs, bs)
            ball.elevation = dp(ctx, 6).toFloat()
            ball.setOnClickListener {
                when (sState) {
                    ST_RETRACT -> applyState(ST_OUT, true)
                    ST_OUT -> applyState(ST_OPEN, true)
                    else -> applyState(ST_RETRACT, true)
                }
            }

            root.addView(panel)
            root.addView(ball)

            // 拖动：仅垂直移动；未超过阈值时交还子 View 处理点击
            val drag = DragTouch()
            root.setOnTouchListener(drag)
            panel.setOnTouchListener(drag)
            ball.setOnTouchListener(drag)
            for (i in 0 until panel.childCount) {
                panel.getChildAt(i).setOnTouchListener(drag)
            }

            sRoot = root
            sPanel = panel
            sBall = ball
            sState = ST_RETRACT

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.RIGHT
            lp.x = dp(ctx, BALL_DP / 2f)           // 缩回侧边：只露半圆
            lp.y = (ctx.resources.displayMetrics.heightPixels * 0.42f).toInt()

            try {
                wm.addView(root, lp)
            } catch (t: Throwable) {
                sRoot = null
                sPanel = null
                sBall = null
                sPlayPause = null
                sPrev = null
                sNext = null
                sClose = null
                sWM = null
                sAct = null
                return
            }

            applyColors()

            sThemeCb = Runnable { H.post { applyColors() } }
            try {
                AppColors.addThemeListener(sThemeCb)
            } catch (ignored: Throwable) {
            }

            sMiss = 0
            H.removeCallbacks(POLL)
            H.postDelayed(POLL, 1500)
            sync()
        }

        /** 按当前深浅模式重刷配色（糖果粉主题）。 */
        private fun applyColors() {
            val ball = sBall
            if (ball == null) return
            val ctx = ball.context
            try {
                val bg = GradientDrawable()
                bg.shape = GradientDrawable.OVAL
                bg.setColor(AppColors.primary())
                ball.background = bg

                val panel = sPanel
                if (panel != null) {
                    val pg = GradientDrawable()
                    pg.setColor(AppColors.surfaceContainerHighest())
                    pg.cornerRadius = dp(ctx, AppColors.SHAPE_CARD_DP.toFloat()).toFloat()
                    pg.setStroke(dp(ctx, 1), AppColors.outlineVariant())
                    panel.background = pg
                }
                sPrev?.setColor(AppColors.primary())
                sPlayPause?.setColor(AppColors.primary())
                sNext?.setColor(AppColors.primary())
                sClose?.setColor(AppColors.tertiary())
                sScheme = AppColors.getSchemeVersion()
            } catch (ignored: Throwable) {
            }
        }

        /** 切换三态：缩回(贴边) / 拉出(整圆) / 展开(控制条)。 */
        private fun applyState(state: Int, animate: Boolean) {
            sState = state
            val panel = sPanel
            val root = sRoot
            if (panel == null || root == null || sWM == null) return
            panel.visibility = if (state == ST_OPEN) View.VISIBLE else View.GONE
            if (state == ST_RETRACT) H.removeCallbacks(AUTO_RETRACT)
            else scheduleAutoRetract()
            if (root.layoutParams !is WindowManager.LayoutParams) return
            val lp = root.layoutParams as WindowManager.LayoutParams
            val target = if (state == ST_RETRACT) dp(root.context, BALL_DP / 2f) else 0
            if (!animate) {
                lp.x = target
                try {
                    sWM!!.updateViewLayout(root, lp)
                } catch (ignored: Throwable) {
                }
                return
            }
            if (sAnim != null) {
                try {
                    sAnim!!.cancel()
                } catch (ignored: Throwable) {
                }
            }
            val a = ValueAnimator.ofInt(lp.x, target)
            a.duration = 180
            a.addUpdateListener { an ->
                if (sRoot != root || sWM == null) return@addUpdateListener
                lp.x = an.animatedValue as Int
                try {
                    sWM!!.updateViewLayout(root, lp)
                } catch (ignored: Throwable) {
                }
            }
            sAnim = a
            a.start()
        }

        private fun scheduleAutoRetract() {
            H.removeCallbacks(AUTO_RETRACT)
            if (sRoot != null) H.postDelayed(AUTO_RETRACT, AUTO_RETRACT_MS)
        }

        private fun sync() {
            val pp = sPlayPause
            if (pp == null) return
            if (AppColors.getSchemeVersion() != sScheme) applyColors()
            if (!OnlineMusicPageView.hasPlayback()) {
                if (++sMiss > 3) remove()
                return
            }
            sMiss = 0
            pp.setType(if (OnlineMusicPageView.playbackPaused()) IconView.PLAY else IconView.PAUSE)
        }

        private fun iconBtn(ctx: Context, type: Int, cb: View.OnClickListener): IconView {
            val v = IconView(ctx, type, AppColors.primary())
            val s = dp(ctx, BTN_DP.toFloat())
            val lp = LinearLayout.LayoutParams(s, s)
            lp.setMargins(dp(ctx, 1), 0, dp(ctx, 1), 0)
            v.layoutParams = lp
            v.setOnClickListener(cb)
            return v
        }

        /** 拖动：位移超过阈值判定为拖动(取消点击)，否则交给子 View 的点击。仅垂直移动。 */
        private class DragTouch : View.OnTouchListener {
            private var downRawY = 0f
            private var startY = 0
            private var dragging = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val root = sRoot
                if (root == null || sWM == null || root.layoutParams !is WindowManager.LayoutParams) {
                    return false
                }
                val lp = root.layoutParams as WindowManager.LayoutParams
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawY = e.rawY
                        startY = lp.y
                        dragging = false
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = e.rawY - downRawY
                        if (!dragging && Math.abs(dy) > 10) dragging = true
                        if (!dragging) return false
                        scheduleAutoRetract()
                        val h = root.context.resources.displayMetrics.heightPixels
                        val maxY = h - dp(root.context, BALL_DP.toFloat()) - dp(root.context, 8)
                        lp.y = Math.max(dp(root.context, 8),
                            Math.min(maxY, startY + dy.toInt()))
                        try {
                            sWM!!.updateViewLayout(root, lp)
                        } catch (ignored: Throwable) {
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) scheduleAutoRetract()
                        return dragging
                    }
                    else -> return false
                }
            }
        }

        /** 圆球图标：4 根白色声波音柱（居中缩小，避免过满）。 */
        private class EqIconView(ctx: Context) : View(ctx) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)

            init {
                p.color = AppColors.whiteTextOnAccent()
                p.style = Paint.Style.FILL
            }

            override fun onDraw(c: Canvas) {
                val w = width
                val h = height
                if (w <= 0 || h <= 0) return
                val icon = Math.min(w, h) * ICON_SCALE
                val barW = icon * 0.16f
                val gap = icon * 0.13f
                val total = RATIOS.size * barW + (RATIOS.size - 1) * gap
                var x = (w - total) / 2f
                val cy = h / 2f
                val r = barW / 2f
                for (ratio in RATIOS) {
                    val bh = icon * ratio
                    val top = cy - bh / 2f
                    c.drawRoundRect(RectF(x, top, x + barW, top + bh), r, r, p)
                    x += barW + gap
                }
            }

            companion object {
                private val RATIOS = floatArrayOf(0.42f, 0.92f, 0.62f, 0.30f)
                /** 图标占圆球直径比例。 */
                private const val ICON_SCALE = 0.52f
            }
        }

        /** 控制条按钮图标：上一首 / 播放 / 暂停 / 下一首 / 关闭。 */
        private class IconView(ctx: Context, type: Int, color: Int) : View(ctx) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            private var type = type

            init {
                this.type = type
                p.color = color
                p.style = Paint.Style.FILL
                p.strokeCap = Paint.Cap.ROUND
                p.strokeJoin = Paint.Join.ROUND
            }

            fun setType(type: Int) {
                if (this.type != type) {
                    this.type = type
                    invalidate()
                }
            }

            fun setColor(color: Int) {
                p.color = color
                invalidate()
            }

            override fun onDraw(c: Canvas) {
                val s = Math.min(width, height)
                if (s <= 0) return
                val pad = s * 0.27f
                val l = pad
                val t = pad
                val r = s - pad
                val b = s - pad
                val cx = s / 2f
                val cy = s / 2f
                val stroke = s * 0.11f
                when (type) {
                    PLAY -> {
                        p.style = Paint.Style.FILL
                        c.drawPath(tri(l, t, l, b, r, cy), p)
                    }
                    PAUSE -> {
                        p.style = Paint.Style.FILL
                        val bw = s * 0.14f
                        val left = cx - bw * 1.15f
                        val right = cx + bw * 0.15f
                        val rr = bw / 2f
                        c.drawRoundRect(RectF(left, t, left + bw, b), rr, rr, p)
                        c.drawRoundRect(RectF(right, t, right + bw, b), rr, rr, p)
                    }
                    PREV -> {
                        p.style = Paint.Style.FILL
                        c.drawPath(tri(r, t, r, b, l, cy), p)
                        c.drawRoundRect(RectF(l, t, l + stroke, b), stroke / 2f, stroke / 2f, p)
                    }
                    NEXT -> {
                        p.style = Paint.Style.FILL
                        c.drawPath(tri(l, t, l, b, r, cy), p)
                        c.drawRoundRect(RectF(r - stroke, t, r, b), stroke / 2f, stroke / 2f, p)
                    }
                    else -> {
                        p.style = Paint.Style.STROKE
                        p.strokeWidth = stroke
                        c.drawLine(l, t, r, b, p)
                        c.drawLine(r, t, l, b, p)
                    }
                }
            }

            companion object {
                const val PREV = 0
                const val PLAY = 1
                const val PAUSE = 2
                const val NEXT = 3
                const val CLOSE = 4

                private fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): android.graphics.Path {
                    val path = android.graphics.Path()
                    path.moveTo(x1, y1)
                    path.lineTo(x2, y2)
                    path.lineTo(x3, y3)
                    path.close()
                    return path
                }
            }
        }

        private fun dp(ctx: Context, v: Float): Int {
            return (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
        }

        private fun dp(ctx: Context, v: Int): Int {
            return (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
        }
    }
}
