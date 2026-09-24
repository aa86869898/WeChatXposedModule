package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.ui.widgets.M3Page;

/**
 * v1015: M3 模块配色页重新设计 ——
 *  · 12 套预设配色（替换旧 5 套）
 *  · 自定义调色板（HSV 取色器自选 seed）
 *  · 动态取色（Material You，Android 12+ 系统强调色）
 *  · 单元素自定义：标题栏 / 开关 / 窗口背景 颜色
 * 选择后立即生效，并随微信深色/浅色模式自动适配。
 */
public class M3ColorPageView {

    public static View create(Context ctx, Activity parentAct) {
        AppColors.refresh();

        LinearLayout root = M3Page.root(ctx);

        root.addView(M3Page.section(ctx, "配色方案",
                "12 套预设 + 自定义调色板 + 动态取色，选择后立即应用并随深色模式适配"));

        LinearLayout card = M3Page.card(ctx);
        int current = AppColors.getPalette();
        for (int i = 0; i < AppColors.paletteCount(); i++) {
            if (i > 0) card.addView(M3Page.divider(ctx));
            card.addView(makePaletteRow(ctx, parentAct, AppColors.paletteName(i),
                    AppColors.paletteSeed(i), current == i, i));
        }
        card.addView(M3Page.divider(ctx));
        card.addView(makePaletteRow(ctx, parentAct, "自定义调色板",
                AppColors.paletteSeed(AppColors.PALETTE_CUSTOM),
                current == AppColors.PALETTE_CUSTOM, AppColors.PALETTE_CUSTOM));
        card.addView(M3Page.divider(ctx));
        card.addView(makeDynamicRow(ctx, parentAct, current == AppColors.PALETTE_DYNAMIC));
        root.addView(card);

        root.addView(M3Page.section(ctx, "自定义颜色",
                "单独覆盖标题栏 / 开关 / 窗口背景，不影响整体配色"));
        LinearLayout ovCard = M3Page.card(ctx);
        ovCard.addView(makeOverrideRow(ctx, parentAct, "标题栏颜色", AppColors.titleBar(),
                () -> ColorPickerDialog.show(ctx, "标题栏颜色", AppColors.titleBar(), c -> {
                    AppColors.setTitleBarColor(c);
                    M3Page.toastSuccess(ctx, "已更新标题栏颜色");
                    SubPageActivity.refreshCurrent(parentAct);
                })));
        ovCard.addView(M3Page.divider(ctx));
        ovCard.addView(makeOverrideRow(ctx, parentAct, "开关按钮颜色", AppColors.switchColor(),
                () -> ColorPickerDialog.show(ctx, "开关按钮颜色", AppColors.switchColor(), c -> {
                    AppColors.setSwitchColor(c);
                    M3Page.toastSuccess(ctx, "已更新开关颜色");
                    SubPageActivity.refreshCurrent(parentAct);
                })));
        ovCard.addView(M3Page.divider(ctx));
        ovCard.addView(makeOverrideRow(ctx, parentAct, "窗口背景颜色", AppColors.windowBg(),
                () -> ColorPickerDialog.show(ctx, "窗口背景颜色", AppColors.windowBg(), c -> {
                    AppColors.setWindowBgColor(c);
                    M3Page.toastSuccess(ctx, "已更新窗口背景");
                    SubPageActivity.refreshCurrent(parentAct);
                })));
        if (AppColors.hasTitleBarOverride() || AppColors.hasSwitchOverride()
                || AppColors.hasWindowBgOverride()) {
            ovCard.addView(M3Page.divider(ctx));
            ovCard.addView(makeResetRow(ctx, parentAct));
        }
        root.addView(ovCard);

        root.addView(M3Page.section(ctx, "当前方案预览"));
        root.addView(makePreview(ctx));

        root.addView(M3Page.section(ctx, "说明"));
        LinearLayout noteCard = M3Page.card(ctx);
        noteCard.addView(M3Page.infoRow(ctx, "生效方式", "实时 · 无需重启"));
        noteCard.addView(M3Page.divider(ctx));
        noteCard.addView(M3Page.infoRow(ctx, "明暗适配", "跟随微信深色模式"));
        noteCard.addView(M3Page.divider(ctx));
        noteCard.addView(M3Page.infoRow(ctx, "动态取色",
                AppColors.dynamicColorAvailable() ? "可用 (Android 12+)" : "当前系统不支持"));
        root.addView(noteCard);

        return M3Page.scroll(ctx, root);
    }

    private static View makePaletteRow(final Context ctx, final Activity parentAct,
                                       String name, int seed, boolean selected, final int id) {
        LinearLayout row = baseRow(ctx);

        LinearLayout swatches = new LinearLayout(ctx);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        swatches.setGravity(Gravity.CENTER_VERTICAL);
        swatches.addView(dot(ctx, seed, 22));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                (int) (6 * ctx.getResources().getDisplayMetrics().density), (int) (22 * ctx.getResources().getDisplayMetrics().density));
        View spacer = new View(ctx);
        spacer.setLayoutParams(gap);
        swatches.addView(spacer);
        swatches.addView(dot(ctx, lighten(seed), 22));
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(-2, -2);
        swLp.setMarginEnd((int) (14 * ctx.getResources().getDisplayMetrics().density));
        swatches.setLayoutParams(swLp);
        row.addView(swatches);

        row.addView(nameView(ctx, name, selected));
        row.addView(checkMark(ctx, selected));

