package com.leshao.v3.ui

import android.app.Activity
import android.content.ComponentCallbacks
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import java.util.concurrent.CopyOnWriteArrayList

/**
 * LeShaoWeChat 设计令牌 —— Material 3 (Material You) 色彩体系。
 *
 * 兼容性铁律：旧公开常量与旧方法全部保留（全项目 20+ 页面依赖，禁止删除/改语义）；
 * M3 色彩角色（primary/onPrimary/primaryContainer/surface/surfaceVariant/outline/…）
 * 一律走动态 getter，支持暗色模式运行时切换。
 *
 * 主题：微信绿 · 纯色（#07C160，动态取色优先，低版本回退微信绿）。
 */
class AppColors private constructor() {

    companion object {
        @Volatile
        private var sDarkMode = false

        // v1140: 主题实时跟随 —— 深色模式变化时通知已打开界面重建
        private val sThemeListeners = CopyOnWriteArrayList<Runnable>()
        @Volatile
        private var sWatcherInstalled = false

        // ==================== M3 多配色方案（v1015：12 套 + 自定义 + 动态取色） ====================
        const val PALETTE_GREEN = 0
        const val PALETTE_BLUE = 1
        const val PALETTE_PURPLE = 2
        const val PALETTE_ORANGE = 3
        const val PALETTE_PINK = 4
        const val PALETTE_TEAL = 5
        const val PALETTE_RED = 6
        const val PALETTE_AMBER = 7
        const val PALETTE_CYAN = 8
        const val PALETTE_INDIGO = 9
        const val PALETTE_VIOLET = 10
        const val PALETTE_GRAPHITE = 11
        /** 固定方案数量 */
        const val PALETTE_COUNT = 12
        /** 自定义调色板（seed 存于 KEY_CUSTOM_SEED） */
        const val PALETTE_CUSTOM = 100
        /** 动态取色（Material You，Android 12+ 系统强调色） */
        const val PALETTE_DYNAMIC = 101

        private val PALETTE_NAMES = arrayOf(
            "糖果粉", "樱粉", "蜜桃", "玫瑰粉", "淡粉", "亮粉",
            "深粉", "藕粉", "玫粉", "暖粉", "荧光粉", "粉紫"
        )
        private val PALETTE_SEEDS = intArrayOf(
            0xFFFF99C2.toInt(), // 糖果粉（主题）
            0xFFFFAFCC.toInt(), // 樱粉
            0xFFF472B6.toInt(), // 蜜桃
            0xFFFF6FB0.toInt(), // 玫瑰粉
            0xFFFFC2D8.toInt(), // 淡粉
            0xFFFF8FC7.toInt(), // 亮粉
            0xFFD9639D.toInt(), // 深粉
            0xFFFFD9E8.toInt(), // 藕粉
            0xFFE56EA6.toInt(), // 玫粉
            0xFFFB7185.toInt(), // 暖粉
            0xFFFF5FA2.toInt(), // 荧光粉
            0xFF8E6B7C.toInt()  // 粉紫
        )
        private const val PREFS = "leshao_m3_prefs"
        private const val KEY_PALETTE = "m3_palette"
        private const val KEY_CUSTOM_SEED = "m3_custom_seed"
        private const val KEY_TITLEBAR = "m3_override_titlebar"
        private const val KEY_SWITCH = "m3_override_switch"
        private const val KEY_WINDOWBG = "m3_override_windowbg"
        @Volatile
        private var sPalette = PALETTE_DYNAMIC
        @Volatile
        private var sCustomSeed = 0xFF07C160.toInt()
        @Volatile
        private var sSchemeVersion = 0
        /** 上次成功读取的动态取色 seed（Android 12+ system_accent1_500），用于 refresh 时检测壁纸变化 */
        @Volatile
        private var sLastDynamicSeed = 0
        /** 三个自定义色（0 表示未设置，走默认角色） */
        @Volatile
        private var sOvTitleBar = 0
        @Volatile
        private var sOvSwitch = 0
        @Volatile
        private var sOvWindowBg = 0

        // 当前调色板解析后的角色（refresh 时重算，getter 直接读）
        @Volatile
        private var cPrimary = 0
        @Volatile
        private var cOnPrimary = 0
        @Volatile
        private var cPrimaryContainer = 0
        @Volatile
        private var cOnPrimaryContainer = 0
        @Volatile
        private var cSecondary = 0
        @Volatile
        private var cOnSecondary = 0
        @Volatile
        private var cSecondaryContainer = 0
        @Volatile
        private var cOnSecondaryContainer = 0
        @Volatile
        private var cTertiary = 0
        @Volatile
        private var cOnTertiary = 0
        @Volatile
        private var cTertiaryContainer = 0
        @Volatile
        private var cOnTertiaryContainer = 0
        @Volatile
        private var cPrimaryDark = 0
        // v1017: 中性色（背景/表面）也随调色板走，保证「背景色」全局适配
        @Volatile
        private var cBackground = 0
        @Volatile
        private var cSurface = 0
        @Volatile
        private var cOnSurface = 0
        @Volatile
        private var cSurfaceVariant = 0
        @Volatile
        private var cOnSurfaceVariant = 0
        @Volatile
        private var cScLowest = 0
        @Volatile
        private var cScLow = 0
        @Volatile
        private var cSc = 0
        @Volatile
        private var cScHigh = 0
        @Volatile
        private var cScHighest = 0
        @Volatile
        private var cOutline = 0
        @Volatile
        private var cOutlineVariant = 0

        // 必须在 PALETTE_* 声明之后执行（初始化顺序铁律）
        init {
            sDarkMode = detectDarkMode()
            loadPaletteFromPrefs()
            recomputePalette()
        }

        private fun loadPaletteFromPrefs() {
            // v1018: 模块统一使用 M3 动态配色（Material You），全局所有界面实时生效；
            // 配色选择功能已移除，不再读取用户选择，单元素颜色覆盖一并复位。
            sPalette = PALETTE_DYNAMIC
            sOvTitleBar = 0
            sOvSwitch = 0
            sOvWindowBg = 0
            try {
                val ctx = com.leshao.v3.ContextManager.getAppContext()
                if (ctx == null) return
                val sp = ctx.getSharedPreferences(PREFS, 0)
                sCustomSeed = sp.getInt(KEY_CUSTOM_SEED, sCustomSeed)
            } catch (ignored: Throwable) {
            }
        }

        private fun adjust(color: Int, satMul: Float, valMul: Float): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hsv[1] = Math.max(0f, Math.min(1f, hsv[1] * satMul))
            hsv[2] = Math.max(0f, Math.min(1f, hsv[2] * valMul))
            return Color.HSVToColor(hsv)
        }

