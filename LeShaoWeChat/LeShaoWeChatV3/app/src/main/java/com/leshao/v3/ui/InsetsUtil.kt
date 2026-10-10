package com.leshao.v3.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 系统栏安全区统一工具（v987）。
 *
 * 以 [AiAssistantPanel] 原有的 inset 读取逻辑为样板提取：
 * 顶部优先取根窗口 WindowInsets，失败回退 `status_bar_height` 资源（再回退 24dp）；
 * 底部优先取根窗口 WindowInsets，失败回退 `navigation_bar_height` 资源（再回退 48dp）。
 *
 * 页面 / 弹窗 / PopupWindow 统一调用本工具，避免内容落在状态栏或导航栏下方被裁切。
 */
object InsetsUtil {

    @JvmStatic
    fun dp(ctx: Context, v: Float): Int {
        return (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    }

    /** 状态栏高度(px)，取系统资源，失败回退 24dp。 */
    @JvmStatic
    fun statusBarHeight(ctx: Context): Int {
        try {
            val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) return ctx.resources.getDimensionPixelSize(id)
        } catch (ignored: Throwable) {}
        return dp(ctx, 24f)
    }

    /** 底部系统栏(导航栏/手势条)高度(px)，取系统资源，失败回退 48dp。 */
    @JvmStatic
    fun navigationBarHeight(ctx: Context): Int {
        try {
            val id = ctx.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (id > 0) {
                val h = ctx.resources.getDimensionPixelSize(id)
                if (h > 0) return h
            }
        } catch (ignored: Throwable) {}
        return dp(ctx, 48f)
    }

    /** 顶部安全区(px)：优先根窗口 WindowInsets，回退状态栏资源高度。 */
    @JvmStatic
    fun topInset(ctx: Context, anchor: View?): Int {
        try {
            if (anchor != null && anchor.isAttachedToWindow) {
                val wi = ViewCompat.getRootWindowInsets(anchor)
                if (wi != null) {
                    val t = wi.getInsets(WindowInsetsCompat.Type.statusBars()).top
                    if (t > 0) return t
                }
            }
        } catch (ignored: Throwable) {}
        return statusBarHeight(ctx)
    }

    /** 底部安全区(px)：优先根窗口 WindowInsets，回退导航栏资源高度。 */
    @JvmStatic
    fun bottomInset(ctx: Context, anchor: View?): Int {
        try {
            if (anchor != null && anchor.isAttachedToWindow) {
                val wi = ViewCompat.getRootWindowInsets(anchor)
                if (wi != null) {
                    val b = wi.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                    if (b > 0) return b
                }
            }
        } catch (ignored: Throwable) {}
        return navigationBarHeight(ctx)
    }

    /**
     * 浮层窗口的顶部安全边距(px)：状态栏高度 + 6dp 呼吸位。
     */
    @JvmStatic
    fun topSafePad(ctx: Context): Int {
        return 0
    }

    /**
     * 浮层窗口的底部安全边距(px)：导航栏/手势条高度 + 6dp 呼吸位。
     * 手势机型导航栏薄、三键机型导航栏厚，均保证浮层控件不被系统栏遮挡，
     * 同时避免旧实现四边固定大留白造成的底部大片无效空白。
     */
    @JvmStatic
    fun bottomSafePad(ctx: Context): Int {
        return 0
    }

    /**
     * @deprecated 用 [topSafePad] / [bottomSafePad] 分别处理，
     * 避免三键机型把导航栏高度重复加到顶部。
     */
    @Deprecated("用 topSafePad / bottomSafePad 分别处理，避免三键机型把导航栏高度重复加到顶部")
    @JvmStatic
    fun vSafePad(ctx: Context): Int {
        return 0
    }

    /** 给已有视图追加顶部内边距（保留原内边距）。 */
    @JvmStatic
    fun padTop(v: View, extraPx: Int) {
    }

    /** 给已有视图追加底部内边距（保留原内边距）。 */
    @JvmStatic
    fun padBottom(v: View, extraPx: Int) {
    }

    /** 让圆角容器裁切子视图，避免内部直角背景从圆角边缘漏出。 */
    @JvmStatic
    fun clipRounded(v: View) {
        try {
            v.clipToOutline = true
        } catch (ignored: Throwable) {}
    }

    /** 将窗口背景设为透明（圆角容器外由透明窗口露出宿主，避免实底间隔）。 */
    @JvmStatic
    fun transparentWindow(w: Window?) {
        if (w == null) return
        try {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        } catch (ignored: Throwable) {}
        // v1015: 统一弹窗进出动画（淡入淡出+缩放），让弹窗/子窗口过渡更丝滑
        try {
            w.setWindowAnimations(android.R.style.Animation_Dialog)
        } catch (ignored: Throwable) {}
    }

