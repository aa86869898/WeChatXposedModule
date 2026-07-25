package com.leshao.v3.ui;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import com.leshao.v3.ContextManager;

public class AppColors {

    private static final int DEF_BG      = 0xFFF4F0FF;
    private static final int DEF_WHITE   = 0xFFFFFFFF;
    private static final int DEF_TEXT    = 0xFF281838;
    private static final int DEF_TEXT2   = 0xFF786890;
    private static final int DEF_ACCENT  = 0xFFFF4298;
    private static final int DEF_DIV     = 0xFFE8DCF0;
    private static final int DEF_ARROW   = 0xFFBFBFBF;
    private static final int DEF_BORDER  = 0xFFE0D0F0;
    private static final int DEF_ACCENT2 = 0xFFB848E0;
    private static final int DEF_GREEN   = 0xFF00C088;
    private static final int DEF_ON      = 0xFFFF4298;
    private static final int DEF_OFF     = 0xFFBBBBBB;

    private static final int DARK_BG      = 0xFF1A1A2E;
    private static final int DARK_CARD    = 0xFF2D2D44;
    private static final int DARK_TEXT    = 0xFFE8E8F0;
    private static final int DARK_TEXT2   = 0xFF9A9AB0;
    private static final int DARK_ACCENT  = 0xFFFF6098;
    private static final int DARK_DIV     = 0xFF3A3A50;
    private static final int DARK_ARROW   = 0xFF6A6A80;
    private static final int DARK_BORDER  = 0xFF3A3A58;
    private static final int DARK_ACCENT2 = 0xFFC868E8;
    private static final int DARK_GREEN   = 0xFF30D8A0;
    private static final int DARK_ON      = 0xFFFF6098;
    private static final int DARK_OFF     = 0xFF555555;
    private static final int DARK_WHITE   = 0xFFE8E8F0;

    public static boolean isDarkMode() {
        try {
            android.content.Context ctx = ContextManager.getAppContext();
            if (ctx == null) return false;
            Resources res = ctx.getResources();
            if (res == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return res.getConfiguration().isNightModeActive();
            }
            int uiMode = res.getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
            return (uiMode == Configuration.UI_MODE_NIGHT_YES);
        } catch (Throwable ignored) {}
        return false;
    }

    private static SharedPreferences p() {
        return ContextManager.getPrefs();
    }

    private static boolean masterOn() {
        SharedPreferences p = p();
        return p != null && p.getBoolean("ls_theme_enabled", false);
    }

    private static int themed(String key, int def) {
        if (!masterOn()) return def;
        SharedPreferences p = p();
        return p != null ? p.getInt(key, def) : def;
    }

    private static int themedDual(String key, int lightDef, int darkDef) {
        if (isDarkMode()) {
            return themed(key + "_dark", darkDef);
        }
        return themed(key, lightDef);
    }

    public static int bg()          { return themedDual("ls_tc_page_bg",          DEF_BG,      DARK_BG); }
    public static int card()        { return themedDual("ls_tc_actionbar_bg",    DEF_WHITE,   DARK_CARD); }
    public static int whiteCard()   { return isDarkMode() ? DARK_CARD : DEF_WHITE; }
    public static int text1()       { return themedDual("ls_tc_text_primary",    DEF_TEXT,    DARK_TEXT); }
    public static int text2()       { return themedDual("ls_tc_text_secondary",  DEF_TEXT2,   DARK_TEXT2); }
    public static int accent()      { return themedDual("ls_tc_tab_selected",    DEF_ACCENT,  DARK_ACCENT); }
    public static int divider()     {
        boolean dm = isDarkMode();
        int b = bg();
        return (0xFF << 24) | adjust(b, dm ? 0.12f : 0.08f);
    }
    public static int arrow()       { return themedDual("ls_tc_text_secondary",  DEF_ARROW,   DARK_ARROW); }
    public static int border()      { return themedDual("ls_tc_tab_selected",    DEF_BORDER,  DARK_BORDER); }
    public static int accent2()     { return themedDual("ls_tc_tab_selected",    DEF_ACCENT2, DARK_ACCENT2); }
    public static int green()       { return themedDual("ls_tc_tab_selected",    DEF_GREEN,   DARK_GREEN); }
    public static int onColor()     { return themedDual("ls_tc_tab_selected",    DEF_ON,      DARK_ON); }
    public static int offColor()    { return isDarkMode() ? DARK_OFF : DEF_OFF; }
    public static int bubbleSelfBg()  { return themedDual("ls_tc_bubble_self_bg", 0xFF95EC69, 0xFF2D8A4E); }
    public static int bubbleOtherBg() { return themedDual("ls_tc_bubble_other_bg", 0xFFFFFFFF, 0xFF2D2D44); }

    public static int whiteTextOnAccent() { return 0xFFFFFFFF; }
    public static int inputBg()    { return isDarkMode() ? DARK_CARD : 0xFFF0F0F0; }

    private static int adjust(int c, float ratio) {
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        r = clamp((int)(r + 255 * ratio));
        g = clamp((int)(g + 255 * ratio));
        b = clamp((int)(b + 255 * ratio));
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
