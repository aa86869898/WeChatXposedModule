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
 * 主题：微信绿 · 纯色（#07C160，动态取色优先，低版本回退微信绿）。
 */
public class AppColors {

    private static volatile boolean sDarkMode;

    // v1140: 主题实时跟随 —— 深色模式变化时通知已打开界面重建
    private static final java.util.List<Runnable> sThemeListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile boolean sWatcherInstalled = false;

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
        "糖果粉", "樱粉", "蜜桃", "玫瑰粉", "淡粉", "亮粉",
        "深粉", "藕粉", "玫粉", "暖粉", "荧光粉", "粉紫"
    };
    private static final int[] PALETTE_SEEDS = {
        0xFFFF99C2, // 糖果粉（主题）
        0xFFFFAFCC, // 樱粉
        0xFFF472B6, // 蜜桃
        0xFFFF6FB0, // 玫瑰粉
        0xFFFFC2D8, // 淡粉
        0xFFFF8FC7, // 亮粉
        0xFFD9639D, // 深粉
        0xFFFFD9E8, // 藕粉
        0xFFE56EA6, // 玫粉
        0xFFFB7185, // 暖粉
        0xFFFF5FA2, // 荧光粉
        0xFF8E6B7C, // 粉紫
    };
    private static final String PREFS = "leshao_m3_prefs";
    private static final String KEY_PALETTE = "m3_palette";
    private static final String KEY_CUSTOM_SEED = "m3_custom_seed";
    private static final String KEY_TITLEBAR = "m3_override_titlebar";
    private static final String KEY_SWITCH = "m3_override_switch";
    private static final String KEY_WINDOWBG = "m3_override_windowbg";
    private static volatile int sPalette = PALETTE_DYNAMIC;
    private static volatile int sCustomSeed = 0xFF07C160;
    private static volatile int sSchemeVersion = 0;
    /** 上次成功读取的动态取色 seed（Android 12+ system_accent1_500），用于 refresh 时检测壁纸变化 */
    private static volatile int sLastDynamicSeed = 0;
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
     * v3.0.102：Material You 动态取色 —— Android 12+ 读取系统壁纸生成的强调色资源，
     * 映射到 M3 全部色彩角色（primary/secondary/tertiary + surfaceContainer 中性层次）。
     * 低版本或系统资源不可用时返回 false，由调用方回退到糖果粉静态色板。
     * 成功后记录 sLastDynamicSeed，供 refresh() 检测壁纸变化后重算。
     */
    private static boolean applyDynamicPalette(boolean dark) {
        try {
            Context ctx = com.leshao.v3.ContextManager.getAppContext();
            if (ctx == null || android.os.Build.VERSION.SDK_INT < 31) return false;
            android.content.res.Resources res = ctx.getResources();
            android.content.res.Resources.Theme theme = ctx.getTheme();
            int seed = res.getColor(android.R.color.system_accent1_500, theme);
            if (dark) {
                cPrimary              = res.getColor(android.R.color.system_accent1_200, theme);
                cOnPrimary            = res.getColor(android.R.color.system_accent1_700, theme);
                cPrimaryContainer     = res.getColor(android.R.color.system_accent1_700, theme);
                cOnPrimaryContainer   = res.getColor(android.R.color.system_accent1_100, theme);
                cSecondary            = res.getColor(android.R.color.system_accent2_200, theme);
                cOnSecondary          = res.getColor(android.R.color.system_accent2_700, theme);
                cSecondaryContainer   = res.getColor(android.R.color.system_accent2_700, theme);
                cOnSecondaryContainer = res.getColor(android.R.color.system_accent2_100, theme);
                cTertiary             = res.getColor(android.R.color.system_accent3_200, theme);
                cOnTertiary           = res.getColor(android.R.color.system_accent3_700, theme);
                cTertiaryContainer    = res.getColor(android.R.color.system_accent3_700, theme);
                cOnTertiaryContainer  = res.getColor(android.R.color.system_accent3_100, theme);
                cPrimaryDark          = cPrimary;
                // 深色中性面保持暖炭色，与静态暗色方案一致
                cBackground           = 0xFF1B1218;
                cSurface              = 0xFF1B1218;
                cScLowest             = 0xFF130B10;
                cScLow                = 0xFF241A20;
                cSc                   = 0xFF2A2026;
                cScHigh               = 0xFF35292F;
                cScHighest            = 0xFF40343A;
                cSurfaceVariant       = 0xFF44323B;
                cOnSurface            = 0xFFF5E6ED;
                cOnSurfaceVariant     = 0xFFD0B4C1;
                cOutline              = 0xFF9D838F;
                cOutlineVariant       = 0xFF4B3843;
            } else {
                cPrimary              = seed;
                cOnPrimary            = onColor(seed);
                cPrimaryContainer     = res.getColor(android.R.color.system_accent1_100, theme);
                cOnPrimaryContainer   = res.getColor(android.R.color.system_accent1_900, theme);
                cSecondary            = res.getColor(android.R.color.system_accent2_500, theme);
                cOnSecondary          = onColor(cSecondary);
                cSecondaryContainer   = res.getColor(android.R.color.system_accent2_100, theme);
                cOnSecondaryContainer = res.getColor(android.R.color.system_accent2_900, theme);
                cTertiary             = res.getColor(android.R.color.system_accent3_500, theme);
                cOnTertiary           = onColor(cTertiary);
                cTertiaryContainer    = res.getColor(android.R.color.system_accent3_100, theme);
                cOnTertiaryContainer  = res.getColor(android.R.color.system_accent3_900, theme);
                cPrimaryDark          = cPrimary;
                // 浅色中性面保持暖白，与静态浅色方案一致
                cBackground           = 0xFFFBF8F6;
                cSurface              = 0xFFFBF8F6;
                cScLowest             = 0xFFFFFFFF;
                cScLow                = 0xFFF7F3F1;
                cSc                   = 0xFFF2ECEA;
                cScHigh               = 0xFFECE5E3;
                cScHighest            = 0xFFE6DEDC;
                cSurfaceVariant       = 0xFFF1DEE4;
                cOnSurface            = 0xFF221A1E;
                cOnSurfaceVariant     = 0xFF6B5560;
                cOutline              = 0xFF9C818D;
                cOutlineVariant       = 0xFFE4CBD5;
            }
            sLastDynamicSeed = seed;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 依据明暗模式重算当前配色角色。
     *
     * <p>v3.0.102：全局锁定「动态取色」（Material You）——Android 12+ 读取系统壁纸强调色，
     * 低版本/系统色不可用时回退到糖果粉静态色板，保证所有界面/弹窗/控件视觉一致。
     * 旧 PALETTE_* API 与 getter 全部保留，仅取值来源优先为系统动态色。</p>
     */
    private static void recomputePalette() {
        boolean dark = sDarkMode;
        // Android 12+ 动态取色可用时直接使用系统强调色（Material You）
        if (sPalette == PALETTE_DYNAMIC
                && android.os.Build.VERSION.SDK_INT >= 31
                && applyDynamicPalette(dark)) {
            try {
                com.leshao.v3.LogWriter.log("AppColors", "dynamic palette: sdk=" + android.os.Build.VERSION.SDK_INT
                        + " dark=" + dark + " seed=0x" + Integer.toHexString(detectDynamicSeed()));
            } catch (Throwable ignored) {}
            return;
        }
        try {
            com.leshao.v3.LogWriter.log("AppColors", "fallback static palette: sdk=" + android.os.Build.VERSION.SDK_INT
                    + " dark=" + dark + " (dynamic unavailable)");
        } catch (Throwable ignored) {}
        // ==================== M3 Expressive · 微信绿体系 ====================
        // 浅色：暖中性 surface（#FBF8F6）避免死白；微信绿 primary；灰绿 secondary；
        //       青蓝 tertiary；完整 surfaceContainer 层次（Lowest=纯白卡片 → Highest=浅灰）。
        // 深色：暖深棕 surface（#1B1218），主色提亮为亮微信绿，保持 Expressive 活力。
        if (dark) {
            cPrimary              = 0xFF3DD68C;
            cOnPrimary            = 0xFF00391F;
            cPrimaryContainer     = 0xFF005234;
            cOnPrimaryContainer   = 0xFFB3F5D3;
            cSecondary            = 0xFFB4CCBC;
            cOnSecondary          = 0xFF21382A;
            cSecondaryContainer   = 0xFF37513F;
            cOnSecondaryContainer = 0xFFD0E8D4;
            cTertiary             = 0xFFA6D8C2;
            cOnTertiary           = 0xFF00382A;
            cTertiaryContainer    = 0xFF00513D;
            cOnTertiaryContainer  = 0xFFB0F2DA;
            cPrimaryDark          = 0xFF3DD68C;

            cBackground           = 0xFF1B1218;
            cSurface              = 0xFF1B1218;
            cScLowest             = 0xFF130B10;
            cScLow                = 0xFF241A20;
            cSc                   = 0xFF2A2026;
            cScHigh               = 0xFF35292F;
            cScHighest            = 0xFF40343A;
            cSurfaceVariant       = 0xFF44323B;
            cOnSurface            = 0xFFF5E6ED;
            cOnSurfaceVariant     = 0xFFD0B4C1;
            cOutline              = 0xFF9D838F;
            cOutlineVariant       = 0xFF4B3843;
        } else {
            cPrimary              = 0xFF07C160;
            cOnPrimary            = 0xFFFFFFFF;
            cPrimaryContainer     = 0xFF9EF2C8;
            cOnPrimaryContainer   = 0xFF00391F;
            cSecondary            = 0xFF4E6356;
            cOnSecondary          = 0xFFFFFFFF;
            cSecondaryContainer   = 0xFFD0E8D4;
            cOnSecondaryContainer = 0xFF0C1F14;
            cTertiary             = 0xFF3D6373;
            cOnTertiary           = 0xFFFFFFFF;
            cTertiaryContainer    = 0xFFC1E8FA;
            cOnTertiaryContainer  = 0xFF001F29;
            cPrimaryDark          = 0xFF07C160;

            cBackground           = 0xFFFBF8F6;
            cSurface              = 0xFFFBF8F6;
            cScLowest             = 0xFFFFFFFF;
            cScLow                = 0xFFF7F3F1;
            cSc                   = 0xFFF2ECEA;
            cScHigh               = 0xFFECE5E3;
            cScHighest            = 0xFFE6DEDC;
            cSurfaceVariant       = 0xFFF1DEE4;
            cOnSurface            = 0xFF221A1E;
            cOnSurfaceVariant     = 0xFF6B5560;
            cOutline              = 0xFF9C818D;
            cOutlineVariant       = 0xFFE4CBD5;
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

    /** 页面 onResume 时调用：微信内切换深色模式/系统壁纸后刷新令牌（旧静态常量不失效，新 getter 立即跟随） */
    public static void refresh() {
        try {
            boolean prev = sDarkMode;
            sDarkMode = detectDarkMode();
            boolean changed = prev != sDarkMode;
            if (sPalette == PALETTE_DYNAMIC) {
                int seed = detectDynamicSeed();
                if (seed != 0 && seed != sLastDynamicSeed) changed = true;
            }
            if (changed) {
                recomputePalette();
                notifyThemeChanged();
            }
        } catch (Throwable ignored) {}
        ensureWatcher();
    }

    /** v1140: 注册主题变化监听（深色模式切换时回调），用于已打开界面就地重建。 */
    public static void addThemeListener(Runnable r) {
        if (r == null || sThemeListeners.contains(r)) return;
        sThemeListeners.add(r);
    }

    public static void removeThemeListener(Runnable r) {
        if (r != null) sThemeListeners.remove(r);
    }

    private static void notifyThemeChanged() {
        for (Runnable r : sThemeListeners) {
            try { r.run(); } catch (Throwable ignored) {}
        }
    }

    /** v1140: 一次性注册系统配置回调，系统深色模式切换时立即重算配色。 */
    private static void ensureWatcher() {
        if (sWatcherInstalled) return;
        Context ctx = com.leshao.v3.ContextManager.getAppContext();
        if (ctx == null) return;
        try {
            ctx.registerComponentCallbacks(new android.content.ComponentCallbacks() {
                @Override
                public void onConfigurationChanged(Configuration newConfig) {
                    try { refresh(); } catch (Throwable ignored) {}
                }
                @Override
                public void onLowMemory() {}
            });
            sWatcherInstalled = true;
        } catch (Throwable ignored) {}
    }

    public static void init(Activity act) { refresh(); }
    public static boolean isDarkMode() { return sDarkMode; }

    // ==================== M3 Expressive · 品牌渐变令牌 ====================
    // 去渐变：渐变三色统一返回主色 primary，所有依赖这些令牌的渐变自动变为纯色。
    public static int gradientStart() { return primary(); }
    public static int gradientMid()   { return primary(); }
    public static int gradientEnd()   { return primary(); }
    public static int onGradient()    { return onPrimary(); }
    /** 品牌渐变循环色（跟随主色） */
    public static int[] gradientColors() {
        return new int[]{ primary(), primary(), primary() };
    }
    /** 品牌色按压态（主色加深） */
    public static int gradientPressed() { return primaryDark(); }

// ==================== M3 色彩角色（浅色方案 · Expressive 中性暖白） ====================
    private static final int L_PRIMARY            = 0xFF07C160;
    private static final int L_ON_PRIMARY        = 0xFFFFFFFF;
    private static final int L_PRIMARY_CONTAINER  = 0xFF9EF2C8;
    private static final int L_ON_PRIMARY_CONTAINER = 0xFF00391F;
    private static final int L_SECONDARY          = 0xFF4E6356;
    private static final int L_ON_SECONDARY       = 0xFFFFFFFF;
    private static final int L_SECONDARY_CONTAINER = 0xFFD0E8D4;
    private static final int L_ON_SECONDARY_CONTAINER = 0xFF0C1F14;
    private static final int L_TERTIARY           = 0xFF3D6373;
    private static final int L_ON_TERTIARY        = 0xFFFFFFFF;
    private static final int L_TERTIARY_CONTAINER = 0xFFC1E8FA;
    private static final int L_ON_TERTIARY_CONTAINER = 0xFF001F29;
    private static final int L_ERROR              = 0xFFBA1A1A;
    private static final int L_ON_ERROR           = 0xFFFFFFFF;
    private static final int L_ERROR_CONTAINER    = 0xFFFFDAD6;
    private static final int L_ON_ERROR_CONTAINER = 0xFF410002;
    private static final int L_BACKGROUND         = 0xFFF8F8F8;
    private static final int L_ON_BACKGROUND      = 0xFF1A1C1B;
    private static final int L_SURFACE            = 0xFFF8F8F8;
    private static final int L_ON_SURFACE        = 0xFF1A1C1B;
    private static final int L_SURFACE_VARIANT    = 0xFFE3E3E3;
    private static final int L_ON_SURFACE_VARIANT = 0xFF444746;
    private static final int L_SURFACE_CONTAINER_LOWEST = 0xFFFFFFFF;
    private static final int L_SURFACE_CONTAINER_LOW = 0xFFF3F3F3;
    private static final int L_SURFACE_CONTAINER = 0xFFEDEDED;
    private static final int L_SURFACE_CONTAINER_HIGH = 0xFFE6E6E6;
    private static final int L_SURFACE_CONTAINER_HIGHEST = 0xFFE0E0E0;
    private static final int L_OUTLINE            = 0xFF79747E;
    private static final int L_OUTLINE_VARIANT    = 0xFFCAC4D0;
    private static final int L_INVERSE_SURFACE    = 0xFF303030;
    private static final int L_INVERSE_ON_SURFACE = 0xFFF5F5F5;

// ==================== M3 色彩角色（暗色方案 · Expressive 中性深灰） ====================
    private static final int D_PRIMARY            = 0xFF3DD68C;
    private static final int D_ON_PRIMARY         = 0xFF00391F;
    private static final int D_PRIMARY_CONTAINER  = 0xFF005234;
    private static final int D_ON_PRIMARY_CONTAINER = 0xFFB3F5D3;
    private static final int D_SECONDARY          = 0xFFB4CCBC;
    private static final int D_ON_SECONDARY       = 0xFF21382A;
    private static final int D_SECONDARY_CONTAINER = 0xFF37513F;
    private static final int D_ON_SECONDARY_CONTAINER = 0xFFD0E8D4;
    private static final int D_TERTIARY           = 0xFFA6D8C2;
    private static final int D_ON_TERTIARY        = 0xFF00382A;
    private static final int D_TERTIARY_CONTAINER = 0xFF00513D;
    private static final int D_ON_TERTIARY_CONTAINER = 0xFFB0F2DA;
    private static final int D_ERROR              = 0xFFFFB4AB;
    private static final int D_ON_ERROR           = 0xFF690005;
    private static final int D_ERROR_CONTAINER    = 0xFF93000A;
    private static final int D_ON_ERROR_CONTAINER = 0xFFFFDAD6;
    private static final int D_BACKGROUND         = 0xFF121212;
    private static final int D_ON_BACKGROUND      = 0xFFF0F0F0;
    private static final int D_SURFACE            = 0xFF121212;
    private static final int D_ON_SURFACE         = 0xFFF0F0F0;
    private static final int D_SURFACE_VARIANT    = 0xFF3A3A3A;
    private static final int D_ON_SURFACE_VARIANT = 0xFFC9C9C9;
    private static final int D_SURFACE_CONTAINER_LOWEST = 0xFF0E0E0E;
    private static final int D_SURFACE_CONTAINER_LOW = 0xFF1A1A1A;
    private static final int D_SURFACE_CONTAINER = 0xFF212121;
    private static final int D_SURFACE_CONTAINER_HIGH = 0xFF2A2A2A;
    private static final int D_SURFACE_CONTAINER_HIGHEST = 0xFF333333;
    private static final int D_OUTLINE            = 0xFF9B9B9B;
    private static final int D_OUTLINE_VARIANT    = 0xFF494949;
    private static final int D_INVERSE_SURFACE    = 0xFF3A3A3A;
    private static final int D_INVERSE_ON_SURFACE = 0xFFF5F5F5;

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
    /** 主色按压深阶（渐变/按压态用）：糖果粉深阶 #D9639D */
    public static int primaryDark()  { return custom() ? cPrimaryDark : (sDarkMode ? 0xFF3DD68C : 0xFF07C160); }
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
    public static final int DIALOG_RADIUS_DP = 28;   // 全局弹窗/页面浮层统一外层圆角（M3 Expressive）

    // ==================== M3 Expressive 形状阶梯（dp） ====================
    public static final int SHAPE_XS_DP = 4;
    public static final int SHAPE_SM_DP = 8;
    public static final int SHAPE_MD_DP = 12;
    public static final int SHAPE_LG_DP = 16;
    public static final int SHAPE_CARD_DP = 24;     // 卡片圆角
    public static final int SHAPE_INPUT_DP = 16;    // 输入框圆角
    public static final int SHAPE_XL_DP = 28;
    public static final int SHAPE_FULL_DP = 999;    // 药丸/全圆角

    // ==================== M3 间距阶梯（dp） ====================
    public static final int SPACE_XS_DP = 4;
    public static final int SPACE_SM_DP = 8;
    public static final int SPACE_MD_DP = 12;
    public static final int SPACE_LG_DP = 16;
    public static final int SPACE_XL_DP = 24;
    public static final int SPACE_CARD_GAP_DP = 13;  // 页面级卡片垂直间距

    public static final int TOP_BAR_HEIGHT_DP = 52;   // M3 top app bar
    public static final int ROW_HEIGHT_DP = 56;       // M3 list item（≥48dp 触控区）
    public static final int BUTTON_HEIGHT_DP = 48;    // M3 button（≥48dp 触控区）

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
    // M3 Expressive 分区标题（粗体 18–20sp）
    public static final float TYPE_SECTION_TITLE = 19f;        // 分区标题
    public static final float TYPE_SECTION_TITLE_LARGE = 20f; // 页面大标题/分区大标题

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

    // ==================== AudioMix 时间线面板（M3 动态角色 · 自绘专用） ====================
    public static int timelinePanelTop()      { return surfaceContainerHigh(); }
    public static int timelinePanelBottom()    { return surfaceContainerHighest(); }
    public static int timelineRulerTop()       { return surfaceContainerLowest(); }
    public static int timelineRulerBottom()    { return surfaceContainerLow(); }
    public static int timelinePink()            { return primary(); }              // 播放头 / 剪辑游标高亮
    public static int timelinePinkText()        { return onPrimaryContainer(); }   // 刻度 / 空状态文字
    public static int timelinePinkSoft()        { return primaryContainer(); }         // 片段名
    public static int timelinePinkPale()        { return secondaryContainer(); }     // 缩放标签
    public static int timelineAmber()           { return 0xFFFFE08A; }            // 剪辑游标
    public static int timelineGridMinor()       { return (0x20 << 24) | (primary() & 0x00FFFFFF); }
    public static int timelineGridMajor()       { return (0x30 << 24) | (tertiary() & 0x00FFFFFF); }
    public static int timelineBlockBase()       { return 0x26FFFFFF; }
    public static int timelineDimOverlay()       { return 0x6E0A0612; }
    public static int timelineDimPlain()       { return 0x2E000000; }
    public static int timelineDeleteBadge()   { return 0xFFE0455F; }
    public static int timelineBadgeDark()       { return 0xB30A0612; }
    public static int timelineWhite()           { return 0xFFFFFFFF; }
    public static int timelineBarAlt1()       { return secondary(); }
    public static int timelineBarAlt2()       { return tertiary(); }

    /** AI 图标分类调色板（M3 动态角色色，10 个分类图标在主题色系内轮换）。 */
    public static int[] aiIconPalette() {
        int p = primary();
        int t = tertiary();
        int s = secondary();
        int pc = primaryContainer();
        int tc = tertiaryContainer();
        int sc = secondaryContainer();
        return new int[]{
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
        };
    }

    private AppColors() {}
}
