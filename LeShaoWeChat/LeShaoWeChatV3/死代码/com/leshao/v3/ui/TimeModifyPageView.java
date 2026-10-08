package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.ChatBubbleHook;
import com.leshao.v3.ui.widgets.ColorPickerDialog;
import com.leshao.v3.ui.widgets.M3Page;

/**
 * 聊天时间修改设置页（微信美化 → 聊天时间修改，pageId=35，v3.0.208）。
 *
 * <p>聚合聊天时间线定制能力：</p>
 * <ul>
 *   <li>总开关：聊天时间修改。</li>
 *   <li>自定义时间线内容：Java SimpleDateFormat 语义 token（yyyy/yy/YYYY/MM/M/dd/d/DD/
 *       HH/H/hh/h/mm/ss/SSS/a/E/EEEE/w），空 = 保留微信原生。</li>
 *   <li>聊天时间线颜色：浅色/暗色两套独立配置（同自定义气泡页风格），运行时按微信深色设置自动套用。</li>
 * </ul>
 * <p>v3.0.208：原「聊天时间线颜色」从微信美化页迁移至此，并升级为浅色/暗色双套配置。</p>
 */
public final class TimeModifyPageView {

    private TimeModifyPageView() {}

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;
        final SharedPreferences prefs = ContextManager.getPrefs();

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());
        InsetsUtil.clipRounded(root);
        root.setPadding((int)(AppColors.SPACE_MD_DP * d), (int)(6 * d),
                (int)(AppColors.SPACE_MD_DP * d), (int)(8 * d));

        root.addView(M3Page.section(ctx, "聊天时间修改",
                "自定义聊天记录时间分隔条显示内容与颜色，浅色/暗色各一套独立配置"));
        root.addView(M3Page.spacer(ctx, 2));

        // ==================== 总开关 ====================
        LinearLayout cardSwitch = makeCard(ctx, d);
        cardSwitch.addView(switchRow(ctx, d, "聊天时间修改",
                "开启后按下方格式改写每条消息的时间分隔条", ChatBubbleHook.isTimeModifyEnabled(),
                (v, on) -> {
                    ChatBubbleHook.setTimeModifyEnabled(on);
                    Toast.makeText(ctx, "聊天时间修改已" + (on ? "开启" : "关闭")
                            + (on ? "（重新进入聊天后生效）" : ""), Toast.LENGTH_SHORT).show();
                }));
        root.addView(cardSwitch);

        root.addView(M3Page.divider(ctx));

        // ==================== 自定义时间线内容 ====================
        LinearLayout cardFormat = makeCard(ctx, d);
        TextView fmtTitle = new TextView(ctx);
        fmtTitle.setText("时间线内容格式");
        fmtTitle.setTextSize(16);
        fmtTitle.setTextColor(AppColors.text1());
        fmtTitle.setTypeface(null, Typeface.BOLD);
        fmtTitle.setPadding((int)(12 * d), (int)(6 * d), (int)(12 * d), (int)(4 * d));
        cardFormat.addView(fmtTitle);

        final EditText et = M3Page.input(ctx, "如 yyyy-MM-dd HH:mm:ss");
        String curFmt = ChatBubbleHook.getTimeFormat();
        et.setText(curFmt == null ? "" : curFmt);
        et.setHorizontallyScrolling(true);
        cardFormat.addView(et);

        TextView pastedTip = new TextView(ctx);
        pastedTip.setText("为空 = 保留微信原生显示\n"
                + "yyyy=4位年 yy=2位年 YYYY=周年\n"
                + "MM=月(补零) M=月(不补零) dd=日(补零) d=日(不补零) DD=年内第几天\n"
                + "HH=24时(补零) H=24时(不补零) hh=12时(补零) h=12时(不补零)\n"
                + "mm=分 ss=秒 SSS=毫秒 a=上午/下午 E=星期简写 EEEE=星期全称 w=当年第几周");
        pastedTip.setTextSize(12);
        pastedTip.setTextColor(AppColors.text2());
        pastedTip.setLineSpacing(0, 1.15f);
        pastedTip.setPadding((int)(12 * d), (int)(6 * d), (int)(12 * d), (int)(4 * d));
        cardFormat.addView(pastedTip);

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);

        TextView previewBtn = textBtn(ctx, d, "预览", AppColors.text2(), false);
        previewBtn.setOnClickListener(v -> {
            String f = et.getText() == null ? "" : et.getText().toString();
            String rendered = ChatBubbleHook.formatTimeLine(System.currentTimeMillis(), f);
            Toast.makeText(ctx, rendered != null ? ("当前时间渲染: " + rendered) : "格式为空",
                    Toast.LENGTH_SHORT).show();
        });
        btnRow.addView(previewBtn);

        TextView saveBtn = textBtn(ctx, d, "保存", AppColors.accent(), true);
        saveBtn.setOnClickListener(v -> {
            String f = et.getText() == null ? "" : et.getText().toString();
            ChatBubbleHook.setTimeFormat(f);
            if (f.trim().isEmpty()) {
                Toast.makeText(ctx, "已恢复微信原生时间显示", Toast.LENGTH_SHORT).show();
            } else {
                String rendered = ChatBubbleHook.formatTimeLine(System.currentTimeMillis(), f);
                Toast.makeText(ctx, rendered != null ? ("格式已保存，示例: " + rendered)
                        : "格式包含无效字符，请检查", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(saveBtn);

        btnRow.setPadding((int)(12 * d), 0, (int)(12 * d), (int)(10 * d));
        cardFormat.addView(btnRow);
        root.addView(cardFormat);

        root.addView(M3Page.divider(ctx));

        // ==================== 浅色模式区：时间线颜色 ====================
        LinearLayout cardLt = makeCard(ctx, d);
        cardLt.addView(themeHeader(ctx, d, "浅色模式"));
        cardLt.addView(colorRow(ctx, d, "时间线文字颜色",
                "聊天记录内时间分隔条文字颜色（0 恢复原生）",
                ChatBubbleHook.getTimeTextColor(ChatBubbleHook.THEME_LIGHT), ChatBubbleHook.THEME_LIGHT));
        root.addView(cardLt);

        // ==================== 暗色模式区：时间线颜色 ====================
        LinearLayout cardDt = makeCard(ctx, d);
        cardDt.addView(themeHeader(ctx, d, "暗色模式"));
        cardDt.addView(colorRow(ctx, d, "时间线文字颜色",
                "微信深色模式下时间分隔条文字颜色（0 恢复原生）",
                ChatBubbleHook.getTimeTextColor(ChatBubbleHook.THEME_DARK), ChatBubbleHook.THEME_DARK));
        root.addView(cardDt);

        return root;
    }

    // ==================== 本地构建器 ====================

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

    private static LinearLayout switchRow(Context ctx, float d, String title, String desc,
                                          boolean checked, CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight((int)(48 * d));
        row.setPadding((int)(12 * d), (int)(10 * d), (int)(12 * d), (int)(10 * d));
        row.setBackground(CandyUi.rowBg(ctx));
        InsetsUtil.clipRounded(row);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView tv = new TextView(ctx);
        tv.setText(title); tv.setTextSize(16);
        tv.setTextColor(AppColors.text1()); tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(ctx);
            dv.setText(desc); dv.setTextSize(12);
            dv.setTextColor(AppColors.text2());
            dv.setPadding(0, (int)(3 * d), 0, 0);
            textCol.addView(dv);
        }
        row.addView(textCol);

        Switch sw = CandyUi.newSwitch(ctx); sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        return row;
    }

    /** 主题分区小标题栏（同 BubblePageView.buildThemeHeader）。 */
    private static View themeHeader(Context ctx, float d, String title) {
        TextView h = new TextView(ctx);
        h.setText(title);
        h.setTextSize(13);
        h.setTextColor(AppColors.accent());
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding((int)(12 * d), (int)(14 * d), (int)(12 * d), (int)(4 * d));
        h.setBackgroundColor(0x0A000000);
        return h;
    }

    /** 颜色修改行（色块预览 + [取色]，0 = 恢复微信原生）。按主题写浅/暗独立配置。 */
    private static LinearLayout colorRow(Context ctx, float d, String title, String desc,
                                         int current, final int theme) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight((int)(48 * d));
        row.setPadding((int)(12 * d), (int)(10 * d), (int)(12 * d), (int)(10 * d));
        row.setBackground(CandyUi.rowBg(ctx));
        InsetsUtil.clipRounded(row);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView tv = new TextView(ctx);
        tv.setText(title); tv.setTextSize(16);
        tv.setTextColor(AppColors.text1()); tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(ctx);
            dv.setText(desc); dv.setTextSize(12);
            dv.setTextColor(AppColors.text2());
            dv.setPadding(0, (int)(3 * d), 0, 0);
            textCol.addView(dv);
        }
        row.addView(textCol);

        int sz = (int)(22 * d);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(sz, sz);
        slp.setMargins(0, 0, (int)(10 * d), 0);
        View swatch = new View(ctx);
        swatch.setLayoutParams(slp);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius((int)(6 * d));
        if (current == 0) {
            gd.setColor(AppColors.inputBg());
            gd.setStroke((int)(1 * d), AppColors.outlineVariant());
        } else {
            gd.setColor(current);
            gd.setStroke((int)(1 * d), 0x33000000);
        }
        swatch.setBackground(gd);
        row.addView(swatch);

        TextView btn = new TextView(ctx);
        btn.setText("[取色]");
        btn.setTextSize(12);
        btn.setTextColor(AppColors.accent());
        btn.setPadding((int)(6 * d), 0, (int)(6 * d), 0);
        btn.setPaintFlags(btn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        CandyUi.ripple(btn, AppColors.SHAPE_FULL_DP);
        btn.setOnClickListener(v ->
                ColorPickerDialog.show(ctx, title, current, true, color -> {
                    ChatBubbleHook.setTimeTextColor(theme, color);
                    Toast.makeText(ctx, color == 0 ? "已恢复默认时间颜色"
                            : "时间颜色已设置，重新进入聊天后生效", Toast.LENGTH_SHORT).show();
                }));
        row.addView(btn);

        return row;
    }

    private static TextView textBtn(Context ctx, float d, String text, int color, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding((int)(16 * d), (int)(8 * d), (int)(16 * d), (int)(8 * d));
        CandyUi.ripple(tv, AppColors.SHAPE_FULL_DP);
        return tv;
    }
}