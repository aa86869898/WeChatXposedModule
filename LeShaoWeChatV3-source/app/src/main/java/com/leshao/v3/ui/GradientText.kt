package com.leshao.v3.ui

import android.widget.TextView

/**
 * 渐变文字工具（v1138）：把 TextView 的文字填充成模块统一主题色的循环流光。
 * 仅在 View attach 时启动动画，detach 时自动停止并释放，避免泄漏。
 *
 * 去渐变：不再使用流光 Shader 动画，文字直接以主题主色纯色渲染。
 */
object GradientText {

    /** 应用纯色糖果粉文字（跟随模块主题色）。 */
    @JvmStatic
    fun apply(tv: TextView?) {
        if (tv == null) return
        tv.setTextColor(AppColors.primary())
    }

    @JvmStatic
    fun apply(tv: TextView?, colors: IntArray?) {
        if (tv == null || colors == null || colors.isEmpty()) return
        tv.setTextColor(colors[0])
    }
}