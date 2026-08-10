package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

public class AppColors {

    private static final boolean sDarkMode;
    static {
        boolean dark = false;
        try {
            Context ctx = com.leshao.v3.ContextManager.getAppContext();
            if (ctx != null) {
                int nightMode = ctx.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                dark = nightMode == Configuration.UI_MODE_NIGHT_YES;
            }
        } catch (Throwable ignored) {}
        sDarkMode = dark;
    }

    public static void init(Activity act) { /* compat, detection in static block */ }
    public static boolean isDarkMode() { return sDarkMode; }

    // 浅色模式
    private static final int L_BG_GRADIENT_START = 0xFFF7F9FF;
    private static final int L_BG_GRADIENT_END   = 0xFFFFF5F8;
    private static final int L_CARD_BG    = 0xB8FFFFFF;
    private static final int L_INPUT_BG   = 0xA6FFFFFF;
    private static final int L_TEXT_TITLE = 0xFF1D1D1F;
    private static final int L_TEXT_BODY  = 0xFF565659;
    private static final int L_TEXT_NOTE  = 0xFF949499;
    private static final int L_ACCENT     = 0xFF2196F3;
    private static final int L_SWITCH_ON  = 0xFFCCF2F9;
    private static final int L_SWITCH_OFF = 0xFFE6E6EA;
    private static final int L_CANDY_PINK   = 0xFFFF94C2;
    private static final int L_CANDY_YELLOW = 0xFFFFE470;
    private static final int L_ARROW    = 0xFF949499;
    private static final int L_DIVIDER  = 0xFFE6E6EA;
    private static final int L_WHITE_TEXT = 0xFFFFFFFF;

    // 暗色模式
    private static final int D_BG_GRADIENT_START = 0xFF1A1A2E;
    private static final int D_BG_GRADIENT_END   = 0xFF16213E;
    private static final int D_CARD_BG    = 0xB81A1A2E;
    private static final int D_INPUT_BG   = 0xA60F3460;
    private static final int D_TEXT_TITLE = 0xFFE4E4E8;
    private static final int D_TEXT_BODY  = 0xFFB0B0B8;
    private static final int D_TEXT_NOTE  = 0xFF707079;
    private static final int D_ACCENT     = 0xFF64B5F6;
    private static final int D_SWITCH_ON  = 0xFF0F3460;
    private static final int D_SWITCH_OFF = 0xFF333344;
    private static final int D_CANDY_PINK   = 0xFFCC5A8A;
    private static final int D_CANDY_YELLOW = 0xFFCCB860;
    private static final int D_ARROW    = 0xFF707079;
    private static final int D_DIVIDER  = 0xFF333344;
    private static final int D_WHITE_TEXT = 0xFFFFFFFF;

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

    public static final int SWITCH_WIDTH_DP  = 48;
    public static final int SWITCH_HEIGHT_DP = 26;
    public static final int SWITCH_RADIUS_DP = 13;
    public static final int ITEM_HEIGHT_DP = 58;
    public static final int DIALOG_RADIUS_DP = 16;

    public static int bg()          { return BG_GRADIENT_START; }
    public static int card()        { return CARD_BG; }
    public static int whiteCard()   { return CARD_BG; }
    public static int text1()       { return TEXT_TITLE; }
    public static int text2()       { return TEXT_BODY; }
    public static int text3()       { return TEXT_NOTE; }
    public static int accent()      { return ACCENT; }
    public static int accent2()     { return ACCENT; }
    public static int onColor()     { return SWITCH_ON; }
    public static int offColor()    { return SWITCH_OFF; }
    public static int arrow()       { return ARROW; }
    public static int divider()     { return DIVIDER; }
    public static int border()      { return DIVIDER; }
    public static int inputBg()     { return INPUT_BG; }
    public static int candyPink()  { return CANDY_PINK; }
    public static int candyYellow(){ return CANDY_YELLOW; }
    public static int whiteTextOnAccent() { return WHITE_TEXT; }
    public static int bubbleSelfBg()  { return CARD_BG; }
    public static int bubbleOtherBg() { return CARD_BG; }

    private AppColors() {}
}
