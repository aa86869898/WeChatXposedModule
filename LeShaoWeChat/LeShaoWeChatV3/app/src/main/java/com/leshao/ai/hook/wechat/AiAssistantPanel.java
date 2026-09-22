package com.leshao.ai.hook.wechat;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.leshao.ai.api.model.ProviderType;
import com.leshao.ai.config.AppConfig;
import com.leshao.ai.util.Whitelist;
import com.leshao.v3.InstanceManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;
import com.leshao.v3.ui.widgets.ModernButton;
import com.leshao.v3.ui.widgets.M3Page;
import com.leshao.v3.ui.widgets.SectionHeader;
import com.leshao.v3.ui.widgets.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 助手弹窗(v962): 微信会话页 ⋮ 菜单点击后在微信进程内展示。
 *
 * <p>v961 用 AlertDialog 在微信进程内显示不可靠(主题/token/触摸不确定性, 实机日志无任何点击痕迹);
 * v962 改用 PopupWindow —— 与 ChatFooterLongPressMenu 音频面板同机制, 微信 3180 实测可显示可交互。
 * 所有入口与按钮均写 LogWriter 日志 + Toast 反馈, 下次实机日志可完整定位点击链路。</p>
 *
 * <p>v962: 白名单删除改两步确认(首次点击标记, 再次点击删除), 防误删。</p>
 */
public final class AiAssistantPanel {

    private static final String TAG = "LeshaoAI.AiAssistantPanel";
    /** SeekBar 进度 → 温度(0..200 → 0.0..2.0) */
    private static final double TEMP_SCALE = 100.0 / 2.0;
    /** 当前打开的弹窗, 用于面板间切换时先关旧窗 */
    private static volatile PopupWindow sPopup;

    private AiAssistantPanel() {
    }

    // ==================== 弹窗基础设施(v962 PopupWindow) ====================

    /** 取弹窗锚点: 优先 decorView, 其次 contentView; 均不可用返回 null */
    private static View resolveAnchor(Activity activity) {
        if (activity == null || activity.isFinishing()) return null;
        try {
            View decor = activity.getWindow().peekDecorView();
            if (decor != null) return decor;
        } catch (Throwable ignored) {}
        try {
            View content = activity.findViewById(android.R.id.content);
            if (content != null) return content;
        } catch (Throwable ignored) {}
        return null;
    }

    /** 关闭当前弹窗(静默) */
    private static void dismissCurrent() {
        try {
            PopupWindow pw = sPopup;
            sPopup = null;
            if (pw != null && pw.isShowing()) pw.dismiss();
        } catch (Throwable ignored) {}
    }

