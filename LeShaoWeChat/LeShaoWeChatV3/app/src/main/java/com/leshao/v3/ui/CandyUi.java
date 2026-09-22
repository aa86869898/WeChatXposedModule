package com.leshao.v3.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.Switch;

/**
 * LeShaoWeChat 组件工厂 —— Material 3 规范。
 * 公开方法签名与旧版完全一致（全项目调用点零改动），内部实现切换为 M3：
 *  - 开关 52×32dp 轨道 + 16/24dp thumb 双态（M3 Switch 规范）
 *  - 卡片 filled/elevated 双型，12dp 圆角
 *  - 对话框 28dp extra-large 圆角
 *  - 按压涟漪用 M3 状态层（12% onSurface）
 */
public class CandyUi {

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** M3 Switch：轨道 52×32dp 圆角16，未选中 thumb 18dp(outline)，选中 thumb 24dp(onPrimary) */
    @SuppressWarnings("deprecation")
    public static Switch newSwitch(Context ctx) {
        Switch sw = new Switch(ctx);
        float d = ctx.getResources().getDisplayMetrics().density;
        int w = (int) (AppColors.SWITCH_WIDTH_DP * d);
        int h = (int) (AppColors.SWITCH_HEIGHT_DP * d);
        int trackR = (int) (AppColors.SWITCH_RADIUS_DP * d);
        int thumbOff = (int) (18 * d);
        int thumbOn = (int) (24 * d);
        sw.setMinimumWidth(w);
        sw.setMinimumHeight(h);
        sw.setPadding(0, 0, 0, 0);
        sw.setTextOff("");
        sw.setTextOn("");
        sw.setShowText(false);
        try {
            // v967 关键修复: 自定义 track/thumb 为 GradientDrawable 时无 intrinsic size,
            // Switch 测量不到尺寸 → 开关整体不可见(人声增强/AI助手等所有 SettingRow 开关丢失)。
            // 必须对每个 drawable 调用 setSize() 显式提供 intrinsic 尺寸。
            StateListDrawable track = new StateListDrawable();
            GradientDrawable off = new GradientDrawable();
            off.setShape(GradientDrawable.RECTANGLE);
            off.setCornerRadius(trackR);
            off.setColor(AppColors.surfaceContainerHighest());
            off.setStroke(dp(ctx, 2), AppColors.outline());
            off.setSize(w, h);
            track.addState(new int[]{-android.R.attr.state_checked}, off);
            GradientDrawable on = new GradientDrawable();
            on.setShape(GradientDrawable.RECTANGLE);
            on.setCornerRadius(trackR);
            on.setColor(AppColors.primary());
            on.setSize(w, h);
            track.addState(new int[]{android.R.attr.state_checked}, on);
            if (android.os.Build.VERSION.SDK_INT >= 16) sw.setTrackDrawable(track);

            // thumb：关=18dp outline 圆点 / 开=24dp onPrimary 圆点
            StateListDrawable thumb = new StateListDrawable();
            GradientDrawable tOff = new GradientDrawable();
            tOff.setShape(GradientDrawable.OVAL);
            tOff.setColor(AppColors.outline());
            tOff.setSize(thumbOff, thumbOff);
            thumb.addState(new int[]{-android.R.attr.state_checked}, tOff);
            GradientDrawable tOn = new GradientDrawable();
            tOn.setShape(GradientDrawable.OVAL);
            tOn.setColor(AppColors.onPrimary());
            tOn.setSize(thumbOn, thumbOn);
            thumb.addState(new int[]{android.R.attr.state_checked}, tOn);
            if (android.os.Build.VERSION.SDK_INT >= 16) sw.setThumbDrawable(thumb);
            // v968 关键修复: 清除 Switch 默认背景(其自带 padding 会把 52dp 轨道撑宽导致
            // 开关显示变形/thumb 行程错位), 并显式固定最小宽度与 thumb 行程为整轨。
            sw.setBackground(null);
            sw.setSwitchMinWidth(w);
        } catch (Throwable ignored) {}
        return sw;
    }

    /** 页面根背景：M3 surface（纯色，Material 3 不用渐变做大背景） */
    public static GradientDrawable pageGradient() {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(AppColors.surface());
        return gd;
    }

