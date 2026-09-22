package com.leshao.ai.hook.wechat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.leshao.ai.api.model.ProviderType;
import com.leshao.ai.config.AppConfig;
import com.leshao.ai.util.Whitelist;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;
import com.leshao.v3.ui.widgets.ModernButton;
import com.leshao.v3.ui.widgets.M3Page;
import com.leshao.v3.ui.widgets.SectionHeader;
import com.leshao.v3.ui.widgets.SettingRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI 助手弹窗(v961): 微信会话页 ⋮ 菜单点击后在微信进程内展示,
 * 复用 v3 Material 3 组件库(SettingRow/ModernButton/SectionHeader/M3Page)。
 *
 * <p>v961: 完整设置/白名单不再跨进程 startActivity(v960 跳桌面/界面乱的根因),
 * 改为微信进程内自绘 M3 弹窗页; 配置读写 AIBotCore 持有的单例, 保存后 reload 生效。</p>
 */
public final class AiAssistantPanel {

    private static final String TAG = "LeshaoAI.AiAssistantPanel";
    /** SeekBar 进度 → 温度(0..200 → 0.0..2.0) */
    private static final double TEMP_SCALE = 100.0 / 2.0;

    private AiAssistantPanel() {
    }

    // ==================== 主弹窗(快捷开关) ====================

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
            // v961: 微信进程内自绘完整设置弹窗, 不再跨进程拉模块 Activity
            showSettings(activity);
        });
        btnRow.addView(btnSettings);

        ModernButton btnWhitelist = new ModernButton(ctx, "白名单", ModernButton.STYLE_GHOST);
        LinearLayout.LayoutParams lpWl = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpWl.setMargins((int) (8 * d), (int) (16 * d), (int) (8 * d), 0);
        btnWhitelist.setLayoutParams(lpWl);
        btnWhitelist.onClick(() -> {
            dismissQuietly(holder);
            showWhitelist(activity);
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

    // ==================== 完整设置(v961 内嵌) ====================

    private static void showSettings(final Activity activity) {
        final Context ctx = activity;
        final float d = ctx.getResources().getDisplayMetrics().density;

        final AppConfig config;
        try {
            config = AIBotCore.config();
        } catch (Throwable t) {
            Log.w(TAG, "showSettings config err: " + t);
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }
        if (config == null) {
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        try {
            root.setBackground(CandyUi.dialogBg(ctx));
        } catch (Throwable ignored) {
        }
        int pad = (int) (20 * d);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText("AI 完整设置");
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(AppColors.textPrimary());
        title.setPadding(0, 0, 0, (int) (4 * d));
        root.addView(title);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll);

        // ---- 服务商 ----
        list.addView(new SectionHeader(ctx, "服务商", "API 协议类型"));
        final SettingRow[] providerRows = new SettingRow[ProviderType.values().length];
        final String currentProvider = config.getProviderType();
        ProviderType[] types = ProviderType.values();
        for (int i = 0; i < types.length; i++) {
            final ProviderType pt = types[i];
            boolean sel = pt.name().toLowerCase(Locale.US).equals(currentProvider);
            SettingRow row = new SettingRow(ctx, "☁", providerLabel(pt), sel ? "当前" : "点击选择");
            providerRows[i] = row;
            row.arrow(() -> {
                try {
                    config.setProviderType(pt.name().toLowerCase(Locale.US));
                    for (int j = 0; j < providerRows.length; j++) {
                        providerRows[j].setSub(j == pt.ordinal() ? "当前" : "点击选择");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "setProviderType err: " + t);
                }
            });
            list.addView(row);
        }

        // ---- 接口 ----
        list.addView(new SectionHeader(ctx, "接口", "服务商提供的接入信息"));
        final EditText etBaseUrl = M3Page.input(ctx, "Base URL, 如 https://api.openai.com/v1");
        etBaseUrl.setText(safe(config.getBaseUrl()));
        list.addView(etBaseUrl);
        final EditText etApiKey = M3Page.input(ctx, "API Key");
        etApiKey.setText(safe(config.getApiKey()));
        list.addView(etApiKey);
        final EditText etModel = M3Page.input(ctx, "模型, 如 gpt-4o-mini");
        etModel.setText(safe(config.getModel()));
        list.addView(etModel);

        // ---- 生成参数 ----
        list.addView(new SectionHeader(ctx, "生成参数", "温度越高回复越随机"));
        final TextView tvTemp = new TextView(ctx);
        tvTemp.setTextSize(14);
        tvTemp.setTextColor(AppColors.textTertiary());
        tvTemp.setPadding(0, (int) (4 * d), 0, (int) (2 * d));
        list.addView(tvTemp);
        final SeekBar seekTemp = new SeekBar(ctx);
        seekTemp.setMax(200);
        list.addView(seekTemp);
        int initProgress = Math.max(0, Math.min(200, (int) Math.round(config.getTemperature() * TEMP_SCALE)));
        seekTemp.setProgress(initProgress);
        updateTempLabel(tvTemp, seekTemp.getProgress());
        seekTemp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateTempLabel(tvTemp, progress);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // ---- 人设与触发 ----
        list.addView(new SectionHeader(ctx, "人设与触发", "机器人身份与唤醒规则"));
        final EditText etBotName = M3Page.input(ctx, "机器人名");
        etBotName.setText(safe(config.getBotName()));
        list.addView(etBotName);
        final EditText etWakeKeyword = M3Page.input(ctx, "唤醒词");
        etWakeKeyword.setText(safe(config.getWakeKeyword()));
        list.addView(etWakeKeyword);
        final EditText etSystemPrompt = M3Page.input(ctx, "系统人设提示词");
        etSystemPrompt.setSingleLine(false);
        etSystemPrompt.setMinLines(3);
        etSystemPrompt.setGravity(Gravity.TOP);
        etSystemPrompt.setText(safe(config.getSystemPrompt()));
        list.addView(etSystemPrompt);

        // ---- 回复策略 ----
        list.addView(new SectionHeader(ctx, "回复策略", "自动回复与播报"));
        list.addView(new SettingRow(ctx, "📣", "仅被@时回复", "群聊中只有被提到时才回复")
                .switchOn(config.isOnlyWhenMentioned(), (btn, checked) -> config.setOnlyWhenMentioned(checked)));
        list.addView(new SettingRow(ctx, "🔊", "语音播报", "收到消息时朗读AI回复内容")
                .switchOn(config.isTtsEnabled(), (btn, checked) -> config.setTtsEnabled(checked)));
        list.addView(new SettingRow(ctx, "👥", "群聊自动回复", "在白名单群内自动回复")
                .switchOn(config.isAutoReplyInGroups(), (btn, checked) -> config.setAutoReplyInGroups(checked)));
        list.addView(new SettingRow(ctx, "💬", "私聊自动回复", "对白名单联系人自动回复")
                .switchOn(config.isAutoReplyInPrivate(), (btn, checked) -> config.setAutoReplyInPrivate(checked)));
        final EditText etMemory = M3Page.input(ctx, "记忆条数(上下文消息数)");
        etMemory.setText(String.valueOf(config.getMaxHistoryMessages()));
        list.addView(etMemory);

        // ---- 底部按钮 ----
        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnLp.setMargins(0, (int) (16 * d), 0, 0);

        ModernButton btnSave = new ModernButton(ctx, "保存", ModernButton.STYLE_PRIMARY);
        btnSave.setLayoutParams(btnLp);
        btnSave.onClick(() -> {
            collectAndSave(config, etBaseUrl, etApiKey, etModel, etBotName, etWakeKeyword,
                    etSystemPrompt, etMemory, seekTemp);
            dismissQuietly(holder);
            toastQuiet(ctx, "已保存");
        });
        btnRow.addView(btnSave);

        ModernButton btnReset = new ModernButton(ctx, "恢复默认", ModernButton.STYLE_GHOST);
        LinearLayout.LayoutParams lpR = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpR.setMargins((int) (8 * d), (int) (16 * d), (int) (8 * d), 0);
        btnReset.setLayoutParams(lpR);
        btnReset.onClick(() -> {
            try {
                config.reset();
                config.save();
                AIBotCore.reload();
                toastQuiet(ctx, "已恢复默认");
                dismissQuietly(holder);
            } catch (Throwable t) {
                Log.w(TAG, "reset err: " + t);
            }
        });
        btnRow.addView(btnReset);

        ModernButton btnClose = new ModernButton(ctx, "关闭", ModernButton.STYLE_GHOST);
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
            Log.w(TAG, "settings dialog show FAILED: " + t);
        }
    }

    private static void collectAndSave(AppConfig config, EditText etBaseUrl, EditText etApiKey,
                                       EditText etModel, EditText etBotName, EditText etWakeKeyword,
                                       EditText etSystemPrompt, EditText etMemory, SeekBar seekTemp) {
        try {
            config.setBaseUrl(str(etBaseUrl));
            config.setApiKey(str(etApiKey));
            config.setModel(str(etModel));
            config.setBotName(str(etBotName));
            config.setWakeKeyword(str(etWakeKeyword));
            config.setSystemPrompt(str(etSystemPrompt));
            config.setTemperature(seekTemp.getProgress() / TEMP_SCALE);
            String mem = str(etMemory);
            if (!TextUtils.isEmpty(mem)) {
                try {
                    config.setMaxHistoryMessages(Integer.parseInt(mem.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
            boolean ok = config.save();
            Log.i(TAG, "settings saved ok=" + ok);
            try {
                AIBotCore.reload();
            } catch (Throwable t) {
                Log.w(TAG, "reload after save err: " + t);
            }
        } catch (Throwable t) {
            Log.w(TAG, "collectAndSave err: " + t);
        }
    }

    // ==================== 白名单(v961 内嵌) ====================

    private static void showWhitelist(final Activity activity) {
        final Context ctx = activity;
        final float d = ctx.getResources().getDisplayMetrics().density;

        final Whitelist wl;
        try {
            wl = AIBotCore.whitelist() != null ? AIBotCore.whitelist()
                    : new Whitelist(ctx.getFilesDir().getParent());
        } catch (Throwable t) {
            Log.w(TAG, "whitelist err: " + t);
            toastQuiet(ctx, "白名单加载失败");
            return;
        }
        try {
            wl.load();
        } catch (Throwable ignored) {
        }

        final List<String> items = new ArrayList<>(wl.list());
        final List<String> labels = new ArrayList<>();
        rebuildLabels(items, labels);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        try {
            root.setBackground(CandyUi.dialogBg(ctx));
        } catch (Throwable ignored) {
        }
        int pad = (int) (20 * d);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText("白名单管理");
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(AppColors.textPrimary());
        root.addView(title);

        TextView tip = new TextView(ctx);
        tip.setText("名单非空时, AI 仅响应名单内会话(wxid / 群 id); 点击条目删除。");
        tip.setTextSize(12);
        tip.setTextColor(AppColors.textTertiary());
        tip.setPadding(0, (int) (4 * d), 0, (int) (8 * d));
        root.addView(tip);

        LinearLayout addRow = new LinearLayout(ctx);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText etAdd = M3Page.input(ctx, "输入 talker / 群 id");
        etAdd.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addRow.addView(etAdd);
        ModernButton btnAdd = new ModernButton(ctx, "添加", ModernButton.STYLE_PRIMARY);
        btnAdd.onClick(() -> {
            String id = str(etAdd);
            if (TextUtils.isEmpty(id)) {
                toastQuiet(ctx, "请输入 talker");
                return;
            }
            try {
                wl.add(id);
                wl.save();
                items.add(id);
                rebuildLabels(items, labels);
                etAdd.setText("");
                toastQuiet(ctx, "已添加 " + id);
            } catch (Throwable t) {
                Log.w(TAG, "wl add err: " + t);
            }
        });
        addRow.addView(btnAdd);
        root.addView(addRow);

        ListView listView = new ListView(ctx);
        listView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(ctx,
                android.R.layout.simple_list_item_1, labels);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= items.size()) return;
            final String removed = items.get(position);
            try {
                wl.remove(removed);
                wl.save();
                items.remove(position);
                rebuildLabels(items, labels);
                toastQuiet(ctx, "已移除 " + removed);
            } catch (Throwable t) {
                Log.w(TAG, "wl remove err: " + t);
            }
        });
        root.addView(listView);

        final AlertDialog[] holder = new AlertDialog[1];
        ModernButton btnClose = new ModernButton(ctx, "关闭", ModernButton.STYLE_GHOST);
        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpC.setMargins(0, (int) (12 * d), 0, 0);
        btnClose.setLayoutParams(lpC);
        root.addView(btnClose);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setView(root)
                .setCancelable(true)
                .create();
        btnClose.onClick(dialog::dismiss);
        holder[0] = dialog;
        try {
            dialog.show();
        } catch (Throwable t) {
            Log.w(TAG, "whitelist dialog show FAILED: " + t);
        }
    }

    private static void rebuildLabels(List<String> items, List<String> labels) {
        labels.clear();
        for (String s : items) {
            labels.add(s + (s != null && s.endsWith("@chatroom") ? "  (群)" : ""));
        }
    }

    // ==================== 工具 ====================

    private interface ConfigMutator {
        void apply(AppConfig c);
    }

    private static void persist(AppConfig config, ConfigMutator mutator) {
        try {
            mutator.apply(config);
            boolean ok = config.save();
            Log.i(TAG, "persist ok=" + ok);
            try {
                AIBotCore.reload();
            } catch (Throwable t) {
                Log.w(TAG, "reload err: " + t);
            }
        } catch (Throwable t) {
            Log.w(TAG, "persist err: " + t);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String str(EditText et) {
        return et.getText() == null ? "" : et.getText().toString();
    }

    private static void updateTempLabel(TextView tv, int progress) {
        tv.setText("温度: " + String.format(Locale.US, "%.1f", progress / TEMP_SCALE));
    }

    private static String providerLabel(ProviderType pt) {
        if (pt == null) return "未知";
        switch (pt) {
            case OPENAI_RESPONSES:
                return "OpenAI Responses";
            case ANTHROPIC:
                return "Anthropic Claude";
            case OPENAI_CHAT:
            default:
                return "OpenAI Chat";
        }
    }

    private static void toastQuiet(Context ctx, String msg) {
        try {
            M3Page.toast(ctx, msg);
        } catch (Throwable ignored) {
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
}
