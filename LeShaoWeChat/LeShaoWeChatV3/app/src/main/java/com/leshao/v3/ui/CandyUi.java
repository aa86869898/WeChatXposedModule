package com.leshao.v3.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.widget.Switch;

public class CandyUi {

    /** 创建平板风格的 Switch（48×26dp，圆角13dp，无文字） */
    @SuppressWarnings("deprecation")
    public static Switch newSwitch(Context ctx) {
        Switch sw = new Switch(ctx);
        float d = ctx.getResources().getDisplayMetrics().density;
        int w = (int)(AppColors.SWITCH_WIDTH_DP * d);
        int h = (int)(AppColors.SWITCH_HEIGHT_DP * d);
        int r = (int)(AppColors.SWITCH_RADIUS_DP * d);
        sw.setMinimumWidth(w);
        sw.setMinimumHeight(h);
        sw.setPadding(0, 0, 0, 0);
        sw.setTextOff("");
        sw.setTextOn("");
        sw.setShowText(false);
        try {
            android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
            GradientDrawable off = new GradientDrawable();
            off.setShape(GradientDrawable.RECTANGLE);
            off.setCornerRadius(r);
            off.setColor(AppColors.SWITCH_OFF);
            track.addState(new int[]{-android.R.attr.state_checked}, off);
            GradientDrawable on = new GradientDrawable();
            on.setShape(GradientDrawable.RECTANGLE);
            on.setCornerRadius(r);
            on.setColor(AppColors.SWITCH_ON);
            track.addState(new int[]{android.R.attr.state_checked}, on);
            if (android.os.Build.VERSION.SDK_INT >= 16)
                sw.setTrackDrawable(track);
        } catch (Throwable ignored) {}
        return sw;
    }

    /** 页面根布局渐变背景 drawable */
    public static GradientDrawable pageGradient() {
        GradientDrawable gd = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{AppColors.BG_GRADIENT_START, AppColors.BG_GRADIENT_END});
        gd.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return gd;
    }

    /** 列表卡片背景 drawable (圆角8dp，半透明白底无阴影) */
    public static GradientDrawable cardBg(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int r = (int)(8 * d);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(r);
        gd.setColor(AppColors.CARD_BG);
        return gd;
    }

    /** 胶囊按钮背景 (选中态/未选中态) */
    public static GradientDrawable pillBg(boolean selected, Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int r = (int)(20 * d);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(r);
        if (selected) {
            gd.setColor(AppColors.ACCENT);
        } else {
            gd.setColor(0x00000000);
        gd.setStroke((int)(1.5f * d), AppColors.candyPink());
        }
        return gd;
    }

    /** 小标签背景 (糖果粉) */
    public static GradientDrawable tagPinkBg(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return roundedRect(AppColors.CANDY_PINK, (int)(4 * d));
    }

    /** 小标签背景 (浅柠黄) */
    public static GradientDrawable tagYellowBg(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return roundedRect(AppColors.CANDY_YELLOW, (int)(4 * d));
    }

    /** 搜索框/输入框背景 (带描边) */
    public static GradientDrawable inputBg(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int r = (int)(8 * d);
        GradientDrawable gd = roundedRect(AppColors.INPUT_BG, r);
        gd.setStroke((int)(1 * d), AppColors.DIVIDER);
        return gd;
    }

    /** 弹窗背景 (圆角16dp) */
    public static GradientDrawable dialogBg(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int r = (int)(AppColors.DIALOG_RADIUS_DP * d);
        return roundedRect(AppColors.CARD_BG, r);
    }

    private static GradientDrawable roundedRect(int color, int radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(radius);
        gd.setColor(color);
        return gd;
    }

    private CandyUi() {}
}
