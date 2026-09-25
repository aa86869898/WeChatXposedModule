package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;

/**
 * LeShaoWeChat 设计令牌 —— Material 3 (Material You) 色彩体系。
 *
 * 兼容性铁律：旧公开常量与旧方法全部保留（全项目 20+ 页面依赖，禁止删除/改语义）；
 * M3 色彩角色（primary/onPrimary/primaryContainer/surface/surfaceVariant/outline/…）
 * 一律走动态 getter，支持暗色模式运行时切换。
 *
 * 主题：葡萄气泡 · 流光渐变（#8B5CF6 / #D8B4FE / #FFAFCC，浅色 + 暗色双方案）。
 */
public class AppColors {

    private static volatile boolean sDarkMode;

    // ==================== M3 多配色方案（v1015：12 套 + 自定义 + 动态取色） ====================
    public static final int PALETTE_GREEN  = 0;
    public static final int PALETTE_BLUE   = 1;
    public static final int PALETTE_PURPLE = 2;
    public static final int PALETTE_ORANGE = 3;
    public static final int PALETTE_PINK   = 4;
    public static final int PALETTE_TEAL   = 5;
    public static final int PALETTE_RED    = 6;
    public static final int PALETTE_AMBER  = 7;
    public static final int PALETTE_CYAN   = 8;
    public static final int PALETTE_INDIGO = 9;
    public static final int PALETTE_VIOLET = 10;
    public static final int PALETTE_GRAPHITE = 11;
    /** 固定方案数量 */
    public static final int PALETTE_COUNT = 12;
    /** 自定义调色板（seed 存于 KEY_CUSTOM_SEED） */
    public static final int PALETTE_CUSTOM = 100;
    /** 动态取色（Material You，Android 12+ 系统强调色） */
    public static final int PALETTE_DYNAMIC = 101;

    private static final String[] PALETTE_NAMES = {
        "葡萄紫", "薰衣草", "品红", "玫瑰粉", "樱粉", "兰紫",
        "深紫", "藕荷", "玫紫", "紫罗兰", "粉紫", "石墨紫"
    };
    private static final int[] PALETTE_SEEDS = {
        0xFF8B5CF6, // 葡萄紫（主题）
        0xFFA78BFA, // 薰衣草
        0xFFD946EF, // 品红
        0xFFFF6FB0, // 玫瑰粉
        0xFFFFAFCC, // 樱粉
        0xFF9F7BFF, // 兰紫
        0xFF7C3AED, // 深紫
        0xFFC9A9FF, // 藕荷
        0xFFB56BF0, // 玫紫
        0xFF9333EA, // 紫罗兰
        0xFFDB2777, // 粉紫
        0xFF6B5B95, // 石墨紫
    };
    private static final String PREFS = "leshao_m3_prefs";
    private static final String KEY_PALETTE = "m3_palette";
    private static final String KEY_CUSTOM_SEED = "m3_custom_seed";
    private static final String KEY_TITLEBAR = "m3_override_titlebar";
    private static final String KEY_SWITCH = "m3_override_switch";
    private static final String KEY_WINDOWBG = "m3_override_windowbg";
    private static volatile int sPalette = PALETTE_DYNAMIC;
    private static volatile int sCustomSeed = 0xFF8B5CF6;
    private static volatile int sSchemeVersion = 0;
    /** 三个自定义色（0 表示未设置，走默认角色） */
    private static volatile int sOvTitleBar = 0, sOvSwitch = 0, sOvWindowBg = 0;

    // 当前调色板解析后的角色（refresh 时重算，getter 直接读）
    private static volatile int cPrimary, cOnPrimary, cPrimaryContainer, cOnPrimaryContainer;
    private static volatile int cSecondary, cOnSecondary, cSecondaryContainer, cOnSecondaryContainer;
    private static volatile int cTertiary, cOnTertiary, cTertiaryContainer, cOnTertiaryContainer;
    private static volatile int cPrimaryDark;
    // v1017: 中性色（背景/表面）也随调色板走，保证「背景色」全局适配
    private static volatile int cBackground, cSurface, cOnSurface, cSurfaceVariant, cOnSurfaceVariant;
    private static volatile int cScLowest, cScLow, cSc, cScHigh, cScHighest;
    private static volatile int cOutline, cOutlineVariant;

    // 必须在 PALETTE_* 声明之后执行（初始化顺序铁律）
    static {
        sDarkMode = detectDarkMode();
        loadPaletteFromPrefs();
        recomputePalette();
    }

