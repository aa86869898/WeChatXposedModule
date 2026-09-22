package com.leshao.ai.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import com.leshao.ai.config.AppConfig;
import com.leshao.v3.R;
import com.leshao.ai.api.model.ProviderType;

import java.util.Locale;

/**
 * 乐小AI 主设置页（独立于微信宿主进程的普通 Activity）。
 *
 * <p>通过图标或控制面板打开，用于配置模块的全局开关、服务商、模型、
 * 人设、触发规则与回复策略。所有配置读写 {@link AppConfig}（磁盘 JSON）。</p>
 *
 * <p>纯 View + XML 实现，不依赖 Compose / Room / Retrofit。</p>
 */
public class SettingsActivity extends Activity {

    // ---------- 控件 ----------
    private Switch switchEnabled;
    private Spinner spinnerProvider;
    private EditText editBaseUrl, editApiKey, editModel, editSystemPrompt,
            editBotName, editWakeKeyword, editMemoryCount;
    private SeekBar seekTemperature;
    private TextView textTemperatureValue;
    private Switch switchAtTrigger, switchTts, switchAutoGroup, switchAutoPrivate;
    private Button btnWhitelist, btnReset, btnSave;

    /** 当前配置对象（同步读取/写入磁盘 JSON）。 */
    private AppConfig config;

    /** SeekBar 进度 → 温度（0..200 → 0.0..2.0）。 */
    private static final double TEMP_SCALE = 100.0 / 2.0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // 从宿主 data 目录构造并加载磁盘配置
        config = new AppConfig(getFilesDir().getParent());
        config.load();