        row.setOnClickListener(v -> {
            if (id == AppColors.PALETTE_CUSTOM) {
                ColorPickerDialog.show(ctx, "自定义调色板", AppColors.currentSeed(), c -> {
                    AppColors.setCustomPalette(c);
                    M3Page.toastSuccess(ctx, "已应用自定义配色");
                    SubPageActivity.refreshCurrent(parentAct);
                });
            } else {
                AppColors.setPalette(id);
                M3Page.toastSuccess(ctx, "已应用配色：" + AppColors.paletteName(id));
                SubPageActivity.refreshCurrent(parentAct);
            }
        });
        return row;
    }

    private static View makeDynamicRow(final Context ctx, final Activity parentAct, boolean selected) {
        LinearLayout row = baseRow(ctx);
        LinearLayout swatches = new LinearLayout(ctx);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        swatches.setGravity(Gravity.CENTER_VERTICAL);
        int dynSeed = AppColors.paletteSeed(AppColors.PALETTE_DYNAMIC);
        swatches.addView(dot(ctx, dynSeed, 22));
        swatches.addView(dot(ctx, AppColors.secondaryContainer(), 22));
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(-2, -2);
        swLp.setMarginEnd((int) (14 * ctx.getResources().getDisplayMetrics().density));
        swatches.setLayoutParams(swLp);
        row.addView(swatches);

        String label = "动态取色" + (AppColors.dynamicColorAvailable() ? "（Material You）" : "（不支持）");
        row.addView(nameView(ctx, label, selected));
        row.addView(checkMark(ctx, selected));

        row.setOnClickListener(v -> {
            if (!AppColors.dynamicColorAvailable()) {
                M3Page.toast(ctx, "动态取色需要 Android 12 及以上系统");
                return;
            }
            AppColors.setPalette(AppColors.PALETTE_DYNAMIC);
            M3Page.toastSuccess(ctx, "已应用动态取色");
            SubPageActivity.refreshCurrent(parentAct);
        });
        return row;
    }

    private static View makeOverrideRow(final Context ctx, final Activity parentAct,
                                        String name, int color, final Runnable onPick) {
        LinearLayout row = baseRow(ctx);
        row.addView(dot(ctx, color, 24));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                (int) (14 * ctx.getResources().getDisplayMetrics().density), 0);
        View spacer = new View(ctx);
        spacer.setLayoutParams(sp);
        row.addView(spacer);
        row.addView(nameView(ctx, name, false));
        TextView arrow = new TextView(ctx);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(AppColors.arrow());
        row.addView(arrow);
        row.setOnClickListener(v -> onPick.run());
        return row;
    }

    private static View makeResetRow(final Context ctx, final Activity parentAct) {
        LinearLayout row = baseRow(ctx);
        row.addView(nameView(ctx, "恢复默认颜色", false));
        row.addView(checkMark(ctx, false));
        row.setOnClickListener(v -> {
            AppColors.clearOverrides();
            M3Page.toastSuccess(ctx, "已恢复默认颜色");
            SubPageActivity.refreshCurrent(parentAct);
        });
        return row;
    }

    private static LinearLayout baseRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int d = (int) ctx.getResources().getDisplayMetrics().density;
        row.setPadding(16 * d, 14 * d, 16 * d, 14 * d);
        row.setClickable(true);
        // v1017: 不可聚焦，避免点击后被 ScrollView 自动滚动定位（页面跳动）
        row.setFocusable(false);
        row.setFocusableInTouchMode(false);
        CandyUi.ripple(row, AppColors.SHAPE_MD_DP);
        return row;
    }

    private static TextView nameView(Context ctx, String name, boolean selected) {
        TextView tv = new TextView(ctx);
        tv.setText(name);
        tv.setTextSize(15);
        tv.setTextColor(AppColors.text1());
        tv.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        return tv;
    }

    private static TextView checkMark(Context ctx, boolean selected) {
        TextView mark = new TextView(ctx);
        mark.setText(selected ? "\u2713" : "");
        mark.setTextSize(18);
        mark.setTextColor(AppColors.primary());
        mark.setGravity(Gravity.CENTER);
        return mark;
    }

    private static View dot(Context ctx, int color, int sizeDp) {
        View v = new View(ctx);
        float d = ctx.getResources().getDisplayMetrics().density;
        int size = (int) (sizeDp * d);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        bg.setStroke((int) (1 * d), AppColors.outlineVariant());
        v.setBackground(bg);
        v.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return v;
    }

    private static int lighten(int color) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 0.45f);
        hsv[2] = Math.min(1f, hsv[2] * 0.92f);
        return android.graphics.Color.HSVToColor(hsv);
    }

    private static View makePreview(Context ctx) {
        LinearLayout card = M3Page.card(ctx);
        card.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16));

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int[] roles = {
            AppColors.primary(), AppColors.primaryContainer(), AppColors.secondary(),
            AppColors.tertiary(), AppColors.titleBar(), AppColors.switchColor(),
            AppColors.windowBg()
        };
        String[] labels = {"主色", "主容器", "次色", "三色", "标题栏", "开关", "背景"};
        for (int i = 0; i < roles.length; i++) {
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            col.addView(dot(ctx, roles[i], 24));
            TextView t = new TextView(ctx);
            t.setText(labels[i]);
            t.setTextSize(9);
            t.setTextColor(AppColors.textTertiary());
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(ctx, 4), 0, 0);
            col.addView(t);
            row.addView(col);
        }
        card.addView(row);
        return card;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
