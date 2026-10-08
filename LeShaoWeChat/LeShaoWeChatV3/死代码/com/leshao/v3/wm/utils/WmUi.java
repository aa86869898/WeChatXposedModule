package com.leshao.v3.wm.utils;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.AppColors;

/**
 * 现代化 UI 工具 — 统一 wm 悬浮面板风格
 * 圆角白卡 + 图标按钮 + 分组标签，与模块页面风格一致
 */
public final class WmUi {

    private WmUi() {}

    public static final int C_BG       = AppColors.surface();               // M3 surface
    public static final int C_CARD     = AppColors.surfaceContainerLow();    // M3 filled card
    public static final int C_TEXT     = AppColors.onSurface();              // M3 onSurface
    public static final int C_TEXT2    = AppColors.onSurfaceVariant();       // M3 onSurfaceVariant
    public static final int C_ACCENT   = AppColors.primary();                // M3 primary
    public static final int C_ACCENT2  = AppColors.primaryDark();            // M3 primary 深阶
    public static final int C_GREEN    = AppColors.primary();
    public static final int C_RED      = AppColors.error();
    public static final int C_BORDER   = AppColors.outlineVariant();         // M3 outlineVariant

    public static LinearLayout makePanel(Activity act) {
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(act, AppColors.SHAPE_LG_DP));
        bg.setStroke(dp(act, 1), C_BORDER);
        panel.setBackground(bg);
        panel.setElevation(dp(act, 14));
        return panel;
    }

    public static LinearLayout makeHeader(Activity act, String title, String subtitle) {
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(C_ACCENT);
        hb.setCornerRadii(new float[]{dp(act,28), dp(act,28), dp(act,28), dp(act,28), 0, 0, 0, 0});
        head.setBackground(hb);
        head.setPadding(dp(act, 16), dp(act, 14), dp(act, 16), dp(act, 14));

        TextView tv = new TextView(act);
        tv.setText(title);
        tv.setTextSize(18);
        tv.setTextColor(AppColors.onPrimary());
        tv.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(tv);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView dv = new TextView(act);
            dv.setText(subtitle);
            dv.setTextSize(12);
            dv.setTextColor(0xB3FFFFFF);
            dv.setPadding(0, dp(act, 3), 0, 0);
            head.addView(dv);
        }
        return head;
    }

    public static TextView makeSection(Activity act, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(C_TEXT2);
        tv.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 4));
        return tv;
    }

    public static Button makeBtn(Activity act, String text, Runnable action) {
        Button btn = new Button(act);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setAllCaps(false);
        btn.setTextColor(C_TEXT);
        btn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        btn.setPadding(dp(act, 16), 0, dp(act, 16), 0);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(AppColors.surfaceContainerHigh());
        gd.setCornerRadius(dp(act, AppColors.SHAPE_MD_DP));
        btn.setBackground(gd);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(act, 40));
        lp.setMargins(dp(act, 8), 0, dp(act, 8), dp(act, 4));
        btn.setLayoutParams(lp);

        btn.setOnClickListener(v -> {
            try { if (action != null) action.run(); } catch (Throwable ignored) {}
        });
        return btn;
    }

    public static Button makePrimaryBtn(Activity act, String text, Runnable action) {
        Button btn = makeBtn(act, text, action);
        btn.setTextColor(AppColors.onPrimary());
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(C_ACCENT);
        gd.setCornerRadius(dp(act, AppColors.SHAPE_FULL_DP));
        btn.setBackground(gd);
        btn.setGravity(Gravity.CENTER);
        return btn;
    }

    public static View makeDivider(Activity act) {
        View v = new View(act);
        v.setBackgroundColor(C_BORDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(act, 1)));
        return v;
    }

    /**
     * 创建全屏透明遮罩，内含悬浮面板。
     * 点击面板外的空白区域自动关闭（通过传入的 onDismiss 回调）。
     * 面板自身仍可正常点击操作。
     */
    public static View makeOverlay(Activity act, View panel, Runnable onDismiss) {
        android.widget.FrameLayout overlay = new android.widget.FrameLayout(act);
        overlay.setBackgroundColor(AppColors.bgMask());
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setOnClickListener(v -> { if (onDismiss != null) onDismiss.run(); });
        overlay.addView(panel, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT));
        return overlay;
    }

    /** 在 WindowManager 中添加全屏遮罩（含面板） */
    public static WindowManager.LayoutParams overlayParams(Activity act) {
        WindowManager.LayoutParams wp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        return wp;
    }

    public static int dp(Activity act, int px) {
        return (int) (px * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ===== 可拖动浮动按钮 =====

    public static class DragFloat {
        public final Button btn;
        public final WindowManager.LayoutParams wp;
        private float mDownX, mDownY;
        private int mInitX, mInitY;
        private long mDownTime;
        private final View mView;
        private final WindowManager mWM;
        private final String mPrefKey;
        private final Runnable mOnTap;
        private boolean mAdded = false;

        public DragFloat(Activity act, WindowManager wm, String text,
                         int bgColor, String prefKey, Runnable onTap) {
            mWM = wm;
            mPrefKey = prefKey;
            mOnTap = onTap;
            View v = new Button(act);
            ((Button) v).setText(text);
            ((Button) v).setTextSize(16);
            ((Button) v).setTextColor(Color.WHITE);
            ((Button) v).setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(bgColor);
            v.setBackground(bg);
            mView = v;
            btn = (Button) v;

            int sz = dp(act, 48);
            wp = new WindowManager.LayoutParams(sz, sz,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            wp.gravity = Gravity.TOP | Gravity.LEFT;

            int sw = act.getResources().getDisplayMetrics().widthPixels;
            int sh = act.getResources().getDisplayMetrics().heightPixels;
            int defX = sw - sz - dp(act, 16);
            int defY = (int) (sh * 0.33f);
            int lx = WmPrefs.getInt(prefKey + "_x", -1);
            int ly = WmPrefs.getInt(prefKey + "_y", -1);
            if (lx < 0 || lx > sw || ly < 0 || ly > sh) {
                wp.x = defX;
                wp.y = defY;
            } else {
                wp.x = lx;
                wp.y = ly;
            }

            v.setOnTouchListener(this::onTouch);
        }

        private boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = e.getRawX();
                    mDownY = e.getRawY();
                    mInitX = wp.x;
                    mInitY = wp.y;
                    mDownTime = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    wp.x = (int) (mInitX + e.getRawX() - mDownX);
                    wp.y = (int) (mInitY + e.getRawY() - mDownY);
                    try { mWM.updateViewLayout(mView, wp); } catch (Exception ignored) {}
                    return true;
                case MotionEvent.ACTION_UP:
                    long dur = System.currentTimeMillis() - mDownTime;
                    int dx = Math.abs(wp.x - mInitX);
                    int dy = Math.abs(wp.y - mInitY);
                    LogWriter.log("DragFloat", "tap detect dur=" + dur + " dx=" + dx + " dy=" + dy + " onTap=" + (mOnTap != null));
                    if (dur < 300 && dx < 15 && dy < 15 && mOnTap != null) {
                        LogWriter.log("DragFloat", "tap FIRE");
                        try { mOnTap.run(); } catch (Throwable t) { LogWriter.log("DragFloat", "tap err: " + t.getMessage()); }
                    }
                    WmPrefs.setInt(mPrefKey + "_x", wp.x);
                    WmPrefs.setInt(mPrefKey + "_y", wp.y);
                    return true;
            }
            return false;
        }

        public void addToWindow() {
            if (mAdded) return;
            try {
                mWM.addView(mView, wp);
                mAdded = true;
            } catch (Throwable ignored) {}
        }

        public void removeFromWindow() {
            if (!mAdded) return;
            try { mWM.removeView(mView); } catch (Exception ignored) {}
            mAdded = false;
        }
    }
}
