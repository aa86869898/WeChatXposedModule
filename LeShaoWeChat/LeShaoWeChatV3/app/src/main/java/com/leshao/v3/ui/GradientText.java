package com.leshao.v3.ui;

import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.view.animation.LinearInterpolator;
import android.animation.ValueAnimator;
import android.widget.TextView;

import java.util.WeakHashMap;

/**
 * 渐变文字工具（v1138）：把 TextView 的文字填充成模块统一主题色的循环流光。
 * 仅在 View attach 时启动动画，detach 时自动停止并释放，避免泄漏。
 */
public final class GradientText {

    private static final WeakHashMap<TextView, ValueAnimator> ANIMATORS = new WeakHashMap<>();

    private GradientText() {}

    /** 应用流光渐变文字（跟随模块主题色）。 */
    public static void apply(TextView tv) {
        int g1 = AppColors.gradientStart();
        int g2 = AppColors.gradientMid();
        int g3 = AppColors.gradientEnd();
        apply(tv, new int[]{g2, g3, g2, g1, g2});
    }

    public static void apply(final TextView tv, final int[] colors) {
        if (tv == null || colors == null || colors.length < 2) return;
        tv.setTextColor(0xFFFFFFFF);
        tv.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View v) {
                v.post(() -> start(tv, colors));
            }
            @Override public void onViewDetachedFromWindow(android.view.View v) {
                ValueAnimator a = ANIMATORS.remove(v);
                if (a != null) a.cancel();
            }
        });
    }

    private static void start(final TextView tv, final int[] colors) {
        CharSequence cs = tv.getText();
        float w = tv.getPaint().measureText(cs == null ? "" : cs.toString());
        if (w <= 1f) w = tv.getWidth();
        if (w <= 1f) return;

        final float span = w * 2f;
        final LinearGradient shader = new LinearGradient(
                0, 0, w, 0, colors, null, Shader.TileMode.MIRROR);
        tv.getPaint().setShader(shader);

        ValueAnimator old = ANIMATORS.get(tv);
        if (old != null) old.cancel();

        final Matrix m = new Matrix();
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(2200L);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            m.setTranslate(-span * t, 0f);
            shader.setLocalMatrix(m);
            tv.invalidate();
        });
        ANIMATORS.put(tv, anim);
        anim.start();
    }
}
