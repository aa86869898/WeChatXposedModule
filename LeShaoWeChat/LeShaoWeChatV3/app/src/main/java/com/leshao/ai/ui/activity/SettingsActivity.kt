package com.leshao.ai.ui.activity

import android.app.Activity
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

import com.leshao.ai.api.model.ProviderType
import com.leshao.ai.config.AppConfig
import com.leshao.v3.ui.AppColors
import com.leshao.v3.ui.InsetsUtil
import com.leshao.v3.ui.widgets.M3Page
import com.leshao.v3.ui.widgets.ModernButton
import com.leshao.v3.ui.widgets.SettingRow

import java.util.Locale

class SettingsActivity : Activity() {

    private var switchEnabled: Switch? = null
    private var editBaseUrl: EditText? = null
    private var editApiKey: EditText? = null
    private var editModel: EditText? = null
    private var editSystemPrompt: EditText? = null
    private var editBotName: EditText? = null
    private var editWakeKeyword: EditText? = null
    private var editMemoryCount: EditText? = null
    private var seekTemperature: SeekBar? = null
    private var textTemperatureValue: TextView? = null
    private var switchTts: Switch? = null
    private var providerRows: Array<SettingRow?>? = null
    private var providerIndex = 0

    private var config: AppConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppColors.refresh()

        config = AppConfig(filesDir.parent)
        config?.load()

