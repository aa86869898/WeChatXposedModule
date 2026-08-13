package com.leshao.v3.ai;

import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.R;

public class AiControlActivity extends android.app.Activity {

    private static final String TAG = "AiControlActivity";

    private CheckBox cbMaster, cbSummary, cbReply, cbMemory, cbPolish, cbEmotion, cbKeyword, cbCustomPrompt;
    private RadioButton rbOpenai, rbDeepseek;
    private EditText etOpenaiBase, etOpenaiKey, etOpenaiModel;
    private EditText etDeepseekBase, etDeepseekKey, etDeepseekModel;
    private EditText etPromptSummary, etPromptReply, etPromptPolish, etPromptEmotion, etPromptKeyword;
    private SeekBar sbSummaryCount, sbMemoryCount, sbMemoryDuration, sbReplyCount;
    private TextView tvSummaryCount, tvMemoryCount, tvMemoryDuration, tvReplyCount;
    private RadioButton rbFill, rbSend;

    @Override
    protected void onCreate(Bundle b) {
        AiLog.i("=== onCreate START ===");
        try {
            super.onCreate(b);
            AiLog.i("super.onCreate OK");
            AiConfig.init(this);
            AiLog.i("AiConfig.init OK");
            setContentView(R.layout.activity_ai_control);
            AiLog.i("setContentView OK");
            bind();
            AiLog.i("bind OK");
            load();
            AiLog.i("load OK");
            wire();
            AiLog.i("wire OK === onCreate DONE ===");
        } catch (Throwable t) {
            String msg = t.getClass().getName() + ": " + t.getMessage();
            AiLog.e("onCreate FAIL: " + msg, t);
            try { Toast.makeText(this, "AI 面板打开失败: " + msg, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
            finish();
        }
    }

    private void bind() {
        cbMaster = findViewById(R.id.cb_master);
        cbSummary = findViewById(R.id.cb_summary);
        cbReply = findViewById(R.id.cb_reply);
        cbMemory = findViewById(R.id.cb_memory);
        cbPolish = findViewById(R.id.cb_polish);
        cbEmotion = findViewById(R.id.cb_emotion);
        cbKeyword = findViewById(R.id.cb_keyword);
        cbCustomPrompt = findViewById(R.id.cb_custom_prompt);
        rbOpenai = findViewById(R.id.rb_openai);
        rbDeepseek = findViewById(R.id.rb_deepseek);
        etOpenaiBase = findViewById(R.id.et_openai_base);
        etOpenaiKey = findViewById(R.id.et_openai_key);
        etOpenaiModel = findViewById(R.id.et_openai_model);
        etDeepseekBase = findViewById(R.id.et_deepseek_base);
        etDeepseekKey = findViewById(R.id.et_deepseek_key);
        etDeepseekModel = findViewById(R.id.et_deepseek_model);
        etPromptSummary = findViewById(R.id.et_prompt_summary);
        etPromptReply = findViewById(R.id.et_prompt_reply);
        etPromptPolish = findViewById(R.id.et_prompt_polish);
        etPromptEmotion = findViewById(R.id.et_prompt_emotion);
        etPromptKeyword = findViewById(R.id.et_prompt_keyword);
        sbSummaryCount = findViewById(R.id.sb_summary_count);
        sbMemoryCount = findViewById(R.id.sb_memory_count);
        sbMemoryDuration = findViewById(R.id.sb_memory_duration);
        sbReplyCount = findViewById(R.id.sb_reply_count);
        tvSummaryCount = findViewById(R.id.tv_summary_count);
        tvMemoryCount = findViewById(R.id.tv_memory_count);
        tvMemoryDuration = findViewById(R.id.tv_memory_duration);
        tvReplyCount = findViewById(R.id.tv_reply_count);
        rbFill = findViewById(R.id.rb_fill);
        rbSend = findViewById(R.id.rb_send);

        StringBuilder missing = new StringBuilder();
        checkMissing(missing, "cb_master", cbMaster);
        checkMissing(missing, "cb_summary", cbSummary);
        checkMissing(missing, "cb_reply", cbReply);
        checkMissing(missing, "cb_memory", cbMemory);
        checkMissing(missing, "cb_polish", cbPolish);
        checkMissing(missing, "cb_emotion", cbEmotion);
        checkMissing(missing, "cb_keyword", cbKeyword);
        checkMissing(missing, "cb_custom_prompt", cbCustomPrompt);
        checkMissing(missing, "rb_openai", rbOpenai);
        checkMissing(missing, "rb_deepseek", rbDeepseek);
        checkMissing(missing, "et_openai_base", etOpenaiBase);
        checkMissing(missing, "et_openai_key", etOpenaiKey);
        checkMissing(missing, "et_openai_model", etOpenaiModel);
        checkMissing(missing, "et_deepseek_base", etDeepseekBase);
        checkMissing(missing, "et_deepseek_key", etDeepseekKey);
        checkMissing(missing, "et_deepseek_model", etDeepseekModel);
        checkMissing(missing, "et_prompt_summary", etPromptSummary);
        checkMissing(missing, "et_prompt_reply", etPromptReply);
        checkMissing(missing, "et_prompt_polish", etPromptPolish);
        checkMissing(missing, "et_prompt_emotion", etPromptEmotion);
        checkMissing(missing, "et_prompt_keyword", etPromptKeyword);
        checkMissing(missing, "sb_summary_count", sbSummaryCount);
        checkMissing(missing, "sb_memory_count", sbMemoryCount);
        checkMissing(missing, "sb_memory_duration", sbMemoryDuration);
        checkMissing(missing, "sb_reply_count", sbReplyCount);
        checkMissing(missing, "tv_summary_count", tvSummaryCount);
        checkMissing(missing, "tv_memory_count", tvMemoryCount);
        checkMissing(missing, "tv_memory_duration", tvMemoryDuration);
        checkMissing(missing, "tv_reply_count", tvReplyCount);
        checkMissing(missing, "rb_fill", rbFill);
        checkMissing(missing, "rb_send", rbSend);

        if (missing.length() > 0) {
            AiLog.w("bind 缺失控件: " + missing.toString().trim());
        } else {
            AiLog.i("bind 全部 31 个控件找到");
        }
    }

    private static void checkMissing(StringBuilder sb, String name, Object v) {
        if (v == null) sb.append(name).append(' ');
    }

    private void load() {
        cbMaster.setChecked(AiConfig.masterEnabled());
        cbSummary.setChecked(AiConfig.summaryEnabled());
        cbReply.setChecked(AiConfig.replyEnabled());
        cbMemory.setChecked(AiConfig.memoryEnabled());
        cbPolish.setChecked(AiConfig.polishEnabled());
        cbEmotion.setChecked(AiConfig.emotionEnabled());
        cbKeyword.setChecked(AiConfig.keywordEnabled());
        cbCustomPrompt.setChecked(AiConfig.customPromptEnabled());
        if (AiConfig.provider() == 0) rbOpenai.setChecked(true); else rbDeepseek.setChecked(true);
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
        sbMemoryDuration.setProgress((int)(AiConfig.memoryDurationMs() / 60_000L));
        sbReplyCount.setProgress(AiConfig.replyCount());
        if (AiConfig.replyMode() == 1) rbSend.setChecked(true); else rbFill.setChecked(true);
        refreshLabels();
    }

    private void wire() {
        cbMaster.setOnCheckedChangeListener((v, c) -> AiConfig.setMaster(c));
        cbSummary.setOnCheckedChangeListener((v, c) -> AiConfig.setSummary(c));
        cbReply.setOnCheckedChangeListener((v, c) -> AiConfig.setReply(c));
        cbMemory.setOnCheckedChangeListener((v, c) -> AiConfig.setMemory(c));
        cbPolish.setOnCheckedChangeListener((v, c) -> AiConfig.setPolish(c));
        cbEmotion.setOnCheckedChangeListener((v, c) -> AiConfig.setEmotion(c));
        cbKeyword.setOnCheckedChangeListener((v, c) -> AiConfig.setKeyword(c));
        cbCustomPrompt.setOnCheckedChangeListener((v, c) -> AiConfig.setCustomPrompt(c));

        rbOpenai.setOnCheckedChangeListener((v, c) -> { if (c) AiConfig.setProvider(0); });
        rbDeepseek.setOnCheckedChangeListener((v, c) -> { if (c) AiConfig.setProvider(1); });
        rbFill.setOnCheckedChangeListener((v, c) -> { if (c) AiConfig.setReplyMode(0); });
        rbSend.setOnCheckedChangeListener((v, c) -> { if (c) AiConfig.setReplyMode(1); });

        SeekBar.OnSeekBarChangeListener l1 = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { refreshLabels(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { saveSliders(); }
        };
        sbSummaryCount.setOnSeekBarChangeListener(l1);
        sbMemoryCount.setOnSeekBarChangeListener(l1);
        sbMemoryDuration.setOnSeekBarChangeListener(l1);
        sbReplyCount.setOnSeekBarChangeListener(l1);

        Button save = findViewById(R.id.btn_save);
        save.setOnClickListener(v -> {
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
            saveSliders();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        });
    }

    private void saveSliders() {
        AiConfig.setSummaryCount(sbSummaryCount.getProgress());
        AiConfig.setMemoryCount(sbMemoryCount.getProgress());
        AiConfig.setMemoryDuration(sbMemoryDuration.getProgress());
        AiConfig.setReplyCount(sbReplyCount.getProgress());
    }

    private void refreshLabels() {
        tvSummaryCount.setText("总结抓取条数：" + sbSummaryCount.getProgress() + " 条");
        tvMemoryCount.setText("记忆条数：" + sbMemoryCount.getProgress() + " 条");
        tvMemoryDuration.setText("记忆时长：" + sbMemoryDuration.getProgress() + " 分钟");
        tvReplyCount.setText("推荐回复条数：" + sbReplyCount.getProgress() + " 条");
    }
}
