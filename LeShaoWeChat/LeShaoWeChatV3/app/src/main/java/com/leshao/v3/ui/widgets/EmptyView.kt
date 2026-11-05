package com.leshao.v3.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.leshao.v3.ui.AppColors

/** 空状态占位 · v3.0.457 玻璃风：主色微光圆片 + 提示文案 + 可选副文案 */
class EmptyView(ctx: Context, icon: String?, msg: String?) : LinearLayout(ctx) {

    private val mMsg: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val d = ctx.resources.displayMetrics.density
        setPadding((24 * d).toInt(), (48 * d).toInt(), (24 * d).toInt(), (48 * d).toInt())

        // 图标玻璃圆片：主色 15% 底 + 主色描边
        val chip = LinearLayout(ctx)
        chip.orientation = LinearLayout.VERTICAL
        chip.gravity = Gravity.CENTER
        val chipSize = (88 * d).toInt()
        val chipBg = GradientDrawable()
        chipBg.shape = GradientDrawable.OVAL
        chipBg.setColor((AppColors.primary() and 0x00FFFFFF) or 0x26FFFFFF)
        chipBg.setStroke(Math.max(1, (1 * d + 0.5f).toInt()), AppColors.primary())
        chip.background = chipBg

        val mIcon = TextView(ctx)
        mIcon.text = icon ?: "📭"
        mIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        mIcon.gravity = Gravity.CENTER
        chip.addView(mIcon)
        addView(chip, LinearLayout.LayoutParams(chipSize, chipSize))

        mMsg = TextView(ctx)
        mMsg.text = msg ?: "暂无数据"
        mMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        mMsg.typeface = Typeface.DEFAULT_BOLD
        mMsg.setTextColor(AppColors.textTertiary())
        mMsg.gravity = Gravity.CENTER
        mMsg.setPadding(0, (12 * d).toInt(), 0, 0)
        addView(mMsg)
    }

    fun setMsg(s: String?): EmptyView {
        mMsg.text = s
        return this
    }
}