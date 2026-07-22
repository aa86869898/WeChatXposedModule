package com.leshao.wechat;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class ThemeEngine {
    public static boolean isDark = false;

    public static String hexBg()    { return isDark ? "0C0818" : "F4F0FF"; }
    public static String hexCard()  { return isDark ? "C814102C" : "B8FFFFFF"; }
    public static String hexBorder(){ return isDark ? "3A2060" : "E0D0F0"; }
    public static String hexAccent(){ return isDark ? "FF60C0" : "FF4298"; }
    public static String hexAcc2()  { return isDark ? "A858F8" : "B848E0"; }
    public static String hexText()  { return isDark ? "F0E8FF" : "281838"; }
    public static String hexText2() { return isDark ? "B0A0D0" : "786890"; }
    public static String hexGreen() { return isDark ? "00F8B8" : "00C088"; }
    public static String hexRed()   { return isDark ? "FF5080" : "FF3860"; }
    public static String hexOrange(){ return isDark ? "FFB080" : "FF8858"; }
    public static String hexSwTrk() { return isDark ? "1C1038" : "E8D8F0"; }
    public static String hexSwThm() { return isDark ? "6848B8" : "C0A0D8"; }
    public static String hexGBd()   { return isDark ? "3A2060" : "E0D0F0"; }
    public static String hexBtnBd() { return isDark ? "5038B0" : "D0B8E8"; }
    public static String hexWhite() { return isDark ? "F0E8FF" : "FFFFFF"; }
    public static String hexDiv()   { return isDark ? "302050" : "E8DCF0"; }

    public static int thBg()    { return Utils.pc(hexBg()); }
    public static int thCard()  { return Utils.pc(hexCard()); }
    public static int thBorder(){ return Utils.pc(hexBorder()); }
    public static int thAccent(){ return Utils.pc(hexAccent()); }
    public static int thAccent2(){ return Utils.pc(hexAcc2()); }
    public static int thText()  { return Utils.pc(hexText()); }
    public static int thText2() { return Utils.pc(hexText2()); }
    public static int thGreen() { return Utils.pc(hexGreen()); }
    public static int thRed()   { return Utils.pc(hexRed()); }
    public static int thOrange(){ return Utils.pc(hexOrange()); }
    public static int thSwitchTrk(){ return Utils.pc(hexSwTrk()); }
    public static int thSwitchThm(){ return Utils.pc(hexSwThm()); }
    public static int thDivider()  { return Utils.pc(hexDiv()); }
    public static int thWhite() { return Utils.pc(hexWhite()); }
    public static int thBtnBd() { return Utils.pc(hexBtnBd()); }

    public static void init() {
        try{ isDark = (MainHook.wechatContext.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }catch(Throwable e){}
    }
    public static boolean isDarkMode() { return isDark; }

    static int[] candyColors() {
        return isDark
            ? new int[]{Utils.pc("FF1E0430"),Utils.pc("FF081838"),Utils.pc("FF082438"),Utils.pc("FF140E38"),Utils.pc("FF200840"),Utils.pc("FF1E0430")}
            : new int[]{Utils.pc("FFFFE8F4"),Utils.pc("FFFCD4EE"),Utils.pc("FFF0D4FF"),Utils.pc("FFD4E6FF"),Utils.pc("FFD8F4EE"),Utils.pc("FFFFE4F4")};
    }

    // ===== Drawable工厂 =====
    public static GradientDrawable createGlassBg(Context ctx, int rad) {
        GradientDrawable gd = new GradientDrawable();
        gd.setOrientation(GradientDrawable.Orientation.TL_BR);
        gd.setColors(candyColors());
        gd.setCornerRadius(Utils.dp(ctx,rad));
        gd.setStroke(Utils.dp(ctx,1),Utils.pc("FF"+hexGBd()));
        return gd;
    }

    public static GradientDrawable createCardBg(Context ctx, int rad) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(thCard());
        gd.setCornerRadius(Utils.dp(ctx,rad));
        gd.setStroke(Utils.dp(ctx,1),Utils.pc("FF"+hexBorder()));
        return gd;
    }

    public static GradientDrawable createInputBg(Context ctx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Utils.pc("18"+hexText()));
        gd.setCornerRadius(Utils.dp(ctx,12));
        gd.setStroke(Utils.dp(ctx,1),Utils.pc("FF"+hexBorder()));
        return gd;
    }

    public static GradientDrawable createGlassBtnBg(Context ctx, int rad) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            isDark
                ? new int[]{Utils.pc("C8"+hexBg()),thCard(),Utils.pc("C8"+hexBg())}
                : new int[]{Utils.pc("C8"+hexBg()),thCard(),Utils.pc("C8"+hexBg())});
        gd.setCornerRadius(Utils.dp(ctx,rad));
        gd.setStroke(Utils.dp(ctx,2),Utils.pc("FF"+hexBtnBd()));
        return gd;
    }

    public static GradientDrawable createPrimaryBtnBg(Context ctx, int rad) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{thAccent2(),thAccent()});
        gd.setCornerRadius(Utils.dp(ctx,rad));
        return gd;
    }

    public static GradientDrawable createOutlineBtnBg(Context ctx, int rad) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(Utils.dp(ctx,rad));
        gd.setColor(Color.TRANSPARENT);
        gd.setStroke(Utils.dp(ctx,2),Utils.pc("FF"+hexBorder()));
        return gd;
    }

    public static GradientDrawable createDangerBtnBg(Context ctx, int rad) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Utils.pc("FF"+hexRed()),Utils.pc("CC"+hexRed())});
        gd.setCornerRadius(Utils.dp(ctx,rad));
        return gd;
    }

    public static GradientDrawable createSuccessBtnBg(Context ctx, int rad) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Utils.pc("FF"+hexGreen()),Utils.pc("CC"+hexGreen())});
        gd.setCornerRadius(Utils.dp(ctx,rad));
        return gd;
    }

    /** 统一按钮样式 - 参照WAuxiliary源码 jdyStyleButton */
    public static void styleButton(Button btn) {
        if (btn == null) return;
        btn.setAllCaps(false);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setTextColor(thWhite());
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{thAccent2(),thAccent()});
        bg.setCornerRadius(Utils.dp(btn.getContext(),14));
        btn.setBackground(bg);
        btn.setPadding(Utils.dp(btn.getContext(),12),Utils.dp(btn.getContext(),8),Utils.dp(btn.getContext(),12),Utils.dp(btn.getContext(),8));
        addClickAnim(btn);
    }

    /** 统一按钮按压动画 - 参照WAuxiliary源码 jdyAddBtnClickAnim */
    public static void addClickAnim(View btn) {
        if (btn == null) return;
        try {
            android.graphics.drawable.Drawable origBg = btn.getBackground();
            if (origBg != null) {
                StateListDrawable sld = new StateListDrawable();
                android.graphics.drawable.Drawable pressed = origBg.getConstantState() != null
                    ? origBg.getConstantState().newDrawable().mutate() : origBg;
                pressed.setColorFilter(Utils.pc("33000000"), PorterDuff.Mode.SRC_ATOP);
                sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
                sld.addState(new int[]{}, origBg);
                btn.setBackground(sld);
            }
        } catch (Throwable e) {}
        btn.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    v.setScaleX(0.90f); v.setScaleY(0.90f);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    v.setScaleX(1f); v.setScaleY(1f);
                }
                return false;
            }
        });
    }

    /** 统一输入框样式 */
    public static void styleInput(EditText et) {
        if (et == null) return;
        et.setHintTextColor(Utils.pc("FF"+hexText2()));
        et.setTextColor(thText());
        et.setTextSize(13);
        et.setSingleLine(true);
        et.setBackgroundDrawable(createInputBg(et.getContext()));
        et.setPadding(Utils.dp(et.getContext(),10),Utils.dp(et.getContext(),8),Utils.dp(et.getContext(),10),Utils.dp(et.getContext(),8));
    }

    /** 创建带样式的按钮 */
    public static Button createBtn(Context ctx, String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setClickable(true);
        b.setFocusable(true);
        b.setGravity(android.view.Gravity.CENTER);
        return b;
    }

    /** 给文本组件添加点击缩放动画 */
    public static void styleClickableText(TextView tv) {
        if (tv == null) return;
        tv.setClickable(true);
        tv.setFocusable(true);
        addClickAnim(tv);
    }

    // ===== Switch 样式 =====
    public static GradientDrawable createSwitchTrackBg(Context ctx, boolean checked) {
        GradientDrawable gd = new GradientDrawable();
        gd.setOrientation(GradientDrawable.Orientation.TL_BR);
        if(checked) {
            gd.setColors(new int[]{thAccent(),thAccent2(),thAccent(),thAccent2(),thAccent()});
            gd.setStroke(Utils.dp(ctx,2),thAccent());
        } else {
            gd.setColor(Utils.pc("FF"+hexSwTrk()));
            gd.setStroke(Utils.dp(ctx,2),thBorder());
        }
        float rT=Utils.dp(ctx,10),rB=Utils.dp(ctx,18);
        try{gd.setCornerRadii(new float[]{rT,rT,rT,rT,rB,rB,rB,rB});}catch(Throwable e){gd.setCornerRadius(Utils.dp(ctx,14));}
        return gd;
    }

    public static void styleSwitch(android.widget.Switch sw, boolean checked, Context ctx) {
        sw.setTrackDrawable(createSwitchTrackBg(ctx, checked));
        sw.setThumbTintList(new android.content.res.ColorStateList(
            new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},
            new int[]{thAccent(),Utils.pc("FF"+hexSwThm())}));
    }

    public static View divider(Context ctx, int color, int h) {
        View v=new View(ctx);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(-1,Utils.dp(ctx,h)));
        v.setBackgroundColor(color);return v;
    }
    public static View spacer(Context ctx, int h) {
        View v=new View(ctx);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(-1,Utils.dp(ctx,h)));
        return v;
    }
}
