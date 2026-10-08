package com.leshao.v3.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import com.leshao.v3.ContextManager
import com.leshao.v3.music.DianGeService
import com.leshao.v3.music.KuwoMusicApi
import com.leshao.v3.music.OnlineMusicPrefs
import com.leshao.v3.ui.widgets.DiscView
import com.leshao.v3.ui.widgets.EqBarsView
import com.leshao.v3.ui.widgets.M3Page
import com.leshao.v3.ui.widgets.ModernTopBar
import com.leshao.v3.ui.widgets.MusicIconView
import com.leshao.v3.ui.widgets.SegmentedControl

import java.io.File
import java.util.ArrayList
import java.util.concurrent.Callable

/**
 * 「在线音乐」功能页（原型第一套 · 流光卡片）。
 *
 * <p>底部导航固定为「首页 / 播放 / 我的」，导航上方为常驻「正在播放」条
 * （显示歌曲 + 播放/暂停 + 下一首），点击该条进入播放器页；播放器页保留底部
 * 导航栏，仅隐藏冗余的「正在播放」条。</p>
 *
 * <p>首页顶部分类：搜索 / 榜单 / 歌单 / 导入；搜索页内再分 歌曲 / 专辑 / 歌手 / 歌单。
 * 歌曲行提供 播放 / 下载 / 发送 图标；发送走模块联系人选择器（可多选）。</p>
 */
class OnlineMusicPageView private constructor() {

    companion object {

        @JvmStatic
        fun create(ctx: Context, parentAct: Activity): View {
            return Controller(ctx, parentAct).build()
        }

        // ==================== 悬浮球/后台播放 外部控制入口 ====================

        /** 是否有活动播放(含暂停)。 */
        @JvmStatic
        fun hasPlayback(): Boolean {
            return Controller.player != null || Controller.current != null
        }

        @JvmStatic
        fun playbackPaused(): Boolean {
            return Controller.paused || Controller.player == null
        }

        @JvmStatic
        fun playbackTitle(): String {
            val s = Controller.current
            return s?.title ?: ""
        }

        @JvmStatic
        fun togglePlayback() {
            val c = Controller.sOwner
            if (c != null) c.togglePlay()
        }

        @JvmStatic
        fun nextPlayback() {
            val c = Controller.sOwner
            if (c != null) c.next()
        }

        @JvmStatic
        fun prevPlayback() {
            val c = Controller.sOwner
            if (c != null) c.prev()
        }

        @JvmStatic
        fun stopPlayback() {
            val c = Controller.sOwner
            if (c != null) c.stopPlayer()
            MusicFloatBall.hide()
        }
    }

    /** 根容器，把返回事件转交给 Controller 的内部返回栈。 */
    private class RootView(ctx: Context) : LinearLayout(ctx), SubPageActivity.BackHandler {
        private var controller: Controller? = null

        fun bind(c: Controller) {
            this.controller = c
        }

        override fun onBack(): Boolean {
            return controller != null && controller!!.handleBack()
        }
    }

    private fun interface ListRenderer<T> {
        fun render(host: LinearLayout, data: List<T>)
    }

    private fun interface DetailBuilder {
        fun build(host: LinearLayout)
    }

    private fun interface ValueCallback {
        fun onValue(value: String)
    }

    private class Controller(private val ctx: Context, private val act: Activity) {

        companion object {
            private const val NAV_HOME = 0
            private const val NAV_PLAY = 1
            private const val NAV_MINE = 2

            // 首页标签（搜索改为顶部只读搜索框 + 弹窗，不再占标签位）
            private const val HOME_TAB_CHARTS = 0
            private const val HOME_TAB_SHEETS = 1
            private const val HOME_TAB_IMPORT = 2
            /** 首页默认标签：歌单 */
            private const val HOME_TAB_DEFAULT = HOME_TAB_SHEETS

            // v1105: 播放状态提升为静态, 关闭页面后由静态状态继续播放; 重新进入时接管并可继续控制
            @JvmField
            var player: MediaPlayer? = null
            @JvmField
            var current: KuwoMusicApi.Song? = null
            @JvmField
            var paused: Boolean = false
            private val queue: MutableList<KuwoMusicApi.Song> = ArrayList()
            private var queueIndex: Int = -1
            /** 当前处于前台的控制器(关闭页面后置空, 仅保留静态播放状态)。 */
            @JvmField
            var sActive: Controller? = null
            /** 播放会话持有者: 关闭页面后仍用于推进后台播放/响应悬浮球控制。 */
            @JvmField
            var sOwner: Controller? = null
            // 播放顺序: 1=列表循环 2=单曲循环 3=随机播放
            private var playMode: Int = 1

            // ===== 音质档位（自动 / 无损 / 320K / 128K） =====
            private val Q_LABELS = arrayOf("自动", "无损 FLAC", "320K", "128K")
            private val Q_SUBS = arrayOf(
                    "优先最高音质 · 自动降级", "最高音质 · 文件较大", "高音质 · 推荐", "标准音质 · 省流量")
            private val Q_VALS = arrayOf(
                    KuwoMusicApi.Q_AUTO, KuwoMusicApi.Q_FLAC, KuwoMusicApi.Q_320, KuwoMusicApi.Q_128)

            private fun qualityIndex(q: String): Int {
                for (i in Q_VALS.indices) {
                    if (Q_VALS[i] == q) return i
                }
                return 0
            }

            /** AUTO 无独立档位,取流时按最高档处理(playLevels 已含自动降级)。 */
            private fun effectiveLevel(q: String): String {
                return if (KuwoMusicApi.Q_AUTO == q) KuwoMusicApi.Q_FLAC else q
            }
        }

        private val d: Float = ctx.resources.displayMetrics.density

        private var screenHolder: LinearLayout? = null
        private var miniBar: LinearLayout? = null
        private var navBar: LinearLayout? = null
        private var homeView: View? = null
        private var playView: View? = null
        private var mineView: View? = null
        private var nav: Int = NAV_HOME

        private val content: LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private var tab: Int = HOME_TAB_DEFAULT

        /** 恢复播放时待跳转的进度(ms)。 */
        private var pendingSeekMs: Int = -1

        /** 歌曲行右侧播放按钮引用：用于同步当前播放曲目的播放/暂停态。 */
        private val rowPlayIcons: MutableList<RowPlay> = ArrayList()

        private class RowPlay(val id: String?, val icon: MusicIconView)

        // 正在播放条
        private var miniTitle: TextView? = null
        private var miniArtist: TextView? = null
        private var miniPlay: MusicIconView? = null
        private var miniNext: MusicIconView? = null
        private var progressFill: View? = null
        private var progressTrack: View? = null

        // 下载进度弹窗
        private var downloadDialog: AlertDialog? = null
        private var downloadBar: FlyingProgressBar? = null
        private var downloadPct: TextView? = null

        // 播放器页
        private var playTitle: TextView? = null
        private var playArtist: TextView? = null
        private var playToggle: FrameLayout? = null
        private var playToggleIcon: MusicIconView? = null
        private var playPos: TextView? = null
        private var playDur: TextView? = null
        private var playQueueInfo: TextView? = null
        private var playSeek: FlyingProgressBar? = null
        private var playEq: EqBarsView? = null
        private var playOrderBtn: MusicIconView? = null
        private var playDisc: DiscView? = null
        private var playCollectBtn: View? = null
        private var miniCoverIv: ImageView? = null
        private var playerCoverToken: String? = null
        private var seeking: Boolean = false

        // 底部导航
        private val navIcons = arrayOfNulls<MusicIconView>(3)
        private val navLabels = arrayOfNulls<TextView>(3)

        // v30029: 内部返回栈 —— 详情页/播放器页逐级返回, 不再一次关闭整个弹窗
        private var showingDetail = false
        /** 详情页返回目标: -1=返回「我的」; >=0=返回对应首页标签 */
        private var detailReturnTab = 0
        /** 进入播放器页前的底部导航位置, 用于返回原页面 */
        private var playerOrigin = NAV_HOME

        private val uiHandler = Handler(Looper.getMainLooper())
        private val ticker = object : Runnable {
            override fun run() {
                if (sActive !== this@Controller) { uiHandler.removeCallbacks(this); return }
                updateProgress()
                if (current != null && player != null) {
                    uiHandler.postDelayed(this, 500L)
                }
            }
        }

        // ==================== 骨架 ====================

