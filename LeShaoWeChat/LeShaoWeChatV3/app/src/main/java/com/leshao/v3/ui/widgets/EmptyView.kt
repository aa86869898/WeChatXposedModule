package com.leshao.v3.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.leshao.v3.ui.AppColors

/** 空状态占位：大 emoji + 提示文案 + 可选副文案 */
class EmptyView(ctx: Context, icon: String?, msg: String?) : LinearLayout(ctx) {

    private val mIcon: TextView
    private val mMsg: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val d = resources.displayMetrics.density
        setPadding((24 * d).toInt(), (48 * d).toInt(), (24 * d).toInt(), (48 * d).toInt())

        mIcon = TextView(ctx)
        mIcon.text = icon ?: "📭"
        mIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 42f)
        mIcon.gravity = Gravity.CENTER
        addView(mIcon)

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