package com.leshao.ai.hook.wechat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.leshao.ai.config.AppConfig;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;
import com.leshao.v3.ui.widgets.ModernButton;
import com.leshao.v3.ui.widgets.SectionHeader;
import com.leshao.v3.ui.widgets.SettingRow;

/**
 * AI 助手弹窗(v960): 微信会话页 ⋮ 菜单点击后在微信进程内展示,
 * 复用 v3 Material 3 组件库(SettingRow/ModernButton/SectionHeader)。
 * 快捷开关直接读写 AppConfig 并落盘; 完整设置/白名单经组件名跨进程拉起模块页面。
 */
public final class AiAssistantPanel {

    private static final String TAG = "LeshaoAI.AiAssistantPanel";
    private static final String MODULE_PACKAGE = "com.leshao.v3";
    private static final String SETTINGS_ACTIVITY = "com.leshao.ai.ui.activity.SettingsActivity";
    private static final String WHITELIST_ACTIVITY = "com.leshao.ai.ui.activity.WhitelistActivity";

    private AiAssistantPanel() {
    }

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            Log.w(TAG, "show skipped: activity null/finishing");
            return;
        }
        final Context ctx = activity;
        final float d = ctx.getResources().getDisplayMetrics().density;

        AppConfig cfg = null;
        try {
            cfg = AIBotCore.config();
        } catch (Throwable t) {
            Log.w(TAG, "AIBotCore.config err: " + t);
        }
        final AppConfig config = cfg;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        try {
            root.setBackground(CandyUi.dialogBg(ctx));
        } catch (Throwable ignored) {
        }
        int pad = (int) (20 * d);
        root.setPadding(pad, pad, pad, pad);

        // 标题
        TextView title = new TextView(ctx);
        title.setText("AI 助手");
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(AppColors.textPrimary());
        title.setPadding(0, 0, 0, (int) (4 * d));
        root.addView(title);

        if (config == null) {
            TextView tip = new TextView(ctx);
            tip.setText("AI 核心尚未初始化,请先打开完整设置完成配置。");
            tip.setTextSize(13);
            tip.setTextColor(AppColors.textTertiary());
            tip.setPadding(0, (int) (12 * d), 0, (int) (16 * d));
            root.addView(tip);
        } else {
            ScrollView scroll = new ScrollView(ctx);
            scroll.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            scroll.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
            LinearLayout list = new LinearLayout(ctx);
            list.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(list);
            root.addView(scroll);

            list.addView(new SectionHeader(ctx, "快捷开关", "修改后即时生效"));

            list.addView(new SettingRow(ctx, "🤖", "AI 助手", "总开关,关闭后全部AI能力停用")
                    .switchOn(config.isEnabled(), (btn, checked) -> persist(config, c -> c.setEnabled(checked))));
            list.addView(new SettingRow(ctx, "🔊", "语音播报", "收到消息时朗读AI回复内容")
                    .switchOn(config.isTtsEnabled(), (btn, checked) -> persist(config, c -> c.setTtsEnabled(checked))));
            list.addView(new SettingRow(ctx, "👥", "群聊自动回复", "在白名单群内自动回复")
                    .switchOn(config.isAutoReplyInGroups(), (btn, checked) -> persist(config, c -> c.setAutoReplyInGroups(checked))));
            list.addView(new SettingRow(ctx, "💬", "私聊自动回复", "对白名单联系人自动回复")
                    .switchOn(config.isAutoReplyInPrivate(), (btn, checked) -> persist(config, c -> c.setAutoReplyInPrivate(checked))));
            list.addView(new SettingRow(ctx, "📣", "仅被@时回复", "群聊中只有被提到时才回复")
                    .switchOn(config.isOnlyWhenMentioned(), (btn, checked) -> persist(config, c -> c.setOnlyWhenMentioned(checked))));
        }

        final AlertDialog[] holder = new AlertDialog[1];

        // 底部按钮行: 完整设置 / 白名单 / 关闭
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnLp.setMargins(0, (int) (16 * d), 0, 0);

        ModernButton btnSettings = new ModernButton(ctx, "完整设置", ModernButton.STYLE_GHOST);
        btnSettings.setLayoutParams(btnLp);
        btnSettings.onClick(() -> {
            dismissQuietly(holder);
            launch(ctx, SETTINGS_ACTIVITY);
        });
        btnRow.addView(btnSettings);

        ModernButton btnWhitelist = new ModernButton(ctx, "白名单", ModernButton.STYLE_GHOST);
        LinearLayout.LayoutParams lpWl = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpWl.setMargins((int) (8 * d), (int) (16 * d), (int) (8 * d), 0);
        btnWhitelist.setLayoutParams(lpWl);
        btnWhitelist.onClick(() -> {
            dismissQuietly(holder);
            launch(ctx, WHITELIST_ACTIVITY);
        });
        btnRow.addView(btnWhitelist);

        ModernButton btnClose = new ModernButton(ctx, "关闭", ModernButton.STYLE_PRIMARY);
        btnClose.setLayoutParams(btnLp);
        btnRow.addView(btnClose);
        root.addView(btnRow);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setView(root)
                .setCancelable(true)
                .create();
        btnClose.onClick(dialog::dismiss);
        holder[0] = dialog;
        try {
            dialog.show();
        } catch (Throwable t) {
            Log.w(TAG, "dialog show FAILED: " + t);
        }
    }

    private interface ConfigMutator {
        void apply(AppConfig c);
    }

    private static void persist(AppConfig config, ConfigMutator mutator) {
        try {
            mutator.apply(config);
            boolean ok = config.save();
            Log.i(TAG, "persist ok=" + ok);
        } catch (Throwable t) {
            Log.w(TAG, "persist err: " + t);
        }
    }

    private static void dismissQuietly(AlertDialog[] holder) {
        try {
            if (holder[0] != null && holder[0].isShowing()) {
                holder[0].dismiss();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void launch(Context ctx, String cls) {
        try {
            Intent i = new Intent();
            i.setClassName(MODULE_PACKAGE, cls);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "launch " + cls + " FAILED: " + t);
        }
    }
}
