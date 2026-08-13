package com.leshao.v3.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.LogWriter;

import java.util.List;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;

public class AiPanelDialog {

    private static final String TAG = "AiPanelDialog";

    public static void show(Activity act) {
        LogWriter.log(TAG, "=== show START ===");
        try {
            AiConfig.init(act);
            LogWriter.log(TAG, "AiConfig.init OK");

            Context ctx = act;
            float d = act.getResources().getDisplayMetrics().density;

            final Switch swMaster = CandyUi.newSwitch(act);
            final Switch swSummary = CandyUi.newSwitch(act);
            final Switch swReply = CandyUi.newSwitch(act);
            final Switch swMemory = CandyUi.newSwitch(act);
            final Switch swPolish = CandyUi.newSwitch(act);
            final Switch swEmotion = CandyUi.newSwitch(act);
            final Switch swKeyword = CandyUi.newSwitch(act);
            final Switch swCustomPrompt = CandyUi.newSwitch(act);

            final EditText etOpenaiBase = makeEditText(act, "OpenAI Base URL");
            final EditText etOpenaiKey = makeEditText(act, "OpenAI API Key");
            etOpenaiKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            final EditText etOpenaiModel = makeEditText(act, "OpenAI 模型名");
            final EditText etDeepseekBase = makeEditText(act, "DeepSeek Base URL");
            final EditText etDeepseekKey = makeEditText(act, "DeepSeek API Key");
            etDeepseekKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            final EditText etDeepseekModel = makeEditText(act, "DeepSeek 模型名");

            final TextView btnOpenai = makePillButton(act, d, "OpenAI", false);
            final TextView btnDeepseek = makePillButton(act, d, "DeepSeek", false);

            final TextView tvSummaryCount = makeLabel(act, "");
            final SeekBar sbSummaryCount = makeSeekBar(act, 500);
            final TextView tvMemoryCount = makeLabel(act, "");
            final SeekBar sbMemoryCount = makeSeekBar(act, 100);
            final TextView tvMemoryDuration = makeLabel(act, "");
            final SeekBar sbMemoryDuration = makeSeekBar(act, 120);
            final TextView tvReplyCount = makeLabel(act, "");
            final SeekBar sbReplyCount = makeSeekBar(act, 6);

            final RadioButton rbFill = makeRadioButton(act, "仅填入输入框");
            final RadioButton rbSend = makeRadioButton(act, "直接发送");

            final EditText etPromptSummary = makeEditTextMultiline(act, "总结 Prompt");
            final EditText etPromptReply = makeEditTextMultiline(act, "推荐回复 Prompt（%d 会被替换为条数）");
            final EditText etPromptPolish = makeEditTextMultiline(act, "润色 Prompt（%s 会被替换为语气）");
            final EditText etPromptEmotion = makeEditTextMultiline(act, "情绪分析 Prompt");
            final EditText etPromptKeyword = makeEditTextMultiline(act, "关键词提取 Prompt");

            final Button btnFetchModels = new Button(act);
            btnFetchModels.setText("获取模型列表");
            btnFetchModels.setTextColor(AppColors.whiteTextOnAccent());
            btnFetchModels.setBackgroundColor(AppColors.accent());
            LinearLayout.LayoutParams fetchLp = new LinearLayout.LayoutParams(-1, -2);
            fetchLp.setMargins(0, 0, 0, dp(d, 10));
            btnFetchModels.setLayoutParams(fetchLp);

            final Button btnSave = new Button(act);
            btnSave.setText("保存全部配置");
            btnSave.setTextColor(AppColors.whiteTextOnAccent());
            btnSave.setBackgroundColor(AppColors.accent());
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, -2);
            btnLp.setMargins((int) (12 * d), (int) (12 * d), (int) (12 * d), (int) (16 * d));
            btnSave.setLayoutParams(btnLp);

            ScrollView sv = new ScrollView(ctx);
            sv.setFillViewport(true);

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackground(CandyUi.pageGradient());

            root.addView(makeTopBar(ctx, d, "AI 聊天助手"));
            root.addView(candyDivider(ctx, d));

            LinearLayout card1 = makeCard(ctx, d);
            card1.addView(makeSectionRow(ctx, d, 0x1F4CB, "功能开关"));
            card1.addView(makeSwitchRow(ctx, d, "总开关（启用所有 AI 功能）", swMaster));
            card1.addView(makeInnerDivider(ctx, d));
            card1.addView(makeSwitchRow(ctx, d, "聊天总结", swSummary));
            card1.addView(makeInnerDivider(ctx, d));
            card1.addView(makeSwitchRow(ctx, d, "来消息推荐回复", swReply));
            card1.addView(makeInnerDivider(ctx, d));
            card1.addView(makeSwitchRow(ctx, d, "多轮上下文记忆", swMemory));
            card1.addView(makeInnerDivider(ctx, d));
            card1.addView(makeSwitchRow(ctx, d, "文本润色", swPolish));
            card1.addView(makeInnerDivider(ctx, d));
            card1.addView(makeSwitchRow(ctx, d, "情绪分析", swEmotion));
            card1.addView(makeInnerDivider(ctx, d));
            card1.addView(makeSwitchRow(ctx, d, "关键词/待办提取", swKeyword));
            card1.addView(makeInnerDivider(ctx, d));
            card1.addView(makeSwitchRow(ctx, d, "自定义 Prompt 模板", swCustomPrompt));
            root.addView(card1);
            root.addView(candyDivider(ctx, d));

            LinearLayout card2 = makeCard(ctx, d);
            card2.addView(makeSectionRow(ctx, d, 0x2699, "AI 后端"));

            LinearLayout providerRow = new LinearLayout(ctx);
            providerRow.setOrientation(LinearLayout.HORIZONTAL);
            providerRow.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(-1, -2);
            prLp.setMargins(0, 0, 0, dp(d, 12));
            providerRow.setLayoutParams(prLp);
            btnOpenai.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
            btnDeepseek.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
            providerRow.addView(btnOpenai);
            View pSpacer = new View(ctx);
            pSpacer.setLayoutParams(new LinearLayout.LayoutParams(dp(d, 12), 0));
            providerRow.addView(pSpacer);
            providerRow.addView(btnDeepseek);
            card2.addView(providerRow);

            final LinearLayout openaiBox = new LinearLayout(ctx);
            openaiBox.setOrientation(LinearLayout.VERTICAL);
            openaiBox.addView(etOpenaiBase);
            openaiBox.addView(etOpenaiKey);
            openaiBox.addView(etOpenaiModel);
            card2.addView(openaiBox);

            final LinearLayout deepseekBox = new LinearLayout(ctx);
            deepseekBox.setOrientation(LinearLayout.VERTICAL);
            deepseekBox.addView(etDeepseekBase);
            deepseekBox.addView(etDeepseekKey);
            deepseekBox.addView(etDeepseekModel);
            card2.addView(deepseekBox);

            card2.addView(makeInnerDivider(ctx, d));
            card2.addView(btnFetchModels);
            root.addView(card2);
            root.addView(candyDivider(ctx, d));

            LinearLayout card3 = makeCard(ctx, d);
            card3.addView(makeSectionRow(ctx, d, 0x1F4CA, "AI 参数"));
            card3.addView(tvSummaryCount);
            card3.addView(sbSummaryCount);
            card3.addView(tvMemoryCount);
            card3.addView(sbMemoryCount);
            card3.addView(tvMemoryDuration);
            card3.addView(sbMemoryDuration);
            card3.addView(tvReplyCount);
            card3.addView(sbReplyCount);
            root.addView(card3);
            root.addView(candyDivider(ctx, d));

            LinearLayout card4 = makeCard(ctx, d);
            card4.addView(makeSectionRow(ctx, d, 0x1F4AC, "回复模式"));
            card4.addView(rbFill);
            card4.addView(rbSend);
            root.addView(card4);
            root.addView(candyDivider(ctx, d));

            LinearLayout card5 = makeCard(ctx, d);
            card5.addView(makeSectionRow(ctx, d, 0x1F4DD, "自定义 Prompt 模板"));
            card5.addView(etPromptSummary);
            card5.addView(etPromptReply);
            card5.addView(etPromptPolish);
            card5.addView(etPromptEmotion);
            card5.addView(etPromptKeyword);
            root.addView(card5);
            root.addView(candyDivider(ctx, d));

            root.addView(btnSave);

            sv.addView(root);
            LogWriter.log(TAG, "UI 构建 OK");

            final Runnable refreshLabels = () -> {
                tvSummaryCount.setText("总结抓取条数：" + sbSummaryCount.getProgress() + " 条");
                tvMemoryCount.setText("记忆条数：" + sbMemoryCount.getProgress() + " 条");
                tvMemoryDuration.setText("记忆时长：" + sbMemoryDuration.getProgress() + " 分钟");
                tvReplyCount.setText("推荐回复条数：" + sbReplyCount.getProgress() + " 条");
            };
            final Runnable saveSliders = () -> {
                AiConfig.setSummaryCount(sbSummaryCount.getProgress());
                AiConfig.setMemoryCount(sbMemoryCount.getProgress());
                AiConfig.setMemoryDuration(sbMemoryDuration.getProgress());
                AiConfig.setReplyCount(sbReplyCount.getProgress());
            };
            final Runnable refreshProvider = () -> {
                boolean openai = AiConfig.provider() == 0;
                btnOpenai.setBackground(CandyUi.pillBg(openai, ctx));
                btnOpenai.setTextColor(openai ? AppColors.whiteTextOnAccent() : AppColors.text1());
                btnDeepseek.setBackground(CandyUi.pillBg(!openai, ctx));
                btnDeepseek.setTextColor(!openai ? AppColors.whiteTextOnAccent() : AppColors.text1());
                openaiBox.setVisibility(openai ? View.VISIBLE : View.GONE);
                deepseekBox.setVisibility(openai ? View.GONE : View.VISIBLE);
            };

            swMaster.setChecked(AiConfig.masterEnabled());
            swSummary.setChecked(AiConfig.summaryEnabled());
            swReply.setChecked(AiConfig.replyEnabled());
            swMemory.setChecked(AiConfig.memoryEnabled());
            swPolish.setChecked(AiConfig.polishEnabled());
            swEmotion.setChecked(AiConfig.emotionEnabled());
            swKeyword.setChecked(AiConfig.keywordEnabled());
            swCustomPrompt.setChecked(AiConfig.customPromptEnabled());
            refreshProvider.run();
            etOpenaiBase.setText(AiConfig.openaiBaseUrl());
            etOpenaiKey.setText(AiConfig.openaiKey());
            etOpenaiModel.setText(AiConfig.openaiModel());
            etDeepseekBase.setText(AiConfig.deepseekBaseUrl());
            etDeepseekKey.setText(AiConfig.deepseekKey());
            etDeepseekModel.setText(AiConfig.deepseekModel());
            etPromptSummary.setText(AiConfig.promptSummary());
            etPromptReply.setText(AiConfig.promptReply());
            etPromptPolish.setText(AiConfig.promptPolish());
            etPromptEmotion.setText(AiConfig.promptEmotion());
            etPromptKeyword.setText(AiConfig.promptKeyword());
            sbSummaryCount.setProgress(AiConfig.summaryCount());
            sbMemoryCount.setProgress(AiConfig.memoryCount());
            sbMemoryDuration.setProgress((int) (AiConfig.memoryDurationMs() / 60_000L));
            sbReplyCount.setProgress(AiConfig.replyCount());
            if (AiConfig.replyMode() == 1) rbSend.setChecked(true); else rbFill.setChecked(true);
            refreshLabels.run();
            LogWriter.log(TAG, "配置加载 OK");

            swMaster.setOnCheckedChangeListener((v, c) -> AiConfig.setMaster(c));
            swSummary.setOnCheckedChangeListener((v, c) -> AiConfig.setSummary(c));
            swReply.setOnCheckedChangeListener((v, c) -> AiConfig.setReply(c));
            swMemory.setOnCheckedChangeListener((v, c) -> AiConfig.setMemory(c));
            swPolish.setOnCheckedChangeListener((v, c) -> AiConfig.setPolish(c));
            swEmotion.setOnCheckedChangeListener((v, c) -> AiConfig.setEmotion(c));
            swKeyword.setOnCheckedChangeListener((v, c) -> AiConfig.setKeyword(c));
            swCustomPrompt.setOnCheckedChangeListener((v, c) -> AiConfig.setCustomPrompt(c));
            btnOpenai.setOnClickListener(v -> { AiConfig.setProvider(0); refreshProvider.run(); });
            btnDeepseek.setOnClickListener(v -> { AiConfig.setProvider(1); refreshProvider.run(); });
            rbFill.setOnCheckedChangeListener((v, c) -> { if (c) AiConfig.setReplyMode(0); });
            rbSend.setOnCheckedChangeListener((v, c) -> { if (c) AiConfig.setReplyMode(1); });

            SeekBar.OnSeekBarChangeListener l1 = new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean u) { refreshLabels.run(); }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) { saveSliders.run(); }
            };
            sbSummaryCount.setOnSeekBarChangeListener(l1);
            sbMemoryCount.setOnSeekBarChangeListener(l1);
            sbMemoryDuration.setOnSeekBarChangeListener(l1);
            sbReplyCount.setOnSeekBarChangeListener(l1);

            btnFetchModels.setOnClickListener(v -> {
                boolean isOpenai = AiConfig.provider() == 0;
                String base = isOpenai ? etOpenaiBase.getText().toString().trim() : etDeepseekBase.getText().toString().trim();
                String key = isOpenai ? etOpenaiKey.getText().toString().trim() : etDeepseekKey.getText().toString().trim();
                if (base.isEmpty() || key.isEmpty()) {
                    Toast.makeText(act, "请先填写 Base URL 和 API Key", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(act, "正在获取模型列表...", Toast.LENGTH_SHORT).show();
                LogWriter.log(TAG, "获取模型列表: provider=" + (isOpenai ? "openai" : "deepseek") + " base=" + base);
                AiClient.listModelsAsync(base, key, new AiClient.ModelsCallback() {
                    @Override public void onModels(List<String> models) {
                        act.runOnUiThread(() -> showModelPicker(act, models, isOpenai, etOpenaiModel, etDeepseekModel));
                    }
                    @Override public void onError(String msg) {
                        act.runOnUiThread(() -> Toast.makeText(act, "获取失败: " + msg, Toast.LENGTH_LONG).show());
                    }
                });
            });

            btnSave.setOnClickListener(v -> {
                AiConfig.setOpenaiBaseUrl(etOpenaiBase.getText().toString());
                AiConfig.setOpenaiKey(etOpenaiKey.getText().toString());
                AiConfig.setOpenaiModel(etOpenaiModel.getText().toString());
                AiConfig.setDeepseekBaseUrl(etDeepseekBase.getText().toString());
                AiConfig.setDeepseekKey(etDeepseekKey.getText().toString());
                AiConfig.setDeepseekModel(etDeepseekModel.getText().toString());
                AiConfig.setPromptSummary(etPromptSummary.getText().toString());
                AiConfig.setPromptReply(etPromptReply.getText().toString());
                AiConfig.setPromptPolish(etPromptPolish.getText().toString());
                AiConfig.setPromptEmotion(etPromptEmotion.getText().toString());
                AiConfig.setPromptKeyword(etPromptKeyword.getText().toString());
                saveSliders.run();
                LogWriter.log(TAG, "配置已保存");
                Toast.makeText(act, "已保存", Toast.LENGTH_SHORT).show();
            });
            LogWriter.log(TAG, "事件绑定 OK");

            int theme = AppColors.isDarkMode()
                    ? android.R.style.Theme_DeviceDefault_NoActionBar
                    : android.R.style.Theme_DeviceDefault_Light_NoActionBar;
            AlertDialog.Builder b = new AlertDialog.Builder(act, theme);
            b.setView(sv);
            b.setCancelable(true);
            AlertDialog dlg = b.create();
            Window w = dlg.getWindow();
            if (w != null) {
                w.setLayout(-1, -1);
                w.setBackgroundDrawable(new ColorDrawable(AppColors.bg()));
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
            dlg.show();
            LogWriter.log(TAG, "=== show DONE ===");
        } catch (Throwable t) {
            LogWriter.log(TAG, "show FAIL: " + t.getClass().getName() + ": " + t.getMessage());
            try { Toast.makeText(act, "AI 面板打开失败: " + t.getMessage(), Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
        }
    }

    private static int dp(float d, int v) {
        return (int) (v * d + 0.5f);
    }

    private static View makeTopBar(Context ctx, float d, String title) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(d, 16), dp(d, 14), dp(d, 16), dp(d, 14));
        bar.setBackgroundColor(AppColors.accent());
        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(18);
        tv.setTextColor(AppColors.whiteTextOnAccent());
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        bar.addView(tv);
        return bar;
    }

    private static View candyDivider(Context ctx, float d) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{AppColors.candyPink(), AppColors.candyYellow(), AppColors.accent(), AppColors.candyPink()});
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (1.5f * d));
        lp.setMargins((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        v.setLayoutParams(lp);
        v.setBackground(gd);
        return v;
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(d, 12), dp(d, 12), dp(d, 12), dp(d, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(d, 8));
        bg.setColor(AppColors.card());
        card.setBackground(bg);
        return card;
    }

    private static View makeSectionRow(Context ctx, float d, int emoji, String title) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(d, 4));
        row.setLayoutParams(lp);
        TextView icon = new TextView(ctx);
        icon.setText(new String(Character.toChars(emoji)));
        icon.setTextSize(16);
        icon.setPadding(0, 0, dp(d, 8), 0);
        row.addView(icon);
        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(AppColors.accent());
        tv.setTypeface(null, Typeface.BOLD);
        row.addView(tv);
        return row;
    }

    private static View makeSwitchRow(Context ctx, float d, String label, Switch sw) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(d, 8), 0, dp(d, 8));
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(15);
        tv.setTextColor(AppColors.text1());
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(tv);
        row.addView(sw);
        return row;
    }

    private static View makeInnerDivider(Context ctx, float d) {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.setMargins(0, dp(d, 2), 0, dp(d, 2));
        v.setLayoutParams(lp);
        v.setBackgroundColor(AppColors.divider());
        return v;
    }

    private static TextView makePillButton(Context ctx, float d, String text, boolean selected) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setTextColor(selected ? AppColors.whiteTextOnAccent() : AppColors.text1());
        btn.setPadding(dp(d, 16), dp(d, 10), dp(d, 16), dp(d, 10));
        btn.setBackground(CandyUi.pillBg(selected, ctx));
        return btn;
    }

    private static RadioButton makeRadioButton(Context ctx, String text) {
        RadioButton rb = new RadioButton(ctx);
        rb.setText(text);
        rb.setTextSize(15);
        rb.setTextColor(AppColors.text1());
        try {
            rb.setButtonTintList(ColorStateList.valueOf(AppColors.accent()));
        } catch (Throwable ignored) {}
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(ctx.getResources().getDisplayMetrics().density, 8));
        rb.setLayoutParams(lp);
        return rb;
    }

    private static EditText makeEditText(Context ctx, String hint) {
        float d = ctx.getResources().getDisplayMetrics().density;
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setSingleLine(true);
        et.setTextSize(14);
        et.setTextColor(AppColors.text1());
        et.setHintTextColor(AppColors.text3());
        et.setBackground(thickInputBg(ctx));
        et.setMinHeight(dp(d, 48));
        et.setGravity(Gravity.CENTER_VERTICAL);
        et.setPadding(dp(d, 14), 0, dp(d, 14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(d, 14));
        et.setLayoutParams(lp);
        return et;
    }

    private static EditText makeEditTextMultiline(Context ctx, String hint) {
        float d = ctx.getResources().getDisplayMetrics().density;
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setMinLines(3);
        et.setGravity(Gravity.TOP);
        et.setTextSize(14);
        et.setTextColor(AppColors.text1());
        et.setHintTextColor(AppColors.text3());
        et.setBackground(thickInputBg(ctx));
        et.setPadding(dp(d, 14), dp(d, 12), dp(d, 14), dp(d, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(d, 14));
        et.setLayoutParams(lp);
        return et;
    }

    private static GradientDrawable thickInputBg(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius((int) (8 * d));
        gd.setColor(AppColors.inputBg());
        gd.setStroke((int) (2 * d), AppColors.accent());
        return gd;
    }

    private static void showModelPicker(Activity act, List<String> models, boolean isOpenai,
                                        EditText etOpenaiModel, EditText etDeepseekModel) {
        if (models == null || models.isEmpty()) {
            Toast.makeText(act, "未获取到模型列表", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = models.toArray(new String[0]);
        new AlertDialog.Builder(act)
                .setTitle("选择默认模型")
                .setItems(items, (dlg, which) -> {
                    String m = items[which];
                    if (isOpenai) {
                        etOpenaiModel.setText(m);
                        AiConfig.setOpenaiModel(m);
                    } else {
                        etDeepseekModel.setText(m);
                        AiConfig.setDeepseekModel(m);
                    }
                    Toast.makeText(act, "已选择模型: " + m, Toast.LENGTH_SHORT).show();
                })
                .setCancelable(true)
                .show();
    }

    private static TextView makeLabel(Context ctx, String text) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(d, 4));
        tv.setLayoutParams(lp);
        return tv;
    }

    private static SeekBar makeSeekBar(Context ctx, int max) {
        float d = ctx.getResources().getDisplayMetrics().density;
        SeekBar sb = new SeekBar(ctx);
        sb.setMax(max);
        try {
            sb.setProgressTintList(ColorStateList.valueOf(AppColors.accent()));
            sb.setThumbTintList(ColorStateList.valueOf(AppColors.accent()));
        } catch (Throwable ignored) {}
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(d, 8));
        sb.setLayoutParams(lp);
        return sb;
    }
}
