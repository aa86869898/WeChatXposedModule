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
        private var sPreviewPlayer: android.media.MediaPlayer? = null

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
        private const val KEY_CUBE_VOICES_CACHE = "tts_cube_voices_cache"
        private const val KEY_CUBE_VOICES_CACHE_TIME = "tts_cube_voices_cache_time"
        private const val GATEWAY_DEFAULT_BASE = "http://127.0.0.1:8787/v1"
        private const val MAX_VOICE_ROWS = 300
        /** 傻包音色库（免登录 ICL/内部音色 ID，100 个）：名字 → voiceId。 */
        private val VOICE_LIBRARY: List<VoiceItem> = listOf(
            VoiceItem("zh_female_wenroutaozi_uranus_bigtts", "傻包音色", "温柔桃子（升级版）", ""),
            VoiceItem("zh_male_cixingjunyu_uranus_bigtts", "傻包音色", "磁性俊宇（升级版）", ""),
            VoiceItem("zh_female_yangguangtianmei_uranus_bigtts", "傻包音色", "阳光甜妹（升级版）", ""),
            VoiceItem("zh_female_wenroutaozi_v2_mars_bigtts", "傻包音色", "温柔桃子（经典版）", ""),
            VoiceItem("zh_female_wenroutaozi_mars_bigtts", "傻包音色", "知性小棠", ""),
            VoiceItem("zh_female_f261_conversation_wvae_bigtts", "傻包音色", "邻家女孩", ""),
            VoiceItem("zh_female_sophie_conversation_wvae_bigtts", "傻包音色", "魅力苏菲", ""),
            VoiceItem("zh_female_yuanqinvyou_wvae_bigtts", "傻包音色", "撒娇学妹", ""),
            VoiceItem("zh_male_linjiananhai_moon_bigtts", "傻包音色", "邻家男孩", ""),
            VoiceItem("zh_male_M100_conversation_wvae_bigtts", "傻包音色", "悠悠君子", ""),
            VoiceItem("zh_male_ahu_conversation_wvae_bigtts", "傻包音色", "温暖阿虎", ""),
            VoiceItem("zh_male_m286_conversation_wvae_bigtts", "傻包音色", "少年梓辛", ""),
            VoiceItem("zh_male_qingyiyuxuan_mars_bigtts", "傻包音色", "阳光阿辰", ""),
            VoiceItem("ICL_c021bc19bf92", "傻包音色", "腹黑霸总", ""),
            VoiceItem("ICL_e0b9b93ee322", "傻包音色", "冷酷霸总", ""),
            VoiceItem("zh_male_aojiaobazong_wvae_bigtts", "傻包音色", "傲娇霸总", ""),
            VoiceItem("ICL_d4d40acd33dd", "傻包音色", "霸道总裁", ""),
            VoiceItem("zh_male_cheng_mars_bigtts", "傻包音色", "温柔子言", ""),
            VoiceItem("zh_male_litiebanzi_mars_bigtts", "傻包音色", "率性阿哲", ""),
            VoiceItem("ICL_df4fc4d1ce4b", "傻包音色", "温柔陆辰", ""),
            VoiceItem("zh_male_shenyeboke_wvae_bigtts", "傻包音色", "深夜播客", ""),
            VoiceItem("ICL_6acf86286e24", "傻包音色", "甜美小雪", ""),
            VoiceItem("ICL_16cd9a58768e", "傻包音色", "清冷阿梦", ""),
            VoiceItem("zh_male_dongfanghaoran_moon_bigtts", "傻包音色", "东方浩然", ""),
            VoiceItem("ICL_72afa6c5dc07", "傻包音色", "病娇少爷", ""),
            VoiceItem("zh_male_junlangxize_mars_bigtts", "傻包音色", "清爽男大", ""),
            VoiceItem("ICL_9b3bc6941076", "傻包音色", "清朗宇澄", ""),
            VoiceItem("ICL_932b3f52bf3d", "傻包音色", "奶音俊少", ""),
            VoiceItem("ICL_5a413fbc14fc", "傻包音色", "沉稳皓轩", ""),
            VoiceItem("ICL_0ce6ef379e73", "傻包音色", "温柔俊彦", ""),
            VoiceItem("ICL_afedffe4586c", "傻包音色", "青涩沐阳", ""),
            VoiceItem("ICL_4ce34d3f60f4", "傻包音色", "睿语舟舟", ""),
            VoiceItem("zh_male_gangpu_conversation_wvae_bigtts", "傻包音色", "湾区大叔", ""),
            VoiceItem("ICL_b718c1050dd1", "傻包音色", "随性先生", ""),
            VoiceItem("zh_male_m219_conversation_wvae_bigtts", "傻包音色", "渊博小叔", ""),
            VoiceItem("ICL_1eed9233299f", "傻包音色", "俊朗男友", ""),
            VoiceItem("BV154_streaming", "傻包音色", "磁性阿铭", ""),
            VoiceItem("ICL_b22cd40ccd3e", "傻包音色", "奶酷小宇", ""),
            VoiceItem("ICL_7a33516fe388", "傻包音色", "暖阳阿晨", ""),
            VoiceItem("ICL_989e59f0082a", "傻包音色", "低音小北", ""),
            VoiceItem("ICL_7ba54f5a883e", "傻包音色", "男闺蜜俊熙", ""),
            VoiceItem("ICL_6e69deb80ce5", "傻包音色", "深情霸总", ""),
            VoiceItem("zh_female_maomao_conversation_wvae_bigtts", "傻包音色", "文静毛毛", ""),
            VoiceItem("zh_female_beijingdaniu_mars_bigtts", "傻包音色", "北京大妞", ""),
            VoiceItem("zh_female_F466_mars_bigtts", "傻包音色", "清甜瑶瑶", ""),
            VoiceItem("zh_female_F765_mars_bigtts", "傻包音色", "活泼可昕", ""),
            VoiceItem("ICL_da6a164900d4", "傻包音色", "水刃公子", ""),
            VoiceItem("ICL_eaf8dd4a3a0d", "傻包音色", "灵兔少女", ""),
            VoiceItem("ICL_4fba48c6d3cc", "傻包音色", "蓝曜修士", ""),
            VoiceItem("ICL_21c82f5cbb7b", "傻包音色", "咆哮小哥", ""),
            VoiceItem("ICL_1289539b58cb", "傻包音色", "解家花少", ""),
            VoiceItem("ICL_ffed9fc2fee7", "傻包音色", "闷油瓶小哥", ""),
            VoiceItem("ICL_ba24ccf23bc7", "傻包音色", "墨镜黑哥", ""),
            VoiceItem("ICL_4b1feb0ced67", "傻包音色", "安全哥", ""),
            VoiceItem("zh_male_nezha", "傻包音色", "藕霸小童", ""),
            VoiceItem("BV428_streaming", "傻包音色", "文艺阿房", ""),
            VoiceItem("BV433_streaming", "傻包音色", "山哥播音", ""),
            VoiceItem("zh_female_guli_stream_gpu", "傻包音色", "顾姐", ""),
            VoiceItem("BV223_streaming", "傻包音色", "小品艺术家", ""),
            VoiceItem("zh_male_wangye", "傻包音色", "王小也", ""),
            VoiceItem("zh_female_leidian", "傻包音色", "雷霆女神", ""),
            VoiceItem("BV427_streaming", "傻包音色", "奶芙波波", ""),
            VoiceItem("zh_male_milaoshu", "傻包音色", "星鼠仔", ""),
            VoiceItem("zh_male_tanglaoya", "傻包音色", "麦小鸭", ""),
            VoiceItem("ICL_cd62e63dcdab", "傻包音色", "黯刃秦主", ""),
            VoiceItem("ICL_19abc3128fbb", "傻包音色", "绯樱神巫", ""),
            VoiceItem("ICL_8d858c1560ed", "傻包音色", "光头憨憨", ""),
            VoiceItem("ICL_f35ff006eb05", "傻包音色", "智慧小羊", ""),
            VoiceItem("ICL_be2856499af4", "傻包音色", "火舞魅娘", ""),
            VoiceItem("ICL_7b76e4aafb6c", "傻包音色", "沪上傲女", ""),
            VoiceItem("ICL_3bb18f934f4e", "傻包音色", "冷酷哥哥", ""),
            VoiceItem("zh_female_leidian_conversation_wvae_bigtts", "傻包音色", "高冷御姐", ""),
            VoiceItem("zh_male_xudong_conversation_wvae_bigtts", "傻包音色", "快乐小东", ""),
            VoiceItem("zh_female_sajiaonvyou_wvae_bigtts", "傻包音色", "柔美女友", ""),
            VoiceItem("zh_female_sinong_conversation_wvae_bigtts", "傻包音色", "爽快思思", ""),
            VoiceItem("ICL_da05661f8c64", "傻包音色", "妩媚御姐", ""),
            VoiceItem("ICL_11c6b3f0bd6d", "傻包音色", "活泼女孩", ""),
            VoiceItem("ICL_bad46416a2b7", "傻包音色", "甜心小美", ""),
            VoiceItem("zh_female_meilinvyou_wvae_bigtts", "傻包音色", "魅力女友", ""),
            VoiceItem("ICL_49240bb7f5ac", "傻包音色", "可爱女生", ""),
            VoiceItem("zh_male_sichuanskn", "傻包音色", "川渝小伙", ""),
            VoiceItem("zh_male_nanchangygn", "傻包音色", "南音阳仔", ""),
            VoiceItem("zh_male_changshakln", "傻包音色", "湘都朗少", ""),
            VoiceItem("zh_female_guangxiwrn", "傻包音色", "桂乡甜妞", ""),
            VoiceItem("zh_male_M392_conversation_wvae_bigtts", "傻包音色", "京腔侃爷", ""),
            VoiceItem("zh_female_qingxin_clone2", "傻包音色", "清新女声", ""),
            VoiceItem("zh_female_taozi_conversation_v4_wvae_bigtts", "傻包音色", "小甜妹", ""),
            VoiceItem("zh_female_xiaonan_lv_clone2", "傻包音色", "温柔姐姐", ""),
            VoiceItem("zh_male_renmao_mars_bigtts", "傻包音色", "桃花庵主", ""),
            VoiceItem("ICL_1beb294a9e3e", "傻包音色", "精灵向导", ""),
            VoiceItem("ICL_d8c94c04d627", "傻包音色", "清朗公子", ""),
            VoiceItem("ICL_e991be511569", "傻包音色", "内敛才俊", ""),
            VoiceItem("zh_female_qingchezizi_moon_bigtts", "傻包音色", "清澈梓梓", ""),
            VoiceItem("ICL_205cc3ca1152", "傻包音色", "不羁雅痞", ""),
            VoiceItem("ICL_4634ac0d9e82", "傻包音色", "媚娇俏娘", ""),
            VoiceItem("zh_female_dongmanxiaoyang_moon_bigtts", "傻包音色", "动漫小羊", ""),
            VoiceItem("ICL_fd7fd93fb7b8", "傻包音色", "成熟姐姐", ""),
            VoiceItem("ICL_bf4e1d132b48", "傻包音色", "恶魔女仆", ""),
            VoiceItem("ICL_c1cee47f0150", "傻包音色", "小丑", ""),
            VoiceItem("ICL_24dff5266b30", "傻包音色", "蛇发女妖", ""),
        )


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

            val announceText = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_TEXT, false)
            val announceImage = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_IMAGE, false)
            val announceVideo = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_VIDEO, false)
            val announceLocation = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_LOCATION, false)
            val announceCard = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_CARD, false)
            val announceFile = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_FILE, false)
            val announceSticker = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_STICKER, false)
            val announceCall = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_CALL, false)
            val announceQuote = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_QUOTE, false)
            val announceMiniProgram = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_MINIPROGRAM, false)
            val announceVideoChannel = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_VIDEOCHANNEL, false)
            val announceChatHistory = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_CHATHISTORY, false)
            val announceNickname = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_NICKNAME, false)
            val announceGroup = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_GROUP, false)
            val announcePat = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_PAT, false)
            val announceAt = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_AT, false)
            val quietOn = prefs != null && prefs.getBoolean(KEY_QUIET_ON, false)
            val quietStart = prefs?.getString(KEY_QUIET_START, "23:00") ?: "23:00"
            val quietEnd = prefs?.getString(KEY_QUIET_END, "07:00") ?: "07:00"
            val whitelist = prefs?.getString(KEY_ANNOUNCE_WL, "") ?: ""
            val blacklist = prefs?.getString(KEY_ANNOUNCE_BL, "") ?: ""
            val interval = try {
                if (prefs != null) Integer.parseInt(prefs.getString(KEY_ANNOUNCE_INTERVAL, "0")) else 0
            } catch (e: NumberFormatException) { 0 }
            val truncate = prefs != null && prefs.getBoolean(KEY_TEXT_TRUNCATE, true)
            val cutoff = try {
                if (prefs != null) Integer.parseInt(prefs.getString(KEY_TEXT_CUTOFF, "150")) else 150
            } catch (e: NumberFormatException) { 150 }
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
            val wlStrict = prefs != null && prefs.getBoolean("ls_tts_whitelist_strict", false)
            card3.addView(switchRow(ctx, d, "播报白名单严格模式", "开: 白名单非空时仅播报白名单内会话; 关: 白名单非空时其他会话也播报; 白名单为空时均全部播报", wlStrict) { _, on ->
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
            etStart.setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            val startBg = GradientDrawable()
            startBg.setColor(AppColors.inputBg())
            startBg.setCornerRadius(AppColors.SHAPE_INPUT_DP * d)
            etStart.setBackground(startBg)
            val etStartLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            etStartLp.weight = 1f
            row.addView(etStart, etStartLp)

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
            etEnd.setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            val endBg = GradientDrawable()
            endBg.setColor(AppColors.inputBg())
            endBg.setCornerRadius(AppColors.SHAPE_INPUT_DP * d)
            etEnd.setBackground(endBg)
            val etEndLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            etEndLp.weight = 1f
            row.addView(etEnd, etEndLp)

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

            // 三选一单选按钮行：傻包TTS / 配音魔方TTS / 系统TTS
            val engine = WmPrefs.getTtsEngine()
            val isSilly = "silly" == engine
            val isCube = "cube" == engine
            val isSys = "system" == engine

            val radioRow = LinearLayout(ctx)
            radioRow.orientation = LinearLayout.HORIZONTAL
            radioRow.setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            radioRow.gravity = Gravity.CENTER_VERTICAL

            val btnSilly = makeEngineButton(ctx, d, "傻包TTS", isSilly)
            val btnCube = makeEngineButton(ctx, d, "配音魔方TTS", isCube)
            val btnSys = makeEngineButton(ctx, d, "系统TTS", isSys)

            val btnSillyLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            btnSillyLp.setMargins(0, 0, (6 * d).toInt(), 0)
            radioRow.addView(btnSilly, btnSillyLp)

            val btnCubeLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            btnCubeLp.setMargins((6 * d).toInt(), 0, (6 * d).toInt(), 0)
            radioRow.addView(btnCube, btnCubeLp)

            val btnSysLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            btnSysLp.setMargins((6 * d).toInt(), 0, 0, 0)
            radioRow.addView(btnSys, btnSysLp)

            card.addView(radioRow)

            CandyUi.ripple(btnSilly, AppColors.SHAPE_FULL_DP.toFloat())
            btnSilly.setOnClickListener {
                if (WmPrefs.getTtsEngine() != "silly") {
                    WmPrefs.setTtsEngine("silly")
                    Toast.makeText(parentAct, "已切换为傻包TTS（免登录豆包音色）", Toast.LENGTH_SHORT).show()
                    SubPageActivity.refreshCurrent(parentAct)
                }
            }
            CandyUi.ripple(btnCube, AppColors.SHAPE_FULL_DP.toFloat())
            btnCube.setOnClickListener {
                if (WmPrefs.getTtsEngine() != "cube") {
                    WmPrefs.setTtsEngine("cube")
                    Toast.makeText(parentAct, "已切换为配音魔方TTS", Toast.LENGTH_SHORT).show()
                    SubPageActivity.refreshCurrent(parentAct)
                }
            }
            CandyUi.ripple(btnSys, AppColors.SHAPE_FULL_DP.toFloat())
            btnSys.setOnClickListener {
                if (WmPrefs.getTtsEngine() != "system") {
                    WmPrefs.setTtsEngine("system")
                    Toast.makeText(parentAct, "已切换为系统TTS", Toast.LENGTH_SHORT).show()
                    SubPageActivity.refreshCurrent(parentAct)
                }
            }

            // 配音魔方接口配置入口：仅选择配音魔方TTS时显示
            val cfgBtn = Button(ctx)
            cfgBtn.text = "配音魔方接口配置"
            cfgBtn.setTextSize(14f)
            cfgBtn.isAllCaps = false
            cfgBtn.setTextColor(AppColors.onPrimary())
            cfgBtn.gravity = Gravity.CENTER
            cfgBtn.setBackground(CandyUi.gradientBg(ctx, AppColors.SHAPE_MD_DP.toFloat()))
            cfgBtn.setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            cfgBtn.minimumHeight = (40 * d).toInt()
            val cfgLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            cfgLp.setMargins((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            cfgBtn.visibility = if (isCube) View.VISIBLE else View.GONE
            card.addView(cfgBtn, cfgLp)

            CandyUi.ripple(cfgBtn, AppColors.SHAPE_FULL_DP.toFloat())
            cfgBtn.setOnClickListener { showTtsCubeDialog(ctx, parentAct, d) }

            // v3.0.410: 登录态/免登录 手动切换（傻包TTS，实时生效）
            val laneMode = WmPrefs.getStr("tts_lane_mode", "samantha")
            val laneTitle = TextView(ctx)
            laneTitle.text = "合成车道：" + if ("voicegenie" == laneMode) "免登录（voicegenie）" else "登录态（samantha 主车道）"
            laneTitle.setTextSize(13f)
            laneTitle.setTextColor(AppColors.text1())
            laneTitle.setTypeface(null, Typeface.BOLD)
            laneTitle.setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (2 * d).toInt())
            laneTitle.visibility = if (isSilly) View.VISIBLE else View.GONE
            card.addView(laneTitle)

            val laneRow = LinearLayout(ctx)
            laneRow.orientation = LinearLayout.HORIZONTAL
            laneRow.setPadding((14 * d).toInt(), (4 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
            laneRow.visibility = if (isSilly) View.VISIBLE else View.GONE

            val btnLaneLogin = makeEngineButton(ctx, d, "登录态(samantha)", "voicegenie" != laneMode)
            val btnLaneFree = makeEngineButton(ctx, d, "免登录(voicegenie)", "voicegenie" == laneMode)
            val laneLoginLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            laneLoginLp.setMargins(0, 0, (6 * d).toInt(), 0)
            laneRow.addView(btnLaneLogin, laneLoginLp)
            val laneFreeLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            laneFreeLp.setMargins((6 * d).toInt(), 0, 0, 0)
            laneRow.addView(btnLaneFree, laneFreeLp)
            card.addView(laneRow)

            CandyUi.ripple(btnLaneLogin, AppColors.SHAPE_FULL_DP.toFloat())
            btnLaneLogin.setOnClickListener {
                if ("voicegenie" != WmPrefs.getStr("tts_lane_mode", "samantha")) return@setOnClickListener
                WmPrefs.setStr("tts_lane_mode", "samantha")
                Toast.makeText(parentAct, "已切换：登录态主车道（samantha）", Toast.LENGTH_SHORT).show()
                try { SubPageActivity.refreshCurrent(parentAct) } catch (ignored: Throwable) {}
            }
            CandyUi.ripple(btnLaneFree, AppColors.SHAPE_FULL_DP.toFloat())
            btnLaneFree.setOnClickListener {
                if ("voicegenie" == WmPrefs.getStr("tts_lane_mode", "samantha")) return@setOnClickListener
                WmPrefs.setStr("tts_lane_mode", "voicegenie")
                Toast.makeText(parentAct, "已切换：免登录（voicegenie）", Toast.LENGTH_SHORT).show()
                try { SubPageActivity.refreshCurrent(parentAct) } catch (ignored: Throwable) {}
            }

            // v3.0.407: 豆包TTS 网关/Cookie 配置（傻包TTS 双车道，仅傻包引擎时显示）
            val samBtn = Button(ctx)
            samBtn.text = "傻包音色库配置"
            samBtn.setTextSize(14f)
            samBtn.isAllCaps = false
            samBtn.setTextColor(AppColors.onPrimary())
            samBtn.gravity = Gravity.CENTER
            samBtn.setBackground(CandyUi.gradientBg(ctx, AppColors.SHAPE_MD_DP.toFloat()))
            samBtn.setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            samBtn.minimumHeight = (40 * d).toInt()
            val samLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            samLp.setMargins((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            samBtn.visibility = if (isSilly) View.VISIBLE else View.GONE
            card.addView(samBtn, samLp)

            CandyUi.ripple(samBtn, AppColors.SHAPE_FULL_DP.toFloat())
            samBtn.setOnClickListener { showSamanthaCookieDialog(ctx, parentAct, d) }

            // 选择音色（多音色随机播报）：任意引擎下均可用，支持多选/全选
            val poolBtn = Button(ctx)
            poolBtn.text = "选择音色 · 多音色随机播报"
            poolBtn.setTextSize(14f)
            poolBtn.isAllCaps = false
            poolBtn.setTextColor(AppColors.accent())
            val poolBg = GradientDrawable()
            poolBg.setColor(AppColors.surfaceContainerLow())
            poolBg.setCornerRadius((AppColors.SHAPE_MD_DP * d).toInt().toFloat())
            poolBg.setStroke(1, AppColors.accent())
            poolBtn.setBackground(poolBg)
            poolBtn.setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            poolBtn.minimumHeight = (40 * d).toInt()
            val poolLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            poolLp.setMargins((14 * d).toInt(), 0, (14 * d).toInt(), (12 * d).toInt())
            card.addView(poolBtn, poolLp)
            CandyUi.ripple(poolBtn, AppColors.SHAPE_FULL_DP.toFloat())
            poolBtn.setOnClickListener { showVoicePoolPicker(ctx, parentAct, d) }

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

        /** 引擎三选一按钮（选中=渐变底白字，未选中=卡片底+边框）。 */
        private fun makeEngineButton(ctx: Context, d: Float, text: String, selected: Boolean): Button {
            val btn = Button(ctx)
            btn.text = text
            btn.setTextSize(13f)
            btn.isAllCaps = false
            btn.gravity = Gravity.CENTER
            val bg = GradientDrawable()
            if (selected) {
                bg.setColor(AppColors.accent())
                bg.setCornerRadius((AppColors.SHAPE_MD_DP * d).toInt().toFloat())
                btn.setTextColor(AppColors.onPrimary())
            } else {
                bg.setColor(AppColors.card())
                bg.setCornerRadius((AppColors.SHAPE_MD_DP * d).toInt().toFloat())
                bg.setStroke(1, AppColors.divider())
                btn.setTextColor(AppColors.text1())
            }
            btn.setBackground(bg)
            btn.setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            btn.minimumHeight = (40 * d).toInt()
            return btn
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

// ===== 豆包TTS 网关/Cookie 配置对话框（v2.4-r1 双车道，与参考文档一致） =====

        @JvmStatic
        fun showSamanthaCookieDialog(ctx: Context, parentAct: Activity, d: Float) {
            val defaultCookie = com.leshao.v3.service.DoubaoTtsV2Bridge.DEFAULT_SAMANTHA_COOKIE
            val savedPrefsCookie = WmPrefs.getStr("tts_samantha_cookie", "")
            val savedCookie = if (com.leshao.v3.service.DoubaoTtsV2Bridge.isCompleteCookie(savedPrefsCookie)) {
                savedPrefsCookie
            } else {
                // 主/分身行为一致：未配置时统一显示内置默认 Cookie
                defaultCookie
            }

            val outerLayout = LinearLayout(ctx)
            outerLayout.orientation = LinearLayout.VERTICAL
            outerLayout.setBackground(CandyUi.pageGradient())
            outerLayout.setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())

            val tipTv = TextView(ctx)
            tipTv.text = "主车道=samantha 登录态（AAC，无需电脑/局域网）：内置默认傻包 Cookie，可直接使用，也可点击下方「登录傻包」自动获取完整 Cookie。" +
                    "兜底车道=voicegenie（OGG）：免登录直连合成。"
            tipTv.setTextSize(12f)
            tipTv.setTextColor(AppColors.text2())
            tipTv.setLineSpacing((2 * d).toInt().toFloat(), 1f)
            tipTv.setPadding(0, 0, 0, (8 * d).toInt())
            outerLayout.addView(tipTv)

            // ── Cookie 当前状态显示 ──
            val cookieStatusTv = TextView(ctx)
            cookieStatusTv.setTextSize(13f)
            cookieStatusTv.setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
            outerLayout.addView(cookieStatusTv)
            fun refreshCookieStatus() {
                val prefsCk = WmPrefs.getStr("tts_samantha_cookie", "")
                val ck = if (com.leshao.v3.service.DoubaoTtsV2Bridge.isCompleteCookie(prefsCk)) prefsCk else defaultCookie
                val fields = ck.split(";").count { it.contains("=") }
                val ok = com.leshao.v3.service.DoubaoTtsV2Bridge.isCompleteCookie(ck)
                cookieStatusTv.text = if (ok) {
                    "✅ Cookie 状态：已配置（" + fields + " 个字段，含 sessionid/sid_guard）"
                } else {
                    "⚠️ Cookie 状态：不完整（" + fields + " 个字段，缺 sessionid 或 sid_guard）"
                }
                cookieStatusTv.setTextColor(if (ok) 0xFFF5B301.toInt() else 0xFFFB8C00.toInt())
            }
            refreshCookieStatus()

            // ── 登录豆包按钮（WebView 自动获取完整 Cookie） ──
            val loginBtn = Button(ctx)
            loginBtn.text = "🌐 登录傻包自动获取完整 Cookie"
            loginBtn.setTextSize(14f)
            loginBtn.isAllCaps = false
            loginBtn.setTextColor(AppColors.onPrimary())
            loginBtn.setBackground(CandyUi.gradientBg(ctx, AppColors.SHAPE_MD_DP.toFloat()))
            loginBtn.minimumHeight = (42 * d).toInt()
            val loginLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            loginLp.setMargins(0, (6 * d).toInt(), 0, (8 * d).toInt())
            outerLayout.addView(loginBtn, loginLp)
            CandyUi.ripple(loginBtn, AppColors.SHAPE_FULL_DP.toFloat())
            loginBtn.setOnClickListener {
                openDoubaoLoginDialog(parentAct, d) { refreshCookieStatus() }
            }

            // Cookie（进程内直连方式）
            val ckLabel = fieldLabel(ctx, d, "傻包网页版登录 Cookie（可修改，默认内置完整值）")
            outerLayout.addView(ckLabel)
            val edit = android.widget.EditText(ctx)
            edit.setText(savedCookie)
            edit.setHint("sessionid=...; sid_guard=...; ...")
            edit.setTextSize(11f)
            edit.setTextColor(AppColors.text1())
            edit.setHintTextColor(AppColors.text3())
            edit.setBackground(CandyUi.cardBg(ctx))
            edit.setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            edit.minimumHeight = (70 * d).toInt()
            edit.gravity = Gravity.TOP or Gravity.START
            edit.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            outerLayout.addView(edit)

            val howBtn = TextView(ctx)
            howBtn.text = "❓ 手动获取 Cookie 步骤（点击查看）"
            howBtn.setTextSize(12f)
            howBtn.setTextColor(AppColors.accent())
            howBtn.setPadding(0, (6 * d).toInt(), 0, (4 * d).toInt())
            outerLayout.addView(howBtn)
            howBtn.setOnClickListener {
                android.app.AlertDialog.Builder(ctx)
                        .setTitle("手动获取傻包登录 Cookie")
                        .setMessage("1. 用电脑浏览器打开 https://www.doubao.com 并登录傻包账号\n" +
                                "2. 按 F12 打开开发者工具 → Network（网络）面板\n" +
                                "3. 刷新页面，点击任意请求\n" +
                                "4. 在 Request Headers（请求标头）里找到 Cookie 整段\n" +
                                "5. 复制整段粘贴到上方输入框并保存")
                        .setPositiveButton("知道了", null)
                        .show()
            }

            val btnRow = LinearLayout(ctx)
            btnRow.orientation = LinearLayout.HORIZONTAL
            btnRow.setPadding(0, (10 * d).toInt(), 0, 0)

            val saveBtn = Button(ctx)
            saveBtn.text = "保存"
            saveBtn.setTextSize(14f)
            saveBtn.isAllCaps = false
            saveBtn.setTextColor(AppColors.onPrimary())
            saveBtn.setBackground(CandyUi.gradientBg(ctx, AppColors.SHAPE_MD_DP.toFloat()))
            saveBtn.minimumHeight = (40 * d).toInt()
            val saveLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            saveLp.setMargins(0, 0, (6 * d).toInt(), 0)
            btnRow.addView(saveBtn, saveLp)

            val clearCookieBtn = Button(ctx)
            clearCookieBtn.text = "清除Cookie"
            clearCookieBtn.setTextSize(14f)
            clearCookieBtn.isAllCaps = false
            clearCookieBtn.setTextColor(AppColors.onPrimary())
            clearCookieBtn.setBackground(CandyUi.gradientBg(ctx, AppColors.SHAPE_MD_DP.toFloat()))
            clearCookieBtn.minimumHeight = (40 * d).toInt()
            val clearLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            clearLp.setMargins((6 * d).toInt(), 0, 0, 0)
            btnRow.addView(clearCookieBtn, clearLp)

            outerLayout.addView(btnRow)

            val statusTv = TextView(ctx)
            statusTv.setTextSize(12f)
            statusTv.setTextColor(AppColors.text3())
            statusTv.text = "保存后立即生效；清除 Cookie 后回退到内置默认 Cookie。"
            statusTv.setPadding(0, (8 * d).toInt(), 0, 0)
            outerLayout.addView(statusTv)

            val dialog = android.app.AlertDialog.Builder(ctx)
                    .setTitle("傻包音色库配置")
                    .setView(outerLayout)
                    .create()
            dialog.setCanceledOnTouchOutside(true)

            saveBtn.setOnClickListener {
                val c = edit.text.toString().trim()
                WmPrefs.setStr("tts_samantha_cookie", c)
                WmPrefs.setStr("doubao_chat_cookie", c)
                Toast.makeText(parentAct, "已保存，傻包TTS 按配置车道合成", Toast.LENGTH_SHORT).show()
                refreshCookieStatus()
                dialog.dismiss()
            }
            clearCookieBtn.setOnClickListener {
                WmPrefs.setStr("tts_samantha_cookie", "")
                WmPrefs.setStr("doubao_chat_cookie", "")
                edit.setText("")
                refreshCookieStatus()
                Toast.makeText(parentAct, "已清除 Cookie 配置（将使用内置默认 Cookie）", Toast.LENGTH_SHORT).show()
            }

            dialog.show()
        }

        /** 打开豆包网页版登录（WebView），登录后自动收集完整 Cookie 并持久化保存。 */
        /** 打开傻包网页版登录：模块独立悬浮窗（新窗口），不干扰微信主页返回。
         *  需要微信「显示悬浮窗」权限；登录成功自动收集完整 Cookie 保存并关闭窗口。
         *  供 AI 助手面板等处调用。 */
        @JvmStatic
        fun openDoubaoLoginDialog(parentAct: Activity, d: Float, onSaved: () -> Unit) {
            // 1. 检查悬浮窗权限
            if (!android.provider.Settings.canDrawOverlays(parentAct)) {
                Toast.makeText(parentAct, "请先允许微信「显示悬浮窗」权限，以打开独立登录窗口", Toast.LENGTH_LONG).show()
                try {
                    val permIntent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + parentAct.packageName))
                    parentAct.startActivity(permIntent)
                } catch (t: Throwable) {
                    com.leshao.v3.LogWriter.log("TTSPageView", "打开悬浮窗设置失败: " + t)
                }
                return
            }

            // 2. 创建 WebView
            val webView: android.webkit.WebView
            try {
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                webView = android.webkit.WebView(parentAct)
                webView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.databaseEnabled = true
                webView.settings.loadWithOverviewMode = true
                webView.settings.useWideViewPort = true
                webView.webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?
                    ): Boolean {
                        view?.loadUrl(request?.url?.toString() ?: return false)
                        return true
                    }
                }
            } catch (t: Throwable) {
                com.leshao.v3.LogWriter.log("TTSPageView", "WebView 创建失败: " + t)
                Toast.makeText(parentAct, "网页登录组件不可用（" + t.message + "），请使用「手动获取 Cookie」", Toast.LENGTH_LONG).show()
                return
            }

            // 3. 悬浮窗内容：FrameLayout = 顶部标题栏(关闭按钮) + WebView
            val overlay = android.widget.FrameLayout(parentAct)
            overlay.setBackgroundColor(0xFFF5F5F5.toInt())
            val titleBar = android.widget.LinearLayout(parentAct)
            titleBar.orientation = android.widget.LinearLayout.HORIZONTAL
            titleBar.gravity = Gravity.CENTER_VERTICAL
            titleBar.setPadding((14 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            titleBar.setBackgroundColor(0xFF2196F3.toInt())
            val titleTv = android.widget.TextView(parentAct)
            titleTv.text = "傻包网页版登录"
            titleTv.setTextSize(16f)
            titleTv.setTextColor(0xFFFFFFFF.toInt())
            titleTv.setTypeface(null, Typeface.BOLD)
            titleBar.addView(titleTv, android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val closeBtn = android.widget.TextView(parentAct)
            closeBtn.text = "✕ 关闭"
            closeBtn.setTextSize(14f)
            closeBtn.setGravity(Gravity.CENTER)
            closeBtn.setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
            closeBtn.setTextColor(0xFFFFFFFF.toInt())
            titleBar.addView(closeBtn)
            overlay.addView(titleBar, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START))
            val webLp = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
            webLp.topMargin = (52 * d).toInt()
            overlay.addView(webView, webLp)

            // 4. 添加到系统窗口（独立悬浮窗）
            val wm = parentAct.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val params = android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            or android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    android.graphics.PixelFormat.TRANSLUCENT)
            params.gravity = Gravity.TOP or Gravity.START
            params.title = "傻包网页登录"

            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            lateinit var poll: Runnable
            fun removeOverlay() {
                handler.removeCallbacks(poll)
                try { wm.removeView(overlay) } catch (ignored: Throwable) {}
                try { webView.destroy() } catch (ignored: Throwable) {}
            }
            closeBtn.setOnClickListener {
                removeOverlay()
                Toast.makeText(parentAct, "已关闭登录窗口", Toast.LENGTH_SHORT).show()
            }

            // 5. 轮询检测登录态 Cookie：多域合并收集（豆包/字节/飞书跨域登录态都要）
            poll = object : Runnable {
                override fun run() {
                    try {
                        val cm = android.webkit.CookieManager.getInstance()
                        val domains = listOf(
                                "https://www.doubao.com",
                                "https://doubao.com",
                                "https://www.doubao.com/chat/",
                                "https://sso.doubao.com",
                                "https://passport.doubao.com",
                                "https://www.bytedance.com",
                                "https://www.toutiao.com",
                                "https://www.feishu.cn",
                                "https://www.larksuite.com"
                        )
                        val merged = LinkedHashMap<String, String>()
                        for (d in domains) {
                            try {
                                val c = cm.getCookie(d) ?: continue
                                for (pair in c.split(";")) {
                                    val idx = pair.indexOf("=")
                                    if (idx > 0) {
                                        val k = pair.substring(0, idx).trim()
                                        val v = pair.substring(idx + 1).trim()
                                        if (k.isNotEmpty()) merged[k] = v
                                    }
                                }
                            } catch (ignored: Throwable) {}
                        }
                        val cookie = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
                        val fields = merged.size
                        val complete = cookie.contains("sessionid=") && cookie.contains("sessionid_ss=")
                                && cookie.contains("sid_tt=") && cookie.contains("uid_tt=")
                                && cookie.contains("sid_guard=")
                        if (complete) {
                            WmPrefs.setStr("tts_samantha_cookie", cookie)
                            WmPrefs.setStr("doubao_chat_cookie", cookie)
                            try { WmPrefs.setStr("doubao_webview_ua", webView.settings.userAgentString) } catch (ignored: Throwable) {}
                            val names = merged.keys.joinToString(",")
                            com.leshao.v3.LogWriter.log("TTSPageView", "✅ 已保存多域完整傻包登录 Cookie: len=" + cookie.length + " fields=" + fields + " keys=" + names)
                            Toast.makeText(parentAct, "✅ 已获取完整傻包登录 Cookie 并持久化保存", Toast.LENGTH_SHORT).show()
                            try { onSaved() } catch (ignored: Throwable) {}
                            removeOverlay()
                            return
                        }
                        if (cookie.contains("sessionid=") && cookie.contains("sid_guard=")) {
                            com.leshao.v3.LogWriter.log("TTSPageView", "⚠️ Cookie 不完整，等待补全: len=" + cookie.length + " fields=" + fields + " keys=" + merged.keys.joinToString(","))
                        }
                    } catch (ignored: Throwable) {
                    }
                    handler.postDelayed(this, 2000)
                }
            }
            handler.postDelayed(poll, 3000)

            try {
                wm.addView(overlay, params)
                webView.loadUrl("https://www.doubao.com/chat/")
            } catch (t: Throwable) {
                com.leshao.v3.LogWriter.log("TTSPageView", "悬浮窗添加失败: " + t)
                removeOverlay()
                Toast.makeText(parentAct, "独立登录窗口打开失败（" + t.message + "），请使用「手动获取 Cookie」", Toast.LENGTH_LONG).show()
            }
        }

        /** 配置对话框小标题。 */
        private fun fieldLabel(ctx: Context, d: Float, text: String): TextView {
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextSize(12f)
            tv.setTextColor(AppColors.text2())
            tv.setPadding(0, (6 * d).toInt(), 0, (3 * d).toInt())
            return tv
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
            titleTv.text = "乐少助手音色库"
            titleTv.setTextSize(16f)
            titleTv.setTextColor(AppColors.text1())
            titleTv.setTypeface(null, Typeface.BOLD)
            titleTv.gravity = Gravity.CENTER
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

            // 音色库标题：竹节跳动 - 傻包音色 - 配音魔方（每个按钮带音色数量）
            val tabRow = LinearLayout(ctx)
            tabRow.orientation = LinearLayout.HORIZONTAL
            tabRow.gravity = Gravity.CENTER_VERTICAL
            tabRow.setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())

            val tabJianying = TextView(ctx)
tabJianying.text = "竹节跳动 " + com.leshao.v3.service.JianYingTtsClient.voices().size + "个"
            tabJianying.setTextSize(11f)
            tabJianying.gravity = Gravity.CENTER
            tabJianying.setSingleLine(true)
            tabJianying.setPadding((5 * d).toInt(), (6 * d).toInt(), (5 * d).toInt(), (6 * d).toInt())
            tabJianying.setBackground(CandyUi.gradientBg(ctx, 16f))
            tabJianying.setTextColor(AppColors.onPrimary())
            val tabL0 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tabL0.setMargins(0, 0, (3 * d).toInt(), 0)
            tabRow.addView(tabJianying, tabL0)

            val tabGateway = TextView(ctx)
            tabGateway.text = "傻包音色 " + VOICE_LIBRARY.size + "个"
            tabGateway.setTextSize(11f)
            tabGateway.gravity = Gravity.CENTER
            tabGateway.setSingleLine(true)
            tabGateway.setPadding((5 * d).toInt(), (6 * d).toInt(), (5 * d).toInt(), (6 * d).toInt())
            tabGateway.setBackground(CandyUi.cardBg(ctx))
            tabGateway.setTextColor(AppColors.text1())
            val tabL1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tabL1.setMargins((3 * d).toInt(), 0, (3 * d).toInt(), 0)
            tabRow.addView(tabGateway, tabL1)

            val tabCube = TextView(ctx)
            tabCube.text = "配音魔方"
            tabCube.setTextSize(11f)
            tabCube.gravity = Gravity.CENTER
            tabCube.setSingleLine(true)
            tabCube.setPadding((5 * d).toInt(), (6 * d).toInt(), (5 * d).toInt(), (6 * d).toInt())
            tabCube.setBackground(CandyUi.cardBg(ctx))
            tabCube.setTextColor(AppColors.text1())
            val tabL2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tabL2.setMargins((3 * d).toInt(), 0, 0, 0)
            tabRow.addView(tabCube, tabL2)
            outerLayout.addView(tabRow)

            // 固定搜索框（不随分页重建，点击弹出输入法，实时过滤）
            val searchEt = android.widget.EditText(ctx)
            searchEt.hint = "搜索音色名称 / ID"
            searchEt.setTextSize(14f)
            searchEt.setTextColor(AppColors.text1())
            searchEt.setHintTextColor(AppColors.text3())
            searchEt.setSingleLine(true)
            val searchBg2 = GradientDrawable()
            searchBg2.setColor(AppColors.inputBg())
            searchBg2.cornerRadius = (AppColors.SHAPE_INPUT_DP * d).toFloat()
            searchEt.background = searchBg2
            searchEt.setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            val searchEtLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            searchEtLp.setMargins((4 * d).toInt(), 0, (4 * d).toInt(), (6 * d).toInt())
            outerLayout.addView(searchEt, searchEtLp)
            val searchQuery = arrayOf("")
            val baseItems = arrayOf<MutableList<Array<String>>>(ArrayList())
            val dlgSearchCallback = arrayOf<((String) -> Unit)?>(null)
            searchEt.setOnClickListener { showIme(searchEt) }
            searchEt.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, st: Int, cnt: Int, aft: Int) {}
                override fun onTextChanged(s: CharSequence, st: Int, bef: Int, cnt: Int) {}
                override fun afterTextChanged(s: Editable) {
                    val cb = dlgSearchCallback[0]
                    if (cb != null) cb(if (s == null) "" else s.toString())
                }
            })

            // tab 选中样式切换（三态）
            fun selectTab(tab: String) {
                tabJianying.setBackground(if (tab == "jianying") CandyUi.gradientBg(ctx, 16f) else CandyUi.cardBg(ctx))
                tabJianying.setTextColor(if (tab == "jianying") AppColors.onPrimary() else AppColors.text1())
                tabGateway.setBackground(if (tab == "gateway") CandyUi.gradientBg(ctx, 16f) else CandyUi.cardBg(ctx))
                tabGateway.setTextColor(if (tab == "gateway") AppColors.onPrimary() else AppColors.text1())
                tabCube.setBackground(if (tab == "cube") CandyUi.gradientBg(ctx, 16f) else CandyUi.cardBg(ctx))
                tabCube.setTextColor(if (tab == "cube") AppColors.onPrimary() else AppColors.text1())
            }

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

            // 底部操作栏：全选 | 上一页 | 页数 | 下一页 | 保存（每页 50，支持多选音色池）
            val PAGE_SIZE = 50
            val currentPage = intArrayOf(0)
            val currentItems = arrayOf<MutableList<Array<String>>>(ArrayList())
            val pool = loadVoicePool()

            val bottomBar = LinearLayout(ctx)
            bottomBar.orientation = LinearLayout.HORIZONTAL
            bottomBar.gravity = Gravity.CENTER_VERTICAL
            bottomBar.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())

            // 底部栏按钮统一模块风格主色（跟随 M3 动态取色/浅色暗色）
            fun makeDlgBottomBtn(text: String): Button {
                val b = Button(ctx)
                b.text = text
                b.setTextSize(13f)
                b.isAllCaps = false
                b.setTextColor(AppColors.onPrimary())
                b.gravity = Gravity.CENTER
                b.setBackground(CandyUi.gradientBg(ctx, AppColors.SHAPE_MD_DP.toFloat()))
                b.setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
b.minimumHeight = (36 * d).toInt()
                return b
            }

            val dlgSelectAll = makeDlgBottomBtn("全选")
            val dlgPrev = makeDlgBottomBtn("上页")
            val dlgPageTv = TextView(ctx)
            dlgPageTv.text = "1/1"
            dlgPageTv.setTextSize(13f)
            dlgPageTv.setTextColor(AppColors.text1())
            dlgPageTv.gravity = Gravity.CENTER
            val dlgNext = makeDlgBottomBtn("下页")
            val dlgSave = makeDlgBottomBtn("保存")

            // 按钮等宽均分整行，间距一致，从左到右刚好铺满
            fun dlgEqLp(left: Int, right: Int): LinearLayout.LayoutParams {
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.leftMargin = left
                lp.rightMargin = right
                return lp
            }
            val dlgGap = (4 * d).toInt()
            bottomBar.addView(dlgSelectAll, dlgEqLp(0, dlgGap))
            bottomBar.addView(dlgPrev, dlgEqLp(dlgGap, dlgGap))
            bottomBar.addView(dlgPageTv, dlgEqLp(dlgGap, dlgGap))
            bottomBar.addView(dlgNext, dlgEqLp(dlgGap, dlgGap))
            bottomBar.addView(dlgSave, dlgEqLp(dlgGap, 0))

            outerLayout.addView(candyDivider(ctx, d))
            outerLayout.addView(bottomBar)

            val dialog = AlertDialog.Builder(parentAct).create()
            sTtsCubeDialog = dialog
            val host = InsetsUtil.windowAutoHeight(dialog, outerLayout, 0.9f)
            dialog.setView(host)
            dialog.show()
            WindowLayer.track(dialog.window)
            try {
                val w = dialog.window
                w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                w?.setSoftInputMode(
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            } catch (ignored: Throwable) {}

            CandyUi.ripple(keyBtn, AppColors.SHAPE_FULL_DP.toFloat())
            val currentTab = java.util.concurrent.atomic.AtomicReference<String>("jianying")
            keyBtn.setOnClickListener { showKeyInputPopup(ctx, parentAct, d, keyBtn, voiceList, statusTv, currentTab) }

            fun dlgTotalPages(): Int {
                val size = currentItems[0].size
                return if (size == 0) 1 else (size + PAGE_SIZE - 1) / PAGE_SIZE
            }

            fun dlgEngineOf(tab: String): String = when (tab) {
                "gateway" -> "silly"
                "cube" -> "cube"
                else -> "jianying"
            }

            fun dlgRenderPage(tab: String) {
                voiceList.removeAllViews()
                val engine = dlgEngineOf(tab)
                val base = baseItems[0]
                val q = searchQuery[0].trim().lowercase()
                // ★ 选中的音色靠前，再按搜索词过滤
                val sorted = base.sortedByDescending { pool.contains(engine + "|" + it[0]) }
                val items = if (q.isEmpty()) sorted else sorted.filter {
                    ((it[1] ?: "").lowercase().contains(q)) || ((it[0] ?: "").lowercase().contains(q))
                }
                currentItems[0] = ArrayList(items)
                val total = dlgTotalPages()
                if (currentPage[0] >= total) currentPage[0] = total - 1
                if (currentPage[0] < 0) currentPage[0] = 0
                val start = currentPage[0] * PAGE_SIZE
                val end = Math.min(start + PAGE_SIZE, items.size)
                if (items.isEmpty()) {
                    val emptyTv = TextView(ctx)
                    emptyTv.text = if (q.isEmpty()) "暂无音色" else "未找到匹配音色"
                    emptyTv.setTextSize(13f)
                    emptyTv.setTextColor(AppColors.text2())
                    emptyTv.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), 0)
                    voiceList.addView(emptyTv)
                } else {
                    for (i in start until end) {
                        val it = items[i]
                        voiceList.addView(makePoolCheckRow(ctx, d, pool, engine, it[0], it[1], it[2]) {
                            previewVoice(ctx, parentAct, engine, it[0])
                        })
                    }
                }
                dlgPageTv.text = (currentPage[0] + 1).toString() + "/" + total
                dlgPrev.isEnabled = currentPage[0] > 0
                dlgNext.isEnabled = currentPage[0] < total - 1
            }

            // 搜索回调注册（dlgRenderPage 定义后）
            dlgSearchCallback[0] = { q ->
                searchQuery[0] = q
                currentPage[0] = 0
                dlgRenderPage(currentTab.get())
            }

            fun dlgRender(tab: String) {
                voiceList.removeAllViews()
                if (tab == "cube") {
                    val k = WmPrefs.getStr("tts_cube_key", "")
                    if (k.isEmpty()) {
                        val hintTv = TextView(ctx)
                        hintTv.text = "未配置配音魔方 API Key：请点击右上角六角图标设置"
                        hintTv.setTextSize(13f)
                        hintTv.setTextColor(AppColors.text2())
                        hintTv.setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), 0)
                        voiceList.addView(hintTv)
                        baseItems[0] = ArrayList()
                        currentItems[0] = ArrayList()
                        currentPage[0] = 0
                        dlgPageTv.text = "1/1"
                        dlgPrev.isEnabled = false
                        dlgNext.isEnabled = false
                        return
                    }
                    // 启动已自动预载缓存 → 直接秒显，无需手动点击等待
                    val cached = loadCachedCubeVoices()
                    if (cached.isNotEmpty()) {
                        val items = ArrayList<Array<String>>()
                        for (vi in cached) {
                            val id = vi.voiceId ?: continue
                            items.add(arrayOf(id, vi.displayName ?: id, "配音魔方"))
                        }
                        baseItems[0] = items
                        currentItems[0] = ArrayList(items)
                        currentPage[0] = 0
                        tabCube.text = "配音魔方 " + items.size + "个"
                        dlgRenderPage(tab)
                        return
                    }
                    voiceList.addView(TextView(ctx).apply {
                        text = "加载配音魔方音色..."
                        setTextSize(13f)
                        setTextColor(AppColors.text2())
                        setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), 0)
                    })
                    Thread {
                        val all = refreshCubeVoiceCache(k)
                        parentAct.runOnUiThread {
                            val items = ArrayList<Array<String>>()
                            for (vi in all) {
                                val id = vi.voiceId ?: continue
                                items.add(arrayOf(id, vi.displayName ?: id, "配音魔方"))
                            }
                            tabCube.text = "配音魔方 " + items.size + "个"
                            baseItems[0] = items
                            currentItems[0] = ArrayList(items)
                            currentPage[0] = 0
                            dlgRenderPage(tab)
                        }
                    }.start()
                    return
                }
                val items = ArrayList<Array<String>>()
                when (tab) {
                    "jianying" -> {
                        for (v in com.leshao.v3.service.JianYingTtsClient.voices()) {
                            items.add(arrayOf(v.voiceType, v.title ?: v.voiceType, if (v.vip) "VIP音色" else ""))
                        }
                    }
                    "gateway" -> {
                        for (vi in VOICE_LIBRARY) {
                            val id = vi.voiceId ?: continue
                            items.add(arrayOf(id, vi.displayName ?: id, "傻包音色"))
                        }
                    }
                }
                baseItems[0] = items
                currentItems[0] = ArrayList(items)
                currentPage[0] = 0
                dlgRenderPage(tab)
            }

            CandyUi.ripple(tabJianying, AppColors.SHAPE_FULL_DP.toFloat())
            tabJianying.setOnClickListener {
                currentTab.set("jianying")
                selectTab("jianying")
                dlgRender("jianying")
            }
            CandyUi.ripple(tabGateway, AppColors.SHAPE_FULL_DP.toFloat())
            tabGateway.setOnClickListener {
                currentTab.set("gateway")
                selectTab("gateway")
                dlgRender("gateway")
            }
            CandyUi.ripple(tabCube, AppColors.SHAPE_FULL_DP.toFloat())
            tabCube.setOnClickListener {
                currentTab.set("cube")
                selectTab("cube")
                dlgRender("cube")
            }

            dlgPrev.setOnClickListener {
                if (currentPage[0] > 0) {
                    currentPage[0]--
                    dlgRenderPage(currentTab.get())
                }
            }
            dlgNext.setOnClickListener {
                if (currentPage[0] < dlgTotalPages() - 1) {
                    currentPage[0]++
                    dlgRenderPage(currentTab.get())
                }
            }
            dlgSelectAll.setOnClickListener {
                val tab = currentTab.get()
                val engine = dlgEngineOf(tab)
                val items = currentItems[0]
                if (items.isEmpty()) return@setOnClickListener
                val start = currentPage[0] * PAGE_SIZE
                val end = Math.min(start + PAGE_SIZE, items.size)
                for (i in start until end) {
                    pool.add(engine + "|" + items[i][0])
                }
                dlgRenderPage(tab)
                Toast.makeText(parentAct, "已全选本页 " + (end - start) + " 个音色", Toast.LENGTH_SHORT).show()
            }
            dlgSave.setOnClickListener {
                saveVoicePool(pool)
                Toast.makeText(parentAct, "已保存 " + pool.size + " 个音色，播报时将随机使用", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }

            // 自动加载：默认「竹节跳动」（剪映音色库）
            currentTab.set("jianying")
            selectTab("jianying")
            dlgRender("jianying")
        }



        /** 竹节跳动（剪映）音色行：勾选 + 音色名（左侧）+ 🎧 试听。 */
        private fun buildJianYingVoiceRow(ctx: Context, parentAct: Activity, d: Float,
                                          v: com.leshao.v3.service.JianYingTtsClient.JianYingVoice,
                                          savedVoice: String, onSelected: () -> Unit,
                                          onPreview: () -> Unit): View {
            val container = LinearLayout(ctx)
            container.orientation = LinearLayout.VERTICAL
            container.setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            container.isClickable = true
            container.isFocusable = true
            CandyUi.ripple(container, AppColors.SHAPE_SM_DP.toFloat())
            container.setOnClickListener { onSelected() }

            val isSelected = savedVoice == v.voiceType
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL

            // 左侧勾选（自绘圆形：选中=主题色底+白✓，未选中=透明底+outline描边）
            val cb = TextView(ctx)
            cb.text = if (isSelected) "✔" else ""
            cb.setTextSize(14f)
            cb.gravity = Gravity.CENTER
            cb.setTextColor(if (isSelected) AppColors.onPrimary() else android.graphics.Color.TRANSPARENT)
            cb.setTypeface(null, Typeface.BOLD)
            val cbBg = GradientDrawable()
            cbBg.setShape(GradientDrawable.OVAL)
            cbBg.setColor(if (isSelected) AppColors.primary() else 0x00FFFFFF.toInt())
            cbBg.setStroke((1.5f * d).toInt(), if (isSelected) AppColors.primary() else AppColors.outline())
            cb.setBackground(cbBg)
            val cbLp = LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt())
            row.addView(cb, cbLp)

            // 音色名（左侧对齐，不居中）
            val titleTv = TextView(ctx)
            titleTv.text = v.title ?: v.voiceType
            titleTv.setTextSize(15f)
            titleTv.setTypeface(null, Typeface.BOLD)
            titleTv.setTextColor(AppColors.textPrimary())
            titleTv.gravity = Gravity.LEFT
            val titleLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            titleLp.leftMargin = (4 * d).toInt()
            row.addView(titleTv, titleLp)

            // 试听按钮（独立点击，不触发选中；尺寸与勾选一致，耳机金色=竹节跳动音色系）
            val earTv = TextView(ctx)
            earTv.text = ""
            earTv.gravity = Gravity.CENTER
            earTv.isClickable = true
            earTv.isFocusable = true
            earTv.setBackground(HeadphoneIconDrawable(0xFFFFB300.toInt()))
            val earLp = LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt())
            earLp.leftMargin = (4 * d).toInt()
            earTv.setOnClickListener { onPreview() }
            row.addView(earTv, earLp)

            // 勾选按钮点击 → 选中该音色
            cb.setOnClickListener { onSelected() }

            container.addView(row)
            // 分割线
            val divider = View(ctx)
            divider.setBackgroundColor(AppColors.divider())
            val divLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1)
            divLp.topMargin = (6 * d).toInt()
            container.addView(divider)
            return container
        }

        /** 向上查找最近的 ScrollView 父容器。 */
        @JvmStatic
        private fun findScrollView(v: View): ScrollView? {
            var p = v.parent
            while (p != null) {
                if (p is ScrollView) return p
                p = p.parent
            }
            return null
        }

        /** 加载配音魔方音色列表到指定容器（启动已自动预载缓存，打开秒显，后台静默刷新）。 */
        @JvmStatic
        fun loadVoices(ctx: Context, parentAct: Activity, d: Float,
                      voiceList: LinearLayout, statusTv: TextView, key: String,
                      onCount: ((Int) -> Unit)? = null) {
            voiceList.removeAllViews()
            // 启动已自动预载缓存 → 先展示缓存，后台静默刷新
            val cached = loadCachedCubeVoices()
            if (cached.isNotEmpty()) {
                buildVoiceListUI(ctx, parentAct, d, voiceList, key, null, cached, ArrayList())
                onCount?.invoke(cached.size)
                Thread {
                    val fresh = refreshCubeVoiceCache(key)
                    if (fresh.isNotEmpty()) {
                        parentAct.runOnUiThread {
                            voiceList.removeAllViews()
                            buildVoiceListUI(ctx, parentAct, d, voiceList, key, null, fresh, ArrayList())
                            onCount?.invoke(fresh.size)
                        }
                    }
                }.start()
                return
            }
            voiceList.addView(statusTv)
            statusTv.text = "校验Key中..."
            Thread {
                val checkResult = checkTtsKey(key)
                parentAct.runOnUiThread {
                    statusTv.text = checkResult
                    if (!checkResult.startsWith("有效")) return@runOnUiThread
                    statusTv.text = "拉取内置音色列表..."
                }
                val all = refreshCubeVoiceCache(key)
                parentAct.runOnUiThread {
                    voiceList.removeView(statusTv)
                    onCount?.invoke(all.size)
                    if (all.isEmpty()) {
                        val hintTv = TextView(ctx)
                        hintTv.text = "未获取到配音魔方音色，请检查 API Key 是否正确"
                        hintTv.setTextSize(13f)
                        hintTv.setTextColor(AppColors.text2())
                        hintTv.setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), 0)
                        voiceList.addView(hintTv)
                    } else {
                        buildVoiceListUI(ctx, parentAct, d, voiceList, key, null, all, ArrayList())
                    }
                }
            }.start()
        }

        /** 加载本地网关豆包音色列表到指定容器（配置弹窗保存后刷新用）。 */
        @JvmStatic
        fun loadGatewayVoices(ctx: Context, parentAct: Activity, d: Float,
                              voiceList: LinearLayout, statusTv: TextView) {
            voiceList.removeAllViews()
            voiceList.addView(statusTv)
            statusTv.text = "加载豆包音色..."
            Thread {
                com.leshao.v3.LogWriter.log("TTSPageView", "loadGatewayVoices: 加载内置傻包音色库 " + VOICE_LIBRARY.size + " 个")
                val finalVoices = compactVoices(VOICE_LIBRARY, MAX_VOICE_ROWS)
                parentAct.runOnUiThread {
                    voiceList.removeView(statusTv)
                    if (finalVoices.isEmpty()) {
                        val hintTv = TextView(ctx)
                        hintTv.text = "音色库为空"
                        hintTv.setTextSize(13f)
                        hintTv.setTextColor(AppColors.text2())
                        hintTv.setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), 0)
                        voiceList.addView(hintTv)
                    } else {
                        buildVoiceListUI(ctx, parentAct, d, voiceList, "", finalVoices, null, null)
                    }
                }
            }.start()
        }

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

        // ===== TTS 播报多音色池（选择音色 · 随机播报） =====

        private const val KEY_VOICE_POOL = "tts_voice_pool"

        /** 读取已选音色池，key 格式 "engine|voice"。 */
        @JvmStatic
        fun loadVoicePool(): MutableSet<String> {
            val set = LinkedHashSet<String>()
            try {
                val arr = JSONArray(WmPrefs.getStr(KEY_VOICE_POOL, "[]"))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val e = o.optString("engine", "")
                    val v = o.optString("voice", "")
                    if (e.isNotEmpty() && v.isNotEmpty()) set.add(e + "|" + v)
                }
            } catch (ignored: Throwable) {}
            return set
        }

        /** 保存音色池。 */
        @JvmStatic
        fun saveVoicePool(pool: Collection<String>) {
            val arr = JSONArray()
            for (key in pool) {
                val idx = key.indexOf('|')
                if (idx <= 0 || idx >= key.length - 1) continue
                val o = JSONObject()
                o.put("engine", key.substring(0, idx))
                o.put("voice", key.substring(idx + 1))
                arr.put(o)
            }
            WmPrefs.setStr(KEY_VOICE_POOL, arr.toString())
        }

        /** 从音色池随机取一个 key（engine|voice）；池为空返回 null。 */
        @JvmStatic
        fun randomVoiceFromPool(): String? {
            val pool = loadVoicePool()
            if (pool.isEmpty()) return null
            val list = ArrayList(pool)
            return list[java.util.concurrent.ThreadLocalRandom.current().nextInt(list.size)]
        }

        /** 统一音色试听：engine 支持 jianying（剪映）/ silly（豆包）/ cube（配音魔方）。 */
        @JvmStatic
        fun previewVoice(ctx: Context, parentAct: Activity, engine: String, voice: String) {
            Toast.makeText(parentAct, "正在合成试听...", Toast.LENGTH_SHORT).show()
            com.leshao.v3.LogWriter.log("TTSPageView", "previewVoice: engine=" + engine + " voice=" + voice)
            Thread {
                var result: String
                try {
                    when (engine) {
                        "jianying" -> {
                            // 每次试听独立发起（WS 已加 15s 握手超时，单个失败不影响其他）
                            val pcm = com.leshao.v3.service.JianYingTtsClient.synthesize(
                                    "欢迎使用乐少助手", voice, 25)
                            val wavBytes = com.leshao.v3.service.JianYingTtsClient.pcmToWav(pcm, 24000)
                            if (wavBytes == null) {
                                result = "合成失败"
                            } else {
                                val wav = File(ctx.cacheDir,
                                        "preview_" + System.currentTimeMillis() + ".wav")
                                FileOutputStream(wav).use { it.write(wavBytes) }
                                result = "OK:" + wav.absolutePath
                            }
                        }
                        "silly" -> {
                            val ogg = com.leshao.v3.service.DoubaoTtsV2Bridge.synthesize(
                                    "欢迎使用乐少助手", voice, 30)
                            val wav = File(ctx.cacheDir,
                                    "preview_" + System.currentTimeMillis() + ".wav")
                            result = if (com.leshao.v3.service.DoubaoTtsV2Bridge.writeWav(ogg, wav)) {
                                "OK:" + wav.absolutePath
                            } else {
                                "解码失败"
                            }
                        }
                        else -> {
                            result = ttsPreviewVoice(WmPrefs.getStr("tts_cube_key", ""),
                                    voice, "欢迎使用乐少助手")
                        }
                    }
                } catch (t: Throwable) {
                    result = "试听失败: " + t.message
                }
                val r = result
                parentAct.runOnUiThread {
                    if (r.startsWith("OK:")) {
                        try {
                            // 单例试听：先释放上一个播放器，避免快速点击累积
                            try { sPreviewPlayer?.stop() } catch (ignored: Throwable) {}
                            try { sPreviewPlayer?.release() } catch (ignored: Throwable) {}
                            sPreviewPlayer = null
                            val mp = android.media.MediaPlayer()
                            sPreviewPlayer = mp
                            mp.setDataSource(r.substring(3))
                            mp.setOnErrorListener { _, _, _ ->
                                try { mp.release() } catch (ignored: Throwable) {}
                                if (sPreviewPlayer === mp) sPreviewPlayer = null
                                true
                            }
                            mp.setOnCompletionListener {
                                try { it.release() } catch (ignored: Throwable) {}
                                if (sPreviewPlayer === it) sPreviewPlayer = null
                            }
                            mp.prepare()
                            mp.start()
                        } catch (t: Throwable) {
                            Toast.makeText(parentAct, "播放失败: " + t.message,
                                    Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(parentAct, r, Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        /** 音色系列「选择音色」多选对话框：支持全选、跨竹节跳动/傻包/配音魔方。 */
        @JvmStatic
        fun showVoicePoolPicker(ctx: Context, parentAct: Activity, d: Float) {
            val pool = loadVoicePool()

            val outerLayout = LinearLayout(ctx)
            outerLayout.orientation = LinearLayout.VERTICAL
            outerLayout.setBackground(CandyUi.pageGradient())
            outerLayout.setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            InsetsUtil.clipRounded(outerLayout)

            val titleBar = LinearLayout(ctx)
            titleBar.orientation = LinearLayout.HORIZONTAL
            titleBar.gravity = Gravity.CENTER_VERTICAL
            val titleTv = TextView(ctx)
            titleTv.text = "选择音色（多音色随机播报）"
            titleTv.setTextSize(16f)
            titleTv.setTextColor(AppColors.textPrimary())
            titleTv.setTypeface(null, Typeface.BOLD)
            titleTv.gravity = Gravity.CENTER
            val ttlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            titleBar.addView(titleTv, ttlp)
            outerLayout.addView(titleBar)
            outerLayout.addView(candyDivider(ctx, d))

            // 三个来源 tab
            val tabRow = LinearLayout(ctx)
            tabRow.orientation = LinearLayout.HORIZONTAL
            tabRow.gravity = Gravity.CENTER_VERTICAL
            tabRow.setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())

            val tabJianying = TextView(ctx)
tabJianying.text = "竹节跳动 " + com.leshao.v3.service.JianYingTtsClient.voices().size + "个"
            tabJianying.setTextSize(11f)
            tabJianying.gravity = Gravity.CENTER
            tabJianying.setSingleLine(true)
            tabJianying.setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
            tabJianying.setBackground(CandyUi.gradientBg(ctx, 16f))
            tabJianying.setTextColor(AppColors.onPrimary())
            val tabL0 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tabL0.setMargins(0, 0, (3 * d).toInt(), 0)
            tabRow.addView(tabJianying, tabL0)

            val tabGateway = TextView(ctx)
            tabGateway.text = "傻包音色 " + VOICE_LIBRARY.size + "个"
            tabGateway.setTextSize(11f)
            tabGateway.gravity = Gravity.CENTER
            tabGateway.setSingleLine(true)
            tabGateway.setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
            tabGateway.setBackground(CandyUi.cardBg(ctx))
            tabGateway.setTextColor(AppColors.text1())
            val tabL1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tabL1.setMargins((3 * d).toInt(), 0, (3 * d).toInt(), 0)
            tabRow.addView(tabGateway, tabL1)

            val tabCube = TextView(ctx)
            tabCube.text = "配音魔方"
            tabCube.setTextSize(11f)
            tabCube.gravity = Gravity.CENTER
            tabCube.setSingleLine(true)
            tabCube.setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
            tabCube.setBackground(CandyUi.cardBg(ctx))
            tabCube.setTextColor(AppColors.text1())
            val tabL2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tabL2.setMargins((3 * d).toInt(), 0, 0, 0)
            tabRow.addView(tabCube, tabL2)
            outerLayout.addView(tabRow)

            // 固定搜索框（不随分页重建，点击弹出输入法，实时过滤）
            val searchEt2 = android.widget.EditText(ctx)
            searchEt2.hint = "搜索音色名称 / ID"
            searchEt2.setTextSize(14f)
            searchEt2.setTextColor(AppColors.text1())
            searchEt2.setHintTextColor(AppColors.text3())
            searchEt2.setSingleLine(true)
            val searchBg3 = GradientDrawable()
            searchBg3.setColor(AppColors.inputBg())
            searchBg3.cornerRadius = (AppColors.SHAPE_INPUT_DP * d).toFloat()
            searchEt2.background = searchBg3
            searchEt2.setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            val searchEt2Lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            searchEt2Lp.setMargins((4 * d).toInt(), 0, (4 * d).toInt(), (6 * d).toInt())
            outerLayout.addView(searchEt2, searchEt2Lp)
            val searchQuery2 = arrayOf("")
            val baseItems2 = arrayOf<MutableList<Array<String>>>(ArrayList())
            val searchCallback2 = arrayOf<((String) -> Unit)?>(null)
            searchEt2.setOnClickListener { showIme(searchEt2) }
            searchEt2.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, st: Int, cnt: Int, aft: Int) {}
                override fun onTextChanged(s: CharSequence, st: Int, bef: Int, cnt: Int) {}
                override fun afterTextChanged(s: Editable) {
                    val cb = searchCallback2[0]
                    if (cb != null) cb(if (s == null) "" else s.toString())
                }
            })

            fun selectTab(tab: String) {
                tabJianying.setBackground(if (tab == "jianying") CandyUi.gradientBg(ctx, 16f) else CandyUi.cardBg(ctx))
                tabJianying.setTextColor(if (tab == "jianying") AppColors.onPrimary() else AppColors.text1())
                tabGateway.setBackground(if (tab == "gateway") CandyUi.gradientBg(ctx, 16f) else CandyUi.cardBg(ctx))
                tabGateway.setTextColor(if (tab == "gateway") AppColors.onPrimary() else AppColors.text1())
                tabCube.setBackground(if (tab == "cube") CandyUi.gradientBg(ctx, 16f) else CandyUi.cardBg(ctx))
                tabCube.setTextColor(if (tab == "cube") AppColors.onPrimary() else AppColors.text1())
            }

            // 列表容器
            val voiceList = LinearLayout(ctx)
            voiceList.orientation = LinearLayout.VERTICAL
            val sv = ScrollView(ctx)
            sv.addView(voiceList)
            val svLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            sv.layoutParams = svLp
            outerLayout.addView(sv)

            val currentTab = java.util.concurrent.atomic.AtomicReference<String>("jianying")

            // 底部操作栏：全选 | 上一页 | 页数 | 下一页 | 保存（每页 50）
            val PAGE_SIZE = 50
            val currentPage = intArrayOf(0)
            val currentItems = arrayOf<MutableList<Array<String>>>(ArrayList())

            val bottomBar = LinearLayout(ctx)
            bottomBar.orientation = LinearLayout.HORIZONTAL
            bottomBar.gravity = Gravity.CENTER_VERTICAL
            bottomBar.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())

            // 底部栏按钮统一模块风格主色（跟随 M3 动态取色/浅色暗色）
            fun makeBottomBtn(text: String): Button {
                val b = Button(ctx)
                b.text = text
                b.setTextSize(13f)
                b.isAllCaps = false
                b.setTextColor(AppColors.onPrimary())
                b.gravity = Gravity.CENTER
                b.setBackground(CandyUi.gradientBg(ctx, AppColors.SHAPE_MD_DP.toFloat()))
                b.setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
                b.minimumHeight = (36 * d).toInt()
                return b
            }
            val selectAllBtn = makeBottomBtn("全选")
            val prevBtn = makeBottomBtn("上页")
            val pageTv = TextView(ctx)
            pageTv.text = "1/1"
            pageTv.setTextSize(13f)
            pageTv.setTextColor(AppColors.text1())
            pageTv.gravity = Gravity.CENTER
            val nextBtn = makeBottomBtn("下页")
            val saveBtn = makeBottomBtn("保存")

            // 按钮等宽均分整行，间距一致，从左到右刚好铺满
            fun poolEqLp(left: Int, right: Int): LinearLayout.LayoutParams {
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.leftMargin = left
                lp.rightMargin = right
                return lp
            }
            val poolGap = (4 * d).toInt()
            bottomBar.addView(selectAllBtn, poolEqLp(0, poolGap))
            bottomBar.addView(prevBtn, poolEqLp(poolGap, poolGap))
            bottomBar.addView(pageTv, poolEqLp(poolGap, poolGap))
            bottomBar.addView(nextBtn, poolEqLp(poolGap, poolGap))
            bottomBar.addView(saveBtn, poolEqLp(poolGap, 0))
            outerLayout.addView(bottomBar)

            val dialog = AlertDialog.Builder(parentAct).create()

            fun totalPages(): Int {
                val size = currentItems[0].size
                return if (size == 0) 1 else (size + PAGE_SIZE - 1) / PAGE_SIZE
            }

            fun engineOf(tab: String): String = when (tab) {
                "gateway" -> "silly"
                "cube" -> "cube"
                else -> "jianying"
            }

            fun renderPage(tab: String) {
                voiceList.removeAllViews()
                val engine = engineOf(tab)
                val base = baseItems2[0]
                val q = searchQuery2[0].trim().lowercase()
                // ★ 选中的音色靠前，再按搜索词过滤
                val sorted = base.sortedByDescending { pool.contains(engine + "|" + it[0]) }
                val items = if (q.isEmpty()) sorted else sorted.filter {
                    ((it[1] ?: "").lowercase().contains(q)) || ((it[0] ?: "").lowercase().contains(q))
                }
                currentItems[0] = ArrayList(items)
                val total = totalPages()
                if (currentPage[0] >= total) currentPage[0] = total - 1
                if (currentPage[0] < 0) currentPage[0] = 0
                val start = currentPage[0] * PAGE_SIZE
                val end = Math.min(start + PAGE_SIZE, items.size)
                if (items.isEmpty()) {
                    val emptyTv = TextView(ctx)
                    emptyTv.text = if (q.isEmpty()) "暂无音色" else "未找到匹配音色"
                    emptyTv.setTextSize(13f)
                    emptyTv.setTextColor(AppColors.text2())
                    emptyTv.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), 0)
                    voiceList.addView(emptyTv)
                } else {
                    for (i in start until end) {
                        val it = items[i]
                        voiceList.addView(makePoolCheckRow(ctx, d, pool, engine, it[0], it[1], it[2]) {
                            previewVoice(ctx, parentAct, engine, it[0])
                        })
                    }
                }
                pageTv.text = (currentPage[0] + 1).toString() + "/" + total
                prevBtn.isEnabled = currentPage[0] > 0
                nextBtn.isEnabled = currentPage[0] < total - 1
            }

            // 搜索回调注册（renderPage 定义后）
            searchCallback2[0] = { q ->
                searchQuery2[0] = q
                currentPage[0] = 0
                renderPage(currentTab.get())
            }

            fun render(tab: String) {
                voiceList.removeAllViews()
                if (tab == "cube") {
                    val k = WmPrefs.getStr("tts_cube_key", "")
                    if (k.isEmpty()) {
                        val hintTv = TextView(ctx)
                        hintTv.text = "未配置配音魔方 API Key，请先在「配音魔方接口配置」中设置"
                        hintTv.setTextSize(13f)
                        hintTv.setTextColor(AppColors.text2())
                        hintTv.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), 0)
                        voiceList.addView(hintTv)
                        baseItems2[0] = ArrayList()
                        currentItems[0] = ArrayList()
                        currentPage[0] = 0
                        pageTv.text = "1/1"
                        prevBtn.isEnabled = false
                        nextBtn.isEnabled = false
                        return
                    }
                    // 启动已自动预载缓存 → 直接秒显，无需手动点击等待
                    val cached = loadCachedCubeVoices()
                    if (cached.isNotEmpty()) {
                        val items = ArrayList<Array<String>>()
                        for (vi in cached) {
                            val id = vi.voiceId ?: continue
                            items.add(arrayOf(id, vi.displayName ?: id, "配音魔方"))
                        }
                        baseItems2[0] = items
                        currentItems[0] = ArrayList(items)
                        currentPage[0] = 0
                        tabCube.text = "配音魔方 " + items.size + "个"
                        renderPage(tab)
                        return
                    }
                    voiceList.addView(TextView(ctx).apply {
                        text = "加载配音魔方音色..."
                        setTextSize(13f)
                        setTextColor(AppColors.text2())
                        setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), 0)
                    })
                    Thread {
                        val all = refreshCubeVoiceCache(k)
                        parentAct.runOnUiThread {
                            val items = ArrayList<Array<String>>()
                            for (vi in all) {
                                val id = vi.voiceId ?: continue
                                items.add(arrayOf(id, vi.displayName ?: id, "配音魔方"))
                            }
                            tabCube.text = "配音魔方 " + items.size + "个"
                            baseItems2[0] = items
                            currentItems[0] = ArrayList(items)
                            currentPage[0] = 0
                            renderPage(tab)
                        }
                    }.start()
                    return
                }
                val items = ArrayList<Array<String>>()
                when (tab) {
                    "jianying" -> {
                        for (v in com.leshao.v3.service.JianYingTtsClient.voices()) {
                            items.add(arrayOf(v.voiceType, v.title ?: v.voiceType, if (v.vip) "VIP音色" else ""))
                        }
                    }
                    "gateway" -> {
                        for (vi in VOICE_LIBRARY) {
                            val id = vi.voiceId ?: continue
                            items.add(arrayOf(id, vi.displayName ?: id, "傻包音色"))
                        }
                    }
                }
                baseItems2[0] = items
                currentItems[0] = ArrayList(items)
                currentPage[0] = 0
                renderPage(tab)
            }

            tabJianying.setOnClickListener { currentTab.set("jianying"); selectTab("jianying"); render("jianying") }
            tabGateway.setOnClickListener { currentTab.set("gateway"); selectTab("gateway"); render("gateway") }
            tabCube.setOnClickListener { currentTab.set("cube"); selectTab("cube"); render("cube") }

            prevBtn.setOnClickListener {
                if (currentPage[0] > 0) {
                    currentPage[0]--
                    renderPage(currentTab.get())
                }
            }
            nextBtn.setOnClickListener {
                if (currentPage[0] < totalPages() - 1) {
                    currentPage[0]++
                    renderPage(currentTab.get())
                }
            }

            selectAllBtn.setOnClickListener {
                val tab = currentTab.get()
                val engine = engineOf(tab)
                val items = currentItems[0]
                if (items.isEmpty()) return@setOnClickListener
                val start = currentPage[0] * PAGE_SIZE
                val end = Math.min(start + PAGE_SIZE, items.size)
                for (i in start until end) {
                    pool.add(engine + "|" + items[i][0])
                }
                renderPage(tab)
                Toast.makeText(parentAct, "已全选本页 " + (end - start) + " 个音色", Toast.LENGTH_SHORT).show()
            }

            saveBtn.setOnClickListener {
                saveVoicePool(pool)
                Toast.makeText(parentAct, "已保存 " + pool.size + " 个音色，播报时将随机使用", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }


            selectTab("jianying")
            render("jianying")

            // 设置视图并显示对话框（此前遗漏 show 导致点击无窗口）
            val host = InsetsUtil.windowAutoHeight(dialog, outerLayout, 0.9f)
            dialog.setView(host)
            dialog.show()
            WindowLayer.track(dialog.window)
            try {
                val w = dialog.window
                w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                w?.setSoftInputMode(
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            } catch (ignored: Throwable) {}
        }

        /** 多选音色行：CheckBox + 名称 + 来源小标签 + 🎧 试听。 */
        private fun makePoolCheckRow(ctx: Context, d: Float, pool: MutableSet<String>,
                                  engine: String, voice: String, title: String, tag: String,
                                  preview: () -> Unit): View {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            row.isClickable = true
            row.isFocusable = true
            val key = engine + "|" + voice
            val checked = arrayOf(key in pool)
            // 自绘圆形勾选：选中=主题色底+白✓，未选中=透明底+outline描边
            val cb = TextView(ctx)
            cb.setTextSize(14f)
            cb.gravity = Gravity.CENTER
            cb.setTypeface(null, Typeface.BOLD)
            fun refreshCb() {
                cb.text = if (checked[0]) "✔" else ""
                cb.setTextColor(if (checked[0]) AppColors.onPrimary() else android.graphics.Color.TRANSPARENT)
                val bg = GradientDrawable()
                bg.setShape(GradientDrawable.OVAL)
                bg.setColor(if (checked[0]) AppColors.primary() else 0x00FFFFFF.toInt())
                bg.setStroke((1.5f * d).toInt(), if (checked[0]) AppColors.primary() else AppColors.outline())
                cb.setBackground(bg)
            }
            refreshCb()
            val cbLp = LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt())
            row.addView(cb, cbLp)

            val nameTv = TextView(ctx)
            nameTv.text = title
            nameTv.setTextSize(15f)
            nameTv.setTextColor(AppColors.textPrimary())
            val nameLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            nameLp.leftMargin = (4 * d).toInt()
            row.addView(nameTv, nameLp)

            // 来源标签颜色跟随音色系：VIP音色=金色 / 傻包音色=蓝色 / 配音魔方=绿色；非 VIP 留空
            val tagColor = when (tag) {
                "VIP音色" -> 0xFFFFB300.toInt()
                "傻包音色" -> 0xFF2196F3.toInt()
                "配音魔方" -> 0xFFF5B301.toInt()
                else -> AppColors.text2()
            }
            val tagTv = TextView(ctx)
            tagTv.text = tag
            tagTv.setTextSize(10f)
            tagTv.setTextColor(tagColor)
            tagTv.setTypeface(null, Typeface.BOLD)
            tagTv.visibility = if (tag.isEmpty()) View.GONE else View.VISIBLE
            val tagLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            tagLp.leftMargin = (4 * d).toInt()
            row.addView(tagTv, tagLp)

            // 试听按钮（独立点击，不触发勾选；尺寸与勾选一致，耳机颜色跟随音色系）
            val earTv = TextView(ctx)
            earTv.text = ""
            earTv.gravity = Gravity.CENTER
            earTv.isClickable = true
            earTv.isFocusable = true
            earTv.setBackground(HeadphoneIconDrawable(tagColor))
            val earLp = LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt())
            earLp.leftMargin = (4 * d).toInt()
            earTv.setOnClickListener { preview() }
            row.addView(earTv, earLp)

            CandyUi.ripple(row, AppColors.SHAPE_SM_DP.toFloat())
            row.setOnClickListener {
                checked[0] = !checked[0]
                if (checked[0]) pool.add(key) else pool.remove(key)
                refreshCb()
            }
            return row
        }

        private fun showKeyInputPopup(ctx: Context, parentAct: Activity, d: Float, keyBtn: Button,
                                       voiceList: LinearLayout, statusTv: TextView,
                                       currentTab: java.util.concurrent.atomic.AtomicReference<String>) {
            val root = M3Page.root(ctx)
            root.addView(M3Page.section(ctx, "接口配置",
                    "配音魔方 API Key 配置；豆包音色为免登录默认音色，无需任何 Key/网关"))

            // 配音魔方
            root.addView(M3Page.fieldLabel(ctx, "配音魔方 API Key"))
            val keyEt = M3Page.input(ctx, "输入配音魔方 API Key（可留空）")
            keyEt.setText(WmPrefs.getStr("tts_cube_key", ""))
            M3Page.trimEdgesOnInput(keyEt)
            root.addView(keyEt)

            val b = AlertDialog.Builder(parentAct)
            b.setView(InsetsUtil.window(null, root, 0.9f, 0.45f))
            b.setCancelable(true)
            val dlg = b.create()
            InsetsUtil.centerAutoHeight(dlg, 0.9f)
            val w = dlg.window
            if (w != null) {
                InsetsUtil.transparentWindow(w)
            }

            val saveBtn = M3Page.button(ctx, "保存") {
                val key = keyEt.text.toString().trim()
                WmPrefs.setStr("tts_cube_key", key)
                Toast.makeText(parentAct, "配置已保存，请重新点击「配音魔方」标签刷新",
                        Toast.LENGTH_SHORT).show()
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
                                     gateway: List<VoiceItem>?, builtin: List<VoiceItem>?, custom: List<VoiceItem>?) {
            buildVoiceListUI(ctx, parentAct, d, voiceList, key, gateway, builtin, custom, "")
        }

        private fun buildVoiceListUI(ctx: Context, parentAct: Activity, d: Float,
                                     voiceList: LinearLayout, key: String,
                                     gateway: List<VoiceItem>?, builtin: List<VoiceItem>?, custom: List<VoiceItem>?,
                                     searchHint: String?) {
            val savedVoice = WmPrefs.getStr("tts_cube_voice", "")
            val savedGatewayVoice = WmPrefs.getStr("tts_gateway_voice", "")

            // 搜索框
            val searchEt = EditText(ctx)
            searchEt.setHint("搜索音色名称...")
            searchEt.setTextSize(13f)
            searchEt.setSingleLine(true)
            searchEt.isFocusable = true
            searchEt.isFocusableInTouchMode = true
            searchEt.isClickable = true
            searchEt.inputType = android.text.InputType.TYPE_CLASS_TEXT
            searchEt.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
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
            // 确保点击可聚焦并弹出输入法
            searchEt.setOnTouchListener { _, event ->
                searchEt.requestFocus()
                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    showIme(searchEt)
                }
                false
            }
            searchEt.setOnClickListener {
                showIme(searchEt)
            }
            voiceList.addView(searchEt)

            // 刷新回调：重新构建列表并保留搜索关键词
            val refreshUi = arrayOfNulls<Runnable>(1)
            refreshUi[0] = Runnable {
                val q = searchEt.text.toString()
                voiceList.removeAllViews()
                buildVoiceListUI(ctx, parentAct, d, voiceList, key, gateway, builtin, custom, q)
            }

            // 收集所有voice rows用于搜索
            val allVoiceRows = ArrayList<View>()
            val allVoiceSearchText = ArrayList<String>()
            val groupHeaders = ArrayList<View>()

            // ★ 傻包音色（免登录）—— 列表顶部，无小标题
            if (gateway != null && gateway.isNotEmpty()) {
                for (vi in gateway) {
                    val row = buildVoiceRow(ctx, parentAct, d, key, vi, savedGatewayVoice, refreshUi[0], true)
                    voiceList.addView(row)
                    allVoiceRows.add(row)
                    allVoiceSearchText.add((vi.displayName ?: vi.voiceId) + "|" + (vi.group ?: ""))
                }
            }

            // 内置音色（无外层小标题，保留分组标题）
            if (builtin != null && builtin.isNotEmpty()) {
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
                    val row = buildVoiceRow(ctx, parentAct, d, key, vi, savedVoice, refreshUi[0], false)
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
                    val row = buildVoiceRow(ctx, parentAct, d, key, vi, savedVoice, refreshUi[0], false)
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
                                  key: String, vi: VoiceItem, savedVoice: String, onSelected: Runnable?,
                                  isGateway: Boolean): View {
            // ★ 免登录多音色方案（doubao-tts-icl-voicepack）：豆包音色 tab 全部免登录，选中状态看 tts_freelogin_voice
            val isFreeLogin = isGateway
            val isSelected = if (isFreeLogin) {
                vi.voiceId == WmPrefs.getStr("tts_freelogin_voice", "")
            } else {
                vi.voiceId == savedVoice
            }

            // 外层垂直容器：内容行 + 小分割线（M3 主题风格，列表不再紧凑）
            val container = LinearLayout(ctx)
            container.orientation = LinearLayout.VERTICAL
            container.setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())

            val vRow = LinearLayout(ctx)
            vRow.orientation = LinearLayout.HORIZONTAL
            vRow.gravity = Gravity.CENTER_VERTICAL

            // 选择按钮（左侧，加大 3dp）
            val selBtn = TextView(ctx)
            selBtn.text = if (isSelected) "✔" else ""
            selBtn.setTextSize(15f)
            selBtn.gravity = Gravity.CENTER
            selBtn.setTextColor(if (isSelected) AppColors.accent() else AppColors.text3())
            selBtn.setTypeface(null, Typeface.BOLD)
            val selBg = GradientDrawable()
            selBg.setShape(GradientDrawable.OVAL)
            selBg.setColor(if (isSelected)
                    ((AppColors.primary() and 0x00FFFFFF) or (0x33 shl 24)) else 0x00FFFFFF.toInt())
            selBg.setStroke((1.5f * d).toInt(), if (isSelected) AppColors.accent() else AppColors.text3())
            selBtn.setBackground(selBg)
            val selSize = (25 * d).toInt()
            selBtn.layoutParams = LinearLayout.LayoutParams(selSize, selSize)
            vRow.addView(selBtn)

            vRow.addView(spacerH(ctx, d, 10))

            // 名称 + 演员（加大 3dp）
            val displayName = vi.displayName ?: vi.voiceId
            val actor = vi.actor
            val subtitle = if (actor != null && actor.isNotEmpty()) " (" + actor + ")" else ""
            val vName = TextView(ctx)
            vName.text = displayName + subtitle
            vName.setTextSize(16f)
            vName.setTextColor(AppColors.text1())
            vName.setTypeface(null, Typeface.BOLD)
            val vnlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            vRow.addView(vName, vnlp)

            // 试听按钮（主题色耳机图标，尺寸与左侧勾选一致）
            val listenBtn = TextView(ctx)
            listenBtn.text = ""
            listenBtn.gravity = Gravity.CENTER
            listenBtn.setBackground(HeadphoneIconDrawable(AppColors.primary()))
            val iconSize = (28 * d).toInt()
            listenBtn.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            vRow.addView(listenBtn)

            CandyUi.ripple(listenBtn, AppColors.SHAPE_FULL_DP.toFloat())
            listenBtn.setOnClickListener {
                Thread {
                    val result = if (isGateway) {
                        // 免登录豆包音色：直接用选中 voiceId 合成试听
                        freeLoginPreview(vi.voiceId ?: "", "欢迎使用乐少助手")
                    } else {
                        ttsPreviewVoice(key, vi.voiceId ?: "", "欢迎使用乐少助手")
                    }
                    if (result.startsWith("OK:")) {
                        // 单例试听：先释放上一个播放器
                        try { sPreviewPlayer?.stop() } catch (ignored: Throwable) {}
                        try { sPreviewPlayer?.release() } catch (ignored: Throwable) {}
                        sPreviewPlayer = null
                        val mp = MediaPlayer()
                        sPreviewPlayer = mp
                        try {
                            mp.setDataSource(result.substring(3))
                            mp.setOnErrorListener { _, _, _ ->
                                try { mp.release() } catch (ignored: Throwable) {}
                                if (sPreviewPlayer === mp) sPreviewPlayer = null
                                true
                            }
                            mp.setOnCompletionListener {
                                try { it.release() } catch (ignored: Throwable) {}
                                if (sPreviewPlayer === it) sPreviewPlayer = null
                            }
                            mp.prepare()
                            mp.start()
                            parentAct.runOnUiThread {
                                Toast.makeText(parentAct, "试听: " + displayName, Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            try { mp.release() } catch (ignored: Throwable) {}
                            if (sPreviewPlayer === mp) sPreviewPlayer = null
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
                if (isGateway) {
                    // 豆包音色（免登录）：保存 voiceId，与其他来源互斥
                    WmPrefs.setStr("tts_freelogin_voice", vi.voiceId ?: "")
                    WmPrefs.setStr("tts_gateway_voice", "")
                    WmPrefs.setStr("tts_cube_voice", "")
                } else {
                    // 配音魔方音色
                    WmPrefs.setStr("tts_cube_voice", vi.voiceId ?: "")
                    WmPrefs.setStr("tts_gateway_voice", "")
                    WmPrefs.setStr("tts_freelogin_voice", "")
                }
                Toast.makeText(parentAct, "已选择默认音色: " + displayName, Toast.LENGTH_SHORT).show()
                if (onSelected != null) onSelected.run()
            }

            container.addView(vRow)

            // 每个音色小分割线
            val divider = View(ctx)
            divider.setBackgroundColor(AppColors.divider())
            val dlp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, (0.8f * d).toInt()))
            dlp.topMargin = (6 * d).toInt()
            dlp.bottomMargin = (2 * d).toInt()
            container.addView(divider, dlp)

            return container
        }

        /** 显式弹出输入法（修复搜索框点击无反应）。 */
        private fun showIme(et: EditText) {
            try {
                val imm = et.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            } catch (ignored: Throwable) {}
        }

        /** 免登录豆包 TTS 试听：按 voiceId 合成 OGG → 解码 WAV → 返回 "OK:路径"。 */
        private fun freeLoginPreview(voiceId: String, text: String): String {
            return try {
                com.leshao.v3.LogWriter.log("TTSPageView", "freelogin preview start voice=" + voiceId + " text=" + text)
                val bytes = com.leshao.v3.service.DoubaoTtsV2Bridge.synthesize(text, voiceId, 30)
                com.leshao.v3.LogWriter.log("TTSPageView", "freelogin preview synth bytes=" + bytes.size)
                val appCtx = ContextManager.getAppContext()
                val cacheDir = appCtx?.cacheDir ?: File(Environment.getExternalStorageDirectory(), "leshao_v3_cache")
                val ttsDir = File(cacheDir, "tts_preview")
                ttsDir.mkdirs()
                val outFile = File(ttsDir, "freelogin_" + System.currentTimeMillis() + ".wav")
                if (com.leshao.v3.service.DoubaoTtsV2Bridge.writeWav(bytes, outFile)) {
                    com.leshao.v3.LogWriter.log("TTSPageView", "freelogin preview OK=" + outFile.absolutePath)
                    "OK:" + outFile.absolutePath
                } else {
                    "解码失败"
                }
            } catch (e: Exception) {
                com.leshao.v3.LogWriter.log("TTSPageView", "freelogin preview err: " + e)
                "合成失败: " + e.message
            }
        }

        // ===== 音色数据模型 =====

        /** v985: 供 AI 助手会话/模板配置复用: 拉取全部音色（傻包音色库 + 配音魔方内置/我的）。 */
        @JvmStatic
        fun fetchAllVoices(key: String?): List<VoiceItem> {
            val all = ArrayList<VoiceItem>()
            // 竹节跳动（剪映 797 个，免网关直连合成）——仅 VIP 音色显示「VIP音色」来源，非 VIP 留空
            for (v in com.leshao.v3.service.JianYingTtsClient.voices()) {
                all.add(VoiceItem(v.voiceType, if (v.vip) "VIP音色" else "", v.title,
                        ""))
            }
            // 傻包音色库（内置 100 个，免登录）
            all.addAll(VOICE_LIBRARY)
            // 配音魔方（若配置了 key）：优先本地缓存，无缓存则网络拉取并写缓存
            if (key != null && key.trim().isNotEmpty()) {
                val cached = loadCachedCubeVoices()
                if (cached.isNotEmpty()) {
                    all.addAll(cached)
                } else {
                    all.addAll(refreshCubeVoiceCache(key))
                }
            }
            return all
        }

        /** 是否为傻包内置音色（属于 VOICE_LIBRARY）。 */
        @JvmStatic
        fun isBuiltinVoice(voiceId: String?): Boolean {
            if (voiceId == null || voiceId.isEmpty()) return false
            return VOICE_LIBRARY.any { it.voiceId == voiceId }
        }

        // ===== 配音魔方音色缓存（微信启动后自动预载，打开列表秒显） =====

        /** 读取配音魔方音色本地缓存（无/损坏返回空列表）。 */
        @JvmStatic
        fun loadCachedCubeVoices(): List<VoiceItem> {
            val out = ArrayList<VoiceItem>()
            try {
                val raw = WmPrefs.getStr(KEY_CUBE_VOICES_CACHE, "")
                if (raw.isEmpty()) return out
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("voiceId", "")
                    if (id.isEmpty()) continue
                    out.add(VoiceItem(id,
                            o.optString("group", ""),
                            o.optString("displayName", id),
                            o.optString("actor", "")))
                }
            } catch (ignored: Throwable) {}
            return out
        }

        /** 将配音魔方音色列表写入本地缓存。 */
        @JvmStatic
        fun saveCubeVoicesCache(voices: List<VoiceItem>) {
            try {
                val arr = JSONArray()
                for (vi in voices) {
                    val id = vi.voiceId ?: continue
                    if (id.isEmpty()) continue
                    val o = JSONObject()
                    o.put("voiceId", id)
                    o.put("group", vi.group ?: "")
                    o.put("displayName", vi.displayName ?: "")
                    o.put("actor", vi.actor ?: "")
                    arr.put(o)
                }
                WmPrefs.setStr(KEY_CUBE_VOICES_CACHE, arr.toString())
                WmPrefs.setStr(KEY_CUBE_VOICES_CACHE_TIME, System.currentTimeMillis().toString())
            } catch (ignored: Throwable) {}
        }

        /** 微信启动后自动刷新配音魔方音色缓存（未配置 key 直接返回空；网络失败不覆盖已有缓存）。 */
        @JvmStatic
        fun refreshCubeVoiceCache(key: String?): List<VoiceItem> {
            if (key == null || key.trim().isEmpty()) return ArrayList()
            val all = ArrayList<VoiceItem>()
            try {
                val builtin = fetchBuiltinVoices(key.trim())
                val custom = fetchUserVoices(key.trim())
                all.addAll(compactVoices(builtin, MAX_VOICE_ROWS))
                all.addAll(compactVoices(custom, 50))
                // 网络失败/无数据时不写缓存，避免用空列表覆盖已预载的有效音色
                if (all.isNotEmpty()) {
                    saveCubeVoicesCache(all)
                }
            } catch (t: Throwable) {
                com.leshao.v3.LogWriter.log("TTSPageView", "refreshCubeVoiceCache err: " + t)
            }
            return all
        }

        /** 获取配音魔方音色：优先返回本地缓存，缓存为空时网络拉取并写缓存。 */
        @JvmStatic
        fun getCubeVoicesWithCache(key: String?): List<VoiceItem> {
            val cached = loadCachedCubeVoices()
            if (cached.isNotEmpty()) return cached
            return refreshCubeVoiceCache(key)
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

        /** 本地网关音色列表：GET {base}/voices，Authorization: Bearer key。
         *  兼容 {"data":[...]} 与裸数组两种响应；voiceId 兼容 voiceId/voice_id/id 字段。 */
        private fun fetchGatewayVoices(base: String, key: String): List<VoiceItem> {
            // 音色列表固定 GET /v1/voices（展示 name，选中保存 id doubao:voice_xxx 作为 voiceId）。
            // 设置里已选模型时，优先带模型参数请求。
            val model = WmPrefs.getStr("tts_gateway_model", "").trim()
            if (model.isNotEmpty()) {
                val withModel = fetchGatewayList(base, key, "voices?model=" + model, "傻包音色")
                if (withModel.isNotEmpty()) return withModel
            }
            return fetchGatewayList(base, key, "voices", "傻包音色")
        }

        /** 拉取网关列表：GET {base}/{path}，兼容 voiceId/voice_id/id 字段与 {"data":[...]} / 裸数组。 */
        private fun fetchGatewayList(base: String, key: String, path: String, group: String): List<VoiceItem> {
            val list = ArrayList<VoiceItem>()
            try {
                val url = base.trimEnd('/') + "/" + path
                com.leshao.v3.LogWriter.log("TTSPageView", "gateway GET " + url + " key=" + maskGatewayKey(key))
                val resp = com.leshao.v3.service.GatewayHttpClient.get(url, key)
                val body = resp.bodyString()
                com.leshao.v3.LogWriter.log("TTSPageView", "gateway " + path + " resp code=" + resp.code
                        + " body=" + (if (body.length > 300) body.substring(0, 300) + "..." else body))
                if (resp.code != 200) return list
                val trimmed = body.trim()
                val arr: org.json.JSONArray
                if (trimmed.startsWith("[")) {
                    arr = JSONArray(trimmed)
                } else {
                    val jo = safeJsonObject(trimmed)
                    arr = jo.optJSONArray("data") ?: jo.optJSONArray("voices") ?: jo.optJSONArray("models") ?: return list
                }
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val voiceId = o.optString("voiceId", "")
                        .ifEmpty { o.optString("voice_id", "") }
                        .ifEmpty { o.optString("id", "") }
                    if (voiceId.isEmpty()) continue
                    val name = o.optString("name", voiceId)
                        .ifEmpty { o.optString("voiceName", voiceId) }
                        .ifEmpty { o.optString("display_name", voiceId) }
                    list.add(VoiceItem(voiceId, group, name, ""))
                }
                com.leshao.v3.LogWriter.log("TTSPageView", "gateway " + path + " parsed count=" + list.size)
            } catch (e: Exception) {
                com.leshao.v3.LogWriter.log("TTSPageView", "gateway " + path + " err: " + e)
            }
            return list
        }

        /** 日志脱敏：网关密钥只保留首尾各 2 位。 */
        private fun maskGatewayKey(key: String): String {
            if (key.isEmpty()) return "<empty>"
            if (key.length <= 4) return "****"
            return key.substring(0, 2) + "****" + key.substring(key.length - 2)
        }

        /** 本地网关合成（OpenAI /audio/speech 标准）：POST {base}/audio/speech，
         *  body {model:"doubao", input:text, voice:voiceId}，响应 mp3 二进制 → 缓存文件，返回 "OK:绝对路径" 或错误文本。 */
        @JvmStatic
        fun gatewayPreviewVoice(base: String, key: String, voiceId: String, text: String): String {
            if (base.isEmpty() || key.isEmpty()) return "未配置本地网关地址或密钥"
            return try {
                val req = JSONObject()
                    .put("model", "doubao")
                    .put("input", text)
                    .put("voice", voiceId)
                val resp = com.leshao.v3.service.GatewayHttpClient.postJson(
                    base.trimEnd('/') + "/audio/speech", key, req.toString())
                if (resp.code != 200) {
                    return "合成失败: HTTP " + resp.code + (if (resp.bodyString().isEmpty()) "" else " - " + resp.bodyString())
                }
                val appCtx = ContextManager.getAppContext()
                val cacheDir = appCtx?.cacheDir ?: File(Environment.getExternalStorageDirectory(), "leshao_v3_cache")
                val ttsDir = File(cacheDir, "tts_preview")
                ttsDir.mkdirs()
                val outFile = File(ttsDir, "gateway_" + System.currentTimeMillis() + ".mp3")
                val bytes = resp.body ?: ByteArray(0)
                FileOutputStream(outFile).use { it.write(bytes) }
                if (outFile.length() < 100L) {
                    return "下载失败: 音频文件过小(" + outFile.length() + "字节)"
                }
                "OK:" + outFile.absolutePath
            } catch (e: Exception) {
                "合成失败: " + e.message
            }
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
            if (raw == null) throw Exception("响应为空")
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) throw Exception("响应为空")
            val first = trimmed[0]
            if (first != '{' && first != '[') throw Exception("接口返回非JSON数据")
            return JSONObject(raw)
        }

        private fun candyDivider(ctx: Context, d: Float): View {
            return M3Page.divider(ctx)
        }

        /** 设置图标: Material 3「推子/滑块」(tune) 空心描边样式, 逻辑坐标系 24x24。 */
        /** 创建指定颜色的耳机试听图标（供 Java/Kotlin 复用）。 */
        @JvmStatic
        fun headphoneIcon(color: Int): android.graphics.drawable.Drawable {
            return HeadphoneIconDrawable(color)
        }

        /** 实心耳机图标：实心耳罩 + 粗头梁，用于音色行「试听」按钮，颜色可跟随音色来源。 */
        class HeadphoneIconDrawable(color: Int) : android.graphics.drawable.Drawable() {
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)

            init {
                fillPaint.color = color
                fillPaint.style = Paint.Style.FILL
                strokePaint.color = color
                strokePaint.style = Paint.Style.STROKE
                strokePaint.strokeWidth = 2.6f
                strokePaint.strokeCap = Paint.Cap.ROUND
            }

            override fun draw(canvas: android.graphics.Canvas) {
                val b = bounds
                val size = Math.min(b.width(), b.height())
                val u = size / 24f
                val ox = b.exactCenterX() - 12f * u
                val oy = b.exactCenterY() - 12f * u

                // 实心耳罩
                canvas.drawRoundRect(android.graphics.RectF(ox + 3f * u, oy + 10f * u,
                        ox + 7.5f * u, oy + 17f * u), 2f * u, 2f * u, fillPaint)
                canvas.drawRoundRect(android.graphics.RectF(ox + 16.5f * u, oy + 10f * u,
                        ox + 21f * u, oy + 17f * u), 2f * u, 2f * u, fillPaint)

                // 粗头梁（实心弧线）
                val path = android.graphics.Path()
                path.moveTo(ox + 5.5f * u, oy + 10f * u)
                path.addArc(android.graphics.RectF(ox + 5.5f * u, oy + 3f * u,
                        ox + 18.5f * u, oy + 17f * u), 180f, 180f)
                strokePaint.strokeWidth = 2.6f * u
                canvas.drawPath(path, strokePaint)
            }

            override fun setAlpha(alpha: Int) {
                fillPaint.alpha = alpha
                strokePaint.alpha = alpha
            }

            override fun setColorFilter(cf: android.graphics.ColorFilter?) {
                fillPaint.colorFilter = cf
                strokePaint.colorFilter = cf
            }

            override fun getOpacity(): Int {
                return android.graphics.PixelFormat.TRANSLUCENT
            }
        }

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