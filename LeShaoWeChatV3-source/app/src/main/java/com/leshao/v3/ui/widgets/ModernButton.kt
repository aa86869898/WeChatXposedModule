package com.leshao.v3.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.leshao.v3.ui.AppColors
import com.leshao.v3.ui.CandyUi

/** 现代化按钮：primary(主) / ghost(描边) / danger(危险) / text(文字) 四型，统一圆角与按压态 */
class ModernButton(ctx: Context, text: String?, style: Int) : LinearLayout(ctx) {

    private val mLabel: TextView
    private var mAction: Runnable? = null
    private var mEnabled = true

    init {
        val d = resources.displayMetrics.density
        minimumHeight = (AppColors.BUTTON_HEIGHT_DP * d).toInt()
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)

        mLabel = TextView(ctx)
        mLabel.text = text
        mLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        mLabel.typeface = Typeface.DEFAULT_BOLD
        mLabel.gravity = Gravity.CENTER
        applyStyle(style)
        addView(mLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        setOnClickListener {
            if (!mEnabled || mAction == null) return@setOnClickListener
            try {
                mAction!!.run()
            } catch (ignored: Throwable) {}
        }
    }

    private fun applyStyle(style: Int) {
        when (style) {
            STYLE_PRIMARY -> {
                background = CandyUi.buttonBg(context)
                mLabel.setTextColor(AppColors.onGradient())
            }
            STYLE_DANGER -> {
                background = CandyUi.buttonDangerBg(context)
                mLabel.setTextColor(AppColors.whiteTextOnAccent())
            }
            STYLE_GHOST -> {
                background = CandyUi.buttonGhostBg(context)
                mLabel.setTextColor(AppColors.primary())
            }
            else -> {
                background = CandyUi.buttonTextBg(context)
                mLabel.setTextColor(AppColors.primary())
            }
        }
    }

    fun setText(t: String?): ModernButton {
        mLabel.text = t
        return this
    }

    fun onClick(r: Runnable?): ModernButton {
        mAction = r
        return this
    }

    override fun setEnabled(enabled: Boolean) {
        mEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
        isClickable = enabled
    }

    companion object {
        const val STYLE_PRIMARY = 0
        const val STYLE_GHOST = 1
        const val STYLE_DANGER = 2
        const val STYLE_TEXT = 3
    }
}