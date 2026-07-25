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

    public static int bg()          { return isDarkMode() ? DARK_BG : themed("ls_tc_page_bg", DEF_BG); }
    public static int card()        { return isDarkMode() ? DARK_CARD : themed("ls_tc_actionbar_bg", DEF_WHITE); }
    public static int whiteCard()   { return isDarkMode() ? DARK_CARD : DEF_WHITE; }
    public static int text1()       { return isDarkMode() ? DARK_TEXT : themed("ls_tc_text_primary", DEF_TEXT); }
    public static int text2()       { return isDarkMode() ? DARK_TEXT2 : themed("ls_tc_text_secondary", DEF_TEXT2); }
    public static int accent()      { return isDarkMode() ? DARK_ACCENT : themed("ls_tc_tab_selected", DEF_ACCENT); }
    public static int divider()     {
        if (isDarkMode()) return DARK_DIV;
        int b = bg();
        return (0xFF << 24) | adjust(b, 0.08f);
    }
    public static int arrow()       { return isDarkMode() ? DARK_ARROW : themed("ls_tc_text_secondary", DEF_ARROW); }
    public static int border()      { return isDarkMode() ? DARK_BORDER : themed("ls_tc_tab_selected", DEF_BORDER); }
    public static int accent2()     { return isDarkMode() ? DARK_ACCENT2 : themed("ls_tc_tab_selected", DEF_ACCENT2); }
    public static int green()       { return isDarkMode() ? DARK_GREEN : themed("ls_tc_tab_selected", DEF_GREEN); }
    public static int onColor()     { return isDarkMode() ? DARK_ON : themed("ls_tc_tab_selected", DEF_ON); }
    public static int offColor()    { return isDarkMode() ? DARK_OFF : DEF_OFF; }
    public static int bubbleSelfBg()  { return themed("ls_tc_bubble_self_bg", 0xFF95EC69); }
    public static int bubbleOtherBg() { return themed("ls_tc_bubble_other_bg", 0xFFFFFFFF); }

    public static int whiteTextOnAccent() { return 0xFFFFFFFF; }

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
