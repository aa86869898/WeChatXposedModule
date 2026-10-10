package com.leshao.v3.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Environment
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.leshao.v3.ContextManager
import com.leshao.v3.service.TTSBroadcaster
import com.leshao.v3.ui.widgets.M3Page
import com.leshao.v3.wm.utils.WmPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.function.IntConsumer

class TTSPageView private constructor() {

    fun interface TimeCallback {
        fun onChange(start: String, end: String)
    }

    fun interface IntCallback {
        fun onChange(value: Int)
    }

    fun interface StringCallback {
        fun onChange(value: String)
    }

    fun interface FloatCallback {
        fun onChange(value: Float)
    }

    class VoiceItem {
        @JvmField
        var voiceId: String? = null
        @JvmField
        var group: String? = null
        @JvmField
        var displayName: String? = null
        @JvmField
        var actor: String? = null

        constructor(voiceId: String?, group: String?, displayName: String?, actor: String?) {
            this.voiceId = voiceId
            this.group = group
            this.displayName = displayName
            this.actor = actor
        }
    }

    companion object {
        private var sTtsCubeDialog: Dialog? = null

        private const val KEY_ANNOUNCE_TEXT = "ls_announce_text"
        private const val KEY_ANNOUNCE_IMAGE = "ls_announce_image"
        private const val KEY_ANNOUNCE_VIDEO = "ls_announce_video"
        private const val KEY_ANNOUNCE_LOCATION = "ls_announce_location"
        private const val KEY_ANNOUNCE_CARD = "ls_announce_card"
        private const val KEY_ANNOUNCE_FILE = "ls_announce_file"
        private const val KEY_ANNOUNCE_STICKER = "ls_announce_sticker"
        private const val KEY_ANNOUNCE_CALL = "ls_announce_call"
        private const val KEY_ANNOUNCE_QUOTE = "ls_announce_quote"
        private const val KEY_ANNOUNCE_MINIPROGRAM = "ls_announce_miniprogram"
        private const val KEY_ANNOUNCE_VIDEOCHANNEL = "ls_announce_videochannel"
        private const val KEY_ANNOUNCE_CHATHISTORY = "ls_announce_chathistory"
        private const val KEY_ANNOUNCE_NICKNAME = "ls_announce_nickname"
        private const val KEY_ANNOUNCE_GROUP = "ls_announce_group"
        private const val KEY_QUIET_ON = "ls_quiet_enabled"
        private const val KEY_QUIET_START = "ls_quiet_start"
        private const val KEY_QUIET_END = "ls_quiet_end"
        private const val KEY_ANNOUNCE_WL = "ls_tts_whitelist"
        private const val KEY_ANNOUNCE_INTERVAL = "ls_announce_interval_ms"
        private const val KEY_TEXT_CUTOFF = "ls_text_truncate_len"
        private const val KEY_TEXT_TRUNCATE = "ls_text_truncate"
        private const val KEY_ANNOUNCE_PAT = "ls_announce_pat"
        private const val KEY_ANNOUNCE_AT = "ls_announce_at"
        private const val KEY_ANNOUNCE_BL = "ls_tts_blacklist"
        private const val KEY_TTS_COMMAND = "ls_tts_command"

        private const val PMF_BASE = "https://peiyinmofang.com"
        private const val MAX_VOICE_ROWS = 300

        @JvmField
        val ALL_FEATURE_ANNOUNCE_KEYS: Array<String> = arrayOf(
                KEY_TTS_COMMAND,
                KEY_ANNOUNCE_TEXT, KEY_ANNOUNCE_IMAGE, KEY_ANNOUNCE_VIDEO, KEY_ANNOUNCE_LOCATION,
                KEY_ANNOUNCE_CARD, KEY_ANNOUNCE_FILE, KEY_ANNOUNCE_STICKER, KEY_ANNOUNCE_CALL,
                KEY_ANNOUNCE_QUOTE, KEY_ANNOUNCE_MINIPROGRAM, KEY_ANNOUNCE_VIDEOCHANNEL,
                KEY_ANNOUNCE_CHATHISTORY, KEY_ANNOUNCE_NICKNAME, KEY_ANNOUNCE_GROUP,
                KEY_ANNOUNCE_PAT, KEY_ANNOUNCE_AT, KEY_TEXT_TRUNCATE
        )

        @JvmStatic
        fun create(ctx: Context, parentAct: Activity): View {
            val d = ctx.resources.displayMetrics.density
            val prefs = ContextManager.getPrefs()

            // 外层 ScrollView 包裹
            val scrollView = ScrollView(ctx)
            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.setBackground(CandyUi.pageGradient())
            InsetsUtil.clipRounded(root)
            root.setPadding((AppColors.SPACE_MD_DP * d).toInt(), (6 * d).toInt(),
                    (AppColors.SPACE_MD_DP * d).toInt(), (8 * d).toInt())

            // v1145: 页面顶部统一分区标题
            root.addView(M3Page.section(ctx, "TTS 语音播报",
                    "自动播报新消息 / 群通知，支持音色切换与静音时段"))

            // ★ TTS 引擎选择 + 配音魔方入口 (置顶)
            root.addView(buildTtsEngineCard(ctx, parentAct, d, prefs))
            root.addView(candyDivider(ctx, d))

            val announceText = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_TEXT, true)
            val announceImage = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_IMAGE, true)
            val announceVideo = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_VIDEO, true)
            val announceLocation = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_LOCATION, true)
            val announceCard = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_CARD, true)
            val announceFile = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_FILE, true)
            val announceSticker = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_STICKER, false)
            val announceCall = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_CALL, true)
            val announceQuote = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_QUOTE, true)
            val announceMiniProgram = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_MINIPROGRAM, true)
            val announceVideoChannel = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_VIDEOCHANNEL, true)
            val announceChatHistory = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_CHATHISTORY, true)
            val announceNickname = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_NICKNAME, true)
            val announceGroup = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_GROUP, false)
            val announcePat = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_PAT, false)
            val announceAt = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_AT, true)
            val quietOn = prefs != null && prefs.getBoolean(KEY_QUIET_ON, false)
            val quietStart = prefs?.getString(KEY_QUIET_START, "23:00") ?: "23:00"
            val quietEnd = prefs?.getString(KEY_QUIET_END, "07:00") ?: "07:00"
            val whitelist = prefs?.getString(KEY_ANNOUNCE_WL, "") ?: ""
            val blacklist = prefs?.getString(KEY_ANNOUNCE_BL, "") ?: ""
            val interval = if (prefs != null) Integer.parseInt(prefs.getString(KEY_ANNOUNCE_INTERVAL, "0")) else 0
            val truncate = prefs != null && prefs.getBoolean(KEY_TEXT_TRUNCATE, true)
            val cutoff = if (prefs != null) Integer.parseInt(prefs.getString(KEY_TEXT_CUTOFF, "150")) else 150
            val speechRate = prefs?.getFloat("ls_speech_rate", 1.1f) ?: 1.1f
            val ttsCommand = prefs != null && prefs.getBoolean(KEY_TTS_COMMAND, false)

            // 启用 #tts 文字转语音指令
            val cardTts = makeCard(ctx, d)
            cardTts.addView(switchRow(ctx, d, "启用 #tts 文字转语音指令", "在聊天窗口发送 #tts XXX内容, 自动将文字合成为语音消息发出", ttsCommand) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_TTS_COMMAND, on).apply()
            })
            root.addView(cardTts)

            root.addView(candyDivider(ctx, d))

            // 语音消息自动播放 (VoiceAutoPlay 读取 wm_prefs 的 auto_voice)
            val autoVoice = WmPrefs.isAutoVoice()
            val cardAutoVoice = makeCard(ctx, d)
            cardAutoVoice.addView(switchRow(ctx, d, "自动播放语音消息", "收到语音消息时自动转文字并播报(需播报白名单)",
                    autoVoice) { _, on -> WmPrefs.set("auto_voice", on) })
            root.addView(cardAutoVoice)

            // 方案7: 双模式开关 — 语音发送音质: 人声增强(v928, 音乐伴奏更清晰) vs 原音还原(v929, 默认保真)
            val voiceEnhance = WmPrefs.get("voice_enhance", false)
            val voiceBassBoost = WmPrefs.get("voice_bass_boost", false)
            val cardVoiceMode = makeCard(ctx, d)
            cardVoiceMode.addView(switchRow(ctx, d, "语音发送人声增强", "开启=人声增强链(高通+EQ+压缩, 音乐带伴奏人声更突出); 关闭=原音还原链(透明处理, 保真优先, 默认)",
                    voiceEnhance) { _, on -> WmPrefs.set("voice_enhance", on) })
            // v1141: DJ低音增强 — 低架EQ提升低频, 音频转语音后音乐/舞曲更澎湃(优先于人声增强)
            cardVoiceMode.addView(itemDivider(ctx, d))
            cardVoiceMode.addView(switchRow(ctx, d, "DJ低音增强", "提升低频下潜, 音乐/舞曲更澎湃(优先于人声增强)",
                    voiceBassBoost) { _, on -> WmPrefs.set("voice_bass_boost", on) })
            root.addView(cardVoiceMode)

            // v1085: 文字转语音误报语音时长 — 超过 60 秒的语音按指定秒数误报, 保证语音能发出
            val falseDurOn = WmPrefs.get("ls_tts_false_dur_on", true)
            val falseDurSec = WmPrefs.getInt("ls_tts_false_dur_sec", 60)
            val cardFalseDur = makeCard(ctx, d)
            cardFalseDur.addView(switchRow(ctx, d, "文字转语音误报语音时长",
                    "开启后超过 60 秒的语音按下方秒数误报时长(默认 60 秒); 关闭则按真实时长上报",
                    falseDurOn) { _, on -> WmPrefs.set("ls_tts_false_dur_on", on) })
            cardFalseDur.addView(itemDivider(ctx, d))
            cardFalseDur.addView(numberInputRow(ctx, d, "误报时长", "超过 60 秒时上报的语音秒数",
                    falseDurSec.toString(), "秒") { sec ->
                WmPrefs.setInt("ls_tts_false_dur_sec", if (sec <= 0) 60 else Math.min(sec, 3600))
            })
            root.addView(cardFalseDur)

            root.addView(candyDivider(ctx, d))
            root.addView(sectionLabel(ctx, d, "自动播报类型"))

            val card1 = makeCard(ctx, d)
            card1.addView(switchRow(ctx, d, "文字消息播报", null, announceText) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_TEXT, on).apply()
            })
            card1.addView(itemDivider(ctx, d))
            card1.addView(switchRow(ctx, d, "语音消息播报", null, announceCall) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_CALL, on).apply()
            })
            card1.addView(itemDivider(ctx, d))
            card1.addView(switchRow(ctx, d, "图片消息播报", null, announceImage) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_IMAGE, on).apply()
            })
            card1.addView(itemDivider(ctx, d))
            card1.addView(switchRow(ctx, d, "视频消息播报", null, announceVideo) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_VIDEO, on).apply()
            })
            card1.addView(itemDivider(ctx, d))
            card1.addView(switchRow(ctx, d, "位置消息播报", null, announceLocation) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_LOCATION, on).apply()
            })
            card1.addView(itemDivider(ctx, d))
            card1.addView(switchRow(ctx, d, "名片消息播报", null, announceCard) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_CARD, on).apply()
            })
            card1.addView(itemDivider(ctx, d))
            card1.addView(switchRow(ctx, d, "文件消息播报", null, announceFile) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_FILE, on).apply()
            })
            card1.addView(itemDivider(ctx, d))
            card1.addView(switchRow(ctx, d, "表情消息播报", null, announceSticker) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_STICKER, on).apply()
            })
            card1.addView(itemDivider(ctx, d))
            card1.addView(switchRow(ctx, d, "引用消息播报", null, announceQuote) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_QUOTE, on).apply()
            })
            card1.addView(itemDivider(ctx, d))
            card1.addView(switchRow(ctx, d, "聊天记录播报", null, announceChatHistory) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_CHATHISTORY, on).apply()
            })
            card1.addView(itemDivider(ctx, d))
            card1.addView(switchRow(ctx, d, "被拍自动播报", null, announcePat) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_PAT, on).apply()
            })
            card1.addView(itemDivider(ctx, d))
            card1.addView(switchRow(ctx, d, "群内被@时播报", null, announceAt) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_AT, on).apply()
            })
            card1.addView(itemDivider(ctx, d))
            card1.addView(switchRow(ctx, d, "小程序消息播报", null, announceMiniProgram) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_MINIPROGRAM, on).apply()
            })
            card1.addView(itemDivider(ctx, d))
            card1.addView(switchRow(ctx, d, "视频号消息播报", null, announceVideoChannel) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_VIDEOCHANNEL, on).apply()
            })
            root.addView(card1)

            root.addView(candyDivider(ctx, d))
            root.addView(sectionLabel(ctx, d, "自动播报规则"))

            val card2 = makeCard(ctx, d)
            card2.addView(switchRow(ctx, d, "是否播报全群消息", null, announceGroup) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_GROUP, on).apply()
            })
            card2.addView(itemDivider(ctx, d))
            card2.addView(switchRow(ctx, d, "是否播报发送人昵称/备注", null, announceNickname) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_NICKNAME, on).apply()
            })
            root.addView(card2)

            root.addView(candyDivider(ctx, d))
            val card3 = makeCard(ctx, d)
            val wlStrict = prefs != null && prefs.getBoolean("ls_tts_whitelist_strict", true)
            card3.addView(switchRow(ctx, d, "播报白名单严格模式", "开: 白名单为空时不播报任何消息; 关: 白名单为空时全部播报", wlStrict) { _, on ->
                if (prefs != null) prefs.edit().putBoolean("ls_tts_whitelist_strict", on).apply()
            })
            card3.addView(itemDivider(ctx, d))
            card3.addView(pickerRow(ctx, d, parentAct, "自动播报白名单列表", "只播报指定好友或群聊的消息", whitelist,
                    ContactPickerDialog.MODE_FRIEND) { value ->
                if (prefs != null) prefs.edit().putString(KEY_ANNOUNCE_WL, value).apply()
            })
            card3.addView(itemDivider(ctx, d))
            card3.addView(pickerRow(ctx, d, parentAct, "自动播报黑名单列表", "不播报指定好友或群聊的消息", blacklist,
                    ContactPickerDialog.MODE_FRIEND) { value ->
                if (prefs != null) prefs.edit().putString(KEY_ANNOUNCE_BL, value).apply()
            })
            // v955: 白名单生效状态警示(修复"TTS播报无效"实为白名单严格过滤的用户困惑)
            card3.addView(itemDivider(ctx, d))
            card3.addView(buildWhitelistStatusRow(ctx, d, whitelist, wlStrict))
            root.addView(card3)

            root.addView(candyDivider(ctx, d))
            val card4 = makeCard(ctx, d)
            card4.addView(switchRow(ctx, d, "仅在时间段内自动播报", "在指定时段内不播报消息", quietOn) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_QUIET_ON, on).apply()
            })
            card4.addView(itemDivider(ctx, d))
            card4.addView(timeRangeRow(ctx, d, quietStart, quietEnd) { s, e ->
                if (prefs != null) {
                    prefs.edit().putString(KEY_QUIET_START, s).putString(KEY_QUIET_END, e).apply()
                }
            })
            root.addView(card4)

            root.addView(candyDivider(ctx, d))
            val card5 = makeCard(ctx, d)
            card5.addView(switchRow(ctx, d, "单条消息播报字符上限设置", "超过此长度的文字将被截断", truncate) { _, on ->
                if (prefs != null) prefs.edit().putBoolean(KEY_TEXT_TRUNCATE, on).apply()
            })
            card5.addView(itemDivider(ctx, d))
            card5.addView(intervalRow(ctx, d, cutoff, "截断长度", "超过此长度的文字将被截断", 1, 500) { value ->
                if (prefs != null) prefs.edit().putString(KEY_TEXT_CUTOFF, value.toString()).apply()
            })
            card5.addView(itemDivider(ctx, d))
            card5.addView(intervalRow(ctx, d, interval, "播报间隔", "两次播报之间最小间隔(毫秒)", 0, 5000) { value ->
                if (prefs != null) prefs.edit().putString(KEY_ANNOUNCE_INTERVAL, value.toString()).apply()
            })
            card5.addView(itemDivider(ctx, d))
            card5.addView(speedRateRow(ctx, d, speechRate) { rate ->
                TTSBroadcaster.setSpeechRate(rate)
                if (prefs != null) prefs.edit().putFloat("ls_speech_rate", rate).apply()
            })
            root.addView(card5)

            scrollView.addView(root)
            return scrollView
        }

        /** v955: 白名单生效状态警示行 — 白名单非空且严格模式时醒目提示拦截范围 */
        private fun buildWhitelistStatusRow(ctx: Context, d: Float, whitelist: String?, strict: Boolean): View {
            var wlCount = 0
            if (whitelist != null && whitelist.trim().isNotEmpty()) {
                wlCount = whitelist.split(Regex("[,，]")).size
            }
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            row.setBackground(CandyUi.rowBg(ctx))
            InsetsUtil.clipRounded(row)

            val status = TextView(ctx)
            status.setTextSize(13f)
            status.setSingleLine(false)

            if (wlCount == 0) {
                // 白名单为空: 不做限制(严格模式仅在白名单非空时生效, 与 FilterManager 一致)
                status.text = "✅ 当前状态：白名单为空 → 全部消息播报（严格模式仅在白名单非空时生效）"
                status.setTextColor(AppColors.onSurfaceVariant())
            } else if (strict) {
                status.text = "⚠️ 当前状态：仅播报白名单内 " + wlCount + " 个会话，其他一切消息将被拦截（如收不到播报请检查此处）"
                status.setTextColor(AppColors.warning())
            } else {
                status.text = "✅ 当前状态：白名单 " + wlCount + " 个会话优先播报，其他会话也播报（非严格模式）"
                status.setTextColor(AppColors.onSurfaceVariant())
            }
            row.addView(status)
            return row
        }

        private fun timeRangeRow(ctx: Context, d: Float, start: String?, end: String?, cb: TimeCallback?): View {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            row.setBackground(CandyUi.rowBg(ctx))
            InsetsUtil.clipRounded(row)

            val label = TextView(ctx)
            label.text = "时间段:  "
            label.setTextSize(13f)
            label.setTextColor(AppColors.text1())
            row.addView(label)

            val etStart = EditText(ctx)
            etStart.setText(start)
            etStart.setTextSize(13f)
            etStart.setTextColor(AppColors.text1())
            etStart.setSingleLine(true)
            etStart.setInputType(InputType.TYPE_CLASS_TEXT)
            etStart.setWidth((80 * d).toInt())
            etStart.setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            val startBg = GradientDrawable()
            startBg.setColor(AppColors.inputBg())
            startBg.setCornerRadius(AppColors.SHAPE_INPUT_DP * d)
            etStart.setBackground(startBg)
            row.addView(etStart)

            val sep = TextView(ctx)
            sep.text = " ~ "
            sep.setTextSize(13f)
            sep.setTextColor(AppColors.text2())
            row.addView(sep)

            val etEnd = EditText(ctx)
            etEnd.setText(end)
            etEnd.setTextSize(13f)
            etEnd.setTextColor(AppColors.text1())
            etEnd.setSingleLine(true)
            etEnd.setInputType(InputType.TYPE_CLASS_TEXT)
            etEnd.setWidth((80 * d).toInt())
            etEnd.setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            val endBg = GradientDrawable()
            endBg.setColor(AppColors.inputBg())
            endBg.setCornerRadius(AppColors.SHAPE_INPUT_DP * d)
            etEnd.setBackground(endBg)
            row.addView(etEnd)

            val save = TextView(ctx)
            save.text = "确定"
            save.setTextSize(12f)
            save.setTextColor(AppColors.accent())
            save.setPadding((8 * d).toInt(), (4 * d).toInt(), 0, (4 * d).toInt())
            CandyUi.ripple(save, AppColors.SHAPE_FULL_DP.toFloat())
            save.setOnClickListener {
                val s = etStart.text.toString().trim()
                val e = etEnd.text.toString().trim()
                if (s.isNotEmpty() && e.isNotEmpty() && cb != null) cb.onChange(s, e)
            }
            row.addView(save)

            return row
        }

        private fun intervalRow(ctx: Context, d: Float, current: Int, label: String,
                                desc: String?, min: Int, max: Int, cb: IntCallback?): View {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            row.setBackground(CandyUi.rowBg(ctx))
            InsetsUtil.clipRounded(row)

            val labelRow = LinearLayout(ctx)
            labelRow.orientation = LinearLayout.HORIZONTAL

            val lv = TextView(ctx)
            lv.text = label + (if (desc != null) " (" + desc + ")" else "") + ": "
            lv.setTextSize(12f)
            lv.setTextColor(AppColors.text2())
            labelRow.addView(lv)

            val valueTv = TextView(ctx)
            valueTv.text = current.toString()
            valueTv.setTextSize(14f)
            valueTv.setTextColor(AppColors.accent())
            valueTv.setTypeface(null, Typeface.BOLD)
            labelRow.addView(valueTv)

            row.addView(labelRow)

            val seekBar = M3Page.slider(ctx)
            val span = Math.max(1, max - min)
            seekBar.max = span
            seekBar.progress = Math.max(0, Math.min(span, current - min))
            seekBar.setPadding(0, (4 * d).toInt(), 0, 0)
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = progress + min
                    valueTv.text = value.toString()
                    if (fromUser && cb != null) cb.onChange(value)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}

                override fun onStopTrackingTouch(sb: SeekBar) {}
            })

            val range = LinearLayout(ctx)
            range.orientation = LinearLayout.HORIZONTAL
            val low = TextView(ctx)
            low.text = min.toString()
            low.setTextSize(10f)
            low.setTextColor(AppColors.text2())
            range.addView(low)
            val high = TextView(ctx)
            high.text = max.toString()
            high.setTextSize(10f)
            high.setTextColor(AppColors.text2())
            high.gravity = Gravity.END
            high.layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f)
            range.addView(high)

            row.addView(seekBar)
            row.addView(range)
            return row
        }

        private fun pickerRow(ctx: Context, d: Float, parentAct: Activity, title: String,
                              desc: String?, current: String?, mode: Int, cb: StringCallback?): View {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = (48 * d).toInt()
            row.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            row.setBackground(CandyUi.rowBg(ctx))
            InsetsUtil.clipRounded(row)

            val textCol = LinearLayout(ctx)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f)

            val tv = TextView(ctx)
            tv.text = title
            tv.setTextSize(16f)
            tv.setTextColor(AppColors.text1())
            tv.setTypeface(null, Typeface.BOLD)
            textCol.addView(tv)

            if (desc != null && desc.isNotEmpty()) {
                val dv = TextView(ctx)
                dv.text = desc
                dv.setTextSize(11f)
                dv.setTextColor(AppColors.text2())
                dv.setPadding(0, (3 * d).toInt(), 0, 0)
                textCol.addView(dv)
            }

            var count = 0
            if (current != null && current.isNotEmpty()) {
                for (id in current.split(",")) {
                    if (id.isNotBlank()) count++
                }
            }
            val cntTv = TextView(ctx)
            cntTv.text = if (count > 0) "已选 " + count + " 个" else "点击选择"
            cntTv.setTextSize(12f)
            cntTv.setTextColor(if (count > 0) AppColors.accent() else AppColors.text2())
            cntTv.setPadding(0, (3 * d).toInt(), 0, 0)
            textCol.addView(cntTv)

            row.addView(textCol)

            CandyUi.ripple(row, AppColors.SHAPE_MD_DP.toFloat())
            row.setOnClickListener {
                ContactPickerDialog.show(parentAct, current, mode) { selected, _ ->
                    cntTv.text = "已选 " + selected.size + " 个"
                    cntTv.setTextColor(AppColors.accent())
                    if (cb != null) cb.onChange(selected.joinToString(","))
                }
            }

            return row
        }

        private fun makeCard(ctx: Context, d: Float): LinearLayout {
            val card = LinearLayout(ctx)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(0, 0, 0, 0)
            card.setBackground(CandyUi.cardBg(ctx))
            InsetsUtil.clipRounded(card)
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, 0, 0, (13 * d).toInt())
            card.layoutParams = lp
            return card
        }

        private fun switchRow(ctx: Context, d: Float, title: String, desc: String?,
                              checked: Boolean, listener: CompoundButton.OnCheckedChangeListener?): LinearLayout {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = (48 * d).toInt()
            row.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            row.setBackground(CandyUi.rowBg(ctx))
            InsetsUtil.clipRounded(row)

            val textCol = LinearLayout(ctx)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f)
            val tv = TextView(ctx)
            tv.text = title
            tv.setTextSize(16f)
            tv.setTextColor(AppColors.text1())
            tv.setTypeface(null, Typeface.BOLD)
            textCol.addView(tv)
            if (desc != null && desc.isNotEmpty()) {
                val dv = TextView(ctx)
                dv.text = desc
                dv.setTextSize(12f)
                dv.setTextColor(AppColors.text2())
                dv.setPadding(0, (3 * d).toInt(), 0, 0)
                textCol.addView(dv)
            }
            row.addView(textCol)
            val sw = CandyUi.newSwitch(ctx)
            sw.isChecked = checked
            sw.setOnCheckedChangeListener(listener)
            row.addView(sw)
            return row
        }

        private fun numberInputRow(ctx: Context, d: Float, title: String, desc: String?,
                                   initial: String?, suffix: String?, onValue: IntConsumer): LinearLayout {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = (48 * d).toInt()
            row.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            row.setBackground(CandyUi.rowBg(ctx))
            InsetsUtil.clipRounded(row)

            val textCol = LinearLayout(ctx)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f)
            val tv = TextView(ctx)
            tv.text = title
            tv.setTextSize(16f)
            tv.setTextColor(AppColors.text1())
            tv.setTypeface(null, Typeface.BOLD)
            textCol.addView(tv)
            if (desc != null && desc.isNotEmpty()) {
                val dv = TextView(ctx)
                dv.text = desc
                dv.setTextSize(12f)
                dv.setTextColor(AppColors.text2())
                dv.setPadding(0, (3 * d).toInt(), 0, 0)
                textCol.addView(dv)
            }
            row.addView(textCol)

            val inputCol = LinearLayout(ctx)
            inputCol.orientation = LinearLayout.HORIZONTAL
            inputCol.gravity = Gravity.CENTER_VERTICAL
            val et = EditText(ctx)
            et.setInputType(InputType.TYPE_CLASS_NUMBER)
            et.setText(initial)
            et.setTextSize(15f)
            et.gravity = Gravity.CENTER
            et.setSingleLine(true)
            et.setTextColor(AppColors.text1())
            val etBg = GradientDrawable()
            etBg.setColor(AppColors.inputBg())
            etBg.setCornerRadius(AppColors.SHAPE_INPUT_DP * d)
            et.setBackground(etBg)
            val etLp = LinearLayout.LayoutParams((64 * d).toInt(), (44 * d).toInt())
            inputCol.addView(et, etLp)
            if (suffix != null && suffix.isNotEmpty()) {
                val sfx = TextView(ctx)
                sfx.text = suffix
                sfx.setTextSize(14f)
                sfx.setTextColor(AppColors.text2())
                sfx.setPadding((6 * d).toInt(), 0, 0, 0)
                inputCol.addView(sfx)
            }
            row.addView(inputCol)

            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable) {
                    val str = if (s == null) "" else s.toString().trim()
                    if (str.isEmpty()) return
                    try {
                        onValue.accept(Integer.parseInt(str))
                    } catch (ignored: Throwable) {}
                }
            })
            return row
        }

        private fun itemDivider(ctx: Context, d: Float): View {
            val v = View(ctx)
            val lp = LinearLayout.LayoutParams(-1, 1)
            lp.setMargins((14 * d).toInt(), 0, (14 * d).toInt(), 0)
            v.layoutParams = lp
            v.setBackgroundColor(AppColors.divider())
            return v
        }

        private fun sectionLabel(ctx: Context, d: Float, text: String): TextView {
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextSize(14f)
            tv.setTypeface(Typeface.DEFAULT_BOLD)
            tv.setTextColor(AppColors.text2())
            tv.setPadding((12 * d).toInt(), 0, (12 * d).toInt(), (8 * d).toInt())
            return tv
        }

        private fun spacerV(ctx: Context, d: Float, dpVal: Int): View {
            val v = View(ctx)
            v.layoutParams = LinearLayout.LayoutParams(-1, (dpVal * d).toInt())
            return v
        }

        private fun spacerH(ctx: Context, d: Float, dpVal: Int): View {
            val v = View(ctx)
            v.layoutParams = LinearLayout.LayoutParams((dpVal * d).toInt(), 1)
            return v
        }

        private fun speedRateRow(ctx: Context, d: Float, currentRate: Float, cb: FloatCallback?): View {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            row.setBackground(CandyUi.rowBg(ctx))
            InsetsUtil.clipRounded(row)

            val header = LinearLayout(ctx)
            header.orientation = LinearLayout.HORIZONTAL

            val label = TextView(ctx)
            label.text = "语速调节"
            label.setTextSize(13f)
            label.setTextColor(AppColors.text1())
            label.setTypeface(null, Typeface.BOLD)
            header.addView(label)

            val valueTv = TextView(ctx)
            valueTv.text = String.format("%.1fx", currentRate)
            valueTv.setTextSize(14f)
            valueTv.setTextColor(AppColors.accent())
            valueTv.setTypeface(null, Typeface.BOLD)
            valueTv.setPadding((12 * d).toInt(), 0, 0, 0)
            header.addView(valueTv)

            row.addView(header)

            val sb = M3Page.slider(ctx)
            sb.max = 20 // 0.5x ~ 2.5x, step 0.1
            sb.progress = Math.max(0, Math.min(20, Math.round((currentRate - 0.5f) * 10)))
            sb.setPadding(0, (8 * d).toInt(), 0, 0)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val rate = 0.5f + progress * 0.1f
                    valueTv.text = String.format("%.1fx", rate)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val rate = 0.5f + seekBar.progress * 0.1f
                    if (cb != null) cb.onChange(rate)
                }
            })
            row.addView(sb)

            return row
        }

        // ===== TTS 引擎选择 (置顶卡片) =====

        private fun buildTtsEngineCard(ctx: Context, parentAct: Activity, d: Float, prefs: SharedPreferences?): View {
            val card = makeCard(ctx, d)

            val title = TextView(ctx)
            title.text = "TTS 引擎选择"
            title.setTextSize(16f)
            title.setTextColor(AppColors.text1())
            title.setTypeface(null, Typeface.BOLD)
            title.setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            card.addView(title)

            // 单选按钮行
            val radioRow = LinearLayout(ctx)
            radioRow.orientation = LinearLayout.HORIZONTAL
            radioRow.setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            radioRow.gravity = Gravity.CENTER_VERTICAL

            val engine = if (WmPrefs.isTTSCube()) "cube" else "system"
            val isCube = "cube" == engine

            val btnCube = Button(ctx)
            btnCube.text = "配音魔方TTS"
            btnCube.setTextSize(13f)
            btnCube.isAllCaps = false
            btnCube.gravity = Gravity.CENTER
            val cubeBg = GradientDrawable()
            cubeBg.setColor(if (isCube) AppColors.accent() else AppColors.card())
            cubeBg.setCornerRadius((AppColors.SHAPE_FULL_DP * d).toInt().toFloat())
            cubeBg.setStroke(if (isCube) 0 else 1, AppColors.divider())
            btnCube.setBackground(cubeBg)
            btnCube.setTextColor(if (isCube) AppColors.WHITE_TEXT else AppColors.text1())
            btnCube.setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            val btnCubeLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            btnCubeLp.setMargins(0, 0, (6 * d).toInt(), 0)
            radioRow.addView(btnCube, btnCubeLp)

            val btnSys = Button(ctx)
            btnSys.text = "手机系统TTS"
            btnSys.setTextSize(13f)
            btnSys.isAllCaps = false
            btnSys.gravity = Gravity.CENTER
            val sysBg = GradientDrawable()
            sysBg.setColor(if (!isCube) AppColors.accent() else AppColors.card())
            sysBg.setCornerRadius((AppColors.SHAPE_FULL_DP * d).toInt().toFloat())
            sysBg.setStroke(if (!isCube) 0 else 1, AppColors.divider())
            btnSys.setBackground(sysBg)
            btnSys.setTextColor(if (!isCube) AppColors.WHITE_TEXT else AppColors.text1())
            btnSys.setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            val btnSysLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            btnSysLp.setMargins((6 * d).toInt(), 0, 0, 0)
            radioRow.addView(btnSys, btnSysLp)
            card.addView(radioRow)

            CandyUi.ripple(btnCube, AppColors.SHAPE_FULL_DP.toFloat())
            btnCube.setOnClickListener {
                WmPrefs.set("tts_cube", true)
                btnCube.setBackground(CandyUi.gradientBg(ctx, AppColors.SHAPE_FULL_DP.toFloat()))
                btnCube.setTextColor(AppColors.WHITE_TEXT)
                val gd2 = GradientDrawable()
                gd2.setColor(AppColors.card())
                gd2.setCornerRadius((AppColors.SHAPE_FULL_DP * d).toInt().toFloat())
                gd2.setStroke(1, AppColors.divider())
                btnSys.setBackground(gd2)
                btnSys.setTextColor(AppColors.text1())
                Toast.makeText(parentAct, "已切换为配音魔方TTS", Toast.LENGTH_SHORT).show()
            }
            CandyUi.ripple(btnSys, AppColors.SHAPE_FULL_DP.toFloat())
            btnSys.setOnClickListener {
                WmPrefs.set("tts_cube", false)
                btnSys.setBackground(CandyUi.gradientBg(ctx, AppColors.SHAPE_FULL_DP.toFloat()))
                btnSys.setTextColor(AppColors.WHITE_TEXT)
                val gd2 = GradientDrawable()
                gd2.setColor(AppColors.card())
                gd2.setCornerRadius((AppColors.SHAPE_FULL_DP * d).toInt().toFloat())
                gd2.setStroke(1, AppColors.divider())
                btnCube.setBackground(gd2)
                btnCube.setTextColor(AppColors.text1())
                Toast.makeText(parentAct, "已切换为手机系统TTS", Toast.LENGTH_SHORT).show()
            }

            // 配音魔方接口配置入口按钮
            val cfgBtn = Button(ctx)
            cfgBtn.text = "配音魔方接口配置"
            cfgBtn.setTextSize(14f)
            cfgBtn.isAllCaps = false
            cfgBtn.setTextColor(AppColors.WHITE_TEXT)
            cfgBtn.gravity = Gravity.CENTER
            cfgBtn.setBackground(CandyUi.gradientBg(ctx, AppColors.SHAPE_FULL_DP.toFloat()))
            cfgBtn.setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            val cfgLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            cfgLp.setMargins((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            card.addView(cfgBtn, cfgLp)

            CandyUi.ripple(cfgBtn, AppColors.SHAPE_SM_DP.toFloat())
            cfgBtn.setOnClickListener { showTtsCubeDialog(ctx, parentAct, d) }

            // v1086: 配音魔方接口配置下方 —— 「一键开启所有功能」总开关
            card.addView(itemDivider(ctx, d))
            card.addView(switchRow(ctx, d, "一键开启所有功能",
                    "一键开启/关闭全部 TTS 转语音与自动播报功能开关",
                    isAllFeaturesOn(prefs)) { _, on ->
                setAllFeatures(prefs, on)
                try {
                    SubPageActivity.refreshCurrent(parentAct)
                } catch (ignored: Throwable) {}
            })

            return card
        }

        /** v1086: 所有功能开关是否全部开启 */
        private fun isAllFeaturesOn(prefs: SharedPreferences?): Boolean {
            if (prefs == null) return false
            for (k in ALL_FEATURE_ANNOUNCE_KEYS) {
                if (!prefs.getBoolean(k, false)) return false
            }
            return WmPrefs.get("auto_voice", false)
                    && WmPrefs.get("voice_enhance", false)
                    && WmPrefs.get("voice_bass_boost", false)
                    && WmPrefs.get("ls_tts_false_dur_on", false)
        }

        /** v1086: 一键设置全部功能开关 */
        private fun setAllFeatures(prefs: SharedPreferences?, on: Boolean) {
            if (prefs != null) {
                val e = prefs.edit()
                for (k in ALL_FEATURE_ANNOUNCE_KEYS) e.putBoolean(k, on)
                e.apply()
            }
            WmPrefs.set("auto_voice", on)
            WmPrefs.set("voice_enhance", on)
            WmPrefs.set("voice_bass_boost", on)
            WmPrefs.set("ls_tts_false_dur_on", on)
        }

        // ===== 配音魔方接口配置对话框 (peiyinmofang.com) =====

        @JvmStatic
        fun showTtsCubeDialog(ctx: Context, parentAct: Activity, d: Float) {
            val existing = sTtsCubeDialog
            if (existing != null && existing.isShowing) {
                existing.dismiss()
            }
            val savedKey = WmPrefs.getStr("tts_cube_key", "")

            val statusTv = TextView(ctx)
            statusTv.text = "加载中..."
            statusTv.setTextSize(13f)
            statusTv.setTextColor(AppColors.text2())
            statusTv.setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())

            // 外层根布局：与主页一致的渐变背景（标题栏 + 可滚动内容 + 固定底部）
            val outerLayout = LinearLayout(ctx)
            outerLayout.orientation = LinearLayout.VERTICAL
            outerLayout.setBackground(CandyUi.pageGradient())
            outerLayout.setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())

            // 标题栏卡片
            val titleBar = LinearLayout(ctx)
            titleBar.orientation = LinearLayout.HORIZONTAL
            titleBar.gravity = Gravity.CENTER_VERTICAL
            titleBar.setPadding((16 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            titleBar.setBackground(CandyUi.cardBg(ctx))
            val titleLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            titleBar.layoutParams = titleLp

            val titleTv = TextView(ctx)
            titleTv.text = "配音魔方接口配置"
            titleTv.setTextSize(16f)
            titleTv.setTextColor(AppColors.text1())
            titleTv.setTypeface(null, Typeface.BOLD)
            val ttlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            titleBar.addView(titleTv, ttlp)

            val keyBtn = Button(ctx)
            keyBtn.text = ""
            keyBtn.minimumWidth = 0
            keyBtn.minimumHeight = 0
            keyBtn.setPadding(0, 0, 0, 0)
            keyBtn.setBackground(TuneIconDrawable(AppColors.accent()))
            val hexSize = (36 * d).toInt()
            titleBar.addView(keyBtn, LinearLayout.LayoutParams(hexSize, hexSize))

            outerLayout.addView(titleBar)
            outerLayout.addView(candyDivider(ctx, d))

            // 音色库标题
            val tabRow = LinearLayout(ctx)
            tabRow.orientation = LinearLayout.HORIZONTAL
            tabRow.gravity = Gravity.CENTER_VERTICAL
            tabRow.setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())

            val tabCube = TextView(ctx)
            tabCube.text = "配音魔方音色库"
            tabCube.setTextSize(13f)
            tabCube.gravity = Gravity.CENTER
            tabCube.setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            tabCube.setBackground(CandyUi.gradientBg(ctx, 16f))
            tabCube.setTextColor(AppColors.WHITE_TEXT)
            val tabL2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tabL2.setMargins((6 * d).toInt(), 0, 0, 0)
            tabRow.addView(tabCube, tabL2)
            outerLayout.addView(tabRow)

            // 内容区卡片（可滚动，weight=1）
            val contentCard = LinearLayout(ctx)
            contentCard.orientation = LinearLayout.VERTICAL
            contentCard.setBackground(CandyUi.cardBg(ctx))
            contentCard.setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            val cardLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            contentCard.layoutParams = cardLp

            // 音色列表容器 (动态填充)
            val voiceList = LinearLayout(ctx)
            voiceList.orientation = LinearLayout.VERTICAL
            contentCard.addView(voiceList)

            val sv = ScrollView(ctx)
            sv.addView(contentCard)
            val svLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            sv.layoutParams = svLp
            outerLayout.addView(sv)

            // 底部固定操作栏（不跟随滑动）
            val bottomBar = LinearLayout(ctx)
            bottomBar.orientation = LinearLayout.HORIZONTAL
            bottomBar.gravity = Gravity.CENTER_VERTICAL
            bottomBar.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), 0)

            val saveBtn = Button(ctx)
            saveBtn.text = "保存"
            saveBtn.setTextSize(14f)
            saveBtn.isAllCaps = false
            saveBtn.setTextColor(AppColors.WHITE_TEXT)
            saveBtn.setBackground(CandyUi.gradientBg(ctx, AppColors.SHAPE_FULL_DP.toFloat()))
            saveBtn.setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            val saveLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            saveLp.setMargins(0, 0, (8 * d).toInt(), 0)
            bottomBar.addView(saveBtn, saveLp)

            val closeBtn = Button(ctx)
            closeBtn.text = "关闭"
            closeBtn.setTextSize(14f)
            closeBtn.isAllCaps = false
            closeBtn.setTextColor(AppColors.text1())
            val closeBg = GradientDrawable()
            closeBg.setColor(AppColors.card())
            closeBg.setCornerRadius((AppColors.SHAPE_FULL_DP * d).toInt().toFloat())
            closeBg.setStroke(1, AppColors.divider())
            closeBtn.setBackground(closeBg)
            closeBtn.setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            val closeLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            bottomBar.addView(closeBtn, closeLp)

            outerLayout.addView(candyDivider(ctx, d))
            outerLayout.addView(bottomBar)

            val dialog = AlertDialog.Builder(parentAct).create()
            sTtsCubeDialog = dialog
            // 窗口自适应：宽度 90% 屏，高度随内容 WRAP，上限 90% 屏（内容区 ScrollView 内部滚动）
            val host = InsetsUtil.windowAutoHeight(dialog, outerLayout, 0.9f)
            dialog.setView(host)
            dialog.show()
            WindowLayer.track(dialog.window)

            CandyUi.ripple(keyBtn, AppColors.SHAPE_FULL_DP.toFloat())
            keyBtn.setOnClickListener { showKeyInputPopup(ctx, parentAct, d, keyBtn, voiceList, statusTv) }

            closeBtn.setOnClickListener { dialog.dismiss() }
            CandyUi.ripple(closeBtn, AppColors.SHAPE_SM_DP.toFloat())
            saveBtn.setOnClickListener { dialog.dismiss() }
            CandyUi.ripple(saveBtn, AppColors.SHAPE_SM_DP.toFloat())

            // 加载配音魔方音色库
            CandyUi.ripple(tabCube, AppColors.SHAPE_FULL_DP.toFloat())
            tabCube.setOnClickListener {
                val k = WmPrefs.getStr("tts_cube_key", "")
                if (k.isEmpty()) {
                    val hintTv = TextView(ctx)
                    hintTv.text = "点击右上角六角图标设置 Key 后加载音色"
                    hintTv.setTextSize(13f)
                    hintTv.setTextColor(AppColors.text2())
                    hintTv.setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), 0)
                    voiceList.removeAllViews()
                    voiceList.addView(hintTv)
                } else {
                    loadVoices(ctx, parentAct, d, voiceList, statusTv, k)
                }
            }

            // 自动加载：已有 Key 时直接加载音色，无需点击六角图标
            if (savedKey.isEmpty()) {
                val hintTv = TextView(ctx)
                hintTv.text = "点击右上角六角图标设置 Key 后加载音色"
                hintTv.setTextSize(13f)
                hintTv.setTextColor(AppColors.text2())
                hintTv.setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), 0)
                voiceList.addView(hintTv)
            } else {
                loadVoices(ctx, parentAct, d, voiceList, statusTv, savedKey)
            }
        }

        /** 加载配音魔方音色列表到指定容器，public 以便跨类调用 */
        @JvmStatic
        fun loadVoices(ctx: Context, parentAct: Activity, d: Float,
                      voiceList: LinearLayout, statusTv: TextView, key: String) {
            voiceList.removeAllViews()
            voiceList.addView(statusTv)
            statusTv.text = "校验Key中..."
            Thread {
                val checkResult = checkTtsKey(key)
                parentAct.runOnUiThread {
                    statusTv.text = checkResult
                    if (!checkResult.startsWith("有效")) return@runOnUiThread
                    statusTv.text = "拉取内置音色列表..."
                }
                val builtin = fetchBuiltinVoices(key)
                parentAct.runOnUiThread { statusTv.text = "拉取自定义音色列表..." }
                val custom = fetchUserVoices(key)
                // v986: 去重/过滤/截断等 CPU 重活在后台线程完成, 主线程只做必要的 View 构建,
                // 防止音色数量很大时在主线程遍历构建造成卡顿/ANR。
                val builtinFinal = compactVoices(builtin, MAX_VOICE_ROWS)
                val customFinal = compactVoices(custom, Math.max(0, MAX_VOICE_ROWS - builtinFinal.size))
                parentAct.runOnUiThread {
                    voiceList.removeView(statusTv)
                    buildVoiceListUI(ctx, parentAct, d, voiceList, key, builtinFinal, customFinal)
                }
            }.start()
        }

        /** v986: 单次最多渲染的音色行数, 避免超长列表在主线程一次性构建。 */
        private fun compactVoices(src: List<VoiceItem?>?, max: Int): List<VoiceItem> {
            val out = ArrayList<VoiceItem>()
            if (src == null || max <= 0) return out
            val seen = HashSet<String>()
            for (vi in src) {
                if (vi == null) continue
                val voiceId = vi.voiceId
                if (voiceId == null || voiceId.isEmpty()) continue
                if (!seen.add(voiceId)) continue
                out.add(vi)
                if (out.size >= max) break
            }
            return out
        }

        private fun showKeyInputPopup(ctx: Context, parentAct: Activity, d: Float, keyBtn: Button,
                                       voiceList: LinearLayout, statusTv: TextView) {
            val root = M3Page.root(ctx)
            root.addView(M3Page.section(ctx, "设置 API Key", "配置配音魔方接口密钥"))

            val card = M3Page.card(ctx)
            card.addView(M3Page.fieldLabel(ctx, "API Key"))
            val keyEt = M3Page.input(ctx, "输入 API Key")
            keyEt.setText(WmPrefs.getStr("tts_cube_key", ""))
            M3Page.trimEdgesOnInput(keyEt)
            card.addView(keyEt)
            root.addView(card)

            val b = AlertDialog.Builder(parentAct)
            b.setView(InsetsUtil.window(null, root, 0.9f, 0.5f))
            b.setCancelable(true)
            val dlg = b.create()
            InsetsUtil.centerAutoHeight(dlg, 0.9f)
            val w = dlg.window
            if (w != null) {
                InsetsUtil.transparentWindow(w)
            }

            val saveBtn = M3Page.button(ctx, "保存") {
                val key = keyEt.text.toString().trim()
                if (key.isEmpty()) {
                    Toast.makeText(parentAct, "请输入Key", Toast.LENGTH_SHORT).show()
                    return@button
                }
                WmPrefs.setStr("tts_cube_key", key)
                Toast.makeText(parentAct, "Key已保存", Toast.LENGTH_SHORT).show()
                loadVoices(ctx, parentAct, d, voiceList, statusTv, key)
                dlg.dismiss()
            }
            val cancelBtn = M3Page.ghostButton(ctx, "取消") { dlg.dismiss() }
            root.addView(M3Page.buttonRow(ctx, saveBtn, cancelBtn))

            InsetsUtil.clearDialogShell(dlg)
            dlg.show()
            WindowLayer.track(dlg.window)
            InsetsUtil.clearDialogShell(dlg)
        }

        // ===== 音色列表UI构建 =====

        private fun buildVoiceListUI(ctx: Context, parentAct: Activity, d: Float,
                                     voiceList: LinearLayout, key: String,
                                     builtin: List<VoiceItem>?, custom: List<VoiceItem>?) {
            buildVoiceListUI(ctx, parentAct, d, voiceList, key, builtin, custom, "")
        }

        private fun buildVoiceListUI(ctx: Context, parentAct: Activity, d: Float,
                                     voiceList: LinearLayout, key: String,
                                     builtin: List<VoiceItem>?, custom: List<VoiceItem>?,
                                     searchHint: String?) {
            val savedVoice = WmPrefs.getStr("tts_cube_voice", "")

            // 标题栏
            val listTitle = TextView(ctx)
            listTitle.text = "选择默认音色"
            listTitle.setTextSize(14f)
            listTitle.setTextColor(AppColors.text1())
            listTitle.setTypeface(null, Typeface.BOLD)
            listTitle.setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            voiceList.addView(listTitle)

            // 搜索框
            val searchEt = EditText(ctx)
            searchEt.setHint("搜索音色名称/影视剧...")
            searchEt.setTextSize(13f)
            searchEt.setSingleLine(true)
            searchEt.setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            searchEt.setHintTextColor(AppColors.text3())
            if (searchHint != null && searchHint.isNotEmpty()) {
                searchEt.setText(searchHint)
            }
            val sBg = GradientDrawable()
            sBg.setColor(AppColors.inputBg())
            sBg.setCornerRadius((AppColors.SHAPE_INPUT_DP * d).toInt().toFloat())
            sBg.setStroke((1.5f * d).toInt(), AppColors.stroke())
            searchEt.setBackground(sBg)
            voiceList.addView(searchEt)

            // 刷新回调：重新构建列表并保留搜索关键词
            val refreshUi = arrayOfNulls<Runnable>(1)
            refreshUi[0] = Runnable {
                val q = searchEt.text.toString()
                voiceList.removeAllViews()
                buildVoiceListUI(ctx, parentAct, d, voiceList, key, builtin, custom, q)
            }

            // 收集所有voice rows用于搜索
            val allVoiceRows = ArrayList<View>()
            val allVoiceSearchText = ArrayList<String>()
            val groupHeaders = ArrayList<View>()

            // 内置音色 (按影视剧分组)
            if (builtin != null && builtin.isNotEmpty()) {
                val secTitle = TextView(ctx)
                secTitle.text = "内置音色 (按影视剧分组):"
                secTitle.setTextSize(13f)
                secTitle.setTextColor(AppColors.text1())
                secTitle.setTypeface(null, Typeface.BOLD)
                secTitle.setPadding(0, (12 * d).toInt(), 0, (6 * d).toInt())
                voiceList.addView(secTitle)

                // 按 group 分组
                var currentGroup: String? = null
                for (vi in builtin) {
                    if (vi.group != currentGroup) {
                        currentGroup = vi.group
                        val gTitle = TextView(ctx)
                        gTitle.text = "  " + vi.group
                        gTitle.setTextSize(13f)
                        gTitle.setTextColor(AppColors.accent())
                        gTitle.setTypeface(null, Typeface.BOLD)
                        gTitle.setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
                        voiceList.addView(gTitle)
                        groupHeaders.add(gTitle)
                    }
                    val row = buildVoiceRow(ctx, parentAct, d, key, vi, savedVoice, refreshUi[0])
                    voiceList.addView(row)
                    allVoiceRows.add(row)
                    allVoiceSearchText.add((vi.displayName ?: vi.voiceId) + "|" + vi.group + "|" + (vi.actor ?: ""))
                }
            }

            // 用户自定义音色
            if (custom != null && custom.isNotEmpty()) {
                val secTitle = TextView(ctx)
                secTitle.text = "我的自定义音色:"
                secTitle.setTextSize(13f)
                secTitle.setTextColor(AppColors.text1())
                secTitle.setTypeface(null, Typeface.BOLD)
                secTitle.setPadding(0, (12 * d).toInt(), 0, (6 * d).toInt())
                voiceList.addView(secTitle)
                for (vi in custom) {
                    val row = buildVoiceRow(ctx, parentAct, d, key, vi, savedVoice, refreshUi[0])
                    voiceList.addView(row)
                    allVoiceRows.add(row)
                    allVoiceSearchText.add(vi.displayName ?: vi.voiceId ?: "")
                }
            }

            if (allVoiceRows.isEmpty()) {
                val emptyTv = TextView(ctx)
                emptyTv.text = "未找到可用音色"
                emptyTv.setTextSize(13f)
                emptyTv.setTextColor(AppColors.text2())
                emptyTv.setPadding(0, (12 * d).toInt(), 0, 0)
                voiceList.addView(emptyTv)
            }

            // 搜索过滤
            searchEt.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, st: Int, cnt: Int, aft: Int) {}
                override fun onTextChanged(s: CharSequence, st: Int, bef: Int, cnt: Int) {}
                override fun afterTextChanged(s: Editable) {
                    val q = s.toString().trim().lowercase()
                    for (i in allVoiceRows.indices) {
                        val match = q.isEmpty() || allVoiceSearchText[i].lowercase().contains(q)
                        allVoiceRows[i].visibility = if (match) View.VISIBLE else View.GONE
                    }
                    // 隐藏/显示分组标题
                    for (gh in groupHeaders) {
                        val idx = voiceList.indexOfChild(gh)
                        var hasVisible = false
                        for (j in idx + 1 until voiceList.childCount) {
                            val child = voiceList.getChildAt(j)
                            if (groupHeaders.contains(child)) break
                            if (child.visibility == View.VISIBLE) {
                                hasVisible = true
                                break
                            }
                        }
                        gh.visibility = if (hasVisible) View.VISIBLE else View.GONE
                    }
                }
            })
        }

        private fun buildVoiceRow(ctx: Context, parentAct: Activity, d: Float,
                                  key: String, vi: VoiceItem, savedVoice: String, onSelected: Runnable?): View {
            val vRow = LinearLayout(ctx)
            vRow.orientation = LinearLayout.HORIZONTAL
            vRow.gravity = Gravity.CENTER_VERTICAL
            vRow.setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())

            val isSelected = vi.voiceId == savedVoice

            // 选择按钮（左侧）
            val selBtn = TextView(ctx)
            selBtn.text = if (isSelected) "✔" else ""
            selBtn.setTextSize(13f)
            selBtn.gravity = Gravity.CENTER
            selBtn.setTextColor(if (isSelected) AppColors.accent() else AppColors.text3())
            selBtn.setTypeface(null, Typeface.BOLD)
            val selBg = GradientDrawable()
            selBg.setShape(GradientDrawable.OVAL)
            selBg.setColor(if (isSelected) 0x332196F3.toInt() else 0x00FFFFFF.toInt())
            selBg.setStroke((1.5f * d).toInt(), if (isSelected) AppColors.accent() else AppColors.text3())
            selBtn.setBackground(selBg)
            val selSize = (22 * d).toInt()
            selBtn.layoutParams = LinearLayout.LayoutParams(selSize, selSize)
            vRow.addView(selBtn)

            vRow.addView(spacerH(ctx, d, 8))

            // 名称 + 演员（流体渐变霓虹糖果色 + 加粗）
            val displayName = vi.displayName ?: vi.voiceId
            val actor = vi.actor
            val subtitle = if (actor != null && actor.isNotEmpty()) " (" + actor + ")" else ""
            val vName = TextView(ctx)
            vName.text = displayName + subtitle
            vName.setTextSize(13f)
            vName.setTextColor(AppColors.text1())
            vName.setTypeface(null, Typeface.BOLD)
            val vnlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            vRow.addView(vName, vnlp)

            // 试听按钮
            val listenBtn = TextView(ctx)
            listenBtn.text = "试听"
            listenBtn.setTextSize(12f)
            listenBtn.setTextColor(AppColors.accent())
            listenBtn.gravity = Gravity.CENTER
            listenBtn.setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
            listenBtn.paint.flags = Paint.UNDERLINE_TEXT_FLAG
            vRow.addView(listenBtn)

            CandyUi.ripple(listenBtn, AppColors.SHAPE_FULL_DP.toFloat())
            listenBtn.setOnClickListener {
                Thread {
                    val result = ttsPreviewVoice(key, vi.voiceId ?: "", "欢迎使用配音魔方")
                    if (result.startsWith("OK:")) {
                        val mp = MediaPlayer()
                        try {
                            mp.setDataSource(result.substring(3))
                            mp.prepare()
                            mp.start()
                            mp.setOnCompletionListener { it.release() }
                            parentAct.runOnUiThread {
                                Toast.makeText(parentAct, "试听: " + displayName, Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            parentAct.runOnUiThread {
                                Toast.makeText(parentAct, "播放失败: " + e.message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        parentAct.runOnUiThread {
                            Toast.makeText(parentAct, result, Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }

            CandyUi.ripple(selBtn, AppColors.SHAPE_FULL_DP.toFloat())
            selBtn.setOnClickListener {
                WmPrefs.setStr("tts_cube_voice", vi.voiceId ?: "")
                Toast.makeText(parentAct, "已选择默认音色: " + displayName, Toast.LENGTH_SHORT).show()
                if (onSelected != null) onSelected.run()
            }

            return vRow
        }

        // ===== 音色数据模型 =====

        /** v985: 供 AI 助手会话/模板配置复用: 拉取配音魔方全部音色(内置 + 我的)。 */
        @JvmStatic
        fun fetchAllVoices(key: String?): List<VoiceItem> {
            val all = ArrayList<VoiceItem>()
            if (key == null || key.trim().isEmpty()) return all
            all.addAll(fetchBuiltinVoices(key))
            all.addAll(fetchUserVoices(key))
            return all
        }

        // ===== API 调用 =====

        private fun checkTtsKey(key: String): String {
            return try {
                val url = URL(PMF_BASE + "/api/open/v1/me")
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Authorization", "Bearer " + key)
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    val code = conn.responseCode
                    if (code == 200) {
                        val ins = conn.inputStream
                        try {
                            val body = readAllAsString(ins)
                            try {
                                val jo = safeJsonObject(body)
                                val status = jo.optInt("status", -1)
                                if (status == 200) return "有效: " + jo.optString("message", "OK")
                            } catch (ignored: Exception) {}
                            "Key 有效"
                        } finally {
                            ins.close()
                        }
                    } else {
                        val err = readErrorStream(conn)
                        if (code == 401) {
                            "无效: 认证失败(401)" + (if (err.isEmpty()) "" else " - " + err)
                        } else if (code == 403) {
                            "无效: Key被禁用或余额不足(403)" + (if (err.isEmpty()) "" else " - " + err)
                        } else {
                            "检测失败: HTTP " + code + (if (err.isEmpty()) "" else " - " + err)
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                "检测失败: " + e.message
            }
        }

        private fun fetchBuiltinVoices(key: String): List<VoiceItem> {
            val list = ArrayList<VoiceItem>()
            try {
                val url = URL(PMF_BASE + "/api/open/v1/voices")
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Authorization", "Bearer " + key)
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    val code = conn.responseCode
                    if (code != 200) return list
                    val ins = conn.inputStream
                    try {
                        val body = readAllAsString(ins)
                        val jo = safeJsonObject(body)
                        if (jo.optInt("status") != 200) return list
                        val data = jo.optJSONArray("data")
                        if (data == null) return list
                        for (i in 0 until data.length()) {
                            val drama = data.getJSONObject(i)
                            val title = drama.optString("title", "未知剧集")
                            val chars = drama.optJSONArray("characters")
                            if (chars == null) continue
                            for (j in 0 until chars.length()) {
                                val chr = chars.getJSONObject(j)
                                val voiceId = chr.optString("voice_id", "")
                                if (voiceId.isEmpty()) continue // 跳过无 voice_id 的条目
                                val name = chr.optString("name", voiceId)
                                val actor = chr.optString("actor", "")
                                list.add(VoiceItem(voiceId, title, name, actor))
                            }
                        }
                    } finally {
                        ins.close()
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                // 静默失败
            }
            return list
        }

        private fun fetchUserVoices(key: String): List<VoiceItem> {
            val list = ArrayList<VoiceItem>()
            try {
                val url = URL(PMF_BASE + "/api/open/v1/user-voices")
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Authorization", "Bearer " + key)
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    val code = conn.responseCode
                    if (code != 200) return list
                    val ins = conn.inputStream
                    try {
                        val body = readAllAsString(ins)
                        val jo = safeJsonObject(body)
                        if (jo.optInt("status") != 200) return list
                        val data = jo.optJSONArray("data")
                        if (data == null) return list
                        for (i in 0 until data.length()) {
                            val uv = data.getJSONObject(i)
                            val voiceId = uv.optString("voice_id", "")
                            if (voiceId.isEmpty()) continue
                            val name = uv.optString("name", voiceId)
                            list.add(VoiceItem(voiceId, "我的音色", name, ""))
                        }
                    } finally {
                        ins.close()
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                // 静默失败
            }
            return list
        }

        /** 配音魔方下载合成音频到缓存 WAV，返回 "OK:绝对路径" 或错误文本 */
        @JvmStatic
        fun ttsPreviewVoice(key: String, voiceId: String, text: String): String {
            return try {
                val req = JSONObject()
                req.put("voiceId", voiceId)
                req.put("text", text)
                val body = req.toString()

                val url = URL(PMF_BASE + "/api/open/v1/tts/simple-generate")
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("X-API-Key", key)
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    val os = conn.outputStream
                    try {
                        os.write(body.toByteArray(Charsets.UTF_8))
                        os.flush()
                    } finally {
                        os.close()
                    }

                    val code = conn.responseCode
                    if (code != 200) {
                        val err = readErrorStream(conn)
                        return "合成失败: HTTP " + code + (if (err.isEmpty()) "" else " - " + err)
                    }

                    val ins = conn.inputStream
                    try {
                        val respStr = readAllAsString(ins)

                        val jo = safeJsonObject(respStr)
                        if (jo.optInt("status") != 200) {
                            return "合成失败: " + jo.optString("message", "状态非200")
                        }
                        val data = jo.optJSONObject("data")
                        if (data == null) return "合成失败: 响应无data字段"
                        val audioUrl = data.optString("audio")
                        if (audioUrl == null || audioUrl.isEmpty()) return "合成失败: 响应无音频URL"

                        // 下载音频文件
                        val audioURL = URL(audioUrl)
                        val audioConn = audioURL.openConnection() as HttpURLConnection
                        try {
                            audioConn.connectTimeout = 15000
                            audioConn.readTimeout = 15000
                            val audioCode = audioConn.responseCode
                            if (audioCode != 200) {
                                return "下载音频失败: HTTP " + audioCode
                            }

                            val safeName = voiceId.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
                            val appCtx = ContextManager.getAppContext()
                            val cacheDir = appCtx?.cacheDir ?: File(Environment.getExternalStorageDirectory(), "leshao_v3_cache")
                            val ttsDir = File(cacheDir, "tts_preview")
                            ttsDir.mkdirs()
                            val outFile = File(ttsDir, safeName + ".wav")

                            val audioIs = audioConn.inputStream
                            val fos = FileOutputStream(outFile)
                            try {
                                val buf = ByteArray(8192)
                                while (true) {
                                    val n = audioIs.read(buf)
                                    if (n <= 0) break
                                    fos.write(buf, 0, n)
                                }
                                fos.flush()
                            } finally {
                                try {
                                    fos.close()
                                } catch (ignored: Exception) {}
                                try {
                                    audioIs.close()
                                } catch (ignored: Exception) {}
                            }

                            // 验证文件大小
                            if (outFile.length() < 100L) {
                                return "下载失败: 音频文件过小(" + outFile.length() + "字节)"
                            }
                            "OK:" + outFile.absolutePath
                        } finally {
                            audioConn.disconnect()
                        }
                    } finally {
                        ins.close()
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                "合成失败: " + e.message
            }
        }

        /** 读 InputStream 为 String (UTF-8) */
        private fun readAllAsString(ins: InputStream): String {
            val bos = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
            }
            return String(bos.toByteArray(), Charsets.UTF_8)
        }

        /** 读 HTTP 错误响应体 */
        private fun readErrorStream(conn: HttpURLConnection): String {
            return try {
                val es = conn.errorStream
                if (es == null) return ""
                val body = readAllAsString(es)
                es.close()
                if (body.length > 200) body.substring(0, 200) + "..." else body
            } catch (e: Exception) {
                ""
            }
        }

        private fun safeJsonObject(raw: String?): JSONObject {
            if (raw == null || raw.isEmpty()) throw Exception("响应为空")
            val first = raw.trim()[0]
            if (first != '{' && first != '[') throw Exception("接口返回非JSON数据")
            return JSONObject(raw)
        }

        private fun candyDivider(ctx: Context, d: Float): View {
            return M3Page.divider(ctx)
        }

        /** 设置图标: Material 3「推子/滑块」(tune) 空心描边样式, 逻辑坐标系 24x24。 */
        private class TuneIconDrawable(color: Int) : android.graphics.drawable.Drawable() {
            private val paint: Paint
            private val path = android.graphics.Path()

            init {
                paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.style = Paint.Style.STROKE
                paint.color = color
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeCap = Paint.Cap.ROUND
            }

            override fun draw(canvas: android.graphics.Canvas) {
                val b = bounds
                val size = Math.min(b.width(), b.height())
                val strokeW = Math.max(1.5f, size * 0.09f)
                paint.strokeWidth = strokeW
                val u = (size - strokeW * 2f) / 24f
                val ox = b.exactCenterX() - 12f * u
                val oy = b.exactCenterY() - 12f * u
                path.reset()

                // 三行推子(逻辑 y = 7 / 12 / 17), 圆环滑块
                val ys = floatArrayOf(7f, 12f, 17f)
                val knobX = floatArrayOf(16f, 11f, 13f)
                val leftEnd = floatArrayOf(13f, 8f, 10f)
                val rightStart = floatArrayOf(-1f, 15f, 17f)
                val knobR = 2.2f
                for (i in 0 until 3) {
                    val y = oy + ys[i] * u
                    path.moveTo(ox + 4f * u, y)
                    path.lineTo(ox + leftEnd[i] * u, y)
                    path.addCircle(ox + knobX[i] * u, y, knobR * u, android.graphics.Path.Direction.CW)
                    if (rightStart[i] > 0) {
                        path.moveTo(ox + rightStart[i] * u, y)
                        path.lineTo(ox + 20f * u, y)
                    }
                }
                canvas.drawPath(path, paint)
            }

            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
            }

            override fun setColorFilter(cf: android.graphics.ColorFilter?) {
                paint.colorFilter = cf
            }

            override fun getOpacity(): Int {
                return android.graphics.PixelFormat.TRANSLUCENT
            }
        }
    }
}