    /**
     * 以 PopupWindow 居中展示面板。构建/展示全链路 try-catch + LogWriter 日志,
     * 任何阶段失败只记日志不抛泡到菜单点击(避免"点了没反应"且无迹可查)。
     *
     * @param heightPx 弹窗高度(像素), <=0 表示 WRAP_CONTENT
     */
    private static void showPopup(Activity activity, View root, int heightPx, String scene) {
        try {
            LogWriter.log(TAG, "showPopup: scene=" + scene + " h=" + heightPx);
            View anchor = resolveAnchor(activity);
            if (anchor == null) {
                LogWriter.log(TAG, "showPopup FAILED: anchor null (activity="
                        + (activity == null ? "null" : activity.getClass().getName()) + ")");
                toastQuiet(activity, "当前页面无法显示弹窗");
                return;
            }
            dismissCurrent();

            android.util.DisplayMetrics dm = anchor.getResources().getDisplayMetrics();
            int panelW = (int) (dm.widthPixels * 0.85f);
            int h = heightPx > 0 ? heightPx : ViewGroup.LayoutParams.WRAP_CONTENT;
            PopupWindow pw = new PopupWindow(root, panelW, h, true);
            pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            try { pw.setElevation(dp(anchor.getContext(), 8)); } catch (Throwable ignored) {}
            pw.setOutsideTouchable(true);
            pw.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            sPopup = pw;
            try {
                pw.showAtLocation(anchor, Gravity.CENTER, 0, 0);
                LogWriter.log(TAG, "showPopup OK: scene=" + scene);
            } catch (Throwable t) {
                sPopup = null;
                LogWriter.log(TAG, "showPopup showAtLocation FAILED: scene=" + scene
                        + " err=" + android.util.Log.getStackTraceString(t));
                toastQuiet(activity, "弹窗显示失败");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "showPopup err: scene=" + scene + " err=" + t);
        }
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static LinearLayout newRoot(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        try {
            root.setBackground(CandyUi.dialogBg(ctx));
        } catch (Throwable ignored) {}
        int pad = dp(ctx, 20);
        root.setPadding(pad, pad, pad, pad);
        return root;
    }

    private static TextView newTitle(Context ctx, String text) {
        TextView title = new TextView(ctx);
        title.setText(text);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(AppColors.textPrimary());
        title.setPadding(0, 0, 0, dp(ctx, 4));
        return title;
    }

    /** 底部三按钮行(等宽) */
    private static LinearLayout newBtnRow(Context ctx, ModernButton left, ModernButton mid, ModernButton right) {
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lpSide = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpSide.setMargins(0, dp(ctx, 16), 0, 0);
        LinearLayout.LayoutParams lpMid = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpMid.setMargins(dp(ctx, 8), dp(ctx, 16), dp(ctx, 8), 0);
        left.setLayoutParams(lpSide);
        mid.setLayoutParams(lpMid);
        right.setLayoutParams(lpSide);
        btnRow.addView(left);
        btnRow.addView(mid);
        btnRow.addView(right);
        return btnRow;
    }

    // ==================== 主弹窗(快捷开关) ====================

    public static void show(Activity activity) {
        LogWriter.log(TAG, "show: enter activity="
                + (activity == null ? "null" : activity.getClass().getName()));
        Log.i(TAG, "show: enter");
        if (activity == null || activity.isFinishing()) {
            LogWriter.log(TAG, "show skipped: activity null/finishing");
            toastQuiet(activity, "当前页面已关闭");
            return;
        }
        final Context ctx = activity;

        AppConfig cfg = null;
        try {
            cfg = AIBotCore.config();
        } catch (Throwable t) {
            LogWriter.log(TAG, "AIBotCore.config err: " + t);
        }
        final AppConfig config = cfg;

        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, "AI 助手"));

