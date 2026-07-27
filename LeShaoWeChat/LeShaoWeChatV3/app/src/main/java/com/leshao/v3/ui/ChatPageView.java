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

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.*;
import com.leshao.v3.model.ModuleConfig;

public class ChatPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;
        final SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);
        final Activity act = parentAct;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());
        root.setPadding((int)(8 * d), (int)(16 * d), (int)(8 * d), (int)(16 * d));

        boolean recallOn = prefs != null && prefs.getBoolean("ls_recall_enabled", false);
        boolean vfOn = prefs != null && prefs.getBoolean("ls_voice_forward", false);

        root.addView(sectionLabel(ctx, "核心功能"));

        LinearLayout cardCore = makeCard(ctx, d);
        cardCore.addView(switchRow(ctx, d, "消息防撤回", null, recallOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean("ls_recall_enabled", on).apply();
            AntiRecallHook.setEnabled(on);
        }, null));
        cardCore.addView(switchRow(ctx, d, "自动关键词回复", null, cfg.autoReplyEnabled, (v, on) -> {
            cfg.autoReplyEnabled = on; cfg.save(prefs); AutoReplyHook.setEnabled(on);
        }, v -> ConfigPanels.showAutoReply(act, prefs)));
        cardCore.addView(switchRow(ctx, d, "语音消息转发", null, vfOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean("ls_voice_forward", on).apply();
            VoiceForwardHook.setEnabled(on);
        }, null));
        root.addView(cardCore);

        root.addView(spacerV(ctx, d, 12));
        root.addView(sectionLabel(ctx, "聊天增强"));

        LinearLayout cardChat = makeCard(ctx, d);
        cardChat.addView(switchRow(ctx, d, "对方正在输入提示", null, cfg.typingIndicatorEnabled, (v, on) -> {
            cfg.typingIndicatorEnabled = on; cfg.save(prefs); TypingIndicator.setEnabled(on);
        }, v -> ConfigPanels.showTypingIndicator(act, prefs)));
        cardChat.addView(switchRow(ctx, d, "底部栏功能增强", null, cfg.chatFooterEnhanceEnabled, (v, on) -> {
            cfg.chatFooterEnhanceEnabled = on; cfg.save(prefs); ChatFooterEnhance.setEnabled(on);
        }, v -> ConfigPanels.showChatFooterEnhance(act, prefs)));
        cardChat.addView(switchRow(ctx, d, "聊天界面UI定制", null, cfg.chatUICustomEnabled, (v, on) -> {
            cfg.chatUICustomEnabled = on; cfg.save(prefs); ChatUICustom.setEnabled(on);
        }, v -> ConfigPanels.showChatUICustom(act, prefs)));
        cardChat.addView(switchRow(ctx, d, "批量群发消息", null, cfg.batchMessageEnabled, (v, on) -> {
            cfg.batchMessageEnabled = on; cfg.save(prefs); BatchMessage.setEnabled(on);
        }, v -> ConfigPanels.showBatchMessage(act, prefs)));
        cardChat.addView(switchRow(ctx, d, "定时发送消息", null, cfg.scheduledSendEnabled, (v, on) -> {
            cfg.scheduledSendEnabled = on; cfg.save(prefs); ScheduledSend.setEnabled(on);
        }, v -> ConfigPanels.showScheduledSend(act, prefs)));
        cardChat.addView(switchRow(ctx, d, "自动设置好友备注", null, cfg.autoRemarkEnabled, (v, on) -> {
            cfg.autoRemarkEnabled = on; cfg.save(prefs); AutoRemark.setEnabled(on);
        }, v -> ConfigPanels.showAutoRemark(act, prefs)));
        cardChat.addView(switchRow(ctx, d, "搜索功能增强", null, cfg.searchEnhanceEnabled, (v, on) -> {
            cfg.searchEnhanceEnabled = on; cfg.save(prefs); SearchEnhance.setEnabled(on);
        }, v -> ConfigPanels.showSearchEnhance(act, prefs)));
        cardChat.addView(switchRow(ctx, d, "消息通知自定义", null, cfg.notifyCustomEnabled, (v, on) -> {
            cfg.notifyCustomEnabled = on; cfg.save(prefs); NotifyCustom.setEnabled(on);
        }, v -> ConfigPanels.showNotifyCustom(act, prefs)));
        root.addView(cardChat);

        root.addView(spacerV(ctx, d, 12));
        root.addView(sectionLabel(ctx, "会话与界面"));

        LinearLayout cardUI = makeCard(ctx, d);
        cardUI.addView(switchRow(ctx, d, "好友删除检测", null, cfg.deleteDetectEnabled, (v, on) -> {
            cfg.deleteDetectEnabled = on; cfg.save(prefs); DeleteDetect.setEnabled(on);
        }, null));
        cardUI.addView(switchRow(ctx, d, "置顶增强", null, cfg.stickyEnhanceEnabled, (v, on) -> {
            cfg.stickyEnhanceEnabled = on; cfg.save(prefs); StickyEnhance.setEnabled(on);
        }, v -> ConfigPanels.showStickyEnhance(act, prefs)));
        cardUI.addView(switchRow(ctx, d, "未读消息角标", null, cfg.unreadBadgeEnabled, (v, on) -> {
            cfg.unreadBadgeEnabled = on; cfg.save(prefs); UnreadBadge.setEnabled(on);
        }, v -> ConfigPanels.showUnreadBadge(act, prefs)));
        cardUI.addView(switchRow(ctx, d, "底部Tab自定义", null, cfg.tabCustomEnabled, (v, on) -> {
            cfg.tabCustomEnabled = on; cfg.save(prefs); TabCustom.setEnabled(on);
        }, v -> ConfigPanels.showTabCustom(act, prefs)));
        cardUI.addView(switchRow(ctx, d, "摇一摇自定义", null, cfg.shakeCustomEnabled, (v, on) -> {
            cfg.shakeCustomEnabled = on; cfg.save(prefs); ShakeCustom.setEnabled(on);
        }, v -> ConfigPanels.showShakeCustom(act, prefs)));
        root.addView(cardUI);

        root.addView(spacerV(ctx, d, 12));
        root.addView(sectionLabel(ctx, "通话与录音"));

        LinearLayout cardCall = makeCard(ctx, d);
        cardCall.addView(switchRow(ctx, d, "通话录音与自动接听", null, cfg.callFeaturesEnabled, (v, on) -> {
            cfg.callFeaturesEnabled = on; cfg.save(prefs); CallFeatures.setEnabled(on);
        }, v -> ConfigPanels.showCallFeatures(act, prefs)));
        root.addView(cardCall);

        return root;
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(2 * d), (int)(2 * d), (int)(2 * d), (int)(2 * d));
        card.setBackgroundColor(AppColors.card());
        return card;
    }

    private static LinearLayout switchRow(Context ctx, float d, String title, String desc,
                                           boolean checked, CompoundButton.OnCheckedChangeListener listener,
                                           View.OnClickListener configListener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(AppColors.whiteCard());

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(AppColors.text1());
        tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(ctx);
            dv.setText(desc);
            dv.setTextSize(12);
            dv.setTextColor(AppColors.text2());
            dv.setPadding(0, (int)(3 * d), 0, 0);
            textCol.addView(dv);
        }

        row.addView(textCol);

        if (configListener != null) {
            TextView btn = new TextView(ctx);
            btn.setText("[设置]");
            btn.setTextSize(12);
            btn.setTextColor(0xFF4A90D9);
            btn.setPadding((int)(6 * d), 0, (int)(6 * d), 0);
            btn.setOnClickListener(configListener);
            row.addView(btn);
        }

        Switch sw = new Switch(ctx);
        sw.setChecked(checked);
        try {
            if (checked) sw.setThumbResource(android.R.drawable.btn_star_big_on);
        } catch (Throwable ignored) {}
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);

        return row;
    }

    private static TextView sectionLabel(Context ctx, String text) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(8 * d));
        return tv;
    }

    private static View spacerV(Context ctx, float d, int dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dp * d)));
        return v;
    }
}
