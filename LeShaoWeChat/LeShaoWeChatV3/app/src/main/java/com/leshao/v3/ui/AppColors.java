package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

/**
 * LeShaoWeChat 设计令牌 —— Material 3 (Material You) 色彩体系。
 *
 * 兼容性铁律：旧公开常量与旧方法全部保留（全项目 20+ 页面依赖，禁止删除/改语义）；
 * M3 色彩角色（primary/onPrimary/primaryContainer/surface/surfaceVariant/outline/…）
 * 一律走动态 getter，支持暗色模式运行时切换。
 *
 * 源色：微信绿 #07C160 生成的 M3  tonal palette（浅色 + 暗色双方案）。
 */
public class AppColors {

    private static volatile boolean sDarkMode;
    static {
        sDarkMode = detectDarkMode();
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
            sDarkMode = detectDarkMode();
        } catch (Throwable ignored) {}
    }

    public static void init(Activity act) { refresh(); }
    public static boolean isDarkMode() { return sDarkMode; }

    // ==================== M3 色彩角色（浅色方案 · 源色微信绿） ====================
    private static final int L_PRIMARY            = 0xFF006C4C;
    private static final int L_ON_PRIMARY         = 0xFFFFFFFF;
    private static final int L_PRIMARY_CONTAINER  = 0xFF89F8C7;
    private static final int L_ON_PRIMARY_CONTAINER = 0xFF002114;
    private static final int L_SECONDARY          = 0xFF4C6358;
    private static final int L_ON_SECONDARY       = 0xFFFFFFFF;
    private static final int L_SECONDARY_CONTAINER = 0xFFCEE9DA;
    private static final int L_ON_SECONDARY_CONTAINER = 0xFF092016;
    private static final int L_TERTIARY           = 0xFF3D6373;
    private static final int L_ON_TERTIARY        = 0xFFFFFFFF;
    private static final int L_TERTIARY_CONTAINER = 0xFFC1E9FB;
    private static final int L_ON_TERTIARY_CONTAINER = 0xFF001F29;
    private static final int L_ERROR              = 0xFFBA1A1A;
    private static final int L_ON_ERROR           = 0xFFFFFFFF;
    private static final int L_ERROR_CONTAINER    = 0xFFFFDAD6;
    private static final int L_ON_ERROR_CONTAINER = 0xFF410002;
    private static final int L_BACKGROUND         = 0xFFFBFDF8;
    private static final int L_ON_BACKGROUND      = 0xFF191C1A;
    private static final int L_SURFACE            = 0xFFFBFDF8;
    private static final int L_ON_SURFACE         = 0xFF191C1A;
    private static final int L_SURFACE_VARIANT    = 0xFFDBE5DD;
    private static final int L_ON_SURFACE_VARIANT = 0xFF404943;
    private static final int L_SURFACE_CONTAINER_LOWEST = 0xFFFFFFFF;
    private static final int L_SURFACE_CONTAINER_LOW = 0xFFF5F7F2;
    private static final int L_SURFACE_CONTAINER = 0xFFEFF1ED;
    private static final int L_SURFACE_CONTAINER_HIGH = 0xFFE9EBE7;
    private static final int L_SURFACE_CONTAINER_HIGHEST = 0xFFE3E5E1;
    private static final int L_OUTLINE            = 0xFF707973;
    private static final int L_OUTLINE_VARIANT    = 0xFFBFC9C2;
    private static final int L_INVERSE_SURFACE    = 0xFF2E312F;
    private static final int L_INVERSE_ON_SURFACE = 0xFFEFF1ED;

    // ==================== M3 色彩角色（暗色方案 · 源色微信绿） ====================
    private static final int D_PRIMARY            = 0xFF6CDBAC;
    private static final int D_ON_PRIMARY         = 0xFF003825;
    private static final int D_PRIMARY_CONTAINER  = 0xFF005138;
    private static final int D_ON_PRIMARY_CONTAINER = 0xFF89F8C7;
    private static final int D_SECONDARY          = 0xFFB3CCBE;
    private static final int D_ON_SECONDARY       = 0xFF1F352A;
    private static final int D_SECONDARY_CONTAINER = 0xFF354B40;
    private static final int D_ON_SECONDARY_CONTAINER = 0xFFCEE9DA;
    private static final int D_TERTIARY           = 0xFFA5CCDF;
    private static final int D_ON_TERTIARY        = 0xFF073543;
    private static final int D_TERTIARY_CONTAINER = 0xFF244C5A;
    private static final int D_ON_TERTIARY_CONTAINER = 0xFFC1E9FB;
    private static final int D_ERROR              = 0xFFFFB4AB;
    private static final int D_ON_ERROR           = 0xFF690005;
    private static final int D_ERROR_CONTAINER    = 0xFF93000A;
    private static final int D_ON_ERROR_CONTAINER = 0xFFFFDAD6;
    private static final int D_BACKGROUND         = 0xFF191C1A;
    private static final int D_ON_BACKGROUND      = 0xFFE1E3DF;
    private static final int D_SURFACE            = 0xFF191C1A;
    private static final int D_ON_SURFACE         = 0xFFE1E3DF;
    private static final int D_SURFACE_VARIANT    = 0xFF404943;
    private static final int D_ON_SURFACE_VARIANT = 0xFFBFC9C2;
    private static final int D_SURFACE_CONTAINER_LOWEST = 0xFF0D0F0E;
    private static final int D_SURFACE_CONTAINER_LOW = 0xFF191C1A;
    private static final int D_SURFACE_CONTAINER = 0xFF1D201E;
    private static final int D_SURFACE_CONTAINER_HIGH = 0xFF272B28;
    private static final int D_SURFACE_CONTAINER_HIGHEST = 0xFF323633;
    private static final int D_OUTLINE            = 0xFF8A938C;
    private static final int D_OUTLINE_VARIANT    = 0xFF404943;
    private static final int D_INVERSE_SURFACE    = 0xFFE1E3DF;
    private static final int D_INVERSE_ON_SURFACE = 0xFF191C1A;

    // ==================== M3 getter（暗色实时跟随） ====================
    public static int primary()              { return sDarkMode ? D_PRIMARY : L_PRIMARY; }
    public static int onPrimary()            { return sDarkMode ? D_ON_PRIMARY : L_ON_PRIMARY; }
    public static int primaryContainer()     { return sDarkMode ? D_PRIMARY_CONTAINER : L_PRIMARY_CONTAINER; }
    public static int onPrimaryContainer()   { return sDarkMode ? D_ON_PRIMARY_CONTAINER : L_ON_PRIMARY_CONTAINER; }
    public static int secondary()            { return sDarkMode ? D_SECONDARY : L_SECONDARY; }
    public static int onSecondary()          { return sDarkMode ? D_ON_SECONDARY : L_ON_SECONDARY; }
    public static int secondaryContainer()   { return sDarkMode ? D_SECONDARY_CONTAINER : L_SECONDARY_CONTAINER; }
    public static int onSecondaryContainer() { return sDarkMode ? D_ON_SECONDARY_CONTAINER : L_ON_SECONDARY_CONTAINER; }
    public static int tertiary()             { return sDarkMode ? D_TERTIARY : L_TERTIARY; }
    public static int onTertiary()           { return sDarkMode ? D_ON_TERTIARY : L_ON_TERTIARY; }
    public static int tertiaryContainer()    { return sDarkMode ? D_TERTIARY_CONTAINER : L_TERTIARY_CONTAINER; }
    public static int onTertiaryContainer()  { return sDarkMode ? D_ON_TERTIARY_CONTAINER : L_ON_TERTIARY_CONTAINER; }
    public static int error()                { return sDarkMode ? D_ERROR : L_ERROR; }
    public static int onError()              { return sDarkMode ? D_ON_ERROR : L_ON_ERROR; }
    public static int errorContainer()       { return sDarkMode ? D_ERROR_CONTAINER : L_ERROR_CONTAINER; }
    public static int onErrorContainer()     { return sDarkMode ? D_ON_ERROR_CONTAINER : L_ON_ERROR_CONTAINER; }
    public static int background()           { return sDarkMode ? D_BACKGROUND : L_BACKGROUND; }
    public static int onBackground()         { return sDarkMode ? D_ON_BACKGROUND : L_ON_BACKGROUND; }
    public static int surface()              { return sDarkMode ? D_SURFACE : L_SURFACE; }
    public static int onSurface()            { return sDarkMode ? D_ON_SURFACE : L_ON_SURFACE; }
    public static int surfaceVariant()       { return sDarkMode ? D_SURFACE_VARIANT : L_SURFACE_VARIANT; }
    public static int onSurfaceVariant()     { return sDarkMode ? D_ON_SURFACE_VARIANT : L_ON_SURFACE_VARIANT; }
    public static int surfaceContainerLowest()  { return sDarkMode ? D_SURFACE_CONTAINER_LOWEST : L_SURFACE_CONTAINER_LOWEST; }
    public static int surfaceContainerLow()     { return sDarkMode ? D_SURFACE_CONTAINER_LOW : L_SURFACE_CONTAINER_LOW; }
    public static int surfaceContainer()        { return sDarkMode ? D_SURFACE_CONTAINER : L_SURFACE_CONTAINER; }
    public static int surfaceContainerHigh()    { return sDarkMode ? D_SURFACE_CONTAINER_HIGH : L_SURFACE_CONTAINER_HIGH; }
    public static int surfaceContainerHighest() { return sDarkMode ? D_SURFACE_CONTAINER_HIGHEST : L_SURFACE_CONTAINER_HIGHEST; }
    public static int outline()              { return sDarkMode ? D_OUTLINE : L_OUTLINE; }
    public static int outlineVariant()       { return sDarkMode ? D_OUTLINE_VARIANT : L_OUTLINE_VARIANT; }
    public static int inverseSurface()       { return sDarkMode ? D_INVERSE_SURFACE : L_INVERSE_SURFACE; }
    public static int inverseOnSurface()     { return sDarkMode ? D_INVERSE_ON_SURFACE : L_INVERSE_ON_SURFACE; }

    /** M3 状态层：按压 12% / 悬浮 8%（由调用方与底色叠加） */
    public static int stateLayerPressed() { return 0x1F000000; }
    public static int stateLayerHover()   { return 0x14000000; }
    /** M3 主色状态层（用于 primary 底上的涟漪） */
    public static int stateLayerOnPrimary() { return 0x14FFFFFF; }

    // ==================== 便捷别名（M3 角色映射，供组件工厂使用） ====================
    /** 主色按压深阶（渐变/按压态用）：浅色 #005138，暗色 #35A67B */
    public static int primaryDark()  { return sDarkMode ? 0xFF35A67B : 0xFF005138; }
    /** 三级文字（弱化）：M3 outline */
    public static int textTertiary() { return sDarkMode ? D_OUTLINE : L_OUTLINE; }
    /** 主色底上的文字/图标：M3 onPrimary */
    public static int textOnPrimary() { return sDarkMode ? D_ON_PRIMARY : L_ON_PRIMARY; }

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
    private static final int L_WHITE_TEXT = L_ON_PRIMARY;

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
    private static final int D_WHITE_TEXT = D_ON_PRIMARY;

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

    public static final int TOP_BAR_HEIGHT_DP = 64;   // M3 top app bar
    public static final int ROW_HEIGHT_DP = 56;       // M3 list item (one line)
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
    public static int bg()          { return sDarkMode ? D_BG_GRADIENT_START : L_BG_GRADIENT_START; }
    public static int card()        { return sDarkMode ? D_CARD_BG : L_CARD_BG; }
    public static int whiteCard()   { return sDarkMode ? D_CARD_BG : L_CARD_BG; }
    public static int text1()       { return sDarkMode ? D_TEXT_TITLE : L_TEXT_TITLE; }
    public static int text2()       { return sDarkMode ? D_TEXT_BODY  : L_TEXT_BODY; }
    public static int text3()       { return sDarkMode ? D_TEXT_NOTE  : L_TEXT_NOTE; }
    public static int accent()      { return sDarkMode ? D_ACCENT     : L_ACCENT; }
    public static int accent2()     { return sDarkMode ? D_ACCENT     : L_ACCENT; }
    public static int onColor()     { return sDarkMode ? D_SWITCH_ON  : L_SWITCH_ON; }
    public static int offColor()    { return sDarkMode ? D_SWITCH_OFF : L_SWITCH_OFF; }
    public static int arrow()       { return sDarkMode ? D_ARROW      : L_ARROW; }
    public static int divider()     { return sDarkMode ? D_DIVIDER    : L_DIVIDER; }
    public static int border()      { return sDarkMode ? D_DIVIDER    : L_DIVIDER; }
    public static int inputBg()     { return sDarkMode ? D_INPUT_BG   : L_INPUT_BG; }
    public static int candyPink()  { return sDarkMode ? D_CANDY_PINK   : L_CANDY_PINK; }
    public static int candyYellow(){ return sDarkMode ? D_CANDY_YELLOW : L_CANDY_YELLOW; }
    public static int whiteTextOnAccent() { return sDarkMode ? D_WHITE_TEXT : L_WHITE_TEXT; }
    public static int bubbleSelfBg()  { return sDarkMode ? D_PRIMARY_CONTAINER : L_PRIMARY_CONTAINER; }
    public static int bubbleOtherBg() { return sDarkMode ? D_SURFACE_CONTAINER_HIGH : L_SURFACE_CONTAINER_HIGH; }

    private AppColors() {}
}