        if (config == null) {
            LogWriter.log(TAG, "show: config null, 仅展示提示");
            TextView tip = new TextView(ctx);
            tip.setText("AI 核心尚未初始化,请先打开完整设置完成配置。");
            tip.setTextSize(13);
            tip.setTextColor(AppColors.textTertiary());
            tip.setPadding(0, dp(ctx, 12), 0, dp(ctx, 16));
            root.addView(tip);
        } else {
            ScrollView scroll = new ScrollView(ctx);
            scroll.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            scroll.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
            LinearLayout list = new LinearLayout(ctx);
            list.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(list);
            root.addView(scroll);

            list.addView(new SectionHeader(ctx, "快捷开关", "修改后即时生效"));

            list.addView(new SettingRow(ctx, "🤖", "AI 助手", "总开关,关闭后全部AI能力停用")
                    .switchOn(config.isEnabled(), (btn, checked) -> {
                        LogWriter.log(TAG, "click: AI助手总开关 -> " + checked);
                        persist(ctx, config, c -> c.setEnabled(checked), "AI助手已" + (checked ? "开启" : "关闭"));
                    }));
            list.addView(new SettingRow(ctx, "🔊", "语音播报", "收到消息时朗读AI回复内容")
                    .switchOn(config.isTtsEnabled(), (btn, checked) -> {
                        LogWriter.log(TAG, "click: 语音播报 -> " + checked);
                        persist(ctx, config, c -> c.setTtsEnabled(checked), "语音播报已" + (checked ? "开启" : "关闭"));
                    }));
            list.addView(new SettingRow(ctx, "👥", "群聊自动回复", "在白名单群内自动回复")
                    .switchOn(config.isAutoReplyInGroups(), (btn, checked) -> {
                        LogWriter.log(TAG, "click: 群聊自动回复 -> " + checked);
                        persist(ctx, config, c -> c.setAutoReplyInGroups(checked), "群聊自动回复已" + (checked ? "开启" : "关闭"));
                    }));
            list.addView(new SettingRow(ctx, "💬", "私聊自动回复", "对白名单联系人自动回复")
                    .switchOn(config.isAutoReplyInPrivate(), (btn, checked) -> {
                        LogWriter.log(TAG, "click: 私聊自动回复 -> " + checked);
                        persist(ctx, config, c -> c.setAutoReplyInPrivate(checked), "私聊自动回复已" + (checked ? "开启" : "关闭"));
                    }));
            list.addView(new SettingRow(ctx, "📣", "仅被@时回复", "群聊中只有被提到时才回复")
                    .switchOn(config.isOnlyWhenMentioned(), (btn, checked) -> {
                        LogWriter.log(TAG, "click: 仅被@时回复 -> " + checked);
                        persist(ctx, config, c -> c.setOnlyWhenMentioned(checked), "已更新@回复规则");
                    }));

            // v962: 实例信息(主微信/分身隔离状态), 与任务2 InstanceManager 联动
            list.addView(new SectionHeader(ctx, "当前微信实例", "主微信与分身配置互相独立"));
            try {
                int userId = InstanceManager.userId();
                boolean primary = InstanceManager.isPrimary();
                boolean enabled = InstanceManager.isEnabled();
                list.addView(new SettingRow(ctx, "🧩", "实例",
                        primary ? "主微信 (user 0)" : ("系统分身 (user " + userId + ")")));
                list.addView(new SettingRow(ctx, "⚡", "本实例模块开关",
                        enabled ? "已开启,重启微信后生效" : "已关闭,重启微信后不再加载")
                        .switchOn(enabled, (btn, checked) -> {
                            LogWriter.log(TAG, "click: 实例模块开关 userId=" + userId + " -> " + checked);
                            try {
                                InstanceManager.setEnabled(checked);
                                toastQuiet(ctx, checked ? "本实例已开启(重启生效)" : "本实例已关闭(重启生效)");
                            } catch (Throwable t) {
                                LogWriter.log(TAG, "InstanceManager.setEnabled err: " + t);
                                toastQuiet(ctx, "开关写入失败");
                            }
                        }));
            } catch (Throwable t) {
                LogWriter.log(TAG, "instance info err: " + t);
            }
        }

        ModernButton btnSettings = new ModernButton(ctx, "完整设置", ModernButton.STYLE_GHOST);
        btnSettings.onClick(() -> {
            LogWriter.log(TAG, "click: 完整设置");
            dismissCurrent();
            showSettings(activity);
        });

        ModernButton btnWhitelist = new ModernButton(ctx, "白名单", ModernButton.STYLE_GHOST);
        btnWhitelist.onClick(() -> {
            LogWriter.log(TAG, "click: 白名单");
            dismissCurrent();
            showWhitelist(activity);
        });

        ModernButton btnClose = new ModernButton(ctx, "关闭", ModernButton.STYLE_PRIMARY);
        btnClose.onClick(() -> {
            LogWriter.log(TAG, "click: 关闭");
            dismissCurrent();
        });

