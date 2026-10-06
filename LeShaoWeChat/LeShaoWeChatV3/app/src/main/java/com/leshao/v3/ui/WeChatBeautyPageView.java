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
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.ChatBubbleHook;
import com.leshao.v3.hook.GroupTitleTagHook;
import com.leshao.v3.ui.widgets.ColorPickerDialog;
import com.leshao.v3.ui.widgets.M3Page;

/**
 * 微信美化页（主页「微信美化」卡片入口，v3.0.165）。
 *
 * <p>聚合聊天界面的外观定制能力：</p>
 * <ul>
 *   <li>自定义气泡：分别选择收到/发出消息的气泡图片（进入 {@link BubblePageView}）。</li>
 *   <li>聊天时间线颜色：聊天记录内时间分隔条文字颜色。</li>
 *   <li>群聊成员昵称颜色：群聊中成员昵称文字颜色。</li>
 *   <li>显示群成员头衔标签：群主/管理员/成员三角色独立配色（背景色+文字色）。</li>
 * </ul>
 * <p>均由原「联系人和群聊 → UI美化」分类迁移而来（v3.0.165 菜单重组）。</p>
 */
public final class WeChatBeautyPageView {

    private WeChatBeautyPageView() {}

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;
        final SharedPreferences prefs = ContextManager.getPrefs();
        final Activity act = parentAct;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());
        InsetsUtil.clipRounded(root);
        root.setPadding((int)(AppColors.SPACE_MD_DP * d), (int)(6 * d),
                (int)(AppColors.SPACE_MD_DP * d), (int)(8 * d));

        // ==================== 自定义气泡 ====================
        LinearLayout cardBubble = makeCard(ctx, d);
        boolean bubbleOn = prefs != null && prefs.getBoolean(ChatBubbleHook.K_ENABLED, false);
        cardBubble.addView(switchRow(ctx, d, "自定义气泡",
                "分别选择收到/发出消息的气泡图片", bubbleOn,
                (v, on) -> {
                    if (prefs != null) prefs.edit().putBoolean(ChatBubbleHook.K_ENABLED, on).apply();
                    ChatBubbleHook.setEnabled(on);
                    Toast.makeText(ctx, "自定义气泡已" + (on ? "开启" : "关闭")
                            + "（重启微信后完全生效）", Toast.LENGTH_SHORT).show();
                },
                v -> SubPageActivity.open(act, "自定义气泡", 27)));
        root.addView(cardBubble);

        root.addView(candyDivider(ctx, d));

        // ==================== 聊天时间修改（v3.0.208：原「聊天时间线颜色」迁移至此，含自定义格式+浅/暗双套颜色） ====================
        LinearLayout cardTimeMod = makeCard(ctx, d);
        cardTimeMod.addView(switchRow(ctx, d, "聊天时间修改",
                "自定义时间线内容 + 时间线颜色（浅色/暗色独立配置）",
                ChatBubbleHook.isTimeModifyEnabled(),
                (v, on) -> {
                    ChatBubbleHook.setTimeModifyEnabled(on);
                    Toast.makeText(ctx, "聊天时间修改已" + (on ? "开启" : "关闭")
                            + (on ? "（重新进入聊天后生效）" : ""), Toast.LENGTH_SHORT).show();
                },
                v -> SubPageActivity.open(act, "聊天时间修改", 35)));
        root.addView(cardTimeMod);

        root.addView(candyDivider(ctx, d));

        // ==================== 群聊成员昵称颜色 ====================
        LinearLayout cardNickColor = makeCard(ctx, d);
        cardNickColor.addView(colorRow(ctx, d, "群聊成员昵称颜色",
                "修改群聊中成员昵称文字颜色",
                ChatBubbleHook.getNickTextColor(), color -> {
                    ChatBubbleHook.setNickTextColor(color);
                    Toast.makeText(ctx, color == 0 ? "已恢复默认昵称颜色"
                            : "昵称颜色已设置，重新进入聊天后生效", Toast.LENGTH_SHORT).show();
                }));
        root.addView(cardNickColor);

        root.addView(candyDivider(ctx, d));

        // ==================== 显示群成员头衔标签 ====================
        LinearLayout cardGroupTitle = makeCard(ctx, d);
        cardGroupTitle.addView(switchRow(ctx, d, "显示群成员头衔标签",
                "群聊消息昵称旁显示「群主/管理员」头衔，三角色独立配色", GroupTitleTagHook.isEnabled(),
                (v, on) -> {
                    GroupTitleTagHook.setEnabled(on);
                    Toast.makeText(ctx, "群成员头衔标签已" + (on ? "开启" : "关闭")
                            + "（重新进入群聊后生效）", Toast.LENGTH_SHORT).show();
                }, null));
        cardGroupTitle.addView(candyDivider(ctx, d));
        cardGroupTitle.addView(colorRow(ctx, d, "群主背景色",
                "群主头衔标签背景色（0 恢复原生）",
                GroupTitleTagHook.getOwnerBg(), color -> {
                    GroupTitleTagHook.setColors(color, GroupTitleTagHook.getOwnerText(),
                            GroupTitleTagHook.getAdminBg(), GroupTitleTagHook.getAdminText(),
                            GroupTitleTagHook.getMemberBg(), GroupTitleTagHook.getMemberText());
                    Toast.makeText(ctx, color == 0 ? "已恢复群主背景默认"
                            : "群主背景色已设置，重新进入群聊后生效", Toast.LENGTH_SHORT).show();
                }));
        cardGroupTitle.addView(candyDivider(ctx, d));
        cardGroupTitle.addView(colorRow(ctx, d, "群主文字色",
                "群主头衔标签文字颜色（0 恢复原生）",
                GroupTitleTagHook.getOwnerText(), color -> {
                    GroupTitleTagHook.setColors(GroupTitleTagHook.getOwnerBg(), color,
                            GroupTitleTagHook.getAdminBg(), GroupTitleTagHook.getAdminText(),
                            GroupTitleTagHook.getMemberBg(), GroupTitleTagHook.getMemberText());
                    Toast.makeText(ctx, color == 0 ? "已恢复群主文字默认"
                            : "群主文字色已设置，重新进入群聊后生效", Toast.LENGTH_SHORT).show();
                }));
        cardGroupTitle.addView(candyDivider(ctx, d));
        cardGroupTitle.addView(colorRow(ctx, d, "管理员背景色",
                "管理员头衔标签背景色（0 恢复原生）",
                GroupTitleTagHook.getAdminBg(), color -> {
                    GroupTitleTagHook.setColors(GroupTitleTagHook.getOwnerBg(), GroupTitleTagHook.getOwnerText(),
                            color, GroupTitleTagHook.getAdminText(),
                            GroupTitleTagHook.getMemberBg(), GroupTitleTagHook.getMemberText());
                    Toast.makeText(ctx, color == 0 ? "已恢复管理员背景默认"
                            : "管理员背景色已设置，重新进入群聊后生效", Toast.LENGTH_SHORT).show();
                }));
        cardGroupTitle.addView(candyDivider(ctx, d));
        cardGroupTitle.addView(colorRow(ctx, d, "管理员文字色",
                "管理员头衔标签文字颜色（0 恢复原生）",
                GroupTitleTagHook.getAdminText(), color -> {
                    GroupTitleTagHook.setColors(GroupTitleTagHook.getOwnerBg(), GroupTitleTagHook.getOwnerText(),
                            GroupTitleTagHook.getAdminBg(), color,
                            GroupTitleTagHook.getMemberBg(), GroupTitleTagHook.getMemberText());
                    Toast.makeText(ctx, color == 0 ? "已恢复管理员文字默认"
                            : "管理员文字色已设置，重新进入群聊后生效", Toast.LENGTH_SHORT).show();
                }));
        cardGroupTitle.addView(candyDivider(ctx, d));
        cardGroupTitle.addView(colorRow(ctx, d, "成员背景色",
                "成员头衔标签背景色（0 恢复原生）",
                GroupTitleTagHook.getMemberBg(), color -> {
                    GroupTitleTagHook.setColors(GroupTitleTagHook.getOwnerBg(), GroupTitleTagHook.getOwnerText(),
                            GroupTitleTagHook.getAdminBg(), GroupTitleTagHook.getAdminText(),
                            color, GroupTitleTagHook.getMemberText());
                    Toast.makeText(ctx, color == 0 ? "已恢复成员背景默认"
                            : "成员背景色已设置，重新进入群聊后生效", Toast.LENGTH_SHORT).show();
                }));
        cardGroupTitle.addView(candyDivider(ctx, d));
        cardGroupTitle.addView(colorRow(ctx, d, "成员文字色",
                "成员头衔标签文字颜色（0 恢复原生）",
                GroupTitleTagHook.getMemberText(), color -> {
                    GroupTitleTagHook.setColors(GroupTitleTagHook.getOwnerBg(), GroupTitleTagHook.getOwnerText(),
                            GroupTitleTagHook.getAdminBg(), GroupTitleTagHook.getAdminText(),
                            GroupTitleTagHook.getMemberBg(), color);
                    Toast.makeText(ctx, color == 0 ? "已恢复成员文字默认"
                            : "成员文字色已设置，重新进入群聊后生效", Toast.LENGTH_SHORT).show();
                }));
        root.addView(cardGroupTitle);

        return root;
    }

    // ==================== 本地构建器（与 ContactGroupPageView 同风格） ====================

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(CandyUi.cardBg(ctx));
        InsetsUtil.clipRounded(card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, (int)(13 * d));
        card.setLayoutParams(lp);
        return card;
    }

    private static LinearLayout switchRow(Context ctx, float d, String title, String desc,
                                          boolean checked, CompoundButton.OnCheckedChangeListener listener,
                                          View.OnClickListener configListener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // 全局规范: 行触控区域不低于 48dp
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

        if (configListener != null) {
            TextView btn = new TextView(ctx);
            btn.setText("[设置]"); btn.setTextSize(12); btn.setTextColor(AppColors.accent());
            btn.setPadding((int)(6 * d), 0, (int)(6 * d), 0);
            btn.setPaintFlags(btn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            CandyUi.ripple(btn, AppColors.SHAPE_FULL_DP);
            btn.setOnClickListener(configListener);
            row.addView(btn);
        }

        Switch sw = CandyUi.newSwitch(ctx); sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        return row;
    }

    /** 颜色修改行（色块预览 + [取色]，0 = 恢复微信原生）。 */
    private static LinearLayout colorRow(Context ctx, float d, String title, String desc,
                                         int current, ColorPickerDialog.OnPick onPick) {
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

        // 色块预览
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
                ColorPickerDialog.show(ctx, title, current, true, onPick));
        row.addView(btn);

        return row;
    }

    private static View candyDivider(Context ctx, float d) {
        return M3Page.divider(ctx);
    }
}
