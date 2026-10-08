package com.leshao.v3.ui.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.leshao.v3.ui.AppColors
import com.leshao.v3.ui.CandyUi
import com.leshao.v3.ui.InsetsUtil

/**
 * M3 Expressive 页面构建工具箱 —— 全部页面深度重排的统一结构件：
 *  root / section / card / switchRow / clickRow / textRow / divider
 *  button / input / spacer
 * 每个页面按 M3 Expressive 规范：12dp 页边距、24dp 大圆角卡片、粗体分区标题、行高 ≥48dp、状态层按压。
 */
class M3Page private constructor() {

    /** 勾选行回调（避免依赖 CompoundButton 的 buttonView 参数）。 */
    interface CheckListener {
        fun onChanged(checked: Boolean)
    }

    companion object {
        private fun density(ctx: Context): Float {
            return ctx.resources.displayMetrics.density
        }

        private fun dp(ctx: Context, v: Float): Int {
            return (v * density(ctx) + 0.5f).toInt()
        }

        private fun dp(ctx: Context, v: Int): Int {
            return (v * density(ctx) + 0.5f).toInt()
        }

        // ==================== 页面骨架 ====================

        /** 页面根：外层透明壳 + 内部 surface 圆角浮层（内容全部挂在内层）。 */
        @JvmStatic
        fun root(ctx: Context): LinearLayout {
            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.background = CandyUi.pageGradient()
            InsetsUtil.clipRounded(root)
            val m = dp(ctx, 12)
            // 全局规范: 页面左右边距 12dp，底部留白 12dp，避免页面根圆角区裁切底部按钮/圆角描边
            root.setPadding(m, dp(ctx, 6), m, dp(ctx, 12))
            return root
        }

        /** 可滚动页面容器（根已内含） */
        @JvmStatic
        fun scroll(ctx: Context, root: LinearLayout): ScrollView {
            val sv = ScrollView(ctx)
            sv.isFillViewport = true
            sv.addView(InsetsUtil.host(root))
            return sv
        }

        /** 分区标题（M3 list subheader）。v3.0.140 起统一返回不可见占位 View。 */
        @JvmStatic
        fun section(ctx: Context, title: String?): View {
            return section(ctx, title, null)
        }

        @JvmStatic
        fun section(ctx: Context, title: String?, sub: String?): View {
            val placeholder = View(ctx)
            placeholder.visibility = View.GONE
            return placeholder
        }

        /** 卡片容器（M3 Expressive filled card：24dp 大圆角 + 纯白底） */
        @JvmStatic
        fun card(ctx: Context): LinearLayout {
            val card = LinearLayout(ctx)
            card.orientation = LinearLayout.VERTICAL
            card.background = CandyUi.cardBg(ctx)
            InsetsUtil.clipRounded(card)
            val p = dp(ctx, 0)
            card.setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, 0, 0, dp(ctx, AppColors.SPACE_CARD_GAP_DP.toFloat()))
            card.layoutParams = lp
            return card
        }

        /** 卡片内垂直间距 */
        @JvmStatic
        fun cardSpacer(ctx: Context, dpV: Float): View {
            val v = View(ctx)
            v.layoutParams = LinearLayout.LayoutParams(-1, dp(ctx, dpV))
            return v
        }

        /** M3 内分割线（左侧留出图标宽度） */
        @JvmStatic
        fun divider(ctx: Context): View {
            val v = View(ctx)
            val lp = LinearLayout.LayoutParams(-1, 1)
            lp.setMargins(dp(ctx, 16), 0, 0, 0)
            v.layoutParams = lp
            v.setBackgroundColor(AppColors.outlineVariant())
            return v
        }

        // ==================== 行 ====================

        /** 开关行：图标 + 标题 + 副标题 + M3 开关 */
        @JvmStatic
        fun switchRow(ctx: Context, icon: String?, title: String?, sub: String?,
                      checked: Boolean, l: CompoundButton.OnCheckedChangeListener?): View {
            return SettingRow(ctx, icon, title, sub).switchOn(checked, l)
        }