        val contentView = buildContentView()
        setContentView(contentView)
        InsetsUtil.applyActivityInsets(this, contentView)
        populateViews()
        wireListeners()
    }

    private fun buildContentView(): View {
        val root = M3Page.root(this)
        root.addView(M3Page.title(this, "AI 助手设置"))

        root.addView(M3Page.section(this, "功能开关", "修改后即时生效"))
        val cardSwitch = M3Page.card(this)
        switchEnabled = M3Page.appendSwitchRow(cardSwitch, this, "🤖",
                "AI 助手", "总开关, 关闭后全部 AI 能力停用", false, null)
        switchTts = M3Page.appendSwitchRow(cardSwitch, this, "🔊",
                "语音消息发送", "开=AI 回复转语音发出; 关=发直文本", false, null)
        root.addView(cardSwitch)

        root.addView(M3Page.section(this, "触发范围", "由会话级个性化配置控制"))
        val cardAuto = M3Page.card(this)
        cardAuto.addView(M3Page.note(this,
                "群聊/联系人是否触发 AI 已改为按会话配置：仅在微信「AI 助手」内" +
                        "「AI回复个性化配置」中已配置且启用的会话才会响应。"))
        root.addView(cardAuto)

        root.addView(M3Page.section(this, "服务商", "API 协议类型"))
        val cardProvider = M3Page.card(this)
        val types = ProviderType.values()
        providerRows = arrayOfNulls(types.size)
        for (i in types.indices) {
            val idx = i
            val row = M3Page.appendClickRow(cardProvider, this, "☁",
                    providerLabel(types[i]), "点击选择") { selectProvider(idx) }
            providerRows?.set(i, row)
        }
        root.addView(cardProvider)

        root.addView(M3Page.section(this, "接口", "服务商提供的接入信息"))
        val cardApi = M3Page.card(this)
        cardApi.addView(M3Page.fieldLabel(this, "接口地址"))
        val eb = M3Page.input(this, "如 https://api.deepseek.com")
        M3Page.trimEdgesOnInput(eb)
        editBaseUrl = eb
        cardApi.addView(eb)
        cardApi.addView(M3Page.fieldLabel(this, "Api Key 密钥"))
        val ek = M3Page.input(this, "sk-...")
        M3Page.trimEdgesOnInput(ek)
        editApiKey = ek
        cardApi.addView(ek)
        cardApi.addView(M3Page.fieldLabel(this, "模型名称"))
        val em = M3Page.input(this, "如 deepseek-chat")
        M3Page.trimEdgesOnInput(em)
        editModel = em
        cardApi.addView(em)
        root.addView(cardApi)

        root.addView(M3Page.section(this, "生成参数", "温度越高回复越随机"))
        val cardTemp = M3Page.card(this)
        val tv = M3Page.note(this, "")
        textTemperatureValue = tv
        cardTemp.addView(tv)
        val sb = M3Page.slider(this)
        sb.max = 200
        seekTemperature = sb
        cardTemp.addView(sb)
        root.addView(cardTemp)

        root.addView(M3Page.section(this, "身份与人设", "AI 对外展示的名字与语气"))
        val cardPersona = M3Page.card(this)
        cardPersona.addView(M3Page.fieldLabel(this, "AI 昵称"))
        val bn = M3Page.input(this, "如 小乐")
        editBotName = bn
        cardPersona.addView(bn)
        cardPersona.addView(M3Page.fieldLabel(this, "唤醒词(多个用逗号分隔)"))
        val wk = M3Page.input(this, "如 小乐, 在吗")
        editWakeKeyword = wk
        cardPersona.addView(wk)
        cardPersona.addView(M3Page.fieldLabel(this, "人设提示词(System Prompt)"))
        val sp = M3Page.input(this, "决定 AI 的语气与身份")
        sp.setSingleLine(false)
        sp.minLines = 4
        sp.maxLines = 6
        sp.setHorizontallyScrolling(false)
        sp.gravity = android.view.Gravity.TOP
        M3Page.enableVerticalScroll(sp)
        editSystemPrompt = sp
        cardPersona.addView(sp)
        root.addView(cardPersona)

        root.addView(M3Page.section(this, "上下文记忆", "带入对话的历史消息条数"))
        val cardMemory = M3Page.card(this)
        val mc = M3Page.input(this, "如 50")
        editMemoryCount = mc
        cardMemory.addView(mc)
        root.addView(cardMemory)

        val btnReset = ModernButton(this, "恢复默认", ModernButton.STYLE_DANGER)
        btnReset.onClick { resetToDefaults() }
        val btnSave = ModernButton(this, "保存", ModernButton.STYLE_PRIMARY)
        btnSave.onClick { saveAndNotify() }
        root.addView(M3Page.buttonRow(this, btnReset, btnSave))

        val sv = M3Page.scroll(this, root)
        sv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        InsetsUtil.transparentWindow(this)
        return sv
    }

    private fun populateViews() {
        selectProvider(indexOfProvider(config?.providerType))
        switchEnabled?.isChecked = config?.enabled == true
        switchTts?.isChecked = config?.ttsEnabled == true
        editBaseUrl?.setText(safe(config?.baseUrl))
        editApiKey?.setText(safe(config?.apiKey))
        editModel?.setText(safe(config?.model))
        editBotName?.setText(safe(config?.botName))
        editWakeKeyword?.setText(safe(config?.wakeKeyword))
        editSystemPrompt?.setText(safe(config?.systemPrompt))
        editMemoryCount?.setText(config?.maxHistoryMessages.toString())
        val progress = (config?.temperature ?: 0.7) * TEMP_SCALE
        val p = Math.round(progress).toInt()
        seekTemperature?.progress = Math.max(0, Math.min(200, p))
        updateTemperatureLabel(seekTemperature?.progress ?: 0)
    }

    private fun wireListeners() {
        seekTemperature?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateTemperatureLabel(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun selectProvider(index: Int) {
        providerIndex = safeIndex(index)
        val rows = providerRows ?: return
        for (j in rows.indices) {
            rows[j]?.setSub(if (j == providerIndex) "当前使用" else "点击选择")
        }
    }

    private fun resetToDefaults() {
        config?.reset()
        populateViews()
    }

    private fun saveAndNotify() {
        saveFieldsToConfig()
        val ok = config?.save() == true
        com.leshao.ai.data.AiDataProvider.pushRefresh(this)
        Toast.makeText(this, if (ok) "已保存" else "保存失败", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        AppColors.refresh()
    }

    override fun onPause() {
        super.onPause()
        saveFieldsToConfig()
        config?.save()
        com.leshao.ai.data.AiDataProvider.pushRefresh(this)
    }

    private fun saveFieldsToConfig() {
        val c = config ?: return
        c.enabled = switchEnabled?.isChecked == true
        c.providerType = providerTypeFromIndex(providerIndex)
        c.baseUrl = str(editBaseUrl)
        c.apiKey = str(editApiKey)
        c.model = str(editModel)
        c.systemPrompt = str(editSystemPrompt)
        c.botName = str(editBotName)
        c.wakeKeyword = str(editWakeKeyword)
        c.ttsEnabled = switchTts?.isChecked == true

        val mem = str(editMemoryCount)
        if (!TextUtils.isEmpty(mem)) {
            try {
                c.maxHistoryMessages = Integer.parseInt(mem.trim())
            } catch (ignored: NumberFormatException) {
            }
        }
        c.temperature = (seekTemperature?.progress ?: 0) / TEMP_SCALE
    }

    private fun updateTemperatureLabel(progress: Int) {
        val temp = progress / TEMP_SCALE
        textTemperatureValue?.text = "温度: " + String.format(Locale.US, "%.1f", temp)
    }

    private fun indexOfProvider(providerType: String?): Int {
        if (!TextUtils.isEmpty(providerType)) {
            val lower = providerType!!.lowercase(Locale.US)
            if (lower.contains("responses")) {
                return 1
            }
            if (lower.contains("anthropic") || lower.contains("claude")) {
                return 2
            }
        }
        return 0
    }

    private fun providerTypeFromIndex(index: Int): String {
        val type = ProviderType.values()[safeIndex(index)]
        return type.name.lowercase(Locale.US)
    }

    private fun providerLabel(pt: ProviderType?): String {
        if (pt == null) return "未知"
        return when (pt) {
            ProviderType.OPENAI_RESPONSES -> "OpenAI Responses"
            ProviderType.ANTHROPIC -> "Anthropic Claude"
            ProviderType.OPENAI_CHAT -> "OpenAI Chat"
        }
    }

    private fun safeIndex(index: Int): Int {
        val types = ProviderType.values()
        return if (index < 0 || index >= types.size) 0 else index
    }

    private fun safe(s: String?): String {
        return s ?: ""
    }

    private fun str(et: EditText?): String {
        return if (et == null) "" else et.text?.toString() ?: ""
    }

    companion object {
        private const val TEMP_SCALE = 100.0 / 2.0
    }
}