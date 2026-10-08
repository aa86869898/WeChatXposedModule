package com.leshao.v3.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.leshao.v3.ui.AppColors

/** 分组标题：粗体主标题 + 可选灰色副标题 */
class SectionHeader @JvmOverloads constructor(
    ctx: Context,
    title: String,
    sub: String? = null
) : LinearLayout(ctx) {

    private val mTitle: TextView
    private val mSub: TextView

    init {
        orientation = VERTICAL
        val d = resources.displayMetrics.density
        // 全局规范: 分区标题与上方卡片间距 13dp（卡片底部 4dp + 本标题顶部 9dp）
        setPadding((12 * d).toInt(), (9 * d).toInt(), (12 * d).toInt(), (13 * d).toInt())

        mTitle = TextView(ctx)
        mTitle.text = title
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, AppColors.TYPE_SECTION_TITLE)
        mTitle.typeface = Typeface.DEFAULT_BOLD
        mTitle.setTextColor(AppColors.textPrimary())
        mTitle.letterSpacing = 0.01f
        addView(mTitle)

        mSub = TextView(ctx)
        mSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        mSub.setTextColor(AppColors.textTertiary())
        mSub.gravity = Gravity.START
        if (sub != null && sub.isNotEmpty()) {
            mSub.text = sub
            mSub.setPadding(0, (2 * d).toInt(), 0, 0)
            addView(mSub)
        }
    }

    fun setSub(s: String?): SectionHeader {
        mSub.text = s
        return this
    }
}