    /** M3 filled 卡片：surfaceContainerLow 底 + 12dp 圆角 */
    public static GradientDrawable cardBg(Context ctx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(ctx, AppColors.SHAPE_MD_DP));
        gd.setColor(AppColors.surfaceContainerLow());
        return gd;
    }

    /** M3 elevated 卡片：surfaceContainerLowest 底 + 12dp 圆角 + 细描边（暗色下区分层级） */
    public static GradientDrawable cardElevatedBg(Context ctx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(ctx, AppColors.SHAPE_MD_DP));
        gd.setColor(AppColors.surfaceContainerLowest());
        if (AppColors.isDarkMode()) {
            gd.setStroke(dp(ctx, 0.5f), AppColors.outlineVariant());
        }
        return gd;
    }

    /** M3 filter chip：选中 primary 底 / 未选中 surfaceContainerLow + outline 描边 */
    public static GradientDrawable pillBg(boolean selected, Context ctx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(ctx, AppColors.SHAPE_SM_DP));
        if (selected) {
            gd.setColor(AppColors.primary());
        } else {
            gd.setColor(AppColors.surfaceContainerLow());
            gd.setStroke(dp(ctx, 1), AppColors.outline());
        }
        return gd;
    }

    /** M3 标签底：primaryContainer（旧糖果粉位） */
    public static GradientDrawable tagPinkBg(Context ctx) {
        return roundedRect(AppColors.primaryContainer(), dp(ctx, AppColors.SHAPE_XS_DP));
    }

    /** M3 标签底：tertiaryContainer（旧柠黄位） */
    public static GradientDrawable tagYellowBg(Context ctx) {
        return roundedRect(AppColors.tertiaryContainer(), dp(ctx, AppColors.SHAPE_XS_DP));
    }

    /** M3 filled text field：surfaceContainerHighest 底 + 12dp 圆角 + outline 描边 */
    public static GradientDrawable inputBg(Context ctx) {
        GradientDrawable gd = roundedRect(AppColors.surfaceContainerHighest(), dp(ctx, AppColors.SHAPE_MD_DP));
        gd.setStroke(dp(ctx, 1), AppColors.outlineVariant());
        return gd;
    }

    /** M3 对话框：surfaceContainerHigh 底 + 28dp extra-large 圆角 */
    public static GradientDrawable dialogBg(Context ctx) {
        return roundedRect(AppColors.surfaceContainerHigh(), dp(ctx, AppColors.DIALOG_RADIUS_DP));
    }

    /** M3 filled button：primary 底 + 20dp 全圆角 + 按压 primaryContainer 态 */
    public static StateListDrawable buttonBg(Context ctx) {
        StateListDrawable sd = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.RECTANGLE);
        pressed.setCornerRadius(dp(ctx, AppColors.SHAPE_FULL_DP));
        pressed.setColor(AppColors.primaryDark());
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.RECTANGLE);
        normal.setCornerRadius(dp(ctx, AppColors.SHAPE_FULL_DP));
        normal.setColor(AppColors.primary());
        sd.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sd.addState(new int[]{}, normal);
        return sd;
    }

    /** M3 outlined button：透明底 + outline 描边 + 全圆角 */
    public static StateListDrawable buttonGhostBg(Context ctx) {
        StateListDrawable sd = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.RECTANGLE);
        pressed.setCornerRadius(dp(ctx, AppColors.SHAPE_FULL_DP));
        pressed.setColor(AppColors.stateLayerPressed());
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.RECTANGLE);
        normal.setCornerRadius(dp(ctx, AppColors.SHAPE_FULL_DP));
        normal.setColor(0x00000000);
        normal.setStroke(dp(ctx, 1), AppColors.outline());
        sd.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sd.addState(new int[]{}, normal);
        return sd;
    }

    /** M3 filled tonal button（危险）：error 底 + 全圆角 */
    public static StateListDrawable buttonDangerBg(Context ctx) {
        StateListDrawable sd = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.RECTANGLE);
        pressed.setCornerRadius(dp(ctx, AppColors.SHAPE_FULL_DP));
        pressed.setColor(0xFF8C0F16);
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.RECTANGLE);
        normal.setCornerRadius(dp(ctx, AppColors.SHAPE_FULL_DP));
        normal.setColor(AppColors.error());
        sd.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sd.addState(new int[]{}, normal);
        return sd;
    }

    /** M3 行按压状态层：12% onSurface 涟漪 + 12dp 圆角裁剪 */
    public static Drawable rowPressBg(Context ctx) {
        try {
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.RECTANGLE);
            mask.setCornerRadius(dp(ctx, AppColors.SHAPE_MD_DP));
            mask.setColor(AppColors.surfaceContainerLow());
            return new RippleDrawable(
                android.content.res.ColorStateList.valueOf(AppColors.stateLayerPressed()),
                null, mask);
        } catch (Throwable t) {
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setCornerRadius(dp(ctx, AppColors.SHAPE_MD_DP));
            gd.setColor(AppColors.stateLayerPressed());
            return gd;
        }
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
