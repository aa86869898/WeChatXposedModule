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
import com.leshao.v3.LogWriter;
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
 * AI 助手弹窗(v967): 微信会话页 ⋮ 菜单点击后在微信进程内展示。
 *
 * <p>PopupWindow 展示(v962 起, AlertDialog 在微信 3180 不可靠)。首页放全部功能开关与
 * 配置入口(模型提供商 / AI 核心参数配置), 不再有「完整设置」二级总入口;
 * TTS 开关语义: 开=AI 回复转成语音消息发出, 关=直接发文本。</p>
 *
 * <p>v967 关键修复: 首页 PopupWindow 高度改为固定 85% 屏高 + 中部 ScrollView weight=1,
 * 解决 WRAP_CONTENT 内容超高时底部按钮被屏幕裁剪、点击不到的问题。</p>
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
            int panelW = (int) (dm.widthPixels * 0.9f);
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
        root.setBackground(CandyUi.dialogBg(ctx));
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

    /** 可滚动内容区(占据剩余高度, 修复固定高度弹窗内容裁剪) */
    private static ScrollView newScroll(LinearLayout root, LinearLayout list) {
        ScrollView scroll = new ScrollView(root.getContext());
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll);
        return scroll;
    }

    /** 底部两按钮行(等宽): 左 / 右 */
    private static LinearLayout newBtnRow2(Context ctx, ModernButton left, ModernButton right) {
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpLeft.setMargins(0, dp(ctx, 16), dp(ctx, 4), 0);
        LinearLayout.LayoutParams lpRight = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpRight.setMargins(dp(ctx, 4), dp(ctx, 16), 0, 0);
        left.setLayoutParams(lpLeft);
        right.setLayoutParams(lpRight);
        btnRow.addView(left);
        btnRow.addView(right);
        return btnRow;
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

    // ==================== 主弹窗(功能开关 + 配置入口) ====================

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
            tip.setText("AI 核心尚未初始化,请稍后重试。");
            tip.setTextSize(13);
            tip.setTextColor(AppColors.textTertiary());
            tip.setPadding(0, dp(ctx, 12), 0, dp(ctx, 16));
            root.addView(tip);
        } else {
            LinearLayout list = new LinearLayout(ctx);
            newScroll(root, list);

            // ---- 1. 功能开关 ----
            list.addView(new SectionHeader(ctx, "功能开关", "修改后即时生效"));
            list.addView(new SettingRow(ctx, "🤖", "AI 助手", "总开关,关闭后全部 AI 能力停用")
                    .switchOn(config.isEnabled(), (btn, checked) -> {
                        LogWriter.log(TAG, "click: AI助手总开关 -> " + checked);
                        persist(ctx, config, c -> c.setEnabled(checked), "AI助手已" + (checked ? "开启" : "关闭"));
                    }));
            list.addView(new SettingRow(ctx, "🔊", "语音消息发送", "开=AI回复转语音消息发出; 关=直接发文本")
                    .switchOn(config.isTtsEnabled(), (btn, checked) -> {
                        LogWriter.log(TAG, "click: 语音消息发送 -> " + checked);
                        persist(ctx, config, c -> c.setTtsEnabled(checked), "语音消息发送已" + (checked ? "开启" : "关闭"));
                    }));

            // ---- 2. 自动回复 ----
            list.addView(new SectionHeader(ctx, "自动回复", "按会话类型控制触发范围"));
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
            list.addView(new SettingRow(ctx, "📣", "仅被@时自动回复", "群聊中只有被提到时才回复")
                    .switchOn(config.isOnlyWhenMentioned(), (btn, checked) -> {
                        LogWriter.log(TAG, "click: 仅被@时回复 -> " + checked);
                        persist(ctx, config, c -> c.setOnlyWhenMentioned(checked), "已更新@回复规则");
                    }));

            // ---- 3. 模型与参数(点击进入配置) ----
            list.addView(new SectionHeader(ctx, "模型与参数", "服务商接入与核心参数"));
            final String providerSub = providerLabel(providerTypeOf(config.getProviderType()))
                    + (TextUtils.isEmpty(config.getModel()) ? "" : " · " + config.getModel());
            list.addView(new SettingRow(ctx, "☁", "模型提供商", providerSub)
                    .arrow(() -> {
                        LogWriter.log(TAG, "click: 模型提供商");
                        dismissCurrent();
                        showProviderConfig(activity);
                    }));
            final String coreSub = (TextUtils.isEmpty(config.getBotName()) ? "未命名" : config.getBotName())
                    + " · 记忆 " + config.getMaxHistoryMessages() + " 条";
            list.addView(new SettingRow(ctx, "🛠", "AI 核心参数配置", coreSub)
                    .arrow(() -> {
                        LogWriter.log(TAG, "click: AI核心参数配置");
                        dismissCurrent();
                        showCoreConfig(activity);
                    }));
        }

        // ---- 底部栏: 左(白名单) 右(关闭) ----
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

        root.addView(newBtnRow2(ctx, btnWhitelist, btnClose));
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        showPopup(activity, root, (int) (dm.heightPixels * 0.85f), "main");
    }

    // ==================== 二级: 模型提供商 ====================

    private static void showProviderConfig(final Activity activity) {
        LogWriter.log(TAG, "showProviderConfig: enter");
        if (activity == null || activity.isFinishing()) return;
        final Context ctx = activity;

        final AppConfig config;
        try {
            config = AIBotCore.config();
        } catch (Throwable t) {
            LogWriter.log(TAG, "showProviderConfig config err: " + t);
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }
        if (config == null) {
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }

        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, "模型提供商"));
        LinearLayout list = new LinearLayout(ctx);
        newScroll(root, list);

        // ---- 服务商 ----
        list.addView(new SectionHeader(ctx, "服务商", "API 协议类型"));
        final SettingRow[] providerRows = new SettingRow[ProviderType.values().length];
        ProviderType[] types = ProviderType.values();
        for (int i = 0; i < types.length; i++) {
            final ProviderType pt = types[i];
            boolean sel = providerMatches(pt, config.getProviderType());
            SettingRow row = new SettingRow(ctx, "☁", providerLabel(pt), sel ? "当前使用" : "点击选择");
            providerRows[i] = row;
            row.arrow(() -> {
                LogWriter.log(TAG, "click: 服务商 -> " + pt.name());
                try {
                    config.setProviderType(pt.name().toLowerCase(Locale.US));
                    for (int j = 0; j < providerRows.length; j++) {
                        providerRows[j].setSub(j == pt.ordinal() ? "当前使用" : "点击选择");
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

        // ---- 底部按钮 ----
        ModernButton btnSave = new ModernButton(ctx, "保存", ModernButton.STYLE_PRIMARY);
        btnSave.onClick(() -> {
            LogWriter.log(TAG, "click(提供商): 保存");
            try {
                config.setBaseUrl(str(etBaseUrl));
                config.setApiKey(str(etApiKey));
                config.setModel(str(etModel));
                config.setTemperature(seekTemp.getProgress() / TEMP_SCALE);
                boolean ok = config.save();
                LogWriter.log(TAG, "provider saved ok=" + ok);
                reload();
                toastQuiet(ctx, "已保存");
            } catch (Throwable t) {
                LogWriter.log(TAG, "provider save err: " + t);
                toastQuiet(ctx, "保存失败");
            }
            dismissCurrent();
            show(activity);
        });

        ModernButton btnClose = new ModernButton(ctx, "返回", ModernButton.STYLE_GHOST);
        btnClose.onClick(() -> {
            LogWriter.log(TAG, "click(提供商): 返回");
            dismissCurrent();
            show(activity);
        });

        root.addView(newBtnRow2(ctx, btnSave, btnClose));
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        showPopup(activity, root, (int) (dm.heightPixels * 0.85f), "provider");
    }

    // ==================== 二级: AI 核心参数 ====================

    private static void showCoreConfig(final Activity activity) {
        LogWriter.log(TAG, "showCoreConfig: enter");
        if (activity == null || activity.isFinishing()) return;
        final Context ctx = activity;

        final AppConfig config;
        try {
            config = AIBotCore.config();
        } catch (Throwable t) {
            LogWriter.log(TAG, "showCoreConfig config err: " + t);
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }
        if (config == null) {
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }

        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, "AI 核心参数"));
        LinearLayout list = new LinearLayout(ctx);
        newScroll(root, list);

        // ---- 身份 ----
        list.addView(new SectionHeader(ctx, "身份", "AI 对外展示的名字与唤醒词"));
        final EditText etBotName = M3Page.input(ctx, "AI 昵称, 如 小乐");
        etBotName.setText(safe(config.getBotName()));
        list.addView(etBotName);
        final EditText etWakeKeyword = M3Page.input(ctx, "唤醒词(多个用逗号分隔)");
        etWakeKeyword.setText(safe(config.getWakeKeyword()));
        list.addView(etWakeKeyword);

        // ---- 人设 ----
        list.addView(new SectionHeader(ctx, "人设提示词", "System Prompt, 决定 AI 的语气与身份"));
        final EditText etSystemPrompt = M3Page.input(ctx, "人设提示词");
        etSystemPrompt.setSingleLine(false);
        etSystemPrompt.setMinLines(4);
        etSystemPrompt.setGravity(Gravity.TOP);
        etSystemPrompt.setText(safe(config.getSystemPrompt()));
        list.addView(etSystemPrompt);

        // ---- 记忆 ----
        list.addView(new SectionHeader(ctx, "上下文记忆", "带入对话的历史消息条数"));
        final EditText etMemory = M3Page.input(ctx, "记忆条数, 如 50");
        etMemory.setText(String.valueOf(config.getMaxHistoryMessages()));
        list.addView(etMemory);

        // ---- 底部按钮 ----
        ModernButton btnSave = new ModernButton(ctx, "保存", ModernButton.STYLE_PRIMARY);
        btnSave.onClick(() -> {
            LogWriter.log(TAG, "click(核心参数): 保存");
            try {
                config.setBotName(str(etBotName));
                config.setWakeKeyword(str(etWakeKeyword));
                config.setSystemPrompt(str(etSystemPrompt));
                String mem = str(etMemory);
                if (!TextUtils.isEmpty(mem)) {
                    try {
                        config.setMaxHistoryMessages(Integer.parseInt(mem.trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
                boolean ok = config.save();
                LogWriter.log(TAG, "core saved ok=" + ok);
                reload();
                toastQuiet(ctx, "已保存");
            } catch (Throwable t) {
                LogWriter.log(TAG, "core save err: " + t);
                toastQuiet(ctx, "保存失败");
            }
            dismissCurrent();
            show(activity);
        });

        ModernButton btnReset = new ModernButton(ctx, "恢复默认", ModernButton.STYLE_GHOST);
        btnReset.onClick(() -> {
            LogWriter.log(TAG, "click(核心参数): 恢复默认");
            try {
                config.reset();
                config.save();
                reload();
                toastQuiet(ctx, "已恢复默认");
            } catch (Throwable t) {
                LogWriter.log(TAG, "reset err: " + t);
                toastQuiet(ctx, "恢复默认失败");
            }
            dismissCurrent();
            show(activity);
        });

        ModernButton btnClose = new ModernButton(ctx, "返回", ModernButton.STYLE_GHOST);
        btnClose.onClick(() -> {
            LogWriter.log(TAG, "click(核心参数): 返回");
            dismissCurrent();
            show(activity);
        });

        root.addView(newBtnRow(ctx, btnSave, btnReset, btnClose));
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        showPopup(activity, root, (int) (dm.heightPixels * 0.85f), "core");
    }

    // ==================== 二级: 白名单 ====================

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
        // 两步删除确认 — 首次点击只标记, 再次点击同一条目才真删
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

        ModernButton btnClose = new ModernButton(ctx, "返回", ModernButton.STYLE_GHOST);
        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpC.setMargins(0, dp(ctx, 12), 0, 0);
        btnClose.setLayoutParams(lpC);
        btnClose.onClick(() -> {
            LogWriter.log(TAG, "click(白名单): 返回");
            dismissCurrent();
            show(activity);
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
            reload();
            toastQuiet(ctx, toast);
        } catch (Throwable t) {
            LogWriter.log(TAG, "persist err: " + t);
            toastQuiet(ctx, "保存失败");
        }
    }

    private static void reload() {
        try {
            AIBotCore.reload();
        } catch (Throwable t) {
            LogWriter.log(TAG, "reload err: " + t);
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

    /** 解析 providerType 字符串为枚举(兼容 "openai" 别名) */
    private static ProviderType providerTypeOf(String current) {
        if (current == null) return ProviderType.OPENAI_CHAT;
        String c = current.toLowerCase(Locale.US).trim();
        for (ProviderType pt : ProviderType.values()) {
            if (pt.name().toLowerCase(Locale.US).equals(c)) return pt;
        }
        if ("openai".equals(c) || "chat".equals(c) || "gpt".equals(c)) return ProviderType.OPENAI_CHAT;
        if ("responses".equals(c)) return ProviderType.OPENAI_RESPONSES;
        if ("claude".equals(c)) return ProviderType.ANTHROPIC;
        return ProviderType.OPENAI_CHAT;
    }

    private static boolean providerMatches(ProviderType pt, String current) {
        return providerTypeOf(current) == pt;
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
