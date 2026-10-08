package com.leshao.v3.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

import com.leshao.v3.ui.widgets.M3Page

/** 新增功能页共用的卡片/开关/分隔线构建器（沿用手工页的糖果风格）。 */
class PageKit private constructor() {

    companion object {

        @JvmStatic
        fun pageRoot(ctx: Context): LinearLayout {
            val d = ctx.resources.displayMetrics.density
            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.background = CandyUi.pageGradient()
            InsetsUtil.clipRounded(root)
            // 全局规范: 页面左右边距 12dp，底部留白 12dp，避免页面根圆角区裁切底部按钮/圆角描边
            root.setPadding((AppColors.SPACE_MD_DP * d).toInt(), (6 * d).toInt(),
                    (AppColors.SPACE_MD_DP * d).toInt(), (12 * d).toInt())
            return root
        }

        @JvmStatic
        fun makeCard(ctx: Context, d: Float): LinearLayout {
            val card = LinearLayout(ctx)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(0, 0, 0, 0)
            card.background = CandyUi.cardBg(ctx)
            InsetsUtil.clipRounded(card)
            return card
        }

        @JvmStatic
        fun divider(ctx: Context): View {
            return M3Page.divider(ctx)
        }

        @JvmStatic
        fun addDivider(ctx: Context, root: LinearLayout) {
            root.addView(divider(ctx))
        }

        @JvmStatic
        fun switchRow(ctx: Context, d: Float, title: String, desc: String?,
                      checked: Boolean,
                      listener: CompoundButton.OnCheckedChangeListener?,
                      configListener: View.OnClickListener?): LinearLayout {
            return switchRow(ctx, d, title, desc, checked, listener, configListener, null)
        }

        @JvmStatic
        fun switchRow(ctx: Context, d: Float, title: String, desc: String?,
                      checked: Boolean,
                      listener: CompoundButton.OnCheckedChangeListener?,
                      configListener: View.OnClickListener?,
                      swHolder: Array<Switch?>?): LinearLayout {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            // 全局规范: 行触控区域不低于 48dp
            row.minimumHeight = (48 * d).toInt()
            row.setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            row.background = CandyUi.cardBg(ctx)
            InsetsUtil.clipRounded(row)

            val textCol = LinearLayout(ctx)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f)

            val tv = TextView(ctx)
            tv.text = title
            tv.setTextSize(16f)
            tv.setTextColor(AppColors.text1())
            tv.setTypeface(null, Typeface.BOLD)
            textCol.addView(tv)

            if (desc != null && desc.isNotEmpty()) {
                val dv = TextView(ctx)
                dv.text = desc
                dv.setTextSize(12f)
                dv.setTextColor(AppColors.text2())
                dv.setPadding(0, (3 * d).toInt(), 0, 0)
                textCol.addView(dv)
            }
            row.addView(textCol)

            if (configListener != null) {
                val btn = TextView(ctx)
                btn.text = "[设置]"
                btn.setTextSize(12f)
                btn.setTextColor(AppColors.accent())
                btn.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
                btn.paintFlags = btn.paintFlags or Paint.UNDERLINE_TEXT_FLAG
                CandyUi.ripple(btn, AppColors.SHAPE_FULL_DP.toFloat())
                btn.setOnClickListener(configListener)
                row.addView(btn)
            }

            val sw = CandyUi.newSwitch(ctx)
            sw.isChecked = checked
            sw.setOnCheckedChangeListener(listener)
            row.addView(sw)
            if (swHolder != null && swHolder.isNotEmpty()) swHolder[0] = sw
            return row
        }

        @JvmStatic
        fun sectionLabel(ctx: Context, text: String): TextView {
            val d = ctx.resources.displayMetrics.density
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextSize(14f)
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.setTextColor(AppColors.text2())
            tv.setPadding(0, 0, 0, (8 * d).toInt())
            return tv
        }

        @JvmStatic
        fun actionButton(ctx: Context, text: String, l: View.OnClickListener?): TextView {
            val d = ctx.resources.displayMetrics.density
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextSize(15f)
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.gravity = Gravity.CENTER
            tv.minimumHeight = (48 * d).toInt()
            tv.setTextColor(AppColors.text1())
            tv.setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            tv.background = CandyUi.rowBg(ctx)
            InsetsUtil.clipRounded(tv)
            CandyUi.ripple(tv, AppColors.SHAPE_FULL_DP.toFloat())
            if (l != null) tv.setOnClickListener(l)
            return tv
        }

        @JvmStatic
        fun bodyText(ctx: Context, text: String): TextView {
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextSize(13f)
            tv.setTextColor(AppColors.text2())
            val d = ctx.resources.displayMetrics.density
            tv.setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
            return tv
        }
    }
}