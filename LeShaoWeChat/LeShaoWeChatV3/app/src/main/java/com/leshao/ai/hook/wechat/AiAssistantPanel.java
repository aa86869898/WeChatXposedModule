package com.leshao.ai.hook.wechat;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.Switch;
import android.widget.TextView;

import com.leshao.ai.api.model.ProviderType;
import com.leshao.ai.config.AppConfig;
import com.leshao.ai.config.ConversationConfig;
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
            android.content.Context actx = anchor.getContext();
            int panelW = (int) (dm.widthPixels * 0.94f);

            // v969: 按“可见显示区”(排除状态栏/导航栏/手势条)计算可用高度并据此限高,
            // 避免弹窗居中后底部按钮被屏幕边缘或导航栏遮挡(表现为下半部分显示不全)。
            android.graphics.Rect frame = new android.graphics.Rect();
            try { anchor.getWindowVisibleDisplayFrame(frame); } catch (Throwable ignored) {}
            int availH = frame.height() > 0 ? frame.height() : dm.heightPixels;
            int margin = dp(actx, 12);
            int maxPanelH = Math.max(dp(actx, 240), availH - margin * 2);

            PopupWindow pw = new PopupWindow(root, panelW, ViewGroup.LayoutParams.WRAP_CONTENT, true);
            pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            try { pw.setElevation(dp(actx, 8)); } catch (Throwable ignored) {}
            pw.setOutsideTouchable(true);
            // v969: 弹窗高度已显式限高, 键盘改用 PAN(整体上移)而非 RESIZE,
            // 避免 RESIZE 时固定高度内容被裁掉导致底部按钮消失。
            pw.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);

            // 预测量: 内容按可用高度收缩, 取实际面板高度用于精确居中(不越过可见区)
            int panelH;
            if (heightPx > 0) {
                panelH = Math.min(heightPx, maxPanelH);
            } else {
                try {
                    root.measure(View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(maxPanelH, View.MeasureSpec.AT_MOST));
                    panelH = Math.min(root.getMeasuredHeight(), maxPanelH);
                } catch (Throwable t) {
                    panelH = ViewGroup.LayoutParams.WRAP_CONTENT;
                }
            }
            if (panelH > 0) pw.setHeight(panelH);

            sPopup = pw;
            try {
                int x = Math.max(0, (dm.widthPixels - panelW) / 2);
                int y = frame.top + Math.max(0, (availH - (panelH > 0 ? panelH : maxPanelH)) / 2);
                pw.showAtLocation(anchor, Gravity.TOP | Gravity.LEFT, x, y);
                LogWriter.log(TAG, "showPopup OK: scene=" + scene + " panelH=" + panelH
                        + " availH=" + availH + " y=" + y);
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

    private static TextView fieldLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.textTertiary());
        tv.setPadding(dp(ctx, 2), dp(ctx, 6), 0, dp(ctx, 2));
        return tv;
    }

    private static LinearLayout newRoot(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.dialogBg(ctx));
        // v968: 左右边距收紧(原 20dp), 让右侧开关等尾部控件更贴边不局促
        root.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 12), dp(ctx, 12));
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

    /** 内容超出上限才可滚动、否则按内容收缩的 ScrollView(v968: 消除底部栏上方大片空白) */
    private static final class CappedScrollView extends ScrollView {
        private int mMaxHeight;

        CappedScrollView(Context c) {
            super(c);
        }

        void setMaxHeight(int h) {
            mMaxHeight = h;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int cap = mMaxHeight;
            int mode = MeasureSpec.getMode(heightSpec);
            // v969: 同时遵守父容器(弹窗可见区/键盘弹出后的可用高度)给出的上限,
            // 避免固定 0.6 屏高在可用空间变小时撑破弹窗、底部按钮被裁掉。
            if (mode != MeasureSpec.UNSPECIFIED && cap > 0) {
                cap = Math.min(cap, MeasureSpec.getSize(heightSpec));
            }
            if (cap > 0) {
                heightSpec = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    /** 可滚动内容区: 高度按内容收缩, 上限 maxHeightPx(超出才滚动) */
    private static ScrollView newScroll(LinearLayout root, LinearLayout list, int maxHeightPx) {
        CappedScrollView scroll = new CappedScrollView(root.getContext());
        scroll.setMaxHeight(maxHeightPx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
            newScroll(root, list, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.60f));

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
            // v969: 按群独立配置入口(点击进入, 不影响上面的总开关)
            list.addView(new SettingRow(ctx, "🧩", "按群聊独立配置", overrideSub(true))
                    .arrow(() -> {
                        LogWriter.log(TAG, "click: 群聊独立配置");
                        dismissCurrent();
                        showConversationList(activity, true);
                    }));
            list.addView(new SettingRow(ctx, "💬", "私聊自动回复", "对白名单联系人自动回复")
                    .switchOn(config.isAutoReplyInPrivate(), (btn, checked) -> {
                        LogWriter.log(TAG, "click: 私聊自动回复 -> " + checked);
                        persist(ctx, config, c -> c.setAutoReplyInPrivate(checked), "私聊自动回复已" + (checked ? "开启" : "关闭"));
                    }));
            // v969: 按联系人独立配置入口
            list.addView(new SettingRow(ctx, "🧩", "按联系人独立配置", overrideSub(false))
                    .arrow(() -> {
                        LogWriter.log(TAG, "click: 私聊独立配置");
                        dismissCurrent();
                        showConversationList(activity, false);
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
            list.addView(new SettingRow(ctx, "🧩", "模板配置", templateSub())
                    .arrow(() -> {
                        LogWriter.log(TAG, "click: 模板配置");
                        dismissCurrent();
                        showTemplateList(activity);
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
        // v968: WRAP_CONTENT 高度, 面板按内容收缩, 底部栏紧贴内容
        showPopup(activity, root, 0, "main");
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
        newScroll(root, list, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.60f));

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
        final TextView lbUrl = fieldLabel(ctx, "接口地址");
        list.addView(lbUrl);
        final EditText etBaseUrl = M3Page.input(ctx, "如 https://api.deepseek.com/v1");
        etBaseUrl.setText(safe(config.getBaseUrl()));
        list.addView(etBaseUrl);
        final TextView lbKey = fieldLabel(ctx, "Api Key密钥");
        list.addView(lbKey);
        final EditText etApiKey = M3Page.input(ctx, "sk-...");
        etApiKey.setText(safe(config.getApiKey()));
        list.addView(etApiKey);
        final TextView lbModel = fieldLabel(ctx, "模型名称");
        list.addView(lbModel);

        // 模型名称 + 「获取模型」按钮同一行
        LinearLayout modelRow = new LinearLayout(ctx);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText etModel = M3Page.input(ctx, "如 deepseek-chat");
        etModel.setText(safe(config.getModel()));
        etModel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        modelRow.addView(etModel);
        final ModernButton btnFetch = new ModernButton(ctx, "获取模型", ModernButton.STYLE_GHOST);
        LinearLayout.LayoutParams lpFetch = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpFetch.setMargins(dp(ctx, 8), 0, 0, 0);
        btnFetch.setLayoutParams(lpFetch);
        modelRow.addView(btnFetch);
        list.addView(modelRow);

        // 拉取到的模型列表(内联展示, 点击即填入模型名称)
        final LinearLayout modelResults = new LinearLayout(ctx);
        modelResults.setOrientation(LinearLayout.VERTICAL);
        list.addView(modelResults);

        btnFetch.onClick(() -> {
            final String base = str(etBaseUrl).trim();
            final String key = str(etApiKey);
            final String ptype = config.getProviderType();
            LogWriter.log(TAG, "click(提供商): 获取模型 base=" + base);
            if (TextUtils.isEmpty(base)) {
                toastQuiet(ctx, "请先填写接口地址");
                return;
            }
            modelResults.removeAllViews();
            btnFetch.setText("获取中…");
            btnFetch.setEnabled(false);
            final Handler ui = new Handler(Looper.getMainLooper());
            new Thread(() -> {
                List<String> models = null;
                String err = null;
                try {
                    models = com.leshao.ai.api.ModelCatalogClient.fetch(ptype, base, key);
                } catch (Throwable t) {
                    err = t.getMessage();
                    LogWriter.log(TAG, "fetchModels err: " + t);
                }
                final List<String> listFinal = models;
                final String errFinal = err;
                ui.post(() -> {
                    btnFetch.setText("获取模型");
                    btnFetch.setEnabled(true);
                    modelResults.removeAllViews();
                    if (errFinal != null) {
                        toastQuiet(ctx, "获取失败: " + errFinal);
                        return;
                    }
                    if (listFinal == null || listFinal.isEmpty()) {
                        toastQuiet(ctx, "未获取到模型列表");
                        return;
                    }
                    int shown = 0;
                    for (String id : listFinal) {
                        if (shown >= 60) break;
                        final String modelId = id;
                        SettingRow row = new SettingRow(ctx, "🧠", modelId, "点击填入模型名称");
                        row.setOnClickListener(v -> {
                            etModel.setText(modelId);
                            modelResults.removeAllViews();
                            toastQuiet(ctx, "已选择模型: " + modelId);
                        });
                        modelResults.addView(row);
                        shown++;
                    }
                    toastQuiet(ctx, "共获取到 " + listFinal.size() + " 个模型");
                });
            }).start();
        });

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
        showPopup(activity, root, 0, "provider");
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
        newScroll(root, list, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.60f));

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
        showPopup(activity, root, 0, "core");
    }

    // ==================== 三级: 按会话独立配置 ====================

    private static String overrideSub(boolean group) {
        try {
            ConversationConfig cc = AIBotCore.conversationConfig();
            int n = cc == null ? 0 : cc.keysByType(group).size();
            return n == 0 ? "未配置, 全部沿用全局"
                    : "已独立配置 " + n + " 个" + (group ? "群" : "联系人");
        } catch (Throwable t) {
            return "未配置";
        }
    }

    private static String templateSub() {
        try {
            ConversationConfig cc = AIBotCore.conversationConfig();
            int n = cc == null ? 0 : cc.templateNames().size();
            return n == 0 ? "可快速套用到不同群 / 联系人" : "已有 " + n + " 个模板";
        } catch (Throwable t) {
            return "可快速套用到不同群 / 联系人";
        }
    }

    private static String label(String talker) {
        try {
            String n = ContactQuery.displayName(talker);
            if (n != null && !n.trim().isEmpty()) return n;
        } catch (Throwable ignored) {
        }
        return talker;
    }

    private static String entrySummary(ConversationConfig.Entry e) {
        if (e == null) return "继承全局";
        List<String> parts = new ArrayList<>();
        if (e.autoReply != null) parts.add(e.autoReply ? "自动回复:开" : "自动回复:关");
        if (e.onlyWhenMentioned != null) parts.add(e.onlyWhenMentioned ? "仅@" : "全部消息");
        if (e.ttsEnabled != null) parts.add(e.ttsEnabled ? "语音" : "文本");
        if (!TextUtils.isEmpty(e.systemPrompt)) parts.add("自定义人设");
        if (!TextUtils.isEmpty(e.model)) parts.add("模型:" + e.model);
        return parts.isEmpty() ? "继承全局" : TextUtils.join(" · ", parts);
    }

    private static Switch makeSwitch(Context ctx, boolean checked) {
        Switch sw = CandyUi.newSwitch(ctx);
        sw.setChecked(checked);
        float d = ctx.getResources().getDisplayMetrics().density;
        sw.setLayoutParams(new LinearLayout.LayoutParams(
                (int) (AppColors.SWITCH_WIDTH_DP * d + 0.5f),
                (int) (AppColors.SWITCH_HEIGHT_DP * d + 0.5f)));
        return sw;
    }

    private static void addSwitchRow(LinearLayout list, Context ctx, Switch sw,
                                     String icon, String title, String sub) {
        SettingRow row = new SettingRow(ctx, icon, title, sub);
        row.tail(sw);
        row.setOnClickListener(v -> {
            try { sw.toggle(); } catch (Throwable ignored) {}
        });
        list.addView(row);
    }

    private static void showConversationList(final Activity activity, final boolean isGroup) {
        LogWriter.log(TAG, "showConversationList: enter group=" + isGroup);
        if (activity == null || activity.isFinishing()) return;
        final Context ctx = activity;
        final ConversationConfig cc = AIBotCore.conversationConfig();
        if (cc == null) {
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }

        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, isGroup ? "群聊独立配置" : "私聊独立配置"));

        TextView tip = new TextView(ctx);
        tip.setText("为指定会话单独设置自动回复 / 人设 / 模型; 未配置的会话沿用全局。");
        tip.setTextSize(12);
        tip.setTextColor(AppColors.textTertiary());
        tip.setPadding(0, dp(ctx, 4), 0, dp(ctx, 8));
        root.addView(tip);

        ModernButton btnAdd = new ModernButton(ctx, "＋ 添加会话", ModernButton.STYLE_PRIMARY);
        LinearLayout.LayoutParams lpAdd = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpAdd.setMargins(0, 0, 0, dp(ctx, 8));
        btnAdd.setLayoutParams(lpAdd);
        btnAdd.onClick(() -> {
            LogWriter.log(TAG, "click: 添加会话 group=" + isGroup);
            dismissCurrent();
            showSessionPicker(activity, isGroup);
        });
        root.addView(btnAdd);

        LinearLayout list = new LinearLayout(ctx);
        newScroll(root, list, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.60f));

        List<String> keys = cc.keysByType(isGroup);
        if (keys.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无独立配置, 点击上方「添加会话」开始。");
            empty.setTextSize(13);
            empty.setTextColor(AppColors.textTertiary());
            empty.setPadding(dp(ctx, 4), dp(ctx, 12), 0, dp(ctx, 12));
            list.addView(empty);
        } else {
            for (String t : keys) {
                final String talker = t;
                list.addView(new SettingRow(ctx, isGroup ? "👥" : "👤",
                        label(talker), entrySummary(cc.get(talker)))
                        .arrow(() -> {
                            LogWriter.log(TAG, "click: 编辑独立配置 " + talker);
                            dismissCurrent();
                            showConvEdit(activity, talker, isGroup);
                        }));
            }
        }

        ModernButton btnClose = new ModernButton(ctx, "返回", ModernButton.STYLE_GHOST);
        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpC.setMargins(0, dp(ctx, 12), 0, 0);
        btnClose.setLayoutParams(lpC);
        btnClose.onClick(() -> {
            LogWriter.log(TAG, "click(独立配置): 返回");
            dismissCurrent();
            show(activity);
        });
        root.addView(btnClose);

        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        showPopup(activity, root, (int) (dm.heightPixels * 0.85f), "convlist");
    }

    private static void showSessionPicker(final Activity activity, final boolean isGroup) {
        LogWriter.log(TAG, "showSessionPicker: enter group=" + isGroup);
        if (activity == null || activity.isFinishing()) return;
        final Context ctx = activity;
        final ConversationConfig cc = AIBotCore.conversationConfig();
        if (cc == null) {
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }

        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, isGroup ? "选择要配置的群" : "选择要配置的联系人"));

        LinearLayout list = new LinearLayout(ctx);
        newScroll(root, list, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.55f));

        int count = 0;
        try {
            List<String[]> sessions = ConversationQuery.listSessions();
            if (sessions != null) {
                for (String[] s : sessions) {
                    if (s == null || s.length < 1 || s[0] == null) continue;
                    final String talker = s[0];
                    if (ConversationConfig.isGroupTalker(talker) != isGroup) continue;
                    if (cc.get(talker) != null) continue;
                    String name = (s.length > 1 && s[1] != null && !s[1].trim().isEmpty())
                            ? s[1] : label(talker);
                    list.addView(new SettingRow(ctx, isGroup ? "👥" : "👤", name, talker)
                            .arrow(() -> {
                                LogWriter.log(TAG, "click: 选中会话 " + talker);
                                dismissCurrent();
                                showConvEdit(activity, talker, isGroup);
                            }));
                    count++;
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "showSessionPicker listSessions err: " + t);
        }
        if (count == 0) {
            TextView empty = new TextView(ctx);
            empty.setText("未读取到可选会话, 可在下方手动输入 talker / 群 id 添加。");
            empty.setTextSize(13);
            empty.setTextColor(AppColors.textTertiary());
            empty.setPadding(dp(ctx, 4), dp(ctx, 12), 0, dp(ctx, 12));
            list.addView(empty);
        }

        LinearLayout addRow = new LinearLayout(ctx);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText etId = M3Page.input(ctx, "手动输入 talker / 群 id");
        etId.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addRow.addView(etId);
        ModernButton btnAdd = new ModernButton(ctx, "添加", ModernButton.STYLE_PRIMARY);
        btnAdd.onClick(() -> {
            String id = str(etId).trim();
            if (TextUtils.isEmpty(id)) {
                toastQuiet(ctx, "请输入 talker");
                return;
            }
            LogWriter.log(TAG, "click(选择会话): 手动添加 " + id);
            dismissCurrent();
            showConvEdit(activity, id, ConversationConfig.isGroupTalker(id));
        });
        addRow.addView(btnAdd);
        root.addView(addRow);

        ModernButton btnClose = new ModernButton(ctx, "返回", ModernButton.STYLE_GHOST);
        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpC.setMargins(0, dp(ctx, 12), 0, 0);
        btnClose.setLayoutParams(lpC);
        btnClose.onClick(() -> {
            dismissCurrent();
            showConversationList(activity, isGroup);
        });
        root.addView(btnClose);

        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        showPopup(activity, root, (int) (dm.heightPixels * 0.85f), "sessionpick");
    }

    private static void showConvEdit(final Activity activity, final String talker,
                                     final boolean isGroup) {
        LogWriter.log(TAG, "showConvEdit: enter talker=" + talker + " group=" + isGroup);
        if (activity == null || activity.isFinishing()) return;
        final Context ctx = activity;
        final ConversationConfig cc = AIBotCore.conversationConfig();
        final AppConfig cfg = AIBotCore.config();
        if (cc == null || cfg == null) {
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }
        ConversationConfig.Entry e = cc.get(talker);
        if (e == null) e = new ConversationConfig.Entry();
        final ConversationConfig.Entry entry = e;

        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, label(talker)));
        TextView idTv = new TextView(ctx);
        idTv.setText(talker);
        idTv.setTextSize(12);
        idTv.setTextColor(AppColors.textTertiary());
        root.addView(idTv);

        LinearLayout list = new LinearLayout(ctx);
        newScroll(root, list, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.55f));

        list.addView(new SectionHeader(ctx, "模板", "一键套用预设"));
        list.addView(new SettingRow(ctx, "🧩", "套用模板", templateSub())
                .arrow(() -> {
                    dismissCurrent();
                    showTemplatePicker(activity, talker, isGroup);
                }));

        list.addView(new SectionHeader(ctx, "独立开关", "保存后独立于全局"));
        boolean effAuto = entry.autoReply != null ? entry.autoReply
                : (isGroup ? cfg.isAutoReplyInGroups() : cfg.isAutoReplyInPrivate());
        final Switch swAuto = makeSwitch(ctx, effAuto);
        addSwitchRow(list, ctx, swAuto, "💬", "自动回复", "关闭则本会话不自动回复");

        Switch swOnly = null;
        if (isGroup) {
            boolean effOnly = entry.onlyWhenMentioned != null ? entry.onlyWhenMentioned : cfg.isOnlyWhenMentioned();
            swOnly = makeSwitch(ctx, effOnly);
            addSwitchRow(list, ctx, swOnly, "📣", "仅被@时回复", "仅在被 @ 或命中唤醒词时回复");
        }
        boolean effTts = entry.ttsEnabled != null ? entry.ttsEnabled : cfg.isTtsEnabled();
        final Switch swTts = makeSwitch(ctx, effTts);
        addSwitchRow(list, ctx, swTts, "🔊", "语音消息发送", "开=转语音发出; 关=发文本");
        final Switch swOnlyF = swOnly;

        list.addView(new SectionHeader(ctx, "人设与模型", "留空表示继承全局"));
        final EditText etSys = M3Page.input(ctx, "人设提示词 (留空 = 全局)");
        etSys.setSingleLine(false);
        etSys.setMinLines(3);
        etSys.setGravity(Gravity.TOP);
        etSys.setText(safe(entry.systemPrompt));
        list.addView(etSys);
        final EditText etModel = M3Page.input(ctx, "模型 (留空 = " + safe(cfg.getModel()) + ")");
        etModel.setText(safe(entry.model));
        list.addView(etModel);

        final boolean groupFinal = isGroup;
        ModernButton btnSave = new ModernButton(ctx, "保存", ModernButton.STYLE_PRIMARY);
        btnSave.onClick(() -> {
            LogWriter.log(TAG, "click(独立配置): 保存 " + talker);
            try {
                ConversationConfig.Entry out = new ConversationConfig.Entry();
                out.autoReply = swAuto.isChecked();
                if (groupFinal && swOnlyF != null) out.onlyWhenMentioned = swOnlyF.isChecked();
                out.ttsEnabled = swTts.isChecked();
                out.systemPrompt = str(etSys).trim();
                out.model = str(etModel).trim();
                cc.put(talker, out);
                cc.save();
                reload();
                toastQuiet(ctx, "已保存独立配置");
            } catch (Throwable t) {
                LogWriter.log(TAG, "conv save err: " + t);
                toastQuiet(ctx, "保存失败");
            }
            dismissCurrent();
            showConversationList(activity, groupFinal);
        });

        ModernButton btnDel = new ModernButton(ctx, "删除配置", ModernButton.STYLE_DANGER);
        btnDel.onClick(() -> {
            LogWriter.log(TAG, "click(独立配置): 删除 " + talker);
            try {
                cc.remove(talker);
                cc.save();
                reload();
                toastQuiet(ctx, "已删除独立配置");
            } catch (Throwable t) {
                LogWriter.log(TAG, "conv del err: " + t);
            }
            dismissCurrent();
            showConversationList(activity, groupFinal);
        });

        ModernButton btnClose = new ModernButton(ctx, "返回", ModernButton.STYLE_GHOST);
        btnClose.onClick(() -> {
            dismissCurrent();
            showConversationList(activity, groupFinal);
        });

        root.addView(newBtnRow(ctx, btnSave, btnDel, btnClose));
        showPopup(activity, root, 0, "convedit");
    }

    private static void showTemplatePicker(final Activity activity, final String talker,
                                           final boolean isGroup) {
        if (activity == null || activity.isFinishing()) return;
        final Context ctx = activity;
        final ConversationConfig cc = AIBotCore.conversationConfig();
        if (cc == null) {
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }
        List<String> names = cc.templateNames();
        if (names.isEmpty()) {
            toastQuiet(ctx, "暂无模板, 请先在「模板配置」中创建");
            dismissCurrent();
            showConvEdit(activity, talker, isGroup);
            return;
        }

        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, "套用模板"));
        LinearLayout list = new LinearLayout(ctx);
        newScroll(root, list, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.50f));
        for (String name : names) {
            final String tpl = name;
            list.addView(new SettingRow(ctx, "🧩", tpl, entrySummary(cc.getTemplate(tpl)))
                    .arrow(() -> {
                        LogWriter.log(TAG, "click(套用模板): " + tpl + " -> " + talker);
                        try {
                            cc.applyTemplate(talker, tpl);
                            cc.save();
                            reload();
                            toastQuiet(ctx, "已套用模板: " + tpl);
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "applyTemplate err: " + t);
                        }
                        dismissCurrent();
                        showConvEdit(activity, talker, isGroup);
                    }));
        }
        ModernButton btnClose = new ModernButton(ctx, "返回", ModernButton.STYLE_GHOST);
        btnClose.onClick(() -> {
            dismissCurrent();
            showConvEdit(activity, talker, isGroup);
        });
        root.addView(btnClose);
        showPopup(activity, root, 0, "tplpick");
    }

    // ==================== 三级: 模板配置 ====================

    private static void showTemplateList(final Activity activity) {
        LogWriter.log(TAG, "showTemplateList: enter");
        if (activity == null || activity.isFinishing()) return;
        final Context ctx = activity;
        final ConversationConfig cc = AIBotCore.conversationConfig();
        if (cc == null) {
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }

        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, "模板配置"));
        TextView tip = new TextView(ctx);
        tip.setText("模板保存一套「开关 + 人设 + 模型」预设, 可在任意群/联系人的独立配置中一键套用。");
        tip.setTextSize(12);
        tip.setTextColor(AppColors.textTertiary());
        tip.setPadding(0, dp(ctx, 4), 0, dp(ctx, 8));
        root.addView(tip);

        ModernButton btnAdd = new ModernButton(ctx, "＋ 新建模板", ModernButton.STYLE_PRIMARY);
        LinearLayout.LayoutParams lpAdd = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpAdd.setMargins(0, 0, 0, dp(ctx, 8));
        btnAdd.setLayoutParams(lpAdd);
        btnAdd.onClick(() -> {
            dismissCurrent();
            showTemplateEdit(activity, null);
        });
        root.addView(btnAdd);

        LinearLayout list = new LinearLayout(ctx);
        newScroll(root, list, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.50f));
        List<String> names = cc.templateNames();
        if (names.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无模板, 点击上方「新建模板」创建。");
            empty.setTextSize(13);
            empty.setTextColor(AppColors.textTertiary());
            empty.setPadding(dp(ctx, 4), dp(ctx, 12), 0, dp(ctx, 12));
            list.addView(empty);
        } else {
            final String[] pendingDelete = {null};
            for (String name : names) {
                final String tpl = name;
                SettingRow row = new SettingRow(ctx, "🧩", tpl, entrySummary(cc.getTemplate(tpl)));
                row.arrow(null);
                row.setOnClickListener(v -> {
                    if (!tpl.equals(pendingDelete[0])) {
                        pendingDelete[0] = tpl;
                        toastQuiet(ctx, "再次点击可删除, 或长按编辑");
                        return;
                    }
                    try {
                        cc.removeTemplate(tpl);
                        cc.save();
                        reload();
                        toastQuiet(ctx, "已删除模板: " + tpl);
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "removeTemplate err: " + t);
                    }
                    dismissCurrent();
                    showTemplateList(activity);
                });
                row.setOnLongClickListener(v -> {
                    LogWriter.log(TAG, "longclick(模板): 编辑 " + tpl);
                    dismissCurrent();
                    showTemplateEdit(activity, tpl);
                    return true;
                });
                list.addView(row);
            }
        }

        ModernButton btnClose = new ModernButton(ctx, "返回", ModernButton.STYLE_GHOST);
        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpC.setMargins(0, dp(ctx, 12), 0, 0);
        btnClose.setLayoutParams(lpC);
        btnClose.onClick(() -> {
            dismissCurrent();
            show(activity);
        });
        root.addView(btnClose);

        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        showPopup(activity, root, (int) (dm.heightPixels * 0.85f), "tpllist");
    }

    private static void showTemplateEdit(final Activity activity, final String originalName) {
        LogWriter.log(TAG, "showTemplateEdit: enter name=" + originalName);
        if (activity == null || activity.isFinishing()) return;
        final Context ctx = activity;
        final ConversationConfig cc = AIBotCore.conversationConfig();
        final AppConfig cfg = AIBotCore.config();
        if (cc == null || cfg == null) {
            toastQuiet(ctx, "AI 核心未初始化");
            return;
        }
        ConversationConfig.Entry e = originalName != null ? cc.getTemplate(originalName)
                : new ConversationConfig.Entry();
        if (e == null) e = new ConversationConfig.Entry();

        LinearLayout root = newRoot(ctx);
        root.addView(newTitle(ctx, originalName == null ? "新建模板" : "编辑模板"));
        LinearLayout list = new LinearLayout(ctx);
        newScroll(root, list, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.55f));

        final EditText etName = M3Page.input(ctx, "模板名称");
        etName.setText(safe(originalName));
        list.addView(etName);

        list.addView(new SectionHeader(ctx, "开关", "留空则套用后仍可单独调整"));
        boolean effAuto = e.autoReply != null ? e.autoReply : cfg.isAutoReplyInGroups();
        final Switch swAuto = makeSwitch(ctx, effAuto);
        addSwitchRow(list, ctx, swAuto, "💬", "自动回复", "套用后本会话自动回复开关");
        boolean effOnly = e.onlyWhenMentioned != null ? e.onlyWhenMentioned : cfg.isOnlyWhenMentioned();
        final Switch swOnly = makeSwitch(ctx, effOnly);
        addSwitchRow(list, ctx, swOnly, "📣", "仅被@时回复", "群聊中仅被 @ / 唤醒词触发");
        boolean effTts = e.ttsEnabled != null ? e.ttsEnabled : cfg.isTtsEnabled();
        final Switch swTts = makeSwitch(ctx, effTts);
        addSwitchRow(list, ctx, swTts, "🔊", "语音消息发送", "开=转语音发出; 关=发文本");

        list.addView(new SectionHeader(ctx, "人设与模型", "留空表示套用后继承全局"));
        final EditText etSys = M3Page.input(ctx, "人设提示词 (可留空)");
        etSys.setSingleLine(false);
        etSys.setMinLines(3);
        etSys.setGravity(Gravity.TOP);
        etSys.setText(safe(e.systemPrompt));
        list.addView(etSys);
        final EditText etModel = M3Page.input(ctx, "模型 (可留空)");
        etModel.setText(safe(e.model));
        list.addView(etModel);

        ModernButton btnSave = new ModernButton(ctx, "保存", ModernButton.STYLE_PRIMARY);
        btnSave.onClick(() -> {
            String name = str(etName).trim();
            if (TextUtils.isEmpty(name)) {
                toastQuiet(ctx, "请输入模板名称");
                return;
            }
            LogWriter.log(TAG, "click(模板): 保存 " + name);
            try {
                if (originalName != null && !name.equals(originalName)) {
                    cc.removeTemplate(originalName);
                }
                ConversationConfig.Entry out = new ConversationConfig.Entry();
                out.autoReply = swAuto.isChecked();
                out.onlyWhenMentioned = swOnly.isChecked();
                out.ttsEnabled = swTts.isChecked();
                out.systemPrompt = str(etSys).trim();
                out.model = str(etModel).trim();
                cc.putTemplate(name, out);
                cc.save();
                reload();
                toastQuiet(ctx, "已保存模板");
            } catch (Throwable t) {
                LogWriter.log(TAG, "template save err: " + t);
                toastQuiet(ctx, "保存失败");
            }
            dismissCurrent();
            showTemplateList(activity);
        });

        ModernButton btnDel = new ModernButton(ctx, "删除", ModernButton.STYLE_DANGER);
        btnDel.onClick(() -> {
            LogWriter.log(TAG, "click(模板): 删除 " + originalName);
            try {
                String name = originalName != null ? originalName : str(etName).trim();
                if (!TextUtils.isEmpty(name)) {
                    cc.removeTemplate(name);
                    cc.save();
                    reload();
                    toastQuiet(ctx, "已删除模板");
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "template del err: " + t);
            }
            dismissCurrent();
            showTemplateList(activity);
        });

        ModernButton btnClose = new ModernButton(ctx, "返回", ModernButton.STYLE_GHOST);
        btnClose.onClick(() -> {
            dismissCurrent();
            showTemplateList(activity);
        });

        root.addView(newBtnRow(ctx, btnSave, btnDel, btnClose));
        showPopup(activity, root, 0, "tpledit");
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