        bindViews();
        populateViews();
        wireListeners();
    }

    private void bindViews() {
        switchEnabled = findViewById(R.id.switch_enabled);
        spinnerProvider = findViewById(R.id.spinner_provider);
        editBaseUrl = findViewById(R.id.edit_base_url);
        editApiKey = findViewById(R.id.edit_api_key);
        editModel = findViewById(R.id.edit_model);
        editSystemPrompt = findViewById(R.id.edit_system_prompt);
        seekTemperature = findViewById(R.id.seek_temperature);
        textTemperatureValue = findViewById(R.id.text_temperature_value);
        editBotName = findViewById(R.id.edit_bot_name);
        editWakeKeyword = findViewById(R.id.edit_wake_keyword);
        switchAtTrigger = findViewById(R.id.switch_at_trigger);
        btnWhitelist = findViewById(R.id.btn_whitelist);
        switchTts = findViewById(R.id.switch_tts);
        switchAutoGroup = findViewById(R.id.switch_auto_group);
        switchAutoPrivate = findViewById(R.id.switch_auto_private);
        editMemoryCount = findViewById(R.id.edit_memory_count);
        btnReset = findViewById(R.id.btn_reset);
        btnSave = findViewById(R.id.btn_save);
    }

    private void populateViews() {
        // 服务商下拉（与 ProviderType 对齐，顺序即界面展示顺序）
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.provider_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProvider.setAdapter(adapter);
        spinnerProvider.setSelection(indexOfProvider(config.getProviderType()));

        switchEnabled.setChecked(config.isEnabled());
        editBaseUrl.setText(config.getBaseUrl());
        editApiKey.setText(config.getApiKey());
        editModel.setText(config.getModel());
        editSystemPrompt.setText(config.getSystemPrompt());
        editBotName.setText(config.getBotName());
        editWakeKeyword.setText(config.getWakeKeyword());
        switchAtTrigger.setChecked(config.isOnlyWhenMentioned());
        switchTts.setChecked(config.isTtsEnabled());
        switchAutoGroup.setChecked(config.isAutoReplyInGroups());
        switchAutoPrivate.setChecked(config.isAutoReplyInPrivate());
        editMemoryCount.setText(String.valueOf(config.getMaxHistoryMessages()));

        // 温度：double(0..2) → SeekBar(0..200)
        int progress = (int) Math.round(config.getTemperature() * TEMP_SCALE);
        seekTemperature.setProgress(Math.max(0, Math.min(200, progress)));
        updateTemperatureLabel(seekTemperature.getProgress());

        btnWhitelist.setText(getString(R.string.btn_manage_whitelist));
    }

    private void wireListeners() {
        seekTemperature.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateTemperatureLabel(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // 白名单管理入口
        btnWhitelist.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveFieldsToConfig(); // 先保存，保证状态一致
                startActivity(new Intent(SettingsActivity.this, WhitelistActivity.class));
            }
        });

        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                config.reset();
                // 重置后回填界面
                spinnerProvider.setSelection(indexOfProvider(config.getProviderType()));
                switchEnabled.setChecked(config.isEnabled());
                editBaseUrl.setText(config.getBaseUrl());
                editApiKey.setText(config.getApiKey());
                editModel.setText(config.getModel());
                editSystemPrompt.setText(config.getSystemPrompt());
                editBotName.setText(config.getBotName());
                editWakeKeyword.setText(config.getWakeKeyword());
                switchAtTrigger.setChecked(config.isOnlyWhenMentioned());
                switchTts.setChecked(config.isTtsEnabled());
                switchAutoGroup.setChecked(config.isAutoReplyInGroups());
                switchAutoPrivate.setChecked(config.isAutoReplyInPrivate());
                editMemoryCount.setText(String.valueOf(config.getMaxHistoryMessages()));
                seekTemperature.setProgress((int) Math.round(config.getTemperature() * TEMP_SCALE));
            }
        });

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveFieldsToConfig();
                boolean ok = config.save();
                // 通知微信进程重新同步配置（Provider 为数据源）
                try {
                    Intent refresh = new Intent(
                            com.leshao.ai.data.AiDataProvider.ACTION_REFRESH_CONFIG);
                    refresh.setPackage("com.tencent.mm");
                    sendBroadcast(refresh);
                } catch (Throwable ignored) {
                }
                Toast.makeText(SettingsActivity.this,
                        ok ? R.string.settings_saved_toast : R.string.settings_save_failed_toast,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开页面时兜底持久化并通知微信进程
        saveFieldsToConfig();
        config.save();
        try {
            Intent refresh = new Intent(
                    com.leshao.ai.data.AiDataProvider.ACTION_REFRESH_CONFIG);
            refresh.setPackage("com.tencent.mm");
            sendBroadcast(refresh);
        } catch (Throwable ignored) {
        }
    }

    /** 把当前界面值写入 config（仅内存），调用方负责 save()。 */
    private void saveFieldsToConfig() {
        config.setEnabled(switchEnabled.isChecked());
        config.setProviderType(providerTypeFromIndex(spinnerProvider.getSelectedItemPosition()));
        config.setBaseUrl(str(editBaseUrl));
        config.setApiKey(str(editApiKey));
        config.setModel(str(editModel));
        config.setSystemPrompt(str(editSystemPrompt));
        config.setBotName(str(editBotName));
        config.setWakeKeyword(str(editWakeKeyword));
        config.setOnlyWhenMentioned(switchAtTrigger.isChecked());
        config.setTtsEnabled(switchTts.isChecked());
        config.setAutoReplyInGroups(switchAutoGroup.isChecked());
        config.setAutoReplyInPrivate(switchAutoPrivate.isChecked());

        // 记忆条数
        String mem = str(editMemoryCount);
        if (!TextUtils.isEmpty(mem)) {
            try {
                config.setMaxHistoryMessages(Integer.parseInt(mem.trim()));
            } catch (NumberFormatException ignored) {
                // 忽略非法输入，保留原值
            }
        }

        // 温度：SeekBar(0..200) → double(0..2)
        config.setTemperature(seekTemperature.getProgress() / TEMP_SCALE);
    }

    private void updateTemperatureLabel(int progress) {
        double temp = progress / TEMP_SCALE;
        String label = String.format(Locale.US, "%.1f", temp);
        textTemperatureValue.setText(getString(R.string.label_temperature_value, label));
    }

    /** 定位 providerType 字符串对应的下拉索引。 */
    private static int indexOfProvider(String providerType) {
        if (!TextUtils.isEmpty(providerType)) {
            String lower = providerType.toLowerCase(Locale.US);
            if (lower.contains("responses")) {
                return 1;
            }
            if (lower.contains("anthropic") || lower.contains("claude")) {
                return 2;
            }
        }
        return 0; // default OpenAI Chat
    }

    /** 下拉索引 → providerType 字符串。 */
    private static String providerTypeFromIndex(int index) {
        ProviderType type = ProviderType.values()[safeIndex(index)];
        return type.name().toLowerCase(Locale.US);
    }

    private static int safeIndex(int index) {
        ProviderType[] types = ProviderType.values();
        if (index < 0 || index >= types.length) {
            return 0;
        }
        return index;
    }

    private static String str(EditText et) {
        return et.getText() == null ? "" : et.getText().toString();
    }
}