        root.addView(newBtnRow(ctx, btnSettings, btnWhitelist, btnClose));
        showPopup(activity, root, 0, "main");
    }

    // ==================== 完整设置(v962 内嵌) ====================

    private static void showSettings(final Activity activity) {
        LogWriter.log(TAG, "showSettings: enter");
        if (activity == null || activity.isFinishing()) {
            LogWriter.log(TAG, "showSettings skipped: activity null/finishing");
            return;
        }
        final Context ctx = activity;

        final AppConfig config;
        try {
            config = AIBotCore.config();
        } catch (Throwable t) {
            LogWriter.log(TAG, "showSettings config err: " + t);
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }
        if (config == null) {
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }

        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, "AI 完整设置"));

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
            boolean sel = pt.name().toLowerCase(java.util.Locale.US).equals(currentProvider);
            SettingRow row = new SettingRow(ctx, "☁", providerLabel(pt), sel ? "当前" : "点击选择");
            providerRows[i] = row;
            row.arrow(() -> {
                LogWriter.log(TAG, "click: 服务商 -> " + pt.name());
                try {
                    config.setProviderType(pt.name().toLowerCase(java.util.Locale.US));
                    for (int j = 0; j < providerRows.length; j++) {
                        providerRows[j].setSub(j == pt.ordinal() ? "当前" : "点击选择");
                    }
                    toastQuiet(ctx, "已选择 " + providerLabel(pt));
                } catch (Throwable t) {
                    LogWriter.log(TAG, "setProviderType err: " + t);
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
        tvTemp.setPadding(0, dp(ctx, 4), 0, dp(ctx, 2));
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
                .switchOn(config.isOnlyWhenMentioned(), (btn, checked) -> {
                    LogWriter.log(TAG, "click(设置页): 仅被@时回复 -> " + checked);
                    config.setOnlyWhenMentioned(checked);
                }));
        list.addView(new SettingRow(ctx, "🔊", "语音播报", "收到消息时朗读AI回复内容")
                .switchOn(config.isTtsEnabled(), (btn, checked) -> {
                    LogWriter.log(TAG, "click(设置页): 语音播报 -> " + checked);
                    config.setTtsEnabled(checked);
                }));
        list.addView(new SettingRow(ctx, "👥", "群聊自动回复", "在白名单群内自动回复")
                .switchOn(config.isAutoReplyInGroups(), (btn, checked) -> {
                    LogWriter.log(TAG, "click(设置页): 群聊自动回复 -> " + checked);
                    config.setAutoReplyInGroups(checked);
                }));
        list.addView(new SettingRow(ctx, "💬", "私聊自动回复", "对白名单联系人自动回复")
                .switchOn(config.isAutoReplyInPrivate(), (btn, checked) -> {
                    LogWriter.log(TAG, "click(设置页): 私聊自动回复 -> " + checked);
                    config.setAutoReplyInPrivate(checked);
                }));
        final EditText etMemory = M3Page.input(ctx, "记忆条数(上下文消息数)");
        etMemory.setText(String.valueOf(config.getMaxHistoryMessages()));
        list.addView(etMemory);

        // ---- 底部按钮 ----
        ModernButton btnSave = new ModernButton(ctx, "保存", ModernButton.STYLE_PRIMARY);
        btnSave.onClick(() -> {
            LogWriter.log(TAG, "click(设置页): 保存");
            collectAndSave(config, etBaseUrl, etApiKey, etModel, etBotName, etWakeKeyword,
                    etSystemPrompt, etMemory, seekTemp);
            dismissCurrent();
            toastQuiet(ctx, "已保存");
        });

        ModernButton btnReset = new ModernButton(ctx, "恢复默认", ModernButton.STYLE_GHOST);
        btnReset.onClick(() -> {
            LogWriter.log(TAG, "click(设置页): 恢复默认");
            try {
                config.reset();
                config.save();
                AIBotCore.reload();
                toastQuiet(ctx, "已恢复默认");
                dismissCurrent();
            } catch (Throwable t) {
                LogWriter.log(TAG, "reset err: " + t);
                toastQuiet(ctx, "恢复默认失败");
            }
        });

        ModernButton btnClose = new ModernButton(ctx, "关闭", ModernButton.STYLE_GHOST);
        btnClose.onClick(() -> {
            LogWriter.log(TAG, "click(设置页): 关闭");
            dismissCurrent();
        });

        root.addView(newBtnRow(ctx, btnSave, btnReset, btnClose));
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        showPopup(activity, root, (int) (dm.heightPixels * 0.85f), "settings");
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
            LogWriter.log(TAG, "settings saved ok=" + ok);
            try {
                AIBotCore.reload();
            } catch (Throwable t) {
                LogWriter.log(TAG, "reload after save err: " + t);
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "collectAndSave err: " + t);
        }
    }

    // ==================== 白名单(v962 内嵌) ====================

    private static void showWhitelist(final Activity activity) {
        LogWriter.log(TAG, "showWhitelist: enter");
        if (activity == null || activity.isFinishing()) {
            LogWriter.log(TAG, "showWhitelist skipped: activity null/finishing");
            return;
        }
        final Context ctx = activity;

        final Whitelist wl;
        try {
            wl = AIBotCore.whitelist() != null ? AIBotCore.whitelist()
                    : new Whitelist(ctx.getFilesDir().getParent());
        } catch (Throwable t) {
            LogWriter.log(TAG, "whitelist err: " + t);
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
        // v962: 两步删除确认 — 首次点击只标记, 再次点击同一条目才真删
        final String[] pendingDelete = {null};

        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, "白名单管理"));

        TextView tip = new TextView(ctx);
        tip.setText("名单非空时, AI 仅响应名单内会话(wxid / 群 id); 点击条目两次确认删除。");
        tip.setTextSize(12);
        tip.setTextColor(AppColors.textTertiary());
        tip.setPadding(0, dp(ctx, 4), 0, dp(ctx, 8));
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
                LogWriter.log(TAG, "click(白名单): 添加-空输入");
                toastQuiet(ctx, "请输入 talker");
                return;
            }
            LogWriter.log(TAG, "click(白名单): 添加 " + id);
            try {
                wl.add(id);
                wl.save();
                items.add(id);
                rebuildLabels(items, labels);
                etAdd.setText("");
                toastQuiet(ctx, "已添加 " + id);
            } catch (Throwable t) {
                LogWriter.log(TAG, "wl add err: " + t);
                toastQuiet(ctx, "添加失败");
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
            final String target = items.get(position);
            if (target == null) return;
            if (!target.equals(pendingDelete[0])) {
                pendingDelete[0] = target;
                LogWriter.log(TAG, "click(白名单): 标记删除 " + target);
                toastQuiet(ctx, "再次点击确认删除 " + target);
                return;
            }
            LogWriter.log(TAG, "click(白名单): 确认删除 " + target);
            try {
                wl.remove(target);
                wl.save();
                items.remove(position);
                pendingDelete[0] = null;
                rebuildLabels(items, labels);
                toastQuiet(ctx, "已移除 " + target);
            } catch (Throwable t) {
                LogWriter.log(TAG, "wl remove err: " + t);
                toastQuiet(ctx, "删除失败");
            }
        });
        root.addView(listView);

        ModernButton btnClose = new ModernButton(ctx, "关闭", ModernButton.STYLE_GHOST);
        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpC.setMargins(0, dp(ctx, 12), 0, 0);
        btnClose.setLayoutParams(lpC);
        btnClose.onClick(() -> {
            LogWriter.log(TAG, "click(白名单): 关闭");
            dismissCurrent();
        });
        root.addView(btnClose);

        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        showPopup(activity, root, (int) (dm.heightPixels * 0.85f), "whitelist");
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

    private static void persist(Context ctx, AppConfig config, ConfigMutator mutator, String toast) {
        try {
            mutator.apply(config);
            boolean ok = config.save();
            LogWriter.log(TAG, "persist ok=" + ok);
            try {
                AIBotCore.reload();
            } catch (Throwable t) {
                LogWriter.log(TAG, "reload err: " + t);
            }
            toastQuiet(ctx, toast);
        } catch (Throwable t) {
            LogWriter.log(TAG, "persist err: " + t);
            toastQuiet(ctx, "保存失败");
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String str(EditText et) {
        return et.getText() == null ? "" : et.getText().toString();
    }

    private static void updateTempLabel(TextView tv, int progress) {
        tv.setText("温度: " + String.format(java.util.Locale.US, "%.1f", progress / TEMP_SCALE));
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
        if (ctx == null) return;
        try {
            M3Page.toast(ctx, msg);
        } catch (Throwable ignored) {
        }
    }
}
