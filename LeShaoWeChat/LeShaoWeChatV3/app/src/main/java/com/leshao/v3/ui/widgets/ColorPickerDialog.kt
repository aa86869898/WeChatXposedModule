package com.leshao.v3.ui.widgets

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.leshao.v3.LogWriter
import com.leshao.v3.ui.AppColors
import com.leshao.v3.ui.CandyUi
import com.leshao.v3.ui.InsetsUtil
import com.leshao.v3.ui.WindowLayer

/**
 * v3.0.166 自定义 HSV 取色器（任务3：去掉格子取色，改交互式取色，实时生效）。
 *
 * 不依赖任何第三方库：纯 View 绘制「色相条 + 饱和度/亮度二维面板 + 预览 + hex 回显」。
 * 拖动过程中实时回调 OnPick#onPick(int)（每次松手或节流后即触发），无需点「确定」即可看到颜色变化。
 *
 * 回调 color == 0 表示"不修改/恢复默认"。确认按钮保留，便于取到精确色后再提交。
 */
class ColorPickerDialog private constructor() {

    interface OnPick {
        /** @param color 0 = 不修改(恢复微信原生)。实时回调时会不断携带当前色。 */
        fun onPick(color: Int)
    }

    /** v3.0.205：实时预览回调 —— 拖动 SV 面板/色相条时高频触发（无节流原始频率）。 */
    interface OnPreview {
        fun onPreview(color: Int)
    }

    companion object {
        private const val HUE_BAR_DP = 26f
        private const val SV_PANEL_DP = 220f
        private const val THUMB_DP = 12f

        @JvmStatic
        fun show(ctx: Context?, title: String?, initialColor: Int,
                 allowDefault: Boolean, cb: OnPick?) {
            show(ctx, title, initialColor, allowDefault, cb, null)
        }

        @JvmStatic
        fun show(ctx: Context?, title: String?, initialColor: Int,
                 allowDefault: Boolean, cb: OnPick?, preview: OnPreview?) {
            if (ctx == null) return
            val d = ctx.resources.displayMetrics.density
            val dlgTheme = if (AppColors.isDarkMode())
                android.R.style.Theme_DeviceDefault_Dialog_Alert
            else
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
            val dialog = AlertDialog.Builder(ctx, dlgTheme).create()

            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            val pad = (16 * d).toInt()
            root.setPadding(pad, (14 * d).toInt(), pad, (8 * d).toInt())
            root.background = CandyUi.dialogBg(ctx)
            InsetsUtil.clipRounded(root)

            val titleTv = TextView(ctx)
            titleTv.text = title ?: "选择颜色"
            titleTv.setTextSize(16f)
            titleTv.setTextColor(AppColors.text1())
            titleTv.typeface = null
            titleTv.setTypeface(null, Typeface.BOLD)
            titleTv.setPadding(0, 0, 0, (12 * d).toInt())
            root.addView(titleTv)

            // ---- 饱和度/亮度 二维面板 + 色相条（实时绘制） ----
            val hsv = FloatArray(3)
            val initial = if (initialColor == 0) 0xFF000000.toInt() else initialColor
            Color.colorToHSV(initial, hsv)

            val svPanel = SvPanel(ctx)
            val svLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (SV_PANEL_DP * d).toInt())
            svPanel.layoutParams = svLp
            root.addView(svPanel)

            root.addView(spacer(ctx, d, 8f))

            val hueBar = HueBar(ctx)
            val hLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (HUE_BAR_DP * d).toInt())
            hueBar.layoutParams = hLp
            root.addView(hueBar)

            // 联动模型：hue 由 HueBar 提供，s/v 由 SvPanel 提供；任一变化重算 hsv/color 并回调
            val current = intArrayOf(Color.HSVToColor(hsv))
            // v3.0.206：程序内回显 hex 时的同步标志，避免 afterTextChanged 反推 hsv 把面板状态覆盖回去
            val syncingHex = booleanArrayOf(false)
            val repaint = Runnable {
                svPanel.invalidate()
                hueBar.invalidate()
            }

            // ---- 预览：色块 + hex 文本 ----
            val previewRow = LinearLayout(ctx)
            previewRow.orientation = LinearLayout.HORIZONTAL
            previewRow.gravity = Gravity.CENTER_VERTICAL

