package com.leshao.v3.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

import com.leshao.v3.ChatFooterLongPressMenu
import com.leshao.v3.LogWriter
import com.leshao.v3.hook.TtsVoiceSender
import com.leshao.v3.ui.widgets.M3Page
import com.leshao.v3.ui.widgets.SegmentedControl
import com.leshao.v3.wm.utils.WmPrefs

import java.io.ByteArrayOutputStream
import java.util.Locale

class AudioMixEditorPage private constructor(
    private val ctx: Context,
    private val talker: String?
) {

    companion object {
        private const val TAG = "LsAudioMix"

        const val TYPE_FILE = 0
        const val TYPE_TTS = 1
        const val TYPE_REC = 2

        private const val MODE_STITCH = 0
        private const val MODE_MIX = 1

        private const val SAMPLE_RATE = 24000
        private const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000
        private const val ENV_COLUMNS = 1800
        private const val REC_SAMPLE_RATE = 24000

        private const val MIN_SEG_MS = 80
        private const val PREVIEW_TAIL_MS = 3000
        private const val PREVIEW_LEAD_MS = 3000

        private const val STEP_RESET = Int.MIN_VALUE
        private const val UNDO_MAX = 60

        private const val NONE = 0
        private const val BODY = 1
        private const val TRIM_L = 2
        private const val TRIM_R = 3
        private const val PAN = 4
        private const val SCRUB = 5
        private const val MAX_ZOOM = 12f

        @JvmStatic
        fun show(ctx: Context, talker: String?) {
            try {
                AudioMixEditorPage(ctx, talker).showInternal()
            } catch (t: Throwable) {
                LogWriter.log(TAG, "show err: " + android.util.Log.getStackTraceString(t))
                try {
                    Toast.makeText(ctx, "打开编辑页失败", Toast.LENGTH_SHORT).show()
                } catch (ignored: Throwable) {}
            }
        }

        @JvmStatic
        private fun withAlpha(color: Int, alpha: Int): Int {
            return (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
        }

        @JvmStatic
        private fun clampGain(percent: Int): Float {
            var p = percent
            if (p < 0) p = 0
            if (p > 200) p = 200
            return p / 100f
        }

        @JvmStatic
        private fun gainDb(g: Float): String {
            if (g <= 0.001f) return "-60.0"
            val db = 20.0 * Math.log10(g.toDouble())
            return String.format(Locale.US, "%.1f", db)
        }

        @JvmStatic
        private fun fmtMs(ms: Int): String {
            var m = ms
            if (m < 0) m = 0
            val total = m / 1000
            val deci = (m % 1000) / 100
            return String.format(Locale.US, "%d:%02d.%d", total / 60, total % 60, deci)
        }

        @JvmStatic
        private fun fmtMs3(ms: Int): String {
            var m = ms
            if (m < 0) m = 0
            val total = m / 1000
            return String.format(Locale.US, "%d:%02d.%03d", total / 60, total % 60, m % 1000)
        }

        @JvmStatic
        private fun shortVoiceName(name: String?): String {
            if (name == null) return "默认"
            val i = name.lastIndexOf('#')
            var n = if (i >= 0) name.substring(i + 1) else name
            if (n.length > 10) n = n.substring(0, 10)
            return if (n.isEmpty()) "默认" else n
        }

        @JvmStatic
        private fun cutPcmMs(pcm: ByteArray?, beginMs: Int, endMs: Int): ByteArray {
            if (pcm == null || pcm.isEmpty()) return ByteArray(0)
            var b = Math.max(0, beginMs) * BYTES_PER_MS
            var e = Math.min(endMs, pcm.size / BYTES_PER_MS) * BYTES_PER_MS
            if (b % 2 != 0) b++
            if (e % 2 != 0) e++
            if (e > pcm.size) e = pcm.size - (pcm.size % 2)
            if (b >= e) return ByteArray(0)
            return pcm.copyOfRange(b, e)
        }

        @JvmStatic
        private fun fillEnvs(s: Seg) {
            if (s == null) return
            val pcm = s.pcm
            if (pcm == null || pcm.size < 2) {
                s.env = FloatArray(0)
                s.envPeak = FloatArray(0)
                return
            }
            val samples = pcm.size / 2
            val columns = Math.min(ENV_COLUMNS, Math.max(8, samples))
            val pkEnv = FloatArray(columns)
            val rmsEnv = FloatArray(columns)
            val step = samples.toDouble() / columns
            var maxPk = 1e-9
            var maxRms = 1e-9
            for (c in 0 until columns) {
                var s0 = (c * step).toInt()
                var s1 = ((c + 1) * step).toInt()
                if (s1 <= s0) s1 = s0 + 1
                if (s1 > samples) s1 = samples
                var pk = 0
                var sum = 0.0
                for (i in s0 until s1) {
                    val v = ((pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)).toShort().toInt()
                    val a = if (v < 0) -v else v
                    if (a > pk) pk = a
                    sum += v.toDouble() * v.toDouble()
                }
                val pv = (pk / 32768.0).toFloat()
                val rv = (Math.sqrt(sum / Math.max(1, s1 - s0)) / 32768.0).toFloat()
                pkEnv[c] = pv
                rmsEnv[c] = rv
                if (pv > maxPk) maxPk = pv.toDouble()
                if (rv > maxRms) maxRms = rv.toDouble()
            }
            for (c in 0 until columns) {
                var p = Math.pow((pkEnv[c] / maxPk).toDouble(), 0.85)
                if (p < 0.04) p = 0.04 else if (p > 1) p = 1.0
                pkEnv[c] = p.toFloat()
                var r = Math.pow((rmsEnv[c] / maxRms).toDouble(), 0.65)
                if (r < 0.04) r = 0.04 else if (r > 1) r = 1.0
                rmsEnv[c] = r.toFloat()
            }
            s.envPeak = pkEnv
            s.env = rmsEnv
        }

        @JvmStatic
        private fun sliceEnv(parent: FloatArray?, aMs: Int, bMs: Int, durMs: Int): FloatArray {
            if (parent == null || parent.isEmpty() || durMs <= 0) return FloatArray(0)
            var a = (aMs.toLong() * parent.size / durMs).toInt()
            var b = Math.ceil((bMs.toDouble() * parent.size / durMs)).toInt()
            if (a < 0) a = 0
            if (a > parent.size) a = parent.size
            if (b <= a) b = a + 1
            if (b > parent.size) b = parent.size
            return parent.copyOfRange(a, b)
        }
    }

    class Seg internal constructor(
        var name: String,
        var type: Int,
        var pcm: ByteArray?
    ) {
        var durMs: Int = TtsVoiceSender.pcmDurationMs(pcm)
        var startMs: Int = 0
        var endMs: Int = 0
        var gain: Float = 1f
        var offsetMs: Int = 0
        var env: FloatArray? = null
        var envPeak: FloatArray? = null

        init {
            endMs = durMs
        }

        fun effStart(): Int {
            if (startMs < 0) return 0
            return if (startMs > durMs) durMs else startMs
        }

        fun effEnd(): Int {
            var e = if (endMs <= 0) durMs else endMs
            if (e > durMs) e = durMs
            val s = effStart()
            return if (e < s) s else e
        }

        fun effDur(): Int {
            return Math.max(0, effEnd() - effStart())
        }

        fun trimmed(): Boolean {
            return effStart() > 0 || effEnd() < durMs
        }

        fun effective(): ByteArray? {
            if (!trimmed()) return pcm
            return cutPcmMs(pcm, effStart(), effEnd())
        }

        fun typeLabel(): String {
            return when (type) {
                TYPE_TTS -> "文字转语音"
                TYPE_REC -> "录音"
                else -> "音频文件"
            }
        }
    }

    private class SegState internal constructor(
        val seg: Seg,
        val startMs: Int,
        val endMs: Int,
        val offsetMs: Int,
        val gain: Float
    ) {
        constructor(s: Seg) : this(s, s.startMs, s.endMs, s.offsetMs, s.gain)
    }

    private val segs: MutableList<Seg> = ArrayList()
    private var dialog: Dialog? = null
    private var modeCtrl: SegmentedControl? = null
    private var timeline: TimelineView? = null
    private var listCard: LinearLayout? = null
    private var editorHost: LinearLayout? = null
    private var emptyHint: TextView? = null
    private var exportBtn: TextView? = null
    private var playAllBtn: TextView? = null

    private var mode = MODE_STITCH
    private var selected = -1

    private var globalPlayer: Player? = null
    private var editPlayer: Player? = null

    private var cursorValueLabel: TextView? = null
    private var playIconBtn: TextView? = null
    private var timelineHeightDp = -1
    private var lastPreviewFrom = Int.MIN_VALUE

    private var dragSeg: Seg? = null
    private var dragIndex = -1
    private var dragActive = false

    private var lastTtsVoice: String? = null

    private val undoStack: MutableList<MutableList<SegState>> = ArrayList()
    private var pendingUndo: MutableList<SegState>? = null

    private var recorder: AudioRecord? = null
    @Volatile private var recording = false
    private var recBuffer: ByteArrayOutputStream? = null
    @Volatile private var exporting = false
    @Volatile private var preparingPreview = false

    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Int): Int {
        return (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun dp(v: Float): Int {
        return (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun dpF(v: Float): Float {
        return v * ctx.resources.displayMetrics.density + 0.5f
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        } catch (ignored: Throwable) {}
    }

    private fun activityOf(): Activity? {
        var c: Context? = ctx
        while (c != null) {
            if (c is Activity) return c
            c = if (c is android.content.ContextWrapper) c.baseContext else null
        }
        return null
    }

    private fun showInternal() {
        globalPlayer = Player(Runnable { onGlobalTick() }, Runnable { onGlobalFinished() })
        editPlayer = Player(Runnable { onEditTick() }, Runnable { onEditFinished() })

        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.background = CandyUi.pageGradient()
        val m = dp(12)
        root.setPadding(m, dp(10), m, dp(10))

        root.addView(buildTopBar(), LinearLayout.LayoutParams(-1, -2))

        modeCtrl = SegmentedControl(ctx, arrayOf("拼接", "混合"), 0)
        modeCtrl?.setOnSegmentChangedListener(object : SegmentedControl.OnSegmentChangedListener {
            override fun onChanged(index: Int, label: String) {
                mode = index
                rebuildTimeline()
                rebuildEditor()
                rebuildList()
            }
        })
        val modeLp = LinearLayout.LayoutParams(-1, -2)
        modeLp.setMargins(dp(2), dp(8), dp(2), dp(8))
        root.addView(modeCtrl, modeLp)

        val sv = ScrollView(ctx)
        sv.isFillViewport = true
        val content = LinearLayout(ctx)
        content.orientation = LinearLayout.VERTICAL
        sv.addView(content, ViewGroup.LayoutParams(-1, -2))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(hint("时间线: 点/拖波形定位剪辑游标; 双指捏合放大波形精确剪辑; 左右滑动波形可移动时间; 长按某段可直接删除; 剪辑按钮在波形下方"))
        content.addView(M3Page.spacer(ctx, 6f))

        val tl = TimelineView(ctx)
        timeline = tl
        val tlLp = LinearLayout.LayoutParams(-1, dp(130))
        tlLp.setMargins(0, 0, 0, dp(6))
        content.addView(tl, tlLp)

        val eh = LinearLayout(ctx)
        eh.orientation = LinearLayout.VERTICAL
        editorHost = eh
        content.addView(eh)

        content.addView(M3Page.section(ctx, "片段列表", "点「剪辑」后在时间线上左右拖动两端手柄剪辑 / 行内试听、删除"))

        val lc = M3Page.card(ctx)
        listCard = lc
        content.addView(lc)

        val hintView = M3Page.note(ctx, "还没有片段。用下方「选择音频 / 文字转语音 / 录制录音」添加。")
        emptyHint = hintView
        content.addView(hintView)

        content.addView(buildAddRow())

        root.addView(buildBottomBar(), LinearLayout.LayoutParams(-1, -2))

        val d = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar)
        val host = InsetsUtil.windowAutoHeight(d, root, 0.9f)
        d.setContentView(host)
        dialog = d
        val w = d.window
        if (w != null) {
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val lp = w.attributes
            lp.dimAmount = 0.5f
            w.attributes = lp
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        d.setOnDismissListener {
            globalPlayer?.release()
            editPlayer?.release()
            stopRecording(true)
        }
        d.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                d.dismiss()
                true
            } else {
                false
            }
        }
        rebuildTimeline()
        rebuildEditor()
        rebuildList()
        d.show()
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL

        val back = TextView(ctx)
        back.text = "‹"
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        back.setTextColor(AppColors.onSurface())
        back.gravity = Gravity.CENTER
        back.setPadding(dp(6), 0, dp(6), 0)
        back.setOnClickListener { dialog?.dismiss() }
        bar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))

        val title = TextView(ctx)
        title.text = "音频拼接与混合"
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(AppColors.onSurface())
        title.gravity = Gravity.CENTER
        title.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        bar.addView(title)

        val send = TextView(ctx)
        send.text = "发送"
        send.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        send.typeface = Typeface.DEFAULT_BOLD
        send.setTextColor(AppColors.primary())
        send.gravity = Gravity.CENTER
        send.setPadding(dp(10), dp(6), dp(6), dp(6))
        send.setOnClickListener { doExport() }
        bar.addView(send)
        return bar
    }

    private fun buildAddRow(): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, dp(8), 0, dp(4))
        row.layoutParams = lp
        row.addView(smallButton("选择音频", this::onAddFile), weight())
        row.addView(smallButton("文字转语音", this::onAddTts), weight())
        row.addView(smallButton("录制录音", this::onAddRec), weight())
        return row
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(0, dp(8), 0, 0)

        val paBtn = outlined("试听全部")
        playAllBtn = paBtn
        paBtn.setOnClickListener {
            if (globalPlayer?.isActive() == true) {
                globalPlayer?.stop()
                return@setOnClickListener
            }
            if (preparingPreview) return@setOnClickListener
            if (segs.isEmpty()) {
                toast("还没有片段")
                return@setOnClickListener
            }
            editPlayer?.stop()
            preparingPreview = true
            paBtn.text = "正在准备 …"
            Thread({
                val all = buildOutputPcm()
                ui.post {
                    preparingPreview = false
                    if (all == null || all.isEmpty()) {
                        paBtn.text = "试听全部"
                        toast("还没有可用片段")
                        return@post
                    }
                    globalPlayer?.play(all, 0, 0, false)
                }
            }, "ls-amix-preview").start()
        }
        val lp1 = LinearLayout.LayoutParams(0, dp(40), 1f)
        lp1.rightMargin = dp(8)
        bar.addView(paBtn, lp1)

        val eb = TextView(ctx)
        exportBtn = eb
        eb.text = "导出并发送"
        eb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        eb.typeface = Typeface.DEFAULT_BOLD
        eb.setTextColor(AppColors.textOnPrimary())
        eb.gravity = Gravity.CENTER
        eb.background = CandyUi.gradientBg(ctx, AppColors.SHAPE_LG_DP.toFloat())
        eb.setOnClickListener { doExport() }
        bar.addView(eb, LinearLayout.LayoutParams(0, dp(40), 1.4f))
        return bar
    }

    private fun hint(text: String): TextView {
        val tv = TextView(ctx)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        tv.setTextColor(AppColors.onSurfaceVariant())
        tv.setLineSpacing(0f, 1.2f)
        return tv
    }

    private fun weight(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, -2, 1f)
        lp.leftMargin = dp(3)
        lp.rightMargin = dp(3)
        return lp
    }

    private fun outlined(text: String): TextView {
        val tv = TextView(ctx)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setTextColor(AppColors.primary())
        tv.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.setCornerRadius(dpF(AppColors.SHAPE_MD_DP.toFloat()))
        bg.setColor(AppColors.surfaceContainerLow())
        bg.setStroke(dp(1), AppColors.outlineVariant())
        tv.background = bg
        tv.setPadding(dp(8), dp(10), dp(8), dp(10))
        return tv
    }

    private fun smallButton(text: String, onClick: Runnable): TextView {
        val tv = outlined(text)
        tv.setOnClickListener { onClick.run() }
        tv.setPadding(dp(4), dp(11), dp(4), dp(11))
        return tv
    }

    private fun smallDangerButton(text: String, onClick: Runnable): TextView {
        val tv = TextView(ctx)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setTextColor(AppColors.error())
        tv.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.setCornerRadius(dpF(AppColors.SHAPE_MD_DP.toFloat()))
        bg.setColor(AppColors.errorContainer())
        tv.background = bg
        tv.setPadding(dp(4), dp(11), dp(4), dp(11))
        tv.setOnClickListener { onClick.run() }
        return tv
    }

    private fun iconButton(text: String, l: View.OnClickListener): View {
        val tv = TextView(ctx)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setTextColor(AppColors.primary())
        tv.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.setCornerRadius(dpF(AppColors.SHAPE_MD_DP.toFloat()))
        bg.setColor(AppColors.surfaceContainerHigh())
        tv.background = bg
        tv.setPadding(dp(10), dp(7), dp(10), dp(7))
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.leftMargin = dp(6)
        tv.layoutParams = lp
        tv.setOnClickListener(l)
        return tv
    }

    private fun dangerButton(text: String, l: View.OnClickListener): View {
        val tv = TextView(ctx)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setTextColor(AppColors.error())
        tv.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.setCornerRadius(dpF(AppColors.SHAPE_MD_DP.toFloat()))
        bg.setColor(AppColors.errorContainer())
        tv.background = bg
        tv.setPadding(dp(10), dp(7), dp(10), dp(7))
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.leftMargin = dp(6)
        tv.layoutParams = lp
        tv.setOnClickListener(l)
        return tv
    }

    private fun valueLabel(text: String): TextView {
        val tv = TextView(ctx)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setTextColor(AppColors.primary())
        return tv
    }

    private fun fieldRow(label: String, value: TextView): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, dp(2))
        val l = TextView(ctx)
        l.text = label
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        l.setTextColor(AppColors.onSurfaceVariant())
        l.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        row.addView(l)
        row.addView(value)
        return row
    }

    private fun borderBg(): GradientDrawable {
        val bg = GradientDrawable()
        bg.setCornerRadius(dpF(AppColors.SHAPE_XS_DP.toFloat()))
        bg.setColor(AppColors.primaryContainer())
        bg.setStroke(dp(1), AppColors.primary())
        return bg
    }

    private fun dragBg(): GradientDrawable {
        val bg = GradientDrawable()
        bg.setCornerRadius(dpF(AppColors.SHAPE_XS_DP.toFloat()))
        bg.setColor(AppColors.primaryContainer())
        bg.setStroke(dp(2), AppColors.primary())
        return bg
    }

    private fun rebuildList() {
        val lc = listCard ?: return
        lc.removeAllViews()
        if (segs.isEmpty()) {
            emptyHint?.visibility = View.VISIBLE
            return
        }
        emptyHint?.visibility = View.GONE
        for (i in segs.indices) {
            if (i > 0) lc.addView(M3Page.divider(ctx))
            lc.addView(buildRow(i))
        }
    }

    private fun buildRow(index: Int): View {
        val s = segs[index]
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), dp(10), dp(10), dp(10))
        row.background = if (selected == index) borderBg() else CandyUi.rowPressBg(ctx)

        val badge = TextView(ctx)
        badge.text = (index + 1).toString()
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        badge.typeface = Typeface.DEFAULT_BOLD
        badge.setTextColor(AppColors.textOnPrimary())
        badge.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(AppColors.primary())
        badge.background = bg
        val blp = LinearLayout.LayoutParams(dp(30), dp(30))
        blp.rightMargin = dp(10)
        row.addView(badge, blp)

        val mid = LinearLayout(ctx)
        mid.orientation = LinearLayout.VERTICAL
        mid.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)

        val name = TextView(ctx)
        name.text = s.name
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        name.typeface = Typeface.DEFAULT_BOLD
        name.setTextColor(AppColors.onSurface())
        name.setSingleLine(true)
        name.ellipsize = TextUtils.TruncateAt.MIDDLE
        mid.addView(name)

        val sub = TextView(ctx)
        val sb = StringBuilder()
        sb.append(s.typeLabel()).append(" · ").append(fmtMs(s.effDur()))
        if (s.trimmed()) sb.append(" (原 ").append(fmtMs(s.durMs)).append(")")
        if (mode == MODE_MIX) sb.append(" · 起始 ").append(fmtMs(s.offsetMs))
        if (s.trimmed()) sb.append(" · 已剪辑")
        sub.text = sb.toString()
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        sub.setTextColor(AppColors.onSurfaceVariant())
        mid.addView(sub)
        row.addView(mid)

        row.addView(iconButton("试听") {
            globalPlayer?.stop()
            if (s.effDur() <= 0) {
                toast("无可播放内容")
            } else {
                editPlayer?.play(s.pcm, s.effStart(), s.effEnd(), false)
            }
        })
        row.addView(iconButton("剪辑") {
            select(index)
            toast("在时间线上左右拖动两端手柄剪辑, 双指捏合可放大")
        })
        row.addView(dangerButton("删除") { deleteSegment(index) })

        row.setOnClickListener { select(index) }
        row.setOnLongClickListener {
            if (segs.size < 2) return@setOnLongClickListener false
            dragSeg = segs[index]
            dragIndex = index
            dragActive = true
            pendingUndo = snapshot()
            try {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            } catch (ignored: Throwable) {}
            LogWriter.log(TAG, "drag start idx=" + index)
            refreshRowHighlights()
            true
        }
        row.setOnTouchListener { _, ev ->
            if (!dragActive) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    var target = rowIndexAtRawY(ev.rawY)
                    var guard = 0
                    while (target >= 0 && target < segs.size && target != dragIndex && guard < segs.size) {
                        guard++
                        val dir = if (target > dragIndex) 1 else -1
                        swapListRows(dragIndex, dragIndex + dir)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragActive = false
                    dragSeg = null
                    dragIndex = -1
                    commitPendingUndo()
                    rebuildList()
                    rebuildTimeline()
                    true
                }
                else -> false
            }
        }
        return row
    }

    private fun swapListRows(a: Int, b: Int) {
        if (a < 0 || b < 0 || a >= segs.size || b >= segs.size || Math.abs(a - b) != 1) return
        val lo = Math.min(a, b)
        val hi = Math.max(a, b)
        val lc = listCard
        if (lc != null && lc.childCount >= hi * 2 + 1) {
            val rowLo = lc.getChildAt(lo * 2)
            val div = lc.getChildAt(lo * 2 + 1)
            val rowHi = lc.getChildAt(hi * 2)
            lc.removeViewAt(hi * 2)
            lc.removeViewAt(lo * 2)
            lc.removeViewAt(lo * 2)
            lc.addView(rowHi, lo * 2)
            lc.addView(div, lo * 2 + 1)
            lc.addView(rowLo, lo * 2 + 2)
        }
        java.util.Collections.swap(segs, a, b)
        if (selected == a) selected = b
        else if (selected == b) selected = a
        dragIndex = b
        refreshRowHighlights()
        timeline?.setData(segs, mode, selected)
        timeline?.invalidate()
    }

    private fun rowIndexAtRawY(rawY: Float): Int {
        val lc = listCard ?: return -1
        for (i in segs.indices) {
            if (i * 2 >= lc.childCount) continue
            val row = lc.getChildAt(i * 2)
            val loc = IntArray(2)
            row.getLocationOnScreen(loc)
            if (rawY >= loc[1] && rawY <= loc[1] + row.height) return i
        }
        return -1
    }

    private fun refreshRowHighlights() {
        val lc = listCard ?: return
        var i = 0
        while (i + 1 < lc.childCount) {
            val row = lc.getChildAt(i)
            val isDrag = dragActive && (i / 2 == dragIndex)
            row.background = if (isDrag) dragBg() else if (selected == i / 2) borderBg() else CandyUi.rowPressBg(ctx)
            i += 2
        }
    }

    private fun select(index: Int) {
        if (index < 0 || index >= segs.size) return
        editPlayer?.stop()
        selected = index
        rebuildList()
        rebuildTimeline()
        timeline?.centerOn(index)
        rebuildEditor()
    }

    private fun rebuildEditor() {
        val eh = editorHost ?: return
        eh.removeAllViews()
        if (segs.isEmpty()) return
        val s = if (selected >= 0 && selected < segs.size) segs[selected] else null
        if (s == null) return

        val card = M3Page.card(ctx)
        card.addView(M3Page.section(ctx, "选中片段 · 时间线剪辑",
                "点波形任意位置即从该点起播; 分割后每份都是独立选区, 点选区右上角的 × 即可删除; 拖左手柄自动从起点试听、拖右手柄自动试听终点前 3 秒; 双指捏合放大"))

        val rangeLabel = valueLabel("")
        updateRangeLabel(rangeLabel, s)
        card.addView(rangeLabel)

        val curVal = valueLabel("剪辑游标 " + fmtMs3(if (timeline == null) s.effStart() else timeline?.scrubLocal() ?: s.effStart()))
        cursorValueLabel = curVal
        card.addView(curVal)

        val pib = playIconButton()
        playIconBtn = pib
        card.addView(pib)

        val tools1 = LinearLayout(ctx)
        tools1.orientation = LinearLayout.HORIZONTAL
        tools1.setPadding(0, dp(6), 0, 0)
        tools1.addView(smallButton("循环试听", Runnable {
            if (s.effDur() <= 0) {
                toast("区间无效")
                return@Runnable
            }
            globalPlayer?.stop()
            editPlayer?.play(s.pcm, s.effStart(), s.effEnd(), true)
            updatePlayIcon()
        }), weight())
        card.addView(tools1)

        editPlayer?.setStateListener(Runnable {
            timeline?.setEditPlayhead(editPlayer?.positionMs() ?: 0, editPlayer?.isActive() == true)
            updatePlayIcon()
        })
        updatePlayIcon()
        pib.setOnClickListener {
            if (editPlayer?.isActive() == true) {
                editPlayer?.stop()
            } else {
                if (s.effDur() <= 0) {
                    toast("区间无效")
                    return@setOnClickListener
                }
                var from = if (timeline == null) s.effStart() else Math.max(s.effStart(), timeline?.scrubLocal() ?: s.effStart())
                if (s.effEnd() - from < 40) from = s.effStart()
                globalPlayer?.stop()
                editPlayer?.play(s.pcm, from, s.effEnd(), false)
            }
            updatePlayIcon()
        }

        val tools2 = LinearLayout(ctx)
        tools2.orientation = LinearLayout.HORIZONTAL
        tools2.setPadding(0, dp(6), 0, 0)

        tools2.addView(smallButton("设为起点", Runnable {
            val pre = beginEdit()
            var local = if (timeline == null) s.effStart() else timeline?.scrubLocal() ?: s.effStart()
            if (local > s.effEnd() - MIN_SEG_MS) local = s.effEnd() - MIN_SEG_MS
            if (local < 0) local = 0
            s.startMs = local
            if (editPlayer?.isActive() == true) editPlayer?.updateRegion(s.effStart(), s.effEnd())
            if (!sameAsSnapshot(pre)) recordUndo(pre)
            afterTrimEdit()
        }), weight())

        tools2.addView(smallButton("设为终点", Runnable {
            val pre = beginEdit()
            var local = if (timeline == null) s.effEnd() else timeline?.scrubLocal() ?: s.effEnd()
            if (local < s.effStart() + MIN_SEG_MS) local = s.effStart() + MIN_SEG_MS
            if (local > s.durMs) local = s.durMs
            s.endMs = local
            if (editPlayer?.isActive() == true) editPlayer?.updateRegion(s.effStart(), s.effEnd())
            if (!sameAsSnapshot(pre)) recordUndo(pre)
            afterTrimEdit()
        }), weight())

        tools2.addView(smallButton("在此分割", Runnable { splitSelected() }), weight())
        card.addView(tools2)

        val tools4 = LinearLayout(ctx)
        tools4.orientation = LinearLayout.HORIZONTAL
        tools4.setPadding(0, dp(6), 0, 0)
        tools4.addView(smallButton(if (undoStack.isEmpty()) "撤销改动" else "撤销改动 (" + undoStack.size + ")", Runnable { undoLast() }), weight())
        tools4.addView(smallDangerButton("删除此段", Runnable { deleteSegment(selected) }), weight())
        card.addView(tools4)

        if (mode == MODE_MIX) {
            card.addView(buildMixParamEditor(s))
        }

        eh.addView(card)
    }

    private fun buildMixParamEditor(s: Seg): View {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL

        val gainVal = valueLabel((s.gain * 100).toInt().toString() + " %  ·  " + gainDb(s.gain) + " dB")
        box.addView(fieldRow("音量", gainVal))
        val gainBar = M3Page.slider(ctx)
        gainBar.max = 200
        gainBar.progress = (s.gain * 100).toInt()
        gainBar.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                pendingUndo = snapshot()
            }
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                s.gain = value / 100f
                gainVal.text = value.toString() + " %  ·  " + gainDb(s.gain) + " dB"
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                commitPendingUndo()
                rebuildList()
                rebuildTimeline()
            }
        })
        box.addView(gainBar)
        box.addView(rulerView(arrayOf("0%", "50%", "100%", "150%", "200%")))
        box.addView(chipRow(arrayOf("-10%", "-5%", "-1%", "重置", "+1%", "+5%", "+10%"),
                intArrayOf(-10, -5, -1, STEP_RESET, 1, 5, 10)) { delta ->
            pendingUndo = snapshot()
            if (delta == STEP_RESET) s.gain = 1f
            else s.gain = clampGain((s.gain * 100).toInt() + delta)
            gainBar.progress = (s.gain * 100).toInt()
            gainVal.text = (s.gain * 100).toInt().toString() + " %  ·  " + gainDb(s.gain) + " dB"
            commitPendingUndo()
            rebuildList()
        })

        box.addView(M3Page.spacer(ctx, 4f))
        val offVal = valueLabel(fmtMs3(s.offsetMs))
        box.addView(fieldRow("起始时间", offVal))
        val offBar = M3Page.slider(ctx)
        val maxOff = Math.max(1000, totalMixMs() + 3000)
        offBar.max = maxOff
        offBar.progress = Math.min(s.offsetMs, maxOff)
        offBar.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                pendingUndo = snapshot()
            }
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                s.offsetMs = value
                offVal.text = fmtMs3(s.offsetMs)
                rebuildTimeline()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                commitPendingUndo()
                rebuildList()
            }
        })
        box.addView(offBar)
        box.addView(rulerView(arrayOf("0", fmtMs3(maxOff / 2), fmtMs3(maxOff))))
        box.addView(chipRow(arrayOf("-1秒", "-100毫秒", "归零", "+100毫秒", "+1秒"),
                intArrayOf(-1000, -100, STEP_RESET, 100, 1000)) { delta ->
            pendingUndo = snapshot()
            s.offsetMs = if (delta == STEP_RESET) 0 else Math.max(0, Math.min(maxOff, s.offsetMs + delta))
            offBar.progress = Math.min(s.offsetMs, maxOff)
            offVal.text = fmtMs3(s.offsetMs)
            commitPendingUndo()
            rebuildTimeline()
            rebuildList()
        })

        return box
    }

    private abstract class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) {}
    }

    private fun interface StepApply {
        fun apply(delta: Int)
    }

    private fun rulerView(labels: Array<String>): View {
        return object : View(ctx) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            init {
                minimumHeight = dp(18)
            }
            override fun onDraw(cv: Canvas) {
                val w = width
                val h = height
                p.color = AppColors.outlineVariant()
                p.strokeWidth = dpF(1.5f)
                val seg = w / (labels.size - 1).toFloat()
                for (i in labels.indices) {
                    val x = i * seg
                    val tick = if (i % 2 == 0) dp(5) else dp(3)
                    cv.drawLine(x, 0f, x, tick.toFloat(), p)
                    p.textSize = dpF(9f)
                    p.textAlign = Paint.Align.CENTER
                    p.color = AppColors.onSurfaceVariant()
                    cv.drawText(labels[i], x, (h - dp(2)).toFloat(), p)
                }
            }
        }
    }

    private fun chipRow(labels: Array<String>, deltas: IntArray, apply: StepApply): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(6), 0, 0)
        for (i in labels.indices) {
            val b = outlined(labels[i])
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            b.setPadding(dp(2), dp(8), dp(2), dp(8))
            val d = deltas[i]
            b.setOnClickListener { apply.apply(d) }
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.leftMargin = dp(2)
            lp.rightMargin = dp(2)
            row.addView(b, lp)
        }
        return row
    }

    private fun updateRangeLabel(tv: TextView, s: Seg) {
        tv.text = "起点 " + fmtMs3(s.effStart()) + "   终点 " + fmtMs3(s.effEnd()) +
                "   保留 " + fmtMs3(s.effDur())
    }

    private fun totalMixMs(): Int {
        var total = 0
        for (s in segs) total = Math.max(total, s.offsetMs + s.effDur())
        return total
    }

    private fun afterTrimEdit() {
        rebuildList()
        rebuildTimeline()
        rebuildEditor()
    }

    private fun previewHandleBoundary(s: Seg?, left: Boolean) {
        if (s == null || s.effDur() <= 0) return
        var from: Int
        var to: Int
        if (left) {
            from = s.effStart()
            to = Math.min(s.effEnd(), from + PREVIEW_TAIL_MS)
        } else {
            to = s.effEnd()
            from = Math.max(s.effStart(), to - PREVIEW_LEAD_MS)
        }
        if (to - from < 60) return
        if (globalPlayer?.isActive() == true) globalPlayer?.stop()
        if (editPlayer?.isActive() == true) {
            editPlayer?.updateRegion(from, to)
            if (Math.abs(from - lastPreviewFrom) >= 60) {
                editPlayer?.seek(from)
                lastPreviewFrom = from
            }
        } else {
            editPlayer?.play(s.pcm, from, to, false)
            lastPreviewFrom = from
        }
    }

    private fun stopHandlePreview() {
        lastPreviewFrom = Int.MIN_VALUE
        if (editPlayer?.isActive() == true) editPlayer?.stop()
    }

    private fun onScrubMoved(localMs: Int) {
        cursorValueLabel?.text = "剪辑游标 " + fmtMs3(localMs)
    }

    private fun snapshot(): MutableList<SegState> {
        val st = ArrayList<SegState>(segs.size)
        for (s in segs) st.add(SegState(s))
        return st
    }

    private fun sameAsSnapshot(st: List<SegState>?): Boolean {
        if (st == null || st.size != segs.size) return false
        for (i in st.indices) {
            val a = st[i]
            val b = segs[i]
            if (a.seg !== b) return false
            if (a.startMs != b.startMs || a.endMs != b.endMs || a.offsetMs != b.offsetMs) return false
            if (Math.abs(a.gain - b.gain) > 1e-6f) return false
        }
        return true
    }

    private fun recordUndo(pre: List<SegState>?) {
        if (pre == null) return
        undoStack.add(ArrayList(pre))
        if (undoStack.size > UNDO_MAX) undoStack.removeAt(0)
    }

    private fun beginEdit(): MutableList<SegState> {
        return snapshot()
    }

    private fun undoLast() {
        if (undoStack.isEmpty()) {
            toast("没有可撤销的改动")
            return
        }
        val st = undoStack.removeAt(undoStack.size - 1)
        editPlayer?.stop()
        segs.clear()
        for (ss in st) {
            ss.seg.startMs = ss.startMs
            ss.seg.endMs = ss.endMs
            ss.seg.offsetMs = ss.offsetMs
            ss.seg.gain = ss.gain
            segs.add(ss.seg)
        }
        if (segs.isEmpty()) selected = -1
        else if (selected >= segs.size) selected = segs.size - 1
        rebuildList()
        rebuildTimeline()
        rebuildEditor()
        toast("已撤销上一步改动")
    }

    private fun commitPendingUndo() {
        val pu = pendingUndo
        if (pu != null && !sameAsSnapshot(pu)) recordUndo(pu)
        pendingUndo = null
    }

    private fun splitSelected() {
        if (selected < 0 || selected >= segs.size) {
            toast("请先选中片段")
            return
        }
        val s = segs[selected]
        val start = s.effStart()
        val end = s.effEnd()
        if (end - start < MIN_SEG_MS * 2) {
            toast("片段太短, 无法分割")
            return
        }
        val pre = beginEdit()
        var local = if (timeline == null) (start + end) / 2 else timeline?.scrubLocal() ?: (start + end) / 2
        if (local < start + MIN_SEG_MS) local = start + MIN_SEG_MS
        if (local > end - MIN_SEG_MS) local = end - MIN_SEG_MS
        editPlayer?.stop()
        val a = cutPcmMs(s.pcm, start, local)
        val b = cutPcmMs(s.pcm, local, end)
        if (a.isEmpty() || b.isEmpty()) {
            toast("分割失败")
            return
        }
        val s1 = Seg(s.name + "·A", s.type, a)
        s1.gain = s.gain
        s1.offsetMs = s.offsetMs
        val s2 = Seg(s.name + "·B", s.type, b)
        s2.gain = s.gain
        s2.offsetMs = if (mode == MODE_MIX) s.offsetMs + (local - start) else 0
        val env = s.env
        val envPeak = s.envPeak
        if (env != null && env.isNotEmpty() && envPeak != null && envPeak.isNotEmpty()) {
            s1.env = sliceEnv(env, start, local, s.durMs)
            s1.envPeak = sliceEnv(envPeak, start, local, s.durMs)
            s2.env = sliceEnv(env, local, end, s.durMs)
            s2.envPeak = sliceEnv(envPeak, local, end, s.durMs)
        } else {
            fillEnvs(s1)
            fillEnvs(s2)
        }
        val idx = selected
        segs[idx] = s1
        segs.add(idx + 1, s2)
        selected = idx + 1
        recordUndo(pre)
        rebuildList()
        rebuildTimeline()
        rebuildEditor()
        toast("已在 " + fmtMs3(local) + " 分割为两段")
    }

    private fun deleteSegmentObject(target: Seg) {
        val index = segs.indexOf(target)
        if (index < 0) return
        deleteSegment(index)
    }

    private fun deleteSegment(index: Int) {
        if (index < 0 || index >= segs.size) return
        val pre = beginEdit()
        editPlayer?.stop()
        val who = segs[index].name
        segs.removeAt(index)
        if (segs.isEmpty()) {
            selected = -1
        } else {
            if (selected == index) selected = Math.min(index, segs.size - 1)
            else if (selected > index) selected--
            else if (selected < 0) selected = Math.min(index, segs.size - 1)
            if (selected >= segs.size) selected = segs.size - 1
            if (selected < 0) selected = 0
        }
        recordUndo(pre)
        rebuildList()
        rebuildTimeline()
        rebuildEditor()
        toast("已删除第 " + (index + 1) + " 段: " + who + " (剩 " + segs.size + " 段)")
    }

    private fun rebuildTimeline() {
        val tl = timeline ?: return
        tl.setData(segs, mode, selected)
        val h = if (mode == MODE_MIX) 170 else 130
        if (h != timelineHeightDp) {
            timelineHeightDp = h
            tl.layoutParams = LinearLayout.LayoutParams(-1, dp(h))
        }
        tl.invalidate()
    }

    private inner class TimelineView(c: Context) : View(c) {
        private var data: MutableList<Seg> = ArrayList()
        private var vMode = MODE_STITCH
        private var vSel = -1
        private var playheadMs = -1
        private var editPosMs = -1
        private var editActive = false

        private var zoom = 1f
        private var scrollMs = 0f

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        private var touchMode = NONE
        private var dragIndex = -1
        private var downHit = -1
        private var downX = 0f
        private var downY = 0f
        private var dragOrigOffset = 0
        private var dragSpanMs = 0f
        private var moved = false

        private var pinching = false
        private var pinchStartDist = 1f
        private var pinchStartZoom = 1f
        private var pinchFocusX = 0f
        private var pinchFocusMs = 0f

        private var scrubMs = -1

        private var layoutDirty = true
        private var cumStart = IntArray(0)
        private var cachedTotalMs = 1

        private var shaderW = -1
        private var shDim: Shader? = null
        private var shDimSel: Shader? = null
        private var shBright: Shader? = null
        private var shBorder: Shader? = null
        private var vgTop = -1
        private var vgBot = -1
        private var vGrad: Shader? = null
        private val cursorTri = Path()

        init {
            textPaint.typeface = Typeface.DEFAULT_BOLD
        }

        fun setData(segs: List<Seg>, mode: Int, sel: Int) {
            if (sel != vSel) scrubMs = -1
            data = ArrayList(segs)
            vMode = mode
            vSel = sel
            layoutDirty = true
            clampScroll()
            invalidate()
        }

        fun markLayoutDirty() {
            layoutDirty = true
            clampScroll()
        }

        private fun ensureLayout() {
            if (!layoutDirty) return
            layoutDirty = false
            val n = data.size
            if (cumStart.size < n + 1) cumStart = IntArray(n + 1)
            var total = 1
            if (vMode == MODE_STITCH) {
                cumStart[0] = 0
                for (i in 0 until n) {
                    cumStart[i + 1] = cumStart[i] + Math.max(1, data[i].durMs)
                }
                total = if (n > 0) cumStart[n] else 1
            } else {
                for (i in 0 until n) {
                    val st = Math.max(0, data[i].offsetMs)
                    cumStart[i] = st
                    val end = st + Math.max(1, data[i].durMs)
                    if (end > total) total = end
                }
            }
            cachedTotalMs = Math.max(1, total)
        }

        fun scrubLocal(): Int {
            if (vSel < 0 || vSel >= data.size) return 0
            val s = data[vSel]
            val d = Math.max(1, s.durMs)
            var v = if (scrubMs < 0) s.effStart() else scrubMs
            if (v < 0) v = 0
            if (v > d) v = d
            return v
        }

        fun setPlayhead(ms: Int) {
            playheadMs = ms
            invalidate()
        }

        fun setEditPlayhead(localMs: Int, active: Boolean) {
            editPosMs = localMs
            editActive = active
            if (active && localMs >= 0) {
                scrubMs = localMs
                onScrubMoved(localMs)
            }
            invalidate()
        }

        fun centerOn(i: Int) {
            if (i < 0 || i >= data.size) return
            if (width <= 0) return
            val g = blockGeom(i)
            val span = viewMsSpan()
            scrollMs = g[0].toFloat() + g[1] / 2f - span / 2f
            clampScroll()
            invalidate()
        }

        private fun padPx(): Int {
            return dp(10)
        }

        private fun totalMs(): Int {
            ensureLayout()
            return cachedTotalMs
        }

        private fun viewMsSpan(): Float {
            return Math.max(1f, totalMs() / Math.max(1f, zoom))
        }

        private fun clampScroll() {
            val maxScroll = Math.max(0f, totalMs().toFloat() - viewMsSpan())
            if (scrollMs < 0f) scrollMs = 0f
            if (scrollMs > maxScroll) scrollMs = maxScroll
        }

        private fun blockGeom(i: Int): IntArray {
            ensureLayout()
            val dur = Math.max(1, data[i].durMs)
            return if (vMode == MODE_STITCH) intArrayOf(cumStart[i], dur)
            else intArrayOf(Math.max(0, data[i].offsetMs), dur)
        }

        private fun xOf(timelineMs: Float): Float {
            val pad = padPx()
            val avail = Math.max(1, width - pad * 2)
            return pad.toFloat() + (timelineMs - scrollMs) / viewMsSpan() * avail
        }

        private fun msOf(x: Float): Float {
            val pad = padPx()
            val avail = Math.max(1, width - pad * 2)
            return scrollMs + (x - pad.toFloat()) / avail * viewMsSpan()
        }

        private fun rulerH(): Int {
            return dp(20)
        }

        private fun laneTop(lane: Int, laneH: Int, laneGap: Int): Int {
            return rulerH() + dp(5) + lane * (laneH + laneGap)
        }

        private fun laneCount(): Int {
            return if (vMode == MODE_MIX) Math.min(3, Math.max(1, data.size)) else 1
        }

        private fun laneHeight(laneGap: Int): Int {
            val h = height
            val lanes = laneCount()
            return (h - rulerH() - dp(5) - dp(6) - laneGap * (lanes - 1)) / lanes
        }

        override fun onDraw(cv: Canvas) {
            super.onDraw(cv)
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            ensureShaders(w)

            paint.style = Paint.Style.FILL
            paint.color = AppColors.timelinePanelTop()
            rect.set(0f, 0f, w.toFloat(), h.toFloat())
            cv.drawRoundRect(rect, dpF(14f), dpF(14f), paint)
            paint.shader = null

            if (data.isEmpty()) {
                textPaint.color = AppColors.timelinePinkText()
                textPaint.textSize = dpF(12f)
                textPaint.textAlign = Paint.Align.CENTER
                cv.drawText("添加片段后可在此查看轨道并剪辑", w / 2f, h / 2f, textPaint)
                return
            }

            val pad = padPx()
            val lanes = laneCount()
            val laneGap = dp(6)
            val laneH = laneHeight(laneGap)
            if (laneH <= 0) return
            val availW = w - pad * 2
            val span = viewMsSpan()
            val rh = rulerH()

            paint.style = Paint.Style.FILL
            paint.color = AppColors.timelineRulerTop()
            rect.set(0f, 0f, w.toFloat(), rh.toFloat())
            cv.drawRoundRect(rect, dpF(14f), dpF(14f), paint)
            cv.drawRect(0f, rh / 2f, w.toFloat(), rh.toFloat(), paint)
            paint.shader = null
            drawRuler(cv, pad, availW)

            drawGrid(cv, availW, rh, h)

            for (i in data.indices) {
                val s = data[i]
                val g = blockGeom(i)
                val lane = if (vMode == MODE_MIX) (i % lanes) else 0
                val top = laneTop(lane, laneH, laneGap)
                val x = xOf(g[0].toFloat())
                val rw = Math.max(dpF(2f), g[1] / span * availW)
                val inX = xOf((g[0] + s.effStart()).toFloat())
                val outX = xOf((g[0] + s.effEnd()).toFloat())
                drawBlock(cv, s, x, rw, top, laneH, inX, outX, i == vSel)
                if (rw > dpF(44f)) {
                    textPaint.color = AppColors.timelinePinkSoft()
                    textPaint.textSize = dpF(9f)
                    textPaint.textAlign = Paint.Align.LEFT
                    cv.drawText(s.name, x + dpF(5f), (top + dp(10)).toFloat(), textPaint)
                }
            }

            if (playheadMs >= 0) {
                drawVLine(cv, xOf(playheadMs.toFloat()), rh, h - dp(2), AppColors.timelinePink())
            }
            if (vSel >= 0 && vSel < data.size) {
                val g = blockGeom(vSel)
                val cx = xOf((g[0] + scrubLocal()).toFloat())
                drawVLine(cv, cx, rh, h - dp(2), if (editActive) AppColors.timelineAmber() else AppColors.timelinePink())
                paint.style = Paint.Style.FILL
                paint.shader = null
                paint.color = if (editActive) AppColors.timelineAmber() else AppColors.timelinePink()
                cursorTri.reset()
                cursorTri.moveTo(cx - dpF(5f), (rh + dp(1)).toFloat())
                cursorTri.lineTo(cx + dpF(5f), (rh + dp(1)).toFloat())
                cursorTri.lineTo(cx, (rh + dp(7)).toFloat())
                cursorTri.close()
                cv.drawPath(cursorTri, paint)
            }
        }

        private fun majorInterval(): Float {
            val span = viewMsSpan()
            val availW = Math.max(1, width - padPx() * 2).toFloat()
            val pxPerMs = availW / span
            val steps = floatArrayOf(100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f, 30000f, 60000f)
            for (st in steps) if (st * pxPerMs >= dpF(52f)) return st
            return steps[steps.size - 1]
        }

        private fun drawRuler(cv: Canvas, pad: Int, availW: Int) {
            val span = viewMsSpan()
            val interval = majorInterval()
            var first = (Math.floor((scrollMs / interval).toDouble()) * interval.toDouble()).toLong()
            textPaint.textSize = dpF(9f)
            textPaint.color = AppColors.timelinePinkText()
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = AppColors.timelinePinkText()
            var t = first.toFloat()
            while (t <= scrollMs + span + 1) {
                val x = xOf(t)
                if (x < pad.toFloat() - dpF(2f) || x > (width - pad).toFloat() + dpF(2f)) {
                    t += interval
                    continue
                }
                cv.drawRect(x, dpF(3f), x + dpF(1.5f), dpF((rulerH() - dp(1)).toFloat()), paint)
                textPaint.textAlign = Paint.Align.LEFT
                cv.drawText(fmtMs(t.toInt()), x + dpF(4f), dpF(12f), textPaint)
                t += interval
            }
            if (zoom > 1.01f) {
                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.color = AppColors.timelinePinkPale()
                cv.drawText(String.format(Locale.US, "%.1fx", zoom), (width - pad).toFloat(), dpF(12f), textPaint)
            }
        }

        private fun drawGrid(cv: Canvas, availW: Int, top: Int, bottom: Int) {
            val span = viewMsSpan()
            val major = majorInterval()
            val minor = major / 4f
            var firstMin = (Math.floor((scrollMs / minor).toDouble()) * minor.toDouble()).toLong()
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = AppColors.timelineGridMinor()
            var t = firstMin.toFloat()
            while (t <= scrollMs + span + 1) {
                val x = xOf(t)
                if (x < 0f || x > width.toFloat()) {
                    t += minor
                    continue
                }
                cv.drawRect(x, top.toFloat(), x + dpF(1f), bottom.toFloat(), paint)
                t += minor
            }
            var firstMaj = (Math.floor((scrollMs / major).toDouble()) * major.toDouble()).toLong()
            paint.color = AppColors.timelineGridMajor()
            t = firstMaj.toFloat()
            while (t <= scrollMs + span + 1) {
                val x = xOf(t)
                if (x < 0f || x > width.toFloat()) {
                    t += major
                    continue
                }
                cv.drawRect(x, top.toFloat(), x + dpF(1.5f), bottom.toFloat(), paint)
                t += major
            }
        }

        private fun grad(alpha: Int): IntArray {
            return intArrayOf(
                    withAlpha(AppColors.gradientStart(), alpha),
                    withAlpha(AppColors.gradientMid(), alpha),
                    withAlpha(AppColors.gradientEnd(), alpha)
            )
        }

        private fun gradShader(w: Int, alpha: Int): Shader {
            return LinearGradient(0f, 0f, Math.max(1, w).toFloat(), 0f, grad(alpha),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }

        private fun ensureShaders(w: Int) {
            if (w == shaderW && shDim != null) return
            shaderW = w
            shDim = gradShader(w, 65)
            shDimSel = gradShader(w, 80)
            shBright = gradShader(w, 255)
            shBorder = gradShader(w, 230)
            vgTop = -1
            vgBot = -1
            vGrad = null
        }

        private fun vGrad(top: Int, bottom: Int): Shader {
            if (vGrad == null || vgTop != top || vgBot != bottom) {
                vgTop = top
                vgBot = bottom
                vGrad = LinearGradient(0f, top.toFloat(), 0f, bottom.toFloat(),
                        AppColors.gradientStart(), AppColors.gradientEnd(), Shader.TileMode.CLAMP)
            }
            return vGrad!!
        }

        private fun drawBlock(cv: Canvas, s: Seg, x: Float, rw: Float, top: Int, laneH: Int,
                              inX: Float, outX: Float, sel: Boolean) {
            val midY = top.toFloat() + laneH / 2f
            val maxH = Math.max(dp(3), laneH - dp(8)).toFloat()

            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = AppColors.timelineBlockBase()
            rect.set(x, top.toFloat(), x + rw, (top + laneH).toFloat())
            cv.drawRoundRect(rect, dpF(6f), dpF(6f), paint)

            val epp = s.envPeak
            val ev = s.env
            val pk: FloatArray? = if (epp != null && epp.isNotEmpty()) epp else ev
            val rms: FloatArray? = if (ev != null && ev.isNotEmpty()) ev else epp
            val dim = if (sel) shDimSel else shDim
            waveLayer(cv, pk, x, rw, midY, maxH, 1.0f, 95, dim)
            waveLayer(cv, rms, x, rw, midY, maxH, 0.62f, 150, dim)

            if (sel) {
                paint.style = Paint.Style.FILL
                paint.shader = null
                paint.color = AppColors.timelineDimOverlay()
                if (inX > x) cv.drawRect(x, top.toFloat(), Math.min(inX, x + rw), (top + laneH).toFloat(), paint)
                if (outX < x + rw) cv.drawRect(Math.max(outX, x), top.toFloat(), x + rw, (top + laneH).toFloat(), paint)

                cv.save()
                cv.clipRect(Math.max(x, inX), top.toFloat(), Math.min(x + rw, outX), (top + laneH).toFloat())
                waveLayer(cv, pk, x, rw, midY, maxH, 1.0f, 160, shBright)
                waveLayer(cv, rms, x, rw, midY, maxH, 0.62f, 255, shBright)
                cv.restore()

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dpF(1.5f)
                paint.shader = shBorder
                rect.set(x, top.toFloat(), x + rw, (top + laneH).toFloat())
                cv.drawRoundRect(rect, dpF(6f), dpF(6f), paint)
                paint.shader = null

                drawSelLine(cv, inX, top, top + laneH)
                drawSelLine(cv, outX, top, top + laneH)
                drawHandle(cv, inX, top, top + laneH)
                drawHandle(cv, outX, top, top + laneH)
            } else {
                paint.style = Paint.Style.FILL
                paint.shader = null
                paint.color = AppColors.timelineDimPlain()
                rect.set(x, top.toFloat(), x + rw, (top + laneH).toFloat())
                cv.drawRoundRect(rect, dpF(6f), dpF(6f), paint)
            }

            if (rw > dpF(40f)) {
                val cx = x + rw - dpF(12f)
                val cy = (top + dp(12)).toFloat()
                paint.shader = null
                paint.style = Paint.Style.FILL
                paint.color = if (sel) AppColors.timelineDeleteBadge() else AppColors.timelineBadgeDark()
                cv.drawCircle(cx, cy, dpF(9f), paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dpF(1.6f)
                paint.color = AppColors.timelineWhite()
                cv.drawLine(cx - dpF(3.6f), cy - dpF(3.6f), cx + dpF(3.6f), cy + dpF(3.6f), paint)
                cv.drawLine(cx + dpF(3.6f), cy - dpF(3.6f), cx - dpF(3.6f), cy + dpF(3.6f), paint)
                paint.style = Paint.Style.FILL
            }
        }

        private fun waveLayer(cv: Canvas, env: FloatArray?, x: Float, rw: Float, midY: Float,
                              maxH: Float, k: Float, alpha: Int, shader: Shader?) {
            if (rw <= 0) return
            val has = env != null && env.isNotEmpty()
            val cols = Math.max(1, (rw / dpF(2f)).toInt())
            val c2 = if (cols > 400) 400 else cols
            val colW = rw / c2
            paint.style = Paint.Style.FILL
            paint.shader = shader
            paint.alpha = alpha
            for (c in 0 until c2) {
                var v: Float
                if (has) {
                    var src = (c.toLong() * env!!.size / c2).toInt()
                    if (src >= env!!.size) src = env!!.size - 1
                    v = env!![src]
                    if (v < 0.04f) v = 0.04f
                    if (v > 1f) v = 1f
                } else {
                    v = 0.5f
                }
                val bh = Math.max(dp(1).toFloat(), v * maxH * k)
                val cx = x + c * colW
                cv.drawRect(cx, midY - bh / 2f, cx + Math.max(dp(1).toFloat(), colW * 0.68f), midY + bh / 2f, paint)
            }
            paint.alpha = 255
            paint.shader = null
        }

        private fun drawSelLine(cv: Canvas, hx: Float, top: Int, bottom: Int) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dpF(1.4f)
            paint.shader = vGrad(top, bottom)
            cv.drawLine(hx, top.toFloat(), hx, bottom.toFloat(), paint)
            paint.shader = null
        }

        private fun drawHandle(cv: Canvas, hx: Float, top: Int, bottom: Int) {
            val bw = dpF(5f)
            paint.style = Paint.Style.FILL
            paint.shader = vGrad(top, bottom)
            rect.set(hx - bw / 2f, top.toFloat(), hx + bw / 2f, bottom.toFloat())
            cv.drawRoundRect(rect, bw / 2f, bw / 2f, paint)
            cv.drawCircle(hx, (top + dp(7)).toFloat(), dpF(6f), paint)
            cv.drawCircle(hx, (bottom - dp(7)).toFloat(), dpF(6f), paint)
            paint.shader = null
            paint.color = AppColors.timelineWhite()
            cv.drawCircle(hx, (top + dp(7)).toFloat(), dpF(2f), paint)
            cv.drawCircle(hx, (bottom - dp(7)).toFloat(), dpF(2f), paint)
        }

        private fun drawVLine(cv: Canvas, x: Float, top: Int, bottom: Int, color: Int) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dpF(2f)
            paint.shader = null
            paint.color = color
            cv.drawLine(x, top.toFloat(), x, bottom.toFloat(), paint)
        }

        private fun hitIndex(x: Float, y: Float): Int {
            val pad = padPx()
            val laneGap = dp(6)
            val laneH = laneHeight(laneGap)
            if (laneH <= 0) return -1
            val lanes = laneCount()
            val span = viewMsSpan()
            val availW = width - pad * 2
            for (i in data.indices) {
                val g = blockGeom(i)
                val lane = if (vMode == MODE_MIX) (i % lanes) else 0
                val top = laneTop(lane, laneH, laneGap)
                val bx = xOf(g[0].toFloat())
                val rw = Math.max(dpF(2f), g[1] / span * availW)
                if (x >= bx && x <= bx + rw && y >= top.toFloat() && y <= (top + laneH).toFloat()) return i
            }
            return -1
        }

        private fun decideMode(i: Int, x: Float): Int {
            if (i == vSel) {
                val g = blockGeom(i)
                val s = data[i]
                val span = viewMsSpan()
                val rw = g[1] / span * (width - padPx() * 2)
                if (rw > dpF(56f)) {
                    val inX = xOf((g[0] + s.effStart()).toFloat())
                    val outX = xOf((g[0] + s.effEnd()).toFloat())
                    val th = dpF(24f)
                    if (Math.abs(x - inX) <= th) return TRIM_L
                    if (Math.abs(x - outX) <= th) return TRIM_R
                }
                return SCRUB
            }
            return BODY
        }

        fun setScrub(index: Int, localMs: Int) {
            if (index != vSel || index < 0 || index >= data.size) return
            val s = data[index]
            val v = Math.max(0, Math.min(s.durMs, localMs))
            scrubMs = v
            if (editPlayer?.isActive() == true) editPlayer?.seek(v)
            onScrubMoved(v)
            invalidate()
        }

        private fun onTimelineTapSegment(index: Int, rawX: Float) {
            if (index < 0 || index >= data.size) return
            val s = data[index]
            val g = blockGeom(index)
            var local = (msOf(rawX) - g[0].toFloat()).toInt()
            local = Math.max(0, Math.min(s.durMs, local))
            var from = Math.max(s.effStart(), Math.min(s.effEnd(), local))
            val switched = selected != index
            if (switched) select(index)
            setScrub(index, from)
            if (s.effEnd() - from < 40) from = s.effStart()
            if (s.effEnd() > from) {
                globalPlayer?.stop()
                editPlayer?.play(s.pcm, from, s.effEnd(), false)
            }
        }

        private fun hitDeleteBadge(x: Float, y: Float, i: Int): Boolean {
            if (i < 0 || i >= data.size) return false
            val g = blockGeom(i)
            val pad = padPx()
            val avail = Math.max(1, width - pad * 2)
            val rw = Math.max(dpF(2f), g[1] / viewMsSpan() * avail)
            if (rw < dpF(40f)) return false
            val lane = if (vMode == MODE_MIX) (i % laneCount()) else 0
            val laneH = laneHeight(dp(6))
            val top = laneTop(lane, laneH, dp(6))
            val bx = xOf(g[0].toFloat())
            val cx = bx + rw - dpF(12f)
            val cy = (top + dp(12)).toFloat()
            val dx = x - cx
            val dy = y - cy
            return dx * dx + dy * dy <= dpF(16f) * dpF(16f)
        }

        private fun applyTrim(i: Int, mode: Int, x: Float) {
            val s = data[i]
            val g = blockGeom(i)
            var local = (msOf(x) - g[0].toFloat()).toInt()
            if (mode == TRIM_L) {
                var end = s.effEnd()
                if (local > end - 80) local = end - 80
                if (local < 0) local = 0
                s.startMs = local
            } else {
                var start = s.effStart()
                if (local < start + 80) local = start + 80
                if (local > s.durMs) local = s.durMs
                s.endMs = local
            }
            previewHandleBoundary(s, mode == TRIM_L)
            invalidate()
        }

        private fun dist(e: MotionEvent): Float {
            if (e.pointerCount < 2) return 1f
            val dx = e.getX(0) - e.getX(1)
            val dy = e.getY(0) - e.getY(1)
            return Math.max(1f, Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat())
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (data.isEmpty()) return true
            return when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    pinching = false
                    moved = false
                    pendingUndo = snapshot()
                    downX = e.x
                    downY = e.y
                    dragSpanMs = viewMsSpan()
                    val hit = hitIndex(e.x, e.y)
                    dragIndex = hit
                    downHit = hit
                    if (hit >= 0) {
                        dragOrigOffset = data[hit].offsetMs
                        touchMode = decideMode(hit, e.x)
                        if (touchMode == SCRUB) {
                            touchMode = if (zoom > 1.01f) PAN else BODY
                        } else if (touchMode == BODY && zoom > 1.01f) {
                            touchMode = PAN
                        }
                        if (touchMode == TRIM_L || touchMode == TRIM_R) {
                            previewHandleBoundary(data[hit], touchMode == TRIM_L)
                        }
                    } else {
                        touchMode = if (zoom > 1.01f) PAN else NONE
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.pointerCount >= 2) {
                        pinching = true
                        touchMode = NONE
                        dragIndex = -1
                        pinchStartDist = dist(e)
                        pinchStartZoom = zoom
                        pinchFocusX = (e.getX(0) + e.getX(1)) / 2f
                        pinchFocusMs = msOf(pinchFocusX)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pinching && e.pointerCount >= 2) {
                        val f = dist(e) / pinchStartDist
                        zoom = Math.max(1f, Math.min(MAX_ZOOM, pinchStartZoom * f))
                        val pad = padPx()
                        val avail = Math.max(1, width - pad * 2)
                        scrollMs = pinchFocusMs - (pinchFocusX - pad.toFloat()) / avail * viewMsSpan()
                        clampScroll()
                        invalidate()
                        return true
                    }
                    if (Math.abs(e.x - downX) > dpF(4f) || Math.abs(e.y - downY) > dpF(4f)) {
                        moved = true
                    }
                    if (touchMode == PAN) {
                        val dx = e.x - downX
                        if (Math.abs(dx) > dpF(2f)) moved = true
                        val pad = padPx()
                        val avail = Math.max(1, width - pad * 2)
                        scrollMs -= dx / avail * viewMsSpan()
                        downX = e.x
                        clampScroll()
                        invalidate()
                        return true
                    }
                    if (dragIndex < 0) return true
                    if (Math.abs(e.x - downX) > dpF(4f)) moved = true
                    if (!moved) return true

                    if (touchMode == TRIM_L || touchMode == TRIM_R) {
                        applyTrim(dragIndex, touchMode, e.x)
                    } else if (touchMode == BODY) {
                        if (vMode == MODE_MIX) {
                            val pad = padPx()
                            val avail = Math.max(1, width - pad * 2)
                            val deltaMs = (e.x - downX) / avail * dragSpanMs
                            val nv = Math.max(0, dragOrigOffset + deltaMs.toInt())
                            data[dragIndex].offsetMs = nv
                            selected = dragIndex
                            markLayoutDirty()
                            invalidate()
                        } else {
                            val target = orderIndexAt(e.x)
                            if (target >= 0 && target != dragIndex && target < segs.size) {
                                val mv = segs.removeAt(dragIndex)
                                segs.add(target, mv)
                                dragIndex = target
                                selected = target
                                data = ArrayList(segs)
                                markLayoutDirty()
                                invalidate()
                                rebuildList()
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (e.pointerCount <= 2) pinching = false
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (touchMode == TRIM_L || touchMode == TRIM_R) stopHandlePreview()
                    if (!moved && downHit >= 0 && !pinching) {
                        if (hitDeleteBadge(e.x, e.y, downHit)) {
                            pendingUndo = null
                            deleteSegmentObject(data[downHit])
                        } else {
                            onTimelineTapSegment(downHit, e.x)
                        }
                    } else if (moved && dragIndex >= 0 && !pinching) {
                        if (touchMode == TRIM_L || touchMode == TRIM_R) {
                            rebuildList()
                            rebuildTimeline()
                            rebuildEditor()
                        } else if (touchMode == BODY) {
                            rebuildList()
                            rebuildTimeline()
                        }
                    }
                    commitPendingUndo()
                    dragIndex = -1
                    downHit = -1
                    touchMode = NONE
                    pinching = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> true
            }
        }

        private fun orderIndexAt(x: Float): Int {
            val pad = padPx()
            val span = viewMsSpan()
            val availW = Math.max(1, width - pad * 2)
            var cursor = 0
            for (i in segs.indices) {
                val dur = Math.max(1, segs[i].durMs)
                val bx = xOf(cursor.toFloat())
                val rw = Math.max(dpF(2f), dur / span * availW)
                if (x < bx + rw / 2f) return i
                cursor += dur
            }
            return segs.size - 1
        }
    }

    private fun barColor(i: Int): Int {
        val pal = intArrayOf(AppColors.gradientStart(), AppColors.gradientEnd(),
                AppColors.timelineBarAlt1(), AppColors.timelineBarAlt2())
        return pal[i % pal.size]
    }

    private inner class Player(
        private val onTick: Runnable,
        private var onFinished: Runnable?
    ) {
        private val lock = Object()
        @Volatile private var track: AudioTrack? = null
        private var thread: Thread? = null
        @Volatile private var threadStarted = false
        @Volatile private var released = false

        @Volatile private var running = false
        @Volatile private var paused = false
        @Volatile private var posMs = 0
        @Volatile private var seekToMs = -1
        @Volatile private var data: ByteArray? = null
        @Volatile private var regionStartMs = 0
        @Volatile private var regionEndMs = 0
        @Volatile private var loop = false
        @Volatile private var gen = 0L

        private var stateListener: Runnable? = null

        fun setStateListener(r: Runnable) {
            stateListener = r
        }

        fun isActive(): Boolean {
            return running
        }

        fun positionMs(): Int {
            return posMs
        }

        private fun ensureThread() {
            if (threadStarted || released) return
            threadStarted = true
            val th = Thread(Runnable { loop() }, "ls-amix-play")
            thread = th
            th.start()
        }

        fun play(pcm: ByteArray?, fromMs: Int, toMs: Int, loop: Boolean) {
            if (released) return
            if (pcm == null || pcm.size < 2) {
                stop()
                return
            }
            val dur = TtsVoiceSender.pcmDurationMs(pcm)
            synchronized(lock) {
                data = pcm
                this.loop = loop
                regionStartMs = Math.max(0, Math.min(fromMs, dur))
                var to = if (toMs <= 0) dur else Math.min(toMs, dur)
                if (to <= regionStartMs) to = dur
                regionEndMs = to
                posMs = regionStartMs
                seekToMs = -1
                paused = false
                gen++
                running = true
                lock.notifyAll()
            }
            ensureThread()
            dispatchState()
        }

        fun updateRegion(startMs: Int, endMs: Int) {
            regionStartMs = Math.max(0, startMs)
            regionEndMs = Math.max(regionStartMs + 1, endMs)
        }

        fun seek(ms: Int) {
            posMs = Math.max(0, ms)
            seekToMs = posMs
            ui.post { timeline?.invalidate() }
        }

        fun pause() {
            paused = true
            setTrackPlaying(false)
            dispatchState()
        }

        fun resume() {
            if (!running) return
            paused = false
            setTrackPlaying(true)
            dispatchState()
        }

        fun stop() {
            synchronized(lock) {
                running = false
                paused = false
                seekToMs = -1
                gen++
                lock.notifyAll()
            }
            val t = track
            if (t != null) {
                try { t.pause() } catch (ignored: Throwable) {}
                try { t.flush() } catch (ignored: Throwable) {}
            }
            dispatchState()
        }

        fun release() {
            stop()
            released = true
            val t = track
            track = null
            if (t != null) {
                try { t.stop() } catch (ignored: Throwable) {}
                try { t.release() } catch (ignored: Throwable) {}
            }
            val th = thread
            th?.interrupt()
        }

        private fun setTrackPlaying(play: Boolean) {
            val t = track
            if (t == null) return
            try {
                if (play) t.play() else t.pause()
            } catch (ignored: Throwable) {}
        }

        private fun dispatchState() {
            ui.post {
                stateListener?.run()
                onTick.run()
            }
        }

        private fun dispatchFinished() {
            ui.post { onFinished?.run() }
        }

        private fun ensureTrack(): Boolean {
            val t = track
            if (t != null && t.state == AudioTrack.STATE_INITIALIZED) return true
            if (t != null) {
                try { t.release() } catch (ignored: Throwable) {}
                track = null
            }
            try {
                var minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) minBuf = SAMPLE_RATE
                val bufBytes = Math.max(minBuf, SAMPLE_RATE * 2 * 120 / 1000)
                val nt = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(bufBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                if (nt.state != AudioTrack.STATE_INITIALIZED) {
                    try { nt.release() } catch (ignored: Throwable) {}
                    return false
                }
                track = nt
                return true
            } catch (ex: Throwable) {
                LogWriter.log(TAG, "audiotrack init err: " + ex)
                return false
            }
        }

        private fun loop() {
            val chunk = 2304
            val buf = ByteArray(chunk)
            while (!released) {
                try {
                    val myGen: Long
                    synchronized(lock) {
                        while (!running && !released) {
                            try {
                                lock.wait()
                            } catch (e: InterruptedException) {
                                return
                            }
                        }
                        if (released) return
                        myGen = gen
                    }
                    if (!ensureTrack()) {
                        val mine: Boolean
                        synchronized(lock) {
                            mine = gen == myGen
                            if (mine) {
                                running = false
                                paused = false
                            }
                        }
                        if (mine) {
                            dispatchState()
                            dispatchFinished()
                        }
                        continue
                    }
                    val t = track
                    val d = data
                    if (d == null || t == null) {
                        Thread.sleep(20)
                        continue
                    }
                    val startB = regionStartMs * BYTES_PER_MS
                    var off = startB
                    var sk = seekToMs
                    if (sk >= 0) {
                        off = Math.max(0, sk) * BYTES_PER_MS
                        seekToMs = -1
                    }
                    var baseMs = off / BYTES_PER_MS
                    posMs = baseMs
                    var writtenFrames = 0

                    try { t.pause() } catch (ignored: Throwable) {}
                    try { t.flush() } catch (ignored: Throwable) {}
                    try { t.play() } catch (ignored: Throwable) {}

                    var naturalEnd = false
                    var superseded = false
                    var endB = Math.min(regionEndMs * BYTES_PER_MS, d.size)
                    if (endB <= startB) endB = d.size

                    while (true) {
                        if (released) return
                        if (gen != myGen || !running) {
                            superseded = true
                            break
                        }
                        if (seekToMs >= 0) {
                            off = Math.max(0, seekToMs) * BYTES_PER_MS
                            seekToMs = -1
                            baseMs = off / BYTES_PER_MS
                            posMs = baseMs
                            writtenFrames = 0
                            try { t.pause(); t.flush(); t.play() } catch (ignored: Throwable) {}
                            continue
                        }
                        if (paused) {
                            try { Thread.sleep(12) } catch (e: InterruptedException) {
                                return
                            }
                            continue
                        }
                        endB = Math.min(regionEndMs * BYTES_PER_MS, d.size)
                        if (endB <= startB) endB = d.size
                        if (off < startB || off > endB) off = startB
                        if (off >= endB) {
                            drain(t, myGen, writtenFrames, baseMs)
                            if (gen != myGen || !running || released) {
                                superseded = true
                                break
                            }
                            if (loop) {
                                off = startB
                                baseMs = off / BYTES_PER_MS
                                posMs = baseMs
                                writtenFrames = 0
                                try { t.pause(); t.flush(); t.play() } catch (ignored: Throwable) {}
                                continue
                            }
                            naturalEnd = true
                            break
                        }
                        val n = Math.min(buf.size, endB - off)
                        if (n <= 0) {
                            naturalEnd = true
                            break
                        }
                        System.arraycopy(d, off, buf, 0, n)
                        val written = t.write(buf, 0, n)
                        if (written <= 0) {
                            naturalEnd = true
                            break
                        }
                        off += written
                        writtenFrames += written / 2
                        posMs = headPosMs(t, baseMs)
                        ui.post(onTick)
                    }

                    val endedMine: Boolean
                    synchronized(lock) {
                        endedMine = gen == myGen
                        if (endedMine) {
                            running = false
                            paused = false
                        }
                    }
                    if (superseded || !endedMine) continue
                    try { t.pause() } catch (ignored: Throwable) {}
                    try { t.flush() } catch (ignored: Throwable) {}
                    dispatchState()
                    if (naturalEnd) dispatchFinished()
                } catch (ex: Throwable) {
                    LogWriter.log(TAG, "player loop err: " + ex)
                    synchronized(lock) {
                        running = false
                        paused = false
                    }
                    try {
                        Thread.sleep(20)
                    } catch (e: InterruptedException) {
                        return
                    }
                }
            }
        }

        private fun headPosMs(t: AudioTrack, baseMs: Int): Int {
            var head = 0
            try {
                head = t.playbackHeadPosition
            } catch (ignored: Throwable) {}
            var ms = baseMs + (head.toLong() * 1000L / SAMPLE_RATE).toInt()
            if (ms < baseMs) ms = baseMs
            val cap = regionEndMs
            return if (ms > cap) cap else ms
        }

        private fun drain(t: AudioTrack, myGen: Long, writtenFrames: Int, baseMs: Int) {
            val deadline = System.currentTimeMillis() + 1500
            while (gen == myGen && running && !released) {
                var head = 0
                try {
                    head = t.playbackHeadPosition
                } catch (ignored: Throwable) {}
                var ms = baseMs + (head.toLong() * 1000L / SAMPLE_RATE).toInt()
                posMs = if (ms > regionEndMs) regionEndMs else ms
                ui.post(onTick)
                if (head >= writtenFrames) break
                if (System.currentTimeMillis() > deadline) break
                try {
                    Thread.sleep(12)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun onGlobalTick() {
        timeline?.setPlayhead(if (globalPlayer?.isActive() == true) globalPlayer?.positionMs() ?: -1 else -1)
        playAllBtn?.text = if (globalPlayer?.isActive() == true) "停止试听" else "试听全部"
    }

    private fun onGlobalFinished() {
        timeline?.setPlayhead(-1)
        playAllBtn?.text = "试听全部"
    }

    private fun onEditTick() {
        timeline?.setEditPlayhead(editPlayer?.positionMs() ?: 0, editPlayer?.isActive() == true)
        updatePlayIcon()
    }

    private fun onEditFinished() {
        timeline?.setEditPlayhead(0, false)
        updatePlayIcon()
    }

    private fun playIconButton(): TextView {
        val tv = TextView(ctx)
        tv.text = "▶"
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        tv.setTextColor(AppColors.whiteTextOnAccent())
        tv.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(AppColors.primary())
        tv.background = bg
        val sz = dp(44)
        val lp = LinearLayout.LayoutParams(sz, sz)
        lp.gravity = Gravity.CENTER_HORIZONTAL
        lp.topMargin = dp(6)
        lp.bottomMargin = dp(2)
        tv.layoutParams = lp
        return tv
    }

    private fun updatePlayIcon() {
        val btn = playIconBtn ?: return
        btn.text = if (editPlayer != null && editPlayer?.isActive() == true) "■" else "▶"
    }

    private fun onAddFile() {
        val act = activityOf()
        if (act == null) {
            toast("无法打开文件选择器")
            return
        }
        ChatFooterLongPressMenu.startAudioPick(act) { path ->
            val p: String = path ?: return@startAudioPick
            if (p.isEmpty()) return@startAudioPick
            val nm = java.io.File(p).name
            showBusy("正在解码 " + nm + " …")
            Thread(Runnable {
                val pcm = TtsVoiceSender.decodeAudioToPcm(p) { cur, _ ->
                    ui.post { setBusyText("正在解码 " + nm + " … " + cur + "%") }
                }
                if (pcm == null || pcm.isEmpty()) {
                    ui.post {
                        hideBusy()
                        toast("解码失败, 请换一个音频文件")
                    }
                    return@Runnable
                }
                ui.post { setBusyText("正在分析波形 …") }
                val s = Seg(nm, TYPE_FILE, pcm)
                fillEnvs(s)
                ui.post {
                    hideBusy()
                    segs.add(s)
                    rebuildList()
                    rebuildTimeline()
                    select(segs.size - 1)
                    toast("已添加: " + nm)
                }
            }, "ls-amix-decode").start()
        }
    }

    private fun onAddTts() {
        val et = M3Page.input(ctx, "输入要合成的文字")
        et.setSingleLine(false)
        et.maxLines = 4
        et.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val p = dp(16)
        box.setPadding(p, dp(8), p, dp(4))
        box.addView(et, LinearLayout.LayoutParams(-1, -2))

        val voiceHint = TextView(ctx)
        voiceHint.text = "音色: 加载中…"
        voiceHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        voiceHint.setTextColor(AppColors.onSurfaceVariant())
        box.addView(voiceHint)
        val sel = arrayOf<String?>(lastTtsVoice)
        val voicesLoaded = booleanArrayOf(false)
        Thread({
            val names = TtsVoiceSender.ttsVoiceNames()
            val def = TtsVoiceSender.ttsDefaultVoiceName()
            ui.post {
                try {
                    voicesLoaded[0] = true
                    if (names == null || names.isEmpty()) {
                        voiceHint.text = "音色: 系统默认（引擎无可选音色）"
                        return@post
                    }
                    if (sel[0] == null) sel[0] = def
                    val chips = LinearLayout(ctx)
                    chips.orientation = LinearLayout.HORIZONTAL
                    chips.setPadding(0, dp(6), 0, 0)
                    val chipViews = ArrayList<TextView>()
                    for (i in names.indices) {
                        val nm = names[i]
                        val chip = TextView(ctx)
                        chip.text = shortVoiceName(nm)
                        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        chip.typeface = Typeface.DEFAULT_BOLD
                        chip.gravity = Gravity.CENTER
                        chip.setPadding(dp(8), dp(7), dp(8), dp(7))
                        val fi = i
                        chip.setOnClickListener {
                            sel[0] = nm
                            for (k in chipViews.indices) {
                                val c = chipViews[k]
                                val on = k == fi
                                c.setTextColor(if (on) AppColors.textOnPrimary() else AppColors.primary())
                                val bg = GradientDrawable()
                                bg.setCornerRadius(dpF(AppColors.SHAPE_MD_DP.toFloat()))
                                bg.setColor(if (on) AppColors.primary() else AppColors.surfaceContainerHigh())
                                c.background = bg
                            }
                        }
                        val lp = LinearLayout.LayoutParams(-2, -2)
                        lp.rightMargin = dp(6)
                        chip.layoutParams = lp
                        chipViews.add(chip)
                        chips.addView(chip)
                    }
                    val idx = box.indexOfChild(voiceHint)
                    box.removeView(voiceHint)
                    box.addView(chips, idx)
                    var dk = names.indexOf(sel[0])
                    if (dk < 0) dk = 0
                    chipViews[dk].performClick()
                    voiceHint.text = "音色: 点击选择"
                    voiceHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    voiceHint.setTextColor(AppColors.onSurfaceVariant())
                    box.addView(voiceHint, idx + 1)
                } catch (t: Throwable) {
                    LogWriter.log(TAG, "tts voice picker err: " + t)
                }
            }
        }, "ls-amix-voice").start()

        AlertDialog.Builder(ctx)
                .setTitle("文字转语音")
                .setView(box)
                .setPositiveButton("生成并加入") { _, _ ->
                    val text = et.text?.toString()?.trim() ?: ""
                    if (text.isEmpty()) {
                        toast("请输入文字")
                        return@setPositiveButton
                    }
                    val voice = sel[0]
                    if (voice != null) lastTtsVoice = voice
                    showBusy("正在合成语音 …")
                    Thread(Runnable {
                        val pcm = TtsVoiceSender.synthesizeTextToPcm(text, voice)
                        if (pcm == null || pcm.isEmpty()) {
                            ui.post {
                                hideBusy()
                                toast("合成失败, 请重试")
                            }
                            return@Runnable
                        }
                        val n = if (text.length > 12) text.substring(0, 12) + "…" else text
                        val s = Seg(n, TYPE_TTS, pcm)
                        fillEnvs(s)
                        ui.post {
                            hideBusy()
                            segs.add(s)
                            rebuildList()
                            rebuildTimeline()
                            select(segs.size - 1)
                            toast("已加入文字转语音")
                        }
                    }, "ls-amix-tts").start()
                }
                .setNegativeButton("取消", null)
                .show()
    }

    private fun onAddRec() {
        if (recording) {
            stopRecording(true)
            return
        }
        try {
            var minBuf = AudioRecord.getMinBufferSize(REC_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) minBuf = REC_SAMPLE_RATE
            recorder = AudioRecord(MediaRecorder.AudioSource.MIC, REC_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
            val r = recorder
            if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
                toast("麦克风不可用")
                r?.release()
                recorder = null
                return
            }
        } catch (t: Throwable) {
            LogWriter.log(TAG, "record init err: " + t)
            toast("无法开始录音(缺少录音权限)")
            return
        }

        val buf = ByteArrayOutputStream()
        recBuffer = buf

        val time = TextView(ctx)
        time.text = "00:00"
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        time.typeface = Typeface.DEFAULT_BOLD
        time.setTextColor(AppColors.onSurface())
        time.gravity = Gravity.CENTER
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(16), 0, dp(4))
        box.addView(time)

        val start = System.currentTimeMillis()
        val recDialog = AlertDialog.Builder(ctx)
                .setTitle("录制录音")
                .setView(box)
                .setNegativeButton("停止并加入", null)
                .setPositiveButton("取消", null)
                .create()

        recording = true
        recorder?.startRecording()
        val saved = booleanArrayOf(false)
        recDialog.setOnDismissListener { if (!saved[0]) stopRecording(false) }
        recDialog.show()
        recDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            val pcm = stopRecording(true)
            saved[0] = true
            recDialog.dismiss()
            if (pcm != null && pcm.isNotEmpty()) {
                val s = Seg("录音 " + fmtMs(TtsVoiceSender.pcmDurationMs(pcm)), TYPE_REC, pcm)
                segs.add(s)
                rebuildList()
                rebuildTimeline()
                select(segs.size - 1)
                toast("已加入录音")
                Thread({
                    fillEnvs(s)
                    ui.post { timeline?.invalidate() }
                }, "ls-amix-env").start()
            } else {
                toast("录音为空")
            }
        }
        recDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            saved[0] = true
            stopRecording(false)
            recDialog.dismiss()
        }

        val maxRecBytes = REC_SAMPLE_RATE * 2 * 60 * 15
        val warned = booleanArrayOf(false)
        Thread({
            val chunk = ByteArray(REC_SAMPLE_RATE)
            while (recording) {
                val rec = recorder ?: break
                val n = rec.read(chunk, 0, chunk.size)
                if (n < 0) break
                if (n > 0) {
                    if (buf.size() < maxRecBytes) buf.write(chunk, 0, n)
                    else if (!warned[0]) {
                        warned[0] = true
                        ui.post { toast("录音已达 15 分钟上限, 请手动停止") }
                    }
                }
                val el = System.currentTimeMillis() - start
                ui.post { time.text = fmtMs(el.toInt()) }
            }
        }, "ls-amix-rec").start()
    }

    private fun stopRecording(keep: Boolean): ByteArray? {
        var out: ByteArray? = null
        if (recording) {
            recording = false
            val r = recorder
            recorder = null
            if (r != null) {
                try { r.stop() } catch (ignored: Throwable) {}
                try { r.release() } catch (ignored: Throwable) {}
            }
        }
        try {
            Thread.sleep(60)
        } catch (ignored: InterruptedException) {}
        val rb = recBuffer
        if (keep && rb != null && rb.size() > 0) {
            out = rb.toByteArray()
        }
        recBuffer = null
        return out
    }

    private fun buildOutputPcm(): ByteArray? {
        if (segs.isEmpty()) return null
        if (mode == MODE_STITCH) {
            val parts = ArrayList<ByteArray>()
            for (s in segs) {
                val e = s.effective()
                if (e != null && e.isNotEmpty()) parts.add(e)
            }
            return TtsVoiceSender.concatPcm(parts)
        }
        val clips = ArrayList<ByteArray>()
        val offs = ArrayList<Int>()
        val gains = ArrayList<Float>()
        var totalSamples = 0
        for (s in segs) {
            val e = s.effective()
            if (e == null || e.isEmpty()) continue
            val samples = e.size / 2
            val offSamples = (s.offsetMs.toLong() * SAMPLE_RATE / 1000).toInt()
            clips.add(e)
            offs.add(offSamples)
            gains.add(s.gain)
            totalSamples = Math.max(totalSamples, offSamples + samples)
        }
        if (clips.isEmpty()) return null
        return TtsVoiceSender.mixPcm(clips, offs, gains, totalSamples)
    }

    private fun doExport() {
        if (exporting) return
        if (globalPlayer?.isActive() == true) globalPlayer?.stop()
        if (editPlayer?.isActive() == true) editPlayer?.stop()
        if (segs.isEmpty()) {
            toast("还没有可用片段")
            return
        }
        val tk = talker
        if (tk.isNullOrEmpty()) {
            toast("无法获取当前聊天对象")
            return
        }
        try {
            val live = ChatFooterLongPressMenu.resolveLiveTalker()
            LogWriter.log(TAG, "doExport talker=" + tk + " liveTalker=" + live
                    + if (live != null && live != tk) "  [MISMATCH live!=target]" else "")
        } catch (ignored: Throwable) {}

        val fakeDurationMs = WmPrefs.getInt("voice_fake_duration_sec", 1) * 1000
        exporting = true
        showBusy("正在编码并发送 …")
        Thread(Runnable {
            val out = buildOutputPcm()
            if (out == null || out.isEmpty()) {
                ui.post {
                    exporting = false
                    hideBusy()
                    toast("还没有可用片段")
                }
                return@Runnable
            }
            val ok = TtsVoiceSender.sendRawPcmVoice(tk, out, fakeDurationMs) { cur, total ->
                ui.post { setBusyText("正在发送 " + cur + "/" + total + " …") }
            }
            ui.post {
                exporting = false
                hideBusy()
                if (ok) {
                    toast("已发送语音")
                    dialog?.dismiss()
                } else {
                    toast("发送失败, 请重试")
                }
            }
        }, "ls-amix-send").start()
    }

    private var busy: AlertDialog? = null
    private var busyText: TextView? = null

    private fun showBusy(msg: String) {
        hideBusy()
        val tv = TextView(ctx)
        tv.text = msg
        tv.setTextColor(AppColors.onSurface())
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        tv.setPadding(dp(20), dp(16), dp(20), dp(16))
        busy = AlertDialog.Builder(ctx).setView(tv).setCancelable(false).create()
        busy?.show()
        busyText = tv
    }

    private fun setBusyText(msg: String) {
        busyText?.text = msg
    }

    private fun hideBusy() {
        val b = busy
        if (b != null) {
            try { b.dismiss() } catch (ignored: Throwable) {}
            busy = null
            busyText = null
        }
    }
}