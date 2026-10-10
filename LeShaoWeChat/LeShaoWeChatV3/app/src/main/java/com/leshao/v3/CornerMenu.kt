/**
 * ============================================================
 * 左上角三横菜单 — 微信 Xposed 模块 (WindowManager 悬浮窗注入)
 * ============================================================
 * 依赖: 仅 de.robv.android.xposed (无第三方)
 * 兼容: Java 8+ / API 24+
 *
 * v1117: 回到 v912 的注入方式(实机验证可用), 放弃 v1109 起的 DecorView 子 View 方案。
 *   - 注入: WindowManager.addView(TYPE_APPLICATION_PANEL), Gravity.TOP|START,
 *           x=6dp, y=状态栏高度+5dp, 恰好落在微信主页标题栏内左侧。
 *           不再读取主题 actionBarSize(该属性在微信下是失真值 12289, 曾把图标顶出屏幕)。
 *   - 显隐: 仅在 LauncherUI(主页) 显示; 进入聊天页隐藏。
 *           聊天判定改为「聊天输入框 MMEditText 是否真正显示在屏幕上」——
 *           进入聊天 MMEditText 可见 → 隐藏; 返回主页 MMEditText 不再可见 → 立即恢复。
 *           旧的 ChattingUIFragment.getView().isShown() 在返回主页后仍为 true, 是
 *           "进入聊天再返回后消失" 的根因, 已弃用。
 *   - 信号: Activity.onResume/onWindowFocusChanged/onPause/onDestroy(仓库已验证触发)
 *           + View.onAttachedToWindow/onDetachedFromWindow(MMEditText, 仓库已验证触发)
 *           + 300ms 兜底轮询(仅在主页 Activity 处于前台时运行)。
 *
 * 保留: 文档符号仅用于 diagSymbols() 诊断打印, 不参与任何判定。
 * 保留: 糖果粉图标样式、wm_prefs.corner_menu 开关、点击弹出的快捷菜单。
 * ============================================================
 */
package com.leshao.v3

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView

import com.leshao.v3.hook.VersionCompat
import com.leshao.v3.ui.AppColors

