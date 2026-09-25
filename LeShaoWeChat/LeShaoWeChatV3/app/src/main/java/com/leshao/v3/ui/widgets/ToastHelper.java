package com.leshao.v3.ui.widgets;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.ui.AppColors;

/** 卡片式 Toast（成功/失败/普通），自动消失，主线程安全 */
public class ToastHelper {

    private static final int COLOR_SUCCESS = 0xFF8B5CF6;
    private static final int COLOR_ERROR = 0xFFFA5151;

    private ToastHelper() {}

    public static void show(Context ctx, String msg) {
        show(ctx, msg, 0);
    }

    public static void success(Context ctx, String msg) {
        show(ctx, "✅ " + msg, COLOR_SUCCESS);
    }

    public static void error(Context ctx, String msg) {
        show(ctx, "❌ " + msg, COLOR_ERROR);
    }

    public static void show(Context ctx, String msg, int accent) {
        if (ctx == null || msg == null) return;
        try {
            Runnable r = () -> makeToast(ctx, msg, accent);
            if (Looper.myLooper() == Looper.getMainLooper()) {
                r.run();
            } else {
                new Handler(Looper.getMainLooper()).post(r);
            }
        } catch (Throwable ignored) {}
    }

    private static void makeToast(Context ctx, String msg, int accent) {
        try {
            float d = ctx.getResources().getDisplayMetrics().density;
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            int pad = (int) (14 * d);
            card.setPadding(pad, pad, pad, pad);

            TextView tv = new TextView(ctx);
            tv.setText(msg);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setTextColor(AppColors.inverseOnSurface());
            tv.setMaxLines(3);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(tv);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(AppColors.SHAPE_XS_DP * d);
            // M3 snackbar：inverseSurface 底 + inverseOnSurface 字
            bg.setColor(accent == 0 ? AppColors.inverseSurface() : accent);
            card.setBackground(bg);

            android.widget.PopupWindow pw = new android.widget.PopupWindow(card,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            pw.setFocusable(false);
            pw.setOutsideTouchable(true);
            android.app.Activity act = resolveActivity(ctx);
            if (act == null || act.isFinishing()) return;
            android.view.View root = act.getWindow().getDecorView();
            pw.showAtLocation(root, Gravity.CENTER, 0, (int) (-40 * d));
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try { pw.dismiss(); } catch (Throwable ignored) {}
            }, 2200);
        } catch (Throwable ignored) {}
    }

    private static android.app.Activity resolveActivity(Context ctx) {
        try {
            if (ctx instanceof android.app.Activity) return (android.app.Activity) ctx;
            if (ctx instanceof android.content.ContextWrapper) {
                return resolveActivity(((android.content.ContextWrapper) ctx).getBaseContext());
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