            val swatch = View(ctx)
            val sw = (34 * d).toInt()
            val swLp = LinearLayout.LayoutParams(sw, sw)
            swatch.layoutParams = swLp
            previewRow.addView(swatch)

            val hexTv = TextView(ctx)
            hexTv.setTextSize(15f)
            hexTv.setTextColor(AppColors.text1())
            hexTv.setPadding((12 * d).toInt(), 0, 0, 0)
            previewRow.addView(hexTv)
            root.addView(previewRow)

            root.addView(spacer(ctx, d, 12f))

            // 十六进制输入
            val hexInput = EditText(ctx)
            hexInput.setText(toHex(current[0]))
            hexInput.setHint("#RRGGBB")
            hexInput.setTextSize(14f)
            hexInput.setSingleLine(true)
            hexInput.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            hexInput.setTextColor(AppColors.text1())
            hexInput.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            val inputBg = GradientDrawable()
            inputBg.setColor(AppColors.inputBg())
            inputBg.cornerRadius = (AppColors.SHAPE_INPUT_DP * d).toInt().toFloat()
            inputBg.setStroke((1.5f * d).toInt(), AppColors.outlineVariant())
            hexInput.background = inputBg
            root.addView(hexInput)

            root.addView(spacer(ctx, d, 10f))

            // 按钮行
            val btnRow = LinearLayout(ctx)
            btnRow.orientation = LinearLayout.HORIZONTAL
            btnRow.gravity = Gravity.CENTER_VERTICAL

            if (allowDefault) {
                val def = textBtn(ctx, d, "默认", AppColors.text2(), false)
                def.setOnClickListener {
                    cb?.onPick(0)
                    dialog.dismiss()
                }
                btnRow.addView(def)
            }
            val filler = View(ctx)
            filler.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            btnRow.addView(filler)

            val cancel = textBtn(ctx, d, "取消", AppColors.text2(), false)
            cancel.setOnClickListener { dialog.dismiss() }
            btnRow.addView(cancel)

            val confirm = textBtn(ctx, d, "确定", AppColors.accent(), true)
            confirm.setOnClickListener {
                val c = parseHex(hexInput.text.toString(), current[0])
                cb?.onPick(c)
                dialog.dismiss()
            }
            btnRow.addView(confirm)
            root.addView(btnRow)

            val firePick = Runnable {
                val c = Color.HSVToColor(hsv)
                current[0] = c
                hexTv.text = toHex(c)
                syncingHex[0] = true
                try {
                    hexInput.setText(toHex(c))
                } finally {
                    syncingHex[0] = false
                }
                swatch.background = swatchBg(c, d)
                // v3.0.205：拖动实时回调走 OnPreview（不重建页面），确认「确定」才走 OnPick。
                if (preview != null) {
                    preview.onPreview(c)
                } else {
                    cb?.onPick(c)
                }
            }

            svPanel.setListener { h ->
                hsv[1] = h[0]
                hsv[2] = h[1]
                repaint.run()
                firePick.run()
            }
            hueBar.setListener { hue ->
                hsv[0] = hue
                // v3.0.207：同步色相到 SV 面板底色 —— SvPanel.onDraw 用内部 mHue 画 S/V 渐变
                svPanel.setHue(hue)
                repaint.run()
                firePick.run()
            }
            hueBar.setHue(hsv[0])
            svPanel.setHue(hsv[0])
            svPanel.setSv(hsv[1], hsv[2])