import java.lang.ref.WeakReference
import java.lang.reflect.Method

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class CornerMenu {
    companion object {

        private const val TAG = "CornerMenu"
        private const val VIEW_TAG = "LESHAO_HAM_V2"

        private const val LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI"
        private const val CHAT_EDIT_TEXT = "com.tencent.mm.ui.widget.MMEditText"

        /** 兜底轮询间隔(仅在主页 Activity 前台时运行) */
        private const val POLL_MS = 300L
        private const val MAX_RETRY = 10
        private const val RETRY_DELAY_MS = 200L

        // ── 文档符号(仅 diagSymbols 诊断用, 不参与判定) ──
        private const val F_HOME_UI = "i"
        private const val F_CHATTING_TAB = "chattingTabUI"
        private const val M_GET_TAB_UI = "getMainTabUI"
        private const val F_TAB_INDEX = "e"
        private const val M_GET_CUR_FRAG = "g"
        private const val M_CHAT_FOREGROUND = "m"

        private var sClassLoader: ClassLoader? = null
        private var sBitmapLight: Bitmap? = null
        private var sBitmapDark: Bitmap? = null
        private val sH = Handler(Looper.getMainLooper())

        /** 悬浮窗状态(全局一份, 只属于当前前台 LauncherUI) */
        @Volatile
        private var sIcon: View? = null
        @Volatile
        private var sWM: WindowManager? = null
        @Volatile
        private var sHost: WeakReference<Activity> = WeakReference(null)
        @Volatile
        private var sPolling = false

        private fun dp(ctx: Context, dp: Float): Int {
            return (dp * ctx.resources.displayMetrics.density + 0.5f).toInt()
        }

        private fun dp(ctx: Context, dp: Int): Int {
            return dp(ctx, dp.toFloat())
        }

        /**
         * 入口方法 — 在 handleLoadPackage 中调用
         */
        @JvmStatic
        fun hook(cl: ClassLoader) {
            try {
                val tkCL = VersionCompat.findTinkerClassLoader(cl)
                sClassLoader = tkCL ?: cl
                LogWriter.log(TAG, "hook: start (WindowManager overlay)")
                createBitmaps()

                val launcher = findClass(LAUNCHER_UI, sClassLoader)
                if (launcher == null) {
                    LogWriter.log(TAG, "hook: 找不到 " + LAUNCHER_UI + ", 请确认微信版本")
                    return
                }

                // ── Activity 生命周期(本仓库 MainHook/WmEntry 已验证 hookAllMethods(Activity) 必定触发) ──
                XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            if (p.thisObject !is Activity) return
                            val a = p.thisObject as Activity
                            if (a.javaClass.name != LAUNCHER_UI) return
                            LogWriter.log(TAG, "onResume LauncherUI -> pump")
                            onHomeResumed(a)
                        } catch (ignored: Throwable) {
                        }
                    }
                })
                XposedBridge.hookAllMethods(Activity::class.java, "onWindowFocusChanged", object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            if (p.thisObject !is Activity) return
                            val a = p.thisObject as Activity
                            if (a.javaClass.name != LAUNCHER_UI) return
                            val focused = p.args.isNotEmpty() && java.lang.Boolean.TRUE == p.args[0]
                            if (focused) onHomeResumed(a)
                        } catch (ignored: Throwable) {
                        }
                    }
                })
                XposedBridge.hookAllMethods(Activity::class.java, "onPause", object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            if (p.thisObject !is Activity) return
                            val a = p.thisObject as Activity
                            if (a.javaClass.name != LAUNCHER_UI) return
                            // 主页暂停(切到设置/朋友圈/离开微信等) → 摘除悬浮窗并停轮询
                            stopPoll()
                            removeOverlay()
                        } catch (ignored: Throwable) {
                        }
                    }
                })
                XposedBridge.hookAllMethods(Activity::class.java, "onDestroy", object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            if (p.thisObject !is Activity) return
                            val a = p.thisObject as Activity
                            if (a.javaClass.name != LAUNCHER_UI) return
                            stopPoll()
                            removeOverlay()
                        } catch (ignored: Throwable) {
                        }
                    }
                })

                // ── 聊天进出即时信号(本仓库 WmEntry 已验证 MMEditText attach/detach 可靠) ──
                XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        if (p.thisObject is View
                                && CHAT_EDIT_TEXT == p.thisObject.javaClass.name) {
                            triggerRefresh("MMEditText attached")
                        }
                    }
                })
                XposedBridge.hookAllMethods(View::class.java, "onDetachedFromWindow", object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        if (p.thisObject is View
                                && CHAT_EDIT_TEXT == p.thisObject.javaClass.name) {
                            triggerRefresh("MMEditText detached")
                        }
                    }
                })

                // ── 文档的打开/关闭聊天 hook: 仅作即时刷新的额外信号(取不到不阻断) ──
                hook(launcher, "startChatting", emptyArray(), object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) { triggerRefresh("startChatting") }
                })
                hook(launcher, "closeChatting", arrayOf<Class<*>>(Boolean::class.javaPrimitiveType!!), object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) { triggerRefresh("closeChatting") }
                })

                diagSymbols(launcher)
                LogWriter.log(TAG, "hook installed (WindowManager overlay + 聊天输入框判定)")
            } catch (e: Throwable) {
                LogWriter.log(TAG, "hook: FAILED - " + e.javaClass.simpleName
                        + ": " + e.message)
            }
        }

        // ================================================================
        // 主页生命周期 / 轮询
        // ================================================================

        private fun onHomeResumed(a: Activity) {
            sHost = WeakReference(a)
            startPoll()
            refresh(a)
        }

        private fun startPoll() {
            if (sPolling) return
            sPolling = true
            sH.post(tick)
        }

        private fun stopPoll() {
            sPolling = false
            sH.removeCallbacks(tick)
        }

        private val tick = object : Runnable {
            override fun run() {
                if (!sPolling) return
                val a = sHost.get()
                if (a == null || a.isFinishing()) { sPolling = false; return }
                try { refresh(a) } catch (ignored: Throwable) {}
                sH.postDelayed(this, POLL_MS)
            }
        }

        private fun triggerRefresh(why: String) {
            sH.post(Runnable {
                val a = sHost.get()
                if (a == null) return@Runnable
                try { refresh(a) } catch (ignored: Throwable) {}
            })
        }

        /** 唯一职责: 决定当前主页是否应该显示左上角按钮 */
        private fun refresh(act: Activity?) {
            if (act == null || act.isFinishing()
                    || (Build.VERSION.SDK_INT >= 17 && act.isDestroyed)) {
                removeOverlay()
                return
            }
            if (!isEnabled(act)) { removeOverlay(); return }
            if (isWeChatHome(act)) showOverlay(act)
            else hideOverlay()
        }

        /**
         * 是否「主页且不在聊天页」。
         * 聊天判定: 聊天输入框 MMEditText 是否真正显示在屏幕上 —— 进入聊天它在, 返回主页它不在。
         */
        private fun isWeChatHome(act: Activity): Boolean {
            try {
                if (act.javaClass.name != LAUNCHER_UI) return false
                if (isChatInputVisible(act)) return false
                return true
            } catch (t: Throwable) {
                return false
            }
        }

        /** DecorView 内是否存在正在显示的 MMEditText(即聊天输入框) */
        private fun isChatInputVisible(act: Activity): Boolean {
            try {
                val decor = act.window?.decorView
                if (decor == null) return false
                return hasShownChatInput(decor)
            } catch (ignored: Throwable) {
                return false
            }
        }

        private fun hasShownChatInput(v: View?): Boolean {
            if (v == null || !v.isShown) return false
            if (CHAT_EDIT_TEXT == v.javaClass.name) return true
            if (v is ViewGroup) {
                val g = v
                for (i in 0 until g.childCount) {
                    if (hasShownChatInput(g.getChildAt(i))) return true
                }
            }
            return false
        }

        // ================================================================
        // 悬浮窗增删
        // ================================================================

        private fun showOverlay(act: Activity) {
            val cur = sIcon
            if (cur != null && sHost.get() == act) {
                if (cur.visibility != View.VISIBLE) {
                    cur.visibility = View.VISIBLE
                    LogWriter.log(TAG, "overlay show")
                }
                return
            }
            addOverlay(act, 0)
        }

        private fun hideOverlay() {
            val cur = sIcon
            if (cur != null && cur.visibility != View.GONE) {
                cur.visibility = View.GONE
                LogWriter.log(TAG, "overlay hide (聊天页)")
            }
        }

        private fun removeOverlay() {
            val cur = sIcon
            val wm = sWM
            sIcon = null
            sWM = null
            if (cur == null) return
            try { if (wm != null) wm.removeViewImmediate(cur) } catch (ignored: Throwable) {}
            LogWriter.log(TAG, "overlay removed")
        }

        private fun addOverlay(act: Activity, attempt: Int) {
            try {
                if (act == null || act.isFinishing()) return
                if (Build.VERSION.SDK_INT >= 17 && act.isDestroyed) return
                // 文档《WeChat_LeftTop_Inject_Analysis.md》注入成功后，隐藏旧版悬浮球入口，避免重复入口
                if (com.leshao.v3.hook.LeftTopEntryHook.isInjected()) { removeOverlay(); return }
                removeOverlay()

                val ctx: Context = act
                val wm = act.windowManager
                if (wm == null) return

                val w = dp(ctx, 36)
                val h = dp(ctx, 36)
                val icon = ImageView(ctx)
                icon.tag = VIEW_TAG
                icon.setImageBitmap(if (darkMode(ctx)) sBitmapDark else sBitmapLight)
                icon.scaleType = ImageView.ScaleType.FIT_CENTER
                icon.isClickable = true
                icon.isFocusable = true
                icon.isEnabled = true
                try {
                    icon.background = null
                    val pad = dp(ctx, 3)
                    icon.setPadding(pad, pad, pad, pad)
                } catch (ignored: Throwable) {}
                // 点击左上角入口弹出快捷菜单（仅模块主页；免打扰入口已移至右上角「+」菜单）
                icon.setOnClickListener {
                    try { com.leshao.v3.hook.LeftTopEntryHook.showMenu(act) }
                    catch (e: Throwable) { LogWriter.log(TAG, "打开左上角菜单失败: " + e.message) }
                }

                val lp = WindowManager.LayoutParams(
                        w, h,
                        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT)
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = dp(ctx, 6)
                lp.y = statusBarHeight(act) + dp(ctx, 5)

                wm.addView(icon, lp)
                sIcon = icon
                sWM = wm
                sHost = WeakReference(act)
                LogWriter.log(TAG, "overlay added x=" + lp.x + " y=" + lp.y
                        + (if (attempt == 0) "" else " (retry " + attempt + ")") + " sb=" + statusBarHeight(act))
            } catch (e: Throwable) {
                if (attempt >= MAX_RETRY) {
                    LogWriter.log(TAG, "overlay add FAILED 放弃 - "
                            + e.javaClass.simpleName + ": " + e.message)
                    return
                }
                if (attempt == 0) {
                    LogWriter.log(TAG, "overlay add retry - "
                            + e.javaClass.simpleName + ": " + e.message)
                }
                sH.postDelayed({ addOverlay(act, attempt + 1) }, RETRY_DELAY_MS)
            }
        }

        /** 状态栏/刘海高度: Insets 优先, status_bar_height 资源兜底 */
        private fun statusBarHeight(act: Activity): Int {
            try {
                if (act.window != null) {
                    val decor = act.window!!.peekDecorView()
                    if (decor != null && decor.isAttachedToWindow) {
                        val wi = androidx.core.view.ViewCompat.getRootWindowInsets(decor)
                        if (wi != null) {
                            val t = wi.getInsets(
                                    androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
                            if (t > 0) return t
                        }
                    }
                }
            } catch (ignored: Throwable) {}
            try {
                val id = act.resources.getIdentifier("status_bar_height", "dimen", "android")
                if (id > 0) return act.resources.getDimensionPixelSize(id)
            } catch (ignored: Throwable) {}
            return dp(act, 24)
        }

        // ================================================================
        // 图标
        // ================================================================

        /** 绘制图标(颜色跟随 AppColors 动态主色)。已锁定样式 02「四宫格」 */
        private fun createBitmaps() {
            val size = 128
            val paint = Paint()
            paint.style = Paint.Style.FILL
            paint.isAntiAlias = true

            val primary = AppColors.primary()
            sBitmapLight = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            var canvas = Canvas(sBitmapLight!!)
            paint.shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
                    intArrayOf(primary, primary, primary), null,
                    android.graphics.Shader.TileMode.CLAMP)
            drawGrid(canvas, paint)

            sBitmapDark = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            canvas = Canvas(sBitmapDark!!)
            paint.shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
                    intArrayOf(primary, primary, primary), null,
                    android.graphics.Shader.TileMode.CLAMP)
            drawGrid(canvas, paint)
        }

        /** 供新版左上角 DecorView 入口复用旧版「四宫格」图标位图。 */
        @JvmStatic
        fun entryIconBitmap(ctx: Context): Bitmap {
            if (sBitmapLight == null || sBitmapDark == null) createBitmaps()
            return if (darkMode(ctx)) sBitmapDark!! else sBitmapLight!!
        }

        /** 02 四宫格: 2×2 圆角方块 */
        private fun drawGrid(canvas: Canvas, paint: Paint) {
            val pad = 22f
            val gap = 10f
            val box = (128f - pad * 2f - gap) / 2f
            val r = 9f
            val rect = android.graphics.RectF()
            for (row in 0 until 2) {
                for (col in 0 until 2) {
                    val x = pad + col * (box + gap)
                    val y = pad + row * (box + gap)
                    rect.set(x, y, x + box, y + box)
                    canvas.drawRoundRect(rect, r, r, paint)
                }
            }
        }

        private fun isEnabled(ctx: Context): Boolean {
            try {
                return UnifiedPrefs.get(ctx, "wm_prefs")?.getBoolean("corner_menu", true) ?: true
            } catch (ignored: Throwable) { return true }
        }

        private fun darkMode(ctx: Context): Boolean {
            try {
                val cl = sClassLoader
                // v3.0.272: 反编译权威类 com.tencent.mm.ui.gk（MicroMsg.UIUtils），方法 D()=深色模式
                val bkClass = VersionCompat.findClassMulti(cl,
                        "com.tencent.mm.ui.gk", "com.tencent.mm.ui.bk", "com.tencent.mm.ui.bl",
                        "com.tencent.mm.ui.bj", "com.tencent.mm.ui.bi")
                if (bkClass == null) return false
                for (m in arrayOf("D", "C", "B", "E")) {
                    try {
                        return XposedHelpers.callStaticMethod(bkClass, m) as Boolean
                    } catch (ignored: Throwable) {
                    }
                }
                return false
            } catch (ignored: Throwable) { return false }
        }

        // ================================================================
        // Hook / 反射工具(R8 安全: 不用 varargs findAndHookMethod)
        // ================================================================

        private fun hook(c: Class<*>, name: String, params: Array<Class<*>>, h: XC_MethodHook) {
            try {
                val m = findMethod(c, name, params)
                if (m == null) {
                    LogWriter.log(TAG, "hook miss " + c.name + "#" + name)
                    return
                }
                m.isAccessible = true
                XposedBridge.hookMethod(m, h)
                LogWriter.log(TAG, "hooked " + c.simpleName + "#" + name)
            } catch (t: Throwable) {
                LogWriter.log(TAG, "hook err " + c.name + "#" + name + ": " + t)
            }
        }

        private fun findMethod(c: Class<*>, name: String, params: Array<Class<*>>): Method? {
            var k: Class<*>? = c
            while (k != null) {
                try {
                    return k.getDeclaredMethod(name, *params)
                } catch (ignored: Throwable) {
                }
                k = k.superclass
            }
            return null
        }

        private fun findClass(name: String, cl: ClassLoader?): Class<*>? {
            return try { XposedHelpers.findClass(name, cl) } catch (t: Throwable) { null }
        }

        /** 诊断: 打印文档符号在当前微信版本是否存在(不参与判定, 仅供定位) */
        private fun diagSymbols(launcher: Class<*>) {
            try {
                val fi = findFieldUp(launcher, F_HOME_UI)
                val fc = findFieldUp(launcher, F_CHATTING_TAB)
                LogWriter.log(TAG, "diag: LauncherUI.i=" + (if (fi == null) "MISSING" else fi.type.name)
                        + "  chattingTabUI=" + (if (fc == null) "MISSING" else fc.type.name))
                if (fi != null) {
                    val home = fi.type
                    val gm = findMethodAny(home, M_GET_TAB_UI, emptyArray())
                    LogWriter.log(TAG, "diag: " + home.name + ".getMainTabUI="
                            + (if (gm == null) "MISSING" else gm.returnType.name))
                    if (gm != null) {
                        val tab = gm.returnType
                        LogWriter.log(TAG, "diag: " + tab.name + " field '" + F_TAB_INDEX + "'="
                                + (findFieldUp(tab, F_TAB_INDEX) != null)
                                + " method '" + M_GET_CUR_FRAG + "'="
                                + (findMethodAny(tab, M_GET_CUR_FRAG, emptyArray()) != null))
                    }
                }
                if (fc != null) {
                    LogWriter.log(TAG, "diag: " + fc.type.name + ".m()="
                            + (findMethodAny(fc.type, M_CHAT_FOREGROUND, emptyArray()) != null))
                }
            } catch (t: Throwable) {
                LogWriter.log(TAG, "diag err: " + t)
            }
        }

        private fun findFieldUp(c: Class<*>, name: String): java.lang.reflect.Field? {
            var k: Class<*>? = c
            while (k != null && k != Any::class.java) {
                try { return k.getDeclaredField(name) } catch (ignored: Throwable) {}
                k = k.superclass
            }
            return null
        }

        private fun findMethodAny(c: Class<*>, name: String, params: Array<Class<*>>): Method? {
            var k: Class<*>? = c
            while (k != null && k != Any::class.java) {
                try { return k.getDeclaredMethod(name, *params) } catch (ignored: Throwable) {}
                k = k.superclass
            }
            return null
        }
    }
}