    /**
     * 清空系统窗口与 DécorView 外壳背景（Activity 用），只保留内部 M3Page 圆角浮层。
     *
     * v988 关键修复：仅把 windowBackground 设为透明并不够——系统 DécorView 外壳本身
     * 仍带一层实白背景，会盖在圆角卡片四角外侧，导致视觉上看不到透明效果。
     * 必须调用 [setBackgroundDrawable]（null）并清空 DécorView 背景，
     * 同时把状态栏/导航栏底色置透明（否则状态栏区域仍是一段实色间隔）。
     */
    @JvmStatic
    fun clearWindowShell(w: Window?) {
        if (w == null) return
        try {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        } catch (ignored: Throwable) {}
        // v1015: 统一进出动画
        try {
            w.setWindowAnimations(android.R.style.Animation_Dialog)
        } catch (ignored: Throwable) {}
        try {
            val decor = w.decorView
            if (decor != null) decor.background = ColorDrawable(Color.TRANSPARENT)
        } catch (ignored: Throwable) {}
        // v3.0.269: 不再清零 dimAmount —— 层级压暗统一由 WindowLayer.applyScrim 管理，
        // 这里清零会覆盖多层弹窗的遮罩，导致堆叠时无法区分当前层。
        try {
            w.statusBarColor = Color.TRANSPARENT
        } catch (ignored: Throwable) {}
        try {
            w.navigationBarColor = Color.TRANSPARENT
        } catch (ignored: Throwable) {}
    }

    /** 清空 Activity 系统窗口外壳背景（window.setBackgroundDrawable(null) + DécorView）。 */
    @JvmStatic
    fun clearWindowShell(a: Activity?) {
        if (a == null) return
        try {
            clearWindowShell(a.window)
        } catch (ignored: Throwable) {}
    }

    /**
     * 清空 AlertDialog 系统外壳背景：窗口 + DécorView + alert 面板(parentPanel 等)。
     *
     * v988 关键修复：全屏 AlertDialog 的 parentPanel 由 dialog 主题提供实底背景，
     * 单改 windowBackground 无法清掉，白色外壳会压在圆角卡片四角与安全区外。
     * 另外 dialog 主题为非悬浮主题时窗口表面不透明，清空背景后会露出黑底，
     * 因此显式把窗口像素格式设为半透明，让圆角外侧透出宿主。
     */
    @JvmStatic
    fun clearDialogShell(d: Dialog?) {
        if (d == null) return
        var w: Window? = null
        try {
            w = d.window
        } catch (ignored: Throwable) {}
        clearWindowShell(w)
        try {
            if (w != null) {
                WindowCompat.setDecorFitsSystemWindows(w, false)
                w.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
                w.navigationBarDividerColor = Color.TRANSPARENT
            }
        } catch (ignored: Throwable) {}
        try {
            val decor = if (w == null) null else w.decorView
            val ctx = d.context
            if (decor != null && ctx != null) {
                val ids = arrayOf(
                    "parentPanel", "topPanel", "contentPanel",
                    "customPanel", "buttonPanel", "scrollView", "content"
                )
                for (name in ids) {
                    val id = ctx.resources.getIdentifier(name, "id", "android")
                    if (id == 0) continue
                    val v = decor.findViewById<View>(id)
                    if (v != null) v.background = null
                }
            }
        } catch (ignored: Throwable) {}
    }

    /** 将窗口背景设为透明（Dialog 重载，并清空 alert 面板实底外壳）。 */
    @JvmStatic
    fun transparentWindow(d: Dialog?) {
        clearDialogShell(d)
    }

    /** 将窗口背景设为透明（Activity 重载）。 */
    @JvmStatic
    fun transparentWindow(a: Activity?) {
        if (a == null) return
        try {
            clearWindowShell(a.window)
        } catch (ignored: Throwable) {}
    }

    /** 将视图设为统一圆角浮层底（surface + 28dp 圆角 + 裁切）。 */
    @JvmStatic
    fun sheet(v: View?) {
        if (v == null) return
        try {
            v.background = CandyUi.pageGradient()
            clipRounded(v)
        } catch (ignored: Throwable) {}
    }

