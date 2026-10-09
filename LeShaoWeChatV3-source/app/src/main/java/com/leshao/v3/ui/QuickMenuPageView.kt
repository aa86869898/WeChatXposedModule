package com.leshao.v3.ui

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 快捷菜单页（主页「快捷菜单」卡片入口，v3.0.165）。
 *
 * 占位页：后续版本在此放置高频快捷功能入口。
 */
class QuickMenuPageView private constructor() {

    companion object {
        @JvmStatic
        fun create(ctx: Context, parentAct: Activity): View {
            val d = ctx.resources.displayMetrics.density

            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.background = CandyUi.pageGradient()
            InsetsUtil.clipRounded(root)
            root.gravity = Gravity.CENTER
            root.setPadding((AppColors.SPACE_LG_DP * d).toInt(), (AppColors.SPACE_MD_DP * d).toInt(),
                    (AppColors.SPACE_LG_DP * d).toInt(), (AppColors.SPACE_MD_DP * d).toInt())

            val placeholder = TextView(ctx)
            placeholder.text = "快捷菜单建设中...\n后续版本将在此放置高频快捷功能入口"
            placeholder.setTextSize(15f)
            placeholder.setTextColor(AppColors.text2())
            placeholder.gravity = Gravity.CENTER
            root.addView(placeholder)

            return root
        }
    }
}