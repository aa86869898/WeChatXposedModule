package com.leshao.v3.ui;

import android.widget.TextView;

/**
 * 渐变文字工具（v1138）：把 TextView 的文字填充成模块统一主题色的循环流光。
 * 仅在 View attach 时启动动画，detach 时自动停止并释放，避免泄漏。
 *
 * <p>去渐变：不再使用流光 Shader 动画，文字直接以主题主色纯色渲染。</p>
 */
public final class GradientText {

    private GradientText() {}

    /** 应用纯色糖果粉文字（跟随模块主题色）。 */
    public static void apply(TextView tv) {
        if (tv == null) return;
        tv.setTextColor(AppColors.primary());
    }

    public static void apply(final TextView tv, final int[] colors) {
        if (tv == null || colors == null || colors.length == 0) return;
        tv.setTextColor(colors[0]);
    }
}