    private static void loadPaletteFromPrefs() {
        // v1018: 模块统一使用 M3 动态配色（Material You），全局所有界面实时生效；
        // 配色选择功能已移除，不再读取用户选择，单元素颜色覆盖一并复位。
        // Android 12+ 读取系统强调色，低版本由 resolveSeed() 回退到微信绿 seed。
        sPalette = PALETTE_DYNAMIC;
        sOvTitleBar = 0;
        sOvSwitch = 0;
        sOvWindowBg = 0;
        try {
            Context ctx = com.leshao.v3.ContextManager.getAppContext();
            if (ctx == null) return;
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, 0);
            sCustomSeed = sp.getInt(KEY_CUSTOM_SEED, sCustomSeed);
        } catch (Throwable ignored) {}
    }

    private static int adjust(int color, float satMul, float valMul) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(0f, Math.min(1f, hsv[1] * satMul));
        hsv[2] = Math.max(0f, Math.min(1f, hsv[2] * valMul));
        return Color.HSVToColor(hsv);
    }

    private static int hueShift(int color, float deg) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + deg) % 360f;
        return Color.HSVToColor(hsv);
    }

    /** 解析当前方案对应的 seed 颜色 */
    private static int resolveSeed() {
        if (sPalette == PALETTE_DYNAMIC) {
            int dyn = detectDynamicSeed();
            return dyn != 0 ? dyn : sCustomSeed;
        }
        if (sPalette == PALETTE_CUSTOM) return sCustomSeed;
        int idx = Math.max(0, Math.min(sPalette, PALETTE_SEEDS.length - 1));
        return PALETTE_SEEDS[idx];
    }

    /** 动态取色：Android 12+ 读取系统强调色（Material You），不可用时返回 0 */
    private static int detectDynamicSeed() {
        if (android.os.Build.VERSION.SDK_INT < 31) return 0;
        try {
            Context ctx = com.leshao.v3.ContextManager.getAppContext();
            if (ctx == null) return 0;
            return ctx.getResources().getColor(android.R.color.system_accent1_500,
                    ctx.getTheme());
        } catch (Throwable ignored) {}
        try {
            Context ctx = com.leshao.v3.ContextManager.getAppContext();
            if (ctx != null) {
                return ctx.getResources().getColor(android.R.color.system_accent1_500);
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    /** 动态取色是否可用（Android 12+） */
    public static boolean dynamicColorAvailable() {
        return android.os.Build.VERSION.SDK_INT >= 31 && detectDynamicSeed() != 0;
    }

    /**
     * 依据明暗模式重算当前配色角色。
     *
     * <p>v1067：全局锁定「葡萄气泡」主题（#8B5CF6 紫 / #D8B4FE 淡紫 / #FFAFCC 粉），
     * 不再随系统强调色或历史配色选择变化，保证所有界面/弹窗/控件视觉完全一致。
     * 旧 PALETTE_* API 与 getter 全部保留，仅取值来源固定为葡萄气泡方案。</p>
     */
    private static void recomputePalette() {
        boolean dark = sDarkMode;
        if (dark) {
            // 葡萄气泡 · 深色
            cPrimary              = 0xFFC9A9FF;
            cOnPrimary            = 0xFF2A1550;
            cPrimaryContainer     = 0xFF4A2A82;
            cOnPrimaryContainer   = 0xFFEADDFF;
            cSecondary            = 0xFFCFC0EA;
            cOnSecondary          = 0xFF332B45;
            cSecondaryContainer   = 0xFF453A5C;
            cOnSecondaryContainer = 0xFFE8DEF8;
            cTertiary             = 0xFFFFB0CE;
            cOnTertiary           = 0xFF560027;
            cTertiaryContainer    = 0xFF7A3A57;
            cOnTertiaryContainer  = 0xFFFFD9E6;
            cPrimaryDark          = 0xFF9F7BFF;

            cBackground           = 0xFF150F1E;
            cSurface              = 0xFF150F1E;
            cScLowest             = 0xFF100A18;
            cScLow                = 0xFF1D1530;
            cSc                   = 0xFF241B38;
            cScHigh               = 0xFF2A2040;
            cScHighest            = 0xFF332845;
            cSurfaceVariant       = 0xFF2A2040;
            cOnSurface            = 0xFFF2ECFB;
            cOnSurfaceVariant     = 0xFFB6A6D1;
            cOutline              = 0xFF8A79A8;
            cOutlineVariant       = 0xFF3B2F55;
        } else {
            // 葡萄气泡 · 浅色
            cPrimary              = 0xFF8B5CF6;
            cOnPrimary            = 0xFFFFFFFF;
            cPrimaryContainer     = 0xFFEADDFF;
            cOnPrimaryContainer   = 0xFF21005D;
            cSecondary            = 0xFF7C6BA8;
            cOnSecondary          = 0xFFFFFFFF;
            cSecondaryContainer   = 0xFFE8DEF8;
            cOnSecondaryContainer = 0xFF21005D;
            cTertiary             = 0xFFD5497E;
            cOnTertiary           = 0xFFFFFFFF;
            cTertiaryContainer    = 0xFFFFD9E6;
            cOnTertiaryContainer  = 0xFF3A001C;
            cPrimaryDark          = 0xFF7C3AED;

            cBackground           = 0xFFFBF9FF;
            cSurface              = 0xFFFBF9FF;
            cScLowest             = 0xFFFFFFFF;
            cScLow                = 0xFFF6F1FF;
            cSc                   = 0xFFF1EAFE;
            cScHigh               = 0xFFECE3FB;
            cScHighest            = 0xFFE7DCF8;
            cSurfaceVariant       = 0xFFE6DCF7;
            cOnSurface            = 0xFF2A2340;
            cOnSurfaceVariant     = 0xFF6F6389;
            cOutline              = 0xFF9A8CB8;
            cOutlineVariant       = 0xFFDCCFF2;
        }
    }

    public static int getPalette() { return sPalette; }
    public static int getSchemeVersion() { return sSchemeVersion; }
    public static String[] paletteNames() { return PALETTE_NAMES.clone(); }
    public static int paletteCount() { return PALETTE_COUNT; }
    public static String paletteName(int id) {
        if (id == PALETTE_CUSTOM) return "自定义";
        if (id == PALETTE_DYNAMIC) return "动态取色";
        if (id < 0 || id >= PALETTE_NAMES.length) return PALETTE_NAMES[0];
        return PALETTE_NAMES[id];
    }
    public static int paletteSeed(int id) {
        if (id == PALETTE_CUSTOM) return sCustomSeed;
        if (id == PALETTE_DYNAMIC) {
            int d = detectDynamicSeed();
            return d != 0 ? d : sCustomSeed;
        }
        if (id < 0 || id >= PALETTE_SEEDS.length) return PALETTE_SEEDS[0];
        return PALETTE_SEEDS[id];
    }
    public static int getCustomSeed() { return sCustomSeed; }
    /** 当前解析后的 seed（供预览/取色器回显） */
    public static int currentSeed() { return resolveSeed(); }

    private static SharedPreferences prefs() {
        try {
            Context ctx = com.leshao.v3.ContextManager.getAppContext();
            if (ctx != null) return ctx.getSharedPreferences(PREFS, 0);
        } catch (Throwable ignored) {}
        return null;
    }

    /** 切换配色方案：持久化 + 立即重算（实时生效） */
    public static void setPalette(int id) {
        if (id != PALETTE_CUSTOM && id != PALETTE_DYNAMIC
                && (id < 0 || id >= PALETTE_COUNT)) return;
        sPalette = id;
        try {
            SharedPreferences sp = prefs();
            if (sp != null) sp.edit().putInt(KEY_PALETTE, id).apply();
        } catch (Throwable ignored) {}
        recomputePalette();
        sSchemeVersion++;
    }

    /** 设置自定义调色板 seed 并切到自定义方案 */
    public static void setCustomPalette(int seed) {
        sCustomSeed = seed;
        sPalette = PALETTE_CUSTOM;
        try {
            SharedPreferences sp = prefs();
            if (sp != null) sp.edit()
                    .putInt(KEY_CUSTOM_SEED, seed)
                    .putInt(KEY_PALETTE, PALETTE_CUSTOM).apply();
        } catch (Throwable ignored) {}
        recomputePalette();
        sSchemeVersion++;
    }

    // ==================== 单元素自定义色（v1015） ====================
    public static int titleBar()       { return sOvTitleBar != 0 ? sOvTitleBar : primary(); }
    public static int switchColor()    { return sOvSwitch != 0 ? sOvSwitch : primary(); }
    public static int windowBg()       { return sOvWindowBg != 0 ? sOvWindowBg : surface(); }
    public static boolean hasTitleBarOverride() { return sOvTitleBar != 0; }
    public static boolean hasSwitchOverride()   { return sOvSwitch != 0; }
    public static boolean hasWindowBgOverride() { return sOvWindowBg != 0; }

    public static void setTitleBarColor(int color) {
        sOvTitleBar = color;
        try {
            SharedPreferences sp = prefs();
            if (sp != null) sp.edit().putInt(KEY_TITLEBAR, color).apply();
        } catch (Throwable ignored) {}
        sSchemeVersion++;
    }
    public static void setSwitchColor(int color) {
        sOvSwitch = color;
        try {
            SharedPreferences sp = prefs();
            if (sp != null) sp.edit().putInt(KEY_SWITCH, color).apply();
        } catch (Throwable ignored) {}
        sSchemeVersion++;
    }
    public static void setWindowBgColor(int color) {
        sOvWindowBg = color;
        try {
            SharedPreferences sp = prefs();
            if (sp != null) sp.edit().putInt(KEY_WINDOWBG, color).apply();
        } catch (Throwable ignored) {}
        sSchemeVersion++;
    }

    /** 清除全部单元素自定义色 */
    public static void clearOverrides() {
        sOvTitleBar = 0;
        sOvSwitch = 0;
        sOvWindowBg = 0;
        try {
            SharedPreferences sp = prefs();
            if (sp != null) sp.edit()
                    .remove(KEY_TITLEBAR).remove(KEY_SWITCH).remove(KEY_WINDOWBG).apply();
        } catch (Throwable ignored) {}
        sSchemeVersion++;
    }

    /** 文字自动对比色：在给定底色上选择黑/白以保证可读性 */
    public static int onColor(int bg) {
        double lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255.0;
        return lum > 0.6 ? 0xFF1B1B1B : 0xFFFFFFFF;
    }

    private static boolean detectDarkMode() {
        // 优先用微信内部暗色检测（bk.C），更贴合微信「深色模式」设置；失败回退系统 uiMode
        try {
            ClassLoader cl = com.leshao.v3.ContextManager.getClassLoader();
            if (cl != null) {
                Class<?> bk = de.robv.android.xposed.XposedHelpers.findClass("com.tencent.mm.ui.bk", cl);
                Object r = de.robv.android.xposed.XposedHelpers.callStaticMethod(bk, "C");
                if (r instanceof Boolean) return (Boolean) r;
            }
        } catch (Throwable ignored) {}
        try {
            Context ctx = com.leshao.v3.ContextManager.getAppContext();
            if (ctx != null) {
                int nightMode = ctx.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                return nightMode == Configuration.UI_MODE_NIGHT_YES;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 页面 onResume 时调用：微信内切换深色模式后刷新令牌（旧静态常量不失效，新 getter 立即跟随） */
    public static void refresh() {
        try {
            boolean prev = sDarkMode;
            sDarkMode = detectDarkMode();
            if (prev != sDarkMode) recomputePalette();
        } catch (Throwable ignored) {}
    }

    public static void init(Activity act) { refresh(); }
    public static boolean isDarkMode() { return sDarkMode; }

    // ==================== 葡萄气泡 · 流光渐变令牌（v1067 全局锁定） ====================
    /** 渐变起始（紫） */
    public static int gradientStart() { return sDarkMode ? 0xFF9F7BFF : 0xFF8B5CF6; }
    /** 渐变中段（淡紫） */
    public static int gradientMid()   { return sDarkMode ? 0xFFDCC3FF : 0xFFD8B4FE; }
    /** 渐变收尾（粉） */
    public static int gradientEnd()   { return sDarkMode ? 0xFFFFB8D4 : 0xFFFFAFCC; }
    /** 渐变上的文字/图标色（白） */
    public static int onGradient()    { return 0xFFFFFFFF; }
    /** 三色流光循环（start → mid → end） */
    public static int[] gradientColors() {
        return new int[]{ gradientStart(), gradientMid(), gradientEnd() };
    }
    /** 渐变按压态（用于状态层/按下变暗） */
    public static int gradientPressed() { return sDarkMode ? 0xFF8A5CF0 : 0xFF6D28D9; }

    // ==================== M3 色彩角色（浅色方案 · 葡萄气泡） ====================
    private static final int L_PRIMARY            = 0xFF8B5CF6;
    private static final int L_ON_PRIMARY         = 0xFFFFFFFF;
    private static final int L_PRIMARY_CONTAINER  = 0xFFEADDFF;
    private static final int L_ON_PRIMARY_CONTAINER = 0xFF21005D;
    private static final int L_SECONDARY          = 0xFF7C6BA8;
    private static final int L_ON_SECONDARY       = 0xFFFFFFFF;
    private static final int L_SECONDARY_CONTAINER = 0xFFE8DEF8;
    private static final int L_ON_SECONDARY_CONTAINER = 0xFF21005D;
    private static final int L_TERTIARY           = 0xFFD5497E;
    private static final int L_ON_TERTIARY        = 0xFFFFFFFF;
    private static final int L_TERTIARY_CONTAINER = 0xFFFFD9E6;
    private static final int L_ON_TERTIARY_CONTAINER = 0xFF3A001C;
    private static final int L_ERROR              = 0xFFBA1A1A;
    private static final int L_ON_ERROR           = 0xFFFFFFFF;
    private static final int L_ERROR_CONTAINER    = 0xFFFFDAD6;
    private static final int L_ON_ERROR_CONTAINER = 0xFF410002;
    private static final int L_BACKGROUND         = 0xFFFBF9FF;
    private static final int L_ON_BACKGROUND      = 0xFF2A2340;
    private static final int L_SURFACE            = 0xFFFBF9FF;
    private static final int L_ON_SURFACE         = 0xFF2A2340;
    private static final int L_SURFACE_VARIANT    = 0xFFE6DCF7;
    private static final int L_ON_SURFACE_VARIANT = 0xFF6F6389;
    private static final int L_SURFACE_CONTAINER_LOWEST = 0xFFFFFFFF;
    private static final int L_SURFACE_CONTAINER_LOW = 0xFFF6F1FF;
    private static final int L_SURFACE_CONTAINER = 0xFFF1EAFE;
    private static final int L_SURFACE_CONTAINER_HIGH = 0xFFECE3FB;
    private static final int L_SURFACE_CONTAINER_HIGHEST = 0xFFE7DCF8;
    private static final int L_OUTLINE            = 0xFF9A8CB8;
    private static final int L_OUTLINE_VARIANT    = 0xFFDCCFF2;
    private static final int L_INVERSE_SURFACE    = 0xFF2E2440;
    private static final int L_INVERSE_ON_SURFACE = 0xFFF1EAFE;

    // ==================== M3 色彩角色（暗色方案 · 葡萄气泡） ====================
    private static final int D_PRIMARY            = 0xFFC9A9FF;
    private static final int D_ON_PRIMARY         = 0xFF2A1550;
    private static final int D_PRIMARY_CONTAINER  = 0xFF4A2A82;
    private static final int D_ON_PRIMARY_CONTAINER = 0xFFEADDFF;
    private static final int D_SECONDARY          = 0xFFCFC0EA;
    private static final int D_ON_SECONDARY       = 0xFF332B45;
    private static final int D_SECONDARY_CONTAINER = 0xFF453A5C;
    private static final int D_ON_SECONDARY_CONTAINER = 0xFFE8DEF8;
    private static final int D_TERTIARY           = 0xFFFFB0CE;
    private static final int D_ON_TERTIARY        = 0xFF560027;
    private static final int D_TERTIARY_CONTAINER = 0xFF7A3A57;
    private static final int D_ON_TERTIARY_CONTAINER = 0xFFFFD9E6;
    private static final int D_ERROR              = 0xFFFFB4AB;
    private static final int D_ON_ERROR           = 0xFF690005;
    private static final int D_ERROR_CONTAINER    = 0xFF93000A;
    private static final int D_ON_ERROR_CONTAINER = 0xFFFFDAD6;
    private static final int D_BACKGROUND         = 0xFF150F1E;
    private static final int D_ON_BACKGROUND      = 0xFFF2ECFB;
    private static final int D_SURFACE            = 0xFF150F1E;
    private static final int D_ON_SURFACE         = 0xFFF2ECFB;
    private static final int D_SURFACE_VARIANT    = 0xFF2A2040;
    private static final int D_ON_SURFACE_VARIANT = 0xFFB6A6D1;
    private static final int D_SURFACE_CONTAINER_LOWEST = 0xFF100A18;
    private static final int D_SURFACE_CONTAINER_LOW = 0xFF1D1530;
    private static final int D_SURFACE_CONTAINER = 0xFF241B38;
    private static final int D_SURFACE_CONTAINER_HIGH = 0xFF2A2040;
    private static final int D_SURFACE_CONTAINER_HIGHEST = 0xFF332845;
    private static final int D_OUTLINE            = 0xFF8A79A8;
    private static final int D_OUTLINE_VARIANT    = 0xFF3B2F55;
    private static final int D_INVERSE_SURFACE    = 0xFFEADDFF;
    private static final int D_INVERSE_ON_SURFACE = 0xFF2A1550;

    // ==================== M3 getter（暗色实时跟随） ====================
    private static boolean custom() { return sPalette != PALETTE_GREEN; }
    public static int primary()              { return custom() ? cPrimary : (sDarkMode ? D_PRIMARY : L_PRIMARY); }
    public static int onPrimary()            { return custom() ? cOnPrimary : (sDarkMode ? D_ON_PRIMARY : L_ON_PRIMARY); }
    public static int primaryContainer()     { return custom() ? cPrimaryContainer : (sDarkMode ? D_PRIMARY_CONTAINER : L_PRIMARY_CONTAINER); }
    public static int onPrimaryContainer()   { return custom() ? cOnPrimaryContainer : (sDarkMode ? D_ON_PRIMARY_CONTAINER : L_ON_PRIMARY_CONTAINER); }
    public static int secondary()            { return custom() ? cSecondary : (sDarkMode ? D_SECONDARY : L_SECONDARY); }
    public static int onSecondary()          { return custom() ? cOnSecondary : (sDarkMode ? D_ON_SECONDARY : L_ON_SECONDARY); }
    public static int secondaryContainer()   { return custom() ? cSecondaryContainer : (sDarkMode ? D_SECONDARY_CONTAINER : L_SECONDARY_CONTAINER); }
    public static int onSecondaryContainer() { return custom() ? cOnSecondaryContainer : (sDarkMode ? D_ON_SECONDARY_CONTAINER : L_ON_SECONDARY_CONTAINER); }
    public static int tertiary()             { return custom() ? cTertiary : (sDarkMode ? D_TERTIARY : L_TERTIARY); }
    public static int onTertiary()           { return custom() ? cOnTertiary : (sDarkMode ? D_ON_TERTIARY : L_ON_TERTIARY); }
    public static int tertiaryContainer()    { return custom() ? cTertiaryContainer : (sDarkMode ? D_TERTIARY_CONTAINER : L_TERTIARY_CONTAINER); }
    public static int onTertiaryContainer()  { return custom() ? cOnTertiaryContainer : (sDarkMode ? D_ON_TERTIARY_CONTAINER : L_ON_TERTIARY_CONTAINER); }
    public static int error()                { return sDarkMode ? D_ERROR : L_ERROR; }
    public static int onError()              { return sDarkMode ? D_ON_ERROR : L_ON_ERROR; }
    public static int errorContainer()       { return sDarkMode ? D_ERROR_CONTAINER : L_ERROR_CONTAINER; }
    public static int onErrorContainer()     { return sDarkMode ? D_ON_ERROR_CONTAINER : L_ON_ERROR_CONTAINER; }
    public static int background()           { return custom() ? cBackground : (sDarkMode ? D_BACKGROUND : L_BACKGROUND); }
    public static int onBackground()         { return custom() ? cOnSurface : (sDarkMode ? D_ON_BACKGROUND : L_ON_BACKGROUND); }
    public static int surface()              { return custom() ? cSurface : (sDarkMode ? D_SURFACE : L_SURFACE); }
    public static int onSurface()            { return custom() ? cOnSurface : (sDarkMode ? D_ON_SURFACE : L_ON_SURFACE); }
    public static int surfaceVariant()       { return custom() ? cSurfaceVariant : (sDarkMode ? D_SURFACE_VARIANT : L_SURFACE_VARIANT); }
    public static int onSurfaceVariant()     { return custom() ? cOnSurfaceVariant : (sDarkMode ? D_ON_SURFACE_VARIANT : L_ON_SURFACE_VARIANT); }
    public static int surfaceContainerLowest()  { return custom() ? cScLowest : (sDarkMode ? D_SURFACE_CONTAINER_LOWEST : L_SURFACE_CONTAINER_LOWEST); }
    public static int surfaceContainerLow()     { return custom() ? cScLow : (sDarkMode ? D_SURFACE_CONTAINER_LOW : L_SURFACE_CONTAINER_LOW); }
    public static int surfaceContainer()        { return custom() ? cSc : (sDarkMode ? D_SURFACE_CONTAINER : L_SURFACE_CONTAINER); }
    public static int surfaceContainerHigh()    { return custom() ? cScHigh : (sDarkMode ? D_SURFACE_CONTAINER_HIGH : L_SURFACE_CONTAINER_HIGH); }
    public static int surfaceContainerHighest() { return custom() ? cScHighest : (sDarkMode ? D_SURFACE_CONTAINER_HIGHEST : L_SURFACE_CONTAINER_HIGHEST); }
    public static int outline()              { return custom() ? cOutline : (sDarkMode ? D_OUTLINE : L_OUTLINE); }
    public static int outlineVariant()       { return custom() ? cOutlineVariant : (sDarkMode ? D_OUTLINE_VARIANT : L_OUTLINE_VARIANT); }
    public static int inverseSurface()       { return sDarkMode ? D_INVERSE_SURFACE : L_INVERSE_SURFACE; }
    public static int inverseOnSurface()     { return sDarkMode ? D_INVERSE_ON_SURFACE : L_INVERSE_ON_SURFACE; }

    /** M3 状态层：按压 12% / 悬浮 8%（由调用方与底色叠加） */
    public static int stateLayerPressed() { return 0x1F000000; }
    public static int stateLayerHover()   { return 0x14000000; }
    /** M3 主色状态层（用于 primary 底上的涟漪） */
    public static int stateLayerOnPrimary() { return 0x14FFFFFF; }

    // ==================== 便捷别名（M3 角色映射，供组件工厂使用） ====================
    /** 主色按压深阶（渐变/按压态用）：浅色 #7C3AED，暗色 #9F7BFF */
    public static int primaryDark()  { return custom() ? cPrimaryDark : (sDarkMode ? 0xFF9F7BFF : 0xFF7C3AED); }
    /** 三级文字（弱化）：M3 outline */
    public static int textTertiary() { return outline(); }
    /** 主色底上的文字/图标：M3 onPrimary */
    public static int textOnPrimary() { return onPrimary(); }

    // ==================== 旧语义 getter 的 M3 角色映射（向后兼容） ====================
    public static int textPrimary()   { return onSurface(); }
    public static int textSecondary() { return onSurfaceVariant(); }
    public static int textDisabled()  { return sDarkMode ? D_OUTLINE_VARIANT : L_OUTLINE_VARIANT; }
    public static int success()       { return primary(); }
    public static int warning()       { return tertiary(); }
    public static int danger()        { return error(); }
    public static int info()          { return secondary(); }
    public static int stroke()        { return outlineVariant(); }
    public static int strokeFocus()   { return primary(); }
    public static int shadowColor()   { return sDarkMode ? 0x33000000 : 0x1A000000; }
    public static int pressOverlay()  { return stateLayerPressed(); }
    public static int bgPage()        { return surface(); }
    public static int bgCard()        { return surfaceContainerLow(); }
    public static int bgInput()       { return surfaceContainerHighest(); }
    public static int bgElevated()    { return surfaceContainerLow(); }
    public static int bgMask()        { return sDarkMode ? 0x88000000 : 0x66000000; }

    // ==================== 旧色板常量（保留兼容；取值切换为 M3 角色） ====================
    private static final int L_BG_GRADIENT_START = L_SURFACE_CONTAINER_LOW;
    private static final int L_BG_GRADIENT_END   = L_SURFACE;
    private static final int L_CARD_BG    = L_SURFACE_CONTAINER_LOWEST;
    private static final int L_INPUT_BG   = L_SURFACE_CONTAINER_HIGHEST;
    private static final int L_TEXT_TITLE = L_ON_SURFACE;
    private static final int L_TEXT_BODY  = L_ON_SURFACE_VARIANT;
    private static final int L_TEXT_NOTE  = L_OUTLINE;
    private static final int L_ACCENT     = L_PRIMARY;
    private static final int L_SWITCH_ON  = L_PRIMARY;
    private static final int L_SWITCH_OFF = L_SURFACE_VARIANT;
    private static final int L_CANDY_PINK   = L_PRIMARY_CONTAINER;   // 旧糖果粉 → M3 primaryContainer
    private static final int L_CANDY_YELLOW = L_TERTIARY_CONTAINER;  // 旧柠黄 → M3 tertiaryContainer
    private static final int L_ARROW    = L_OUTLINE;
    private static final int L_DIVIDER  = L_OUTLINE_VARIANT;
    private static final int L_WHITE_TEXT = 0xFFFFFFFF;  // 渐变/主色面统一白字

    private static final int D_BG_GRADIENT_START = D_SURFACE;
    private static final int D_BG_GRADIENT_END   = D_SURFACE_CONTAINER_LOWEST;
    private static final int D_CARD_BG    = D_SURFACE_CONTAINER_LOW;
    private static final int D_INPUT_BG   = D_SURFACE_CONTAINER_HIGHEST;
    private static final int D_TEXT_TITLE = D_ON_SURFACE;
    private static final int D_TEXT_BODY  = D_ON_SURFACE_VARIANT;
    private static final int D_TEXT_NOTE  = D_OUTLINE;
    private static final int D_ACCENT     = D_PRIMARY;
    private static final int D_SWITCH_ON  = D_PRIMARY;
    private static final int D_SWITCH_OFF = D_SURFACE_VARIANT;
    private static final int D_CANDY_PINK   = D_PRIMARY_CONTAINER;
    private static final int D_CANDY_YELLOW = D_TERTIARY_CONTAINER;
    private static final int D_ARROW    = D_OUTLINE;
    private static final int D_DIVIDER  = D_OUTLINE_VARIANT;
    private static final int D_WHITE_TEXT = 0xFFFFFFFF;  // 渐变/主色面统一白字

    // 动态取色 (static final, computed after sDarkMode)
    public static final int BG_GRADIENT_START = sDarkMode ? D_BG_GRADIENT_START : L_BG_GRADIENT_START;
    public static final int BG_GRADIENT_END   = sDarkMode ? D_BG_GRADIENT_END   : L_BG_GRADIENT_END;
    public static final int CARD_BG    = sDarkMode ? D_CARD_BG    : L_CARD_BG;
    public static final int INPUT_BG   = sDarkMode ? D_INPUT_BG   : L_INPUT_BG;
    public static final int TEXT_TITLE = sDarkMode ? D_TEXT_TITLE : L_TEXT_TITLE;
    public static final int TEXT_BODY  = sDarkMode ? D_TEXT_BODY  : L_TEXT_BODY;
    public static final int TEXT_NOTE  = sDarkMode ? D_TEXT_NOTE  : L_TEXT_NOTE;
    public static final int ACCENT     = sDarkMode ? D_ACCENT     : L_ACCENT;
    public static final int SWITCH_ON  = sDarkMode ? D_SWITCH_ON  : L_SWITCH_ON;
    public static final int SWITCH_OFF = sDarkMode ? D_SWITCH_OFF : L_SWITCH_OFF;
    public static final int CANDY_PINK   = sDarkMode ? D_CANDY_PINK   : L_CANDY_PINK;
    public static final int CANDY_YELLOW = sDarkMode ? D_CANDY_YELLOW : L_CANDY_YELLOW;
    public static final int ARROW    = sDarkMode ? D_ARROW    : L_ARROW;
    public static final int DIVIDER  = sDarkMode ? D_DIVIDER  : L_DIVIDER;
    public static final int WHITE_TEXT = sDarkMode ? D_WHITE_TEXT : L_WHITE_TEXT;

    public static final int candyPink   = sDarkMode ? D_CANDY_PINK   : L_CANDY_PINK;
    public static final int candyYellow = sDarkMode ? D_CANDY_YELLOW : L_CANDY_YELLOW;

    public static final int SWITCH_WIDTH_DP  = 52;   // M3 switch: 52×32
    public static final int SWITCH_HEIGHT_DP = 32;
    public static final int SWITCH_RADIUS_DP = 16;
    public static final int ITEM_HEIGHT_DP = 58;
    public static final int DIALOG_RADIUS_DP = 28;   // M3 extra-large shape

    // ==================== M3 形状阶梯（dp） ====================
    public static final int SHAPE_XS_DP = 4;
    public static final int SHAPE_SM_DP = 8;
    public static final int SHAPE_MD_DP = 12;
    public static final int SHAPE_LG_DP = 16;
    public static final int SHAPE_XL_DP = 28;
    public static final int SHAPE_FULL_DP = 999;

    // ==================== M3 间距阶梯（dp） ====================
    public static final int SPACE_XS_DP = 4;
    public static final int SPACE_SM_DP = 8;
    public static final int SPACE_MD_DP = 12;
    public static final int SPACE_LG_DP = 16;
    public static final int SPACE_XL_DP = 24;

    public static final int TOP_BAR_HEIGHT_DP = 52;   // M3 top app bar
    public static final int ROW_HEIGHT_DP = 50;       // M3 list item (one line)
    public static final int BUTTON_HEIGHT_DP = 40;    // M3 button

    // ==================== M3 字阶（sp） ====================
    public static final float TYPE_HEADLINE_SMALL = 24f;
    public static final float TYPE_TITLE_LARGE = 22f;
    public static final float TYPE_TITLE_MEDIUM = 16f;
    public static final float TYPE_TITLE_SMALL = 14f;
    public static final float TYPE_BODY_LARGE = 16f;
    public static final float TYPE_BODY_MEDIUM = 14f;
    public static final float TYPE_BODY_SMALL = 12f;
    public static final float TYPE_LABEL_LARGE = 14f;
    public static final float TYPE_LABEL_MEDIUM = 12f;
    public static final float TYPE_LABEL_SMALL = 11f;

    // ==================== 旧动态方法（全部保留，语义切换到 M3 角色） ====================
    public static int bg()          { return surfaceContainerLow(); }
    public static int card()        { return surfaceContainerLowest(); }
    public static int whiteCard()   { return surfaceContainerLowest(); }
    public static int text1()       { return onSurface(); }
    public static int text2()       { return onSurfaceVariant(); }
    public static int text3()       { return outline(); }
    public static int accent()      { return primary(); }
    public static int accent2()     { return primary(); }
    public static int onColor()     { return primary(); }
    public static int offColor()    { return surfaceVariant(); }
    public static int arrow()       { return outline(); }
    public static int divider()     { return outlineVariant(); }
    public static int border()      { return outlineVariant(); }
    public static int inputBg()     { return surfaceContainerHighest(); }
    public static int candyPink()  { return sDarkMode ? D_CANDY_PINK   : L_CANDY_PINK; }
    public static int candyYellow(){ return sDarkMode ? D_CANDY_YELLOW : L_CANDY_YELLOW; }
    public static int whiteTextOnAccent() { return sDarkMode ? D_WHITE_TEXT : L_WHITE_TEXT; }
    public static int bubbleSelfBg()  { return sDarkMode ? D_PRIMARY_CONTAINER : L_PRIMARY_CONTAINER; }
    public static int bubbleOtherBg() { return sDarkMode ? D_SURFACE_CONTAINER_HIGH : L_SURFACE_CONTAINER_HIGH; }

    private AppColors() {}
}
