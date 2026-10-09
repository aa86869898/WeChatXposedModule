package com.leshao.v3.ui.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.leshao.v3.ui.AppColors

/** 模块统一顶栏：返回箭头 + 标题 + 右侧动作区 */
class ModernTopBar(ctx: Context, title: String?, showBack: Boolean, onBack: Runnable?) : LinearLayout(ctx) {

    private val mBack: TextView
    private val mTitle: TextView
    private val mActions: LinearLayout

    init {
        val d = resources.displayMetrics.density
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (AppColors.TOP_BAR_HEIGHT_DP * d).toInt()
        setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)

        mBack = TextView(ctx)
        mBack.text = "‹"
        mBack.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        mBack.setTextColor(AppColors.primary())
        mBack.gravity = Gravity.CENTER
        mBack.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
        mBack.isClickable = true
        applyRipple(mBack, AppColors.SHAPE_FULL_DP.toFloat())
        if (showBack) {
            mBack.setOnClickListener {
                onBack?.run()
            }
        } else {
            mBack.visibility = GONE
        }
        addView(mBack, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        mTitle = TextView(ctx)
        mTitle.text = title
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        mTitle.typeface = Typeface.DEFAULT_BOLD
        mTitle.setTextColor(AppColors.onSurface())
        mTitle.setSingleLine(true)
        mTitle.ellipsize = TextUtils.TruncateAt.END
        // v998: 标题栏标题居中显示
        mTitle.gravity = Gravity.CENTER
        val titleLp = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        titleLp.marginStart = (4 * d).toInt()
        addView(mTitle, titleLp)

        mActions = LinearLayout(ctx)
        mActions.orientation = HORIZONTAL
        mActions.gravity = Gravity.CENTER_VERTICAL
        addView(mActions, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun addAction(text: String?, onClick: Runnable?): ModernTopBar {
        try {
            val d = resources.displayMetrics.density
            val a = TextView(context)
            a.text = text
            a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            a.setTextColor(AppColors.primary())
            a.setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            a.isClickable = true
            applyRipple(a, AppColors.SHAPE_FULL_DP.toFloat())
            a.setOnClickListener {
                onClick?.run()
            }
            mActions.addView(a)
        } catch (ignored: Throwable) {}
        return this
    }

    fun setTitle(t: String?) {
        mTitle.text = t
    }

    companion object {
        /** v1033 M3: 给透明底的文字按钮挂全圆角涟漪边界 */
        private fun applyRipple(v: TextView, radiusDp: Float) {
            try {
                val d = v.resources.displayMetrics.density
                val mask = GradientDrawable()
                mask.shape = GradientDrawable.RECTANGLE
                mask.setCornerRadius(radiusDp * d)
                mask.setColor(0xFFFFFFFF.toInt())
                v.foreground = RippleDrawable(
                    ColorStateList.valueOf(AppColors.stateLayerPressed()),
                    null, mask)
            } catch (ignored: Throwable) {}
        }
    }
}