        fun build(): View {
            val root = RootView(ctx)
            root.bind(this)
            root.orientation = LinearLayout.VERTICAL
            root.tag = "no_wrap"

            screenHolder = LinearLayout(ctx)
            screenHolder!!.orientation = LinearLayout.VERTICAL
            root.addView(screenHolder, LinearLayout.LayoutParams(-1, 0, 1f))

            root.addView(buildMiniBar())
            root.addView(buildNavBar())

            selectNav(NAV_HOME)

            sActive = this
            sOwner = this
            MusicFloatBall.hide()
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { }
                override fun onViewDetachedFromWindow(v: View) {
                    // v1105: 页面关闭时保存播放进度, 交由静态状态继续播放并由悬浮球控制
                    if (sActive === this@Controller) {
                        savePlaybackState()
                        sActive = null
                    }
                    if (OnlineMusicPageView.hasPlayback()) MusicFloatBall.show(act)
                }
            })
            adoptOrRestore()
            return root
        }

        /** v1105: 重新进入页面时接管后台播放, 或从偏好恢复上次播放状态。 */
        private fun adoptOrRestore() {
            val p = player
            if (p != null) {
                // 后台仍在播放：把回调重新绑定到本控制器并刷新 UI
                bindPlayerCallbacks(p)
                try { paused = !p.isPlaying } catch (ignored: Throwable) { paused = false }
                if (playOrderBtn != null) playOrderBtn!!.setIcon(orderIcon())
                refreshPlayerUi()
                updateProgress()
                syncEq()
                if (!paused && current != null) {
                    uiHandler.removeCallbacks(ticker)
                    uiHandler.post(ticker)
                }
                return
            }
            val saved = OnlineMusicPrefs.playbackQueue()
            if (saved.isNotEmpty()) {
                queue.clear()
                queue.addAll(saved)
                queueIndex = OnlineMusicPrefs.playbackIndex()
                playMode = OnlineMusicPrefs.playbackMode()
                if (queueIndex < 0 || queueIndex >= queue.size) queueIndex = 0
                current = queue[queueIndex]
                paused = true
                pendingSeekMs = OnlineMusicPrefs.playbackPosition()
                if (playOrderBtn != null) playOrderBtn!!.setIcon(orderIcon())
                refreshPlayerUi()
                updateProgressTextFromPending()
            }
            maybeAutoRandomPlay()
        }

        /** v1105: 进入页面即随机播放(优先从已有队列/上次队列取, 空队列则拉取默认榜单)。 */
        private fun maybeAutoRandomPlay() {
            if (!OnlineMusicPrefs.autoRandom()) return
            if (!queue.isEmpty()) {
                queueIndex = java.util.Random().nextInt(queue.size)
                playQueueAt(queueIndex)
                return
            }
            bg {
                try {
                    val groups = KuwoMusicApi.chartGroups()
                    if (groups == null || groups.isEmpty()) return@bg
                    val g = groups[0]
                    if (g.charts.isEmpty()) return@bg
                    val songs = KuwoMusicApi.chartSongs(g.charts[0].id)
                    if (songs == null || songs.isEmpty()) return@bg
                    ui {
                        if (sActive !== this@Controller) return@ui
                        queue.clear()
                        queue.addAll(songs)
                        queueIndex = java.util.Random().nextInt(queue.size)
                        playQueueAt(queueIndex)
                    }
                } catch (ignored: Throwable) {
                }
            }
        }

        private fun selectNav(index: Int) {
            nav = index
            screenHolder!!.removeAllViews()
            if (index == NAV_HOME) {
                if (homeView == null) homeView = buildHome()
                screenHolder!!.addView(homeView!!, LinearLayout.LayoutParams(-1, -1))
            } else if (index == NAV_PLAY) {
                if (playView == null) playView = buildPlayer()
                screenHolder!!.addView(playView!!, LinearLayout.LayoutParams(-1, -1))
            } else {
                if (mineView == null) mineView = buildMine()
                screenHolder!!.addView(mineView!!, LinearLayout.LayoutParams(-1, -1))
            }
            val isPlayer = index == NAV_PLAY
            // 播放器页保留底部导航栏(用户要求, 避免影响体验); 仅隐藏冗余的「正在播放」条
            miniBar!!.visibility = if (isPlayer) View.GONE else View.VISIBLE
            navBar!!.visibility = View.VISIBLE
            updateNavSelection()
            refreshPlayerUi()
            updateProgress()
            syncEq()
        }

        // ==================== 内部返回 ====================

        /**
         * 逐级向上返回：详情页 → 首页标签 → 我的/播放器 → 首页。
         * 已在最顶层时返回 false，交回宿主关闭弹窗。
         */
        fun handleBack(): Boolean {
            if (nav == NAV_HOME && showingDetail) {
                backFromDetail()
                return true
            }
            if (nav == NAV_PLAY) {
                backFromPlayer()
                return true
            }
            if (nav == NAV_MINE) {
                selectNav(NAV_HOME)
                return true
            }
            if (nav == NAV_HOME && tab != HOME_TAB_DEFAULT) {
                selectTab(HOME_TAB_DEFAULT)
                return true
            }
            return false
        }

        private fun backFromDetail() {
            showingDetail = false
            if (detailReturnTab < 0) {
                selectNav(NAV_MINE)
            } else {
                selectTab(detailReturnTab)
            }
        }

        private fun backFromPlayer() {
            selectNav(if (playerOrigin == NAV_PLAY) NAV_HOME else playerOrigin)
        }

        private fun buildHome(): View {
            val scroll = ScrollView(ctx)
            scroll.isFillViewport = true

            val page = LinearLayout(ctx)
            page.orientation = LinearLayout.VERTICAL
            page.setPadding(dp(12), dp(6), dp(12), dp(12))

            page.addView(searchEntry())
            page.addView(M3Page.spacer(ctx, 8f))
            val seg = SegmentedControl(ctx, arrayOf("榜单", "歌单", "导入"), HOME_TAB_SHEETS)
            seg.setOnSegmentChangedListener(object : SegmentedControl.OnSegmentChangedListener {
                override fun onChanged(index: Int, label: String) { selectTab(index) }
            })
            page.addView(seg)
            page.addView(M3Page.spacer(ctx, 8f))
            page.addView(content)

            scroll.addView(page)
            selectTab(HOME_TAB_SHEETS)
            return scroll
        }

        /** 首页顶部只读搜索框：点击弹出搜索窗口。 */
        private fun searchEntry(): View {
            val box = LinearLayout(ctx)
            box.orientation = LinearLayout.HORIZONTAL
            box.gravity = Gravity.CENTER_VERTICAL
            box.setPadding(dp(14), dp(11), dp(14), dp(11))
            try {
                val bg = GradientDrawable()
                bg.setColor(AppColors.surfaceContainerHighest())
                bg.cornerRadius = dp(AppColors.SHAPE_INPUT_DP).toFloat()
                box.background = bg
            } catch (ignored: Throwable) {}
            CandyUi.ripple(box, AppColors.SHAPE_INPUT_DP.toFloat())

            val ic = MusicIconView(ctx, MusicIconView.SEARCH)
            ic.setIconSizeDp(18)
            ic.setStrokeWidthDp(2.4f)
            ic.setActive(false)
            box.addView(ic)

            val hint = TextView(ctx)
            hint.text = "搜索歌曲 / 专辑 / 歌手 / 歌单"
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            hint.setTextColor(AppColors.text3())
            hint.isSingleLine = true
            hint.setPadding(dp(8), 0, 0, 0)
            box.addView(hint, LinearLayout.LayoutParams(0, -2, 1f))

            box.contentDescription = "搜索"
            box.setOnClickListener { showSearchWindow() }
            return box
        }

        // ==================== 底部 · 正在播放条 ====================

        private fun buildMiniBar(): View {
            miniBar = LinearLayout(ctx)
            miniBar!!.orientation = LinearLayout.VERTICAL
            try {
                val bgd = GradientDrawable()
                bgd.setColor(AppColors.surfaceContainerLowest())
                val r = dp(AppColors.SHAPE_LG_DP).toFloat()
                bgd.setCornerRadii(floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f))
                miniBar!!.background = bgd
            } catch (ignored: Throwable) {}
            CandyUi.elevate(miniBar)

            // 顶部细进度线（分隔 + 进度）
            val track = FrameLayout(ctx)
            track.setBackgroundColor(AppColors.outlineVariant())
            progressTrack = track
            val fill = View(ctx)
            try {
                val gd = GradientDrawable()
                gd.setColor(AppColors.primary())
                fill.background = gd
            } catch (ignored: Throwable) { fill.setBackgroundColor(AppColors.primary()) }
            fill.layoutParams = FrameLayout.LayoutParams(0, dp(2))
            progressFill = fill
            track.addView(fill)
            miniBar!!.addView(track, LinearLayout.LayoutParams(-1, dp(2)))

            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(12), dp(8), dp(10), dp(8))
            miniBar!!.addView(row)

            val miniCover = baseCover(40, 18)
            miniCoverIv = addCoverImage(miniCover)
            row.addView(miniCover)

            val info = LinearLayout(ctx)
            info.orientation = LinearLayout.VERTICAL
            info.gravity = Gravity.CENTER_VERTICAL
            info.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            info.setPadding(dp(10), 0, dp(6), 0)

            miniTitle = TextView(ctx)
            miniTitle!!.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            miniTitle!!.typeface = Typeface.DEFAULT_BOLD
            miniTitle!!.setTextColor(AppColors.text1())
            miniTitle!!.isSingleLine = true
            miniTitle!!.ellipsize = TextUtils.TruncateAt.END
            info.addView(miniTitle!!)

            miniArtist = TextView(ctx)
            miniArtist!!.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            miniArtist!!.setTextColor(AppColors.text2())
            miniArtist!!.isSingleLine = true
            miniArtist!!.ellipsize = TextUtils.TruncateAt.END
            info.addView(miniArtist!!)
            info.setOnClickListener { openPlayer() }
            row.addView(info)

            miniPlay = miniIcon(MusicIconView.PLAY, "播放/暂停") { togglePlay() }
            row.addView(miniPlay!!)
            miniNext = miniIcon(MusicIconView.NEXT, "下一首") { next() }
            row.addView(miniNext!!)

            return miniBar!!
        }

        private fun miniIcon(icon: String, desc: String, cb: View.OnClickListener): MusicIconView {
            val ic = MusicIconView(ctx, icon)
            ic.setIconSizeDp(20)
            ic.setStrokeWidthDp(2.6f)
            ic.setPadding(dp(9), dp(6), dp(9), dp(6))
            ic.contentDescription = desc
            ic.setOnClickListener(cb)
            return ic
        }

        // ==================== 底部 · 导航栏 ====================

        private fun buildNavBar(): View {
            navBar = LinearLayout(ctx)
            navBar!!.orientation = LinearLayout.HORIZONTAL
            navBar!!.setBackgroundColor(AppColors.surfaceContainer())
            CandyUi.elevate(navBar)
            val icons = arrayOf(MusicIconView.HOME, MusicIconView.DISC, MusicIconView.USER)
            val labels = arrayOf("首页", "播放", "我的")
            for (i in 0 until 3) {
                val idx = i
                val item = LinearLayout(ctx)
                item.orientation = LinearLayout.VERTICAL
                item.gravity = Gravity.CENTER
                item.setPadding(0, dp(7), 0, dp(6))
                item.setOnClickListener { selectNav(idx) }
                CandyUi.ripple(item, 0f)

                val ic = MusicIconView(ctx, icons[i])
                ic.setIconSizeDp(23)
                ic.setStrokeWidthDp(2.6f)
                ic.setActive(false)
                item.addView(ic)

                val lb = TextView(ctx)
                lb.text = labels[i]
                lb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                lb.gravity = Gravity.CENTER
                lb.setPadding(0, dp(2), 0, 0)
                item.addView(lb)

                navIcons[i] = ic
                navLabels[i] = lb
                navBar!!.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
            }
            return navBar!!
        }

        private fun updateNavSelection() {
            for (i in 0 until 3) {
                val on = i == nav
                val c = if (on) AppColors.primary() else AppColors.text2()
                navIcons[i]!!.setActive(on)
                navLabels[i]!!.setTextColor(c)
                navLabels[i]!!.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            }
        }

        // ==================== 播放器页 ====================

        private fun buildPlayer(): View {
            val page = LinearLayout(ctx)
            page.orientation = LinearLayout.VERTICAL
            page.gravity = Gravity.CENTER_HORIZONTAL
            page.setPadding(dp(22), dp(6), dp(22), dp(12))
            page.layoutParams = LinearLayout.LayoutParams(-1, -1)

            val back = TextView(ctx)
            back.text = "\u2190 返回"
            back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            back.setTextColor(AppColors.primary())
            back.typeface = Typeface.DEFAULT_BOLD
            back.setPadding(dp(2), dp(4), dp(2), dp(4))
            back.setOnClickListener { backFromPlayer() }
            page.addView(back, LinearLayout.LayoutParams(-1, -2))

            page.addView(spaceGrow())

            // 碟片：加深渐变 + 旋转发光，中心圆形＝封面位
            val disc = DiscView(ctx)
            playDisc = disc
            val dLp = LinearLayout.LayoutParams(dp(200), dp(200))
            dLp.gravity = Gravity.CENTER_HORIZONTAL
            page.addView(disc, dLp)

            page.addView(spaceGrow())

            playTitle = centerText(20, AppColors.text1(), true)
            page.addView(playTitle!!, LinearLayout.LayoutParams(-1, -2))
            playArtist = centerText(13, AppColors.text2(), false)
            playArtist!!.setPadding(0, dp(5), 0, 0)
            page.addView(playArtist!!, LinearLayout.LayoutParams(-1, -2))

            page.addView(spaceGrow())

            // 旋律柱(宽 90%), 播放时显示并绑定播放器频谱
            playEq = EqBarsView(ctx)
            playEq!!.setSpanRatio(0.90f)
            playEq!!.visibility = View.GONE
            page.addView(playEq!!, LinearLayout.LayoutParams(-1, dp(160)))

            page.addView(spaceGrow())

            val prog = LinearLayout(ctx)
            prog.orientation = LinearLayout.VERTICAL
            // 进度条：与「转码中」同款的流光飞鸟进度条，支持拖动调节
            playSeek = FlyingProgressBar(ctx)
            playSeek!!.setSeekable(true)
            playSeek!!.setOnSeekListener(object : FlyingProgressBar.OnSeekListener {
                override fun onSeekStart() {
                    seeking = true
                }
                override fun onSeek(percent: Int) {
                    updateProgressText(percent)
                }
                override fun onSeekEnd(percent: Int) {
                    seeking = false
                    if (player != null) {
                        try {
                            val dur = player!!.duration
                            if (dur > 0) player!!.seekTo((dur * (percent / 100f)).toInt())
                        } catch (ignored: Throwable) {}
                    }
                    updateProgress()
                    savePlaybackState()
                }
            })
            prog.addView(playSeek!!, LinearLayout.LayoutParams(-1, -2))

            val times = LinearLayout(ctx)
            times.orientation = LinearLayout.HORIZONTAL
            playPos = TextView(ctx)
            playPos!!.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            playPos!!.setTextColor(AppColors.text2())
            playPos!!.text = "00:00"
            playPos!!.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            playDur = TextView(ctx)
            playDur!!.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            playDur!!.setTextColor(AppColors.text2())
            playDur!!.text = "00:00"
            playDur!!.gravity = Gravity.END
            times.addView(playPos!!)
            times.addView(playDur!!)
            prog.addView(times, LinearLayout.LayoutParams(-1, -2))
            page.addView(prog, LinearLayout.LayoutParams(-1, -2))

            page.addView(spaceGrow())

            val frow = LinearLayout(ctx)
            frow.orientation = LinearLayout.HORIZONTAL
            playCollectBtn = fbtn(MusicIconView.FAV, "收藏") { toggleCollect() }
            frow.addView(playCollectBtn!!, LinearLayout.LayoutParams(0, -2, 1f))
            frow.addView(fbtn(MusicIconView.DL, "下载") {
                val s = current
                if (s == null) { M3Page.toast(ctx, "还没有在播放的歌曲"); return@fbtn }
                download(s)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            frow.addView(fbtn(MusicIconView.SEND, "发送") {
                val s = current
                if (s == null) { M3Page.toast(ctx, "还没有在播放的歌曲"); return@fbtn }
                sendToTargets(s)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            frow.addView(fbtn(MusicIconView.QUALITY, "音质") { showQualityDialog() },
                    LinearLayout.LayoutParams(0, -2, 1f))
            page.addView(frow, LinearLayout.LayoutParams(-1, -2))

            page.addView(spaceGrow())

            val transport = transportBar()
            transport.translationY = dp(6).toFloat()
            page.addView(transport, LinearLayout.LayoutParams(-1, -2))

            return page
        }

        private fun playToggle(): View {
            val box = FrameLayout(ctx)
            val lp = LinearLayout.LayoutParams(dp(48), dp(48))
            lp.setMargins(dp(14), 0, dp(14), 0)
            box.layoutParams = lp
            playToggle = box
            playToggleIcon = MusicIconView(ctx, MusicIconView.PLAY)
            playToggleIcon!!.setIconSizeDp(28)
            val ilp = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
            box.addView(playToggleIcon!!, ilp)
            CandyUi.ripple(box, 24f)
            box.contentDescription = "播放/暂停"
            box.setOnClickListener { togglePlay() }
            return box
        }

        private fun spaceGrow(): View {
            val v = View(ctx)
            v.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            return v
        }

        private fun baseCover(sizeDp: Int, glyphSp: Int): FrameLayout {
            val box = FrameLayout(ctx)
            try {
                val bg = GradientDrawable()
                bg.shape = GradientDrawable.RECTANGLE
                val r = dp(if (sizeDp >= 100) AppColors.SHAPE_CARD_DP else AppColors.SHAPE_MD_DP).toFloat()
                bg.cornerRadius = r
                bg.setColor(AppColors.primary())
                box.background = bg
                box.clipToOutline = true
                box.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, r)
                    }
                }
            } catch (ignored: Throwable) {}
            val note = TextView(ctx)
            note.text = "\u266A"
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, glyphSp.toFloat())
            note.setTextColor(AppColors.onGradient())
            note.gravity = Gravity.CENTER
            box.addView(note, FrameLayout.LayoutParams(-1, -1))
            box.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            return box
        }

        private fun addCoverImage(box: FrameLayout): ImageView {
            val iv = ImageView(ctx)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            box.addView(iv, FrameLayout.LayoutParams(-1, -1))
            return iv
        }

        /** 根据当前歌曲刷新盘心封面与「正在播放」条缩略图；列表接口无封面时按 id 懒解析。 */
        private fun applyCover() {
            if (miniCoverIv != null) miniCoverIv!!.setImageDrawable(null)
            if (playDisc != null) playDisc!!.setCoverBitmap(null)
            val s = current ?: return
            val token = "cover:" + s.id
            playerCoverToken = token
            if (miniCoverIv != null) miniCoverIv!!.tag = token
            if (!s.artwork.isNullOrEmpty()) {
                if (miniCoverIv != null) CoverLoader.load(s.artwork, miniCoverIv)
                CoverLoader.loadBitmap(s.artwork, dp(220)) { bm ->
                    if (token == playerCoverToken && playDisc != null) playDisc!!.setCoverBitmap(bm)
                }
                return
            }
            bg {
                val c = KuwoMusicApi.cover(s.id) ?: return@bg
                s.artwork = c
                ui {
                    if (token != playerCoverToken) return@ui
                    if (miniCoverIv != null && token == miniCoverIv!!.tag) {
                        CoverLoader.load(c, miniCoverIv)
                    }
                    CoverLoader.loadBitmap(c, dp(220)) { bm ->
                        if (token == playerCoverToken && playDisc != null) playDisc!!.setCoverBitmap(bm)
                    }
                }
            }
        }

        /** 列表行缩略图：有封面直载，无封面按 id 懒解析后回填。 */
        private fun bindRowCover(s: KuwoMusicApi.Song, iv: ImageView?) {
            if (iv == null) return
            if (!s.artwork.isNullOrEmpty()) {
                CoverLoader.load(s.artwork, iv)
                return
            }
            val token = "cover:" + s.id
            iv.tag = token
            bg {
                val c = KuwoMusicApi.cover(s.id) ?: return@bg
                s.artwork = c
                ui {
                    if (token == iv.tag) CoverLoader.load(c, iv)
                }
            }
        }

        private fun centerText(sp: Int, color: Int, bold: Boolean): TextView {
            val tv = TextView(ctx)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
            if (bold) tv.typeface = Typeface.DEFAULT_BOLD
            tv.setTextColor(color)
            tv.gravity = Gravity.CENTER
            tv.isSingleLine = true
            tv.ellipsize = TextUtils.TruncateAt.END
            return tv
        }

        // 图标化功能按钮(收藏/下载/发送/音质)
        private fun fbtn(icon: String, label: String, cb: View.OnClickListener): View {
            val item = LinearLayout(ctx)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            item.setOnClickListener(cb)
            CandyUi.ripple(item, 0f)
            val ic = MusicIconView(ctx, icon)
            ic.setIconSizeDp(22)
            item.addView(ic)
            val lb = TextView(ctx)
            lb.text = label
            lb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            lb.setTextColor(AppColors.text2())
            lb.gravity = Gravity.CENTER
            lb.setPadding(0, dp(4), 0, 0)
            item.addView(lb)
            return item
        }

        // 播放顺序最左 + 上一首/播放暂停/下一首底部居中(间距拉开)
        private fun transportBar(): View {
            val bar = FrameLayout(ctx)
            val center = LinearLayout(ctx)
            center.orientation = LinearLayout.HORIZONTAL
            center.gravity = Gravity.CENTER
            center.addView(transportIcon(MusicIconView.PREV, "上一首") { prev() },
                    LinearLayout.LayoutParams(dp(40), dp(40)))
            val plp = LinearLayout.LayoutParams(dp(48), dp(48))
            plp.setMargins(dp(14), 0, dp(14), 0)
            center.addView(playToggle(), plp)
            center.addView(transportIcon(MusicIconView.NEXT, "下一首") { next() },
                    LinearLayout.LayoutParams(dp(40), dp(40)))
            bar.addView(center, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))

            val order = transportIcon(orderIcon(), "播放顺序") { cyclePlayMode() }
            bar.addView(order, FrameLayout.LayoutParams(-2, -2,
                    Gravity.START or Gravity.CENTER_VERTICAL))
            playOrderBtn = order
            return bar
        }

        private fun transportIcon(icon: String, desc: String, cb: View.OnClickListener): MusicIconView {
            val ic = MusicIconView(ctx, icon)
            ic.setIconSizeDp(26)
            ic.contentDescription = desc
            ic.setOnClickListener(cb)
            return ic
        }

        private fun orderIcon(): String {
            if (playMode == 2) return MusicIconView.ORDER_SINGLE
            if (playMode == 3) return MusicIconView.ORDER_SHUFFLE
            return MusicIconView.ORDER_LIST
        }

        private fun cyclePlayMode() {
            playMode = if (playMode == 1) 2 else (if (playMode == 2) 3 else 1)
            if (playOrderBtn != null) playOrderBtn!!.setIcon(orderIcon())
            M3Page.toast(ctx, if (playMode == 2) "单曲循环" else (if (playMode == 3) "随机播放" else "列表循环"))
            savePlaybackState()
        }

        private fun toggleCollect() {
            if (current == null || current!!.id.isNullOrEmpty()) {
                M3Page.toast(ctx, "还没有在播放的歌曲")
                return
            }
            val now = OnlineMusicPrefs.toggleFavorite(current!!.id)
            refreshCollectBtn()
            M3Page.toast(ctx, if (now) "已收藏" else "已取消收藏")
        }

        /** 同步收藏按钮心形填充态。 */
        private fun refreshCollectBtn() {
            if (playCollectBtn !is ViewGroup) return
            val g = playCollectBtn as ViewGroup
            for (i in 0 until g.childCount) {
                val ch = g.getChildAt(i)
                if (ch is MusicIconView) {
                    val fav = current != null && OnlineMusicPrefs.isFavorite(current!!.id)
                    ch.setSolid(fav)
                    break
                }
            }
        }

        private fun showQualityDialog() {
            val labels = Q_LABELS
            val subs = Q_SUBS
            val vals = Q_VALS
            val q = OnlineMusicPrefs.quality()
            val cur = intArrayOf(qualityIndex(q))

            val holder = arrayOfNulls<AlertDialog>(1)

            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.background = CandyUi.dialogBg(ctx)
            InsetsUtil.clipRounded(root)

            val topBar = ModernTopBar(ctx, "选择音质", true) { if (holder[0] != null) holder[0]!!.dismiss() }
            root.addView(topBar, LinearLayout.LayoutParams(-1, -2))

            val body = LinearLayout(ctx)
            body.orientation = LinearLayout.VERTICAL
            body.setPadding(dp(12), dp(4), dp(12), dp(12))

            val card = M3Page.card(ctx)
            for (i in labels.indices) {
                if (i > 0) card.addView(M3Page.divider(ctx))
                card.addView(qualityRow(labels[i], subs[i], i == cur[0],
                        vals[i], labels[i], holder))
            }
            body.addView(card)
            root.addView(body)

            val dialog = AlertDialog.Builder(ctx)
                    .setView(root)
                    .setCancelable(true)
                    .create()
            holder[0] = dialog
            InsetsUtil.transparentWindow(dialog)
            dialog.show()
            WindowLayer.track(dialog.window)
        }

        /** 音质选项行：标题 + 说明 + 右侧勾选；点击即选中并关闭。 */
        private fun qualityRow(label: String, sub: String, checked: Boolean,
                               value: String, labelText: String,
                               holder: Array<AlertDialog?>): View {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(12), dp(12), dp(12), dp(12))
            row.background = CandyUi.rowPressBg(ctx)

            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            col.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            val title = TextView(ctx)
            title.text = label
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            title.typeface = Typeface.DEFAULT_BOLD
            title.setTextColor(AppColors.text1())
            col.addView(title)
            val s = TextView(ctx)
            s.text = sub
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            s.setTextColor(AppColors.text2())
            col.addView(s)
            row.addView(col)

            val check = TextView(ctx)
            check.text = if (checked) "✓" else ""
            check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            check.typeface = Typeface.DEFAULT_BOLD
            check.setTextColor(AppColors.primary())
            check.gravity = Gravity.CENTER
            row.addView(check)

            row.setOnClickListener {
                OnlineMusicPrefs.setQuality(value)
                M3Page.toast(ctx, "音质：" + labelText)
                if (holder[0] != null) holder[0]!!.dismiss()
            }
            return row
        }

        // ==================== 我的 ====================

        private fun buildMine(): View {
            val scroll = ScrollView(ctx)
            scroll.isFillViewport = true

            val page = LinearLayout(ctx)
            page.orientation = LinearLayout.VERTICAL
            page.setPadding(dp(12), dp(6), dp(12), dp(12))

            page.addView(M3Page.section(ctx, "点歌与设置"))
            val card1 = M3Page.card(ctx)
            card1.addView(M3Page.clickRow(ctx, "\u2699", "点歌设置",
                    "白名单 / 别名 / 音质 / 误报时长") {
                selectNav(NAV_HOME)
                openSettings()
            })
            page.addView(card1)

            page.addView(M3Page.section(ctx, "默认音质"))
            val q = OnlineMusicPrefs.quality()
            val qIdx = qualityIndex(q)
            val qSeg = SegmentedControl(ctx, Q_LABELS, qIdx)
            qSeg.setOnSegmentChangedListener(object : SegmentedControl.OnSegmentChangedListener {
                override fun onChanged(index: Int, label: String) {
                    OnlineMusicPrefs.setQuality(Q_VALS[index])
                    M3Page.toast(ctx, "音质：" + label)
                }
            })
            page.addView(qSeg)
            page.addView(M3Page.spacer(ctx, 6f))
            page.addView(M3Page.note(ctx, "“自动”优先取最高音质，不可用时自动降档；点歌与在线试听均按此设置。"))

            page.addView(M3Page.section(ctx, "下载"))
            val card2 = M3Page.card(ctx)
            card2.addView(M3Page.clickRow(ctx, "\u2B07", "下载目录",
                    downloadDir().absolutePath) {
                M3Page.toast(ctx, "已保存歌曲位于：" + downloadDir().absolutePath)
            })
            page.addView(card2)

            scroll.addView(page)
            return scroll
        }

        private fun downloadDir(): File {
            val base = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir
            return File(base, "LeShaoMusic")
        }

        // ==================== 播放控制 ====================

        private fun openPlayer() {
            if (current == null) {
                M3Page.toast(ctx, "还没有在播放的歌曲")
                return
            }
            playerOrigin = nav
            selectNav(NAV_PLAY)
        }

        private fun refreshPlayerUi() {
            if (miniTitle == null) return
            val c = current
            miniTitle!!.text = if (c != null) nz(c.title) else "未在播放"
            miniArtist!!.text = if (c != null) (nz(c.artist) + if (c.album.isNullOrEmpty()) "" else " · " + c.album) else "点击选择歌曲试听"
            miniPlay!!.setIcon(if (paused) MusicIconView.PLAY else MusicIconView.PAUSE)
            if (playTitle != null) playTitle!!.text = if (c != null) nz(c.title) else "未在播放"
            if (playArtist != null) playArtist!!.text = if (c != null) nz(c.artist) else "选择一首歌开始"
            if (playToggleIcon != null) playToggleIcon!!.setIcon(if (paused) MusicIconView.PLAY else MusicIconView.PAUSE)
            if (playQueueInfo != null) {
                playQueueInfo!!.text = if (queue.isEmpty()) "播放队列为空"
                        else "播放队列 " + (queueIndex + 1) + " / " + queue.size
            }
            refreshCollectBtn()
            refreshRowPlayIcons()
            applyCover()
        }

        private fun nz(s: String?): String { return s ?: "" }

        /** 旋律柱：仅在播放器页且正在播放时显示并跟随播放器频谱跳动。 */
        private fun syncEq() {
            if (playEq == null) return
            val show = current != null && nav == NAV_PLAY
            playEq!!.visibility = if (show) View.VISIBLE else View.GONE
            if (show && !paused && player != null) {
                playEq!!.start(player)
            } else {
                playEq!!.stop()
            }
        }

        private fun updateProgress() {
            var dur = 0
            var pos = 0
            try {
                if (player != null) { dur = player!!.duration; pos = player!!.currentPosition }
            } catch (ignored: Throwable) {}
            val frac = if (dur > 0) Math.max(0f, Math.min(1f, pos / dur.toFloat())) else 0f
            if (playSeek != null && !seeking) playSeek!!.setProgress((frac * 100).toInt())
            if (seeking) return
            if (progressFill != null && progressTrack != null) {
                val tw = progressTrack!!.width
                val lp = progressFill!!.layoutParams
                val nw = (tw * frac).toInt()
                if (lp.width != nw) { lp.width = nw; progressFill!!.layoutParams = lp }
            }
            if (playPos != null) playPos!!.text = fmtTime(pos)
            if (playDur != null) playDur!!.text = fmtTime(dur)
        }

        /** 拖动进度条时实时刷新左侧时间（按百分比预估）。 */
        private fun updateProgressText(percent: Int) {
            if (player == null) return
            try {
                val dur = player!!.duration
                if (dur > 0 && playPos != null) {
                    playPos!!.text = fmtTime((dur * (percent / 100f)).toInt())
                }
                if (playDur != null) playDur!!.text = fmtTime(dur)
            } catch (ignored: Throwable) {}
        }

        private fun fmtTime(ms: Int): String {
            if (ms <= 0) return "00:00"
            val s = ms / 1000
            return String.format(java.util.Locale.CHINA, "%02d:%02d", s / 60, s % 60)
        }

        private fun onSongTap(s: KuwoMusicApi.Song, fromList: List<KuwoMusicApi.Song>?) {
            if (current != null && !s.id.isNullOrEmpty() && s.id == current!!.id && player != null) {
                stopPlayer()
                return
            }
            if (fromList != null && fromList.isNotEmpty()) {
                queue.clear()
                queue.addAll(fromList)
                queueIndex = indexOfId(queue, s.id)
            } else if (!queue.isEmpty()) {
                val idx = indexOfId(queue, s.id)
                if (idx >= 0) queueIndex = idx
            }
            resolveAndPlay(s)
        }

        private fun indexOfId(list: List<KuwoMusicApi.Song>, id: String?): Int {
            if (id == null) return -1
            for (i in list.indices) {
                if (id == list[i].id) return i
            }
            return -1
        }

        private fun playQueueAt(i: Int) {
            if (queue.isEmpty() || i < 0 || i >= queue.size) return
            queueIndex = i
            resolveAndPlay(queue[i])
        }

        fun next() {
            if (queue.isEmpty()) { M3Page.toast(ctx, "播放队列为空"); return }
            playQueueAt((queueIndex + 1) % queue.size)
        }

        fun prev() {
            if (queue.isEmpty()) { M3Page.toast(ctx, "播放队列为空"); return }
            playQueueAt((queueIndex - 1 + queue.size) % queue.size)
        }

        /** 试听取流档位: 以所选音质为首选, 失败时逐级降档(与点歌一致)。 */
        private fun playLevels(): Array<String> {
            val q = OnlineMusicPrefs.quality()
            // 自动 / 无损：从最高档开始，逐级降档
            if (KuwoMusicApi.Q_AUTO == q || KuwoMusicApi.Q_FLAC == q) {
                return arrayOf(KuwoMusicApi.Q_FLAC, KuwoMusicApi.Q_320, KuwoMusicApi.Q_128)
            }
            if (KuwoMusicApi.Q_320 == q) {
                return arrayOf(KuwoMusicApi.Q_320, KuwoMusicApi.Q_128)
            }
            return arrayOf(KuwoMusicApi.Q_128, KuwoMusicApi.Q_320)
        }

        private fun resolveAndPlay(s: KuwoMusicApi.Song) {
            stopPlayer()
            if (sActive === this) M3Page.toast(ctx, "正在解析音质…")
            bg {
                var last: Throwable? = null
                for (level in playLevels()) {
                    try {
                        val finalUrl = KuwoMusicApi.resolveFinalUrl(
                                KuwoMusicApi.streamUrl(s.id, level))
                        if (!finalUrl.isEmpty()) {
                            ui { startPlayer(s, finalUrl) }
                            return@bg
                        }
                        last = Exception(level + " 无可用直链")
                    } catch (t: Throwable) {
                        last = t
                    }
                }
                val f = last
                ui { if (sActive === this@Controller) M3Page.toastError(ctx, "取流失败：" + (if (f == null) "无可用音质" else msg(f))) }
            }
        }

        private fun startPlayer(s: KuwoMusicApi.Song, url: String) {
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                mp.setDataSource(url)
                mp.setOnPreparedListener { p ->
                    current = s
                    paused = false
                    if (pendingSeekMs > 0) {
                        try { p.seekTo(pendingSeekMs) } catch (ignored: Throwable) {}
                    }
                    pendingSeekMs = -1
                    p.start()
                    savePlaybackState()
                    // 页面已关闭时仅后台继续播放, 不刷新 UI, 由悬浮球接管控制
                    if (sActive !== this@Controller) { MusicFloatBall.show(act); return@setOnPreparedListener }
                    refreshPlayerUi()
                    updateProgress()
                    syncEq()
                    uiHandler.removeCallbacks(ticker)
                    uiHandler.post(ticker)
                    M3Page.toast(ctx, "播放中：" + s.title)
                }
                bindPlayerCallbacks(mp)
                mp.prepareAsync()
                player = mp
            } catch (t: Throwable) {
                stopPlayer()
                M3Page.toastError(ctx, "播放失败：" + msg(t))
            }
        }

        /** v1105: 绑定播放完成/错误回调(可重复调用以接管后台播放器)。 */
        private fun bindPlayerCallbacks(mp: MediaPlayer) {
            mp.setOnCompletionListener { p ->
                if (queue.isEmpty() || queueIndex < 0 || queueIndex >= queue.size) {
                    stopPlayer()
                    return@setOnCompletionListener
                }
                if (playMode == 2) {
                    playQueueAt(queueIndex)
                } else if (playMode == 3) {
                    playQueueAt(java.util.Random().nextInt(queue.size))
                } else {
                    playQueueAt((queueIndex + 1) % queue.size)
                }
            }
            mp.setOnErrorListener { p, what, extra ->
                stopPlayer()
                if (sActive === this@Controller) M3Page.toastError(ctx, "播放失败")
                true
            }
        }

        /** v1105: 保存当前播放进度/模式, 便于关闭页面后恢复。 */
        private fun savePlaybackState() {
            try {
                var pos = 0
                if (player != null) {
                    try { pos = player!!.currentPosition } catch (ignored: Throwable) {}
                } else if (pendingSeekMs > 0) {
                    pos = pendingSeekMs
                }
                OnlineMusicPrefs.savePlayback(queue, queueIndex, pos, playMode, paused || player == null)
            } catch (ignored: Throwable) {
            }
        }

        /** v1105: 恢复状态下把上次进度显示到时间栏。 */
        private fun updateProgressTextFromPending() {
            if (pendingSeekMs > 0 && playPos != null) playPos!!.text = fmtTime(pendingSeekMs)
        }

        fun togglePlay() {
            if (player == null) {
                if (current != null) {
                    // 恢复的暂停曲目: 重新取流并跳回上次进度
                    pendingSeekMs = OnlineMusicPrefs.playbackPosition()
                    resolveAndPlay(current!!)
                    return
                }
                M3Page.toast(ctx, "还没有在播放的歌曲")
                return
            }
            try {
                if (paused) { player!!.start(); paused = false; uiHandler.post(ticker) }
                else { player!!.pause(); paused = true; uiHandler.removeCallbacks(ticker) }
            } catch (ignored: Throwable) {}
            refreshPlayerUi()
            syncEq()
            savePlaybackState()
        }

        fun stopPlayer() {
            uiHandler.removeCallbacks(ticker)
            if (player != null) {
                try { player!!.stop() } catch (ignored: Throwable) {}
                try { player!!.release() } catch (ignored: Throwable) {}
                player = null
            }
            current = null
            paused = false
            if (progressFill != null) {
                val lp = progressFill!!.layoutParams
                lp.width = 0
                progressFill!!.layoutParams = lp
            }
            refreshPlayerUi()
            updateProgress()
            syncEq()
            savePlaybackState()
        }

        // ==================== 分类 ====================

        private fun selectTab(index: Int) {
            tab = index
            showingDetail = false
            content.removeAllViews()
            when (index) {
                HOME_TAB_CHARTS -> buildCharts()
                HOME_TAB_SHEETS -> buildSheets()
                HOME_TAB_IMPORT -> buildImport()
            }
        }

        // ==================== 搜索（弹窗） ====================

        private var searchKeyword = ""
        private var searchType = 0
        private var searchDialog: AlertDialog? = null
        private var searchInput: EditText? = null
        private var searchHistoryHost: LinearLayout? = null
        private var searchResultHost: LinearLayout? = null

        /** 弹出独立搜索窗口：输入 + 类型切换 + 搜索历史 + 结果（模块统一弹窗风格）。 */
        private fun showSearchWindow() {
            val holder = arrayOfNulls<AlertDialog>(1)

            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            // v30111: 高度随内容自适应（上限 90%），不再强制最小高避免底部留白
            root.background = CandyUi.dialogBg(ctx)
            InsetsUtil.clipRounded(root)

            val topBar = ModernTopBar(ctx, "搜索", true) { if (holder[0] != null) holder[0]!!.dismiss() }
            root.addView(topBar, LinearLayout.LayoutParams(-1, -2))

            val body = LinearLayout(ctx)
            body.orientation = LinearLayout.VERTICAL
            body.setPadding(dp(12), dp(4), dp(12), dp(10))
            root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

            val inputRow = LinearLayout(ctx)
            inputRow.orientation = LinearLayout.HORIZONTAL
            inputRow.gravity = Gravity.CENTER_VERTICAL
            searchInput = M3Page.input(ctx, "输入歌曲 / 专辑 / 歌手 / 歌单")
            searchInput!!.setText(searchKeyword)
            searchInput!!.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            searchInput!!.setOnEditorActionListener { v, actionId, event ->
                val enter = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                        && event.action == android.view.KeyEvent.ACTION_DOWN)
                if (enter) { runSearch(); true } else false
            }
            searchInput!!.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            inputRow.addView(searchInput!!)
            val go = actionText("搜索") { runSearch() }
            go.setPadding(dp(12), dp(9), dp(2), dp(9))
            inputRow.addView(go)
            body.addView(inputRow)

            searchHistoryHost = LinearLayout(ctx)
            searchHistoryHost!!.orientation = LinearLayout.VERTICAL
            searchHistoryHost!!.setPadding(0, dp(8), 0, 0)
            body.addView(searchHistoryHost!!)
            renderSearchHistory()

            body.addView(M3Page.spacer(ctx, 10f))

            val types = SegmentedControl(ctx, arrayOf("歌曲", "专辑", "歌手", "歌单"), searchType)
            types.setOnSegmentChangedListener(object : SegmentedControl.OnSegmentChangedListener {
                override fun onChanged(index: Int, label: String) {
                    searchType = index
                    if (!searchKeyword.isEmpty()) doSearch(index)
                }
            })
            body.addView(types)
            body.addView(M3Page.spacer(ctx, 8f))

            val resultScroll = ScrollView(ctx)
            resultScroll.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            searchResultHost = LinearLayout(ctx)
            searchResultHost!!.orientation = LinearLayout.VERTICAL
            resultScroll.addView(searchResultHost!!)
            body.addView(resultScroll)

            try {
                searchDialog = AlertDialog.Builder(ctx)
                        .setView(root)
                        .setCancelable(true)
                        .create()
                holder[0] = searchDialog
                InsetsUtil.transparentWindow(searchDialog)
                searchDialog!!.show()
                WindowLayer.track(searchDialog!!.window)
            } catch (ignored: Throwable) {}
        }

        private fun runSearch() {
            if (searchInput == null) return
            searchKeyword = searchInput!!.text.toString().trim()
            if (searchKeyword.isEmpty()) {
                M3Page.toast(ctx, "请输入关键词")
                return
            }
            OnlineMusicPrefs.addSearchHistory(searchKeyword)
            renderSearchHistory()
            doSearch(searchType)
        }

        /** 搜索历史：标题 + 清空 + 横向滚动的关键词胶囊。 */
        private fun renderSearchHistory() {
            if (searchHistoryHost == null) return
            searchHistoryHost!!.removeAllViews()
            val hist = OnlineMusicPrefs.searchHistory()
            if (hist.isEmpty()) return

            val head = LinearLayout(ctx)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            val title = TextView(ctx)
            title.text = "搜索历史"
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            title.setTextColor(AppColors.text2())
            title.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            head.addView(title)
            head.addView(actionText("清空") {
                OnlineMusicPrefs.clearSearchHistory()
                renderSearchHistory()
            })
            searchHistoryHost!!.addView(head)

            val hs = HorizontalScrollView(ctx)
            hs.isHorizontalScrollBarEnabled = false
            val chips = LinearLayout(ctx)
            chips.orientation = LinearLayout.HORIZONTAL
            chips.setPadding(0, dp(6), 0, 0)
            for (kw in hist) {
                val chip = TextView(ctx)
                chip.text = kw
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                chip.setTextColor(AppColors.text1())
                chip.isSingleLine = true
                chip.setPadding(dp(12), dp(6), dp(12), dp(6))
                try {
                    val bg = GradientDrawable()
                    bg.setColor(AppColors.surfaceContainerHigh())
                    bg.cornerRadius = dp(AppColors.SHAPE_FULL_DP).toFloat()
                    chip.background = bg
                } catch (ignored: Throwable) {}
                val lp = LinearLayout.LayoutParams(-2, -2)
                lp.rightMargin = dp(8)
                chip.layoutParams = lp
                chip.setOnClickListener {
                    if (searchInput != null) searchInput!!.setText(kw)
                    searchKeyword = kw
                    OnlineMusicPrefs.addSearchHistory(kw)
                    renderSearchHistory()
                    doSearch(searchType)
                }
                chips.addView(chip)
            }
            hs.addView(chips)
            searchHistoryHost!!.addView(hs)
        }

        private fun doSearch(type: Int) {
            if (searchResultHost == null) return
            val kw = searchKeyword
            when (type) {
                0 -> loadList(searchResultHost!!, Callable { KuwoMusicApi.search(kw, 1) }, this::renderSongs)
                1 -> loadList(searchResultHost!!, Callable { KuwoMusicApi.searchAlbums(kw, 1) }, this::renderAlbums)
                2 -> loadList(searchResultHost!!, Callable { KuwoMusicApi.searchArtists(kw, 1) }, this::renderArtists)
                else -> loadList(searchResultHost!!, Callable { KuwoMusicApi.searchPlaylists(kw, 1) }, this::renderPlaylists)
            }
        }

        /** 关闭搜索窗口（进入详情/播放前调用，避免遮罩残留）。 */
        private fun dismissSearchWindow() {
            try {
                if (searchDialog != null && searchDialog!!.isShowing) searchDialog!!.dismiss()
            } catch (ignored: Throwable) {}
            searchDialog = null
            searchInput = null
            searchHistoryHost = null
            searchResultHost = null
        }

        // ==================== 榜单 ====================

        private fun buildCharts() {
            content.addView(M3Page.section(ctx, "排行榜"))
            val host = LinearLayout(ctx)
            host.orientation = LinearLayout.VERTICAL
            content.addView(host)
            loadList(host, Callable { KuwoMusicApi.chartGroups() }) { h, groups ->
                for (g in groups) {
                    h.addView(M3Page.section(ctx, g.title))
                    val card = M3Page.card(ctx)
                    for (i in g.charts.indices) {
                        if (i > 0) card.addView(M3Page.divider(ctx))
                        val c = g.charts[i]
                        card.addView(M3Page.clickRow(ctx, "\uD83C\uDFC6", c.title, c.description) { openChart(c) })
                    }
                    h.addView(card)
                }
            }
        }

        private fun openChart(c: KuwoMusicApi.Chart) {
            showDetail(c.title ?: "") { host ->
                loadList(host, Callable { KuwoMusicApi.chartSongs(c.id) }) { h, data -> renderSongs(h, data) }
            }
        }

        // ==================== 歌单 ====================

        private fun buildSheets() {
            content.addView(M3Page.section(ctx, "推荐歌单"))
            val tagHost = LinearLayout(ctx)
            tagHost.orientation = LinearLayout.VERTICAL
            content.addView(tagHost)
            val listHost = LinearLayout(ctx)
            listHost.orientation = LinearLayout.VERTICAL
            content.addView(listHost)

            tagHost.addView(M3Page.note(ctx, "加载标签中…"))
            bg {
                try {
                    val tags = KuwoMusicApi.recommendTags()
                    ui {
                        tagHost.removeAllViews()
                        buildTagBar(tagHost, listHost, tags)
                    }
                } catch (t: Throwable) {
                    ui {
                        tagHost.removeAllViews()
                        tagHost.addView(M3Page.note(ctx, "标签加载失败：" + msg(t)))
                    }
                }
            }
        }

        private fun buildTagBar(tagHost: LinearLayout, listHost: LinearLayout, tags: List<KuwoMusicApi.Tag>) {
            if (tags.isEmpty()) {
                loadPlaylists(listHost, "")
                return
            }
            val n = Math.min(5, tags.size)
            val labels = Array(n) { tags[it].title ?: "" }
            val seg = SegmentedControl(ctx, labels, 0)
            seg.setOnSegmentChangedListener(object : SegmentedControl.OnSegmentChangedListener {
                override fun onChanged(index: Int, label: String) { loadPlaylists(listHost, tags[index].id) }
            })
            tagHost.addView(seg)
            tagHost.addView(M3Page.spacer(ctx, 6f))
            tagHost.addView(M3Page.clickRow(ctx, "\u2630", "更多标签", "浏览全部音乐分类") {
                val all = Array(tags.size) { tags[it].title ?: "" }
                AlertDialog.Builder(ctx)
                        .setTitle("选择标签")
                        .setItems(all) { dlg, which ->
                            loadPlaylists(listHost, tags[which].id)
                            M3Page.toast(ctx, "已选择：" + tags[which].title)
                        }
                        .show()
            })
            tagHost.addView(M3Page.spacer(ctx, 6f))
            loadPlaylists(listHost, tags[0].id)
        }

        private fun loadPlaylists(host: LinearLayout, tagId: String?) {
            loadList(host, Callable { KuwoMusicApi.recommendPlaylists(tagId, 1) }, this::renderPlaylists)
        }

        // ==================== 导入 ====================

        private fun buildImport() {
            content.addView(M3Page.section(ctx, "导入歌单"))
            val card = M3Page.card(ctx)
            val et = M3Page.input(ctx, "粘贴酷我歌单链接或纯数字 ID")
            card.addView(et)
            card.addView(M3Page.spacer(ctx, 8f))
            val host = LinearLayout(ctx)
            host.orientation = LinearLayout.VERTICAL
            card.addView(M3Page.button(ctx, "导 入") {
                val id = KuwoMusicApi.parsePlaylistId(et.text.toString())
                if (id.isNullOrEmpty()) {
                    M3Page.toast(ctx, "无法识别歌单 ID")
                    return@button
                }
                loadList(host, Callable { KuwoMusicApi.playlistSongs(id, 1, 200) }) { h, data ->
                    h.addView(M3Page.note(ctx, "共导入 " + data.size + " 首（歌单 " + id + "）"))
                    renderSongs(h, data)
                }
            })
            content.addView(card)
            content.addView(M3Page.note(ctx,
                    "支持 www.kuwo.cn/playlist_detail/{id} 或 m.kuwo.cn/h5app/playlist/{id}，也可直接填数字 ID"))
            content.addView(host)
        }

        // ==================== 列表渲染 ====================

        private fun renderSongs(host: LinearLayout, data: List<KuwoMusicApi.Song>) {
            rowPlayIcons.clear()
            val card = M3Page.card(ctx)
            for (i in data.indices) {
                if (i > 0) card.addView(M3Page.divider(ctx))
                card.addView(songRow(data[i], data))
            }
            host.addView(card)
            refreshRowPlayIcons()
        }

        private fun songRow(s: KuwoMusicApi.Song, list: List<KuwoMusicApi.Song>): View {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(14), dp(8), dp(6), dp(8))
            row.background = CandyUi.rowPressBg(ctx)

            val thumbBox = baseCover(44, 20)
            val thumb = addCoverImage(thumbBox)
            bindRowCover(s, thumb)
            val thumbLp = LinearLayout.LayoutParams(dp(44), dp(44))
            thumbLp.rightMargin = dp(10)
            row.addView(thumbBox, thumbLp)

            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            col.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)

            val title = TextView(ctx)
            title.text = s.title ?: ""
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            title.typeface = Typeface.DEFAULT_BOLD
            title.setTextColor(AppColors.text1())
            title.isSingleLine = true
            title.ellipsize = TextUtils.TruncateAt.END
            col.addView(title)

            var sub = s.artist ?: ""
            if (!s.album.isNullOrEmpty()) sub += " · " + s.album
            val subTv = TextView(ctx)
            subTv.text = sub
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            subTv.setTextColor(AppColors.text2())
            subTv.isSingleLine = true
            subTv.ellipsize = TextUtils.TruncateAt.END
            col.addView(subTv)
            row.addView(col)

            val playIc = icon(MusicIconView.PLAY, "播放") { onRowPlay(s, list) }
            rowPlayIcons.add(RowPlay(s.id, playIc))
            row.addView(playIc)
            row.addView(icon(MusicIconView.DL, "下载") { download(s) })
            row.addView(icon(MusicIconView.SEND, "发送") { sendToTargets(s) })
            row.setOnClickListener { onSongTap(s, list) }
            return row
        }

        /** 歌曲行播放按钮：当前曲目则切换播放/暂停，否则开始播放该曲。 */
        private fun onRowPlay(s: KuwoMusicApi.Song, list: List<KuwoMusicApi.Song>) {
            if (current != null && !s.id.isNullOrEmpty() && s.id == current!!.id && player != null) {
                togglePlay()
            } else {
                onSongTap(s, list)
            }
        }

        /** 同步歌曲行播放按钮：当前曲目显示暂停/播放态，其余显示播放态。 */
        private fun refreshRowPlayIcons() {
            if (rowPlayIcons.isEmpty()) return
            for (rp in rowPlayIcons) {
                val isCur = current != null && rp.id != null && rp.id == current!!.id
                val playing = isCur && !paused
                rp.icon.setIcon(if (playing) MusicIconView.PAUSE else MusicIconView.PLAY)
            }
        }

        private fun renderAlbums(host: LinearLayout, data: List<KuwoMusicApi.Album>) {
            val card = M3Page.card(ctx)
            for (i in data.indices) {
                if (i > 0) card.addView(M3Page.divider(ctx))
                val a = data[i]
                val sub = StringBuilder()
                if (!a.artist.isNullOrEmpty()) sub.append(a.artist)
                if (!a.date.isNullOrEmpty()) {
                    if (sub.length > 0) sub.append(" · ")
                    sub.append(a.date)
                }
                card.addView(M3Page.clickRow(ctx, "\uD83D\uDCBF", a.title,
                        if (sub.length == 0) null else sub.toString()) { openAlbum(a) })
            }
            host.addView(card)
        }

        private fun renderArtists(host: LinearLayout, data: List<KuwoMusicApi.Artist>) {
            val card = M3Page.card(ctx)
            for (i in data.indices) {
                if (i > 0) card.addView(M3Page.divider(ctx))
                val a = data[i]
                val sub = if (a.worksNum > 0) (a.worksNum.toString() + " 首作品") else a.description
                card.addView(M3Page.clickRow(ctx, "\uD83C\uDFA4", a.name, sub) { openArtist(a) })
            }
            host.addView(card)
        }

        private fun renderPlaylists(host: LinearLayout, data: List<KuwoMusicApi.Playlist>) {
            val card = M3Page.card(ctx)
            for (i in data.indices) {
                if (i > 0) card.addView(M3Page.divider(ctx))
                val p = data[i]
                val sub = StringBuilder()
                if (!p.artist.isNullOrEmpty()) sub.append(p.artist)
                if (p.playCount > 0) {
                    if (sub.length > 0) sub.append(" · ")
                    sub.append(fmtCount(p.playCount)).append(" 播放")
                }
                card.addView(M3Page.clickRow(ctx, "\uD83C\uDFB5", p.title,
                        if (sub.length == 0) null else sub.toString()) { openPlaylist(p) })
            }
            host.addView(card)
        }

        // ==================== 详情 ====================

        private fun showDetail(title: String, builder: DetailBuilder) {
            showDetail(title, tab, builder)
        }

        /** returnTab: 详情返回目标, -1=返回「我的」, >=0=返回对应首页标签。 */
        private fun showDetail(title: String, returnTab: Int, builder: DetailBuilder) {
            dismissSearchWindow()
            showingDetail = true
            detailReturnTab = returnTab
            content.removeAllViews()
            val back = TextView(ctx)
            back.text = "← 返回"
            back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            back.setTextColor(AppColors.primary())
            back.typeface = Typeface.DEFAULT_BOLD
            back.setPadding(dp(2), dp(4), dp(2), dp(8))
            back.setOnClickListener { backFromDetail() }
            content.addView(back)
            content.addView(M3Page.section(ctx, title))
            val host = LinearLayout(ctx)
            host.orientation = LinearLayout.VERTICAL
            content.addView(host)
            builder.build(host)
        }

        private fun openAlbum(a: KuwoMusicApi.Album) {
            showDetail(a.title ?: "") { host ->
                if (!a.artist.isNullOrEmpty()) host.addView(M3Page.note(ctx, a.artist))
                loadList(host, Callable { KuwoMusicApi.albumSongs(a.id) }) { h, data -> renderSongs(h, data) }
            }
        }

        private fun openArtist(a: KuwoMusicApi.Artist) {
            showDetail(a.name ?: "") { host ->
                if (!a.description.isNullOrEmpty()) {
                    host.addView(M3Page.note(ctx, a.description))
                }
                host.addView(M3Page.section(ctx, "代表作品"))
                val songHost = LinearLayout(ctx)
                songHost.orientation = LinearLayout.VERTICAL
                host.addView(songHost)
                loadList(songHost, Callable { KuwoMusicApi.artistSongs(a.id, 1) }) { h, data -> renderSongs(h, data) }
            }
        }

        private fun openPlaylist(p: KuwoMusicApi.Playlist) {
            showDetail(p.title ?: "") { host ->
                if (!p.description.isNullOrEmpty()) {
                    host.addView(M3Page.note(ctx, p.description))
                }
                loadList(host, Callable { KuwoMusicApi.playlistSongs(p.id, 1, 200) }) { h, data -> renderSongs(h, data) }
            }
        }

        // ==================== 点歌设置 ====================

        private fun openSettings() {
            showDetail("点歌设置", -1) { host ->
                val card = M3Page.card(ctx)
                card.addView(M3Page.switchRow(ctx, "\uD83C\uDFA7", "启用点歌",
                        "聊天窗口发送「点歌 歌名」自动取最高音质转为语音消息",
                        OnlineMusicPrefs.enabled()) { _, on -> OnlineMusicPrefs.setEnabled(on) })
                card.addView(M3Page.divider(ctx))
                card.addView(M3Page.switchRow(ctx, "\uD83D\uDCAC", "发送提示语",
                        "处理前在会话内发送提示语（内容可在下方自定义）",
                        OnlineMusicPrefs.notice()) { _, on -> OnlineMusicPrefs.setNotice(on) })
                card.addView(M3Page.divider(ctx))
                card.addView(M3Page.switchRow(ctx, "\u2B07", "自动下载",
                        "点歌后将音频保存到本地",
                        OnlineMusicPrefs.autoDownload()) { _, on -> OnlineMusicPrefs.setAutoDownload(on) })
                card.addView(M3Page.divider(ctx))
                card.addView(M3Page.switchRow(ctx, "\uD83D\uDD00", "进入自动随机播放",
                        "打开在线音乐即随机播放一首（优先上次队列/默认榜单）",
                        OnlineMusicPrefs.autoRandom()) { _, on -> OnlineMusicPrefs.setAutoRandom(on) })
                host.addView(card)

                host.addView(M3Page.section(ctx, "生效范围"))
                val wlCard = M3Page.card(ctx)
                wlCard.addView(M3Page.clickRow(ctx, "\uD83D\uDC65", "点歌白名单",
                        "仅白名单会话内生效；为空时不触发（已选 " +
                                OnlineMusicPrefs.whitelist().size + " 个）") {
                    val cur = OnlineMusicPrefs.whitelist().joinToString(",")
                    ContactPickerDialog.show(act, cur,
                            ContactPickerDialog.MODE_FRIEND) { selected, display ->
                        OnlineMusicPrefs.setWhitelist(selected.joinToString(","))
                        M3Page.toast(ctx, "已保存 " + selected.size + " 个会话")
                        openSettings()
                    }
                })
                host.addView(wlCard)

                host.addView(M3Page.section(ctx, "指令与参数"))
                val card2 = M3Page.card(ctx)
                card2.addView(inputSaveRow("触发别名", "逗号分隔，如：点歌,点唱,来一首",
                        OnlineMusicPrefs.aliases().joinToString(","), true) { v ->
                    OnlineMusicPrefs.setAliases(v)
                })
                card2.addView(M3Page.divider(ctx))
                card2.addView(inputSaveRow("误报时长（秒）", "超过 60 秒的语音按此秒数上报，范围 1-60",
                        OnlineMusicPrefs.falseDurSec().toString(), false) { v ->
                    try { OnlineMusicPrefs.setFalseDurSec(v.trim().toInt()) }
                    catch (ignored: Throwable) { M3Page.toast(ctx, "请输入 1-60 的数字") }
                })
                card2.addView(M3Page.divider(ctx))
                card2.addView(inputSaveRow("提示语内容", "点歌处理前发送的文字；{song} 代表歌名",
                        OnlineMusicPrefs.noticeText(), true) { v ->
                    OnlineMusicPrefs.setNoticeText(v)
                })
                host.addView(card2)

                host.addView(M3Page.section(ctx, "音质"))
                val q = OnlineMusicPrefs.quality()
                val qIdx = qualityIndex(q)
                val qSeg = SegmentedControl(ctx, Q_LABELS, qIdx)
                qSeg.setOnSegmentChangedListener(object : SegmentedControl.OnSegmentChangedListener {
                    override fun onChanged(index: Int, label: String) {
                        OnlineMusicPrefs.setQuality(Q_VALS[index])
                        M3Page.toast(ctx, "音质：" + label)
                    }
                })
                host.addView(qSeg)
                host.addView(M3Page.note(ctx, "“自动”优先取最高音质，不可用时自动降档；点歌与在线试听均按此设置。"))
            }
        }

        private fun inputSaveRow(title: String, desc: String, value: String, text: Boolean, cb: ValueCallback): View {
            val card = LinearLayout(ctx)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(14), dp(12), dp(14), dp(12))
            card.setBackgroundColor(AppColors.whiteCard())

            val tv = TextView(ctx)
            tv.text = title
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.setTextColor(AppColors.text1())
            card.addView(tv)

            if (!desc.isEmpty()) {
                val dv = TextView(ctx)
                dv.text = desc
                dv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                dv.setTextColor(AppColors.text2())
                dv.setPadding(0, dp(3), 0, 0)
                card.addView(dv)
            }

            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(6), 0, 0)

            val et = EditText(ctx)
            et.setText(value)
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            et.isSingleLine = true
            et.setHorizontallyScrolling(true)
            et.setTextColor(AppColors.text1())
            et.inputType = if (text) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_NUMBER
            et.background = CandyUi.inputBg(ctx)
            et.setPadding(dp(12), dp(9), dp(12), dp(9))
            et.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            // v1105: 修复点击输入框不弹键盘 —— 显式允许软键盘、聚焦后强制唤起 IME
            et.isFocusable = true
            et.isFocusableInTouchMode = true
            et.showSoftInputOnFocus = true
            et.setOnClickListener { et.requestFocus(); showKeyboard(et) }
            et.setOnTouchListener { v, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    et.requestFocus()
                    showKeyboard(et)
                }
                false
            }
            et.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showKeyboard(et) }
            // 进入点歌设置即让宿主弹窗支持 IME 缩放(避免键盘被输入框挡住/不弹出)
            ensureImeResize()
            row.addView(et)

            val save = actionText("保存") {
                cb.onValue(et.text.toString())
                M3Page.toastSuccess(ctx, "已保存")
            }
            save.setPadding(dp(12), dp(9), dp(4), dp(9))
            row.addView(save)
            card.addView(row)
            return card
        }

        /** v1105: 强制唤起软键盘(修复点歌设置输入框点击无键盘)。 */
        private fun showKeyboard(v: View) {
            if (v == null) return
            v.postDelayed({
                try {
                    if (v is EditText) v.requestFocusFromTouch()
                    else v.requestFocus()
                    SubPageActivity.ensureImeVisible()
                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    if (imm != null) {
                        imm.showSoftInput(v, 0)
                        if (!imm.isActive(v)) {
                            imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
                        }
                    }
                } catch (ignored: Throwable) {
                }
            }, 80L)
        }

        /** v1105: 让承载本页的宿主 AlertDialog 窗口支持软键盘缩放。 */
        private fun ensureImeResize() {
            try {
                SubPageActivity.ensureImeResize()
            } catch (ignored: Throwable) {
            }
        }

        // ==================== 下载 / 发送 ====================

        private fun download(s: KuwoMusicApi.Song) {
            M3Page.toast(ctx, "开始下载：" + s.title)
            showDownloadProgress()
            bg {
                try {
                    val level = effectiveLevel(OnlineMusicPrefs.quality())
                    val url = KuwoMusicApi.resolveFinalUrl(KuwoMusicApi.streamUrl(s.id, level))
                    val dir = downloadDir()
                    if (!dir.exists()) dir.mkdirs()
                    val ext = if (KuwoMusicApi.Q_FLAC == level) ".flac" else ".mp3"
                    val safe = (s.title + (if (s.artist.isNullOrEmpty()) "" else " - " + s.artist)).replace("[\\\\/:*?\"<>|]".toRegex(), "_")
                    val out = File(dir, safe + ext)
                    KuwoMusicApi.download(url, out, object : KuwoMusicApi.Progress {
                        override fun onProgress(current: Long, total: Long) {
                            if (total > 0) {
                                val p = Math.min(100L, current * 100L / total).toInt()
                                ui { if (downloadBar != null) downloadBar!!.setProgress(p) }
                            }
                        }
                        override fun isCancelled(): Boolean = false
                    })
                    ui {
                        dismissDownloadProgress()
                        M3Page.toastSuccess(ctx, "已保存：" + out.name)
                    }
                } catch (t: Throwable) {
                    ui {
                        dismissDownloadProgress()
                        M3Page.toastError(ctx, "下载失败：" + msg(t))
                    }
                }
            }
        }

        /** 下载进度弹窗（复用「转码中」同款流光飞鸟进度条）。 */
        private fun showDownloadProgress() {
            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.gravity = Gravity.CENTER
            root.setPadding(dp(22), dp(18), dp(22), dp(16))

            val label = TextView(ctx)
            label.text = "下载中…"
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            label.typeface = Typeface.DEFAULT_BOLD
            label.setTextColor(AppColors.text1())
            label.gravity = Gravity.CENTER
            label.setPadding(0, 0, 0, dp(10))
            root.addView(label)

            val bar = FlyingProgressBar(ctx)
            root.addView(bar, LinearLayout.LayoutParams(-1, -2))
            downloadBar = bar

            val pct = TextView(ctx)
            pct.text = "0%"
            pct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            pct.typeface = Typeface.DEFAULT_BOLD
            pct.gravity = Gravity.CENTER
            pct.setPadding(0, dp(8), 0, 0)
            GradientText.apply(pct)
            root.addView(pct)
            downloadPct = pct

            bar.setProgressListener(object : FlyingProgressBar.ProgressListener {
                override fun onDisplay(percent: Int) { if (downloadPct != null) downloadPct!!.text = "$percent%" }
            })

            try {
                downloadDialog = AlertDialog.Builder(ctx)
                        .setTitle("下载音频")
                        .setView(root)
                        .setCancelable(false)
                        .create()
                downloadDialog!!.show()
            } catch (ignored: Throwable) {}
        }

        private fun dismissDownloadProgress() {
            try {
                if (downloadDialog != null && downloadDialog!!.isShowing) downloadDialog!!.dismiss()
            } catch (ignored: Throwable) {}
            downloadDialog = null
            downloadBar = null
            downloadPct = null
        }

        private fun sendToTargets(s: KuwoMusicApi.Song) {
            val cl = ContextManager.getClassLoader()
            if (cl == null) {
                M3Page.toastError(ctx, "模块未初始化，无法发送")
                return
            }
            ContactPickerDialog.show(act, "", ContactPickerDialog.MODE_FRIEND) { selected, display ->
                if (selected.isEmpty()) return@show
                DianGeService.sendSongToTargets(cl, s, ArrayList(selected))
                M3Page.toast(ctx, "正在发送到 " + selected.size + " 个会话…")
            }
        }

        // ==================== 通用 ====================

        private fun <T> loadList(host: LinearLayout, task: Callable<List<T>>, render: ListRenderer<T>) {
            host.removeAllViews()
            host.addView(M3Page.note(ctx, "加载中…"))
            bg {
                try {
                    val data = task.call()
                    ui {
                        host.removeAllViews()
                        if (data == null || data.isEmpty()) {
                            host.addView(M3Page.empty(ctx, "\uD83C\uDFB5", "暂无结果"))
                            return@ui
                        }
                        render.render(host, data)
                    }
                } catch (t: Throwable) {
                    ui {
                        host.removeAllViews()
                        host.addView(M3Page.note(ctx, "加载失败：" + msg(t)))
                    }
                }
            }
        }

        private fun icon(name: String, desc: String, cb: Runnable): MusicIconView {
            val ic = MusicIconView(ctx, name)
            ic.setSolid(true)
            ic.setIconSizeDp(28)
            ic.setPadding(dp(10), dp(10), dp(10), dp(10))
            ic.contentDescription = desc
            ic.setOnClickListener {
                try { cb.run() } catch (ignored: Throwable) {}
            }
            return ic
        }

        private fun actionText(label: String, cb: Runnable): TextView {
            val tv = TextView(ctx)
            tv.text = label
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.setTextColor(AppColors.primary())
            tv.gravity = Gravity.CENTER
            tv.setPadding(dp(8), dp(6), dp(8), dp(6))
            tv.setOnClickListener {
                try { cb.run() } catch (ignored: Throwable) {}
            }
            return tv
        }

        private fun fmtCount(n: Long): String {
            if (n >= 100000000L) return String.format(java.util.Locale.CHINA, "%.1f亿", n / 100000000.0)
            if (n >= 10000L) return String.format(java.util.Locale.CHINA, "%.1f万", n / 10000.0)
            return n.toString()
        }

        private fun msg(t: Throwable): String {
            val m = t.message
            return if (m.isNullOrEmpty()) t.toString() else m
        }

        private fun bg(r: Runnable) {
            Thread(r, "OnlineMusic").start()
        }

        private fun ui(r: Runnable) {
            // v1105: 页面关闭后仍需在后台推进播放(下一首), 统一投递到主线程, 不再依赖 Activity
            if (Looper.myLooper() == Looper.getMainLooper()) {
                r.run()
            } else {
                Handler(Looper.getMainLooper()).post(r)
            }
        }

        private fun dp(v: Float): Int {
            return (v * d + 0.5f).toInt()
        }

        private fun dp(v: Int): Int {
            return dp(v.toFloat())
        }
    }
}