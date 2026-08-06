package com.leshao.v3.ui;

public class AppColors {

    // 淡霓虹糖果风 - 统一色彩系统
    // 页面背景渐变起止色
    public static final int BG_GRADIENT_START = 0xFFF7F9FF;
    public static final int BG_GRADIENT_END   = 0xFFFFF5F8;

    // 卡片底色 (极淡柔和高光, rgba(255,255,255,0.72))
    public static final int CARD_BG    = 0xB8FFFFFF;

    // 搜索/输入框底色 (rgba(255,255,255,0.65))
    public static final int INPUT_BG   = 0xA6FFFFFF;

    // 标题文字
    public static final int TEXT_TITLE = 0xFF1D1D1F;
    // 正文文字
    public static final int TEXT_BODY  = 0xFF565659;
    // 备注小字
    public static final int TEXT_NOTE  = 0xFF949499;

    // 主题蓝
    public static final int ACCENT     = 0xFF2196F3;

    // 开关轨道
    public static final int SWITCH_ON  = 0xFFCCF2F9;
    public static final int SWITCH_OFF = 0xFFE6E6EA;

    // 点缀色 - 仅用于标签/次要按钮
    public static final int CANDY_PINK   = 0xFFFF94C2;
    public static final int CANDY_YELLOW = 0xFFFFE470;

    // 箭头/分割线
    public static final int ARROW    = 0xFF949499;
    public static final int DIVIDER  = 0xFFE6E6EA;

    // 白色文字(用于强调色之上的文字)
    public static final int WHITE_TEXT = 0xFFFFFFFF;

    // Switch 尺寸 (dp值)
    public static final int SWITCH_WIDTH_DP  = 48;
    public static final int SWITCH_HEIGHT_DP = 26;
    public static final int SWITCH_RADIUS_DP = 13;

    // 列表条目高度 (dp)
    public static final int ITEM_HEIGHT_DP = 58;
    // 弹窗圆角 (dp)
    public static final int DIALOG_RADIUS_DP = 16;

    // ---------- 便捷取值 (向后兼容) ----------

    // 别名 (供 static import 或直接引用使用)
    public static final int candyPink   = CANDY_PINK;
    public static final int candyYellow = CANDY_YELLOW;

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

    public static boolean isDarkMode() { return false; }

    private AppColors() {}
}