            // 输入联动预览
            hexInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable) {
                    // v3.0.206：拖动 SV/色相条时 firePick 会 setText 回显 hex，
                    // 程序内同步不重复回写 hsv（否则会把刚拖动的值反推覆盖回去）
                    if (syncingHex[0]) return
                    val c = tryParse(s?.toString())
                    if (c == null) return
                    current[0] = c
                    Color.colorToHSV(c, hsv)
                    hueBar.setHue(hsv[0])
                    svPanel.setHue(hsv[0])
                    svPanel.setSv(hsv[1], hsv[2])
                    hexTv.text = toHex(c)
                    swatch.background = swatchBg(c, d)
                    svPanel.invalidate()
                    hueBar.invalidate()
                    if (preview != null) {
                        preview.onPreview(c)
                    } else {
                        cb?.onPick(c)
                    }
                }
            })

            swatch.background = swatchBg(current[0], d)
            hexTv.text = toHex(current[0])

            dialog.setView(root)
            InsetsUtil.transparentWindow(dialog)
            dialog.show()
            WindowLayer.track(dialog.window)
        }

        // ==================== HSV 控件 ====================

        /** 饱和度(S)/亮度(V) 二维面板。 */
        private class SvPanel internal constructor(ctx: Context) : View(ctx) {
            private val mPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val mThumb = Paint(Paint.ANTI_ALIAS_FLAG)
            private val mThumbStroke = Paint(Paint.ANTI_ALIAS_FLAG)
            private var mHue = 0f
            private var mS = 0f   // 0..1
            private var mV = 1f   // 0..1
            private var mListener: Listener? = null

            fun interface Listener {
                fun onChanged(sv: FloatArray)
            }

            init {
                mThumb.style = Paint.Style.FILL
                mThumb.color = Color.WHITE
                mThumbStroke.style = Paint.Style.STROKE
                mThumbStroke.setColor(0x55000000.toInt())
                mThumbStroke.strokeWidth = dp(1.5f)
            }

            private fun dp(v: Float): Float {
                return v * resources.displayMetrics.density
            }

            fun setHue(hue: Float) {
                mHue = hue
                invalidate()
            }

            fun setSv(s: Float, v: Float) {
                mS = s
                mV = v
                invalidate()
            }

            fun setListener(l: Listener) {
                mListener = l
            }

            private fun xyFromSV(): FloatArray {
                val w = width - dp(4f)
                val h = height - dp(4f)
                return floatArrayOf(dp(2f) + mS * w, dp(2f) + (1f - mV) * h)
            }

            private fun svFromTouch(x: Float, y: Float) {
                val w = width - dp(4f)
                val h = height - dp(4f)
                mS = if (w <= 0) 0f else Math.max(0f, Math.min(1f, (x - dp(2f)) / w))
                mV = if (h <= 0) 1f else Math.max(0f, Math.min(1f, 1f - (y - dp(2f)) / h))
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val w = width
                val h = height
                if (w <= 0 || h <= 0) return
                // S 方向：白 → 纯色；V 方向：黑 → 纯色
                val base = Color.HSVToColor(floatArrayOf(mHue, 1f, 1f))
                val sg = LinearGradient(dp(2f), 0f, w - dp(2f), 0f,
                    intArrayOf(Color.WHITE, base), null, Shader.TileMode.CLAMP)
                val vg = LinearGradient(0f, dp(2f), 0f, h - dp(2f),
                    intArrayOf(0xFF000000.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
                val bg = Paint()
                bg.shader = sg
                canvas.drawRect(dp(2f), dp(2f), w - dp(2f), h - dp(2f), bg)
                val vp = Paint()
                vp.shader = vg
                canvas.drawRect(dp(2f), dp(2f), w - dp(2f), h - dp(2f), vp)

                val xy = xyFromSV()
                val r = dp(THUMB_DP / 2f)
                canvas.drawCircle(xy[0], xy[1], r, mThumbStroke)
                canvas.drawCircle(xy[0], xy[1], r - dp(1.2f), mThumb)
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                val x = ev.x
                val y = ev.y
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        if (ev.actionMasked == MotionEvent.ACTION_DOWN && mListener != null) {
                            LogWriter.log("ColorPicker", "SvPanel touch DOWN at ${x.toInt()},${y.toInt()}")
                        }
                        svFromTouch(x, y)
                        invalidate()
                        // v3.0.206：回调加保护，回调异常不回传中断触摸（否则拖动会"点不动"）
                        val l = mListener
                        if (l != null) {
                            try {
                                l.onChanged(floatArrayOf(mS, mV))
                            } catch (ignored: Throwable) {
                            }
                        }
                        return true
                    }
                    else -> return super.onTouchEvent(ev)
                }
            }
        }

        /** 色相条。 */
        private class HueBar internal constructor(ctx: Context) : View(ctx) {
            private val mThumb = Paint(Paint.ANTI_ALIAS_FLAG)
            private val mThumbStroke = Paint(Paint.ANTI_ALIAS_FLAG)
            private var mHue = 0f
            private var mListener: Listener? = null

            fun interface Listener {
                fun onChanged(hue: Float)
            }

            init {
                mThumb.style = Paint.Style.FILL
                mThumb.color = Color.WHITE
                mThumbStroke.style = Paint.Style.STROKE
                mThumbStroke.setColor(0x55000000.toInt())
                mThumbStroke.strokeWidth = dp(1.5f)
            }

            private fun dp(v: Float): Float {
                return v * resources.displayMetrics.density
            }

            fun setHue(hue: Float) {
                mHue = hue
                invalidate()
            }

            fun setListener(l: Listener) {
                mListener = l
            }

            private fun thumbX(): Float {
                val w = width - dp(2f)
                return dp(1f) + (mHue / 360f) * w
            }

            private fun hueFromTouch(x: Float) {
                val w = width - dp(2f)
                mHue = if (w <= 0) 0f else Math.max(0f, Math.min(360f, ((x - dp(1f)) / w) * 360f))
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val w = width
                val h = height
                if (w <= 0 || h <= 0) return
                val n = 12
                val colors = IntArray(n)
                val stops = FloatArray(n)
                for (i in 0 until n) {
                    stops[i] = i / (n - 1).toFloat()
                    colors[i] = Color.HSVToColor(floatArrayOf(stops[i] * 360f, 1f, 1f))
                }
                val g = LinearGradient(dp(1f), 0f, w - dp(1f), 0f,
                    colors, stops, Shader.TileMode.CLAMP)
                val bg = Paint()
                bg.shader = g
                canvas.drawRect(dp(1f), 0f, w - dp(1f), h.toFloat(), bg)
                val x = thumbX()
                val r = dp(THUMB_DP / 2f)
                canvas.drawCircle(x, h / 2f, r, mThumbStroke)
                canvas.drawCircle(x, h / 2f, r - dp(1.2f), mThumb)
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        if (ev.actionMasked == MotionEvent.ACTION_DOWN && mListener != null) {
                            LogWriter.log("ColorPicker", "HueBar touch DOWN at ${ev.x.toInt()}")
                        }
                        hueFromTouch(ev.x)
                        invalidate()
                        // v3.0.206：回调加保护，回调异常不回传中断触摸
                        val l = mListener
                        if (l != null) {
                            try {
                                l.onChanged(mHue)
                            } catch (ignored: Throwable) {
                            }
                        }
                        return true
                    }
                    else -> return super.onTouchEvent(ev)
                }
            }
        }

        // ==================== 通用 ====================

        private fun spacer(ctx: Context, d: Float, dp: Float): View {
            val v = View(ctx)
            v.layoutParams = LinearLayout.LayoutParams(-1, (dp * d).toInt())
            return v
        }

        private fun textBtn(ctx: Context, d: Float, text: String, color: Int, bold: Boolean): TextView {
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextSize(14f)
            tv.setTextColor(color)
            if (bold) tv.setTypeface(null, Typeface.BOLD)
            tv.setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            CandyUi.ripple(tv, AppColors.SHAPE_FULL_DP.toFloat())
            return tv
        }

        private fun swatchBg(color: Int, d: Float): GradientDrawable {
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.RECTANGLE
            gd.setColor(color)
            gd.cornerRadius = (7 * d).toInt().toFloat()
            gd.setStroke((1 * d).toInt(), 0x33000000)
            return gd
        }

        private fun toHex(color: Int): String {
            return String.format("#%06X", 0xFFFFFF and color)
        }

        private fun tryParse(s: String?): Int? {
            if (s == null) return null
            var t = s.trim()
            if (t.isEmpty()) return null
            if (!t.startsWith("#")) t = "#" + t
            return try {
                Color.parseColor(t)
            } catch (t2: Throwable) {
                null
            }
        }

        private fun parseHex(s: String, fallback: Int): Int {
            val c = tryParse(s)
            return c ?: fallback
        }
    }
}
