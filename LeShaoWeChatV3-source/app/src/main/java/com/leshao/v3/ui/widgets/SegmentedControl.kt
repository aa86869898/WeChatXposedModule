package com.leshao.v3.ui.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.leshao.v3.ui.AppColors
import com.leshao.v3.ui.CandyUi

/** 分段选择器：替代 spinner/胶囊组，选中项主色底白字，未选中卡片底主色字 */
class SegmentedControl(ctx: Context, items: Array<String>, initial: Int) : LinearLayout(ctx) {

    interface OnSegmentChangedListener {
        fun onChanged(index: Int, label: String)
    }

    private var mSelected = -1
    private var mListener: OnSegmentChangedListener? = null

    init {
        orientation = HORIZONTAL
        val d = resources.displayMetrics.density
        val pad = (3 * d).toInt()
        setPadding(pad, pad, pad, pad)
        try {
            val containerBg = GradientDrawable()
            containerBg.shape = GradientDrawable.RECTANGLE
            containerBg.setCornerRadius(dp(ctx, AppColors.SHAPE_FULL_DP.toFloat()).toFloat())
            containerBg.setColor(0x00000000)
            containerBg.setStroke(dp(ctx, 1f), AppColors.outline())
            background = containerBg
        } catch (ignored: Throwable) {}

        for (i in items.indices) {
            val idx = i
            val label = items[i]
            val seg = TextView(ctx)
            seg.text = label
            seg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            seg.gravity = Gravity.CENTER
            seg.setSingleLine(true)
            seg.isClickable = true
            seg.isFocusable = true
            // v1033 M3: 未选中段为透明底, 用 foreground 涟漪保证按压反馈
            try {
                val mask = GradientDrawable()
                mask.shape = GradientDrawable.RECTANGLE
                mask.setCornerRadius(dp(ctx, AppColors.SHAPE_FULL_DP.toFloat()).toFloat())
                mask.setColor(0xFFFFFFFF.toInt())
                seg.foreground = RippleDrawable(
                    ColorStateList.valueOf(AppColors.stateLayerPressed()),
                    null, mask)
            } catch (ignored: Throwable) {}
            val lp = LayoutParams(0, (36 * d).toInt(), 1f)
            seg.layoutParams = lp
            seg.setOnClickListener { select(idx, true) }
            addView(seg)
        }
        if (initial >= 0 && initial < items.size) select(initial, false)
    }

    fun select(index: Int, notify: Boolean) {
        if (index < 0 || index >= childCount) return
        mSelected = index
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val sel = (i == index)
            if (child is TextView) {
                val tv = child as TextView
                tv.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                tv.setTextColor(if (sel) AppColors.textOnPrimary() else AppColors.textSecondary())
            }
            try {
                child.background = if (sel) CandyUi.pillBg(true, context) else null
            } catch (ignored: Throwable) {}
        }
        if (notify && mListener != null) {
            try {
                var label = ""
                val child = getChildAt(index)
                if (child is TextView) {
                    label = child.text.toString()
                }
                mListener!!.onChanged(index, label)
            } catch (ignored: Throwable) {}
        }
    }

    fun getSelectedIndex(): Int {
        return mSelected
    }

    fun setOnSegmentChangedListener(l: OnSegmentChangedListener?): SegmentedControl {
        mListener = l
        return this
    }

    companion object {
        private fun dp(ctx: Context, v: Float): Int {
            return (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
        }
    }
}