package com.leshao.ai.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.widget.SeekBar;
import android.widget.Switch;
import com.leshao.ai.api.model.ProviderType;
import com.leshao.ai.config.AppConfig;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.InsetsUtil;
import com.leshao.v3.ui.widgets.M3Page;
import com.leshao.v3.ui.widgets.ModernButton;
import com.leshao.v3.ui.widgets.SettingRow;

import java.util.Locale;

/**
 * 乐小AI 主设置页（独立于微信宿主进程的普通 Activity）。
 *
 * <p>通过图标或控制面板打开，用于配置模块的全局开关、服务商、模型、
 * 人设、触发规则与回复策略。所有配置读写 {@link AppConfig}（磁盘 JSON）。</p>
 *
 * <p>v986: 界面由旧 XML 主题重写为程序化 M3（M3Page 组件 + AppColors 动态色），
 * 与模块其余页面统一，支持深色模式。</p>
 */
public class SettingsActivity extends Activity {

    // ---------- 控件 ----------
    private Switch switchEnabled;
    private EditText editBaseUrl, editApiKey, editModel, editSystemPrompt,
            editBotName, editWakeKeyword, editMemoryCount;
    private SeekBar seekTemperature;
    private TextView textTemperatureValue;
    private Switch switchTts;
    private SettingRow[] providerRows;
    private int providerIndex;

    /** 当前配置对象（同步读取/写入磁盘 JSON）。 */
    private AppConfig config;

    /** SeekBar 进度 → 温度（0..200 → 0.0..2.0）。 */
    private static final double TEMP_SCALE = 100.0 / 2.0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppColors.refresh();

        // 从宿主 data 目录构造并加载磁盘配置
        config = new AppConfig(getFilesDir().getParent());
        config.load();