    /**
     * 用透明外层容器包裹圆角浮层（强制层级改造）。
     *
     * 外层 [FrameLayout] 背景恒为透明，只负责布局；圆角渐变由内部
     * 传入的 sheet 绘制。这样圆角弧线以外（含屏幕四角、状态栏/导航栏侧边）
     * 100% 无颜料，直接露出宿主微信画面。
     */
    @JvmStatic
    fun host(sheet: View?): ViewGroup? {
        if (sheet == null) return null
        val outer = FrameLayout(sheet.context)
        outer.background = null
        outer.addView(sheet, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))
        return outer
    }

    /**
     * v998: 将页面/弹窗改造为「居中浮层窗口」。
     *
     * 不再全屏贴顶铺满：窗口仍为全屏透明，内容外层 [FrameLayout] 通过内边距留白，
     * 内部 sheet 以屏幕比例固定尺寸并 [Gravity.CENTER] 居中，配合
     * [CandyUi.pageGradient] 的描边与 [CandyUi.elevate] 的阴影，
     * 形成悬浮卡片观感。系统状态栏/导航栏区域露出宿主画面。
     *
     * @param d     目标弹窗（可为空，仅做容器包装时）
     * @param sheet 圆角浮层本体（页面根 / 弹窗根）
     * @param wRatio 宽度占屏比 0~1
     * @param hRatio 历史参数，不再使用（v1148 起高度一律随内容自适应）
     */
    @JvmStatic
    fun window(d: Dialog?, sheet: View, wRatio: Float, hRatio: Float): ViewGroup {
        // v1148: 大页面/窗口统一「宽度按屏比 + 高度随内容自适应（上限 90% 屏）」，
        // 内容过长时由调用方内部 ScrollView 承载滚动；不再固定窗口高度。
        return windowAutoHeight(d, sheet, wRatio)
    }

    /**
     * v1145: 按内容自适应高度的居中浮层窗口。
     *
     * 用于短内容子页面：窗口高度随内容 WRAP（上限约 90% 屏），内容不超过上限时
     * 弹窗紧贴内容高度，不再像固定比例窗口那样在内容下方留出大片空白；长内容仍由
     * 内部滚动承载。宽度仍按屏比固定。
     *
     * @param d       目标弹窗（可为空，仅做容器包装时）
     * @param sheet   圆角浮层本体（页面根 / 弹窗根）
     * @param wRatio  宽度占屏比 0~1
     */
    @JvmStatic
    fun windowAutoHeight(d: Dialog?, sheet: View, wRatio: Float): ViewGroup {
        val ctx = sheet.context
        val padH = dp(ctx, 6f)

        val outer = MaxHeightFrameLayout(ctx)
        outer.background = null
        outer.clipChildren = false
        outer.clipToPadding = false
        outer.setPadding(padH, 0, padH, 0)

        val dm = ctx.resources.displayMetrics
        val w = if (wRatio > 0) (dm.widthPixels * wRatio).toInt() else FrameLayout.LayoutParams.WRAP_CONTENT
        val maxH = Math.max(0, (dm.heightPixels * 0.9f).toInt())
        outer.setMaxHeight(maxH)

        CandyUi.elevate(sheet)
        val sheetLp = FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT)
        sheetLp.gravity = Gravity.CENTER
        outer.addView(sheet, sheetLp)
        if (d != null) centerAutoHeight(d, wRatio)
        return outer
    }

    /**
     * v998: 将弹窗窗口设为「居中浮层」。
     *
     * 关键点：窗口本身按屏幕比例固定尺寸并 [Gravity.CENTER] 居中（而非全屏 MATCH_PARENT），
     * 这样无论系统 decor / 内容 FrameLayout 如何布局，弹窗都稳定出现在屏幕正中间，
     * 不会贴顶。比例传入 &lt;=0 表示该方向 [ViewGroup.LayoutParams.WRAP_CONTENT]（用于短内容弹窗）。
     */
    @JvmStatic
    fun center(d: Dialog?, wRatio: Float, hRatio: Float) {
        if (d == null) return
        var win: Window? = null
        try { win = d.window } catch (ignored: Throwable) {}
        if (win == null) return
        val ctx = d.context
        val padH = dp(ctx, 6f)
        try { win.setGravity(Gravity.CENTER) } catch (ignored: Throwable) {}
        try {
            val dm = ctx.resources.displayMetrics
            val w = if (wRatio > 0) (dm.widthPixels * wRatio).toInt() + 2 * padH
            else ViewGroup.LayoutParams.WRAP_CONTENT
            var h = ViewGroup.LayoutParams.WRAP_CONTENT
            if (hRatio > 0) {
                // 全局规范: 窗口高度上限 90% 屏幕（与 window() 同步）。
                val ratio = Math.min(hRatio, 0.9f)
                val want = (dm.heightPixels * ratio).toInt()
                val avail = dm.heightPixels
                h = Math.min(want, avail)
            }
            win.setLayout(w, h)
        } catch (ignored: Throwable) {}
        clearDialogShell(d)
    }

    /**
     * v1145: 将弹窗窗口设为「居中 + 高度自适应」。
     *
     * 与 [windowAutoHeight] 配套：窗口宽度按屏比，
     * 高度为 [ViewGroup.LayoutParams.WRAP_CONTENT]（由内容决定），并居中显示。
     */
    @JvmStatic
    fun centerAutoHeight(d: Dialog?, wRatio: Float) {
        if (d == null) return
        var win: Window? = null
        try { win = d.window } catch (ignored: Throwable) {}
        if (win == null) return
        val ctx = d.context
        val padH = dp(ctx, 6f)
        try { win.setGravity(Gravity.CENTER) } catch (ignored: Throwable) {}
        try {
            val dm = ctx.resources.displayMetrics
            val w = if (wRatio > 0) (dm.widthPixels * wRatio).toInt() + 2 * padH
            else ViewGroup.LayoutParams.WRAP_CONTENT
            win.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        } catch (ignored: Throwable) {}
        clearDialogShell(d)
    }

    /**
     * 将子视图包装为「高度随内容自适应 + 上限 90% 屏」的容器。
     *
     * 用于「可滚动内容区 + 固定底部栏」的布局：内容区以 [ViewGroup.LayoutParams.WRAP_CONTENT]
     * 方式参与父容器测量，内容超长时由内部 ScrollView 自行滚动，
     * 整体窗口高度不会超过 90% 屏，底部不留白。
     */
    @JvmStatic
    fun maxHeight90(ctx: Context, child: View): ViewGroup {
        val outer = MaxHeightFrameLayout(ctx)
        outer.background = null
        outer.clipChildren = false
        outer.clipToPadding = false
        val dm = ctx.resources.displayMetrics
        val maxH = Math.max(0, (dm.heightPixels * 0.9f).toInt())
        outer.setMaxHeight(maxH)
        outer.addView(child, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT))
        return outer
    }

    /** 高度上限容器：子视图按内容自适应，但整体高度不超过给定上限。 */
    private class MaxHeightFrameLayout(ctx: Context) : FrameLayout(ctx) {
        private var mMaxHeight = 0

        fun setMaxHeight(maxHeightPx: Int) {
            mMaxHeight = maxHeightPx
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            var heightSpec = heightMeasureSpec
            if (mMaxHeight > 0) {
                val mode = View.MeasureSpec.getMode(heightMeasureSpec)
                val size = View.MeasureSpec.getSize(heightMeasureSpec)
                if (mode == View.MeasureSpec.UNSPECIFIED || size > mMaxHeight) {
                    heightSpec = View.MeasureSpec.makeMeasureSpec(
                        mMaxHeight, View.MeasureSpec.AT_MOST)
                }
            }
            super.onMeasure(widthMeasureSpec, heightSpec)
        }
    }

    /** v998: 仅设置窗口居中重力并清理外壳（尺寸由调用方或内容自适应）。 */
    @JvmStatic
    fun center(d: Dialog?) {
        if (d == null) return
        var win: Window? = null
        try { win = d.window } catch (ignored: Throwable) {}
        if (win == null) return
        try { win.setGravity(Gravity.CENTER) } catch (ignored: Throwable) {}
        clearDialogShell(d)
    }

    /** Activity 内容边到边 + 自动按系统栏追加内边距（setContentView 之后调用）。 */
    @JvmStatic
    fun applyActivityInsets(activity: Activity?, content: View?) {
        if (activity == null || content == null) return
        var w: Window? = null
        try {
            w = activity.window
        } catch (ignored: Throwable) {}
        install(w, content)
    }

    /** Dialog 内容边到边 + 自动按系统栏追加内边距（create 之后、show 前后皆可）。 */
    @JvmStatic
    fun applyDialogInsets(dialog: Dialog?, content: View?) {
        if (dialog == null || content == null) return
        var w: Window? = null
        try {
            w = dialog.window
        } catch (ignored: Throwable) {}
        install(w, content)
    }

    /**
     * 边到边 + 将系统栏安全区转为内容视图的*外边距*（而非内边距）。
     *
     * v1148 去系统栏预留：仅保留边到边（内容可延伸到系统栏区域），
     * 不再给内容追加状态栏/导航栏外边距，模块所有窗口统一不预留系统栏空白。
     */
    private fun install(window: Window?, content: View?) {
        if (window == null || content == null) return
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        } catch (ignored: Throwable) {}
    }
}