        /** 导航行：图标 + 标题 + 副标题 + 箭头（点击） */
        @JvmStatic
        fun clickRow(ctx: Context, icon: String?, title: String?, sub: String?, onClick: Runnable?): View {
            return SettingRow(ctx, icon, title, sub).arrow(onClick)
        }

        /** 尾部自定义控件行 */
        @JvmStatic
        fun tailRow(ctx: Context, icon: String?, title: String?, sub: String?, tail: View?): View {
            return SettingRow(ctx, icon, title, sub).tail(tail)
        }

        /** 创建导航行并追加到卡片, 返回可更新副标题的行对象。 */
        @JvmStatic
        fun appendClickRow(card: LinearLayout?, ctx: Context, icon: String?,
                          title: String?, sub: String?, onClick: Runnable?): SettingRow {
            val row = SettingRow(ctx, icon, title, sub)
            if (onClick != null) {
                row.setOnClickListener {
                    try {
                        onClick.run()
                    } catch (ignored: Throwable) {
                    }
                }
            }
            if (card != null) card.addView(row)
            return row
        }

        /** 创建开关行并追加到卡片, 返回底层 framework Switch 以便读取状态。 */
        @JvmStatic
        fun appendSwitchRow(card: LinearLayout?, ctx: Context, icon: String?, title: String?,
                            sub: String?, checked: Boolean,
                            listener: CompoundButton.OnCheckedChangeListener?): Switch {
            val sw = CandyUi.newSwitch(ctx)
            sw.isChecked = checked
            if (listener != null) sw.setOnCheckedChangeListener(listener)
            val d = density(ctx)
            val w = (AppColors.SWITCH_WIDTH_DP * d + 0.5f).toInt()
            val h = (AppColors.SWITCH_HEIGHT_DP * d + 0.5f).toInt()
            val row = SettingRow(ctx, icon, title, sub)
            row.tail(sw)
            row.setOnClickListener {
                try {
                    sw.toggle()
                } catch (ignored: Throwable) {
                }
            }
            if (card != null) card.addView(row)
            return sw
        }

        /** 底部等宽双按钮行。 */
        @JvmStatic
        fun buttonRow(ctx: Context, left: View, right: View): View {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val lpL = LinearLayout.LayoutParams(0, -2, 1f)
            lpL.setMargins(0, dp(ctx, 16), dp(ctx, 4), 0)
            val lpR = LinearLayout.LayoutParams(0, -2, 1f)
            lpR.setMargins(dp(ctx, 4), dp(ctx, 16), 0, 0)
            left.layoutParams = lpL
            right.layoutParams = lpR
            row.addView(left)
            row.addView(right)
            return row
        }

        /** 纯信息行（标题 + 右侧值文字） */
        @JvmStatic
        fun infoRow(ctx: Context, title: String?, value: String?): View {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val p = dp(ctx, 12)
            // 全局规范: 行触控区域不低于 48dp
            row.minimumHeight = dp(ctx, 48)
            row.setPadding(p, dp(ctx, 14), p, dp(ctx, 14))
            row.background = CandyUi.rowPressBg(ctx)

            val t = TextView(ctx)
            t.text = title
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            t.typeface = Typeface.DEFAULT_BOLD
            t.setTextColor(AppColors.onSurface())
            t.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            row.addView(t)

            if (!TextUtils.isEmpty(value)) {
                val v = TextView(ctx)
                v.text = value
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                v.setTextColor(AppColors.onSurfaceVariant())
                v.setSingleLine(true)
                v.ellipsize = TextUtils.TruncateAt.END
                row.addView(v)
            }
            return row
        }

        // ==================== 控件 ====================

        /** M3 filled button */
        @JvmStatic
        fun button(ctx: Context, text: String?, onClick: Runnable?): View {
            return ModernButton(ctx, text, ModernButton.STYLE_PRIMARY).onClick(onClick)
        }

        /** M3 outlined button */
        @JvmStatic
        fun ghostButton(ctx: Context, text: String?, onClick: Runnable?): View {
            return ModernButton(ctx, text, ModernButton.STYLE_GHOST).onClick(onClick)
        }

        /** M3 danger button */
        @JvmStatic
        fun dangerButton(ctx: Context, text: String?, onClick: Runnable?): View {
            return ModernButton(ctx, text, ModernButton.STYLE_DANGER).onClick(onClick)
        }