        View contentView = buildContentView();
        setContentView(contentView);
        InsetsUtil.applyActivityInsets(this, contentView);
        populateViews();
        wireListeners();
    }

    // ==================== M3 界面 ====================

    private View buildContentView() {
        LinearLayout root = M3Page.root(this);
        root.addView(M3Page.title(this, "AI 助手设置"));

        // ---- 1. 总开关 ----
        root.addView(M3Page.section(this, "功能开关", "修改后即时生效"));
        LinearLayout cardSwitch = M3Page.card(this);
        switchEnabled = M3Page.appendSwitchRow(cardSwitch, this, "🤖",
                "AI 助手", "总开关, 关闭后全部 AI 能力停用", false, null);
        switchTts = M3Page.appendSwitchRow(cardSwitch, this, "🔊",
                "语音消息发送", "开=AI 回复转语音发出; 关=发直文本", false, null);
        root.addView(cardSwitch);

        // ---- 2. 触发范围 ----
        root.addView(M3Page.section(this, "触发范围", "由会话级个性化配置控制"));
        LinearLayout cardAuto = M3Page.card(this);
        cardAuto.addView(M3Page.note(this,
                "群聊/联系人是否触发 AI 已改为按会话配置：仅在微信「AI 助手」内"
                        + "「AI回复个性化配置」中已配置且启用的会话才会响应。"));
        root.addView(cardAuto);

        // ---- 3. 服务商 ----
        root.addView(M3Page.section(this, "服务商", "API 协议类型"));
        LinearLayout cardProvider = M3Page.card(this);
        ProviderType[] types = ProviderType.values();
        providerRows = new SettingRow[types.length];
        for (int i = 0; i < types.length; i++) {
            final int idx = i;
            SettingRow row = M3Page.appendClickRow(cardProvider, this, "☁",
                    providerLabel(types[i]), "点击选择", () -> selectProvider(idx));
            providerRows[i] = row;
        }
        root.addView(cardProvider);

        // ---- 4. 接口信息 ----
        root.addView(M3Page.section(this, "接口", "服务商提供的接入信息"));
        LinearLayout cardApi = M3Page.card(this);
        cardApi.addView(M3Page.fieldLabel(this, "接口地址"));
        editBaseUrl = M3Page.input(this, "如 https://api.deepseek.com");
        M3Page.trimEdgesOnInput(editBaseUrl);
        cardApi.addView(editBaseUrl);
        cardApi.addView(M3Page.fieldLabel(this, "Api Key 密钥"));
        editApiKey = M3Page.input(this, "sk-...");
        M3Page.trimEdgesOnInput(editApiKey);
        cardApi.addView(editApiKey);
        cardApi.addView(M3Page.fieldLabel(this, "模型名称"));
        editModel = M3Page.input(this, "如 deepseek-chat");
        M3Page.trimEdgesOnInput(editModel);
        cardApi.addView(editModel);
        root.addView(cardApi);

        // ---- 5. 生成参数 ----
        root.addView(M3Page.section(this, "生成参数", "温度越高回复越随机"));
        LinearLayout cardTemp = M3Page.card(this);
        textTemperatureValue = M3Page.note(this, "");
        cardTemp.addView(textTemperatureValue);
        seekTemperature = M3Page.slider(this);
        seekTemperature.setMax(200);
        cardTemp.addView(seekTemperature);
        root.addView(cardTemp);

        // ---- 6. 身份与人设 ----
        root.addView(M3Page.section(this, "身份与人设", "AI 对外展示的名字与语气"));
        LinearLayout cardPersona = M3Page.card(this);
        cardPersona.addView(M3Page.fieldLabel(this, "AI 昵称"));
        editBotName = M3Page.input(this, "如 小乐");
        cardPersona.addView(editBotName);
        cardPersona.addView(M3Page.fieldLabel(this, "唤醒词(多个用逗号分隔)"));
        editWakeKeyword = M3Page.input(this, "如 小乐, 在吗");
        cardPersona.addView(editWakeKeyword);
        cardPersona.addView(M3Page.fieldLabel(this, "人设提示词(System Prompt)"));
        editSystemPrompt = M3Page.input(this, "决定 AI 的语气与身份");
        editSystemPrompt.setSingleLine(false);
        editSystemPrompt.setMinLines(4);
        // v1046: 长内容多行输入时限定最大高度, 在框内内部滚动, 避免无限撑高把底部按钮顶出可视区
        editSystemPrompt.setMaxLines(6);
        editSystemPrompt.setHorizontallyScrolling(false);
        editSystemPrompt.setGravity(android.view.Gravity.TOP);
        M3Page.enableVerticalScroll(editSystemPrompt);
        cardPersona.addView(editSystemPrompt);
        root.addView(cardPersona);

        // ---- 7. 上下文记忆 ----
        root.addView(M3Page.section(this, "上下文记忆", "带入对话的历史消息条数"));
        LinearLayout cardMemory = M3Page.card(this);
        editMemoryCount = M3Page.input(this, "如 50");
        cardMemory.addView(editMemoryCount);
        root.addView(cardMemory);

        // ---- 8. 底部操作 ----
        ModernButton btnReset = new ModernButton(this, "恢复默认", ModernButton.STYLE_DANGER);
        btnReset.onClick(this::resetToDefaults);
        ModernButton btnSave = new ModernButton(this, "保存", ModernButton.STYLE_PRIMARY);
        btnSave.onClick(this::saveAndNotify);
        root.addView(M3Page.buttonRow(this, btnReset, btnSave));

        ScrollView sv = M3Page.scroll(this, root);
        sv.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        InsetsUtil.transparentWindow(this);
        return sv;
    }

    private void populateViews() {
        selectProvider(indexOfProvider(config.getProviderType()));
        switchEnabled.setChecked(config.isEnabled());
        switchTts.setChecked(config.isTtsEnabled());
        editBaseUrl.setText(safe(config.getBaseUrl()));
        editApiKey.setText(safe(config.getApiKey()));
        editModel.setText(safe(config.getModel()));
        editBotName.setText(safe(config.getBotName()));
        editWakeKeyword.setText(safe(config.getWakeKeyword()));
        editSystemPrompt.setText(safe(config.getSystemPrompt()));
        editMemoryCount.setText(String.valueOf(config.getMaxHistoryMessages()));
        int progress = (int) Math.round(config.getTemperature() * TEMP_SCALE);
        seekTemperature.setProgress(Math.max(0, Math.min(200, progress)));
        updateTemperatureLabel(seekTemperature.getProgress());
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
    }

    private void selectProvider(int index) {
        providerIndex = safeIndex(index);
        if (providerRows == null) return;
        for (int j = 0; j < providerRows.length; j++) {
            if (providerRows[j] != null) {
                providerRows[j].setSub(j == providerIndex ? "当前使用" : "点击选择");
            }
        }
    }

    private void resetToDefaults() {
        config.reset();
        populateViews();
    }

    private void saveAndNotify() {
        saveFieldsToConfig();
        boolean ok = config.save();
        // 通知微信进程同步配置（广播直带 payload，绕过 Android 11+ 包可见性）
        com.leshao.ai.data.AiDataProvider.pushRefresh(SettingsActivity.this);
        Toast.makeText(SettingsActivity.this,
                ok ? "已保存" : "保存失败", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppColors.refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开页面时兜底持久化并通知微信进程
        saveFieldsToConfig();
        config.save();
        com.leshao.ai.data.AiDataProvider.pushRefresh(SettingsActivity.this);
    }

    /** 把当前界面值写入 config（仅内存），调用方负责 save()。 */
    private void saveFieldsToConfig() {
        config.setEnabled(switchEnabled.isChecked());
        config.setProviderType(providerTypeFromIndex(providerIndex));
        config.setBaseUrl(str(editBaseUrl));
        config.setApiKey(str(editApiKey));
        config.setModel(str(editModel));
        config.setSystemPrompt(str(editSystemPrompt));
        config.setBotName(str(editBotName));
        config.setWakeKeyword(str(editWakeKeyword));
        config.setTtsEnabled(switchTts.isChecked());

        String mem = str(editMemoryCount);
        if (!TextUtils.isEmpty(mem)) {
            try {
                config.setMaxHistoryMessages(Integer.parseInt(mem.trim()));
            } catch (NumberFormatException ignored) {
                // 忽略非法输入，保留原值
            }
        }
        config.setTemperature(seekTemperature.getProgress() / TEMP_SCALE);
    }

    private void updateTemperatureLabel(int progress) {
        double temp = progress / TEMP_SCALE;
        textTemperatureValue.setText("温度: " + String.format(Locale.US, "%.1f", temp));
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

    /** 索引 → providerType 字符串。 */
    private static String providerTypeFromIndex(int index) {
        ProviderType type = ProviderType.values()[safeIndex(index)];
        return type.name().toLowerCase(Locale.US);
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

    private static int safeIndex(int index) {
        ProviderType[] types = ProviderType.values();
        if (index < 0 || index >= types.length) {
            return 0;
        }
        return index;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String str(EditText et) {
        return et.getText() == null ? "" : et.getText().toString();
    }
}
