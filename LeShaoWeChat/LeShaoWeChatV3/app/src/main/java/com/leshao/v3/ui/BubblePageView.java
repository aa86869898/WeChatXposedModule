package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.hook.ChatBubbleHook;
import com.leshao.v3.ui.widgets.ColorPickerDialog;
import com.leshao.v3.ui.widgets.M3Page;

/**
 * 自定义气泡设置页：浅色/暗色两套独立配置，每套含
 * 「自己文字颜色 / 自己气泡 / 对方文字颜色 / 对方气泡」四行，按顺序平铺。
 * v3.0.204：两区用一行小标题栏隔开，实时生效；语音/位置/名片/链接等其它消息
 * 文字统一跟随当前模式字色。
 * 图片经 SAF 复制到微信私有目录后由 {@link ChatBubbleHook} 在气泡解析层替换。
 */
public class BubblePageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());
        InsetsUtil.clipRounded(root);
        root.setPadding((int)(AppColors.SPACE_MD_DP * d), (int)(6 * d),
                (int)(AppColors.SPACE_MD_DP * d), (int)(8 * d));

        root.addView(M3Page.section(ctx, "自定义气泡",
                "分别设置对方/自己消息的气泡图片与文字颜色，浅色/暗色各一套独立配置"));
        root.addView(M3Page.spacer(ctx, 2));

        LinearLayout card = makeCard(ctx, d);

        // ==================== 浅色模式区 ====================
        card.addView(buildThemeHeader(ctx, d, "浅色模式"));
        card.addView(buildColorRow(ctx, parentAct, d, "自己文字颜色", ChatBubbleHook.KIND_TO, ChatBubbleHook.THEME_LIGHT));
        card.addView(M3Page.divider(ctx));
        card.addView(buildPickRow(ctx, parentAct, d, "自己气泡",
                ChatBubbleHook.getToPath(ChatBubbleHook.THEME_LIGHT), ChatBubbleHook.KIND_TO, ChatBubbleHook.THEME_LIGHT));
        card.addView(M3Page.divider(ctx));
        card.addView(buildColorRow(ctx, parentAct, d, "对方文字颜色", ChatBubbleHook.KIND_FROM, ChatBubbleHook.THEME_LIGHT));
        card.addView(M3Page.divider(ctx));
        card.addView(buildPickRow(ctx, parentAct, d, "对方气泡",
                ChatBubbleHook.getFromPath(ChatBubbleHook.THEME_LIGHT), ChatBubbleHook.KIND_FROM, ChatBubbleHook.THEME_LIGHT));

        // ==================== 暗色模式区 ====================
        card.addView(buildThemeHeader(ctx, d, "暗色模式"));
        card.addView(buildColorRow(ctx, parentAct, d, "自己文字颜色", ChatBubbleHook.KIND_TO, ChatBubbleHook.THEME_DARK));
        card.addView(M3Page.divider(ctx));
        card.addView(buildPickRow(ctx, parentAct, d, "自己气泡",
                ChatBubbleHook.getToPath(ChatBubbleHook.THEME_DARK), ChatBubbleHook.KIND_TO, ChatBubbleHook.THEME_DARK));
        card.addView(M3Page.divider(ctx));
        card.addView(buildColorRow(ctx, parentAct, d, "对方文字颜色", ChatBubbleHook.KIND_FROM, ChatBubbleHook.THEME_DARK));
        card.addView(M3Page.divider(ctx));
        card.addView(buildPickRow(ctx, parentAct, d, "对方气泡",
                ChatBubbleHook.getFromPath(ChatBubbleHook.THEME_DARK), ChatBubbleHook.KIND_FROM, ChatBubbleHook.THEME_DARK));

        root.addView(card);

        TextView tip = new TextView(ctx);
        tip.setText("微信为深色模式时自动套用「暗色模式」配置，否则套用「浅色模式」。\n"
                + "文字颜色同时作用于语音动画/秒数、位置、名片、文章链接等其它消息文字，修改后实时生效。");
        tip.setTextSize(12);
        tip.setTextColor(AppColors.text2());
        tip.setPadding((int)(12 * d), (int)(10 * d), (int)(12 * d), (int)(4 * d));
        root.addView(tip);

        return root;
    }

    /** 主题分区小标题栏（浅色模式 / 暗色模式）。 */
    private static View buildThemeHeader(Context ctx, float d, String title) {
        TextView h = new TextView(ctx);
        h.setText(title);
        h.setTextSize(13);
        h.setTextColor(AppColors.accent());
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding((int)(12 * d), (int)(14 * d), (int)(12 * d), (int)(4 * d));
        h.setBackgroundColor(0x0A000000);
        return h;
    }

    private static View buildPickRow(Context ctx, Activity parentAct, float d,
                                     String title, String currentPath, final int kind, final int theme) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(12 * d), (int)(10 * d), (int)(12 * d), (int)(10 * d));
        row.setBackground(CandyUi.rowPressBg(ctx));

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(16);
        tv.setTextColor(AppColors.text1());
        tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        TextView pathTv = new TextView(ctx);
        pathTv.setText(currentPath == null ? "未设置" : new java.io.File(currentPath).getName());
        pathTv.setTextSize(12);
        pathTv.setTextColor(AppColors.text2());
        pathTv.setPadding(0, (int)(2 * d), 0, 0);
        pathTv.setSingleLine(true);
        textCol.addView(pathTv);
        row.addView(textCol);

        if (currentPath != null) {
            TextView clearBtn = new TextView(ctx);
            clearBtn.setText("清除");
            clearBtn.setTextSize(12);
            clearBtn.setTextColor(AppColors.text2());
            clearBtn.setPadding((int)(6 * d), 0, (int)(6 * d), 0);
            CandyUi.ripple(clearBtn, AppColors.SHAPE_FULL_DP);
            clearBtn.setOnClickListener(v -> {
                ChatBubbleHook.setBubblePath(kind, theme, null);
                Toast.makeText(ctx, "已清除", Toast.LENGTH_SHORT).show();
                SubPageActivity.refreshCurrent(parentAct);
            });
            row.addView(clearBtn);
        }

        TextView btn = new TextView(ctx);
        btn.setText("[选择图片]");
        btn.setTextSize(12);
        btn.setTextColor(AppColors.accent());
        btn.setPadding((int)(6 * d), 0, (int)(6 * d), 0);
        CandyUi.ripple(btn, AppColors.SHAPE_FULL_DP);
        btn.setOnClickListener(v -> pickImage(ctx, parentAct, d, kind, theme));
        row.addView(btn);

        return row;
    }

    /** 文字颜色行（自定义色板取色，0=不修改）。v3.0.204：设置后立即刷新已渲染聊天窗口。 */
    private static View buildColorRow(Context ctx, Activity parentAct, float d,
                                      String title, final int kind, final int theme) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(12 * d), (int)(10 * d), (int)(12 * d), (int)(10 * d));
        row.setBackground(CandyUi.rowPressBg(ctx));

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(16);
        tv.setTextColor(AppColors.text1());
        tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        int cur = ChatBubbleHook.getTextColor(kind, theme);
        TextView sub = new TextView(ctx);
        sub.setText(cur == 0 ? "默认（跟随微信）" : String.format("#%06X", 0xFFFFFF & cur));
        sub.setTextSize(12);
        sub.setTextColor(AppColors.text2());
        sub.setPadding(0, (int)(2 * d), 0, 0);
        textCol.addView(sub);
        row.addView(textCol);

        // 颜色预览块
        View swatch = new View(ctx);
        int sz = (int)(22 * d);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(sz, sz);
        slp.setMargins(0, 0, (int)(10 * d), 0);
        swatch.setLayoutParams(slp);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius((int)(6 * d));
        if (cur == 0) {
            gd.setColor(AppColors.inputBg());
            gd.setStroke((int)(1 * d), AppColors.outlineVariant());
        } else {
            gd.setColor(cur);
            gd.setStroke((int)(1 * d), 0x33000000);
        }
        swatch.setBackground(gd);
        row.addView(swatch);

        TextView btn = new TextView(ctx);
        btn.setText("[取色]");
        btn.setTextSize(12);
        btn.setTextColor(AppColors.accent());
        btn.setPadding((int)(6 * d), 0, (int)(6 * d), 0);
        CandyUi.ripple(btn, AppColors.SHAPE_FULL_DP);
        btn.setOnClickListener(v -> ColorPickerDialog.show(ctx, title,
                ChatBubbleHook.getTextColor(kind, theme), true,
                color -> {
                    // 确认：写入并刷新渲染，就地重建刷新展现
                    ChatBubbleHook.setTextColor(kind, theme, color);
                    Toast.makeText(ctx, color == 0 ? "已恢复默认文字颜色" : "文字颜色已设置，已实时生效",
                            Toast.LENGTH_SHORT).show();
                    SubPageActivity.refreshCurrent(parentAct);
                },
                color -> {
                    // v3.0.207：实时预览（拖动色相/SV 面板高频回调）——更新内存渲染，并就地刷新本行
                    // 颜色预览块 + hex 文本，让拖动立刻看到反馈（不重建页面，避免爆闪）。
                    ChatBubbleHook.previewTextColor(kind, theme, color);
                    updateRowPreview(d, swatch, sub, color);
                }));
        row.addView(btn);

        return row;
    }

    private static void pickImage(Context ctx, Activity parentAct, float d, int kind, int theme) {
        ChatBubbleHook.pickBubbleImage(parentAct, kind, path -> {
            if (path != null) {
                ChatBubbleHook.setBubblePath(kind, theme, path);
                Toast.makeText(ctx, "气泡图片已设置，重启微信或重新进入聊天后生效",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(ctx, "未选择图片", Toast.LENGTH_SHORT).show();
            }
            SubPageActivity.refreshCurrent(parentAct);
        });
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, 0, 0, 0);
        card.setBackground(CandyUi.cardBg(ctx));
        InsetsUtil.clipRounded(card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, (int)(13 * d));
        card.setLayoutParams(lp);
        return card;
    }

    /** v3.0.207：拖动取色实时就地刷新本行颜色预览块 + hex 文本（不重建页面）。
     *  color==0 表示恢复默认：显示占位背景 + 「默认（跟随微信）」。 */
    private static void updateRowPreview(float d, View swatch, TextView sub, int color) {
        try {
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setCornerRadius((int)(6 * d));
            if (color == 0) {
                gd.setColor(AppColors.inputBg());
                gd.setStroke((int)(1 * d), AppColors.outlineVariant());
                sub.setText("默认（跟随微信）");
            } else {
                gd.setColor(color);
                gd.setStroke((int)(1 * d), 0x33000000);
                sub.setText(String.format("#%06X", 0xFFFFFF & color));
            }
            swatch.setBackground(gd);
        } catch (Throwable ignored) {}
    }
}