        /** M3 filled text field */
        @JvmStatic
        fun input(ctx: Context, hint: String?): EditText {
            val et = EditText(ctx)
            et.setHint(hint)
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            et.setTextColor(AppColors.onSurface())
            et.setHintTextColor(AppColors.onSurfaceVariant())
            et.setSingleLine(true)
            // 长内容(如接口地址/密钥)支持左右拖动查看, 不被截断
            et.setHorizontallyScrolling(true)
            et.background = CandyUi.inputBg(ctx)
            val p = dp(ctx, 14)
            et.setPadding(p, dp(ctx, 12), p, dp(ctx, 12))
            return et
        }

        /**
         * v1055: 让受 maxLines 限制的多行输入框在文本超出时可在框内上下滚动。
         */
        @JvmStatic
        fun enableVerticalScroll(et: EditText?) {
            if (et == null) return
            et.isVerticalScrollBarEnabled = true
            et.scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            et.movementMethod = ScrollingMovementMethod.getInstance()
            et.setOnTouchListener { v, event ->
                try {
                    v.getParent().requestDisallowInterceptTouchEvent(true)
                    val action = event.actionMasked
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        v.getParent().requestDisallowInterceptTouchEvent(false)
                    }
                } catch (ignored: Throwable) {
                }
                false
            }
        }

        /**
         * 为单行输入框附加「自动剔除首尾空白」能力。
         */
        @JvmStatic
        fun trimEdgesOnInput(et: EditText?) {
            if (et == null) return
            et.setHorizontallyScrolling(true)
            et.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable) {
                    if (s == null) return
                    val len = s.length
                    var start = 0
                    var end = len
                    while (start < end && isEdgeBlank(s[start])) start++
                    while (end > start && isEdgeBlank(s[end - 1])) end--
                    if (start == 0 && end == len) return
                    val cleaned = s.subSequence(start, end)
                    et.setText(cleaned)
                    et.setSelection(cleaned.length)
                }
            })
        }

        /** 首尾需剔除的空白/不可见字符 */
        private fun isEdgeBlank(c: Char): Boolean {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r'
                    || c == '\u00A0' || c == '\u3000' || c == '\u200B' || c == '\uFEFF'
        }

        /** 页面区块间距 */
        @JvmStatic
        fun spacer(ctx: Context, dpV: Float): View {
            val v = View(ctx)
            v.layoutParams = LinearLayout.LayoutParams(-1, dp(ctx, dpV))
            return v
        }

        // ==================== 文本排版（M3 type scale） ====================

        /** 页面/弹窗标题（M3 Expressive title large · onSurface · 粗体 · 微字距） */
        @JvmStatic
        fun title(ctx: Context, text: String?): TextView {
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.setTextColor(AppColors.onSurface())
            tv.gravity = Gravity.CENTER
            tv.letterSpacing = 0.02f
            tv.setPadding(0, 0, 0, dp(ctx, 2))
            return tv
        }

        /** 字段标签（M3 label medium · onSurfaceVariant，置于输入框上方） */
        @JvmStatic
        fun fieldLabel(ctx: Context, text: String?): TextView {
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            tv.setTextColor(AppColors.onSurfaceVariant())
            tv.setPadding(dp(ctx, 2), dp(ctx, 6), 0, dp(ctx, 2))
            return tv
        }

        /** 说明/提示段落（M3 body small · onSurfaceVariant） */
        @JvmStatic
        fun note(ctx: Context, text: String?): TextView {
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            tv.setTextColor(AppColors.onSurfaceVariant())
            tv.setLineSpacing(dp(ctx, 2).toFloat(), 1.2f)
            tv.setPadding(dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), dp(ctx, 8))
            return tv
        }

        /**
         * M3 滑杆：糖果粉纯色进度 + 圆形滑块 + surfaceContainerHighest 轨道
         * （framework SeekBar，避免 appcompat 属性碰撞）。
         */
        @JvmStatic
        fun slider(ctx: Context): SeekBar {
            val sb = SeekBar(ctx)
            val h = dp(ctx, 8)
            try {
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    sb.progressTintList = null
                    sb.thumbTintList = null
                    sb.progressBackgroundTintList = null
                }
                val track = GradientDrawable()
                track.shape = GradientDrawable.RECTANGLE
                track.cornerRadius = h / 2f
                track.setColor(AppColors.surfaceContainerHighest())

                val fill = GradientDrawable()
                fill.shape = GradientDrawable.RECTANGLE
                fill.cornerRadius = h / 2f
                fill.setSize(0, h)
                fill.setColor(AppColors.primary())

                val clip = ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)
                clip.level = 5000

                val thumbSize = dp(ctx, 24)
                val thumb = GradientDrawable()
                thumb.shape = GradientDrawable.OVAL
                thumb.cornerRadius = thumbSize / 2f
                thumb.setSize(thumbSize, thumbSize)
                thumb.setColor(AppColors.primary())

                sb.background = track
                sb.progressDrawable = clip
                sb.thumb = thumb
                sb.thumbOffset = 0
                sb.splitTrack = false
                sb.minimumHeight = dp(ctx, 20)
            } catch (t: Throwable) {
                try {
                    sb.progressTintList = ColorStateList.valueOf(AppColors.primary())
                    sb.thumbTintList = ColorStateList.valueOf(AppColors.primary())
                    sb.progressBackgroundTintList = ColorStateList.valueOf(AppColors.surfaceContainerHighest())
                } catch (ignored: Throwable) {
                }
            }
            sb.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
            return sb
        }

        /**
         * 可勾选列表行（M3 list item）：图标 + 标题 + 副标题 + 尾部圆形勾选标记，
         * 点击整行切换勾选态并回调。
         */
        @JvmStatic
        fun checkRow(ctx: Context, icon: String?, title: String?, sub: String?,
                     checked: Boolean, listener: CheckListener?): SettingRow {
            val row = SettingRow(ctx, icon, title, sub)
            val mark = TextView(ctx)
            val state = booleanArrayOf(checked)
            val size = dp(ctx, 28)
            mark.gravity = Gravity.CENTER
            mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            mark.typeface = Typeface.DEFAULT_BOLD
            mark.layoutParams = LinearLayout.LayoutParams(size, size)
            val refresh = Runnable {
                if (state[0]) {
                    val fg = GradientDrawable()
                    fg.shape = GradientDrawable.OVAL
                    fg.cornerRadius = size / 2f
                    fg.setColor(AppColors.primary())
                    mark.background = fg
                    mark.text = "✓"
                    mark.setTextColor(AppColors.onGradient())
                } else {
                    val bg = GradientDrawable()
                    bg.shape = GradientDrawable.OVAL
                    bg.setColor(0x00000000)
                    bg.setStroke(dp(ctx, 2), AppColors.outline())
                    mark.background = bg
                    mark.text = ""
                }
            }
            refresh.run()
            row.tail(mark)
            row.setOnClickListener {
                state[0] = !state[0]
                refresh.run()
                listener?.onChanged(state[0])
            }
            return row
        }

        /**
         * M3 风格复选框：primary 勾选色 + onSurface 文案，供弹窗/滚动列表内单独使用。
         */
        @JvmStatic
        fun checkBox(ctx: Context, text: String?): CheckBox {
            val cb = CheckBox(ctx)
            if (text != null) cb.text = text
            cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            cb.setTextColor(AppColors.onSurface())
            try {
                cb.buttonTintList = ColorStateList.valueOf(AppColors.primary())
            } catch (ignored: Throwable) {
            }
            cb.setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
            return cb
        }

        @JvmStatic
        fun checkBox(ctx: Context): CheckBox {
            return checkBox(ctx, null)
        }

        /** 空状态 */
        @JvmStatic
        fun empty(ctx: Context, icon: String?, msg: String?): View {
            return EmptyView(ctx, icon, msg)
        }

        /** M3 snackbar 提示 */
        @JvmStatic
        fun toast(ctx: Context, msg: String?) {
            ToastHelper.show(ctx, msg)
        }

        @JvmStatic
        fun toastSuccess(ctx: Context, msg: String?) {
            ToastHelper.success(ctx, msg)
        }

        @JvmStatic
        fun toastError(ctx: Context, msg: String?) {
            ToastHelper.error(ctx, msg)
        }
    }
}
