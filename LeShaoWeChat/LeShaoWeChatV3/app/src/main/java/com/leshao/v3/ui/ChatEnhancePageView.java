package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.TypingIndicator;
import com.leshao.v3.hook.ChatFooterEnhance;
import com.leshao.v3.hook.ChatUICustom;
import com.leshao.v3.hook.BatchMessage;
import com.leshao.v3.hook.AutoRemark;
import com.leshao.v3.hook.SearchEnhance;
import com.leshao.v3.hook.NotifyCustom;
import com.leshao.v3.model.ModuleConfig;

public class ChatEnhancePageView {

    public static View create(Context ctx, Activity parentAct) {
        final SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);
        final Activity act = parentAct;
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(8 * d), (int)(16 * d), (int)(8 * d), (int)(16 * d));

        root.addView(sLabel(ctx, d, "聊天增强"));

        LinearLayout card = makeCard(ctx, d);
        card.addView(switchRow(ctx, d, "对方正在输入提示",
                null, cfg.typingIndicatorEnabled, (v, on) -> {
            cfg.typingIndicatorEnabled = on; cfg.save(prefs); TypingIndicator.setEnabled(on);
        }, v -> ConfigPanels.showTypingIndicator(act, prefs)));
        card.addView(switchRow(ctx, d, "输入框增强",
                "突破字数限制/输入框自适应", cfg.chatFooterEnhanceEnabled, (v, on) -> {
            cfg.chatFooterEnhanceEnabled = on; cfg.save(prefs); ChatFooterEnhance.setEnabled(on);
        }, v -> ConfigPanels.showChatFooterEnhance(act, prefs)));
        card.addView(switchRow(ctx, d, "聊天界面自定义",
                "背景/气泡颜色/圆角/昵称", cfg.chatUICustomEnabled, (v, on) -> {
            cfg.chatUICustomEnabled = on; cfg.save(prefs); ChatUICustom.setEnabled(on);
        }, v -> ConfigPanels.showChatUICustom(act, prefs)));
        card.addView(switchRow(ctx, d, "批量消息操作",
                "突破9条限制/全选反选", cfg.batchMessageEnabled, (v, on) -> {
            cfg.batchMessageEnabled = on; cfg.save(prefs); BatchMessage.setEnabled(on);
        }, v -> ConfigPanels.showBatchMessage(act, prefs)));
        card.addView(switchRow(ctx, d, "自动备注好友",
                "从群昵称/名片自动填充", cfg.autoRemarkEnabled, (v, on) -> {
            cfg.autoRemarkEnabled = on; cfg.save(prefs); AutoRemark.setEnabled(on);
        }, v -> ConfigPanels.showAutoRemark(act, prefs)));
        card.addView(switchRow(ctx, d, "全文搜索增强",
                null, cfg.searchEnhanceEnabled, (v, on) -> {
            cfg.searchEnhanceEnabled = on; cfg.save(prefs); SearchEnhance.setEnabled(on);
        }, v -> ConfigPanels.showSearchEnhance(act, prefs)));
        root.addView(card);

        root.addView(candyDivider(ctx, d));
        root.addView(sLabel(ctx, d, "通知增强"));

        LinearLayout card2 = makeCard(ctx, d);
        card2.addView(switchRow(ctx, d, "通知自定义",
                "快捷回复/头像/优先级", cfg.notifyCustomEnabled, (v, on) -> {
            cfg.notifyCustomEnabled = on; cfg.save(prefs); NotifyCustom.setEnabled(on);
        }, v -> ConfigPanels.showNotifyCustom(act, prefs)));
        root.addView(card2);

        ScrollView sv = new ScrollView(ctx);
        sv.addView(root);
        return sv;
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(2 * d), (int)(2 * d), (int)(2 * d), (int)(2 * d));
        card.setBackgroundColor(AppColors.bg());
        return card;
    }

    private static LinearLayout switchRow(Context ctx, float d, String title, String desc,
                                          boolean checked, CompoundButton.OnCheckedChangeListener l,
                                          View.OnClickListener config) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(AppColors.card());

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView tv = new TextView(ctx);
        tv.setText(title); tv.setTextSize(15);
        tv.setTextColor(AppColors.text1()); tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(ctx);
            dv.setText(desc); dv.setTextSize(12); dv.setTextColor(AppColors.text2());
            dv.setPadding(0, (int)(3 * d), 0, 0);
            textCol.addView(dv);
        }

        row.addView(textCol);

        if (config != null) {
            TextView btn = new TextView(ctx);
            btn.setText("[设置]"); btn.setTextSize(12); btn.setTextColor(AppColors.accent());
            btn.setPadding((int)(6 * d), 0, (int)(6 * d), 0);
            btn.setPaintFlags(btn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            btn.setOnClickListener(config);
            row.addView(btn);
        }

        Switch sw = new Switch(ctx); sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        return row;
    }

    private static TextView sLabel(Context ctx, float d, String t) {
        TextView tv = new TextView(ctx);
        tv.setText(t); tv.setTextSize(13); tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(8 * d));
        return tv;
    }

    private static View candyDivider(Context ctx, float d) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{AppColors.candyPink(), AppColors.candyYellow(), AppColors.accent(), AppColors.candyPink()});
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int)(1.5f * d));
        lp.setMargins((int)(12 * d), (int)(6 * d), (int)(12 * d), (int)(6 * d));
        v.setLayoutParams(lp);
        v.setBackground(gd);
        return v;
    }
}
