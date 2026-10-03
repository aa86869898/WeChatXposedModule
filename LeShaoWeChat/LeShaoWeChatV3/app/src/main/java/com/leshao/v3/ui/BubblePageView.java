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
 * 自定义气泡设置页：分别选择「收到气泡图」和「发出气泡图」。
 * 选择通过系统文件管理器（SAF）完成，图片复制到微信私有目录后由
 * {@link ChatBubbleHook} 在 X2C 气泡解析层替换。
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

        // v1145: 页面顶部统一分区标题
        root.addView(M3Page.section(ctx, "自定义气泡",
                "分别设置对方/自己消息的气泡图片，并可修改气泡内文字颜色"));
        root.addView(M3Page.spacer(ctx, 2));

        LinearLayout card = makeCard(ctx, d);

        TextView header = new TextView(ctx);
        header.setText("气泡外观设置");
        header.setTextSize(16);
        header.setTextColor(AppColors.text1());
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding((int)(12 * d), (int)(12 * d), (int)(12 * d), (int)(4 * d));
        card.addView(header);

        TextView tip = new TextView(ctx);
        tip.setText("图片会拉伸填充消息气泡，建议选择纯色/简单图形背景图。仅替换文本消息气泡。");
        tip.setTextSize(12);
        tip.setTextColor(AppColors.text2());
        tip.setPadding((int)(12 * d), (int)(4 * d), (int)(12 * d), (int)(10 * d));
        card.addView(tip);

        card.addView(M3Page.divider(ctx));

        card.addView(buildPickRow(ctx, parentAct, d,
                "对方气泡", ChatBubbleHook.getFromPath(), ChatBubbleHook.KIND_FROM));
        card.addView(M3Page.divider(ctx));
        card.addView(buildPickRow(ctx, parentAct, d,
                "自己气泡", ChatBubbleHook.getToPath(), ChatBubbleHook.KIND_TO));
        card.addView(M3Page.divider(ctx));
        card.addView(buildColorRow(ctx, parentAct, d,
                "对方文字颜色", ChatBubbleHook.KIND_FROM));
        card.addView(M3Page.divider(ctx));
        card.addView(buildColorRow(ctx, parentAct, d,
                "自己文字颜色", ChatBubbleHook.KIND_TO));

        root.addView(card);
        return root;
    }

    private static View buildPickRow(Context ctx, Activity parentAct, float d,
                                     String title, String currentPath, final int kind) {
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
                ChatBubbleHook.setBubblePath(kind, null);
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
        btn.setOnClickListener(v -> pickImage(ctx, parentAct, d, kind));
        row.addView(btn);

        return row;
    }

    /** v3.0.128：气泡内文字颜色行（自定义色板取色，0=不修改）。 */
    private static View buildColorRow(Context ctx, Activity parentAct, float d,
                                      String title, final int kind) {
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

        int cur = ChatBubbleHook.getTextColor(kind);
        TextView sub = new TextView(ctx);
        sub.setText(cur == 0 ? "默认（不修改）" : String.format("#%06X", 0xFFFFFF & cur));
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
                ChatBubbleHook.getTextColor(kind), true, color -> {
                    ChatBubbleHook.setTextColor(kind, color);
                    Toast.makeText(ctx, color == 0 ? "已恢复默认文字颜色" : "文字颜色已设置，重新进入聊天后生效",
                            Toast.LENGTH_SHORT).show();
                    SubPageActivity.refreshCurrent(parentAct);
                }));
        row.addView(btn);

        return row;
    }

    private static void pickImage(Context ctx, Activity parentAct, float d, int kind) {
        ChatBubbleHook.pickBubbleImage(parentAct, kind, path -> {
            if (path != null) {
                ChatBubbleHook.setBubblePath(kind, path);
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
}