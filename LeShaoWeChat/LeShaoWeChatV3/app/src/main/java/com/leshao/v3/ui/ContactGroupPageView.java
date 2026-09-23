package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.*;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.ui.widgets.M3Page;

import android.widget.Toast;

public class ContactGroupPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;
        final SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);
        final Activity act = parentAct;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());
        InsetsUtil.clipRounded(root);
        root.setPadding((int)(16 * d), (int)(16 * d), (int)(16 * d), (int)(16 * d));

        LinearLayout cardChat = makeCard(ctx, d);
        boolean vfOn = prefs != null && prefs.getBoolean("ls_voice_forward", false);

        // v998: 移除"消息防撤回"功能入口
        cardChat.addView(switchRow(ctx, d, "语音消息转发", null, vfOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean("ls_voice_forward", on).apply();
            VoiceForwardHook.setEnabled(on);
        }, null));
        root.addView(cardChat);

        root.addView(candyDivider(ctx, d));

        // 自动转发卡片
        boolean afOn = prefs != null && prefs.getBoolean("ls_autofw_enabled", false);
        LinearLayout cardAutoFw = makeCard(ctx, d);
        cardAutoFw.addView(switchRow(ctx, d, "自动转发", "来源消息自动转发给目标联系人/群聊", afOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean("ls_autofw_enabled", on).apply();
            com.leshao.v3.hook.AutoForwardHook.setEnabled(on);
            if (on) com.leshao.v3.hook.AutoForwardHook.updateConfig(prefs);
            Toast.makeText(ctx, "自动转发已" + (on ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        }, v -> com.leshao.v3.hook.AutoForwardHook.showConfigDialog(act)));
        root.addView(cardAutoFw);

        root.addView(candyDivider(ctx, d));

        // 聊天分组卡片
        boolean chatGroupOn = prefs != null && prefs.getBoolean("ls_chat_group_enabled", true);
        LinearLayout cardGroup = makeCard(ctx, d);
        cardGroup.addView(switchRow(ctx, d, "聊天分组", null, chatGroupOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean("ls_chat_group_enabled", on).apply();
            // v998: 开关变化后立即显示/隐藏聊天列表顶部的分组栏
            ChatGroupUiInjector.onEnabledChanged();
        }, v -> SubPageActivity.open(act, "聊天分组管理", 14)));
        root.addView(cardGroup);

        root.addView(candyDivider(ctx, d));

        // 群聊批量加好友卡片（模块内仅总开关，其余配置在群聊详情页按钮弹窗内）
        LinearLayout cardBatch = makeCard(ctx, d);
        boolean batchOn = prefs != null && prefs.getBoolean("ls_batch_add_enabled", false);
        cardBatch.addView(switchRow(ctx, d, "群聊批量加好友", "群聊详情页右上角[批量加友]菜单内配置", batchOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean("ls_batch_add_enabled", on).apply();
            BatchAddFriend.setEnabled(on);
            Toast.makeText(ctx, "批量加好友已" + (on ? "开启" : "关闭")
                    + "\n重启微信后生效", Toast.LENGTH_LONG).show();
        }, null));
        root.addView(cardBatch);

        root.addView(candyDivider(ctx, d));

        // v998: 万群定时群发从"群管理助手"移植到本菜单, 点击进入独立页面
        LinearLayout cardWanQun = makeCard(ctx, d);
        cardWanQun.addView(M3Page.clickRow(ctx, "\uD83D\uDCE2", "乐少万群定时群发", "勾选多个群+定时发送",
                () -> SubPageActivity.open(act, "乐少万群定时群发", 4)));
        root.addView(cardWanQun);

        root.addView(candyDivider(ctx, d));

        LinearLayout cardEntry = makeCard(ctx, d);
        boolean cornerMenuOn = com.leshao.v3.wm.utils.WmPrefs.isCornerMenu();
        boolean longPressMenuOn = com.leshao.v3.wm.utils.WmPrefs.isLongPressMenu();
        boolean inputButtonsOn = com.leshao.v3.wm.utils.WmPrefs.isInputButtons();

        cardEntry.addView(switchRow(ctx, d, "微信左上角菜单", null, cornerMenuOn, (v, on) -> {
            com.leshao.v3.wm.utils.WmPrefs.set("corner_menu", on);
        }, null));
        cardEntry.addView(switchRow(ctx, d, "聊天窗口长按菜单", null, longPressMenuOn, (v, on) -> {
            com.leshao.v3.wm.utils.WmPrefs.set("long_press_menu", on);
        }, null));
        cardEntry.addView(switchRow(ctx, d, "输入框功能按钮", null, inputButtonsOn, (v, on) -> {
            com.leshao.v3.wm.utils.WmPrefs.set("input_buttons", on);
        }, null));
        root.addView(cardEntry);

        return root;
    }

    private static LinearLayout subSwitch(Context ctx, SharedPreferences prefs, float d,
                                           String key, String title, String desc, boolean defVal) {
        return subSwitch(ctx, prefs, d, key, title, desc, defVal, null);
    }

    private static LinearLayout subSwitch(Context ctx, SharedPreferences prefs, float d,
                                           String key, String title, String desc, boolean defVal,
                                           View.OnClickListener config) {
        boolean checked = prefs != null ? prefs.getBoolean(key, defVal) : defVal;
        return switchRow(ctx, d, title, desc, checked, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(key, on).apply();
        }, config);
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(2 * d), (int)(2 * d), (int)(2 * d), (int)(2 * d));
        card.setBackground(CandyUi.cardBg(ctx));
        InsetsUtil.clipRounded(card);
        return card;
    }

    private static LinearLayout switchRow(Context ctx, float d, String title, String desc,
                                           boolean checked, CompoundButton.OnCheckedChangeListener listener,
                                           View.OnClickListener configListener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackground(CandyUi.cardBg(ctx));
        InsetsUtil.clipRounded(row);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView tv = new TextView(ctx);
        tv.setText(title); tv.setTextSize(15);
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
            LogWriter.log("ContactGroupPageView", "switchRow [" + title + "] 显示[设置]按钮");
            TextView btn = new TextView(ctx);
            btn.setText("[设置]"); btn.setTextSize(12); btn.setTextColor(AppColors.accent());
            btn.setPadding((int)(6 * d), 0, (int)(6 * d), 0);
            btn.setPaintFlags(btn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            btn.setOnClickListener(configListener);
            row.addView(btn);
        }

            Switch sw = CandyUi.newSwitch(ctx); sw.setChecked(checked);
        try { sw.setThumbResource(android.R.drawable.btn_star_big_on); } catch (Throwable ignored) {}
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        return row;
    }

    private static TextView sectionLabel(Context ctx, String text) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        tv.setText(text); tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(8 * d));
        return tv;
    }

    private static View candyDivider(Context ctx, float d) {
        return M3Page.divider(ctx);
    }

    private static View spacerV(Context ctx, float d, int dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dp * d)));
        return v;
    }
}
