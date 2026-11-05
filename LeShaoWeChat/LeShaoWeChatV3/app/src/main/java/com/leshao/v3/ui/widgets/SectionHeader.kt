package com.leshao.v3.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.leshao.v3.ui.AppColors

/** 分组标题 · v3.0.457 玻璃风：主色竖条 + 粗体主标题 + 可选灰副标题 */
class SectionHeader @JvmOverloads constructor(
    ctx: Context,
    title: String,
    sub: String? = null
) : LinearLayout(ctx) {

    private val mSub: TextView
    private val mSubHolder: LinearLayout

    init {
        orientation = VERTICAL
        val d = resources.displayMetrics.density
        // 全局规范: 分区标题与上方卡片间距 13dp（卡片底部 4dp + 本标题顶部 9dp）
        setPadding((12 * d).toInt(), (9 * d).toInt(), (12 * d).toInt(), (13 * d).toInt())

        // 主色竖条 + 标题横排
        val titleRow = LinearLayout(ctx)
        titleRow.orientation = HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        addView(titleRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val accent = View(ctx)
        val accentSize = (3 * d).toInt()
        val accentLp = LayoutParams(accentSize, (16 * d).toInt())
        accentLp.marginEnd = (6 * d).toInt()
        val accentBg = GradientDrawable()
        accentBg.shape = GradientDrawable.RECTANGLE
        accentBg.cornerRadius = accentSize / 2f
        accentBg.setColor(AppColors.primary())
        accent.background = accentBg
        titleRow.addView(accent, accentLp)

        val mTitle = TextView(ctx)
        mTitle.text = title
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, AppColors.TYPE_SECTION_TITLE)
        mTitle.typeface = Typeface.DEFAULT_BOLD
        mTitle.setTextColor(AppColors.textPrimary())
        mTitle.letterSpacing = 0.01f
        titleRow.addView(mTitle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        mSubHolder = LinearLayout(ctx)
        mSubHolder.orientation = HORIZONTAL
        addView(mSubHolder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        mSub = TextView(ctx)
        mSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        mSub.setTextColor(AppColors.textTertiary())
        mSub.gravity = Gravity.START
        if (sub != null && sub.isNotEmpty()) {
            mSub.text = sub
            mSub.setPadding((9 * d).toInt(), (2 * d).toInt(), 0, 0)
            mSubHolder.addView(mSub)
        }
    }

    fun setSub(s: String?): SectionHeader {
        mSub.text = s
        return this
    }
}