        private fun hueShift(color: Int, deg: Float): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hsv[0] = (hsv[0] + deg) % 360f
            return Color.HSVToColor(hsv)
        }

        /** 解析当前方案对应的 seed 颜色 */
        private fun resolveSeed(): Int {
            if (sPalette == PALETTE_DYNAMIC) {
                val dyn = detectDynamicSeed()
                return if (dyn != 0) dyn else sCustomSeed
            }
            if (sPalette == PALETTE_CUSTOM) return sCustomSeed
            val idx = Math.max(0, Math.min(sPalette, PALETTE_SEEDS.size - 1))
            return PALETTE_SEEDS[idx]
        }

        /** 动态取色：Android 12+ 读取系统强调色（Material You），不可用时返回 0 */
        private fun detectDynamicSeed(): Int {
            if (android.os.Build.VERSION.SDK_INT < 31) return 0
            try {
                val ctx = themedContext()
                if (ctx == null) return 0
                return ctx.resources.getColor(android.R.color.system_accent1_500, ctx.theme)
            } catch (ignored: Throwable) {
            }
            try {
                val ctx = com.leshao.v3.ContextManager.getAppContext()
                if (ctx != null) {
                    return ctx.resources.getColor(android.R.color.system_accent1_500)
                }
            } catch (ignored: Throwable) {
            }
            return 0
        }

        /**
         * v3.0.123：获取带有效 Theme 的 Context。
         * Application context 的 getTheme() 在部分机型/时机下不可用，这里用 ContextThemeWrapper
         * 绑定设备默认主题，保证系统强调色资源在任意线程都能解析。
         */
        private fun themedContext(): Context? {
            val ctx = com.leshao.v3.ContextManager.getAppContext()
            if (ctx == null) return null
            return try {
                android.view.ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_DayNight)
            } catch (ignored: Throwable) {
                ctx
            }
        }

        /** 动态取色是否可用（Android 12+） */
        @JvmStatic
        fun dynamicColorAvailable(): Boolean {
            return android.os.Build.VERSION.SDK_INT >= 31 && detectDynamicSeed() != 0
        }

        /**
         * v3.0.102：Material You 动态取色 —— Android 12+ 读取系统壁纸生成的强调色资源，
         * 映射到 M3 全部色彩角色。低版本或系统资源不可用时返回 false。
         */
        private fun applyDynamicPalette(dark: Boolean): Boolean {
            return try {
                val ctx = themedContext()
                if (ctx == null || android.os.Build.VERSION.SDK_INT < 31) return false
                val res = ctx.resources
                val theme = ctx.theme
                val seed = res.getColor(android.R.color.system_accent1_500, theme)
                if (dark) {
                    cPrimary = res.getColor(android.R.color.system_accent1_200, theme)
                    cOnPrimary = res.getColor(android.R.color.system_accent1_700, theme)
                    cPrimaryContainer = res.getColor(android.R.color.system_accent1_700, theme)
                    cOnPrimaryContainer = res.getColor(android.R.color.system_accent1_100, theme)
                    cSecondary = res.getColor(android.R.color.system_accent2_200, theme)
                    cOnSecondary = res.getColor(android.R.color.system_accent2_700, theme)
                    cSecondaryContainer = res.getColor(android.R.color.system_accent2_700, theme)
                    cOnSecondaryContainer = res.getColor(android.R.color.system_accent2_100, theme)
                    cTertiary = res.getColor(android.R.color.system_accent3_200, theme)
                    cOnTertiary = res.getColor(android.R.color.system_accent3_700, theme)
                    cTertiaryContainer = res.getColor(android.R.color.system_accent3_700, theme)
                    cOnTertiaryContainer = res.getColor(android.R.color.system_accent3_100, theme)
                    cPrimaryDark = cPrimary
                    cBackground = 0xFF1B1218.toInt()
                    cSurface = 0xFF1B1218.toInt()
                    cScLowest = 0xFF130B10.toInt()
                    cScLow = 0xFF241A20.toInt()
                    cSc = 0xFF2A2026.toInt()
                    cScHigh = 0xFF35292F.toInt()
                    cScHighest = 0xFF40343A.toInt()
                    cSurfaceVariant = 0xFF44323B.toInt()
                    cOnSurface = 0xFFF5E6ED.toInt()
                    cOnSurfaceVariant = 0xFFD0B4C1.toInt()
                    cOutline = 0xFF9D838F.toInt()
                    cOutlineVariant = 0xFF4B3843.toInt()
                } else {
                    cPrimary = seed
                    cOnPrimary = onColor(seed)
                    cPrimaryContainer = res.getColor(android.R.color.system_accent1_100, theme)
                    cOnPrimaryContainer = res.getColor(android.R.color.system_accent1_900, theme)
                    cSecondary = res.getColor(android.R.color.system_accent2_500, theme)
                    cOnSecondary = onColor(cSecondary)
                    cSecondaryContainer = res.getColor(android.R.color.system_accent2_100, theme)
                    cOnSecondaryContainer = res.getColor(android.R.color.system_accent2_900, theme)
                    cTertiary = res.getColor(android.R.color.system_accent3_500, theme)
                    cOnTertiary = onColor(cTertiary)
                    cTertiaryContainer = res.getColor(android.R.color.system_accent3_100, theme)
                    cOnTertiaryContainer = res.getColor(android.R.color.system_accent3_900, theme)
                    cPrimaryDark = cPrimary
                    cBackground = 0xFFFBF8F6.toInt()
                    cSurface = 0xFFFBF8F6.toInt()
                    cScLowest = 0xFFFFFFFF.toInt()
                    cScLow = 0xFFF7F3F1.toInt()
                    cSc = 0xFFF2ECEA.toInt()
                    cScHigh = 0xFFECE5E3.toInt()
                    cScHighest = 0xFFE6DEDC.toInt()
                    cSurfaceVariant = 0xFFF1DEE4.toInt()
                    cOnSurface = 0xFF221A1E.toInt()
                    cOnSurfaceVariant = 0xFF6B5560.toInt()
                    cOutline = 0xFF9C818D.toInt()
                    cOutlineVariant = 0xFFE4CBD5.toInt()
                }
                sLastDynamicSeed = seed
                true
            } catch (ignored: Throwable) {
                false
            }
        }

        /**
         * 依据明暗模式重算当前配色角色。
         *
         * v3.0.102：全局锁定「动态取色」（Material You）——Android 12+ 读取系统壁纸强调色，
         * 低版本/系统色不可用时回退到糖果粉静态色板。
         */
        @Synchronized
        private fun recomputePalette() {
            val dark = sDarkMode
            // Android 12+ 动态取色可用时直接使用系统强调色（Material You）
            if (sPalette == PALETTE_DYNAMIC
                && android.os.Build.VERSION.SDK_INT >= 31
                && applyDynamicPalette(dark)) {
                try {
                    com.leshao.v3.LogWriter.log("AppColors", "dynamic palette: sdk=" + android.os.Build.VERSION.SDK_INT
                        + " dark=" + dark + " seed=0x" + Integer.toHexString(detectDynamicSeed()))
                } catch (ignored: Throwable) {
                }
                return
            }
            // v3.0.123: 线程竞争修复 —— 后台线程可能在 Application 尚未 attach 时（app context 为空）
            // 触发类加载并调用本方法，导致动态取色失败而回退静态色。若此前已成功取到动态色
            // （sLastDynamicSeed != 0），则保留已生效的动态配色，绝不用静态色覆盖。
            if (sPalette == PALETTE_DYNAMIC && sLastDynamicSeed != 0) {
                try {
                    com.leshao.v3.LogWriter.log("AppColors", "keep dynamic palette (ctx not ready), seed=0x"
                        + Integer.toHexString(sLastDynamicSeed))
                } catch (ignored: Throwable) {
                }
                return
            }
            try {
                com.leshao.v3.LogWriter.log("AppColors", "fallback static palette: sdk=" + android.os.Build.VERSION.SDK_INT
                    + " dark=" + dark + " (dynamic unavailable)")
            } catch (ignored: Throwable) {
            }
            // ==================== M3 Expressive · 微信绿体系 ====================
            if (dark) {
                cPrimary = 0xFF3DD68C.toInt()
                cOnPrimary = 0xFF00391F.toInt()
                cPrimaryContainer = 0xFF005234.toInt()
                cOnPrimaryContainer = 0xFFB3F5D3.toInt()
                cSecondary = 0xFFB4CCBC.toInt()
                cOnSecondary = 0xFF21382A.toInt()
                cSecondaryContainer = 0xFF37513F.toInt()
                cOnSecondaryContainer = 0xFFD0E8D4.toInt()
                cTertiary = 0xFFA6D8C2.toInt()
                cOnTertiary = 0xFF00382A.toInt()
                cTertiaryContainer = 0xFF00513D.toInt()
                cOnTertiaryContainer = 0xFFB0F2DA.toInt()
                cPrimaryDark = 0xFF3DD68C.toInt()

                cBackground = 0xFF1B1218.toInt()
                cSurface = 0xFF1B1218.toInt()
                cScLowest = 0xFF130B10.toInt()
                cScLow = 0xFF241A20.toInt()
                cSc = 0xFF2A2026.toInt()
                cScHigh = 0xFF35292F.toInt()
                cScHighest = 0xFF40343A.toInt()
                cSurfaceVariant = 0xFF44323B.toInt()
                cOnSurface = 0xFFF5E6ED.toInt()
                cOnSurfaceVariant = 0xFFD0B4C1.toInt()
                cOutline = 0xFF9D838F.toInt()
                cOutlineVariant = 0xFF4B3843.toInt()
            } else {
                cPrimary = 0xFF07C160.toInt()
                cOnPrimary = 0xFFFFFFFF.toInt()
                cPrimaryContainer = 0xFF9EF2C8.toInt()
                cOnPrimaryContainer = 0xFF00391F.toInt()
                cSecondary = 0xFF4E6356.toInt()
                cOnSecondary = 0xFFFFFFFF.toInt()
                cSecondaryContainer = 0xFFD0E8D4.toInt()
                cOnSecondaryContainer = 0xFF0C1F14.toInt()
                cTertiary = 0xFF3D6373.toInt()
                cOnTertiary = 0xFFFFFFFF.toInt()
                cTertiaryContainer = 0xFFC1E8FA.toInt()
                cOnTertiaryContainer = 0xFF001F29.toInt()
                cPrimaryDark = 0xFF07C160.toInt()

                cBackground = 0xFFFBF8F6.toInt()
                cSurface = 0xFFFBF8F6.toInt()
                cScLowest = 0xFFFFFFFF.toInt()
                cScLow = 0xFFF7F3F1.toInt()
                cSc = 0xFFF2ECEA.toInt()
                cScHigh = 0xFFECE5E3.toInt()
                cScHighest = 0xFFE6DEDC.toInt()
                cSurfaceVariant = 0xFFF1DEE4.toInt()
                cOnSurface = 0xFF221A1E.toInt()
                cOnSurfaceVariant = 0xFF6B5560.toInt()
                cOutline = 0xFF9C818D.toInt()
                cOutlineVariant = 0xFFE4CBD5.toInt()
            }
        }

        @JvmStatic
        fun getPalette(): Int {
            return sPalette
        }

        @JvmStatic
        fun getSchemeVersion(): Int {
            return sSchemeVersion
        }

        @JvmStatic
        fun paletteNames(): Array<String> {
            return PALETTE_NAMES.clone()
        }

        @JvmStatic
        fun paletteCount(): Int {
            return PALETTE_COUNT
        }

        @JvmStatic
        fun paletteName(id: Int): String {
            if (id == PALETTE_CUSTOM) return "自定义"
            if (id == PALETTE_DYNAMIC) return "动态取色"
            if (id < 0 || id >= PALETTE_NAMES.size) return PALETTE_NAMES[0]
            return PALETTE_NAMES[id]
        }

        @JvmStatic
        fun paletteSeed(id: Int): Int {
            if (id == PALETTE_CUSTOM) return sCustomSeed
            if (id == PALETTE_DYNAMIC) {
                val d = detectDynamicSeed()
                return if (d != 0) d else sCustomSeed
            }
            if (id < 0 || id >= PALETTE_SEEDS.size) return PALETTE_SEEDS[0]
            return PALETTE_SEEDS[id]
        }

        @JvmStatic
        fun getCustomSeed(): Int {
            return sCustomSeed
        }

        /** 当前解析后的 seed（供预览/取色器回显） */
        @JvmStatic
        fun currentSeed(): Int {
            return resolveSeed()
        }

        private fun prefs(): SharedPreferences? {
            return try {
                val ctx = com.leshao.v3.ContextManager.getAppContext()
                if (ctx != null) ctx.getSharedPreferences(PREFS, 0) else null
            } catch (ignored: Throwable) {
                null
            }
        }

        /** 切换配色方案：持久化 + 立即重算（实时生效） */
        @JvmStatic
        fun setPalette(id: Int) {
            if (id != PALETTE_CUSTOM && id != PALETTE_DYNAMIC
                && (id < 0 || id >= PALETTE_COUNT)) return
            sPalette = id
            try {
                val sp = prefs()
                if (sp != null) sp.edit().putInt(KEY_PALETTE, id).apply()
            } catch (ignored: Throwable) {
            }
            recomputePalette()
            sSchemeVersion++
        }

        /** 设置自定义调色板 seed 并切到自定义方案 */
        @JvmStatic
        fun setCustomPalette(seed: Int) {
            sCustomSeed = seed
            sPalette = PALETTE_CUSTOM
            try {
                val sp = prefs()
                if (sp != null) sp.edit()
                    .putInt(KEY_CUSTOM_SEED, seed)
                    .putInt(KEY_PALETTE, PALETTE_CUSTOM).apply()
            } catch (ignored: Throwable) {
            }
            recomputePalette()
            sSchemeVersion++
        }

        // ==================== 单元素自定义色（v1015） ====================
        @JvmStatic
        fun titleBar(): Int {
            return if (sOvTitleBar != 0) sOvTitleBar else primary()
        }

        @JvmStatic
        fun switchColor(): Int {
            return if (sOvSwitch != 0) sOvSwitch else primary()
        }

        @JvmStatic
        fun windowBg(): Int {
            return if (sOvWindowBg != 0) sOvWindowBg else surface()
        }

        @JvmStatic
        fun hasTitleBarOverride(): Boolean {
            return sOvTitleBar != 0
        }

        @JvmStatic
        fun hasSwitchOverride(): Boolean {
            return sOvSwitch != 0
        }

        @JvmStatic
        fun hasWindowBgOverride(): Boolean {
            return sOvWindowBg != 0
        }

        @JvmStatic
        fun setTitleBarColor(color: Int) {
            sOvTitleBar = color
            try {
                val sp = prefs()
                if (sp != null) sp.edit().putInt(KEY_TITLEBAR, color).apply()
            } catch (ignored: Throwable) {
            }
            sSchemeVersion++
        }

        @JvmStatic
        fun setSwitchColor(color: Int) {
            sOvSwitch = color
            try {
                val sp = prefs()
                if (sp != null) sp.edit().putInt(KEY_SWITCH, color).apply()
            } catch (ignored: Throwable) {
            }
            sSchemeVersion++
        }

        @JvmStatic
        fun setWindowBgColor(color: Int) {
            sOvWindowBg = color
            try {
                val sp = prefs()
                if (sp != null) sp.edit().putInt(KEY_WINDOWBG, color).apply()
            } catch (ignored: Throwable) {
            }
            sSchemeVersion++
        }

        /** 清除全部单元素自定义色 */
        @JvmStatic
        fun clearOverrides() {
            sOvTitleBar = 0
            sOvSwitch = 0
            sOvWindowBg = 0
            try {
                val sp = prefs()
                if (sp != null) sp.edit()
                    .remove(KEY_TITLEBAR).remove(KEY_SWITCH).remove(KEY_WINDOWBG).apply()
            } catch (ignored: Throwable) {
            }
            sSchemeVersion++
        }

        /** 文字自动对比色：在给定底色上选择黑/白以保证可读性 */
        @JvmStatic
        fun onColor(bg: Int): Int {
            val lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255.0
            return if (lum > 0.6) 0xFF1B1B1B.toInt() else 0xFFFFFFFF.toInt()
        }

        private fun detectDarkMode(): Boolean {
            // 优先用微信内部暗色检测（bk.C），更贴合微信「深色模式」设置；失败回退系统 uiMode
            try {
                val cl = com.leshao.v3.ContextManager.getClassLoader()
                if (cl != null) {
                    val bk = de.robv.android.xposed.XposedHelpers.findClass("com.tencent.mm.ui.bk", cl)
                    val r = de.robv.android.xposed.XposedHelpers.callStaticMethod(bk, "C")
                    if (r is Boolean) return r
                }
            } catch (ignored: Throwable) {
            }
            try {
                val ctx = com.leshao.v3.ContextManager.getAppContext()
                if (ctx != null) {
                    val nightMode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    return nightMode == Configuration.UI_MODE_NIGHT_YES
                }
            } catch (ignored: Throwable) {
            }
            return false
        }

        /** 页面 onResume 时调用：微信内切换深色模式/系统壁纸后刷新令牌 */
        @JvmStatic
        fun refresh() {
            try {
                val prev = sDarkMode
                sDarkMode = detectDarkMode()
                var changed = prev != sDarkMode
                if (sPalette == PALETTE_DYNAMIC) {
                    val seed = detectDynamicSeed()
                    if (seed != 0 && seed != sLastDynamicSeed) changed = true
                }
                if (changed) {
                    recomputePalette()
                    notifyThemeChanged()
                }
            } catch (ignored: Throwable) {
            }
            ensureWatcher()
        }

        /** v1140: 注册主题变化监听（深色模式切换时回调），用于已打开界面就地重建。 */
        @JvmStatic
        fun addThemeListener(r: Runnable?) {
            if (r == null || sThemeListeners.contains(r)) return
            sThemeListeners.add(r)
        }

        @JvmStatic
        fun removeThemeListener(r: Runnable?) {
            if (r != null) sThemeListeners.remove(r)
        }

        private fun notifyThemeChanged() {
            for (r in sThemeListeners) {
                try {
                    r.run()
                } catch (ignored: Throwable) {
                }
            }
        }

        /** v1140: 一次性注册系统配置回调，系统深色模式切换时立即重算配色。 */
        private fun ensureWatcher() {
            if (sWatcherInstalled) return
            val ctx = com.leshao.v3.ContextManager.getAppContext()
            if (ctx == null) return
            try {
                ctx.registerComponentCallbacks(object : ComponentCallbacks {
                    override fun onConfigurationChanged(newConfig: Configuration) {
                        try {
                            refresh()
                        } catch (ignored: Throwable) {
                        }
                    }

                    override fun onLowMemory() {}
                })
                sWatcherInstalled = true
            } catch (ignored: Throwable) {
            }
        }

        @JvmStatic
        @JvmName("init")
        fun initTheme(act: Activity) {
            refresh()
        }

        @JvmStatic
        fun isDarkMode(): Boolean {
            return sDarkMode
        }

        // ==================== M3 Expressive · 品牌渐变令牌 ====================
        @JvmStatic
        fun gradientStart(): Int {
            return primary()
        }

        @JvmStatic
        fun gradientMid(): Int {
            return primary()
        }

        @JvmStatic
        fun gradientEnd(): Int {
            return primary()
        }

        @JvmStatic
        fun onGradient(): Int {
            return onPrimary()
        }

        /** 品牌渐变循环色（跟随主色） */
        @JvmStatic
        fun gradientColors(): IntArray {
            return intArrayOf(primary(), primary(), primary())
        }

        /** 品牌色按压态（主色加深） */
        @JvmStatic
        fun gradientPressed(): Int {
            return primaryDark()
        }

        // ==================== M3 色彩角色（浅色方案 · Expressive 中性暖白） ====================
        private val L_PRIMARY = 0xFF07C160.toInt()
        private val L_ON_PRIMARY = 0xFFFFFFFF.toInt()
        private val L_PRIMARY_CONTAINER = 0xFF9EF2C8.toInt()
        private val L_ON_PRIMARY_CONTAINER = 0xFF00391F.toInt()
        private val L_SECONDARY = 0xFF4E6356.toInt()
        private val L_ON_SECONDARY = 0xFFFFFFFF.toInt()
        private val L_SECONDARY_CONTAINER = 0xFFD0E8D4.toInt()
        private val L_ON_SECONDARY_CONTAINER = 0xFF0C1F14.toInt()
        private val L_TERTIARY = 0xFF3D6373.toInt()
        private val L_ON_TERTIARY = 0xFFFFFFFF.toInt()
        private val L_TERTIARY_CONTAINER = 0xFFC1E8FA.toInt()
        private val L_ON_TERTIARY_CONTAINER = 0xFF001F29.toInt()
        private val L_ERROR = 0xFFBA1A1A.toInt()
        private val L_ON_ERROR = 0xFFFFFFFF.toInt()
        private val L_ERROR_CONTAINER = 0xFFFFDAD6.toInt()
        private val L_ON_ERROR_CONTAINER = 0xFF410002.toInt()
        private val L_BACKGROUND = 0xFFF8F8F8.toInt()
        private val L_ON_BACKGROUND = 0xFF1A1C1B.toInt()
        private val L_SURFACE = 0xFFF8F8F8.toInt()
        private val L_ON_SURFACE = 0xFF1A1C1B.toInt()
        private val L_SURFACE_VARIANT = 0xFFE3E3E3.toInt()
        private val L_ON_SURFACE_VARIANT = 0xFF444746.toInt()
        private val L_SURFACE_CONTAINER_LOWEST = 0xFFFFFFFF.toInt()
        private val L_SURFACE_CONTAINER_LOW = 0xFFF3F3F3.toInt()
        private val L_SURFACE_CONTAINER = 0xFFEDEDED.toInt()
        private val L_SURFACE_CONTAINER_HIGH = 0xFFE6E6E6.toInt()
        private val L_SURFACE_CONTAINER_HIGHEST = 0xFFE0E0E0.toInt()
        private val L_OUTLINE = 0xFF79747E.toInt()
        private val L_OUTLINE_VARIANT = 0xFFCAC4D0.toInt()
        private val L_INVERSE_SURFACE = 0xFF303030.toInt()
        private val L_INVERSE_ON_SURFACE = 0xFFF5F5F5.toInt()

        // ==================== M3 色彩角色（暗色方案 · Expressive 中性深灰） ====================
        private val D_PRIMARY = 0xFF3DD68C.toInt()
        private val D_ON_PRIMARY = 0xFF00391F.toInt()
        private val D_PRIMARY_CONTAINER = 0xFF005234.toInt()
        private val D_ON_PRIMARY_CONTAINER = 0xFFB3F5D3.toInt()
        private val D_SECONDARY = 0xFFB4CCBC.toInt()
        private val D_ON_SECONDARY = 0xFF21382A.toInt()
        private val D_SECONDARY_CONTAINER = 0xFF37513F.toInt()
        private val D_ON_SECONDARY_CONTAINER = 0xFFD0E8D4.toInt()
        private val D_TERTIARY = 0xFFA6D8C2.toInt()
        private val D_ON_TERTIARY = 0xFF00382A.toInt()
        private val D_TERTIARY_CONTAINER = 0xFF00513D.toInt()
        private val D_ON_TERTIARY_CONTAINER = 0xFFB0F2DA.toInt()
        private val D_ERROR = 0xFFFFB4AB.toInt()
        private val D_ON_ERROR = 0xFF690005.toInt()
        private val D_ERROR_CONTAINER = 0xFF93000A.toInt()
        private val D_ON_ERROR_CONTAINER = 0xFFFFDAD6.toInt()
        private val D_BACKGROUND = 0xFF121212.toInt()
        private val D_ON_BACKGROUND = 0xFFF0F0F0.toInt()
        private val D_SURFACE = 0xFF121212.toInt()
        private val D_ON_SURFACE = 0xFFF0F0F0.toInt()
        private val D_SURFACE_VARIANT = 0xFF3A3A3A.toInt()
        private val D_ON_SURFACE_VARIANT = 0xFFC9C9C9.toInt()
        private val D_SURFACE_CONTAINER_LOWEST = 0xFF0E0E0E.toInt()
        private val D_SURFACE_CONTAINER_LOW = 0xFF1A1A1A.toInt()
        private val D_SURFACE_CONTAINER = 0xFF212121.toInt()
        private val D_SURFACE_CONTAINER_HIGH = 0xFF2A2A2A.toInt()
        private val D_SURFACE_CONTAINER_HIGHEST = 0xFF333333.toInt()
        private val D_OUTLINE = 0xFF9B9B9B.toInt()
        private val D_OUTLINE_VARIANT = 0xFF494949.toInt()
        private val D_INVERSE_SURFACE = 0xFF3A3A3A.toInt()
        private val D_INVERSE_ON_SURFACE = 0xFFF5F5F5.toInt()

        // ==================== M3 getter（暗色实时跟随） ====================
        private fun custom(): Boolean {
            return sPalette != PALETTE_GREEN
        }

        @JvmStatic
        fun primary(): Int {
            return if (custom()) cPrimary else if (sDarkMode) D_PRIMARY else L_PRIMARY
        }

        @JvmStatic
        fun onPrimary(): Int {
            return if (custom()) cOnPrimary else if (sDarkMode) D_ON_PRIMARY else L_ON_PRIMARY
        }

        @JvmStatic
        fun primaryContainer(): Int {
            return if (custom()) cPrimaryContainer else if (sDarkMode) D_PRIMARY_CONTAINER else L_PRIMARY_CONTAINER
        }

        @JvmStatic
        fun onPrimaryContainer(): Int {
            return if (custom()) cOnPrimaryContainer else if (sDarkMode) D_ON_PRIMARY_CONTAINER else L_ON_PRIMARY_CONTAINER
        }

        @JvmStatic
        fun secondary(): Int {
            return if (custom()) cSecondary else if (sDarkMode) D_SECONDARY else L_SECONDARY
        }

        @JvmStatic
        fun onSecondary(): Int {
            return if (custom()) cOnSecondary else if (sDarkMode) D_ON_SECONDARY else L_ON_SECONDARY
        }

        @JvmStatic
        fun secondaryContainer(): Int {
            return if (custom()) cSecondaryContainer else if (sDarkMode) D_SECONDARY_CONTAINER else L_SECONDARY_CONTAINER
        }

        @JvmStatic
        fun onSecondaryContainer(): Int {
            return if (custom()) cOnSecondaryContainer else if (sDarkMode) D_ON_SECONDARY_CONTAINER else L_ON_SECONDARY_CONTAINER
        }

        @JvmStatic
        fun tertiary(): Int {
            return if (custom()) cTertiary else if (sDarkMode) D_TERTIARY else L_TERTIARY
        }

        @JvmStatic
        fun onTertiary(): Int {
            return if (custom()) cOnTertiary else if (sDarkMode) D_ON_TERTIARY else L_ON_TERTIARY
        }

        @JvmStatic
        fun tertiaryContainer(): Int {
            return if (custom()) cTertiaryContainer else if (sDarkMode) D_TERTIARY_CONTAINER else L_TERTIARY_CONTAINER
        }

        @JvmStatic
        fun onTertiaryContainer(): Int {
            return if (custom()) cOnTertiaryContainer else if (sDarkMode) D_ON_TERTIARY_CONTAINER else L_ON_TERTIARY_CONTAINER
        }

        @JvmStatic
        fun error(): Int {
            return if (sDarkMode) D_ERROR else L_ERROR
        }

        @JvmStatic
        fun onError(): Int {
            return if (sDarkMode) D_ON_ERROR else L_ON_ERROR
        }

        @JvmStatic
        fun errorContainer(): Int {
            return if (sDarkMode) D_ERROR_CONTAINER else L_ERROR_CONTAINER
        }

        @JvmStatic
        fun onErrorContainer(): Int {
            return if (sDarkMode) D_ON_ERROR_CONTAINER else L_ON_ERROR_CONTAINER
        }

        @JvmStatic
        fun background(): Int {
            return if (custom()) cBackground else if (sDarkMode) D_BACKGROUND else L_BACKGROUND
        }

        @JvmStatic
        fun onBackground(): Int {
            return if (custom()) cOnSurface else if (sDarkMode) D_ON_BACKGROUND else L_ON_BACKGROUND
        }

        @JvmStatic
        fun surface(): Int {
            return if (custom()) cSurface else if (sDarkMode) D_SURFACE else L_SURFACE
        }

        @JvmStatic
        fun onSurface(): Int {
            return if (custom()) cOnSurface else if (sDarkMode) D_ON_SURFACE else L_ON_SURFACE
        }

        @JvmStatic
        fun surfaceVariant(): Int {
            return if (custom()) cSurfaceVariant else if (sDarkMode) D_SURFACE_VARIANT else L_SURFACE_VARIANT
        }

        @JvmStatic
        fun onSurfaceVariant(): Int {
            return if (custom()) cOnSurfaceVariant else if (sDarkMode) D_ON_SURFACE_VARIANT else L_ON_SURFACE_VARIANT
        }

        @JvmStatic
        fun surfaceContainerLowest(): Int {
            return if (custom()) cScLowest else if (sDarkMode) D_SURFACE_CONTAINER_LOWEST else L_SURFACE_CONTAINER_LOWEST
        }

        @JvmStatic
        fun surfaceContainerLow(): Int {
            return if (custom()) cScLow else if (sDarkMode) D_SURFACE_CONTAINER_LOW else L_SURFACE_CONTAINER_LOW
        }

        @JvmStatic
        fun surfaceContainer(): Int {
            return if (custom()) cSc else if (sDarkMode) D_SURFACE_CONTAINER else L_SURFACE_CONTAINER
        }

        @JvmStatic
        fun surfaceContainerHigh(): Int {
            return if (custom()) cScHigh else if (sDarkMode) D_SURFACE_CONTAINER_HIGH else L_SURFACE_CONTAINER_HIGH
        }

        @JvmStatic
        fun surfaceContainerHighest(): Int {
            return if (custom()) cScHighest else if (sDarkMode) D_SURFACE_CONTAINER_HIGHEST else L_SURFACE_CONTAINER_HIGHEST
        }

        @JvmStatic
        fun outline(): Int {
            return if (custom()) cOutline else if (sDarkMode) D_OUTLINE else L_OUTLINE
        }

        @JvmStatic
        fun outlineVariant(): Int {
            return if (custom()) cOutlineVariant else if (sDarkMode) D_OUTLINE_VARIANT else L_OUTLINE_VARIANT
        }

        @JvmStatic
        fun inverseSurface(): Int {
            return if (sDarkMode) D_INVERSE_SURFACE else L_INVERSE_SURFACE
        }

        @JvmStatic
        fun inverseOnSurface(): Int {
            return if (sDarkMode) D_INVERSE_ON_SURFACE else L_INVERSE_ON_SURFACE
        }

        /** M3 状态层：按压 12% / 悬浮 8%（由调用方与底色叠加） */
        @JvmStatic
        fun stateLayerPressed(): Int {
            return 0x1F000000.toInt()
        }

        @JvmStatic
        fun stateLayerHover(): Int {
            return 0x14000000.toInt()
        }

        /** M3 主色状态层（用于 primary 底上的涟漪） */
        @JvmStatic
        fun stateLayerOnPrimary(): Int {
            return 0x14FFFFFF.toInt()
        }

        // ==================== 便捷别名（M3 角色映射，供组件工厂使用） ====================
        /** 主色按压深阶（渐变/按压态用） */
        @JvmStatic
        fun primaryDark(): Int {
            return if (custom()) cPrimaryDark else if (sDarkMode) 0xFF3DD68C.toInt() else 0xFF07C160.toInt()
        }

        /** 三级文字（弱化）：M3 outline */
        @JvmStatic
        fun textTertiary(): Int {
            return outline()
        }

        /** 主色底上的文字/图标：M3 onPrimary */
        @JvmStatic
        fun textOnPrimary(): Int {
            return onPrimary()
        }

        // ==================== 旧语义 getter 的 M3 角色映射（向后兼容） ====================
        @JvmStatic
        fun textPrimary(): Int {
            return onSurface()
        }

        @JvmStatic
        fun textSecondary(): Int {
            return onSurfaceVariant()
        }

        @JvmStatic
        fun textDisabled(): Int {
            return if (sDarkMode) D_OUTLINE_VARIANT else L_OUTLINE_VARIANT
        }

        @JvmStatic
        fun success(): Int {
            return primary()
        }

        @JvmStatic
        fun warning(): Int {
            return tertiary()
        }

        @JvmStatic
        fun danger(): Int {
            return error()
        }

        @JvmStatic
        fun info(): Int {
            return secondary()
        }

        @JvmStatic
        fun stroke(): Int {
            return outlineVariant()
        }

        @JvmStatic
        fun strokeFocus(): Int {
            return primary()
        }

        @JvmStatic
        fun shadowColor(): Int {
            return if (sDarkMode) 0x33000000.toInt() else 0x1A000000.toInt()
        }

        @JvmStatic
        fun pressOverlay(): Int {
            return stateLayerPressed()
        }

        @JvmStatic
        fun bgPage(): Int {
            return surface()
        }

        @JvmStatic
        fun bgCard(): Int {
            return surfaceContainerLow()
        }

        @JvmStatic
        fun bgInput(): Int {
            return surfaceContainerHighest()
        }

        @JvmStatic
        fun bgElevated(): Int {
            return surfaceContainerLow()
        }

        @JvmStatic
        fun bgMask(): Int {
            return if (sDarkMode) 0x88000000.toInt() else 0x66000000.toInt()
        }

        // ==================== 旧色板常量（保留兼容；取值切换为 M3 角色） ====================
        private val L_BG_GRADIENT_START = L_SURFACE_CONTAINER_LOW
        private val L_BG_GRADIENT_END = L_SURFACE
        private val L_CARD_BG = L_SURFACE_CONTAINER_LOWEST
        private val L_INPUT_BG = L_SURFACE_CONTAINER_HIGHEST
        private val L_TEXT_TITLE = L_ON_SURFACE
        private val L_TEXT_BODY = L_ON_SURFACE_VARIANT
        private val L_TEXT_NOTE = L_OUTLINE
        private val L_ACCENT = L_PRIMARY
        private val L_SWITCH_ON = L_PRIMARY
        private val L_SWITCH_OFF = L_SURFACE_VARIANT
        private val L_CANDY_PINK = L_PRIMARY_CONTAINER
        private val L_CANDY_YELLOW = L_TERTIARY_CONTAINER
        private val L_ARROW = L_OUTLINE
        private val L_DIVIDER = L_OUTLINE_VARIANT
        private val L_WHITE_TEXT = 0xFFFFFFFF.toInt()

        private val D_BG_GRADIENT_START = D_SURFACE
        private val D_BG_GRADIENT_END = D_SURFACE_CONTAINER_LOWEST
        private val D_CARD_BG = D_SURFACE_CONTAINER_LOW
        private val D_INPUT_BG = D_SURFACE_CONTAINER_HIGHEST
        private val D_TEXT_TITLE = D_ON_SURFACE
        private val D_TEXT_BODY = D_ON_SURFACE_VARIANT
        private val D_TEXT_NOTE = D_OUTLINE
        private val D_ACCENT = D_PRIMARY
        private val D_SWITCH_ON = D_PRIMARY
        private val D_SWITCH_OFF = D_SURFACE_VARIANT
        private val D_CANDY_PINK = D_PRIMARY_CONTAINER
        private val D_CANDY_YELLOW = D_TERTIARY_CONTAINER
        private val D_ARROW = D_OUTLINE
        private val D_DIVIDER = D_OUTLINE_VARIANT
        private val D_WHITE_TEXT = 0xFFFFFFFF.toInt()

        // 动态取色 (static final, computed after sDarkMode)
        @JvmField
        val BG_GRADIENT_START: Int = if (sDarkMode) D_BG_GRADIENT_START else L_BG_GRADIENT_START
        @JvmField
        val BG_GRADIENT_END: Int = if (sDarkMode) D_BG_GRADIENT_END else L_BG_GRADIENT_END
        @JvmField
        val CARD_BG: Int = if (sDarkMode) D_CARD_BG else L_CARD_BG
        @JvmField
        val INPUT_BG: Int = if (sDarkMode) D_INPUT_BG else L_INPUT_BG
        @JvmField
        val TEXT_TITLE: Int = if (sDarkMode) D_TEXT_TITLE else L_TEXT_TITLE
        @JvmField
        val TEXT_BODY: Int = if (sDarkMode) D_TEXT_BODY else L_TEXT_BODY
        @JvmField
        val TEXT_NOTE: Int = if (sDarkMode) D_TEXT_NOTE else L_TEXT_NOTE
        @JvmField
        val ACCENT: Int = if (sDarkMode) D_ACCENT else L_ACCENT
        @JvmField
        val SWITCH_ON: Int = if (sDarkMode) D_SWITCH_ON else L_SWITCH_ON
        @JvmField
        val SWITCH_OFF: Int = if (sDarkMode) D_SWITCH_OFF else L_SWITCH_OFF
        @JvmField
        val CANDY_PINK: Int = if (sDarkMode) D_CANDY_PINK else L_CANDY_PINK
        @JvmField
        val CANDY_YELLOW: Int = if (sDarkMode) D_CANDY_YELLOW else L_CANDY_YELLOW
        @JvmField
        val ARROW: Int = if (sDarkMode) D_ARROW else L_ARROW
        @JvmField
        val DIVIDER: Int = if (sDarkMode) D_DIVIDER else L_DIVIDER
        @JvmField
        val WHITE_TEXT: Int = if (sDarkMode) D_WHITE_TEXT else L_WHITE_TEXT
        @JvmField
        val candyPink: Int = if (sDarkMode) D_CANDY_PINK else L_CANDY_PINK
        @JvmField
        val candyYellow: Int = if (sDarkMode) D_CANDY_YELLOW else L_CANDY_YELLOW

        const val SWITCH_WIDTH_DP = 52   // M3 switch: 52×32
        const val SWITCH_HEIGHT_DP = 32
        const val SWITCH_RADIUS_DP = 16
        const val ITEM_HEIGHT_DP = 58
        const val DIALOG_RADIUS_DP = 28   // 全局弹窗/页面浮层统一外层圆角（M3 Expressive）

        // ==================== M3 Expressive 形状阶梯（dp） ====================
        const val SHAPE_XS_DP = 4
        const val SHAPE_SM_DP = 8
        const val SHAPE_MD_DP = 12
        const val SHAPE_LG_DP = 16
        const val SHAPE_CARD_DP = 24     // 卡片圆角
        const val SHAPE_INPUT_DP = 16    // 输入框圆角
        const val SHAPE_XL_DP = 28
        const val SHAPE_FULL_DP = 999    // 药丸/全圆角

        // ==================== M3 间距阶梯（dp） ====================
        const val SPACE_XS_DP = 4
        const val SPACE_SM_DP = 8
        const val SPACE_MD_DP = 12
        const val SPACE_LG_DP = 16
        const val SPACE_XL_DP = 24
        const val SPACE_CARD_GAP_DP = 13  // 页面级卡片垂直间距

        const val TOP_BAR_HEIGHT_DP = 52   // M3 top app bar
        const val ROW_HEIGHT_DP = 56       // M3 list item（≥48dp 触控区）
        const val BUTTON_HEIGHT_DP = 48    // M3 button（≥48dp 触控区）

        // ==================== M3 字阶（sp） ====================
        const val TYPE_HEADLINE_SMALL = 24f
        const val TYPE_TITLE_LARGE = 22f
        const val TYPE_TITLE_MEDIUM = 16f
        const val TYPE_TITLE_SMALL = 14f
        const val TYPE_BODY_LARGE = 16f
        const val TYPE_BODY_MEDIUM = 14f
        const val TYPE_BODY_SMALL = 12f
        const val TYPE_LABEL_LARGE = 14f
        const val TYPE_LABEL_MEDIUM = 12f
        const val TYPE_LABEL_SMALL = 11f
        const val TYPE_SECTION_TITLE = 19f        // 分区标题
        const val TYPE_SECTION_TITLE_LARGE = 20f // 页面大标题/分区大标题

        // ==================== 旧动态方法（全部保留，语义切换到 M3 角色） ====================
        @JvmStatic
        fun bg(): Int {
            return surfaceContainerLow()
        }

        @JvmStatic
        fun card(): Int {
            return surfaceContainerLowest()
        }

        @JvmStatic
        fun whiteCard(): Int {
            return surfaceContainerLowest()
        }

        @JvmStatic
        fun text1(): Int {
            return onSurface()
        }

        @JvmStatic
        fun text2(): Int {
            return onSurfaceVariant()
        }

        @JvmStatic
        fun text3(): Int {
            return outline()
        }

        @JvmStatic
        fun accent(): Int {
            return primary()
        }

        @JvmStatic
        fun accent2(): Int {
            return primary()
        }

        @JvmStatic
        fun onColor(): Int {
            return primary()
        }

        @JvmStatic
        fun offColor(): Int {
            return surfaceVariant()
        }

        @JvmStatic
        fun arrow(): Int {
            return outline()
        }

        @JvmStatic
        fun divider(): Int {
            return outlineVariant()
        }

        @JvmStatic
        fun border(): Int {
            return outlineVariant()
        }

        @JvmStatic
        fun inputBg(): Int {
            return surfaceContainerHighest()
        }

        @JvmStatic
        fun candyPink(): Int {
            return if (sDarkMode) D_CANDY_PINK else L_CANDY_PINK
        }

        @JvmStatic
        fun candyYellow(): Int {
            return if (sDarkMode) D_CANDY_YELLOW else L_CANDY_YELLOW
        }

        @JvmStatic
        fun whiteTextOnAccent(): Int {
            return if (sDarkMode) D_WHITE_TEXT else L_WHITE_TEXT
        }

        @JvmStatic
        fun bubbleSelfBg(): Int {
            return if (sDarkMode) D_PRIMARY_CONTAINER else L_PRIMARY_CONTAINER
        }

        @JvmStatic
        fun bubbleOtherBg(): Int {
            return if (sDarkMode) D_SURFACE_CONTAINER_HIGH else L_SURFACE_CONTAINER_HIGH
        }

        // ==================== AudioMix 时间线面板（M3 动态角色 · 自绘专用） ====================
        @JvmStatic
        fun timelinePanelTop(): Int {
            return surfaceContainerHigh()
        }

        @JvmStatic
        fun timelinePanelBottom(): Int {
            return surfaceContainerHighest()
        }

        @JvmStatic
        fun timelineRulerTop(): Int {
            return surfaceContainerLowest()
        }

        @JvmStatic
        fun timelineRulerBottom(): Int {
            return surfaceContainerLow()
        }

        @JvmStatic
        fun timelinePink(): Int {
            return primary()
        }

        @JvmStatic
        fun timelinePinkText(): Int {
            return onPrimaryContainer()
        }

        @JvmStatic
        fun timelinePinkSoft(): Int {
            return primaryContainer()
        }

        @JvmStatic
        fun timelinePinkPale(): Int {
            return secondaryContainer()
        }

        @JvmStatic
        fun timelineAmber(): Int {
            return 0xFFFFE08A.toInt()
        }

        @JvmStatic
        fun timelineGridMinor(): Int {
            return (0x20 shl 24) or (primary() and 0x00FFFFFF.toInt())
        }

        @JvmStatic
        fun timelineGridMajor(): Int {
            return (0x30 shl 24) or (tertiary() and 0x00FFFFFF.toInt())
        }

        @JvmStatic
        fun timelineBlockBase(): Int {
            return 0x26FFFFFF.toInt()
        }

        @JvmStatic
        fun timelineDimOverlay(): Int {
            return 0x6E0A0612.toInt()
        }

        @JvmStatic
        fun timelineDimPlain(): Int {
            return 0x2E000000.toInt()
        }

        @JvmStatic
        fun timelineDeleteBadge(): Int {
            return 0xFFE0455F.toInt()
        }

        @JvmStatic
        fun timelineBadgeDark(): Int {
            return 0xB30A0612.toInt()
        }

        @JvmStatic
        fun timelineWhite(): Int {
            return 0xFFFFFFFF.toInt()
        }

        @JvmStatic
        fun timelineBarAlt1(): Int {
            return secondary()
        }

        @JvmStatic
        fun timelineBarAlt2(): Int {
            return tertiary()
        }

        /** AI 图标分类调色板（M3 动态角色色，10 个分类图标在主题色系内轮换）。 */
        @JvmStatic
        fun aiIconPalette(): IntArray {
            val p = primary()
            val t = tertiary()
            val s = secondary()
            val pc = primaryContainer()
            val tc = tertiaryContainer()
            val sc = secondaryContainer()
            return intArrayOf(
                p,   // spark 总开关
                t,   // cloud 服务商
                p,   // chip 模型/核心
                s,   // user 人设/会话
                t,   // bell 唤醒/@
                p,   // voice 语音
                s,   // eq 音色
                t,   // db 记忆
                p,   // layers 模板
                s    // sliders 独立配置
            )
        }
    }
}
