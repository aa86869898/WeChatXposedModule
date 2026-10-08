package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.AutoForwardHook;
import com.leshao.v3.hook.ChatFooterBarHook;
import com.leshao.v3.ui.widgets.M3Page;
import com.leshao.v3.wm.utils.WmPrefs;

/**
 * 乐少群发页（主页「乐少群发」卡片入口，v3.0.165）。
 *
 * <p>v3.0.165 菜单重组：本页移除了原「用户信息」卡片（头像/昵称/微信号），
 * 聚合群发相关能力：</p>
 * <ul>
 *   <li>乐少万群定时群发：勾选多个群 + 输入内容 + 可选定时，一键群发。</li>
 *   <li>自动转发：来源消息自动转发给目标联系人/群聊。</li>
 * </ul>
 */
public final class GroupSendPageView {

    private GroupSendPageView() {}

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;
        final SharedPreferences prefs = ContextManager.getPrefs();

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());
        InsetsUtil.clipRounded(root);
        root.setPadding((int)(AppColors.SPACE_MD_DP * d), (int)(6 * d),
                (int)(AppColors.SPACE_MD_DP * d), (int)(8 * d));

        // ==================== 乐少万群定时群发 ====================
        // v3.0.167：改为纯开关（不再点击进入子页）；打开后聊天输入框快捷菜单才显示「群发」入口
        LinearLayout cardWanQun = makeCard(ctx, d);
        boolean wqOn = WmPrefs.get("batch_send", true);
        cardWanQun.addView(switchRow(ctx, d, "乐少万群定时群发",
                "勾选多个群+定时发送", wqOn,
                (v, on) -> {
                    WmPrefs.set("batch_send", on);
                    ChatFooterBarHook.refreshAfterSwitch();
                    Toast.makeText(ctx, "乐少万群定时群发已" + (on ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
                },
                null));
        root.addView(cardWanQun);

        root.addView(candyDivider(ctx, d));

        // ==================== 自动转发 ====================
        LinearLayout cardAutoFw = makeCard(ctx, d);
        boolean afOn = prefs != null && prefs.getBoolean("ls_autofw_enabled", false);
        cardAutoFw.addView(switchRow(ctx, d, "自动转发",
                "来源消息自动转发给目标联系人/群聊", afOn,
                (v, on) -> {
                    if (prefs != null) prefs.edit().putBoolean("ls_autofw_enabled", on).apply();
                    AutoForwardHook.setEnabled(on);
                    if (on) AutoForwardHook.updateConfig(prefs);
                    ChatFooterBarHook.refreshAfterSwitch();
                    Toast.makeText(ctx, "自动转发已" + (on ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
                },
                v -> AutoForwardHook.showConfigDialog(parentAct)));
        root.addView(cardAutoFw);

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
            btn.setPaintFlags(btn.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            CandyUi.ripple(btn, AppColors.SHAPE_FULL_DP);
            btn.setOnClickListener(configListener);
            row.addView(btn);
        }

        Switch sw = CandyUi.newSwitch(ctx); sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        return row;
    }

    private static View candyDivider(Context ctx, float d) {
        return M3Page.divider(ctx);
    }
}