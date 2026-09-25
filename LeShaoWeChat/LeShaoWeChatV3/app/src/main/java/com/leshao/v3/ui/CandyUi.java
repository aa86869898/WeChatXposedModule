package com.leshao.v3.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
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

    /**
     * M3 开关：轨道 52×32dp（开=深蓝/关=灰），thumb 外圈圆环包裹本体。
     *
     * <p>v1056 关键修复：改用 framework {@link android.widget.Switch}。此前用
     * {@code SwitchMaterial}（appcompat）时，其构造会读取 appcompat 属性 ID，而模块资源未注入
     * 宿主(微信)资源表，属性 ID 与宿主资源碰撞 → 解析到 {@code res/raw/chatfrom_voice_playing_f3.svg}
     * 并抛 {@code Resources$NotFoundException}，导致所有开关创建失败、个性化配置面板整体构建中断。</p>
     */
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
            // 自绘 track/thumb 精确保留原配色与尺寸；清空框架 tint 避免覆盖自定义绘制(API 21+)。
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                sw.setTrackTintList(null);
                sw.setThumbTintList(null);
            }
            // v967 关键修复: 自定义 track/thumb 为 GradientDrawable 时无 intrinsic size,
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
            on.setColor(AppColors.switchColor());
            on.setSize(w, h);
            track.addState(new int[]{android.R.attr.state_checked}, on);
            sw.setTrackDrawable(track);

            // thumb 外圈圆环：关=空心圆环；开=白色本体 + 圆环。
            StateListDrawable thumb = new StateListDrawable();
            GradientDrawable tOff = new GradientDrawable();
            tOff.setShape(GradientDrawable.OVAL);
            tOff.setColor(0x00000000);
            tOff.setStroke(dp(ctx, 2), AppColors.outline());
            tOff.setSize(thumbOff, thumbOff);
            thumb.addState(new int[]{-android.R.attr.state_checked}, tOff);
            GradientDrawable tOn = new GradientDrawable();
            tOn.setShape(GradientDrawable.OVAL);
            tOn.setColor(AppColors.onColor(AppColors.switchColor()));
            tOn.setStroke(dp(ctx, 2), AppColors.outline());
            tOn.setSize(thumbOn, thumbOn);
            thumb.addState(new int[]{android.R.attr.state_checked}, tOn);
            sw.setThumbDrawable(thumb);

            sw.setBackground(null);
            sw.setSwitchMinWidth(w);
        } catch (Throwable ignored) {}
        return sw;
    }

    /**
     * 页面根背景：M3 surface 纯色 + 28dp 圆角浮层。
     *
     * <p>v987 统一透明化：全屏页面统一改为圆角浮层，圆角外区域由透明窗口露出宿主，
     * 不再用直角实底填满整屏（避免在状态栏/安全区露出白色实底间隔）。</p>
     */
    public static GradientDrawable pageGradient() {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        float d = Resources.getSystem().getDisplayMetrics().density;
        gd.setCornerRadius(AppColors.DIALOG_RADIUS_DP * d);
        gd.setColor(AppColors.windowBg());
        gd.setStroke(Math.max(1, (int) (1.0f * d + 0.5f)), AppColors.outlineVariant());
        return gd;
    }

    /**
     * v998: 给浮层容器附加轻微阴影(硬件层 elevation)，与 {@link #pageGradient()} 的描边配合，
     * 让居中浮层与宿主画面之间产生柔和层次。
     */
    public static void elevate(View v) {
        if (v == null) return;
        try {
            float d = v.getResources().getDisplayMetrics().density;
            v.setElevation(8f * d);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                v.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            }
        } catch (Throwable ignored) {
        }
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

    /** M3 filled button：primary 底 + 20dp 全圆角 + 状态层涟漪 */
    public static Drawable buttonBg(Context ctx) {
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
        return rippleWrap(ctx, sd, AppColors.stateLayerOnPrimary());
    }

    /** M3 outlined button：透明底 + outline 描边 + 全圆角 + 状态层涟漪 */
    public static Drawable buttonGhostBg(Context ctx) {
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
        return rippleWrap(ctx, sd, AppColors.stateLayerPressed());
    }

    /** M3 filled tonal button（危险）：error 底 + 全圆角 + 状态层涟漪 */
    public static Drawable buttonDangerBg(Context ctx) {
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
        return rippleWrap(ctx, sd, 0x1FFFFFFF);
    }

    /** M3 text button：透明底 + 全圆角涟漪 */
    public static Drawable buttonTextBg(Context ctx) {
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.RECTANGLE);
        normal.setCornerRadius(dp(ctx, AppColors.SHAPE_FULL_DP));
        normal.setColor(0x00000000);
        return rippleWrap(ctx, normal, AppColors.stateLayerPressed());
    }

    /** 为任意已 setClickable 的 View 附加 M3 状态层涟漪 foreground（全圆角边界）。 */
    public static void ripple(View v, float radiusDp) {
        if (v == null) return;
        try {
            float d = v.getResources().getDisplayMetrics().density;
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.RECTANGLE);
            mask.setCornerRadius(radiusDp * d);
            mask.setColor(0xFFFFFFFF);
            v.setForeground(new RippleDrawable(
                    android.content.res.ColorStateList.valueOf(AppColors.stateLayerPressed()), null, mask));
        } catch (Throwable ignored) {}
    }

    /** 将 StateListDrawable 包成 M3 涟漪(显式全圆角遮罩, 保证透明底按钮也有边界涟漪) */
    private static Drawable rippleWrap(Context ctx, Drawable content, int rippleColor) {
        try {
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.RECTANGLE);
            mask.setCornerRadius(dp(ctx, AppColors.SHAPE_FULL_DP));
            mask.setColor(0xFFFFFFFF);
            return new RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor), content, mask);
        } catch (Throwable t) {
            return content;
        }
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
